package meteordevelopment.meteorclient.utils.misc;

import com.google.gson.JsonParser;

/** Synthetic desktop samples and search results, without live accounts or lyric fixtures. */
public final class DesktopMusicChecks {
    public static void run() {
        long now = 10_000_000_000L;
        String sample = String.join("\u0001", "Synthetic Song", "Example Artist", "1200", "180000", "Playing", "1", "Spotify.exe", "Example Album");
        var session = DesktopMediaSession.parse(sample, null, now);
        require(session.title().equals("Synthetic Song") && session.album().equals("Example Album"), "Publish metadata in one snapshot");
        require(session.clock().positionAt(now + 300_000_000L) == 1500, "Interpolate the Windows playback clock");
        var paused = DesktopMediaSession.parse(sample.replace("Playing", "Paused"), session, now + 500_000_000L);
        require(paused.clock().positionAt(now + 30_000_000_000L) == 1200, "Desktop pause freezes the lyric clock");
        var seek = DesktopMediaSession.parse(sample.replace("1200", "30000"), session, now + 500_000_000L);
        require(seek.clock().positionAt(now + 500_000_000L) == 30000, "Desktop seeks apply immediately");
        var tab = DesktopMediaSession.parse(sample.replace("Spotify.exe", "Chrome.exe").replace("1200", "0"), session, now + 500_000_000L);
        require(!tab.key().equals(session.key()) && tab.clock().positionMs() == 0, "Switching player resets identity and timing");
        require(DesktopMediaSession.parse("\u0001\u0001" + "0\u0001" + "0\u0001None\u00010", session, now) == null, "Closed sessions clear playback");
        require(DesktopMediaSession.parse("invalid", session, now) == null, "Malformed media samples are ignored");

        var direct = new SpotifyPlayback.Playback("0000000000000000000000", session.title(), session.artist(), "", session.clock(), now);
        var exact = MusicPlayback.choose(direct, session);
        require(exact.spotifyId().equals(direct.trackId()), "Matching desktop songs retain the exact Spotify ID");
        for (int i = 0; i < 200; i++) {
            var jitter = new DesktopMediaSession(session.title(), session.artist(), i % 2 == 0 ? "" : session.album(), session.appId(),
                new PlaybackClock(60000 + i, 180000 + i % 4 * 100, true, now + i), now + i);
            var droppedPoll = MusicPlayback.choose(i % 5 == 0 ? direct : null, jitter);
            require(droppedPoll.spotifyId().equals(direct.trackId()) && droppedPoll.key().equals(exact.key()),
                "A temporary Spotify dropout, duration jitter, or late album must not change the verified recording ID");
        }
        var otherApp = new DesktopMediaSession(session.title(), session.artist(), session.album(), "Chrome.exe", session.clock(), now);
        require(MusicPlayback.choose(null, otherApp).spotifyId().isEmpty(), "Another app must verify its own recording before inheriting an ID");
        MusicPlayback.choose(direct, session);
        var otherVersion = new DesktopMediaSession(session.title(), session.artist(), "", session.appId(), new PlaybackClock(0, 200000, true, now), now);
        require(MusicPlayback.choose(null, otherVersion).spotifyId().isEmpty(), "A changed recording duration must clear the retained ID");
        MusicPlayback.choose(direct, session);
        require(MusicPlayback.choose(null, null) == null, "Missing players must not fabricate active playback");
        require(MusicPlayback.choose(null, session).spotifyId().equals(direct.trackId()), "A media-session reconnect can reuse verified recording metadata");
        // Clear the binding through a known different song before testing desktop-only identity.
        MusicPlayback.choose(null, otherVersion);
        var desktopAlbum = MusicPlayback.choose(null, session);
        var withoutAlbum = new DesktopMediaSession(session.title(), session.artist(), "", session.appId(), session.clock(), now);
        require(MusicPlayback.choose(null, withoutAlbum).key().equals(desktopAlbum.key()), "An arriving album label must not reset desktop lyrics either");
        MusicPlayback.choose(direct, session);
        var differentSong = new DesktopMediaSession("Another Song", "Another Artist", "", "Chrome.exe", session.clock(), now);
        var browser = MusicPlayback.choose(direct, differentSong);
        require(browser.spotifyId().isEmpty() && browser.title().equals("Another Song"), "A playing browser song must never inherit an unrelated Spotify ID");
        require(MusicPlayback.choose(direct, null).spotifyId().equals(direct.trackId()), "Keep Spotify playback on systems without a desktop media session");
        require(MusicPlayback.choose(null, differentSong).key().startsWith("desktop:"), "Desktop playback works without a Spotify connection");

        require(LrclibLyrics.matches("Synthetic Song (Official Music Video)", "Example Artist - Topic", 180000,
            "Synthetic Song", "Example Artist", 181500), "Ignore cosmetic video labels and Topic suffixes");
        require(LrclibLyrics.matches("Example Artist - Synthetic Song (Official Audio)", "", 180000,
            "Synthetic Song", "Example Artist", 180000), "Recover artist and title from a browser media title");
        require(!LrclibLyrics.matches("Synthetic Song (Live)", "Example Artist", 180000,
            "Synthetic Song", "Example Artist", 180000), "Never strip recording version labels");
        require(!LrclibLyrics.matches("Synthetic Song", "Example Artist", 180000,
            "Synthetic Song", "Example Artist", 190000), "Reject a different recording duration");
        require(!LrclibLyrics.matches("Synthetic Song", "Art", 180000,
            "Synthetic Song", "Artist", 180000), "Artist substrings are not identity matches");
        require(!LrclibLyrics.matches("Synthetic Song", "", 180000,
            "Synthetic Song", "Example Artist", 180000), "Do not guess a song from the title alone");

        var lrc = LrclibLyrics.parseLrc("[ar:Example Artist]\n[00:01.25][00:08.50]first phrase\n[00:05.00]second phrase\n[00:07.00]\n[bad]ignored", 12);
        require(lrc.lines().size() == 3, "Repeated timestamp tags create separate timed entries");
        require(lrc.at(1.249) == null && lrc.at(1.25).text().equals("first phrase"), "LRC fractional timestamps must be exact");
        require(lrc.at(7.5) == null && lrc.at(8.5).text().equals("first phrase"), "Empty timed rows end vocals without becoming empty UI rows");
        require(lrc.at(10).words().isEmpty() && lrc.background().isEmpty(), "Line sync must not fabricate words or backing lanes");
        require(lrc.credits().getFirst().name().contains("LRCLIB"), "Keep provider attribution visible");
        require(LrclibLyrics.parseLrc("untimed text only", 12).lines().isEmpty(), "Static lyrics cannot acquire invented timing");
        require(LrclibLyrics.parseLrc("[offset:500]\n[00:02.00]offset phrase", 12).at(1.5) != null, "Positive LRC offsets advance the display");
        require(LrclibLyrics.parseLrc("[00:02]first\n[00:02]second\n[00:04]", 12).at(3).text().equals("first / second"), "Simultaneous lines must not drop a vocal");
        var candidates = JsonParser.parseString("""
            [{"trackName":"Synthetic Song","artistName":"Other Artist","duration":180,"syncedLyrics":"[00:01]wrong"},
             {"trackName":"Synthetic Song","artistName":"Example Artist","duration":190,"syncedLyrics":"[00:01]wrong version"},
             {"trackName":"Synthetic Song","artistName":"Example Artist","duration":180,"syncedLyrics":"[00:01]right recording"}]
            """).getAsJsonArray();
        var selected = LrclibLyrics.select(candidates, new LrclibLyrics.Query("Synthetic Song", "Example Artist", 180000));
        require(selected.at(2).text().equals("right recording"), "Rank verified candidates instead of accepting the first search result");
        var oversized = MusicLyricsService.limitedBody();
        boolean[] cancelled = {false};
        oversized.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long count) {}
            public void cancel() { cancelled[0] = true; }
        });
        oversized.onNext(java.util.List.of(java.nio.ByteBuffer.allocate(2_000_001)));
        require(cancelled[0] && oversized.getBody().toCompletableFuture().isCompletedExceptionally(), "Oversized search bodies must cancel promptly");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
