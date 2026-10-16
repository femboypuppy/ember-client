/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.WireframeEntityRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.utils.render.EmberShapes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgEmber = settings.createGroup("Ember Style");
    private final SettingGroup sgColors = settings.createGroup("Colors");

    // General

    public final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Rendering mode.")
        .defaultValue(Mode.Ember)
        .build()
    );

    public final Setting<Boolean> highlightTarget = sgGeneral.add(new BoolSetting.Builder()
        .name("highlight-target")
        .description("highlights the currently targeted entity differently")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> targetHitbox = sgGeneral.add(new BoolSetting.Builder()
        .name("target-hitbox")
        .description("draw the hitbox of the target entity")
        .defaultValue(true)
        .visible(highlightTarget::get)
        .build()
    );

    public final Setting<Integer> outlineWidth = sgGeneral.add(new IntSetting.Builder()
        .name("outline-width")
        .description("The width of the shader outline.")
        .visible(() -> mode.get() == Mode.Shader)
        .defaultValue(2)
        .range(1, 10)
        .sliderRange(1, 5)
        .build()
    );

    public final Setting<Double> glowMultiplier = sgGeneral.add(new DoubleSetting.Builder()
        .name("glow-multiplier")
        .description("Multiplier for glow effect")
        .visible(() -> mode.get() == Mode.Shader)
        .decimalPlaces(3)
        .defaultValue(3.5)
        .min(0)
        .sliderMax(10)
        .build()
    );

    public final Setting<Boolean> ignoreSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-self")
        .description("Ignores yourself drawing the shader.")
        .defaultValue(true)
        .build()
    );

    public final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .visible(() -> mode.get() != Mode.Glow)
        .defaultValue(ShapeMode.Both)
        .build()
    );

    public final Setting<Double> fillOpacity = sgGeneral.add(new DoubleSetting.Builder()
        .name("fill-opacity")
        .description("The opacity of the shape fill.")
        .visible(() -> shapeMode.get() != ShapeMode.Lines && mode.get() != Mode.Glow)
        .defaultValue(0.3)
        .range(0, 1)
        .sliderMax(1)
        .build()
    );

    private final Setting<Double> fadeDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("fade-distance")
        .description("The distance from an entity where the color begins to fade.")
        .defaultValue(3)
        .min(0)
        .sliderMax(12)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Select specific entities.")
        .defaultValue(EntityType.PLAYER)
        .build()
    );

    // Ember style

    private final Setting<BoxStyle> boxStyle = sgEmber.add(new EnumSetting.Builder<BoxStyle>()
        .name("box-style")
        .description("Full outline or just the four corners.")
        .defaultValue(BoxStyle.Corners)
        .visible(this::isEmber)
        .build()
    );

    private final Setting<Double> lineWidth = sgEmber.add(new DoubleSetting.Builder()
        .name("line-width")
        .description("Thickness of the box lines in pixels.")
        .defaultValue(2.0)
        .range(1.0, 5.0)
        .sliderRange(1.0, 5.0)
        .decimalPlaces(1)
        .visible(this::isEmber)
        .build()
    );

    private final Setting<Boolean> outline = sgEmber.add(new BoolSetting.Builder()
        .name("dark-outline")
        .description("Thin dark border around the lines so they stay readable on bright backgrounds.")
        .defaultValue(true)
        .visible(this::isEmber)
        .build()
    );

    private final Setting<Boolean> glow = sgEmber.add(new BoolSetting.Builder()
        .name("glow")
        .description("Soft coloured halo around the box.")
        .defaultValue(true)
        .visible(this::isEmber)
        .build()
    );

    private final Setting<Boolean> gradientFill = sgEmber.add(new BoolSetting.Builder()
        .name("gradient-fill")
        .description("Fill fades from the top of the box to the bottom instead of a flat colour.")
        .defaultValue(true)
        .visible(this::isEmber)
        .build()
    );

    private final Setting<Boolean> healthBar = sgEmber.add(new BoolSetting.Builder()
        .name("health-bar")
        .description("Vertical health bar next to living entities, absorption shown in gold.")
        .defaultValue(true)
        .visible(this::isEmber)
        .build()
    );

    private final Setting<Boolean> nameLabel = sgEmber.add(new BoolSetting.Builder()
        .name("name")
        .description("Name label above the box.")
        .defaultValue(true)
        .visible(this::isEmber)
        .build()
    );

    private final Setting<Boolean> distanceLabel = sgEmber.add(new BoolSetting.Builder()
        .name("distance")
        .description("Distance next to the name.")
        .defaultValue(true)
        .visible(() -> isEmber() && nameLabel.get())
        .build()
    );

    private final Setting<Double> textScale = sgEmber.add(new DoubleSetting.Builder()
        .name("text-scale")
        .defaultValue(0.8)
        .range(0.4, 2.0)
        .sliderRange(0.4, 2.0)
        .decimalPlaces(2)
        .visible(() -> isEmber() && nameLabel.get())
        .build()
    );

    // Colors

    public final Setting<ESPColorMode> colorMode = sgColors.add(new EnumSetting.Builder<ESPColorMode>()
        .name("color-mode")
        .description("Determines the colors used for entities.")
        .defaultValue(ESPColorMode.EntityType)
        .build()
    );

    public final Setting<Boolean> friendOverride = sgColors.add(new BoolSetting.Builder()
        .name("show-friend-colors")
        .description("Whether or not to override the distance/health color of friends with the friend color.")
        .defaultValue(true)
        .visible(() -> colorMode.get() == ESPColorMode.Distance || colorMode.get() == ESPColorMode.Health)
        .build()
    );

    private final Setting<SettingColor> nonLivingEntityColor = sgColors.add(new ColorSetting.Builder()
        .name("non-living-entity-color")
        .description("The color used for non living entities such as dropped items.")
        .defaultValue(new SettingColor(25, 25, 25))
        .visible(() -> colorMode.get() == ESPColorMode.Health)
        .build()
    );

    private final Setting<SettingColor> playersColor = sgColors.add(new ColorSetting.Builder()
        .name("players-color")
        .description("The other player's color.")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> colorMode.get() == ESPColorMode.EntityType)
        .build()
    );

    private final Setting<SettingColor> animalsColor = sgColors.add(new ColorSetting.Builder()
        .name("animals-color")
        .description("The animal's color.")
        .defaultValue(new SettingColor(25, 255, 25, 255))
        .visible(() -> colorMode.get() == ESPColorMode.EntityType)
        .build()
    );

    private final Setting<SettingColor> waterAnimalsColor = sgColors.add(new ColorSetting.Builder()
        .name("water-animals-color")
        .description("The water animal's color.")
        .defaultValue(new SettingColor(25, 25, 255, 255))
        .visible(() -> colorMode.get() == ESPColorMode.EntityType)
        .build()
    );

    private final Setting<SettingColor> monstersColor = sgColors.add(new ColorSetting.Builder()
        .name("monsters-color")
        .description("The monster's color.")
        .defaultValue(new SettingColor(255, 25, 25, 255))
        .visible(() -> colorMode.get() == ESPColorMode.EntityType)
        .build()
    );

    private final Setting<SettingColor> ambientColor = sgColors.add(new ColorSetting.Builder()
        .name("ambient-color")
        .description("The ambient's color.")
        .defaultValue(new SettingColor(25, 25, 25, 255))
        .visible(() -> colorMode.get() == ESPColorMode.EntityType)
        .build()
    );

    private final Setting<SettingColor> miscColor = sgColors.add(new ColorSetting.Builder()
        .name("misc-color")
        .description("The misc color.")
        .defaultValue(new SettingColor(175, 175, 175, 255))
        .visible(() -> colorMode.get() == ESPColorMode.EntityType)
        .build()
    );

    private final Setting<SettingColor> targetColor = sgColors.add(new ColorSetting.Builder()
        .name("target-color")
        .description("The target color.")
        .defaultValue(new SettingColor(200, 200, 200, 255))
        .visible(highlightTarget::get)
        .build()
    );

    private final Setting<SettingColor> targetHitboxColor = sgColors.add(new ColorSetting.Builder()
        .name("target-hitbox-color")
        .description("The target hitbox color.")
        .defaultValue(new SettingColor(100, 200, 200, 255))
        .visible(() -> highlightTarget.get() && targetHitbox.get())
        .build()
    );

    private final Color lineColor = new Color();
    private final Color sideColor = new Color();
    private final Color baseColor = new Color();

    private final Vector3d pos1 = new Vector3d();
    private final Vector3d pos2 = new Vector3d();
    private final Vector3d pos = new Vector3d();

    private int count;

    public ESP() {
        super(Categories.Render, "esp", "Renders entities through walls.");
    }

    // Box

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mode.get() == Mode._2D || mode.get() == Mode.Ember) return;

        count = 0;

        Entity target = null;
        if (highlightTarget.get() && targetHitbox.get() && mc.crosshairTarget instanceof EntityHitResult hr) target = hr.getEntity();

        for (Entity entity : mc.world.getEntities()) {
            if (target != entity && shouldSkip(entity)) continue;
            if (target == entity || mode.get() == Mode.Box || mode.get() == Mode.Wireframe) drawBoundingBox(event, entity);
            count++;
        }
    }

    private void drawBoundingBox(Render3DEvent event, Entity entity) {
        Color color = getColor(entity);
        if (color != null) {
            lineColor.set(color);
            sideColor.set(color).a((int) (sideColor.a * fillOpacity.get()));
        }

        if (mode.get() == Mode.Wireframe) {
            WireframeEntityRenderer.render(event, entity, 1, sideColor, lineColor, shapeMode.get());
        }

        boolean target = drawAsTarget(entity);

        if (mode.get() == Mode.Box || (targetHitbox.get() && target)) {
            double x = MathHelper.lerp(event.tickDelta, entity.lastRenderX, entity.getX()) - entity.getX();
            double y = MathHelper.lerp(event.tickDelta, entity.lastRenderY, entity.getY()) - entity.getY();
            double z = MathHelper.lerp(event.tickDelta, entity.lastRenderZ, entity.getZ()) - entity.getZ();

            ShapeMode shape = shapeMode.get();
            if (target && mode.get() != Mode.Box) shape = ShapeMode.Lines;
            if (target) lineColor.set(targetHitboxColor.get());

            Box box = entity.getBoundingBox();
            event.renderer.box(x + box.minX, y + box.minY, z + box.minZ, x + box.maxX, y + box.maxY, z + box.maxZ, sideColor, lineColor, shape, 0);
        }
    }

    // 2D

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mode.get() == Mode.Ember) {
            renderEmber(event);
            return;
        }
        if (mode.get() != Mode._2D) return;

        Renderer2D.COLOR.begin();
        count = 0;

        for (Entity entity : mc.world.getEntities()) {
            if (shouldSkip(entity)) continue;

            Box box = entity.getBoundingBox();

            double x = MathHelper.lerp(event.tickDelta, entity.lastRenderX, entity.getX()) - entity.getX();
            double y = MathHelper.lerp(event.tickDelta, entity.lastRenderY, entity.getY()) - entity.getY();
            double z = MathHelper.lerp(event.tickDelta, entity.lastRenderZ, entity.getZ()) - entity.getZ();

            // Check corners
            pos1.set(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
            pos2.set(0, 0, 0);

            //     Bottom
            if (checkCorner(box.minX + x, box.minY + y, box.minZ + z, pos1, pos2)) continue;
            if (checkCorner(box.maxX + x, box.minY + y, box.minZ + z, pos1, pos2)) continue;
            if (checkCorner(box.minX + x, box.minY + y, box.maxZ + z, pos1, pos2)) continue;
            if (checkCorner(box.maxX + x, box.minY + y, box.maxZ + z, pos1, pos2)) continue;

            //     Top
            if (checkCorner(box.minX + x, box.maxY + y, box.minZ + z, pos1, pos2)) continue;
            if (checkCorner(box.maxX + x, box.maxY + y, box.minZ + z, pos1, pos2)) continue;
            if (checkCorner(box.minX + x, box.maxY + y, box.maxZ + z, pos1, pos2)) continue;
            if (checkCorner(box.maxX + x, box.maxY + y, box.maxZ + z, pos1, pos2)) continue;

            // Setup color
            Color color = getColor(entity);
            if (color != null) {
                lineColor.set(color);
                sideColor.set(color).a((int) (sideColor.a * fillOpacity.get()));
            }

            // Render
            if (shapeMode.get() != ShapeMode.Lines && sideColor.a > 0) {
                Renderer2D.COLOR.quad(pos1.x, pos1.y, pos2.x - pos1.x, pos2.y - pos1.y, sideColor);
            }

            if (shapeMode.get() != ShapeMode.Sides) {
                Renderer2D.COLOR.line(pos1.x, pos1.y, pos1.x, pos2.y, lineColor);
                Renderer2D.COLOR.line(pos2.x, pos1.y, pos2.x, pos2.y, lineColor);
                Renderer2D.COLOR.line(pos1.x, pos1.y, pos2.x, pos1.y, lineColor);
                Renderer2D.COLOR.line(pos1.x, pos2.y, pos2.x, pos2.y, lineColor);
            }

            count++;
        }

        Renderer2D.COLOR.render();
    }

    // Ember

    private record Label(String name, String distance, double centerX, double bottomY, Color color) {}

    private final List<Label> labels = new ArrayList<>();
    private final Color shadowColor = new Color();
    private final Color glowColor = new Color();
    private final Color fillTop = new Color();
    private final Color fillBottom = new Color();
    private final Color barColor = new Color();
    private final Color barTop = new Color();

    private static final Color BAR_BACKGROUND = new Color(0, 0, 0, 150);
    private static final Color LABEL_BACKGROUND = new Color(12, 12, 16, 170);
    private static final Color LABEL_TEXT = new Color(255, 255, 255, 255);
    private static final Color LABEL_DIM = new Color(190, 190, 200, 255);
    private static final Color ABSORPTION = new Color(255, 200, 40, 220);

    private void renderEmber(Render2DEvent event) {
        count = 0;
        labels.clear();

        Renderer2D r = Renderer2D.COLOR;
        r.begin();

        for (Entity entity : mc.world.getEntities()) {
            if (shouldSkip(entity)) continue;
            if (!projectBox(entity, event.tickDelta)) continue;

            Color color = getColor(entity);
            if (color == null) continue;
            lineColor.set(color);

            double x1 = pos1.x, y1 = pos1.y, x2 = pos2.x, y2 = pos2.y;
            double w = x2 - x1, h = y2 - y1;
            if (w < 1 || h < 1) continue;

            double t = lineWidth.get();
            double fade = lineColor.a / 255.0;

            if (glow.get()) {
                for (int i = 3; i >= 1; i--) {
                    glowColor.set(lineColor).a((int) (lineColor.a * 0.12 * (4 - i)));
                    emberShape(r, x1 - i * t, y1 - i * t, x2 + i * t, y2 + i * t, t, glowColor);
                }
            }

            if (shapeMode.get() != ShapeMode.Lines) {
                int fillAlpha = (int) (lineColor.a * fillOpacity.get());
                fillTop.set(lineColor).a(fillAlpha);
                fillBottom.set(lineColor).a(gradientFill.get() ? fillAlpha / 6 : fillAlpha);
                r.quad(x1, y1, w, h, fillTop, fillTop, fillBottom, fillBottom);
            }

            if (shapeMode.get() != ShapeMode.Sides) {
                if (outline.get()) {
                    shadowColor.set(0, 0, 0, (int) (170 * fade));
                    emberShape(r, x1 - 1, y1 - 1, x2 + 1, y2 + 1, t + 2, shadowColor);
                }
                emberShape(r, x1, y1, x2, y2, t, lineColor);
            }

            if (healthBar.get() && entity instanceof LivingEntity living) drawHealthBar(r, living, x1, y1, h, t, fade);

            if (nameLabel.get()) {
                String name = entity instanceof PlayerEntity player ? player.getName().getString() : entity.getType().getName().getString();
                String distance = distanceLabel.get() ? String.format("%.0fm", PlayerUtils.distanceToCamera(entity)) : "";
                labels.add(new Label(name, distance, (x1 + x2) / 2, y1, new Color(lineColor)));
            }

            count++;
        }

        drawLabelBackgrounds(r);
        r.render();
        drawLabelText();
    }

    /** Projects the entity's interpolated bounding box into pos1 (min) / pos2 (max) screen coordinates. */
    private boolean projectBox(Entity entity, float tickDelta) {
        Box box = entity.getBoundingBox();
        double x = MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()) - entity.getX();
        double y = MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()) - entity.getY();
        double z = MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ()) - entity.getZ();

        pos1.set(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
        pos2.set(0, 0, 0);

        return !(checkCorner(box.minX + x, box.minY + y, box.minZ + z, pos1, pos2)
            || checkCorner(box.maxX + x, box.minY + y, box.minZ + z, pos1, pos2)
            || checkCorner(box.minX + x, box.minY + y, box.maxZ + z, pos1, pos2)
            || checkCorner(box.maxX + x, box.minY + y, box.maxZ + z, pos1, pos2)
            || checkCorner(box.minX + x, box.maxY + y, box.minZ + z, pos1, pos2)
            || checkCorner(box.maxX + x, box.maxY + y, box.minZ + z, pos1, pos2)
            || checkCorner(box.minX + x, box.maxY + y, box.maxZ + z, pos1, pos2)
            || checkCorner(box.maxX + x, box.maxY + y, box.maxZ + z, pos1, pos2));
    }

    /** Full outline or corner brackets made of quads, so the line width is real pixels. */
    private void emberShape(Renderer2D r, double x1, double y1, double x2, double y2, double t, Color c) {
        double w = x2 - x1, h = y2 - y1;
        t = Math.min(t, Math.min(w, h) / 2);

        if (boxStyle.get() == BoxStyle.Full) {
            r.quad(x1, y1, w, t, c);
            r.quad(x1, y2 - t, w, t, c);
            r.quad(x1, y1 + t, t, h - t * 2, c);
            r.quad(x2 - t, y1 + t, t, h - t * 2, c);
            return;
        }

        double lx = Math.max(t * 2, w * 0.28);
        double ly = Math.max(t * 2, h * 0.22);

        r.quad(x1, y1, lx, t, c);
        r.quad(x1, y1 + t, t, ly - t, c);
        r.quad(x2 - lx, y1, lx, t, c);
        r.quad(x2 - t, y1 + t, t, ly - t, c);
        r.quad(x1, y2 - t, lx, t, c);
        r.quad(x1, y2 - ly, t, ly - t, c);
        r.quad(x2 - lx, y2 - t, lx, t, c);
        r.quad(x2 - t, y2 - ly, t, ly - t, c);
    }

    private void drawHealthBar(Renderer2D r, LivingEntity living, double x1, double y1, double h, double t, double fade) {
        float max = Math.max(1f, living.getMaxHealth());
        double ratio = MathHelper.clamp(living.getHealth() / max, 0, 1);
        double absorption = MathHelper.clamp(living.getAbsorptionAmount() / max, 0, 1);

        double barW = Math.max(2, t + 1);
        double bx = x1 - barW - 4;

        BAR_BACKGROUND.a = (int) (150 * fade);
        r.quad(bx - 1, y1 - 1, barW + 2, h + 2, BAR_BACKGROUND);

        // Red at empty, yellow at half, green at full.
        int red = ratio > 0.5 ? (int) (255 * (1 - ratio) * 2) : 255;
        int green = ratio > 0.5 ? 255 : (int) (255 * ratio * 2);
        int alpha = (int) (255 * fade);
        barColor.set(red, green, 60, alpha);
        barTop.set(Math.min(255, red + 50), Math.min(255, green + 50), 110, alpha);

        double filled = h * ratio;
        r.quad(bx, y1 + h - filled, barW, filled, barTop, barTop, barColor, barColor);

        if (absorption > 0) {
            ABSORPTION.a = (int) (220 * fade);
            r.quad(bx, y1 + h - h * absorption, barW, h * absorption, ABSORPTION);
        }
    }

    private void drawLabelBackgrounds(Renderer2D r) {
        if (labels.isEmpty()) return;

        TextRenderer text = TextRenderer.get();
        text.begin(textScale.get());
        for (Label label : labels) {
            double width = labelWidth(text, label);
            double height = text.getHeight();
            double pad = 3;
            double bx = label.centerX - width / 2 - pad;
            double by = label.bottomY - height - pad * 2 - 4;
            LABEL_BACKGROUND.a = (int) (170 * label.color.a / 255.0);
            EmberShapes.roundedQuad(r, bx, by, width + pad * 2, height + pad * 2, 4, LABEL_BACKGROUND);
            // Accent strip in the entity colour along the bottom of the label.
            r.quad(bx + 3, by + height + pad * 2 - 1.5, width + pad * 2 - 6, 1.5, label.color);
        }
        text.end();
    }

    private void drawLabelText() {
        if (labels.isEmpty()) return;

        TextRenderer text = TextRenderer.get();
        text.begin(textScale.get());
        for (Label label : labels) {
            double width = labelWidth(text, label);
            double height = text.getHeight();
            double x = label.centerX - width / 2;
            double y = label.bottomY - height - 3 - 4;
            LABEL_TEXT.a = label.color.a;
            double after = text.render(label.name, x, y, LABEL_TEXT, true);
            if (!label.distance.isEmpty()) {
                LABEL_DIM.a = label.color.a;
                text.render(" " + label.distance, after, y, LABEL_DIM, true);
            }
        }
        text.end();
    }

    private static double labelWidth(TextRenderer text, Label label) {
        double width = text.getWidth(label.name, true);
        if (!label.distance.isEmpty()) width += text.getWidth(" " + label.distance, true);
        return width;
    }

    private boolean isEmber() {
        return mode.get() == Mode.Ember;
    }

    public boolean forceRender() {
        return isActive() && (mode.get() == Mode.Shader || mode.get() == Mode.Glow);
    }

    private boolean checkCorner(double x, double y, double z, Vector3d min, Vector3d max) {
        pos.set(x, y, z);
        if (!NametagUtils.to2D(pos, 1)) return true;

        // Check Min
        if (pos.x < min.x) min.x = pos.x;
        if (pos.y < min.y) min.y = pos.y;
        if (pos.z < min.z) min.z = pos.z;

        // Check Max
        if (pos.x > max.x) max.x = pos.x;
        if (pos.y > max.y) max.y = pos.y;
        if (pos.z > max.z) max.z = pos.z;

        return false;
    }

    // Utils

    public boolean drawAsTarget(Entity entity) {
        return highlightTarget.get() && mc.crosshairTarget instanceof EntityHitResult hr && hr.getEntity() == entity;
    }

    public boolean shouldSkip(Entity entity) {
        if (drawAsTarget(entity)) return false;
        if (!entities.get().contains(entity.getType())) return true;
        if (entity == mc.player && ignoreSelf.get()) return true;
        if (entity == mc.getCameraEntity() && mc.options.getPerspective().isFirstPerson()) return true;
        return !EntityUtils.isInRenderDistance(entity);
    }

    public boolean shouldSkip(EntityType<?> entityType) {
        return !entities.get().contains(entityType);
    }

    public Color getColor(Entity entity) {
        Color color;
        double alpha = 1;

        if (drawAsTarget(entity)) {
            color = targetColor.get();
        } else {
            if (!entities.get().contains(entity.getType())) return null;

            alpha = getFadeAlpha(entity);
            if (alpha == 0) return null;

            color = getEntityTypeColor(entity);
        }

        return baseColor.set(color.r, color.g, color.b, (int) (color.a * alpha));
    }

    private double getFadeAlpha(Entity entity) {
        double dist = PlayerUtils.squaredDistanceToCamera(entity.getX(), entity.getY() + entity.getEyeHeight(entity.getPose()), entity.getZ());
        double fadeDist = Math.pow(fadeDistance.get(), 2);
        double alpha = 1;
        if (dist <= fadeDist * fadeDist) alpha = (float) (Math.sqrt(dist) / fadeDist);
        if (alpha <= 0.075) alpha = 0;
        return alpha;
    }

    public Color getEntityTypeColor(Entity entity) {
        if (colorMode.get() == ESPColorMode.EntityType) {
            if (entity instanceof PlayerEntity player) {
                return PlayerUtils.getPlayerColor(player, playersColor.get());
            } else {
                return switch (entity.getType().getSpawnGroup()) {
                    case CREATURE -> animalsColor.get();
                    case WATER_AMBIENT, WATER_CREATURE, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> waterAnimalsColor.get();
                    case MONSTER -> monstersColor.get();
                    case AMBIENT -> ambientColor.get();
                    default -> miscColor.get();
                };
            }
        }

        if (friendOverride.get() && entity instanceof PlayerEntity player
            && Friends.get().isFriend(player)) {
            return Config.get().friendColor.get();
        }

        if (colorMode.get() == ESPColorMode.Health) return EntityUtils.getColorFromHealth(entity, nonLivingEntityColor.get());
        else return EntityUtils.getColorFromDistance(entity);
    }

    @Override
    public String getInfoString() {
        return Integer.toString(count);
    }

    public boolean isShader() {
        return isActive() && mode.get() == Mode.Shader;
    }

    public boolean isGlow() {
        return isActive() && mode.get() == Mode.Glow;
    }

    public enum ESPColorMode {
        EntityType,
        Distance,
        Health;

        @Override
        public String toString() {
            return this == EntityType ? "Entity Type" : super.toString();
        }
    }

    public enum BoxStyle {
        Full,
        Corners
    }

    public enum Mode {
        Ember,
        Box,
        Wireframe,
        _2D,
        Shader,
        Glow;

        @Override
        public String toString() {
            return this == _2D ? "2D" : super.toString();
        }
    }
}
