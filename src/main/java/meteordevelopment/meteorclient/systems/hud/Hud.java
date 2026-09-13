/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud;

import meteordevelopment.meteorclient.events.meteor.CustomFontChangedEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.hud.elements.*;
import meteordevelopment.meteorclient.systems.hud.elements.keyboard.KeyboardHud;
import meteordevelopment.meteorclient.systems.hud.screens.HudEditorScreen;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Hud extends System<Hud> implements Iterable<HudElement> {
    public static final HudGroup GROUP = new HudGroup("Meteor");

    /**
     * Bumped when the default layout changes in a way that must replace older saved ones.
     * Version 3 exists because 2 rebuilt the layout but kept the master switch off, so the
     * rebuilt widgets only ever showed up inside the HUD editor.
     */
    private static final int LAYOUT_VERSION = 3;

    /**
     * The master switch for the whole HUD. Upstream leaves this off until something turns it
     * on, which for Ember means a fresh install renders nothing in game while still drawing
     * in the HUD editor - the editor paints elements on its own path and ignores this flag.
     */
    public boolean active = true;
    public Settings settings = new Settings();

    public final Map<String, HudElementInfo<?>> infos = new TreeMap<>();
    private final List<HudElement> elements = new ArrayList<>();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgEditor = settings.createGroup("Editor");
    private final SettingGroup sgKeybind = settings.createGroup("Bind");

    // General

    private final Setting<Boolean> customFont = sgGeneral.add(new BoolSetting.Builder()
        .name("custom-font")
        .description("Text will use custom font.")
        .defaultValue(true)
        .onChanged(aBoolean -> {
            for (HudElement element : elements) element.onFontChanged();
        })
        .build()
    );

    private final Setting<Boolean> hideInMenus = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-in-menus")
        .description("Hides the meteor hud when in inventory screens or game menus.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> textScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("text-scale")
        .description("Scale of text if not overridden by the element.")
        .defaultValue(1)
        .min(0.5)
        .sliderRange(0.5, 3)
        .build()
    );

    public final Setting<List<SettingColor>> textColors = sgGeneral.add(new ColorListSetting.Builder()
        .name("text-colors")
        .description("Colors used for the Text element.")
        .defaultValue(List.of(new SettingColor(), new SettingColor(175, 175, 175), new SettingColor(25, 225, 25), new SettingColor(225, 25, 25)))
        .build()
    );

    // Editor

    public final Setting<Integer> border = sgEditor.add(new IntSetting.Builder()
        .name("border")
        .description("Space around the edges of the screen.")
        .defaultValue(4)
        .sliderMax(20)
        .build()
    );

    public final Setting<Integer> snappingRange = sgEditor.add(new IntSetting.Builder()
        .name("snapping-range")
        .description("Snapping range in editor.")
        .defaultValue(10)
        .sliderMax(20)
        .build()
    );

    // Keybindings
    @SuppressWarnings("unused")
    private final Setting<Keybind> keybind = sgKeybind.add(new KeybindSetting.Builder()
        .name("bind")
        .defaultValue(Keybind.none())
        .action(() -> active = !active)
        .build()
    );

    private boolean resetToDefaultElements;

    public Hud() {
        super("hud");
    }

    public static Hud get() {
        return Systems.get(Hud.class);
    }

    @Override
    public void init() {
        settings.registerColorSettings(null);

        register(MeteorTextHud.INFO);
        register(meteordevelopment.meteorclient.systems.hud.elements.SpotifyHud.INFO);
        register(meteordevelopment.meteorclient.systems.hud.elements.EmberModuleListHud.INFO);
        register(meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud.INFO);
        register(meteordevelopment.meteorclient.systems.hud.elements.EmberBubblesHud.INFO);
        register(meteordevelopment.meteorclient.systems.hud.elements.EmberStatusBarHud.INFO);
        register(meteordevelopment.meteorclient.systems.hud.elements.EmberKeybindsHud.INFO);
        register(meteordevelopment.meteorclient.systems.hud.elements.EmberArmorHud.INFO);
        register(ItemHud.INFO);
        register(InventoryHud.INFO);
        register(CompassHud.INFO);
        register(ArmorHud.INFO);
        register(HoleHud.INFO);
        register(PlayerModelHud.INFO);
        register(ActiveModulesHud.INFO);
        register(LagNotifierHud.INFO);
        register(PlayerRadarHud.INFO);
        register(ModuleInfosHud.INFO);
        register(PotionTimersHud.INFO);
        register(CombatHud.INFO);
        register(MapHud.INFO);
        register(KeyboardHud.INFO);

        // Default config
        if (isFirstInit) resetToDefaultElements();
    }

    public void register(HudElementInfo<?> info) {
        infos.put(info.name, info);
    }

    private void add(HudElement element, int x, int y, XAnchor xAnchor, YAnchor yAnchor) {
        element.box.setPos(x, y);

        if (xAnchor == null || yAnchor == null) element.box.updateAnchors();
        else {
            element.box.xAnchor = xAnchor;
            element.box.yAnchor = yAnchor;
        }

        element.settings.registerColorSettings(null);

        elements.add(element);
    }

    public void add(HudElementInfo<?> info, int x, int y, XAnchor xAnchor, YAnchor yAnchor) {
        add(info.create(), x, y, xAnchor, yAnchor);
    }

    public void add(HudElementInfo<?> info, int x, int y) {
        add(info, x, y, null, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void add(@NotNull HudElementInfo.Preset preset, int x, int y, XAnchor xAnchor, YAnchor yAnchor) {
        HudElement element = preset.info.create();
        preset.callback.accept(element);
        add(element, x, y, xAnchor, yAnchor);
    }

    public void add(@NotNull HudElementInfo<?>.Preset preset, int x, int y) {
        add(preset, x, y, null, null);
    }

    void remove(HudElement element) {
        element.settings.unregisterColorSettings();
        elements.remove(element);
    }

    public void clear() {
        elements.clear();
    }

    public void resetToDefaultElements() {
        resetToDefaultElements = true;
    }

    /**
     * Ember's own layout. Meteor's stacked text readouts are deliberately not added: the
     * status bar already carries the same figures, and having both put two sets of numbers in
     * the same corner on top of each other.
     */
    private void resetToDefaultElementsImpl() {
        elements.clear();

        add(meteordevelopment.meteorclient.systems.hud.elements.EmberStatusBarHud.INFO, 8, 8, XAnchor.Left, YAnchor.Top);
        add(meteordevelopment.meteorclient.systems.hud.elements.SpotifyHud.INFO, 8, 56, XAnchor.Left, YAnchor.Top);
        add(meteordevelopment.meteorclient.systems.hud.elements.EmberKeybindsHud.INFO, 8, 112, XAnchor.Left, YAnchor.Top);
        add(meteordevelopment.meteorclient.systems.hud.elements.EmberArmorHud.INFO, 8, -48, XAnchor.Left, YAnchor.Bottom);
        add(meteordevelopment.meteorclient.systems.hud.elements.EmberBubblesHud.INFO, 8, -8, XAnchor.Left, YAnchor.Bottom);
        add(meteordevelopment.meteorclient.systems.hud.elements.EmberModuleListHud.INFO, -8, 8, XAnchor.Right, YAnchor.Top);
        add(meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud.INFO, -8, -8, XAnchor.Right, YAnchor.Bottom);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (Utils.isLoading()) return;

        if (resetToDefaultElements) {
            resetToDefaultElementsImpl();
            resetToDefaultElements = false;
        }

        if (!(active || HudEditorScreen.isOpen())) return;

        for (HudElement element : elements) {
            if (element.isActive() || element.isInEditor()) {
                element.tick(HudRenderer.INSTANCE);
            }
        }
    }

    @EventHandler
    private void onRender(Render2DEvent event) {
        if (Utils.isLoading()) return;

        if (!active || shouldHideHud()) return;
        if ((mc.options.hudHidden || mc.debugHudEntryList.isF3Enabled()) && !HudEditorScreen.isOpen()) return;

        HudRenderer.INSTANCE.begin(event.drawContext);

        for (HudElement element : elements) {
            element.updatePos();

            if (element.isActive() || element.isInEditor()) {
                element.render(HudRenderer.INSTANCE);
            }
        }

        HudRenderer.INSTANCE.end();
    }

    private boolean shouldHideHud() {
        return hideInMenus.get() && mc.currentScreen != null && !(mc.currentScreen instanceof WidgetScreen);
    }

    @EventHandler
    private void onCustomFontChanged(CustomFontChangedEvent event) {
        if (customFont.get()) {
            for (HudElement element : elements) element.onFontChanged();
        }
    }

    public boolean hasCustomFont() {
        return customFont.get();
    }

    public double getTextScale() {
        return textScale.get();
    }

    @NotNull
    @Override
    public Iterator<HudElement> iterator() {
        return elements.iterator();
    }

    // Serialization

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();

        tag.putInt("__version__", LAYOUT_VERSION);

        tag.putBoolean("active", active);
        tag.put("settings", settings.toTag());
        tag.put("elements", NbtUtils.listToTag(elements));

        return tag;
    }

    @Override
    public Hud fromTag(NbtCompound tag) {
        int version = tag.getInt("__version__").orElse(0);

        // Configs written before the Ember layout carry Meteor's stacked text readouts and its
        // own module list. Those draw a second copy of everything Ember already shows, in the
        // same corners, and no new default can take effect while they are saved - so such a
        // config is rebuilt once instead of being loaded back on top of the new layout.
        if (version < LAYOUT_VERSION) {
            // Deliberately not carrying the old flag over: rebuilding the layout while the
            // master switch stays off leaves the new widgets invisible everywhere except the
            // editor, which is indistinguishable from the migration having failed.
            active = true;
            settings.fromTag(tag.getCompoundOrEmpty("settings"));
            resetToDefaultElements();
            return this;
        }

        tag.getBoolean("active").ifPresent(active1 -> active = active1);
        settings.fromTag(tag.getCompoundOrEmpty("settings"));

        // Elements
        elements.clear();

        for (NbtElement e : tag.getListOrEmpty("elements")) {
            NbtCompound c = (NbtCompound) e;
            if (c.getString("name").isEmpty()) continue;

            HudElementInfo<?> info = infos.get(c.getString("name").get());
            if (info != null) {
                HudElement element = info.create();
                element.fromTag(c);
                element.settings.registerColorSettings(null);
                elements.add(element);
            }
        }

        return this;
    }
}
