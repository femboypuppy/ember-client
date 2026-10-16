package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.XAnchor;
import meteordevelopment.meteorclient.systems.hud.YAnchor;
import meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud;
import meteordevelopment.meteorclient.systems.hud.elements.EmberStaffHud;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.scoreboard.Team;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class StaffDetector extends Module {
    /**
     * Known DonutSMP staff accounts. Most come from the Glazed addon's roster; Dough4 was seen in a staff sort team
     * (weight 08) on the live server. Team weights catch staff who are missing here, so this is only a fallback.
     */
    public static final List<String> DEFAULT_DONUT_STAFF = List.of(
        "DrDonutt",
        "FluffyMaster07",
        "archivePedro",
        "Munkerlich",
        "Frenk_Btw",
        "Napooo_",
        "auzzitech",
        "CryptoDaveYT",
        "W1zoX_",
        "ZEEF69",
        "Showered",
        "CaptainMoose35",
        "BobIsFound",
        "NoahvdAa",
        "0GSummer",
        "LzouzMP5",
        "PastaGamer08",
        "u_vv",
        "Owen1212055",
        "Splaterd",
        "Fallerfly",
        "Frwost",
        "bautiedgar",
        "Itszdeath",
        "Dough4",
        "Bloodspulse"
    );

    /**
     * DonutSMP marks staff members in the Tab list with a leading star glyph.
     */
    private static final Set<Integer> STAR_CODEPOINTS = new HashSet<>();
    private static final Set<String> DEFAULT_STAFF_LOWER = new HashSet<>();

    static {
        "★☆✦✧✪✯✰⭐".codePoints().forEach(STAR_CODEPOINTS::add);
        for (String s : DEFAULT_DONUT_STAFF) {
            DEFAULT_STAFF_LOWER.add(s.toLowerCase(Locale.ROOT));
        }
    }

    public static final class StaffEntry {
        public final String name;
        public UUID uuid;
        public String rank;
        public Identifier skin;
        public int ping;
        public boolean online;
        public boolean vanished;
        public boolean spectator;
        public boolean inRegion;
        public boolean nearby;
        public double distance;
        public long joinedAt;
        public long leftAt;
        public long regionProvenAt;
        public float anim;
        public float pulse;

        public StaffEntry(String name, String rank, Identifier skin, int ping, long now) {
            this.name = name;
            this.rank = rank;
            this.skin = skin;
            this.ping = ping;
            this.online = true;
            this.joinedAt = now;
            this.pulse = 1f;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAlerts = settings.createGroup("Alerts");

    private final Setting<Boolean> detectByTeam = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-by-team")
        .description("Detect staff from DonutSMP's tab sort teams (NN-name-flag). Regular players are 09, staff are 08 and below.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> teamWeight = sgGeneral.add(new IntSetting.Builder()
        .name("staff-team-weight")
        .description("Highest team weight that still counts as staff.")
        .defaultValue(8)
        .range(0, 20)
        .sliderRange(0, 12)
        .visible(detectByTeam::get)
        .build()
    );

    private final Setting<Boolean> shardProbe = sgGeneral.add(new BoolSetting.Builder()
        .name("shard-probe")
        .description("Every 5 seconds, silently asks your region's server which players it hosts, so staff on your shard are marked IN REGION.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> hiddenTimeout = sgGeneral.add(new DoubleSetting.Builder()
        .name("hidden-timeout")
        .description("Minutes a staff member stays marked HIDDEN on your shard after their game mode leaks. Nothing tells us when a vanished player leaves, so this is a guess.")
        .defaultValue(3.0)
        .min(0.5)
        .max(30.0)
        .sliderRange(0.5, 10.0)
        .build()
    );

    private final Setting<Boolean> useDonutRoster = sgGeneral.add(new BoolSetting.Builder()
        .name("donut-staff-roster")
        .description("Also include the built-in DonutSMP staff username roster.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> checkStarPrefix = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-star-prefix")
        .description("Detect staff by a star symbol at the start of their tab list name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> staffNames = sgGeneral.add(new StringListSetting.Builder()
        .name("staff-names")
        .description("Usernames that always count as staff.")
        .defaultValue(DEFAULT_DONUT_STAFF)
        .build()
    );

    private final Setting<Boolean> checkRanks = sgGeneral.add(new BoolSetting.Builder()
        .name("check-ranks")
        .description("Treat players whose tab list rank contains one of the rank words as staff.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> rankWords = sgGeneral.add(new StringListSetting.Builder()
        .name("rank-words")
        .description("Rank tags that mark staff, matched as whole words.")
        .defaultValue("Owner", "Admin", "Manager", "Mod", "Moderator", "Helper", "Staff", "Developer", "SrMod", "JrMod", "Trial")
        .visible(checkRanks::get)
        .build()
    );

    public final Setting<Boolean> showEmpty = sgGeneral.add(new BoolSetting.Builder()
        .name("show-when-empty")
        .description("Keep the on-screen Staff Detector UI visible even when 0 moderators are online.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> leaveHoldSeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("leave-display-seconds")
        .description("How long a moderator stays shown as LEFT on the UI before disappearing.")
        .defaultValue(4.0)
        .min(0.5)
        .max(15.0)
        .sliderRange(0.5, 15.0)
        .build()
    );

    private final Setting<Boolean> alertNearby = sgAlerts.add(new BoolSetting.Builder()
        .name("alert-region")
        .description("Alert when a staff member is proven to be on your shard or comes into render distance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> alertJoinLeave = sgAlerts.add(new BoolSetting.Builder()
        .name("alert-join-leave")
        .description("Small notification when a staff member joins or leaves the server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chat = sgAlerts.add(new BoolSetting.Builder()
        .name("chat")
        .description("Also post alerts in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> notification = sgAlerts.add(new BoolSetting.Builder()
        .name("notification")
        .description("Also show alerts in the bottom-right notifications widget.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sound = sgAlerts.add(new BoolSetting.Builder()
        .name("sound")
        .description("Play a sound for join/leave notifications.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> elderAnimation = sgAlerts.add(new BoolSetting.Builder()
        .name("elder-guardian-animation")
        .description("Show the staff alert overlay: a calm card when staff are in your region, the elder guardian face when they are near you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> testAnimation = sgAlerts.add(new BoolSetting.Builder()
        .name("test-elder-animation")
        .description("Click to immediately preview the Elder Guardian staff-head alert.")
        .defaultValue(false)
        .onChanged(v -> {
            if (v) {
                triggerTestAnimation();
            }
        })
        .build()
    );

    /** Staff currently online or recently left (keyed by lowercase name). */
    private final Map<String, StaffEntry> tracked = new LinkedHashMap<>();
    private final Set<String> nearbySet = new HashSet<>();
    private final Map<UUID, Boolean> listedState = new ConcurrentHashMap<>();
    private final Set<UUID> seenListed = ConcurrentHashMap.newKeySet();
    private boolean primed;
    private long lastScanMs;
    private Object connection;
    private long settleUntil;
    private long offlineSince;

    public StaffDetector() {
        super(Categories.Donut, "staff-detector", "Displays an on-screen UI of online moderators that updates as they join or leave.");
        StaffIntel.get().onLeak(this::onLeakVerdict);
    }

    /** A staff game mode change forwarded by your shard means they are watching someone (see AntiVanish#onLeakVerdict). */
    private void onLeakVerdict(StaffIntel.LeakEvent leak) {
        if (!isActive() || !alertNearby.get() || mc.player == null) return;
        String mode = leak.mode() == null ? "?" : leak.mode().asString().toUpperCase(Locale.ROOT);
        String state = leak.verdict() == StaffIntel.Verdict.HIDDEN ? "Vanished"
            : leak.verdict() == StaffIntel.Verdict.VISIBLE ? "Visible" : "On this server";

        StaffEntry entry = tracked.get(leak.name().toLowerCase(Locale.ROOT));
        if (entry != null) entry.pulse = 1f;
        big(leak.id(), leak.name(), StaffIntel.AlertKind.WATCHING, state + ", switched to " + mode + " · " + StaffIntel.get().currentShardLabel());
    }

    private void triggerTestAnimation() {
        String sampleName = "DrDonutt";
        for (StaffEntry entry : tracked.values()) {
            if (entry.online && entry.name != null && !entry.name.isBlank()) {
                sampleName = entry.name;
                break;
            }
        }
        StaffWatchingEffect.get().play(null, sampleName, StaffWatchingEffect.Level.REGION, "Preview \u00B7 " + StaffIntel.get().currentShardLabel());
        testAnimation.set(false);
    }

    public boolean probeEnabled() {
        return shardProbe.get();
    }

    public long hiddenTimeoutMs() {
        return Math.round(hiddenTimeout.get() * 60_000.0);
    }

    public int teamThreshold() {
        return isActive() && !detectByTeam.get() ? -1 : teamWeight.get();
    }

    /** True if the name is on the built-in roster (when enabled) or the custom staff list. */
    public boolean isRosterName(String name) {
        if (name == null || name.isBlank()) return false;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (useDonutRoster.get() && DEFAULT_STAFF_LOWER.contains(lower)) return true;
        for (String listed : staffNames.get()) {
            if (listed != null && listed.trim().equalsIgnoreCase(lower)) return true;
        }
        return false;
    }

    /** True if this staff member is proven to be on your shard (game mode leak) or within render distance. */
    public boolean isOnYourShard(String name) {
        if (name == null || name.isBlank()) return false;
        StaffEntry entry = tracked.get(name.trim().toLowerCase(Locale.ROOT));
        return entry != null && entry.online && (entry.inRegion || entry.nearby);
    }

    public boolean isTrackedStaffName(String name) {
        if (name == null || name.isBlank()) return false;
        return tracked.containsKey(name.trim().toLowerCase(Locale.ROOT)) || StaffIntel.get().isStaffName(name);
    }

    @Override
    public void onActivate() {
        tracked.clear();
        nearbySet.clear();
        listedState.clear();
        seenListed.clear();
        primed = false;
        lastScanMs = 0;

        for (String staff : DEFAULT_DONUT_STAFF) {
            StaffFaceSkins.preload(staff);
        }

        ensureHudElementActive();
    }

    private void ensureHudElementActive() {
        Hud hud = Hud.get();
        if (hud == null) return;

        EmberStaffHud found = null;
        for (HudElement el : hud) {
            if (el instanceof EmberStaffHud staffHud) {
                found = staffHud;
                break;
            }
        }

        if (found == null) {
            hud.add(EmberStaffHud.INFO, 8, 168, XAnchor.Left, YAnchor.Top);
            for (HudElement el : hud) {
                if (el instanceof EmberStaffHud staffHud) {
                    found = staffHud;
                    break;
                }
            }
        }

        if (found != null && !found.isActive()) {
            found.toggle();
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerListS2CPacket listPacket)) return;

        var actions = listPacket.getActions();
        if (!actions.contains(PlayerListS2CPacket.Action.ADD_PLAYER) && !actions.contains(PlayerListS2CPacket.Action.UPDATE_LISTED)) return;

        for (PlayerListS2CPacket.Entry entry : listPacket.getEntries()) {
            if (entry == null || entry.profileId() == null) continue;
            listedState.put(entry.profileId(), entry.listed());
            if (entry.listed()) seenListed.add(entry.profileId());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        scanNow();
    }

    /**
     * Scans the tab list and nearby players for staff members and updates join/leave state.
     * Safe to call from both tick and HUD render.
     */
    public List<StaffEntry> getTrackedEntries() {
        scanNow();
        return new ArrayList<>(tracked.values());
    }

    public int getOnlineCount() {
        int count = 0;
        for (StaffEntry entry : tracked.values()) {
            if (entry.online) count++;
        }
        return count;
    }

    public void scanNow() {
        long nowMs = System.currentTimeMillis();

        if (mc.getNetworkHandler() == null || mc.world == null || mc.player == null) {
            // DonutSMP reconnects you on every region change; only forget everything after a real disconnect.
            if (offlineSince == 0) offlineSince = nowMs;
            if (nowMs - offlineSince > 30_000L) {
                tracked.clear();
                nearbySet.clear();
                primed = false;
            }
            return;
        }

        if (mc.getNetworkHandler() != connection) {
            connection = mc.getNetworkHandler();
            settleUntil = nowMs + 4000L;
        }
        offlineSince = 0;

        if (nowMs - lastScanMs < 400) return;
        lastScanMs = nowMs;
        StaffIntel intel = StaffIntel.get();
        // Switching shard re-sends the whole tab list, so give it a moment before treating gaps as leaves.
        boolean settling = nowMs < settleUntil || intel.msOnShard() < 4000L;
        String self = mc.player.getName().getString();

        Map<String, PlayerListEntry> currentTab = new LinkedHashMap<>();
        Map<String, String> currentRanks = new LinkedHashMap<>();

        for (PlayerListEntry info : mc.getNetworkHandler().getPlayerList()) {
            if (info.getProfile() == null) continue;
            String name = info.getProfile().name();
            if (name == null || name.equalsIgnoreCase(self)) continue;

            String cleanName = stripZeroWidth(name).trim();
            if (cleanName.isEmpty()) continue;

            String rank = staffRank(info, cleanName);
            if (rank != null) {
                String key = cleanName.toLowerCase(Locale.ROOT);
                currentTab.put(key, info);
                currentRanks.put(key, rank);
            }
        }

        // Newly joined or still-online staff
        for (Map.Entry<String, PlayerListEntry> e : currentTab.entrySet()) {
            String key = e.getKey();
            PlayerListEntry info = e.getValue();
            String name = stripZeroWidth(info.getProfile().name()).trim();
            UUID uuid = info.getProfile().id();
            String rank = currentRanks.get(key);
            Identifier skin = info.getSkinTextures() != null && info.getSkinTextures().body() != null
                ? info.getSkinTextures().body().texturePath()
                : StaffFaceSkins.getOrFetch(uuid, name);

            PlayerEntity body = intel.entity(uuid);
            boolean onShard = intel.isOnYourShard(uuid, name);
            GameMode shardMode = intel.shardGameMode(uuid);
            boolean spectator = shardMode == GameMode.SPECTATOR || (shardMode == null && info.getGameMode() == GameMode.SPECTATOR);
            // Only a listed -> unlisted change counts as vanish; some servers never list part of the tab.
            boolean unlisted = uuid != null && seenListed.contains(uuid) && Boolean.FALSE.equals(listedState.get(uuid));
            // Their game mode leaked from your shard but the backend does not list them: vanished here.
            boolean hiddenHere = intel.isHiddenOnShard(uuid, name);
            boolean vanished = unlisted || hiddenHere;

            StaffEntry entry = tracked.get(key);
            boolean fresh = entry == null;
            boolean wasOffline = !fresh && !entry.online;
            boolean wasSpectator = !fresh && entry.spectator;
            boolean wasVanished = !fresh && entry.vanished;
            boolean wasOnShard = !fresh && entry.inRegion;

            if (fresh) {
                entry = new StaffEntry(name, rank, skin, info.getLatency(), nowMs);
                tracked.put(key, entry);
            }

            entry.uuid = uuid;
            entry.online = true;
            entry.rank = rank;
            if (skin != null) entry.skin = skin;
            entry.ping = info.getLatency();
            entry.spectator = spectator;
            entry.vanished = vanished;
            entry.inRegion = onShard;
            entry.nearby = body != null;
            entry.distance = body != null ? mc.player.distanceTo(body) : 0;
            if (onShard && !wasOnShard) entry.regionProvenAt = nowMs;
            entry.leftAt = 0;

            if (!primed) continue;

            // You changing shard clears everyone; only their own departure is news.
            if (wasOnShard && !onShard && !settling) {
                intel.noteLeft(name);
                nearbySet.remove(key);
                if (alertNearby.get()) small(name + " left your region", wasVanished ? "or the hidden timeout ran out" : intel.currentShardLabel());
            }

            if (fresh || wasOffline) {
                entry.joinedAt = nowMs;
                entry.pulse = 1f;
                // While the tab list refills after a transfer, staff showing up is not a join.
                if (alertJoinLeave.get() && !settling) small(name + " joined", rank);
            }

            if (!alertNearby.get()) continue;

            String nearKey = key;
            if (body != null) {
                if (nearbySet.add(nearKey)) {
                    entry.pulse = 1f;
                    big(uuid, name, StaffIntel.AlertKind.NEARBY, String.format("%.0f blocks away", entry.distance));
                }
            } else {
                nearbySet.remove(nearKey);
            }

            if (onShard && spectator && !wasSpectator) {
                entry.pulse = 1f;
                big(uuid, name, StaffIntel.AlertKind.SPECTATOR, "Spectator mode \u00B7 " + intel.currentShardLabel());
            } else if (onShard && vanished && !wasVanished) {
                entry.pulse = 1f;
                big(uuid, name, StaffIntel.AlertKind.VANISHED,
                    (hiddenHere ? "Game mode leaked, not in the player list" : "Hidden from tab") + " \u00B7 " + intel.currentShardLabel());
            } else if (onShard && !wasOnShard) {
                entry.pulse = 1f;
                big(uuid, name, StaffIntel.AlertKind.REGION, intel.currentShardLabel());
            } else if (!onShard && vanished && !wasVanished && alertJoinLeave.get()) {
                entry.pulse = 1f;
                small(name + " vanished", "somewhere else on the server");
            }
        }

        // Staff that dropped out of the tab list (not while it is still refilling after a transfer)
        for (StaffEntry entry : tracked.values()) {
            String key = entry.name.toLowerCase(Locale.ROOT);
            if (settling || !entry.online || currentTab.containsKey(key)) continue;

            PlayerEntity body = intel.entity(entry.uuid);
            if (body != null) {
                // Gone from tab but the body is still here: hidden from the list while next to you.
                entry.nearby = true;
                entry.distance = mc.player.distanceTo(body);
                if (!entry.vanished) {
                    entry.vanished = true;
                    entry.pulse = 1f;
                    if (primed && alertNearby.get()) big(entry.uuid, entry.name, StaffIntel.AlertKind.NEARBY, "Hidden from tab, right next to you");
                }
                continue;
            }

            entry.online = false;
            entry.nearby = false;
            entry.vanished = false;
            entry.spectator = false;
            entry.inRegion = false;
            entry.leftAt = nowMs;
            entry.pulse = 1f;
            nearbySet.remove(key);
            if (primed && alertJoinLeave.get()) small(entry.name + " left", entry.rank);
        }

        if (!primed) {
            primed = true;
            List<String> names = new ArrayList<>();
            for (StaffEntry se : tracked.values()) if (se.online) names.add(se.name);
            if (!names.isEmpty()) small("Staff online", String.join(", ", names));
            // Mark staff that are already around so they do not alert on the first scan.
            for (StaffEntry se : tracked.values()) if (se.nearby) nearbySet.add(se.name.toLowerCase(Locale.ROOT));
        }

        // Expire left entries after hold duration and anim fade out
        long holdMs = Math.round(leaveHoldSeconds.get() * 1000.0);
        tracked.entrySet().removeIf(en -> {
            StaffEntry se = en.getValue();
            return !se.online && (nowMs - se.leftAt > holdMs) && se.anim <= 0.02f;
        });
    }

    private String staffRank(PlayerListEntry info, String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);

        int weight = StaffIntel.teamPriority(info);
        if (detectByTeam.get() && weight >= 0 && weight <= teamWeight.get()) {
            if (lowerName.equals("drdonutt")) return "OWNER";
            return weight <= 3 ? "ADMIN" : "STAFF";
        }

        if (isRosterName(name)) {
            if (lowerName.equals("drdonutt")) return "OWNER";
            return "STAFF";
        }

        StringBuilder text = new StringBuilder();
        if (info.getDisplayName() != null) text.append(info.getDisplayName().getString()).append(' ');

        Team team = info.getScoreboardTeam();
        if (team != null) {
            text.append(team.getPrefix().getString()).append(' ').append(team.getSuffix().getString());
        }

        String fullTabText = text.toString();
        if (checkStarPrefix.get() && hasLeadingStar(fullTabText)) {
            return "★ STAFF";
        }

        if (!checkRanks.get()) return null;

        String clean = stripFormatting(fullTabText);

        // A username can itself contain a rank word ("mod_steve"), so only the decoration around it is checked.
        String decoration = clean.replace(name, " ");

        for (String word : rankWords.get()) {
            String trimmed = word.trim();
            if (trimmed.isEmpty()) continue;

            Pattern pattern = Pattern.compile("(?i)(?<![A-Za-z])" + Pattern.quote(trimmed) + "(?![A-Za-z])");
            if (pattern.matcher(decoration).find()) return trimmed.toUpperCase(Locale.ROOT);
        }

        return null;
    }

    /**
     * Checks if the Tab list display name starts with a star symbol
     * (★, ☆, ✦, ✧, ✪, ✯, ✰, ⭐) after stripping formatting codes and zero-width characters.
     */
    public static boolean hasLeadingStar(String text) {
        if (text == null || text.isEmpty()) return false;
        String clean = stripFormatting(text);

        int i = 0;
        while (i < clean.length()) {
            int cp = clean.codePointAt(i);
            i += Character.charCount(cp);

            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)
                || cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFE0F || cp == 0xFEFF) {
                continue;
            }
            return STAR_CODEPOINTS.contains(cp);
        }
        return false;
    }

    private static String stripFormatting(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)\\u00a7[0-9A-FK-ORX]", "");
    }

    private static String stripZeroWidth(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\u200B\\u200C\\u200D\\uFE0F\\uFEFF]", "");
    }

    /** Staff alert overlay (or a notification when it is off), gated so the same staff member does not keep re-alerting. */
    private void big(UUID uuid, String name, StaffIntel.AlertKind kind, String subtitle) {
        if (!StaffIntel.get().tryAlert(name, kind)) return;

        StaffWatchingEffect.Level level = StaffWatchingEffect.Level.of(kind);
        String title = switch (level) {
            case REGION -> name + " is in your region";
            case HIDDEN -> name + " is hidden in your region";
            case WATCHING -> name + " is watching you";
        };

        if (elderAnimation.get()) {
            StaffWatchingEffect.get().play(uuid, name, level, subtitle);
        } else {
            if (notification.get()) EmberNotificationsHud.push(title, subtitle, level == StaffWatchingEffect.Level.REGION ? EmberNotificationsHud.WARN : EmberNotificationsHud.BAD);
            if (sound.get() && mc.player != null) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.85f, 1.1f);
        }
        if (chat.get() && !EmberNotificationsHud.shouldInterceptChat()) info("%s (%s)", title, subtitle);
    }

    private void small(String title, String subtitle) {
        if (notification.get()) EmberNotificationsHud.push(title, subtitle, EmberNotificationsHud.WARN);
        if (chat.get()) info("%s (%s)", title, subtitle);
        if (sound.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.85f, 1.1f);
        }
    }
}
