package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

import java.util.Locale;
import java.util.UUID;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Renders the 10-second Elder Guardian curse-style sweeping Staff Head animation,
 * crimson screen flash/heartbeat pulse, and top-center "<Staff> is in your region" banner.
 */
public final class StaffWatchingEffect {
    private static final StaffWatchingEffect INSTANCE = new StaffWatchingEffect();

    private static final float FLASH_SECONDS = 0.95f;
    private static final float FLASH_RAMP = 0.14f;
    private static final float FLASH_PEAK = 0.42f;
    private static final float ELDER_SWEEP_SECONDS = 2.65f;
    private static final float CARD_START_SECONDS = 0.35f;
    private static final float POP_SECONDS = 0.28f;
    private static final float END_SECONDS = 10.0f;

    private static final int BEAT_TICKS = 24;
    private static final float PULSE_BASE = 0.045f;
    private static final float PULSE_THUMP = 0.125f;

    private UUID watcherId;
    private String watcherName = "";
    private String titleText = "";
    private String subtitleText = "";
    private StaffFaceSkins.Skin skin;
    private long startNanos;
    private boolean active;
    private int beatTicks;
    private long lastBeatNanos;

    private StaffWatchingEffect() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public static StaffWatchingEffect get() {
        return INSTANCE;
    }

    public void play(UUID id, String name, String subtitle, int extraWatchers) {
        if (mc == null || name == null || name.isBlank()) return;

        String cleanName = name.trim();
        StaffFaceSkins.preload(cleanName);

        watcherId = id;
        watcherName = cleanName;

        String who = extraWatchers > 0 ? cleanName + " +" + extraWatchers : cleanName;
        boolean physicallyNear = subtitle != null && subtitle.toUpperCase(Locale.ROOT).contains("NEARBY");
        titleText = physicallyNear ? who + " is near you" : who + " is in your region";
        subtitleText = (subtitle == null || subtitle.isBlank()) ? "STAFF DETECTED IN YOUR REGION" : subtitle;
        skin = StaffFaceSkins.get(id, cleanName);

        long now = System.nanoTime();
        startNanos = now;
        lastBeatNanos = now;
        beatTicks = 0;
        active = true;

        playCurseSiren();
    }

    public void cancel() {
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    private void playCurseSiren() {
        if (mc == null || mc.player == null) return;
        try {
            // Iconic Elder Guardian curse jump-scare sound + deep Warden heartbeat stinger
            mc.player.playSound(SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 0.95f);
            mc.player.playSound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.0f, 0.85f);
            mc.player.playSound(SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, 0.55f, 1.35f);
        } catch (Throwable ignored) {}
    }

