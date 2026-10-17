package meteordevelopment.meteorclient.utils.render;

/** Render-thread scope: Ember's extra submissions must not replace a cached vanilla HUD. */
public final class EmberGuiPass {
    private static int depth;

    private EmberGuiPass() {}

    public static boolean isActive() { return depth > 0; }
    public static void begin() { depth++; }
    public static void end() {
        if (depth == 0) throw new IllegalStateException("No Ember GUI pass is active");
        depth--;
    }
}
