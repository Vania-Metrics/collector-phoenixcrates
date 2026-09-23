package fr.samflix.vaniametrics.module.phoenixcrates;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.phoenixplugins.phoenixcrates.api.PhoenixCratesAPI;
import com.phoenixplugins.phoenixcrates.api.crate.CrateType;
import com.phoenixplugins.phoenixcrates.api.crate.events.CrateOpenEvent;
import com.phoenixplugins.phoenixcrates.api.crate.events.CratePlaceEvent;
import com.phoenixplugins.phoenixcrates.api.crate.events.CratePreOpenEvent;
import com.phoenixplugins.phoenixcrates.api.crate.events.CratePreviewOpenEvent;
import com.phoenixplugins.phoenixcrates.api.crate.events.CrateRewardPlayerEvent;
import com.phoenixplugins.phoenixcrates.api.crate.events.CrateRewardSelectionEvent;
import com.phoenixplugins.phoenixcrates.api.crate.events.CrateSelectiveConfirmEvent;
import com.phoenixplugins.phoenixcrates.api.events.items.ItemDeliveryEvent;
import com.phoenixplugins.phoenixcrates.api.key.Key;
import com.phoenixplugins.phoenixcrates.api.reward.BaseReward;
import com.phoenixplugins.phoenixcrates.api.reward.Reward;

import fr.samflix.vaniametrics.api.Collector;
import fr.samflix.vaniametrics.api.Config;
import fr.samflix.vaniametrics.api.Counter;
import fr.samflix.vaniametrics.api.Gauge;
import fr.samflix.vaniametrics.api.Joueur;
import fr.samflix.vaniametrics.api.MetricRegistry;
import fr.samflix.vaniametrics.api.Platform;
import fr.samflix.vaniametrics.api.PlayerSeries;

/**
 * Métriques PhoenixCrates — et surtout : LE HASARD TIENT-IL SES PROMESSES ?
 *
 * <p>C'est la seule chose qu'on ne peut pas voir en jeu. Un joueur qui ouvre cent coffres sent
 * bien que la récompense annoncée à 2 % ne tombe jamais, mais il ne peut rien démontrer ; un
 * administrateur non plus. Deux métriques suffisent à trancher :
 *
 * <pre>{@code
 *   # probabilité OBSERVÉE sur 30 jours, rapportée à celle qui est CONFIGURÉE
 *     sum by (crate,reward) (increase(mc_crate_rewards_total{alternative="false"}[30d]))
 *   / ignoring(reward) group_left
 *     sum by (crate)        (increase(mc_crate_rewards_total{alternative="false"}[30d]))
 *   / mc_crate_reward_expected_ratio
 *   # 1.0 = conforme, 0.5 = deux fois moins souvent que promis
 * }</pre>
 *
 * <p>IL N'EXISTE AUCUN MOYEN DE MESURER LE TIRAGE BRUT, et il a fallu l'apprendre sur le serveur.
 * Ce module déclarait au départ un compteur {@code reward_draws} alimenté par
 * {@code CrateRewardSelectionEvent} — qui n'est jamais monté. Son nom trompe : l'événement est
 * déclenché AVANT le tirage, avec la liste des candidats et une récompense à {@code null}, pour
 * laisser un plugin tiers imposer son choix ; c'est seulement après, si personne n'a répondu, que
 * {@code selectByWeight} tire. Il n'y a donc pas de compte rendu de tirage à écouter.
 *
 * <p>Le repli est {@code rewards_total} filtré sur {@code alternative="false"} : ce qui est remis
 * SANS avoir été substitué. Reste une imprécision assumée — les limites de gain et les
 * permissions retirent des candidats avant le tirage, sans que cela se voie. D'où
 * {@code crate_reward_candidates}, qui dit combien de lots étaient réellement en jeu.
 *
 * <p>DEUX RÉGIMES DANS UNE SEULE CLASSE. Les compteurs sont alimentés par ÉVÉNEMENTS et ne
 * coûtent rien au scrape : ils sont déjà à jour. Les jauges — configuration et état par joueur —
 * demandent de parcourir des listes, d'où {@link #enFond()} vrai. Les deux mécaniques cohabitent
 * ici parce qu'elles décrivent le même objet ; les séparer obligerait à partager les
 * identifiants de coffres entre deux classes.
 *
 * <p>TOUS LES GESTIONNAIRES SONT EN {@code MONITOR} ET EN O(1). Les événements de PhoenixCrates
 * sont déclenchés sur le fil principal, dans la pile d'appel de l'ouverture elle-même — vérifié
 * au bytecode. Un gestionnaire qui prendrait une milliseconde la prendrait sur le tick.
 */
