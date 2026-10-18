package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.*;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.meteorclient.utils.misc.MusicReactive;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.gl.DynamicUniformStorage;
import net.minecraft.entity.effect.StatusEffects;
import org.joml.Vector4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.util.OptionalInt;
import java.util.OptionalDouble;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/** World-space celestial atmosphere. Far-depth sky and scene-depth motes have separate pipelines. */
public final class EmberWorldAtmosphere {
    private static final CelestialMesh SHELL = new CelestialMesh(3072), STARS = new CelestialMesh(60000), MOTES = new CelestialMesh(24000);
    private static Renderer3D sky, stars, motes;
    private static long previous;
    private static Object world;
    private static double time;
    private static double horizonEnvelope;
    private static long previousLightSample;
    private static double sampledLightReaction = 1;
    private EmberWorldAtmosphere() {}

    @PostInit public static void init() { MeteorClient.EVENT_BUS.subscribe(EmberWorldAtmosphere.class); }
    public static double time() { return time; }

    private static boolean enabled() {
        var c = Config.get();
        return c != null && c.ambience.get() && c.worldAtmosphere.get() && mc.world != null && mc.player != null;
    }
    private static boolean effectsVisible() {
        // Liquid changes fog visibility, not the selected atmosphere or daylight palette.
        return !mc.player.hasStatusEffect(StatusEffects.BLINDNESS) && !mc.player.hasStatusEffect(StatusEffects.DARKNESS);
    }
    public static boolean openSky() { return enabled() && mc.world.getDimension().hasSkyLight() && effectsVisible(); }
    public static boolean fogEnabled() { return openSky() && Config.get().accentFog.get() && Config.get().fogAmount.get() > 0; }
    public static boolean fogHazeEnabled() {
        // Underwater/lava/snow distances belong to Minecraft's medium-specific fog.
        return fogEnabled() && mc.gameRenderer.getCamera().getSubmersionType() == CameraSubmersionType.NONE;
    }
    public static boolean skyActive() { return openSky() && Config.get().accentSky.get() && Config.get().skyIntensity.get() > 0; }
    public static boolean lightingEnabled() {
        return openSky() && Config.get().worldLighting.get() && Config.get().vanillaLightMix.get() < 1
            && (Config.get().worldDarkness.get() > 0 || Config.get().worldLightTint.get() > 0
                || Config.get().daylightBrightness.get() != 1 || Config.get().musicReactive.get() && Config.get().bassLighting.get());
    }
    public static double lightBlend() { return 1 - Config.get().vanillaLightMix.get(); }
    // Quantized daylight changes limit lightmap uploads while the sky remains smoothly animated.
    private static double lightReaction() {
        long now = System.nanoTime();
        if (now - previousLightSample >= 50_000_000L) {
            sampledLightReaction = Math.round(MusicReactive.exposure(Config.get().bassLighting.get()) * 32) / 32.0;
            previousLightSample = now;
        }
        return sampledLightReaction;
    }
    public static float daylight(float original) {
        var c = Config.get();
        return CelestialLighting.illumination(original, c.worldDarkness.get(), c.daylightBrightness.get(), lightBlend(), lightReaction());
    }
    public static float gamma(float original) { return CelestialLighting.mixedGamma(original, Config.get().worldDarkness.get(), lightBlend()); }
    private static double skyExposure() { return MusicReactive.exposure(Config.get().bassSky.get()); }
    public static int lightingState() {
        if (!lightingEnabled()) return 0;
        var c = Config.get(); var color = EmberPalette.accent();
        int hash = 31 * (31 * (31 * (1 + color.r) + color.g) + color.b) + Double.hashCode(c.worldDarkness.get());
        hash = 31 * hash + Double.hashCode(c.worldLightTint.get());
        hash = 31 * hash + Double.hashCode(c.vanillaLightMix.get());
        hash = 31 * hash + Double.hashCode(c.daylightBrightness.get());
        return 31 * hash + Double.hashCode(lightReaction());
    }
    public static Vector3fc lightTint(Vector3fc original) {
        if (!lightingEnabled()) return original;
        var accent = EmberPalette.accent();
        float light = Math.max(original.x(), Math.max(original.y(), original.z()))
            * CelestialLighting.colorExposure(accent.r << 16 | accent.g << 8 | accent.b);
        int max = Math.max(accent.r, Math.max(accent.g, accent.b));
        double strength = Config.get().worldLightTint.get() * lightBlend();
        return new Vector3f(CelestialLighting.channel(original.x(), accent.r, max, light, strength),
            CelestialLighting.channel(original.y(), accent.g, max, light, strength),
            CelestialLighting.channel(original.z(), accent.b, max, light, strength));
    }

