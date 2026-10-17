package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.EmberHudCards;
import meteordevelopment.meteorclient.utils.render.EmberIcons;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.Random;
import java.util.UUID;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Staff alert overlay, in three levels so the alarm matches the threat:
 * <ul>
 *     <li>{@link Level#REGION}: a staff member is on your region's server. A small card slides in at the top with a
 *     soft bell. They could be anywhere in a 25k block region, so this is information, not an alarm.</li>
 *     <li>{@link Level#HIDDEN}: a staff member is vanished or spectating on your region's server. Same card in amber
 *     with a lower two-note bell.</li>
 *     <li>{@link Level#WATCHING}: a staff member's game mode leaked from your shard, or they are near you. Their head
 *     pops like a totem of undying (swells, spins, a burst of embers), then a WATCHING YOU title, and the card turns
 *     red.</li>
 * </ul>
 * A lower level never interrupts a higher one that is still on screen.
 */
public final class StaffWatchingEffect {
    private static final StaffWatchingEffect INSTANCE = new StaffWatchingEffect();

    private static final float SLIDE_SECONDS = 0.35f;
    private static final float FADE_OUT_SECONDS = 0.45f;
    private static final float VIGNETTE_SECONDS = 3.0f;

    // Totem pop: vanilla's floating item lasts 40 ticks.
    private static final float POP_SECONDS = 2.0f;
    private static final float TITLE_AT_SECONDS = 0.5f;
    private static final float HAT_SCALE = 1.125f;
    private static final float CAMERA_DISTANCE = 5f;
    private static final int CELLS = 8;
    private static final int PARTICLES = 56;

    /**
     * The six faces of a skin's head: the 8x8 patch (u, v) and how it lies on a cube from -1 to 1 (corner A at the
     * patch's top-left, then the directions of the patch's u and v axes). y is up, z points at the camera.
     */
    private static final float[][] HEAD_FACES = {
        {8, 8,   -1, 1, 1,    2, 0, 0,    0, -2, 0},   // front
        {24, 8,   1, 1, -1,  -2, 0, 0,    0, -2, 0},   // back
        {0, 8,   -1, 1, -1,   0, 0, 2,    0, -2, 0},   // right side (viewer's left)
        {16, 8,   1, 1, 1,    0, 0, -2,   0, -2, 0},   // left side
        {8, 0,   -1, 1, -1,   2, 0, 0,    0, 0, 2},    // top
        {16, 0,  -1, -1, 1,   2, 0, 0,    0, 0, -2}    // bottom
    };

    private static final Color[] EMBER_COLORS = {
        new Color(255, 84, 84), new Color(255, 140, 60), new Color(255, 205, 90), new Color(200, 40, 40)
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

    // Where the pop drifts to, like the totem's random offset.
    private float driftX, driftY;
    private final float[] pAngle = new float[PARTICLES], pSpeed = new float[PARTICLES], pLife = new float[PARTICLES];
    private final float[] pSize = new float[PARTICLES], pDelay = new float[PARTICLES];
    private final int[] pColor = new int[PARTICLES];

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

        if (level == Level.WATCHING) seedPop();
        playSound(0);
    }

    public void cancel() {
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    private void seedPop() {
        Random random = new Random();
        driftX = (random.nextFloat() * 2 - 1) * 0.12f;
        driftY = (random.nextFloat() * 2 - 1) * 0.08f;

        for (int i = 0; i < PARTICLES; i++) {
            pAngle[i] = (float) (random.nextFloat() * Math.PI * 2);
            pSpeed[i] = 0.25f + random.nextFloat() * 0.55f;
            pLife[i] = 0.8f + random.nextFloat() * 0.8f;
            pSize[i] = 0.004f + random.nextFloat() * 0.006f;
            // Two waves: the pop itself and a smaller one as the head peaks.
            pDelay[i] = i < PARTICLES * 2 / 3 ? random.nextFloat() * 0.08f : 0.3f + random.nextFloat() * 0.1f;
            pColor[i] = random.nextInt(EMBER_COLORS.length);
        }
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
                if (tick == 0) mc.player.playSound(SoundEvents.ITEM_TOTEM_USE, 0.9f, 1.0f);
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
            mc.inGameHud.setTitleTicks(6, 40, 15);
            mc.inGameHud.setTitle(Text.literal("WATCHING YOU").formatted(Formatting.RED, Formatting.BOLD));
            mc.inGameHud.setSubtitle(Text.literal(watcherName).formatted(Formatting.RED));
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
            if (t < POP_SECONDS + 1.6f) drawEmbers(renderer, sw, sh, t);
            if (t < POP_SECONDS) drawTotemHead(renderer, sw, sh, t / POP_SECONDS);
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

    /** Sparks thrown out of the pop, falling and fading like totem particles. */
    private void drawEmbers(HudRenderer r, double sw, double sh, float t) {
        double cx = sw / 2 + driftX * sw * 0.5, cy = sh / 2 + driftY * sh * 0.5;
        double unit = sh;

        for (int i = 0; i < PARTICLES; i++) {
            float age = t - pDelay[i];
            if (age <= 0 || age >= pLife[i]) continue;

            float life = age / pLife[i];
            // Fast burst that slows down, plus gravity pulling the sparks down.
            double travel = pSpeed[i] * (1 - Math.pow(1 - Math.min(1, age / 0.9), 3)) * 0.55;
            double x = cx + Math.cos(pAngle[i]) * travel * unit;
            double y = cy + Math.sin(pAngle[i]) * travel * unit * 0.8 + 0.22 * age * age * unit;
            double size = pSize[i] * unit * (1 - life * 0.6);

            Color base = EMBER_COLORS[pColor[i]];
            int alpha = Math.round(255 * (1 - life * life));
            r.quad(x - size / 2, y - size / 2, size, size, work.set(base.r, base.g, base.b, alpha));
        }
    }

    /**
     * The staff member's head popping like a totem of undying: it swells from small to huge and back while spinning
     * two and a half turns, with the totem's little wobble. Drawn as a real cube (head plus hat layer) with a light
     * from the front, each face cut into its 8x8 skin pixels so the slight perspective stays correct.
     */
    private void drawTotemHead(HudRenderer r, double sw, double sh, float f) {
        Identifier texture = skin != null ? skin.texture() : null;
        if (texture == null) return;

        // Vanilla's floating item curve: k runs from 0 to pi, so sin(k) swells and settles.
        float g = f * f, h = f * g;
        float k = (float) ((10.25f * h * g - 24.95f * g * g + 25.5f * h - 13.8f * g + 4.0f * f) * Math.PI);
        double guiScale = mc.getWindow().getScaleFactor();
        double half = (50 + 175 * Math.sin(k)) * 0.42 * guiScale;
        double drift = Math.abs(Math.sin(k * 2));
        double cx = sw / 2 + driftX * sw * drift, cy = sh / 2 + driftY * sh * drift;

        double yaw = Math.toRadians(900 * Math.abs(Math.sin(k)));
        double wobble = Math.toRadians(6 * Math.cos(f * 8));
        float alpha = f > 0.85f ? (1 - f) / 0.15f : 1f;

        float[][] rot = rotation(yaw, wobble, wobble);
        r.post(() -> r.texturedMesh(texture, mesh -> {
            drawCube(mesh, rot, 1f, 0f, cx, cy, half, alpha);
            drawCube(mesh, rot, HAT_SCALE, 32f, cx, cy, half, alpha);
        }));
    }

    /** Rotation matrix for Z then X then Y (the order the totem applies them). */
    private static float[][] rotation(double yaw, double pitch, double roll) {
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double cx = Math.cos(pitch), sx = Math.sin(pitch);
        double cz = Math.cos(roll), sz = Math.sin(roll);

        double[][] ry = {{cy, 0, sy}, {0, 1, 0}, {-sy, 0, cy}};
        double[][] rx = {{1, 0, 0}, {0, cx, -sx}, {0, sx, cx}};
        double[][] rz = {{cz, -sz, 0}, {sz, cz, 0}, {0, 0, 1}};
        double[][] m = mul(ry, mul(rx, rz));

        float[][] out = new float[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) out[i][j] = (float) m[i][j];
        return out;
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
        return out;
    }

    private static void drawCube(Renderer2D mesh, float[][] rot, float scale, float uShift, double cx, double cy, double half, float alpha) {
        float[] a = new float[3], u = new float[3], v = new float[3];
        float[] px = new float[(CELLS + 1) * (CELLS + 1)], py = new float[px.length];
        Color color = new Color();

        for (float[] face : HEAD_FACES) {
            transform(rot, face[2] * scale, face[3] * scale, face[4] * scale, a);
            transform(rot, face[5] * scale, face[6] * scale, face[7] * scale, u);
            transform(rot, face[8] * scale, face[9] * scale, face[10] * scale, v);

            // Outward normal is v x u for this face table; skip faces turned away from the camera.
            float nx = v[1] * u[2] - v[2] * u[1], ny = v[2] * u[0] - v[0] * u[2], nz = v[0] * u[1] - v[1] * u[0];
            float mx = a[0] + (u[0] + v[0]) / 2, my = a[1] + (u[1] + v[1]) / 2, mz = a[2] + (u[2] + v[2]) / 2;
            if (nx * -mx + ny * -my + nz * (CAMERA_DISTANCE - mz) <= 0) continue;

            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            float light = Math.max(0, (ny * 0.55f + nz * 0.83f) / len);
            int shade = Math.round(255 * (0.5f + 0.5f * light));
            color.set(shade, shade, shade, Math.round(255 * clamp(alpha)));

            for (int j = 0; j <= CELLS; j++) {
                for (int i = 0; i <= CELLS; i++) {
                    float s = (float) i / CELLS, t = (float) j / CELLS;
                    float x = a[0] + u[0] * s + v[0] * t;
                    float y = a[1] + u[1] * s + v[1] * t;
                    float z = a[2] + u[2] * s + v[2] * t;
                    float persp = CAMERA_DISTANCE / (CAMERA_DISTANCE - z);
                    px[j * (CELLS + 1) + i] = (float) (cx + x * persp * half);
                    py[j * (CELLS + 1) + i] = (float) (cy - y * persp * half);
                }
            }

            // The UI texture pipeline culls one winding, so match the winding Renderer2D.texQuad uses.
            int far = CELLS * (CELLS + 1);
            float area = (px[0] * py[CELLS] - px[CELLS] * py[0])
                + (px[CELLS] * py[far + CELLS] - px[far + CELLS] * py[CELLS])
                + (px[far + CELLS] * py[far] - px[far] * py[far + CELLS])
                + (px[far] * py[0] - px[0] * py[far]);
            boolean flip = area > 0;

            float u0 = (face[0] + uShift) / 64f, v0 = face[1] / 64f, texel = 1f / 64f;
            for (int j = 0; j < CELLS; j++) {
                for (int i = 0; i < CELLS; i++) {
                    int p = j * (CELLS + 1) + i, q = p + CELLS + 1;
                    float cu = u0 + i * texel, cv = v0 + j * texel;

                    mesh.triangles.ensureQuadCapacity();
                    int v1 = mesh.triangles.vec2(px[p], py[p]).vec2(cu, cv).color(color).next();
                    int v2 = mesh.triangles.vec2(px[p + 1], py[p + 1]).vec2(cu + texel, cv).color(color).next();
                    int v3 = mesh.triangles.vec2(px[q + 1], py[q + 1]).vec2(cu + texel, cv + texel).color(color).next();
                    int v4 = mesh.triangles.vec2(px[q], py[q]).vec2(cu, cv + texel).color(color).next();
                    if (flip) mesh.triangles.quad(v1, v4, v3, v2);
                    else mesh.triangles.quad(v1, v2, v3, v4);
                }
            }
        }
    }

    private static void transform(float[][] m, float x, float y, float z, float[] out) {
        out[0] = m[0][0] * x + m[0][1] * y + m[0][2] * z;
        out[1] = m[1][0] * x + m[1][1] * y + m[1][2] * z;
        out[2] = m[2][0] * x + m[2][1] * y + m[2][2] * z;
    }

    private void drawCard(HudRenderer r, double sw, double sh, float t) {
        float in = easeOutCubic(clamp(t / SLIDE_SECONDS));
        float out = clamp((level.seconds - t) / FADE_OUT_SECONDS);
        float opacity = Math.min(in, out);
        if (opacity <= 0.01f) return;
        double s = Math.clamp(sh / 1000, 0.85, 1.6);
        double pad = 12 * s, face = 26 * s, gap = 10 * s;
        double titleScale = 0.85 * s, subScale = 0.68 * s, tagScale = 0.58 * s;
        double tagW = EmberHudCards.badgeWidth(r, level.tag, tagScale, s);
        double fixedW = pad * 2 + face + gap * 2 + tagW;
        double textW = Math.max(r.textWidth(title, false, titleScale), r.textWidth(subtitle, false, subScale));
        double maxW = Math.min(520 * s, sw - 24 * s);
        double w = Math.min(maxW, Math.max(320 * s, fixedW + textW));
        double available = Math.max(0, w - fixedW);
        String heading = EmberHudCards.fit(r, title, titleScale, available);
        var lines = EmberHudCards.wrap(r, subtitle, subScale, available);
        double titleH = r.textHeight(false, titleScale), subH = r.textHeight(false, subScale);
        double textH = titleH + (lines.isEmpty() ? 0 : 4 * s + lines.size() * subH + (lines.size() - 1) * 2 * s);
        double h = pad * 2 + Math.max(face, textH) + 5 * s;
        double x = (sw - w) / 2, y = 16 * s - (1 - in) * (h + 24 * s);
        Color status = level == Level.REGION ? EmberPalette.accent() : level.accent;
        EmberHudCards.surface(r, x, y, w, h, s, opacity, true);
        double fx = x + pad, fy = y + (h - 5 * s - face) / 2, radius = face * 0.26;
        EmberHudCards.icon(r, EmberIcons.Glyph.USER, fx, fy, face, status, opacity);
        Identifier texture = skin != null ? skin.texture() : null;
        if (texture != null) {
            Color faceTint = EmberHudCards.alpha(Color.WHITE, opacity);
            r.post(() -> {
                r.roundedTextureRegion(texture, fx, fy, face, radius, 0.125, 0.125, 0.25, 0.25, faceTint);
                r.roundedTextureRegion(texture, fx, fy, face, radius, 0.625, 0.125, 0.75, 0.25, faceTint);
            });
        }
        double tx = fx + face + gap, ty = y + (h - 5 * s - textH) / 2;
        r.text(heading, tx, ty, EmberHudCards.alpha(EmberPalette.textBright(), opacity), false, titleScale);
        double subY = ty + titleH + 4 * s;
        for (String line : lines) {
            r.text(line, tx, subY, EmberHudCards.alpha(EmberPalette.textDim(), opacity), false, subScale);
            subY += subH + 2 * s;
        }
        double tagH = r.textHeight(false, tagScale) + 6 * s;
        EmberHudCards.badge(r, level.tag, x + w - pad - tagW, y + (h - 5 * s - tagH) / 2, tagScale, s, status, opacity);
        double barW = (w - pad * 2) * clamp(1 - t / level.seconds);
        if (barW > 0.5) r.roundedQuad(x + pad, y + h - 5 * s, barW, Math.max(1, 1.4 * s), s,
            EmberHudCards.alpha(status, opacity * 0.6));
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
