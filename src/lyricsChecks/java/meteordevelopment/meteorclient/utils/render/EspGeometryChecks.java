package meteordevelopment.meteorclient.utils.render;

import java.util.HashMap;
import java.util.Map;

/** Check the production rounded mesh without loading a game or GPU context. */
final class EspGeometryChecks {
    static void run() {
        for (int radius = 0; radius <= 20; radius++) {
            var mesh = RoundedBoxMesh.get(radius / 100.0);
            require(mesh == RoundedBoxMesh.get(radius / 100.0), "Roundness must reuse cached geometry");
            require(mesh.faces.size() <= 150 && mesh.edges.size() <= 120, "Detail must retain a fixed geometry budget");
            Map<String, Integer> incidence = new HashMap<>();
            for (var face : mesh.faces) {
                require(Double.isFinite(face.nx() + face.ny() + face.nz()), "Normals must remain finite");
                double length = face.nx() * face.nx() + face.ny() * face.ny() + face.nz() * face.nz();
                require(Math.abs(length - 1) < 1e-8, "Surface normals must be unit length");
                double[] v = face.vertices();
                for (double value : v) require(Double.isFinite(value) && value >= -1e-10 && value <= 1 + 1e-10,
                    "Rounded geometry must fit inside the actual block bounds");
                for (int i = 0; i < 4; i++) {
                    String a = point(v, i), b = point(v, (i + 1) % 4);
                    String key = a.compareTo(b) < 0 ? a + "/" + b : b + "/" + a;
                    require(!a.equals(b), "Rounded faces cannot contain collapsed edges");
                    incidence.merge(key, 1, Integer::sum);
                }
            }
            for (int count : incidence.values()) require(count == 2, "Every face edge must have a neighbor; rounded boxes cannot have holes");
            for (double[] edge : mesh.edges) {
                double length = 0;
                for (int i = 0; i < 3; i++) length += Math.pow(edge[i] - edge[i + 3], 2);
                require(length > 1e-12, "The outline cannot submit zero-length segments");
            }
        }
        require(RoundedBoxMesh.get(-1) == RoundedBoxMesh.get(0) && RoundedBoxMesh.get(5) == RoundedBoxMesh.get(.2), "Clamp out-of-range roundness");
        require(RoundedBoxMesh.get(0).faces.size() == 6, "Zero roundness must restore the original six planar faces");
        var east = RoundedBoxMesh.get(.1, RoundedBoxMesh.EAST);
        var west = RoundedBoxMesh.get(.1, RoundedBoxMesh.WEST);
        require(east.faces.size() == 125 && west.faces.size() == 125, "Connected blocks omit their touching faces");
        var seamA = new java.util.HashSet<String>(); var seamB = new java.util.HashSet<String>();
        for (var face : east.faces) for (int i = 0; i < 4; i++) if (Math.abs(face.vertices()[i * 3] - 1) < 1e-8)
            seamA.add(Math.round(face.vertices()[i * 3 + 1] * 1e8) + "," + Math.round(face.vertices()[i * 3 + 2] * 1e8));
        for (var face : west.faces) for (int i = 0; i < 4; i++) if (Math.abs(face.vertices()[i * 3]) < 1e-8)
            seamB.add(Math.round(face.vertices()[i * 3 + 1] * 1e8) + "," + Math.round(face.vertices()[i * 3 + 2] * 1e8));
        require(seamA.equals(seamB), "Joined rounded blocks must meet at matching vertices without a gap");
        require(RoundedBoxMesh.get(.1, 63).faces.isEmpty(), "Fully enclosed blocks submit no visible faces");
        RoundedBoxMesh.prepare(.13);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (RoundedBoxMesh.peek(.13, 63) == null && System.nanoTime() < deadline) {
            try { Thread.sleep(1); } catch (InterruptedException error) { throw new AssertionError(error); }
        }
        for (int joined = 0; joined < 64; joined++) require(RoundedBoxMesh.peek(.13, joined) != null,
            "Background preparation must publish every neighbor variant for non-blocking world rendering");
        System.out.println("ESP geometry passed: closed rounded surfaces, normals, bounds, cache reuse, zero roundness and detail budgets.");
    }

    static void export(java.nio.file.Path directory) throws Exception {
        java.nio.file.Files.createDirectories(directory);
        var cases = new java.util.LinkedHashMap<String, RoundedBoxMesh>();
        cases.put("square", RoundedBoxMesh.get(0)); cases.put("rounded", RoundedBoxMesh.get(.1));
        cases.put("joined-east", RoundedBoxMesh.get(.1, RoundedBoxMesh.EAST)); cases.put("joined-west", RoundedBoxMesh.get(.1, RoundedBoxMesh.WEST));
        java.nio.file.Files.writeString(directory.resolve("esp-geometry.json"), new com.google.gson.Gson().toJson(cases));
    }

    private static String point(double[] v, int i) { return Math.round(v[i * 3] * 1e8) + "," + Math.round(v[i * 3 + 1] * 1e8) + "," + Math.round(v[i * 3 + 2] * 1e8); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
