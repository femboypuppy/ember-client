package meteordevelopment.meteorclient.utils.render;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;

/** Samples weather once per tick; HUD drawing only reads the resulting state. */
public final class EmberRainStatus {
    private static final RainTracker TRACKER = new RainTracker();
    private static final ServerRainState SERVER = new ServerRainState();
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
        SERVER.reset();
        world = null;
    }

    private static void ensureWorld() {
        if (world != mc.world) {
            reset();
            world = mc.world;
        }
    }

    @EventHandler
    private static void onPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof GameStateChangeS2CPacket packet) || mc.world == null) return;
        var reason = packet.getReason();
        if (reason != GameStateChangeS2CPacket.RAIN_STARTED && reason != GameStateChangeS2CPacket.RAIN_STOPPED
            && reason != GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED) return;
        var receivedWorld = mc.world;
        mc.execute(() -> {
            if (mc.world != receivedWorld) return;
            ensureWorld();
            if (reason == GameStateChangeS2CPacket.RAIN_STARTED) SERVER.start();
            else if (reason == GameStateChangeS2CPacket.RAIN_STOPPED) SERVER.stop();
            else SERVER.level(packet.getValue());
        });
    }

    @EventHandler
    private static void onTick(TickEvent.Post event) {
        var c = Config.get();
        if (c == null || mc.world == null || mc.player == null) {
            reset();
            return;
        }
        ensureWorld();
        // Local particles are a separate visual layer, not a replacement for server weather.
        // Snow/leaves/petals must not hide a real rain event from the HUD.
        boolean serverWeather = mc.world.canHaveWeather();
        var server = SERVER.sample(mc.world.getLevelProperties().isRaining(), mc.world.getRainGradient(1));
        boolean requested = serverWeather && server.requested();
        float strength = serverWeather ? server.strength() : 0;
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
        var change = TRACKER.update(requested, strength, !localRain && server.easing());
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
                            "Rain is easing",
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
