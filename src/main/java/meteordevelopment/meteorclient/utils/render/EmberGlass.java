package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ResolutionChangedEvent;
import meteordevelopment.meteorclient.events.render.RenderAfterWorldEvent;
import meteordevelopment.meteorclient.renderer.FixedUniformStorage;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.orbit.listeners.ConsumerListener;
import net.minecraft.client.gl.DynamicUniformStorage;

import java.nio.ByteBuffer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Keeps a blurred copy of the scene so panels can be filled with what is actually behind
 * them, which is what makes glass read as glass rather than as a tinted rectangle.
 *
 * The blur chain is the same downsample-upsample Meteor's Blur module uses. The difference is
 * the last step: Blur blits the result back over the whole framebuffer, blurring everything on
 * screen. This keeps the result in a texture and hands it out, so each panel can sample just
 * the part of it that sits behind that panel.
 *
 * Capture happens after the world draws and before the HUD does, so the blur contains the
 * world and never the interface - a panel sampling a frame that already had panels in it
 * would smear itself.
 */
public final class EmberGlass {
    private static final int LEVELS = 5;
    /** Downsample steps and sampling offset; roughly Blur's level eight. */
    private static final int ITERATIONS = 3;
    private static final float OFFSET = 4.25f;

    private static final GpuTextureView[] fbos = new GpuTextureView[LEVELS + 1];
    private static GpuBufferSlice[] ubos;

    private static boolean ready;
    private static int builtWidth, builtHeight;

    private EmberGlass() {
    }

    @PreInit
    public static void init() {
        // Textures are not created here: this runs long before there is a device to create
        // them on. The first capture builds them.
        MeteorClient.EVENT_BUS.subscribe(new ConsumerListener<>(ResolutionChangedEvent.class, event -> release()));
        MeteorClient.EVENT_BUS.subscribe(new ConsumerListener<>(RenderAfterWorldEvent.class, event -> capture()));
    }

    /** True when a blurred frame is available to sample this frame. */
    public static boolean ready() {
        return ready && fbos[0] != null;
    }

    public static GpuTextureView texture() {
        return fbos[0];
    }

    public static net.minecraft.client.gl.GpuSampler sampler() {
        return RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
    }

    private static void release() {
        for (int i = 0; i < fbos.length; i++) {
            if (fbos[i] != null) {
                fbos[i].close();
                fbos[i] = null;
            }
        }

        ubos = null;
        ready = false;
    }

    private static void capture() {
        ready = false;

        // Only worth the passes when something is actually going to sample it.
        if (!EmberAppearance.frosted()) return;
        if (mc.getFramebuffer() == null) return;

        int width = mc.getWindow().getFramebufferWidth();
        int height = mc.getWindow().getFramebufferHeight();
        if (width <= 0 || height <= 0) return;

        try {
            if (fbos[0] == null || width != builtWidth || height != builtHeight) {
                release();
                build(width, height);
            }

            // Scene into the first level, then down and back up.
            renderToFbo(fbos[0], mc.getFramebuffer().getColorAttachmentView(), MeteorRenderPipelines.BLUR_DOWN, ubos[0]);

            for (int i = 0; i < ITERATIONS; i++) {
                renderToFbo(fbos[i + 1], fbos[i], MeteorRenderPipelines.BLUR_DOWN, ubos[i + 1]);
            }

            for (int i = ITERATIONS; i >= 1; i--) {
                renderToFbo(fbos[i - 1], fbos[i], MeteorRenderPipelines.BLUR_UP, ubos[i - 1]);
            }

            ready = true;
        } catch (Throwable e) {
            // A driver or pipeline problem must not take the whole frame down; the panels
            // simply fall back to their flat fill.
            MeteorClient.LOG.error("Ember glass capture failed, falling back to flat panels", e);
            release();
        }
    }

    private static void build(int width, int height) {
        for (int i = 0; i < fbos.length; i++) {
            double scale = 1 / Math.pow(2, i);
            int w = Math.max(1, (int) (width * scale));
            int h = Math.max(1, (int) (height * scale));

            fbos[i] = RenderSystem.getDevice().createTextureView(
                RenderSystem.getDevice().createTexture("Ember Glass - " + i, 15, TextureFormat.RGBA8, w, h, 1, 1));
        }

        builtWidth = width;
        builtHeight = height;

        UNIFORM_STORAGE.clear();

        BlurUniformData[] data = new BlurUniformData[fbos.length];
        for (int i = 0; i < fbos.length; i++) {
            data[i] = new BlurUniformData(0.5f / fbos[i].getWidth(0), 0.5f / fbos[i].getHeight(0), OFFSET);
        }

        ubos = UNIFORM_STORAGE.writeAll(data);
    }

    private static void renderToFbo(GpuTextureView target, GpuTextureView source, RenderPipeline pipeline, GpuBufferSlice ubo) {
        MeshRenderer.begin()
            .attachments(target, null)
            .pipeline(pipeline)
            .fullscreen()
            .uniform("BlurData", ubo)
            .sampler("u_Texture", source, RenderSystem.getSamplerCache().get(FilterMode.LINEAR))
            .end();
    }

    private static final int UNIFORM_SIZE = new Std140SizeCalculator()
        .putVec2()
        .putFloat()
        .get();

    private static final FixedUniformStorage<BlurUniformData> UNIFORM_STORAGE =
        new FixedUniformStorage<>("Ember - Glass UBO", UNIFORM_SIZE, LEVELS + 1);

    private record BlurUniformData(float halfTexelSizeX, float halfTexelSizeY,
                                   float offset) implements DynamicUniformStorage.Uploadable {
        @Override
        public void write(ByteBuffer buffer) {
            Std140Builder.intoBuffer(buffer)
                .putVec2(halfTexelSizeX, halfTexelSizeY)
                .putFloat(offset);
        }
    }
}
