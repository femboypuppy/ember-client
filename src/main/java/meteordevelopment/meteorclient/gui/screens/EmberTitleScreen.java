package meteordevelopment.meteorclient.gui.screens;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import static org.lwjgl.glfw.GLFW.*;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.renderer.text.CustomTextRenderer;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.*;
import meteordevelopment.meteorclient.utils.render.color.Color;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerWarningScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Clock-first startup with a small glass action dock. Child screens return to this instance. */
public final class EmberTitleScreen extends Screen {
    private record Label(
            String text,
            double x,
            double y,
            double height,
            Color color,
            boolean centered,
            double maxWidth) {}

    private record Hit(StartMenuLayout.Rect rect, Runnable action) {}

    private static final Color WHITE = new Color(250, 250, 253, 255),
            DIM = new Color(205, 208, 217, 255),
            RED = new Color(255, 105, 113, 255);
    private static boolean shown;
    private final List<Hit> hits = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final GuiRenderer renderer = new GuiRenderer();
    private final EmberAnim.Clock clock = new EmberAnim.Clock();
    private boolean locked, customizing, keyboardFocus;
    private int focused, customFocused, customButton, heldUnlockKey = -1;
    private double lastMouseX = -1, lastMouseY = -1;
    private double reveal, openFade, panelFade;
    private long clockSecond = -1;
    private boolean last24;
    private String time = "", date = "";
    private StartMenuLayout.Frame frame;

    public EmberTitleScreen(boolean locked) {
        super(Text.literal("Ember Client"));
        this.locked = locked;
        reveal = locked ? 0 : 1;
        shown = true;
    }

    public static EmberTitleScreen firstScreen() {
        return new EmberTitleScreen(!shown);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {}

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        Utils.unscaledProjection();
        try {
            double dt = clock.tick();
            openFade = EmberAnim.approach(openFade, 1, dt, .2);
            reveal = EmberAnim.approach(reveal, locked ? 0 : 1, dt, .1);
            panelFade = EmberAnim.approach(panelFade, customizing ? 1 : 0, dt, .08);
            int w = Utils.getWindowWidth(), h = Utils.getWindowHeight();
            double scale = mc.getWindow().getScaleFactor(),
                    mx = mouseX * scale,
                    my = mouseY * scale;
            if (lastMouseX >= 0
                    && (Math.abs(mx - lastMouseX) > .1 || Math.abs(my - lastMouseY) > .1))
                keyboardFocus = false;
            lastMouseX = mx;
            lastMouseY = my;
            frame = StartMenuLayout.frame(w, h, reveal);
            double u = frame.unit();
            hits.clear();
            labels.clear();
            EmberMenuWallpaper.drawPixels(context);
            if (reveal > .02) EmberGlass.ensureCaptured();
            GuiRenderer r = renderer;
            r.theme = GuiThemes.get();
            r.begin(context);
            if (openFade < .995)
                r.quad(0, 0, w, h, new Color(0, 0, 0, (int) (255 * (1 - openFade))));
            label("EMBER", 30 * u, 25 * u, 15 * u, DIM, false, 150 * u);
            if (!locked && !customizing && reveal > .9) {
                var accounts = new StartMenuLayout.Rect(w - 154 * u, 20 * u, 124 * u, 30 * u);
                r.roundedRect(accounts.x(), accounts.y(), accounts.w(), accounts.h(), 8 * u,
                    new Color(255, 255, 255, accounts.contains(mx, my) ? 35 : 18));
                label("Accounts", accounts.x() + accounts.w() / 2, accounts.y() + 7 * u,
                    17 * u, alpha(WHITE, reveal), true, accounts.w() - 12 * u);
                hits.add(new Hit(accounts, () -> mc.setScreen(GuiThemes.get().accountsScreen())));
            }
            updateClock();
            if (panelFade < .99) {
                Color clockColor = alpha(WHITE, openFade * (1 - panelFade));
                label(date, w / 2.0, frame.clockY() - 26 * u, 20 * u, clockColor, true, w - 32);
            }
            if (reveal > .02) {
                var dock = frame.dock();
                pane(r, dock, 21 * u, reveal);
                for (var action : StartMenuLayout.Action.values()) {
                    var rect = frame.buttons()[action.ordinal()];
                    boolean hover = rect.contains(mx, my);
                    if (hover && !customizing && !keyboardFocus) focused = action.ordinal();
                    boolean active =
                            !customizing && (keyboardFocus ? focused == action.ordinal() : hover);
                    if (active)
                        r.roundedRect(
                                rect.x(),
                                rect.y(),
                                rect.w(),
                                rect.h(),
                                15 * u,
                                new Color(255, 255, 255, 28));
                    glyph(
                            r,
                            action,
                            rect.x() + rect.w() / 2,
                            rect.y() + rect.h() / 2,
                            21 * u,
                            alpha(action == StartMenuLayout.Action.Quit ? RED : WHITE, reveal));
                    if (!locked && !customizing && reveal > .9)
                        hits.add(new Hit(rect, () -> activate(action)));
                }
                if (!customizing) {
                    var action = StartMenuLayout.Action.values()[focused];
                    label(
                            action.label,
                            w / 2.0,
                            dock.y() + dock.h() + 14 * u,
                            18 * u,
                            alpha(WHITE, reveal),
                            true,
                            w - 32);
                }
            }
            if (locked)
                label(
                        "Click anywhere or press Enter",
                        w / 2.0,
                        h * .83,
                        18 * u,
                        alpha(DIM, openFade * (1 - reveal)),
                        true,
                        w - 32);
            if (panelFade > .01) customization(r, panelFade, mx, my);
            label(
                    "Ember Client " + MeteorClient.VERSION,
                    24 * u,
                    h - 27 * u,
                    Math.max(10, 13 * u),
                    DIM,
                    false,
                    w * .45);
            var wallpaper = Config.get().startWallpaper.get();
            label(
                    "Wallpaper: " + wallpaper.artist,
                    w - 24 * u,
                    h - 27 * u,
                    Math.max(10, 13 * u),
                    DIM,
                    false,
                    -w * .43);
            if (!locked && !customizing)
                hits.add(
                        new Hit(
                                new StartMenuLayout.Rect(w * .56, h - 34 * u, w * .43, 30 * u),
                                () -> Util.getOperatingSystem().open(wallpaper.url)));
            r.end();
            if (panelFade < .99)
                EmberClockText.draw(
                        time,
                        w / 2.0,
                        frame.clockY(),
                        frame.clockHeight(),
                        alpha(WHITE, openFade * (1 - panelFade)));
            renderLabels(context);
        } finally {
            Utils.scaledProjection();
        }
    }

