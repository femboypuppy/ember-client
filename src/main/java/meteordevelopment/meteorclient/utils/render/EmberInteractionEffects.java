package meteordevelopment.meteorclient.utils.render;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.BlockEffectEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.*;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;

public final class EmberInteractionEffects {
    private static final InteractionEffects EFFECTS = new InteractionEffects();
    private static final CelestialMesh MESH = new CelestialMesh(60000);
    private static Object world;
    private static double lastX, lastY, lastZ, travel;
    private static int blockBirths;
    private static Renderer3D renderer;

    private EmberInteractionEffects() {}

    @PostInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(EmberInteractionEffects.class);
    }

    private static boolean enabled() {
        var c = Config.get();
        return c != null
                && c.ambience.get()
                && c.worldAtmosphere.get()
                && mc.world != null
                && mc.player != null
                && !mc.player.isSpectator();
    }

    @EventHandler
    private static void onTick(TickEvent.Post event) {
        blockBirths = 0;
        if (!enabled()) {
            EFFECTS.clear();
            world = null;
            travel = 0;
            return;
        }
        var p = mc.player;
        var c = Config.get();
        double x = p.getX(), y = p.getY(), z = p.getZ();
        if (world != mc.world
                || Math.sqrt(
                                Math.pow(x - lastX, 2)
                                        + Math.pow(y - lastY, 2)
                                        + Math.pow(z - lastZ, 2))
                        > 8) {
            EFFECTS.clear();
            world = mc.world;
            lastX = x;
            lastY = y;
            lastZ = z;
            travel = 0;
        }
        EFFECTS.tick(
                .05,
                c.movementEffects.get() && c.movementAmount.get() > 0,
                c.blockEffects.get() && c.blockEffectAmount.get() > 0);
        double dx = x - lastX, dz = z - lastZ, distance = Math.hypot(dx, dz);
        lastX = x;
        lastY = y;
        lastZ = z;
        if (!c.movementEffects.get()
                || c.movementAmount.get() <= 0
                || p.hasVehicle()
                || p.getAbilities().flying) {
            travel = 0;
            return;
        }
        if (distance < .015) {
            travel = 0;
            return;
        }
        EmberWeather.disturb(x, y, z, dx, dz, c.movementAmount.get());
        BlockPos pos = BlockPos.ofFloored(x, y - .05, z);
        var state = mc.world.getBlockState(pos);
        var fluid = state.getFluidState();
        boolean water = fluid.isIn(FluidTags.WATER);
        if (water && !p.isSubmergedInWater()) {
            for (int i = 0; i < 3; i++) {
                var above = mc.world.getFluidState(pos.up());
                if (!above.isIn(FluidTags.WATER)) break;
                pos = pos.up();
                fluid = above;
            }
        }
        double surface = pos.getY() + fluid.getHeight(mc.world, pos);
        if (water && !p.isSubmergedInWater() && surface >= y - .25 && surface <= y + p.getHeight() + .2) {
            travel += distance;
            if (c.movementRipples.get() && travel >= .45) {
                travel %= .45;
                EFFECTS.emit(
                        InteractionEffects.Kind.Water,
                        x,
                        surface + .018,
                        z,
                        c.movementAmount.get(),
                        .5,
                        c.movementLifetime.get() * 1.35);
            }
        } else if (p.isOnGround() && !p.isTouchingWater()) {
            travel += distance;
            if (travel >= .65) {
                travel %= .65;
                EFFECTS.emit(
                        InteractionEffects.Kind.Step,
                        x,
                        y + .025,
                        z,
                        c.movementAmount.get(),
                        .6,
                        c.movementLifetime.get());
            }
        } else travel = 0;
    }

    @EventHandler
    private static void onBlock(BlockEffectEvent event) {
        if (!enabled()) return;
        var c = Config.get();
        if (!c.blockEffects.get()
                || c.blockEffectAmount.get() <= 0
                || event.placed() && !c.blockPlaceEffects.get()
                || !event.placed() && !c.blockBreakEffects.get()
                || blockBirths >= 6) return;
        if (world != mc.world) {
            EFFECTS.clear();
            world = mc.world;
            lastX = mc.player.getX();
            lastY = mc.player.getY();
            lastZ = mc.player.getZ();
            travel = 0;
        }
        double x = event.pos().getX() + .5,
                y = event.pos().getY() + .5,
                z = event.pos().getZ() + .5;
        if (mc.player.squaredDistanceTo(x, y, z) > 144) return;
        if (event.placed()) {
            var camera = mc.gameRenderer.getCamera().getCameraPos();
            double dx = camera.x - x, dy = camera.y - y, dz = camera.z - z;
            if (Math.abs(dy) > Math.max(Math.abs(dx), Math.abs(dz))) y += Math.copySign(.508, dy);
            else if (Math.abs(dx) > Math.abs(dz)) x += Math.copySign(.508, dx);
            else z += Math.copySign(.508, dz);
        }
        blockBirths++;
        EFFECTS.emit(
                event.placed() ? InteractionEffects.Kind.Place : InteractionEffects.Kind.Break,
                x,
                y,
                z,
                c.blockEffectAmount.get(),
                c.blockEffectSize.get(),
                c.blockEffectLifetime.get());
    }

    @EventHandler
    private static void onRender(Render3DEvent event) {
        if (!enabled() || world != mc.world) return;
        var c = Config.get();
        if (!c.movementEffects.get() && !c.blockEffects.get()) return;
        var accent = EmberPalette.accent();
        MESH.clear();
        EFFECTS.build(
                MESH,
                event.offsetX,
                event.offsetY,
                event.offsetZ,
                event.tickDelta,
                accent.r << 16 | accent.g << 8 | accent.b);
        if (MESH.size == 0) return;
        if (renderer == null)
            renderer =
                    new Renderer3D(
                            MeteorRenderPipelines.WORLD_COLORED_LINES_DEPTH,
                            MeteorRenderPipelines.WORLD_COLORED_DEPTH);
        renderer.begin();
        CelestialRenderer.draw(MESH, renderer, event.offsetX, event.offsetY, event.offsetZ, 0, 0);
        renderer.render(event.matrices);
    }
}
