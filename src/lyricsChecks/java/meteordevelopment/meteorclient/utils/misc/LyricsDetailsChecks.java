package meteordevelopment.meteorclient.utils.misc;

/** Checks real intro/hover state transitions without depending on the renderer or a music service. */
final class LyricsDetailsChecks {
    static void run() {
        var details = new LyricsDetails();
        long started = 10_000_000_000L;
        require(details.update("one", 90_000, started, 0, false, false, false) == 1,
            "A newly detected track gets an introduction even when joined mid-song");
        require(details.update("one", 94_999, started + 4_999_000_000L, 0, false, false, false) == 1,
            "Credits must remain visible through the first five seconds");
        require(details.update("one", 95_000, started + 5_000_000_000L, 0, false, false, false) == 0,
            "The automatic details row must close after five seconds");
        require(details.update("one", 95_000, started + 6_000_000_000L, 0, true, false, false) == 1,
            "Hover must reveal credits on a paused track after the introduction");
        require(details.update("one", 95_000, started + 7_000_000_000L, 0, false, false, false) == 0,
            "Leaving the pill must close its details again");
        require(details.update("one", 95_000, started + 8_000_000_000L, 0, false, true, false) == 1,
            "Always-visible credits must override the timeout without hover");
        require(details.update("one", 95_000, started + 9_000_000_000L, 0, false, false, false) == 0,
            "Turning off always-visible credits must not start a fresh introduction");
        require(details.update("one", 20_000, started + 10_000_000_000L, 0, false, false, false) == 0,
            "An ordinary backward seek must not restart the introduction");
        require(details.update("one", 0, started + 11_000_000_000L, 0, false, false, false) == 1,
            "Repeating the same recording must get a fresh introduction");
        require(details.update("two", 40_000, started + 20_000_000_000L, 0, false, false, false) == 1,
            "Switching tracks must reset the timeout");

        var low = new LyricsDetails(); var high = new LyricsDetails();
        low.update("one", 0, started, 0, false, false, false);
        high.update("one", 0, started, 0, false, false, false);
        for (int i = 1; i <= 30; i++) low.update("one", 7000, started + 6_000_000_000L, 1.0 / 30, false, false, true);
        for (int i = 1; i <= 144; i++) high.update("one", 7000, started + 6_000_000_000L, 1.0 / 144, false, false, true);
        require(low.reveal() == 0 && high.reveal() == 0, "Details must fully collapse without a residual empty strip at both frame rates");
        for (int i = 1; i <= 3; i++) low.update("one", 7000, started + 7_000_000_000L, 1.0 / 30, true, false, true);
        for (int i = 1; i <= 14; i++) high.update("one", 7000, started + 7_000_000_000L, 1.0 / 140, true, false, true);
        require(low.reveal() > 0 && low.reveal() < 1, "Hover must open the row smoothly");
        require(Math.abs(low.reveal() - high.reveal()) < 0.000001, "The reveal must be independent of FPS");
        double opening = low.reveal();
        low.update("one", 7000, started + 7_100_000_000L, 1.0 / 30, false, false, true);
        require(low.reveal() > 0 && low.reveal() < opening, "Leaving during expansion must reverse smoothly");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
