package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** A dedicated background pass keeps stars behind controls and glass, never over their text. */
public final class EmberAtmosphere {
    private static final CelestialMesh MESH = new CelestialMesh(18000);
    private static final Color A = new Color(), B = new Color(), C = new Color();
    private static long previous;
    private static double time, mouseX, mouseY;
    private EmberAtmosphere() {}

    @PostInit public static void init() { MeteorClient.EVENT_BUS.subscribe(EmberAtmosphere.class); }

    public static void background(DrawContext context, GuiTheme theme, double width, double height,
                                  double mx, double my, double opacity, double darkness) {
        render(context, theme, width, height, mx, my, opacity, darkness, false);
    }

    @EventHandler private static void onRender(Render2DEvent event) {
        var config = Config.get();
        if (config == null || !config.ambience.get() || !config.ambienceHud.get() || mc.world == null
            || mc.currentScreen != null || mc.options.hudHidden) return;
        render(event.drawContext, GuiThemes.get(), event.screenWidth, event.screenHeight,
            event.screenWidth * 0.5, event.screenHeight * 0.5, 0.28, 0, true);
    }

    private static void render(DrawContext context, GuiTheme theme, double width, double height, double mx, double my,
                                double opacity, double darkness, boolean hud) {
        GuiRenderer renderer = new GuiRenderer(); renderer.theme = theme; renderer.begin(context);
        if (darkness > 0) renderer.quad(0, 0, width, height, new Color(0, 0, 0, (int) (darkness * opacity)));
        var config = Config.get();
        if (config != null && config.ambience.get() && opacity > 0.001) {
            long now = System.nanoTime();
            double dt = previous == 0 ? 0 : Math.clamp((now - previous) / 1e9, 0, 0.05); previous = now;
            double speed = config.ambienceSpeed.get();
            if (EmberAppearance.liquid() && EmberAppearance.motion() == 0) speed = 0;
            time += dt * speed;
            mouseX = CelestialGeometry.approach(mouseX, speed == 0 || hud ? 0 : (mx / Math.max(1, width) - 0.5) * 2, dt, 0.42);
            mouseY = CelestialGeometry.approach(mouseY, speed == 0 || hud ? 0 : (my / Math.max(1, height) - 0.5) * 2, dt, 0.42);
            var accent = EmberPalette.accent();
            MESH.clear();
            CelestialGeometry.atmosphere(MESH, width, height, time, mouseX, mouseY, config.ambienceDensity.get(),
                config.ambienceIntensity.get() * opacity, (accent.r << 16) | (accent.g << 8) | accent.b);
            MESH.faceScreen();
            for (int i = 0; i < MESH.size; i += 3) {
                rgba(A, MESH.colors[i]); rgba(B, MESH.colors[i + 1]); rgba(C, MESH.colors[i + 2]);
                int p = i * 3;
                renderer.triangle(MESH.positions[p], MESH.positions[p + 1], MESH.positions[p + 3], MESH.positions[p + 4],
                    MESH.positions[p + 6], MESH.positions[p + 7], A, B, C);
            }
        } else previous = 0;
        renderer.end();
    }
    private static void rgba(Color c, int packed) { c.set((packed >> 16) & 255, (packed >> 8) & 255, packed & 255, (packed >>> 24) & 255); }
}
