package meteordevelopment.meteorclient.utils.misc;

import com.google.gson.JsonParser;

final class LyricsLanguageChecks {
    static void run() {
        var envelope = JsonParser.parseString("""
            {"Body":{"Type":"Syllable","Content":[
              {"Type":"Vocal","Lead":{"TransliteratedText":"whole phrase","Syllables":[
                {"Text":"ね","TransliteratedText":"ne","StartTime":1,"EndTime":1.5,"IsPartOfWord":true},
                {"Text":"こ","TransliteratedText":"ko","StartTime":1.5,"EndTime":2}]},
               "Background":[{"Syllables":[
                 {"Text":"하","TransliteratedText":"ha","StartTime":1,"EndTime":1.5,"IsPartOfWord":true},
                 {"Text":"늘","TransliteratedText":"neul","StartTime":1.5,"EndTime":2}]}]},
              {"Type":"Vocal","Lead":{"Syllables":[{"Text":"星","StartTime":3,"EndTime":4}]}},
              {"Type":"Vocal","Lead":{"TransliteratedText":"sora","Syllables":[{"Text":"空","StartTime":5,"EndTime":6}]}}
            ]}}
            """).getAsJsonObject();
        var nativeText = LyricsTimeline.parse(envelope);
        var roman = LyricsTimeline.parse(envelope, true);
        require(nativeText.lines().getFirst().text().equals("ねこ"), "Preserve Japanese text by default");
        require(nativeText.background().getFirst().text().equals("하늘"), "Preserve Korean backing vocals");
        require(roman.lines().getFirst().text().equals("neko"), "Prefer per-syllable romanization to a whole phrase");
        require(roman.background().getFirst().text().equals("haneul"), "Romanize backing vocals using the same option");
        require(roman.lines().getFirst().words().getFirst().syllables().getLast().start() == 1.5,
            "Romanization must retain original syllable clocks");
        require(roman.lines().get(1).text().equals("星"), "Missing romanization must fall back to the original script");
        require(roman.lines().get(2).text().equals("sora") && roman.lines().get(2).words().isEmpty(),
            "Whole-phrase romanization must not fabricate syllable timings");
        require(nativeText.lines().get(2).text().equals("空"), "Selecting romanization must not mutate the native timeline");

        var lineOnly = LyricsTimeline.parse(JsonParser.parseString("""
            {"Body":{"Type":"Line","Content":[{"Type":"Vocal","Text":"하늘 — 星","TransliteratedText":"haneul — hoshi","StartTime":1,"EndTime":3}]}}
            """).getAsJsonObject(), true);
        require(lineOnly.lines().getFirst().text().equals("haneul — hoshi"), "Line-synced romanization must preserve punctuation");

        var punctuation = LyricsTimeline.parse(JsonParser.parseString("""
            {"Body":{"Type":"Syllable","Content":[{"Type":"Vocal","Lead":{"Syllables":[
              {"Text":"(","StartTime":1,"EndTime":1},
              {"Text":"we","StartTime":1,"EndTime":1.4},
              {"Text":"'","StartTime":1.4,"EndTime":1.4,"IsPartOfWord":true},
              {"Text":"re","StartTime":1.8,"EndTime":2},
              {"Text":"well","StartTime":2,"EndTime":2.5},
              {"Text":"-","IsPartOfWord":true},
              {"Text":"timed","StartTime":2.5,"EndTime":3},
              {"Text":")","StartTime":3,"EndTime":3},
              {"Text":"next","StartTime":4,"EndTime":5},
              {"Text":"—","StartTime":5,"EndTime":5,"IsPartOfWord":false},
              {"Text":"word","StartTime":6,"EndTime":7}
            ]}}]}}
            """).getAsJsonObject());
        var phrase = punctuation.lines().getFirst();
        require(phrase.text().equals("(we're well-timed) next— word"), "Zero-duration apostrophes, hyphens and surrounding punctuation cannot disappear");
        require(phrase.words().getFirst().syllables().size() == 2, "Punctuation must not add fake syllables");
        require(phrase.words().getFirst().syllables().getFirst().end() == 1.4
            && phrase.words().getFirst().syllables().getLast().start() == 1.8, "Punctuation cannot fill a real vocal timing gap");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
