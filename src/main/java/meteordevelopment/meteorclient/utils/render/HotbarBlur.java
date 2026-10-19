package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ResolutionChangedEvent;
import meteordevelopment.meteorclient.events.render.RenderAfterWorldEvent;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gl.DynamicUniformStorage;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.texture.TextureSetup;
import org.joml.Matrix3x2f;

import java.nio.ByteBuffer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** A separate scene-only blur; menu glass settings and sharp native item rendering are unaffected. */
public final class HotbarBlur {
    private static final GpuTexture[] TEXTURES = new GpuTexture[4];
    private static final GpuTextureView[] VIEWS = new GpuTextureView[4];
    private static DynamicUniformStorage<BlurData> uniforms;
    private static int frameWidth, frameHeight;
    private static boolean ready, failed;

    private HotbarBlur() {}
    @PostInit public static void init() { MeteorClient.EVENT_BUS.subscribe(HotbarBlur.class); }
    @EventHandler private static void onLeft(GameLeftEvent event) { release(); failed = false; }
    @EventHandler private static void onResize(ResolutionChangedEvent event) { release(); failed = false; }

    // Run after world bloom and before native GUI extraction. Never sample already-drawn HUD items.
    @EventHandler(priority = -2000) private static void capture(RenderAfterWorldEvent event) {
        ready = false;
        Config c = Config.get();
        if (c == null || !c.pillHotbar.get() || c.hotbarBlur.get() <= 0 || mc.world == null
            || mc.player == null || mc.options.hudHidden || mc.player.isSpectator()) {
            release(); failed = false; return;
        }
        if (failed || mc.getFramebuffer() == null) return;
        int w = mc.getWindow().getFramebufferWidth(), h = mc.getWindow().getFramebufferHeight();
        if (w <= 0 || h <= 0) return;
        try {
            if (VIEWS[0] == null || w != frameWidth || h != frameHeight) {
                release(); frameWidth = w; frameHeight = h;
                for (int i = 0; i < VIEWS.length; i++) {
                    TEXTURES[i] = RenderSystem.getDevice().createTexture("Ember hotbar blur " + i, 15, TextureFormat.RGBA8,
                        Math.max(1, w >> (i + 1)), Math.max(1, h >> (i + 1)), 1, 1);
                    VIEWS[i] = RenderSystem.getDevice().createTextureView(TEXTURES[i]);
                }
            }
            if (uniforms == null) uniforms = new DynamicUniformStorage<>("Ember hotbar blur", 16, 8);
            uniforms.clear();
            double amount = c.hotbarBlur.get();
            float offset = (float) (.35 + amount * 4);
            int levels = 1 + (int) Math.round(amount * 2);
            pass(VIEWS[0], mc.getFramebuffer().getColorAttachmentView(), false, offset);
            for (int i = 1; i <= levels; i++) pass(VIEWS[i], VIEWS[i - 1], false, offset);
            for (int i = levels; i > 0; i--) pass(VIEWS[i - 1], VIEWS[i], true, offset);
            ready = true;
        } catch (RuntimeException error) {
            release(); failed = true;
            MeteorClient.LOG.error("Hotbar blur failed; using the flat hotbar. Set Hotbar blur to zero to reset.", error);
        }
    }

    public static void draw(DrawContext context, int x, int y, int width, int height, float radius) {
        var c = Config.get();
        if (!ready || VIEWS[0] == null || c == null || !c.pillHotbar.get() || c.hotbarBlur.get() <= 0) return;
        var pose = new Matrix3x2f(context.getMatrices());
        ScreenRect scissor = context.scissorStack.peekLast();
        ScreenRect bounds = new ScreenRect(x, y, width, height).transformEachVertex(pose);
        if (scissor != null) bounds = bounds.intersection(scissor);
        context.state.addSimpleElement(new HotbarBlurPane(pose, TextureSetup.of(VIEWS[0], RenderSystem.getSamplerCache().get(FilterMode.LINEAR)),
            x, y, width, height, radius, (float) mc.getWindow().getScaleFactor(), frameWidth, frameHeight, scissor, bounds));
    }

    private static void pass(GpuTextureView target, GpuTextureView source, boolean up, float offset) {
        GpuBufferSlice data = uniforms.write(new BlurData(.5f / source.getWidth(0), .5f / source.getHeight(0), offset));
        MeshRenderer.begin().attachments(target, null)
            .pipeline(up ? MeteorRenderPipelines.BLUR_UP : MeteorRenderPipelines.BLUR_DOWN).fullscreen()
            .uniform("BlurData", data).sampler("u_Texture", source, RenderSystem.getSamplerCache().get(FilterMode.LINEAR)).end();
    }

    private static void release() {
        ready = false;
        for (int i = 0; i < VIEWS.length; i++) {
            if (VIEWS[i] != null) { VIEWS[i].close(); VIEWS[i] = null; }
            if (TEXTURES[i] != null) { TEXTURES[i].close(); TEXTURES[i] = null; }
        }
    }

    private record BlurData(float halfX, float halfY, float offset) implements DynamicUniformStorage.Uploadable {
        @Override public void write(ByteBuffer buffer) { Std140Builder.intoBuffer(buffer).putVec2(halfX, halfY).putFloat(offset); }
    }
}
