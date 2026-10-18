package meteordevelopment.meteorclient.utils.render;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.orbit.EventHandler;

/** Samples weather once per tick; HUD drawing only reads the resulting state. */
public final class EmberRainStatus {
    private static final RainTracker TRACKER = new RainTracker();
    private static Object world;

    private EmberRainStatus() {}

    @PostInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(EmberRainStatus.class);
    }

    public static boolean indicatorVisible() {
        var c = Config.get();
        return c != null
                && c.rainStatusIcon.get()
                && mc.world != null
                && mc.world == world
                && TRACKER.raining();
    }

    public static boolean ending() {
        return TRACKER.ending();
    }

    @EventHandler
    private static void onLeft(GameLeftEvent event) {
        reset();
    }

    private static void reset() {
        TRACKER.reset();
        world = null;
    }

    @EventHandler
    private static void onTick(TickEvent.Post event) {
        var c = Config.get();
        if (c == null || mc.world == null || mc.player == null) {
            reset();
            return;
        }
        if (world != mc.world) {
            TRACKER.reset();
            world = mc.world;
        }
        // Local particles are a separate visual layer, not a replacement for server weather.
        // Snow/leaves/petals must not hide a real rain event from the HUD.
        boolean serverWeather = mc.world.canHaveWeather();
        boolean requested = serverWeather && mc.world.getLevelProperties().isRaining();
        float strength = serverWeather ? mc.world.getRainGradient(1) : 0;
        // Match custom weather's skylight eligibility, including sky dimensions without native
        // weather. Roofs and temporary blindness do not change the chosen local weather.
        boolean localRain = c.customWeather.get()
                && c.ambience.get()
                && c.worldAtmosphere.get()
                && mc.world.getDimension().hasSkyLight()
                && c.weatherStyle.get() == WeatherSimulation.Kind.Rain
                && c.weatherDensity.get() > 0;
        if (localRain) {
            requested = true;
            strength = 1;
        }
        var change = TRACKER.update(requested, strength);
        if (!c.rainNotifications.get()) return;
        switch (change) {
            case Started ->
                    EmberNotificationsHud.push(
                            0x45575201,
                            "Rain has started",
                            "Rain is falling in this world.",
                            EmberNotificationsHud.Kind.INFO);
            case Ending -> {
                if (c.rainEndingNotification.get())
                    EmberNotificationsHud.push(
                            0x45575202,
                            "Rain is about to stop",
                            "The rain is easing off.",
                            EmberNotificationsHud.Kind.INFO);
            }
            case Stopped ->
                    EmberNotificationsHud.push(
                            0x45575203,
                            "Rain has stopped",
                            "The weather is clearing.",
                            EmberNotificationsHud.Kind.INFO);
            case None -> {}
        }
    }
}
