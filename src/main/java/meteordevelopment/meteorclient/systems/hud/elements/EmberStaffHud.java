package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
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
import meteordevelopment.meteorclient.utils.render.EmberStrip;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

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
    private static final Color WARN_AMBER = new Color(231, 164, 80, 255);
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
        boolean moduleOn = detector != null && detector.isActive();

        if (!isInEditor() && !moduleOn) {
            setSize(0, 0);
            return;
        }

        List<StaffEntry> entries = detector != null ? detector.getTrackedEntries() : new ArrayList<>();
        long nowMs = System.currentTimeMillis();
        long holdMs = detector != null ? Math.round(detector.leaveHoldSeconds.get() * 1000.0) : 4000L;

        for (StaffEntry entry : entries) {
            boolean shouldShow = entry.online || (nowMs - entry.leftAt <= holdMs);
            entry.anim = EmberAnim.approach(entry.anim, shouldShow ? 1f : 0f, dt, 0.065);
            entry.pulse = EmberAnim.approach(entry.pulse, 0f, dt, 0.14);
        }

        if (entries.isEmpty() && isInEditor()) {
            StaffEntry demo1 = new StaffEntry("Mod_Alex", "MOD", null, 42, nowMs - 15000);
            demo1.anim = 1f;
            StaffEntry demo2 = new StaffEntry("Admin_Steve", "ADMIN", null, 28, nowMs - 1000);
            demo2.anim = 1f;
            entries = List.of(demo1, demo2);
        }

        boolean keepEmpty = (detector == null || detector.showEmpty.get()) && showEmpty.get();
        if (entries.isEmpty() && !keepEmpty && !isInEditor()) {
            setSize(0, 0);
            return;
        }

        handleDirectChatDrag();

        int onlineCount = 0;
        boolean anyUrgent = false;
        for (StaffEntry e : entries) {
            if (e.online) {
                onlineCount++;
                if (isUrgent(e)) anyUrgent = true;
            }
        }

        double s = scale.get();
        double titleScale = 0.82 * s;
        double nameScale = 0.78 * s;
        double badgeScale = 0.62 * s;

        double titleH = renderer.textHeight(true, titleScale);
        double nameH = renderer.textHeight(true, nameScale);
        double badgeTextH = renderer.textHeight(false, badgeScale);

        double pad = 9 * s;
        double headerH = Math.max(22 * s, titleH + 6 * s);
        double rowH = Math.max(18 * s, nameH + 6 * s);
        double rowGap = 2 * s;
        double avatarSize = 12 * s;
        double pillH = badgeTextH + 4 * s;
        double radius = 7 * s;

        Color accent = EmberPalette.accent();
        Color bright = EmberPalette.textBright();
        Color dim = EmberPalette.textDim();
        Color statusColor = anyUrgent ? ALERT_RED : (onlineCount > 0 ? WARN_AMBER : SAFE_GREEN);

        String countLabel = onlineCount > 0 ? onlineCount + " ONLINE" : "CLEAR";
        double countDot = 4 * s;
        double countPillW = countDot + 4 * s + renderer.textWidth(countLabel, badgeScale) + 12 * s;

        // Measure.
        double widest = 8 * s + 6 * s + renderer.textWidth("STAFF", true, titleScale) + 16 * s + countPillW;
        if (entries.isEmpty()) {
            widest = Math.max(widest, 12 * s + renderer.textWidth("No staff detected", nameScale));
        } else {
            for (StaffEntry entry : entries) {
                double left = 6 * s + (showHeads.get() ? avatarSize : 6 * s) + 6 * s + renderer.textWidth(entry.name, true, nameScale);
                double rankW = renderer.textWidth(rankLabel(entry), badgeScale) + 10 * s;
                String tag = stateTag(entry, nowMs);
                double tagW = tag != null ? renderer.textWidth(tag, badgeScale) + 10 * s + 4 * s : 0;
                widest = Math.max(widest, left + 14 * s + tagW + rankW + 6 * s);
            }
        }

        double bodyH;
        if (entries.isEmpty()) {
            bodyH = rowH;
        } else {
            bodyH = 0;
            for (StaffEntry entry : entries) bodyH += (rowH + rowGap) * Math.max(0.05f, entry.anim);
            bodyH -= rowGap;
        }

        double w = Math.max(168 * s, pad * 2 + widest);
        double h = pad + headerH + 1 + 6 * s + bodyH + pad;

        setSize(w, h);

        if (glow.get()) renderer.softGlow(x, y, w, h, radius, 10 * s, new Color(accent.r, accent.g, accent.b, onlineCount > 0 ? 120 : 80));
        renderer.dropShadow(x, y, w, h, radius, 20 * s);
        EmberStrip.panel(renderer, x, y, w, h, radius, 1f);

        // Accent spine down the left edge.
        renderer.roundedQuad(x + 1 * s, y + radius, 2 * s, h - radius * 2, 1 * s, new Color(accent.r, accent.g, accent.b, 200));

        // Header.
        double cy = y + pad;
        double dotSize = 6 * s;
        double dotX = x + pad, dotY = cy + (headerH - dotSize) / 2;
        renderer.softGlow(dotX, dotY, dotSize, dotSize, dotSize / 2, 5 * s, new Color(accent.r, accent.g, accent.b, 150));
        renderer.roundedQuad(dotX, dotY, dotSize, dotSize, dotSize / 2, accent);
        renderer.text("STAFF", dotX + dotSize + 6 * s, cy + (headerH - titleH) / 2, bright, true, titleScale);

        double cpx = x + w - pad - countPillW;
        double cpy = cy + (headerH - pillH) / 2;
        double rim = Math.max(1, s);
        renderer.roundedQuad(cpx - rim, cpy - rim, countPillW + rim * 2, pillH + rim * 2, pillH / 2 + rim, withAlpha(statusColor, 70));
        renderer.roundedQuad(cpx, cpy, countPillW, pillH, pillH / 2, EmberPalette.control());
        double pulse = onlineCount > 0 ? 0.55 + 0.45 * Math.sin(nowMs / 220.0) : 1;
        renderer.roundedQuad(cpx + 6 * s, cpy + (pillH - countDot) / 2, countDot, countDot, countDot / 2, withAlpha(statusColor, (int) (255 * pulse)));
        renderer.text(countLabel, cpx + 6 * s + countDot + 4 * s, cpy + (pillH - badgeTextH) / 2, onlineCount > 0 ? bright : SAFE_GREEN, false, badgeScale);

        cy += headerH;
        Color divider = EmberPalette.divider();
        renderer.quad(x + pad, cy, w - pad * 2, 1, new Color(divider.r, divider.g, divider.b, 150));
        double lead = Math.min((w - pad * 2) * 0.35, 50 * s);
        Color clear = new Color(accent.r, accent.g, accent.b, 0);
        Color hot = new Color(accent.r, accent.g, accent.b, 190);
        renderer.quad(x + pad, cy, lead, 1, hot, clear, clear, hot);
        cy += 1 + 6 * s;

        if (entries.isEmpty()) {
            double d = 5 * s;
            renderer.softGlow(x + pad + 2 * s, cy + (rowH - d) / 2, d, d, d / 2, 3 * s, withAlpha(SAFE_GREEN, 120));
            renderer.roundedQuad(x + pad + 2 * s, cy + (rowH - d) / 2, d, d, d / 2, SAFE_GREEN);
            renderer.text("No staff detected", x + pad + d + 8 * s, cy + (rowH - nameH) / 2, dim, true, nameScale);
            return;
        }

        for (StaffEntry entry : entries) {
            float a = Math.max(0f, Math.min(1f, entry.anim));
            if (a <= 0.02f) continue;

            double effRowH = rowH * a;
            double rx = x + pad - 3 * s, rw = w - pad * 2 + 6 * s;
            double rowCenterY = cy + effRowH / 2;
            Color barColor = !entry.online ? LEFT_GREY : (isUrgent(entry) ? ALERT_RED : WARN_AMBER);

            // Row card, status bar, and a flash when the entry changes.
            renderer.roundedQuad(rx, cy, rw, effRowH, 4 * s, withAlpha(EmberPalette.control(), (int) (120 * a)));
            if (entry.pulse > 0.01f) {
                renderer.roundedQuad(rx, cy, rw, effRowH, 4 * s, withAlpha(barColor, (int) (50 * entry.pulse * a)));
            }
            renderer.roundedQuad(rx, cy + 2 * s * a, 2 * s, Math.max(0, effRowH - 4 * s * a), 1 * s, withAlpha(barColor, (int) (235 * a)));

            double textX = rx + 6 * s;
            if (showHeads.get()) {
                drawStaffAvatar(renderer, entry.skin, textX, rowCenterY - avatarSize / 2, avatarSize, a, accent);
                textX += avatarSize + 6 * s;
            } else {
                double d = 5 * s;
                renderer.roundedQuad(textX, rowCenterY - d / 2, d, d, d / 2, withAlpha(barColor, (int) (245 * a)));
                textX += d + 6 * s;
            }

            Color nameColor = entry.online ? withAlpha(bright, (int) (255 * a)) : withAlpha(dim, (int) (210 * a));
            renderer.text(entry.name, textX, rowCenterY - nameH / 2, nameColor, true, nameScale);

            // Rank pill in the theme accent, flush right.
            String rank = rankLabel(entry);
            double rankW = renderer.textWidth(rank, badgeScale) + 10 * s;
            double pillX = rx + rw - 5 * s - rankW;
            double pillY = rowCenterY - pillH / 2;
            renderer.roundedQuad(pillX - rim, pillY - rim, rankW + rim * 2, pillH + rim * 2, pillH / 2 + rim, withAlpha(accent, (int) (95 * a)));
            renderer.roundedQuad(pillX, pillY, rankW, pillH, pillH / 2, blend(withAlpha(EmberPalette.control(), (int) (255 * a)), accent, 0.13));
            Color rankText = new Color((accent.r * 3 + 255 * 2) / 5, (accent.g * 3 + 255 * 2) / 5, (accent.b * 3 + 255 * 2) / 5, (int) (255 * a));
            renderer.text(rank, pillX + 5 * s, pillY + (pillH - badgeTextH) / 2, rankText, false, badgeScale);

            // State tag: what the detector knows about where they are.
            String tag = stateTag(entry, nowMs);
            if (tag != null) {
                Color tagColor = tagColor(entry, nowMs);
                float tagAlpha = a;
                if (tag.equals("NEW")) tagAlpha *= (float) (0.6 + 0.4 * Math.sin(nowMs / 160.0));
                double tagW = renderer.textWidth(tag, badgeScale) + 10 * s;
                double tagX = pillX - 4 * s - tagW;
                renderer.roundedQuad(tagX, pillY, tagW, pillH, pillH / 2, withAlpha(tagColor, (int) (48 * tagAlpha)));
                renderer.text(tag, tagX + 5 * s, pillY + (pillH - badgeTextH) / 2, withAlpha(tagColor, (int) (255 * tagAlpha)), false, badgeScale);
            }

            cy += (rowH + rowGap) * a;
        }
    }

    private static boolean isUrgent(StaffEntry entry) {
        return entry.nearby || entry.inRegion || entry.spectator || entry.vanished;
    }

    private static String rankLabel(StaffEntry entry) {
        return entry.rank != null && !entry.rank.isEmpty() ? entry.rank.toUpperCase() : "STAFF";
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

    private Color tagColor(StaffEntry entry, long nowMs) {
        if (!entry.online) return LEFT_GREY;
        if (isUrgent(entry) || StaffIntel.get().isWatching(entry.uuid)) return ALERT_RED;
        return SAFE_GREEN;
    }

    private void drawStaffAvatar(HudRenderer renderer, Identifier skin, double hx, double hy, double size, float alpha, Color accent) {
        double r = size * 0.22;
        double frame = Math.max(1, size / 12);
        renderer.roundedQuad(hx - frame, hy - frame, size + frame * 2, size + frame * 2, r + frame, withAlpha(accent, (int) (90 * alpha)));
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

    private static Color blend(Color base, Color over, double t) {
        return new Color(
            (int) (base.r + (over.r - base.r) * t),
            (int) (base.g + (over.g - base.g) * t),
            (int) (base.b + (over.b - base.b) * t),
            base.a);
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
