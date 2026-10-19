package meteordevelopment.meteorclient.utils.render;

/** Deterministic world-anchored effects. All coordinates are camera-relative, with local Z reversed. */
public final class SceneEffectsGeometry {
    public static final int MAX_VERTICES = 180000, MAX_PLAYERS = 12;
    private static final double TAU = Math.PI * 2;
    private static final double[] CUBE = {-1,-1,-1, 1,-1,-1, 1,1,-1, -1,1,-1, -1,-1,1, 1,-1,1, 1,1,1, -1,1,1};
    private static final double[] CRYSTAL = {0,1.6,0, 1,0,0, 0,0,1, -1,0,0, 0,0,-1, 0,-1.6,0};
    private static final int[] CUBE_EDGES = {0,1,1,2,2,3,3,0,4,5,5,6,6,7,7,4,0,4,1,5,2,6,3,7};
    private static final int[] CRYSTAL_EDGES = {0,1,0,2,0,3,0,4,5,1,5,2,5,3,5,4,1,2,2,3,3,4,4,1};
    private static final int[] CUBE_FACES = {0,1,2,0,2,3,4,6,5,4,7,6,0,4,5,0,5,1,3,2,6,3,6,7,0,3,7,0,7,4,1,5,6,1,6,2};
    private static final int[] CRYSTAL_FACES = {0,1,2,0,2,3,0,3,4,0,4,1,5,2,1,5,3,2,5,4,3,5,1,4};
    private static final double[] WORK = new double[24];
    private static final double[] CURVES = new double[96 * 6];
    private SceneEffectsGeometry() {}

    public static void ambient(CelestialMesh mesh, double cameraX, double cameraY, double cameraZ, double time,
                               double density, double size, double trails, double brightness, int rgb) {
        if (brightness <= 0 || density <= 0 && trails <= 0) return;
        int cx = (int)Math.floor(cameraX / 4), cy = (int)Math.floor(cameraY / 4), cz = (int)Math.floor(cameraZ / 4);
        int shapes = 0, curves = 0;
        for (int x = cx - 5; x <= cx + 5; x++) for (int y = cy - 5; y <= cy + 5; y++) for (int z = cz - 5; z <= cz + 5; z++) {
            long key = x * 73856093L ^ y * 19349663L ^ z * 83492791L;
            // Selection depends only on the cell key. Empty cells do not need animated positions.
            boolean drawShape = density > 0 && shapes < 192 && random(key + 5) < density * .4;
            boolean drawCurve = trails > 0 && random(key + 9) < trails * .8;
            if (!drawShape && !drawCurve) continue;
            double phase = random(key + 4) * TAU;
            double px = x * 4.0 + random(key + 1) * 4 + Math.sin(time * .17 + phase) * .6 - cameraX;
            double py = y * 4.0 + random(key + 2) * 4 + Math.sin(time * .23 + phase) * .7 - cameraY;
            double pz = cameraZ - (z * 4.0 + random(key + 3) * 4 + Math.cos(time * .15 + phase) * .6);
            double distance = Math.sqrt(px * px + py * py + pz * pz);
            if (distance > 19 || distance < 1) continue;
            double fade = Math.pow(Math.clamp((19 - distance) / 7, 0, 1), 2) * Math.clamp((distance - 1) / 2, 0, 1) * brightness;
            if (drawShape) {
                shape(mesh, (int)(random(key + 6) * 3), px, py, pz, size * (.13 + random(key + 7) * .2), time * .27 + phase, fade, rgb);
                shapes++;
            }
            if (drawCurve) {
                // Keep nearby curves instead of truncating the volume in cell iteration order.
                int at = curves;
                if (at == 96) { if (distance >= CURVES[(at - 1) * 6]) continue; at--; }
                while (at > 0 && CURVES[(at - 1) * 6] > distance) {
                    if (at < 96) System.arraycopy(CURVES, (at - 1) * 6, CURVES, at * 6, 6);
                    at--;
                }
                int p = at * 6;
                CURVES[p] = distance; CURVES[p+1] = px; CURVES[p+2] = py; CURVES[p+3] = pz;
                CURVES[p+4] = phase; CURVES[p+5] = 2 + random(key + 10) * 1.5;
                curves = Math.min(96, curves + 1);
            }
        }
        for (int curve = 0; curve < curves; curve++) {
                int p = curve * 6; double distance = CURVES[p];
                double px = CURVES[p+1], py = CURVES[p+2], pz = CURVES[p+3];
                double fade = Math.pow(Math.clamp((19 - distance) / 7, 0, 1), 2) * Math.clamp((distance - 1) / 2, 0, 1) * brightness;
                double head = time * .65 + CURVES[p+4], length = CURVES[p+5];
                for (int i = 0; i < 18; i++) {
                    double a = i / 18.0, b = (i + 1) / 18.0;
                    double ax = px + (a - .5) * length, bx = px + (b - .5) * length;
                    double ay = py + Math.sin(head + a * 1.8) * .35, by = py + Math.sin(head + b * 1.8) * .35;
                    double az = pz + Math.cos(head + a * 1.2) * .45, bz = pz + Math.cos(head + b * 1.2) * .45;
                    line(mesh, ax, ay, az, bx, by, bz, .022, fade * a * a, rgb);
                }
                spark(mesh, px + length * .5, py + Math.sin(head + 1.8) * .35, pz + Math.cos(head + 1.2) * .45, .07, fade, rgb);
        }
    }

