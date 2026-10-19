package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;

import java.util.Locale;

public class AHSell extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> price = sgGeneral.add(new StringSetting.Builder()
        .name("list-price")
        .description("Price to list each hotbar item at: any amount, as a number or with k, m or b (250k, 1.5m, 2b).")
        .defaultValue("1k")
        .placeholder("e.g. 1.5m")
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait between listings, so the auction house keeps up.")
        .defaultValue(20)
        .min(1)
        .sliderRange(1, 100)
        .build()
    );

    private int slot;
    private int timer;
    private long listPrice;

    public AHSell() {
        super(Categories.Donut, "ah-sell", "Lists every hotbar item on the auction house, then turns off.");
    }

    @Override
    public void onActivate() {
        slot = 0;
        timer = 0;
        listPrice = parsePrice(price.get());
        if (listPrice <= 0) {
            error("Can't read the price \"%s\": use a number like 5000, 250k, 1.5m or 2b.", price.get());
            toggle();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        if (timer > 0) {
            timer--;
            return;
        }

        while (slot < 9 && mc.player.getInventory().getStack(slot).isEmpty()) slot++;

        if (slot >= 9) {
            info("Listed all hotbar items.");
            toggle();
            return;
        }

        // /ah sell lists the held item, so select each slot before sending. Sent as a plain number.
        InvUtils.swap(slot, false);
        mc.player.networkHandler.sendChatCommand("ah sell " + listPrice);

        slot++;
        timer = delay.get();
    }

    /** "1500", "1,500", "$2.5k", "1.5m", "2b" to a whole amount, or -1 if it isn't a price. */
    static long parsePrice(String text) {
        String s = text.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("$", "").replace("_", "").replace(" ", "");
        if (s.isEmpty()) return -1;
        double multiplier = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k' -> multiplier = 1e3;
            case 'm' -> multiplier = 1e6;
            case 'b' -> multiplier = 1e9;
            case 't' -> multiplier = 1e12;
            default -> { }
        }
        if (multiplier != 1) s = s.substring(0, s.length() - 1);
        try {
            double value = Double.parseDouble(s) * multiplier;
            if (Double.isNaN(value) || value < 1 || value > 9e15) return -1;
            return Math.round(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
