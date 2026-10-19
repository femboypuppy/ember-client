package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.systems.RenderSystem;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.Identifier;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** The same bounded card renderer is used in-world and by the live settings preview. */
public final class EmberNametagCard {
    public enum Style { Glass, Slate, Compact }
    public record Chip(String text, Color color) {}
    public record Data(String name, String health, double healthRatio, double absorptionRatio, Color nameColor,
                       Color healthColor, Color accent, Color background, Identifier skin, List<Chip> chips,
                       boolean avatar, boolean bar, boolean shadow, double opacity, double maxWidth, Style style) {}
    private static final Color WHITE = new Color(255, 255, 255);
    private EmberNametagCard() {}

    /** Text metrics must use the same font size and atlas as the ensuing draw, even after another tag's small text. */
    public static double measure(TextRenderer text, String value, double scale) {
        text.begin(scale, true, true);
        try { return text.getWidth(value, false); } finally { text.end(); }
    }

    public static double height(TextRenderer text, double scale) {
        text.begin(scale, true, true);
        try { return text.getHeight(false); } finally { text.end(); }
    }

    public static NametagCardLayout.Layout draw(Data d, double anchorX, double anchorY, double projectedScale) {
        TextRenderer text = TextRenderer.get();
        double nameWidth = measure(text, d.name, 1);
        double hpWidth = d.health.isEmpty() ? 0 : measure(text, d.health, .7);
        double lineH = height(text, 1);
        List<Double> chipWidths = d.chips.stream().map(c -> measure(text, c.text, .58)).toList();
        var layout = NametagCardLayout.create(nameWidth, hpWidth, chipWidths, lineH, d.avatar, d.bar,
            d.style == Style.Compact, d.maxWidth);
        double x = -layout.width() / 2, y = -layout.height(), radius = d.style == Style.Compact ? 11 : 10;
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        if (d.shadow) for (int i = 4; i >= 1; i--) {
            double spread = i * 1.7;
            EmberShapes.roundedGradient(r, x - spread, y - spread, layout.width() + spread * 2, layout.height() + spread * 2,
                radius + spread, new Color(0, 0, 0, (int) ((24 - i * 3) * d.opacity)), new Color(0, 0, 0, (int) ((30 - i * 4) * d.opacity)));
        }
        r.render();

        boolean glass = false;
        if (d.style == Style.Glass && LiquidGlassRenderer.available()) {
            var matrices = RenderSystem.getModelViewStack();
            matrices.pushMatrix();
            try {
                matrices.identity();
                glass = LiquidGlassRenderer.draw(anchorX + x * projectedScale, anchorY + y * projectedScale,
                    layout.width() * projectedScale, layout.height() * projectedScale, radius * projectedScale, d.opacity);
            } finally { matrices.popMatrix(); }
        }
        r.begin();
        if (!glass) {
            Color bg = d.background;
            EmberShapes.roundedGradient(r, x, y, layout.width(), layout.height(), radius,
                new Color(Math.min(255, bg.r + 5), Math.min(255, bg.g + 5), Math.min(255, bg.b + 6), (int) (bg.a * d.opacity)),
                new Color(bg.r, bg.g, bg.b, (int) (bg.a * d.opacity)));
        }
        EmberShapes.roundedOutline(r, x, y, layout.width(), layout.height(), radius, .65,
            new Color(d.accent.r, d.accent.g, d.accent.b, (int) (95 * d.opacity)), false);
        var hp = layout.health();
        if (hp.width() > 0) EmberShapes.roundedGradient(r, hp.x(), hp.y(), hp.width(), hp.height(), 6,
            new Color(d.healthColor.r, d.healthColor.g, d.healthColor.b, 38), new Color(d.healthColor.r, d.healthColor.g, d.healthColor.b, 22));
        double chipX = layout.details().x();
        for (int i = 0; i < d.chips.size(); i++) {
            double w = chipWidths.get(i) + 10;
            if (chipX + w > layout.details().x() + layout.details().width() + .01) break;
            EmberShapes.roundedQuad(r, chipX, layout.details().y(), w, layout.details().height(), 5, new Color(255, 255, 255, 13));
            chipX += w + 4;
        }
        var bar = layout.bar();
        if (bar.height() > 0) {
            EmberShapes.roundedQuad(r, bar.x(), bar.y(), bar.width(), bar.height(), 1.5, new Color(255, 255, 255, 22));
            double fraction = Math.clamp(Double.isFinite(d.healthRatio) ? d.healthRatio : 0, 0, 1);
            if (fraction > 0) EmberShapes.roundedQuad(r, bar.x(), bar.y(), bar.width() * fraction, bar.height(), 1.5, d.healthColor);
            double absorbed = Math.clamp(Double.isFinite(d.absorptionRatio) ? d.absorptionRatio : 0, 0, 1);
            if (absorbed > 0) EmberShapes.roundedQuad(r, bar.x() + bar.width() * Math.min(fraction, 1 - absorbed), bar.y(), bar.width() * absorbed, bar.height(), 1.5, new Color(255, 207, 103));
        }
        r.render();

        var head = layout.avatar();
        if (head.width() > 0) {
            if (d.skin != null) {
                var skin = mc.getTextureManager().getTexture(d.skin);
                Renderer2D.TEXTURE.begin();
                EmberShapes.roundedTexture(Renderer2D.TEXTURE, head.x(), head.y(), head.width(), 8, .125, .125, .25, .25, WHITE);
                EmberShapes.roundedTexture(Renderer2D.TEXTURE, head.x(), head.y(), head.width(), 8, .625, .125, .75, .25, WHITE);
                Renderer2D.TEXTURE.render(skin.getGlTextureView(), skin.getSampler());
            } else {
                r.begin(); EmberShapes.roundedQuad(r, head.x(), head.y(), head.width(), head.height(), 8,
                    new Color(d.accent.r, d.accent.g, d.accent.b, 60)); r.render();
            }
        }
        String name = NametagCardLayout.fit(d.name, layout.name().width(), s -> measure(text, s, 1));
        text.beginBig();
        if (head.width() > 0 && d.skin == null) text.render("E", head.x() + (head.width() - text.getWidth("E", false)) / 2,
            head.y() + (head.height() - text.getHeight(false)) / 2, d.accent, false);
        text.render(name, layout.name().x(), layout.name().y(), d.nameColor, false);
        text.end();
        if (!d.health.isEmpty()) {
            text.begin(.7, false, true);
            text.render(d.health, hp.x() + 6, hp.y() + (hp.height() - text.getHeight(false)) / 2, d.healthColor, false);
            text.end();
        }
        text.begin(.58, false, true);
        chipX = layout.details().x();
        for (int i = 0; i < d.chips.size(); i++) {
            double w = chipWidths.get(i) + 10;
            if (chipX + w > layout.details().x() + layout.details().width() + .01) break;
            text.render(d.chips.get(i).text, chipX + 5,
                layout.details().y() + (layout.details().height() - text.getHeight(false)) / 2, d.chips.get(i).color, false);
            chipX += w + 4;
        }
        text.end();
        return layout;
    }
}
