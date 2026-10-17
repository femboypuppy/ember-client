package meteordevelopment.meteorclient.utils.render;

import java.util.ArrayList;
import java.util.List;

/** Unit rounded cuboid, cached by radius. Faces are shared by the world and preview. */
public final class RoundedBoxMesh {
    public record Face(double[] vertices, double nx, double ny, double nz) {}
    public static final int WEST = 1, EAST = 2, DOWN = 4, UP = 8, NORTH = 16, SOUTH = 32;
    private static final java.util.concurrent.atomic.AtomicReferenceArray<RoundedBoxMesh> CACHE = new java.util.concurrent.atomic.AtomicReferenceArray<>(21 * 64);
    private static final java.util.concurrent.atomic.AtomicIntegerArray SCHEDULED = new java.util.concurrent.atomic.AtomicIntegerArray(21);
    private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Ember-ESP-Surfaces"); thread.setDaemon(true); return thread;
    });
    public final List<Face> faces;
    public final List<double[]> edges;

    public static RoundedBoxMesh get(double radius) {
        return get(radius, 0);
    }

    public static RoundedBoxMesh get(double radius, int joined) {
        int r = (int) Math.round(Math.clamp(radius, 0, 0.2) * 100), key = r * 64 + (joined & 63);
        RoundedBoxMesh cached = CACHE.get(key);
        if (cached != null) return cached;
        var built = new RoundedBoxMesh(r / 100.0, joined & 63);
        CACHE.compareAndSet(key, null, built);
        return CACHE.get(key);
    }

    public static RoundedBoxMesh peek(double radius, int joined) {
        int r = (int) Math.round(Math.clamp(radius, 0, 0.2) * 100);
        return CACHE.get(r * 64 + (joined & 63));
    }

    /** Build neighbor variants away from chunk submission and the render thread. */
    public static void prepare(double radius) {
        int r = (int) Math.round(Math.clamp(radius, 0, 0.2) * 100);
        if (SCHEDULED.compareAndSet(r, 0, 1)) WORKER.execute(() -> {
            for (int joined = 0; joined < 64; joined++) get(r / 100.0, joined);
        });
    }

    private RoundedBoxMesh(double radius, int joined) {
        List<Face> faces = new ArrayList<>();
        List<double[]> edges = new ArrayList<>();
        double[] grid = radius == 0 ? new double[]{0, 1} : new double[]{0, radius * 0.293, radius, 1 - radius, 1 - radius * 0.293, 1};
        int[][] bits = {{WEST, EAST}, {DOWN, UP}, {NORTH, SOUTH}};
        double[] min = new double[3], max = new double[3];
        for (int axis = 0; axis < 3; axis++) { min[axis] = (joined & bits[axis][0]) == 0 ? radius : 0; max[axis] = (joined & bits[axis][1]) == 0 ? 1 - radius : 1; }
        for (int axis = 0; axis < 3; axis++) for (int side = 0; side < 2; side++) {
            if ((joined & bits[axis][side]) != 0) continue;
            int a = (axis + 1) % 3, b = (axis + 2) % 3;
            for (int u = 0; u < grid.length - 1; u++) for (int v = 0; v < grid.length - 1; v++) {
                double[] vertices = new double[12];
                for (int corner = 0; corner < 4; corner++) {
                    double[] p = new double[3]; p[axis] = side;
                    p[a] = grid[u + (corner == 1 || corner == 2 ? 1 : 0)];
                    p[b] = grid[v + (corner >= 2 ? 1 : 0)];
                    round(p, radius, min, max);
                    System.arraycopy(p, 0, vertices, corner * 3, 3);
                }
                // The broad center face and rounded bevel receive different shading.
                double nx = 0, ny = 0, nz = 0;
                for (int c = 0; c < 4; c++) {
                    nx += vertices[c * 3] - Math.clamp(vertices[c * 3], min[0], max[0]);
                    ny += vertices[c * 3 + 1] - Math.clamp(vertices[c * 3 + 1], min[1], max[1]);
                    nz += vertices[c * 3 + 2] - Math.clamp(vertices[c * 3 + 2], min[2], max[2]);
                }
                double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length < 1e-9) { nx = axis == 0 ? side * 2 - 1 : 0; ny = axis == 1 ? side * 2 - 1 : 0; nz = axis == 2 ? side * 2 - 1 : 0; length = 1; }
                faces.add(new Face(vertices, nx / length, ny / length, nz / length));
            }
            // Rounded rectangle on each of the six faces; straight segments meet the bevel.
            int segments = radius == 0 ? 1 : 4;
            List<double[]> ring = new ArrayList<>();
            for (int c = 0; c < 4; c++) for (int step = 0; step <= segments; step++) {
                double angle = c * Math.PI / 2 + step * Math.PI / (2 * segments);
                double[] p = new double[3]; p[axis] = side;
                p[a] = (c == 0 || c == 3 ? 1 - radius : radius) + Math.cos(angle) * radius;
                p[b] = (c < 2 ? 1 - radius : radius) + Math.sin(angle) * radius;
                round(p, radius, min, max);
                ring.add(p);
            }
            if (joined != 0) {
                ring.clear();
                for (int c = 0; c < 4; c++) for (int step = 0; step < grid.length - 1; step++) {
                    double[] p = new double[3]; p[axis] = side;
                    p[a] = c == 0 ? grid[step] : c == 1 ? 1 : c == 2 ? grid[grid.length - 1 - step] : 0;
                    p[b] = c == 0 ? 0 : c == 1 ? grid[step] : c == 2 ? 1 : grid[grid.length - 1 - step];
                    round(p, radius, min, max); ring.add(p);
                }
            }
            for (int i = 0; i < ring.size(); i++) {
                double[] p = ring.get(i), q = ring.get((i + 1) % ring.size());
                boolean seam = false;
                for (int j = 0; j < 3; j++) for (int end = 0; end < 2; end++)
                    if ((joined & bits[j][end]) != 0 && Math.abs(p[j] - end) < 1e-8 && Math.abs(q[j] - end) < 1e-8) seam = true;
                if (seam) continue;
                edges.add(new double[]{p[0], p[1], p[2], q[0], q[1], q[2]});
            }
        }
        var unique = new java.util.LinkedHashMap<String, double[]>();
        for (double[] edge : edges) {
            String first = pointKey(edge[0], edge[1], edge[2]), second = pointKey(edge[3], edge[4], edge[5]);
            if (first.equals(second)) continue;
            String key = first.compareTo(second) < 0 ? first + "/" + second : second + "/" + first;
            unique.putIfAbsent(key, edge);
        }
        this.faces = List.copyOf(faces); this.edges = List.copyOf(unique.values());
    }

    private static void round(double[] p, double radius, double[] min, double[] max) {
        if (radius <= 0) return;
        double x = Math.clamp(p[0], min[0], max[0]), y = Math.clamp(p[1], min[1], max[1]), z = Math.clamp(p[2], min[2], max[2]);
        double dx = p[0] - x, dy = p[1] - y, dz = p[2] - z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length > 0) { p[0] = x + dx / length * radius; p[1] = y + dy / length * radius; p[2] = z + dz / length * radius; }
    }

    private static String pointKey(double x, double y, double z) {
        return Math.round(x * 1e8) + "," + Math.round(y * 1e8) + "," + Math.round(z * 1e8);
    }
}
