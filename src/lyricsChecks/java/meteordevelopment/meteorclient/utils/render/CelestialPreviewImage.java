package meteordevelopment.meteorclient.utils.render;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Files;
import java.nio.file.Path;

/** Software rasterization of the real gradient meshes, for visual review without starting Minecraft. */
public final class CelestialPreviewImage {
    private static final int W = 1600, H = 1450;
    private static final BufferedImage IMAGE = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    private static final int[] PIXELS = ((DataBufferInt) IMAGE.getRaster().getDataBuffer()).getData();
    private static final int ACCENT = 0xA78BE0;
    private interface Projection { double[] at(double x, double y, double z); }

    public static void main(String[] args) throws Exception {
        Graphics2D g = IMAGE.createGraphics();
        g.setColor(new Color(8, 9, 14)); g.fillRect(0, 0, W, H);
        g.setColor(new Color(12, 12, 19)); g.fillRoundRect(60, 130, 1480, 265, 28, 28);
        var mesh = new CelestialMesh(60000);
        CelestialGeometry.atmosphere(mesh, 1480, 265, 5, 0, 0, 0.55, 0.65, ACCENT);
        draw(mesh, (x, y, z) -> new double[]{60 + x, 130 + y}, false);
        int i = 0;
        for (var style : CelestialGeometry.WingStyle.values()) {
            int left = 60 + i++ * 503;
            card(g, left, 420, 474, 340);
            mesh.clear(); CelestialGeometry.wings(mesh, style, 0.7, 1, 0.93, 0.7, 0.8, 14, ACCENT, 0.85);
            draw(mesh, (x, y, z) -> new double[]{left + 237 + x * 111 + z * 8, 420 + 198 - (y - 1.25) * 118 - z * 5}, false);
        }
        i = 0;
        for (var style : CelestialGeometry.CircleStyle.values()) {
            int left = 60 + i++ * 503;
            card(g, left, 783, 474, 285);
            mesh.clear(); CelestialGeometry.circle(mesh, style, 0.28, 1.4, 0.13, 0.7, 0.032, 0.85, 96, ACCENT, 1, 0.42);
            draw(mesh, (x, y, z) -> new double[]{left + 237 + (x * 0.97 + z * 0.15) * 135, 783 + 174 + z * 0.43 * 135 - y * 0.75 * 135}, false);
        }
        card(g, 60, 1090, 1480, 270);
        Projection floor = (x, y, z) -> new double[]{1060 + (x + z * 0.43) * 47, 1230 + z * 0.36 * 47 - y * 47};
        mesh.clear();
        var terrain = new java.util.ArrayList<TerrainLightSurfaces.Bounds>();
        for (int x = -4; x < 4; x++) for (int z = -4; z < 4; z++) {
            double top = x >= 0 && x <= 2 && z >= -1 && z <= 1 ? 0.5 : -0.5;
            terrain.add(new TerrainLightSurfaces.Bounds(x, -1, z, x + 1, top, z + 1));
        }
        var faces = terrain.stream().flatMap(b -> TerrainLightSurfaces.exposed(b, terrain).stream())
            .filter(f -> f.axis() == 1 ? f.sign() == 1 : f.sign() == -1)
            .sorted(java.util.Comparator.comparingDouble(f -> -0.43 * f.center(0) + 0.36 * f.center(1) - f.center(2))).toList();
        for (var face : faces) {
            mesh.clear();
            int color = CelestialGeometry.color(face.axis() == 1 ? 0x303139 : 0x202128, 1, 0);
            double[][] p = new double[4][3];
            for (int j = 0; j < 4; j++) {
                p[j][face.axis()] = face.plane();
                p[j][(face.axis() + 1) % 3] = j == 1 || j == 2 ? face.a1() : face.a0();
                p[j][(face.axis() + 2) % 3] = j >= 2 ? face.b1() : face.b0();
            }
            mesh.quad(p[0][0], p[0][1], -p[0][2], p[1][0], p[1][1], -p[1][2],
                p[2][0], p[2][1], -p[2][2], p[3][0], p[3][1], -p[3][2], color, color, color, color);
            draw(mesh, floor, false); mesh.clear();
            face.light(mesh, 0, 0, 0, 3.4, 0.44, ACCENT);
            draw(mesh, floor, true);
        }
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        text(g, "EMBER  /  CELESTIAL COLLECTION", 60, 46, 12, 0xB59CE5);
        text(g, "Light, motion & atmosphere.", 60, 89, 34, 0xF2EFF9);
        text(g, "Production geometry review · translucent surfaces, pearlescent highlights and a shared accent", 62, 113, 12, 0x898597);
        text(g, "ATMOSPHERE", 91, 174, 12, 0xB59CE5);
        text(g, "A little closer to the stars.", 91, 212, 27, 0xF2EFF9);
        text(g, "Slow aurora light, drifting stars and subtle constellations.", 92, 239, 13, 0xA39CB4);
        String[] wingNames = {"Astral", "Seraph", "Prism"}, circleNames = {"Ripple", "Orbit", "Sigil"};
        String[] wingDetails = {"Curved feathers · scattered starlight", "Pearlescent plumage · luminous quills", "Separated crystal blades · floating tips"};
        String[] circleDetails = {"Liquid light · concentric ribbons", "Orbital stars · luminous arcs", "Celestial dial · radial engravings"};
        for (i = 0; i < 3; i++) {
            int left = 82 + i * 503;
            text(g, wingNames[i], left, 455, 18, 0xEDE8F6); text(g, wingDetails[i], left, 477, 11, 0x8E859F);
            text(g, "WINGS / F5 THIRD PERSON", left, 741, 10, 0x7D728E);
            text(g, circleNames[i], left, 818, 18, 0xEDE8F6); text(g, circleDetails[i], left, 840, 11, 0x8E859F);
            text(g, "CUSTOM CIRCLES / GROUND PULSE", left, 1048, 10, 0x7D728E);
        }
        text(g, "ACCENT GROUND LIGHT", 90, 1136, 12, 0xB59CE5);
        text(g, "Color that reaches the world.", 90, 1176, 25, 0xF2EFF9);
        text(g, "Soft light wraps over ledges, sides, slabs and stair risers.", 91, 1204, 13, 0xA39CB4);
        text(g, "The same tint flows through the GUI, atmosphere and cosmetics.", 91, 1228, 13, 0xA39CB4);
        text(g, "Geometry preview, not a Minecraft screenshot. In-game effects also follow body pose, scene depth and elapsed animation time.", 62, 1400, 12, 0x7F778D);
        g.dispose();
        Path target = Path.of(args[0]); Files.createDirectories(target.toAbsolutePath().getParent()); ImageIO.write(IMAGE, "png", target.toFile());
        System.out.println("Rendered production geometry: " + target.toAbsolutePath());
    }

