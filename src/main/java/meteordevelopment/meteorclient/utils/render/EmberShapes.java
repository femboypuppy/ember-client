package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Rounded shapes emitted straight into a {@link Renderer2D} batch, for the places that draw
 * outside the HUD renderer - nametags project into their own 2D space and have to build
 * geometry themselves.
 *
 * Corners are cut from horizontal slices rather than masked, since there is no stencil and
 * the surface behind a nametag is the world, not a known flat colour.
 */
public final class EmberShapes {
    private EmberShapes() {
    }

    /** Emits a rounded rectangle. The caller owns begin() and render(). */
    public static void roundedQuad(Renderer2D r, double x, double y, double w, double h, double radius, Color color) {
        if (w <= 0 || h <= 0) return;

        radius = Math.min(radius, Math.min(w, h) / 2);

        if (radius <= 0) {
            r.quad(x, y, w, h, color);
            return;
        }

        r.quad(x, y + radius, w, h - radius * 2, color);

        // One pixel per row, with the pixel the curve passes through drawn at an alpha equal
        // to how much of it the shape covers. Hard slices left the corner as a staircase,
        // since nothing in this path antialiases.
        int rows = (int) Math.ceil(radius);

        for (int i = 0; i < rows; i++) {
            double top = i;
            double bottom = Math.min(radius, i + 1.0);
            double rowH = bottom - top;
            if (rowH <= 0.0001) continue;

            double dy = radius - (top + rowH / 2);
            double dx = Math.sqrt(Math.max(0, radius * radius - dy * dy));
            double edge = radius - dx;

            double solid = Math.ceil(edge - 0.0001);
            double coverage = solid - edge;

            double innerW = w - solid * 2;
            double topY = y + top;
            double botY = y + h - bottom;

            if (innerW > 0) {
                r.quad(x + solid, topY, innerW, rowH, color);
                r.quad(x + solid, botY, innerW, rowH, color);
            }

            if (coverage > 0.02 && solid >= 1) {
                Color soft = new Color(color.r, color.g, color.b, (int) (color.a * coverage));

                r.quad(x + solid - 1, topY, 1, rowH, soft);
                r.quad(x + w - solid, topY, 1, rowH, soft);
                r.quad(x + solid - 1, botY, 1, rowH, soft);
                r.quad(x + w - solid, botY, 1, rowH, soft);
            }
        }
    }

    /**
     * Emits a rounded rectangle filled with the part of a full-screen texture that lies behind
     * it - the UVs come from the rectangle's own screen position rather than from a 0..1 box,
     * so the content stays pinned to the world as the panel moves across it.
     *
     * {@code flipV} exists because a framebuffer's origin is its bottom left while the
     * interface is laid out from the top.
     */
    /**
     * Signed distance to a rounded rectangle, negative inside. This is what lets the lens know
     * how close every point is to the edge in any direction, rather than only vertically.
     */
    private static double sdRoundRect(double px, double py, double hw, double hh, double radius) {
        double qx = Math.abs(px) - (hw - radius);
        double qy = Math.abs(py) - (hh - radius);

        double ax = Math.max(qx, 0), ay = Math.max(qy, 0);
        return Math.sqrt(ax * ax + ay * ay) + Math.min(Math.max(qx, qy), 0) - radius;
    }

