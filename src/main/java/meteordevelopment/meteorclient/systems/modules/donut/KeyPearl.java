package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.util.Hand;
import net.minecraft.item.Items;

public class KeyPearl extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Keybind> pearlKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("pearl-key")
        .description("Key to throw an ender pearl.")
        .defaultValue(Keybind.none())
        .action(this::throwPearl)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Return to the item you were holding after throwing.")
        .defaultValue(true)
        .build()
    );

    public KeyPearl() {
        super(Categories.Donut, "key-pearl", "Throw an ender pearl from your hotbar with a keybind.");
    }

    private void throwPearl() {
        if (mc.player == null || mc.interactionManager == null || mc.currentScreen != null) return;

        FindItemResult pearl = InvUtils.findInHotbar(Items.ENDER_PEARL);
        if (!pearl.found()) {
            info("No ender pearl in your hotbar.");
            return;
        }

        if (pearl.isOffhand()) {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
            return;
        }

        InvUtils.swap(pearl.slot(), swapBack.get());
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (swapBack.get()) InvUtils.swapBack();
    }
}
