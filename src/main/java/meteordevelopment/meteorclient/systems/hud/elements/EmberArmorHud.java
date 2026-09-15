package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.EmberStrip;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.item.ItemStack;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Your armour, laid out as a row or column of slots on the Ember panel, each with a durability
 * ring or a count underneath. Empty slots stay as dim placeholders so the row does not jump
 * around as pieces break.
 */
public class EmberArmorHud extends HudElement {
    public static final HudElementInfo<EmberArmorHud> INFO = new HudElementInfo<>(Hud.GROUP, "ember-armor", "Your armour with durability, in the Ember style.", EmberArmorHud::new);

    public enum Direction { Horizontal, Vertical }

    public enum Durability { None, Number, Bar, Percent }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the slots.")
        .defaultValue(1.4)
        .min(0.6)
        .max(4.0)
        .sliderRange(0.6, 4.0)
        .build()
    );

    private final Setting<Direction> direction = sgGeneral.add(new EnumSetting.Builder<Direction>()
        .name("direction")
        .description("Lay the slots out across or down.")
        .defaultValue(Direction.Horizontal)
        .build()
    );

    private final Setting<Durability> durability = sgGeneral.add(new EnumSetting.Builder<Durability>()
        .name("durability")
        .description("How remaining durability is shown.")
        .defaultValue(Durability.Bar)
        .build()
    );

    private final Setting<Boolean> background = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Draw the panel behind the slots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> emptySlots = sgGeneral.add(new BoolSetting.Builder()
        .name("empty-slots")
        .description("Keep a placeholder where a piece is missing.")
        .defaultValue(true)
        .build()
    );

    public EmberArmorHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        double s = scale.get();
        double slot = 18 * s;
        double gap = 4 * s;
        double pad = 7 * s;

        double textScale = 0.62 * s;
        double textH = renderer.textHeight(true, textScale);

        boolean horizontal = direction.get() == Direction.Horizontal;
        boolean labels = durability.get() == Durability.Number || durability.get() == Durability.Percent;
        double labelRoom = labels ? textH + 2 * s : 0;

        ItemStack[] pieces = pieces();
        int shown = 0;
        for (ItemStack piece : pieces) if (emptySlots.get() || !piece.isEmpty()) shown++;
        if (shown == 0) shown = emptySlots.get() ? 4 : 0;

        if (shown == 0 && !isInEditor()) {
            setSize(0, 0);
            return;
        }
        if (shown == 0) shown = 4;

        double cellW = slot;
        double cellH = slot + labelRoom;

        double w = horizontal
            ? pad * 2 + shown * cellW + (shown - 1) * gap
            : pad * 2 + cellW;
        double h = horizontal
            ? pad * 2 + cellH
            : pad * 2 + shown * cellH + (shown - 1) * gap;

        setSize(w, h);

        if (background.get()) {
            renderer.dropShadow(x, y, w, h, 8 * s, 20 * s);
            EmberStrip.panel(renderer, x, y, w, h, 8 * s, 1);
        }

        double cx = x + pad, cy = y + pad;

        for (ItemStack piece : pieces) {
            if (!emptySlots.get() && piece.isEmpty()) continue;

            drawSlot(renderer, piece, cx, cy, slot, s, textScale, textH);

            if (horizontal) cx += cellW + gap;
            else cy += cellH + gap;
        }
    }

    private void drawSlot(HudRenderer renderer, ItemStack piece, double sx, double sy,
                          double slot, double s, double textScale, double textH) {
        Color accent = EmberPalette.accent();

        // The well stays put whether or not a piece is in it, so the row never reflows.
        renderer.roundedQuad(sx, sy, slot, slot, 5 * s, new Color(26, 26, 32, 200));

        if (piece.isEmpty()) return;

        // Items go through post() - drawing them inline corrupts the batch the text uses.
        // Sized to leave a little of the well showing around the piece, and centred exactly:
        // the integer form of this call rounds down twice and creeps toward the top left.
        final double itemSize = slot * 0.76;
        final double ix = sx + (slot - itemSize) / 2, iy = sy + (slot - itemSize) / 2;
        renderer.post(() -> renderer.itemExact(piece, ix, iy, itemSize));

        if (!piece.isDamageable() || durability.get() == Durability.None) return;

        int left = piece.getMaxDamage() - piece.getDamage();
        double pct = piece.getMaxDamage() == 0 ? 1 : (double) left / piece.getMaxDamage();

        switch (durability.get()) {
            case Bar -> {
                double barH = 2.5 * s;
                double barY = sy + slot - barH - 1.5 * s;
                double barX = sx + 2.5 * s;
                double barW = slot - 5 * s;

                renderer.roundedQuad(barX, barY, barW, barH, barH / 2, new Color(0, 0, 0, 160));
                renderer.roundedQuad(barX, barY, barW * pct, barH, barH / 2, durabilityColor(pct, accent));
            }
            case Number, Percent -> {
                String label = durability.get() == Durability.Percent
                    ? Math.round(pct * 100) + "%"
                    : String.valueOf(left);

                double lw = renderer.textWidth(label, true, textScale);
                renderer.text(label, sx + (slot - lw) / 2, sy + slot + 2 * s,
                    durabilityColor(pct, accent), true, textScale);
            }
            default -> {
            }
        }
    }

    /** Green through amber to red as a piece wears out, so a breaking item is obvious. */
    private Color durabilityColor(double pct, Color accent) {
        if (pct > 0.5) return new Color(93, 217, 127, 255);
        if (pct > 0.25) return new Color(231, 164, 80, 255);
        if (pct > 0.1) return new Color(222, 120, 86, 255);
        return new Color(222, 86, 86, 255);
    }

    /** Helmet first, boots last - the order armour is normally read in. */
    private ItemStack[] pieces() {
        ItemStack[] out = new ItemStack[4];

        for (int i = 0; i < 4; i++) {
            out[i] = mc.player == null ? ItemStack.EMPTY : mc.player.getInventory().getStack(39 - i);
        }

        return out;
    }
}
