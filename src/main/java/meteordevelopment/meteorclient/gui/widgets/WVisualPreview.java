package meteordevelopment.meteorclient.gui.widgets;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.render.Chams;
import meteordevelopment.meteorclient.systems.modules.render.StorageESP;
import meteordevelopment.meteorclient.systems.modules.render.blockesp.BlockESP;
import meteordevelopment.meteorclient.utils.render.*;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.client.gui.Click;

import java.util.List;

/** Live settings preview. Rotating and zooming never changes a module setting. */
public final class WVisualPreview extends WWidget {
    private final Module module;
    private double yaw = -.55, pitch = .22, zoom = 1;
    private boolean dragging, hand;
    private double idle;

    public WVisualPreview(Module module) { this.module = module; }
    @Override protected void onCalculateSize() { width = theme.scale(320); height = theme.scale(236); }

    @Override protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
        double s = theme.scale(1), pad = 14 * s;
        idle += Math.min(delta, .05);
        if (!dragging && idle > 2) yaw += Math.min(delta, .05) * .16;
        Color accent = EmberPalette.accent(), text = EmberPalette.textBright(), dim = EmberPalette.textDim();
        r.roundedRect(x, y, width, height, 13 * s, new Color(10, 12, 18, 210));
        r.glow(x + width * .25, y + height * .4, width * .5, height * .25, 25 * s, new Color(accent.r, accent.g, accent.b, 24), false);
        r.text("Material preview", x + pad, y + 11 * s, text, false);
        if (module instanceof Chams) {
            String target = hand ? "Hand  /  switch" : "Player  /  switch";
            r.text(target, x + width - pad - theme.textWidth(target), y + 11 * s, accent, false);
        }
        String hint = "Drag to rotate · scroll to zoom";
        r.text(hint, x + pad, y + height - theme.textHeight() - 11 * s, dim, false);
        double top = y + 43 * s, h = height - 79 * s;
        // Fine floor marks give transparent materials an honest backdrop.
        for (int i = 0; i < 5; i++) r.quad(x + width * .18, top + h * (.66 + i * .045), width * .64, Math.max(.5, s * .5), new Color(255, 255, 255, 8));
        if (module instanceof Chams chams) {
            var material = hand ? chams.handMaterial.get() : chams.playerMaterial.get();
            Color color = hand ? chams.handTint() : chams.playerTint();
            boolean original = hand ? chams.handTexture.get() : chams.playersTexture.get();
            String caption = original ? "Original texture · material off" : material.name() + " · " + (hand ? "first-person arms" : "player model");
            r.text(caption, x + pad, y + 31 * s, dim, false);
            var parts = hand ? List.of(new VisualPreviewRenderer.Part(-.22, -.8, -.22, .44, 1.6, .44, .6875, .3125, .0625, .1875)) : VisualPreviewRenderer.player();
            r.post(() -> VisualPreviewRenderer.draw(parts, x, top, width, h, yaw, pitch, zoom, 0, color, color, true, false,
                original ? 0 : material.ordinal(), chams.animateMaterials.get(), 0, 0));
        } else {
            EspStyle style;
            Color fill, line;
            boolean sides, outlines;
            if (module instanceof BlockESP block) {
                style = block.style;
                var data = block.previewData(); fill = style.tint(data.sideColor); line = style.tint(data.lineColor);
                sides = data.shapeMode.sides(); outlines = data.shapeMode.lines();
            } else {
                StorageESP storage = (StorageESP) module; style = storage.style;
                line = storage.previewColor(); fill = new Color(line.r, line.g, line.b, storage.fillOpacity.get());
                sides = storage.shapeMode.get().sides(); outlines = storage.shapeMode.get().lines();
            }
            double radius = module instanceof StorageESP storage && storage.renderMode() != StorageESP.Mode.Ember ? 0 : style.roundness.get();
            int pattern = module instanceof StorageESP storage && storage.renderMode() == StorageESP.Mode.Box ? 0 : style.texture.get().ordinal();
            String mode = module instanceof StorageESP storage ? storage.renderMode().name() : "Rounded surface";
            r.text(mode + " · " + (pattern == 0 ? "No texture" : style.texture.get().name()), x + pad, y + 31 * s, dim, false);
            var parts = List.of(new VisualPreviewRenderer.Part(-.65, -.65, -.65, 1.3, 1.3, 1.3, 0, 0, 1, 1));
            r.post(() -> VisualPreviewRenderer.draw(parts, x, top, width, h, yaw, pitch, zoom, radius, fill, line, sides, outlines,
                -1, false, pattern, style.textureStrength.get()));
        }
    }

    @Override public boolean onMouseClicked(Click click, boolean doubled) {
        if (!mouseOver || click.button() != 0) return false;
        idle = 0;
        if (module instanceof Chams && click.y() < y + theme.scale(31) && click.x() > x + width / 2) { hand = !hand; return true; }
        if (doubled) { yaw = -.55; pitch = .22; zoom = 1; return true; }
        dragging = true; return true;
    }
    @Override public boolean onMouseReleased(Click click) { boolean handled = dragging; dragging = false; return handled; }
    @Override public void onMouseMoved(double mouseX, double mouseY, double lastX, double lastY) {
        if (!dragging) return;
        yaw += (mouseX - lastX) * .009; pitch = Math.clamp(pitch + (mouseY - lastY) * .009, -.9, .9); idle = 0;
    }
    @Override public boolean onMouseScrolled(double amount) { if (!mouseOver) return false; zoom = Math.clamp(zoom + amount * .08, .65, 1.3); idle = 0; return true; }
}