    public static int skyTint(int original) {
        if (!skyActive()) return original;
        var accent = EmberPalette.accent();
        double amount = Config.get().skyIntensity.get();
        double exposure = skyExposure();
        int r = (int) (CelestialLighting.horizon(accent.r, amount) * exposure * 255);
        int g = (int) (CelestialLighting.horizon(accent.g, amount) * exposure * 255);
        int b = (int) (CelestialLighting.horizon(accent.b, amount) * exposure * 255);
        return (original & 0xFF000000) | r << 16 | g << 8 | b;
    }
    public static Vector4f fogTint(Vector4f original) {
        if (!fogEnabled() && !skyActive()) return original;
        var accent = EmberPalette.accent();
        if (skyActive()) {
            double intensity = Config.get().skyIntensity.get();
            float exposure = (float) skyExposure();
            return new Vector4f(CelestialLighting.horizon(accent.r, intensity) * exposure, CelestialLighting.horizon(accent.g, intensity) * exposure,
                CelestialLighting.horizon(accent.b, intensity) * exposure, original.w);
        }
        float lum = (original.x + original.y + original.z) / 3;
        double amount = Config.get().fogAmount.get();
        return new Vector4f(CelestialWorldGeometry.tint(original.x, accent.r, lum, amount),
            CelestialWorldGeometry.tint(original.y, accent.g, lum, amount),
            CelestialWorldGeometry.tint(original.z, accent.b, lum, amount), original.w);
    }

    private static void advance() {
        long now = System.nanoTime();
        if (world != mc.world) { world = mc.world; previous = now; time = 0; horizonEnvelope = 0; }
        double dt = previous == 0 ? 0 : Math.clamp((now - previous) / 1e9, 0, 0.1); previous = now;
        var c = Config.get();
        if (!mc.isPaused()) {
            time += dt * c.ambienceSpeed.get();
            double target = c.horizonBass.get() ? MusicReactive.level() : 0;
            horizonEnvelope = EmberAnim.approach(horizonEnvelope, target, dt, target > horizonEnvelope ? .65 : 1.8);
        }
    }

    /** Called inside Minecraft's sky pass, before cutouts, translucency, terrain and entities. */
    public static void renderSky(boolean background) {
        if (!openSky()) return;
        advance();
        var c = Config.get();
        var accent = EmberPalette.accent();
        int rgb = accent.r << 16 | accent.g << 8 | accent.b;
        double intensity = starIntensity();
        var camera = mc.gameRenderer.getCamera().getCameraPos();
        if (background && skyActive()) {
            if (sky == null) {
                sky = new Renderer3D(MeteorRenderPipelines.WORLD_COLORED_LINES_DEPTH, MeteorRenderPipelines.CELESTIAL_SKY);
                CelestialWorldGeometry.skyShell(SHELL);
            }
            sky.begin();
            // Sky shader takes direction-only vertices; Y is deliberately camera-relative here.
            CelestialRenderer.draw(SHELL, sky, camera.x, 0, camera.z, 0, 0);
            SkyUniforms.STORAGE.clear();
            var uniform = SkyUniforms.STORAGE.write(new SkyUniforms.Data(accent.r / 255f, accent.g / 255f, accent.b / 255f,
                c.skyIntensity.get().floatValue(), (float) time, c.celestialSky.get().ordinal(), (float) skyExposure(),
                c.horizonGlow.get() ? c.horizonStrength.get().floatValue() : 0, c.horizonWidth.get().floatValue(),
                (float) (1 + horizonEnvelope * c.horizonPulse.get() * .9)));
            submitSky(sky, true, uniform);
        }
        if (c.worldStars.get()) {
            STARS.clear();
            if (c.constellations.get()) CelestialWorldGeometry.constellations(STARS, time, c.ambienceDensity.get(),
                intensity * c.constellationStrength.get(), rgb, c.constellationCount.get());
            CelestialWorldGeometry.skyStars(STARS, time, c.ambienceDensity.get(), intensity, rgb);
            if (stars == null) stars = new Renderer3D(MeteorRenderPipelines.WORLD_COLORED_LINES_DEPTH, MeteorRenderPipelines.CELESTIAL_STARS);
            stars.begin(); CelestialRenderer.draw(STARS, stars, camera.x, 0, camera.z, 0, 0); submitSky(stars, false, null);
        }
    }

