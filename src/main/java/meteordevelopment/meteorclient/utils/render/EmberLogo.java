package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.Identifier;

/** The uploaded Ember artwork, with an optional moving rounded tile around it. */
public final class EmberLogo {
    private static final Identifier IMAGE = MeteorClient.identifier("icon.png");
    public record Frame(double x, double y, double size, double radius) {}

    private EmberLogo() {}

    /** Plain mark for the noncompact status bar. RGB tint never changes the artwork. */
    public static void draw(HudRenderer renderer, double x, double y, double size, double opacity) {
        if (size <= 0 || opacity <= .01) return;
        Color white = new Color(255, 255, 255, (int) Math.round(255 * Math.clamp(opacity, 0, 1)));
        // Texture work belongs after the HUD's color/font batches have been submitted.
        renderer.post(() -> renderer.roundedTextureRegion(IMAGE, x, y, size, size * .23,
            .08, .08, .90, .90, white, true));
    }

    public static Frame frame(double x, double y, double size, double seconds, double motion) {
        double amount = Math.clamp(motion, 0, 1);
        double inner = size * .77 * (1 + .025 * amount * Math.sin(seconds * 1.7));
        double bob = size * .014 * amount * Math.sin(seconds * 1.25);
        return new Frame(x + (size - inner) / 2, y + (size - inner) / 2 + bob, inner, inner * .23);
    }

    public static void tile(HudRenderer renderer, double x, double y, double size, double seconds,
                            double motion, Color accent, double opacity) {
        if (size <= 0 || opacity <= .01) return;
        double fade = Math.clamp(opacity, 0, 1);
        Frame f = frame(x, y, size, seconds, motion);
        double edge = Math.max(.65, size * .018);
        renderer.roundedQuad(f.x - edge, f.y - edge, f.size + edge * 2, f.size + edge * 2, f.radius + edge,
            new Color((accent.r + 255) / 2, (accent.g + 255) / 2, (accent.b + 255) / 2, (int) (85 * fade)));
        renderer.roundedQuad(f.x, f.y, f.size, f.size, f.radius, new Color(8, 8, 11, (int) (240 * fade)));

        if (motion > 0) {
            // A short light travels along the rounded perimeter. Bounded primitive work,
            // no texture uploads, framebuffer copies or additional render targets.
            double phase = seconds / 6;
            for (int i = 0; i < 18; i++) {
                double t = phase - i * .006;
                double[] a = roundedPoint(f.size + edge, f.radius + edge / 2, t);
                double[] b = roundedPoint(f.size + edge, f.radius + edge / 2, t - .006);
                double strength = 1 - i / 18.0;
                Color color = new Color(255, (accent.g + 160) / 2, (accent.b + 90) / 2,
                    (int) (220 * fade * strength * strength));
                renderer.line(f.x - edge / 2 + a[0], f.y - edge / 2 + a[1],
                    f.x - edge / 2 + b[0], f.y - edge / 2 + b[1], color);
            }
        }
        draw(renderer, f.x + edge, f.y + edge, f.size - edge * 2, fade);
    }

    /** Arc-length position around a rounded square, clockwise from its upper edge. */
    public static double[] roundedPoint(double size, double radius, double phase) {
        double r = Math.clamp(radius, 0, size / 2), straight = size - 2 * r;
        double quarter = straight + Math.PI * r / 2;
        if (quarter <= 0) return new double[]{0, 0};
        double distance = (phase - Math.floor(phase)) * quarter * 4;
        int side = Math.min(3, (int) (distance / quarter));
        double d = distance - side * quarter;
        double px, py;
        if (d <= straight || r == 0) { px = r + d; py = 0; }
        else {
            double angle = -Math.PI / 2 + (d - straight) / r;
            px = size - r + Math.cos(angle) * r; py = r + Math.sin(angle) * r;
        }
        return switch (side) {
            case 1 -> new double[]{size - py, px};
            case 2 -> new double[]{size - px, size - py};
            case 3 -> new double[]{py, size - px};
            default -> new double[]{px, py};
        };
    }
}
