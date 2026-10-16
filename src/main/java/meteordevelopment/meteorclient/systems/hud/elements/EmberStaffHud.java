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
import meteordevelopment.meteorclient.utils.render.EmberIcons;
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

    private static final Color SKIN_TINT = new Color(255, 255, 255, 255);
    private static final Color SAFE_GREEN = new Color(93, 217, 127, 255);
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
        boolean anyNearby = false;
        for (StaffEntry e : entries) {
            if (e.online) {
                onlineCount++;
                if (e.nearby || e.inRegion || e.spectator || e.vanished) anyNearby = true;
            }
        }

        double s = scale.get();
        double titleScale = 0.90 * s;
        double rowScale = 0.84 * s;
        double badgeScale = 0.68 * s;

        double titleH = renderer.textHeight(true, titleScale);
        double rowTextH = renderer.textHeight(true, rowScale);
        double badgeTextH = renderer.textHeight(true, badgeScale);

        double padX = 10 * s;
        double padY = 8.5 * s;
        double headerH = titleH + 8 * s;
        double rowH = Math.max(rowTextH + 9 * s, 21 * s);
        double avatarSize = 14.5 * s;

        Color accent = EmberPalette.accent();
        Color bg = EmberStrip.background();
        Color bright = new Color(255, 255, 255, 255);
        Color dim = new Color(160, 160, 174, 245);

        Color statusColor = anyNearby ? ALERT_RED : (onlineCount > 0 ? WARN_AMBER : SAFE_GREEN);
        String countLabel = onlineCount + " ONLINE";
        double countBadgeW = renderer.textWidth(countLabel, true, badgeScale) + 10 * s;
        double countBadgeH = badgeTextH + 4 * s;

        double minW = 176 * s;
        double widest = 12 * s + renderer.textWidth("Staff Detector", true, titleScale) + 14 * s + countBadgeW;

        if (entries.isEmpty()) {
            widest = Math.max(widest, 12 * s + renderer.textWidth("No moderators online", true, rowScale));
        } else {
            for (StaffEntry entry : entries) {
                String badgeText = primaryBadge(entry, nowMs);
                double bw = renderer.textWidth(badgeText, true, badgeScale) + 10 * s;
                String stateTag = secondaryBadge(entry, nowMs);
                double sw = stateTag != null ? renderer.textWidth(stateTag, true, badgeScale) + 10 * s + 5 * s : 0;
                double leftW = (showHeads.get() ? avatarSize + 6.5 * s : 10 * s) + renderer.textWidth(entry.name, true, rowScale);
                widest = Math.max(widest, leftW + 16 * s + sw + bw);
            }
        }

        double bodyRowsH = 0;
        if (entries.isEmpty()) {
            bodyRowsH = rowH;
        } else {
            for (StaffEntry entry : entries) {
                bodyRowsH += rowH * Math.max(0.05f, entry.anim);
            }
        }

        double w = Math.max(minW, padX * 2 + widest);
        double h = padY * 2 + headerH + 3 * s + bodyRowsH;
        double radius = 8 * s;

        setSize(w, h);

        if (glow.get()) {
            Color glowCol = onlineCount > 0 ? statusColor : accent;
            renderer.softGlow(x, y, w, h, radius, 10 * s, new Color(glowCol.r, glowCol.g, glowCol.b, onlineCount > 0 ? 135 : 90));
        }

        renderer.dropShadow(x, y, w, h, radius, 20 * s);

        if (onlineCount > 0) {
            double rim = 1.1 * s;
            renderer.roundedQuad(x - rim, y - rim, w + rim * 2, h + rim * 2, radius + rim,
                new Color(statusColor.r, statusColor.g, statusColor.b, anyNearby ? 155 : 95));
        }

        EmberStrip.panel(renderer, x, y, w, h, radius, 1f);

        // Header
        double cy = y + padY;
        double iconSize = 10.5 * s;
        EmberIcons.Glyph headerGlyph = onlineCount > 0 ? EmberIcons.Glyph.WARNING : EmberIcons.Glyph.USER;
        EmberIcons.draw(renderer, headerGlyph, x + padX, cy + (titleH - iconSize) / 2, iconSize, statusColor, bg);
        renderer.text("Staff Detector", x + padX + iconSize + 6 * s, cy, bright, true, titleScale);

        // Header count pill
        double cbx = x + w - padX - countBadgeW;
        double cby = cy + (titleH - countBadgeH) / 2;
        renderer.roundedQuad(cbx, cby, countBadgeW, countBadgeH, countBadgeH / 2,
            new Color(statusColor.r, statusColor.g, statusColor.b, 46));
        double ctw = renderer.textWidth(countLabel, true, badgeScale);
        renderer.text(countLabel, cbx + (countBadgeW - ctw) / 2, cby + (countBadgeH - badgeTextH) / 2, statusColor, true, badgeScale);

        cy += headerH;
        renderer.quad(x + padX, cy - 2 * s, w - padX * 2, 1, new Color(255, 255, 255, 20));
        cy += 3 * s;

        if (entries.isEmpty()) {
            double dotSize = 5.5 * s;
            double dotY = cy + (rowH - dotSize) / 2;
            renderer.roundedQuad(x + padX + 2 * s, dotY, dotSize, dotSize, dotSize / 2, SAFE_GREEN);
            renderer.text("No moderators online", x + padX + dotSize + 8 * s, cy + (rowH - rowTextH) / 2, dim, true, rowScale);
            return;
        }

        for (StaffEntry entry : entries) {
            float a = Math.max(0f, Math.min(1f, entry.anim));
            if (a <= 0.02f) continue;

            double effRowH = rowH * a;
            double rowCenterY = cy + effRowH / 2;

            if (entry.pulse > 0.01f) {
                Color pulseCol = entry.online ? (entry.nearby ? ALERT_RED : SAFE_GREEN) : ALERT_RED;
                renderer.roundedQuad(x + 4 * s, cy + 1 * s, w - 8 * s, effRowH - 2 * s, 5 * s,
                    new Color(pulseCol.r, pulseCol.g, pulseCol.b, (int) (45 * entry.pulse * a)));
            }

            double textX = x + padX;
            if (showHeads.get()) {
                double hy = rowCenterY - avatarSize / 2;
                drawStaffAvatar(renderer, entry.skin, textX, hy, avatarSize, a, bg);
                textX += avatarSize + 6.5 * s;
            } else {
                double dotSize = 5.5 * s;
                Color dotCol = !entry.online ? ALERT_RED : (entry.nearby ? ALERT_RED : SAFE_GREEN);
                renderer.roundedQuad(textX + 1 * s, rowCenterY - dotSize / 2, dotSize, dotSize, dotSize / 2,
                    withAlpha(dotCol, (int) (245 * a)));
                textX += dotSize + 6 * s;
            }

            Color nameColor = entry.online
                ? new Color(255, 255, 255, (int) (255 * a))
                : new Color(165, 165, 175, (int) (200 * a));
            renderer.text(entry.name, textX, rowCenterY - rowTextH / 2, nameColor, true, rowScale);

            // Primary right badge (Rank, SPECTATOR, VANISHED, or LEFT)
            String primary = primaryBadge(entry, nowMs);
            boolean urgentState = !entry.online || entry.nearby || entry.spectator || entry.vanished || entry.inRegion;
            Color primaryCol = urgentState ? ALERT_RED : WARN_AMBER;
            double pbW = renderer.textWidth(primary, true, badgeScale) + 9 * s;
            double pbH = badgeTextH + 3.5 * s;
            double pbX = x + w - padX - pbW;
            double pbY = rowCenterY - pbH / 2;

            renderer.roundedQuad(pbX, pbY, pbW, pbH, pbH / 2, withAlpha(primaryCol, (int) (46 * a)));
            double ptw = renderer.textWidth(primary, true, badgeScale);
            renderer.text(primary, pbX + (pbW - ptw) / 2, pbY + (pbH - badgeTextH) / 2,
                withAlpha(primaryCol, (int) (250 * a)), true, badgeScale);

            // Optional secondary badge (JOINED, IN REGION, or NEARBY)
            String secondary = secondaryBadge(entry, nowMs);
            if (secondary != null) {
                Color secCol = (entry.nearby || entry.inRegion || entry.spectator || entry.vanished) ? ALERT_RED : SAFE_GREEN;
                double sbW = renderer.textWidth(secondary, true, badgeScale) + 9 * s;
                double sbX = pbX - 5 * s - sbW;
                renderer.roundedQuad(sbX, pbY, sbW, pbH, pbH / 2, withAlpha(secCol, (int) (52 * a)));
                double stw = renderer.textWidth(secondary, true, badgeScale);
                renderer.text(secondary, sbX + (sbW - stw) / 2, pbY + (pbH - badgeTextH) / 2,
                    withAlpha(secCol, (int) (255 * a)), true, badgeScale);
            }

            cy += effRowH;
        }
    }

    private String primaryBadge(StaffEntry entry, long nowMs) {
        if (!entry.online) return "LEFT";
        if (entry.spectator) return "SPECTATOR";
        if (entry.vanished) return "VANISHED";
        return entry.rank != null && !entry.rank.isEmpty() ? entry.rank : "STAFF";
    }

    private String secondaryBadge(StaffEntry entry, long nowMs) {
        if (!entry.online) return null;
        if (entry.nearby) return String.format("NEAR %.0fm", entry.distance);
        if (StaffIntel.get().isWatching(entry.uuid)) return "WATCHING";
        if (entry.inRegion) {
            // Hidden staff only show up through a game mode leak, so say how old that proof is.
            long leakAge = StaffIntel.get().leakAgeMs(entry.uuid);
            if (entry.vanished && leakAge >= 0) {
                long s = leakAge / 1000;
                return "IN REGION · " + (s < 60 ? s + "s" : (s / 60) + "m") + " AGO";
            }
            return "IN REGION";
        }
        if (nowMs - entry.joinedAt < 4500) return "JOINED";
        return null;
    }

    private void drawStaffAvatar(HudRenderer renderer, Identifier skin, double hx, double hy, double size, float alpha, Color bg) {
        double r = size * 0.24;
        renderer.roundedQuad(hx, hy, size, size, r, new Color(24, 24, 30, (int) (220 * alpha)));

        if (skin == null) {
            EmberIcons.draw(renderer, EmberIcons.Glyph.USER, hx + size * 0.18, hy + size * 0.18,
                size * 0.64, withAlpha(EmberPalette.accent(), (int) (245 * alpha)), bg);
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
