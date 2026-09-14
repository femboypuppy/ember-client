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
        r.quad(x + radius, y, w - radius * 2, radius, color);
        r.quad(x + radius, y + h - radius, w - radius * 2, radius, color);

        int steps = Math.max(4, (int) Math.ceil(radius * 2));

        for (int i = 0; i < steps; i++) {
            double sliceTop = i * radius / steps;
            double sliceBottom = (i + 1) * radius / steps;
            double dy = radius - sliceTop;
            double inset = radius - Math.sqrt(Math.max(0, radius * radius - dy * dy));
            double sliceH = sliceBottom - sliceTop;
            double sliceW = radius - inset;
            if (sliceW <= 0) continue;

            r.quad(x + inset, y + sliceTop, sliceW, sliceH, color);
            r.quad(x + w - radius, y + sliceTop, sliceW, sliceH, color);
            r.quad(x + inset, y + h - sliceBottom, sliceW, sliceH, color);
            r.quad(x + w - radius, y + h - sliceBottom, sliceW, sliceH, color);
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

        int steps = Math.max(6, (int) Math.ceil(radius * 2));
        double sliceH = size / (steps * 2.0);

        for (int i = 0; i < steps * 2; i++) {
            double top = i * sliceH;
            double bottom = top + sliceH;

            double dy;
            if (bottom <= radius) dy = radius - top;
            else if (top >= size - radius) dy = bottom - (size - radius);
            else dy = 0;

            double inset = dy <= 0 ? 0 : radius - Math.sqrt(Math.max(0, radius * radius - dy * dy));
            double w = size - inset * 2;
            if (w <= 0) continue;

            double uInset = (u2 - u1) * (inset / size);
            double vTop = v1 + (v2 - v1) * (top / size);
            double vBottom = v1 + (v2 - v1) * (bottom / size);

            r.texQuad(x + inset, y + top, w, sliceH, 0, u1 + uInset, vTop, u2 - uInset, vBottom, color);
        }
    }
}
