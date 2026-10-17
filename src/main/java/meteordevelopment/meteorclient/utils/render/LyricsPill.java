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

/** Text-sized lyric pill with clipped scrolling, timed syllable fill and transient backing lanes. */
public final class LyricsPill {
    public record Size(double width, double height) {}
    private record CreditHit(double x, double y, double w, double h, String url) {}
    private static final EmberAnim.Clock CLOCK = new EmberAnim.Clock();
    private static final LyricsDetails DETAILS = new LyricsDetails();
    private static final Lane LEAD = new Lane();
    private static final Lane[] BACKING = {new Lane(), new Lane()};
    private static String track = "";
    private static boolean mouseDown;
    private static double lastSeconds = Double.NaN, width, backingHeight;

    private LyricsPill() {}

    public static Size render(HudRenderer r, double x, double y, double baseHeight, double s, double opacity, double availableWidth) {
        Config config = Config.get();
        if (!config.lyricsEnabled.get() || availableWidth < 140 * s) return new Size(0, 0);
        double dt = CLOCK.tick();
        MusicPlayback.Playback playback = MusicPlayback.current();
        MusicLyricsService.Result result = MusicLyricsService.update(playback);
        LyricsTimeline timeline = playback != null && playback.key().equals(result.trackKey()) ? result.timeline() : null;
        String id = playback == null ? "" : playback.key();
        long positionMs = playback == null ? 0 : playback.positionMs();
        double seconds = (positionMs + config.lyricsOffset.get()) / 1000.0;
        boolean changedTrack = !id.equals(track);
        boolean seek = changedTrack || !Double.isFinite(lastSeconds) || seconds < lastSeconds - 0.15 || seconds > lastSeconds + 1;
        if (changedTrack) {
            LEAD.reset(); for (Lane lane : BACKING) lane.reset();
            track = id; width = 0; backingHeight = 0;
        }
        lastSeconds = seconds;
        boolean animate = !EmberAppearance.liquid() || EmberAppearance.motion() > 0;
        double ts = config.lyricsTextSize.get() * s, bs = ts * 0.81, cs = 0.43 * s, pad = 11 * s;
        LyricsTimeline.Line line = timeline == null ? null : timeline.displayAt(seconds);
        LEAD.prepare(r, line, ts, dt, seek, animate);
        List<LyricsTimeline.Line> backing = timeline != null && config.lyricsBackground.get() ? timeline.backgroundAt(seconds) : List.of();
        List<LyricsTimeline.Line> pending = new ArrayList<>(backing);
        for (Lane lane : BACKING) pending.remove(lane.line);
        for (Lane lane : BACKING) {
            var next = lane.line != null && backing.contains(lane.line) ? lane.line : pending.isEmpty() ? null : pending.removeFirst();
            lane.prepare(r, next, bs, dt, seek, animate);
        }
        String title = playback != null ? playback.title() : MediaInfo.isAvailable() ? MediaInfo.getTitle() : "Music lyrics";
        double measured = line == null ? r.textWidth(title, false, ts) : LEAD.measuredWidth();
        double totalPresence = 0;
        double[] presence = new double[BACKING.length];
        for (int i = 0; i < BACKING.length; i++) {
            presence[i] = BACKING[i].presence(seconds);
            totalPresence += presence[i];
            if (presence[i] > 0.03) measured = Math.max(measured, BACKING[i].measuredWidth());
        }
        double maxW = Math.min(config.lyricsMaxWidth.get() * s, availableWidth);
        double targetW = LyricMotion.fittedWidth(measured, maxW, pad, s);
        width = width == 0 || seek || !animate ? targetW : EmberAnim.approach(width, targetW, dt, 0.105);
        double w = Math.min(maxW, width), inner = w - 2 * pad;
        double leadH = baseHeight;
        double leadY = y + (leadH - r.textHeight(false, ts)) / 2;
        double backingH = r.textHeight(false, bs) + 5 * s;
        double targetBacking = backingH * totalPresence;
        backingHeight = seek || !animate ? targetBacking : EmberAnim.approach(backingHeight, targetBacking, dt, 0.07);
        if (totalPresence < 0.001 && backingHeight < 0.08 * s) backingHeight = 0;
        double creditH = r.textHeight(false, cs) + 2 * s;
        double creditExtent = creditH + 5 * s;
        boolean hovered = hovered(x, y, w, leadH + backingHeight + creditExtent * DETAILS.reveal());
        long now = System.nanoTime();
        double detailReveal = DETAILS.update(id, positionMs, now, dt, hovered, config.lyricsCreditsAlways.get(), animate);
        double h = leadH + backingHeight + creditExtent * detailReveal;
        double radius = Math.min(9 * s, h * 0.25), left = x + pad, right = x + w - pad;
        r.dropShadow(x, y, w, h, radius, 12 * s, (float) opacity);
        EmberStrip.panel(r, x, y, w, h, radius, opacity);
        LEAD.draw(r, left, leadY, right, y + s, y + leadH - s, ts, seconds, dt, opacity, seek, animate, false);
        if (line == null) r.textClipped(elide(r, title, inner, ts), left, leadY, new Color(234, 237, 245, (int) (255 * opacity)), ts, left, right);
        double backingY = y + leadH;
        for (int i = 0; i < BACKING.length; i++) {
            if (presence[i] < 0.001 || backingHeight < 0.1 * s) continue;
            double slot = backingHeight * presence[i] / totalPresence;
            BACKING[i].draw(r, left, backingY + 2 * s, right, backingY, backingY + slot, bs, seconds, dt, opacity * 0.86, seek, animate, true);
            backingY += slot;
        }

        // Fade and clip the details within their expanding row. The compact lead stays
        // centered and still, and hidden credits reserve no height or clickable area.
        double creditTop = y + leadH + backingHeight, creditY = creditTop + s;
        double creditBottom = y + h - 3 * s;
        List<LyricsTimeline.Credit> credits = timeline != null && (!timeline.lines().isEmpty() || !timeline.background().isEmpty()) ? timeline.credits() : List.of();
        List<CreditHit> hits = new ArrayList<>();
        Color quiet = new Color(151, 160, 179, (int) (220 * opacity * LyricMotion.smooth(detailReveal)));
        if (detailReveal > 0 && credits.isEmpty()) {
            String status = timeline != null && !timeline.lines().isEmpty() ? "Waiting for vocals" : result.status();
            if (playback != null && !playback.clock().playing()) status = "Paused";
            r.textClipped(elide(r, status, inner, cs), left, creditY, quiet, cs, left, right, creditTop, creditBottom);
        } else if (detailReveal > 0) {
            double creditsWidth = 0;
            for (int i = 0; i < credits.size(); i++) creditsWidth += r.textWidth((i > 0 ? "  /  " : "") + credits.get(i).name(), false, cs);
            double overflow = Math.max(0, creditsWidth - inner), travel = overflow / Math.max(1, 13 * s);
            double phase = DETAILS.elapsedSeconds(now) % (travel * 2 + 4);
            double offset = phase < 2 ? 0 : phase < 2 + travel ? (phase - 2) * 13 * s
                : phase < 4 + travel ? overflow : Math.max(0, overflow - (phase - 4 - travel) * 13 * s);
            double cx = left - offset;
            for (int i = 0; i < credits.size(); i++) {
                var credit = credits.get(i);
                String text = (i > 0 ? "  /  " : "") + credit.name();
                double cw = r.textWidth(text, false, cs);
                r.textClipped(text, cx, creditY, quiet, cs, left, right, creditTop, creditBottom);
                double hitLeft = Math.max(left, cx), hitRight = Math.min(right, cx + cw);
                if (detailReveal > 0.95 && hitRight > hitLeft) hits.add(new CreditHit(hitLeft, creditY, hitRight - hitLeft,
                    Math.min(creditH, Math.max(0, creditBottom - creditY)), credit.url()));
                cx += cw;
            }
        }
        handleCredits(hits);
        return new Size(w, h);
    }

