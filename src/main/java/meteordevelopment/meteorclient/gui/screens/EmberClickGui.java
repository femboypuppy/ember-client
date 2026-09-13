package meteordevelopment.meteorclient.gui.screens;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.XAnchor;
import meteordevelopment.meteorclient.systems.hud.YAnchor;
import meteordevelopment.meteorclient.systems.hud.screens.HudElementScreen;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.Utils.getWindowHeight;
import static meteordevelopment.meteorclient.utils.Utils.getWindowWidth;
import static org.lwjgl.glfw.GLFW.*;

/**
 * Ember's module menu. Flat near-black panels, a switch on every row, and depth from soft
 * shadows rather than glowing outlines - the style defined in {@link EmberUI}.
 */
public class EmberClickGui extends TabScreen {
    private static final Color BG_OVERLAY = new Color(0, 0, 0, 110);

    private static final double PW = 224;
    private static final double HH = 36;
    private static final double MH = 30;
    private static final double GAP = 16;

    /** Switch geometry, measured in from the right edge of a row. */
    private static final double SW_W = 26, SW_H = 14, SW_RIGHT = 14;
    /** The chevron that opens a row's settings sits just left of the switch. */
    private static final double CHEV_RIGHT = 52;

    private final List<Panel> panels = new ArrayList<>();
    private Panel dragging = null;
    private double dragOX, dragOY;
    private double mx, my;
    private double rawMx, rawMy;
    private String search = "";
    private boolean searchFocused = false;
    private float globalFade = 0f;
    private final EmberAnim.Clock clock = new EmberAnim.Clock();
    private float frameDt;

    private final Map<Object, Float> hoverAnims = new HashMap<>();
    private final Map<Object, Float> activeAnims = new HashMap<>();

    private final EmberSettingsPopup popup;

    private static final Map<String, double[]> saved = new HashMap<>();

    public EmberClickGui(GuiTheme theme) {
        super(theme, Tabs.get().getFirst());
        popup = new EmberSettingsPopup(theme);
        buildPanels();
    }

    private void buildPanels() {
        panels.clear();
        double sx = 12, sy = 46;

        for (Category cat : Modules.loopCategories()) {
            List<Module> mods = Modules.get().getGroup(cat);
            if (mods.isEmpty()) continue;

            Panel p = new Panel();
            p.category = cat;
            p.name = cat.name;
            p.modules = mods;
            p.icon = cat.icon;

            double[] s = saved.get(p.name);
            if (s != null) {
                p.x = s[0]; p.y = s[1]; p.collapsed = s[2] > 0;
            } else {
                p.x = sx; p.y = sy;
                sx += PW + GAP;
                if (sx + PW > getWindowWidth() - 12) { sx = 12; sy += 330; }
            }
            panels.add(p);
        }

        {
            Panel cp = new Panel();
            cp.name = "Client";
            cp.isClient = true;
            cp.icon = new ItemStack(Items.ENDER_EYE);
            List<ClientEntry> entries = new ArrayList<>();

            String[] wanted = {"ember-status-bar", "ember-bubbles", "ember-keybinds", "ember-armor",
                "ember-target", "ember-module-list", "ember-notifications", "spotify"};

            for (String wName : wanted) {
                HudElement found = null;
                for (HudElement el : Hud.get()) {
                    if (el.info.name.equals(wName)) { found = el; break; }
                }
                if (found == null) {
                    var info = Hud.get().infos.get(wName);
                    if (info != null) {
                        switch (wName) {
                            case "ember-module-list" -> Hud.get().add(info, -8, 8, XAnchor.Right, YAnchor.Top);
                            case "ember-notifications" -> Hud.get().add(info, -8, -8, XAnchor.Right, YAnchor.Bottom);
                            case "ember-status-bar" -> Hud.get().add(info, 8, 8, XAnchor.Left, YAnchor.Top);
                            case "ember-keybinds" -> Hud.get().add(info, 8, 112, XAnchor.Left, YAnchor.Top);
                            case "ember-armor" -> Hud.get().add(info, 8, -48, XAnchor.Left, YAnchor.Bottom);
                            case "ember-bubbles" -> Hud.get().add(info, 8, -8, XAnchor.Left, YAnchor.Bottom);
                            case "ember-target" -> Hud.get().add(info, 0, -52, XAnchor.Center, YAnchor.Bottom);
                            default -> Hud.get().add(info, 8, 56);
                        }
                        for (HudElement el : Hud.get()) {
                            if (el.info.name.equals(wName)) { found = el; break; }
                        }
                    }
                }
                if (found != null) entries.add(new ClientEntry(found.info.title, found));
            }

            // Meteor's plain list sits in the same corner as Ember's. This used to run only
            // when the Ember list was first created, so a config that already had both kept
            // drawing them on top of each other - hence the doubled, garbled module names.
            boolean emberList = false;
            for (HudElement el : Hud.get()) {
                if (el.info.name.equals("ember-module-list") && el.isActive()) emberList = true;
            }
            if (emberList) {
                for (HudElement el : Hud.get()) {
                    if (el.info.name.equals("active-modules") && el.isActive()) el.toggle();
                }
            }

            entries.add(new ClientEntry("Edit HUD Positions",
                () -> mc.setScreen(new meteordevelopment.meteorclient.systems.hud.screens.HudEditorScreen(theme))));

            cp.clientEntries = entries;

            double[] s = saved.get("Client");
            if (s != null) {
                cp.x = s[0]; cp.y = s[1]; cp.collapsed = s[2] > 0;
            } else {
                cp.x = sx; cp.y = sy;
            }
            panels.add(cp);
        }
    }

