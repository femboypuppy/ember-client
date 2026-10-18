package meteordevelopment.meteorclient.utils.render;

/** Bounded holographic hat meshes. Origin is the top of the head; positive Y points upward. */
public final class HatGeometry {
    public enum Style { Hologram, Halo, Crown, Witch, TopHat }
    public static final int MAX_VERTICES = 24000;
    private static final int SEGMENTS = 64;
    private static final double TAU = Math.PI * 2;
    // radius, height, sideways bend. Profiles include the brim and crown in one continuous surface.
    private static final double[][] CONE = {{.60, 0, 0}, {.49, .08, 0}, {.37, .17, 0}, {.24, .27, 0}, {.11, .37, 0}, {0, .46, 0}};
    private static final double[][] WITCH = {{.62, 0, 0}, {.45, .025, 0}, {.31, .06, 0}, {.26, .22, 0}, {.18, .40, .025}, {.105, .56, .08}, {.045, .66, .17}, {0, .63, .27}};
    private static final double[][] TOP = {{.48, 0, 0}, {.30, .015, 0}, {.29, .10, 0}, {.30, .26, 0}, {.34, .43, 0}, {.20, .43, 0}, {0, .43, 0}};
    private HatGeometry() {}

    public static void build(CelestialMesh mesh, Style style, double time, double size, double opacity,
                             double glow, boolean scanlines, double iridescence, int rgb) {
        if (opacity <= 0 || size <= 0) return;
        if (style == Style.Halo) {
            ring(mesh, .39, .10, 0, .022, time, size, opacity, glow, iridescence, rgb, true);
            ring(mesh, .48, .10, 0, .005, -time * .65, size, opacity * .65, glow, iridescence, rgb, true);
            if (scanlines) ring(mesh, .435, .10, 0, .004, time, size, opacity * .3, glow, iridescence, rgb, false);
            for (int i = 0; i < 4; i++) {
                double a = time * .35 + i * TAU / 4;
                diamond(mesh, Math.cos(a) * .48, .10, Math.sin(a) * .48, .038, size,
                    tint(rgb, a, time, iridescence), opacity * .85, glow);
            }
            return;
        }
        if (style == Style.Crown) {
            crown(mesh, time, size, opacity, glow, scanlines, iridescence, rgb);
            return;
        }
        double[][] profile = style == Style.Witch ? WITCH : style == Style.TopHat ? TOP : CONE;
        for (int row = 0; row < profile.length - 1; row++) {
            double[] lower = profile[row], upper = profile[row + 1];
            for (int i = 0; i < SEGMENTS; i++) {
                double a = i * TAU / SEGMENTS, b = (i + 1) * TAU / SEGMENTS;
                int ca = tint(rgb, a + row * .3, time, iridescence), cb = tint(rgb, b + row * .3, time, iridescence);
                double shine = .14 + .06 * Math.cos(a * 2 - time * .65);
                mesh.quad((lower[2] + Math.cos(a) * lower[0]) * size, lower[1] * size, Math.sin(a) * lower[0] * size,
                    (lower[2] + Math.cos(b) * lower[0]) * size, lower[1] * size, Math.sin(b) * lower[0] * size,
                    (upper[2] + Math.cos(b) * upper[0]) * size, upper[1] * size, Math.sin(b) * upper[0] * size,
                    (upper[2] + Math.cos(a) * upper[0]) * size, upper[1] * size, Math.sin(a) * upper[0] * size,
                    color(ca, opacity * shine, .12), color(cb, opacity * shine, .12),
                    color(cb, opacity * (shine + .035), .25), color(ca, opacity * (shine + .035), .25));
            }
        }
        // Bright outer silhouette and smaller crown bands; no filled opaque brim.
        ring(mesh, profile[0][0], 0, 0, .009, time, size, opacity, glow, iridescence, rgb, false);
        if (style == Style.TopHat) {
            ring(mesh, .34, .43, 0, .007, time, size, opacity * .9, glow, iridescence, rgb, false);
            ring(mesh, .292, .08, 0, .012, time, size, opacity * .9, glow, iridescence, rgb, false);
        }
        if (scanlines) for (int row = 0; row < profile.length - 1; row++) {
            double[] lower = profile[row], upper = profile[row + 1];
            for (int line = 1; line <= 2; line++) {
                double t = line / 3.0;
                double radius = lerp(lower[0], upper[0], t), y = lerp(lower[1], upper[1], t);
                double alpha = opacity * (.15 + .30 * Math.pow(Math.max(0, Math.cos(y * 13 - time * 2)), 6));
                ring(mesh, radius, y, lerp(lower[2], upper[2], t), .0022, time, size, alpha, 0, iridescence, rgb, false);
            }
        }
        // Sparse wireframe seams describe the silhouette from any direction.
        for (int seam = 0; seam < 8; seam++) {
            double a = seam * TAU / 8;
            for (int row = 0; row < profile.length - 1; row++) {
                double[] lo = profile[row], hi = profile[row + 1];
                stroke(mesh, lo[2] + Math.cos(a) * lo[0], lo[1], Math.sin(a) * lo[0],
                    hi[2] + Math.cos(a) * hi[0], hi[1], Math.sin(a) * hi[0], .002, size,
                    tint(rgb, a, time, iridescence), opacity * .3, glow * .5);
            }
        }
    }

