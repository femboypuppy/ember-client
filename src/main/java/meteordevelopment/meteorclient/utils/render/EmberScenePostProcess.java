package meteordevelopment.meteorclient.utils.render;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.*;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.render.RenderAfterWorldEvent;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gl.DynamicUniformStorage;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.nio.ByteBuffer;

/** Haze runs before hands; bloom runs before the HUD and the glass capture. */
public final class EmberScenePostProcess {
    private static final int LEVELS = 4;
    private static final GpuTexture[] TEXTURES = new GpuTexture[LEVELS];
    private static final GpuTextureView[] VIEWS = new GpuTextureView[LEVELS];
    private static int width, height;
    private static boolean bloomFailed, fogFailed;
    private EmberScenePostProcess() {}
    @PostInit public static void init() { MeteorClient.EVENT_BUS.subscribe(EmberScenePostProcess.class); }

    @EventHandler private static void onLeft(GameLeftEvent event) { release(); bloomFailed = fogFailed = false; }

    @EventHandler(priority = 1000) private static void fog(Render3DEvent event) {
        var c = Config.get();
        if (c == null || !c.depthFog.get() || !EmberWorldAtmosphere.fogAirEnabled() || fogFailed) return;
        var target = mc.getFramebuffer();
        if (target == null || target.getDepthAttachmentView() == null) return;
        var range = AtmosphereFog.range(c.fogStart.get(), c.fogEnd.get(), c.fogAmount.get());
        var tint = EmberWorldAtmosphere.fogTint(new Vector4f(.4f, .45f, .5f, 1));
        try {
            FogUniforms.STORAGE.clear();
            var data = FogUniforms.STORAGE.write(new FogData(new Matrix4f(RenderUtils.projection).invert(), tint,
                range.start(), range.end(), range.opacity()));
            // Sample depth without binding it as an attachment in the same pass.
            MeshRenderer.begin().attachments(target.getColorAttachmentView(), null)
                .pipeline(MeteorRenderPipelines.SCENE_FOG).fullscreen().uniform("SceneFogData", data)
                .sampler("u_Depth", target.getDepthAttachmentView(), RenderSystem.getSamplerCache().get(FilterMode.NEAREST)).end();
        } catch (RuntimeException error) {
            fogFailed = true;
            MeteorClient.LOG.error("Ember depth fog failed; disable Depth-based fog to use native haze", error);
        }
    }

    @EventHandler(priority = 1000) private static void bloom(RenderAfterWorldEvent event) {
        var c = Config.get();
        if (c == null || mc.world == null || mc.player == null || !c.ambience.get() || !c.worldAtmosphere.get()
            || !c.bloom.get() || c.bloomStrength.get() <= 0) { release(); bloomFailed = false; return; }
        if (bloomFailed) return;
        var target = mc.getFramebuffer(); if (target == null) return;
        int w = mc.getWindow().getFramebufferWidth(), h = mc.getWindow().getFramebufferHeight();
        if (w <= 0 || h <= 0) return;
        try {
            if (VIEWS[0] == null || width != w || height != h) {
                release(); width = w; height = h;
                for (int i = 0; i < LEVELS; i++) {
                    TEXTURES[i] = RenderSystem.getDevice().createTexture("Ember bloom " + i, 15, TextureFormat.RGBA8,
                        Math.max(1, w >> (i + 1)), Math.max(1, h >> (i + 1)), 1, 1);
                    VIEWS[i] = RenderSystem.getDevice().createTextureView(TEXTURES[i]);
                }
            }
            BloomUniforms.STORAGE.clear();
            var settings = BloomUniforms.STORAGE.write(new BloomData(c.bloomThreshold.get().floatValue(), c.bloomStrength.get().floatValue()));
            draw(VIEWS[0], target.getColorAttachmentView(), MeteorRenderPipelines.BLOOM_EXTRACT, "BloomData", settings);
            BlurUniforms.STORAGE.clear();
            float radius = (float)(.6 + c.bloomRadius.get() * 2);
            for (int i = 1; i < LEVELS; i++)
                draw(VIEWS[i], VIEWS[i - 1], MeteorRenderPipelines.BLUR_DOWN, "BlurData", blur(VIEWS[i - 1], radius));
            for (int i = LEVELS - 1; i > 0; i--)
                draw(VIEWS[i - 1], VIEWS[i], MeteorRenderPipelines.BLUR_UP, "BlurData", blur(VIEWS[i], radius));
            draw(target.getColorAttachmentView(), VIEWS[0], MeteorRenderPipelines.BLOOM_COMPOSITE, "BloomData", settings);
        } catch (RuntimeException error) {
            release(); bloomFailed = true;
            MeteorClient.LOG.error("Ember bloom failed; toggle Bloom off and on to retry", error);
        }
    }

    private static GpuBufferSlice blur(GpuTextureView source, float radius) {
        return BlurUniforms.STORAGE.write(new BlurData(.5f / source.getWidth(0), .5f / source.getHeight(0), radius));
    }
    private static void draw(GpuTextureView target, GpuTextureView source, RenderPipeline pipeline, String uniform, GpuBufferSlice data) {
        MeshRenderer.begin().attachments(target, null).pipeline(pipeline).fullscreen().uniform(uniform, data)
            .sampler("u_Texture", source, RenderSystem.getSamplerCache().get(FilterMode.LINEAR)).end();
    }
    private static void release() {
        for (int i = 0; i < LEVELS; i++) {
            if (VIEWS[i] != null) { VIEWS[i].close(); VIEWS[i] = null; }
            if (TEXTURES[i] != null) { TEXTURES[i].close(); TEXTURES[i] = null; }
        }
        width = height = 0;
    }
    private static final class FogUniforms { static final DynamicUniformStorage<FogData> STORAGE = new DynamicUniformStorage<>("Ember depth fog", 96, 2); }
    private static final class BloomUniforms { static final DynamicUniformStorage<BloomData> STORAGE = new DynamicUniformStorage<>("Ember bloom", 16, 2); }
    private static final class BlurUniforms { static final DynamicUniformStorage<BlurData> STORAGE = new DynamicUniformStorage<>("Ember bloom blur", 16, 8); }
    private record FogData(Matrix4f inverse, Vector4f color, float start, float end, float density) implements DynamicUniformStorage.Uploadable {
        @Override public void write(ByteBuffer b) { Std140Builder.intoBuffer(b).putMat4f(inverse).putVec4(color.x, color.y, color.z, color.w).putVec4(start, end, density, 0); }
    }
    private record BloomData(float threshold, float strength) implements DynamicUniformStorage.Uploadable {
        @Override public void write(ByteBuffer b) { Std140Builder.intoBuffer(b).putVec4(threshold, strength, 0, 0); }
    }
    private record BlurData(float x, float y, float radius) implements DynamicUniformStorage.Uploadable {
        @Override public void write(ByteBuffer b) { Std140Builder.intoBuffer(b).putVec2(x, y).putFloat(radius); }
    }
}
