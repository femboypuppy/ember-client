package meteordevelopment.meteorclient.utils.render;

import java.util.Arrays;

/** Covers the production cosmetic at every exposed size, material and animation extreme. */
public final class HatVisualChecks {
    public static void main(String[] args) throws Exception {
        run();
        if (args.length == 1) export(java.nio.file.Path.of(args[0]));
    }
    static void run() {
        var mesh = new CelestialMesh(60000);
        for (var style : HatGeometry.Style.values()) {
            for (double time : new double[]{0, .2, 7, 10000}) for (double size : new double[]{.65, 1, 1.7})
                for (double glow : new double[]{0, .75, 1.5}) for (boolean scan : new boolean[]{false, true}) {
                    mesh.clear(); HatGeometry.build(mesh, style, time, size, 1, glow, scan, .3, 0x6898E8);
                    require(mesh.size > 0 && mesh.size < HatGeometry.MAX_VERTICES && mesh.size % 3 == 0,
                        style + " must fit the live vertex budget without truncation: " + mesh.size);
                    for (int i = 0; i < mesh.size; i++) {
                        double x = mesh.positions[i * 3], y = mesh.positions[i * 3 + 1], z = mesh.positions[i * 3 + 2];
                        require(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z), "Hat vertices must be finite");
                        require(Math.hypot(x, z) <= .8 * size && y >= -.06 * size && y <= .8 * size, "Hat must stay near the head");
                    }
                }
            mesh.clear(); HatGeometry.build(mesh, style, 0, 1, 1, .75, true, .3, 0x6898E8);
            float[] positions = Arrays.copyOf(mesh.positions, mesh.size * 3);
            int[] colors = Arrays.copyOf(mesh.colors, mesh.size);
            mesh.clear(); HatGeometry.build(mesh, style, 0, 1, 1, .75, true, .3, 0x6898E8);
            require(Arrays.equals(positions, Arrays.copyOf(mesh.positions, mesh.size * 3))
                && Arrays.equals(colors, Arrays.copyOf(mesh.colors, mesh.size)), "Frozen animation cannot jitter");
            mesh.clear(); HatGeometry.build(mesh, style, 3, 1, 1, .75, true, .3, 0x6898E8);
            require(!Arrays.equals(colors, Arrays.copyOf(mesh.colors, mesh.size)), "Holographic highlights must animate");
            mesh.clear(); HatGeometry.build(mesh, style, 0, 1, 1, 0, false, 0, 0x0010FF);
            for (int i = 0; i < mesh.size; i++) require((mesh.colors[i] & 255) >= (mesh.colors[i] >> 16 & 255),
                "Zero color shift keeps blue accents blue");
            mesh.clear(); HatGeometry.build(mesh, style, 0, 1, 0, 1, true, 1, 0xFFFFFF);
            require(mesh.size == 0, "Zero opacity leaves no geometry");
            var tiny = new CelestialMesh(9);
            HatGeometry.build(tiny, style, 0, 1, 1, 1.5, true, 1, 0xFFFFFF);
            require(tiny.size <= 9 && tiny.size % 3 == 0, "Exhaustion must never submit partial triangles");
        }
        System.out.println("Hat checks passed: five silhouettes, size/material extremes, bounded meshes, frozen animation, changing highlights, custom color and zero opacity.");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static void export(java.nio.file.Path path) throws Exception {
        var json = new StringBuilder("[");
        var mesh = new CelestialMesh(HatGeometry.MAX_VERTICES);
        for (var style : HatGeometry.Style.values()) for (int frame = 0; frame < 3; frame++) {
            mesh.clear();
            HatGeometry.build(mesh, style, frame == 0 ? .7 : 4, 1, .85, .75, true, .3, frame == 2 ? 0xE7A450 : 0xA78BE0);
            if (json.length() > 1) json.append(',');
            json.append("{\"style\":\"").append(style).append("\",\"frame\":").append(frame).append(",\"positions\":[");
            for (int i = 0; i < mesh.size * 3; i++) { if (i > 0) json.append(','); json.append(mesh.positions[i]); }
            json.append("],\"colors\":[");
            for (int i = 0; i < mesh.size; i++) { if (i > 0) json.append(','); json.append(Integer.toUnsignedLong(mesh.colors[i])); }
            json.append("]}");
        }
        java.nio.file.Files.createDirectories(path.toAbsolutePath().getParent());
        java.nio.file.Files.writeString(path, json.append(']').toString());
    }
}