    private static void crown(CelestialMesh m, double time, double size, double opacity, double glow, boolean scanlines, double iridescence, int rgb) {
        for (int i = 0; i < SEGMENTS; i++) {
            double a = i * TAU / SEGMENTS, b = (i + 1) * TAU / SEGMENTS;
            double ya = .17 + .17 * Math.pow(.5 + .5 * Math.cos(a * 8), 2);
            double yb = .17 + .17 * Math.pow(.5 + .5 * Math.cos(b * 8), 2);
            int ca = color(tint(rgb, a, time, iridescence), opacity * .19, .15);
            int cb = color(tint(rgb, b, time, iridescence), opacity * .19, .15);
            m.quad(Math.cos(a) * .32 * size, 0, Math.sin(a) * .32 * size, Math.cos(b) * .32 * size, 0, Math.sin(b) * .32 * size,
                Math.cos(b) * .34 * size, yb * size, Math.sin(b) * .34 * size,
                Math.cos(a) * .34 * size, ya * size, Math.sin(a) * .34 * size, ca, cb, cb, ca);
            stroke(m, Math.cos(a) * .34, ya, Math.sin(a) * .34, Math.cos(b) * .34, yb, Math.sin(b) * .34,
                .006, size, tint(rgb, a, time, iridescence), opacity * .85, glow);
        }
        ring(m, .32, 0, 0, .009, time, size, opacity, glow, iridescence, rgb, false);
        if (scanlines) ring(m, .33, .09, 0, .004, time, size, opacity * .5, glow, iridescence, rgb, false);
        for (int i = 0; i < 8; i++) {
            double a = i * TAU / 8;
            diamond(m, Math.cos(a) * .34, .35, Math.sin(a) * .34, .028, size, tint(rgb, a, time, iridescence), opacity, glow);
        }
    }

    private static void ring(CelestialMesh m, double radius, double y, double bend, double width, double time, double size,
                             double opacity, double glow, double iridescence, int rgb, boolean broken) {
        for (int i = 0; i < SEGMENTS; i++) {
            double a = i * TAU / SEGMENTS, b = (i + 1) * TAU / SEGMENTS;
            double alpha = opacity * (broken ? .18 + .82 * Math.pow(Math.max(0, Math.cos(a * 3 - time)), 2) : 1);
            int rgbA = tint(rgb, a + y * 2, time, iridescence), rgbB = tint(rgb, b + y * 2, time, iridescence);
            for (int layer = glow > 0 ? 1 : 0; layer >= 0; layer--) {
                double w = width * (layer == 1 ? 2.5 + glow * 2 : 1);
                int ca = color(rgbA, alpha * (layer == 1 ? .12 * glow : .85), .6);
                int cb = color(rgbB, alpha * (layer == 1 ? .12 * glow : .85), .6);
                for (int side = -1; side <= 1; side += 2) {
                    double outer = radius + w * side;
                    m.quad((bend + Math.cos(a) * radius) * size, y * size, Math.sin(a) * radius * size,
                        (bend + Math.cos(b) * radius) * size, y * size, Math.sin(b) * radius * size,
                        (bend + Math.cos(b) * outer) * size, y * size, Math.sin(b) * outer * size,
                        (bend + Math.cos(a) * outer) * size, y * size, Math.sin(a) * outer * size,
                        ca, cb, color(rgbB, 0, 0), color(rgbA, 0, 0));
                    // A vertical ribbon keeps the rim luminous when viewed at head height.
                    if (glow > 0) m.quad((bend + Math.cos(a) * radius) * size, y * size, Math.sin(a) * radius * size,
                        (bend + Math.cos(b) * radius) * size, y * size, Math.sin(b) * radius * size,
                        (bend + Math.cos(b) * radius) * size, (y + w * side) * size, Math.sin(b) * radius * size,
                        (bend + Math.cos(a) * radius) * size, (y + w * side) * size, Math.sin(a) * radius * size,
                        ca, cb, color(rgbB, 0, 0), color(rgbA, 0, 0));
                }
            }
        }
    }

