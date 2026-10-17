package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import meteordevelopment.meteorclient.renderer.*;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gl.DynamicUniformStorage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** A real projected 3D mesh using the same surface shader as the world models. */
public final class VisualPreviewRenderer {
    public record Part(double x, double y, double z, double w, double h, double d, double u, double v, double uw, double vh) {}
    private record Face(double[] xy, double[] uv, double depth, double light) {}
    private static final MeshBuilder MESH = new MeshBuilder(MeteorRenderPipelines.CHAMS_PREVIEW);
    private static final Renderer2D LINES = new Renderer2D(false);
    private static final DynamicUniformStorage<Data> UNIFORMS = new DynamicUniformStorage<>("Ember material preview", 32, 8);
    private record Data(float time, float material, float texture, float strength, float r, float g, float b) implements DynamicUniformStorage.Uploadable {
        @Override public void write(ByteBuffer buffer) { Std140Builder.intoBuffer(buffer).putVec4(time, material, texture, strength).putVec4(r, g, b, 1); }
    }
    public static void flipFrame() { UNIFORMS.clear(); }

    public static List<Part> player() {
        return List.of(new Part(-.25, .5, -.25, .5, .5, .5, .125, .125, .125, .125),
            new Part(-.25, -.25, -.125, .5, .75, .25, .3125, .3125, .125, .1875),
            new Part(-.5, -.25, -.125, .25, .75, .25, .6875, .3125, .0625, .1875),
            new Part(.25, -.25, -.125, .25, .75, .25, .5625, .8125, .0625, .1875),
            new Part(-.25, -1, -.125, .25, .75, .25, .0625, .3125, .0625, .1875),
            new Part(0, -1, -.125, .25, .75, .25, .3125, .8125, .0625, .1875));
    }

    public static void draw(List<Part> parts, double x, double y, double width, double height, double yaw, double pitch, double zoom,
                            double roundness, Color fill, Color line, boolean sides, boolean outlines,
                            int material, boolean animate, int texture, double strength) {
        double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
        List<Face> faces = new ArrayList<>();
        double scale = Math.min(width, height) * .34 * zoom;
        double cx = x + width / 2, centerY = y + height / 2;
        var geometry = RoundedBoxMesh.get(roundness);
        MESH.begin(); LINES.begin();
        for (Part part : parts) {
            for (var face : geometry.faces) {
                double nx = face.nx() * cy + face.nz() * sy, nz = -face.nx() * sy + face.nz() * cy;
                double ny = face.ny() * cp - nz * sp;
                nz = face.ny() * sp + nz * cp;
                if (nz < -.05) continue;
                double[] xy = new double[8], uv = new double[8];
                double depth = 0;
                double[] v = face.vertices();
                for (int i = 0; i < 4; i++) {
                    double[] p = project(part.x + v[i * 3] * part.w, part.y + v[i * 3 + 1] * part.h, part.z + v[i * 3 + 2] * part.d, sy, cy, sp, cp);
                    xy[i * 2] = cx + p[0] * scale; xy[i * 2 + 1] = centerY - p[1] * scale; depth += p[2];
                    double u = Math.abs(face.nx()) > .6 ? v[i * 3 + 2] : v[i * 3];
                    double vv = Math.abs(face.ny()) > .6 ? v[i * 3 + 2] : 1 - v[i * 3 + 1];
                    uv[i * 2] = part.u + u * part.uw; uv[i * 2 + 1] = part.v + vv * part.vh;
                }
                faces.add(new Face(xy, uv, depth / 4, .68 + .20 * Math.max(ny, 0) + .12 * Math.abs(nz)));
            }
            if (outlines) for (double[] edge : geometry.edges) {
                double[] p = project(part.x + edge[0] * part.w, part.y + edge[1] * part.h, part.z + edge[2] * part.d, sy, cy, sp, cp);
                double[] q = project(part.x + edge[3] * part.w, part.y + edge[4] * part.h, part.z + edge[5] * part.d, sy, cy, sp, cp);
                LINES.line(cx + p[0] * scale, centerY - p[1] * scale, cx + q[0] * scale, centerY - q[1] * scale, line);
            }
        }
        faces.sort(Comparator.comparingDouble(Face::depth));
        if (sides) for (Face face : faces) {
            MESH.ensureQuadCapacity();
            int[] indices = new int[4];
            int light = (int) (255 * face.light);
            Color c = new Color(light, light, light, fill.a);
            for (int i = 0; i < 4; i++) indices[i] = MESH.vec2(face.xy[i * 2], face.xy[i * 2 + 1]).vec2(face.uv[i * 2], face.uv[i * 2 + 1]).color(c).next();
            MESH.quad(indices[0], indices[1], indices[2], indices[3]);
        }
        MESH.end();
        float time = animate ? (float) (org.lwjgl.glfw.GLFW.glfwGetTime() % 1200) : 0;
        var data = UNIFORMS.write(new Data(time, material, texture, (float) strength, fill.r / 255f, fill.g / 255f, fill.b / 255f));
        MeshRenderer.begin().attachments(mc.getFramebuffer()).pipeline(MeteorRenderPipelines.CHAMS_PREVIEW).mesh(MESH).uniform("MaterialData", data).end();
        LINES.render();
    }

    private static double[] project(double x, double y, double z, double sy, double cy, double sp, double cp) {
        double xx = x * cy + z * sy, zz = -x * sy + z * cy;
        return new double[]{xx, y * cp - zz * sp, y * sp + zz * cp};
    }
    private VisualPreviewRenderer() {}
}
