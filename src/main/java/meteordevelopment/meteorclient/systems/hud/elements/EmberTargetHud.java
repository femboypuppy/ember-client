package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.EmberIcons;
import meteordevelopment.meteorclient.utils.render.EmberStrip;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Who you are fighting: their head, name, health and distance. The target is held for a moment
 * after you look away, so glancing aside mid-fight does not make the panel disappear and
 * reappear - which is both distracting and useless at the moment you most want to read it.
 */
public class EmberTargetHud extends HudElement {
    public static final HudElementInfo<EmberTargetHud> INFO = new HudElementInfo<>(Hud.GROUP, "ember-target", "Health, armour and distance for the entity you are fighting.", EmberTargetHud::new);

    private static final Color SKIN_TINT = new Color(255, 255, 255, 255);

    private static final Color GOOD = new Color(93, 217, 127, 255);
    private static final Color FAIR = new Color(231, 164, 80, 255);
    private static final Color POOR = new Color(222, 86, 86, 255);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the panel.")
        .defaultValue(1.2)
        .min(0.5)
        .max(3.0)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<Double> hold = sgGeneral.add(new DoubleSetting.Builder()
        .name("hold-seconds")
        .description("How long the last target stays on screen after you look away.")
        .defaultValue(2.5)
        .min(0)
        .max(15)
        .sliderRange(0, 15)
        .build()
    );

    private final Setting<Boolean> showHead = sgGeneral.add(new BoolSetting.Builder()
        .name("head")
        .description("Show the target's face.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("distance")
        .description("Show how far away they are.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showArmor = sgGeneral.add(new BoolSetting.Builder()
        .name("armor")
        .description("Show their armour points.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> playersOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("players-only")
        .description("Ignore mobs and only track players.")
        .defaultValue(false)
        .build()
    );

    private LivingEntity target;
    private long lastSeen;

    public EmberTargetHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        LivingEntity shown = resolveTarget();

        if (shown == null) {
            if (!isInEditor()) {
                setSize(0, 0);
                return;
            }
        }

        double s = scale.get();
        double nameScale = 0.9 * s;
        double subScale = 0.75 * s;

        double nameH = renderer.textHeight(true, nameScale);
        double subH = renderer.textHeight(true, subScale);

        double pad = 9 * s;
        double gap = 9 * s;
        double rowGap = 4 * s;
        double barH = 5 * s;

        String name = shown != null ? shown.getName().getString() : "Target";
        double health = shown != null ? shown.getHealth() : 20;
        double maxHealth = shown != null ? Math.max(1, shown.getMaxHealth()) : 20;
        double pct = Math.max(0, Math.min(1, health / maxHealth));

        String healthText = String.format("%.1f HP", health);
        String subtitle = buildSubtitle(shown);

        double head = showHead.get() ? nameH + rowGap + barH + rowGap + subH : 0;
        double contentW = Math.max(
            renderer.textWidth(name, true, nameScale) + 10 * s + renderer.textWidth(healthText, true, subScale),
            Math.max(90 * s, renderer.textWidth(subtitle, true, subScale)));

        double w = pad + (showHead.get() ? head + gap : 0) + contentW + pad;
        double h = pad * 2 + nameH + rowGap + barH + rowGap + subH;
        double radius = 9 * s;

        setSize(w, h);

        renderer.dropShadow(x, y, w, h, radius, 20 * s);
        renderer.roundedQuad(x, y, w, h, radius, EmberStrip.background());

        double cx = x + pad;

        if (showHead.get()) {
            drawHead(renderer, shown, cx, y + pad, head);
            cx += head + gap;
        }

        Color bright = new Color(255, 255, 255, 255);
        Color dim = new Color(150, 150, 162, 255);
        Color bar = healthColor(pct);

        renderer.text(name, cx, y + pad, bright, true, nameScale);

        double hw = renderer.textWidth(healthText, true, subScale);
        renderer.text(healthText, x + w - pad - hw, y + pad + (nameH - subH) / 2, bar, true, subScale);

        // Health bar
        double barY = y + pad + nameH + rowGap;
        double barW = w - (cx - x) - pad;
        renderer.roundedQuad(cx, barY, barW, barH, barH / 2, new Color(0, 0, 0, 150));
        if (barW * pct > barH) renderer.roundedQuad(cx, barY, barW * pct, barH, barH / 2, bar);

        renderer.text(subtitle, cx, barY + barH + rowGap, dim, true, subScale);
    }

    private String buildSubtitle(LivingEntity shown) {
        StringBuilder sb = new StringBuilder();

        if (showDistance.get() && shown != null && mc.player != null) {
            sb.append(String.format("%.1fm", mc.player.distanceTo(shown)));
        }

        if (showArmor.get() && shown != null) {
            int armor = shown.getArmor();
            if (armor > 0) {
                if (!sb.isEmpty()) sb.append("  ·  ");
                sb.append(armor).append(" armor");
            }
        }

        return sb.isEmpty() ? "-" : sb.toString();
    }

    private void drawHead(HudRenderer renderer, LivingEntity shown, double hx, double hy, double size) {
        renderer.roundedQuad(hx, hy, size, size, size * 0.22, new Color(26, 26, 32, 220));

        if (!(shown instanceof AbstractClientPlayerEntity player)) {
            // Not a player, so there is no skin - mark it with a generic figure instead.
            EmberIcons.draw(renderer, EmberIcons.Glyph.USER, hx + size * 0.2, hy + size * 0.2,
                size * 0.6, EmberPalette.accent(), EmberStrip.background());
            return;
        }

        Identifier skin = player.getSkin().body().texturePath();

        // Face at (8,8) and hat layer at (40,8), both 8x8 patches of a 64x64 skin.
        renderer.post(() -> {
            renderer.textureRegion(skin, hx, hy, size, size, 0.125, 0.125, 0.25, 0.25, SKIN_TINT);
            renderer.textureRegion(skin, hx, hy, size, size, 0.625, 0.125, 0.75, 0.25, SKIN_TINT);
        });
    }

    private Color healthColor(double pct) {
        if (pct > 0.6) return GOOD;
        if (pct > 0.3) return FAIR;
        return POOR;
    }

    /** The entity under the crosshair, or the last one for as long as the hold lasts. */
    private LivingEntity resolveTarget() {
        Entity looking = mc.targetedEntity;

        if (looking instanceof LivingEntity living && living != mc.player && living.isAlive()
            && (!playersOnly.get() || living instanceof AbstractClientPlayerEntity)) {
            target = living;
            lastSeen = System.currentTimeMillis();
            return target;
        }

        if (target != null) {
            boolean expired = System.currentTimeMillis() - lastSeen > hold.get() * 1000;
            if (expired || !target.isAlive() || target.isRemoved() || mc.world == null) target = null;
        }

        return target;
    }
}
