package meteordevelopment.meteorclient.utils.misc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Spicy Lyrics' documented seconds-based timing, without fabricated word timestamps. */
public record LyricsTimeline(String type, List<Line> lines, List<Line> background, List<Credit> credits) {
    public record Syllable(String text, double start, double end) {
        public double progress(double seconds) { return Math.clamp((seconds - start) / Math.max(0.001, end - start), 0, 1); }
    }
    public record Word(String text, double start, double end, List<Syllable> syllables) {
        public Word { syllables = List.copyOf(syllables); }
        public Word(String text, double start, double end) { this(text, start, end, List.of(new Syllable(text, start, end))); }

        /** Keep pauses and independently timed cuts inside a single written word. */
        public double progress(double seconds) {
            double filled = 0, total = 0;
            for (Syllable part : syllables) {
                int length = part.text.codePointCount(0, part.text.length());
                filled += length * part.progress(seconds); total += length;
            }
            return total == 0 ? 0 : filled / total;
        }
    }
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

    /** Keep the last lead phrase during breaths and instrumentals; seeking remains stateless. */
    public Line displayAt(double seconds) {
        int low = 0, high = lines.size() - 1, candidate = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).start <= seconds) { candidate = mid; low = mid + 1; }
            else high = mid - 1;
        }
        Line active = at(seconds);
        return active != null ? active : candidate < 0 ? null : lines.get(candidate);
    }

    public List<Line> backgroundAt(double seconds) {
        return background.stream().filter(line -> seconds >= line.start && seconds < line.end + 0.45).toList();
    }

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
        List<Line> background = new ArrayList<>();
        JsonArray content = body.has("Content") && body.get("Content").isJsonArray() ? body.getAsJsonArray("Content") : new JsonArray();
        if (content.size() > 5000) throw new IllegalArgumentException("Too many lyric lines");
        for (JsonElement element : content) {
            if (!element.isJsonObject()) continue;
            JsonObject line = element.getAsJsonObject();
            if (!text(line, "Type").equals("Vocal")) continue;
            if (type.equals("Syllable")) {
                if (line.has("Lead") && line.get("Lead").isJsonObject()) addSyllables(lines, line.getAsJsonObject("Lead"));
                if (line.has("Background") && line.get("Background").isJsonArray()) {
                    JsonArray backing = line.getAsJsonArray("Background");
                    for (int i = 0; i < Math.min(32, backing.size()); i++) {
                        if (backing.get(i).isJsonObject()) addSyllables(background, backing.get(i).getAsJsonObject());
                    }
                }
            } else if (type.equals("Line")) {
                double start = number(line, "StartTime"), end = number(line, "EndTime");
                String value = text(line, "Text").strip();
                if (valid(start, end) && !value.isEmpty()) lines.add(new Line(value, start, end, List.of()));
            }
        }
        lines.sort(Comparator.comparingDouble(Line::start));
        background.sort(Comparator.comparingDouble(Line::start));
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
        return new LyricsTimeline(type, List.copyOf(lines), List.copyOf(background), List.copyOf(credits));
    }

    private static void addSyllables(List<Line> target, JsonObject vocal) {
        if (!vocal.has("Syllables") || !vocal.get("Syllables").isJsonArray()) return;
        JsonArray syllables = vocal.getAsJsonArray("Syllables");
        if (syllables.size() > 2000) return;
        List<Word> words = new ArrayList<>();
        List<Syllable> parts = new ArrayList<>();
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
            parts.add(new Syllable(value, start, end));
            if (!item.has("IsPartOfWord") || !item.get("IsPartOfWord").getAsBoolean()) {
                words.add(new Word(word.toString(), wordStart, wordEnd, parts));
                word.setLength(0);
                parts.clear();
                wordStart = wordEnd = -1;
            }
        }
        if (!word.isEmpty()) words.add(new Word(word.toString(), wordStart, wordEnd, parts));
        words.sort(Comparator.comparingDouble(Word::start));
        if (words.isEmpty()) return;
        // Some catalogues end the group before the last syllable; never drop that word.
        double start = words.getFirst().start;
        double end = words.stream().mapToDouble(Word::end).max().orElse(start);
        double groupStart = number(vocal, "StartTime"), groupEnd = number(vocal, "EndTime");
        if (valid(groupStart, groupEnd)) { start = Math.min(start, groupStart); end = Math.max(end, groupEnd); }
        target.add(new Line(String.join(" ", words.stream().map(Word::text).toList()), start, end, List.copyOf(words)));
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
