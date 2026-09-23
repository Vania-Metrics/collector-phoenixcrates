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
import fr.samflix.vaniametrics.api.MetricRegistry;
import fr.samflix.vaniametrics.api.Platform;
import fr.samflix.vaniametrics.api.PlayerRef;
import fr.samflix.vaniametrics.api.PlayerSeries;

/**
 * PhoenixCrates metrics — and above all: does the randomness keep its promises?
 *
 * <p>That's the one thing you can't see in-game. A player who opens a hundred crates can sense
 * that the reward advertised at 2% never comes up, but can't prove it; neither can an admin.
 * Two metrics settle it:
 *
 * <pre>{@code
 *   # OBSERVED probability over 30 days, against the CONFIGURED one
 *     sum by (crate,reward) (increase(mc_crate_rewards_total{alternative="false"}[30d]))
 *   / ignoring(reward) group_left
 *     sum by (crate)        (increase(mc_crate_rewards_total{alternative="false"}[30d]))
 *   / mc_crate_reward_expected_ratio
 *   # 1.0 = matches, 0.5 = half as frequent as promised
 * }</pre>
 *
 * <p>There is no way to measure the raw draw, which had to be learned the hard way on the
 * server. This module originally declared a {@code reward_draws} counter fed by
 * {@code CrateRewardSelectionEvent} — which never fires as its name suggests. The event fires
 * BEFORE the draw, with the list of candidates and a {@code null} reward, to let a third-party
 * plugin impose its own choice; only afterward, if nobody responded, does
 * {@code selectByWeight} actually draw. So there's no draw outcome to listen for.
 *
 * <p>The fallback is {@code rewards_total} filtered on {@code alternative="false"}: what gets
 * handed out WITHOUT having been substituted. An acknowledged imprecision remains — win limits
 * and permissions remove candidates before the draw, invisibly. Hence
 * {@code crate_reward_candidates}, which says how many rewards were actually in play.
 *
 * <p>Two regimes live in one class. The counters are fed by events and cost nothing at scrape
 * time: they're already current. The gauges — configuration and per-player state — require
 * walking lists, hence {@link #isBackground()} being true. Both mechanics live here together
 * because they describe the same object; splitting them would force sharing crate identifiers
 * between two classes.
 *
 * <p>All handlers run at {@code MONITOR} and in O(1). PhoenixCrates' events fire on the main
 * thread, in the call stack of the open itself — verified at the bytecode level. A handler that
 * took a millisecond would take it out of the tick.
 */
public final class CratesCollector implements Collector, Listener {

	private final Platform platform;
	private final Config config;

	/** The reflection bridge to persisted state, or {@code null} if it could not be resolved. */
	private PlayerDataBridge playerData;

	private Counter attempts;
	private Counter opens;
	private Counter playerOpens;
	private Counter failures;
	private Counter rewards;
	private Gauge candidates;
	private Counter previews;
	private Counter placements;
	private Counter confirmations;
	private Counter deliveries;

	private Gauge types;
	private Gauge keys;
	private Gauge rewardsConfigured;
	private Gauge weight;
	private Gauge expectedRatio;
	private Gauge requiredKeys;
	private Gauge winLimit;
	private Gauge cooldown;
	private Gauge cost;

	private Gauge playerKeys;
	private Gauge playerTotalOpens;
	private Gauge playerCooldown;
	private Gauge playerRewardWins;
	private PlayerSeries series;

	/**
	 * Opens started and not yet confirmed, by player identifier.
	 *
	 * <p>The reason for a refusal can't be read anywhere. {@code Crate.openCrate} throws thirteen
	 * distinct exceptions, all with an ALREADY TRANSLATED message and no code: classifying on
	 * that would break on the server's first language change.
	 *
	 * <p>Hence this pairing. {@code CratePreOpenEvent} and {@code CrateOpenEvent} share the same
	 * call stack and tick; whatever is still here after one tick is an open that didn't
	 * complete, and the snapshot taken on entry says why.
	 */
	private final Map<String, Attempt> pending = new ConcurrentHashMap<>();

	/** What we knew at the moment the open was requested. */
	private record Attempt(String crate, String likelyReason) {}

	public CratesCollector(Platform platform, Config config) {
		this.platform = platform;
		this.config = config;
	}

	@Override
	public String name() {
		return "crate";
	}

	@Override
	public String source() {
		return "PhoenixCrates";
	}