public final class CratesCollector implements Collector, Listener {

	private final Platform plateforme;
	private final Config config;

	/** Le pont de réflexion vers l'état persisté, ou {@code null} s'il n'a pas pu se résoudre. */
	private DonneesJoueur donnees;

	private Counter tentatives;
	private Counter ouvertures;
	private Counter ouverturesJoueur;
	private Counter echecs;
	private Counter recompenses;
	private Gauge candidats;
	private Counter apercus;
	private Counter poses;
	private Counter confirmations;
	private Counter remises;

	private Gauge types;
	private Gauge cles;
	private Gauge recompensesConfigurees;
	private Gauge poids;
	private Gauge ratioAttendu;
	private Gauge clesRequises;
	private Gauge limiteGains;
	private Gauge attente;
	private Gauge cout;

	private Gauge clesJoueur;
	private Gauge ouverturesTotalJoueur;
	private Gauge attenteJoueur;
	private Gauge gainsJoueur;
	private PlayerSeries series;

	/**
	 * Les ouvertures commencées et pas encore confirmées, par identifiant de joueur.
	 *
	 * <p>LE MOTIF D'UN REFUS NE SE LIT NULLE PART. {@code Crate.openCrate} construit treize
	 * exceptions distinctes, toutes avec un message DÉJÀ TRADUIT et aucun code : classer dessus
	 * casserait au premier changement de langue du serveur.
	 *
	 * <p>D'où cet appariement. {@code CratePreOpenEvent} et {@code CrateOpenEvent} sont dans la
	 * même pile d'appel et le même tick ; ce qui reste ici après un tick est une ouverture qui
	 * n'a pas abouti, et l'instantané pris à l'entrée dit pourquoi.
	 */
	private final Map<String, Tentative> enAttente = new ConcurrentHashMap<>();

	/** Ce qu'on savait au moment où l'ouverture a été demandée. */
	private record Tentative(String coffre, String raisonProbable) {}

	public CratesCollector(Platform plateforme, Config config) {
		this.plateforme = plateforme;
		this.config = config;
	}

	@Override
	public String nom() {
		return "crate";
	}

	@Override
	public String origine() {
		return "PhoenixCrates";
	}

	@Override
	public boolean enFond() {
		return true;
	}

	@Override
	public boolean filPrincipal() {
		// Parcourt les joueurs connectés et l'API du plugin, qui n'est pas conçue pour être lue
		// d'un autre fil.
		return true;
	}

	@Override
	public long intervalleSecondes() {
		return config.entier("collector.crate.interval", 30);
	}

	// ------------------------------------------------------------------ déclaration

