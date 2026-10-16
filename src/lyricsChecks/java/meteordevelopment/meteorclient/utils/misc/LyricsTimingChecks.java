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
        System.out.println("Lyrics checks passed: millisecond timing, pause, seek, gaps, syllables, source attribution, and sync fallbacks.");
    }

    private static LyricsTimeline parse(String json) { return LyricsTimeline.parse(JsonParser.parseString(json).getAsJsonObject()); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
