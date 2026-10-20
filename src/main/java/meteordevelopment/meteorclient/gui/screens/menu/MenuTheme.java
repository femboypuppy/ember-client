package meteordevelopment.meteorclient.gui.screens.menu;

/** Virtual pixels at 1280x720; Minecraft GUI scaling is applied once by MenuScreen. */
public final class MenuTheme {
    public static final double WIDTH = 1280, HEIGHT = 720, RADIUS = 20, ROW = 54;
    public static final double DOCK_ICON = 44, DOCK_GAP = 16, DOCK_HEIGHT = 76, DOCK_BOTTOM = 126;
    public static final double BODY_CAP_HEIGHT = 12, CLOCK_CAP_HEIGHT = 70, CLOCK_SIZE = 86, CLOCK_INTRO_SECONDS = .65, DOCK_INTRO_SECONDS = .55;
    public static final double SETTINGS_OPEN_TAU = .18, SETTINGS_CLOSE_TAU = .10, TOOLTIP_TAU = .09;
    public static final int WHITE = 0xFFF7F8FC, DIM = 0xFFBFC3D0, RED = 0xFFFF686E;
    private MenuTheme() {}
    public static int alpha(int color, double alpha) {
        return (color & 0xFFFFFF) | (int) Math.round(((color >>> 24) & 255) * Math.clamp(alpha, 0, 1)) << 24;
    }
    public static int accent() { return 0xFF000000 | ConfigManager.get().accentColor(); }
    public static int text() { return ConfigManager.get().lightGlass ? 0xFF20232D : WHITE; }
    public static int dim() { return ConfigManager.get().lightGlass ? 0xFF626776 : DIM; }
    public static int glass() { return ConfigManager.get().lightGlass ? 0xB8EEF0F7 : 0xB8181B25; }
    public static int well() { return ConfigManager.get().lightGlass ? 0x13000000 : 0x16FFFFFF; }
}