    public static void player(CelestialMesh mesh, double x, double y, double z, double height, double width, double time,
                              double aura, boolean halo, double haloRadius, double ribbons, double visibility, int rgb) {
        if (visibility <= 0 || height <= 0 || width <= 0) return;
        double radius = Math.max(.32, width * .75);
        if (aura > 0) {
            ring(mesh, x, y + .07, z, radius * 1.15, time * .3, .018, aura * visibility * .45, rgb, .015);
            for (int i = 0; i < 24; i++) {
                double phase = i * 2.3999632297;
                double rise = fract(time * .22 + i / 24.0), angle = phase + time * .55;
                double envelope = Math.sin(rise * Math.PI) * aura * visibility;
                double r = radius * (1 + .18 * Math.sin(phase + time));
                double px = x + Math.cos(angle) * r, py = y + rise * height, pz = z + Math.sin(angle) * r;
                spark(mesh, px, py, pz, .025 + .025 * Math.sin(rise * Math.PI), envelope * .9, rgb);
                line(mesh, px, py, pz, px - Math.sin(angle) * .06, py - .12, pz + Math.cos(angle) * .06, .008, envelope * .35, rgb);
            }
        }
        if (halo) {
            double hy = y + height + .27 + Math.sin(time * 1.3) * .035;
            int light = whiten(rgb, .85);
            ring(mesh, x, hy, z, haloRadius, time * .35, .025, visibility * .95, light, .025);
            ring(mesh, x, hy + .035, z, haloRadius * 1.12, -time * .25, .007, visibility * .4, rgb, .025);
        }
        if (ribbons > 0) {
            double alpha = ribbons * visibility;
            ring(mesh, x, y + height * .48, z, radius * 1.55, time, .014, alpha * .55, rgb, .22);
            for (int arm = 0; arm < 2; arm++) for (int i = 0; i < 64; i++) {
                double a = i / 64.0, b = (i + 1) / 64.0;
                double aa = a * TAU * 1.15 + time * 1.25 + arm * Math.PI, ba = b * TAU * 1.15 + time * 1.25 + arm * Math.PI;
                double ra = radius * (1.3 + .15 * Math.sin(a * TAU + time)), rb = radius * (1.3 + .15 * Math.sin(b * TAU + time));
                double ax = x + Math.cos(aa) * ra, ay = y + .1 + a * height, az = z + Math.sin(aa) * ra;
                double bx = x + Math.cos(ba) * rb, by = y + .1 + b * height, bz = z + Math.sin(ba) * rb;
                double envelope = Math.sin(a * Math.PI) * alpha;
                mesh.quad(ax, ay - .035, az, bx, by - .035, bz, bx, by + .035, bz, ax, ay + .035, az,
                    color(rgb, envelope * .16), color(rgb, envelope * .16), color(rgb, envelope * .7), color(rgb, envelope * .7));
                line(mesh, ax, ay + .035, az, bx, by + .035, bz, .008, envelope * .85, rgb);
            }
        }
    }

