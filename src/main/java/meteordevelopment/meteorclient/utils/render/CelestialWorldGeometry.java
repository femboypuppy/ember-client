package meteordevelopment.meteorclient.utils.render;

/** Stable celestial directions and world-anchored motes, independent of the game and GPU. */
public final class CelestialWorldGeometry {
    private static final double TAU = Math.PI * 2;
    private CelestialWorldGeometry() {}

    public static void skyShell(CelestialMesh mesh) {
        for (int y = 0; y < 16; y++) for (int x = 0; x < 32; x++) {
            double a = -Math.PI / 2 + Math.PI * y / 16, b = -Math.PI / 2 + Math.PI * (y + 1) / 16;
            double u = TAU * x / 32, v = TAU * (x + 1) / 32;
            mesh.quad(Math.cos(a) * Math.cos(u) * 32, Math.sin(a) * 32, Math.cos(a) * Math.sin(u) * 32,
                Math.cos(a) * Math.cos(v) * 32, Math.sin(a) * 32, Math.cos(a) * Math.sin(v) * 32,
                Math.cos(b) * Math.cos(v) * 32, Math.sin(b) * 32, Math.cos(b) * Math.sin(v) * 32,
                Math.cos(b) * Math.cos(u) * 32, Math.sin(b) * 32, Math.cos(b) * Math.sin(u) * 32,
                -1, -1, -1, -1);
        }
    }

    public static void skyStars(CelestialMesh mesh, double time, double density, double intensity, int rgb) {
        if (density <= 0 || intensity <= 0) return;
        int count = (int) (1200 * Math.clamp(density, 0, 1));
        for (int i = 0; i < count; i++) {
            double y = 0.025 + random(i * 7L + 1) * 0.975;
            double a = random(i * 7L + 2) * TAU + time * 0.0007, r = Math.sqrt(1 - y * y) * 32;
            double glow = intensity * (0.55 + 0.25 * Math.sin(time * (0.2 + random(i * 7L + 3) * 0.3) + i));
            star(mesh, Math.cos(a) * r, y * 32, Math.sin(a) * r, 0.035 + random(i * 7L + 4) * 0.065, glow, rgb);
        }
    }

    public static void motes(CelestialMesh mesh, double cameraX, double cameraY, double cameraZ,
                             double time, double density, double intensity, int rgb) {
        if (density <= 0 || intensity <= 0) return;
        int cellX = (int) Math.floor(cameraX / 6), cellY = (int) Math.floor(cameraY / 6), cellZ = (int) Math.floor(cameraZ / 6);
        for (int cx = cellX - 3; cx <= cellX + 3; cx++) for (int cy = cellY - 3; cy <= cellY + 3; cy++) for (int cz = cellZ - 3; cz <= cellZ + 3; cz++) {
            long key = cx * 73856093L ^ cy * 19349663L ^ cz * 83492791L;
            if (random(key + 5) > density * 0.8) continue;
            double phase = random(key + 4) * TAU;
            double x = cx * 6.0 + random(key + 1) * 6 + Math.sin(time * 0.13 + phase) * 0.4 - cameraX;
            double y = cy * 6.0 + random(key + 2) * 6 + Math.sin(time * 0.19 + phase) * 0.55 - cameraY;
            double z = cameraZ - (cz * 6.0 + random(key + 3) * 6 + Math.cos(time * 0.1 + phase) * 0.4);
            double distance = Math.sqrt(x * x + y * y + z * z);
            if (distance >= 17 || distance < 0.7) continue;
            double fade = Math.pow(1 - distance / 17, 1.5) * Math.clamp((distance - 0.7) / 1.3, 0, 1);
            star(mesh, x, y, z, 0.035 + random(key + 6) * 0.035,
                fade * intensity * (0.65 + 0.2 * Math.sin(time * 0.6 + phase)), rgb);
        }
    }

    private static void star(CelestialMesh mesh, double x, double y, double z, double size, double opacity, int rgb) {
        if (!mesh.hasRoom(48) || opacity < 0.003) return;
        double length = Math.sqrt(x * x + y * y + z * z), nx = x / length, ny = y / length, nz = z / length;
        double horizontal = Math.hypot(nx, nz);
        double ux = horizontal < 0.0001 ? 1 : nz / horizontal, uz = horizontal < 0.0001 ? 0 : -nx / horizontal;
        double vx = ny * uz, vy = nz * ux - nx * uz, vz = -ny * ux;
        int halo = CelestialGeometry.color(rgb, opacity * 0.28, 0.15), clear = CelestialGeometry.color(rgb, 0, 0);
        int core = CelestialGeometry.color(rgb, opacity, 0.85), tip = CelestialGeometry.color(rgb, opacity * 0.5, 0.55);
        for (int j = 0; j < 8; j++) {
            double a = TAU * j / 8, b = TAU * (j + 1) / 8;
            double ax = Math.cos(a) * size * 2.2, ay = Math.sin(a) * size * 2.2;
            double bx = Math.cos(b) * size * 2.2, by = Math.sin(b) * size * 2.2;
            mesh.tri(x, y, z, x + ux * ax + vx * ay, y + vy * ay, z + uz * ax + vz * ay,
                x + ux * bx + vx * by, y + vy * by, z + uz * bx + vz * by, halo, clear, clear);
            double sa = j % 2 == 0 ? size : size * 0.17, sb = j % 2 == 0 ? size * 0.17 : size;
            ax = Math.cos(a) * sa; ay = Math.sin(a) * sa; bx = Math.cos(b) * sb; by = Math.sin(b) * sb;
            mesh.tri(x, y, z, x + ux * ax + vx * ay, y + vy * ay, z + uz * ax + vz * ay,
                x + ux * bx + vx * by, y + vy * by, z + uz * bx + vz * by, core, tip, tip);
        }
    }

    /** Tint preserves scene brightness so an accent does not turn fog into a flat bright wall. */
    public static float tint(float original, int channel, float luminance, double amount) {
        float weight = (float) Math.clamp(amount * 0.65, 0, 0.65);
        float target = (channel / 255f) * Math.clamp(luminance * 0.75f + 0.12f, 0.12f, 0.7f);
        return original + (target - original) * weight;
    }

    private static double random(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return ((value ^ (value >>> 31)) >>> 11) * 0x1.0p-53;
    }
}
