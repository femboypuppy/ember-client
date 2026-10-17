package meteordevelopment.meteorclient.utils.misc;

import com.google.gson.JsonParser;

/** Synthetic timings: no copyrighted lyric fixtures or live credentials are needed. */
public final class LyricsTimingChecks {
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--live-resolver")) { LyricsSourceChecks.liveResolver(); return; }
        LyricsSourceChecks.run();
        ChunkFindIndexChecks.run();
        long sampled = 5_000_000_000L;
        PlaybackClock playing = new PlaybackClock(1250, 5000, true, sampled);
        require(playing.positionAt(sampled + 275_000_000L) == 1525, "Playback must interpolate milliseconds");
        require(playing.positionAt(sampled - 1) == 1250, "A clock cannot run backwards");
        require(playing.positionAt(sampled + 20_000_000_000L) == 5000, "Position must stop at track duration");
        PlaybackClock paused = new PlaybackClock(1525, 5000, false, sampled);
        require(paused.positionAt(sampled + 30_000_000_000L) == 1525, "Pause must freeze the word clock");
        PlaybackClock sought = new PlaybackClock(200, 5000, true, sampled + 1_000_000_000L);
        require(sought.positionAt(sampled + 1_100_000_000L) == 300, "A seek must replace the old clock");
        PlaybackClock corrected = playing.corrected(2050, 5000, true, sampled + 1_000_000_000L);
        require(corrected.positionMs() > 2200 && corrected.positionMs() <= 2250, "Small polling jitter must not rewind the full playback error");
        require(corrected.positionAt(sampled + 1_000_000_000L) == 2250, "A polling correction must be continuous at receipt");
        long previousPosition = corrected.positionMs();
        for (int i = 1; i <= 1000; i++) {
            long next = corrected.positionAt(sampled + 1_000_000_000L + i * 1_000_000L);
            require(next >= previousPosition, "Clock correction must never reverse a lyric boundary");
            previousPosition = next;
        }
        require(playing.corrected(200, 5000, true, sampled + 1_000_000_000L).positionMs() == 200, "Real seeks must bypass smoothing");
        require(playing.corrected(2050, 5000, false, sampled + 1_000_000_000L).positionMs() == 2050, "Pausing must use the reported position immediately");
        require(paused.corrected(1600, 5000, true, sampled + 1_000_000_000L).positionMs() == 1600, "Resuming must establish a fresh position");

        LyricsTimeline lyrics = parse("""
            {"Body":{"id":"0000000000000000000000","source":"spicy_lyrics","Type":"Syllable",
              "UploadAttribution":{"Uploader":{"id":"123","username":"uploader"},"Maker":{"id":"456","username":"maker"}},
              "Content":[
                {"Type":"Vocal","Lead":{"StartTime":4,"EndTime":5,"Syllables":[{"Text":"later","StartTime":4,"EndTime":5}]}},
                {"Type":"Vocal","Lead":{"StartTime":1,"EndTime":3,"Syllables":[
                  {"Text":"sun","StartTime":1,"EndTime":1.3,"IsPartOfWord":true},
                  {"Text":"light","StartTime":1.3,"EndTime":1.8,"IsPartOfWord":false},
                  {"Text":"moves","StartTime":2,"EndTime":2.7,"IsPartOfWord":false}]}},
                {"Type":"Interlude","StartTime":0,"EndTime":1}
              ]}}
            """);
        require(lyrics.at(0.999) == null, "Do not show a line before its start");
        var first = lyrics.at(1);
        require(first != null && first.text().equals("sunlight moves"), "Join syllables belonging to the same word");
        require(first.words().size() == 2, "Syllable splitting must not create extra words");
        require(first.words().getFirst().syllables().size() == 2, "A joined word must retain both syllable clocks");
        require(first.words().getFirst().syllables().getLast().text().equals("light"), "Syllable text must survive parsing");
        require(Math.abs(first.words().getFirst().progress(1.2) - 0.25) < 0.0001, "The reveal must follow the first syllable's actual duration");
        require(first.activeWord(1.6) == 0, "Highlight the combined word for all its syllables");
        require(first.activeWord(1.8) == -1, "End times are exclusive; gaps have no active word");
        require(first.activeWord(2) == 1, "Use the next word's actual start");
        require(lyrics.at(3) == null, "Instrumental gaps must not retain the previous lyric");
        require(lyrics.displayAt(3.01) == first, "The display must hold a phrase through a short breath");
        require(lyrics.displayAt(0.5) == null, "Seeking before the first vocal must clear held text");
        require(lyrics.displayAt(60).text().equals("later"), "Instrumental display must not flicker back to the song title");
        require(lyrics.displayAt(1.1) == first, "Seeking back must restore the correct phrase immediately");
        require(lyrics.at(4).text().equals("later"), "Out-of-order API lines must sort by time");
        require(lyrics.at(1.2).text().equals(first.text()), "Seeking backwards must find the earlier lyric");
        require(lyrics.credits().size() == 3, "Community syncs need provider, uploader and maker credits");
        require(lyrics.credits().get(2).url().equals("https://spicylyrics.org/uid/456"), "Credit links must point to contributor profiles");

