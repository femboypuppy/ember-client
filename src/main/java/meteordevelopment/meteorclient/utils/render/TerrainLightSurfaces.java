package meteordevelopment.meteorclient.utils.render;

import java.util.ArrayList;
import java.util.List;

/** Exposed rectangles of a union of terrain boxes; independent of Minecraft and the GPU. */
public final class TerrainLightSurfaces {
    public record Bounds(double x0, double y0, double z0, double x1, double y1, double z1) {
        double min(int axis) { return axis == 0 ? x0 : axis == 1 ? y0 : z0; }
        double max(int axis) { return axis == 0 ? x1 : axis == 1 ? y1 : z1; }
    }
    public record Face(int axis, int sign, double plane, double a0, double b0, double a1, double b1) {
        public double area() { return (a1 - a0) * (b1 - b0); }
        public double center(int coordinate) {
            return coordinate == axis ? plane : coordinate == (axis + 1) % 3 ? (a0 + a1) / 2 : (b0 + b1) / 2;
        }
        public void light(CelestialMesh mesh, double x, double y, double z, double radius, double strength, int rgb) {
            light(mesh, x, y, z, radius, strength, rgb, 0, 0);
        }
        public void light(CelestialMesh mesh, double x, double y, double z, double radius, double strength, int rgb, double time, double shimmer) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            double offset = plane + sign * 0.004, width = a1 - a0, height = b1 - b0;
            // CelestialRenderer's local Z points opposite world Z.
            CelestialGeometry.lightSurface(mesh, (axis == 0 ? offset : u == 0 ? a0 : b0) - x,
                (axis == 1 ? offset : u == 1 ? a0 : b0) - y, z - (axis == 2 ? offset : u == 2 ? a0 : b0),
                u == 0 ? width : 0, u == 1 ? width : 0, u == 2 ? -width : 0,
                v == 0 ? height : 0, v == 1 ? height : 0, v == 2 ? -height : 0, radius, strength, rgb, time, shimmer);
        }
    }
    private record Rect(double a0, double b0, double a1, double b1) {}
    private TerrainLightSurfaces() {}

    public static List<Face> exposed(Bounds box, List<Bounds> nearby) {
        List<Face> result = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            double plane = sign < 0 ? box.min(axis) : box.max(axis), sample = plane + sign * 0.00001;
            List<Rect> remaining = new ArrayList<>();
            remaining.add(new Rect(box.min(u), box.min(v), box.max(u), box.max(v)));
            for (Bounds other : nearby) {
                if (other == box || other.min(axis) > sample || other.max(axis) < sample) continue;
                List<Rect> next = new ArrayList<>();
                for (Rect rect : remaining) subtract(next, rect, other.min(u), other.min(v), other.max(u), other.max(v));
                remaining = next;
                if (remaining.isEmpty()) break;
            }
            for (Rect r : remaining) result.add(new Face(axis, sign, plane, r.a0, r.b0, r.a1, r.b1));
        }
        return result;
    }

    private static void subtract(List<Rect> out, Rect r, double a0, double b0, double a1, double b1) {
        a0 = Math.max(a0, r.a0); b0 = Math.max(b0, r.b0); a1 = Math.min(a1, r.a1); b1 = Math.min(b1, r.b1);
        if (a1 <= a0 || b1 <= b0) { out.add(r); return; }
        if (a0 > r.a0) out.add(new Rect(r.a0, r.b0, a0, r.b1));
        if (a1 < r.a1) out.add(new Rect(a1, r.b0, r.a1, r.b1));
        if (b0 > r.b0) out.add(new Rect(a0, r.b0, a1, b0));
        if (b1 < r.b1) out.add(new Rect(a0, b1, a1, r.b1));
    }
}
