package meteordevelopment.meteorclient.utils.misc;

import com.google.gson.JsonParser;

/** Synthetic timings: no copyrighted lyric fixtures or live credentials are needed. */
public final class LyricsTimingChecks {
    public static void main(String[] args) {
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
        System.out.println("Lyrics checks passed: playback, held phrases, seeking, backing vocals, continuous scrolling, frame-rate independence and attribution.");
    }

    private static LyricsTimeline parse(String json) { return LyricsTimeline.parse(JsonParser.parseString(json).getAsJsonObject()); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
