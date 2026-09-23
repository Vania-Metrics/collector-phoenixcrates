package fr.samflix.vaniametrics.module.phoenixcrates;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import fr.samflix.vaniametrics.api.VaniaMetrics;
import fr.samflix.vaniametrics.api.VaniaMetricsProvider;

/**
 * Métriques PhoenixCrates.
 *
 * <p>DEUX NOMS POUR UN MÊME PLUGIN, et c'est ce qui interdit un {@code depend}. La version
 * gratuite s'appelle {@code PhoenixCratesLite}, la payante {@code PhoenixCrates} — le jar est le
 * même, seul le nom déclaré change, et c'est littéralement ce nom qui décide de l'édition côté
 * plugin. Un {@code depend} sur l'un ferait refuser ce module par Bukkit là où l'autre est
 * installé.
 *
 * <p>{@code softdepend} ne garantit que l'ORDRE de chargement, jamais la présence. C'est donc à
 * cette classe de vérifier, et de se taire proprement sinon.
 */
public final class CratesPaper extends JavaPlugin implements Listener {

	private static final String[] NOMS = {"PhoenixCratesLite", "PhoenixCrates"};

	private CratesCollector collecteur;

	@Override
	public void onEnable() {
		Plugin coffres = trouver();
		if (coffres == null) {
			getLogger().warning("PhoenixCrates n'est pas installé — aucun coffre ne sera mesuré.");
			return;
		}

		VaniaMetrics metriques = VaniaMetricsProvider.get();
		collecteur = new CratesCollector(metriques.plateforme(), metriques.config());

		// ON ENREGISTRE MÊME SI L'ÉTAT PAR JOUEUR ÉCHOUE, contrairement au module d'économie.
		// Là-bas tout passait par la réflexion, donc un échec ne laissait rien à mesurer. Ici
		// dix compteurs événementiels restent parfaitement valides : les jeter parce que le
		// stock de clés est hors de portée serait perdre l'essentiel pour l'accessoire.
		collecteur.brancherEtat(coffres.getClass().getClassLoader());

		metriques.enregistrer(collecteur);
		Bukkit.getPluginManager().registerEvents(collecteur, this);
		Bukkit.getPluginManager().registerEvents(this, this);
	}

	@Override
	public void onDisable() {
		if (collecteur != null) {
			VaniaMetricsProvider.chercher().ifPresent(m -> m.retirer(collecteur));
		}
	}

	/**
	 * L'état par joueur meurt avec la session.
	 *
	 * <p>Sans ça, la carte des ouvertures en cours grossirait d'une entrée par joueur ayant
	 * quitté pendant une animation, et pour toujours.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent e) {
		if (collecteur != null) {
			collecteur.oublier(e.getPlayer().getUniqueId().toString());
		}
	}

	private static Plugin trouver() {
		for (String nom : NOMS) {
			Plugin p = Bukkit.getPluginManager().getPlugin(nom);
			if (p != null && p.isEnabled()) {
				return p;
			}
		}
		return null;
	}
}
