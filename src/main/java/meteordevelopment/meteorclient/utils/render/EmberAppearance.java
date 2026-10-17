package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.systems.config.Config;

/** Selectable surface styles. Existing configurations retain their saved appearance. */
public enum EmberAppearance {
    Solid,
    Frosted,
    LiquidGlass;

    @Override
    public String toString() {
        return this == LiquidGlass ? "Liquid Glass" : name();
    }

    public static EmberAppearance current() {
        Config config = Config.get();
        return config == null ? LiquidGlass : config.appearance.get();
    }

    public static boolean frosted() {
        return current() != Solid;
    }

    public static boolean liquid() {
        return current() == LiquidGlass;
    }

    public static double motion() {
        Config config = Config.get();
        return config == null ? 1 : config.liquidMotion.get();
    }

    /** Panel opacity, out of 255, for the current appearance. */
    public static int panelAlpha() {
        return liquid() ? 98 : frosted() ? 132 : 242;
    }

    /**
     * How solid the glass is, 0 to 1. At zero it is clear and barely tinted, with a hard rim
     * and pronounced edge refraction - liquid glass. At one it is heavily blurred and close to
     * opaque - frost. Everything about the frosted look reads this one number.
     */
    public static double glass() {
        Config config = Config.get();
        return config == null ? 0.5 : config.glass.get();
    }

    /**
     * How far the scene behind is smeared. Separate from {@link #glass()} because blur and
     * refraction pull in opposite directions: smearing the background hides the very bending
     * that makes glass look liquid, so tying them to one control meant sharp and liquid could
     * never happen together.
     */
    public static double glassBlur() {
        Config config = Config.get();
        return config == null ? 0.6 : config.glassBlur.get();
    }

    /** How hard the edges bend the scene and split its colour. */
    public static double glassLiquid() {
        Config config = Config.get();
        return config == null ? 0 : config.glassLiquid.get();
    }

    /** 0 is a dark pane, 1 is white. The wash and the film both follow it. */
    public static double glassTint() {
        Config config = Config.get();
        return config == null ? 0 : config.glassTint.get();
    }

    /**
     * Whether the edge carries the accent. With it off the glass has no colour of its own and
     * shows only what it picks up from behind, which is closer to real glass.
     */
    public static boolean glassAccent() {
        Config config = Config.get();
        return config == null || config.glassAccent.get();
    }
}