        LyricsTimeline lineOnly = parse("""
            {"Body":{"source":"spotify","Type":"Line","Content":[
              {"Type":"Vocal","Text":"synthetic timed line","StartTime":2,"EndTime":4}]}}
            """);
        require(lineOnly.at(2.5).words().isEmpty(), "Line sync must not invent word timings");
        require(lineOnly.credits().size() == 1, "Commercial syncs must not create empty contributor credits");
        require(lineOnly.at(4) == null, "A line must expire at its end");
        LyricsTimeline untimed = parse("""
            {"Body":{"source":"apple_music","Type":"Static","Lines":[{"Text":"synthetic untimed line"}]}}
            """);
        require(untimed.at(1) == null, "Static lyrics cannot become a fake timed line");
        LyricsTimeline invalid = parse("""
            {"Body":{"source":"spotify","Type":"Line","Content":[
              {"Type":"Vocal","Text":"missing timing"},
              {"Type":"Vocal","Text":"backwards","StartTime":5,"EndTime":2}]}}
            """);
        require(invalid.lines().isEmpty(), "Malformed timing must be ignored safely");
        LyricsTimeline backing = parse("""
            {"Body":{"source":"spicy_lyrics","Type":"Syllable","Content":[
              {"Type":"Vocal","Lead":{"StartTime":1,"EndTime":2,"Syllables":[{"Text":"lead","StartTime":1,"EndTime":3}]},
               "Background":[
                 {"StartTime":2,"EndTime":4,"Syllables":[
                   {"Text":"back","StartTime":2,"EndTime":2.5,"IsPartOfWord":true},
                   {"Text":"ing","StartTime":2.5,"EndTime":3},
                   {"Text":"voice","StartTime":3,"EndTime":4}]},
                 {"StartTime":2.5,"EndTime":3.5,"Syllables":[{"Text":"echo","StartTime":2.5,"EndTime":3.5}]}]},
              {"Type":"Vocal","Background":[{"Syllables":[{"Text":"solo","StartTime":6,"EndTime":7}]}]}
            ]}}
            """);
        require(backing.at(2.9) != null, "Group timing must include its final syllable");
        require(backing.backgroundAt(1.9).isEmpty(), "Backing vocals cannot appear before their start");
        require(backing.backgroundAt(2.7).size() == 2, "Overlapping background voices must be preserved");
        require(backing.backgroundAt(2.2).getFirst().text().equals("backing voice"), "Backing syllables must merge like lead syllables");
        require(backing.backgroundAt(3.1).getFirst().activeWord(3.1) == 1, "Backing words use their own timestamps");
        require(backing.backgroundAt(4.2).size() == 1, "A short tail allows a smooth backing-vocal exit");
        require(backing.backgroundAt(4.5).isEmpty(), "Backing vocals must clear after their exit tail");
        require(backing.backgroundAt(6.1).getFirst().text().equals("solo"), "Background-only vocal groups must not be lost");
        require(backing.backgroundAt(1).isEmpty(), "Seeking back must clear later backing vocals");
        require(backing.backgroundAt(2.2).getFirst().words().getFirst().syllables().size() == 2, "Backing words must retain their syllable animation");
        LyricsTimeline cut = parse("""
            {"Body":{"Type":"Syllable","Content":[{"Type":"Vocal","Lead":{"Syllables":[
                {"Text":"he","StartTime":1,"EndTime":1.2,"IsPartOfWord":true},
                {"Text":"'re","StartTime":1.8,"EndTime":2.2} ]}}]}}
            """);
        var cutWord = cut.lines().getFirst().words().getFirst();
        require(cutWord.text().equals("he're"), "Keep supplied punctuation without inserting spaces inside a word");
        require(cutWord.syllables().size() == 2, "A timed cut cannot be flattened into a single interval");
        require(Math.abs(cutWord.progress(1.4) - 0.4) < 0.00001 && cutWord.progress(1.4) == cutWord.progress(1.7), "The sweep must pause during a cut between syllables");
        require(cutWord.syllables().getLast().progress(1.7) == 0, "The second syllable cannot light before its timestamp");
        require(cutWord.progress(2) > 0.4 && cutWord.progress(2) < 1, "The sweep must resume through the second syllable");

