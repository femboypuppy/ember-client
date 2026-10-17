package meteordevelopment.meteorclient.utils.misc;

import meteordevelopment.meteorclient.systems.config.Config;

/** Prefer the desktop's active player; retain exact Spotify IDs when the recordings match. */
public final class MusicPlayback {
    private static String matchedDesktop = "", matchedSpotify = "";
    private static long matchedDesktopDuration, matchedSpotifyDuration;
    private static boolean sameRecording;
    public record Playback(String key, String spotifyId, String title, String artist, String album,
                           String source, String artworkUrl, PlaybackClock clock) {
        public long positionMs() { return clock.positionAt(System.nanoTime()); }
    }

    private MusicPlayback() {}

    public static Playback current() {
        var spotify = SpotifyPlayback.current();
        var config = Config.get();
        var desktop = config != null && config.desktopMusic.get() ? MediaInfo.current() : null;
        return choose(spotify, desktop);
    }

    static Playback choose(SpotifyPlayback.Playback spotify, DesktopMediaSession desktop) {
        if (desktop != null && (desktop.clock().playing() || spotify == null || !spotify.clock().playing())) {
            String desktopKey = desktop.key();
            String spotifyKey = spotify == null ? "" : spotify.trackId();
            long spotifyDuration = spotify == null ? 0 : spotify.clock().durationMs();
            if (!desktopKey.equals(matchedDesktop) || !spotifyKey.equals(matchedSpotify)
                || desktop.clock().durationMs() != matchedDesktopDuration || spotifyDuration != matchedSpotifyDuration) {
                matchedDesktop = desktopKey; matchedSpotify = spotifyKey;
                matchedDesktopDuration = desktop.clock().durationMs(); matchedSpotifyDuration = spotifyDuration;
                sameRecording = spotify != null && LrclibLyrics.matches(desktop.title(), desktop.artist(), matchedDesktopDuration,
                    spotify.title(), spotify.artist(), spotifyDuration);
            }
            boolean same = sameRecording;
            return new Playback(same ? "spotify:" + spotify.trackId() : "desktop:" + desktop.key(),
                same ? spotify.trackId() : "", desktop.title(), desktop.artist(), desktop.album(),
                "Desktop player", same ? spotify.artworkUrl() : "", desktop.clock());
        }
        return spotify == null ? null : new Playback("spotify:" + spotify.trackId(), spotify.trackId(), spotify.title(),
            spotify.artist(), "", "Spotify", spotify.artworkUrl(), spotify.clock());
    }

    public static String status() {
        var current = current();
        if (current != null) return current.source() + (current.clock().playing() ? " playing: " : " paused: ") + current.title();
        return Config.get().desktopMusic.get() ? "Play music in a Windows media player or browser tab" : SpotifyPlayback.status();
    }
}
