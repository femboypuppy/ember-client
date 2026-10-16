package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

import java.util.UUID;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Staff alert overlay, in three levels so the alarm matches the threat:
 * <ul>
 *     <li>{@link Level#REGION}: a staff member is on your region's server. A small card slides in at the top with a
 *     soft bell. They could be anywhere in a 25k block region, so this is information, not an alarm.</li>
 *     <li>{@link Level#HIDDEN}: a staff member is vanished or spectating on your region's server. Same card in amber
 *     with a lower two-note bell.</li>
 *     <li>{@link Level#WATCHING}: a staff member is near you. Their face fades in and out over the screen the way
 *     the elder guardian does, the screen edges darken, and the card turns red.</li>
 * </ul>
 * A lower level never interrupts a higher one that is still on screen.
 */
public final class StaffWatchingEffect {
    private static final StaffWatchingEffect INSTANCE = new StaffWatchingEffect();

    private static final float SLIDE_SECONDS = 0.35f;
    private static final float FADE_OUT_SECONDS = 0.45f;
    private static final float GHOST_SECONDS = 2.2f;
    private static final float VIGNETTE_SECONDS = 3.0f;

    public enum Level {
        REGION("IN REGION", new Color(110, 165, 255), 6f),
        HIDDEN("HIDDEN", new Color(255, 172, 64), 8f),
        WATCHING("NEAR YOU", new Color(255, 84, 84), 10f);

        final String tag;
        final Color accent;
        final float seconds;

        Level(String tag, Color accent, float seconds) {
            this.tag = tag;
            this.accent = accent;
            this.seconds = seconds;
        }

        public static Level of(StaffIntel.AlertKind kind) {
            return switch (kind) {
                case REGION -> REGION;
                case SPECTATOR, VANISHED -> HIDDEN;
                case NEARBY -> WATCHING;
            };
        }
    }

    private UUID watcherId;
    private String watcherName = "";
    private String title = "";
    private String subtitle = "";
    private Level level = Level.REGION;
    private StaffFaceSkins.Skin skin;
    private long startNanos;
    private boolean active;
    private int ticks;

    private final Color work = new Color();
    private final Color clear = new Color(0, 0, 0, 0);

    private StaffWatchingEffect() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public static StaffWatchingEffect get() {
        return INSTANCE;
    }

    public void play(UUID id, String name, Level level, String subtitle) {
        if (mc == null || name == null || name.isBlank()) return;
        if (active && level.ordinal() < this.level.ordinal() && elapsed() < this.level.seconds) return;

        String clean = name.trim();
        StaffFaceSkins.preload(clean);

        this.watcherId = id;
        this.watcherName = clean;
        this.level = level;
        this.title = switch (level) {
            case REGION -> clean + " is in your region";
            case HIDDEN -> clean + " is hidden in your region";
            case WATCHING -> clean + " is near you";
        };
        this.subtitle = subtitle == null ? "" : subtitle;
        this.skin = StaffFaceSkins.get(id, clean);
        this.startNanos = System.nanoTime();
        this.ticks = 0;
        this.active = true;

        playSound(0);
    }

    public void cancel() {
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    private void playSound(int tick) {
        if (mc.player == null) return;

        switch (level) {
            case REGION -> {
                if (tick == 0) mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 0.55f, 1.0f);
            }
            case HIDDEN -> {
                if (tick == 0) mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 0.7f, 0.84f);
                if (tick == 4) mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 0.7f, 0.63f);
            }
            case WATCHING -> {
                if (tick == 0) mc.player.playSound(SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 1.0f);
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) {
            active = false;
            return;
        }
        if (!active) return;

        ticks++;
        playSound(ticks);
        if (elapsed() >= level.seconds) active = false;
    }

    public void render(HudRenderer renderer) {
        if (!active) return;

        float t = elapsed();
        if (t >= level.seconds) {
            active = false;
            return;
        }

        double sw = Utils.getWindowWidth();
        double sh = Utils.getWindowHeight();
        if (sw <= 0 || sh <= 0) return;

        if (skin == null || skin.pending()) skin = StaffFaceSkins.get(watcherId, watcherName);

        if (level == Level.WATCHING) {
            if (t < VIGNETTE_SECONDS) drawVignette(renderer, sw, sh, t);
            if (t < GHOST_SECONDS) drawGhost(renderer, sw, sh, t / GHOST_SECONDS);
        }

        drawCard(renderer, sw, sh, t);
    }

    /** Dark red falling in from the screen edges, strongest just after the alert starts. */
    private void drawVignette(HudRenderer r, double sw, double sh, float t) {
        float in = clamp(t / 0.25f);
        float out = 1f - clamp((t - 0.25f) / (VIGNETTE_SECONDS - 0.25f));
        int a = Math.round(150 * in * out * out);
        if (a <= 0) return;

        work.set(70, 0, 6, a);
        double band = Math.min(sw, sh) * 0.28;
        r.quad(0, 0, sw, band, work, work, clear, clear);
        r.quad(0, sh - band, sw, band, clear, clear, work, work);
        r.quad(0, 0, band, sh, work, clear, clear, work);
        r.quad(sw - band, 0, band, sh, clear, work, work, clear);
    }

    /**
     * The elder guardian curse: a large, half-transparent face that swells in, hangs for a moment while slowly
     * drifting closer, then fades away. No trails or tints, the skin is drawn as it is.
     */
    private void drawGhost(HudRenderer r, double sw, double sh, float p) {
        Identifier texture = skin != null ? skin.texture() : null;
        if (texture == null) return;

        float alpha = (float) Math.sin(Math.PI * p);
        alpha = alpha * alpha * 0.62f;
        if (alpha <= 0.01f) return;

        double size = sh * 0.42 * (0.9 + 0.16 * easeOutCubic(p));
        double x = (sw - size) / 2.0;
        double y = (sh - size) / 2.0 - sh * 0.04;
        int a = Math.round(alpha * 255);

        r.post(() -> {
            Color tint = new Color(255, 255, 255, a);
            r.textureRegion(texture, x, y, size, size, 0.125, 0.125, 0.25, 0.25, tint);
            double hat = size * 1.125;
            r.textureRegion(texture, x - (hat - size) / 2, y - (hat - size) / 2, hat, hat, 0.625, 0.125, 0.75, 0.25, tint);
        });
    }

    private void drawCard(HudRenderer r, double sw, double sh, float t) {
        float in = easeOutCubic(clamp(t / SLIDE_SECONDS));
        float out = clamp((level.seconds - t) / FADE_OUT_SECONDS);
        float alpha = Math.min(in, out);
        if (alpha <= 0.01f) return;

        double s = Math.max(0.85, Math.min(1.6, sh / 1000.0));
        double pad = 10 * s;
        double face = 28 * s;
        double gap = 10 * s;
        double titleScale = 0.95 * s;
        double subScale = 0.72 * s;
        double tagScale = 0.62 * s;

        double titleW = r.textWidth(title, false, titleScale);
        double subW = subtitle.isEmpty() ? 0 : r.textWidth(subtitle, false, subScale);
        double tagW = r.textWidth(level.tag, false, tagScale);
        double tagPad = 5 * s;

        double w = pad + face + gap + Math.max(titleW, subW) + gap + tagW + tagPad * 2 + pad;
        double h = pad * 2 + face;
        double radius = 9 * s;
        double x = (sw - w) / 2;
        double y = 16 * s - (1 - in) * (h + 24 * s);

        Color accent = level.accent;

        r.dropShadow(x, y, w, h, radius, 16 * s, alpha * 0.9f);
        r.roundedQuad(x - 1, y - 1, w + 2, h + 2, radius + 1, alpha(work, accent, 70 * alpha));
        r.roundedQuad(x, y, w, h, radius, new Color(16, 16, 20, Math.round(235 * alpha)));

        // Face
        double fx = x + pad;
        double fy = y + pad;
        double faceRadius = face * 0.2;
        r.roundedQuad(fx, fy, face, face, faceRadius, new Color(40, 40, 46, Math.round(255 * alpha)));
        Identifier texture = skin != null ? skin.texture() : null;
        if (texture != null) {
            Color faceTint = new Color(255, 255, 255, Math.round(255 * alpha));
            r.post(() -> {
                r.roundedTextureRegion(texture, fx, fy, face, faceRadius, 0.125, 0.125, 0.25, 0.25, faceTint);
                r.roundedTextureRegion(texture, fx, fy, face, faceRadius, 0.625, 0.125, 0.75, 0.25, faceTint);
            });
        }

        // Text
        double tx = fx + face + gap;
        double titleH = r.textHeight(false, titleScale);
        double subH = r.textHeight(false, subScale);
        double block = subtitle.isEmpty() ? titleH : titleH + 3 * s + subH;
        double ty = y + (h - block) / 2;
        r.text(title, tx, ty, new Color(245, 245, 248, Math.round(255 * alpha)), false, titleScale);
        if (!subtitle.isEmpty()) {
            r.text(subtitle, tx, ty + titleH + 3 * s, new Color(160, 160, 172, Math.round(255 * alpha)), false, subScale);
        }

        // Level tag on the right
        double tagH = r.textHeight(false, tagScale) + tagPad;
        double tagX = x + w - pad - tagW - tagPad * 2;
        double tagY = y + (h - tagH) / 2;
        r.roundedQuad(tagX, tagY, tagW + tagPad * 2, tagH, tagH / 2, alpha(new Color(), accent, 45 * alpha));
        r.text(level.tag, tagX + tagPad, tagY + tagPad / 2, alpha(new Color(), accent, 255 * alpha), false, tagScale);

        // Time left
        double remaining = 1 - t / level.seconds;
        double barH = 2 * s;
        r.quad(x + radius, y + h - barH - 2 * s, (w - radius * 2) * remaining, barH, alpha(new Color(), accent, 160 * alpha));
    }

    private static Color alpha(Color out, Color base, float alpha) {
        return out.set(base.r, base.g, base.b, Math.round(Math.max(0, Math.min(255, alpha))));
    }

    private float elapsed() {
        return (System.nanoTime() - startNanos) / 1.0E9f;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }
}
