package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;

public class ShieldBreaker extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> onlyAxe = sgGeneral.add(new BoolSetting.Builder()
        .name("only-axe")
        .description("Only swing when an axe is in your hotbar, since only axes disable shields.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> crosshairOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("crosshair-only")
        .description("Only target the player you are looking at. Off targets the nearest blocking player and rotates to them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance to a blocking player.")
        .defaultValue(3.5)
        .min(1)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between shield-break attempts.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 40)
        .build()
    );

    private int timer;

    public ShieldBreaker() {
        super(Categories.Donut, "shield-breaker", "Swaps to an axe and hits players who are blocking.");
    }

    @Override
    public void onActivate() {
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (timer > 0) {
            timer--;
            return;
        }

        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        PlayerEntity target = findTarget();
        if (target == null) return;

        FindItemResult axe = InvUtils.findInHotbar(stack -> stack.isIn(ItemTags.AXES));
        if (!axe.found() && onlyAxe.get()) return;

        if (crosshairOnly.get()) {
            hit(target, axe);
        } else {
            Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target), () -> hit(target, axe));
        }

        timer = delay.get();
    }

    private PlayerEntity findTarget() {
        double maxSq = range.get() * range.get();

        if (crosshairOnly.get()) {
            if (mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof PlayerEntity player
                && player.isBlocking() && mc.player.squaredDistanceTo(player) <= maxSq) {
                return player;
            }
            return null;
        }

        PlayerEntity nearest = null;
        double nearestSq = maxSq;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isBlocking()) continue;

            double distSq = mc.player.squaredDistanceTo(player);
            if (distSq <= nearestSq) {
                nearest = player;
                nearestSq = distSq;
            }
        }
        return nearest;
    }

    private void hit(PlayerEntity target, FindItemResult axe) {
        if (axe.found()) InvUtils.swap(axe.slot(), true);

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);

        if (axe.found()) InvUtils.swapBack();
    }
}