	@Override
	public boolean isBackground() {
		return true;
	}

	@Override
	public boolean needsMainThread() {
		// Walks online players and the plugin's API, which isn't designed to be read from
		// another thread.
		return true;
	}

	@Override
	public long intervalSeconds() {
		return config.getInt("collector.crate.interval", 30);
	}

	// ------------------------------------------------------------------ declaration

	@Override
	public void declare(MetricRegistry r) {
		attempts = r.counter("crate_open_attempts_total",
				"Opens REQUESTED, whether they completed or not. Checks the invariant "
						+ "attempts = opens + failures; a gap means the plugin opened a crate "
						+ "without going through the normal path.",
				"crate");
		opens = r.counter("crate_opens_total",
				"Opens completed, across all players.", "crate");
		playerOpens = r.counter("crate_player_opens_total",
				"Opens completed, per player and per crate. Counts SINCE THE EXPORTER "
						+ "STARTED; for the all-time total, see mc_crate_player_opens, which "
						+ "the plugin persists.",
				"player", "uuid", "crate");
		failures = r.counter("crate_open_failures_total",
				"Opens refused. \"reason\" is INFERRED from the player's state at the time "
						+ "of the request, since the plugin only returns a translated message: "
						+ "cooldown, no_key, money, cancelled, other.",
				"crate", "reason");

		rewards = r.counter("crate_rewards_total",
				"Rewards actually HANDED OUT. \"alternative\" distinguishes the consolation "
						+ "reward from the drawn one.",
				"crate", "reward", "alternative");
		candidates = r.gauge("crate_reward_candidates",
				"Rewards actually in play at the last draw. Lower than "
						+ "crate_rewards_configured when win limits or permissions removed "
						+ "rewards: this is what explains why a player can no longer win a "
						+ "reward they already got.",
				"crate");
		confirmations = r.counter("crate_selective_confirms_total",
				"Choices confirmed in selective mode, where the player picks their reward.",
				"crate", "reward");
		previews = r.counter("crate_previews_total",
				"Previews opened without opening the crate: interest that doesn't convert.",
				"crate");
		placements = r.counter("crate_placements_total",
				"Physical crates placed in the world.", "crate", "world");
		deliveries = r.counter("crate_deliveries_total",
				"Item DELIVERIES, not items delivered: eight keys given at once count as "
						+ "one. \"source\" is key_grant, crate_grant or reward_grant. Only "
						+ "PHYSICAL keys go through here — granting a virtual key fires no "
						+ "event and only shows up as a jump in mc_crate_player_keys.",
				"source");

		// --- configuration, re-read on every pass: a reload changes it without a restart
		types = r.gauge("crate_types", "Crate types declared.", "enabled");
		keys = r.gauge("crate_keys", "Keys declared.", "virtual");
		rewardsConfigured = r.gauge("crate_rewards_configured",
				"Rewards declared on a crate. The Lite edition caps this at 5: this gauge "
						+ "says when that cap is hit.",
				"crate");
		weight = r.gauge("crate_reward_weight",
				"Raw weight of a reward in the draw. This is NOT a percentage.",
				"crate", "reward");
		expectedRatio = r.gauge("crate_reward_expected_ratio",
				"EXPECTED probability of a reward, between 0 and 1, normalized BY US "
						+ "(weight / sum of weights). Describes the draw's configuration, not "
						+ "the effective odds: guaranteed rewards, win limits and permissions "
						+ "are applied on top of it.",
				"crate", "reward");
		requiredKeys = r.gauge("crate_reward_required_keys",
				"Keys required to be eligible for a reward. This is the ONLY notion of "
						+ "rarity the plugin has: there is no getRarity() in its API.",
				"crate", "reward");
		winLimit = r.gauge("crate_reward_win_limit",
				"Maximum number of times a reward can be won. NEGATIVE means unlimited: "
						+ "-1 is what the plugin returns, observed on its sample crates, not 0 "
						+ "as one might assume.",
				"crate", "reward");
		cooldown = r.gauge("crate_open_cooldown_seconds",
				"Delay imposed between two opens of the same crate.", "crate");
		cost = r.gauge("crate_open_cost",
				"Price of an open. \"currency\" is the configured cost engine, which leaves "
						+ "the door open to multiple currencies, as with the economy module.",
				"crate", "currency");

		// --- per player, bounded by PlayerSeries
		playerKeys = r.gauge("crate_player_keys",
				"VIRTUAL keys in stock for a connected player. Physical keys are in their "
						+ "inventory and are not counted here.",
				"player", "uuid", "key");
		playerTotalOpens = r.gauge("crate_player_opens",
				"A player's opens EVER, by crate type. Persisted by the plugin: the only "
						+ "metric in this module that doesn't reset to zero on restart.",
				"player", "uuid", "crate");
		playerCooldown = r.gauge("crate_player_cooldown_seconds",
				"Time REMAINING before a player can reopen a crate. Zero if they can open "
						+ "right away.",
				"player", "uuid", "crate");
		playerRewardWins = r.gauge("crate_player_reward_wins",
				"How many times a player has won a given reward, ever. WITHOUT a \"crate\" "
						+ "label: the plugin indexes these wins by reward identifier ALONE, so "
						+ "two crates that both name a reward \"diamond\" share the counter. "
						+ "That's not a design choice, it's what the data allows.",
				"player", "uuid", "reward");

		series = new PlayerSeries(r, config);
	}

