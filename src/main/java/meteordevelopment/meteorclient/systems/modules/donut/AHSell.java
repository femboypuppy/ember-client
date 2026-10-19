package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists every hotbar item on DonutSMP's auction house. Optionally looks each item up first (/ah <name>) and lists it just
 * under the cheapest matching listing, compared per item so a stack of 64 and a single one are judged fairly.
 */
public class AHSell extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgUndercut = settings.createGroup("Undercut");

    private final Setting<String> price = sgGeneral.add(new StringSetting.Builder()
        .name("list-price")
        .description("Price to list each hotbar item at: any amount, as a number or with k, m or b (250k, 1.5m, 2b). With undercut on, used when nothing on the auction house matches.")
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

    private final Setting<Boolean> undercut = sgUndercut.add(new BoolSetting.Builder()
        .name("undercut")
        .description("Look the item up on the auction house first and list it just under the cheapest one, so yours sells first.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> undercutBy = sgUndercut.add(new StringSetting.Builder()
        .name("undercut-by")
        .description("How much under the cheapest listing to go, for the whole stack you're selling.")
        .defaultValue("1")
        .placeholder("e.g. 1 or 1k")
        .visible(undercut::get)
        .build()
    );

    private final Setting<String> minPrice = sgUndercut.add(new StringSetting.Builder()
        .name("min-price")
        .description("Never list below this for one item, whatever the auction house says (stops a troll listing at $1 from dragging yours down). Empty for no minimum.")
        .defaultValue("")
        .placeholder("no minimum")
        .visible(undercut::get)
        .build()
    );

    private final Setting<Integer> pages = sgUndercut.add(new IntSetting.Builder()
        .name("pages")
        .description("Auction house pages to read for each item.")
        .defaultValue(2)
        .range(1, 5)
        .sliderRange(1, 5)
        .visible(undercut::get)
        .build()
    );

    private enum Step { NEXT, OPENING, READING, TURNING, SELL }

    /** "$ 467", "$1.2K", "$ 1,500,000": the price line of an auction house listing. */
    private static final Pattern PRICE_LINE = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?\\s*[kmbtKMBT]?)");
    private static final int NEXT_PAGE_SLOT = 53;

    private int slot, timer, waited, page;
    private long listPrice, by, min;
    private Step step = Step.NEXT;
    private ItemStack selling = ItemStack.EMPTY;
    private double cheapestEach;
    private int matches;

    public AHSell() {
        super(Categories.Donut, "ah-sell", "Lists every hotbar item on the auction house, then turns off. Can undercut the cheapest listing.");
    }

    @Override
    public void onActivate() {
        slot = 0;
        timer = 0;
        step = Step.NEXT;
        listPrice = parsePrice(price.get());
        by = undercut.get() ? parsePrice(undercutBy.get()) : 0;
        min = minPrice.get().isBlank() ? 0 : parsePrice(minPrice.get());
        if (listPrice <= 0) {
            error("Can't read the price \"%s\": use a number like 5000, 250k, 1.5m or 2b.", price.get());
            toggle();
        } else if (undercut.get() && (by < 0 || min < 0)) {
            error("Can't read undercut-by or min-price: use a number like 1, 500 or 1k.");
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        if (step != Step.NEXT && step != Step.SELL && mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (timer > 0) {
            timer--;
            return;
        }

        switch (step) {
            case NEXT -> {
                while (slot < 9 && mc.player.getInventory().getStack(slot).isEmpty()) slot++;
                if (slot >= 9) {
                    info("Listed all hotbar items.");
                    toggle();
                    return;
                }
                InvUtils.swap(slot, false);
                selling = mc.player.getInventory().getStack(slot).copy();
                if (!undercut.get()) {
                    step = Step.SELL;
                    return;
                }
                cheapestEach = Double.MAX_VALUE;
                matches = 0;
                page = 1;
                waited = 0;
                mc.player.networkHandler.sendChatCommand("ah " + searchName(selling));
                step = Step.OPENING;
            }
            case OPENING -> {
                // The listings arrive a moment after the menu opens.
                if (++waited > 60) {
                    warning("The auction house didn't open for %s: listing it at %s.", selling.getName().getString(), listPrice);
                    step = Step.SELL;
                } else if (container() != null && waited >= 8) {
                    step = Step.READING;
                }
            }
            case READING -> {
                GenericContainerScreenHandler handler = container();
                if (handler == null) {
                    step = Step.SELL;
                    return;
                }
                read(handler);
                ItemStack next = handler.getInventory().size() > NEXT_PAGE_SLOT ? handler.getInventory().getStack(NEXT_PAGE_SLOT) : ItemStack.EMPTY;
                if (page < pages.get() && next.isOf(Items.ARROW)) {
                    mc.interactionManager.clickSlot(handler.syncId, NEXT_PAGE_SLOT, 0, SlotActionType.PICKUP, mc.player);
                    page++;
                    waited = 0;
                    step = Step.TURNING;
                } else {
                    mc.player.closeHandledScreen();
                    step = Step.SELL;
                    timer = 4;
                }
            }
            case TURNING -> {
                if (++waited >= 10) step = Step.READING;
            }
            case SELL -> {
                if (mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
                // The menu may have moved the selection: hold the item being sold again.
                InvUtils.swap(slot, false);
                long at = undercut.get() ? undercutPrice() : listPrice;
                mc.player.networkHandler.sendChatCommand("ah sell " + at);
                slot++;
                step = Step.NEXT;
                timer = delay.get();
            }
        }
    }

    private GenericContainerScreenHandler container() {
        return mc.currentScreen instanceof GenericContainerScreen screen ? screen.getScreenHandler() : null;
    }

    /** Cheapest price per item among this page's listings of the same item. The bottom row is the menu's buttons. */
    private void read(GenericContainerScreenHandler handler) {
        Inventory inventory = handler.getInventory();
        int listings = Math.min(inventory.size(), (handler.getRows() - 1) * 9);
        for (int i = 0; i < listings; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty() || !sameItem(stack, selling)) continue;
            long total = priceOf(stack);
            if (total <= 0) continue;
            matches++;
            cheapestEach = Math.min(cheapestEach, (double) total / stack.getCount());
        }
    }

    private long undercutPrice() {
        String name = selling.getName().getString();
        if (matches == 0) {
            info("No %s on the auction house: listing at %s.", name, listPrice);
            return listPrice;
        }
        int count = selling.getCount();
        long cheapest = (long) Math.floor(cheapestEach * count);
        long at = cheapest - by;
        long floor = min * count;
        if (min > 0 && at < floor) {
            info("Cheapest %s is %s for %d: staying at your minimum, %s.", name, cheapest, count, floor);
            return floor;
        }
        at = Math.max(1, at);
        info("Cheapest %s is %s for %d (from %d listings): listing at %s.", name, cheapest, count, matches, at);
        return at;
    }

    /** Same item, and for named or enchanted items the same name and enchantments too. */
    private static boolean sameItem(ItemStack listing, ItemStack mine) {
        if (!listing.isOf(mine.getItem())) return false;
        if (!Objects.equals(listing.get(DataComponentTypes.ENCHANTMENTS), mine.get(DataComponentTypes.ENCHANTMENTS))) return false;
        if (!Objects.equals(listing.get(DataComponentTypes.STORED_ENCHANTMENTS), mine.get(DataComponentTypes.STORED_ENCHANTMENTS))) return false;
        Text a = listing.get(DataComponentTypes.CUSTOM_NAME), b = mine.get(DataComponentTypes.CUSTOM_NAME);
        return a == null && b == null || a != null && b != null && a.getString().equals(b.getString());
    }

    /** The listing's price, from the "$ 467" line of its lore. */
    private static long priceOf(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        for (Text line : lore.lines()) {
            Matcher m = PRICE_LINE.matcher(line.getString());
            if (m.find()) return parsePrice(m.group(1));
        }
        return -1;
    }

    /** What /ah searches by: the item's plain name, e.g. "gold nugget". */
    private static String searchName(ItemStack stack) {
        return stack.getItem().getName().getString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim();
    }

    /** "1500", "1,500", "$2.5k", "1.5m", "2b" to a whole amount, or -1 if it isn't a price. */
    static long parsePrice(String text) {
        String s = text.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("$", "").replace("_", "").replace(" ", "");
        if (s.isEmpty()) return -1;
        double multiplier = 1;
        switch (s.charAt(s.length() - 1)) {
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
