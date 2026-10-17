package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.donut.StaffDetector;
import meteordevelopment.meteorclient.systems.modules.donut.StaffDetector.StaffEntry;
import meteordevelopment.meteorclient.systems.modules.donut.StaffIntel;
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.EmberHudCards;
import meteordevelopment.meteorclient.utils.render.EmberIcons;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Movable on-screen Staff Detector panel showing online moderators and live join/leave status.
 */
public class EmberStaffHud extends HudElement {
    public static final HudElementInfo<EmberStaffHud> INFO = new HudElementInfo<>(
        Hud.GROUP,
        "ember-staff",
        "Movable on-screen UI showing online moderators and live join/leave updates.",
        EmberStaffHud::new
    );

    private static final Color SAFE_GREEN = new Color(93, 217, 127, 255);
    private static final Color LEFT_GREY = new Color(140, 140, 152, 255);
    private static final Color ALERT_RED = new Color(222, 86, 86, 255);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the Staff Detector panel.")
        .defaultValue(1.05)
        .min(0.5)
        .max(3.0)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<Boolean> showHeads = sgGeneral.add(new BoolSetting.Builder()
        .name("show-heads")
        .description("Display each moderator's skin face.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showEmpty = sgGeneral.add(new BoolSetting.Builder()
        .name("show-when-empty")
        .description("Keep the panel visible when 0 moderators are online.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> glow = sgGeneral.add(new BoolSetting.Builder()
        .name("glow")
        .description("Soft glow behind the Staff Detector panel.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxRows = sgGeneral.add(new IntSetting.Builder()
        .name("visible-rows").description("Maximum staff rows. Nearby and hidden staff appear first.")
        .defaultValue(6).range(1, 12).sliderRange(1, 12).build());

    private final Map<String, Double> rowPositions = new HashMap<>();
    private double shownWidth, shownHeight;
    private final EmberAnim.Clock clock = new EmberAnim.Clock();
    private boolean directDragging;
    private int dragLastX, dragLastY;
    private boolean wasLeftDown;

    public EmberStaffHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        float dt = clock.tick();
        StaffDetector detector = Modules.get() != null ? Modules.get().get(StaffDetector.class) : null;
        if (!isInEditor() && (detector == null || !detector.isActive())) {
            setSize(0, 0);
            rowPositions.clear(); shownWidth = shownHeight = 0;
            return;
        }
        List<StaffEntry> entries = detector != null ? detector.getTrackedEntries() : new ArrayList<>();
        long now = System.currentTimeMillis();
        long hold = detector != null ? Math.round(detector.leaveHoldSeconds.get() * 1000) : 4000;
        for (StaffEntry entry : entries) {
            boolean visible = entry.online || now - entry.leftAt <= hold;
            entry.anim = EmberAnim.approach(entry.anim, visible ? 1f : 0f, dt, 0.12);
            entry.pulse = EmberAnim.approach(entry.pulse, 0f, dt, 0.2);
        }
        entries.removeIf(e -> !e.online && now - e.leftAt > hold && e.anim <= 0.02f);
        if (entries.isEmpty() && isInEditor()) {
            StaffEntry mod = new StaffEntry("Mod_Alex", "MOD", null, 42, now - 15000);
            StaffEntry admin = new StaffEntry("Admin_Steve", "ADMIN", null, 28, now - 1000);
            mod.anim = admin.anim = 1;
            entries = new ArrayList<>(List.of(mod, admin));
        }
        if (entries.isEmpty() && !((detector == null || detector.showEmpty.get()) && showEmpty.get()) && !isInEditor()) {
            setSize(0, 0); rowPositions.clear(); shownWidth = shownHeight = 0;
            return;
        }
        handleDirectChatDrag();
        int online = 0;
        boolean urgent = false;
        for (StaffEntry e : entries) if (e.online) { online++; urgent |= isUrgent(e); }
        entries.sort(Comparator.comparingInt((StaffEntry e) -> !e.online ? 2 : isUrgent(e) ? 0 : 1)
            .thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));

        double s = scale.get(), titleScale = 0.83 * s, nameScale = 0.78 * s, stateScale = 0.61 * s, badgeScale = 0.58 * s;
        double pad = 12 * s, avatar = 23 * s, gap = 5 * s;
        double titleH = renderer.textHeight(false, titleScale), nameH = renderer.textHeight(false, nameScale);
        double stateH = renderer.textHeight(false, stateScale), headerH = Math.max(30 * s, titleH + 12 * s);
        double rowH = Math.max(37 * s, nameH + stateH + 12 * s);
        int capacity = Math.min(maxRows.get(), Math.max(1, (int) ((Utils.getWindowHeight() - headerH - pad * 4) / (rowH + gap))));
        int rows = Math.min(capacity, entries.size()), hidden = entries.size() - rows;
        double footerH = hidden > 0 ? stateH + 10 * s : 0;
        double widest = 216 * s;
        for (int i = 0; i < rows; i++) {
            StaffEntry e = entries.get(i);
            double rankW = Math.min(76 * s, EmberHudCards.badgeWidth(renderer, rankLabel(e), badgeScale, s));
            widest = Math.max(widest, pad * 2 + 10 * s + (showHeads.get() ? avatar + 10 * s : 0)
                + renderer.textWidth(e.name, false, nameScale) + rankW + 12 * s);
        }
        double maxW = Math.min(326 * s, Math.max(180, Utils.getWindowWidth() - 24 * s));
        double wantedW = Math.min(widest, maxW);
        shownWidth = shownWidth == 0 ? wantedW : EmberAnim.approach(shownWidth, wantedW, dt, 0.12);
        double w = shownWidth;
        double bodyY = pad + headerH + 7 * s;
        double wantedH = bodyY + (rows == 0 ? rowH : rows * (rowH + gap) - gap) + footerH + pad;
        double lowestRow = 0;
        var retained = new HashSet<String>();
        for (int i = 0; i < rows; i++) {
            StaffEntry e = entries.get(i);
            retained.add(e.name);
            double target = bodyY + i * (rowH + gap);
            double rowY = EmberAnim.approach(rowPositions.getOrDefault(e.name, target + 6 * s), target, dt, 0.12);
            rowPositions.put(e.name, rowY);
            lowestRow = Math.max(lowestRow, rowY + rowH + pad + footerH);
        }
        rowPositions.keySet().retainAll(retained);
        shownHeight = shownHeight == 0 ? wantedH : EmberAnim.approach(shownHeight, wantedH, dt, 0.12);
        shownHeight = Math.max(shownHeight, lowestRow);
        double h = shownHeight;
        setSize(w, h);

        Color accent = EmberPalette.accent(), bright = EmberPalette.textBright(), dim = EmberPalette.textDim();
        Color status = urgent ? ALERT_RED : online > 0 ? accent : SAFE_GREEN;
        EmberHudCards.surface(renderer, x, y, w, h, s, 1, glow.get());
        double headerY = y + pad;
        EmberHudCards.icon(renderer, EmberIcons.Glyph.USER, x + pad, headerY + (headerH - 24 * s) / 2, 24 * s, accent, 1);
        String count = online + " online";
        double countW = EmberHudCards.badgeWidth(renderer, count, badgeScale, s);
        double countH = renderer.textHeight(false, badgeScale) + 6 * s;
        double countX = x + w - pad - countW;
        renderer.text(EmberHudCards.fit(renderer, "Staff detector", titleScale, countX - x - pad - 39 * s),
            x + pad + 33 * s, headerY + (headerH - titleH) / 2, bright, false, titleScale);
        EmberHudCards.badge(renderer, count, countX, headerY + (headerH - countH) / 2, badgeScale, s, status, 1);
        renderer.quad(x + pad, headerY + headerH, w - pad * 2, Math.max(0.6, s * 0.65), new Color(255, 255, 255, 20));

        if (rows == 0) {
            double iconY = y + bodyY + (rowH - 23 * s) / 2;
            EmberHudCards.icon(renderer, EmberIcons.Glyph.CHECK, x + pad, iconY, 23 * s, accent, 0.8);
            double textW = w - pad * 2 - 33 * s;
            renderer.text(EmberHudCards.fit(renderer, "No staff detected", nameScale, textW), x + pad + 33 * s,
                y + bodyY + 4 * s, EmberHudCards.ink(dim, 1), false, nameScale);
            renderer.text(EmberHudCards.fit(renderer, "Monitoring this session", stateScale, textW), x + pad + 33 * s,
                y + bodyY + 8 * s + nameH, dim, false, stateScale);
        }
        for (int i = 0; i < rows; i++) {
            StaffEntry e = entries.get(i);
            double a = Math.clamp(e.anim, 0, 1);
            if (a <= 0.02) continue;
            double ry = y + rowPositions.get(e.name), rx = x + pad - 3 * s, rw = w - pad * 2 + 6 * s;
            Color stateColor = !e.online ? LEFT_GREY : isUrgent(e) ? ALERT_RED : accent;
            renderer.roundedQuad(rx, ry, rw, rowH, 8 * s, new Color(255, 255, 255, (int) ((7 + e.pulse * 9) * a)));
            double textX = rx + 7 * s;
            if (showHeads.get()) {
                double ax = textX, ay = ry + (rowH - avatar) / 2;
                drawStaffAvatar(renderer, e.skin, ax, ay, avatar, (float) a, accent);
                // Skin post-tasks draw later; keep the status mark beside, not over, the face.
                renderer.roundedQuad(ax + avatar + 2 * s, ay + avatar - 5 * s, 4 * s, 4 * s, 2 * s, EmberHudCards.alpha(stateColor, a));
                textX += avatar + 11 * s;
            }
            String rank = EmberHudCards.fit(renderer, rankLabel(e), badgeScale, Math.min(64 * s, rw * 0.28));
            double rankW = EmberHudCards.badgeWidth(renderer, rank, badgeScale, s);
            double rankX = rx + rw - 7 * s - rankW;
            double textTop = ry + (rowH - nameH - stateH - 3 * s) / 2;
            String name = EmberHudCards.fit(renderer, e.name, nameScale, rankX - textX - 9 * s);
            renderer.text(name, textX, textTop, EmberHudCards.alpha(e.online ? bright : dim, a), false, nameScale);
            EmberHudCards.badge(renderer, rank, rankX, ry + (rowH - countH) / 2, badgeScale, s, accent, a * (e.online ? 1 : 0.65));
            String tag = stateTag(e, now);
            String state = tag == null ? "Online" : tag;
            state = EmberHudCards.fit(renderer, state, stateScale, rankX - textX - 9 * s);
            renderer.text(state, textX, textTop + nameH + 3 * s,
                EmberHudCards.alpha(isUrgent(e) && e.online ? stateColor : dim, a), false, stateScale);
        }
        if (hidden > 0) {
            renderer.text("+" + hidden + " more tracked", x + pad + 7 * s, y + h - pad - stateH,
                dim, false, stateScale);
        }
    }

    private static boolean isUrgent(StaffEntry entry) {
        return entry.nearby || entry.inRegion || entry.spectator || entry.vanished || StaffIntel.get().isWatching(entry.uuid);
    }

    private static String rankLabel(StaffEntry entry) {
        return entry.rank != null && !entry.rank.isEmpty() ? entry.rank.toUpperCase(Locale.ROOT) : "STAFF";
    }

    private String stateTag(StaffEntry entry, long nowMs) {
        if (!entry.online) return "LEFT";
        if (entry.nearby) return String.format("NEAR %.0fm", entry.distance);
        if (StaffIntel.get().isWatching(entry.uuid)) return "WATCHING";
        if (entry.spectator) return "SPECTATOR";
        if (entry.inRegion) {
            // Hidden staff only show up through a game mode leak, so say how old that proof is.
            long leakAge = StaffIntel.get().leakAgeMs(entry.uuid);
            if (entry.vanished && leakAge >= 0) {
                long s = leakAge / 1000;
                return "IN REGION · " + (s < 60 ? s + "s" : (s / 60) + "m") + " AGO";
            }
            return "IN REGION";
        }
        if (entry.vanished) return "VANISHED";
        if (nowMs - entry.joinedAt < 5000) return "NEW";
        return null;
    }

    private void drawStaffAvatar(HudRenderer renderer, Identifier skin, double hx, double hy, double size, float alpha, Color accent) {
        double r = size * 0.22;
        double frame = Math.max(1, size / 12);
        renderer.roundedQuad(hx - frame, hy - frame, size + frame * 2, size + frame * 2, r + frame, withAlpha(accent, (int) (36 * alpha)));
        renderer.roundedQuad(hx, hy, size, size, r, new Color(24, 24, 30, (int) (220 * alpha)));

        if (skin == null) {
            // Diamond badge when the skin hasn't loaded.
            double cx = hx + size / 2, cy = hy + size / 2, d = size * 0.3;
            Color c = withAlpha(accent, (int) (235 * alpha));
            renderer.triangle(cx - d, cy, cx + d, cy, cx, cy - d, c);
            renderer.triangle(cx - d, cy, cx, cy + d, cx + d, cy, c);
            return;
        }

        Color tint = new Color(255, 255, 255, (int) (255 * alpha));
        renderer.post(() -> {
            renderer.roundedTextureRegion(skin, hx, hy, size, r, 0.125, 0.125, 0.25, 0.25, tint);
            renderer.roundedTextureRegion(skin, hx, hy, size, r, 0.625, 0.125, 0.75, 0.25, tint);
        });
    }

    /** Allows dragging the Staff Detector widget directly when the chat screen is open. */
    private void handleDirectChatDrag() {
        if (mc == null || mc.getWindow() == null || !(mc.currentScreen instanceof ChatScreen)) {
            directDragging = false;
            wasLeftDown = false;
            return;
        }

        long handle = mc.getWindow().getHandle();
        boolean leftDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        double scaleFactor = mc.getWindow().getScaleFactor();
        int mx = (int) (mc.mouse.getX() * scaleFactor);
        int my = (int) (mc.mouse.getY() * scaleFactor);

        if (leftDown && !wasLeftDown) {
            if (mx >= x && mx <= x + getWidth() && my >= y && my <= y + getHeight()) {
                directDragging = true;
                dragLastX = mx;
                dragLastY = my;
            }
        } else if (leftDown && directDragging) {
            int dx = mx - dragLastX;
            int dy = my - dragLastY;
            if (dx != 0 || dy != 0) {
                move(dx, dy);
                dragLastX = mx;
                dragLastY = my;
            }
        } else if (!leftDown) {
            directDragging = false;
        }

        wasLeftDown = leftDown;
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.r, c.g, c.b, Math.max(0, Math.min(255, alpha)));
    }
}