    private record Part(LyricsTimeline.Syllable syllable, double start, double width) {}
    private static final class Layout {
        final LyricsTimeline.Line line;
        final double[] starts, widths;
        final List<Part> parts;
        final double total;
        Layout(HudRenderer r, LyricsTimeline.Line line, double scale) {
            this.line = line;
            starts = new double[line.words().size()]; widths = new double[starts.length];
            List<Part> pieces = new ArrayList<>();
            double x = 0, gap = r.textWidth(" ", false, scale);
            for (int i = 0; i < starts.length; i++) {
                var word = line.words().get(i);
                starts[i] = x;
                for (var syllable : word.syllables()) {
                    double partW = r.textWidth(syllable.text(), false, scale);
                    pieces.add(new Part(syllable, x, partW)); x += partW;
                }
                widths[i] = x - starts[i]; x += gap;
            }
            parts = List.copyOf(pieces);
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
        double measuredWidth() { return Math.max(layout == null ? 0 : layout.total, outgoing == null ? 0 : outgoing.total); }
        double presence(double seconds) {
            return Math.max(LyricMotion.backingPresence(line, seconds), outgoing == null ? 0 : LyricMotion.backingPresence(outgoing.line, seconds) * (1 - elapsed));
        }
        void prepare(HudRenderer r, LyricsTimeline.Line next, double ts, double dt, boolean seek, boolean animate) {
            Object selectedFont = Config.get().font.get();
            boolean custom = meteordevelopment.meteorclient.systems.hud.Hud.get().hasCustomFont();
            boolean changedFont = scale != ts || font != selectedFont || customFont != custom;
            if (line != next || changedFont) {
                outgoing = seek || changedFont ? null : layout; outgoingScroll = scroll;
                line = next; layout = next == null ? null : new Layout(r, next, ts);
                scroll = 0; elapsed = outgoing == null && seek ? 1 : 0;
                scale = ts; font = selectedFont; customFont = custom;
            }
            if (seek) { outgoing = null; elapsed = 1; }
            elapsed = animate ? Math.min(1, elapsed + dt / 0.22) : 1;
            if (elapsed >= 1) outgoing = null;
        }
        void draw(HudRenderer r, double x, double y, double right, double top, double bottom, double ts,
                  double seconds, double dt, double opacity, boolean seek, boolean animate, boolean backing) {
            double fade = LyricMotion.smooth(elapsed);
            if (layout != null) {
                double target = LyricsScroll.target(line, seconds, layout.starts, layout.widths, right - x, layout.total);
                if (layout.starts.length == 0 && layout.total > right - x) target = Math.clamp((seconds - line.start() - 0.8) / Math.max(1, line.end() - line.start() - 1.6), 0, 1) * (layout.total - (right - x));
                scroll = seek || !animate ? target : LyricsScroll.advance(scroll, target, dt);
            }
            if (outgoing != null && fade < 1) drawLayout(r, outgoing, x, y - fade * 3 * ts, right, top, bottom, ts, seconds, outgoingScroll, opacity * (1 - fade), animate, backing);
            if (layout != null) drawLayout(r, layout, x, y + (1 - fade) * 3 * ts, right, top, bottom, ts, seconds, scroll, opacity * fade, animate, backing);
        }
    }