    private static void card(Graphics2D g, int x, int y, int w, int h) {
        g.setPaint(new GradientPaint(x, y, new Color(20, 18, 28), x + w, y + h, new Color(10, 11, 17)));
        g.fillRoundRect(x, y, w, h, 22, 22); g.setColor(new Color(38, 33, 49)); g.drawRoundRect(x, y, w, h, 22, 22);
    }
    private static void text(Graphics2D g, String value, int x, int y, int size, int rgb) {
        g.setFont(new Font("Segoe UI", Font.PLAIN, size)); g.setColor(new Color(rgb)); g.drawString(value, x, y);
    }
    private static void draw(CelestialMesh mesh, Projection projection, boolean additive) {
        for (int i = 0; i < mesh.size; i += 3) {
            double[][] p = new double[3][]; int[] c = new int[3];
            for (int j = 0; j < 3; j++) { int v = (i + j) * 3; p[j] = projection.at(mesh.positions[v], mesh.positions[v + 1], mesh.positions[v + 2]); c[j] = mesh.colors[i + j]; }
            double den = (p[1][1] - p[2][1]) * (p[0][0] - p[2][0]) + (p[2][0] - p[1][0]) * (p[0][1] - p[2][1]);
            if (Math.abs(den) < 1e-7) continue;
            int minX = Math.max(0, (int) Math.floor(Math.min(p[0][0], Math.min(p[1][0], p[2][0]))));
            int maxX = Math.min(W - 1, (int) Math.ceil(Math.max(p[0][0], Math.max(p[1][0], p[2][0]))));
            int minY = Math.max(0, (int) Math.floor(Math.min(p[0][1], Math.min(p[1][1], p[2][1]))));
            int maxY = Math.min(H - 1, (int) Math.ceil(Math.max(p[0][1], Math.max(p[1][1], p[2][1]))));
            for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
                double a = ((p[1][1] - p[2][1]) * (x + 0.5 - p[2][0]) + (p[2][0] - p[1][0]) * (y + 0.5 - p[2][1])) / den;
                double b = ((p[2][1] - p[0][1]) * (x + 0.5 - p[2][0]) + (p[0][0] - p[2][0]) * (y + 0.5 - p[2][1])) / den;
                double d = 1 - a - b;
                if (a < 0 || b < 0 || d < 0) continue;
                double alpha = ((c[0] >>> 24) * a + (c[1] >>> 24) * b + (c[2] >>> 24) * d) / 255.0;
                if (alpha <= 0) continue;
                int old = PIXELS[y * W + x], output = 0;
                for (int shift = 16; shift >= 0; shift -= 8) {
                    double value = (((c[0] >> shift) & 255) * a + ((c[1] >> shift) & 255) * b + ((c[2] >> shift) & 255) * d) * alpha;
                    value += ((old >> shift) & 255) * (additive ? 1 : 1 - alpha);
                    output |= Math.clamp((int) Math.round(value), 0, 255) << shift;
                }
                PIXELS[y * W + x] = output;
            }
        }
    }
}
