package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class AutoSpawnerSell extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between taking each stack.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Boolean> onlyBones = sgGeneral.add(new BoolSetting.Builder()
        .name("only-bones")
        .description("Only take bones. Off takes every drop.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> sellCommand = sgGeneral.add(new StringSetting.Builder()
        .name("sell-command")
        .description("Command run once the spawner is emptied, without the slash.")
        .defaultValue("sell")
        .build()
    );

    private int timer;
    private boolean sold;

    public AutoSpawnerSell() {
        super(Categories.Donut, "auto-spawner-sell", "Empties an open spawner menu, then runs the sell command.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        sold = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (timer > 0) {
            timer--;
            return;
        }

        if (mc.player == null) return;
        if (!(mc.currentScreen instanceof HandledScreen<?>)) return;

        ScreenHandler menu = mc.player.currentScreenHandler;
        if (menu == mc.player.playerScreenHandler) return;

        int containerSlots = menu.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (onlyBones.get() && stack.getItem() != Items.BONE) continue;

            InvUtils.shiftClick().slotId(i);
            sold = false;
            timer = delay.get();
            return;
        }

        if (!sold && !sellCommand.get().isBlank()) {
            sold = true;
            mc.player.closeHandledScreen();
            mc.player.networkHandler.sendChatCommand(sellCommand.get().trim());
        }
    }
}