        require(LyricMotion.fittedWidth(70, 360, 11, 1) == 112, "A short phrase needs only a compact minimum width");
        require(LyricMotion.fittedWidth(200, 360, 11, 1) == 222, "Ordinary phrases must fit their actual text width");
        require(LyricMotion.fittedWidth(900, 360, 11, 1) == 360, "Long phrases must stop growing at the configured maximum");
        require(LyricMotion.fittedWidth(900, 230, 11, 1) == 230, "The pill must respect the remaining screen width");
        var backingLine = backing.backgroundAt(2.2).getFirst();
        require(LyricMotion.backingPresence(backingLine, 1.9) == 0, "Backing rows must reserve no space before their vocal");
        require(LyricMotion.backingPresence(backingLine, 2.2) == 1, "A singing backing voice gets its readable row height");
        require(Math.abs(LyricMotion.backingPresence(backingLine, 4.175) - 0.5) < 0.001, "Backing text and row space fade together");
        require(LyricMotion.backingPresence(backingLine, 4.4) == 0, "Backing rows must fully collapse during instrumentals");
        require(LyricMotion.activity(0.9, 1, 2) == 0 && LyricMotion.activity(2.3, 1, 2) == 0, "Glow must not appear before or linger after its syllable");
        require(LyricMotion.activity(1.5, 1, 2) == 1, "Held syllables keep a steady glow without scaling text");

