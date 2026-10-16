package meteordevelopment.meteorclient.gui.screens;

import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.renderer.text.FontFamily;
import meteordevelopment.meteorclient.renderer.text.FontInfo;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.network.PacketUtils;
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.item.Item;
import net.minecraft.network.packet.Packet;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.joml.Vector3d;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.Utils.getWindowHeight;
import static meteordevelopment.meteorclient.utils.Utils.getWindowWidth;
import static org.lwjgl.glfw.GLFW.*;

/**
 * Ember Client UI sub-editor for list, registry, coordinate, and complex settings.
 * Replaces the legacy Meteor Client "Open editor" fallback inside {@link EmberSettingsPopup}.
 */
public class EmberSubEditor {
    private static final Color DANGER = new Color(222, 86, 86, 255);
    private static final double MAX_W = 500;
    private static final double HEADER_H = 64;
    private static final double TOOLBAR_H = 42;
    private static final double FOOTER_H = 30;
    private static final double ROW_H = 36;
    private static final double PAD = 20;
    private static final double RADIUS = 12;

    private static final double TITLE_SCALE = 1.02;
    private static final double LABEL_SCALE = 0.84;
    private static final double SMALL_SCALE = 0.66;

    private record Hit(double x, double y, double w, double h, Runnable action) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private record TextOp(String text, double x, double y, Color color, double scale, int align, double maxW) {}

    private record ChoiceItem(Object value, String label, String sublabel) {}

    private final GuiTheme theme;
    private final Runnable onBack;
    private final Runnable onCloseAll;

    private Setting<?> setting;
    private double scroll;
    private double maxScroll;
    private double mx, my;

    private String search = "";
    private boolean searchFocused = false;
    private boolean selectedOnly = false;

    private String newStringBuffer = "";
    private boolean addingStringFocused = false;
    private int editingStringIndex = -1;
    private String editingStringBuffer = "";

    private int editingCoordAxis = -1;
    private String coordBuffer = "";

    private int draggingColorIndex = -1;
    private int draggingColorChannel = -1;
    private double dragTrackX, dragTrackW;
    private boolean mouseDown;

    private List<ChoiceItem> cachedChoices = Collections.emptyList();
    private final List<Hit> hits = new ArrayList<>();
    private final List<TextOp> texts = new ArrayList<>();
    private final Map<Object, Float> anims = new HashMap<>();

    public EmberSubEditor(GuiTheme theme, Runnable onBack, Runnable onCloseAll) {
        this.theme = theme;
        this.onBack = onBack;
        this.onCloseAll = onCloseAll;
    }

    public void open(Setting<?> setting) {
        this.setting = setting;
        this.scroll = 0;
        this.maxScroll = 0;
        this.search = "";
        this.selectedOnly = false;
        this.newStringBuffer = "";
        this.editingStringIndex = -1;
        this.editingStringBuffer = "";
        this.editingCoordAxis = -1;
        this.coordBuffer = "";
        this.draggingColorIndex = -1;
        this.draggingColorChannel = -1;
        this.anims.clear();

        if (setting instanceof StringListSetting) {
            this.addingStringFocused = true;
            this.searchFocused = false;
            this.cachedChoices = Collections.emptyList();
        } else {
            this.addingStringFocused = false;
            this.searchFocused = isSearchable(setting);
            this.cachedChoices = buildChoices(setting);
        }
    }

    public void close() {
        commitPendingEdits();
        this.setting = null;
        this.cachedChoices = Collections.emptyList();
    }

    public boolean isOpen() {
        return setting != null;
    }

    public Setting<?> getSetting() {
        return setting;
    }

    /** Short summary shown on the button in EmberSettingsPopup instead of generic "Open editor". */
    public static String summaryText(Setting<?> s) {
        if (s == null) return "Open editor";
        Object val = s.get();
        if (val instanceof Collection<?> col) {
            return "Edit (" + col.size() + ")";
        }
        if (s instanceof BlockSetting bs && bs.get() != null) {
            return Names.get(bs.get());
        }
        if (s instanceof ItemSetting is && is.get() != null) {
            return Names.get(is.get());
        }
        if (s instanceof FontFaceSetting fs && fs.get() != null) {
            return fs.get().info.family();
        }
        if (s instanceof BlockPosSetting bps && bps.get() != null) {
            BlockPos p = bps.get();
            return p.getX() + ", " + p.getY() + ", " + p.getZ();
        }
        if (s instanceof Vector3dSetting vs && vs.get() != null) {
            Vector3d v = vs.get();
            return String.format("%.1f, %.1f, %.1f", v.x, v.y, v.z);
        }
        if (s instanceof StatusEffectAmplifierMapSetting mapSetting) {
            int active = 0;
            for (int lvl : mapSetting.get().values()) if (lvl > 0) active++;
            return "Edit (" + active + ")";
        }
        return "Open editor";
    }

    private boolean isSearchable(Setting<?> s) {
        return s instanceof BlockListSetting
            || s instanceof ItemListSetting
            || s instanceof EntityTypeListSetting
            || s instanceof ModuleListSetting
            || s instanceof StorageBlockListSetting
            || s instanceof StatusEffectListSetting
            || s instanceof SoundEventListSetting
            || s instanceof ParticleTypeListSetting
            || s instanceof EnchantmentListSetting
            || s instanceof PacketListSetting
            || s instanceof ScreenHandlerListSetting
            || s instanceof BlockSetting
            || s instanceof ItemSetting
            || s instanceof FontFaceSetting
            || s instanceof StatusEffectAmplifierMapSetting;
    }