	// ------------------------------------------------------------------ wiring

	/**
	 * Resolves the reflection bridge. Returns false if per-player state is out of reach.
	 *
	 * <p>Returning false is NOT a reason to give up on the module, unlike the economy one:
	 * there, everything went through reflection, so a failure left nothing to measure. Here the
	 * ten event-driven counters remain perfectly valid. The caller registers the collector
	 * either way.
	 */
	boolean bindState(ClassLoader loader) {
		playerData = PlayerDataBridge.resolve(loader, platform);
		return playerData != null;
	}

	/** See the betonquest module: PlayerSeries bounds what's published, this bounds what's kept. */
	void forget(String identifier) {
		pending.remove(identifier);
	}

	// ------------------------------------------------------------------ events

	/**
	 * The entry point: record the request, and what we knew of the player's state.
	 *
	 * <p>{@code ignoreCancelled = false} on purpose: an open cancelled by another plugin is
	 * exactly one of the refusals we want to count.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
	public void onPreOpen(CratePreOpenEvent e) {
		CrateType type = e.getType();
		if (type == null || e.getPlayer() == null) {
			return;
		}
		String crate = identifier(type.getIdentifier());
		if (e.isCancelled()) {
			failures.inc(crate, "cancelled");
			return;
		}
		attempts.inc(crate);
		// ONE SLOT PER PLAYER, so a second click OVERWRITES the first. Without the line below,
		// the overwritten attempt disappeared uncounted: observed on the server, 31 attempts
		// for 11 opens and 3 failures — seventeen vanished. What gets overwritten is, by
		// definition, an attempt that didn't complete: it's a failure.
		Attempt previous = pending.put(e.getPlayer().getUniqueId().toString(),
				new Attempt(crate, likelyReason(type, e.getPlayer())));
		if (previous != null) {
			failures.inc(previous.crate(), previous.likelyReason());
		}
	}

	/** Completion: the open happened, the request is no longer pending. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onOpen(CrateOpenEvent e) {
		CrateType type = e.getType();
		if (type == null || e.getPlayer() == null) {
			return;
		}
		String crate = identifier(type.getIdentifier());
		opens.inc(crate);
		PlayerRef ref = PlayerRef.of(e.getPlayer().getUniqueId(), e.getPlayer().getName());
		playerOpens.inc(ref.labels(crate));
		pending.remove(e.getPlayer().getUniqueId().toString());
	}

	/**
	 * The draw's candidates — NOT the draw itself.
	 *
	 * <p>Don't trust the event's name: it fires BEFORE randomness decides, to give a
	 * third-party plugin the chance to impose a reward. {@code getSelectedReward()} is
	 * {@code null} under normal conditions — which is what made the draw counter originally
	 * written here silent.
	 *
	 * <p>What IS usable is {@code getCandidates()}: the list of rewards still eligible once a
	 * win limit or permission has removed some. A gap against {@code crate_rewards_configured}
	 * says a player no longer has access to everything.
	 *
	 * <p>Only fires in RANDOM mode: it comes from the weighted generator.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onSelection(CrateRewardSelectionEvent e) {
		if (e.getCrate() == null || e.getCrate().getType() == null || e.getCandidates() == null) {
			return;
		}
		candidates.set(e.getCandidates().size(),
				identifier(e.getCrate().getType().getIdentifier()));
	}

	/** The actual handout. MONITOR is required: another plugin can change the reward. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onReward(CrateRewardPlayerEvent e) {
		if (e.getReward() == null || e.getCrate() == null || e.getCrate().getType() == null) {
			return;
		}
		rewards.inc(identifier(e.getCrate().getType().getIdentifier()),
				identifier(e.getReward().getIdentifier()),
				Boolean.toString(e.getReward().isAlternative()));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onSelectiveConfirm(CrateSelectiveConfirmEvent e) {
		if (e.getSelectedReward() == null || e.getCrate() == null
				|| e.getCrate().getType() == null) {
			return;
		}
		confirmations.inc(identifier(e.getCrate().getType().getIdentifier()),
				identifier(e.getSelectedReward().getIdentifier()));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPreview(CratePreviewOpenEvent e) {
		if (e.getCrateType() != null) {
			previews.inc(identifier(e.getCrateType().getIdentifier()));
		}
	}

	/**
	 * Placement of a physical crate.
	 *
	 * <p>Watch the naming, which diverges from every other event in this plugin:
	 * {@code getCrate()} here returns a {@code CrateType}, not a {@code CrateInstance}, and the
	 * player is {@code getWhoPlaced()}.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlace(CratePlaceEvent e) {
		if (e.getCrate() == null || e.getLocation() == null || e.getLocation().getWorld() == null) {
			return;
		}
		placements.inc(identifier(e.getCrate().getIdentifier()), e.getLocation().getWorld().getName());
	}

	/**
	 * Item delivery — the only observation point for distributed keys.
	 *
	 * <p>And it's PARTIAL, which matters before relying on it: {@code KeyFacade.giveKey} writes
	 * virtual keys directly into the player's data, without firing any event. Only physical
	 * keys go through here.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onDelivery(ItemDeliveryEvent e) {
		if (e.getSource() != null) {
			deliveries.inc(e.getSource().name().toLowerCase(Locale.ROOT));
		}
	}

	// ------------------------------------------------------------------ collection

	@Override
	public void collect(MetricRegistry r) {
		collectConfiguration();
		collectPlayers();
		// Anything still pending here has outlived more than one scrape: the open didn't complete.
		flushOrphaned();
	}

	private void collectConfiguration() {
		var manager = PhoenixCratesAPI.getCratesManager();
		int enabled = 0;
		int disabled = 0;
		for (CrateType type : manager.getCrateTypes()) {
			if (type.isEnabled()) {
				enabled++;
			} else {
				disabled++;
			}
			String crate = identifier(type.getIdentifier());
			cooldown.set(type.getOpenCooldownSeconds(), crate);
			cost.set(type.getOpenMoneyCost(), crate, currency(type));

			var rewardList = type.getRegisteredRewards();
			rewardsConfigured.set(rewardList.size(), crate);

			// The sum EXCLUDES alternatives: they don't take part in the draw, they substitute
			// for it. Including them would inflate the denominator and make every probability
			// look lower than it is.
			double sum = 0;
			for (Reward reward : rewardList) {
				if (!reward.isAlternative()) {
					sum += reward.getWeight();
				}
			}
			for (Reward reward : rewardList) {
				String rewardId = identifier(reward.getIdentifier());
				weight.set(reward.getWeight(), crate, rewardId);
				requiredKeys.set(reward.getRequiredKeys(), crate, rewardId);
				winLimit.set(reward.getWinLimits(), crate, rewardId);
				// getPercentage() would return the RAW WEIGHT — its bytecode literally does
				// "return getWeight()". The normalization has to be ours.
				expectedRatio.set(
						sum <= 0 || reward.isAlternative() ? 0 : reward.getWeight() / sum,
						crate, rewardId);
			}
		}
		types.set(enabled, "true");
		types.set(disabled, "false");

		int virtual = 0;
		int physical = 0;
		for (Key k : PhoenixCratesAPI.getKeysManager().getRegisteredKeys()) {
			if (k.isVirtual()) {
				virtual++;
			} else {
				physical++;
			}
		}
		keys.set(virtual, "true");
		keys.set(physical, "false");
	}

	private void collectPlayers() {
		if (playerData == null) {
			return;
		}
		var online = Bukkit.getOnlinePlayers().stream()
				.map(p -> PlayerRef.of(p.getUniqueId(), p.getName()))
				.toList();
		var selected = series.select(online,
				playerKeys, playerTotalOpens, playerCooldown, playerRewardWins);
		if (selected.isEmpty()) {
			return;
		}

		var cratesManager = PhoenixCratesAPI.getCratesManager();
		var keysManager = PhoenixCratesAPI.getKeysManager();
		var playersManager = PhoenixCratesAPI.getPlayersManager();
		var crateIds = cratesManager.getCratesIdentifier();
		var keyIds = keysManager.getKeysIdentifier();
		boolean detailWins = config.getBoolean("collector.crate.player_reward_wins", true);

		for (PlayerRef ref : selected) {
			Player player = Bukkit.getPlayer(java.util.UUID.fromString(ref.uuid()));
			if (player == null) {
				continue;
			}
			// FALSE, AND NEVER TRUE. The boolean set to true triggers a database read if the
			// cache is cold; getCachedDataNow is worse, it throws. With false, it's a hash
			// table lookup and nothing more.
			Object data = playersManager.getPlayerIfCached(player, false);
			if (data == null) {
				continue;
			}
			for (String key : keyIds) {
				playerKeys.set(playerData.virtualKeys(data, key), ref.labels(identifier(key)));
			}
			for (String crate : crateIds) {
				String crateId = identifier(crate);
				playerTotalOpens.set(playerData.opens(data, crate), ref.labels(crateId));
				playerCooldown.set(playerData.cooldownSeconds(data, crate), ref.labels(crateId));
			}
			if (!detailWins) {
				continue;
			}
			for (String crate : crateIds) {
				CrateType type = cratesManager.getTypeByIdentifier(crate);
				if (type == null) {
					continue;
				}
				for (Reward reward : type.getRegisteredRewards()) {
					int n = playerData.wins(data, reward.getIdentifier());
					if (n > 0) {
						playerRewardWins.set(n, ref.labels(identifier(reward.getIdentifier())));
					}
				}
			}
		}
	}

	/**
	 * Anything still pending at the next scrape is a refusal.
	 *
	 * <p>Acknowledged limitation: {@code CratePreOpenEvent} only fires through the plugin's
	 * normal path. Another plugin calling {@code CrateInstance.openCrate()} directly would
	 * produce an open with no matching attempt — the two counters aren't linked by
	 * construction, only in practice.
	 */
	private void flushOrphaned() {
		if (pending.isEmpty()) {
			return;
		}
		for (var entry : Map.copyOf(pending).entrySet()) {
			Attempt a = pending.remove(entry.getKey());
			if (a != null) {
				failures.inc(a.crate(), a.likelyReason());
			}
		}
	}

