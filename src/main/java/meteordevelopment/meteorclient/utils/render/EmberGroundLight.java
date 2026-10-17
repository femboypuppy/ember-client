package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Additive colored illumination projected onto cached nearby terrain surfaces, including slabs and stairs. */
public final class EmberGroundLight {
    private record Tile(double x, double y, double z, double width, double depth) {}
    private static final List<Tile> TILES = new ArrayList<>();
    private static final CelestialMesh MESH = new CelestialMesh(60000);
    private static Renderer3D renderer;
    private static Object world;
    private static int ticks;
    private static double scanX, scanY, scanZ, scannedRadius;
    private EmberGroundLight() {}

    @PostInit public static void init() { MeteorClient.EVENT_BUS.subscribe(EmberGroundLight.class); }

    @EventHandler private static void onTick(TickEvent.Post event) {
        var config = Config.get();
        if (mc.world == null || mc.player == null || config == null || !config.ambience.get() || !config.groundLight.get()) {
            TILES.clear(); world = null; return;
        }
        double radius = config.groundLightRadius.get();
        double moved = Math.abs(mc.player.getX() - scanX) + Math.abs(mc.player.getZ() - scanZ) + Math.abs(mc.player.getY() - scanY);
        if (world == mc.world && radius == scannedRadius && ++ticks % 3 != 0 && moved < 1) return;
        TILES.clear(); world = mc.world; scannedRadius = radius;
        scanX = mc.player.getX(); scanY = mc.player.getY(); scanZ = mc.player.getZ();
        int reach = (int) Math.ceil(radius) + 1, px = mc.player.getBlockX(), py = mc.player.getBlockY(), pz = mc.player.getBlockZ();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = px - reach; x <= px + reach; x++) for (int z = pz - reach; z <= pz + reach; z++) {
            if (Math.hypot(x + 0.5 - scanX, z + 0.5 - scanZ) > radius + 1) continue;
            if (!mc.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = py + 1; y >= py - 3; y--) {
                pos.set(x, y, z);
                var state = mc.world.getBlockState(pos);
                if (state.getCollisionShape(mc.world, pos).isEmpty()) continue;
                boolean found = false;
                for (var box : state.getOutlineShape(mc.world, pos).getBoundingBoxes()) {
                    double top = y + box.maxY;
                    if (top > scanY + 1.05 || top < scanY - 2.75) continue;
                    if (TILES.size() >= 600) return;
                    TILES.add(new Tile(x + box.minX, top + 0.008, z + box.minZ, box.maxX - box.minX, box.maxZ - box.minZ));
                    found = true;
                }
                if (found) break;
            }
        }
    }

    @EventHandler private static void onRender(Render3DEvent event) {
        var config = Config.get();
        if (config == null || !config.ambience.get() || !config.groundLight.get() || mc.player == null
            || mc.world == null || mc.world != world || TILES.isEmpty()) return;
        var center = mc.player.getLerpedPos(event.tickDelta);
        var tint = EmberPalette.accent();
        int rgb = (tint.r << 16) | (tint.g << 8) | tint.b;
        double strength = config.groundLightIntensity.get() * 0.44;
        if (strength <= 0.001) return;
        MESH.clear();
        for (var tile : TILES) {
            if (!MESH.hasRoom(96)) break;
            CelestialGeometry.groundTile(MESH, tile.x - center.x, tile.y - center.y, center.z - tile.z - tile.depth,
                tile.width, tile.depth, config.groundLightRadius.get(), strength, rgb);
        }
        if (MESH.size == 0) return;
        if (renderer == null) renderer = new Renderer3D(MeteorRenderPipelines.WORLD_COLORED_LINES_DEPTH, MeteorRenderPipelines.WORLD_ACCENT_LIGHT);
        renderer.begin();
        CelestialRenderer.draw(MESH, renderer, center.x, center.y, center.z, 0, 0);
        renderer.render(event.matrices);
    }
}
