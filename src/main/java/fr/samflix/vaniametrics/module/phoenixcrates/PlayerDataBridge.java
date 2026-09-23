package fr.samflix.vaniametrics.module.phoenixcrates;

import java.lang.reflect.Method;

import fr.samflix.vaniametrics.api.Platform;

/**
 * The bridge to a player's persisted state, and the only place in this module that uses
 * reflection.
 *
 * <p>The reason fits in one line of {@code javap}:
 *
 * <pre>{@code public interface PlayerData { }}</pre>
 *
 * <p>The public interface {@code api.player.PlayerData} is EMPTY — zero methods. Everything we
 * need (virtual key stock, cumulative opens, cooldown) lives on the implementation class
 * {@code managers.players.PlayerData}, which no contract protects.
 *
 * <p>Unlike the economy module, reflection here is not a compilation workaround:
 * PhoenixCrates' classes are class file version 52 (Java 8) and {@code javac --release 21}
 * reads them without trouble. This is a deliberate choice not to bind to the internal API —
 * everything else in this module compiles against the public API, checked by the compiler.
 *
 * <p>The cost is the same as everywhere else: a signature change upstream will no longer be
 * caught at compile time. Hence resolving ONCE at startup, which fails loudly and cleanly
 * disables the affected metrics, rather than failing once per player per scrape.
 *
 * <p>The event-driven counters don't depend on this class. If it fails to resolve, the module
 * keeps measuring opens and rewards; only the per-player state gauges disappear.
 */
final class PlayerDataBridge {

	/** The implementation class, outside the API — which is exactly why we use reflection. */
	private static final String CLASS_NAME = "com.phoenixplugins.phoenixcrates.managers.players.PlayerData";

	private final Method readVirtualKeys;
	private final Method readOpens;
	private final Method readCooldown;
	private final Method readWins;

	private PlayerDataBridge(Method virtualKeys, Method opens, Method cooldown, Method wins) {
		this.readVirtualKeys = virtualKeys;
		this.readOpens = opens;
		this.readCooldown = cooldown;
		this.readWins = wins;
	}

	/**
	 * Resolves the four accessors, or returns {@code null} having logged why.
	 *
	 * @param loader the crates plugin's class loader, not ours: on Paper each plugin has its
	 *     own, and {@code Class.forName} without an explicit loader would look in the wrong one.
	 */
	static PlayerDataBridge resolve(ClassLoader loader, Platform platform) {
		try {
			Class<?> c = Class.forName(CLASS_NAME, false, loader);
			return new PlayerDataBridge(
					c.getMethod("getVirtualKeys", String.class),
					c.getMethod("getOpenedCratesAmount", String.class),
					c.getMethod("getCrateCooldown", String.class),
					c.getMethod("getRewardWinAmount", String.class));
		} catch (ReflectiveOperationException | RuntimeException e) {
			platform.warn("PhoenixCrates: internal player state layout has changed, "
					+ "key stocks and per-player totals will not be measured. "
					+ "Opens and rewards remain measured — " + e);
			return null;
		}
	}

	int virtualKeys(Object data, String key) {
		return asInt(readVirtualKeys, data, key);
	}

	int opens(Object data, String crate) {
		return asInt(readOpens, data, crate);
	}

	int wins(Object data, String reward) {
		return asInt(readWins, data, reward);
	}

	/**
	 * The REMAINING cooldown, in seconds, or zero.
	 *
	 * <p>The plugin stores an end timestamp in milliseconds, not a duration: publishing the raw
	 * value would give a thirteen-digit number that doesn't read as anything. The subtraction is
	 * clamped to zero — an expired cooldown must not show up negative on a graph.
	 */
	double cooldownSeconds(Object data, String crate) {
		try {
			Object v = readCooldown.invoke(data, crate);
			if (!(v instanceof Number n)) {
				return 0;
			}
			long remaining = n.longValue() - System.currentTimeMillis();
			return remaining <= 0 ? 0 : remaining / 1000.0;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return 0;
		}
	}

	/**
	 * Zero rather than a propagated exception.
	 *
	 * <p>These calls sit in a loop over online players: a propagated error would lose the scrape
	 * for ALL the following ones. Zero is the right fallback here — it's also what the plugin
	 * itself returns for an identifier it doesn't know.
	 */
	private static int asInt(Method m, Object target, String argument) {
		try {
			Object v = m.invoke(target, argument);
			return v instanceof Number n ? n.intValue() : 0;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return 0;
		}
	}
}
