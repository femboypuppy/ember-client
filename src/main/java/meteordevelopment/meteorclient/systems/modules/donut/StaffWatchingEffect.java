package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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
 *     <li>{@link Level#WATCHING}: a staff member's game mode leaked from your shard, or they are near you. Their 3D
 *     head tumbles past the camera like the elder guardian curse, then a WATCHING YOU title, and the card turns
 *     red.</li>
 * </ul>
 * A lower level never interrupts a higher one that is still on screen.
 * <p>
 * The tumbling head and title are ported from Anubis Client's StaffGhost (4ldenz, GPL-3.0,
 * https://github.com/4ldenz/Anubis-client), redrawn with Meteor's renderer instead of ImGui.
 */
public final class StaffWatchingEffect {
    private static final StaffWatchingEffect INSTANCE = new StaffWatchingEffect();

    private static final float SLIDE_SECONDS = 0.35f;
    private static final float FADE_OUT_SECONDS = 0.45f;
    private static final float VIGNETTE_SECONDS = 3.0f;

    // Anubis StaffGhost: head geometry in camera space, the tumble lasts 1.5 s and the title follows at 1.25 s.
    private static final float GHOST_SECONDS = 1.5f;
    private static final float TITLE_AT_SECONDS = 1.25f;
    private static final float HEAD_DISTANCE = 1.49f;
    private static final float HEAD_LIFT = 0.24f;
    private static final float HEAD_HALF = 0.45f;
    private static final float HAT_SCALE = 1.125f;
    private static final float NEAR_PLANE = -0.08f;
    private static final float HALF_FOV_TAN = (float) Math.tan(Math.toRadians(35.0));
    private static final int CELLS = 8;
    // Per face: skin u, v of the 8x8 patch, then corners a, b, c (unit cube) and a shade.
    private static final float[][] FACES = {
        {8, 8, -1, 1, 1, 1, 1, 1, -1, -1, 1, 1.0f},
        {0, 8, -1, 1, -1, -1, 1, 1, -1, -1, -1, 0.8f},
        {16, 8, 1, 1, 1, 1, 1, -1, 1, -1, 1, 0.8f},
        {8, 0, -1, 1, -1, 1, 1, -1, -1, 1, 1, 0.95f},
        {16, 0, -1, -1, 1, 1, -1, 1, -1, -1, -1, 0.6f}
    };

    public enum Level {
        REGION("IN REGION", new Color(110, 165, 255), 6f),
        HIDDEN("HIDDEN", new Color(255, 172, 64), 8f),
        WATCHING("WATCHING", new Color(255, 84, 84), 10f);

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
                case NEARBY, WATCHING -> WATCHING;
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
    private boolean titleShown;
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
            case WATCHING -> clean + " is watching you";
        };
        this.subtitle = subtitle == null ? "" : subtitle;
        this.skin = StaffFaceSkins.get(id, clean);
        this.startNanos = System.nanoTime();
        this.ticks = 0;
        this.titleShown = false;
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

        if (level == Level.WATCHING && !titleShown && elapsed() >= TITLE_AT_SECONDS) {
            titleShown = true;
            mc.inGameHud.setTitleTicks(10, 40, 15);
            mc.inGameHud.setTitle(Text.literal("WATCHING YOU").formatted(Formatting.RED, Formatting.BOLD));
            mc.inGameHud.setSubtitle(Text.literal(watcherName).formatted(Formatting.RED, Formatting.BOLD));
        }

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
            if (t < GHOST_SECONDS) drawGhostHead(renderer, sw, sh, t / GHOST_SECONDS);
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
     * The elder guardian curse, as Anubis draws it: the staff member's head as a real cube (plus the hat layer)
     * right in front of the camera, tipping over from above as it fades in and out. Each face is split into its 8x8
     * skin pixels so the perspective stays correct without a 3D pipeline.
     */
    private void drawGhostHead(HudRenderer r, double sw, double sh, float p) {
        Identifier texture = skin != null ? skin.texture() : null;
        if (texture == null) return;

        float alpha = 0.05f + 0.6f * (float) Math.sin(p * Math.PI);
        double angle = Math.toRadians(60.0 - 150.0 * p);
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float w = (float) sw, h = (float) sh;
        float focal = h * 0.5f / HALF_FOV_TAN;

        r.post(() -> r.texturedMesh(texture, mesh -> {
            drawCube(mesh, HEAD_HALF, 0f, cos, sin, w, h, focal, alpha);
            drawCube(mesh, HEAD_HALF * HAT_SCALE, 32f, cos, sin, w, h, focal, alpha);
        }));
    }

    private static void drawCube(Renderer2D mesh, float half, float uShift, float cos, float sin, float w, float h, float focal, float alpha) {
        float[] a = new float[3], b = new float[3], c = new float[3];
        float[] sx = new float[(CELLS + 1) * (CELLS + 1)];
        float[] sy = new float[sx.length];
        Color color = new Color();

        for (float[] face : FACES) {
            corner(face, 2, half, cos, sin, a);
            corner(face, 5, half, cos, sin, b);
            corner(face, 8, half, cos, sin, c);

            float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float mx = a[0] + (ux + vx) * 0.5f, my = a[1] + (uy + vy) * 0.5f, mz = a[2] + (uz + vz) * 0.5f;

            // Facing away from the camera, or poking through the near plane.
            if (nx * mx + ny * my + nz * mz <= 0f) continue;
            if (a[2] > NEAR_PLANE || b[2] > NEAR_PLANE || c[2] > NEAR_PLANE || c[2] + uz > NEAR_PLANE) continue;

            for (int j = 0; j <= CELLS; j++) {
                for (int i = 0; i <= CELLS; i++) {
                    float s = (float) i / CELLS, t = (float) j / CELLS;
                    float x = a[0] + ux * s + vx * t;
                    float y = a[1] + uy * s + vy * t;
                    float z = a[2] + uz * s + vz * t;
                    sx[j * (CELLS + 1) + i] = w * 0.5f + x / -z * focal;
                    sy[j * (CELLS + 1) + i] = h * 0.5f - y / -z * focal;
                }
            }

            int shade = Math.round(face[11] * 255f);
            color.set(shade, shade, shade, Math.round(clamp(alpha) * 255f));
            float u0 = (face[0] + uShift) / 64f, v0 = face[1] / 64f, texel = 1f / 64f;

            for (int j = 0; j < CELLS; j++) {
                for (int i = 0; i < CELLS; i++) {
                    int p0 = j * (CELLS + 1) + i;
                    int q0 = p0 + CELLS + 1;
                    float cu = u0 + i * texel, cv = v0 + j * texel;

                    mesh.triangles.ensureQuadCapacity();
                    int i1 = mesh.triangles.vec2(sx[p0], sy[p0]).vec2(cu, cv).color(color).next();
                    int i2 = mesh.triangles.vec2(sx[p0 + 1], sy[p0 + 1]).vec2(cu + texel, cv).color(color).next();
                    int i3 = mesh.triangles.vec2(sx[q0 + 1], sy[q0 + 1]).vec2(cu + texel, cv + texel).color(color).next();
                    int i4 = mesh.triangles.vec2(sx[q0], sy[q0]).vec2(cu, cv + texel).color(color).next();
                    mesh.triangles.quad(i1, i2, i3, i4);
                }
            }
        }
    }

    /** One cube corner in camera space, tipped about the X axis by the current tumble angle. */
    private static void corner(float[] face, int at, float half, float cos, float sin, float[] out) {
        float x = face[at] * half;
        float y = face[at + 1] * half + HEAD_LIFT;
        float z = face[at + 2] * half - HEAD_DISTANCE;
        out[0] = x;
        out[1] = y * cos - z * sin;
        out[2] = y * sin + z * cos;
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
