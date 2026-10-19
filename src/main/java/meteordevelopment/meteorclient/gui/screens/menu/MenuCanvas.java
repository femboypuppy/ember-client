package meteordevelopment.meteorclient.gui.screens.menu;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Native GUI commands only. Minecraft owns texture uploads, text atlases, batching and clipping.
 * Shapes are placed on the window's physical pixel grid (not the coarser GUI-scaled grid), and
 * rounded edges, shadows and icons come from linear-filtered mask textures, so the menu stays
 * smooth at 1440p, 4K or any GUI scale.
 */
public final class MenuCanvas {
    private record Label(Text text, int width) {}
    private static final Style BODY = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of("ember", "ui")));
    private static final Style CLOCK = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of("ember", "clock")));
    // Quarter-disc masks, one per size, laid out left to right with a 1px gutter (scripts/generate_menu_assets.py).
    private static final int[] CORNER_SIZES = {4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128};
    private static final int[] CORNER_U = new int[CORNER_SIZES.length];
    private static final int CORNER_ATLAS_W, CORNER_ATLAS_H = 130;
    static { int u = 0; for (int i = 0; i < CORNER_SIZES.length; i++) { CORNER_U[i] = u; u += CORNER_SIZES[i] + 2; } CORNER_ATLAS_W = u; }
    private static final Identifier CORNERS = Identifier.of("ember", "textures/menu/ui/corners.png");
    // Blurred rounded rectangle: edge 80px in from each side, 48px corner radius, sliced into nine parts.
    private static final Identifier SHADOW = Identifier.of("ember", "textures/menu/ui/shadow.png");
    private static final int SHADOW_SIZE = 256, SHADOW_HALF = 128;
    private static final double SHADOW_INSET = 80, SHADOW_RADIUS = 48;
    // Texture columns/rows per slice: corners use whole quadrants, edges stretch the two middle texels.
    private static final int[] SHADOW_U = {0, SHADOW_HALF - 1, SHADOW_HALF}, SHADOW_SPAN = {SHADOW_HALF, 2, SHADOW_HALF};
    private static final Identifier[] ICONS = new Identifier[Dock.ACTIONS.length], SMALL_ICONS = new Identifier[Dock.ACTIONS.length];
    static {
        for (Dock.Action action : Dock.ACTIONS) {
            String name = action.name().toLowerCase(java.util.Locale.ROOT);
            ICONS[action.ordinal()] = Identifier.of("ember", "textures/menu/icon/" + name + ".png");
            SMALL_ICONS[action.ordinal()] = Identifier.of("ember", "textures/menu/icon/" + name + "_64.png");
        }
    }
    /** Virtual texture size: the int region API then addresses UVs in 1/65536 steps. */
    private static final int UV = 65536;
    private final Map<String, Label> bodyCache = new HashMap<>(), clockCache = new HashMap<>();
    public DrawContext context;
    public double opacity = 1;
    // Local -> physical pixel transform, valid between begin() and end().
    private double sx = 1, sy = 1, ox, oy;
    private boolean device;
    private final int[] shadowX = new int[4], shadowY = new int[4];

    public void reset() { bodyCache.clear(); clockCache.clear(); }

    /** Switch to a physical-pixel matrix for the current local transform. Rotated transforms keep local units. */
    private void begin() {
        // Minecraft orders overlapping GUI elements by bounds rounded to the coarse GUI grid, which can miss
        // sub-GUI-pixel overlaps between physical-pixel slices. Each primitive's pieces are disjoint, so a
        // root layer per primitive keeps exact painter's order.
        context.createNewRootLayer();
        var m = context.getMatrices();
        device = Math.abs(m.m01()) < 1e-5 && Math.abs(m.m10()) < 1e-5;
        if (!device) { sx = sy = 1; ox = oy = 0; return; }
        double f = MinecraftClient.getInstance().getWindow().getScaleFactor();
        sx = m.m00() * f; sy = m.m11() * f; ox = m.m20() * f; oy = m.m21() * f;
        m.pushMatrix(); m.identity(); m.scale((float) (1 / f));
    }
    private void end() { if (device) context.getMatrices().popMatrix(); device = false; }
    private int px(double x) { return (int) Math.round(x * sx + ox); }
    private int py(double y) { return (int) Math.round(y * sy + oy); }
    private double scale() { return Math.min(sx, sy); }
    private int color(int color) { return MenuTheme.alpha(color, opacity); }

    public void rect(double x, double y, double w, double h, int color) {
        int c = color(color);
        if (w <= 0 || h <= 0 || c >>> 24 == 0) return;
        begin();
        int x0 = px(x), y0 = py(y), x1 = Math.max(x0 + 1, px(x + w)), y1 = Math.max(y0 + 1, py(y + h));
        context.fill(x0, y0, x1, y1, c);
        end();
    }
    /** A one-physical-pixel horizontal line, used for glass highlights. */
    public void hairline(double x, double y, double w, int color) {
        int c = color(color);
        if (w <= 0 || c >>> 24 == 0) return;
        begin(); int x0 = px(x), y0 = py(y); context.fill(x0, y0, Math.max(x0 + 1, px(x + w)), y0 + 1, c); end();
    }
    public void gradient(double x, double y, double w, double h, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        begin(); context.fillGradient(px(x), py(y), px(x + w), py(y + h), color(top), color(bottom)); end();
    }
    public void round(double x, double y, double w, double h, double radius, int color) {
        int c = color(color);
        if (w <= 0 || h <= 0 || c >>> 24 == 0) return;
        begin();
        int x0 = px(x), y0 = py(y), x1 = Math.max(x0 + 1, px(x + w)), y1 = Math.max(y0 + 1, py(y + h));
        int r = (int) Math.min(Math.round(radius * scale()), Math.min(x1 - x0, y1 - y0) / 2);
        if (r <= 0) context.fill(x0, y0, x1, y1, c);
        else {
            context.fill(x0, y0 + r, x1, y1 - r, c);
            context.fill(x0 + r, y0, x1 - r, y0 + r, c);
            context.fill(x0 + r, y1 - r, x1 - r, y1, c);
            corner(x0, y0, r, c, false, false); corner(x1 - r, y0, r, c, true, false);
            corner(x0, y1 - r, r, c, false, true); corner(x1 - r, y1 - r, r, c, true, true);
        }
        end();
    }
    private void corner(int x, int y, int r, int color, boolean flipX, boolean flipY) {
        int i = 0;
        while (i < CORNER_SIZES.length - 1 && CORNER_SIZES[i] < r) i++;
        int s = CORNER_SIZES[i];
        // Negative regions mirror the top-left mask into the other three corners.
        float u = CORNER_U[i] + 1 + (flipX ? s : 0), v = 1 + (flipY ? s : 0);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, CORNERS, x, y, u, v, r, r, flipX ? -s : s, flipY ? -s : s, CORNER_ATLAS_W, CORNER_ATLAS_H, color);
    }
    public void border(double x, double y, double w, double h, double radius, int color) {
        // A continuous one-pixel rim; callers draw the inner surface over the fill.
        round(x - 1, y - 1, w + 2, h + 2, radius + 1, color);
    }
    /** Soft drop shadow whose blur and corner radius scale with the shape's radius. */
    public void shadow(double x, double y, double w, double h, double radius, double amount) {
        int c = color(MenuTheme.alpha(0x8C000000, amount));
        if (w <= 0 || h <= 0 || c >>> 24 == 0) return;
        begin();
        double k = Math.max(radius, 6) * scale() / SHADOW_RADIUS, extent = SHADOW_INSET * k, tile = SHADOW_HALF * k, drop = extent * .3;
        double left = x * sx + ox - extent, top = y * sy + oy - extent + drop, right = (x + w) * sx + ox + extent, bottom = (y + h) * sy + oy + extent + drop;
        int x0 = (int) Math.round(left), x1 = (int) Math.round(left + tile), x2 = (int) Math.round(right - tile), x3 = (int) Math.round(right);
        int y0 = (int) Math.round(top), y1 = (int) Math.round(top + tile), y2 = (int) Math.round(bottom - tile), y3 = (int) Math.round(bottom);
        int[] xs = shadowX, ys = shadowY;
        xs[0] = x0; xs[1] = x1; xs[2] = Math.max(x1, x2); xs[3] = x3;
        ys[0] = y0; ys[1] = y1; ys[2] = Math.max(y1, y2); ys[3] = y3;
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            int w0 = xs[col + 1] - xs[col], h0 = ys[row + 1] - ys[row];
            if (w0 <= 0 || h0 <= 0) continue;
            if (row == 1 && col == 1) context.fill(xs[1], ys[1], xs[2], ys[2], c);
            else context.drawTexture(RenderPipelines.GUI_TEXTURED, SHADOW, xs[col], ys[row], SHADOW_U[col], SHADOW_U[row],
                w0, h0, SHADOW_SPAN[col], SHADOW_SPAN[row], SHADOW_SIZE, SHADOW_SIZE, c);
        }
        end();
    }
    /**
     * Draws the part of a texture stretched over the cover rectangle that falls inside a rounded rectangle.
     * Corner rows are drawn individually, with partially covered edge pixels faded by their coverage.
     */
    public void cover(Identifier texture, double x, double y, double w, double h, double radius,
                      double coverX, double coverY, double coverW, double coverH, double alpha) {
        int c = color(MenuTheme.alpha(0xFFFFFFFF, alpha));
        if (w <= 0 || h <= 0 || c >>> 24 == 0) return;
        begin();
        double cx = coverX * sx + ox, cy = coverY * sy + oy, cw = coverW * sx, ch = coverH * sy;
        int x0 = px(x), y0 = py(y), x1 = Math.max(x0 + 1, px(x + w)), y1 = Math.max(y0 + 1, py(y + h));
        int r = (int) Math.min(Math.round(radius * scale()), Math.min(x1 - x0, y1 - y0) / 2);
        slice(texture, x0, y0 + r, x1 - x0, y1 - y0 - 2 * r, cx, cy, cw, ch, c);
        for (int i = 0; i < r; i++) {
            double dy = r - i - .5, inset = r - Math.sqrt(Math.max(0, r * (double) r - dy * dy));
            coverRow(texture, y0 + i, x0 + inset, x1 - inset, cx, cy, cw, ch, c);
            coverRow(texture, y1 - 1 - i, x0 + inset, x1 - inset, cx, cy, cw, ch, c);
        }
        end();
    }
    private void coverRow(Identifier texture, int row, double left, double right, double cx, double cy, double cw, double ch, int color) {
        int solidLeft = (int) Math.ceil(left), solidRight = (int) Math.floor(right);
        slice(texture, solidLeft, row, solidRight - solidLeft, 1, cx, cy, cw, ch, color);
        double edge = solidLeft - left;
        if (edge > .004) {
            int faded = MenuTheme.alpha(color, edge);
            slice(texture, solidLeft - 1, row, 1, 1, cx, cy, cw, ch, faded);
            slice(texture, solidRight, row, 1, 1, cx, cy, cw, ch, faded);
        }
    }
    private void slice(Identifier texture, int x, int y, int w, int h, double cx, double cy, double cw, double ch, int color) {
        if (w <= 0 || h <= 0 || color >>> 24 == 0) return;
        float u = (float) ((x - cx) / cw * UV), v = (float) ((y - cy) / ch * UV);
        int regionW = Math.max(1, (int) Math.round(w / cw * UV)), regionH = Math.max(1, (int) Math.round(h / ch * UV));
        context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, w, h, regionW, regionH, UV, UV, color);
    }
    public void glass(WallpaperRenderer wallpaper, double x, double y, double w, double h, double radius, double alpha) {
        double old = opacity; opacity *= alpha;
        shadow(x, y, w, h, radius, 1);
        border(x, y, w, h, radius, 0x35FFFFFF);
        round(x, y, w, h, radius, ConfigManager.get().lightGlass ? 0xFFE9EDF5 : 0xFF191D29);
        wallpaper.sample(this, x, y, w, h, radius, ConfigManager.get().blur ? ConfigManager.get().blurIntensity * .82 : 0);
        round(x, y, w, h, radius, MenuTheme.glass());
        hairline(x + radius, y, w - radius * 2, 0x36FFFFFF);
        opacity = old;
    }
    private Label label(String value, boolean clock) {
        var cache = clock ? clockCache : bodyCache; Label label = cache.get(value);
        if (label == null) {
            if (cache.size() >= 768) cache.clear();
            Text text = Text.literal(value).setStyle(clock ? CLOCK : BODY);
            label = new Label(text, MinecraftClient.getInstance().textRenderer.getWidth(text)); cache.put(value, label);
        }
        return label;
    }
    public double width(String value, double height) { return label(value, false).width * height / MenuTheme.BODY_CAP_HEIGHT; }
    public void text(String value, double x, double y, double height, int color) { text(value, x, y, height, color, false, false); }
    public void center(String value, double x, double y, double height, int color) { text(value, x, y, height, color, true, false); }
    public void clock(String value, double x, double y, double height, int color) { text(value, x, y, height, color, true, true); }
    private void text(String value, double x, double y, double height, int color, boolean center, boolean clock) {
        if (value == null || value.isEmpty() || opacity < .001) return;
        if ((MenuTheme.alpha(color, opacity) >>> 24) == 0) return;
        // Inter cap heights are 12/70 at the provider sizes; heights here mean visible pixels.
        Label label = label(value, clock); double scale = height / (clock ? MenuTheme.CLOCK_CAP_HEIGHT : MenuTheme.BODY_CAP_HEIGHT);
        var m = context.getMatrices(); m.pushMatrix();
        m.translate((float) (x - (center ? label.width * scale * .5 : 0)), (float) y); m.scale((float) scale, (float) scale);
        context.drawText(MinecraftClient.getInstance().textRenderer, label.text, 0, 0, MenuTheme.alpha(color, opacity), false);
        m.popMatrix();
    }
    public void clippedText(String value, double x, double y, double height, double maxWidth, int color) {
        context.enableScissor((int) x, (int) y - 3, (int) (x + Math.max(0, maxWidth)), (int) (y + height * 1.5));
        text(value, x, y, height, color); context.disableScissor();
    }
    /** Dock glyph from a linear-filtered white mask, tinted by color; the 64px mask serves small sizes. */
    public void icon(Dock.Action action, double x, double y, double size, int color) {
        int c = color(color);
        if (size <= 0 || c >>> 24 == 0) return;
        begin();
        int s = Math.max(1, (int) Math.round(size * scale()));
        boolean small = s <= 80;
        int t = small ? 64 : 128;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, (small ? SMALL_ICONS : ICONS)[action.ordinal()], px(x), py(y), 0, 0, s, s, t, t, t, t, c);
        end();
    }
}
