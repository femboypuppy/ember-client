package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.misc.*;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Fixed-size lyric viewport with retained phrases, continuous scrolling and independent backing vocals. */
public final class LyricsPill {
    public record Size(double width, double height) {}
    private record CreditHit(double x, double y, double w, double h, String url) {}
    private static final EmberAnim.Clock CLOCK = new EmberAnim.Clock();
    private static final Lane LEAD = new Lane();
    private static final Lane[] BACKING = {new Lane(), new Lane()};
    private static String track = "";
    private static LyricsTimeline lastTimeline;
    private static int backingRows;
    private static boolean mouseDown;
    private static double lastSeconds = Double.NaN;

    private LyricsPill() {}

    public static Size render(HudRenderer r, double x, double y, double baseHeight, double s, double opacity, double availableWidth) {
        if (!Config.get().lyricsEnabled.get() || availableWidth < 140 * s) return new Size(0, 0);
        double dt = CLOCK.tick();
        SpicyLyricsService.Result result = SpicyLyricsService.update();
        SpotifyPlayback.Playback playback = SpotifyPlayback.current();
        LyricsTimeline timeline = playback != null && playback.trackId().equals(result.trackId()) ? result.timeline() : null;
        String id = playback == null ? "" : playback.trackId();
        double seconds = playback == null ? 0 : (playback.positionMs() + Config.get().lyricsOffset.get()) / 1000.0;
        boolean changedTrack = !id.equals(track);
        boolean seek = changedTrack || !Double.isFinite(lastSeconds) || seconds < lastSeconds - 0.15 || seconds > lastSeconds + 1;
        if (changedTrack) { LEAD.reset(); for (Lane lane : BACKING) lane.reset(); track = id; }
        if (timeline != lastTimeline) { lastTimeline = timeline; backingRows = backgroundRows(timeline); }
        lastSeconds = seconds;

        double ts = 0.76 * s, bs = 0.58 * s, cs = 0.43 * s, pad = 12 * s;
        double w = Math.min(420 * s, availableWidth), inner = w - 2 * pad;
        LyricsTimeline.Line line = timeline == null ? null : timeline.displayAt(seconds);
        boolean showBacking = Config.get().lyricsBackground.get() && backingRows > 0;
        double leadH = Math.max(baseHeight, r.textHeight(false, ts) + 12 * s);
        double backingH = r.textHeight(false, bs) + 5 * s;

        List<LyricsTimeline.Credit> credits = timeline != null && (!timeline.lines().isEmpty() || !timeline.background().isEmpty()) ? timeline.credits() : List.of();
        List<CreditHit> creditLayout = new ArrayList<>();
        List<String> creditText = new ArrayList<>();
        double creditH = r.textHeight(false, cs) + 2 * s, creditX = 0;
        int row = 0;
        for (LyricsTimeline.Credit credit : credits) {
            String text = (creditX > 0 ? "  /  " : "") + credit.name();
            double cw = r.textWidth(text, false, cs);
            if (creditX > 0 && creditX + cw > inner) { row++; creditX = 0; text = credit.name(); }
            String shown = elide(r, text, inner, cs);
            cw = r.textWidth(shown, false, cs);
            creditText.add(shown);
            creditLayout.add(new CreditHit(creditX, row * creditH, cw, creditH, credit.url()));
            creditX += cw;
        }
        // Reserve attribution rows before the first vocal so the panel never jumps on a breath.
        int creditRows = timeline == null ? 1 : creditRowCount(r, timeline.credits(), inner, cs);
        double h = leadH + (showBacking ? backingRows * backingH : 0) + creditRows * creditH + 5 * s;
        double radius = Math.min(9 * s, h * 0.25);
        r.dropShadow(x, y, w, h, radius, 12 * s, (float) opacity);
        EmberStrip.panel(r, x, y, w, h, radius, opacity);
        double left = x + pad, right = x + w - pad;
        boolean animate = !EmberAppearance.liquid() || EmberAppearance.motion() > 0;
        LEAD.draw(r, line, left, y + 5 * s, right, ts, seconds, dt, opacity, seek, animate, false);
        if (line == null) {
            String title = playback != null ? playback.title() : MediaInfo.isAvailable() ? MediaInfo.getTitle() : "Spotify lyrics";
            r.text(elide(r, title, inner, ts), left, y + 5 * s, new Color(234, 237, 245, (int) (255 * opacity)), false, ts);
        }
        if (showBacking) {
            List<LyricsTimeline.Line> backing = timeline.backgroundAt(seconds);
            // Keep each overlapping vocal in its own lane until it has finished.
            List<LyricsTimeline.Line> pending = new ArrayList<>(backing);
            for (Lane lane : BACKING) pending.remove(lane.line);
            for (int i = 0; i < backingRows; i++) {
                Lane lane = BACKING[i];
                LyricsTimeline.Line next = backing.contains(lane.line) ? lane.line : pending.isEmpty() ? null : pending.removeFirst();
                lane.draw(r, next, left, y + leadH + i * backingH, right, bs, seconds, dt, opacity * 0.85, seek, animate, true);
            }
        }

        double creditY = y + h - creditRows * creditH - 4 * s;
        List<CreditHit> hits = new ArrayList<>();
        Color quiet = new Color(151, 160, 179, (int) (220 * opacity));
        if (credits.isEmpty()) {
            String status = timeline != null && !timeline.lines().isEmpty() ? "Waiting for vocals" : result.status();
            if (playback != null && !playback.clock().playing()) status = "Paused";
            r.text(elide(r, status, inner, cs), left, creditY, quiet, false, cs);
        } else for (int i = 0; i < creditLayout.size(); i++) {
            CreditHit credit = creditLayout.get(i);
            r.text(creditText.get(i), left + credit.x, creditY + credit.y, quiet, false, cs);
            hits.add(new CreditHit(left + credit.x, creditY + credit.y, credit.w, credit.h, credit.url));
        }
        handleCredits(hits);
        return new Size(w, h);
    }

