package meteordevelopment.meteorclient.gui.screens.menu;

/** Menu-only preferences. Accounts and Minecraft's own options stay in their existing stores. */
public final class MenuConfig {
    public int schema = 1;
    public boolean clock24 = false, showDate = true;
    public String language = "system";
    public Wallpaper wallpaper = Wallpaper.CHROME_LIVE;
    public int accent = 0xB49AFF;
    public double blurIntensity = .65, uiScale = 1;
    public boolean lightGlass = false, animations = true, parallax = true, magnification = true;
    public double animationSpeed = 1;
    public boolean music = true, clicks = true;
    public double musicVolume = .45;
    public int fpsLimit = 120;
    public boolean blur = true, reduceMotion = false;

    public enum Wallpaper {
        CHROME_LIVE("chrome", "wallpaper.live", "Logan Voss"),
        CHROME("chrome", "wallpaper.chrome", "Logan Voss"),
        MIDNIGHT("midnight", "wallpaper.midnight", "Fausto Garcia-Menendez");
        public final String file, key, artist;
        Wallpaper(String file, String key, String artist) { this.file = file; this.key = key; this.artist = artist; }
    }

    public void validate() {
        schema = 1;
        if (wallpaper == null) wallpaper = Wallpaper.CHROME_LIVE;
        if (!"en_us".equals(language) && !"fr_fr".equals(language)) language = "system";
        accent &= 0xFFFFFF;
        blurIntensity = finite(blurIntensity, .65, 0, 1);
        uiScale = finite(uiScale, 1, .8, 1.25);
        animationSpeed = finite(animationSpeed, 1, .4, 2);
        musicVolume = finite(musicVolume, .45, 0, 1);
        fpsLimit = Math.clamp(fpsLimit, 30, 240);
    }
    private static double finite(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
    public boolean motion() { return animations && !reduceMotion; }
}
