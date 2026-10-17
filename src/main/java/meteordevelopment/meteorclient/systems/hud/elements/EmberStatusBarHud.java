package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.NameProtect;
import meteordevelopment.meteorclient.utils.render.EmberIcons;
import meteordevelopment.meteorclient.utils.render.EmberStrip;
import meteordevelopment.meteorclient.utils.render.EmberAppearance;
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.util.Identifier;

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

    /** Skins must be tinted pure white or they render off-colour. */
    private static final Color SKIN_TINT = new Color(255, 255, 255, 255);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Boolean> compactGlass = sgGeneral.add(new BoolSetting.Builder()
        .name("compact-glass-layout")
        .description("Liquid Glass reference layout: separate logo, username, and server/FPS/time pills. Turn off for all performance statistics.")
        .defaultValue(true)
        .visible(EmberAppearance::liquid)
        .build());
    private final EmberAnim.Clock glassClock = new EmberAnim.Clock();
    private final Setting<Boolean> mediaPill = sgGeneral.add(new BoolSetting.Builder()
        .name("glass-now-playing")
        .description("Show the current song beside the glass status pills when media is available.")
        .defaultValue(true)
        .visible(EmberAppearance::liquid)
        .build());
    private float glassIntro;
    private double glassWidth;

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

    private final meteordevelopment.meteorclient.utils.render.EmberCounter counter =
        new meteordevelopment.meteorclient.utils.render.EmberCounter();

    public EmberStatusBarHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        if (EmberAppearance.liquid() && compactGlass.get()) {
            renderLiquid(renderer);
            return;
        }
        double s = scale.get();
        double textScale = 0.9 * s;

        counter.tick();

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

        // Every figure eases toward its reading, so the bar counts rather than flickers.
        if (showPing.get()) {
            long shown = Math.round(counter.get("ping", ping(), 0.3));
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.PING, shown + " MS"));
        }

        if (showFps.get()) {
            long shown = Math.round(counter.get("fps", mc.getCurrentFps(), 0.25));
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.FPS, shown + " FPS"));
        }

        if (showCpu.get()) {
            double cpu = cpu();
            if (cpu >= 0) {
                long shown = Math.round(counter.get("cpu", cpu, 0.35));
                segments.add(new EmberStrip.Segment(EmberIcons.Glyph.CPU, shown + "% CPU"));
            }
        }

        if (showGpu.get()) {
            int gpu = meteordevelopment.meteorclient.utils.misc.GpuMonitor.usage();
            if (gpu >= 0) {
                long shown = Math.round(counter.get("gpu", gpu, 0.35));
                segments.add(new EmberStrip.Segment(EmberIcons.Glyph.GPU, shown + "% GPU"));
            }
        }

        if (showRam.get()) {
            long shown = Math.round(counter.get("ram", ram(), 0.35));
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.RAM, shown + "% RAM"));
        }

        if (showUser.get()) {
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.USER, displayName()));
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
        if (meteordevelopment.meteorclient.systems.config.Config.get().lyricsEnabled.get()) {
            var lyrics = meteordevelopment.meteorclient.utils.render.LyricsPill.render(renderer, x + w + 6 * s, y, h, s, 1,
                mc.getWindow().getFramebufferWidth() - x - w - 16 * s);
            if (lyrics.width() > 0) setSize(w + 6 * s + lyrics.width(), Math.max(h, lyrics.height()));
        }
    }

    /** Your actual skin face, falling back to a lettered chip before the skin has loaded. */
    private void renderLiquid(HudRenderer renderer) {
        double dt = glassClock.tick();
        double motion = EmberAppearance.motion();
        glassIntro = EmberAnim.approach(glassIntro, 1, dt, motion == 0 ? 0 : 0.09 / motion);
        double s = scale.get(), ts = 0.74 * s;
        double h = EmberStrip.height(renderer, s, ts), gap = 5 * s, radius = 7 * s;
        double by = y - (1 - glassIntro) * 8 * s;
        Color accent = EmberPalette.accent();
        Color white = new Color(244, 245, 250, (int) (255 * glassIntro));

        counter.tick();
        double cx = x;
        EmberStrip.panel(renderer, cx, by, h, h, radius, glassIntro);
        EmberIcons.draw(renderer, EmberIcons.Glyph.BRAND, cx + h * 0.25, by + h * 0.25, h * 0.5, white, EmberStrip.background());
        cx += h + gap;

        if (showUser.get()) {
            String user = displayName();
            double badge = h - 8 * s;
            double uw = renderer.textWidth(user, true, ts) + 16 * s + (showAvatar.get() ? badge + 5 * s : 0);
            EmberStrip.panel(renderer, cx, by, uw, h, radius, glassIntro);
            double tx = cx + 8 * s;
            if (showAvatar.get()) {
                drawBadge(renderer, tx, by + 4 * s, badge, accent);
                tx += badge + 5 * s;
            }
            renderer.text(user, tx, by + (h - renderer.textHeight(true, ts)) / 2, white, true, ts);
            cx += uw + gap;
        }

        List<EmberStrip.Segment> metrics = new ArrayList<>();
        if (showServer.get()) metrics.add(new EmberStrip.Segment(EmberIcons.Glyph.SERVER,
            mc.getCurrentServerEntry() == null ? "Singleplayer" : mc.getCurrentServerEntry().address));
        if (showFps.get()) metrics.add(new EmberStrip.Segment(EmberIcons.Glyph.FPS, Math.round(counter.get("fps", mc.getCurrentFps(), 0.25)) + " fps"));
        if (showClock.get()) metrics.add(new EmberStrip.Segment(EmberIcons.Glyph.CLOCK, LocalTime.now().format(CLOCK)));
        if (!metrics.isEmpty()) {
            double mw = EmberStrip.width(renderer, metrics, s, ts);
            EmberStrip.draw(renderer, cx, by, mw, h, radius, metrics, s, ts);
            cx += mw;
        } else cx -= gap;

        double lyricHeight = h;
        if (meteordevelopment.meteorclient.systems.config.Config.get().lyricsEnabled.get()) {
            var lyrics = meteordevelopment.meteorclient.utils.render.LyricsPill.render(renderer, cx + gap, by, h, s, glassIntro,
                mc.getWindow().getFramebufferWidth() - cx - gap - 10 * s);
            if (lyrics.width() > 0) { cx += gap + lyrics.width(); lyricHeight = Math.max(h, lyrics.height()); }
        } else if (mediaPill.get()) {
            meteordevelopment.meteorclient.utils.misc.MediaInfo.start();
            if (meteordevelopment.meteorclient.utils.misc.MediaInfo.isAvailable()) {
                String song = meteordevelopment.meteorclient.utils.misc.MediaInfo.getTitle();
                double maxText = Math.min(220 * s, Math.max(0, mc.getWindow().getFramebufferWidth() - cx - 70 * s));
                if (maxText > 60 * s) {
                    while (song.length() > 1 && renderer.textWidth(song, false, ts) > maxText) song = song.substring(0, song.length() - 1);
                    double mw = renderer.textWidth(song, false, ts) + 41 * s;
                    cx += gap;
                    EmberStrip.panel(renderer, cx, by, mw, h, radius, glassIntro);
                    renderer.text(song, cx + 9 * s, by + (h - renderer.textHeight(false, ts)) / 2, white, false, ts);
                    boolean playing = meteordevelopment.meteorclient.utils.misc.MediaInfo.isPlaying();
                    double seconds = System.nanoTime() / 1_000_000_000.0 * motion;
                    for (int i = 0; i < 4; i++) {
                        double bh = (playing ? 3 + (0.5 + 0.5 * Math.sin(seconds * 4 + i * 1.7)) * 8 : 3) * s;
                        renderer.roundedQuad(cx + mw - 23 * s + i * 4 * s, by + (h - bh) / 2, 2 * s, bh, s,
                            new Color(accent.r, accent.g, accent.b, (int) (220 * glassIntro)));
                    }
                    cx += mw;
                }
            }
        }

        double target = cx - x;
        glassWidth = glassWidth == 0 ? target : EmberAnim.approach(glassWidth, target, dt, motion == 0 ? 0 : 0.1 / motion);
        setSize(Math.max(target, glassWidth), lyricHeight);
    }

    private void drawBadge(HudRenderer renderer, double bx, double by, double size, Color accent) {
        Identifier skin = skin();

        if (skin != null) {
            double r = size * 0.3;
            renderer.roundedQuad(bx, by, size, size, r, new Color(0, 0, 0, 120));

            // The face is an 8x8 patch at (8,8) of a 64x64 skin, the hat layer at (40,8).
            final double fx = bx, fy = by, fs = size, fr = r;
            renderer.post(() -> {
                renderer.roundedTextureRegion(skin, fx, fy, fs, fr, 0.125, 0.125, 0.25, 0.25, SKIN_TINT);
                renderer.roundedTextureRegion(skin, fx, fy, fs, fr, 0.625, 0.125, 0.75, 0.25, SKIN_TINT);
            });
            return;
        }

        renderer.roundedQuad(bx, by, size, size, size / 2, accent);

        String name = displayName();
        if (name.isEmpty()) return;

        String initial = name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
        double letterScale = size / renderer.textHeight(true, 1) * 0.62;
        double lw = renderer.textWidth(initial, true, letterScale);
        double lh = renderer.textHeight(true, letterScale);

        renderer.text(initial, bx + (size - lw) / 2, by + (size - lh) / 2, EmberStrip.background(), true, letterScale);
    }

    private static String displayName() {
        String name = mc.getSession().getUsername();
        var modules = Modules.get();
        NameProtect protect = modules == null ? null : modules.get(NameProtect.class);
        return protect == null ? name : protect.getName(name);
    }

    /** The local player's skin, or null while offline or before it has downloaded. */
    private Identifier skin() {
        if (mc.player != null) return mc.player.getSkin().body().texturePath();

        if (mc.getNetworkHandler() != null) {
            var entry = mc.getNetworkHandler().getPlayerListEntry(mc.getSession().getUuidOrNull());
            if (entry != null) return entry.getSkinTextures().body().texturePath();
        }

        return null;
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