	// ------------------------------------------------------------------ utilities

	/**
	 * Why this open is likely to fail, based on the state BEFORE the attempt.
	 *
	 * <p>An inference, not a read: the plugin only returns a translated message. The order
	 * follows the plugin's own checks, so the most likely reason comes out first.
	 */
	private String likelyReason(CrateType type, Player player) {
		if (playerData != null) {
			Object data = PhoenixCratesAPI.getPlayersManager().getPlayerIfCached(player, false);
			if (data != null) {
				if (playerData.cooldownSeconds(data, type.getIdentifier()) > 0) {
					return "cooldown";
				}
				if (type.isKeyRequired() && noVirtualKey(data, type)) {
					// "likely" in the strict sense: the player may be carrying a PHYSICAL key
					// that we don't count here, for lack of a public accessor to see it.
					return "no_key";
				}
			}
		}
		return type.getOpenMoneyCost() > 0 ? "money" : "other";
	}

	private boolean noVirtualKey(Object data, CrateType type) {
		for (String key : type.getLinkedKeysIds()) {
			if (playerData.virtualKeys(data, key) > 0) {
				return false;
			}
		}
		return true;
	}

	private static String currency(CrateType type) {
		var engine = type.getCostEngineType();
		// getName() and not name(): CostEngineType is an interface, not an enum — which is
		// what leaves the door open to cost engines added by extensions.
		return engine == null ? "unknown" : identifier(engine.getName());
	}

	/**
	 * A stable label.
	 *
	 * <p>Always the identifier, never {@code getDisplayName()}, which carries color codes and
	 * changes with formatting — a Prometheus label must not move when a menu gets repainted.
	 */
	private static String identifier(String raw) {
		return raw == null || raw.isBlank() ? "unknown" : raw.toLowerCase(Locale.ROOT);
	}
}