    private static void submitSky(Renderer3D renderer, boolean background, com.mojang.blaze3d.buffers.GpuBufferSlice skyData) {
        var mesh = renderer.triangles;
        renderer.lines.end(); mesh.end();
        if (mesh.getIndicesCount() == 0) return;
        // Uploads issue command-encoder writes, which are forbidden inside a render pass.
        // Prepare both buffers before opening the pass (including on the first sky frame).
        var vertexBuffer = mesh.getVertexBuffer();
        var indexBuffer = mesh.getIndexBuffer();
        var transforms = RenderSystem.getDynamicUniforms().write(RenderSystem.getModelViewMatrix(), new Vector4f(1), new Vector3f(), new Matrix4f());
        var target = mc.getFramebuffer();
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Ember celestial sky",
            target.getColorAttachmentView(), OptionalInt.empty(), target.getDepthAttachmentView(), OptionalDouble.empty())) {
            pass.setPipeline(background ? MeteorRenderPipelines.CELESTIAL_SKY : MeteorRenderPipelines.CELESTIAL_STARS);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            if (background) pass.setUniform("SkyData", skyData);
            pass.setVertexBuffer(0, vertexBuffer);
            pass.setIndexBuffer(indexBuffer, VertexFormat.IndexType.INT);
            pass.drawIndexed(0, 0, mesh.getIndicesCount(), 1);
        }
    }

    @EventHandler private static void onRender(Render3DEvent event) {
        if (!enabled()) { previous = 0; world = null; return; }
        advance();
        if (!effectsVisible()) return;
        var c = Config.get(); var accent = EmberPalette.accent();
        int rgb = accent.r << 16 | accent.g << 8 | accent.b;
        double intensity = starIntensity();
        if (c.worldMotes.get()) {
            MOTES.clear(); CelestialWorldGeometry.motes(MOTES, event.offsetX, event.offsetY, event.offsetZ, time, c.ambienceDensity.get(), intensity, rgb);
            if (motes == null) motes = new Renderer3D(MeteorRenderPipelines.WORLD_COLORED_LINES_DEPTH, MeteorRenderPipelines.WORLD_ACCENT_LIGHT);
            motes.begin(); CelestialRenderer.draw(MOTES, motes, event.offsetX, event.offsetY, event.offsetZ, 0, 0); motes.render(event.matrices);
        }
    }

    private static double starIntensity() {
        var c = Config.get();
        return Math.clamp(c.ambienceIntensity.get() * c.starBrightness.get()
            * (1 + (c.bassStars.get() ? MusicReactive.level() * c.bassStrength.get() * 0.8 : 0)), 0, 2);
    }

    private static final class SkyUniforms {
        static final DynamicUniformStorage<Data> STORAGE = new DynamicUniformStorage<>("Ember - Celestial sky", 48, 2);
        private record Data(float r, float g, float b, float intensity, float time, float style, float exposure,
                            float horizon, float width, float pulse) implements DynamicUniformStorage.Uploadable {
            @Override public void write(ByteBuffer buffer) {
                Std140Builder.intoBuffer(buffer).putVec4(r, g, b, intensity).putVec4(time, style, exposure, 0).putVec4(horizon, width, pulse, 0);
            }
        }
    }
}
