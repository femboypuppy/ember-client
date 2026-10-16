package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.scoreboard.Team;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Shared DonutSMP staff state used by {@link StaffDetector} and {@link AntiVanish}:
 * <ul>
 *     <li>Who is staff. DonutSMP sorts the tab list with scoreboard teams named {@code NN-<name>-<flag>}, where NN
 *     is the rank weight. Regular players (Donut+ included) are 09, staff are 08 and below.</li>
 *     <li>Which goliath (backend shard) you are on, and which staff have been proven to be on it. Proof is a game
 *     mode update that arrives without ADD_PLAYER (the backend you are on only reports game mode changes of its own
 *     players) or the staff member's entity being loaded. Proofs are dropped whenever you change shard.</li>
 *     <li>One alert gate so a single event never plays the staff alert twice, and the same staff member only
 *     re-alerts when the situation gets worse (region, then spectator/vanish, then physically near you).</li>
 * </ul>
 */
public final class StaffIntel {
    private static final StaffIntel INSTANCE = new StaffIntel();

    private static final Pattern TEAM_PATTERN = Pattern.compile("^(\\d{1,3})-([A-Za-z0-9_.]{1,17})-(\\w+)$");
    private static final long PROOF_TTL_MS = 10 * 60_000L;
    private static final long ALERT_COOLDOWN_MS = 5 * 60_000L;

    public enum AlertKind {
        REGION(1),
        SPECTATOR(2),
        VANISHED(2),
        NEARBY(3);

        final int severity;

        AlertKind(int severity) {
            this.severity = severity;
        }
    }

    private record PendingMode(UUID id, GameMode mode) {}
    private record AlertRecord(long at, int severity) {}

    private final Queue<PendingMode> pendingModes = new ConcurrentLinkedQueue<>();
    private final Queue<UUID> pendingRemovals = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Long> provenAt = new ConcurrentHashMap<>();
    private final Map<UUID, GameMode> shardGameModes = new ConcurrentHashMap<>();
    private final Map<String, AlertRecord> alerts = new HashMap<>();
    private final Set<String> loggedTeams = new HashSet<>();
    private final Set<String> starred = ConcurrentHashMap.newKeySet();

    private ClientPlayNetworkHandler connection;
    private Object trackedWorld;
    private int shard = Integer.MIN_VALUE;
    private long shardSinceMs;

    public volatile boolean debug;

    private StaffIntel() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public static StaffIntel get() {
        return INSTANCE;
    }

    // Staff identity

