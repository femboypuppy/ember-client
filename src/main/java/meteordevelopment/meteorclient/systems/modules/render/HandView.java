/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.ArmRenderEvent;
import meteordevelopment.meteorclient.events.render.HeldItemRendererEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.HandLayout;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.screens.HandEditorScreen;
import meteordevelopment.meteorclient.gui.screens.HandMirrorScreen;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.RotationAxis;
import org.joml.Vector3d;

public class HandView extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMainHand = settings.createGroup("Main Hand");
    private final SettingGroup sgOffHand = settings.createGroup("Off Hand");
    private final SettingGroup sgArm = settings.createGroup("Arm");

    // General

    public final Setting<Boolean> includeEmptyArms = sgGeneral.add(new BoolSetting.Builder()
        .name("include-empty-arms").description("Apply each hand's layout to empty arms too, before the shared Arm adjustments.")
        .defaultValue(true).build());

    private final Setting<Boolean> followRotations = sgGeneral.add(new BoolSetting.Builder()
        .name("server-rotations")
        .description("Makes your hands follow your serverside rotations.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> oldAnimations = sgGeneral.add(new BoolSetting.Builder()
        .name("old-animations")
        .description("Changes hit animations to those like 1.8")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> skipSwapping = sgGeneral.add(new BoolSetting.Builder()
        .name("skip-swapping-animation")
        .description("Whether or not to skip the item swapping animation")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> disableFoodAnimation = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-eating-animation")
        .description("Disables the eating animation. Potentially desirable if it goes offscreen.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> swordSlash = sgGeneral.add(new BoolSetting.Builder()
        .name("sword-slash")
        .description("Replaces the sword swing animation with the offhand idle animation.")
        .defaultValue(false)
        .build()
    );

    public final Setting<SwingMode> swingMode = sgGeneral.add(new EnumSetting.Builder<SwingMode>()
        .name("swing-mode")
        .description("Modifies your client & server hand swinging.")
        .defaultValue(SwingMode.None)
        .build()
    );

    public final Setting<Integer> swingSpeed = sgGeneral.add(new IntSetting.Builder()
        .name("swing-speed")
        .description("The swing speed of your hands.")
        .defaultValue(6)
        .range(0, 20)
        .sliderMax(20)
        .build()
    );

    public final Setting<Double> mainSwing = sgGeneral.add(new DoubleSetting.Builder()
        .name("main-hand-progress")
        .description("The swing progress of your main hand.")
        .defaultValue(0)
        .range(0, 1)
        .sliderMax(1)
        .build()
    );

    public final Setting<Double> offSwing = sgGeneral.add(new DoubleSetting.Builder()
        .name("off-hand-progress")
        .description("The swing progress of your off hand.")
        .defaultValue(0)
        .range(0, 1)
        .sliderMax(1)
        .build()
    );

    // Main Hand

    private final Setting<Vector3d> scaleMain = sgMainHand.add(new Vector3dSetting.Builder()
        .name("scale")
        .description("The scale of your main hand.")
        .defaultValue(1, 1, 1)
        .sliderMax(5)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Vector3d> posMain = sgMainHand.add(new Vector3dSetting.Builder()
        .name("position")
        .description("The position of your main hand.")
        .defaultValue(0, 0, 0)
        .sliderRange(-3, 3)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Vector3d> rotMain = sgMainHand.add(new Vector3dSetting.Builder()
        .name("rotation")
        .description("The rotation of your main hand.")
        .defaultValue(0, 0, 0)
        .sliderRange(-180, 180)
        .decimalPlaces(0)
        .build()
    );

    // Offhand

    private final Setting<Vector3d> scaleOff = sgOffHand.add(new Vector3dSetting.Builder()
        .name("scale")
        .description("The scale of your off hand.")
        .defaultValue(1, 1, 1)
        .sliderMax(5)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Vector3d> posOff = sgOffHand.add(new Vector3dSetting.Builder()
        .name("position")
        .description("The position of your off hand.")
        .defaultValue(0, 0, 0)
        .sliderRange(-3, 3)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Vector3d> rotOff = sgOffHand.add(new Vector3dSetting.Builder()
        .name("rotation")
        .description("The rotation of your off hand.")
        .defaultValue(0, 0, 0)
        .sliderRange(-180, 180)
        .decimalPlaces(0)
        .build()
    );

    // Arm

    private final Setting<Vector3d> scaleArm = sgArm.add(new Vector3dSetting.Builder()
        .name("scale")
        .defaultValue(1, 1, 1)
        .sliderMax(5)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Vector3d> posArm = sgArm.add(new Vector3dSetting.Builder()
        .name("position")
        .defaultValue(0, 0, 0)
        .sliderRange(-3, 3)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Vector3d> rotArm = sgArm.add(new Vector3dSetting.Builder()
        .name("rotation")
        .defaultValue(0, 0, 0)
        .sliderRange(-180, 180)
        .decimalPlaces(0)
        .build()
    );

    public HandView() {
        super(Categories.Render, "hand-view", "Alters the way items are rendered in your hands.");
    }

    public HandLayout.Pose pose(boolean main) {
        return HandLayout.Pose.of((main ? posMain : posOff).get(), (main ? rotMain : rotOff).get(), (main ? scaleMain : scaleOff).get());
    }

    public void setPose(boolean main, HandLayout.Pose pose) {
        if (!Double.isFinite(pose.x() + pose.y() + pose.z() + pose.rx() + pose.ry() + pose.rz() + pose.sx() + pose.sy() + pose.sz())) return;
        (main ? posMain : posOff).set(pose.position());
        (main ? rotMain : rotOff).set(pose.rotation());
        (main ? scaleMain : scaleOff).set(pose.scale());
    }
    public HandLayout.Snapshot snapshot() { return new HandLayout.Snapshot(pose(true), pose(false)); }
    public void restore(HandLayout.Snapshot snapshot) { setPose(true, snapshot.main()); setPose(false, snapshot.off()); }
    public void mirrorFrom(boolean main) { setPose(!main, HandLayout.mirror(pose(main))); }

    @Override public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        list.add(theme.button("Make symmitrical")).expandX().widget().action = () -> mc.setScreen(new HandMirrorScreen(theme, this, () -> {}));
        list.add(theme.button("Open hand editor")).expandX().widget().action = () -> mc.setScreen(new HandEditorScreen(theme, this));
        return list;
    }

    @EventHandler
    private void onHeldItemRender(HeldItemRendererEvent event) {
        if (Rotations.rotating && followRotations.get()) {
            applyServerRotations(event.matrix);
        }

        if (event.hand == Hand.MAIN_HAND) {
            rotate(event.matrix, rotMain.get());
            scale(event.matrix, scaleMain.get());
            translate(event.matrix, posMain.get());
        }
        else {
            rotate(event.matrix, rotOff.get());
            scale(event.matrix, scaleOff.get());
            translate(event.matrix, posOff.get());
        }
    }

    @EventHandler
    private void onRenderArm(ArmRenderEvent event) {
        if (includeEmptyArms.get()) {
            boolean main = event.hand == Hand.MAIN_HAND;
            rotate(event.matrix, (main ? rotMain : rotOff).get());
            scale(event.matrix, (main ? scaleMain : scaleOff).get());
            translate(event.matrix, (main ? posMain : posOff).get());
        }
        rotate(event.matrix, rotArm.get());
        scale(event.matrix, scaleArm.get());
        translate(event.matrix, posArm.get());
    }

    private void rotate(MatrixStack matrix, Vector3d rotation) {
        matrix.multiply(RotationAxis.POSITIVE_X.rotationDegrees((float) rotation.x));
        matrix.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((float) rotation.y));
        matrix.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) rotation.z));
    }

    private void scale(MatrixStack matrix, Vector3d scale) {
        matrix.scale((float) scale.x, (float) scale.y, (float) scale.z);
    }

    private void translate(MatrixStack matrix, Vector3d translation) {
        matrix.translate((float) translation.x, (float) translation.y, (float) translation.z);
    }

    private void applyServerRotations(MatrixStack matrix) {
        matrix.multiply(RotationAxis.POSITIVE_X.rotationDegrees(mc.player.getPitch() - Rotations.serverPitch));
        matrix.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(mc.player.getYaw() - Rotations.serverYaw));
    }

    public boolean oldAnimations() {
        return isActive() && oldAnimations.get();
    }

    public boolean skipSwapping() {
        return isActive() && skipSwapping.get();
    }

    public boolean disableFoodAnimation() {
        return isActive() && disableFoodAnimation.get();
    }

    public boolean swordSlash() {
        return isActive() && swordSlash.get();
    }

    public enum SwingMode {
        Offhand,
        Mainhand,
        None
    }
}
