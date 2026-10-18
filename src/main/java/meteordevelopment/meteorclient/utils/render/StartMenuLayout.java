package meteordevelopment.meteorclient.utils.render;

/** Framebuffer-pixel layout shared by drawing and input. No GUI-scale assumptions. */
public final class StartMenuLayout {
    public enum Style {
        LockScreen,
        Classic;

        @Override
        public String toString() {
            return this == LockScreen ? "Lock screen" : "Classic Minecraft";
        }
    }

    public enum Wallpaper {
        Chrome(
                "chrome",
                "Logan Voss",
                "https://unsplash.com/photos/abstract-metallic-fluid-shapes-on-black-background-Tvl0vDC_eUg"),
        Midnight(
                "midnight",
                "Fausto Garcia-Menendez",
                "https://unsplash.com/photos/silhouette-of-mountain-during-night-time-5uNXExF4TzI");
        public final String file, artist, url;

        Wallpaper(String file, String artist, String url) {
            this.file = file;
            this.artist = artist;
            this.url = url;
        }
    }

    public enum Action {
        Play("Play", "Singleplayer worlds"),
        Multiplayer("Multiplayer", "Join a server"),
        Settings("Settings", "Minecraft options"),
        Menu("Change menu", "Wallpaper and menu style"),
        Quit("Quit", "Close Minecraft");
        public final String label, detail;

        Action(String label, String detail) {
            this.label = label;
            this.detail = detail;
        }
    }

    public record Rect(double x, double y, double w, double h) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    public record Frame(
            double unit,
            Rect dock,
            Rect panel,
            double clockY,
            double clockHeight,
            Rect[] buttons) {}

    public record Cover(double x, double y, double width, double height) {}

    private StartMenuLayout() {}

    public static Frame frame(double width, double height, double reveal) {
        double u = Math.clamp(Math.min(width / 1600, height / 900), .35, 2.5);
        double w = 304 * u,
                h = 60 * u,
                x = (width - w) / 2,
                y = height * .67 + (1 - Math.clamp(reveal, 0, 1)) * 20 * u;
        Rect[] buttons = new Rect[5];
        for (int i = 0; i < 5; i++)
            buttons[i] = new Rect(x + (9 + i * 58) * u, y + 7 * u, 54 * u, 46 * u);
        double pw = Math.min(452 * u, width - 24), ph = 258 * u;
        Rect panel = new Rect((width - pw) / 2, Math.max(12, y - ph - 18 * u), pw, ph);
        return new Frame(
                u, new Rect(x, y, w, h), panel, height * .15 - reveal * 6 * u, 88 * u, buttons);
    }

    public static Cover cover(double width, double height, double imageWidth, double imageHeight) {
        double scale = Math.max(width / imageWidth, height / imageHeight);
        double w = imageWidth * scale, h = imageHeight * scale;
        return new Cover((width - w) / 2, (height - h) / 2, w, h);
    }
}