    public static void shape(CelestialMesh mesh, int kind, double x, double y, double z, double size, double angle, double opacity, int rgb) {
        if (opacity <= 0 || size <= 0 || !mesh.hasRoom(360)) return;
        double[] points = kind == 1 ? CRYSTAL : CUBE;
        double c = Math.cos(angle), s = Math.sin(angle), cp = Math.cos(angle * .63), sp = Math.sin(angle * .63);
        for (int i = 0; i < points.length; i += 3) {
            double px = points[i] * size, py = points[i + 1] * size, pz = points[i + 2] * size;
            WORK[i] = x + px * c + pz * s; WORK[i + 1] = y + py * cp - (-px * s + pz * c) * sp;
            WORK[i + 2] = z + py * sp + (-px * s + pz * c) * cp;
        }
        if (kind != 2) {
            int[] faces = kind == 1 ? CRYSTAL_FACES : CUBE_FACES;
            int face = color(rgb, opacity * .14);
            for (int i = 0; i < faces.length; i += 3) {
                int a = faces[i] * 3, b = faces[i + 1] * 3, d = faces[i + 2] * 3;
                mesh.tri(WORK[a], WORK[a+1], WORK[a+2], WORK[b], WORK[b+1], WORK[b+2], WORK[d], WORK[d+1], WORK[d+2], face, face, face);
            }
        }
        int[] edges = kind == 1 ? CRYSTAL_EDGES : CUBE_EDGES;
        for (int i = 0; i < edges.length; i += 2) {
            int a = edges[i] * 3, b = edges[i + 1] * 3;
            line(mesh, WORK[a], WORK[a+1], WORK[a+2], WORK[b], WORK[b+1], WORK[b+2], .009, opacity * .7, rgb);
        }
    }

    private static void ring(CelestialMesh mesh, double x, double y, double z, double radius, double time, double width, double alpha, int rgb, double tilt) {
        for (int i = 0; i < 80; i++) {
            double a = i * TAU / 80, b = (i + 1) * TAU / 80;
            double glow = alpha * (.72 + .28 * Math.sin(a * 3 + time * 2));
            line(mesh, x + Math.cos(a) * radius, y + Math.sin(a + time) * tilt, z + Math.sin(a) * radius,
                x + Math.cos(b) * radius, y + Math.sin(b + time) * tilt, z + Math.sin(b) * radius, width, glow, rgb);
        }
    }

    private static void spark(CelestialMesh mesh, double x, double y, double z, double size, double alpha, int rgb) {
        line(mesh, x - size, y, z, x + size, y, z, size * .22, alpha, rgb);
        line(mesh, x, y - size, z, x, y + size, z, size * .22, alpha, rgb);
        line(mesh, x, y, z - size, x, y, z + size, size * .22, alpha, rgb);
    }

    /** Camera-facing strip, including a soft outer layer; no depth writes or wall-through glow. */
    private static void line(CelestialMesh mesh, double ax, double ay, double az, double bx, double by, double bz, double width, double alpha, int rgb) {
        if (alpha < .002 || !mesh.hasRoom(12)) return;
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double vx = (ax + bx) * .5, vy = (ay + by) * .5, vz = (az + bz) * .5;
        double nx = dy * vz - dz * vy, ny = dz * vx - dx * vz, nz = dx * vy - dy * vx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-8) { nx = dz; ny = 0; nz = -dx; length = Math.hypot(nx, nz); }
        if (length < 1e-8) { nx = 1; ny = nz = 0; length = 1; }
        nx *= width / length; ny *= width / length; nz *= width / length;
        for (int layer = 0; layer < 2; layer++) {
            double scale = layer == 0 ? 3.5 : 1;
            int c = color(layer == 0 ? rgb : whiten(rgb, .32), alpha * (layer == 0 ? .12 : 1));
            mesh.quad(ax - nx * scale, ay - ny * scale, az - nz * scale, bx - nx * scale, by - ny * scale, bz - nz * scale,
                bx + nx * scale, by + ny * scale, bz + nz * scale, ax + nx * scale, ay + ny * scale, az + nz * scale, c,c,c,c);
        }
    }
    private static int color(int rgb, double alpha) { return ((int)(Math.clamp(alpha, 0, 1) * 255) << 24) | (rgb & 0xFFFFFF); }
    private static int whiten(int rgb, double amount) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
        return (int)(r + (255-r)*amount) << 16 | (int)(g + (255-g)*amount) << 8 | (int)(b + (255-b)*amount);
    }
    private static double fract(double n) { return n - Math.floor(n); }
    private static double random(long key) { key = (key ^ (key >>> 33)) * 0xff51afd7ed558ccdL; key = (key ^ (key >>> 33)) * 0xc4ceb9fe1a85ec53L; return ((key ^ (key >>> 33)) >>> 11) * 0x1.0p-53; }
}
