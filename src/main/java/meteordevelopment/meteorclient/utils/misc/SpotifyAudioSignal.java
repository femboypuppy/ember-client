package meteordevelopment.meteorclient.utils.misc;

/** Strict helper protocol: attaching to Spotify is distinct from receiving audible audio. */
public record SpotifyAudioSignal(double bass, double rms, String status) {
    public static SpotifyAudioSignal parse(String line) {
        if (line == null || line.length() > 128) return null;
        if (line.equals("STATUS waiting")) return new SpotifyAudioSignal(0, 0, "Open Spotify desktop and play a song");
        if (line.equals("STATUS starting")) return new SpotifyAudioSignal(0, 0, "Connecting to Spotify audio");
        if (line.startsWith("ERROR ") && line.substring(6).matches("[0-9A-Fa-f]{8}"))
            return new SpotifyAudioSignal(0, 0, "Spotify audio unavailable (0x" + line.substring(6) + "); retrying");
        if (!line.startsWith("LEVEL ")) return null;
        String[] parts = line.split(" ", -1);
        if (parts.length != 3) return null;
        try {
            double bass = Double.parseDouble(parts[1]), rms = Double.parseDouble(parts[2]);
            if (!Double.isFinite(bass) || !Double.isFinite(rms) || bass < 0 || bass > 1 || rms < 0 || rms > 1) return null;
            return new SpotifyAudioSignal(bass, rms, rms <= 0.0005 ? "Spotify is paused or silent"
                : bass <= 0.002 ? "Spotify playing (low bass)" : "Spotify audio detected");
        } catch (NumberFormatException ignored) { return null; }
    }
}
