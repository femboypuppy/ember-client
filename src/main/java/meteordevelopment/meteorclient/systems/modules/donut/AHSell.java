package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sells one chosen item on DonutSMP's auction house, a listing at a time, until you turn it off. Before each listing it
 * can look the item up (/ah <name>) and go just under the cheapest one. A listing that doesn't go through (your
 * auction slots are full, say) is retried later, and running out of the item just means waiting for more.
 */
public class AHSell extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgUndercut = settings.createGroup("Undercut");

    private final Setting<Item> item = sgGeneral.add(new ItemSetting.Builder()
        .name("item")
        .description("The item to sell. Pick it from the list.")
        .defaultValue(Items.AIR)
        .build()
    );

    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount-per-listing")
        .description("How many of the item go in each listing.")
        .defaultValue(1)
        .range(1, 64)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<String> price = sgGeneral.add(new StringSetting.Builder()
        .name("price-each")
        .description("Price for one item: a number or with k, m or b (250k, 1.5m, 2b). With undercut on, only used when nothing on the auction house matches.")
        .defaultValue("1k")
        .placeholder("e.g. 1.5m")
        .build()
    );

    private final Setting<Integer> sellSlot = sgGeneral.add(new IntSetting.Builder()
        .name("sell-slot")
        .description("Hotbar slot (1-9) each listing is put in before /ah sell. Keep it free.")
        .defaultValue(9)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Seconds between listings.")
        .defaultValue(3)
        .range(1, 600)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Integer> retryWait = sgGeneral.add(new IntSetting.Builder()
        .name("retry-wait")
        .description("Seconds to wait when a listing doesn't go through (auction slots full) or you're out of the item.")
        .defaultValue(60)
        .range(5, 3600)
        .sliderRange(10, 600)
        .build()
    );

    private final Setting<Boolean> undercut = sgUndercut.add(new BoolSetting.Builder()
        .name("undercut")
        .description("Before each listing, look the item up on the auction house and price it just under the cheapest one, so yours sells first. This price is used instead of price-each.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> undercutBy = sgUndercut.add(new StringSetting.Builder()
        .name("undercut-by")
        .description("How much under the cheapest listing to go, for one listing.")
        .defaultValue("1")
        .placeholder("e.g. 1 or 1k")
        .visible(undercut::get)
        .build()
    );

    private final Setting<String> minPrice = sgUndercut.add(new StringSetting.Builder()
        .name("min-price-each")
        .description("Never sell one item for less than this, whatever the auction house says (a troll listing at $1 can't drag yours down). Empty for no minimum.")
        .defaultValue("")
        .placeholder("no minimum")
        .visible(undercut::get)
        .build()
    );

    private final Setting<Integer> pages = sgUndercut.add(new IntSetting.Builder()
        .name("pages")
        .description("Auction house pages to read each time.")
        .defaultValue(2)
        .range(1, 5)
        .sliderRange(1, 5)
        .visible(undercut::get)
        .build()
    );

    private enum Step { NEXT, LOOKUP, TURNING, SELL, CONFIRM }

    /** "$ 467", "$1.2K", "$ 1,500,000": the price line of an auction house listing. */
    private static final Pattern PRICE_LINE = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?\\s*[kmbtKMBT]?)");
    private static final int NEXT_PAGE_SLOT = 53;

    private Step step = Step.NEXT;
    private int timer, waited, page, stable, lastMatches, listed;
    private long nextAllowed;
    private double cheapestEach;
    private int matches;
    private String waitingFor = "";
    private int chatLinesToLog;

    public AHSell() {
        super(Categories.Donut, "ah-sell", "Sells a chosen item on the auction house one listing at a time, undercutting the cheapest, until you turn it off.");
    }

    @Override
    public void onActivate() {
        step = Step.NEXT;
        timer = 0;
        nextAllowed = 0;
        listed = 0;
        waitingFor = "";
        if (item.get() == Items.AIR) {
            error("Pick the item to sell first (the item setting).");
            toggle();
            return;
        }
        if (parsePrice(price.get()) <= 0 || undercut.get() && (parsePrice(undercutBy.get()) < 0 || !minPrice.get().isBlank() && parsePrice(minPrice.get()) < 0)) {
            error("Can't read a price: use numbers like 500, 250k, 1.5m or 2b.");
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && step != Step.NEXT && mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
        if (listed > 0) info("Listed %d this time.", listed);
    }

    @Override
    public String getInfoString() {
        return waitingFor.isEmpty() ? listed + " listed" : waitingFor;
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
                if (System.currentTimeMillis() < nextAllowed) return;
                if (mc.currentScreen != null) return;
                int have = ready();
                if (have <= 0) return;
                waitingFor = "";
                InvUtils.swap(sellSlot.get() - 1, false);
                if (!undercut.get()) {
                    step = Step.SELL;
                    return;
                }
                cheapestEach = Double.MAX_VALUE;
                matches = 0;
                page = 1;
                waited = stable = 0;
                lastMatches = -1;
                mc.player.networkHandler.sendChatCommand("ah " + searchName(item.get()));
                step = Step.LOOKUP;
            }
            case LOOKUP -> {
                // Results fill in a moment after the menu opens: read once the listings stop changing.
                waited++;
                GenericContainerScreenHandler handler = container();
                if (handler == null) {
                    if (waited > 100) {
                        warning("The auction house didn't open: listing at price-each.");
                        step = Step.SELL;
                    }
                    return;
                }
                int seen = countPriced(handler);
                if (seen == lastMatches) stable++;
                else stable = 0;
                lastMatches = seen;
                if ((seen > 0 && stable >= 4) || waited > 60) {
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
            }
            case TURNING -> {
                if (++waited >= 6) {
                    waited = stable = 0;
                    lastMatches = -1;
                    step = Step.LOOKUP;
                }
            }
            case SELL -> {
                if (mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
                int count = ready();
                if (count <= 0) {
                    step = Step.NEXT;
                    return;
                }
                InvUtils.swap(sellSlot.get() - 1, false);
                long at = listingPrice(count);
                mc.player.networkHandler.sendChatCommand("ah sell " + at);
                MeteorClient.LOG.info("[Ember AHSell] /ah sell {} for {} x{}", at, item.get(), count);
                chatLinesToLog = 4;
                waited = 0;
                step = Step.CONFIRM;
            }
            case CONFIRM -> {
                // Listed if the item left the sell slot; still there after 3 seconds means the server said no.
                ItemStack inSlot = mc.player.getInventory().getStack(sellSlot.get() - 1);
                if (!inSlot.isOf(item.get())) {
                    listed++;
                    step = Step.NEXT;
                    nextAllowed = System.currentTimeMillis() + delay.get() * 1000L;
                } else if (++waited >= 60) {
                    waitingFor = "slots full? retry in " + retryWait.get() + "s";
                    info("The listing didn't go through (auction slots full?): trying again in %d seconds.", retryWait.get());
                    step = Step.NEXT;
                    nextAllowed = System.currentTimeMillis() + retryWait.get() * 1000L;
                }
            }
        }
    }

    /** What the server says after /ah sell, logged so its wording can be learned. */
    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (chatLinesToLog <= 0) return;
        chatLinesToLog--;
        MeteorClient.LOG.info("[Ember AHSell] Server: {}", event.getMessage().getString());
    }

    /**
     * Puts one listing's worth of the item in the sell slot, taking it from anywhere in the inventory.
     *
     * @return how many are there now, 0 when there's nothing to sell yet
     */
    private int ready() {
        PlayerInventory inv = mc.player.getInventory();
        int sell = sellSlot.get() - 1, want = amount.get();
        int syncId = mc.player.playerScreenHandler.syncId;
        ItemStack current = inv.getStack(sell);

        // Something else in the sell slot: move it out of the way.
        if (!current.isEmpty() && !current.isOf(item.get())) {
            int free = emptyMainSlot(inv);
            if (free < 0) {
                waitFor("sell slot taken, inventory full");
                return 0;
            }
            mc.interactionManager.clickSlot(syncId, SlotUtils.indexToId(sell), 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, SlotUtils.indexToId(free), 0, SlotActionType.PICKUP, mc.player);
            current = inv.getStack(sell);
        }
        // Too many: put the extra back.
        if (current.isOf(item.get()) && current.getCount() > want) {
            int free = emptyMainSlot(inv);
            if (free < 0) return current.getCount();
            mc.interactionManager.clickSlot(syncId, SlotUtils.indexToId(sell), 0, SlotActionType.PICKUP, mc.player);
            for (int i = 0; i < want; i++) mc.interactionManager.clickSlot(syncId, SlotUtils.indexToId(sell), 1, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, SlotUtils.indexToId(free), 0, SlotActionType.PICKUP, mc.player);
            return inv.getStack(sell).getCount();
        }
        // Top it up from the rest of the inventory, one item at a time.
        int have = current.isOf(item.get()) ? current.getCount() : 0;
        while (have < want) {
            int from = -1;
            for (int i = 0; i < 36; i++) {
                if (i != sell && inv.getStack(i).isOf(item.get())) {
                    from = i;
                    break;
                }
            }
            if (from < 0) break;
            int take = Math.min(want - have, inv.getStack(from).getCount());
            mc.interactionManager.clickSlot(syncId, SlotUtils.indexToId(from), 0, SlotActionType.PICKUP, mc.player);
            for (int i = 0; i < take; i++) mc.interactionManager.clickSlot(syncId, SlotUtils.indexToId(sell), 1, SlotActionType.PICKUP, mc.player);
            if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                mc.interactionManager.clickSlot(syncId, SlotUtils.indexToId(from), 0, SlotActionType.PICKUP, mc.player);
            }
            have += take;
        }
        if (have <= 0) waitFor("out of " + item.get().getName().getString().toLowerCase(Locale.ROOT));
        return have;
    }

    private void waitFor(String why) {
        if (!why.equals(waitingFor)) info("Waiting: %s. Checking again every %d seconds.", why, retryWait.get());
        waitingFor = why;
        nextAllowed = System.currentTimeMillis() + retryWait.get() * 1000L;
    }

    private static int emptyMainSlot(PlayerInventory inv) {
        for (int i = 9; i < 36; i++) if (inv.getStack(i).isEmpty()) return i;
        return -1;
    }

    private long listingPrice(int count) {
        long each = parsePrice(price.get());
        if (!undercut.get()) return each * count;
        String name = item.get().getName().getString();
        if (matches == 0) {
            info("No %s on the auction house: listing at price-each (%s each).", name, each);
            return each * count;
        }
        long by = Math.max(0, parsePrice(undercutBy.get()));
        long cheapest = (long) Math.floor(cheapestEach * count);
        long at = Math.max(1, cheapest - by);
        long floor = minPrice.get().isBlank() ? 0 : parsePrice(minPrice.get()) * count;
        if (at < floor) {
            info("Cheapest %s is %s for %d: staying at your minimum, %s.", name, cheapest, count, floor);
            return floor;
        }
        info("Cheapest %s is %s for %d (%d listings seen): listing at %s.", name, cheapest, count, matches, at);
        return at;
    }

    private GenericContainerScreenHandler container() {
        return mc.currentScreen instanceof GenericContainerScreen screen ? screen.getScreenHandler() : null;
    }

    private int countPriced(GenericContainerScreenHandler handler) {
        Inventory inventory = handler.getInventory();
        int n = 0, listings = Math.min(inventory.size(), (handler.getRows() - 1) * 9);
        for (int i = 0; i < listings; i++) if (priceOf(inventory.getStack(i)) > 0) n++;
        return n;
    }

    /** Cheapest price per item among this page's listings of the same item. The bottom row is the menu's buttons. */
    private void read(GenericContainerScreenHandler handler) {
        Inventory inventory = handler.getInventory();
        int listings = Math.min(inventory.size(), (handler.getRows() - 1) * 9), priced = 0;
        for (int i = 0; i < listings; i++) {
            ItemStack stack = inventory.getStack(i);
            long total = priceOf(stack);
            if (total <= 0) continue;
            priced++;
            if (!sameItem(stack)) continue;
            matches++;
            cheapestEach = Math.min(cheapestEach, (double) total / stack.getCount());
        }
        MeteorClient.LOG.info("[Ember AHSell] Page {}: {} priced listings, {} of {} so far, cheapest each {}", page, priced, matches, item.get(),
            matches == 0 ? "-" : cheapestEach);
    }

    /** A plain one of the chosen item: renamed or enchanted copies are priced differently. */
    private boolean sameItem(ItemStack listing) {
        if (!listing.isOf(item.get())) return false;
        if (listing.get(DataComponentTypes.CUSTOM_NAME) != null) return false;
        var enchants = listing.get(DataComponentTypes.ENCHANTMENTS);
        return enchants == null || enchants.isEmpty();
    }

    /** The listing's price, from the "$ 467" line of its lore. */
    private static long priceOf(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        for (Text line : lore.lines()) {
            Matcher m = PRICE_LINE.matcher(line.getString());
            if (m.find()) return parsePrice(m.group(1));
        }
        return -1;
    }

    /** What /ah searches by: the item's plain name, e.g. "gold nugget". */
    private static String searchName(Item item) {
        return item.getName().getString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim();
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
            if (Double.isNaN(value) || value < 0 || value > 9e15) return -1;
            return Math.round(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
