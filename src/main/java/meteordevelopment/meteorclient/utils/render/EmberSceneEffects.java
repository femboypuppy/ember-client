package meteordevelopment.meteorclient.utils.render;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;

/** Local cosmetics for at most twelve nearby players, and a fixed world-space ambient volume. */
public final class EmberSceneEffects {
    private static final CelestialMesh MESH = new CelestialMesh(SceneEffectsGeometry.MAX_VERTICES);
    private static final PlayerEntity[] NEAREST = new PlayerEntity[SceneEffectsGeometry.MAX_PLAYERS];
    private static final double[] DISTANCES = new double[SceneEffectsGeometry.MAX_PLAYERS];
    private static Renderer3D renderer;
    private EmberSceneEffects() {}
    @PostInit public static void init() { MeteorClient.EVENT_BUS.subscribe(EmberSceneEffects.class); }

    @EventHandler private static void onRender(Render3DEvent event) {
        var c = Config.get();
        if (c == null || !c.ambience.get() || !c.worldAtmosphere.get() || mc.world == null || mc.player == null
            || mc.player.hasStatusEffect(StatusEffects.BLINDNESS) || mc.player.hasStatusEffect(StatusEffects.DARKNESS)) return;
        MESH.clear();
        var accent = EmberPalette.accent(); int rgb = accent.r << 16 | accent.g << 8 | accent.b;
        double time = (mc.world.getTime() + (mc.isPaused() ? 0 : event.tickDelta)) * .05 * c.sceneEffectSpeed.get();
        SceneEffectsGeometry.ambient(MESH, event.offsetX, event.offsetY, event.offsetZ, time,
            c.geometricParticles.get() ? c.geometricDensity.get() : 0, c.geometricSize.get(),
            c.curvedTrails.get() ? c.curvedTrailDensity.get() : 0, c.sceneEffectBrightness.get(), rgb);
        if (c.playerAuras.get() || c.playerHalos.get() || c.playerRibbons.get()) {
            int count = 0; double range = c.cosmeticRange.get();
            for (var player : mc.world.getPlayers()) {
                boolean self = player == mc.player;
                if (player.isSpectator() || player.isInvisible() || !player.isAlive()
                    || self && (!c.cosmeticsSelf.get() || mc.getCameraEntity() == player && mc.options.getPerspective().isFirstPerson())
                    || !self && !c.cosmeticsOthers.get()) continue;
                double distance = player.squaredDistanceTo(event.offsetX, event.offsetY, event.offsetZ);
                if (distance > range * range) continue;
                int at = count;
                if (at == NEAREST.length) { if (distance >= DISTANCES[at - 1]) continue; at--; }
                while (at > 0 && DISTANCES[at - 1] > distance) {
                    if (at < NEAREST.length) { DISTANCES[at] = DISTANCES[at - 1]; NEAREST[at] = NEAREST[at - 1]; } at--;
                }
                NEAREST[at] = player; DISTANCES[at] = distance; count = Math.min(count + 1, NEAREST.length);
            }
            for (int i = 0; i < count; i++) {
                var player = NEAREST[i]; var pos = player.getLerpedPos(event.tickDelta);
                double fade = Math.clamp((range - Math.sqrt(DISTANCES[i])) / 6, 0, 1);
                SceneEffectsGeometry.player(MESH, pos.x - event.offsetX, pos.y - event.offsetY, event.offsetZ - pos.z,
                    player.getHeight(), player.getWidth(), time + (player.getId() * .37), c.playerAuras.get() ? c.auraStrength.get() : 0,
                    c.playerHalos.get(), c.haloSize.get(), c.playerRibbons.get() ? c.ribbonStrength.get() : 0, fade, rgb);
            }
            java.util.Arrays.fill(NEAREST, null);
        }
        if (MESH.size == 0) return;
        if (renderer == null) renderer = new Renderer3D(MeteorRenderPipelines.WORLD_COLORED_LINES_DEPTH, MeteorRenderPipelines.WORLD_ACCENT_LIGHT);
        renderer.begin(); CelestialRenderer.draw(MESH, renderer, event.offsetX, event.offsetY, event.offsetZ, 0, 0); renderer.render(event.matrices);
    }
}
