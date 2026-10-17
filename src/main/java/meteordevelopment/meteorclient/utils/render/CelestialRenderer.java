package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Submits to the existing world pass. No framebuffer, blend-state or depth-state mutation. */
public final class CelestialRenderer {
    private static final Color WORK = new Color();
    private CelestialRenderer() {}

    public static void draw(CelestialMesh source, Renderer3D renderer, double x, double y, double z, double yaw, double pitch) {
        var mesh = renderer.triangles;
        mesh.ensureCapacity(source.size, source.size);
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        for (int i = 0; i < source.size; i += 3) {
            int first = 0, second = 0;
            for (int j = 0; j < 3; j++) {
                int index = i + j, p = index * 3, color = source.colors[index];
                double lx = source.positions[p], ly = source.positions[p + 1], lz = source.positions[p + 2];
                if (pitch != 0) {
                    double yy = ly - 1.25;
                    ly = 1.25 + yy * cp - lz * sp; lz = yy * sp + lz * cp;
                }
                WORK.set((color >> 16) & 255, (color >> 8) & 255, color & 255, (color >>> 24) & 255);
                int next = mesh.vec3(x + lx * cy + lz * sy, y + ly, z + lx * sy - lz * cy).color(WORK).next();
                if (j == 0) first = next;
                else if (j == 1) second = next;
                else mesh.triangle(first, second, next);
            }
        }
    }
}
