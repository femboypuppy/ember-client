package meteordevelopment.meteorclient.gui.screens.menu;

import com.google.gson.annotations.SerializedName;

/** Menu-only preferences. Accounts and Minecraft's own options stay in their existing stores. */
public final class MenuConfig {
    public static final double DEFAULT_UI_SCALE = .9;
    public int schema = 2;
    public boolean clock24 = false, showDate = true;
    public String language = "system";
    public Wallpaper wallpaper = Wallpaper.LIVE;
    public int accent = 0xB49AFF;
    public boolean matchWallpaperAccent = true;
    public double blurIntensity = .65, uiScale = DEFAULT_UI_SCALE;
    public boolean lightGlass = false, animations = true, parallax = true, magnification = true;
    public double animationSpeed = 1;
    public boolean music = true, clicks = true;
    public double musicVolume = .45;
    public int fpsLimit = 120;
    public boolean blur = true, reduceMotion = false;

    /** Selectable wallpapers. Live shares Nebula's artwork and adds slow drift; older saved names map to the closest new art. */
    public enum Wallpaper {
        @SerializedName(value = "LIVE", alternate = "CHROME_LIVE") LIVE("nebula", "wallpaper.live", "BoliviaInteligente", true, 0xA38CFA),
        NEBULA("nebula", "wallpaper.nebula", "BoliviaInteligente", false, 0xA38CFA),
        SILK("silk", "wallpaper.silk", "Pawel Czerwinski", false, 0xD467B4),
        @SerializedName(value = "OBSIDIAN", alternate = "CHROME") OBSIDIAN("obsidian", "wallpaper.obsidian", "Philip Oroni", false, 0x7E8C9F),
        @SerializedName(value = "SUMMIT", alternate = "MIDNIGHT") SUMMIT("summit", "wallpaper.summit", "Benjamin Voros", false, 0x748FCB),
        AURORA("aurora", "wallpaper.aurora", "Jonathan Bean", false, 0x58B879),
        DUNES("dunes", "wallpaper.dunes", "Fabian Struwe", false, 0xCE854B),
        FOREST("forest", "wallpaper.forest", "Dave Hoefler", false, 0x66968B),
        OCEAN("ocean", "wallpaper.ocean", "Josh Withers", false, 0x32ADAD);
        public final String file, key, artist;
        public final boolean live;
        /** Curated from the artwork, so rendering never scans or reads back texture pixels. */
        public final int accent;
        Wallpaper(String file, String key, String artist, boolean live, int accent) { this.file = file; this.key = key; this.artist = artist; this.live = live; this.accent = accent; }
    }

    public void validate() {
        // Move the previous default to 90%, while retaining explicitly customized scales.
        if (schema < 2 && uiScale == 1) uiScale = DEFAULT_UI_SCALE;
        schema = 2;
        if (wallpaper == null) wallpaper = Wallpaper.LIVE;
        if (!"en_us".equals(language) && !"fr_fr".equals(language)) language = "system";
        accent &= 0xFFFFFF;
        blurIntensity = finite(blurIntensity, .65, 0, 1);
        uiScale = finite(uiScale, DEFAULT_UI_SCALE, .8, 1.25);
        animationSpeed = finite(animationSpeed, 1, .4, 2);
        musicVolume = finite(musicVolume, .45, 0, 1);
        fpsLimit = Math.clamp(fpsLimit, 30, 240);
    }
    private static double finite(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
    public boolean motion() { return animations && !reduceMotion; }
    /** Keep the saved custom color separate so automatic matching never destroys it. */
    public int accentColor() { return matchWallpaperAccent ? wallpaper.accent : accent; }
    public void setCustomAccent(int color) { accent = color & 0xFFFFFF; matchWallpaperAccent = false; }
}
