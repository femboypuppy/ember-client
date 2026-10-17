package meteordevelopment.meteorclient.utils.misc;

/** One atomic Windows media sample, shared by the music widget and lyric clock. */
public record DesktopMediaSession(String title, String artist, String album, String appId,
                                  PlaybackClock clock, long receivedAtNanos) {
    public String key() {
        return appId + "\u0001" + title + "\u0001" + artist + "\u0001" + album;
    }

    public static DesktopMediaSession parse(String record, DesktopMediaSession previous, long now) {
        String[] parts = record.split("\u0001", -1);
        if (parts.length < 6 || parts[0].isBlank()) return null;
        String title = bounded(parts[0]), artist = bounded(parts[1]);
        String album = parts.length > 7 ? bounded(parts[7]) : "";
        String app = parts.length > 6 ? bounded(parts[6]) : "";
        long position = number(parts[2]), duration = number(parts[3]);
        boolean playing = parts[4].equalsIgnoreCase("Playing");
        boolean same = previous != null && previous.title.equals(title) && previous.artist.equals(artist)
            && previous.album.equals(album) && previous.appId.equals(app);
        PlaybackClock clock = same ? previous.clock.corrected(position, duration, playing, now)
            : new PlaybackClock(position, duration, playing, now);
        return new DesktopMediaSession(title, artist, album, app, clock, now);
    }

    private static String bounded(String value) { return value.strip().substring(0, Math.min(value.strip().length(), 512)); }
    private static long number(String value) {
        try { return Math.clamp(Long.parseLong(value.trim()), 0, 86_400_000); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