    /**
     * Fills a rounded rectangle with a lensed view of a full-screen texture - the refraction
     * half of a liquid glass panel.
     *
     * The surface is cut into a grid, and each cell samples from a point displaced outward
     * along the edge normal, by an amount that rises sharply as the signed distance to the
     * edge approaches zero. That is what bends the scene around the whole rim rather than only
     * at the corners, and what separates a lens from a plain blur.
     *
     * @param lens     how far, in pixels, the rim pulls its sample from
     * @param edgeOnly fade the whole fill out away from the edge, for the colour-fringe passes
     */
    public static void liquidGlass(Renderer2D r, double x, double y, double w, double h, double radius,
                                   double screenW, double screenH, boolean flipV,
                                   double lens, Color tint, boolean edgeOnly) {
        if (w <= 0 || h <= 0 || screenW <= 0 || screenH <= 0) return;

        radius = Math.min(radius, Math.min(w, h) / 2);

        double hw = w / 2, hh = h / 2;
        double cx = x + hw, cy = y + hh;

        // How far inward the lens reaches. Tied to the corner radius, since that is what sets
        // how thick the glass edge looks.
        double band = Math.max(8, radius * 1.8);

        int rows = (int) Math.max(10, Math.min(56, h / 4));
        int cols = (int) Math.max(10, Math.min(72, w / 4));

        double cellH = h / rows;
        double cellW = w / cols;

        for (int row = 0; row < rows; row++) {
            double top = row * cellH;

            // The rounded silhouette still comes from a per-row inset, so the outline stays
            // clean no matter how coarse the grid is.
            double dy;
            if (top + cellH <= radius) dy = radius - top;
            else if (top >= h - radius) dy = (top + cellH) - (h - radius);
            else dy = 0;

            double inset = dy <= 0 ? 0 : radius - Math.sqrt(Math.max(0, radius * radius - dy * dy));
            double rowW = w - inset * 2;
            if (rowW <= 0) continue;

            int rowCols = Math.max(1, (int) Math.round(rowW / cellW));
            double stepW = rowW / rowCols;

            for (int col = 0; col < rowCols; col++) {
                double sx = x + inset + col * stepW;
                double sy = y + top;

                double px = sx + stepW / 2 - cx;
                double py = sy + cellH / 2 - cy;

                double d = sdRoundRect(px, py, hw, hh, radius);
                double t = Math.max(0, Math.min(1, 1 + d / band));

                // Steep falloff: the middle of the pane stays honest and the distortion piles
                // up in the last few pixels, which is how a thick glass edge behaves.
                double amount = t * t * t;

                double len = Math.sqrt(px * px + py * py);
                double nx = len < 0.001 ? 0 : px / len;
                double ny = len < 0.001 ? 0 : py / len;

                double push = lens * amount;
                double ox = nx * push, oy = ny * push;

                int alpha = edgeOnly ? (int) (tint.a * amount) : tint.a;
                if (alpha <= 2) continue;

                double u1 = (sx + ox) / screenW;
                double u2 = (sx + stepW + ox) / screenW;
                double v1 = (sy + oy) / screenH;
                double v2 = (sy + cellH + oy) / screenH;

                if (flipV) {
                    v1 = 1 - v1;
                    v2 = 1 - v2;
                }

                r.texQuad(sx, sy, stepW + 0.5, cellH + 0.5, 0, u1, v1, u2, v2,
                    new Color(tint.r, tint.g, tint.b, alpha));
            }
        }
    }

    /**
     * Emits a square patch of a texture with rounded corners, as horizontal strips whose UVs
     * are inset along with the geometry so the image does not stretch as the strips narrow.
     * The caller owns begin() and render(), and so chooses the texture.
     */
    public static void roundedTexture(Renderer2D r, double x, double y, double size, double radius,
                                      double u1, double v1, double u2, double v2, Color color) {
        if (size <= 0) return;

        radius = Math.min(radius, size / 2);

        // One pixel per row, with the pixel the curve crosses drawn at partial alpha - the
        // same analytic coverage the solid shapes use. As plain strips this had the staircase
        // edge, which shows up worst on album art, where the fill is a photograph rather than
        // a flat colour and every step is a different shade.
        int rows = Math.max(1, (int) Math.ceil(size));
        double rowH = size / rows;

        for (int i = 0; i < rows; i++) {
            double top = i * rowH;
            double bottom = top + rowH;
            double mid = top + rowH / 2;

            double dy;
            if (mid < radius) dy = radius - mid;
            else if (mid > size - radius) dy = mid - (size - radius);
            else dy = 0;

            double inset = dy <= 0 ? 0 : radius - Math.sqrt(Math.max(0, radius * radius - dy * dy));

            double solid = Math.ceil(inset - 0.0001);
            double coverage = solid - inset;

            double vTop = v1 + (v2 - v1) * (top / size);
            double vBottom = v1 + (v2 - v1) * (bottom / size);
            double innerW = size - solid * 2;

            if (innerW > 0) {
                double uLeft = u1 + (u2 - u1) * (solid / size);
                double uRight = u2 - (u2 - u1) * (solid / size);
                r.texQuad(x + solid, y + top, innerW, rowH, 0, uLeft, vTop, uRight, vBottom, color);
            }

            if (coverage > 0.02 && solid >= 1) {
                Color soft = new Color(color.r, color.g, color.b, (int) (color.a * coverage));

                double uA = u1 + (u2 - u1) * ((solid - 1) / size);
                double uB = u1 + (u2 - u1) * (solid / size);
                r.texQuad(x + solid - 1, y + top, 1, rowH, 0, uA, vTop, uB, vBottom, soft);

                double uC = u2 - (u2 - u1) * (solid / size);
                double uD = u2 - (u2 - u1) * ((solid - 1) / size);
                r.texQuad(x + size - solid, y + top, 1, rowH, 0, uC, vTop, uD, vBottom, soft);
            }
        }
    }
}