    private static int creditRowCount(HudRenderer r, List<LyricsTimeline.Credit> credits, double width, double scale) {
        int rows = 1;
        double x = 0;
        for (var credit : credits) {
            double cw = r.textWidth((x > 0 ? "  /  " : "") + credit.name(), false, scale);
            if (x > 0 && x + cw > width) { rows++; x = 0; cw = r.textWidth(credit.name(), false, scale); }
            x += Math.min(width, cw);
        }
        return rows;
    }

    private static int backgroundRows(LyricsTimeline timeline) {
        if (timeline == null || timeline.background().isEmpty()) return 0;
        double lastEnd = -1;
        for (var line : timeline.background()) {
            if (line.start() < lastEnd) return 2;
            lastEnd = Math.max(lastEnd, line.end() + 0.45);
        }
        return 1;
    }

    /** A phrase keeps its measured geometry; only translation and color change each frame. */
    private static final class Layout {
        final LyricsTimeline.Line line;
        final double[] starts, widths;
        final double total;
        Layout(HudRenderer r, LyricsTimeline.Line line, double scale) {
            this.line = line;
            starts = new double[line.words().size()]; widths = new double[starts.length];
            double x = 0, gap = r.textWidth(" ", false, scale);
            for (int i = 0; i < starts.length; i++) {
                starts[i] = x; widths[i] = r.textWidth(line.words().get(i).text(), false, scale);
                x += widths[i] + gap;
            }
            total = starts.length == 0 ? r.textWidth(line.text(), false, scale) : x - gap;
        }
    }

    private static final class Lane {
        LyricsTimeline.Line line;
        Layout layout, outgoing;
        double scroll, outgoingScroll, elapsed = 1, scale;
        Object font;
        boolean customFont;
        void reset() { line = null; layout = outgoing = null; scroll = 0; elapsed = 1; }

        void draw(HudRenderer r, LyricsTimeline.Line next, double x, double y, double right, double ts,
                  double seconds, double dt, double opacity, boolean seek, boolean animate, boolean backing) {
            Object selectedFont = Config.get().font.get();
            boolean custom = meteordevelopment.meteorclient.systems.hud.Hud.get().hasCustomFont();
            boolean changedFont = scale != ts || font != selectedFont || customFont != custom;
            if (line != next || changedFont) {
                outgoing = seek || changedFont ? null : layout;
                outgoingScroll = scroll;
                line = next;
                layout = next == null ? null : new Layout(r, next, ts);
                scroll = 0; elapsed = outgoing == null && seek ? 1 : 0;
                scale = ts; font = selectedFont; customFont = custom;
            }
            if (seek) { outgoing = null; elapsed = 1; }
            elapsed = animate ? Math.min(1, elapsed + dt / 0.24) : 1;
            double fade = elapsed * elapsed * (3 - 2 * elapsed);
            if (layout != null) {
                double target = LyricsScroll.target(line, seconds, layout.starts, layout.widths, right - x, layout.total);
                if (layout.starts.length == 0 && layout.total > right - x) {
                    target = Math.clamp((seconds - line.start() - 0.8) / Math.max(1, line.end() - line.start() - 1.6), 0, 1) * (layout.total - (right - x));
                }
                scroll = seek || !animate ? target : LyricsScroll.advance(scroll, target, dt);
            }
            if (outgoing != null && fade < 1) drawLayout(r, outgoing, x, y - fade * 4 * ts, right, ts, seconds, outgoingScroll, opacity * (1 - fade), backing);
            if (layout != null) drawLayout(r, layout, x, y + (1 - fade) * 4 * ts, right, ts, seconds, scroll, opacity * fade, backing);
            if (elapsed >= 1) outgoing = null;
        }
    }

    private static void drawLayout(HudRenderer r, Layout layout, double x, double y, double right, double scale,
                                   double seconds, double scroll, double opacity, boolean backing) {
        if (opacity < 0.01) return;
        Color accent = EmberPalette.accent();
        if (layout.starts.length == 0) {
            r.textClipped(layout.line.text(), x - scroll, y, new Color(230, 234, 243, (int) (255 * opacity)), scale, x, right);
            return;
        }
        for (int i = 0; i < layout.starts.length; i++) {
            double wx = x + layout.starts[i] - scroll;
            if (wx + layout.widths[i] < x || wx > right) continue;
            var word = layout.line.words().get(i);
            double lit = Math.clamp((seconds - word.start()) / 0.09, 0, 1);
            double active = seconds < word.end() ? lit : Math.max(0, 1 - (seconds - word.end()) / 0.16);
            int base = seconds >= word.start() ? (backing ? 205 : 233) : (backing ? 147 : 174);
            Color color = new Color((int) (base + (accent.r - base) * active), (int) (base + (accent.g - base) * active),
                (int) (base + (accent.b - base) * active), (int) (255 * opacity));
            r.textClipped(word.text(), wx, y, color, scale, x, right);
        }
    }

    private static String elide(HudRenderer r, String text, double maxWidth, double scale) {
        if (r.textWidth(text, false, scale) <= maxWidth) return text;
        int low = 0, high = text.codePointCount(0, text.length());
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (r.textWidth(text.substring(0, text.offsetByCodePoints(0, mid)) + "\u2026", false, scale) <= maxWidth) low = mid;
            else high = mid - 1;
        }
        return low == 0 ? "" : text.substring(0, text.offsetByCodePoints(0, low)) + "\u2026";
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