    @Override
    public void initWidgets() {
        clear();
    }

    private float anim(Object key, Map<Object, Float> map, float target, float speed, float dt) {
        float cur = map.getOrDefault(key, target == 1f ? 0f : target);
        cur = EmberAnim.approach(cur, target, dt, 0.8 / speed);
        map.put(key, cur);
        return cur;
    }

    /** This branch hands onRenderBefore no mouse position, so track the cursor here. */
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        double s = mc.getWindow().getScaleFactor();
        rawMx = mouseX * s;
        rawMy = mouseY * s;
        super.mouseMoved(mouseX, mouseY);
    }

    /**
     * Temporary instrumentation for the menu's own drawing cost, averaged so one slow frame
     * does not dominate. Printed while the menu is open; remove once the cost is settled.
     */
    private long perfNanos;
    private int perfFrames;

    @Override
    protected void onRenderBefore(DrawContext graphics, float delta) {
        long perfStart = System.nanoTime();
        renderAll(graphics, delta);

        perfNanos += System.nanoTime() - perfStart;
        if (++perfFrames >= 60) {
            System.out.printf("[EmberPerf] ClickGUI draw: %.2f ms/frame over %d frames (%d panels)%n",
                perfNanos / 1_000_000.0 / perfFrames, perfFrames, panels.size());
            perfNanos = 0;
            perfFrames = 0;
        }
    }

    private void renderAll(DrawContext graphics, float delta) {
        frameDt = clock.tick();

        boolean popupUp = popup.isVisible();
        mx = popupUp ? -10000 : rawMx;
        my = popupUp ? -10000 : rawMy;

        globalFade = Math.min(1f, globalFade + frameDt / 0.22f);
        float fade = easeOut(globalFade);

        if (dragging != null) {
            dragging.x = rawMx - dragOX;
            dragging.y = rawMy - dragOY;
        }

        for (Panel p : panels) {
            p.openAnim = EmberAnim.approach(p.openAnim, p.collapsed ? 0f : 1f, frameDt, 0.07);
            p.bodyH = computeBodyH(p);
        }

        GuiRenderer r = new GuiRenderer();
        r.theme = theme;
        r.begin(graphics);

        r.quad(0, 0, getWindowWidth(), getWindowHeight(), new Color(0, 0, 0, (int) (BG_OVERLAY.a * fade)));

        // No scissor needed to layer these under the panels: the renderer keeps glow in its
        // own batch and draws it before plain quads within a flush. Forcing a flush here just
        // to order them cost two full endRender passes - four batches and two text passes
        // each - every frame.
        for (Panel p : panels) {
            EmberUI.shadow(r, p.x, p.y, PW, HH + p.bodyH * p.openAnim, fade);
        }
        EmberUI.shadow(r, configButtonX(), configButtonY(), CFG_W, CFG_H, fade);
        EmberUI.shadow(r, gearX(), gearY(), GEAR, GEAR, fade);

        for (Panel p : panels) {
            if (p.isClient) drawClientPanel(r, graphics, p, fade);
            else drawPanel(r, graphics, p, fade);
        }

        drawSearchBar(r, fade);
        drawConfigButton(r, fade);
        drawGearButton(r, fade);

        r.end();

        drawAllPanelText();
        drawSearchText();
        drawConfigButtonText();

        // Its own pass, after the background text: the popup is modal, so its panel and
        // labels must sit above text that is drawn later in the frame.
        if (popup.isVisible()) {
            GuiRenderer pr = new GuiRenderer();
            pr.theme = theme;
            pr.begin(graphics);
            popup.render(pr, rawMx, rawMy, delta);
            pr.end();
        }
        popup.renderText(graphics);
    }

    private double computeBodyH(Panel p) {
        if (p.collapsed && p.openAnim <= 0.01f) return 0;
        if (p.isClient) return p.clientEntries == null ? 0 : Math.min(p.clientEntries.size() * MH, bodyLimit(p));
        return Math.min(filtered(p).size() * MH, bodyLimit(p));
    }

    private float easeOut(float t) {
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    /** Shared so other Ember screens follow the selected accent. */
    public static Color accent() {
        return EmberUI.accent();
    }

    private double bodyLimit(Panel p) {
        return Math.max(MH * 5, getWindowHeight() - p.y - HH - CFG_H - 60);
    }

    // --- Panel chrome ---

    private void drawPanelFrame(GuiRenderer r, DrawContext gfx, Panel p, float fade) {
        double x = p.x, y = p.y;
        double totalH = HH + p.bodyH * p.openAnim;

        r.roundedRect(x, y, PW, totalH, EmberUI.RADIUS, EmberUI.alpha(EmberUI.BG, fade));

        boolean hHover = mx >= x && mx < x + PW && my >= y && my < y + HH;
        float hAmt = anim("hdr_" + p.name, hoverAnims, hHover ? 1f : 0f, 10f, frameDt);

        // Header sits slightly above the body, brightening a touch under the cursor.
        Color hc = new Color(
            EmberUI.RAISED.r + (int) (10 * hAmt),
            EmberUI.RAISED.g + (int) (10 * hAmt),
            EmberUI.RAISED.b + (int) (12 * hAmt),
            (int) (255 * fade));

        if (p.openAnim < 0.05f) {
            r.roundedRect(x, y, PW, HH, EmberUI.RADIUS, hc);
        } else {
            r.roundedRect(x, y, PW, HH + EmberUI.RADIUS, EmberUI.RADIUS, hc);
            r.quad(x, y + HH - EmberUI.RADIUS, PW, EmberUI.RADIUS, hc);
            r.quad(x, y + HH, PW, 1, EmberUI.alpha(EmberUI.DIVIDER, fade));
        }

        EmberUI.chevron(r, x + PW - 17, y + HH / 2, 8,
            p.openAnim > 0.5f ? EmberUI.Direction.UP : EmberUI.Direction.DOWN,
            EmberUI.alpha(EmberUI.TEXT_DIM, fade));

        int ix = (int) (x + 11);
        int iy = (int) (y + (HH - 16) / 2);
        final ItemStack icon = p.icon;
        if (icon != null) r.post(() -> RenderUtils.drawItem(gfx, icon, ix, iy, 0.8f, false, null, false));
    }

    /** One row: hover lift, an optional settings chevron, and the switch on the right. */
    private void drawRowChrome(GuiRenderer r, double x, double rowY, float hA, float aA, boolean hasSettings, float fade) {
        EmberUI.hoverFill(r, x + 5, rowY + 1, PW - 10, MH - 2, hA);

        if (hasSettings && hA > 0.01f) {
            EmberUI.chevron(r, x + PW - CHEV_RIGHT, rowY + MH / 2, 7, EmberUI.Direction.RIGHT,
                EmberUI.alpha(EmberUI.TEXT_FAINT, (int) (200 * hA * fade)));
        }

        EmberUI.toggle(r, x + PW - SW_RIGHT - SW_W, rowY + (MH - SW_H) / 2, SW_W, SW_H, aA, fade);
    }

    private void drawPanel(GuiRenderer r, DrawContext gfx, Panel p, float fade) {
        drawPanelFrame(r, gfx, p, fade);
        if (p.openAnim < 0.02f) return;

        double x = p.x, y = p.y;
        double visBody = p.bodyH * p.openAnim;
        double rowY = y + HH - p.scroll;
        double clipT = y + HH, clipB = y + HH + visBody;

        for (Module m : filtered(p)) {
            if (rowY >= clipT - 0.5 && rowY + MH <= clipB + 0.5) {
                boolean hover = mx >= x && mx < x + PW && my >= rowY && my < rowY + MH && my < clipB;
                float hA = anim(m, hoverAnims, hover ? 1f : 0f, 12f, frameDt);
                float aA = anim(m, activeAnims, m.isActive() ? 1f : 0f, 9f, frameDt);

                drawRowChrome(r, x, rowY, hA, aA, hasSettings(m.settings), fade);
            }
            rowY += MH;
        }
    }

    private void drawClientPanel(GuiRenderer r, DrawContext gfx, Panel p, float fade) {
        drawPanelFrame(r, gfx, p, fade);
        if (p.openAnim < 0.02f || p.clientEntries == null) return;

        double x = p.x, y = p.y;
        double visBody = p.bodyH * p.openAnim;
        double rowY = y + HH - p.scroll;
        double clipT = y + HH, clipB = y + HH + visBody;

        for (ClientEntry entry : p.clientEntries) {
            if (rowY >= clipT - 0.5 && rowY + MH <= clipB + 0.5) {
                boolean hover = mx >= x && mx < x + PW && my >= rowY && my < rowY + MH && my < clipB;
                float hA = anim("cl_" + entry.name, hoverAnims, hover ? 1f : 0f, 12f, frameDt);

                if (entry.element == null) {
                    // Action row: no switch, just a chevron saying it opens something.
                    EmberUI.hoverFill(r, x + 5, rowY + 1, PW - 10, MH - 2, hA);
                    EmberUI.chevron(r, x + PW - 18, rowY + MH / 2, 8, EmberUI.Direction.RIGHT,
                        EmberUI.alpha(EmberUI.TEXT_DIM, (int) ((150 + 90 * hA) * fade)));
                } else {
                    float aA = anim("cl_" + entry.name, activeAnims, entry.element.isActive() ? 1f : 0f, 9f, frameDt);
                    drawRowChrome(r, x, rowY, hA, aA, hasSettings(entry.element.settings), fade);
                }
            }
            rowY += MH;
        }
    }

    // --- Text ---

    /**
     * All panel labels in two batches rather than two per panel. Each begin/end pair binds
     * the font and emits a draw call, so doing this per panel meant roughly two dozen flushes
     * a frame - the single biggest cost in this screen.
     */
    private void drawAllPanelText() {
        theme.textRenderer().begin(theme.scale(0.92));
        double headerH = theme.textHeight();

        for (Panel p : panels) {
            theme.textRenderer().render(p.isClient ? "Client" : p.name, p.x + 32,
                p.y + (HH - headerH) / 2, EmberUI.TEXT, false);
        }
        theme.textRenderer().end();

        theme.textRenderer().begin(theme.scale(0.82));
        double rowTextH = theme.textHeight();

        for (Panel p : panels) {
            if (p.openAnim < 0.02f) continue;

            double visBody = p.bodyH * p.openAnim;
            double rowY = p.y + HH - p.scroll;
            double clipT = p.y + HH, clipB = p.y + HH + visBody;

            if (p.isClient) {
                if (p.clientEntries == null) continue;

                for (ClientEntry entry : p.clientEntries) {
                    if (rowY >= clipT - 0.5 && rowY + MH <= clipB + 0.5) {
                        Color tc = entry.element == null
                            ? EmberUI.TEXT
                            : blendText(activeAnims.getOrDefault((Object) ("cl_" + entry.name), 0f));
                        theme.textRenderer().render(entry.name, p.x + 14,
                            rowY + (MH - rowTextH) / 2, tc, false);
                    }
                    rowY += MH;
                }
            } else {
                for (Module m : filtered(p)) {
                    if (rowY >= clipT - 0.5 && rowY + MH <= clipB + 0.5) {
                        theme.textRenderer().render(m.title, p.x + 14,
                            rowY + (MH - rowTextH) / 2,
                            blendText(activeAnims.getOrDefault((Object) m, 0f)), false);
                    }
                    rowY += MH;
                }
            }
        }
        theme.textRenderer().end();
    }

    /** Dim when off, full white when on - the switch carries the colour, not the label. */
    private Color blendText(float amount) {
        return new Color(
            (int) (EmberUI.TEXT_DIM.r + (EmberUI.TEXT.r - EmberUI.TEXT_DIM.r) * amount),
            (int) (EmberUI.TEXT_DIM.g + (EmberUI.TEXT.g - EmberUI.TEXT_DIM.g) * amount),
            (int) (EmberUI.TEXT_DIM.b + (EmberUI.TEXT.b - EmberUI.TEXT_DIM.b) * amount),
            255);
    }

    // --- Search ---

    private static final double SEARCH_W = 240, SEARCH_H = 28;

    private double searchX() { return (getWindowWidth() - SEARCH_W) / 2; }

    private double searchY() { return 10; }

    private void drawSearchBar(GuiRenderer r, float fade) {
        double x = searchX(), y = searchY();
        r.roundedRect(x, y, SEARCH_W, SEARCH_H, SEARCH_H / 2, EmberUI.alpha(EmberUI.RAISED, fade));

        if (searchFocused) {
            r.roundedRect(x, y, SEARCH_W, SEARCH_H, SEARCH_H / 2, EmberUI.accent((int) (28 * fade)));
        }

        // Magnifier: a ring with a short handle.
        double cx = x + 16, cy = y + SEARCH_H / 2;
        Color ic = EmberUI.alpha(searchFocused ? EmberUI.accent() : EmberUI.TEXT_FAINT, fade);
        r.quad(cx - 4.5, cy - 4.5, 9, 9, GuiRenderer.CIRCLE, ic);
        r.quad(cx - 3, cy - 3, 6, 6, GuiRenderer.CIRCLE, EmberUI.alpha(EmberUI.RAISED, fade));
        EmberUI.bar(r, cx + 3, cy + 3, cx + 6.5, cy + 6.5, 2, ic);
    }

    private void drawSearchText() {
        double x = searchX(), y = searchY();
        theme.textRenderer().begin(theme.scale(0.8));
        boolean empty = search.isEmpty();
        theme.textRenderer().render(empty && !searchFocused ? "Search modules" : search + (searchFocused ? "|" : ""),
            x + 28, y + (SEARCH_H - theme.textHeight()) / 2,
            empty && !searchFocused ? EmberUI.TEXT_FAINT : EmberUI.TEXT, false);
        theme.textRenderer().end();
    }

    // --- Bottom buttons ---

    private static final double CFG_W = 124, CFG_H = 32, GEAR = 32;

    private double configButtonX() { return (getWindowWidth() - CFG_W - 8 - GEAR) / 2; }

    private double configButtonY() { return getWindowHeight() - CFG_H - 24; }

    private double gearX() { return configButtonX() + CFG_W + 8; }

    private double gearY() { return configButtonY(); }

    private void drawConfigButton(GuiRenderer r, float fade) {
        double bx = configButtonX(), by = configButtonY();
        boolean hover = mx >= bx && mx < bx + CFG_W && my >= by && my < by + CFG_H;
        float hA = anim("cfgbtn", hoverAnims, hover ? 1f : 0f, 12f, frameDt);

        r.roundedRect(bx, by, CFG_W, CFG_H, CFG_H / 2, EmberUI.alpha(EmberUI.RAISED, fade));
        if (hA > 0.01f) r.roundedRect(bx, by, CFG_W, CFG_H, CFG_H / 2, EmberUI.accent((int) (34 * hA * fade)));

        // Folder glyph.
        double gx = bx + 20, gy = by + CFG_H / 2;
        Color gc = EmberUI.accent((int) ((215 + 40 * hA) * fade));
        r.roundedRect(gx - 7, gy - 5, 14, 10, 2.5, gc);
        r.roundedRect(gx - 7, gy - 7.5, 6, 4, 1.5, gc);
    }

    private void drawConfigButtonText() {
        double bx = configButtonX(), by = configButtonY();
        theme.textRenderer().begin(theme.scale(0.85));
        theme.textRenderer().render("Configs", bx + 34, by + (CFG_H - theme.textHeight()) / 2, EmberUI.TEXT, false);
        theme.textRenderer().end();
    }

    private void drawGearButton(GuiRenderer r, float fade) {
        double bx = gearX(), by = gearY();
        boolean hover = mx >= bx && mx < bx + GEAR && my >= by && my < by + GEAR;
        float hA = anim("gearbtn", hoverAnims, hover ? 1f : 0f, 12f, frameDt);

        r.quad(bx, by, GEAR, GEAR, GuiRenderer.CIRCLE, EmberUI.alpha(EmberUI.RAISED, fade));
        if (hA > 0.01f) r.quad(bx, by, GEAR, GEAR, GuiRenderer.CIRCLE, EmberUI.accent((int) (34 * hA * fade)));

        double cx = bx + GEAR / 2, cy = by + GEAR / 2;
        Color gc = EmberUI.accent((int) Math.min(255, (215 + 40 * hA) * fade));

        // Teeth turn a little as the button lights up.
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(30 * hA + i * 60);
            r.roundedRect(cx + Math.cos(a) * 7.4 - 1.7, cy + Math.sin(a) * 7.4 - 1.7, 3.4, 3.4, 1.2, gc);
        }

        r.quad(cx - 5.5, cy - 5.5, 11, 11, GuiRenderer.CIRCLE, gc);
        r.quad(cx - 2.4, cy - 2.4, 4.8, 4.8, GuiRenderer.CIRCLE, EmberUI.alpha(EmberUI.RAISED, fade));
    }

    // --- Helpers ---

    private List<Module> filtered(Panel p) {
        if (p.modules == null) return Collections.emptyList();
        if (search.isEmpty()) return p.modules;
        String q = search.toLowerCase();
        List<Module> out = new ArrayList<>();
        for (Module m : p.modules) {
            if (m.title.toLowerCase().contains(q) || m.name.toLowerCase().contains(q)) out.add(m);
        }
        return out;
    }

    private static boolean hasSettings(Settings settings) {
        for (SettingGroup g : settings) for (Setting<?> s : g) if (s.isVisible()) return true;
        return false;
    }

    /** Opens the settings popup for a module, e.g. from the .settings command. */
    public void openSettingsFor(Module module) {
        openModuleSettings(module);
    }

    private void openModuleSettings(Module m) {
        popup.open(new EmberSettingsPopup.Target(
            m.title, m.description, m.settings, m::isActive, m::toggle, m.keybind,
            () -> mc.setScreen(new ModuleScreen(theme, m))
        ));
    }

    private void openHudSettings(HudElement element) {
        popup.open(new EmberSettingsPopup.Target(
            element.info.title, element.info.description, element.settings, element::isActive, element::toggle, null,
            () -> mc.setScreen(new HudElementScreen(theme, element))
        ));
    }

    /** True when the cursor is over the chevron that opens a row's settings. */
    private boolean onChevron(double cx, double panelX) {
        return cx >= panelX + PW - CHEV_RIGHT - 9 && cx < panelX + PW - CHEV_RIGHT + 9;
    }

    // --- Input ---

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double s = mc.getWindow().getScaleFactor();
        double cx = click.x() * s, cy = click.y() * s;
        int btn = click.button();

        if (popup.isVisible()) return popup.mouseClicked(cx, cy, btn);

        if (cx >= searchX() && cx < searchX() + SEARCH_W && cy >= searchY() && cy < searchY() + SEARCH_H) {
            searchFocused = true;
            return true;
        }
        searchFocused = false;

        double bx = configButtonX(), by = configButtonY();
        if (cx >= bx && cx < bx + CFG_W && cy >= by && cy < by + CFG_H) {
            mc.setScreen(new EmberConfigScreen(theme));
            return true;
        }

        if (cx >= gearX() && cx < gearX() + GEAR && cy >= gearY() && cy < gearY() + GEAR) {
            mc.setScreen(new EmberClientSettingsScreen(theme));
            return true;
        }

        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel p = panels.get(i);

            if (cx >= p.x && cx < p.x + PW && cy >= p.y && cy < p.y + HH) {
                if (btn == 1) { p.collapsed = !p.collapsed; return true; }
                dragging = p; dragOX = cx - p.x; dragOY = cy - p.y;
                panels.remove(i); panels.add(p);
                return true;
            }

            if (p.openAnim < 0.1f) continue;
            double totalH = HH + p.bodyH * p.openAnim;
            if (cx < p.x || cx >= p.x + PW || cy < p.y || cy >= p.y + totalH) continue;

            double rowY = p.y + HH - p.scroll;

            if (p.isClient && p.clientEntries != null) {
                for (ClientEntry entry : p.clientEntries) {
                    if (cy >= rowY && cy < rowY + MH) {
                        if (entry.element == null) {
                            if (btn == 0 && entry.action != null) entry.action.run();
                        } else if (btn == 1 || (onChevron(cx, p.x) && hasSettings(entry.element.settings))) {
                            openHudSettings(entry.element);
                        } else if (btn == 0) {
                            entry.element.toggle();
                        }
                        return true;
                    }
                    rowY += MH;
                }
                continue;
            }

            for (Module m : filtered(p)) {
                if (cy >= rowY && cy < rowY + MH) {
                    if (btn == 1 || (onChevron(cx, p.x) && hasSettings(m.settings))) openModuleSettings(m);
                    else if (btn == 0) m.toggle();
                    return true;
                }
                rowY += MH;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        popup.mouseReleased();

        if (dragging != null) {
            saved.put(dragging.name, new double[]{dragging.x, dragging.y, dragging.collapsed ? 1 : 0});
            dragging = null;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        if (popup.isVisible()) return popup.mouseScrolled(v);

        double s = mc.getWindow().getScaleFactor();
        double sx = mouseX * s, sy = mouseY * s;

        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel p = panels.get(i);
            double totalH = HH + p.bodyH * p.openAnim;
            if (sx >= p.x && sx < p.x + PW && sy >= p.y && sy < p.y + totalH) {
                p.scroll -= (int) (v * MH);
                p.scroll = Math.max(0, p.scroll);

                double maxBody;
                if (p.isClient && p.clientEntries != null) maxBody = p.clientEntries.size() * MH;
                else if (p.modules != null) maxBody = filtered(p).size() * MH;
                else maxBody = 0;

                p.scroll = Math.min(p.scroll, (int) Math.max(0, maxBody - bodyLimit(p)));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, h, v);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (popup.isVisible()) return popup.keyPressed(input);

        if (searchFocused) {
            if (input.key() == GLFW_KEY_ESCAPE) { searchFocused = false; search = ""; return true; }
            if (input.key() == GLFW_KEY_BACKSPACE && !search.isEmpty()) {
                search = search.substring(0, search.length() - 1);
                return true;
            }
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (popup.isVisible()) return popup.charTyped(input);

        if (searchFocused) {
            char c = (char) input.codepoint();
            if (c >= 32) search += c;
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public void close() {
        for (Panel p : panels) saved.put(p.name, new double[]{p.x, p.y, p.collapsed ? 1 : 0});
        super.close();
    }

    @Override
    public void reload() {
    }

    private static class Panel {
        Category category; String name; List<Module> modules; ItemStack icon;
        double x, y, bodyH; boolean collapsed; float openAnim = 1f; int scroll;
        boolean isClient; List<ClientEntry> clientEntries;
    }

    private static class ClientEntry {
        String name; HudElement element; Runnable action;

        ClientEntry(String n, HudElement e) { name = n; element = e; }

        ClientEntry(String n, Runnable a) { name = n; action = a; }
    }
}
