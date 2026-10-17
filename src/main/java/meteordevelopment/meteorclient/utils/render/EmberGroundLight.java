package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.meteorclient.utils.misc.MusicReactive;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Additive colored illumination projected onto cached nearby terrain surfaces, including slabs and stairs. */
public final class EmberGroundLight {
    private static final List<TerrainLightSurfaces.Face> TILES = new ArrayList<>();
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
        Map<Long, List<TerrainLightSurfaces.Bounds>> shapes = new HashMap<>();
        scan:
        for (int x = px - reach; x <= px + reach; x++) for (int z = pz - reach; z <= pz + reach; z++) {
            if (Math.hypot(x + 0.5 - scanX, z + 0.5 - scanZ) > radius + 1) continue;
            if (!mc.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = py + 1; y >= py - 3; y--) {
                var boxes = bounds(shapes, pos, x, y, z);
                if (boxes.isEmpty()) continue;
                List<TerrainLightSurfaces.Bounds> nearby = new ArrayList<>(boxes);
                nearby.addAll(bounds(shapes, pos, x - 1, y, z)); nearby.addAll(bounds(shapes, pos, x + 1, y, z));
                nearby.addAll(bounds(shapes, pos, x, y - 1, z)); nearby.addAll(bounds(shapes, pos, x, y + 1, z));
                nearby.addAll(bounds(shapes, pos, x, y, z - 1)); nearby.addAll(bounds(shapes, pos, x, y, z + 1));
                for (var box : boxes) {
                    TILES.addAll(TerrainLightSurfaces.exposed(box, nearby));
                    if (TILES.size() >= 4096) break scan;
                }
            }
        }
        TILES.sort(Comparator.comparingDouble(f -> Math.pow(f.center(0) - scanX, 2)
            + Math.pow(f.center(1) - scanY, 2) + Math.pow(f.center(2) - scanZ, 2)));
    }

    private static List<TerrainLightSurfaces.Bounds> bounds(Map<Long, List<TerrainLightSurfaces.Bounds>> cache,
                                                          BlockPos.Mutable pos, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        var cached = cache.get(key);
        if (cached != null) return cached;
        List<TerrainLightSurfaces.Bounds> result = new ArrayList<>();
        if (mc.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
            pos.set(x, y, z);
            var state = mc.world.getBlockState(pos);
            if (!state.getCollisionShape(mc.world, pos).isEmpty()) {
                for (var b : state.getOutlineShape(mc.world, pos).getBoundingBoxes()) {
                    if (result.size() >= 32) break;
                    result.add(new TerrainLightSurfaces.Bounds(x + b.minX, y + b.minY, z + b.minZ, x + b.maxX, y + b.maxY, z + b.maxZ));
                }
            }
        }
        cache.put(key, result);
        return result;
    }

    @EventHandler private static void onRender(Render3DEvent event) {
        var config = Config.get();
        if (config == null || !config.ambience.get() || !config.groundLight.get() || mc.player == null
            || mc.world == null || mc.world != world || TILES.isEmpty()) return;
        var center = mc.player.getLerpedPos(event.tickDelta);
        var tint = EmberPalette.accent();
        int rgb = CelestialLighting.surfaceColor((tint.r << 16) | (tint.g << 8) | tint.b);
        double strength = config.groundLightIntensity.get() * CelestialLighting.SURFACE_GAIN * MusicReactive.exposure(config.bassGround.get());
        if (strength <= 0.001) return;
        MESH.clear();
        for (var tile : TILES) {
            if (!MESH.hasRoom(384)) break;
            tile.light(MESH, center.x, center.y, center.z, config.groundLightRadius.get(), strength, rgb,
                EmberWorldAtmosphere.time(), config.worldAtmosphere.get() ? config.lightShimmer.get() : 0);
        }
        if (MESH.size == 0) return;
        if (renderer == null) renderer = new Renderer3D(MeteorRenderPipelines.WORLD_COLORED_LINES_DEPTH, MeteorRenderPipelines.WORLD_ACCENT_LIGHT);
        renderer.begin();
        CelestialRenderer.draw(MESH, renderer, center.x, center.y, center.z, 0, 0);
        renderer.render(event.matrices);
    }
}
