package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * First person swing animations: a slower swing (so mining looks like a steady, heavy swing instead of a flail),
 * a handful of swing styles, and 1.7 style sword block-hitting. Everything here is visual only; nothing changes
 * what is sent to the server.
 */
public class Animations extends Module {
    private final SettingGroup sgSwing = settings.getDefaultGroup();
    private final SettingGroup sgBlock = settings.createGroup("Block Hit");

    private final Setting<Double> swingSlowness = sgSwing.add(new DoubleSetting.Builder()
        .name("swing-slowness")
        .description("How much slower your hand swings. 1 is vanilla, 2 is half speed. Mining keeps one long smooth swing going.")
        .defaultValue(1.8)
        .range(0.5, 4.0)
        .sliderRange(0.5, 4.0)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Boolean> thirdPerson = sgSwing.add(new BoolSetting.Builder()
        .name("third-person")
        .description("Also slow your swing in third person.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Style> style = sgSwing.add(new EnumSetting.Builder<Style>()
        .name("swing-style")
        .description("How the item moves when you swing.")
        .defaultValue(Style.Smooth)
        .build()
    );

    private final Setting<Double> strength = sgSwing.add(new DoubleSetting.Builder()
        .name("strength")
        .description("How far the swing style moves the item.")
        .defaultValue(1.0)
        .range(0.2, 2.0)
        .sliderRange(0.2, 2.0)
        .decimalPlaces(2)
        .visible(() -> style.get() != Style.Vanilla)
        .build()
    );

    private final Setting<Boolean> blockHit = sgBlock.add(new BoolSetting.Builder()
        .name("block-hit")
        .description("Holding use with a sword raises it into the old 1.7 blocking pose, and swings keep that pose.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> blockHeight = sgBlock.add(new DoubleSetting.Builder()
        .name("block-height")
        .description("Raises or lowers the blocking pose.")
        .defaultValue(0.0)
        .range(-0.5, 0.5)
        .sliderRange(-0.5, 0.5)
        .decimalPlaces(2)
        .visible(blockHit::get)
        .build()
    );

    public Animations() {
        super(Categories.Render, "animations", "Slow, smooth swing animations, swing styles and 1.7 sword block-hitting.");
    }

    /** Swing duration in ticks for the local player, scaled by {@link #swingSlowness}. */
    public int swingDuration(int original, boolean firstPerson) {
        if (!isActive() || (!firstPerson && !thirdPerson.get())) return original;
        return Math.max(1, Math.round(original * swingSlowness.get().floatValue()));
    }

    /**
     * Replaces the vanilla swingArm transform. Returns false to let vanilla run.
     * The equip offset has already been applied when this is called.
     */
    public boolean transformSwing(MatrixStack matrices, Arm arm, float swing, ItemStack stack) {
        if (!isActive()) return false;

        int side = arm == Arm.RIGHT ? 1 : -1;
        boolean blocking = isBlockHitting(stack);

        if (!blocking && style.get() == Style.Vanilla) return false;

        float s = strength.get().floatValue();
        float sqrt = MathHelper.sqrt(swing);
        float arc = MathHelper.sin(sqrt * MathHelper.PI);
        float early = MathHelper.sin(swing * swing * MathHelper.PI);

        if (blocking) {
            // The 1.7 block-hit: the sword stays in the blocking pose and dips forward on each swing.
            matrices.translate(0, blockHeight.get().floatValue(), 0);
            matrices.translate(side * -0.14142136F, 0.08F, 0.14142136F);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * 45.0F));
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * early * -20.0F));
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * arc * -20.0F));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(arc * -40.0F * s));
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * -45.0F));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-102.25F));
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * 13.365F));
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * 78.05F));
            return true;
        }

        switch (style.get()) {
            case Smooth -> {
                // Vanilla's shape with the jerky sqrt/square easing replaced by one even arc.
                float t = MathHelper.sin(swing * MathHelper.PI);
                matrices.translate(side * -0.32F * t * s, 0.12F * MathHelper.sin(swing * MathHelper.TAU) * s, -0.18F * t * s);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * (45.0F - 20.0F * t * s)));
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * t * -20.0F * s));
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(t * -70.0F * s));
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * -45.0F));
            }
            case Tap -> {
                // A short downward tap, the arm barely moves.
                matrices.translate(0, -0.18F * arc * s, -0.05F * arc * s);
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(arc * -25.0F * s));
            }
            case Stab -> {
                // Thrusts straight forward.
                matrices.translate(side * -0.08F * arc * s, 0.05F * arc * s, -0.45F * arc * s);
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(arc * -15.0F * s));
            }
            case Spin -> {
                matrices.translate(side * -0.2F * arc * s, 0.08F * arc * s, -0.1F * arc * s);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * swing * 360.0F));
            }
            case Slide -> {
                // Sweeps across the screen sideways.
                matrices.translate(side * -0.55F * arc * s, 0.04F * arc * s, -0.12F * arc * s);
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * arc * -35.0F * s));
            }
            case Swank -> {
                // Rolls the item outward and back.
                matrices.translate(side * -0.1F * arc * s, 0.1F * arc * s, -0.15F * arc * s);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * (45.0F + early * -25.0F * s)));
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * arc * 30.0F * s));
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(arc * -40.0F * s));
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * -45.0F));
            }
            default -> {
                return false;
            }
        }

        return true;
    }

    public boolean isBlockHitting(ItemStack stack) {
        return isActive() && blockHit.get() && stack != null && stack.isIn(ItemTags.SWORDS)
            && mc.options.useKey.isPressed() && mc.currentScreen == null;
    }

    public enum Style {
        Vanilla,
        Smooth,
        Tap,
        Stab,
        Spin,
        Slide,
        Swank
    }
}
