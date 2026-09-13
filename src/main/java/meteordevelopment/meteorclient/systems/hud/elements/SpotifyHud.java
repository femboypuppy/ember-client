package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.MediaInfo;
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import org.lwjgl.glfw.GLFW;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The music widget, shaped like a dynamic island: normally just the album art and the track
 * title on one line, growing to show the artist and a progress bar when you point at it or
 * when the track changes.
 */
public class SpotifyHud extends HudElement {
    public static final HudElementInfo<SpotifyHud> INFO = new HudElementInfo<>(Hud.GROUP, "spotify", "Shows the song currently playing on your PC.", SpotifyHud::new);

    /** Album art must be tinted pure white or it renders off-colour. */
    private static final Color ART_TINT = new Color(255, 255, 255, 255);

    /** How long the island stays open by itself after the track changes. */
    private static final long AUTO_OPEN_MS = 3500;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgControls = settings.createGroup("Controls");

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the widget.")
        .defaultValue(1.0)
        .min(0.5)
        .max(3.0)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<Double> collapsedWidth = sgGeneral.add(new DoubleSetting.Builder()
        .name("collapsed-width")
        .description("How much room the title gets while the island is closed.")
        .defaultValue(96.0)
        .min(40)
        .max(240)
        .sliderRange(40, 240)
        .build()
    );

    private final Setting<Double> width = sgGeneral.add(new DoubleSetting.Builder()
        .name("expanded-width")
        .description("How much room the song text gets once it opens.")
        .defaultValue(150.0)
        .min(80)
        .max(320)
        .sliderRange(80, 320)
        .build()
    );

