package meteordevelopment.meteorclient.gui.screens.menu;

/** Allocation-free dock layout/animation model. Rendering and hit testing share these bounds. */
public final class Dock {
    public enum Action { PLAY, ACCOUNTS, SETTINGS, DISPLAY, POWER }
    public static final Action[] ACTIONS = Action.values();
    public final double[] x = new double[5], y = new double[5], size = new double[5], tooltip = new double[5];
    private final MenuAnimation.Spring[] scale = new MenuAnimation.Spring[5], bounce = new MenuAnimation.Spring[5];
    public double left, top, width, height;
    public int focused = -1, active = -1;
    public Dock() {
        for (int i = 0; i < 5; i++) { scale[i] = new MenuAnimation.Spring(1); bounce[i] = new MenuAnimation.Spring(0); }
    }
    public void update(double viewportWidth, double viewportHeight, double mx, double my, double dt,
                       double reveal, boolean interactive, boolean motion, boolean magnify, double speed) {
        double total = 24;
        double baseTop = viewportHeight - MenuTheme.DOCK_BOTTOM + (motion ? (1 - reveal) * 32 : 0);
        for (int i = 0; i < 5; i++) {
            double center = x[i] + size[i] * .5;
            double dx = mx - center, dy = my - (baseTop + 35);
            double influence = interactive && my > baseTop - 70 && my < baseTop + 85 ? Math.exp(-(dx * dx) / (2 * 68 * 68) - dy * dy / (2 * 90 * 90)) : 0;
            double target = 1 + (magnify && motion ? .43 * influence : 0);
            if (motion) { scale[i].update(target, dt * speed, 5.5, .82); bounce[i].update(0, dt * speed, 4, .48); }
            else { scale[i].snap(1); bounce[i].snap(0); }
            size[i] = MenuTheme.DOCK_ICON * Math.clamp(scale[i].value, .9, 1.5);
            total += size[i] + MenuTheme.DOCK_GAP;
        }
        width = total; height = MenuTheme.DOCK_HEIGHT; left = (viewportWidth - width) / 2; top = baseTop;
        double cursor = left + 20;
        for (int i = 0; i < 5; i++) {
            x[i] = cursor; y[i] = top + 57 - size[i] + bounce[i].value;
            cursor += size[i] + MenuTheme.DOCK_GAP;
            boolean hover = interactive && contains(i, mx, my);
            tooltip[i] = MenuAnimation.smooth(tooltip[i], interactive && (hover || focused == i) ? 1 : 0, dt, MenuTheme.TOOLTIP_TAU);
        }
    }
    public boolean contains(int i, double mx, double my) {
        return mx >= x[i] - 5 && mx < x[i] + size[i] + 5 && my >= y[i] - 5 && my < top + height;
    }
    public int hit(double mx, double my) { for (int i = 4; i >= 0; i--) if (contains(i, mx, my)) return i; return -1; }
    public void click(int i, boolean motion) { active = i; if (motion) bounce[i].kick(-195); }
}
