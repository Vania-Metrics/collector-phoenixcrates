package fr.samflix.vaniametrics.module.phoenixcrates;

import java.lang.reflect.Method;

import fr.samflix.vaniametrics.api.Platform;

/**
 * LE PONT VERS L'ÉTAT PERSISTÉ D'UN JOUEUR, et le seul endroit du module qui réfléchit.
 *
 * <p>La raison tient en une ligne de {@code javap} :
 *
 * <pre>{@code public interface PlayerData { }}</pre>
 *
 * <p>L'interface publique {@code api.player.PlayerData} est VIDE — zéro méthode. Tout ce qui nous
 * intéresse (stock de clés virtuelles, ouvertures cumulées, temps d'attente) vit sur la classe
 * d'implémentation {@code managers.players.PlayerData}, qu'aucun contrat ne protège.
 *
 * <p>LA RÉFLEXION N'EST DONC PAS ICI UN PIS-ALLER DE COMPILATION, contrairement au module
 * d'économie : les classes de PhoenixCrates sont en version 52 (Java 8) et {@code javac --release
 * 21} les lit sans difficulté. C'est un choix délibéré de ne pas se lier à l'interne — tout le
 * reste du module compile contre l'API publique, avec vérification par le compilateur.
 *
 * <p>Le prix est le même que partout ailleurs : un changement de signature chez l'auteur ne se
 * verra plus à la compilation. D'où la résolution en UNE fois au démarrage, qui échoue bruyamment
 * et désactive proprement les métriques concernées, plutôt que d'échouer une fois par joueur et
 * par relevé.
 *
 * <p>Les compteurs par ÉVÉNEMENT ne dépendent pas de cette classe. Si elle ne se résout pas, le
 * module continue de mesurer les ouvertures et les récompenses ; seules les jauges d'état par
 * joueur disparaissent.
 */
final class DonneesJoueur {

	/** La classe d'implémentation, hors API — c'est bien pour cela qu'on réfléchit. */
	private static final String CLASSE = "com.phoenixplugins.phoenixcrates.managers.players.PlayerData";

	private final Method lireClesVirtuelles;
	private final Method lireOuvertures;
	private final Method lireAttente;
	private final Method lireGains;

	private DonneesJoueur(Method cles, Method ouvertures, Method attente, Method gains) {
		this.lireClesVirtuelles = cles;
		this.lireOuvertures = ouvertures;
		this.lireAttente = attente;
		this.lireGains = gains;
	}

	/**
	 * Résout les quatre accesseurs, ou rend {@code null} en l'ayant dit.
	 *
	 * @param chargeur le chargeur de classes du plugin de coffres, pas le nôtre : sur Paper chaque
	 *     plugin a le sien, et {@code Class.forName} sans chargeur explicite chercherait dans le
	 *     mauvais.
	 */
	static DonneesJoueur resoudre(ClassLoader chargeur, Platform plateforme) {
		try {
			Class<?> c = Class.forName(CLASSE, false, chargeur);
			return new DonneesJoueur(
					c.getMethod("getVirtualKeys", String.class),
					c.getMethod("getOpenedCratesAmount", String.class),
					c.getMethod("getCrateCooldown", String.class),
					c.getMethod("getRewardWinAmount", String.class));
		} catch (ReflectiveOperationException | RuntimeException e) {
			plateforme.avertir("PhoenixCrates : l'état interne des joueurs a changé de forme, "
					+ "les stocks de clés et les totaux par joueur ne seront pas mesurés. "
					+ "Les ouvertures et les récompenses le restent — " + e);
			return null;
		}
	}

	int clesVirtuelles(Object donnees, String cle) {
		return entier(lireClesVirtuelles, donnees, cle);
	}

	int ouvertures(Object donnees, String coffre) {
		return entier(lireOuvertures, donnees, coffre);
	}

	int gains(Object donnees, String recompense) {
		return entier(lireGains, donnees, recompense);
	}

	/**
	 * Le temps d'attente RESTANT, en secondes, ou zéro.
	 *
	 * <p>Le plugin range un horodatage de fin en millisecondes, pas une durée : publier la valeur
	 * brute donnerait un nombre à treize chiffres qui ne se lit pas. La soustraction est bornée à
	 * zéro — un temps d'attente échu ne doit pas ressortir en négatif sur un graphique.
	 */
	double attenteSecondes(Object donnees, String coffre) {
		try {
			Object v = lireAttente.invoke(donnees, coffre);
			if (!(v instanceof Number n)) {
				return 0;
			}
			long reste = n.longValue() - System.currentTimeMillis();
			return reste <= 0 ? 0 : reste / 1000.0;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return 0;
		}
	}

	/**
	 * Zéro plutôt qu'une exception qui remonte.
	 *
	 * <p>Ces appels sont dans une boucle sur les joueurs connectés : une erreur qui se propagerait
	 * ferait perdre le relevé de TOUS les suivants. Zéro est ici la bonne valeur de repli — c'est
	 * aussi ce que rend le plugin pour un identifiant qu'il ne connaît pas.
	 */
	private static int entier(Method m, Object cible, String argument) {
		try {
			Object v = m.invoke(cible, argument);
			return v instanceof Number n ? n.intValue() : 0;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return 0;
		}
	}
}
