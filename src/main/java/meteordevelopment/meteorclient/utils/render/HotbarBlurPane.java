package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import org.joml.Matrix3x2f;

/** Rounded, antialiased native GUI geometry sampling the scene at its screen position. */
public record HotbarBlurPane(Matrix3x2f pose, TextureSetup textureSetup, int x, int y, int width, int height,
                             float radius, float pixelScale, int frameWidth, int frameHeight,
                             ScreenRect scissorArea, ScreenRect bounds) implements SimpleGuiElementRenderState {
    @Override public RenderPipeline pipeline() { return RenderPipelines.GUI_TEXTURED; }

    @Override public void setupVertices(VertexConsumer vertices) {
        float inset = Math.min(radius, 1 / pixelScale);
        float cx = x + width / 2f, cy = y + height / 2f;
        // Nine samples per corner; straight edges connect consecutive corners.
        for (int i = 0; i < 36; i++) {
            Point a = point(i, radius - inset), b = point((i + 1) % 36, radius - inset);
            vertex(vertices, cx, cy, -1);
            vertex(vertices, b.x, b.y, -1);
            vertex(vertices, a.x, a.y, -1);
            vertex(vertices, a.x, a.y, -1); // Degenerate half of the native quad.
            Point outerA = point(i, radius), outerB = point((i + 1) % 36, radius);
            vertex(vertices, a.x, a.y, -1);
            vertex(vertices, b.x, b.y, -1);
            vertex(vertices, outerB.x, outerB.y, 0xFFFFFF);
            vertex(vertices, outerA.x, outerA.y, 0xFFFFFF);
        }
    }

    private Point point(int index, float r) {
        int corner = index / 9;
        double angle = Math.toRadians(-90 + corner * 90 + index % 9 * 90.0 / 8);
        float centerX = x + (corner == 0 || corner == 1 ? width - radius : radius);
        float centerY = y + (corner == 0 || corner == 3 ? radius : height - radius);
        return new Point(centerX + (float) Math.cos(angle) * r, centerY + (float) Math.sin(angle) * r);
    }

    private void vertex(VertexConsumer vertices, float px, float py, int color) {
        float screenX = pose.m00() * px + pose.m10() * py + pose.m20();
        float screenY = pose.m01() * px + pose.m11() * py + pose.m21();
        vertices.vertex(pose, px, py)
            .texture(screenX * pixelScale / frameWidth, 1 - screenY * pixelScale / frameHeight).color(color);
    }

    private record Point(float x, float y) {}
}
