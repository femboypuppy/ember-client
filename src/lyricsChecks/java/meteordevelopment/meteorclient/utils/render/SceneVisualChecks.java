package meteordevelopment.meteorclient.utils.render;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class SceneVisualChecks {
    public static void main(String[] args) throws Exception { run(); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void valid(CelestialMesh mesh) {
        require(mesh.size % 3 == 0 && mesh.size <= mesh.colors.length, "Only complete triangles fit the mesh budget");
        for (int i = 0; i < mesh.size * 3; i++) require(Float.isFinite(mesh.positions[i]), "Effect coordinates stay finite");
    }
    public static void run() throws Exception {
        var mesh = new CelestialMesh(SceneEffectsGeometry.MAX_VERTICES);
        SceneEffectsGeometry.ambient(mesh, 0, 64, 0, 4, 0, .6, 0, 1, 0x6898E8);
        require(mesh.size == 0, "Disabled ambient effects leave no geometry");
        SceneEffectsGeometry.ambient(mesh, 0, 64, 0, 4, 1, .6, 1, 0, 0x6898E8);
        require(mesh.size == 0, "Zero brightness clears geometry");
        for (double camera : new double[]{0, 5.999, 6.001, -200, 29999900}) {
            mesh.clear(); SceneEffectsGeometry.ambient(mesh, camera, 64, -camera, 42, 1, 1.5, 1, 1, 0x6898E8);
            valid(mesh); require(mesh.size > 0 && mesh.size < 90000, "World volume remains bounded across teleports and cell boundaries");
            for (int i = 0; i < mesh.size; i++) {
                int p = i * 3;
                require(Math.abs(mesh.positions[p]) < 23 && Math.abs(mesh.positions[p+1]) < 23 && Math.abs(mesh.positions[p+2]) < 23, "Ambient vertices stay camera-relative even far from origin");
            }
        }
        mesh.clear(); SceneEffectsGeometry.ambient(mesh, 0, 64, 0, 42, .5, .6, .5, .65, 0x6898E8);
        float[] positions = Arrays.copyOf(mesh.positions, mesh.size * 3); int[] colors = Arrays.copyOf(mesh.colors, mesh.size);
        mesh.clear(); SceneEffectsGeometry.ambient(mesh, 0, 64, 0, 42, .5, .6, .5, .65, 0x6898E8);
        require(Arrays.equals(positions, Arrays.copyOf(mesh.positions, mesh.size * 3)) && Arrays.equals(colors, Arrays.copyOf(mesh.colors, mesh.size)), "Effects do not rerandomize between frames");
        mesh.clear(); SceneEffectsGeometry.player(mesh,0,-1.5,5,1.8,.6,3,0,false,.42,0,1,0x6898E8);
        require(mesh.size == 0, "All player toggles off leaves no cosmetic geometry");
        SceneEffectsGeometry.player(mesh,0,-1.5,5,1.8,.6,3,1,true,.42,1,0,0x6898E8);
        require(mesh.size == 0, "Players at the range boundary fade out completely");
        for (int i = 0; i < SceneEffectsGeometry.MAX_PLAYERS; i++) SceneEffectsGeometry.player(mesh,i-6,-1.5,5,1.8,.6,3+i,1,true,.9,1,1,0x6898E8);
        valid(mesh); require(mesh.size < 100000 && mesh.size > 10000, "Twelve decorated players stay inside their geometry budget");
        mesh.clear(); SceneEffectsGeometry.player(mesh,0,0,5,1.8,.6,3,0,true,.42,0,1,0x6898E8);
        require(mesh.size > 0, "Halos work independently from auras and ribbons");
        for (int i = 0; i < mesh.size; i++) require(mesh.positions[i*3+1] > 1.9, "The complete halo glow stays above the head");
        for (int kind = 0; kind < 3; kind++) { mesh.clear(); SceneEffectsGeometry.shape(mesh,kind,0,0,4,.3,2,1,0x6898E8);valid(mesh);require(mesh.size > 0,"Every geometric style renders"); }
        var tiny = new CelestialMesh(15);
        SceneEffectsGeometry.ambient(tiny,0,64,0,4,1,1.5,1,1,0x6898E8); valid(tiny);
        SceneEffectsGeometry.player(tiny,0,0,5,1.8,.6,3,1,true,.42,1,1,0x6898E8); valid(tiny);
        Path out = Path.of("build/visual-preview/scene-effects.json");Files.createDirectories(out.getParent());
        StringBuilder json = new StringBuilder("[");
        mesh.clear(); SceneEffectsGeometry.ambient(mesh,0,2,0,4,1,.8,1,.8,0x6898E8); append(json,"ambient",mesh);
        mesh.clear(); SceneEffectsGeometry.player(mesh,0,-1.5,5,1.8,.6,3,.8,true,.42,.8,1,0xA78BE0);append(json,"player",mesh);
        mesh.clear(); SceneEffectsGeometry.player(mesh,0,-1.5,5,1.8,.6,3,0,true,.42,0,1,0xA78BE0);append(json,"halo",mesh);
        mesh.clear(); SceneEffectsGeometry.player(mesh,0,-1.5,5,1.8,.6,3,.8,false,.42,0,1,0xA78BE0);append(json,"aura",mesh);
        mesh.clear(); SceneEffectsGeometry.player(mesh,0,-1.5,5,1.8,.6,3,0,false,.42,.8,1,0xA78BE0);append(json,"ribbons",mesh);
        mesh.clear(); SceneEffectsGeometry.ambient(mesh,0,2,0,4,0,.8,1,.8,0x6898E8);append(json,"curved trails",mesh);
        json.append(']');Files.writeString(out,json);
        System.out.println("Scene effects passed: deterministic geometry, all styles, distant coordinates, independent toggles, above-head halos, twelve-player limits, zero brightness and finite triangle budgets.");
    }
    private static void append(StringBuilder out, String name, CelestialMesh mesh) {
        if (out.length() > 1) out.append(',');out.append("{\"name\":\"").append(name).append("\",\"positions\":[");
        for (int i=0;i<mesh.size*3;i++){if(i>0)out.append(',');out.append(mesh.positions[i]);}out.append("],\"colors\":[");
        for (int i=0;i<mesh.size;i++){if(i>0)out.append(',');out.append(Integer.toUnsignedLong(mesh.colors[i]));}out.append("]}");
    }
}
