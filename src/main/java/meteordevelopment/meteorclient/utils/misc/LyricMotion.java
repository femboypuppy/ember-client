package meteordevelopment.meteorclient.utils.misc;

/** Playback-driven envelopes keep syllables, backing rows and seeks independent of FPS. */
public final class LyricMotion {
    private LyricMotion() {}
    public static double smooth(double t) { t = Math.clamp(t, 0, 1); return t * t * (3 - 2 * t); }
    public static double activity(double seconds, double start, double end) {
        return smooth((seconds - start) / 0.085) * (1 - smooth((seconds - end) / 0.24));
    }
    public static double backingPresence(LyricsTimeline.Line line, double seconds) {
        return line == null ? 0 : smooth((seconds - line.start()) / 0.14) * (1 - smooth((seconds - line.end()) / 0.35));
    }
    public static double fittedWidth(double content, double maximum, double padding, double scale) {
        return Math.min(maximum, Math.max(112 * scale, content + padding * 2));
    }
}