    private static void drawLayout(HudRenderer r, Layout layout, double x, double y, double right, double top, double bottom,
                                   double scale, double seconds, double scroll, double opacity, boolean animate, boolean backing) {
        if (backing) opacity *= LyricMotion.backingPresence(layout.line, seconds);
        if (opacity < 0.01 || right <= x || bottom <= top) return;
        if (layout.parts.isEmpty()) {
            r.textClipped(layout.line.text(), x - scroll, y, new Color(238, 240, 247, (int) (255 * opacity)), scale, x, right, top, bottom);
            return;
        }
        Color accent = EmberPalette.accent();
        Color dim = new Color(173, 175, 185, (int) (235 * opacity));
        Color lit = new Color(250, 250, 255, (int) (255 * opacity));
        for (Part part : layout.parts) {
            double sx = x + part.start - scroll;
            if (sx + part.width < x || sx > right) continue;
            var syllable = part.syllable;
            double progress = syllable.progress(seconds);
            double activity = animate ? LyricMotion.activity(seconds, syllable.start(), syllable.end()) : 0;
            double sy = y - activity * 1.2 * scale;
            // Completed syllables need one white draw, not two overlaid font submissions.
            if (progress < 1) r.textClipped(syllable.text(), sx, sy, dim, scale, x, right, top, bottom);
            double fillRight = Math.min(right, sx + part.width * progress);
            double fillLeft = Math.max(x, sx);
            if (fillRight <= fillLeft) continue;
            if (activity > 0.01) {
                Color glow = new Color((accent.r + 510) / 3, (accent.g + 510) / 3, (accent.b + 510) / 3, (int) (24 * opacity * activity));
                double distance = 1.9 * scale;
                for (int i = 0; i < 6; i++) {
                    double angle = i * Math.PI / 3;
                    r.textClipped(syllable.text(), sx + Math.cos(angle) * distance, sy + Math.sin(angle) * distance, glow, scale,
                        Math.max(x, fillLeft - distance), Math.min(right, fillRight + distance), top, bottom);
                }
            }
            // Independent syllable fill preserves a held cut inside a joined word; the
            // white sweep resumes only at the next supplied syllable timestamp.
            r.textClipped(syllable.text(), sx, sy, lit, scale, fillLeft, fillRight, top, bottom);
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
    private static boolean hovered(double x, double y, double w, double h) {
        if (mc.currentScreen == null) return false;
        double scale = mc.getWindow().getScaleFactor();
        double mx = mc.mouse.getScaledX(mc.getWindow()) * scale, my = mc.mouse.getScaledY(mc.getWindow()) * scale;
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
    private static void handleCredits(List<CreditHit> hits) {
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (mc.currentScreen != null && down && !mouseDown) {
            double scale = mc.getWindow().getScaleFactor();
            double mx = mc.mouse.getScaledX(mc.getWindow()) * scale, my = mc.mouse.getScaledY(mc.getWindow()) * scale;
            for (CreditHit hit : hits) if (mx >= hit.x && mx < hit.x + hit.w && my >= hit.y && my < hit.y + hit.h) { Util.getOperatingSystem().open(hit.url); break; }
        }
        mouseDown = down;
    }
}
