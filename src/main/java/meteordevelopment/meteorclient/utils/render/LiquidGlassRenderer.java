package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.client.gl.DynamicUniformStorage;

import java.nio.ByteBuffer;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** One quad per pane. The shader rounds, refracts and lights it per pixel. */
public final class LiquidGlassRenderer {
    private static final int SIZE = new Std140SizeCalculator()
        .putVec4().putVec4().putVec4().putVec4().get();
    private static DynamicUniformStorage<PaneData> storage;
    public static final int BATCH_CAPACITY = 64;
    private static final int BATCH_SIZE = 48 + BATCH_CAPACITY * 32;
    private static DynamicUniformStorage<BatchData> batchStorage;
    private static Renderer2D quad;
    private static final long START = System.nanoTime();

    private LiquidGlassRenderer() {}

    public static boolean available() {
        return EmberAppearance.liquid() && EmberGlass.ready();
    }

    public static void flipFrame() {
        if (storage != null) storage.clear();
        if (batchStorage != null) batchStorage.clear();
    }

    public record Pane(double x, double y, double w, double h, double radius, double opacity) {}

    /** HUD panes already share a background layer. Preserve their order in one instanced draw. */
    public static void drawAll(List<Pane> panes) {
        if (panes.isEmpty() || !available()) return;
        if (batchStorage == null) batchStorage = new DynamicUniformStorage<>("Ember HUD glass batches", BATCH_SIZE, 8);
        Color accent = EmberPalette.accent();
        float time = (float) ((System.nanoTime() - START) / 1_000_000_000.0 * EmberAppearance.motion() % 3600);
        float mx = mc.currentScreen == null ? -10000 : (float) (mc.mouse.getScaledX(mc.getWindow()) * mc.getWindow().getScaleFactor());
        float my = mc.currentScreen == null ? -10000 : (float) (mc.mouse.getScaledY(mc.getWindow()) * mc.getWindow().getScaleFactor());
        float r = EmberAppearance.glassAccent() ? accent.r / 255f : 0;
        float g = EmberAppearance.glassAccent() ? accent.g / 255f : 0;
        float b = EmberAppearance.glassAccent() ? accent.b / 255f : 0;
        for (int first = 0; first < panes.size(); first += BATCH_CAPACITY) {
            int count = Math.min(BATCH_CAPACITY, panes.size() - first);
            var data = batchStorage.write(new BatchData(panes, first, count, time,
                (float) EmberAppearance.glass(), (float) EmberAppearance.glassLiquid(), (float) EmberAppearance.glassTint(),
                r, g, b, Utils.getWindowWidth(), Utils.getWindowHeight(), mx, my));
            MeshRenderer.begin().attachments(mc.getFramebuffer()).pipeline(MeteorRenderPipelines.UI_LIQUID_GLASS_BATCH)
                .fullscreen().instances(count).sampler("u_Texture", EmberGlass.texture(), EmberGlass.sampler())
                .uniform("GlassBatchData", data).end();
        }
    }

    public static boolean draw(double x, double y, double w, double h, double radius, double opacity) {
        if (!available() || w <= 0 || h <= 0 || opacity <= 0.001) return false;
        if (quad == null) quad = new Renderer2D(true);
        if (storage == null) storage = new DynamicUniformStorage<>("Ember Liquid Glass", SIZE, 128);

        Color accent = EmberPalette.accent();
        float time = (float) ((System.nanoTime() - START) / 1_000_000_000.0 * EmberAppearance.motion() % 3600);
        double mx = mc.currentScreen == null ? -10000 : mc.mouse.getScaledX(mc.getWindow()) * mc.getWindow().getScaleFactor();
        double my = mc.currentScreen == null ? -10000 : mc.mouse.getScaledY(mc.getWindow()) * mc.getWindow().getScaleFactor();
        GpuBufferSlice data = storage.write(new PaneData(
            (float) x, (float) y, (float) w, (float) h,
            (float) Math.max(0, Math.min(radius, Math.min(w, h) / 2)), time,
            (float) EmberAppearance.glass(), (float) EmberAppearance.glassLiquid(),
            EmberAppearance.glassAccent() ? accent.r / 255f : 0,
            EmberAppearance.glassAccent() ? accent.g / 255f : 0,
            EmberAppearance.glassAccent() ? accent.b / 255f : 0, (float) EmberAppearance.glassTint(),
            Utils.getWindowWidth(), Utils.getWindowHeight(), (float) mx, (float) my));

        quad.begin();
        quad.texQuad(x - 1, y - 1, w + 2, h + 2, new Color(255, 255, 255, (int) (255 * Math.clamp(opacity, 0, 1))));
        quad.end();
        MeshRenderer.begin()
            .attachments(mc.getFramebuffer())
            .pipeline(MeteorRenderPipelines.UI_LIQUID_GLASS)
            .mesh(quad.triangles)
            .sampler("u_Texture", EmberGlass.texture(), EmberGlass.sampler())
            .uniform("GlassData", data)
            .end();
        return true;
    }

    private record PaneData(float x, float y, float w, float h,
                            float radius, float time, float density, float refraction,
                            float r, float g, float b, float tint,
                            float screenW, float screenH, float mouseX, float mouseY)
        implements DynamicUniformStorage.Uploadable {
        @Override
        public void write(ByteBuffer buffer) {
            Std140Builder.intoBuffer(buffer)
                .putVec4(x, y, w, h)
                .putVec4(radius, time, density, refraction)
                .putVec4(r, g, b, tint)
                .putVec4(screenW, screenH, mouseX, mouseY);
        }
    }

    private record BatchData(List<Pane> panes, int first, int count, float time, float density, float refraction,
                             float tint, float r, float g, float b, float screenW, float screenH, float mouseX, float mouseY)
        implements DynamicUniformStorage.Uploadable {
        @Override public void write(ByteBuffer buffer) {
            var builder = Std140Builder.intoBuffer(buffer).putVec4(time, density, refraction, tint)
                .putVec4(r, g, b, 0).putVec4(screenW, screenH, mouseX, mouseY);
            for (int i = 0; i < BATCH_CAPACITY; i++) {
                if (i < count) {
                    Pane p = panes.get(first + i);
                    builder.putVec4((float) p.x, (float) p.y, (float) p.w, (float) p.h)
                        .putVec4((float) Math.clamp(p.radius, 0, Math.min(p.w, p.h) / 2),
                            (int) (255 * Math.clamp(p.opacity, 0, 1)) / 255f, 0, 0);
                } else builder.putVec4(0, 0, 0, 0).putVec4(0, 0, 0, 0);
            }
        }
    }
}
