package meteordevelopment.meteorclient.utils.render;

import java.util.List;
import java.util.function.ToDoubleFunction;

/** Shared layout for projected nametags and their settings preview, in unscaled tag units. */
public final class NametagCardLayout {
    public record Box(double x, double y, double width, double height) {}
    public record Layout(double width, double height, Box avatar, Box name, Box health, Box details, Box bar) {}
    private NametagCardLayout() {}

    public static Layout create(double nameWidth, double healthWidth, List<Double> chips, double lineHeight,
                                boolean avatar, boolean healthBar, boolean compact, double maxWidth) {
        double pad = compact ? 7 : 9;
        double head = avatar && !compact ? 30 : 0;
        double textX = pad + (head > 0 ? head + 8 : 0);
        double hpWidth = healthWidth > 0 ? healthWidth + 12 : 0;
        double chipWidth = chips.stream().mapToDouble(w -> w + 10).sum() + Math.max(0, chips.size() - 1) * 4;
        double content = Math.max(nameWidth + (hpWidth > 0 ? hpWidth + 10 : 0), chipWidth);
        double width = Math.clamp(textX + content + pad, Math.min(maxWidth, compact ? 96 : 144), maxWidth);
        double rowH = Math.max(lineHeight, hpWidth > 0 ? 20 : 0);
        double detailH = chips.isEmpty() ? 0 : lineHeight * .58 + 7;
        double barH = healthBar && !compact ? 3 : 0;
        double contentH = rowH + (detailH > 0 ? detailH + 5 : 0) + (barH > 0 ? barH + 7 : 0);
        double height = Math.max(head, contentH) + pad * 2;
        double y = -height, contentY = y + (height - contentH) / 2;
        double nameAvailable = Math.max(1, width - textX - pad - (hpWidth > 0 ? hpWidth + 10 : 0));
        return new Layout(width, height,
            new Box(-width / 2 + pad, y + (height - head) / 2, head, head),
            new Box(-width / 2 + textX, contentY + (rowH - lineHeight) / 2, nameAvailable, lineHeight),
            new Box(width / 2 - pad - hpWidth, contentY + (rowH - 20) / 2, hpWidth, 20),
            new Box(-width / 2 + textX, contentY + rowH + 5, width - textX - pad, detailH),
            new Box(-width / 2 + textX, y + height - pad - barH, width - textX - pad, barH));
    }

    public static String fit(String value, double width, ToDoubleFunction<String> measure) {
        if (measure.applyAsDouble(value) <= width) return value;
        String suffix = "...";
        if (measure.applyAsDouble(suffix) > width) return "";
        int[] points = value.codePoints().toArray();
        int lo = 0, hi = points.length;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (measure.applyAsDouble(new String(points, 0, mid) + suffix) <= width) lo = mid;
            else hi = mid - 1;
        }
        return new String(points, 0, lo) + suffix;
    }
}