	@Override
	public void declarer(MetricRegistry r) {
		tentatives = r.counter("crate_open_attempts_total",
				"Ouvertures DEMANDÉES, abouties ou non. Vérifie l'invariant "
						+ "attempts = opens + failures ; un écart signale que le plugin a ouvert "
						+ "un coffre sans passer par le chemin normal.",
				"crate");
		ouvertures = r.counter("crate_opens_total",
				"Ouvertures abouties, tous joueurs confondus.", "crate");
		ouverturesJoueur = r.counter("crate_player_opens_total",
				"Ouvertures abouties, joueur par joueur et coffre par coffre. Compte DEPUIS LE "
						+ "DÉMARRAGE de l'exportateur ; pour le total de toujours, voir "
						+ "mc_crate_player_opens, que le plugin persiste.",
				"player", "uuid", "crate");
		echecs = r.counter("crate_open_failures_total",
				"Ouvertures refusées. « reason » est DÉDUIT de l'état du joueur au moment de la "
						+ "demande, car le plugin ne rend qu'un message traduit : cooldown, "
						+ "no_key, money, cancelled, other.",
				"crate", "reason");

		recompenses = r.counter("crate_rewards_total",
				"Récompenses effectivement REMISES. « alternative » distingue le lot de "
						+ "consolation du lot tiré.",
				"crate", "reward", "alternative");
		candidats = r.gauge("crate_reward_candidates",
				"Lots réellement en jeu au dernier tirage. Plus bas que "
						+ "crate_rewards_configured quand des limites de gain ou des permissions "
						+ "ont écarté des récompenses : c'est ce qui explique qu'un joueur ne "
						+ "puisse plus gagner une pièce qu'il a déjà obtenue.",
				"crate");
		confirmations = r.counter("crate_selective_confirms_total",
				"Choix confirmés en mode sélectif, où le joueur désigne son lot.",
				"crate", "reward");
		apercus = r.counter("crate_previews_total",
				"Aperçus ouverts sans ouvrir le coffre : de l'intérêt qui ne se convertit pas.",
				"crate");
		poses = r.counter("crate_placements_total",
				"Coffres physiques posés dans le monde.", "crate", "world");
		remises = r.counter("crate_deliveries_total",
				"REMISES d'objets, et non objets remis : huit clés données d'un coup comptent "
						+ "pour une. « source » vaut key_grant, crate_grant ou reward_grant. "
						+ "Seules les clés PHYSIQUES passent par là — donner une clé virtuelle "
						+ "ne déclenche aucun événement et ne se voit que comme un saut de "
						+ "mc_crate_player_keys.",
				"source");

		// --- configuration, relue à chaque passage : un rechargement la change sans redémarrage
		types = r.gauge("crate_types", "Types de coffres déclarés.", "enabled");
		cles = r.gauge("crate_keys", "Clés déclarées.", "virtual");
		recompensesConfigurees = r.gauge("crate_rewards_configured",
				"Récompenses déclarées sur un coffre. En version Lite le plafond est 5 : cette "
						+ "jauge dit quand on le touche.",
				"crate");
		poids = r.gauge("crate_reward_weight",
				"Poids brut d'une récompense dans le tirage. Ce N'EST PAS un pourcentage.",
				"crate", "reward");
		ratioAttendu = r.gauge("crate_reward_expected_ratio",
				"Probabilité ATTENDUE d'une récompense, entre 0 et 1, normalisée PAR NOUS "
						+ "(poids / somme des poids). Décrit la configuration du tirage, pas la "
						+ "loi effective : les récompenses garanties, les limites de gain et les "
						+ "permissions s'y ajoutent ensuite.",
				"crate", "reward");
		clesRequises = r.gauge("crate_reward_required_keys",
				"Clés exigées pour prétendre à une récompense. C'EST LA SEULE NOTION DE RARETÉ "
						+ "du plugin : il n'existe aucun getRarity() dans son API.",
				"crate", "reward");
		limiteGains = r.gauge("crate_reward_win_limit",
				"Nombre maximal de fois qu'une récompense peut être gagnée. NÉGATIF = sans "
						+ "limite : c'est -1 que rend le plugin, relevé sur ses coffres "
						+ "d'exemple, et non 0 comme on pourrait le supposer.",
				"crate", "reward");
		attente = r.gauge("crate_open_cooldown_seconds",
				"Délai imposé entre deux ouvertures d'un même coffre.", "crate");
		cout = r.gauge("crate_open_cost",
				"Prix d'une ouverture. « currency » est le moteur de coût configuré, ce qui "
						+ "laisse la porte ouverte aux monnaies multiples comme pour l'économie.",
				"crate", "currency");

		// --- par joueur, bornés par PlayerSeries
		clesJoueur = r.gauge("crate_player_keys",
				"Clés VIRTUELLES en stock chez un joueur connecté. Les clés physiques sont dans "
						+ "son inventaire et ne sont pas comptées ici.",
				"player", "uuid", "key");
		ouverturesTotalJoueur = r.gauge("crate_player_opens",
				"Ouvertures d'un joueur DEPUIS TOUJOURS, par type de coffre. Persisté par le "
						+ "plugin : c'est la seule métrique de ce module qui ne repart pas de "
						+ "zéro au redémarrage.",
				"player", "uuid", "crate");
		attenteJoueur = r.gauge("crate_player_cooldown_seconds",
				"Temps d'attente RESTANT avant qu'un joueur puisse rouvrir un coffre. Zéro s'il "
						+ "peut ouvrir tout de suite.",
				"player", "uuid", "crate");
		gainsJoueur = r.gauge("crate_player_reward_wins",
				"Combien de fois un joueur a gagné une récompense donnée, depuis toujours. "
						+ "SANS étiquette « crate » : le plugin indexe ces gains par identifiant "
						+ "de récompense SEUL, donc deux coffres qui nomment tous deux un lot "
						+ "« diamant » partagent le compteur. Ce n'est pas un choix, c'est ce que "
						+ "la donnée permet.",
				"player", "uuid", "reward");

		series = new PlayerSeries(r, config);
	}