    private List<ChoiceItem> buildChoices(Setting<?> s) {
        List<ChoiceItem> list = new ArrayList<>();

        if (s instanceof BlockListSetting bls) {
            for (Block b : Registries.BLOCK) {
                if (bls.filter != null && !bls.filter.test(b)) continue;
                Identifier id = Registries.BLOCK.getId(b);
                list.add(new ChoiceItem(b, Names.get(b), id.getPath()));
            }
        } else if (s instanceof BlockSetting bs) {
            for (Block b : Registries.BLOCK) {
                if (bs.filter != null && !bs.filter.test(b)) continue;
                Identifier id = Registries.BLOCK.getId(b);
                list.add(new ChoiceItem(b, Names.get(b), id.getPath()));
            }
        } else if (s instanceof ItemListSetting ils) {
            for (Item item : Registries.ITEM) {
                if (ils.filter != null && !ils.filter.test(item)) continue;
                Identifier id = Registries.ITEM.getId(item);
                list.add(new ChoiceItem(item, Names.get(item), id.getPath()));
            }
        } else if (s instanceof ItemSetting is) {
            for (Item item : Registries.ITEM) {
                if (is.filter != null && !is.filter.test(item)) continue;
                Identifier id = Registries.ITEM.getId(item);
                list.add(new ChoiceItem(item, Names.get(item), id.getPath()));
            }
        } else if (s instanceof EntityTypeListSetting etls) {
            for (EntityType<?> type : Registries.ENTITY_TYPE) {
                if (etls.filter != null && !etls.filter.test(type)) continue;
                Identifier id = Registries.ENTITY_TYPE.getId(type);
                list.add(new ChoiceItem(type, Names.get(type), id.getPath()));
            }
        } else if (s instanceof ModuleListSetting) {
            if (Modules.get() != null) {
                for (Module m : Modules.get().getAll()) {
                    list.add(new ChoiceItem(m, m.title, m.category.name));
                }
            }
        } else if (s instanceof StorageBlockListSetting) {
            for (BlockEntityType<?> type : StorageBlockListSetting.STORAGE_BLOCKS) {
                Identifier id = Registries.BLOCK_ENTITY_TYPE.getId(type);
                String name = id != null ? prettyId(id.getPath()) : "Storage";
                list.add(new ChoiceItem(type, name, id != null ? id.getPath() : ""));
            }
        } else if (s instanceof StatusEffectListSetting || s instanceof StatusEffectAmplifierMapSetting) {
            for (StatusEffect effect : Registries.STATUS_EFFECT) {
                Identifier id = Registries.STATUS_EFFECT.getId(effect);
                list.add(new ChoiceItem(effect, Names.get(effect), id != null ? id.getPath() : ""));
            }
        } else if (s instanceof SoundEventListSetting) {
            for (SoundEvent sound : Registries.SOUND_EVENT) {
                Identifier id = sound.id();
                list.add(new ChoiceItem(sound, Names.getSoundName(id), id.getPath()));
            }
        } else if (s instanceof ParticleTypeListSetting) {
            for (ParticleType<?> pt : Registries.PARTICLE_TYPE) {
                if (!(pt instanceof ParticleEffect)) continue;
                Identifier id = Registries.PARTICLE_TYPE.getId(pt);
                list.add(new ChoiceItem(pt, Names.get(pt), id != null ? id.getPath() : ""));
            }
        } else if (s instanceof EnchantmentListSetting els) {
            Set<Identifier> ids = new LinkedHashSet<>();
            for (Identifier id : els.getIdentifierSuggestions()) ids.add(id);
            for (RegistryKey<Enchantment> k : els.get()) ids.add(k.getValue());
            for (RegistryKey<Enchantment> k : els.getDefaultValue()) ids.add(k.getValue());
            for (Identifier id : ids) {
                RegistryKey<Enchantment> key = RegistryKey.of(RegistryKeys.ENCHANTMENT, id);
                list.add(new ChoiceItem(key, Names.get(key), id.getPath()));
            }
        } else if (s instanceof PacketListSetting pls) {
            List<Class<? extends Packet<?>>> all = new ArrayList<>();
            all.addAll(PacketUtils.getC2SPackets());
            all.addAll(PacketUtils.getS2CPackets());
            for (Class<? extends Packet<?>> pkt : all) {
                if (pls.filter != null && !pls.filter.test(pkt)) continue;
                String name = PacketUtils.getName(pkt);
                boolean c2s = PacketUtils.getC2SPackets().contains(pkt);
                list.add(new ChoiceItem(pkt, name, c2s ? "C2S" : "S2C"));
            }
        } else if (s instanceof ScreenHandlerListSetting) {
            for (ScreenHandlerType<?> type : Registries.SCREEN_HANDLER) {
                Identifier id = Registries.SCREEN_HANDLER.getId(type);
                String name = id != null ? prettyId(id.getPath()) : "Screen";
                list.add(new ChoiceItem(type, name, id != null ? id.getPath() : ""));
            }
        } else if (s instanceof FontFaceSetting) {
            for (FontFamily family : Fonts.FONT_FAMILIES) {
                FontFace face = family.get(FontInfo.Type.Regular);
                if (face != null) {
                    list.add(new ChoiceItem(face, family.getName(), "Font"));
                }
            }
        }

        list.sort(Comparator.comparing(ChoiceItem::label, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private static String prettyId(String path) {
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private float anim(Object key, boolean on, float dt) {
        float target = on ? 1f : 0f;
        float cur = EmberAnim.approach(anims.getOrDefault(key, target), target, dt, 0.065);
        anims.put(key, cur);
        return cur;
    }

    private Color accent() {
        return EmberPalette.accent();
    }

    private static Color alpha(Color c, float f) {
        return new Color(c.r, c.g, c.b, (int) (c.a * f));
    }

    private static Color lerp(Color a, Color b, float t) {
        return new Color(
            (int) MathHelper.lerp(t, a.r, b.r),
            (int) MathHelper.lerp(t, a.g, b.g),
            (int) MathHelper.lerp(t, a.b, b.b),
            (int) MathHelper.lerp(t, a.a, b.a)
        );
    }

    private boolean over(double x, double y, double w, double h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public void render(GuiRenderer r, double mouseX, double mouseY, float fade, float dt) {
        if (setting == null) return;
        this.mx = mouseX;
        this.my = mouseY;

        if (mouseDown && draggingColorIndex >= 0 && setting instanceof ColorListSetting cls) {
            applyColorSliderDrag(cls, mx);
        }

        hits.clear();
        texts.clear();

        double w = Math.min(MAX_W, getWindowWidth() - 40);
        double h = Math.min(getWindowHeight() - 70, 480);
        double x = (getWindowWidth() - w) / 2;
        double y = (getWindowHeight() - h) / 2 + 14 * (1 - fade);

        // Backdrop
        r.scissorStart(0, 0, getWindowWidth(), getWindowHeight());
        r.quad(0, 0, getWindowWidth(), getWindowHeight(), new Color(0, 0, 0, (int) (130 * fade)));
        r.scissorEnd();

        EmberUI.shadow(r, x, y, w, h, fade);
        if (meteordevelopment.meteorclient.utils.render.EmberAppearance.liquid()) EmberUI.rim(r, x, y, w, h, RADIUS, fade);
        r.roundedRect(x, y, w, h, RADIUS, alpha(EmberUI.bg(), fade));

        drawHeader(r, x, y, w, fade, dt);

        double bodyTop = y + HEADER_H;
        double bodyBottom = y + h - FOOTER_H;

        if (setting instanceof StringListSetting sls) {
            drawStringListEditor(r, sls, x, w, bodyTop, bodyBottom, fade, dt);
        } else if (setting instanceof ColorListSetting cls) {
            drawColorListEditor(r, cls, x, w, bodyTop, bodyBottom, fade, dt);
        } else if (setting instanceof BlockPosSetting bps) {
            drawBlockPosEditor(r, bps, x, w, bodyTop, bodyBottom, fade, dt);
        } else if (setting instanceof Vector3dSetting vs) {
            drawVector3dEditor(r, vs, x, w, bodyTop, bodyBottom, fade, dt);
        } else if (setting instanceof StatusEffectAmplifierMapSetting mapSetting) {
            drawAmplifierMapEditor(r, mapSetting, x, w, bodyTop, bodyBottom, fade, dt);
        } else if (!cachedChoices.isEmpty() || isSearchable(setting)) {
            drawChoicesEditor(r, x, w, bodyTop, bodyBottom, fade, dt);
        } else {
            drawFallbackStringEditor(r, x, w, bodyTop, bodyBottom, fade, dt);
        }

        // Footer
        r.quad(x + PAD, bodyBottom, w - PAD * 2, 1, alpha(EmberUI.DIVIDER, fade));
        text("Esc or Back to return   ·   Changes apply immediately", x + w / 2, bodyBottom + (FOOTER_H - 9) / 2,
            alpha(EmberUI.TEXT_FAINT, fade), SMALL_SCALE, 2, 0);
    }

    private void drawHeader(GuiRenderer r, double x, double y, double w, float fade, float dt) {
        r.roundedRect(x, y, w, HEADER_H + RADIUS, RADIUS, alpha(EmberUI.raised(), fade));
        r.quad(x, y + HEADER_H - RADIUS, w, RADIUS, alpha(EmberUI.raised(), fade));
        r.quad(x, y + HEADER_H, w, 1, alpha(EmberUI.DIVIDER, fade));

        // Back button on the left
        double backW = 64, backH = 26, backX = x + PAD, backY = y + (HEADER_H - backH) / 2;
        boolean backHover = over(backX, backY, backW, backH);
        float ba = anim("back_btn", backHover, dt);
        r.roundedRect(backX, backY, backW, backH, 7, alpha(lerp(EmberUI.WELL, new Color(44, 44, 53, 255), ba), fade));
        EmberUI.chevron(r, backX + 13, backY + backH / 2, 7, EmberUI.Direction.LEFT, alpha(accent(), fade));
        text("Back", backX + 24, backY + (backH - 9) / 2, alpha(EmberUI.TEXT, fade), LABEL_SCALE - 0.04, 0, 0);
        hits.add(new Hit(backX, backY, backW, backH, () -> {
            close();
            onBack.run();
        }));

        // Title & description
        double titleX = backX + backW + 14;
        text(setting.title, titleX, y + 14, alpha(EmberUI.TEXT, fade), TITLE_SCALE, 0, w - (titleX - x) - 125);
        if (setting.description != null && !setting.description.isEmpty()) {
            text(setting.description, titleX, y + 38, alpha(EmberUI.TEXT_DIM, fade), SMALL_SCALE, 0, w - (titleX - x) - 125);
        }

        // Close button
        double cs = 26, cx = x + w - PAD - cs, cy = y + (HEADER_H - cs) / 2;
        boolean closeHover = over(cx, cy, cs, cs);
        float ca = anim("sub_close", closeHover, dt);
        if (ca > 0.01f) r.roundedRect(cx, cy, cs, cs, cs / 2, new Color(DANGER.r, DANGER.g, DANGER.b, (int) (70 * ca * fade)));
        EmberUI.cross(r, cx + cs / 2, cy + cs / 2, 9, alpha(closeHover ? EmberUI.TEXT : EmberUI.TEXT_DIM, fade));
        hits.add(new Hit(cx, cy, cs, cs, () -> {
            close();
            onCloseAll.run();
        }));

        // Reset button
        double rw = 56, rh = 26, rx = cx - 10 - rw, ry = y + (HEADER_H - rh) / 2;
        boolean resetHover = over(rx, ry, rw, rh);
        float ra = anim("sub_reset", resetHover, dt);
        r.roundedRect(rx, ry, rw, rh, 7, alpha(lerp(EmberUI.WELL, new Color(44, 44, 53, 255), ra), fade));
        text("Reset", rx + rw / 2, ry + (rh - 9) / 2, alpha(resetHover ? accent() : EmberUI.TEXT_DIM, fade), SMALL_SCALE + 0.06, 2, 0);
        hits.add(new Hit(rx, ry, rw, rh, () -> {
            setting.reset();
            setting.onChanged();
            editingStringIndex = -1;
        }));
    }

    // --- 1. StringListSetting Editor ---

    private void drawStringListEditor(GuiRenderer r, StringListSetting sls, double x, double w, double top, double bottom, float fade, float dt) {
        double barY = top + 10;
        double addBtnW = 68;
        double inputX = x + PAD;
        double inputW = w - PAD * 2 - addBtnW - 8;
        double inputH = 28;

        // Input box for adding a new string
        boolean inHover = over(inputX, barY, inputW, inputH);
        float ia = anim("str_add_in", addingStringFocused || inHover, dt);
        if (addingStringFocused) {
            r.glow(inputX, barY, inputW, inputH, 8, new Color(accent().r, accent().g, accent().b, (int) (110 * fade)), false);
        }
        r.roundedRect(inputX, barY, inputW, inputH, 7, alpha(lerp(EmberUI.WELL, new Color(44, 44, 53, 255), ia), fade));
        String shownIn = newStringBuffer.isEmpty() && !addingStringFocused
            ? "Type value to add..."
            : newStringBuffer + (addingStringFocused ? "|" : "");
        Color inCol = (newStringBuffer.isEmpty() && !addingStringFocused) ? EmberUI.TEXT_FAINT : EmberUI.TEXT;
        text(shownIn, inputX + 12, barY + (inputH - 9) / 2, alpha(inCol, fade), LABEL_SCALE, 0, inputW - 24);
        hits.add(new Hit(inputX, barY, inputW, inputH, () -> {
            commitPendingEdits();
            addingStringFocused = true;
        }));

        // "+ Add" button
        double btnX = inputX + inputW + 8;
        boolean addHover = over(btnX, barY, addBtnW, inputH);
        float aa = anim("str_add_btn", addHover, dt);
        r.roundedRect(btnX, barY, addBtnW, inputH, 7, alpha(lerp(accent(), new Color(255, 255, 255, 255), aa * 0.18f), fade));
        text("+ Add", btnX + addBtnW / 2, barY + (inputH - 9) / 2, new Color(12, 12, 16, (int) (255 * fade)), LABEL_SCALE, 2, 0);
        hits.add(new Hit(btnX, barY, addBtnW, inputH, () -> addNewString(sls)));

        double listTop = barY + inputH + 10;
        r.quad(x + PAD, listTop - 4, w - PAD * 2, 1, alpha(EmberUI.DIVIDER, fade));

        List<String> items = sls.get();
        double contentH = Math.max(ROW_H, items.size() * ROW_H);
        maxScroll = Math.max(0, contentH + 8 - (bottom - listTop));
        scroll = MathHelper.clamp(scroll, 0, maxScroll);

        if (items.isEmpty()) {
            text("No entries yet — type above and click + Add", x + w / 2, listTop + (bottom - listTop) / 2 - 6,
                alpha(EmberUI.TEXT_FAINT, fade), LABEL_SCALE, 2, 0);
            return;
        }

        double rowY = listTop + 4 - scroll;
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            String val = items.get(i);
            if (rowY >= listTop - 0.5 && rowY + ROW_H <= bottom + 0.5) {
                boolean hover = over(x + 10, rowY, w - 20, ROW_H);
                float ha = anim("srow_" + i, hover, dt);
                if (ha > 0.01f) {
                    r.roundedRect(x + 10, rowY + 2, w - 20, ROW_H - 4, 8, new Color(255, 255, 255, (int) (10 * ha * fade)));
                }

                text("#" + (i + 1), x + PAD, rowY + (ROW_H - 9) / 2, alpha(EmberUI.TEXT_FAINT, fade), SMALL_SCALE + 0.04, 0, 0);

                double boxX = x + PAD + 30;
                double delW = 24;
                double boxW = w - PAD * 2 - 30 - delW - 10;
                boolean isEditing = editingStringIndex == i;
                r.roundedRect(boxX, rowY + 5, boxW, ROW_H - 10, 6, alpha(EmberUI.WELL, fade));
                if (isEditing) {
                    r.glow(boxX, rowY + 5, boxW, ROW_H - 10, 6, new Color(accent().r, accent().g, accent().b, (int) (100 * fade)), false);
                }

                String shown = isEditing ? editingStringBuffer + "|" : val;
                text(shown.isEmpty() ? "(empty)" : shown, boxX + 10, rowY + (ROW_H - 9) / 2,
                    alpha(isEditing ? accent() : (shown.isEmpty() ? EmberUI.TEXT_FAINT : EmberUI.TEXT), fade), LABEL_SCALE, 0, boxW - 20);

                hits.add(new Hit(boxX, rowY + 4, boxW, ROW_H - 8, () -> {
                    commitPendingEdits();
                    addingStringFocused = false;
                    editingStringIndex = idx;
                    editingStringBuffer = items.get(idx);
                }));

                // Delete button
                double dx = x + w - PAD - delW;
                double dy = rowY + (ROW_H - delW) / 2;
                boolean delHover = over(dx, dy, delW, delW);
                float da = anim("sdel_" + i, delHover, dt);
                if (da > 0.01f) {
                    r.roundedRect(dx, dy, delW, delW, 6, new Color(DANGER.r, DANGER.g, DANGER.b, (int) (65 * da * fade)));
                }
                EmberUI.cross(r, dx + delW / 2, dy + delW / 2, 7, alpha(delHover ? DANGER : EmberUI.TEXT_DIM, fade));
                hits.add(new Hit(dx, dy, delW, delW, () -> {
                    commitPendingEdits();
                    List<String> copy = new ArrayList<>(sls.get());
                    if (idx >= 0 && idx < copy.size()) {
                        copy.remove(idx);
                        sls.set(copy);
                    }
                }));
            }
            rowY += ROW_H;
        }

        drawScrollbar(r, x, w, listTop, bottom, contentH, fade);
    }

    private void addNewString(StringListSetting sls) {
        String trimmed = newStringBuffer.trim();
        if (trimmed.isEmpty()) return;
        List<String> copy = new ArrayList<>(sls.get());
        copy.add(trimmed);
        sls.set(copy);
        newStringBuffer = "";
        addingStringFocused = true;
    }

    // --- 2. Multi-Select & Single-Select Registry / Collection Choices Editor ---

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void drawChoicesEditor(GuiRenderer r, double x, double w, double top, double bottom, float fade, float dt) {
        boolean isMulti = setting.get() instanceof Collection<?>;
        Collection<Object> selectedCol = isMulti ? (Collection<Object>) setting.get() : null;

        // Search + Filter Toolbar
        double barY = top + 8;
        double barH = 28;
        double filterW = isMulti ? 105 : 0;
        double clearW = isMulti ? 54 : 0;
        double searchX = x + PAD;
        double searchW = w - PAD * 2 - (isMulti ? filterW + clearW + 16 : 0);

        boolean sHover = over(searchX, barY, searchW, barH);
        float sa = anim("choice_search", searchFocused || sHover, dt);
        if (searchFocused) {
            r.glow(searchX, barY, searchW, barH, 7, new Color(accent().r, accent().g, accent().b, (int) (100 * fade)), false);
        }
        r.roundedRect(searchX, barY, searchW, barH, 7, alpha(lerp(EmberUI.WELL, new Color(44, 44, 53, 255), sa), fade));
        String searchShown = search.isEmpty() && !searchFocused ? "Search..." : search + (searchFocused ? "|" : "");
        Color sCol = (search.isEmpty() && !searchFocused) ? EmberUI.TEXT_FAINT : EmberUI.TEXT;
        text(searchShown, searchX + 12, barY + (barH - 9) / 2, alpha(sCol, fade), LABEL_SCALE, 0, searchW - 24);
        hits.add(new Hit(searchX, barY, searchW, barH, () -> searchFocused = true));

        if (isMulti && selectedCol != null) {
            double fx = searchX + searchW + 8;
            boolean fHover = over(fx, barY, filterW, barH);
            float fa = anim("choice_filter", selectedOnly || fHover, dt);
            r.roundedRect(fx, barY, filterW, barH, 7, alpha(lerp(EmberUI.WELL, accent(), selectedOnly ? 0.28f : fa * 0.12f), fade));
            String fText = selectedOnly ? "Selected (" + selectedCol.size() + ")" : "All (" + selectedCol.size() + " on)";
            text(fText, fx + filterW / 2, barY + (barH - 9) / 2, alpha(selectedOnly ? accent() : EmberUI.TEXT, fade), SMALL_SCALE + 0.05, 2, 0);
            hits.add(new Hit(fx, barY, filterW, barH, () -> {
                selectedOnly = !selectedOnly;
                scroll = 0;
            }));

            double cx = fx + filterW + 8;
            boolean cHover = over(cx, barY, clearW, barH);
            float ca = anim("choice_clear", cHover, dt);
            r.roundedRect(cx, barY, clearW, barH, 7, alpha(lerp(EmberUI.WELL, new Color(44, 44, 53, 255), ca), fade));
            text("Clear", cx + clearW / 2, barY + (barH - 9) / 2, alpha(cHover ? DANGER : EmberUI.TEXT_DIM, fade), SMALL_SCALE + 0.05, 2, 0);
            hits.add(new Hit(cx, barY, clearW, barH, () -> {
                selectedCol.clear();
                setting.onChanged();
            }));
        }

        double listTop = top + TOOLBAR_H;
        r.quad(x + PAD, listTop - 2, w - PAD * 2, 1, alpha(EmberUI.DIVIDER, fade));

        String q = search.trim().toLowerCase(Locale.ROOT);
        List<ChoiceItem> filtered = new ArrayList<>();
        for (ChoiceItem item : cachedChoices) {
            boolean isSel = isMulti ? selectedCol.contains(item.value()) : Objects.equals(setting.get(), item.value());
            if (selectedOnly && !isSel) continue;
            if (!q.isEmpty() && !item.label().toLowerCase(Locale.ROOT).contains(q) && !item.sublabel().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            filtered.add(item);
        }

        double contentH = filtered.size() * ROW_H;
        maxScroll = Math.max(0, contentH + 8 - (bottom - listTop));
        scroll = MathHelper.clamp(scroll, 0, maxScroll);

        if (filtered.isEmpty()) {
            text("No matching entries found", x + w / 2, listTop + (bottom - listTop) / 2 - 5,
                alpha(EmberUI.TEXT_FAINT, fade), LABEL_SCALE, 2, 0);
            return;
        }

        double rowY = listTop + 4 - scroll;
        for (int i = 0; i < filtered.size(); i++) {
            ChoiceItem item = filtered.get(i);
            if (rowY >= listTop - 0.5 && rowY + ROW_H <= bottom + 0.5) {
                boolean isSel = isMulti ? selectedCol.contains(item.value()) : Objects.equals(setting.get(), item.value());
                boolean hover = over(x + 10, rowY, w - 20, ROW_H);
                float ha = anim(item.value(), hover, dt);
                if (ha > 0.01f) {
                    r.roundedRect(x + 10, rowY + 2, w - 20, ROW_H - 4, 8, new Color(255, 255, 255, (int) (10 * ha * fade)));
                }

                text(item.label(), x + PAD, rowY + (ROW_H - 9) / 2, alpha(isSel ? EmberUI.TEXT : EmberUI.TEXT_DIM, fade), LABEL_SCALE, 0, w - PAD * 2 - 140);
                if (item.sublabel() != null && !item.sublabel().isEmpty()) {
                    text(item.sublabel(), x + w - PAD - 52, rowY + (ROW_H - 9) / 2, alpha(EmberUI.TEXT_FAINT, fade), SMALL_SCALE, 1, 90);
                }

                double tw = 36, th = 19, tx = x + w - PAD - tw, ty = rowY + (ROW_H - th) / 2;
                EmberUI.toggle(r, tx, ty, tw, th, anim(item.value() + "_tgl", isSel, dt * 1.4f), fade);

                final Object val = item.value();
                hits.add(new Hit(x + 10, rowY, w - 20, ROW_H, () -> {
                    if (isMulti) {
                        if (selectedCol.contains(val)) selectedCol.remove(val);
                        else selectedCol.add(val);
                        setting.onChanged();
                    } else {
                        ((Setting) setting).set(val);
                    }
                }));
            }
            rowY += ROW_H;
        }

        drawScrollbar(r, x, w, listTop, bottom, contentH, fade);
    }

    // --- 3. ColorListSetting Editor ---

    private void drawColorListEditor(GuiRenderer r, ColorListSetting cls, double x, double w, double top, double bottom, float fade, float dt) {
        double barY = top + 8;
        double btnW = 110, btnH = 26;
        double btnX = x + PAD;
        boolean addHover = over(btnX, barY, btnW, btnH);
        float aa = anim("clr_add", addHover, dt);
        r.roundedRect(btnX, barY, btnW, btnH, 7, alpha(lerp(EmberUI.WELL, accent(), 0.25f + aa * 0.2f), fade));
        text("+ Add Color", btnX + btnW / 2, barY + (btnH - 9) / 2, alpha(EmberUI.TEXT, fade), LABEL_SCALE, 2, 0);
        hits.add(new Hit(btnX, barY, btnW, btnH, () -> {
            cls.get().add(new SettingColor(255, 255, 255, 255));
            cls.onChanged();
        }));

        double listTop = top + TOOLBAR_H;
        r.quad(x + PAD, listTop - 2, w - PAD * 2, 1, alpha(EmberUI.DIVIDER, fade));

        List<SettingColor> colors = cls.get();
        double blockH = 128;
        double contentH = colors.size() * blockH;
        maxScroll = Math.max(0, contentH + 8 - (bottom - listTop));
        scroll = MathHelper.clamp(scroll, 0, maxScroll);

        double cy = listTop + 4 - scroll;
        String[] chNames = {"R", "G", "B", "A"};

        for (int i = 0; i < colors.size(); i++) {
            final int idx = i;
            SettingColor c = colors.get(i);
            if (cy >= listTop - 0.5 && cy + blockH <= bottom + 0.5) {
                r.roundedRect(x + 12, cy + 2, w - 24, blockH - 6, 9, alpha(EmberUI.WELL, fade));

                // Header of color entry
                r.roundedRect(x + PAD + 2, cy + 10, 18, 18, 5, new Color(c.r, c.g, c.b, (int) (255 * fade)));
                text(String.format("Color #%d  (#%02X%02X%02X)", i + 1, c.r, c.g, c.b), x + PAD + 28, cy + 14, alpha(EmberUI.TEXT, fade), LABEL_SCALE, 0, 0);

                // Delete color button
                double dx = x + w - PAD - 24, dy = cy + 8;
                boolean dHover = over(dx, dy, 22, 22);
                EmberUI.cross(r, dx + 11, dy + 11, 7, alpha(dHover ? DANGER : EmberUI.TEXT_DIM, fade));
                hits.add(new Hit(dx, dy, 22, 22, () -> {
                    if (idx < cls.get().size()) {
                        cls.get().remove(idx);
                        cls.onChanged();
                    }
                }));

                // 4 Channel sliders
                int[] rgba = {c.r, c.g, c.b, c.a};
                double sy = cy + 36;
                double trackX = x + PAD + 28;
                double trackW = w - PAD * 2 - 80;
                for (int ch = 0; ch < 4; ch++) {
                    final int channel = ch;
                    double pct = rgba[ch] / 255.0;
                    text(chNames[ch], x + PAD + 6, sy + 2, alpha(EmberUI.TEXT_DIM, fade), SMALL_SCALE + 0.04, 0, 0);
                    r.roundedRect(trackX, sy + 5, trackW, 4, 2, alpha(new Color(24, 24, 30, 255), fade));
                    if (trackW * pct > 1) r.roundedRect(trackX, sy + 5, trackW * pct, 4, 2, alpha(accent(), fade));
                    r.quad(trackX + trackW * pct - 5, sy + 2, 10, 10, GuiRenderer.CIRCLE, alpha(EmberUI.TEXT, fade));
                    text(String.valueOf(rgba[ch]), x + w - PAD - 6, sy + 2, alpha(EmberUI.TEXT, fade), SMALL_SCALE + 0.04, 1, 0);

                    hits.add(new Hit(trackX - 4, sy, trackW + 8, 16, () -> {
                        draggingColorIndex = idx;
                        draggingColorChannel = channel;
                        dragTrackX = trackX;
                        dragTrackW = trackW;
                        applyColorSliderDrag(cls, mx);
                    }));
                    sy += 21;
                }
            }
            cy += blockH;
        }

        drawScrollbar(r, x, w, listTop, bottom, contentH, fade);
    }

    private void applyColorSliderDrag(ColorListSetting cls, double mouseX) {
        if (draggingColorIndex < 0 || draggingColorIndex >= cls.get().size()) return;
        SettingColor c = cls.get().get(draggingColorIndex);
        int val = (int) Math.round(MathHelper.clamp((mouseX - dragTrackX) / dragTrackW, 0, 1) * 255);
        int[] rgba = {c.r, c.g, c.b, c.a};
        rgba[draggingColorChannel] = val;
        c.set(rgba[0], rgba[1], rgba[2], rgba[3]);
        cls.onChanged();
    }

    // --- 4. BlockPosSetting & Vector3dSetting Editors ---

    private void drawBlockPosEditor(GuiRenderer r, BlockPosSetting bps, double x, double w, double top, double bottom, float fade, float dt) {
        BlockPos pos = bps.get();
        int[] vals = {pos.getX(), pos.getY(), pos.getZ()};
        String[] labels = {"X Coordinate", "Y Coordinate", "Z Coordinate"};

        double rowY = top + 16;
        for (int axis = 0; axis < 3; axis++) {
            final int a = axis;
            r.roundedRect(x + PAD, rowY, w - PAD * 2, ROW_H, 8, alpha(EmberUI.WELL, fade));
            text(labels[axis], x + PAD + 14, rowY + (ROW_H - 9) / 2, alpha(EmberUI.TEXT, fade), LABEL_SCALE, 0, 0);

            double boxW = 110, boxH = 24, boxX = x + w - PAD - 12 - boxW, boxY = rowY + (ROW_H - boxH) / 2;
            boolean editing = editingCoordAxis == axis;
            r.roundedRect(boxX, boxY, boxW, boxH, 6, alpha(new Color(18, 18, 22, 255), fade));
            if (editing) r.glow(boxX, boxY, boxW, boxH, 6, new Color(accent().r, accent().g, accent().b, (int) (100 * fade)), false);

            String shown = editing ? coordBuffer + "|" : String.valueOf(vals[axis]);
            text(shown, boxX + boxW / 2, boxY + (boxH - 9) / 2, alpha(editing ? accent() : EmberUI.TEXT, fade), LABEL_SCALE, 2, 0);
            hits.add(new Hit(boxX, boxY, boxW, boxH, () -> {
                commitPendingEdits();
                editingCoordAxis = a;
                coordBuffer = String.valueOf(vals[a]);
            }));
            rowY += ROW_H + 10;
        }

        if (mc.player != null) {
            double btnW = 160, btnH = 30, btnX = x + PAD, btnY = rowY + 6;
            boolean bHover = over(btnX, btnY, btnW, btnH);
            float ba = anim("bpos_player", bHover, dt);
            r.roundedRect(btnX, btnY, btnW, btnH, 7, alpha(lerp(EmberUI.WELL, accent(), 0.22f + ba * 0.18f), fade));
            text("Use Player Position", btnX + btnW / 2, btnY + (btnH - 9) / 2, alpha(EmberUI.TEXT, fade), LABEL_SCALE, 2, 0);
            hits.add(new Hit(btnX, btnY, btnW, btnH, () -> {
                editingCoordAxis = -1;
                bps.set(mc.player.getBlockPos());
            }));
        }
    }

    private void drawVector3dEditor(GuiRenderer r, Vector3dSetting vs, double x, double w, double top, double bottom, float fade, float dt) {
        Vector3d vec = vs.get();
        double[] vals = {vec.x, vec.y, vec.z};
        String[] labels = {"X Component", "Y Component", "Z Component"};

        double rowY = top + 16;
        for (int axis = 0; axis < 3; axis++) {
            final int a = axis;
            r.roundedRect(x + PAD, rowY, w - PAD * 2, ROW_H, 8, alpha(EmberUI.WELL, fade));
            text(labels[axis], x + PAD + 14, rowY + (ROW_H - 9) / 2, alpha(EmberUI.TEXT, fade), LABEL_SCALE, 0, 0);

            double boxW = 110, boxH = 24, boxX = x + w - PAD - 12 - boxW, boxY = rowY + (ROW_H - boxH) / 2;
            boolean editing = editingCoordAxis == axis;
            r.roundedRect(boxX, boxY, boxW, boxH, 6, alpha(new Color(18, 18, 22, 255), fade));
            if (editing) r.glow(boxX, boxY, boxW, boxH, 6, new Color(accent().r, accent().g, accent().b, (int) (100 * fade)), false);

            String shown = editing ? coordBuffer + "|" : String.format("%.2f", vals[axis]);
            text(shown, boxX + boxW / 2, boxY + (boxH - 9) / 2, alpha(editing ? accent() : EmberUI.TEXT, fade), LABEL_SCALE, 2, 0);
            hits.add(new Hit(boxX, boxY, boxW, boxH, () -> {
                commitPendingEdits();
                editingCoordAxis = a;
                coordBuffer = String.format(Locale.ROOT, "%.2f", vals[a]);
            }));
            rowY += ROW_H + 10;
        }
    }

    // --- 5. StatusEffectAmplifierMapSetting Editor ---

    private void drawAmplifierMapEditor(GuiRenderer r, StatusEffectAmplifierMapSetting mapSetting, double x, double w, double top, double bottom, float fade, float dt) {
        Reference2IntMap<StatusEffect> map = mapSetting.get();
        double listTop = top + 8;
        double contentH = cachedChoices.size() * ROW_H;
        maxScroll = Math.max(0, contentH + 8 - (bottom - listTop));
        scroll = MathHelper.clamp(scroll, 0, maxScroll);

        double rowY = listTop + 4 - scroll;
        for (ChoiceItem item : cachedChoices) {
            StatusEffect effect = (StatusEffect) item.value();
            int level = map.getOrDefault(effect, 0);
            if (rowY >= listTop - 0.5 && rowY + ROW_H <= bottom + 0.5) {
                text(item.label(), x + PAD, rowY + (ROW_H - 9) / 2, alpha(level > 0 ? EmberUI.TEXT : EmberUI.TEXT_DIM, fade), LABEL_SCALE, 0, w - PAD * 2 - 120);

                double bw = 24, bh = 22, by = rowY + (ROW_H - bh) / 2;
                double plusX = x + w - PAD - bw;
                double minusX = plusX - 48 - bw;

                r.roundedRect(minusX, by, bw, bh, 6, alpha(EmberUI.WELL, fade));
                text("-", minusX + bw / 2, by + (bh - 9) / 2, alpha(EmberUI.TEXT, fade), LABEL_SCALE, 2, 0);
                hits.add(new Hit(minusX, by, bw, bh, () -> {
                    map.put(effect, Math.max(0, level - 1));
                    mapSetting.onChanged();
                }));

                text(String.valueOf(level), minusX + bw + 24, rowY + (ROW_H - 9) / 2, alpha(level > 0 ? accent() : EmberUI.TEXT_DIM, fade), LABEL_SCALE, 2, 0);

                r.roundedRect(plusX, by, bw, bh, 6, alpha(EmberUI.WELL, fade));
                text("+", plusX + bw / 2, by + (bh - 9) / 2, alpha(EmberUI.TEXT, fade), LABEL_SCALE, 2, 0);
                hits.add(new Hit(plusX, by, bw, bh, () -> {
                    map.put(effect, Math.min(255, level + 1));
                    mapSetting.onChanged();
                }));
            }
            rowY += ROW_H;
        }

        drawScrollbar(r, x, w, listTop, bottom, contentH, fade);
    }

    // --- 6. Fallback Editor ---

    private void drawFallbackStringEditor(GuiRenderer r, double x, double w, double top, double bottom, float fade, float dt) {
        double boxY = top + 24;
        double boxH = 32;
        r.roundedRect(x + PAD, boxY, w - PAD * 2, boxH, 8, alpha(EmberUI.WELL, fade));
        String val = String.valueOf(setting.get());
        text(val, x + PAD + 12, boxY + (boxH - 9) / 2, alpha(EmberUI.TEXT, fade), LABEL_SCALE, 0, w - PAD * 2 - 24);
    }

    private void drawScrollbar(GuiRenderer r, double x, double w, double top, double bottom, double contentH, float fade) {
        if (maxScroll <= 0) return;
        double trackH = bottom - top - 12;
        double thumbH = Math.max(26, trackH * (trackH / (contentH + 8)));
        double thumbY = top + 6 + (trackH - thumbH) * (scroll / maxScroll);
        r.roundedRect(x + w - 7, thumbY, 3, thumbH, 1.5, new Color(accent().r, accent().g, accent().b, (int) (110 * fade)));
    }

    // --- Text Batching ---

    private void text(String s, double x, double y, Color color, double scale, int align, double maxW) {
        if (s == null || s.isEmpty()) return;
        texts.add(new TextOp(s, x, y, color, scale, align, maxW));
    }

    public void renderText(DrawContext graphics) {
        if (setting == null) return;
        Map<Double, List<TextOp>> byScale = new TreeMap<>();
        for (TextOp op : texts) byScale.computeIfAbsent(op.scale(), k -> new ArrayList<>()).add(op);

        for (Map.Entry<Double, List<TextOp>> entry : byScale.entrySet()) {
            theme.textRenderer().begin(theme.scale(entry.getKey()));
            double lineH = theme.textRenderer().getHeight(false);

            for (TextOp op : entry.getValue()) {
                String s = op.maxW() > 0 ? fit(op.text(), op.maxW()) : op.text();
                double tw = theme.textWidth(s);
                double drawX = switch (op.align()) {
                    case 1 -> op.x() - tw;
                    case 2 -> op.x() - tw / 2;
                    default -> op.x();
                };
                double drawY = op.y() + (9 - lineH) / 2;
                theme.textRenderer().render(s, drawX, drawY, op.color(), false);
            }
            theme.textRenderer().end();
        }
    }

    private String fit(String s, double maxWidth) {
        if (theme.textWidth(s) <= maxWidth) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() > 1 && theme.textWidth(sb + "...") > maxWidth) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb + "...";
    }

    // --- Input Handling ---

    public boolean mouseClicked(double cx, double cy, int button) {
        if (setting == null) return false;
        mouseDown = true;

        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.contains(cx, cy)) {
                h.action().run();
                return true;
            }
        }

        commitPendingEdits();
        addingStringFocused = false;
        searchFocused = false;

        double w = Math.min(MAX_W, getWindowWidth() - 40);
        double x = (getWindowWidth() - w) / 2;
        if (cx < x || cx > x + w) {
            close();
            onBack.run();
        }
        return true;
    }

    public void mouseReleased() {
        mouseDown = false;
        draggingColorIndex = -1;
        draggingColorChannel = -1;
    }

    public boolean mouseScrolled(double amount) {
        if (setting == null) return false;
        scroll = MathHelper.clamp(scroll - amount * ROW_H * 1.1, 0, maxScroll);
        return true;
    }

    public boolean keyPressed(KeyInput input) {
        if (setting == null) return false;
        int key = input.key();

        if (editingStringIndex >= 0 && setting instanceof StringListSetting sls) {
            if (key == GLFW_KEY_ESCAPE) {
                editingStringIndex = -1;
                editingStringBuffer = "";
                return true;
            } else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
                commitPendingEdits();
                return true;
            } else if (key == GLFW_KEY_BACKSPACE && !editingStringBuffer.isEmpty()) {
                editingStringBuffer = editingStringBuffer.substring(0, editingStringBuffer.length() - 1);
                return true;
            }
            return true;
        }

        if (addingStringFocused && setting instanceof StringListSetting sls) {
            if (key == GLFW_KEY_ESCAPE) {
                addingStringFocused = false;
                return true;
            } else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
                addNewString(sls);
                return true;
            } else if (key == GLFW_KEY_BACKSPACE && !newStringBuffer.isEmpty()) {
                newStringBuffer = newStringBuffer.substring(0, newStringBuffer.length() - 1);
                return true;
            }
            return true;
        }

        if (editingCoordAxis >= 0) {
            if (key == GLFW_KEY_ESCAPE) {
                editingCoordAxis = -1;
                coordBuffer = "";
                return true;
            } else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
                commitPendingEdits();
                return true;
            } else if (key == GLFW_KEY_BACKSPACE && !coordBuffer.isEmpty()) {
                coordBuffer = coordBuffer.substring(0, coordBuffer.length() - 1);
                return true;
            }
            return true;
        }