    /** Rank weight from the DonutSMP sort team, or -1 when the player has no such team. */
    public static int teamPriority(PlayerListEntry entry) {
        if (entry == null) return -1;
        Team team = entry.getScoreboardTeam();
        if (team == null) return -1;

        Matcher m = TEAM_PATTERN.matcher(team.getName());
        if (!m.matches()) return -1;

        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public int staffTeamThreshold() {
        StaffDetector detector = detector();
        return detector != null ? detector.teamThreshold() : 8;
    }

    /** True if the tab entry belongs to staff by team weight, star prefix, roster or the custom name list. */
    public boolean isStaff(PlayerListEntry entry) {
        if (entry == null || entry.getProfile() == null) return false;
        String name = entry.getProfile().name();

        int priority = teamPriority(entry);
        if (priority >= 0 && priority <= staffTeamThreshold()) {
            logTeamOnce(entry, priority);
            return true;
        }

        if (entry.getDisplayName() != null && StaffDetector.hasLeadingStar(entry.getDisplayName().getString())) {
            starred.add(name.toLowerCase(Locale.ROOT));
            return true;
        }

        return isStaffName(name);
    }

    /** Name-only check for players that are not (or no longer) in the tab list. Main thread only. */
    public boolean isStaffName(String name) {
        if (name == null || name.isBlank()) return false;

        if (mc.getNetworkHandler() != null) {
            PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(name.trim());
            int priority = teamPriority(entry);
            if (priority >= 0 && priority <= staffTeamThreshold()) return true;
        }

        return isRosterOrStarred(name);
    }

    /** Roster, custom list and learned star prefixes only; safe off the main thread. */
    public boolean isRosterOrStarred(String name) {
        if (name == null || name.isBlank()) return false;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (starred.contains(lower)) return true;

        StaffDetector detector = detector();
        if (detector != null) return detector.isRosterName(name);

        for (String s : StaffDetector.DEFAULT_DONUT_STAFF) {
            if (s.equalsIgnoreCase(lower)) return true;
        }
        return false;
    }

    private void logTeamOnce(PlayerListEntry entry, int priority) {
        if (!debug) return;
        Team team = entry.getScoreboardTeam();
        if (team == null || !loggedTeams.add(team.getName())) return;
        log("staff team %s (weight %d) display=%s", team.getName(), priority,
            entry.getDisplayName() == null ? "-" : entry.getDisplayName().getString());
    }

    // Shard / region proof

    public int currentShard() {
        return shard;
    }

    public String currentShardLabel() {
        if (shard < DonutShards.OFF_MAP && mc.world != null) return mc.world.getRegistryKey().getValue().getPath();
        return DonutShards.label(shard);
    }

    /** True if the player is proven to be on the goliath you are on right now. */
    public boolean isOnYourShard(UUID id) {
        if (id == null) return false;
        Long at = provenAt.get(id);
        if (at != null && System.currentTimeMillis() - at < PROOF_TTL_MS) return true;
        return entity(id) != null;
    }

    /** Last game mode the current goliath reported for this player, if any. */
    public GameMode shardGameMode(UUID id) {
        return id == null ? null : shardGameModes.get(id);
    }

    public PlayerEntity entity(UUID id) {
        if (id == null || mc.world == null) return null;
        PlayerEntity player = mc.world.getPlayerByUuid(id);
        return player == null || player == mc.player || player.isRemoved() ? null : player;
    }

    public long msOnShard() {
        return System.currentTimeMillis() - shardSinceMs;
    }

    // Alert gate

    /**
     * Returns true if this alert should be shown. The same staff member only alerts again within the cooldown when
     * the new alert is more severe than the last one (region -> spectator/vanish -> near you).
     */
    public boolean tryAlert(String name, AlertKind kind) {
        if (name == null || name.isBlank()) return false;
        String key = name.trim().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        AlertRecord last = alerts.get(key);
        if (last != null && now - last.at < ALERT_COOLDOWN_MS && kind.severity <= last.severity) {
            log("alert suppressed for %s (%s, cooldown)", name, kind);
            return false;
        }

        alerts.put(key, new AlertRecord(now, kind.severity));
        log("ALERT %s %s on %s", kind, name, currentShardLabel());
        return true;
    }

    // Events

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerListS2CPacket packet) {
            EnumSet<PlayerListS2CPacket.Action> actions = packet.getActions();
            boolean add = actions.contains(PlayerListS2CPacket.Action.ADD_PLAYER);

            if (actions.contains(PlayerListS2CPacket.Action.UPDATE_GAME_MODE) && !add) {
                for (PlayerListS2CPacket.Entry entry : packet.getEntries()) {
                    if (entry == null || entry.profileId() == null || entry.gameMode() == null) continue;
                    pendingModes.offer(new PendingMode(entry.profileId(), entry.gameMode()));
                }
            }

            if (debug) logListPacket(packet, actions, add);
        } else if (event.packet instanceof PlayerRemoveS2CPacket remove && debug) {
            pendingRemovals.addAll(remove.profileIds());
        }
    }

    private void logListPacket(PlayerListS2CPacket packet, EnumSet<PlayerListS2CPacket.Action> actions, boolean add) {
        // Names for ADD_PLAYER come with the packet; other actions are resolved later on the main thread.
        if (add) {
            List<String> staff = new ArrayList<>();
            for (PlayerListS2CPacket.Entry entry : packet.getEntries()) {
                if (entry == null || entry.profile() == null) continue;
                String name = entry.profile().name();
                if (isRosterOrStarred(name)) staff.add(name + "[gm=" + entry.gameMode() + ",listed=" + entry.listed() + "]");
            }
            if (!staff.isEmpty() || packet.getEntries().size() <= 3) {
                log("tab ADD %s x%d staff=%s", actions, packet.getEntries().size(), staff);
            }
        } else if (actions.contains(PlayerListS2CPacket.Action.UPDATE_LISTED)) {
            for (PlayerListS2CPacket.Entry entry : packet.getEntries()) {
                if (entry == null || entry.listed()) continue;
                log("tab UNLISTED %s (actions %s)", entry.profileId(), actions);
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientPlayNetworkHandler conn = mc.getNetworkHandler();
        if (conn == null || mc.world == null || mc.player == null) {
            if (connection != null) resetAll();
            return;
        }

        if (conn != connection) {
            resetAll();
            connection = conn;
            log("connected, tracking staff");
        }

        // Other dimensions are their own servers; give each one a distinct id below OFF_MAP.
        int nowShard = mc.world.getRegistryKey() == World.OVERWORLD
            ? DonutShards.shardAt(mc.player.getX(), mc.player.getZ())
            : DonutShards.OFF_MAP - 1 - Math.floorMod(mc.world.getRegistryKey().getValue().hashCode(), 1000);

        if (mc.world != trackedWorld || nowShard != shard) {
            trackedWorld = mc.world;
            shard = nowShard;
            shardSinceMs = System.currentTimeMillis();
            if (!provenAt.isEmpty()) log("shard changed, dropping %d region proofs", provenAt.size());
            provenAt.clear();
            shardGameModes.clear();
            log("now on %s (%s, %d %d)", currentShardLabel(), mc.world.getRegistryKey().getValue(),
                mc.player.getBlockX(), mc.player.getBlockZ());
        }

        PendingMode mode;
        while ((mode = pendingModes.poll()) != null) {
            if (mode.id().equals(mc.player.getUuid())) continue;

            PlayerListEntry entry = conn.getPlayerListEntry(mode.id());
            String name = entry != null && entry.getProfile() != null ? entry.getProfile().name() : null;
            boolean staff = entry != null ? isStaff(entry) : isStaffName(name);

            provenAt.put(mode.id(), System.currentTimeMillis());
            shardGameModes.put(mode.id(), mode.mode());

            if (debug) {
                PlayerEntity body = entity(mode.id());
                log("gamemode-only update: %s -> %s staff=%s entity=%s team=%s", name == null ? mode.id() : name, mode.mode(), staff,
                    body == null ? "no" : String.format("%.0fm", body.distanceTo(mc.player)),
                    entry == null || entry.getScoreboardTeam() == null ? "-" : entry.getScoreboardTeam().getName());
            }
        }

        UUID removed;
        while ((removed = pendingRemovals.poll()) != null) {
            Long proof = provenAt.get(removed);
            if (proof != null) log("tab REMOVE of a player proven on this shard: %s", removed);
        }

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != mc.player) provenAt.put(player.getUuid(), System.currentTimeMillis());
        }
    }

    private void resetAll() {
        connection = null;
        trackedWorld = null;
        shard = Integer.MIN_VALUE;
        pendingModes.clear();
        pendingRemovals.clear();
        provenAt.clear();
        shardGameModes.clear();
        alerts.clear();
        loggedTeams.clear();
        starred.clear();
    }

    private static StaffDetector detector() {
        return Modules.get() != null ? Modules.get().get(StaffDetector.class) : null;
    }

    public void log(String format, Object... args) {
        if (!debug) return;
        MeteorClient.LOG.info("[Ember StaffIntel] " + String.format(Locale.ROOT, format, args));
    }
}
