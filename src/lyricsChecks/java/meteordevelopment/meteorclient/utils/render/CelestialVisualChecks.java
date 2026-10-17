package meteordevelopment.meteorclient.utils.render;

import java.nio.file.Files;
import java.nio.file.Path;

/** Exercises the exact procedural geometry used by Minecraft; also exports reviewable previews. */
public final class CelestialVisualChecks {
    public static void main(String[] args) throws Exception {
        terrainSurfaces();
        worldAtmosphere();
        worldLighting();
        meteordevelopment.meteorclient.utils.misc.MusicReactionChecks.run();
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

    private static void worldLighting() {
        require(CelestialLighting.skyFactor(1, 0.42) < 0.7, "Daytime must actually dim when world darkness is enabled");
        require(CelestialLighting.skyFactor(1, 0) == 1, "Zero darkness must preserve daytime light power");
        require(CelestialLighting.skyFactor(0, 1) == 0, "World lighting cannot create sunlight in an unlit cave");
        require(CelestialLighting.gamma(100, 0.42) < 0.5, "An external gamma boost must not erase the requested darkness");
        float red = CelestialLighting.channel(1, 255, 255, 1, 1), blue = CelestialLighting.channel(1, 16, 255, 1, 1);
        require(red > blue * 4 && blue > 0, "Red illumination must be strongly red while retaining texture readability");
        require(CelestialLighting.channel(0.6f, 255, 255, 1, 0) == 0.6f, "Zero tint must preserve the original daylight color");
        require(CelestialLighting.horizon(255, 0.75) > CelestialLighting.horizon(16, 0.75) * 5, "Fog horizon must be colored by the sky without a blue background mix");
        System.out.println("World lighting passed: dimmed daylight, colored light, gamma bounds, cave darkness and horizon matching.");
    }
    private static void valid(CelestialMesh mesh) {
        require(mesh.size % 3 == 0 && mesh.size <= mesh.colors.length, "Triangle count must remain valid");
        for (int i = 0; i < mesh.size * 3; i++) require(Float.isFinite(mesh.positions[i]), "Every generated vertex must be finite");
    }
    private static void terrainSurfaces() {
        var cube = new TerrainLightSurfaces.Bounds(0, 0, 0, 1, 1, 1);
        var alone = TerrainLightSurfaces.exposed(cube, java.util.List.of(cube));
        require(alone.size() == 6, "An isolated block needs six lit faces, including vertical sides");
        var neighbor = new TerrainLightSurfaces.Bounds(1, 0, 0, 2, 1, 1);
        var pair = java.util.List.of(cube, neighbor);
        require(TerrainLightSurfaces.exposed(cube, pair).size() == 5, "A shared full-block face must not be rendered");
        var slab = new TerrainLightSurfaces.Bounds(1, 0, 0, 2, 0.5, 1);
        var partial = TerrainLightSurfaces.exposed(cube, java.util.List.of(cube, slab));
        double sideArea = partial.stream().filter(f -> f.axis() == 0 && f.sign() == 1).mapToDouble(TerrainLightSurfaces.Face::area).sum();
        require(Math.abs(sideArea - 0.5) < 1e-9, "An adjacent slab must leave the upper half of the block side lit");
        var lower = new TerrainLightSurfaces.Bounds(0, 0, 0, 1, 0.5, 1);
        var step = new TerrainLightSurfaces.Bounds(0.5, 0.5, 0, 1, 1, 1);
        var stair = java.util.List.of(lower, step);
        double surfaceArea = stair.stream().flatMap(b -> TerrainLightSurfaces.exposed(b, stair).stream()).mapToDouble(TerrainLightSurfaces.Face::area).sum();
        require(Math.abs(surfaceArea - 5.5) < 1e-9, "Stair shapes must remove only the internal shared surfaces");
        var mesh = new CelestialMesh(60000);
        for (var face : alone) face.light(mesh, 0.5, 1, 0.5, 3.4, 0.44, 0xFF2020);
        valid(mesh);
        boolean vertical = false;
        for (int i = 0; i < mesh.size; i += 3) {
            int p = i * 3;
            if (Math.abs(mesh.positions[p + 1] - mesh.positions[p + 4]) > 0.01 || Math.abs(mesh.positions[p + 1] - mesh.positions[p + 7]) > 0.01) vertical = true;
            require((mesh.colors[i] >> 16 & 255) > (mesh.colors[i] >> 8 & 255), "Sides must share the red accent");
        }
        require(vertical, "The submitted mesh must contain lit vertical triangles");
        require(mesh.size <= 6 * 96, "A cube must keep the bounded per-face light budget");
        mesh.clear();
        for (var face : alone) face.light(mesh, 0.5, 1, 0.5, 3.4, 0.44, 0xFF2020, 0, 1);
        int[] shimmer = java.util.Arrays.copyOf(mesh.colors, mesh.size);
        mesh.clear();
        for (var face : alone) face.light(mesh, 0.5, 1, 0.5, 3.4, 0.44, 0xFF2020, 8, 1);
        valid(mesh);
        require(!java.util.Arrays.equals(shimmer, java.util.Arrays.copyOf(mesh.colors, mesh.size)), "Light shimmer must change illumination without changing the surface geometry");
        var tiny = new CelestialMesh(9);
        for (var face : alone) face.light(tiny, 0.5, 1, 0.5, 3.4, 0.44, 0xFF2020);
        valid(tiny);
        require(tiny.size <= 9, "Surface light must stop cleanly when its mesh is full");
        System.out.println("Terrain light passed: vertical sides, hidden faces, slabs, stairs and bounded meshes.");
    }
    private static void worldAtmosphere() {
        var sky = new CelestialMesh(60000);
        CelestialWorldGeometry.skyShell(sky); valid(sky);
        require(sky.size == 3072, "The sky shell must stay in its fixed mesh budget");
        for (int i = 0; i < sky.size; i++) {
            int p = i * 3;
            double radius = Math.sqrt(sky.positions[p] * sky.positions[p] + sky.positions[p + 1] * sky.positions[p + 1] + sky.positions[p + 2] * sky.positions[p + 2]);
            require(Math.abs(radius - 32) < 0.0001, "Sky direction vertices must lie on a closed sphere");
        }
        sky.clear(); CelestialWorldGeometry.skyStars(sky, 10000, 1, 1, 0xFF2020); valid(sky);
        require(sky.size > 40000 && sky.size <= 60000, "Maximum sky-star density must fit its allocation");
        float[] stable = java.util.Arrays.copyOf(sky.positions, sky.size * 3);
        sky.clear(); CelestialWorldGeometry.skyStars(sky, 10000, 1, 1, 0xFF2020);
        require(java.util.Arrays.equals(stable, java.util.Arrays.copyOf(sky.positions, sky.size * 3)), "Stars must not rerandomize at a fixed time");
        sky.clear(); CelestialWorldGeometry.skyStars(sky, 0, 0, 1, 0xFF2020);
        require(sky.size == 0, "Zero star density must produce no sky stars");
        var a = new CelestialMesh(24000); var b = new CelestialMesh(24000);
        CelestialWorldGeometry.motes(a, 5.99, 65, -1000, 9, 1, 1, 0xFF2020);
        CelestialWorldGeometry.motes(b, 6.01, 65, -1000, 9, 1, 1, 0xFF2020);
        valid(a); valid(b); require(a.size > 0 && b.size > 0, "World starlight must render outside menus");
        int matched = 0;
        for (int i = 0; i < a.size; i += 48) for (int j = 0; j < b.size; j += 48) {
            if (Math.abs(a.positions[i * 3] + 5.99 - b.positions[j * 3] - 6.01) < 0.0001
                && Math.abs(a.positions[i * 3 + 1] - b.positions[j * 3 + 1]) < 0.0001
                && Math.abs(a.positions[i * 3 + 2] - b.positions[j * 3 + 2]) < 0.0001) { matched++; break; }
        }
        require(matched > 20, "Moving across a grid boundary must retain world-anchored particle positions");
        a.clear(); CelestialWorldGeometry.motes(a, 0, 0, 0, 0, 1, 0, 0xFF2020);
        require(a.size == 0, "Zero intensity must hide world motes");
        require(CelestialWorldGeometry.tint(0.4f, 255, 0.4f, 0) == 0.4f, "Zero fog amount must preserve the original scene");
        require(CelestialWorldGeometry.tint(0.4f, 255, 0.4f, 1) > CelestialWorldGeometry.tint(0.4f, 0, 0.4f, 1), "Fog tint must follow the selected accent channel");
        System.out.println("World atmosphere passed: closed sky sphere, stable stars, world-space parallax, color tint and geometry budgets.");
    }
    private static double width(CelestialMesh mesh) {
        double max = 0; for (int i = 0; i < mesh.size; i++) max = Math.max(max, Math.abs(mesh.positions[i * 3])); return max * 2;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void export(Path path) throws Exception {
        StringBuilder json = new StringBuilder("[");
        var mesh = new CelestialMesh(60000);
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
        mesh.clear(); CelestialWorldGeometry.skyShell(mesh);
        append(json, mesh, "sky", "Celestial sky");
        mesh.clear(); CelestialWorldGeometry.skyStars(mesh, 5, 0.8, 0.85, 0xA78BE0);
        append(json, mesh, "sky-stars", "Celestial stars");
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
        json.append(",{\"kind\":\"lighting\",\"name\":\"Vanilla\",\"sky\":1,\"gamma\":0.7,\"tint\":[1,1,1]}");
        lightFixture(json, "Red", 0xFF2020, 0.42);
        lightFixture(json, "Blue", 0x2020FF, 0.42);
        lightFixture(json, "Dark", 0xFF2020, 1);
        json.append(']');
        Files.createDirectories(path.toAbsolutePath().getParent()); Files.writeString(path, json);
    }
    private static void lightFixture(StringBuilder json, String name, int rgb, double darkness) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255, max = Math.max(r, Math.max(g, b));
        json.append(",{\"kind\":\"lighting\",\"name\":\"").append(name).append("\",\"sky\":")
            .append(CelestialLighting.skyFactor(1, darkness)).append(",\"gamma\":").append(CelestialLighting.gamma(0.7f, darkness)).append(",\"tint\":[")
            .append(CelestialLighting.channel(1, r, max, 1, 0.8)).append(',')
            .append(CelestialLighting.channel(1, g, max, 1, 0.8)).append(',')
            .append(CelestialLighting.channel(1, b, max, 1, 0.8)).append("]}");
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