    private void playHeartbeat() {
        if (mc == null || mc.player == null) return;
        try {
            mc.player.playSound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 0.85f, 0.9f);
        } catch (Throwable ignored) {}
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc == null || mc.world == null || mc.player == null) {
            active = false;
            return;
        }

        if (!active) return;

        float elapsed = elapsedSeconds();
        if (elapsed >= END_SECONDS) {
            active = false;
            return;
        }

        // Play heartbeat sound only during the 10-second animation window
        if (++beatTicks >= BEAT_TICKS) {
            beatTicks = 0;
            lastBeatNanos = System.nanoTime();
            playHeartbeat();
        }
    }

    public void render(HudRenderer renderer) {
        if (!active || mc == null) return;

        float seconds = elapsedSeconds();
        if (seconds >= END_SECONDS) {
            active = false;
            return;
        }

        long now = System.nanoTime();

        // Smooth fade-out envelope over the last 1.0 second of the 10-second duration
        float remaining = END_SECONDS - seconds;
        float level = clamp(remaining);

        if (skin == null || skin.pending()) {
            skin = StaffFaceSkins.get(watcherId, watcherName);
        }

        double sw = Utils.getWindowWidth();
        double sh = Utils.getWindowHeight();
        if (sw <= 0 || sh <= 0) return;

        // 1. Full-screen crimson flash / Warden heartbeat pulse (strictly bounded to 10s)
        if (seconds < FLASH_SECONDS) {
            drawFlash(renderer, sw, sh, seconds);
        } else if (level > 0f) {
            drawPulse(renderer, sw, sh, now, level);
        }

        // 2. Elder Guardian Curse sweeping Staff Head apparition across the center of the screen
        if (seconds < ELDER_SWEEP_SECONDS) {
            drawElderGuardianHead(renderer, sw, sh, seconds / ELDER_SWEEP_SECONDS);
        }

        // 3. Top-center "<Staff> is in your region" notification card (visible for the 10s duration)
        if (seconds >= CARD_START_SECONDS && seconds < END_SECONDS) {
            drawWatchingCard(renderer, sw, sh, seconds - CARD_START_SECONDS);
        }
    }

    private void drawFlash(HudRenderer renderer, double w, double h, float seconds) {
        float t = seconds < FLASH_RAMP
            ? seconds / FLASH_RAMP
            : 1f - (seconds - FLASH_RAMP) / (FLASH_SECONDS - FLASH_RAMP);
        int alpha = Math.round(clamp(t) * FLASH_PEAK * 255f);
        if (alpha > 0) {
            renderer.quad(0, 0, w, h, new Color(200, 0, 12, alpha));
        }
    }

    private void drawPulse(HudRenderer renderer, double w, double h, long now, float level) {
        float phase = (now - lastBeatNanos) / (BEAT_TICKS * 5.0E7f);
        float thump = Math.max(0f, 1f - phase * 2.5f);
        float a = (PULSE_BASE + PULSE_THUMP * thump * thump) * level;
        int alpha = Math.round(clamp(a) * 255f);
        if (alpha > 0) {
            renderer.quad(0, 0, w, h, new Color(200, 0, 12, alpha));
        }
    }

    /**
     * Replicates the Elder Guardian Mining Fatigue curse jump-scare animation using the staff
     * member's Minecraft skin head + 3D hat overlay sweeping vertically across the screen.
     */
    private void drawElderGuardianHead(HudRenderer renderer, double sw, double sh, float progress) {
        float p = clamp(progress);

        // Alpha envelope: fast spectral fade-in, eerie hold through center, ghostly dissolve at bottom
        float alpha;
        if (p < 0.16f) {
            alpha = p / 0.16f;
        } else if (p < 0.72f) {
            alpha = 1.0f - (p - 0.16f) * 0.18f;
        } else {
            alpha = (1.0f - (p - 0.72f) / 0.28f) * 0.90f;
        }
        alpha = clamp(alpha) * 0.88f;
        if (alpha <= 0.01f) return;

        // Vertical Elder Guardian sweep trajectory (top -> center -> bottom) with subtle spectral sway
        double startY = sh * 0.16;
        double endY = sh * 0.82;
        double smoothP = p * p * (3.0 - 2.0 * p);
        double cy = startY + (endY - startY) * smoothP;
        double cx = sw * 0.50 + Math.sin(p * Math.PI * 2.0) * (sw * 0.032);

        // Looming scale curve: swells largest as it crosses the center of your vision
        double swell = Math.sin(p * Math.PI * 0.88);
        double headSize = Math.max(88.0, sh * (0.145 + 0.155 * swell));
        double hatSize = headSize * 1.11;

        // Subtle Elder Guardian curse tilt oscillation
        double rotation = Math.sin(p * Math.PI * 2.2) * 8.5;

        // Soft spectral curse aura behind the sweeping head
        double auraSize = headSize * 1.24;
        renderer.softGlow(
            cx - auraSize / 2.0,
            cy - auraSize / 2.0,
            auraSize,
            auraSize,
            auraSize * 0.25,
            headSize * 0.32,
            new Color(225, 36, 52, Math.round(alpha * 145f))
        );

        Identifier texId = skin != null ? skin.texture() : null;
        if (texId == null) return;

        final float finalAlpha = alpha;
        renderer.post(() -> {
            var tex = mc.getTextureManager().getTexture(texId);
            if (tex == null) return;

            Renderer2D.TEXTURE.begin();

            // Ghostly trailing Elder Guardian curse afterimage echoes
            for (int echo = 2; echo >= 1; echo--) {
                double lag = echo * 0.055;
                double echoP = Math.max(0.0, smoothP - lag);
                double echoCy = startY + (endY - startY) * echoP;
                double echoCx = sw * 0.50 + Math.sin(Math.max(0.0, p - lag) * Math.PI * 2.0) * (sw * 0.032);
                double echoSize = headSize * (1.0 + echo * 0.12);
                double echoRot = rotation + (echo == 1 ? -3.5 : 3.5);
                int echoA = Math.round(finalAlpha * (echo == 1 ? 85f : 45f));

                // Eerie Elder-Guardian spectral cyan-red tint on the trailing echoes
                Color echoTint = echo == 1
                    ? new Color(255, 110, 120, echoA)
                    : new Color(130, 225, 240, echoA);

                Renderer2D.TEXTURE.texQuad(
                    echoCx - echoSize / 2.0, echoCy - echoSize / 2.0,
                    echoSize, echoSize, echoRot,
                    0.125, 0.125, 0.25, 0.25, echoTint
                );
                Renderer2D.TEXTURE.texQuad(
                    echoCx - echoSize * 0.55, echoCy - echoSize * 0.55,
                    echoSize * 1.10, echoSize * 1.10, echoRot,
                    0.625, 0.125, 0.75, 0.25, echoTint
                );
            }

            // Main looming Staff Head (base face + 3D outer hat layer)
            Color mainTint = new Color(255, 255, 255, Math.round(finalAlpha * 245f));
            Renderer2D.TEXTURE.texQuad(
                cx - headSize / 2.0, cy - headSize / 2.0,
                headSize, headSize, rotation,
                0.125, 0.125, 0.25, 0.25, mainTint
            );
            Renderer2D.TEXTURE.texQuad(
                cx - hatSize / 2.0, cy - hatSize / 2.0,
                hatSize, hatSize, rotation,
                0.625, 0.125, 0.75, 0.25, mainTint
            );

            Renderer2D.TEXTURE.render(tex.getGlTextureView(), tex.getSampler());
        });
    }

    private void drawWatchingCard(HudRenderer renderer, double sw, double sh, float cardSeconds) {
        float remaining = END_SECONDS - CARD_START_SECONDS - cardSeconds;
        float fadeOut = remaining < 0.6f ? remaining / 0.6f : 1f;
        float alpha = clamp(Math.min(fadeOut, cardSeconds / 0.10f));
        if (alpha <= 0.01f) return;

        float pop = cardSeconds < POP_SECONDS ? easeOutBack(cardSeconds / POP_SECONDS) : 1f;
        double s = Math.max(0.85, Math.min(1.65, sh / 720.0)) * pop;

        double titleScale = 1.25 * s;
        double subScale = 0.80 * s;

        double titleW = renderer.textWidth(titleText, true, titleScale);
        double titleH = renderer.textHeight(true, titleScale);
        double subW = renderer.textWidth(subtitleText, true, subScale);
        double subH = renderer.textHeight(true, subScale);

        double faceSize = 44.0 * s;
        double pad = 13.0 * s;
        double gap = 13.0 * s;

        double textBlockW = Math.max(titleW, subW);
        double cardW = pad + faceSize + gap + textBlockW + pad;
        double cardH = pad + faceSize + pad;
        double radius = 11.0 * s;

        double cardX = (sw - cardW) / 2.0;
        double cardY = sh * 0.11;

        renderer.softGlow(cardX, cardY, cardW, cardH, radius, 14.0 * s, new Color(220, 35, 35, Math.round(alpha * 155f)));
        renderer.dropShadow(cardX, cardY, cardW, cardH, radius, 22.0 * s, alpha);

        // Outer crimson alert border + dark crimson card body
        double rim = 1.4 * s;
        renderer.roundedQuad(cardX - rim, cardY - rim, cardW + rim * 2, cardH + rim * 2, radius + rim,
            new Color(235, 65, 65, Math.round(alpha * 210f)));
        renderer.roundedQuad(cardX, cardY, cardW, cardH, radius,
            new Color(26, 6, 8, Math.round(alpha * 232f)));

        // Staff face icon on the left
        double fx = cardX + pad;
        double fy = cardY + pad;
        double faceRadius = faceSize * 0.22;
        renderer.roundedQuad(fx, fy, faceSize, faceSize, faceRadius, new Color(45, 16, 18, Math.round(alpha * 235f)));

        Identifier texId = skin != null ? skin.texture() : null;
        if (texId != null) {
            Color faceTint = new Color(255, 255, 255, Math.round(alpha * 255f));
            renderer.post(() -> {
                renderer.roundedTextureRegion(texId, fx, fy, faceSize, faceRadius, 0.125, 0.125, 0.25, 0.25, faceTint);
                renderer.roundedTextureRegion(texId, fx, fy, faceSize, faceRadius, 0.625, 0.125, 0.75, 0.25, faceTint);
            });
        }

        // Title + Subtitle
        double tx = fx + faceSize + gap;
        double totalTextH = titleH + 3.0 * s + subH;
        double ty = cardY + (cardH - totalTextH) / 2.0;

        renderer.text(titleText, tx, ty, new Color(255, 85, 85, Math.round(alpha * 255f)), true, titleScale);
        renderer.text(subtitleText, tx, ty + titleH + 3.0 * s, new Color(235, 195, 195, Math.round(alpha * 235f)), true, subScale);
    }

    private float elapsedSeconds() {
        return (float) (System.nanoTime() - startNanos) / 1.0E9f;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }
}
