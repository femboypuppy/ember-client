package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.*;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.gl.DynamicUniformStorage;
import net.minecraft.entity.effect.StatusEffects;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/** World-space celestial atmosphere. Far-depth sky and scene-depth motes have separate pipelines. */
public final class EmberWorldAtmosphere {
    private static final CelestialMesh SHELL = new CelestialMesh(3072), STARS = new CelestialMesh(60000), MOTES = new CelestialMesh(24000);
    private static Renderer3D sky, stars, motes;
    private static long previous;
    private static Object world;
    private static double time;
    private EmberWorldAtmosphere() {}

    @PostInit public static void init() { MeteorClient.EVENT_BUS.subscribe(EmberWorldAtmosphere.class); }
    public static double time() { return time; }

    private static boolean enabled() {
        var c = Config.get();
        return c != null && c.ambience.get() && c.worldAtmosphere.get() && mc.world != null && mc.player != null;
    }
    private static boolean clearAir() {
        return mc.gameRenderer.getCamera().getSubmersionType() == CameraSubmersionType.NONE
            && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS) && !mc.player.hasStatusEffect(StatusEffects.DARKNESS);
    }
    public static boolean openSky() { return enabled() && mc.world.getDimension().hasSkyLight() && clearAir(); }
    public static boolean fogEnabled() { return openSky() && Config.get().accentFog.get() && Config.get().fogAmount.get() > 0; }

    public static int skyTint(int original) {
        if (!openSky() || !Config.get().accentSky.get()) return original;
        var accent = EmberPalette.accent();
        float lum = (((original >> 16) & 255) + ((original >> 8) & 255) + (original & 255)) / 765f;
        double amount = Config.get().skyIntensity.get();
        int r = (int) (CelestialWorldGeometry.tint((original >> 16 & 255) / 255f, accent.r, lum, amount) * 255);
        int g = (int) (CelestialWorldGeometry.tint((original >> 8 & 255) / 255f, accent.g, lum, amount) * 255);
        int b = (int) (CelestialWorldGeometry.tint((original & 255) / 255f, accent.b, lum, amount) * 255);
        return (original & 0xFF000000) | r << 16 | g << 8 | b;
    }
    public static Vector4f fogTint(Vector4f original) {
        if (!fogEnabled()) return original;
        var accent = EmberPalette.accent();
        float lum = (original.x + original.y + original.z) / 3;
        double amount = Config.get().fogAmount.get();
        return new Vector4f(CelestialWorldGeometry.tint(original.x, accent.r, lum, amount),
            CelestialWorldGeometry.tint(original.y, accent.g, lum, amount),
            CelestialWorldGeometry.tint(original.z, accent.b, lum, amount), original.w);
    }

    @EventHandler private static void onRender(Render3DEvent event) {
        if (!enabled()) { previous = 0; world = null; return; }
        long now = System.nanoTime();
        if (world != mc.world) { world = mc.world; previous = now; time = 0; }
        double dt = previous == 0 ? 0 : Math.clamp((now - previous) / 1e9, 0, 0.1); previous = now;
        var c = Config.get();
        if (!mc.isPaused()) time += dt * c.ambienceSpeed.get();
        if (!clearAir()) return;
        var accent = EmberPalette.accent();
        int rgb = accent.r << 16 | accent.g << 8 | accent.b;
        double intensity = c.ambienceIntensity.get();
        if (openSky()) {
            if (c.accentSky.get() && c.skyIntensity.get() > 0) {
                if (sky == null) {
                    sky = new Renderer3D(MeteorRenderPipelines.WORLD_COLORED_LINES_DEPTH, MeteorRenderPipelines.CELESTIAL_SKY);
                    CelestialWorldGeometry.skyShell(SHELL);
                }
                sky.begin();
                // Sky shader takes direction-only vertices; Y is deliberately camera-relative here.
                CelestialRenderer.draw(SHELL, sky, event.offsetX, 0, event.offsetZ, 0, 0);
                SkyUniforms.STORAGE.clear();
                var uniform = SkyUniforms.STORAGE.write(new SkyUniforms.Data(accent.r / 255f, accent.g / 255f, accent.b / 255f,
                    c.skyIntensity.get().floatValue(), (float) time, c.celestialSky.get().ordinal()));
                MeshRenderer.begin().attachments(mc.getFramebuffer()).pipeline(MeteorRenderPipelines.CELESTIAL_SKY)
                    .mesh(sky.triangles, event.matrices).uniform("SkyData", uniform).end();
                sky.lines.end();
            }
            if (c.worldStars.get()) {
                STARS.clear(); CelestialWorldGeometry.skyStars(STARS, time, c.ambienceDensity.get(), intensity, rgb);
                if (stars == null) stars = new Renderer3D(MeteorRenderPipelines.WORLD_COLORED_LINES_DEPTH, MeteorRenderPipelines.CELESTIAL_STARS);
                stars.begin(); CelestialRenderer.draw(STARS, stars, event.offsetX, 0, event.offsetZ, 0, 0); stars.render(event.matrices);
            }
        }
        if (c.worldStars.get()) {
            MOTES.clear(); CelestialWorldGeometry.motes(MOTES, event.offsetX, event.offsetY, event.offsetZ, time, c.ambienceDensity.get(), intensity, rgb);
            if (motes == null) motes = new Renderer3D(MeteorRenderPipelines.WORLD_COLORED_LINES_DEPTH, MeteorRenderPipelines.WORLD_ACCENT_LIGHT);
            motes.begin(); CelestialRenderer.draw(MOTES, motes, event.offsetX, event.offsetY, event.offsetZ, 0, 0); motes.render(event.matrices);
        }
    }

    private static final class SkyUniforms {
        static final DynamicUniformStorage<Data> STORAGE = new DynamicUniformStorage<>("Ember - Celestial sky", 32, 2);
        private record Data(float r, float g, float b, float opacity, float time, float style) implements DynamicUniformStorage.Uploadable {
            @Override public void write(ByteBuffer buffer) {
                Std140Builder.intoBuffer(buffer).putVec4(r, g, b, opacity).putVec4(time, style, 0, 0);
            }
        }
    }
}
