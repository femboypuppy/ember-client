package meteordevelopment.meteorclient.utils.misc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Spicy Lyrics' documented seconds-based timing, without fabricated word timestamps. */
public record LyricsTimeline(String type, List<Line> lines, List<Credit> credits) {
    public record Word(String text, double start, double end) {}
    public record Line(String text, double start, double end, List<Word> words) {
        public int activeWord(double seconds) {
            for (int i = 0; i < words.size(); i++) {
                Word word = words.get(i);
                if (seconds >= word.start && seconds < word.end) return i;
            }
            return -1;
        }
    }
    public record Credit(String name, String url) {}

    public Line at(double seconds) {
        int low = 0, high = lines.size() - 1, candidate = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).start <= seconds) { candidate = mid; low = mid + 1; }
            else high = mid - 1;
        }
        // Overlapping lead vocals can outlast the line that started after them.
        for (int i = candidate; i >= 0; i--) {
            Line line = lines.get(i);
            if (seconds < line.end) return line;
        }
        return null;
    }

    public static LyricsTimeline parse(JsonObject envelope) {
        JsonObject body = envelope.getAsJsonObject("Body");
        if (body == null) throw new IllegalArgumentException("Missing lyric body");
        String type = text(body, "Type");
        List<Line> lines = new ArrayList<>();
        JsonArray content = body.has("Content") && body.get("Content").isJsonArray() ? body.getAsJsonArray("Content") : new JsonArray();
        if (content.size() > 5000) throw new IllegalArgumentException("Too many lyric lines");
        for (JsonElement element : content) {
            if (!element.isJsonObject()) continue;
            JsonObject line = element.getAsJsonObject();
            if (!text(line, "Type").equals("Vocal")) continue;
            if (type.equals("Syllable")) {
                if (!line.has("Lead") || !line.get("Lead").isJsonObject()) continue;
                JsonObject lead = line.getAsJsonObject("Lead");
                if (!lead.has("Syllables") || !lead.get("Syllables").isJsonArray()) continue;
                JsonArray syllables = lead.getAsJsonArray("Syllables");
                if (syllables.size() > 2000) continue;
                List<Word> words = new ArrayList<>();
                StringBuilder word = new StringBuilder();
                double wordStart = -1, wordEnd = -1;
                for (JsonElement syllable : syllables) {
                    if (!syllable.isJsonObject()) continue;
                    JsonObject item = syllable.getAsJsonObject();
                    double start = number(item, "StartTime"), end = number(item, "EndTime");
                    String value = text(item, "Text").strip();
                    if (!valid(start, end) || value.isEmpty()) continue;
                    if (wordStart < 0) wordStart = start;
                    wordEnd = Math.max(wordEnd, end);
                    word.append(value);
                    if (!item.has("IsPartOfWord") || !item.get("IsPartOfWord").getAsBoolean()) {
                        words.add(new Word(word.toString(), wordStart, wordEnd));
                        word.setLength(0);
                        wordStart = wordEnd = -1;
                    }
                }
                if (!word.isEmpty()) words.add(new Word(word.toString(), wordStart, wordEnd));
                words.sort(Comparator.comparingDouble(Word::start));
                if (words.isEmpty()) continue;
                String joined = String.join(" ", words.stream().map(Word::text).toList());
                double start = number(lead, "StartTime"), end = number(lead, "EndTime");
                if (!valid(start, end)) {
                    start = words.getFirst().start;
                    end = words.stream().mapToDouble(Word::end).max().orElse(start);
                }
                if (valid(start, end)) lines.add(new Line(joined, start, end, List.copyOf(words)));
            } else if (type.equals("Line")) {
                double start = number(line, "StartTime"), end = number(line, "EndTime");
                String value = text(line, "Text").strip();
                if (valid(start, end) && !value.isEmpty()) lines.add(new Line(value, start, end, List.of()));
            }
        }
        lines.sort(Comparator.comparingDouble(Line::start));
        List<Credit> credits = new ArrayList<>();
        String source = text(body, "source");
        credits.add(switch (source) {
            case "spicy_lyrics" -> new Credit("Spicy Lyrics", "https://spicylyrics.org");
            case "apple_music" -> new Credit("Apple Music", "https://music.apple.com");
            case "spotify" -> new Credit("Spotify", "https://open.spotify.com");
            default -> new Credit("Lyrics via Spicy Lyrics", "https://spicylyrics.org");
        });
        if (source.equals("spicy_lyrics") && body.has("UploadAttribution") && body.get("UploadAttribution").isJsonObject()) {
            JsonObject attribution = body.getAsJsonObject("UploadAttribution");
            addCredit(credits, attribution, "Uploader", "Uploaded by ");
            addCredit(credits, attribution, "Maker", "Synced by ");
        }
        return new LyricsTimeline(type, List.copyOf(lines), List.copyOf(credits));
    }

    private static void addCredit(List<Credit> credits, JsonObject attribution, String key, String prefix) {
        if (!attribution.has(key) || !attribution.get(key).isJsonObject()) return;
        JsonObject contributor = attribution.getAsJsonObject(key);
        String name = text(contributor, "username");
        String id = text(contributor, "id");
        if (!name.isBlank()) credits.add(new Credit(prefix + name,
            id.matches("[0-9]+") ? "https://spicylyrics.org/uid/" + id : "https://spicylyrics.org"));
    }

    private static boolean valid(double start, double end) {
        return Double.isFinite(start) && Double.isFinite(end) && start >= 0 && end > start;
    }
    private static double number(JsonObject value, String key) {
        try { return value.has(key) ? value.get(key).getAsDouble() : -1; }
        catch (RuntimeException ignored) { return -1; }
    }
    private static String text(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : "";
    }
}
