package meteordevelopment.meteorclient.utils.misc;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.net.URI;

/** Small, population-weighted artwork palette with readable UI brightness. No audio analysis. */
public final class ArtworkAccent {
    private ArtworkAccent() {}

    public static int extract(BufferedImage image) {
        int[] count = new int[36 * 4 * 4];
        long[] red = new long[count.length], green = new long[count.length], blue = new long[count.length];
        double[] scores = new double[count.length];
        float[] hsv = new float[3];
        int chromatic = 0, samples = 0;
        int sx = Math.max(1, image.getWidth() / 64), sy = Math.max(1, image.getHeight() / 64);
        for (int y = 0; y < image.getHeight(); y += sy) for (int x = 0; x < image.getWidth(); x += sx) {
            int rgb = image.getRGB(x, y);
            if ((rgb >>> 24) < 128) continue;
            int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            Color.RGBtoHSB(r, g, b, hsv); samples++;
            if (hsv[2] < 0.12 || (hsv[2] > 0.97 && hsv[1] < 0.12)) continue;
            if (hsv[1] >= 0.18) chromatic++;
            int bin = Math.min(35, (int) (hsv[0] * 36)) * 16 + Math.min(3, (int) (hsv[1] * 4)) * 4 + Math.min(3, (int) (hsv[2] * 4));
            count[bin]++; red[bin] += r; green[bin] += g; blue[bin] += b;
            scores[bin] += (0.35 + hsv[1] * 0.65) * (0.45 + hsv[2] * 0.55);
        }
        boolean preferColor = chromatic >= Math.max(1, samples * 0.02);
        int best = -1;
        double bestScore = -1;
        for (int i = 0; i < count.length; i++) {
            if (count[i] == 0) continue;
            Color.RGBtoHSB((int) (red[i] / count[i]), (int) (green[i] / count[i]), (int) (blue[i] / count[i]), hsv);
            if (preferColor && hsv[1] < 0.18) continue;
            if (scores[i] > bestScore) { best = i; bestScore = scores[i]; }
        }
        if (best < 0) return 0xD0D0D0;
        Color.RGBtoHSB((int) (red[best] / count[best]), (int) (green[best] / count[best]), (int) (blue[best] / count[best]), hsv);
        float saturation = hsv[1] < 0.1 ? 0 : Math.clamp(hsv[1], 0.28f, 0.78f);
        return Color.HSBtoRGB(hsv[0], saturation, Math.clamp(hsv[2], 0.80f, 0.95f)) & 0xFFFFFF;
    }

    public static String normalizeUrl(String source) {
        if (source == null || source.length() > 2048) return "";
        if (source.matches("spotify:image:[0-9a-fA-F]{32,80}")) source = "https://i.scdn.co/image/" + source.substring(14);
        try {
            URI uri = URI.create(source);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                && (host.equals("scdn.co") || host.endsWith(".scdn.co")) && uri.getUserInfo() == null
                && uri.getPort() == -1 && uri.getFragment() == null ? uri.toASCIIString() : "";
        } catch (IllegalArgumentException ignored) { return ""; }
    }

    /** Keep floating-point state so a slow color transition cannot stall on rounded channels. */
    public static final class Transition {
        private double r, g, b;
        private long previous = Long.MIN_VALUE;
        public int sample(int target, long now) {
            int tr = (target >> 16) & 255, tg = (target >> 8) & 255, tb = target & 255;
            if (previous == Long.MIN_VALUE) { r = tr; g = tg; b = tb; }
            double amount = previous == Long.MIN_VALUE ? 1 : 1 - Math.exp(-Math.max(0, (now - previous) / 1e9) / 0.24);
            r += (tr - r) * amount; g += (tg - g) * amount; b += (tb - b) * amount;
            previous = now;
            return ((int) Math.round(r) << 16) | ((int) Math.round(g) << 8) | (int) Math.round(b);
        }
    }
}
