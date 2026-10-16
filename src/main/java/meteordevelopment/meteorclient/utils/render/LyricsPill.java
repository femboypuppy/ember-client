package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.misc.LyricsTimeline;
import meteordevelopment.meteorclient.utils.misc.MediaInfo;
import meteordevelopment.meteorclient.utils.misc.SpotifyPlayback;
import meteordevelopment.meteorclient.utils.misc.SpicyLyricsService;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** A bounded, word-synced lyric window beside the status bar, including source credits. */
public final class LyricsPill {
    public record Size(double width, double height) {}
    private record CreditHit(double x, double y, double w, double h, String url) {}
    private static final EmberAnim.Clock CLOCK = new EmberAnim.Clock();
    private static String lastLine = "";
    private static float lineFade = 1;
    private static double width;
    private static boolean mouseDown;

    private LyricsPill() {}

    public static Size render(HudRenderer r, double x, double y, double baseHeight, double s, double opacity, double availableWidth) {
        if (!Config.get().lyricsEnabled.get() || availableWidth < 140 * s) return new Size(0, 0);
        double dt = CLOCK.tick();
        double motion = EmberAppearance.liquid() ? EmberAppearance.motion() : 1;
        SpicyLyricsService.Result result = SpicyLyricsService.update();
        SpotifyPlayback.Playback playback = SpotifyPlayback.current();
        LyricsTimeline timeline = result.timeline();
        boolean matching = playback != null && playback.trackId().equals(result.trackId());
        double seconds = playback == null ? 0 : (playback.positionMs() + Config.get().lyricsOffset.get()) / 1000.0;
        LyricsTimeline.Line line = matching && timeline != null ? timeline.at(seconds) : null;
        String title = playback != null ? playback.title() : MediaInfo.isAvailable() ? MediaInfo.getTitle() : "Spotify lyrics";
        String key = result.trackId() + ":" + (line == null ? title : line.start());
        if (!key.equals(lastLine)) { lastLine = key; lineFade = 0; }
        lineFade = EmberAnim.approach(lineFade, 1, dt, motion == 0 ? 0 : 0.055 / motion);

        double ts = 0.76 * s, cs = 0.43 * s, pad = 10 * s;
        double maxW = Math.min(470 * s, availableWidth), inner = maxW - 2 * pad;
        int active = line == null ? -1 : line.activeWord(seconds);
        List<LyricsTimeline.Word> words = line == null ? List.of() : line.words();
        int focus = active;
        if (focus < 0) for (int i = 0; i < words.size(); i++) { if (words.get(i).start() <= seconds) focus = i; }
        int first = Math.max(0, focus - 2), last = first;
        double gap = r.textWidth(" ", false, ts);
        double runW = 0;
        if (!words.isEmpty()) {
            while (first < Math.max(0, focus)) {
                double before = 0;
                for (int i = first; i <= focus; i++) before += r.textWidth(words.get(i).text(), false, ts) + gap;
                if (before <= inner) break;
                first++;
            }
            last = first;
            while (last < words.size()) {
                double next = r.textWidth(words.get(last).text(), false, ts) + (last > first ? gap : 0);
                if (runW + next > inner && last > first) break;
                runW += Math.min(next, inner); last++;
            }
        }
        String main = elide(r, line == null ? title : line.text(), inner, ts);
        if (words.isEmpty()) runW = r.textWidth(main, false, ts);

        // Credits remain visible whenever lyric text is visible, with links in menu mode.
        List<LyricsTimeline.Credit> credits = line != null ? timeline.credits() : List.of();
        double creditH = r.textHeight(false, cs) + 2 * s;
        List<CreditHit> creditLayout = new ArrayList<>();
        List<String> creditText = new ArrayList<>();
        double creditX = 0, creditW = 0;
        int row = 0;
        for (LyricsTimeline.Credit credit : credits) {
            String text = (creditX > 0 ? "  /  " : "") + credit.name();
            double cw = r.textWidth(text, false, cs);
            if (creditX > 0 && creditX + cw > inner) { row++; creditX = 0; text = credit.name(); cw = r.textWidth(text, false, cs); }
            String shown = elide(r, text, inner, cs);
            cw = r.textWidth(shown, false, cs);
            creditText.add(shown);
            creditLayout.add(new CreditHit(creditX, row * creditH, cw, creditH, credit.url()));
            creditX += cw; creditW = Math.max(creditW, creditX);
        }
        String subtitle = line == null ? (timeline != null && result.status().isEmpty() ? "Instrumental / waiting for vocals" : result.status()) : "";
        if (playback != null && !playback.clock().playing() && line == null) subtitle = "Paused";
        subtitle = elide(r, subtitle, inner, cs);
        if (credits.isEmpty()) creditW = r.textWidth(subtitle, false, cs);
        int creditRows = credits.isEmpty() ? 1 : row + 1;
        double target = Math.min(maxW, Math.max(150 * s, Math.max(runW, creditW) + pad * 2));
        width = width == 0 ? target : EmberAnim.approach(width, target, dt, motion == 0 ? 0 : 0.09 / motion);
        double w = Math.min(maxW, Math.max(target, width));
        double h = Math.max(baseHeight, r.textHeight(false, ts) + 10 * s) + creditRows * creditH + 2 * s;
        double radius = Math.min(8 * s, h * 0.25);
        r.dropShadow(x, y, w, h, radius, 12 * s, (float) opacity);
        EmberStrip.panel(r, x, y, w, h, radius, opacity);
        double ty = y + 5 * s + (1 - lineFade) * 3 * s;
        double cx = x + pad;
        Color accent = EmberPalette.accent();
        if (words.isEmpty()) {
            r.text(main, cx, ty, new Color(234, 237, 245, (int) (255 * opacity * lineFade)), false, ts);
        } else {
            for (int i = first; i < last; i++) {
                LyricsTimeline.Word word = words.get(i);
                String shown = elide(r, word.text(), inner, ts);
                double ww = r.textWidth(shown, false, ts);
                boolean current = i == active;
                int alpha = (int) (255 * opacity * lineFade);
                Color tint = current ? new Color(accent.r, accent.g, accent.b, alpha)
                    : new Color(seconds >= word.end() ? 220 : 133, seconds >= word.end() ? 224 : 139, seconds >= word.end() ? 235 : 154, alpha);
                double progress = Math.clamp((seconds - word.start()) / Math.max(0.001, word.end() - word.start()), 0, 1);
                double lift = current && motion > 0 ? Math.sin(progress * Math.PI) * 1.2 * s : 0;
                r.text(shown, cx, ty - lift, tint, false, ts);
                if (current && ww > 0) r.roundedQuad(cx, ty + r.textHeight(false, ts) + s, Math.max(s, ww * progress), Math.max(0.8, s), s * 0.5,
                    new Color(accent.r, accent.g, accent.b, (int) (170 * opacity * lineFade)));
                cx += ww + gap;
            }
        }
        double creditY = y + h - creditRows * creditH - 4 * s;
        List<CreditHit> hits = new ArrayList<>();
        Color quiet = new Color(151, 160, 179, (int) (220 * opacity));
        if (credits.isEmpty()) r.text(subtitle, x + pad, creditY, quiet, false, cs);
        else for (int i = 0; i < creditLayout.size(); i++) {
            CreditHit credit = creditLayout.get(i);
            r.text(creditText.get(i), x + pad + credit.x, creditY + credit.y, quiet, false, cs);
            hits.add(new CreditHit(x + pad + credit.x, creditY + credit.y, credit.w, credit.h, credit.url));
        }
        handleCredits(hits);
        return new Size(w, h);
    }

    private static String elide(HudRenderer r, String text, double maxWidth, double scale) {
        if (r.textWidth(text, false, scale) <= maxWidth) return text;
        while (!text.isEmpty() && r.textWidth(text + "\u2026", false, scale) > maxWidth) {
            text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
        }
        return text.isEmpty() ? "" : text + "\u2026";
    }

    private static void handleCredits(List<CreditHit> hits) {
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (mc.currentScreen != null && down && !mouseDown) {
            double scale = mc.getWindow().getScaleFactor();
            double mx = mc.mouse.getScaledX(mc.getWindow()) * scale, my = mc.mouse.getScaledY(mc.getWindow()) * scale;
            for (CreditHit hit : hits) if (mx >= hit.x && mx < hit.x + hit.w && my >= hit.y && my < hit.y + hit.h) {
                Util.getOperatingSystem().open(hit.url); break;
            }
        }
        mouseDown = down;
    }
}
