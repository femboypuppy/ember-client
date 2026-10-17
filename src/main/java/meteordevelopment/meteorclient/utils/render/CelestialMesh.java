package meteordevelopment.meteorclient.utils.render;

/** Reusable, bounded triangle storage. Coordinates stay local until submitted to the renderer. */
public final class CelestialMesh {
    public final float[] positions;
    public final int[] colors;
    public int size;

    public CelestialMesh(int maxVertices) {
        maxVertices -= maxVertices % 3;
        positions = new float[maxVertices * 3]; colors = new int[maxVertices];
    }
    public void clear() { size = 0; }
    public boolean hasRoom(int count) { return size + count <= colors.length; }
    /** Match Renderer2D's front-face winding, retaining each vertex's gradient color. */
    public void faceScreen() {
        for (int i = 0; i < size; i += 3) {
            int p = i * 3;
            double area = (positions[p + 3] - positions[p]) * (positions[p + 7] - positions[p + 1])
                - (positions[p + 4] - positions[p + 1]) * (positions[p + 6] - positions[p]);
            if (area <= 0) continue;
            for (int axis = 0; axis < 3; axis++) {
                float value = positions[p + 3 + axis];
                positions[p + 3 + axis] = positions[p + 6 + axis];
                positions[p + 6 + axis] = value;
            }
            int color = colors[i + 1]; colors[i + 1] = colors[i + 2]; colors[i + 2] = color;
        }
    }
    public void vertex(double x, double y, double z, int color) {
        if (!hasRoom(1)) return;
        positions[size * 3] = (float) x; positions[size * 3 + 1] = (float) y; positions[size * 3 + 2] = (float) z;
        colors[size++] = color;
    }
    public void tri(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz, int a, int b, int c) {
        if (!hasRoom(3)) return;
        vertex(ax, ay, az, a); vertex(bx, by, bz, b); vertex(cx, cy, cz, c);
    }
    public void quad(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz,
                     double dx, double dy, double dz, int a, int b, int c, int d) {
        if (!hasRoom(6)) return;
        tri(ax, ay, az, bx, by, bz, cx, cy, cz, a, b, c);
        tri(ax, ay, az, cx, cy, cz, dx, dy, dz, a, c, d);
    }
}
