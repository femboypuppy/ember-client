package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.MeteorClient;
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
        .description("Seconds between listings. DonutSMP stops opening the auction house for a while if it's used too fast.")
        .defaultValue(5)
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

    private final Setting<Integer> priceCheck = sgUndercut.add(new IntSetting.Builder()
        .name("price-check-every")
        .description("Seconds between auction house price checks; listings in between use the last price seen. 0 checks before every listing, which DonutSMP may rate-limit.")
        .defaultValue(30)
        .range(0, 3600)
        .sliderRange(0, 300)
        .visible(undercut::get)
        .build()
    );

    private final Setting<Integer> pages = sgUndercut.add(new IntSetting.Builder()
        .name("pages")
        .description("Auction house pages to read each time. One is safest; each extra page is another click on the menu.")
        .defaultValue(1)
        .range(1, 5)
        .sliderRange(1, 5)
        .visible(undercut::get)
        .build()
    );

    private enum Step { NEXT, LOOKUP, TURNING, CLOSING, SELL, MOVE, SEND, CONFIRM }

    /** Server inventory updates still to log, after moving items. */
    private volatile int inventoryLinesToLog;

    private String pageBefore = "";

    /** The first few listings of a page, to tell when the next page has replaced it. */
    private static String signature(GenericContainerScreenHandler handler) {
        StringBuilder sb = new StringBuilder();
        Inventory inventory = handler.getInventory();
        for (int i = 0; i < Math.min(9, inventory.size()); i++) {
            ItemStack stack = inventory.getStack(i);
            sb.append(stack.getItem()).append(stack.getCount()).append(priceOf(stack)).append('|');
        }
        return sb.toString();
    }

    /** "$ 467", "$1.2K", "$ 1,500,000": the price line of an auction house listing. */
    private static final Pattern PRICE_LINE = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?(?:\\s?[kmbtKMBT](?![A-Za-z]))?)");

    /** A listing it made: amount, whole-listing price, when. */
    private record Own(int count, long price, long at) {}

    /** What it listed lately, so its own auctions aren't taken for the cheapest and undercut. */
    private final java.util.ArrayDeque<Own> own = new java.util.ArrayDeque<>();
    private static final int NEXT_PAGE_SLOT = 53;

    private Step step = Step.NEXT;
    private int timer, waited, page, stable, lastMatches, listed;
    private long nextAllowed;
    private double cheapestEach;
    private int matches;
    private String waitingFor = "";
    /** Server messages to log after a command: chat, above the hotbar and titles. */
    private volatile int serverLinesToLog;
    private volatile boolean listedMessage;
    /** Last price check: cheapest per item and how many listings matched, and when. */
    private double cachedEach = Double.MAX_VALUE;
    private int cachedMatches = -1;
    private long cachedAt;

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
        cachedMatches = -1;
        cachedAt = 0;
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
                if (available() <= 0) {
                    waitFor("out of " + item.get().getName().getString().toLowerCase(Locale.ROOT));
                    return;
                }
                waitingFor = "";
                InvUtils.swap(sellSlot.get() - 1, false);
                boolean fresh = cachedMatches >= 0 && System.currentTimeMillis() - cachedAt < priceCheck.get() * 1000L;
                if (!undercut.get() || fresh) {
                    cheapestEach = cachedEach;
                    matches = Math.max(0, cachedMatches);
                    step = Step.SELL;
                    return;
                }
                cheapestEach = Double.MAX_VALUE;
                matches = 0;
                page = 1;
                waited = stable = 0;
                lastMatches = -1;
                serverLinesToLog = 3;
                mc.player.networkHandler.sendChatCommand("ah " + searchName(item.get()));
                step = Step.LOOKUP;
            }
            case LOOKUP -> {
                // The menu can open on the usual page and switch to the search results a moment later: read only once
                // the listings have stayed the same for half a second.
                waited++;
                GenericContainerScreenHandler handler = container();
                if (handler == null) {
                    if (waited > 100) {
                        // Usually DonutSMP's limit on opening the auction house: back off instead of hammering it.
                        if (cachedMatches >= 0 && System.currentTimeMillis() - cachedAt < 300_000) {
                            cheapestEach = cachedEach;
                            matches = cachedMatches;
                            step = Step.SELL;
                        } else {
                            waitingFor = "auction house closed, retry in " + retryWait.get() + "s";
                            info("The auction house didn't open (used too fast?): trying again in %d seconds.", retryWait.get());
                            nextAllowed = System.currentTimeMillis() + retryWait.get() * 1000L;
                            step = Step.NEXT;
                        }
                    }
                    return;
                }
                int seen = countPriced(handler);
                if (seen == lastMatches) stable++;
                else stable = 0;
                lastMatches = seen;
                if ((seen > 0 && waited >= 20 && stable >= 10) || waited > 80) {
                    read(handler);
                    ItemStack next = handler.getInventory().size() > NEXT_PAGE_SLOT ? handler.getInventory().getStack(NEXT_PAGE_SLOT) : ItemStack.EMPTY;
                    if (page < pages.get() && next.isOf(Items.ARROW)) {
                        pageBefore = signature(handler);
                        mc.interactionManager.clickSlot(handler.syncId, NEXT_PAGE_SLOT, 0, SlotActionType.PICKUP, mc.player);
                        page++;
                        waited = 0;
                        step = Step.TURNING;
                    } else {
                        cachedEach = cheapestEach;
                        cachedMatches = matches;
                        cachedAt = System.currentTimeMillis();
                        // Like a person: a moment on the menu before closing it, and before the next command. Closing
                        // the auction house mid-load, then commanding right away, seems to lock it for a minute.
                        step = Step.CLOSING;
                        timer = 10;
                    }
                }
            }
            case TURNING -> {
                // Wait for the next page to actually arrive before reading it.
                GenericContainerScreenHandler handler = container();
                if (handler == null) {
                    step = Step.CLOSING;
                    return;
                }
                if (!signature(handler).equals(pageBefore) || ++waited >= 60) {
                    waited = stable = 0;
                    lastMatches = -1;
                    step = Step.LOOKUP;
                }
            }
            case CLOSING -> {
                if (mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
                step = Step.SELL;
                timer = 30;
            }
            case SELL -> {
                if (mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
                // Typing in chat or dead: wait rather than open the inventory over it.
                else if (mc.currentScreen != null) return;
                clicks.clear();
                planMove();
                if (clicks.isEmpty()) {
                    // More than one listing's worth that couldn't be split (no free slot): never list it all.
                    step = held() > 0 && held() <= amount.get() ? Step.SEND : Step.NEXT;
                    return;
                }
                // The server ignores clicks sent with the inventory closed, all at once: the item only looks moved.
                // So, like a person: open the inventory, one click at a time, close it.
                inventoryLinesToLog = 8;
                mc.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(mc.player));
                step = Step.MOVE;
                timer = 6;
            }
            case MOVE -> {
                if (!(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)) {
                    // Closed by the player or the server: start the move over.
                    clicks.clear();
                    step = Step.SELL;
                    timer = 10;
                    return;
                }
                if (clicks.isEmpty()) {
                    planMove();
                    if (clicks.isEmpty()) {
                        mc.player.closeHandledScreen();
                        InvUtils.swap(sellSlot.get() - 1, false);
                        step = held() > 0 && held() <= amount.get() ? Step.SEND : Step.NEXT;
                        timer = 10;
                    }
                    return;
                }
                int[] c = clicks.removeFirst();
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, c[0], c[1], SlotActionType.PICKUP, mc.player);
                timer = 3;
            }
            case SEND -> {
                int sell = sellSlot.get() - 1;
                ItemStack held = mc.player.getInventory().getStack(sell);
                if (!held.isOf(item.get())) {
                    MeteorClient.LOG.info("[Ember AHSell] Sell slot no longer holds the item ({}): getting it again", held);
                    step = Step.SELL;
                    return;
                }
                // Tell the server again which slot is held, so /ah sell takes this one.
                mc.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(sell));
                int count = held.getCount();
                long at = listingPrice(count);
                listedMessage = false;
                serverLinesToLog = 4;
                mc.player.networkHandler.sendChatCommand("ah sell " + at);
                own.addLast(new Own(count, at, System.currentTimeMillis()));
                while (own.size() > 50) own.removeFirst();
                MeteorClient.LOG.info("[Ember AHSell] /ah sell {} for {} x{}", at, item.get(), count);
                waited = 0;
                step = Step.CONFIRM;
            }
            case CONFIRM -> {
                // Listed when the server says "You listed ..." or the item left the sell slot; neither after 3
                // seconds means it said no.
                ItemStack inSlot = mc.player.getInventory().getStack(sellSlot.get() - 1);
                if (listedMessage || !inSlot.isOf(item.get())) {
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

    /**
     * Network thread. What the server says after a command, in chat, above the hotbar or as a title, logged so its
     * wording (a cooldown, full auction slots) can be learned; "You listed" confirms a listing.
     */
    @EventHandler
    private void onPacket(meteordevelopment.meteorclient.events.packets.PacketEvent.Receive event) {
        Text text = null;
        String where = "chat";
        if (event.packet instanceof net.minecraft.network.packet.s2c.play.GameMessageS2CPacket p) {
            text = p.content();
            if (p.overlay()) where = "above hotbar";
        } else if (event.packet instanceof net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket p) {
            text = p.text();
            where = "above hotbar";
        } else if (event.packet instanceof net.minecraft.network.packet.s2c.play.TitleS2CPacket p) {
            text = p.text();
            where = "title";
        } else if (event.packet instanceof net.minecraft.network.packet.s2c.play.SubtitleS2CPacket p) {
            text = p.text();
            where = "subtitle";
        }
        if (inventoryLinesToLog > 0) {
            int sellId = 36 + sellSlot.get() - 1;
            if (event.packet instanceof net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket p) {
                inventoryLinesToLog--;
                MeteorClient.LOG.info("[Ember AHSell] Server sets window {} slot {} to {}{}", p.getSyncId(), p.getSlot(), p.getStack(),
                    p.getSlot() == sellId ? " (the sell slot)" : "");
            } else if (event.packet instanceof net.minecraft.network.packet.s2c.play.InventoryS2CPacket p) {
                inventoryLinesToLog--;
                MeteorClient.LOG.info("[Ember AHSell] Server resends window {}: sell slot holds {}", p.syncId(),
                    p.syncId() == 0 && p.contents().size() > sellId ? p.contents().get(sellId) : "?");
            }
        }
        if (text == null) return;
        String s = text.getString();
        if (s.startsWith("You listed")) listedMessage = true;
        if (serverLinesToLog > 0 && !s.isBlank()) {
            serverLinesToLog--;
            MeteorClient.LOG.info("[Ember AHSell] Server ({}): {}", where, s);
        }
    }

    /** Clicks still to make, as {slot id, mouse button}: one move at a time, each leaving the cursor empty. */
    private final java.util.ArrayDeque<int[]> clicks = new java.util.ArrayDeque<>();

    /** How many of the item the sell slot holds. */
    private int held() {
        ItemStack stack = mc.player.getInventory().getStack(sellSlot.get() - 1);
        return stack.isOf(item.get()) ? stack.getCount() : 0;
    }

    /** How many of the item are in the inventory, sell slot included. */
    private int available() {
        PlayerInventory inv = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) if (inv.getStack(i).isOf(item.get())) n += inv.getStack(i).getCount();
        return n;
    }

    /**
     * The next move towards one listing's worth of the item in the sell slot, as clicks: clear the slot of anything
     * else, put back any extra, or bring more from elsewhere in the inventory. Nothing queued means the slot is ready.
     */
    private void planMove() {
        PlayerInventory inv = mc.player.getInventory();
        int sell = sellSlot.get() - 1, want = Math.min(amount.get(), item.get().getMaxCount());
        int sellId = SlotUtils.indexToId(sell);
        ItemStack current = inv.getStack(sell);

        if (!current.isEmpty() && !current.isOf(item.get())) {
            int free = emptyMainSlot(inv);
            if (free < 0) {
                waitFor("sell slot taken, inventory full");
                return;
            }
            clicks.add(new int[] {sellId, 0});
            clicks.add(new int[] {SlotUtils.indexToId(free), 0});
            return;
        }
        if (current.isOf(item.get()) && current.getCount() > want) {
            int free = emptyMainSlot(inv);
            if (free < 0) return;
            clicks.add(new int[] {sellId, 0});
            for (int i = 0; i < want; i++) clicks.add(new int[] {sellId, 1});
            clicks.add(new int[] {SlotUtils.indexToId(free), 0});
            return;
        }
        int have = current.isOf(item.get()) ? current.getCount() : 0;
        if (have >= want) return;
        for (int i = 0; i < 36; i++) {
            if (i == sell || !inv.getStack(i).isOf(item.get())) continue;
            int count = inv.getStack(i).getCount(), take = Math.min(want - have, count);
            clicks.add(new int[] {SlotUtils.indexToId(i), 0});
            // The whole stack: one left click. Part of it: one right click per item, then the rest goes back.
            if (take == count) clicks.add(new int[] {sellId, 0});
            else {
                for (int k = 0; k < take; k++) clicks.add(new int[] {sellId, 1});
                clicks.add(new int[] {SlotUtils.indexToId(i), 0});
            }
            return;
        }
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
        long now = System.currentTimeMillis();
        own.removeIf(o -> now - o.at > 48 * 3_600_000L);
        java.util.List<Own> mine = new java.util.ArrayList<>(own);
        for (int i = 0; i < listings; i++) {
            ItemStack stack = inventory.getStack(i);
            long total = priceOf(stack);
            if (total <= 0) continue;
            priced++;
            if (!sameItem(stack) || isOwn(stack, mine)) continue;
            matches++;
            cheapestEach = Math.min(cheapestEach, (double) total / stack.getCount());
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(6, listings); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) names.append(stack.getName().getString()).append(" ").append(priceOf(stack)).append("; ");
        }
        MeteorClient.LOG.info("[Ember AHSell] Page {} of \"{}\": {} priced listings, {} of {} so far, cheapest each {}; first: {}", page,
            mc.currentScreen == null ? "?" : mc.currentScreen.getTitle().getString(), priced, matches, item.get(), matches == 0 ? "-" : cheapestEach, names);
    }

    /**
     * A plain one of the chosen item. The auction house gives every listing a custom name, so a renamed copy is told
     * apart by the shown name, not by having one; enchanted copies are priced differently.
     */
    private boolean sameItem(ItemStack listing) {
        if (!listing.isOf(item.get())) return false;
        if (!listing.getName().getString().trim().equalsIgnoreCase(item.get().getName().getString())) return false;
        var enchants = listing.get(DataComponentTypes.ENCHANTMENTS);
        return enchants == null || enchants.isEmpty();
    }

    /** The listing's price, from the "$ 467" line of its lore. */
    private static long priceOf(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        for (Text line : lore.lines()) {
            String s = line.getString();
            if (s.contains("each") || s.contains("Worth")) continue;
            Matcher m = PRICE_LINE.matcher(s);
            if (m.find()) return parsePrice(m.group(1));
        }
        return -1;
    }

    /**
     * One of its own auctions: you're named as the seller, or it has the amount and (shown) price of a listing it made
     * in the last two days. Each of `mine` is matched once; matched ones are taken out of the list.
     */
    private boolean isOwn(ItemStack listing, java.util.List<Own> mine) {
        String me = mc.player.getGameProfile().name();
        LoreComponent lore = listing.get(DataComponentTypes.LORE);
        Pattern name = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(me) + "(?![A-Za-z0-9_])");
        if (lore != null) for (Text line : lore.lines()) if (name.matcher(line.getString()).find()) return true;
        FlipBot.Shown shown = shownPrice(listing);
        if (shown == null) return false;
        for (Own o : mine) {
            if (o.count == listing.getCount() && Math.abs(o.price - shown.value()) <= shown.slack() + 1) {
                mine.remove(o);
                return true;
            }
        }
        return false;
    }

    private static FlipBot.Shown shownPrice(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return null;
        for (Text line : lore.lines()) {
            String s = line.getString();
            if (s.contains("each") || s.contains("Worth")) continue;
            Matcher m = PRICE_LINE.matcher(s);
            if (m.find()) return FlipBot.shown(m.group(1));
        }
        return null;
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