	// ------------------------------------------------------------------ branchement

	/**
	 * Résout le pont de réflexion. Rend faux si l'état par joueur est hors de portée.
	 *
	 * <p>Rendre faux N'EST PAS une raison de renoncer au module, contrairement à ce que fait
	 * celui de l'économie : là-bas tout passait par la réflexion, ici les dix compteurs
	 * événementiels restent parfaitement valides. L'appelant enregistre donc le collecteur dans
	 * tous les cas.
	 */
	boolean brancherEtat(ClassLoader chargeur) {
		donnees = DonneesJoueur.resoudre(chargeur, plateforme);
		return donnees != null;
	}

	/** Voir le module betonquest : PlayerSeries borne ce qui est publié, ceci ce qui est retenu. */
	void oublier(String identifiant) {
		enAttente.remove(identifiant);
	}

	// ------------------------------------------------------------------ événements

	/**
	 * L'entrée : on note la demande, et ce qu'on savait de l'état du joueur.
	 *
	 * <p>{@code ignoreCancelled = false} à dessein : une ouverture annulée par un autre plugin
	 * est précisément l'un des refus qu'on veut compter.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
	public void onPreOpen(CratePreOpenEvent e) {
		CrateType type = e.getType();
		if (type == null || e.getPlayer() == null) {
			return;
		}
		String coffre = identifiant(type.getIdentifier());
		if (e.isCancelled()) {
			echecs.inc(coffre, "cancelled");
			return;
		}
		tentatives.inc(coffre);
		// UN SEUL EMPLACEMENT PAR JOUEUR, donc un second clic ÉCRASE le premier. Sans la ligne
		// ci-dessous la tentative écrasée disparaissait sans être comptée nulle part : relevé
		// sur le serveur, 31 tentatives pour 11 ouvertures et 3 échecs — dix-sept évaporées.
		// Ce qui est écrasé n'a par définition pas abouti : c'est un échec.
		Tentative precedente = enAttente.put(e.getPlayer().getUniqueId().toString(),
				new Tentative(coffre, raisonProbable(type, e.getPlayer())));
		if (precedente != null) {
			echecs.inc(precedente.coffre(), precedente.raisonProbable());
		}
	}

	/** L'aboutissement : l'ouverture a eu lieu, la demande n'est plus en attente. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onOpen(CrateOpenEvent e) {
		CrateType type = e.getType();
		if (type == null || e.getPlayer() == null) {
			return;
		}
		String coffre = identifiant(type.getIdentifier());
		ouvertures.inc(coffre);
		Joueur qui = Joueur.de(e.getPlayer().getUniqueId(), e.getPlayer().getName());
		ouverturesJoueur.inc(qui.etiquettes(coffre));
		enAttente.remove(e.getPlayer().getUniqueId().toString());
	}

	/**
	 * Les candidats au tirage — et NON le tirage lui-même.
	 *
	 * <p>Ne pas se fier au nom de l'événement : il est déclenché AVANT que le hasard ne tranche,
	 * pour offrir à un plugin tiers la possibilité d'imposer un lot. {@code getSelectedReward()}
	 * y vaut {@code null} en temps normal — c'est ce qui rendait muet le compteur de tirages
	 * qu'on avait d'abord écrit ici.
	 *
	 * <p>Ce qui EST exploitable, c'est {@code getCandidates()} : la liste des lots encore
	 * éligibles une fois retirés ceux qu'une limite de gain ou une permission a écartés. Un
	 * écart avec {@code crate_rewards_configured} dit qu'un joueur n'a plus accès à tout.
	 *
	 * <p>Ne se déclenche qu'en mode ALÉATOIRE : il vient du générateur pondéré.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onSelection(CrateRewardSelectionEvent e) {
		if (e.getCrate() == null || e.getCrate().getType() == null || e.getCandidates() == null) {
			return;
		}
		candidats.set(e.getCandidates().size(),
				identifiant(e.getCrate().getType().getIdentifier()));
	}

	/** La remise effective. MONITOR obligatoire : un autre plugin peut changer la récompense. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onReward(CrateRewardPlayerEvent e) {
		if (e.getReward() == null || e.getCrate() == null || e.getCrate().getType() == null) {
			return;
		}
		recompenses.inc(identifiant(e.getCrate().getType().getIdentifier()),
				identifiant(e.getReward().getIdentifier()),
				Boolean.toString(e.getReward().isAlternative()));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onSelectiveConfirm(CrateSelectiveConfirmEvent e) {
		if (e.getSelectedReward() == null || e.getCrate() == null
				|| e.getCrate().getType() == null) {
			return;
		}
		confirmations.inc(identifiant(e.getCrate().getType().getIdentifier()),
				identifiant(e.getSelectedReward().getIdentifier()));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPreview(CratePreviewOpenEvent e) {
		if (e.getCrateType() != null) {
			apercus.inc(identifiant(e.getCrateType().getIdentifier()));
		}
	}

	/**
	 * Pose d'un coffre physique.
	 *
	 * <p>Attention au nommage, qui diverge de tous les autres événements du plugin :
	 * {@code getCrate()} rend ici un {@code CrateType} et non un {@code CrateInstance}, et le
	 * joueur s'appelle {@code getWhoPlaced()}.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlace(CratePlaceEvent e) {
		if (e.getCrate() == null || e.getLocation() == null || e.getLocation().getWorld() == null) {
			return;
		}
		poses.inc(identifiant(e.getCrate().getIdentifier()), e.getLocation().getWorld().getName());
	}

	/**
	 * Remise d'objets — le seul point d'observation des clés distribuées.
	 *
	 * <p>Et il est PARTIEL, ce qu'il faut savoir avant de s'y fier : {@code KeyFacade.giveKey}
	 * écrit les clés virtuelles directement dans les données du joueur, sans déclencher le
	 * moindre événement. Seules les clés physiques passent ici.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onDelivery(ItemDeliveryEvent e) {
		if (e.getSource() != null) {
			remises.inc(e.getSource().name().toLowerCase(Locale.ROOT));
		}
	}

	// ------------------------------------------------------------------ relevé

	@Override
	public void relever(MetricRegistry r) {
		releverConfiguration();
		releverJoueurs();
		// Ce qui traîne encore ici a plus d'un relevé : l'ouverture n'a pas abouti.
		viderLesOrphelines();
	}

	private void releverConfiguration() {
		var gestionnaire = PhoenixCratesAPI.getCratesManager();
		int actifs = 0;
		int inactifs = 0;
		for (CrateType type : gestionnaire.getCrateTypes()) {
			if (type.isEnabled()) {
				actifs++;
			} else {
				inactifs++;
			}
			String coffre = identifiant(type.getIdentifier());
			attente.set(type.getOpenCooldownSeconds(), coffre);
			cout.set(type.getOpenMoneyCost(), coffre, monnaie(type));

			var lots = type.getRegisteredRewards();
			recompensesConfigurees.set(lots.size(), coffre);

			// La somme EXCLUT les alternatives : elles ne participent pas au tirage, elles s'y
			// substituent. Les inclure gonflerait le dénominateur et ferait paraître toutes les
			// probabilités plus faibles qu'elles ne sont.
			double somme = 0;
			for (Reward lot : lots) {
				if (!lot.isAlternative()) {
					somme += lot.getWeight();
				}
			}
			for (Reward lot : lots) {
				String nom = identifiant(lot.getIdentifier());
				poids.set(lot.getWeight(), coffre, nom);
				clesRequises.set(lot.getRequiredKeys(), coffre, nom);
				limiteGains.set(lot.getWinLimits(), coffre, nom);
				// getPercentage() rendrait le POIDS BRUT — son bytecode fait littéralement
				// « return getWeight() ». La normalisation doit être la nôtre.
				ratioAttendu.set(
						somme <= 0 || lot.isAlternative() ? 0 : lot.getWeight() / somme,
						coffre, nom);
			}
		}
		types.set(actifs, "true");
		types.set(inactifs, "false");

		int virtuelles = 0;
		int physiques = 0;
		for (Key k : PhoenixCratesAPI.getKeysManager().getRegisteredKeys()) {
			if (k.isVirtual()) {
				virtuelles++;
			} else {
				physiques++;
			}
		}
		cles.set(virtuelles, "true");
		cles.set(physiques, "false");
	}

	private void releverJoueurs() {
		if (donnees == null) {
			return;
		}
		var connectes = Bukkit.getOnlinePlayers().stream()
				.map(j -> Joueur.de(j.getUniqueId(), j.getName()))
				.toList();
		var retenus = series.retenir(connectes,
				clesJoueur, ouverturesTotalJoueur, attenteJoueur, gainsJoueur);
		if (retenus.isEmpty()) {
			return;
		}

		var gestionnaireCoffres = PhoenixCratesAPI.getCratesManager();
		var gestionnaireCles = PhoenixCratesAPI.getKeysManager();
		var gestionnaireJoueurs = PhoenixCratesAPI.getPlayersManager();
		var idCoffres = gestionnaireCoffres.getCratesIdentifier();
		var idCles = gestionnaireCles.getKeysIdentifier();
		boolean detailGains = config.actif("collector.crate.player_reward_wins", true);

		for (Joueur qui : retenus) {
			Player joueur = Bukkit.getPlayer(java.util.UUID.fromString(qui.uuid()));
			if (joueur == null) {
				continue;
			}
			// FALSE, ET JAMAIS TRUE. Le booléen à vrai déclenche une lecture en base si le
			// cache est froid ; getCachedDataNow fait pire, il lève une exception. Avec faux,
			// c'est un accès de table de hachage et rien d'autre.
			Object etat = gestionnaireJoueurs.getPlayerIfCached(joueur, false);
			if (etat == null) {
				continue;
			}
			for (String cle : idCles) {
				clesJoueur.set(donnees.clesVirtuelles(etat, cle), qui.etiquettes(identifiant(cle)));
			}
			for (String coffre : idCoffres) {
				String nom = identifiant(coffre);
				ouverturesTotalJoueur.set(donnees.ouvertures(etat, coffre), qui.etiquettes(nom));
				attenteJoueur.set(donnees.attenteSecondes(etat, coffre), qui.etiquettes(nom));
			}
			if (!detailGains) {
				continue;
			}
			for (String coffre : idCoffres) {
				CrateType type = gestionnaireCoffres.getTypeByIdentifier(coffre);
				if (type == null) {
					continue;
				}
				for (Reward lot : type.getRegisteredRewards()) {
					int n = donnees.gains(etat, lot.getIdentifier());
					if (n > 0) {
						gainsJoueur.set(n, qui.etiquettes(identifiant(lot.getIdentifier())));
					}
				}
			}
		}
	}

	/**
	 * Tout ce qui est encore en attente au relevé suivant est un refus.
	 *
	 * <p>LIMITE ASSUMÉE : {@code CratePreOpenEvent} n'est déclenché que par le chemin normal du
	 * plugin. Un autre plugin appelant {@code CrateInstance.openCrate()} directement produirait
	 * une ouverture sans tentative correspondante — les deux compteurs ne sont donc pas liés par
	 * construction, seulement en pratique.
	 */
	private void viderLesOrphelines() {
		if (enAttente.isEmpty()) {
			return;
		}
		for (var entree : Map.copyOf(enAttente).entrySet()) {
			Tentative t = enAttente.remove(entree.getKey());
			if (t != null) {
				echecs.inc(t.coffre(), t.raisonProbable());
			}
		}
	}

