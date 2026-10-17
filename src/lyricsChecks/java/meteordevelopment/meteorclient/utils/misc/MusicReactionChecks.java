package meteordevelopment.meteorclient.utils.misc;

import meteordevelopment.meteorclient.utils.render.CelestialLighting;

public final class MusicReactionChecks {
    private MusicReactionChecks() {}
    public static void run() {
        require(SpotifyAudioSignal.parse("STATUS waiting").status().contains("Open Spotify"), "Missing Spotify must have an actionable status");
        require(SpotifyAudioSignal.parse("LEVEL 0 0").status().contains("silent"), "Zero measurements cannot claim music was detected");
        require(SpotifyAudioSignal.parse("LEVEL 0 0.1").status().contains("low bass"), "Audible music without bass is different from a missing stream");
        require(SpotifyAudioSignal.parse("LEVEL 0.02 0.1").bass() == 0.02, "The real Spotify bass sample must reach the envelope");
        require(SpotifyAudioSignal.parse("ERROR 80070057").bass() == 0, "A capture error must clear the signal and show its reason");
        for (String invalid : new String[]{"0.01 0.1", "LEVEL NaN 0", "LEVEL 0 Infinity", "LEVEL -1 0", "LEVEL 0 2", "ERROR private payload", "LEVEL 0 0 junk"})
            require(SpotifyAudioSignal.parse(invalid) == null, "Malformed or obsolete helper output must be rejected");
        var envelope = new BassEnvelope();
        for (int i = 0; i < 1000; i++) require(envelope.update(0, 1.5, 0.45, 1.0 / 60) == 0, "Silence must not synthesize a beat");
        require(envelope.update(0.001, 4, 0.45, 0.1) == 0, "The noise floor must not pulse");
        double hit = envelope.update(0.2, 1.5, 0.45, 0.05);
        require(hit > 0.6 && hit <= 1, "A bass hit must produce a bounded response");
        double tail = envelope.update(0, 1.5, 0.45, 0.05);
        require(tail > 0 && tail < hit, "A paused or silent source must ease out");
        for (int i = 0; i < 20; i++) tail = envelope.update(Double.NaN, 1.5, 0.45, 1);
        require(tail == 0, "Missing or invalid input must decay completely");
        double slow = simulate(30), fast = simulate(144);
        require(Math.abs(slow - fast) < 1e-9, "The same bass sample must have the same response at 30 and 144 FPS");
        envelope.update(0.5, 4, 1, 0.2); envelope.reset();
        require(envelope.update(0, 1, 1, 0.2) == 0, "Disabling must clear the old envelope");
        for (boolean glow : new boolean[]{false, true}) for (double level : new double[]{0, 0.2, 1}) {
            require(BassEnvelope.exposure(level, 0, glow) == 1, "Zero strength must restore unchanged exposure");
            double exposure = BassEnvelope.exposure(level, 1, glow);
            require(exposure >= 0.4 && exposure <= 1.65, "Reaction cannot black out or overexpose the scene");
            require(CelestialLighting.illumination(0, 1, 1.6, 1, exposure) == 0, "Bass cannot create daylight in an unlit cave");
            require(CelestialLighting.illumination(0.73f, 0.8, 0.3, 0, exposure) == 0.73f, "Full vanilla mix must restore exact daylight, even during bass");
        }
        require(CelestialLighting.mixedGamma(17, 0.9, 0) == 17, "Vanilla mix must restore external gamma without clamping it");
        require(CelestialLighting.illumination(1, 0.4, 0.5, 1, 1) < CelestialLighting.illumination(1, 0.4, 1.5, 1, 1), "Brightness must work independently of tint");
        System.out.println("Music reaction passed: silence/noise gating, bass attack, pause/missing-input release, FPS independence, bounded exposure, exact vanilla mix and cave darkness.");
    }
    private static double simulate(int fps) {
        var envelope = new BassEnvelope(); double value = 0;
        for (int i = 0; i < fps; i++) value = envelope.update(0.1, 1.5, 0.45, 1.0 / fps);
        for (int i = 0; i < fps; i++) value = envelope.update(0, 1.5, 0.45, 1.0 / fps);
        return value;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
