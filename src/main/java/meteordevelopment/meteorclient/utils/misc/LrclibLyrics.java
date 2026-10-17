package meteordevelopment.meteorclient.utils.misc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative recording matching and real LRC timestamps; never synthesizes word timing. */
public final class LrclibLyrics {
    private static final Pattern COSMETIC = Pattern.compile("(?i)[(\\[](?:official\\s+)?(?:music\\s+video|video|audio|lyric(?:s|\\s+video)?|visuali[sz]er|hd|4k)[)\\]]");
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):([0-5]\\d(?:[.,]\\d{1,3})?)\\]");
    private static final Pattern OFFSET = Pattern.compile("(?im)\\[offset:([+-]?\\d{1,8})\\]");
    public record Query(String title, String artist, long durationMs) {}
    private record Entry(String text, double seconds) {}

    private LrclibLyrics() {}

    public static Query query(String title, String artist, long durationMs) {
        String cleanTitle = COSMETIC.matcher(title).replaceAll("").strip();
        String cleanArtist = artist.replaceFirst("(?i)\\s*-\\s*Topic$", "").strip();
        String[] split = cleanTitle.split("\\s+[–—-]\\s+", 2);
        if (split.length == 2 && (cleanArtist.isBlank() || normalize(split[0]).equals(normalize(cleanArtist))
            || cleanArtist.toLowerCase(Locale.ROOT).endsWith("vevo"))) {
            cleanArtist = split[0].strip(); cleanTitle = split[1].strip();
        }
        return new Query(cleanTitle, cleanArtist, durationMs);
    }

    public static boolean matches(String title, String artist, long duration, String otherTitle, String otherArtist, long otherDuration) {
        Query a = query(title, artist, duration), b = query(otherTitle, otherArtist, otherDuration);
        String an = normalize(a.artist), bn = normalize(b.artist);
        if (an.isEmpty() || bn.isEmpty() || normalize(a.title).isEmpty()) return false;
        // Multiple credited artists are common, but a common substring is not an artist match.
        boolean sameArtist = an.equals(bn) || an.length() >= 3 && (" " + bn + " ").contains(" " + an + " ")
            || bn.length() >= 3 && (" " + an + " ").contains(" " + bn + " ");
        return sameArtist && normalize(a.title).equals(normalize(b.title))
            && (duration <= 0 || otherDuration <= 0 || Math.abs(duration - otherDuration) <= 3000);
    }

    public static LyricsTimeline select(JsonArray records, Query query) {
        LyricsTimeline chosen = null;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < Math.min(100, records.size()); i++) {
            if (!records.get(i).isJsonObject()) continue;
            JsonObject row = records.get(i).getAsJsonObject();
            try {
                double seconds = row.get("duration").getAsDouble();
                if (!Double.isFinite(seconds) || seconds < 0 || seconds > 86400) continue;
                long duration = Math.round(seconds * 1000);
                if (!matches(query.title, query.artist, query.durationMs, text(row, "trackName"), text(row, "artistName"), duration)) continue;
                double distance = query.durationMs > 0 ? Math.abs(query.durationMs - duration) : 0;
                if (distance >= best) continue;
                LyricsTimeline timeline = parseLrc(text(row, "syncedLyrics"), seconds);
                if (timeline.lines().isEmpty()) continue;
                best = distance; chosen = timeline;
            } catch (RuntimeException ignored) { /* A malformed candidate does not hide valid matches. */ }
        }
        return chosen;
    }

    public static LyricsTimeline parseLrc(String source, double duration) {
        if (source.length() > 1_000_000) throw new IllegalArgumentException("Lyrics too large");
        double offset = 0;
        var offsetMatch = OFFSET.matcher(source);
        while (offsetMatch.find()) offset = Long.parseLong(offsetMatch.group(1)) / 1000.0;
        List<Entry> entries = new ArrayList<>();
        for (String row : source.split("\\R")) {
            var stamp = STAMP.matcher(row);
            List<Double> starts = new ArrayList<>();
            int end = 0;
            while (stamp.find()) {
                starts.add(Math.max(0, Integer.parseInt(stamp.group(1)) * 60 + Double.parseDouble(stamp.group(2).replace(',', '.')) - offset));
                end = stamp.end();
            }
            String text = row.substring(end).strip();
            for (double start : starts) entries.add(new Entry(text, start));
            if (entries.size() > 5000) throw new IllegalArgumentException("Too many lyric lines");
        }
        entries.sort(Comparator.comparingDouble(Entry::seconds));
        List<LyricsTimeline.Line> lines = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            String value = entry.text;
            while (i + 1 < entries.size() && entries.get(i + 1).seconds == entry.seconds) {
                String other = entries.get(++i).text;
                if (!other.isEmpty() && !other.equals(value)) value = value.isEmpty() ? other : value + " / " + other;
            }
            if (value.isEmpty()) continue;
            double end = i + 1 < entries.size() ? entries.get(i + 1).seconds : duration;
            if (end <= entry.seconds) continue;
            lines.add(new LyricsTimeline.Line(value, entry.seconds, end, List.of()));
        }
        return new LyricsTimeline("Line", List.copyOf(lines), List.of(),
            List.of(new LyricsTimeline.Credit("LRCLIB · line sync", "https://lrclib.net")));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
    private static String text(JsonObject row, String key) {
        return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsString() : "";
    }
}
