package meteordevelopment.meteorclient.gui.screens;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.misc.SpotifyPlayback;
import net.minecraft.util.Util;

/** Connection setup uses public client identifiers; Spotify tokens stay in the local game folder. */
public class EmberLyricsSettingsScreen extends WindowScreen {
    private WLabel connection;

    public EmberLyricsSettingsScreen(GuiTheme theme) { super(theme, "Spotify / Lyrics"); }

    @Override
    public void initWidgets() {
        Settings view = new Settings();
        view.groups.add(Config.get().sgLyrics);
        add(theme.settings(view)).expandX();
        connection = add(theme.label(SpotifyPlayback.status())).widget();
        add(theme.button("Connect regular Spotify")).expandX().widget().action = SpotifyPlayback::connect;
        add(theme.button("Enable local Spicetify bridge")).expandX().widget().action = SpotifyPlayback::enableBridge;
        add(theme.button("Disconnect Spotify")).expandX().widget().action = SpotifyPlayback::disconnect;
        add(theme.button("Spicy Lyrics key dashboard")).expandX().widget().action =
            () -> Util.getOperatingSystem().open("https://developers.spicylyrics.org/dashboard/applications");
        add(theme.button("Spotify app dashboard")).expandX().widget().action =
            () -> Util.getOperatingSystem().open("https://developer.spotify.com/dashboard");
        add(theme.label("Spotify redirect URI: " + SpotifyPlayback.REDIRECT));
        add(theme.label("For native access, allow requests without an Origin header on your Spicy Lyrics client key."));
    }

    @Override
    public void tick() {
        super.tick();
        if (connection != null) connection.set(SpotifyPlayback.status());
    }
}
