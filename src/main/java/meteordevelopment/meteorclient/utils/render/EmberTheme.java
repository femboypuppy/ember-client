package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;

/**
 * Which Ember layout is in use. This is a layout choice, separate from EmberPalette, which
 * picks the colours - a theme here decides which widgets are on screen and how they draw.
 *
 * Switching enables the chosen set's widgets and disables the other's, so the two never
 * stack on top of each other.
 */
public final class EmberTheme {
    public enum Style {
        /** The original layout: one top bar strip, module list, notifications, music. */
        Ember("Ember", new String[]{"ember-top-bar"}),

        /** Wide status bar along the top, info pill bottom left, keybind panel on the left. */
        EmberV2("Ember V2", new String[]{"ember-status-bar", "ember-bubbles", "ember-keybinds"});

        public final String displayName;
        /** Widgets belonging only to this style. */
        public final String[] widgets;

        Style(String displayName, String[] widgets) {
            this.displayName = displayName;
            this.widgets = widgets;
        }
    }

    private static Style current = Style.Ember;

    private EmberTheme() {
    }

    public static Style current() {
        return current;
    }

    public static boolean isV2() {
        return current == Style.EmberV2;
    }

    /** Switches layout, turning the other style's widgets off so they cannot overlap. */
    public static void select(Style style) {
        if (style == null || style == current) return;
        current = style;

        // Mirror into the saved setting. Guarded by the equality check above, so the
        // setting's own onChanged calling back in here stops rather than looping.
        Hud hud = Hud.get();
        if (hud != null && hud.layout.get() != style) hud.layout.set(style);

        applyCurrent();
    }

    /** Takes a loaded value without writing back to the setting it came from. */
    public static void adopt(Style style) {
        if (style != null) current = style;
        applyCurrent();
    }

    /** Enforces the current layout, for use after widgets are first created. */
    public static void applyCurrent() {
        for (Style style : Style.values()) {
            boolean wanted = style == current;
            for (String name : style.widgets) setActive(name, wanted);
        }
    }

    private static void setActive(String name, boolean active) {
        Hud hud = Hud.get();
        if (hud == null) return;

        for (HudElement element : hud) {
            if (!element.info.name.equals(name)) continue;
            if (element.isActive() != active) element.toggle();
            return;
        }
    }
}
