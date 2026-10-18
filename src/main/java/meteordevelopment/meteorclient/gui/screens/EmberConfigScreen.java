package meteordevelopment.meteorclient.gui.screens;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.Utils.*;

import static org.lwjgl.glfw.GLFW.*;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.systems.profiles.*;
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.color.Color;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.util.Util;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Saved snapshots and portable sharing, with explicit actions and clipped library rows. */
public class EmberConfigScreen extends WidgetScreen {
    private record Hit(double x, double y, double w, double h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final EmberAnim.Clock clock = new EmberAnim.Clock();
    private final Map<String, Float> animations = new HashMap<>();
    private Profile selected;
    private String name = "",
            query = "",
            status = "Save a snapshot, or import a shared config.",
            armed = "";
    private int field;
    private boolean selectAll,
            busy,
            includeAppearance = true,
            includeModules = true,
            includeHud = true,
            includeMacros,
            includeWaypoints,
            clipping;
    private long armedUntil;
    private double px,
            py,
            pw,
            ph,
            mx,
            my,
            scroll,
            detailScroll,
            listX,
            listY,
            listW,
            listH,
            clipTop,
            clipBottom;
    private float fade;

    public EmberConfigScreen(GuiTheme theme) {
        super(theme, "Configs");
    }

    @Override
    public void initWidgets() {
        clear();
        Profiles.get().discover();
    }

    @Override
    public void mouseMoved(double x, double y) {
        double s = mc.getWindow().getScaleFactor();
        mx = x * s;
        my = y * s;
        super.mouseMoved(x, y);
    }

    private List<Profile> profiles() {
        return Profiles.get().getAll().stream()
                .filter(
                        p ->
                                p.name.get()
                                        .toLowerCase(Locale.ROOT)
                                        .contains(query.toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(p -> p.name.get().toLowerCase(Locale.ROOT)))
                .toList();
    }

    @Override
    protected void onRenderBefore(DrawContext context, float delta) {
        float dt = clock.tick();
        fade = EmberAnim.approach(fade, 1, dt, .09);
        pw = Math.min(760, getWindowWidth() - 32);
        ph = Math.min(510, getWindowHeight() - 32);
        px = (getWindowWidth() - pw) / 2;
        py = (getWindowHeight() - ph) / 2;
        hits.clear();
        GuiRenderer r = new GuiRenderer();
        r.theme = theme;
        r.begin(context);
        r.scissorStart(0, 0, getWindowWidth(), getWindowHeight());
        r.quad(0, 0, getWindowWidth(), getWindowHeight(), new Color(0, 0, 0, (int) (140 * fade)));
        r.scissorEnd();
        EmberUI.shadow(r, px, py, pw, ph, fade);
        EmberUI.rim(r, px, py, pw, ph, 13, fade);
        r.roundedRect(px, py, pw, ph, 13, EmberUI.bg());
        EmberUI.gloss(r, px, py, pw, ph, 13, fade);
        text(r, "Config library", px + 22, py + 18, 1.1, EmberUI.TEXT, pw - 120);
        text(
                r,
                "Your setup, ready to keep or share.",
                px + 22,
                py + 44,
                .72,
                EmberUI.TEXT_DIM,
                pw - 100);
        button(r, px + pw - 58, py + 16, 36, 30, "X", this::close, false);
        double toolW = (pw - 68) / 4;
        button(
                r,
                px + 22,
                py + 78,
                toolW,
                32,
                "New snapshot",
                () -> {
                    selected = null;
                    name = "";
                    field = 1;
                    armed = "";
                    detailScroll = 0;
                },
                false);
        button(r, px + 30 + toolW, py + 78, toolW, 32, "Import file", () -> dialog(false), false);
        button(r, px + 38 + toolW * 2, py + 78, toolW, 32, "Paste share code", this::paste, false);
        button(
                r,
                px + 46 + toolW * 3,
                py + 78,
                toolW,
                32,
                "Open folder",
                () -> Util.getOperatingSystem().open(Profiles.FOLDER),
                false);
        listX = px + 22;
        listY = py + 164;
        listW = Math.floor((pw - 54) * .41);
        listH = ph - 218;
        double dx = listX + listW + 18, dw = px + pw - 22 - dx;
        input(r, listX, py + 124, listW, 30, query, "Search configs...", 2);
        var list = profiles();
        scroll = Math.clamp(scroll, 0, Math.max(0, list.size() * 62 - listH));
        r.roundedRect(listX, listY, listW, listH, 9, new Color(0, 0, 0, 28));
        clipping = true;
        clipTop = listY;
        clipBottom = listY + listH;
        r.scissorStart(listX, listY, listW, listH);
        double rowY = listY - scroll;
        for (Profile p : list) {
            if (rowY + 56 > listY && rowY < listY + listH) {
                double y = rowY;
                float a =
                        EmberAnim.approach(
                                animations.getOrDefault(p.name.get(), 0f),
                                selected == p ? 1 : 0,
                                dt,
                                .07);
                animations.put(p.name.get(), a);
                r.roundedRect(
                        listX,
                        y,
                        listW - 3,
                        56,
                        8,
                        a > .01 ? EmberUI.accent((int) (35 + 45 * a)) : EmberUI.raised());
                text(r, p.name.get(), listX + 13, y + 9, .85, EmberUI.TEXT, listW - 28);
                text(r, sections(p), listX + 13, y + 32, .65, EmberUI.TEXT_DIM, listW - 28);
                hit(listX, y, listW, 56, () -> select(p));
            }
            rowY += 62;
        }
        if (list.isEmpty())
            text(
                    r,
                    query.isBlank() ? "No saved configs yet" : "No matching configs",
                    listX + 12,
                    listY + 18,
                    .8,
                    EmberUI.TEXT_DIM,
                    listW - 24);
        r.scissorEnd();
        clipTop = py + 122;
        clipBottom = py + ph - 48;
        r.scissorStart(dx, clipTop, dw, clipBottom - clipTop);
        detailScroll =
                Math.clamp(
                        detailScroll,
                        0,
                        Math.max(0, (selected == null ? 258 : 337) - (clipBottom - clipTop)));
        double dy = py - detailScroll;
        text(
                r,
                selected == null ? "CREATE A SNAPSHOT" : "SAVED CONFIG",
                dx,
                dy + 126,
                .65,
                EmberUI.TEXT_DIM,
                dw);
        input(r, dx, dy + 150, dw, 34, name, "Give your config a name...", 1);
        text(r, "Include in this snapshot", dx, dy + 199, .78, EmberUI.TEXT, dw);
        double cw = (dw - 8) / 2;
        chip(
                r,
                dx,
                dy + 227,
                cw,
                "Appearance",
                includeAppearance,
                () -> includeAppearance = !includeAppearance);
        chip(
                r,
                dx + cw + 8,
                dy + 227,
                cw,
                "Modules",
                includeModules,
                () -> includeModules = !includeModules);
        chip(r, dx, dy + 264, cw, "HUD layout", includeHud, () -> includeHud = !includeHud);
        chip(
                r,
                dx + cw + 8,
                dy + 264,
                cw,
                "Macros",
                includeMacros,
                () -> includeMacros = !includeMacros);
        chip(
                r,
                dx,
                dy + 301,
                cw,
                "Waypoints",
                includeWaypoints,
                () -> includeWaypoints = !includeWaypoints);
        button(
                r,
                dx,
                dy + 345,
                dw,
                34,
                armed("save") ? "Click again to replace" : "Save current setup",
                this::saveSnapshot,
                true);
        if (selected != null) {
            double bw = (dw - 16) / 3;
            button(r, dx, dy + 389, bw, 32, "Load", this::load, true);
            button(r, dx + bw + 8, dy + 389, bw, 32, "Export file", () -> dialog(true), false);
            button(r, dx + (bw + 8) * 2, dy + 389, bw, 32, "Copy code", this::copy, false);
            button(
                    r,
                    dx,
                    dy + 431,
                    dw,
                    28,
                    armed("delete") ? "Click again to delete" : "Delete saved config",
                    this::delete,
                    false);
        }
        r.scissorEnd();
        clipping = false;
        r.quad(px + 22, py + ph - 40, pw - 44, 1, EmberUI.DIVIDER);
        text(
                r,
                busy ? "Working..." : status,
                px + 22,
                py + ph - 27,
                .68,
                EmberUI.TEXT_DIM,
                pw - 44);
        r.end();
    }

    private String sections(Profile p) {
        List<String> v = new ArrayList<>();
        if (p.appearance.get()) v.add("Appearance");
        if (p.modules.get()) v.add("Modules");
        if (p.hud.get()) v.add("HUD");
        if (p.macros.get()) v.add("Macros");
        if (p.waypoints.get()) v.add("Waypoints");
        return String.join(" \u00b7 ", v);
    }

    private void select(Profile p) {
        selected = p;
        name = p.name.get();
        field = 0;
        armed = "";
        detailScroll = 0;
        includeAppearance = p.appearance.get();
        includeModules = p.modules.get();
        includeHud = p.hud.get();
        includeMacros = p.macros.get();
        includeWaypoints = p.waypoints.get();
        status = "Load or share the saved snapshot. Saving updates it from your current setup.";
    }

    private void text(
            GuiRenderer r,
            String value,
            double x,
            double y,
            double scale,
            Color color,
            double max) {
        r.post(
                () -> {
                    var tr = theme.textRenderer();
                    tr.begin(theme.scale(scale));
                    String shown = value;
                    while (!shown.isEmpty() && tr.getWidth(shown) > max)
                        shown = shown.substring(0, shown.offsetByCodePoints(shown.length(), -1));
                    tr.render(shown, x, y, color, false);
                    tr.end();
                });
    }

    private void hit(double x, double y, double w, double h, Runnable action) {
        if (clipping) {
            double bottom = Math.min(y + h, clipBottom);
            y = Math.max(y, clipTop);
            h = bottom - y;
        }
        if (h > 0) hits.add(new Hit(x, y, w, h, action));
    }

    private void button(
            GuiRenderer r,
            double x,
            double y,
            double w,
            double h,
            String title,
            Runnable action,
            boolean primary) {
        boolean hover = new Hit(x, y, w, h, action).contains(mx, my);
        r.roundedRect(x, y, w, h, 7, primary ? EmberUI.accent(hover ? 135 : 95) : EmberUI.raised());
        if (hover) EmberUI.hoverFill(r, x, y, w, h, 1);
        text(r, title, x + 10, y + 8, .73, EmberUI.TEXT, w - 20);
        hit(
                x,
                y,
                w,
                h,
                () -> {
                    if (!busy) {
                        field = 0;
                        selectAll = false;
                        action.run();
                    }
                });
    }

    private void chip(
            GuiRenderer r,
            double x,
            double y,
            double w,
            String title,
            boolean checked,
            Runnable action) {
        button(r, x, y, w, 30, "    " + title, action, checked);
        r.roundedRect(x + 10, y + 10, 10, 10, 3, checked ? EmberUI.accent(255) : EmberUI.TEXT_DIM);
        r.roundedRect(x + 12, y + 12, 6, 6, 2, checked ? EmberUI.TEXT : EmberUI.raised());
    }

    private void input(
            GuiRenderer r,
            double x,
            double y,
            double w,
            double h,
            String value,
            String placeholder,
            int id) {
        r.roundedRect(x, y, w, h, 7, field == id ? EmberUI.accent(40) : EmberUI.WELL);
        text(
                r,
                value.isEmpty() ? placeholder : value + (field == id ? "|" : ""),
                x + 10,
                y + 8,
                .76,
                value.isEmpty() ? EmberUI.TEXT_DIM : EmberUI.TEXT,
                w - 20);
        hit(
                x,
                y,
                w,
                h,
                () -> {
                    field = id;
                    selectAll = false;
                    armed = "";
                });
    }

    private boolean armed(String action) {
        return armed.equals(action) && System.currentTimeMillis() < armedUntil;
    }

    private void saveSnapshot() {
        if (name.isBlank()) {
            status = "Enter a name first.";
            field = 1;
            return;
        }
        if (!(includeAppearance
                || includeModules
                || includeHud
                || includeMacros
                || includeWaypoints)) {
            status = "Select at least one section.";
            return;
        }
        String clean = ProfileArchive.cleanName(name);
        Profile p = Profiles.get().get(clean);
        if (p != null && !armed("save")) {
            armed = "save";
            armedUntil = System.currentTimeMillis() + 4000;
            return;
        }
        try {
            p = p == null ? new Profile() : new Profile(p.toTag());
            p.name.set(clean);
            p.appearance.set(includeAppearance);
            p.modules.set(includeModules);
            p.hud.set(includeHud);
            p.macros.set(includeMacros);
            p.waypoints.set(includeWaypoints);
            ProfileTransfer.saveSnapshot(p);
            Profiles.get().registerExisting(p);
            select(p);
            status = "Saved " + clean;
            armed = "";
        } catch (Exception e) {
            error(e);
        }
    }

    private void load() {
        try {
            selected.load();
            status = "Loaded " + selected.name.get();
        } catch (Exception e) {
            error(e);
        }
    }

    private void delete() {
        if (!armed("delete")) {
            armed = "delete";
            armedUntil = System.currentTimeMillis() + 4000;
            return;
        }
        Profiles.get().remove(selected);
        selected = null;
        name = "";
        armed = "";
        status = "Config deleted.";
    }

    private void copy() {
        try {
            mc.keyboard.setClipboard(
                    "ember:1:"
                            + Base64.getEncoder().encodeToString(ProfileTransfer.export(selected)));
            status = "Share code copied. The recipient can use Paste share code.";
        } catch (Exception e) {
            error(e);
        }
    }

    private void paste() {
        try {
            String code = mc.keyboard.getClipboard().strip();
            if (!code.startsWith("ember:1:") || code.length() > ProfileArchive.LIMIT * 2)
                throw new IllegalArgumentException("Copy an Ember share code first");
            importBytes(Base64.getDecoder().decode(code.substring(8)));
        } catch (Exception e) {
            error(e);
        }
    }

    private void importBytes(byte[] bytes) throws Exception {
        Profile p = ProfileTransfer.importBytes(bytes);
        query = "";
        select(p);
        status = "Imported " + p.name.get() + ". Click Load to apply it.";
    }

    private void dialog(boolean exporting) {
        Profile source = selected;
        busy = true;
        CompletableFuture.supplyAsync(
                        () -> {
                            try (var stack = MemoryStack.stackPush()) {
                                var filters = stack.mallocPointer(1);
                                filters.put(stack.UTF8("*.ember")).flip();
                                return exporting
                                        ? TinyFileDialogs.tinyfd_saveFileDialog(
                                                "Export Ember config",
                                                ProfileArchive.cleanName(source.name.get())
                                                        + ".ember",
                                                filters,
                                                "Ember config")
                                        : TinyFileDialogs.tinyfd_openFileDialog(
                                                "Import Ember config",
                                                null,
                                                filters,
                                                "Ember config",
                                                false);
                            }
                        })
                .whenComplete(
                        (path, failure) ->
                                mc.execute(
                                        () -> {
                                            busy = false;
                                            if (failure != null) {
                                                error(failure);
                                                return;
                                            }
                                            if (path == null) return;
                                            try {
                                                Path file = Path.of(path);
                                                if (exporting) {
                                                    if (!path.toLowerCase(Locale.ROOT)
                                                            .endsWith(".ember"))
                                                        file = Path.of(path + ".ember");
                                                    Files.write(
                                                            file, ProfileTransfer.export(source));
                                                    status = "Exported " + file.getFileName();
                                                } else {
                                                    if (Files.size(file) > ProfileArchive.LIMIT)
                                                        throw new IllegalArgumentException(
                                                                "Config is too large");
                                                    importBytes(Files.readAllBytes(file));
                                                }
                                            } catch (Exception e) {
                                                error(e);
                                            }
                                        }));
    }

    private void error(Throwable e) {
        status = e.getMessage() == null ? "Could not read or save this config." : e.getMessage();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(click, doubled);
        double s = mc.getWindow().getScaleFactor();
        for (Hit hit : hits)
            if (hit.contains(click.x() * s, click.y() * s)) {
                hit.action.run();
                return true;
            }
        field = 0;
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        double s = mc.getWindow().getScaleFactor();
        if (new Hit(listX, listY, listW, listH, () -> {}).contains(x * s, y * s))
            scroll = Math.max(0, scroll - v * 36);
        else if (x * s > listX + listW && y * s > py + 122 && y * s < py + ph - 48)
            detailScroll = Math.max(0, detailScroll - v * 30);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (field == 0) return super.keyPressed(input);
        String value = field == 1 ? name : query;
        boolean control = (input.modifiers() & GLFW_MOD_CONTROL) != 0;
        if (control && input.key() == GLFW_KEY_A) {
            selectAll = true;
            return true;
        }
        if (control && input.key() == GLFW_KEY_V) {
            String pasted = mc.keyboard.getClipboard().replaceAll("[^\\p{L}\\p{N} _-]", "");
            value = selectAll ? pasted : value + pasted;
            selectAll = false;
        } else if (input.key() == GLFW_KEY_BACKSPACE) {
            value =
                    selectAll
                            ? ""
                            : value.isEmpty()
                                    ? ""
                                    : value.substring(
                                            0, value.offsetByCodePoints(value.length(), -1));
            selectAll = false;
        } else if (input.key() == GLFW_KEY_ENTER || input.key() == GLFW_KEY_KP_ENTER) {
            if (field == 1) saveSnapshot();
            return true;
        }
        if (field == 1) name = value.substring(0, Math.min(value.length(), 32));
        else {
            query = value.substring(0, Math.min(value.length(), 64));
            scroll = 0;
        }
        return true;
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (field == 0) return super.charTyped(input);
        armed = "";
        int cp = input.codepoint();
        if (Character.isLetterOrDigit(cp) || cp == ' ' || cp == '_' || cp == '-') {
            String value = selectAll ? "" : field == 1 ? name : query;
            selectAll = false;
            if (value.length() < (field == 1 ? 32 : 64)) value += Character.toString(cp);
            if (field == 1) name = value;
            else {
                query = value;
                scroll = 0;
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (parent != null) mc.setScreen(parent);
        else super.close();
    }
}
