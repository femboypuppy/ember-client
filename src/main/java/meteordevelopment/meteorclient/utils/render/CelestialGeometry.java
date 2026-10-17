package meteordevelopment.meteorclient.utils.render;

/** Procedural surfaces shared by the live effects and their geometry previews. No game state or GL calls. */
public final class CelestialGeometry {
    public enum WingStyle { Astral, Seraph, Prism }
    public enum CircleStyle { Ripple, Orbit, Sigil }
    public static final double TAU = Math.PI * 2;
    private CelestialGeometry() {}

    public static double smooth(double x) { x = Math.clamp(x, 0, 1); return x * x * (3 - 2 * x); }
    public static double approach(double current, double target, double dt, double seconds) {
        return target + (current - target) * Math.exp(-Math.clamp(dt, 0, 0.1) / Math.max(0.001, seconds));
    }
    public static int color(int rgb, double alpha, double pearl) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return (int) Math.clamp(alpha * 255, 0, 255) << 24
            | (int) (r + (255 - r) * pearl) << 16 | (int) (g + (255 - g) * pearl) << 8 | (int) (b + (255 - b) * pearl);
    }

    /** Four-point star with zero-alpha tips, rather than a screen-aligned square particle. */
    public static void star(CelestialMesh m, double x, double y, double z, double size, int rgb, double alpha, boolean ground) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4, b = (i + 1) * Math.PI / 4;
            double ra = size * (i % 2 == 0 ? 1 : 0.18), rb = size * (i % 2 == 0 ? 0.18 : 1);
            double ax = x + Math.cos(a) * ra, ay = Math.sin(a) * ra;
            double bx = x + Math.cos(b) * rb, by = Math.sin(b) * rb;
            m.tri(x, y, z, ax, ground ? y : y + ay, ground ? z + ay : z,
                bx, ground ? y : y + by, ground ? z + by : z,
                color(rgb, alpha, 0.82), color(rgb, alpha * 0.06, 0.22), color(rgb, alpha * 0.06, 0.22));
        }
    }

    public static void wings(CelestialMesh m, WingStyle style, double time, double scale, double openness, double flutter,
                             double glow, int feathers, int rgb, double opacity) {
        int count = style == WingStyle.Prism ? 7 : Math.clamp(feathers, 8, 22);
        double fold = (1 - Math.clamp(openness, 0, 1)) * 1.18 + Math.sin(time * 2.25) * 0.11 * flutter;
        double c = Math.cos(fold), s = Math.sin(fold);
        for (int side = -1; side <= 1; side += 2) {
            for (int i = count - 1; i >= 0; i--) {
                double f = (double) i / (count - 1);
                double rootX = 0.18 + f * 0.32, rootY = 1.38 + Math.sin(f * Math.PI) * 0.24;
                double tipX = 0.57 + 1.23 * Math.cos(f * Math.PI * 0.49);
                double tipY = 2.16 - 1.93 * f + Math.sin(f * Math.PI) * 0.10;
                if (style == WingStyle.Astral) { tipX += Math.sin(f * Math.PI) * 0.17; tipY += 0.10 * Math.cos(f * 4); }
                if (style == WingStyle.Prism) { tipX += i % 2 == 0 ? 0.12 : -0.10; tipY += Math.sin(time * 1.3 + f * 3) * 0.025 * flutter; }
                double dx = tipX - rootX, dy = tipY - rootY, length = Math.hypot(dx, dy);
                double nx = -dy / length, ny = dx / length;
                double width = style == WingStyle.Prism ? 0.145 : 0.075 + 0.036 * Math.sin(f * Math.PI);
                int steps = style == WingStyle.Prism ? 5 : 9;
                for (int j = 0; j < steps; j++) {
                    double t0 = (double) j / steps, t1 = (double) (j + 1) / steps;
                    double curve0 = Math.sin(t0 * Math.PI) * 0.12, curve1 = Math.sin(t1 * Math.PI) * 0.12;
                    double x0 = rootX + dx * t0 + curve0, y0 = rootY + dy * t0;
                    double x1 = rootX + dx * t1 + curve1, y1 = rootY + dy * t1;
                    double w0 = width * Math.pow(Math.sin(t0 * Math.PI), 0.7), w1 = width * Math.pow(Math.sin(t1 * Math.PI), 0.7);
                    double z0 = 0.23 + Math.sin(f * Math.PI) * 0.07 + t0 * t0 * 0.055 * Math.sin(time * 2.25 - f * 2) * flutter;
                    double z1 = 0.23 + Math.sin(f * Math.PI) * 0.07 + t1 * t1 * 0.055 * Math.sin(time * 2.25 - f * 2) * flutter;
                    double a0 = opacity * (0.36 + 0.50 * Math.sin(t0 * Math.PI)), a1 = opacity * (0.36 + 0.50 * Math.sin(t1 * Math.PI));
                    double pearl = style == WingStyle.Seraph ? 0.70 : style == WingStyle.Prism ? 0.16 : 0.35;
                    // A translucent blade and a narrow pearlescent quill. All feather edges fade to transparent.
                    featherStrip(m, side, scale, c, s, x0, y0, z0, x1, y1, z1, nx, ny, w0 * (1 + glow * 1.5), w1 * (1 + glow * 1.5),
                        color(rgb, a0 * 0.12 * glow, 0), color(rgb, a1 * 0.12 * glow, 0), color(rgb, 0, 0), color(rgb, 0, 0));
                    featherStrip(m, side, scale, c, s, x0, y0, z0, x1, y1, z1, nx, ny, w0, w1,
                        color(rgb, a0 * 0.68, pearl), color(rgb, a1 * 0.68, pearl), color(rgb, a0 * 0.05, 0.08), color(rgb, a1 * 0.05, 0.08));
                    featherStrip(m, side, scale, c, s, x0, y0, z0 - 0.002, x1, y1, z1 - 0.002, nx, ny, w0 * 0.065, w1 * 0.065,
                        color(rgb, a0 * 0.92, 0.82), color(rgb, a1 * 0.92, 0.82), color(rgb, 0, 0.4), color(rgb, 0, 0.4));
                }
                if (style != WingStyle.Seraph && i % 2 == 0) {
                    double twinkle = opacity * (0.45 + 0.20 * Math.sin(time * 1.8 + i * 2));
                    star(m, side * (0.14 + (tipX - 0.14) * c) * scale, (1.25 + (tipY - 1.25) * scale),
                        (0.23 + (tipX - 0.14) * s) * scale, 0.035 * scale, rgb, twinkle, false);
                }
            }
        }
    }

    private static void featherStrip(CelestialMesh m, int side, double scale, double c, double s,
                                      double x0, double y0, double z0, double x1, double y1, double z1,
                                      double nx, double ny, double w0, double w1, int mid0, int mid1, int edge0, int edge1) {
        // Duplicate the centre spine so both sides get independent soft edge gradients.
        for (int edge = -1; edge <= 1; edge += 2) {
            double ax = side * (0.14 + (x0 - 0.14) * c) * scale, ay = 1.25 + (y0 - 1.25) * scale, az = (z0 + (x0 - 0.14) * s) * scale;
            double bx = side * (0.14 + (x1 - 0.14) * c) * scale, by = 1.25 + (y1 - 1.25) * scale, bz = (z1 + (x1 - 0.14) * s) * scale;
            double ex0 = x0 + edge * nx * w0, ex1 = x1 + edge * nx * w1;
            m.quad(ax, ay, az, bx, by, bz,
                side * (0.14 + (ex1 - 0.14) * c) * scale, 1.25 + (y1 + edge * ny * w1 - 1.25) * scale, (z1 + (ex1 - 0.14) * s) * scale,
                side * (0.14 + (ex0 - 0.14) * c) * scale, 1.25 + (y0 + edge * ny * w0 - 1.25) * scale, (z0 + (ex0 - 0.14) * s) * scale,
                mid0, mid1, edge1, edge0);
        }
    }

    public static double circleEnvelope(double age) {
        return age <= 0 || age >= 1 ? 0 : smooth(age / 0.09) * Math.pow(1 - smooth((age - 0.30) / 0.70), 1.3);
    }

    public static void circle(CelestialMesh m, CircleStyle style, double age, double radius, double rise, double spin,
                              double thickness, double glow, int segments, int rgb, double opacity, double seed) {
        double alpha = circleEnvelope(age) * opacity;
        if (alpha < 0.003) return;
        int steps = Math.clamp(segments, 32, 128);
        double ease = 1 - Math.pow(1 - age, 3), r = radius * (0.12 + 0.88 * ease), y = 0.025 + rise * smooth(age);
        double width = Math.max(0.009, thickness * (0.75 + 0.25 * (1 - age)));
        band(m, r, y, width * (3 + glow * 3), spin, alpha * 0.20 * glow, rgb, steps, true, false);
        band(m, r, y + 0.001, width, spin, alpha * 0.84, rgb, steps, true, style == CircleStyle.Orbit);
        band(m, r * 0.82, y + 0.002, width * 0.23, -spin * 0.7, alpha * 0.47, rgb, steps, false, style == CircleStyle.Sigil);
        if (style == CircleStyle.Ripple) {
            band(m, r * 0.53, y - 0.002, width * 0.40, spin, alpha * 0.20, rgb, steps, true, false);
        } else if (style == CircleStyle.Orbit) {
            for (int i = 0; i < 4; i++) {
                double a = spin + i * TAU / 4;
                star(m, Math.cos(a) * r, y + 0.008, Math.sin(a) * r, width * 2.9, rgb, alpha * 0.95, true);
            }
        } else {
            for (int i = 0; i < 12; i++) {
                double a = -spin * 0.6 + i * TAU / 12, c = Math.cos(a), s = Math.sin(a), t = 0.014;
                double inner = r * (i % 3 == 0 ? 0.58 : 0.70), outer = r * 0.76;
                m.quad(c * inner - s * t, y, s * inner + c * t, c * outer - s * t, y, s * outer + c * t,
                    c * outer + s * t, y, s * outer - c * t, c * inner + s * t, y, s * inner - c * t,
                    color(rgb, alpha * 0.06, 0.4), color(rgb, alpha * 0.72, 0.55), color(rgb, alpha * 0.72, 0.55), color(rgb, alpha * 0.06, 0.4));
            }
            star(m, 0, y, 0, r * 0.18, rgb, alpha * 0.28, true);
        }
        // Sparse motes rise from the circumference and vanish before the ring does.
        for (int i = 0; i < 7; i++) {
            double a = seed * 7 + i * 2.399963, life = Math.clamp(age * 1.35 - i * 0.035, 0, 1);
            star(m, Math.cos(a) * r * (0.92 + life * 0.12), y + life * 0.30, Math.sin(a) * r * (0.92 + life * 0.12),
                0.021 * (1 - life), rgb, alpha * (1 - life) * 0.7, false);
        }
    }

    private static void band(CelestialMesh m, double radius, double y, double width, double spin, double alpha, int rgb,
                             int steps, boolean soft, boolean broken) {
        for (int i = 0; i < steps; i++) {
            double a = i * TAU / steps, b = (i + 1) * TAU / steps;
            double light = broken ? 0.16 + 0.84 * Math.pow(Math.max(0, Math.cos(a * 3 - spin * 2)), 2) : 0.72 + 0.28 * Math.cos(a - spin);
            int core = color(rgb, alpha * light, 0.55), edge = color(rgb, soft ? 0 : alpha * light * 0.14, 0.05);
            for (int side = -1; side <= 1; side += 2) {
                double outer = radius + width * side;
                m.quad(Math.cos(a) * radius, y, Math.sin(a) * radius, Math.cos(b) * radius, y, Math.sin(b) * radius,
                    Math.cos(b) * outer, y, Math.sin(b) * outer, Math.cos(a) * outer, y, Math.sin(a) * outer, core, core, edge, edge);
            }
        }
    }

    private static double hash(int index, double salt) {
        double value = Math.sin(index * 127.1 + salt * 311.7) * 43758.5453;
        return value - Math.floor(value);
    }

    public static double groundFalloff(double x, double y, double z, double radius) {
        if (radius <= 0) return 0;
        double q = (x * x + z * z) / (radius * radius);
        return q >= 1 ? 0 : Math.exp(-q * 2.5) * smooth((1 - q) / 0.30) / (1 + Math.abs(y) * 0.85);
    }

    /** A terrain-conforming light tile. Shared edge samples make neighboring surfaces seamless. */
    public static void groundTile(CelestialMesh mesh, double x, double y, double z, double width, double depth,
                                   double radius, double strength, int rgb) {
        for (int ix = 0; ix < 4; ix++) for (int iz = 0; iz < 4; iz++) {
            double x0 = x + width * ix / 4, x1 = x + width * (ix + 1) / 4;
            double z0 = z + depth * iz / 4, z1 = z + depth * (iz + 1) / 4;
            double a = groundFalloff(x0, y, z0, radius) * strength, b = groundFalloff(x1, y, z0, radius) * strength;
            double c = groundFalloff(x1, y, z1, radius) * strength, d = groundFalloff(x0, y, z1, radius) * strength;
            if (Math.max(Math.max(a, b), Math.max(c, d)) < 0.003) continue;
            mesh.quad(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1,
                color(rgb, a, 0), color(rgb, b, 0), color(rgb, c, 0), color(rgb, d, 0));
        }
    }

    public static void atmosphere(CelestialMesh m, double w, double h, double time, double mouseX, double mouseY,
                                  double density, double intensity, int rgb) {
        if (w <= 0 || h <= 0 || intensity <= 0) return;
        halo(m, w * (0.15 + Math.sin(time * 0.09) * 0.045), h * 0.23, w * 0.42, h * 0.61, rgb, intensity * 0.17);
        halo(m, w * 0.84, h * (0.78 + Math.cos(time * 0.07) * 0.05), w * 0.42, h * 0.56, rgb, intensity * 0.13);
        for (int ribbon = 0; ribbon < 2; ribbon++) {
            for (int i = 0; i < 48; i++) {
                double x0 = w * i / 48, x1 = w * (i + 1) / 48;
                double y0 = h * (0.28 + ribbon * 0.42 + Math.sin(i / 48.0 * 4 + time * 0.12 + ribbon * 2) * 0.07);
                double y1 = h * (0.28 + ribbon * 0.42 + Math.sin((i + 1) / 48.0 * 4 + time * 0.12 + ribbon * 2) * 0.07);
                for (int side = -1; side <= 1; side += 2) m.quad(x0, y0, 0, x1, y1, 0, x1, y1 + h * 0.12 * side, 0, x0, y0 + h * 0.12 * side, 0,
                    color(rgb, intensity * 0.022, 0.15), color(rgb, intensity * 0.022, 0.15), color(rgb, 0, 0), color(rgb, 0, 0));
            }
        }
        int count = (int) (30 + Math.clamp(density, 0, 1) * 95);
        double scale = Math.clamp(w / 1600.0, 0.75, 1.5);
        for (int i = 0; i < count; i++) {
            double depth = 0.35 + hash(i, 3) * 0.65;
            double x = hash(i, 1) * w + Math.sin(time * 0.11 + i) * 6 * depth + mouseX * depth * 9;
            double py = hash(i, 2) - time * (0.0017 + depth * 0.0012), y = (py - Math.floor(py)) * h + mouseY * depth * 7;
            double border = Math.clamp(Math.min(Math.min(x, w - x), Math.min(y, h - y)) / 30, 0, 1);
            double twinkle = 0.45 + 0.55 * Math.pow(Math.sin(time * (0.30 + hash(i, 5) * 0.28) + i), 2);
            double alpha = intensity * border * twinkle * (0.32 + depth * 0.45);
            double size = (i % 9 == 0 ? 3.3 : 0.9 + depth * 0.9) * scale;
            star(m, x, y, 0, size, rgb, alpha, false);
            if (i % 9 == 0) halo(m, x, y, size * 2.8, size * 2.8, rgb, alpha * 0.19, 12, 3);
            if (i % 8 == 0) {
                double xx = hash(i + 1, 1) * w, yy = hash(i + 1, 2) - time * (0.0017 + (0.35 + hash(i + 1, 3) * 0.65) * 0.0012);
                yy = (yy - Math.floor(yy)) * h;
                double length = Math.hypot(xx - x, yy - y);
                if (length > 25 && length < Math.min(210, w * 0.17)) {
                    double dx = (yy - y) / length * 0.4, dy = -(xx - x) / length * 0.4;
                    m.quad(x - dx, y - dy, 0, xx - dx, yy - dy, 0, xx + dx, yy + dy, 0, x + dx, y + dy, 0,
                        color(rgb, alpha * 0.14, 0.18), color(rgb, alpha * 0.035, 0.18), color(rgb, alpha * 0.035, 0.18), color(rgb, alpha * 0.14, 0.18));
                }
            }
        }
    }

    private static void halo(CelestialMesh m, double x, double y, double rx, double ry, int rgb, double alpha) {
        halo(m, x, y, rx, ry, rgb, alpha, 40, 7);
    }
    private static void halo(CelestialMesh m, double x, double y, double rx, double ry, int rgb, double alpha, int segments, int bands) {
        for (int ring = 0; ring < bands; ring++) {
            double r0 = ring / (double) bands, r1 = (ring + 1) / (double) bands;
            int inner = color(rgb, alpha * Math.pow(1 - r0 * r0, 3), 0.08), outer = color(rgb, alpha * Math.pow(1 - r1 * r1, 3), 0.08);
            for (int i = 0; i < segments; i++) {
                double a = i * TAU / segments, b = (i + 1) * TAU / segments;
                m.quad(x + Math.cos(a) * rx * r0, y + Math.sin(a) * ry * r0, 0, x + Math.cos(b) * rx * r0, y + Math.sin(b) * ry * r0, 0,
                    x + Math.cos(b) * rx * r1, y + Math.sin(b) * ry * r1, 0, x + Math.cos(a) * rx * r1, y + Math.sin(a) * ry * r1, 0, inner, inner, outer, outer);
            }
        }
    }
}