        if (searchFocused) {
            if (key == GLFW_KEY_ESCAPE) {
                if (!search.isEmpty()) {
                    search = "";
                    scroll = 0;
                } else {
                    searchFocused = false;
                }
                return true;
            } else if (key == GLFW_KEY_BACKSPACE && !search.isEmpty()) {
                search = search.substring(0, search.length() - 1);
                scroll = 0;
                return true;
            }
            return true;
        }

        if (key == GLFW_KEY_ESCAPE) {
            close();
            onBack.run();
        }
        return true;
    }

    public boolean charTyped(CharInput input) {
        if (setting == null) return false;
        char c = (char) input.codepoint();
        if (c < 32) return true;

        if (editingStringIndex >= 0) {
            if (editingStringBuffer.length() < 256) editingStringBuffer += c;
            return true;
        }
        if (addingStringFocused) {
            if (newStringBuffer.length() < 256) newStringBuffer += c;
            return true;
        }
        if (editingCoordAxis >= 0) {
            boolean allowDot = setting instanceof Vector3dSetting && c == '.' && !coordBuffer.contains(".");
            boolean allowMinus = c == '-' && coordBuffer.isEmpty();
            if ((c >= '0' && c <= '9') || allowDot || allowMinus) {
                if (coordBuffer.length() < 18) coordBuffer += c;
            }
            return true;
        }
        if (searchFocused || isSearchable(setting)) {
            searchFocused = true;
            if (search.length() < 64) {
                search += c;
                scroll = 0;
            }
            return true;
        }
        return true;
    }

    private void commitPendingEdits() {
        if (editingStringIndex >= 0 && setting instanceof StringListSetting sls) {
            List<String> copy = new ArrayList<>(sls.get());
            if (editingStringIndex < copy.size()) {
                String trimmed = editingStringBuffer.trim();
                if (trimmed.isEmpty()) copy.remove(editingStringIndex);
                else copy.set(editingStringIndex, trimmed);
                sls.set(copy);
            }
            editingStringIndex = -1;
            editingStringBuffer = "";
        }

        if (editingCoordAxis >= 0) {
            try {
                if (setting instanceof BlockPosSetting bps) {
                    int val = Integer.parseInt(coordBuffer.trim());
                    BlockPos p = bps.get();
                    int[] xyz = {p.getX(), p.getY(), p.getZ()};
                    xyz[editingCoordAxis] = val;
                    bps.set(new BlockPos(xyz[0], xyz[1], xyz[2]));
                } else if (setting instanceof Vector3dSetting vs) {
                    double val = Double.parseDouble(coordBuffer.trim());
                    Vector3d v = vs.get();
                    double[] xyz = {v.x, v.y, v.z};
                    xyz[editingCoordAxis] = val;
                    vs.set(xyz[0], xyz[1], xyz[2]);
                }
            } catch (NumberFormatException ignored) {
            }
            editingCoordAxis = -1;
            coordBuffer = "";
        }
    }
}
