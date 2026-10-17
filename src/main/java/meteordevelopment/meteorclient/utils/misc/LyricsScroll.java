package meteordevelopment.meteorclient.utils.misc;

/** Font-independent viewport motion, driven by precise playback time rather than word steps. */
public final class LyricsScroll {
    private LyricsScroll() {}

    public static double target(LyricsTimeline.Line line, double seconds, double[] starts, double[] widths, double viewport, double total) {
        if (total <= viewport || starts.length == 0) return 0;
        double focus = 0;
        for (int i = 0; i < line.words().size(); i++) {
            LyricsTimeline.Word word = line.words().get(i);
            if (seconds < word.start()) break;
            double progress = Math.clamp((seconds - word.start()) / Math.max(0.001, word.end() - word.start()), 0, 1);
            focus = starts[i] + widths[i] * progress;
        }
        // Start with the whole beginning in view, then follow the voice through the line.
        return Math.clamp(focus - viewport * 0.58, 0, total - viewport);
    }

    public static double advance(double current, double target, double dt) {
        return current + (target - current) * (1 - Math.exp(-Math.max(0, dt) / 0.13));
    }
}
