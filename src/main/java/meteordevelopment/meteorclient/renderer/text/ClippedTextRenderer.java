package meteordevelopment.meteorclient.renderer.text;

import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.font.TextRenderer.TextLayerType;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.text.Text;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Uses Minecraft's Unicode font fallback and owns a single batch per HUD frame. */
public final class ClippedTextRenderer {
    private final BufferAllocator buffer = new BufferAllocator(2048);
    private final VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(buffer);
    private final ClippedGlyphConsumer clipper = new ClippedGlyphConsumer();
    private final Matrix4f matrix = new Matrix4f();
    private record Draw(String text, double x, double y, int color, double scale, boolean shadow,
                        double left, double right, double top, double bottom) {}
    private final List<Draw> pending = new ArrayList<>();

    public static double width(String text, double scale) { return mc.textRenderer.getWidth(Text.literal(text)) * scale; }

    public void draw(String text, double x, double y, Color color, double scale, boolean shadow,
                     double left, double right, double top, double bottom) {
        if (color.a <= 0 || right <= left || bottom <= top || text.isEmpty()) return;
        pending.add(new Draw(text, x, y, color.getPacked(), scale, shadow, left, right, top, bottom));
    }

    public void end() {
        if (pending.isEmpty()) return;
        // Switching a native font atlas can flush Immediate inside getBuffer(). Defer
        // the text itself so even that flush happens after the HUD's glass/backgrounds.
        try {
            for (Draw draw : pending) {
                clipper.bounds(draw.left, draw.top, draw.right, draw.bottom);
                matrix.identity().scaling((float) draw.scale, (float) draw.scale, 1);
                mc.textRenderer.draw(Text.literal(draw.text), (float) (draw.x / draw.scale), (float) (draw.y / draw.scale),
                    draw.color, draw.shadow, matrix, layer -> clipper.target(immediate.getBuffer(layer)),
                    TextLayerType.NORMAL, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            }
            immediate.draw();
        } finally { pending.clear(); }
    }
}