    private void updateClock() {
        long second = System.currentTimeMillis() / 1000;
        boolean hour24 = Config.get().startClock24.get();
        if (clockSecond == second && last24 == hour24) return;
        clockSecond = second;
        last24 = hour24;
        var now = LocalDateTime.now();
        time =
                now.format(
                        DateTimeFormatter.ofPattern(
                                hour24 ? "HH:mm" : "h:mm", Locale.getDefault()));
        if (!hour24)
            time += " " + now.format(DateTimeFormatter.ofPattern("a", Locale.getDefault()));
        date = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()));
    }

    private void customization(GuiRenderer r, double fade, double mx, double my) {
        customButton = 0;
        var p = frame.panel();
        double u = frame.unit();
        pane(r, p, 20 * u, fade);
        label(
                "Make it yours",
                p.x() + 22 * u,
                p.y() + 17 * u,
                24 * u,
                alpha(WHITE, fade),
                false,
                p.w() - 44 * u);
        double gap = 8 * u, bw = (p.w() - 52 * u) / 2, x = p.x() + 22 * u;
        button(
                r,
                new StartMenuLayout.Rect(x, p.y() + 59 * u, bw, 36 * u),
                "Lock screen",
                true,
                () -> {
                    customizing = false;
                    locked = true;
                },
                fade,
                mx,
                my);
        button(
                r,
                new StartMenuLayout.Rect(x + bw + gap, p.y() + 59 * u, bw, 36 * u),
                "Classic Minecraft",
                false,
                () -> {
                    Config.get().startMenu.set(StartMenuLayout.Style.Classic);
                    Config.get().save();
                    mc.setScreen(new TitleScreen());
                },
                fade,
                mx,
                my);
        label("WALLPAPER", x, p.y() + 109 * u, 13 * u, alpha(DIM, fade), false, p.w() - 44 * u);
        for (var wallpaper : StartMenuLayout.Wallpaper.values()) {
            button(
                    r,
                    new StartMenuLayout.Rect(
                            x + wallpaper.ordinal() * (bw + gap), p.y() + 132 * u, bw, 34 * u),
                    wallpaper.name(),
                    Config.get().startWallpaper.get() == wallpaper,
                    () -> {
                        Config.get().startWallpaper.set(wallpaper);
                        Config.get().save();
                    },
                    fade,
                    mx,
                    my);
        }
        button(
                r,
                new StartMenuLayout.Rect(x, p.y() + 179 * u, p.w() - 44 * u, 30 * u),
                Config.get().startClock24.get() ? "24-hour clock: On" : "24-hour clock: Off",
                false,
                () -> {
                    Config.get().startClock24.set(!Config.get().startClock24.get());
                    Config.get().save();
                },
                fade,
                mx,
                my);
        button(
                r,
                new StartMenuLayout.Rect(
                        p.x() + p.w() / 2 - 52 * u, p.y() + 220 * u, 104 * u, 26 * u),
                "Done",
                false,
                () -> customizing = false,
                fade,
                mx,
                my);
    }

    private void pane(GuiRenderer r, StartMenuLayout.Rect rect, double radius, double opacity) {
        float fade = (float) opacity;
        EmberUI.shadow(r, rect.x(), rect.y(), rect.w(), rect.h(), fade);
        EmberUI.rim(r, rect.x(), rect.y(), rect.w(), rect.h(), radius, fade);
        r.roundedRect(
                rect.x(), rect.y(), rect.w(), rect.h(), radius, EmberUI.alpha(EmberUI.bg(), fade));
        EmberUI.gloss(r, rect.x(), rect.y(), rect.w(), rect.h(), radius, fade);
    }

    private void button(
            GuiRenderer r,
            StartMenuLayout.Rect rect,
            String title,
            boolean selected,
            Runnable action,
            double fade,
            double mx,
            double my) {
        int index = customButton++;
        if (rect.contains(mx, my) && !keyboardFocus) customFocused = index;
        boolean focus = keyboardFocus && customFocused == index;
        r.roundedRect(
                rect.x(),
                rect.y(),
                rect.w(),
                rect.h(),
                8 * frame.unit(),
                new Color(
                        255,
                        255,
                        255,
                        (int)
                                ((focus ? 65 : selected ? 48 : rect.contains(mx, my) ? 30 : 15)
                                        * fade)));
        label(
                title,
                rect.x() + rect.w() / 2,
                rect.y() + 7 * frame.unit(),
                17 * frame.unit(),
                alpha(WHITE, fade),
                true,
                rect.w() - 12 * frame.unit());
        if (customizing && panelFade > .85) hits.add(new Hit(rect, action));
    }

    private void label(
            String text,
            double x,
            double y,
            double height,
            Color color,
            boolean center,
            double maxWidth) {
        labels.add(new Label(text, x, y, height, color, center, maxWidth));
    }

    private void renderLabels(DrawContext context) {
        CustomTextRenderer renderer = EmberMenuFont.renderer();
        for (var label : labels) {
            if (label.color.a == 0) continue;
            // The menu has its own linear font scale; HUD preferences cannot enlarge it.
            renderer.begin(label.height / 18);
            try {
                double limit = Math.abs(label.maxWidth);
                String text = label.text;
                while (!text.isEmpty() && renderer.getWidth(text) > limit)
                    text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
                double width = renderer.getWidth(text),
                        x =
                                label.centered
                                        ? label.x - width / 2
                                        : label.maxWidth < 0 ? label.x - width : label.x;
                renderer.render(text, x, label.y, label.color, false);
            } finally {
                renderer.end();
            }
        }
    }

    private static Color alpha(Color c, double amount) {
        return new Color(c.r, c.g, c.b, (int) (c.a * Math.clamp(amount, 0, 1)));
    }

    private void activate(StartMenuLayout.Action action) {
        switch (action) {
            case Play -> mc.setScreen(new SelectWorldScreen(this));
            case Multiplayer -> mc.setScreen(mc.options.skipMultiplayerWarning
                ? new MultiplayerScreen(this) : new MultiplayerWarningScreen(this));
            case Settings -> mc.setScreen(new OptionsScreen(this, mc.options));
            case Menu -> {
                customizing = !customizing;
                customFocused = 0;
            }
            case Quit -> mc.scheduleStop();
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != GLFW_MOUSE_BUTTON_LEFT) return false;
        keyboardFocus = false;
        if (locked) {
            locked = false;
            keyboardFocus = false;
            return true;
        }
        if (frame == null) return false;
        double scale = mc.getWindow().getScaleFactor(),
                x = click.x() * scale,
                y = click.y() * scale;
        for (var hit : hits)
            if (hit.rect.contains(x, y)) {
                hit.action.run();
                return true;
            }
        if (customizing && !frame.panel().contains(x, y)) customizing = false;
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == heldUnlockKey) return true;
        if (locked) {
            if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER || key == GLFW_KEY_SPACE) {
                locked = false;
                keyboardFocus = true;
                heldUnlockKey = key;
            }
            return true;
        }
        if (key == GLFW_KEY_ESCAPE) {
            if (customizing) customizing = false;
            else locked = true;
            return true;
        }
        if (customizing) {
            if (key == GLFW_KEY_TAB
                    || key == GLFW_KEY_RIGHT
                    || key == GLFW_KEY_DOWN
                    || key == GLFW_KEY_LEFT
                    || key == GLFW_KEY_UP) {
                int direction =
                        key == GLFW_KEY_LEFT
                                        || key == GLFW_KEY_UP
                                        || key == GLFW_KEY_TAB
                                                && (input.modifiers() & GLFW_MOD_SHIFT) != 0
                                ? -1
                                : 1;
                customFocused = Math.floorMod(customFocused + direction, 6);
                keyboardFocus = true;
                return true;
            }
            if ((key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER || key == GLFW_KEY_SPACE)
                    && panelFade > .85
                    && hits.size() == 6) {
                heldUnlockKey = key;
                hits.get(customFocused).action.run();
            }
            return true;
        }
        if (key == GLFW_KEY_TAB || key == GLFW_KEY_RIGHT || key == GLFW_KEY_LEFT) {
            int direction =
                    key == GLFW_KEY_LEFT
                                    || key == GLFW_KEY_TAB
                                            && (input.modifiers() & GLFW_MOD_SHIFT) != 0
                            ? -1
                            : 1;
            focused = Math.floorMod(focused + direction, 5);
            keyboardFocus = true;
            return true;
        }
        if ((key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER || key == GLFW_KEY_SPACE)
                && reveal > .9) {
            heldUnlockKey = key;
            activate(StartMenuLayout.Action.values()[focused]);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean keyReleased(KeyInput input) {
        if (input.key() == heldUnlockKey) heldUnlockKey = -1;
        return super.keyReleased(input);
    }

    @Override
    public void close() {
        customizing = false;
        locked = true;
    }

    private static void glyph(
            GuiRenderer r,
            StartMenuLayout.Action action,
            double x,
            double y,
            double size,
            Color color) {
        double s = size / 24;
        switch (action) {
            case Play ->
                    r.triangle(x - 6 * s, y - 9 * s, x - 6 * s, y + 9 * s, x + 10 * s, y, color);
            case Multiplayer -> {
                person(r, x - 6 * s, y + 1 * s, s * .78, color);
                person(r, x + 6 * s, y + 1 * s, s * .78, color);
            }
            case Settings -> {
                ring(r, x, y, 7 * s, 2.4 * s, 0, Math.PI * 2, color);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    EmberUI.bar(
                            r,
                            x + Math.cos(a) * 7 * s,
                            y + Math.sin(a) * 7 * s,
                            x + Math.cos(a) * 10 * s,
                            y + Math.sin(a) * 10 * s,
                            3 * s,
                            color);
                }
                ring(r, x, y, 2.3 * s, 1.8 * s, 0, Math.PI * 2, color);
            }
            case Menu -> {
                ringBox(r, x - 11 * s, y - 8 * s, 22 * s, 16 * s, 2 * s, color);
                EmberUI.bar(r, x, y + 8 * s, x, y + 12 * s, 2 * s, color);
                EmberUI.bar(r, x - 5 * s, y + 12 * s, x + 5 * s, y + 12 * s, 2 * s, color);
                r.roundedRect(x - 6 * s, y - 4 * s, 4 * s, 4 * s, s, color);
                EmberUI.bar(r, x + 1 * s, y - 3 * s, x + 7 * s, y - 3 * s, 1.6 * s, color);
                EmberUI.bar(r, x - 6 * s, y + 3 * s, x + 7 * s, y + 3 * s, 1.6 * s, color);
            }
            case Quit -> {
                ring(
                        r,
                        x,
                        y + 1 * s,
                        9 * s,
                        2.4 * s,
                        -Math.PI / 2 + .55,
                        Math.PI * 1.5 - .55,
                        color);
                EmberUI.bar(r, x, y - 11 * s, x, y - 1 * s, 2.6 * s, color);
            }
        }
    }

    private static void person(GuiRenderer r, double x, double y, double s, Color c) {
        r.roundedRect(x - 4 * s, y - 10 * s, 8 * s, 8 * s, 4 * s, c);
        r.roundedRect(x - 7 * s, y, 14 * s, 10 * s, 4 * s, c);
    }

    private static void ringBox(
            GuiRenderer r, double x, double y, double w, double h, double t, Color c) {
        r.roundedRect(x, y, w, t, t / 2, c);
        r.roundedRect(x, y + h - t, w, t, t / 2, c);
        r.roundedRect(x, y, t, h, t / 2, c);
        r.roundedRect(x + w - t, y, t, h, t / 2, c);
    }

    private static void ring(
            GuiRenderer r,
            double x,
            double y,
            double radius,
            double thickness,
            double start,
            double end,
            Color c) {
        int count = 32;
        for (int i = 0; i < count; i++) {
            double a = start + (end - start) * i / count,
                    b = start + (end - start) * (i + 1) / count;
            EmberUI.bar(
                    r,
                    x + Math.cos(a) * radius,
                    y + Math.sin(a) * radius,
                    x + Math.cos(b) * radius,
                    y + Math.sin(b) * radius,
                    thickness,
                    c);
        }
    }
}
