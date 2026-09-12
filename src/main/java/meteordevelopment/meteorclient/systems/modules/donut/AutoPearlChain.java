package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Hand;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Items;

public class AutoPearlChain extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait after landing before throwing the next pearl.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Boolean> stopWhenOut = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-when-out")
        .description("Turn off when you run out of pearls.")
        .defaultValue(true)
        .build()
    );

    private int timer;

    public AutoPearlChain() {
        super(Categories.Donut, "auto-pearl-chain", "Throws a new pearl where you look each time the last one lands.");
    }

    @Override
    public void onActivate() {
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (timer > 0) {
            timer--;
            return;
        }

        if (pearlInFlight()) return;

        FindItemResult pearl = InvUtils.findInHotbar(Items.ENDER_PEARL);
        if (!pearl.found()) {
            if (stopWhenOut.get()) {
                info("Out of ender pearls.");
                toggle();
            }
            return;
        }

        if (pearl.isOffhand()) {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
        } else {
            InvUtils.swap(pearl.slot(), true);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
        }

        timer = delay.get();
    }

    private boolean pearlInFlight() {
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EnderPearlEntity pearl && pearl.getOwner() == mc.player) return true;
        }
        return false;
    }
}
