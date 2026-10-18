package meteordevelopment.meteorclient.utils.render;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;

import net.minecraft.client.gui.DrawContext;

/** Bundled, resource-managed wallpapers. There is no network request during startup. */
public final class EmberMenuWallpaper {
    private static GuiRenderer renderer;

    private EmberMenuWallpaper() {}

    public static boolean enabled() {
        var c = Config.get();
        return c != null
                && c.startMenu.get() == StartMenuLayout.Style.LockScreen
                && mc.world == null;
    }

    public static void renderScaled(DrawContext context) {
        Utils.unscaledProjection();
        try {
            drawPixels(context);
        } finally {
            Utils.scaledProjection();
        }
    }

    public static void drawPixels(DrawContext context) {
        int width = Utils.getWindowWidth(), height = Utils.getWindowHeight();
        var wallpaper = Config.get().startWallpaper.get();
        var texture =
                mc.getTextureManager()
                        .getTexture(
                                MeteorClient.identifier(
                                        "textures/menu/" + wallpaper.file + ".png"));
        var cover = StartMenuLayout.cover(width, height, 2560, 1440);
        Renderer2D.TEXTURE.begin();
        Renderer2D.TEXTURE.texQuad(
                cover.x(), cover.y(), cover.width(), cover.height(), new Color(255, 255, 255, 255));
        Renderer2D.TEXTURE.render(texture.getGlTextureView(), texture.getSampler());
        if (renderer == null) renderer = new GuiRenderer();
        GuiRenderer r = renderer;
        r.theme = GuiThemes.get();
        r.begin(context);
        r.quad(
                0,
                0,
                width,
                height,
                new Color(0, 0, 0, 115),
                new Color(0, 0, 0, 115),
                new Color(0, 0, 0, 30),
                new Color(0, 0, 0, 30));
        r.end();
    }
}