    /** Two crossing ribbons keep thin outlines visible edge-on; the wider halo fades to zero. */
    private static void stroke(CelestialMesh m, double ax, double ay, double az, double bx, double by, double bz,
                               double width, double size, int rgb, double opacity, double glow) {
        double dx = bx - ax, dy = by - ay, dz = bz - az, length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-8) return;
        double horizontal = Math.hypot(dx, dz);
        double ux = horizontal > 1e-8 ? -dz / horizontal : 1, uz = horizontal > 1e-8 ? dx / horizontal : 0;
        double vx = dy * uz / length, vy = (dz * ux - dx * uz) / length, vz = -dy * ux / length;
        for (int axis = 0; axis < 2; axis++) for (int layer = glow > 0 ? 1 : 0; layer >= 0; layer--) {
            double w = width * (layer == 1 ? 2.5 + glow * 2 : 1);
            double nx = (axis == 0 ? ux : vx) * w, ny = (axis == 0 ? 0 : vy) * w, nz = (axis == 0 ? uz : vz) * w;
            int core = color(rgb, opacity * (layer == 1 ? .12 * glow : .78), layer == 1 ? .05 : .6);
            int edge = color(rgb, 0, 0);
            for (int side = -1; side <= 1; side += 2) m.quad(ax * size, ay * size, az * size, bx * size, by * size, bz * size,
                (bx + nx * side) * size, (by + ny * side) * size, (bz + nz * side) * size,
                (ax + nx * side) * size, (ay + ny * side) * size, (az + nz * side) * size, core, core, edge, edge);
        }
    }

    private static void diamond(CelestialMesh m, double x, double y, double z, double radius, double size, int rgb, double opacity, double glow) {
        for (int side = 0; side < 4; side++) {
            double a = side * TAU / 4, b = (side + 1) * TAU / 4;
            int core = color(rgb, opacity * .85, .7), edge = color(rgb, opacity * .25, .15);
            m.tri(x * size, (y + radius) * size, z * size, (x + Math.cos(a) * radius * .6) * size, y * size, (z + Math.sin(a) * radius * .6) * size,
                (x + Math.cos(b) * radius * .6) * size, y * size, (z + Math.sin(b) * radius * .6) * size, core, edge, edge);
            m.tri(x * size, (y - radius) * size, z * size, (x + Math.cos(b) * radius * .6) * size, y * size, (z + Math.sin(b) * radius * .6) * size,
                (x + Math.cos(a) * radius * .6) * size, y * size, (z + Math.sin(a) * radius * .6) * size, core, edge, edge);
        }
        if (glow > 0) CelestialGeometry.star(m, x * size, y * size, z * size, radius * 1.9 * size, rgb, opacity * glow * .24, false);
    }

    private static int tint(int rgb, double angle, double time, double strength) {
        if (strength <= 0) return rgb;
        double phase = angle + time * .55;
        int result = 0;
        for (int i = 0; i < 3; i++) {
            int shift = 16 - i * 8, base = rgb >> shift & 255;
            double pearl = 128 + 127 * Math.cos(phase + i * TAU / 3);
            result |= (int) Math.clamp(lerp(base, pearl, strength), 0, 255) << shift;
        }
        return result;
    }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static int color(int rgb, double alpha, double pearl) { return CelestialGeometry.color(rgb, alpha, pearl); }
}