	// ------------------------------------------------------------------ utilitaires

	/**
	 * Pourquoi cette ouverture risque de ne pas aboutir, d'après l'état AVANT la tentative.
	 *
	 * <p>Une déduction, pas une lecture : le plugin ne rend qu'un message traduit. L'ordre suit
	 * celui de ses propres contrôles, pour que la raison la plus probable sorte en premier.
	 */
	private String raisonProbable(CrateType type, Player joueur) {
		if (donnees != null) {
			Object etat = PhoenixCratesAPI.getPlayersManager().getPlayerIfCached(joueur, false);
			if (etat != null) {
				if (donnees.attenteSecondes(etat, type.getIdentifier()) > 0) {
					return "cooldown";
				}
				if (type.isKeyRequired() && aucuneCleVirtuelle(etat, type)) {
					// « probable » au sens strict : le joueur peut porter une clé PHYSIQUE que
					// l'on ne compte pas ici, faute d'un accès public pour la voir.
					return "no_key";
				}
			}
		}
		return type.getOpenMoneyCost() > 0 ? "money" : "other";
	}

	private boolean aucuneCleVirtuelle(Object etat, CrateType type) {
		for (String cle : type.getLinkedKeysIds()) {
			if (donnees.clesVirtuelles(etat, cle) > 0) {
				return false;
			}
		}
		return true;
	}

	private static String monnaie(CrateType type) {
		var moteur = type.getCostEngineType();
		// getName() et non name() : CostEngineType est une interface, pas une énumération —
		// c'est ce qui laisse la porte ouverte aux moteurs de coût ajoutés par extension.
		return moteur == null ? "unknown" : identifiant(moteur.getName());
	}

	/**
	 * Une étiquette stable.
	 *
	 * <p>Toujours l'identifiant et jamais {@code getDisplayName()}, qui porte des codes couleur
	 * et change au gré de la mise en forme — une étiquette Prometheus ne doit pas bouger quand on
	 * repeint un menu.
	 */
	private static String identifiant(String brut) {
		return brut == null || brut.isBlank() ? "unknown" : brut.toLowerCase(Locale.ROOT);
	}
}
