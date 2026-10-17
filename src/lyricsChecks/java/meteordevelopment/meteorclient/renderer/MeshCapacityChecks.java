package meteordevelopment.meteorclient.renderer;

import com.mojang.blaze3d.vertex.VertexFormat;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.render.VertexFormats;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;

/** Exercises the production CPU allocator, without uploading buffers or starting a game. */
public final class MeshCapacityChecks {
    public static void main(String[] args) throws Exception {
        // MeshBuilder asks Fabric whether debug guards should be enabled. Supply the
        // launch metadata for this standalone CPU check; no Minecraft client is started.
        if (FabricLauncherBase.getLauncher() == null) {
            FabricLauncherBase.setLauncher((FabricLauncher) Proxy.newProxyInstance(MeshCapacityChecks.class.getClassLoader(),
                new Class<?>[]{FabricLauncher.class}, (proxy, method, parameters) -> switch (method.getName()) {
                    case "isDevelopment" -> false;
                    case "getTargetNamespace" -> "named";
                    case "getTargetClassLoader" -> MeshCapacityChecks.class.getClassLoader();
                    default -> null;
                }));
        }
        // The reported ground-light frame requested 2,916 vertices / 46,656 bytes.
        // Assert the reservation before writing, so the broken allocator cannot corrupt memory.
        checkFresh(2916, 2916);
        checkFresh(60000, 60000);
        checkFresh(18000, 18000);
        checkFresh(90000, 90000);
        checkFresh(4, 12000);

        var mesh = new MeshBuilder(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES);
        mesh.ensureCapacity(3, 3);
        append(mesh, 3);
        mesh.ensureCapacity(60000, 60000);
        capacity(mesh, 60003, 60003);
        append(mesh, 60000);
        ByteBuffer vertices = buffer(mesh, "vertices");
        require(vertices.getFloat(16) == 1 && vertices.getFloat(3 * 16) == 0,
            "Growing a buffer must retain previously written vertex data");
        require(vertices.getFloat(60002 * 16) == 59999, "The complete appended mesh must fit");
        require(mesh.getIndicesCount() == 60003, "Growing must retain the existing triangle indices");
        System.out.println("Mesh capacity checks passed: crash-sized first reservation, full effect budgets, independent index sizing, growth and retained data; no GPU uploads required.");
    }

    private static void checkFresh(int vertices, int indices) throws Exception {
        var mesh = new MeshBuilder(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES);
        mesh.ensureCapacity(vertices, indices);
        capacity(mesh, vertices, indices);
        // Capacity reservation has no camera/device dependency. begin() is intentionally
        // omitted here because it reads the game's camera; freshly allocated CPU cursors
        // already point at the beginning of both buffers.
        if (vertices == indices && vertices % 3 == 0) append(mesh, vertices);
    }

    private static void capacity(MeshBuilder mesh, int vertices, int indices) throws Exception {
        require(buffer(mesh, "vertices").capacity() >= vertices * VertexFormats.POSITION_COLOR.getVertexSize(),
            "First/bulk vertex reservation is undersized: needed " + vertices * 16 + " bytes, got " + buffer(mesh, "vertices").capacity());
        require(buffer(mesh, "indices").capacity() >= indices * Integer.BYTES, "Index reservation is undersized");
    }

    private static void append(MeshBuilder mesh, int count) {
        Color color = new Color(255, 32, 32, 255);
        for (int i = 0; i < count; i += 3) {
            int first = mesh.vec3(i, 0, 0).color(color).next();
            int second = mesh.vec3(i + 1, 0, 0).color(color).next();
            int third = mesh.vec3(i + 2, 0, 0).color(color).next();
            mesh.triangle(first, second, third);
        }
    }

    private static ByteBuffer buffer(MeshBuilder mesh, String name) throws Exception {
        var field = MeshBuilder.class.getDeclaredField(name); field.setAccessible(true);
        return (ByteBuffer) field.get(mesh);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
