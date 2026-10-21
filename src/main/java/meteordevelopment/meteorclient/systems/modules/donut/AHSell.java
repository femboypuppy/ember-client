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

    private final Setting<Boolean> matchListingSize = sgUndercut.add(new BoolSetting.Builder()
        .name("match-listing-size")
        .description("Compare auctions with the same item count as your listing. Prevents bulk discounts from pricing single items. Disable to compare all per-item prices.")
        .defaultValue(true)
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

    private enum Step { NEXT, LOOKUP, TURNING, CLOSING, SELL, MOVE, SELECT, SEND, CONFIRM }

    /** Server inventory updates still to log, after moving items. */
    private volatile int inventoryLinesToLog;

    private String pageBefore = "";
    private String stablePage = "";

    /** Every listing matters: counts and prices can change while the number of priced slots stays the same. */
    private static String signature(GenericContainerScreenHandler handler) {
        StringBuilder sb = new StringBuilder();
        Inventory inventory = handler.getInventory();
        for (int i = 0; i < Math.min((handler.getRows() - 1) * 9, inventory.size()); i++) {
            ItemStack stack = inventory.getStack(i);
            sb.append(i).append(':').append(stack.getItem()).append(':').append(stack.getCount()).append(':').append(priceOf(stack)).append(':').append(stack.getName().getString()).append('|');
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
    private int timer, waited, page, stable, listed;
    private long nextAllowed;
    private long sellNotBefore;
    private Item cycleItem, cachedItem;
    private int cycleAmount, cycleConfiguredAmount, cachedAmount;
    private boolean cycleMatchSize, cachedMatchSize;
    private Own pendingOwn;
    private volatile String pendingCommand;
    private volatile boolean commandSent, inventoryCloseSent;
    private volatile long commandSentAt;
    private double cheapestEach;
    private int matches;
    private String waitingFor = "";
    /** Server messages to log after a command: chat, above the hotbar and titles. */
    private volatile int serverLinesToLog;
    private volatile boolean listedMessage;
    /** /ah sell commands in a row the server didn't confirm; the last thing it said while one was pending. */
    private int unanswered;
    private volatile String lastServerLine;
    /** The server said "You have too many listed items" to the last /ah sell. */
    private volatile boolean slotsFull;
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
        pendingOwn = null;
        pendingCommand = null;
        commandSent = inventoryCloseSent = listedMessage = slotsFull = false;
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
        if (mc.player != null && step != Step.NEXT && (mc.currentScreen instanceof GenericContainerScreen || mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)) mc.player.closeHandledScreen();
        pendingCommand = null;
        if (listed > 0) info("Listed %d this time.", listed);
    }

    @Override
    public String getInfoString() {
        return waitingFor.isEmpty() ? listed + " listed" : waitingFor;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (step != Step.NEXT && (item.get() != cycleItem || matchListingSize.get() != cycleMatchSize || amount.get() != cycleConfiguredAmount)) {
            if (mc.currentScreen instanceof GenericContainerScreen || mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen) mc.player.closeHandledScreen();
            clicks.clear(); pendingCommand = null; pendingOwn = null; cachedMatches = -1;
            step = Step.NEXT; timer = 10;
            return;
        }
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
                cycleItem = item.get();
                cycleConfiguredAmount = amount.get();
                cycleAmount = Math.min(Math.min(amount.get(), item.get().getMaxCount()), available());
                cycleMatchSize = matchListingSize.get();
                InvUtils.swap(sellSlot.get() - 1, false);
                boolean sameMarket = cachedItem == cycleItem && cachedAmount == cycleAmount && cachedMatchSize == cycleMatchSize;
                boolean fresh = sameMarket && cachedMatches >= 0 && System.currentTimeMillis() - cachedAt < priceCheck.get() * 1000L;
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
                stablePage = "";
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
                        waitingFor = "auction house closed, retry in " + retryWait.get() + "s";
                        info("The auction house didn't open (used too fast?): trying again in %d seconds.", retryWait.get());
                        nextAllowed = System.currentTimeMillis() + retryWait.get() * 1000L;
                        step = Step.NEXT;
                    }
                    return;
                }
                int seen = countPriced(handler);
                String snapshot = signature(handler);
                if (snapshot.equals(stablePage)) stable++;
                else { stablePage = snapshot; stable = 0; }
                if (waited >= 120 && stable < 10) {
                    mc.player.closeHandledScreen();
                    waitFor("auction prices still changing");step = Step.NEXT;return;
                }
                if (stable >= 10 && (seen > 0 && waited >= 20 || waited > 80)) {
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
                        cachedItem = cycleItem; cachedAmount = cycleAmount; cachedMatchSize = cycleMatchSize;
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
                    stablePage = "";
                    step = Step.LOOKUP;
                }
            }
            case CLOSING -> {
                if (mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
                step = Step.SELL;
                // Prepare the item now; SELECT enforces the command gap after the final inventory close.
                timer = 10;
            }
            case SELL -> {
                if (mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
                // Typing in chat or dead: wait rather than open the inventory over it.
                else if (mc.currentScreen != null) return;
                clicks.clear();
                planMove();
                if (clicks.isEmpty()) {
                    // More than one listing's worth that couldn't be split (no free slot): never list it all. Say so
                    // and wait, instead of silently starting over every tick.
                    if (held() <= 0 || held() > cycleAmount) {
                        waitFor(held() > cycleAmount ? "sell slot holds too many, no free slot to split it" : "couldn't move the item into the sell slot");
                        step = Step.NEXT;
                        return;
                    }
                    // Already in the slot, but the server may not agree (then /ah sell gets no answer at all): pick
                    // it up and put it back, which makes the server send what's really there.
                    int sellId = SlotUtils.indexToId(sellSlot.get() - 1);
                    clicks.add(new int[] {sellId, 0});
                    clicks.add(new int[] {sellId, 0});
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
                        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                            waitFor("item still on the inventory cursor");step = Step.NEXT;return;
                        }
                        inventoryCloseSent = false;
                        mc.player.closeHandledScreen();
                        InvUtils.swap(sellSlot.get() - 1, false);
                        if (held() > 0 && held() <= cycleAmount) step = Step.SELECT;
                        else {
                            waitFor(held() > cycleAmount ? "sell slot holds too many, no free slot to split it" : "couldn't move the item into the sell slot");
                            step = Step.NEXT;
                        }
                        // The final inventory close is another GUI action. The auction-house-only
                        // delay previously expired before this close and sent /ah sell just 0.6s later.
                        sellNotBefore = System.currentTimeMillis() + 3500;
                        timer = 2;
                    }
                    return;
                }
                int[] c = clicks.removeFirst();
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, c[0], c[1], SlotActionType.PICKUP, mc.player);
                timer = 3;
            }
            case SELECT -> {
                if (mc.currentScreen != null || System.currentTimeMillis() < sellNotBefore) return;
                if (!inventoryCloseSent) {
                    saleFeedback("Inventory close was blocked. Check XCarry or packet buffering before retrying.");
                    waitFor("inventory close blocked");step = Step.NEXT;return;
                }
                InvUtils.swap(sellSlot.get() - 1, false);
                mc.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(sellSlot.get() - 1));
                step = Step.SEND;timer = 2;
            }
            case SEND -> {
                if (mc.currentScreen != null) { step = Step.SELECT; sellNotBefore = System.currentTimeMillis() + 3500; return; }
                int sell = sellSlot.get() - 1;
                ItemStack held = mc.player.getInventory().getStack(sell);
                if (!held.isOf(item.get()) || held.getCount() != cycleAmount || !mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                    // Start the cycle over (it recounts what's available), rather than SELL, which would keep comparing
                    // against a count that can't come back if items were picked up or lost meanwhile.
                    MeteorClient.LOG.info("[Ember AHSell] Sell slot holds {} instead of {}: starting the listing over", held, cycleAmount);
                    step = Step.NEXT;
                    timer = 10;
                    return;
                }
                if (mc.player.getInventory().getSelectedSlot() != sell) { step = Step.SELECT; return; }
                int count = held.getCount();
                long at = listingPrice(count);
                if (at <= 0) { waitFor("invalid listing price; check price settings");step = Step.NEXT;return; }
                listedMessage = false;
                slotsFull = false;
                serverLinesToLog = 4;
                pendingOwn = new Own(count, at, System.currentTimeMillis());
                lastServerLine = null;
                pendingCommand = "ah sell " + at;
                commandSent = false;
                commandSentAt = 0;
                mc.player.networkHandler.sendChatCommand(pendingCommand);
                mc.inGameHud.getChatHud().addToMessageHistory("/" + pendingCommand);
                MeteorClient.LOG.info("[Ember AHSell] /ah sell {} for {} x{}", at, item.get(), count);
                waited = 0;
                step = Step.CONFIRM;
            }
            case CONFIRM -> {
                // An emptied slot is not proof of a listing: refusals and other inventory actions
                // can also remove it. Only book a listing after the server's confirmation.
                ItemStack inSlot = mc.player.getInventory().getStack(sellSlot.get() - 1);
                waited++;
                if (slotsFull) {
                    waitingFor = "auction slots full, retry in " + retryWait.get() + "s";
                    saleFeedback("Your auction slots are full: retry in " + retryWait.get() + " seconds.");
                    step = Step.NEXT;
                    pendingCommand = null; pendingOwn = null;
                    nextAllowed = System.currentTimeMillis() + retryWait.get() * 1000L;
                } else if (listedMessage) {
                    listed++;
                    unanswered = 0;
                    if (pendingOwn != null) own.addLast(pendingOwn);
                    while (own.size() > 50) own.removeFirst();
                    saleFeedback("Server confirmed the auction listing.");
                    pendingCommand = null; pendingOwn = null;
                    step = Step.NEXT;
                    nextAllowed = System.currentTimeMillis() + delay.get() * 1000L;
                } else if (commandSent ? System.currentTimeMillis() - commandSentAt >= 5000 : waited >= 100) {
                    int retry = Math.min(15, retryWait.get());
                    waitingFor = "no answer, retry in " + retry + "s";
                    if (!commandSent) {
                        saleFeedback("The client did not dispatch /" + pendingCommand + ". AH Sell stopped; check packet cancellation or buffering.");
                        pendingCommand = null;pendingOwn = null;toggle();return;
                    }
                    // If it went up after all (a late answer), it must not be taken for a competitor and undercut.
                    if (pendingOwn != null && !inSlot.isOf(item.get())) {
                        own.addLast(pendingOwn);
                        while (own.size() > 50) own.removeFirst();
                    }
                    String said = lastServerLine;
                    MeteorClient.LOG.info("[Ember AHSell] No answer to /ah sell; the sell slot holds {}; last server line: {}", inSlot, said);
                    // A refusal it doesn't recognise would otherwise repeat every 15 s forever, unseen: after three in a
                    // row, say what the server last said and pause longer.
                    if (++unanswered >= 3) {
                        unanswered = 0;
                        retry = Math.max(retry, 300);
                        saleFeedback("DonutSMP didn't confirm 3 listings in a row" + (said == null ? "" : "; it last said: \"" + said + "\"") + ". Pausing 5 minutes.");
                    } else {
                        saleFeedback("No server confirmation for /" + pendingCommand + "; " + (inSlot.isOf(item.get()) ? "the item is still in the sell slot" : "the item moved, so the sale is unconfirmed") + ". Retry in " + retry + " seconds.");
                    }
                    waitingFor = "no answer, retry in " + retry + "s";
                    pendingCommand = null; pendingOwn = null;
                    step = Step.NEXT;
                    nextAllowed = System.currentTimeMillis() + retry * 1000L;
                }
            }
        }
    }

    /** Local audit feedback stays visible in chat even when module info is routed to HUD notifications. */
    private void saleFeedback(String message) {
        info(message);
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(Text.literal("[AH Sell] " + message));
    }

    @EventHandler
    private void onGameLeft(meteordevelopment.meteorclient.events.game.GameLeftEvent event) {
        step = Step.NEXT;timer = 0;nextAllowed = 0;
        cachedMatches = -1;pendingCommand = null;pendingOwn = null;
        clicks.clear();own.clear();
    }

    @EventHandler
    private void onSent(meteordevelopment.meteorclient.events.packets.PacketEvent.Sent event) {
        if (event.packet instanceof net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket p && p.getSyncId() == 0) inventoryCloseSent = true;
        String command = null;
        if (event.packet instanceof net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket p) command = p.command();
        else if (event.packet instanceof net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket p) command = p.command();
        if (pendingCommand == null || !pendingCommand.equals(command)) return;
        commandSentAt = System.currentTimeMillis();
        commandSent = true;
        String dispatched = command;
        mc.execute(() -> {
            if (isActive() && dispatched.equals(pendingCommand)) saleFeedback("Sent /" + dispatched + " for " + cycleAmount + " " + item.get().getName().getString() + ". Waiting for server confirmation.");
        });
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
        if (pendingCommand != null && confirmsListing(s)) listedMessage = true;
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if (pendingCommand != null && (lower.contains("too many listed") || lower.contains("listing limit"))) slotsFull = true;
        if (pendingCommand != null && !s.isBlank()) lastServerLine = s;
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
        int sell = sellSlot.get() - 1, want = cycleAmount > 0 ? cycleAmount : Math.min(amount.get(), item.get().getMaxCount());
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
        if (!undercut.get()) return fallbackPrice(each, count, 0);
        String name = item.get().getName().getString();
        long minimumEach = minPrice.get().isBlank() ? 0 : parsePrice(minPrice.get());
        if (matches == 0) {
            long fallback = fallbackPrice(each, count, minimumEach);
            info("No %s auctions%s: using price-each with your minimum (%s for %d).", name, cycleMatchSize ? " with " + count + " items" : " matching", fallback, count);
            return fallback;
        }
        long by = parsePrice(undercutBy.get());
        long cheapest = (long) Math.floor(cheapestEach * count);
        long at = undercutPrice(cheapestEach, count, by, minimumEach);
        if (at < 0) return at;
        long floor = minimumEach * count;
        if (Math.max(1, cheapest - by) < floor) {
            info("Cheapest %s is %s for %d: staying at your minimum, %s.", name, cheapest, count, floor);
            return floor;
        }
        info("Cheapest comparable %s: %s for %d (%d listings). Whole-listing price: %s.", name, cheapest, count, matches, at);
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
            double each = comparablePrice(total, stack.getCount(), cycleAmount, cycleMatchSize);
            if (each == Double.MAX_VALUE) continue;
            matches++;
            cheapestEach = Math.min(cheapestEach, each);
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(6, listings); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) names.append(stack.getName().getString()).append(" x").append(stack.getCount()).append(" total ").append(priceOf(stack)).append("; ");
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
        FlipBot.Shown shown = shownPrice(stack);
        return shown == null ? -1 : shown.value();
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
            String token = totalPriceToken(line.getString());
            if (token != null) return FlipBot.shown(token);
        }
        return null;
    }

    static String totalPriceToken(String text) {
        String clean = text.replaceAll("\u00a7.", "").replace('\u00a0', ' ').replace('\u202f', ' ');
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("each") || lower.contains("per item") || lower.contains("/item") || lower.contains("worth") || lower.contains("balance") || lower.contains("tax") || lower.contains("fee")) return null;
        Matcher match = PRICE_LINE.matcher(clean);
        return match.find() ? match.group(1) : null;
    }

    static double comparablePrice(long total, int count, int intendedCount, boolean sameSize) {
        if (total <= 0 || count <= 0 || intendedCount <= 0 || sameSize && count != intendedCount) return Double.MAX_VALUE;
        return (double) total / count;
    }

    static long undercutPrice(double each, int count, long by, long minimumEach) {
        if (!Double.isFinite(each) || each <= 0 || count <= 0 || by < 0 || minimumEach < 0) return -1;
        return Math.max(Math.max(1, (long) Math.floor(each * count) - by), minimumEach * count);
    }

    static long fallbackPrice(long each, int count, long minimumEach) {
        if (each <= 0 || count <= 0 || minimumEach < 0) return -1;
        return Math.max(each, minimumEach) * count;
    }

    static boolean confirmsListing(String text) {
        String clean = text.replaceAll("\u00a7.", "").strip();
        return clean.matches("(?i)^(?:\\[[^\\]]+\\]\\s*|[\u00bb\u203a>\u2713\u2714]\\s*)*you (?:have )?listed\\b.*");
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
