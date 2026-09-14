package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.systems.config.Config;

/**
 * How Ember's surfaces are filled.
 *
 * Frosted is a translucent glass treatment - a thin panel, a white film and a lit top edge -
 * rather than a true gaussian blur of what is behind it. Sampling a blurred copy of the frame
 * per element would mean keeping a second framebuffer and a blur pass running during play,
 * which is a far heavier thing than a HUD panel should cost. Meteor's Blur module does the
 * real thing for menu backgrounds, and pairs well with this.
 */
public enum EmberAppearance {
    Solid,
    Frosted;

    public static EmberAppearance current() {
        Config config = Config.get();
        return config == null ? Solid : config.appearance.get();
    }

    public static boolean frosted() {
        return current() == Frosted;
    }

    /** Panel opacity, out of 255, for the current appearance. */
    public static int panelAlpha() {
        return frosted() ? 132 : 242;
    }

    /**
     * How solid the glass is, 0 to 1. At zero it is clear and barely tinted, with a hard rim
     * and pronounced edge refraction - liquid glass. At one it is heavily blurred and close to
     * opaque - frost. Everything about the frosted look reads this one number.
     */
    public static double glass() {
        Config config = Config.get();
        return config == null ? 0.45 : config.glass.get();
    }
}
