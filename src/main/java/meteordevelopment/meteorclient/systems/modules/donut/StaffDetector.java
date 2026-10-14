package meteordevelopment.meteorclient.systems.modules.donut;

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
import net.minecraft.scoreboard.Team;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.regex.Pattern;

public class StaffDetector extends Module {
    public static final class StaffEntry {
        public final String name;
        public String rank;
        public Identifier skin;
        public int ping;
        public boolean online;
        public boolean nearby;
        public double distance;
        public long joinedAt;
        public long leftAt;
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

    private final Setting<List<String>> staffNames = sgGeneral.add(new StringListSetting.Builder()
        .name("staff-names")
        .description("Usernames that always count as staff.")
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
        .description("Highlight when a staff member comes into render distance.")
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
        .description("Play a sound when staff join, leave, or come near you.")
        .defaultValue(true)
        .build()
    );

    /** Staff currently online or recently left (keyed by lowercase name). */
    private final Map<String, StaffEntry> tracked = new LinkedHashMap<>();
    private final Set<String> nearbySet = new HashSet<>();
    private boolean primed;
    private long lastScanMs;

    public StaffDetector() {
        super(Categories.Donut, "staff-detector", "Displays an on-screen UI of online moderators that updates as they join or leave.");
    }

    @Override
    public void onActivate() {
        tracked.clear();
        nearbySet.clear();
        primed = false;
        lastScanMs = 0;

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
            nearbySet.clear();
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
            String name = info.getProfile().name();
            if (name == null || name.equalsIgnoreCase(self)) continue;

            String rank = staffRank(info, name);
            if (rank != null) {
                String key = name.toLowerCase(Locale.ROOT);
                currentTab.put(key, info);
                currentRanks.put(key, rank);
            }
        }

        // Check for newly joined or still-online staff
        for (Map.Entry<String, PlayerListEntry> e : currentTab.entrySet()) {
            String key = e.getKey();
            PlayerListEntry info = e.getValue();
            String name = info.getProfile().name();
            String rank = currentRanks.get(key);
            Identifier skin = info.getSkinTextures() != null && info.getSkinTextures().body() != null
                ? info.getSkinTextures().body().texturePath()
                : null;
            int ping = info.getLatency();

            StaffEntry existing = tracked.get(key);
            if (existing == null) {
                StaffEntry created = new StaffEntry(name, rank, skin, ping, nowMs);
                tracked.put(key, created);
                if (primed) {
                    alert(name + " joined", rank);
                }
            } else {
                boolean wasOffline = !existing.online;
                existing.online = true;
                existing.rank = rank;
                if (skin != null) existing.skin = skin;
                existing.ping = ping;
                existing.leftAt = 0;
                if (wasOffline) {
                    existing.joinedAt = nowMs;
                    existing.pulse = 1f;
                    if (primed) {
                        alert(name + " joined", rank);
                    }
                }
            }
        }

        // Check for staff who left
        for (StaffEntry entry : tracked.values()) {
            String key = entry.name.toLowerCase(Locale.ROOT);
            if (entry.online && !currentTab.containsKey(key)) {
                entry.online = false;
                entry.nearby = false;
                entry.leftAt = nowMs;
                entry.pulse = 1f;
                if (primed && alertLeave.get()) {
                    alert(entry.name + " left", entry.rank);
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

        // Check nearby players in world
        Map<String, PlayerEntity> nearbyPlayers = new HashMap<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            String pName = player.getName().getString();
            if (pName != null) nearbyPlayers.put(pName.toLowerCase(Locale.ROOT), player);
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
                entry.distance = mc.player.distanceTo(entity);
                seenNearby.add(key);
                if (nearbySet.add(key) && alertNearby.get()) {
                    entry.pulse = 1f;
                    alert(entry.name + " is near you", String.format("%.0f blocks away", entry.distance));
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

    private String staffRank(PlayerListEntry info, String name) {
        for (String listed : staffNames.get()) {
            if (listed.trim().equalsIgnoreCase(name)) return "STAFF";
        }

        if (!checkRanks.get()) return null;

        StringBuilder text = new StringBuilder();
        if (info.getDisplayName() != null) text.append(info.getDisplayName().getString()).append(' ');

        Team team = info.getScoreboardTeam();
        if (team != null) {
            text.append(team.getPrefix().getString()).append(' ').append(team.getSuffix().getString());
        }

        // Strip Minecraft formatting codes (§x) before matching rank words
        String clean = text.toString().replaceAll("(?i)\\u00a7[0-9A-FK-ORX]", "");

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
