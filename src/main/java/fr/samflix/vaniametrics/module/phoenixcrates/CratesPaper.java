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
 * PhoenixCrates metrics.
 *
 * <p>Two names for the same plugin, which rules out a {@code depend}. The free edition is named
 * {@code PhoenixCratesLite}, the paid one {@code PhoenixCrates} — the jar is the same, only the
 * declared name changes, and that name is literally what decides the edition on the plugin's
 * side. A {@code depend} on either would get this module rejected by Bukkit wherever the other
 * is installed.
 *
 * <p>{@code softdepend} only guarantees load ORDER, never presence. So it's up to this class to
 * check, and to stay quiet otherwise.
 */
public final class CratesPaper extends JavaPlugin implements Listener {

	private static final String[] NAMES = {"PhoenixCratesLite", "PhoenixCrates"};

	private CratesCollector collector;

	@Override
	public void onEnable() {
		Plugin crates = find();
		if (crates == null) {
			getLogger().warning("PhoenixCrates is not installed — no crate will be measured.");
			return;
		}

		VaniaMetrics metrics = VaniaMetricsProvider.get();
		collector = new CratesCollector(metrics.platform(), metrics.config());

		// WE REGISTER EVEN IF PER-PLAYER STATE FAILS, unlike the economy module. There
		// everything went through reflection, so a failure left nothing to measure. Here ten
		// event-driven counters remain perfectly valid: dropping them because the key stock is
		// out of reach would lose the essential for the sake of the accessory.
		collector.bindState(crates.getClass().getClassLoader());

		metrics.register(collector);
		Bukkit.getPluginManager().registerEvents(collector, this);
		Bukkit.getPluginManager().registerEvents(this, this);
	}

	@Override
	public void onDisable() {
		if (collector != null) {
			VaniaMetricsProvider.find().ifPresent(m -> m.unregister(collector));
		}
	}

	/**
	 * Per-player state dies with the session.
	 *
	 * <p>Without this, the map of in-progress opens would grow by one entry per player who quit
	 * mid-animation, forever.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent e) {
		if (collector != null) {
			collector.forget(e.getPlayer().getUniqueId().toString());
		}
	}

	private static Plugin find() {
		for (String name : NAMES) {
			Plugin p = Bukkit.getPluginManager().getPlugin(name);
			if (p != null && p.isEnabled()) {
				return p;
			}
		}
		return null;
	}
}
