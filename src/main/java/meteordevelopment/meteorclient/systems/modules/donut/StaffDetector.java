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
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
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
     * Built-in DonutSMP known staff/admin roster.
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
        "Itszdeath"
    );

    /**
     * DonutSMP marks staff members in the Tab list with a leading star glyph.
     */
    private static final Set<Integer> STAR_CODEPOINTS = new HashSet<>();
    private static final Set<String> DEFAULT_STAFF_LOWER = new HashSet<>();

    static {
        "\u2605\u2606\u2726\u2727\u272A\u272F\u2730\u2B50".codePoints().forEach(STAR_CODEPOINTS::add);
        for (String s : DEFAULT_DONUT_STAFF) {
            DEFAULT_STAFF_LOWER.add(s.toLowerCase(Locale.ROOT));
        }
    }

    public static final class StaffEntry {
        public final String name;
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

    private final Setting<Boolean> useDonutRoster = sgGeneral.add(new BoolSetting.Builder()
        .name("donut-staff-roster")
        .description("Automatically include the built-in DonutSMP admin and moderator username roster.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> checkStarPrefix = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-star-prefix")
        .description("Detect DonutSMP staff by the star symbol (★) at the start of their tab list name.")
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
        .name("alert-nearby")
        .description("Highlight when a staff member comes into render distance or onto your region shard.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> alertLeave = sgAlerts.add(new BoolSetting.Builder()
        .name("alert-leave")
        .description("Track when a staff member leaves the server.")
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
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> sound = sgAlerts.add(new BoolSetting.Builder()
        .name("sound")
        .description("Play a sound when staff join, leave, vanish, or come near you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> elderAnimation = sgAlerts.add(new BoolSetting.Builder()
        .name("elder-guardian-animation")
        .description("Play the Elder Guardian staff-head curse animation and siren when staff spectate, vanish, or enter your region.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> testAnimation = sgAlerts.add(new BoolSetting.Builder()
        .name("test-elder-animation")
        .description("Click to immediately preview the Elder Guardian staff-head curse animation and siren.")
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
    private final Set<String> learnedStarredNames = new HashSet<>();
    private final Set<String> nearbySet = new HashSet<>();
    private final Map<UUID, Long> regionSwitchTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> listedState = new ConcurrentHashMap<>();
    private boolean primed;
    private long lastScanMs;

    public StaffDetector() {
        super(Categories.Donut, "staff-detector", "Displays an on-screen UI of online moderators that updates as they join or leave.");
    }

    private void triggerTestAnimation() {
        String sampleName = "DrDonutt";
        for (StaffEntry entry : tracked.values()) {
            if (entry.online && entry.name != null && !entry.name.isBlank()) {
                sampleName = entry.name;
                break;
            }
        }
        StaffWatchingEffect.get().play(null, sampleName, "ELDER GUARDIAN STAFF CURSE PREVIEW", 0);
        testAnimation.set(false);
    }

    public boolean isWatchingOrInRegion(String name) {
        if (name == null || name.isBlank()) return false;
        StaffEntry entry = tracked.get(name.trim().toLowerCase(Locale.ROOT));
        return entry != null && entry.online && (entry.inRegion || entry.nearby || entry.spectator || entry.vanished);
    }

    public boolean isTrackedStaffName(String name) {
        if (name == null || name.isBlank()) return false;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (useDonutRoster.get() && DEFAULT_STAFF_LOWER.contains(lower)) return true;
        if (learnedStarredNames.contains(lower) || tracked.containsKey(lower)) return true;
        for (String listed : staffNames.get()) {
            if (listed != null && listed.trim().equalsIgnoreCase(name.trim())) return true;
        }
        return false;
    }

    @Override
    public void onActivate() {
        tracked.clear();
        learnedStarredNames.clear();
        nearbySet.clear();
        regionSwitchTimestamps.clear();
        listedState.clear();
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
        handlePacket(event.packet);
    }

    private void handlePacket(Packet<?> packet) {
        if (packet instanceof PlayerListS2CPacket listPacket) {
            var actions = listPacket.getActions();
            boolean checkListed = actions.contains(PlayerListS2CPacket.Action.ADD_PLAYER)
                || actions.contains(PlayerListS2CPacket.Action.UPDATE_LISTED);
            // On DonutSMP, UPDATE_GAME_MODE without ADD_PLAYER only arrives for players on your current region shard
            boolean checkRegionSwitch = actions.contains(PlayerListS2CPacket.Action.UPDATE_GAME_MODE)
                && !actions.contains(PlayerListS2CPacket.Action.ADD_PLAYER);

            if (checkListed || checkRegionSwitch) {
                long now = System.currentTimeMillis();
                for (PlayerListS2CPacket.Entry entry : listPacket.getEntries()) {
                    if (entry == null || entry.profileId() == null) continue;
                    if (checkListed) {
                        listedState.put(entry.profileId(), entry.listed());
                    }
                    if (checkRegionSwitch) {
                        regionSwitchTimestamps.put(entry.profileId(), now);
                    }
                }
            }
        } else if (packet instanceof BundleS2CPacket bundle) {
            for (Packet<?> sub : bundle.getPackets()) {
                handlePacket(sub);
            }
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
        if (mc.getNetworkHandler() == null || mc.world == null || mc.player == null) {
            tracked.clear();
            learnedStarredNames.clear();
            nearbySet.clear();
            regionSwitchTimestamps.clear();
            listedState.clear();
            primed = false;
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastScanMs < 400) return;
        lastScanMs = nowMs;

        String self = mc.player.getName().getString();

        Map<String, PlayerListEntry> currentTab = new LinkedHashMap<>();
        Map<String, String> currentRanks = new LinkedHashMap<>();

        for (PlayerListEntry info : mc.getNetworkHandler().getPlayerList()) {
            if (info.getProfile() == null) continue;
            String name = info.getProfile().name();
            if (name == null || name.equalsIgnoreCase(self)) continue;

            // Strip any zero-width characters around the username if present
            String cleanName = stripZeroWidth(name).trim();
            if (cleanName.isEmpty()) continue;

            String rank = staffRank(info, cleanName);
            if (rank != null) {
                String key = cleanName.toLowerCase(Locale.ROOT);
                currentTab.put(key, info);
                currentRanks.put(key, rank);
            }
        }

        // Check for newly joined or still-online staff
        for (Map.Entry<String, PlayerListEntry> e : currentTab.entrySet()) {
            String key = e.getKey();
            PlayerListEntry info = e.getValue();
            String name = stripZeroWidth(info.getProfile().name()).trim();
            UUID uuid = info.getProfile().id();
            String rank = currentRanks.get(key);
            Identifier skin = info.getSkinTextures() != null && info.getSkinTextures().body() != null
                ? info.getSkinTextures().body().texturePath()
                : StaffFaceSkins.getOrFetch(uuid, name);
            int ping = info.getLatency();
            boolean isSpectator = info.getGameMode() == GameMode.SPECTATOR;
            boolean isVanished = uuid != null && Boolean.FALSE.equals(listedState.get(uuid));
            Long regionSwitchAt = uuid != null ? regionSwitchTimestamps.get(uuid) : null;
            boolean inRegion = regionSwitchAt != null && (nowMs - regionSwitchAt < 600_000L);

            StaffEntry existing = tracked.get(key);
            if (existing == null) {
                StaffEntry created = new StaffEntry(name, rank, skin, ping, nowMs);
                created.spectator = isSpectator;
                created.vanished = isVanished;
                created.inRegion = inRegion;
                if (inRegion) created.regionProvenAt = regionSwitchAt;
                tracked.put(key, created);
                if (primed) {
                    alert(name + " joined", statusSummary(created));
                    if (elderAnimation.get() && (isSpectator || isVanished || inRegion)) {
                        StaffWatchingEffect.get().play(uuid, name, statusSummary(created) + " IN YOUR REGION", 0);
                    }
                }
            } else {
                boolean wasOffline = !existing.online;
                boolean wasSpectator = existing.spectator;
                boolean wasVanished = existing.vanished;
                boolean wasInRegion = existing.inRegion;

                existing.online = true;
                existing.rank = rank;
                if (skin != null) existing.skin = skin;
                existing.ping = ping;
                existing.spectator = isSpectator;
                existing.vanished = isVanished;
                existing.inRegion = inRegion || existing.nearby;
                if (inRegion) existing.regionProvenAt = regionSwitchAt;
                existing.leftAt = 0;

                if (wasOffline) {
                    existing.joinedAt = nowMs;
                    existing.pulse = 1f;
                    if (primed) {
                        alert(name + " joined", statusSummary(existing));
                    }
                } else if (primed) {
                    if (!wasSpectator && isSpectator) {
                        existing.pulse = 1f;
                        alert(name + " entered Spectator", rank);
                        if (elderAnimation.get()) {
                            StaffWatchingEffect.get().play(uuid, name, "SWITCHED TO SPECTATOR MODE", 0);
                        }
                    } else if (!wasVanished && isVanished) {
                        existing.pulse = 1f;
                        alert(name + " vanished", rank);
                        if (elderAnimation.get()) {
                            StaffWatchingEffect.get().play(uuid, name, "HIDDEN FROM TAB (VANISHED)", 0);
                        }
                    } else if (!wasInRegion && inRegion && alertNearby.get()) {
                        existing.pulse = 1f;
                        alert(name + " is in your region!", rank);
                        if (elderAnimation.get()) {
                            StaffWatchingEffect.get().play(uuid, name, "ACTIVE ON YOUR REGION SHARD", 0);
                        }
                    }
                }
            }
        }

        // Check nearby players in world (also catches vanished staff whose player entity is still in world)
        Map<String, PlayerEntity> nearbyPlayers = new HashMap<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            String pName = player.getName().getString();
            if (pName != null) {
                String cleanPName = stripZeroWidth(pName).trim();
                nearbyPlayers.put(cleanPName.toLowerCase(Locale.ROOT), player);
            }
        }

        // Check for staff who left Tab (if still in world, they vanished; otherwise they left)
        for (StaffEntry entry : tracked.values()) {
            String key = entry.name.toLowerCase(Locale.ROOT);
            if (entry.online && !currentTab.containsKey(key)) {
                if (nearbyPlayers.containsKey(key)) {
                    if (!entry.vanished) {
                        entry.vanished = true;
                        entry.pulse = 1f;
                        if (primed) {
                            alert(entry.name + " vanished nearby!", entry.rank);
                            if (elderAnimation.get()) {
                                StaffWatchingEffect.get().play(null, entry.name, "VANISHED NEARBY IN WORLD", 0);
                            }
                        }
                    }
                } else {
                    entry.online = false;
                    entry.nearby = false;
                    entry.vanished = false;
                    entry.spectator = false;
                    entry.inRegion = false;
                    entry.leftAt = nowMs;
                    entry.pulse = 1f;
                    if (primed && alertLeave.get()) {
                        alert(entry.name + " left", entry.rank);
                    }
                }
            }
        }

        if (!primed) {
            primed = true;
            if (!currentTab.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (StaffEntry se : tracked.values()) if (se.online) names.add(se.name);
                if (!names.isEmpty()) alert("Staff online", String.join(", ", names));
            }
        }

        Set<String> seenNearby = new HashSet<>();
        for (StaffEntry entry : tracked.values()) {
            if (!entry.online) {
                entry.nearby = false;
                continue;
            }
            String key = entry.name.toLowerCase(Locale.ROOT);
            PlayerEntity entity = nearbyPlayers.get(key);
            if (entity != null) {
                entry.nearby = true;
                entry.inRegion = true;
                entry.distance = mc.player.distanceTo(entity);
                seenNearby.add(key);
                if (nearbySet.add(key) && alertNearby.get()) {
                    entry.pulse = 1f;
                    alert(entry.name + " is near you", String.format("%.0f blocks away", entry.distance));
                    if (elderAnimation.get()) {
                        StaffWatchingEffect.get().play(entity.getUuid(), entry.name, String.format("NEARBY (%.0f BLOCKS AWAY)", entry.distance), 0);
                    }
                }
            } else {
                entry.nearby = false;
                entry.distance = 0;
            }
        }
        nearbySet.retainAll(seenNearby);

        // Expire left entries after hold duration and anim fade out
        long holdMs = Math.round(leaveHoldSeconds.get() * 1000.0);
        tracked.entrySet().removeIf(en -> {
            StaffEntry se = en.getValue();
            return !se.online && (nowMs - se.leftAt > holdMs) && se.anim <= 0.02f;
        });
    }

    private String statusSummary(StaffEntry entry) {
        if (entry.spectator) return "SPECTATOR";
        if (entry.vanished) return "VANISHED";
        return entry.rank;
    }

    private String staffRank(PlayerListEntry info, String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);

        // 1. Check built-in DonutSMP staff roster
        if (useDonutRoster.get() && DEFAULT_STAFF_LOWER.contains(lowerName)) {
            if (lowerName.equals("drdonutt")) return "OWNER";
            return "ADMIN";
        }

        // 2. Check custom staff-names setting
        for (String listed : staffNames.get()) {
            if (listed != null && listed.trim().equalsIgnoreCase(name)) {
                if (lowerName.equals("drdonutt")) return "OWNER";
                return DEFAULT_STAFF_LOWER.contains(lowerName) ? "ADMIN" : "STAFF";
            }
        }

        // 3. Check DonutSMP leading star prefix in Tab display name / scoreboard team
        StringBuilder text = new StringBuilder();
        if (info.getDisplayName() != null) text.append(info.getDisplayName().getString()).append(' ');

        Team team = info.getScoreboardTeam();
        if (team != null) {
            text.append(team.getPrefix().getString()).append(' ').append(team.getSuffix().getString());
        }

        String fullTabText = text.toString();
        if (checkStarPrefix.get()) {
            if (hasLeadingStar(fullTabText) || learnedStarredNames.contains(lowerName)) {
                learnedStarredNames.add(lowerName);
                return lowerName.equals("drdonutt") ? "OWNER" : "★ STAFF";
            }
        }

        // 4. Check rank words in tab decoration
        if (!checkRanks.get()) return null;

        // Strip Minecraft formatting codes (§x) before matching rank words
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
     * Checks if the Tab list display name starts with a DonutSMP staff star symbol
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

    private void alert(String title, String subtitle) {
        if (notification.get()) {
            EmberNotificationsHud.push(title, subtitle, EmberNotificationsHud.BAD);
            if (chat.get() && !EmberNotificationsHud.shouldInterceptChat()) info("%s (%s)", title, subtitle);
        } else if (chat.get()) {
            info("%s (%s)", title, subtitle);
        }
        if (sound.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.85f, 1.1f);
        }
    }
}
