package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.EmberIcons;
import meteordevelopment.meteorclient.utils.render.EmberStrip;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;

import java.lang.management.ManagementFactory;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Ember V2: the wide status bar that sits along the top of the screen - client mark, server,
 * clock, latency, framerate, machine load and your name, each with its own glyph and split by
 * dim middle dots.
 */
public class EmberStatusBarHud extends HudElement {
    public static final HudElementInfo<EmberStatusBarHud> INFO = new HudElementInfo<>(Hud.GROUP, "ember-status-bar", "Ember V2 status bar: client, server, clock, ping, FPS, CPU, RAM and user.", EmberStatusBarHud::new);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the bar.")
        .defaultValue(1.35)
        .min(0.5)
        .max(3.0)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<String> label = sgGeneral.add(new meteordevelopment.meteorclient.settings.StringSetting.Builder()
        .name("label")
        .description("The client name shown at the far left.")
        .defaultValue("Ember")
        .build()
    );

    private final Setting<Boolean> showServer = sgGeneral.add(new BoolSetting.Builder()
        .name("server")
        .description("Show the server you are on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showClock = sgGeneral.add(new BoolSetting.Builder()
        .name("clock")
        .description("Show the real world time.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showPing = sgGeneral.add(new BoolSetting.Builder()
        .name("ping")
        .description("Show your latency.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showFps = sgGeneral.add(new BoolSetting.Builder()
        .name("fps")
        .description("Show your framerate.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showCpu = sgGeneral.add(new BoolSetting.Builder()
        .name("cpu")
        .description("Show system processor load.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showGpu = sgGeneral.add(new BoolSetting.Builder()
        .name("gpu")
        .description("Show graphics card load. Needs nvidia-smi, so NVIDIA cards only - the segment hides itself where it cannot be read.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showRam = sgGeneral.add(new BoolSetting.Builder()
        .name("ram")
        .description("Show how much of the game's memory is in use.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showUser = sgGeneral.add(new BoolSetting.Builder()
        .name("username")
        .description("Show your username.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showAvatar = sgGeneral.add(new BoolSetting.Builder()
        .name("avatar")
        .description("Show an initial badge after your username.")
        .defaultValue(true)
        .visible(showUser::get)
        .build()
    );

    private final Setting<Boolean> glow = sgGeneral.add(new BoolSetting.Builder()
        .name("glow")
        .description("Soft glow behind the bar.")
        .defaultValue(false)
        .build()
    );

    /** getCpuLoad samples over an interval, so it is polled on a timer rather than per frame. */
    private double cpuPercent = -1;
    private long lastCpuPoll;

    public EmberStatusBarHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        double s = scale.get();
        double textScale = 0.9 * s;

        Color accent = EmberPalette.accent();

        List<EmberStrip.Segment> segments = new ArrayList<>(8);
        segments.add(new EmberStrip.Segment(EmberIcons.Glyph.BRAND, label.get(), accent));

        if (showServer.get()) {
            String address = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "Local";
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.SERVER, address));
        }

        if (showClock.get()) {
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.CLOCK, LocalTime.now().format(CLOCK)));
        }

        if (showPing.get()) {
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.PING, ping() + " MS"));
        }

        if (showFps.get()) {
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.FPS, mc.getCurrentFps() + " FPS"));
        }

        if (showCpu.get()) {
            double cpu = cpu();
            if (cpu >= 0) segments.add(new EmberStrip.Segment(EmberIcons.Glyph.CPU, Math.round(cpu) + "% CPU"));
        }

        if (showGpu.get()) {
            int gpu = meteordevelopment.meteorclient.utils.misc.GpuMonitor.usage();
            if (gpu >= 0) segments.add(new EmberStrip.Segment(EmberIcons.Glyph.GPU, gpu + "% GPU"));
        }

        if (showRam.get()) {
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.RAM, Math.round(ram()) + "% RAM"));
        }

        if (showUser.get()) {
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.USER, mc.getSession().getUsername()));
        }

        double h = EmberStrip.height(renderer, s, textScale);
        double w = EmberStrip.width(renderer, segments, s, textScale);

        // The badge rides inside the pill, so its room is added before the background is drawn.
        boolean avatar = showUser.get() && showAvatar.get();
        double badge = h - 10 * s;
        if (avatar) w += 6 * s + badge;

        double radius = 9 * s;
        setSize(w, h);

        if (glow.get()) {
            renderer.softGlow(x, y, w, h, radius, 10 * s, new Color(accent.r, accent.g, accent.b, 150));
        }

        EmberStrip.draw(renderer, x, y, w, h, radius, segments, s, textScale);

        if (avatar) drawBadge(renderer, x + w - 11 * s - badge, y + (h - badge) / 2, badge, accent);
    }

    /** A round accent chip carrying the first letter of your name. */
    private void drawBadge(HudRenderer renderer, double bx, double by, double size, Color accent) {
        renderer.roundedQuad(bx, by, size, size, size / 2, accent);

        String name = mc.getSession().getUsername();
        if (name.isEmpty()) return;

        String initial = name.substring(0, 1).toUpperCase();
        double letterScale = size / renderer.textHeight(true, 1) * 0.62;
        double lw = renderer.textWidth(initial, true, letterScale);
        double lh = renderer.textHeight(true, letterScale);

        renderer.text(initial, bx + (size - lw) / 2, by + (size - lh) / 2, EmberStrip.background(), true, letterScale);
    }

    private int ping() {
        if (mc.getNetworkHandler() == null || mc.player == null) return 0;
        var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry != null ? entry.getLatency() : 0;
    }

    /** Percentage of the heap the game is currently holding. */
    private double ram() {
        Runtime runtime = Runtime.getRuntime();
        double max = runtime.maxMemory();
        if (max <= 0) return 0;
        return (runtime.totalMemory() - runtime.freeMemory()) / max * 100;
    }

    /**
     * System-wide processor load, or -1 where the JVM will not report it. There is no
     * equivalent for GPU load in Java, which is why the bar carries no GPU segment.
     */
    private double cpu() {
        long now = System.currentTimeMillis();
        if (now - lastCpuPoll < 500) return cpuPercent;
        lastCpuPoll = now;

        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean) {
                double load = bean.getCpuLoad();
                cpuPercent = load >= 0 ? load * 100 : -1;
            }
        } catch (Throwable ignored) {
            cpuPercent = -1;
        }

        return cpuPercent;
    }
}
