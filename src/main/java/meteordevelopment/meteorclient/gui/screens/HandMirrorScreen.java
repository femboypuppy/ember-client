package meteordevelopment.meteorclient.gui.screens;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.systems.modules.render.HandView;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** An explicit source choice; opening or cancelling this screen never edits either hand. */
public final class HandMirrorScreen extends WindowScreen {
    private final HandView module;
    private final Runnable beforeCopy;
    public HandMirrorScreen(GuiTheme theme, HandView module, Runnable beforeCopy) {
        super(theme, "Make symmitrical"); this.module = module; this.beforeCopy = beforeCopy;
    }
    @Override public void initWidgets() {
        add(theme.label("Which hand should be copied?"));
        add(theme.label("The source stays unchanged. The other hand receives its mirrored position,"));
        add(theme.label("rotation and matching scale."));
        add(theme.button("Copy main hand → off hand")).expandX().widget().action = () -> copy(true);
        add(theme.button("Copy off hand → main hand")).expandX().widget().action = () -> copy(false);
        add(theme.button("Cancel")).expandX().widget().action = this::close;
    }
    private void copy(boolean main) {
        beforeCopy.run(); module.mirrorFrom(main);
        if (parent instanceof WidgetScreen screen && !(screen instanceof HandEditorScreen)) screen.reload();
        close();
    }
    @Override public void close() { mc.setScreen(parent); }
}
