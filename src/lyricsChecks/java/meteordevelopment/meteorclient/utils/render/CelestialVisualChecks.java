package meteordevelopment.meteorclient.utils.render;

import java.nio.file.Files;
import java.nio.file.Path;

/** Exercises the exact procedural geometry used by Minecraft; also exports reviewable previews. */
public final class CelestialVisualChecks {
    public static void main(String[] args) throws Exception {
        var mesh = new CelestialMesh(90000);
        for (var style : CelestialGeometry.WingStyle.values()) {
            for (double time : new double[]{0, 0.1, 1.2, 10000}) {
                mesh.clear(); CelestialGeometry.wings(mesh, style, time, 1, 0.93, 0.7, 0.8, 22, 0xA78BE0, 0.85);
                valid(mesh); require(mesh.size > 0 && mesh.size < 18000, "Wing detail must fit its live mesh budget");
                float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < mesh.size; i++) { min = Math.min(min, mesh.positions[i * 3]); max = Math.max(max, mesh.positions[i * 3]); }
                require(Math.abs(min + max) < 0.00001, "The wings must mirror around the player's spine");
            }
        }
        mesh.clear(); CelestialGeometry.wings(mesh, CelestialGeometry.WingStyle.Astral, 0, 1, 1, 0, 1, 14, 0xA78BE0, 1);
        double open = width(mesh);
        mesh.clear(); CelestialGeometry.wings(mesh, CelestialGeometry.WingStyle.Astral, 0, 1, 0.2, 0, 1, 14, 0xA78BE0, 1);
        require(width(mesh) < open * 0.8, "Folding must move the tips behind the shoulders");
        for (var style : CelestialGeometry.CircleStyle.values()) {
            for (double age : new double[]{0, 0.01, 0.15, 0.6, 0.999, 1, 2}) {
                mesh.clear(); CelestialGeometry.circle(mesh, style, age, 1.4, 0.13, 0.7, 0.032, 0.85, 128, 0xA78BE0, 1, 0.42);
                valid(mesh);
                if (age <= 0 || age >= 1) require(mesh.size == 0, "Completed circles cannot leave residual geometry");
            }
        }
        require(CelestialGeometry.circleEnvelope(0) == 0 && CelestialGeometry.circleEnvelope(1) == 0, "Pulse opacity must reach zero at both endpoints");
        double slow = 0, fast = 0;
        for (int i = 0; i < 30; i++) slow = CelestialGeometry.approach(slow, 1, 1.0 / 30, 0.19);
        for (int i = 0; i < 144; i++) fast = CelestialGeometry.approach(fast, 1, 1.0 / 144, 0.19);
        require(Math.abs(slow - fast) < 1e-10, "Wing response must be independent of FPS");
        mesh.clear(); CelestialGeometry.atmosphere(mesh, 3840, 2160, 4, 0.5, -0.5, 1, 1, 0xA78BE0);
        valid(mesh); require(mesh.size < 18000, "Atmosphere must stay bounded at 4K and maximum density");
        mesh.faceScreen();
        for (int i = 0; i < mesh.size; i += 3) {
            int p = i * 3;
            double area = (mesh.positions[p + 3] - mesh.positions[p]) * (mesh.positions[p + 7] - mesh.positions[p + 1])
                - (mesh.positions[p + 4] - mesh.positions[p + 1]) * (mesh.positions[p + 6] - mesh.positions[p]);
            require(area <= 0, "Menu face culling must not remove stars or half of an aurora ribbon");
        }
        var gradient = new CelestialMesh(3);
        gradient.tri(0, 0, 0, 1, 0, 0, 0, 1, 0, 0xFF112233, 0xFF445566, 0xFF778899);
        gradient.faceScreen();
        require(gradient.positions[4] == 1 && gradient.colors[1] == 0xFF778899
            && gradient.positions[6] == 1 && gradient.colors[2] == 0xFF445566,
            "Winding correction must keep gradient colors attached to their positions");
        int count = mesh.size;
        mesh.clear(); CelestialGeometry.atmosphere(mesh, 3840, 2160, 4, 0.5, -0.5, 1, 1, 0xA78BE0);
        require(mesh.size == count, "Atmospheric particles must not rerandomize between frames");
        mesh.clear(); CelestialGeometry.atmosphere(mesh, 100, 100, 4, 0, 0, 1, 0, 0xA78BE0);
        require(mesh.size == 0, "Zero ambience intensity must produce no geometry");
        require(CelestialGeometry.groundFalloff(0, 0, 0, 3.4) == 1, "Ground illumination must peak below the player");
        require(CelestialGeometry.groundFalloff(3.4, 0, 0, 3.4) == 0, "The ground light must vanish at its outer edge");
        require(CelestialGeometry.groundFalloff(0, -2, 0, 3.4) < 1, "Distant lower terrain must receive less light");
        mesh.clear(); CelestialGeometry.groundTile(mesh, -0.5, 0.508, -0.5, 1, 1, 3.4, 0.44, 0xFF2020);
        valid(mesh); require(mesh.size > 0 && mesh.size <= 96, "Each terrain tile must have a bounded tessellation");
        for (int i = 0; i < mesh.size; i++) {
            require(Math.abs(mesh.positions[i * 3 + 1] - 0.508) < 0.00001, "Light vertices must retain the actual slab height");
            require(((mesh.colors[i] >> 16) & 255) > ((mesh.colors[i] >> 8) & 255), "Red ambience must produce red ground illumination");
        }
        var tiny = new CelestialMesh(9);
        CelestialGeometry.wings(tiny, CelestialGeometry.WingStyle.Astral, 0, 1, 1, 1, 1, 22, 0xA78BE0, 1);
        valid(tiny); require(tiny.size <= 9 && tiny.size % 3 == 0, "Budget exhaustion cannot submit a partial triangle");
        if (args.length == 1) export(Path.of(args[0]));
        System.out.println("Visual checks passed: wing symmetry and folding, pulse lifetime, stable stars, colored terrain light, finite geometry, FPS independence and mesh budgets.");
    }

    private static void valid(CelestialMesh mesh) {
        require(mesh.size % 3 == 0 && mesh.size <= mesh.colors.length, "Triangle count must remain valid");
        for (int i = 0; i < mesh.size * 3; i++) require(Float.isFinite(mesh.positions[i]), "Every generated vertex must be finite");
    }
    private static double width(CelestialMesh mesh) {
        double max = 0; for (int i = 0; i < mesh.size; i++) max = Math.max(max, Math.abs(mesh.positions[i * 3])); return max * 2;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void export(Path path) throws Exception {
        StringBuilder json = new StringBuilder("[");
        var mesh = new CelestialMesh(18000);
        for (var style : CelestialGeometry.WingStyle.values()) {
            mesh.clear(); CelestialGeometry.wings(mesh, style, 0.7, 1, 0.93, 0.7, 0.8, 14, 0xA78BE0, 0.85);
            append(json, mesh, "wings", style.name());
        }
        for (var style : CelestialGeometry.CircleStyle.values()) {
            mesh.clear(); CelestialGeometry.circle(mesh, style, 0.28, 1.4, 0.13, 0.7, 0.032, 0.85, 96, 0xA78BE0, 1, 0.42);
            append(json, mesh, "circle", style.name());
        }
        mesh.clear(); CelestialGeometry.atmosphere(mesh, 1600, 900, 5, 0, 0, 0.55, 0.65, 0xA78BE0);
        append(json, mesh, "atmosphere", "Starlight");
        mesh.clear();
        for (int x = -4; x < 4; x++) for (int z = -4; z < 4; z++) {
            double y = x >= 1 && x <= 2 && z >= -1 && z <= 1 ? 0.5 : 0;
            int c = CelestialGeometry.color((x + z) % 2 == 0 ? 0x303139 : 0x26272F, 1, 0);
            mesh.quad(x, y, z, x + 0.985, y, z, x + 0.985, y, z + 0.985, x, y, z + 0.985, c, c, c, c);
        }
        int baseVertices = mesh.size;
        for (int x = -4; x < 4; x++) for (int z = -4; z < 4; z++) {
            double y = x >= 1 && x <= 2 && z >= -1 && z <= 1 ? 0.508 : 0.008;
            CelestialGeometry.groundTile(mesh, x, y, z, 0.985, 0.985, 3.4, 0.44, 0xA78BE0);
        }
        append(json, mesh, "floor", "Ground illumination", baseVertices);
        json.append(']');
        Files.createDirectories(path.toAbsolutePath().getParent()); Files.writeString(path, json);
    }
    private static void append(StringBuilder json, CelestialMesh mesh, String kind, String name) {
        append(json, mesh, kind, name, 0);
    }
    private static void append(StringBuilder json, CelestialMesh mesh, String kind, String name, int baseVertices) {
        if (json.length() > 1) json.append(',');
        json.append("{\"kind\":\"").append(kind).append("\",\"name\":\"").append(name).append("\",\"baseVertices\":").append(baseVertices).append(",\"positions\":[");
        for (int i = 0; i < mesh.size * 3; i++) { if (i > 0) json.append(','); json.append(mesh.positions[i]); }
        json.append("],\"colors\":[");
        for (int i = 0; i < mesh.size; i++) { if (i > 0) json.append(','); json.append(Integer.toUnsignedString(mesh.colors[i])); }
        json.append("]}");
    }
}
