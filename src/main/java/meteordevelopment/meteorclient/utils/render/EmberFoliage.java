package meteordevelopment.meteorclient.utils.render;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.orbit.EventHandler;

/** Chunk workers read one published value. Music and accent changes never rebuild chunks. */
public final class EmberFoliage {
    private static volatile double balance;
    private static double requested;
    private static int settled;
    private static Object world;

    private EmberFoliage() {}

    @PostInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(EmberFoliage.class);
    }

    public static int color(int original) {
        return CelestialLighting.foliage(original, balance);
    }

    @EventHandler
    private static void onTick(TickEvent.Post event) {
        var c = Config.get();
        double next =
                c != null
                                && c.ambience.get()
                                && c.worldAtmosphere.get()
                                && c.worldLighting.get()
                                && mc.world != null
                                && mc.world.getDimension().hasSkyLight()
                                && c.worldLightTint.get() > 0
                        ? c.foliageBalance.get() * (1 - c.vanillaLightMix.get())
                        : 0;
        if (world != mc.world) {
            world = mc.world;
            requested = next;
            settled = 0;
        }
        if (Math.abs(next - requested) > .0001) {
            requested = next;
            settled = 0;
        }
        if (Math.abs(balance - requested) < .0001) return;
        if (++settled < 8) return;
        balance = requested;
        if (mc.world != null) mc.worldRenderer.reload();
    }
}
