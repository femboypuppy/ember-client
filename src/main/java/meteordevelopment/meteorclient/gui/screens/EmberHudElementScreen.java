package meteordevelopment.meteorclient.gui.screens;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Settings for one HUD element, shown in Ember's own popup rather than Meteor's stock window.
 * Right-clicking a widget in the HUD editor lands here, so the editor matches the rest of the
 * client instead of dropping into a differently styled screen.
 */
public class EmberHudElementScreen extends WidgetScreen {
    private final EmberSettingsPopup popup;
    private double rawMx, rawMy;

    public EmberHudElementScreen(GuiTheme theme, HudElement element) {
        super(theme, element.info.title);

        popup = new EmberSettingsPopup(theme);
        popup.open(new EmberSettingsPopup.Target(
            element.info.title, element.info.description, element.settings,
            element::isActive, element::toggle, null,
            () -> mc.setScreen(new meteordevelopment.meteorclient.systems.hud.screens.HudElementScreen(theme, element))
        ));
    }

    @Override
    public void initWidgets() {
        clear();
    }

    /** This branch hands onRenderBefore no mouse position, so track the cursor here. */
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        double s = mc.getWindow().getScaleFactor();
        rawMx = mouseX * s;
        rawMy = mouseY * s;
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    protected void onRenderBefore(DrawContext graphics, float delta) {
        // Dismissing the popup leaves nothing to show, so the screen goes with it.
        if (!popup.isVisible()) {
            close();
            return;
        }

        GuiRenderer r = new GuiRenderer();
        r.theme = theme;
        r.begin(graphics);
        popup.render(r, rawMx, rawMy, delta);
        r.end();

        popup.renderText(graphics);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double s = mc.getWindow().getScaleFactor();
        return popup.mouseClicked(click.x() * s, click.y() * s, click.button());
    }

    @Override
    public boolean mouseReleased(Click click) {
        popup.mouseReleased();
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        return popup.mouseScrolled(v);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        return popup.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        return popup.charTyped(input);
    }

    @Override
    public void reload() {
    }
}