    private final Setting<Boolean> expandOnHover = sgGeneral.add(new BoolSetting.Builder()
        .name("expand-on-hover")
        .description("Open the island when you point at it. Needs a cursor, so it applies while a menu is open.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> expandOnChange = sgGeneral.add(new BoolSetting.Builder()
        .name("expand-on-track-change")
        .description("Open the island briefly whenever the track changes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showProgress = sgGeneral.add(new BoolSetting.Builder()
        .name("show-progress")
        .description("Show the progress bar and timestamps while open.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> glow = sgGeneral.add(new BoolSetting.Builder()
        .name("glow")
        .description("Soft glow behind the widget.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> hideWhenIdle = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-when-idle")
        .description("Hide the widget when nothing is playing.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Keybind> playPauseKey = sgControls.add(new KeybindSetting.Builder()
        .name("play-pause-key")
        .description("Play or pause whatever is playing.")
        .defaultValue(Keybind.none())
        .action(() -> control("toggle"))
        .build()
    );

    private final Setting<Keybind> nextKey = sgControls.add(new KeybindSetting.Builder()
        .name("next-key")
        .description("Skip to the next track.")
        .defaultValue(Keybind.none())
        .action(() -> control("next"))
        .build()
    );

    private final Setting<Keybind> previousKey = sgControls.add(new KeybindSetting.Builder()
        .name("previous-key")
        .description("Go back to the previous track.")
        .defaultValue(Keybind.none())
        .action(() -> control("prev"))
        .build()
    );

    private final EmberAnim.Clock clock = new EmberAnim.Clock();
    private float expand;
    private String lastTitle = "";
    private long autoOpenUntil;

    public SpotifyHud() {
        super(INFO);
    }

    /** Keybind settings on HUD elements fire even when the widget is off, and while typing in chat. */
    private void control(String command) {
        if (!isActive() || mc.currentScreen != null) return;
        MediaInfo.sendCommand(command);
    }

    @Override
    public void render(HudRenderer renderer) {
        MediaInfo.start();
        MediaInfo.uploadPendingArt();

        boolean has = MediaInfo.isAvailable();

        if (!has && hideWhenIdle.get() && !isInEditor()) {
            setSize(0, 0);
            return;
        }

        float dt = clock.tick();

        Color accent = EmberPalette.accent();
        Color bg = new Color(7, 7, 9, 242);
        Color artBg = new Color(26, 26, 32, 255);
        Color white = new Color(255, 255, 255, 255);
        Color gray = new Color(150, 150, 162, 255);
        Color track = new Color(44, 44, 53, 255);

        double s = scale.get();
        double titleScale = 0.88 * s;
        double subScale = 0.78 * s;

        double titleH = renderer.textHeight(true, titleScale);
        double subH = renderer.textHeight(true, subScale);

        String title = has ? MediaInfo.getTitle() : "Nothing playing";
        String artist = has ? MediaInfo.getArtist() : "";
        boolean withArtist = !artist.isEmpty();
        boolean withProgress = showProgress.get();

        // A new track pops the island open on its own, the way a real one announces itself.
        if (has && !title.equals(lastTitle)) {
            lastTitle = title;
            if (expandOnChange.get()) autoOpenUntil = System.currentTimeMillis() + AUTO_OPEN_MS;
        }

        boolean open = (expandOnHover.get() && hovered())
            || System.currentTimeMillis() < autoOpenUntil
            || isInEditor();

        expand = EmberAnim.approach(expand, open ? 1f : 0f, dt, 0.09);
        double e = ease(expand);

        double pad = 7 * s;
        double gap = 8 * s;
        double rowGap = 3 * s;

        // Closed, the art is one line tall. Open, it grows to match the stacked text.
        double artClosed = titleH * 1.5;
        double artOpen = titleH + rowGap + (withArtist ? subH + rowGap : 0) + (withProgress ? subH : 0);
        double art = lerp(artClosed, Math.max(artClosed, artOpen), e);

        double closedW = Math.min(renderer.textWidth(title, true, titleScale), collapsedWidth.get() * s);
        double contentW = lerp(closedW, width.get() * s, e);

        double w = pad + art + gap + contentW + pad;
        double h = pad * 2 + art;
        double radius = h / 2;

        setSize(w, h);

        if (glow.get()) {
            renderer.softGlow(x, y, w, h, radius, 10 * s, new Color(accent.r, accent.g, accent.b, 150));
        }

        renderer.dropShadow(x, y, w, h, radius, 13 * s);
        renderer.roundedQuad(x, y, w, h, radius, bg);

        // Album art
        double ax = x + pad, ay = y + pad;
        renderer.roundedQuad(ax, ay, art, art, art * 0.22, artBg);

        if (MediaInfo.hasArt() && has) {
            final double fax = ax, fay = ay, fart = art;
            renderer.post(() -> renderer.texture(MediaInfo.ART_ID, fax, fay, fart, fart, ART_TINT));
        } else {
            double mx = ax + art / 2, my = ay + art / 2;
            double n = art / 30;
            renderer.roundedQuad(mx - 5 * n, my + 1 * n, 5 * n, 4 * n, 2 * n, gray);
            renderer.quad(mx - 1 * n, my - 6 * n, 1.5 * n, 9 * n, gray);
            renderer.quad(mx - 1 * n, my - 6 * n, 6 * n, 1.5 * n, gray);
        }

        double cx = ax + art + gap;

        // Closed, the title sits centred against the art; open, it rises to the top line.
        double titleY = lerp(y + (h - titleH) / 2, y + pad, e);
        renderer.text(truncate(renderer, title, contentW, titleScale), cx, titleY,
            has ? white : gray, true, titleScale);

        // Everything below only exists while open, so it fades with the animation.
        int fade = (int) (255 * e);
        if (fade < 6) return;

        double ty = y + pad + titleH + rowGap;

        if (withArtist) {
            renderer.text(truncate(renderer, artist, contentW, subScale), cx, ty,
                new Color(gray.r, gray.g, gray.b, fade), false, subScale);
            ty += subH + rowGap;
        }

        if (withProgress) {
            int pos = MediaInfo.getPosition();
            int dur = MediaInfo.getDuration();

            String elapsed = MediaInfo.formatTime(pos);
            String total = MediaInfo.formatTime(dur);

            double elapsedW = renderer.textWidth(elapsed, true, subScale);
            double totalW = renderer.textWidth(total, true, subScale);

            Color dimFade = new Color(gray.r, gray.g, gray.b, fade);
            renderer.text(elapsed, cx, ty, dimFade, false, subScale);
            renderer.text(total, cx + contentW - totalW, ty, dimFade, false, subScale);

            double barX = cx + elapsedW + 5 * s;
            double barW = contentW - elapsedW - totalW - 10 * s;
            double barH = 3 * s;
            double barY = ty + subH / 2 - barH / 2;

            if (barW > 4 * s) {
                renderer.roundedQuad(barX, barY, barW, barH, barH / 2,
                    new Color(track.r, track.g, track.b, fade));

                double pct = dur > 0 ? Math.min(1.0, (double) pos / dur) : 0;
                double fill = barW * pct;
                if (fill > barH) {
                    renderer.roundedQuad(barX, barY, fill, barH, barH / 2,
                        new Color(accent.r, accent.g, accent.b, fade));
                }
            }
        }
    }

    /**
     * Whether the cursor is over the widget. The mouse is captured during play, so this only
     * reports true while a screen is open. GLFW is asked directly and the reading converted
     * from window pixels to the framebuffer space the HUD is laid out in, which is what makes
     * it correct on a scaled or high-DPI display.
     */
    private boolean hovered() {
        if (mc.currentScreen == null) return false;

        long handle = mc.getWindow().getHandle();
        double[] px = new double[1], py = new double[1];
        GLFW.glfwGetCursorPos(handle, px, py);

        int[] ww = new int[1], wh = new int[1];
        GLFW.glfwGetWindowSize(handle, ww, wh);
        if (ww[0] <= 0 || wh[0] <= 0) return false;

        double hx = px[0] * Utils.getWindowWidth() / ww[0];
        double hy = py[0] * Utils.getWindowHeight() / wh[0];

        return hx >= x && hx < x + getWidth() && hy >= y && hy < y + getHeight();
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Eased so the island settles rather than snapping to its open size. */
    private static double ease(double t) {
        return t * t * (3 - 2 * t);
    }

    private String truncate(HudRenderer renderer, String s, double maxW, double textScale) {
        if (s.isEmpty()) return s;
        if (renderer.textWidth(s, true, textScale) <= maxW) return s;

        StringBuilder sb = new StringBuilder(s);
        while (sb.length() > 1 && renderer.textWidth(sb + "...", true, textScale) > maxW) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb + "...";
    }
}
