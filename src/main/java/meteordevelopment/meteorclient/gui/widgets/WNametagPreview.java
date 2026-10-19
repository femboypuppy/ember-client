package meteordevelopment.meteorclient.gui.widgets;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.systems.modules.render.Nametags;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;

/** Settings and in-world tags share the actual card painter and typography. */
public final class WNametagPreview extends WWidget {
    private final Nametags module;
    public WNametagPreview(Nametags module) { this.module = module; }
    @Override protected void onCalculateSize() { width = theme.scale(380); height = theme.scale(160); }
    @Override protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
        double s = theme.scale(1), pad = 14 * s;
        r.roundedRect(x, y, width, height, 13 * s, new Color(13, 16, 23, 225));
        r.text("Nametag preview", x + pad, y + 12 * s, EmberPalette.textBright(), false);
        String style = module.cardStyle.get().name();
        r.text(style, x + width - pad - theme.textWidth(style), y + 12 * s, EmberPalette.accent(), false);
        for (int i = 0; i < 6; i++) r.quad(x + pad + i * (width - pad * 2) / 5, y + 43 * s, .7 * s, height - 80 * s, new Color(255, 255, 255, 7));
        r.text("Sample data; settings apply above players.", x + pad, y + height - theme.textHeight() - 12 * s, EmberPalette.textDim(), false);
        double zoom = Math.min(s * 1.12, (width - pad * 2) / module.maxCardWidth.get());
        r.post(() -> module.renderPreview(x + width / 2, y + height - 38 * s, zoom));
    }
}