        var longLine = new LyricsTimeline.Line("one two three", 0, 6, java.util.List.of(
            new LyricsTimeline.Word("one", 0, 2), new LyricsTimeline.Word("two", 2, 4), new LyricsTimeline.Word("three", 4, 6)));
        double[] starts = {0, 110, 220}, widths = {100, 100, 100};
        require(LyricsScroll.target(longLine, 0.5, starts, widths, 180, 320) == 0, "A line starts at its natural size and left edge");
        double a = LyricsScroll.target(longLine, 2.5, starts, widths, 180, 320);
        double b = LyricsScroll.target(longLine, 2.6, starts, widths, 180, 320);
        require(b > a && b - a < 10, "Scrolling must progress within a word, not jump by word count");
        require(LyricsScroll.target(longLine, 6, starts, widths, 180, 320) == 140, "The final word must fit within the viewport");
        require(LyricsScroll.target(longLine, 4, starts, widths, 400, 320) == 0, "Short lines must never scroll");
        double at30 = 0, at144 = 0;
        for (int i = 0; i < 30; i++) at30 = LyricsScroll.advance(at30, 100, 1.0 / 30);
        for (int i = 0; i < 144; i++) at144 = LyricsScroll.advance(at144, 100, 1.0 / 144);
        require(Math.abs(at30 - at144) < 0.00001, "Scrolling must settle identically at different frame rates");
        require(LyricsScroll.advance(42, 100, 0) == 42, "A zero-time frame cannot move the viewport");
        artworkChecks();
        LyricsDetailsChecks.run();
        LyricsLanguageChecks.run();
        meteordevelopment.meteorclient.utils.render.GlyphClipChecks.run();
        DesktopMusicChecks.run();
        System.out.println("Lyrics checks passed: desktop playback, recording search, LRC parsing, playback, syllable cuts, fitted width, collapsing backing rows, scrolling, artwork accent, color transitions and timed/hover attribution.");
    }

    private static void artworkChecks() {
        var image = new java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) image.setRGB(x, y, x < 70 ? 0xFFE04040 : 0xFF3050D0);
        int rgb = ArtworkAccent.extract(image);
        require(((rgb >> 16) & 255) > (rgb & 255) + 60, "The dominant red region must beat a smaller blue region");
        for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) image.setRGB(x, y, x < 90 ? 0xFFFFFFFF : 0xFF248050);
        rgb = ArtworkAccent.extract(image);
        require(((rgb >> 8) & 255) > ((rgb >> 16) & 255) + 50, "A white artwork border must not wash out its colored subject");
        for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) image.setRGB(x, y, 0xFF202020);
        rgb = ArtworkAccent.extract(image);
        var hsv = java.awt.Color.RGBtoHSB((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, null);
        require(hsv[1] < 0.05 && hsv[2] >= 0.79, "Monochrome covers need a readable neutral accent");
        for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) image.setRGB(x, y, x < 90 ? 0x003030D0 : 0xFFE04040);
        rgb = ArtworkAccent.extract(image);
        require(((rgb >> 16) & 255) > (rgb & 255) + 60, "Transparent pixels cannot choose the artwork color");
        String hash = "0123456789abcdef0123456789abcdef0123456789";
        require(ArtworkAccent.normalizeUrl("spotify:image:" + hash).equals("https://i.scdn.co/image/" + hash), "Spicetify artwork URIs must resolve to the Spotify image CDN");
        require(ArtworkAccent.normalizeUrl("http://127.0.0.1/image").isEmpty() && ArtworkAccent.normalizeUrl("https://example.com/image").isEmpty(), "Only Spotify CDN artwork should be fetched");
        require(ArtworkAccent.normalizeUrl("https://i.scdn.co@127.0.0.1/image").isEmpty(), "URL user info cannot bypass the artwork host check");
        var transition = new ArtworkAccent.Transition();
        require(transition.sample(0x102030, 0) == 0x102030, "The initial manual accent must stay exact");
        require(transition.sample(0xE08040, 0) == 0x102030, "A new artwork color cannot jump on the first frame");
        int middle = transition.sample(0xE08040, 120_000_000);
        require(middle != 0x102030 && middle != 0xE08040, "Artwork colors must blend through intermediate colors");
        require(transition.sample(0xE08040, 10_000_000_000L) == 0xE08040, "The accent must reach its chosen artwork color");
        var low = new ArtworkAccent.Transition(); var high = new ArtworkAccent.Transition();
        low.sample(0, 0); high.sample(0, 0);
        int lowRgb = 0, highRgb = 0;
        for (int i = 1; i <= 30; i++) lowRgb = low.sample(0xF08040, i * 1_000_000_000L / 30);
        for (int i = 1; i <= 144; i++) highRgb = high.sample(0xF08040, i * 1_000_000_000L / 144);
        require(lowRgb == highRgb, "Artwork color transitions must be independent of FPS");
    }

    private static LyricsTimeline parse(String json) { return LyricsTimeline.parse(JsonParser.parseString(json).getAsJsonObject()); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
