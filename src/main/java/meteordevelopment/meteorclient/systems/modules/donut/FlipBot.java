package meteordevelopment.meteorclient.systems.modules.donut;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.dialog.DialogScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flips items on DonutSMP by itself: buys through /orders (just above the best buy order), sells on the auction house
 * one listing at a time (just under the cheapest), and moves on when an item stops paying.
 *
 * <p>Each item on the watchlist (plus items it sees people ordering) is scanned now and then: cheapest auction per item
 * and best order per item. An item is worth an order when the auction price after tax is at least min-profit times
 * the order price, and has been for a couple of scans in a row. Stop-loss: when an item's auction price falls well
 * under its recent level (panic selling) it stops buying it, cancels its order if the margin is gone, and holds its
 * stock instead of following the price down; it sells again once the price comes back.
 */
public class FlipBot extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgProfit = settings.createGroup("Profit");
    private final SettingGroup sgRisk = settings.createGroup("Stop Loss");
    private final SettingGroup sgSell = settings.createGroup("Selling");
    private final SettingGroup sgPace = settings.createGroup("Pacing");

    // General

    private final Setting<List<Item>> watchlist = sgGeneral.add(new ItemListSetting.Builder()
        .name("watchlist")
        .description("Items the bot considers flipping.")
        .defaultValue(Items.DISPENSER, Items.REPEATER, Items.COMPARATOR, Items.OBSERVER, Items.PISTON, Items.STICKY_PISTON,
            Items.HOPPER, Items.TARGET, Items.SLIME_BLOCK, Items.HONEY_BLOCK, Items.DROPPER, Items.ENDER_PEARL, Items.BLAZE_ROD,
            Items.GOLDEN_CARROT, Items.OBSIDIAN, Items.TNT, Items.SHULKER_SHELL, Items.TOTEM_OF_UNDYING, Items.NETHERITE_SCRAP,
            Items.EXPERIENCE_BOTTLE)
        .build()
    );

    private final Setting<Boolean> discover = sgGeneral.add(new BoolSetting.Builder()
        .name("discover")
        .description("Also consider items other players are ordering in /orders, not only the watchlist.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> budget = sgGeneral.add(new StringSetting.Builder()
        .name("budget")
        .description("Most money the bot may have tied up at once, in orders and unsold stock (250k, 10m, 1b).")
        .defaultValue("10m")
        .build()
    );

    private final Setting<String> maxPerItem = sgGeneral.add(new StringSetting.Builder()
        .name("max-per-item")
        .description("Most money one item's order may cost, however good it looks.")
        .defaultValue("5m")
        .build()
    );

    private final Setting<String> keepBalance = sgGeneral.add(new StringSetting.Builder()
        .name("keep-balance")
        .description("Never place an order that would take your balance below this (when the balance can be read from the sidebar). Empty for none.")
        .defaultValue("")
        .placeholder("none")
        .build()
    );

    private final Setting<Integer> maxOrders = sgGeneral.add(new IntSetting.Builder()
        .name("max-items")
        .description("How many different items it invests in at once.")
        .defaultValue(2)
        .range(1, 18)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> maxOrderAmount = sgGeneral.add(new IntSetting.Builder()
        .name("max-order-amount")
        .description("Most items in one order.")
        .defaultValue(1000)
        .range(1, 100000)
        .sliderRange(64, 5000)
        .build()
    );

    // Profit

    private final Setting<Double> minProfit = sgProfit.add(new DoubleSetting.Builder()
        .name("min-profit")
        .description("Only flip when the auction price after tax is at least this many times the order price (1.5 = +50%).")
        .defaultValue(1.5)
        .range(1.01, 100)
        .sliderRange(1.1, 5)
        .build()
    );

    private final Setting<Double> tax = sgProfit.add(new DoubleSetting.Builder()
        .name("ah-tax")
        .description("Percent the auction house keeps from a sale.")
        .defaultValue(5)
        .range(0, 50)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<String> outbid = sgProfit.add(new StringSetting.Builder()
        .name("outbid-by")
        .description("How much above the best order (per item) to place yours, so it fills first: an amount (100) or a percent (5%).")
        .defaultValue("3%")
        .build()
    );

    private final Setting<Boolean> followOutbids = sgProfit.add(new BoolSetting.Builder()
        .name("follow-outbids")
        .description("When someone outbids your order and the flip still pays, cancel it and order again above them (at most every 30 minutes per item).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> confirmScans = sgProfit.add(new IntSetting.Builder()
        .name("confirm-scans")
        .description("Scans in a row an item must look good before it's bought, so a passing dip isn't mistaken for a flip.")
        .defaultValue(2)
        .range(1, 10)
        .sliderRange(1, 5)
        .build()
    );

    // Stop loss

    private final Setting<Integer> crashDrop = sgRisk.add(new IntSetting.Builder()
        .name("crash-drop")
        .description("Percent under the item's recent auction price that counts as panic selling: it stops buying, holds its stock until the price is back within half of this, and moves to other items.")
        .defaultValue(25)
        .range(5, 90)
        .sliderRange(10, 60)
        .build()
    );

    private final Setting<Integer> cooldown = sgRisk.add(new IntSetting.Builder()
        .name("crash-cooldown")
        .description("Minutes an item is left alone after panic selling before it's bought again.")
        .defaultValue(60)
        .range(5, 1440)
        .sliderRange(15, 240)
        .build()
    );

    private final Setting<Double> minSellMargin = sgRisk.add(new DoubleSetting.Builder()
        .name("min-sell-margin")
        .description("Never list below the price paid times this, after tax (1.1 = always at least +10%). Below it the stock is held until the price recovers.")
        .defaultValue(1.1)
        .range(0.1, 10)
        .sliderRange(0.5, 2)
        .build()
    );

    private final Setting<Integer> maxHold = sgRisk.add(new IntSetting.Builder()
        .name("max-hold-hours")
        .description("After holding stock this long, accept selling it at up to max-loss under what it cost. 0 never sells at a loss.")
        .defaultValue(0)
        .range(0, 720)
        .sliderRange(0, 72)
        .build()
    );

    private final Setting<Integer> maxLoss = sgRisk.add(new IntSetting.Builder()
        .name("max-loss")
        .description("Percent under cost it may sell at, once max-hold-hours has passed.")
        .defaultValue(20)
        .range(0, 90)
        .sliderRange(0, 50)
        .visible(() -> maxHold.get() > 0)
        .build()
    );

    // Selling

    private final Setting<String> undercutBy = sgSell.add(new StringSetting.Builder()
        .name("undercut-by")
        .description("How much under the cheapest auction to list, for one listing.")
        .defaultValue("1")
        .build()
    );

    private final Setting<Integer> perListing = sgSell.add(new IntSetting.Builder()
        .name("amount-per-listing")
        .description("Items in each auction listing.")
        .defaultValue(1)
        .range(1, 64)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Integer> maxListings = sgSell.add(new IntSetting.Builder()
        .name("max-listings")
        .description("Your auction house slot limit: it waits when this many of its listings are up.")
        .defaultValue(10)
        .range(1, 100)
        .sliderRange(1, 45)
        .build()
    );

    private final Setting<Integer> sellSlot = sgSell.add(new IntSetting.Builder()
        .name("sell-slot")
        .description("Hotbar slot (1-9) used for /ah sell. Keep it free.")
        .defaultValue(9)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Integer> priceCheck = sgSell.add(new IntSetting.Builder()
        .name("price-check-every")
        .description("Seconds a looked-up auction price is reused for listings before looking again.")
        .defaultValue(60)
        .range(5, 3600)
        .sliderRange(10, 600)
        .build()
    );

    // Pacing

    private final Setting<Integer> menuGap = sgPace.add(new IntSetting.Builder()
        .name("menu-gap")
        .description("Seconds between commands that open a menu (/ah, /orders). DonutSMP blocks the auction house for a while when it's used too fast.")
        .defaultValue(4)
        .range(1, 60)
        .sliderRange(2, 20)
        .build()
    );

    private final Setting<Integer> rescan = sgPace.add(new IntSetting.Builder()
        .name("rescan-minutes")
        .description("Minutes between price scans of the same item.")
        .defaultValue(10)
        .range(1, 600)
        .sliderRange(5, 120)
        .build()
    );

    private final Setting<Integer> orderCheck = sgPace.add(new IntSetting.Builder()
        .name("order-check-minutes")
        .description("Minutes between visits to Your Orders to collect what was delivered.")
        .defaultValue(3)
        .range(1, 120)
        .sliderRange(1, 30)
        .build()
    );

    public FlipBot() {
        super(Categories.Donut, "flip-bot", "Buys items through /orders and sells them on the auction house by itself, picking what's worth flipping and backing off when prices crash.");    }

    // ------------------------------------------------------------------ saved state

    static final class Position {
        String item;
        long priceEach;
        int amount;
        int delivered, collected, listed, sold;
        long revenue;
        long createdAt, lastSeen, closedAt;
        /** PENDING (just placed), OPEN, CLOSED (no longer buying, stock may remain), DONE (all sold). */
        String state = "PENDING";
        boolean cancelWanted;
        int missedChecks;
    }

    static final class Obs {
        long t;
        /** Cheapest, second cheapest and the average of the five cheapest auctions, per item. */
        double ahEach, ah2nd, ahLow5;
        int ahCount;
        double orderEach;
        boolean hasOrder, full, crash;
    }

    static final class ItemInfo {
        List<Obs> obs = new ArrayList<>();
        long lastScan, lastAhScan, cooldownUntil, lastReorder, holdUntil;
        int goodStreak;
        boolean holdNotified;
        long soldUnits, soldValue;
        long firstSale;
        boolean discovered;
    }

    static final class Listing {
        String item;
        int units;
        long price;
        long at;
    }

    static final class State {
        List<Position> positions = new ArrayList<>();
        Map<String, ItemInfo> items = new HashMap<>();
        List<Listing> listings = new ArrayList<>();
        long profit;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private State state;
    private long lastSave;

    private File folder() {
        return new File(MeteorClient.FOLDER, "flip-bot");
    }

    private void load() {
        File file = new File(folder(), "state.json");
        state = null;
        if (file.exists()) {
            try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                state = GSON.fromJson(r, State.class);
            } catch (Exception e) {
                MeteorClient.LOG.warn("[Ember FlipBot] Couldn't read {}: {}", file, e.toString());
            }
        }
        if (state == null) state = new State();
        if (state.positions == null) state.positions = new ArrayList<>();
        if (state.items == null) state.items = new HashMap<>();
        if (state.listings == null) state.listings = new ArrayList<>();
    }

    private void save() {
        if (state == null) return;
        lastSave = System.currentTimeMillis();
        try {
            folder().mkdirs();
            File tmp = new File(folder(), "state.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(state, w);
            }
            Files.move(tmp.toPath(), new File(folder(), "state.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            MeteorClient.LOG.warn("[Ember FlipBot] Couldn't save state: {}", e.toString());
        }
    }

    /** One line per trade event in flip-bot/trades.csv, for a morning read. */
    private void trade(String what, Item item, long units, long money, String note) {
        try {
            folder().mkdirs();
            File file = new File(folder(), "trades.csv");
            boolean header = !file.exists();
            try (FileWriter w = new FileWriter(file, StandardCharsets.UTF_8, true)) {
                if (header) w.write("time,event,item,units,money,note\n");
                w.write(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "," + what + "," + name(item) + "," + units + "," + money + "," + note.replace(',', ';') + "\n");
            }
        } catch (Exception ignored) {
        }
        MeteorClient.LOG.info("[Ember FlipBot] {} {} x{} ${} {}", what, name(item), units, money, note);
    }

    // ------------------------------------------------------------------ runtime state

    private Job job;
    private int delayTicks;
    private long backoffUntil, nextOrderCheck, nextDiscover, lastMenuAt, listingsFullUntil, orderSlotsFullUntil, noMoneyUntil, nextSummary;
    private String status = "";
    private final ConcurrentLinkedQueue<String> serverLines = new ConcurrentLinkedQueue<>();
    private int serverLinesToLog;
    private long sessionStart;

    @Override
    public void onActivate() {
        // Also runs on every join while on: keep the saved state, just restart what was in progress.
        if (state == null) load();
        abortJob(null);
        backoffUntil = System.currentTimeMillis() + 3000;
        nextOrderCheck = 0;
        if (sessionStart == 0) sessionStart = System.currentTimeMillis();
        if (parsePrice(budget.get()) <= 0 || parsePrice(maxPerItem.get()) <= 0) {
            error("Can't read budget or max-per-item: use numbers like 500k, 10m or 1b.");
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        abortJob(null);
        save();
        if (state != null) info("Stopped. Profit so far: %s. Open: %s.", money(state.profit), openSummary());
        sessionStart = 0;
    }

    @EventHandler
    private void onLeave(GameLeftEvent event) {
        job = null;
        save();
    }

    @Override
    public String getInfoString() {
        return status;
    }

    // ------------------------------------------------------------------ job runner

    private static final class Fail extends RuntimeException {
        Fail(String message) {
            super(message, null, false, false);
        }
    }

    @FunctionalInterface
    private interface StepFn {
        /** Called every tick with the ticks spent in this step; true when done. Throw Fail to give up. */
        boolean tick(int t);
    }

    private record Step(String what, int timeout, StepFn fn) {}

    private final class Job {
        final String name;
        final List<Step> steps = new ArrayList<>();
        int index, ticks;
        Runnable onFail = () -> {};
        Runnable onDone = () -> {};

        Job(String name) {
            this.name = name;
        }

        Job then(String what, int timeout, StepFn fn) {
            steps.add(new Step(what, timeout, fn));
            return this;
        }

        Job pause(int ticks) {
            return then("pause", ticks + 5, t -> t >= ticks);
        }
    }

    private void start(Job j) {
        job = j;
        status = j.name;
        MeteorClient.LOG.info("[Ember FlipBot] Job: {}", j.name);
    }

    private void abortJob(String why) {
        if (job != null && why != null) MeteorClient.LOG.info("[Ember FlipBot] Job \"{}\" stopped: {}", job.name, why);
        job = null;
        if (mc.player != null && (mc.currentScreen instanceof GenericContainerScreen || mc.currentScreen instanceof InventoryScreen)) mc.player.closeHandledScreen();
        else if (mc.currentScreen instanceof DialogScreen<?>) mc.setScreen(null);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.interactionManager == null || state == null) return;
        drainServerLines();
        long now = System.currentTimeMillis();
        if (now - lastSave > 60_000) save();
        if (now >= nextSummary) {
            if (nextSummary != 0) info("Profit so far: %s. %s", money(state.profit), openSummary());
            nextSummary = now + 3_600_000;
        }

        if (job != null) {
            if (delayTicks > 0) {
                delayTicks--;
                return;
            }
            Job j = job;
            Step s = j.steps.get(j.index);
            try {
                if (s.fn.tick(j.ticks++)) {
                    j.index++;
                    j.ticks = 0;
                    if (job == j && j.index >= j.steps.size()) {
                        job = null;
                        j.onDone.run();
                    }
                } else if (j.ticks > s.timeout) throw new Fail(s.what + " took too long");
            } catch (Fail f) {
                abortJob(f.getMessage());
                backoffUntil = now + 20_000;
                status = "retrying soon";
                j.onFail.run();
            }
            return;
        }

        if (now < backoffUntil) return;
        // Left running overnight: a death shouldn't stop it for good.
        if (mc.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen && mc.player.isDead()) {
            mc.player.requestRespawn();
            mc.setScreen(null);
            backoffUntil = now + 3000;
            return;
        }
        // The player is using a menu or typing: don't fight them for the screen.
        if (mc.currentScreen != null) {
            status = "paused, menu open";
            return;
        }
        plan(now);
    }

    // ------------------------------------------------------------------ what to do next

    private void plan(long now) {
        boolean anyOpen = false;
        for (Position p : state.positions) if (p.state.equals("PENDING") || p.state.equals("OPEN") || p.cancelWanted) anyOpen = true;
        boolean collectable = false;
        for (Position p : state.positions) if (!p.state.equals("DONE") && p.delivered > p.collected) collectable = true;

        // 1. Orders: collect deliveries, cancel or confirm.
        if ((anyOpen || collectable) && now >= nextOrderCheck) {
            nextOrderCheck = now + orderCheck.get() * 60_000L;
            startOrdersVisit();
            return;
        }

        // 2. Sell stock.
        Item sell = pickSellItem(now);
        if (sell != null) {
            startSell(sell, now);
            return;
        }

        // 3. Learn which items people order.
        if (discover.get() && now >= nextDiscover) {
            nextDiscover = now + 30 * 60_000L;
            startDiscover();
            return;
        }

        // 4. Once every candidate has been looked at, invest in the best one that's passed its checks; otherwise
        // scan the next item. (Investing goes first: with many items a scan round can outlast rescan-minutes.)
        if (canInvest(now) && everyoneScanned(now)) {
            Pick pick = bestPick(now);
            if (pick != null) {
                startOrder(pick);
                return;
            }
        }
        Item scan = nextScan(now);
        if (scan != null) {
            startScan(scan, false);
            return;
        }
        status = stockCount() > 0 && activeListings() >= maxListings.get() ? "listings full, waiting" : "watching " + candidates().size() + " items";
    }

    private List<Item> candidates() {
        LinkedHashSet<Item> set = new LinkedHashSet<>(watchlist.get());
        if (discover.get()) {
            for (Map.Entry<String, ItemInfo> e : state.items.entrySet()) if (e.getValue().discovered) set.add(item(e.getKey()));
        }
        for (Position p : state.positions) if (!p.state.equals("DONE")) set.add(item(p.item));
        set.remove(Items.AIR);
        return new ArrayList<>(set);
    }

    private Item nextScan(long now) {
        Item oldest = null;
        long oldestAt = Long.MAX_VALUE;
        for (Item item : candidates()) {
            ItemInfo info = info(item);
            if (now < info.cooldownUntil && !hasStake(item)) continue;
            if (now - info.lastScan < rescan.get() * 60_000L) continue;
            if (info.lastScan < oldestAt) {
                oldestAt = info.lastScan;
                oldest = item;
            }
        }
        return oldest;
    }

    /** Every candidate scanned at least once this session, so the best pick is compared against all of them. */
    private boolean everyoneScanned(long now) {
        for (Item item : candidates()) {
            ItemInfo info = info(item);
            if (now < info.cooldownUntil) continue;
            if (info.lastScan < sessionStart) return false;
        }
        return true;
    }

    private boolean canInvest(long now) {
        if (now < orderSlotsFullUntil || now < noMoneyUntil) return false;
        int open = 0;
        for (Position p : state.positions) if (p.state.equals("PENDING") || p.state.equals("OPEN")) open++;
        // Stock held through a crash doesn't block other items, but deliveries need room to land in.
        return open < maxOrders.get() && freeSlots() >= 6 && budgetLeft() > 0;
    }

    private long committed() {
        return committed(null);
    }

    /** Money in orders and unsold stock, for one item or (null) all of them. */
    private long committed(Item item) {
        long sum = 0;
        for (Position p : state.positions) {
            if (p.state.equals("DONE") || item != null && !p.item.equals(id(item))) continue;
            int bought = p.state.equals("CLOSED") ? p.delivered : p.amount;
            sum += p.priceEach * Math.max(0, bought - p.sold);
        }
        return sum;
    }

    private long budgetLeft() {
        long left = parsePrice(budget.get()) - committed();
        Long balance = readBalance();
        long keep = keepBalance.get().isBlank() ? 0 : Math.max(0, parsePrice(keepBalance.get()));
        if (balance != null) left = Math.min(left, balance - keep);
        return left;
    }

    private record Pick(Item item, long priceEach, int amount, double ratio, double score) {}

    /** The best item to order now, if any passes every check. */
    private Pick bestPick(long now) {
        Pick best = null;
        long left = budgetLeft();
        for (Item item : candidates()) {
            if (hasOpenOrder(item)) continue;
            ItemInfo info = info(item);
            if (now < info.cooldownUntil) continue;
            Obs o = latestFull(item);
            if (o == null || now - o.t > Math.max(rescan.get() * 120_000L, 20 * 60_000L) || !o.hasOrder || o.ahCount == 0) continue;
            if (info.goodStreak < confirmScans.get()) continue;
            long buy = bidPrice(o.orderEach);
            double net = listPrice(sellAgainst(o), 1) * (1 - tax.get() / 100);
            double ratio = net / buy;
            if (ratio < minProfit.get()) continue;
            // max-per-item covers what's already tied up in the item (unsold stock), not just the new order.
            long cap = Math.min(parsePrice(maxPerItem.get()) - committed(item), left);
            int amount = (int) Math.min(maxOrderAmount.get(), cap / Math.max(1, buy));
            // What it sold in the past bounds how much it buys: about a day of its own sales, never under a stack.
            if (info.soldUnits > 0 && info.firstSale > 0) {
                double hours = Math.max(1, (now - info.firstSale) / 3_600_000.0);
                amount = (int) Math.min(amount, Math.max(64, info.soldUnits / hours * 24));
            }
            if (amount < 1) continue;
            double score = (net - buy) * amount;
            if (best == null || score > best.score) best = new Pick(item, buy, amount, ratio, score);
        }
        return best;
    }

    /** Stock the bot bought and hasn't listed yet, for an item with stock in the inventory. */
    private Item pickSellItem(long now) {
        if (now < listingsFullUntil) return null;
        // Sales made while offline never send "bought your ...": when its count says full, still try every 10 minutes.
        if (activeListings() >= maxListings.get() && now - lastListAttempt < 10 * 60_000L) return null;
        for (Position p : state.positions) {
            if (p.state.equals("DONE")) continue;
            Item item = item(p.item);
            if (unlisted(item) <= 0 || count(item) <= 0) continue;
            if (now < info(item).holdUntil) continue;
            return item;
        }
        return null;
    }

    private int unlisted(Item item) {
        int n = 0;
        for (Position p : state.positions) if (p.item.equals(id(item)) && !p.state.equals("DONE")) n += p.collected - p.listed;
        return n;
    }

    private int stockCount() {
        int n = 0;
        for (Position p : state.positions) if (!p.state.equals("DONE")) n += p.collected - p.listed;
        return n;
    }

    private int activeListings() {
        long now = System.currentTimeMillis();
        state.listings.removeIf(l -> now - l.at > 48 * 3_600_000L);
        return state.listings.size();
    }

    private boolean hasOpenOrder(Item item) {
        for (Position p : state.positions) if (p.item.equals(id(item)) && (p.state.equals("PENDING") || p.state.equals("OPEN"))) return true;
        return false;
    }

    private boolean hasStake(Item item) {
        for (Position p : state.positions) if (p.item.equals(id(item)) && !p.state.equals("DONE")) return true;
        return false;
    }

    /** Average price paid per item for the stock still held. */
    private double costEach(Item item) {
        long cost = 0, units = 0;
        for (Position p : state.positions) {
            if (!p.item.equals(id(item)) || p.state.equals("DONE")) continue;
            cost += p.priceEach * (long) Math.max(1, p.collected - p.sold);
            units += Math.max(1, p.collected - p.sold);
        }
        return units == 0 ? 0 : (double) cost / units;
    }

    private long oldestStock(Item item) {
        long t = Long.MAX_VALUE;
        for (Position p : state.positions) if (p.item.equals(id(item)) && !p.state.equals("DONE") && p.collected > p.listed) t = Math.min(t, p.createdAt);
        return t;
    }

    // ------------------------------------------------------------------ prices and the stop loss

    private Obs latest(Item item) {
        List<Obs> obs = info(item).obs;
        return obs.isEmpty() ? null : obs.getLast();
    }

    /** The latest scan that looked at orders too (price checks before listing only look at the auction house). */
    private Obs latestFull(Item item) {
        List<Obs> obs = info(item).obs;
        for (int i = obs.size() - 1; i >= 0; i--) if (obs.get(i).full) return obs.get(i);
        return null;
    }

    /**
     * The auction price to undercut: the cheapest, unless it's a lone listing far under the rest (a troll or one
     * panicking seller) while the item as a whole isn't crashing; then the next one.
     */
    private double sellAgainst(Obs o) {
        if (o.ahCount >= 2 && o.ahEach < o.ah2nd * (1 - crashDrop.get() / 100.0) && !o.crash) return o.ah2nd;
        return o.ahEach;
    }

    /**
     * The item's usual auction price: the median of its "low five" averages over the last 3 hours, leaving out scans
     * taken during a crash (so a crash can't drag "usual" down with it). A crash that lasts longer than that has no
     * usual price left to compare with, so it's taken as the new level.
     */
    private double reference(Item item) {
        long now = System.currentTimeMillis();
        List<Double> values = new ArrayList<>();
        for (Obs o : info(item).obs) if (o.ahCount > 0 && !o.crash && now - o.t < 3 * 3_600_000L) values.add(o.ahLow5);
        if (values.isEmpty()) return 0;
        Collections.sort(values);
        return values.get(values.size() / 2);
    }

    /** Just above the best order for an item, beating the shown price's rounding too. */
    private long bidPrice(double topShown) {
        double above = topShown + roundingSlack(topShown);
        String by = outbid.get().trim();
        double add = by.endsWith("%") ? above * parsePercent(by) / 100 : Math.max(0, parsePrice(by));
        return (long) Math.ceil(above + Math.max(1, add));
    }

    /** What a listing of `units` goes for: just under the cheapest, beating the shown price's rounding. */
    private long listPrice(double cheapestEachShown, int units) {
        double below = cheapestEachShown * units - roundingSlack(cheapestEachShown * units);
        return (long) Math.floor(below - Math.max(0, parsePrice(undercutBy.get())));
    }

    /** DonutSMP shows prices as 9.2K or 1.5M, so the real price can be up to half a step away. */
    private static double roundingSlack(double shown) {
        if (shown >= 1e9) return 0.05e9;
        if (shown >= 1e6) return 0.05e6;
        if (shown >= 1e3) return 50;
        return 0;
    }

    /**
     * Records a scan and runs the stop loss: an auction price well under the item's recent level means people are
     * dumping it. Buying stops for a while, an order that no longer pays is cancelled, and stock is held.
     */
    private void recordScan(Item item, Obs o) {
        ItemInfo info = info(item);
        long now = System.currentTimeMillis();
        double ref = reference(item);
        // Panic selling is many cheap listings, not one: judge by the five cheapest together.
        boolean crashed = ref > 0 && o.ahCount > 0 && o.ahLow5 < ref * (1 - crashDrop.get() / 100.0);
        o.crash = crashed;
        info.obs.add(o);
        info.obs.removeIf(x -> now - x.t > 24 * 3_600_000L);
        while (info.obs.size() > 200) info.obs.removeFirst();
        if (o.full) info.lastScan = now;

        if (crashed) {
            boolean fresh = now >= info.cooldownUntil;
            info.cooldownUntil = now + cooldown.get() * 60_000L;
            info.goodStreak = 0;
            if (fresh) {
                warning("%s is crashing: cheapest %s vs usually %s. Not buying it for %d minutes%s.", name(item), money(o.ahEach), money(ref),
                    cooldown.get(), hasStake(item) ? ", holding stock until it recovers" : "");
                trade("crash", item, 0, (long) o.ahEach, "usual " + (long) ref);
            }
        }

        double ratio = 0;
        double net = o.ahCount > 0 ? listPrice(sellAgainst(o), 1) * (1 - tax.get() / 100) : 0;
        if (o.hasOrder && o.ahCount > 0) ratio = net / bidPrice(o.orderEach);
        if (o.full && !crashed) info.goodStreak = ratio >= minProfit.get() ? info.goodStreak + 1 : 0;

        // An order that no longer pays (or whose item is crashing): stop buying.
        for (Position p : state.positions) {
            if (!p.item.equals(id(item)) || !p.state.equals("OPEN") || p.cancelWanted || o.ahCount == 0) continue;
            double nowRatio = net / p.priceEach;
            if (crashed && nowRatio < minProfit.get() || nowRatio < minProfit.get() * 0.85) {
                p.cancelWanted = true;
                nextOrderCheck = 0;
                info("Cancelling the %s order: it would only make %.2fx now.", name(item), nowRatio);
            } else if (followOutbids.get() && o.hasOrder && o.orderEach > p.priceEach + roundingSlack(p.priceEach)
                && now - info.lastReorder > 30 * 60_000L && ratio >= minProfit.get()) {
                p.cancelWanted = true;
                info.lastReorder = now;
                info.goodStreak = confirmScans.get();
                nextOrderCheck = 0;
                info("Someone outbid the %s order (%s vs yours %s): ordering again above them.", name(item), money(o.orderEach), money(p.priceEach));
            }
        }
        MeteorClient.LOG.info("[Ember FlipBot] Scan {}: ah {} x{} (low5 {}, usual {}), order {}, ratio {}, streak {}{}", name(item),
            o.ahCount > 0 ? (long) o.ahEach : "-", o.ahCount, (long) o.ahLow5, (long) ref, o.hasOrder ? (long) o.orderEach : "-",
            String.format(Locale.ROOT, "%.2f", ratio), info.goodStreak, crashed ? " CRASH" : "");
        save();
    }

    /** The lowest it will list one item at now, or -1 to hold. */
    private long floorEach(Item item) {
        double cost = costEach(item);
        double floor = cost * minSellMargin.get() / (1 - tax.get() / 100);
        if (maxHold.get() > 0 && System.currentTimeMillis() - oldestStock(item) > maxHold.get() * 3_600_000L) {
            floor = cost * (1 - maxLoss.get() / 100.0) / (1 - tax.get() / 100);
        }
        return (long) Math.ceil(floor);
    }

    // ------------------------------------------------------------------ jobs: scanning

    /** /ah <item> and /order <item>; with ahOnly, just the auction part (a price check before listing). */
    private void startScan(Item item, boolean ahOnly) {
        Obs o = new Obs();
        o.full = !ahOnly;
        Job j = new Job("checking " + name(item));
        ahLookup(j, item, o);
        if (!ahOnly) orderLookup(j, item, o);
        j.onDone = () -> {
            o.t = System.currentTimeMillis();
            info(item).lastAhScan = o.t;
            recordScan(item, o);
        };
        j.onFail = () -> info(item).lastScan = System.currentTimeMillis() - rescan.get() * 30_000L;
        start(j);
    }

    private void ahLookup(Job j, Item item, Obs o) {
        j.then("wait to open the auction house", 2000, t -> gapPassed())
            .then("open /ah", 2, t -> command("ah " + searchName(item)))
            .then("auction house", 120, t -> settled(t, "Auction", 20))
            .then("read auctions", 2, t -> {
                List<Double> each = new ArrayList<>();
                GenericContainerScreenHandler h = container();
                if (h == null) throw new Fail("auction house closed");
                int listings = (h.getRows() - 1) * 9;
                for (int i = 0; i < listings; i++) {
                    ItemStack stack = h.getInventory().getStack(i);
                    if (!sameItem(stack, item)) continue;
                    long total = priceOf(stack);
                    if (total > 0) each.add((double) total / stack.getCount());
                }
                Collections.sort(each);
                o.ahCount = each.size();
                if (!each.isEmpty()) {
                    o.ahEach = each.getFirst();
                    o.ah2nd = each.size() > 1 ? each.get(1) : o.ahEach;
                    double sum = 0;
                    int n = Math.min(5, each.size());
                    for (int i = 0; i < n; i++) sum += each.get(i);
                    o.ahLow5 = sum / n;
                }
                return true;
            })
            .pause(10)
            .then("close", 2, t -> closeMenu());
    }

    /** The orders menu's sort button (a hopper listing the sorts). */
    private static int filterSlot(GenericContainerScreenHandler h) {
        return findSlot(h, s -> s.isOf(Items.HOPPER) && loreText(s).contains("Most Per Item"));
    }

    /** Adds steps: click the sort button (if `when` says so), wait for the menu to change, wait for it to settle. */
    private void cycleSort(Job j, java.util.function.BooleanSupplier when) {
        String[] before = {null};
        j.then("change sort", 2, t -> {
            GenericContainerScreenHandler h = container();
            if (h == null) throw new Fail("orders menu closed");
            int filter = filterSlot(h);
            if (filter < 0 || !when.getAsBoolean()) return true;
            before[0] = signatureOf(h);
            click(h, filter, SlotActionType.PICKUP);
            return true;
        })
            .then("sort shows", 80, t -> before[0] == null || container() != null && !signatureOf(container()).equals(before[0]) || t >= 60)
            .then("sorted", 120, t -> before[0] == null || settled(t, "Orders", 6));
    }

    private boolean sortedPerItem() {
        GenericContainerScreenHandler h = container();
        int filter = filterSlot(h);
        return filter < 0 || selectedOption(h.getInventory().getStack(filter)).contains("Per Item");
    }

    private void orderLookup(Job j, Item item, Obs o) {
        j.then("wait to open orders", 2000, t -> gapPassed())
            .then("open /order", 2, t -> command("order " + searchName(item)))
            .then("orders", 120, t -> settled(t, "Orders", 12));
        // DonutSMP sorts by "Most Paid" (the order's total) by default; the best price per item is "Most Per Item".
        for (int i = 0; i < 3; i++) cycleSort(j, () -> !sortedPerItem());
        j.pause(6)
            .then("read orders", 2, t -> {
                GenericContainerScreenHandler h = container();
                if (h == null) throw new Fail("orders menu closed");
                for (int i = 0; i < (h.getRows() - 1) * 9; i++) {
                    ItemStack stack = h.getInventory().getStack(i);
                    if (!stack.isOf(item)) continue;
                    OrderLine line = orderLine(stack);
                    if (line == null || line.amount > 0 && line.delivered >= line.amount) continue;
                    if (isOurs(item, line)) continue;
                    if (!o.hasOrder || line.each > o.orderEach) {
                        o.orderEach = line.each;
                        o.hasOrder = true;
                    }
                }
                return true;
            })
            .pause(10)
            .then("close", 2, t -> closeMenu());
    }

    private boolean isOurs(Item item, OrderLine line) {
        for (Position p : state.positions) {
            if (!p.item.equals(id(item)) || !(p.state.equals("OPEN") || p.state.equals("PENDING"))) continue;
            if (line.amount == p.amount && Math.abs(line.each - p.priceEach) <= roundingSlack(p.priceEach) + 1) return true;
        }
        return false;
    }

    /** Items people order right now, from the /orders pages under each sort. */
    private void startDiscover() {
        Job j = new Job("looking at orders");
        Set<Item> seen = new HashSet<>();
        j.then("wait to open orders", 2000, t -> gapPassed())
            .then("open /orders", 2, t -> command("orders"))
            .then("orders", 120, t -> settled(t, "Orders", 12));
        // Each sort shows different items: the priciest per item, the biggest orders, the newest.
        for (int k = 0; k < 3; k++) {
            if (k > 0) cycleSort(j, () -> true);
            j.then("read orders", 2, t -> {
                GenericContainerScreenHandler h = container();
                if (h == null) throw new Fail("orders menu closed");
                for (int i = 0; i < (h.getRows() - 1) * 9; i++) {
                    ItemStack stack = h.getInventory().getStack(i);
                    if (stack.isEmpty() || orderLine(stack) == null) continue;
                    var enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
                    if (enchants == null || enchants.isEmpty()) seen.add(stack.getItem());
                }
                return true;
            });
        }
        j.pause(10).then("close", 2, t -> closeMenu());
        j.onDone = () -> {
            int added = 0;
            for (Item item : seen) {
                ItemInfo info = info(item);
                if (!info.discovered && !watchlist.get().contains(item)) added++;
                info.discovered = true;
            }
            // Keep the list of discovered items from growing without end: drop ones not seen for a day.
            long now = System.currentTimeMillis();
            for (Map.Entry<String, ItemInfo> e : state.items.entrySet()) {
                if (e.getValue().discovered && !seen.contains(item(e.getKey())) && now - e.getValue().lastScan > 24 * 3_600_000L && e.getValue().lastScan > 0) e.getValue().discovered = false;
            }
            MeteorClient.LOG.info("[Ember FlipBot] Orders show {} items, {} new to scan", seen.size(), added);
            save();
        };
        start(j);
    }

    // ------------------------------------------------------------------ jobs: placing an order

    private void startOrder(Pick pick) {
        Item item = pick.item;
        Screen[] last = {null};
        Job j = new Job("ordering " + name(item));
        Position p = new Position();
        p.item = id(item);
        p.priceEach = pick.priceEach;
        p.amount = pick.amount;
        orderFail = null;
        j.then("wait to open orders", 2000, t -> gapPassed())
            .then("open /orders", 2, t -> command("orders"))
            .then("orders", 120, t -> settled(t, "Orders", 12))
            .then("open your orders", 2, t -> clickNamed("Your Orders"))
            .then("your orders", 120, t -> settled(t, "Your Orders", 10))
            .then("new order", 2, t -> {
                GenericContainerScreenHandler h = container();
                int slot = findSlot(h, s -> s.getName().getString().contains("New Order"));
                if (slot < 0) {
                    orderSlotsFullUntil = System.currentTimeMillis() + 30 * 60_000L;
                    throw new Fail("no free order slot");
                }
                click(h, slot, SlotActionType.PICKUP);
                return true;
            })
            .then("choose item dialog", 100, t -> dialogWith("Search") != null && textField(0) != null)
            .then("search item", 10, t -> {
                if (t < 6) return false;
                textField(0).setText(searchName(item));
                last[0] = mc.currentScreen;
                return press(b -> b.equalsIgnoreCase("Search"));
            })
            .then("search results", 100, t -> mc.currentScreen != last[0] && itemButton(item) != null)
            .then("pick item", 10, t -> {
                if (t < 6) return false;
                last[0] = mc.currentScreen;
                return pressWidget(itemButton(item));
            })
            .then("amount dialog", 100, t -> mc.currentScreen != last[0] && dialogWith("Next") != null && textField(0) != null)
            .then("type amount", 10, t -> {
                if (t < 6) return false;
                textField(0).setText(Integer.toString(pick.amount));
                last[0] = mc.currentScreen;
                return press(b -> b.equalsIgnoreCase("Next"));
            })
            .then("price dialog", 100, t -> mc.currentScreen != last[0] && dialogWith("Review Order") != null && textField(0) != null)
            .then("type price", 10, t -> {
                if (t < 6) return false;
                textField(0).setText(Long.toString(pick.priceEach));
                last[0] = mc.currentScreen;
                return press(b -> b.equalsIgnoreCase("Review Order"));
            })
            .then("review dialog", 100, t -> mc.currentScreen != last[0] && dialogWith("Create Order") != null)
            .then("create order", 20, t -> {
                if (t < 10) return false;
                String text = screenText(mc.currentScreen);
                if (!text.contains(Integer.toString(pick.amount)) && !text.contains(String.format(Locale.ROOT, "%,d", pick.amount))) {
                    throw new Fail("review shows the wrong amount: " + text);
                }
                Matcher shown = Pattern.compile("Item:\\s*([^|\\n]+)").matcher(text);
                if (shown.find() && !shown.group(1).trim().equalsIgnoreCase(name(item))) throw new Fail("review shows the wrong item: " + shown.group(1).trim());
                p.createdAt = System.currentTimeMillis();
                press(b -> b.equalsIgnoreCase("Create Order"));
                // Tracked from here on, even if the reply is missed: the next visit to Your Orders confirms it or
                // drops it, so a created order is never left unwatched (or ordered twice).
                state.positions.add(p);
                save();
                return true;
            })
            .then("server reply", 80, t -> {
                if (orderFail != null) throw new Fail(orderFail);
                if (t >= 40 && mc.currentScreen instanceof DialogScreen<?>) throw new Fail("a dialog stayed open after Create Order: " + screenText(mc.currentScreen));
                return t >= 40;
            });
        j.onDone = () -> {
            info(item).goodStreak = 0;
            nextOrderCheck = System.currentTimeMillis() + 8000;
            info("Ordered %d %s at %s each (%s), aiming for %.2fx.", pick.amount, name(item), money(pick.priceEach), money(pick.priceEach * pick.amount), pick.ratio);
            trade("order", item, pick.amount, pick.priceEach * pick.amount, "each " + pick.priceEach);
            save();
        };
        j.onFail = () -> {
            if (orderFail != null) {
                // The server refused it: nothing was created.
                state.positions.remove(p);
                warning("Couldn't order %s: %s", name(item), orderFail);
                if (orderFail.toLowerCase(Locale.ROOT).contains("money")) noMoneyUntil = System.currentTimeMillis() + 15 * 60_000L;
                if (orderFail.toLowerCase(Locale.ROOT).contains("limit")) orderSlotsFullUntil = System.currentTimeMillis() + 30 * 60_000L;
            } else if (state.positions.contains(p)) {
                nextOrderCheck = System.currentTimeMillis() + 8000;
            }
            info(item).goodStreak = 0;
            save();
        };
        start(j);
    }

    private volatile String orderFail;

    // ------------------------------------------------------------------ jobs: your orders (collect, cancel)

    private record OrderLine(double each, int delivered, int amount, boolean completed, boolean cancelled) {}

    private static final Pattern EACH = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?\\s*[kmbtKMBT]?)\\s*each");
    private static final Pattern DELIVERED = Pattern.compile("([0-9][0-9,]*)\\s*/\\s*([0-9][0-9,]*)\\s*Delivered");
    private static final Pattern REQUESTED = Pattern.compile("([0-9][0-9,]*)\\s*requested");

    private static OrderLine orderLine(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return null;
        double each = -1;
        int delivered = 0, amount = 0;
        boolean completed = false, cancelled = false;
        for (Text line : lore.lines()) {
            String s = line.getString();
            Matcher m = EACH.matcher(s);
            if (m.find()) each = parsePrice(m.group(1));
            m = DELIVERED.matcher(s);
            if (m.find()) {
                delivered = Integer.parseInt(m.group(1).replace(",", ""));
                amount = Integer.parseInt(m.group(2).replace(",", ""));
            }
            m = REQUESTED.matcher(s);
            if (m.find() && amount == 0) amount = Integer.parseInt(m.group(1).replace(",", ""));
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.contains("completed")) completed = true;
            if (lower.contains("cancelled") || lower.contains("canceled") || lower.contains("expired")) cancelled = true;
        }
        return each > 0 ? new OrderLine(each, delivered, amount, completed, cancelled) : null;
    }

    /** Opens Your Orders, matches what's there to the bot's positions, then collects or cancels one order. */
    private void startOrdersVisit() {
        Job j = new Job("checking orders");
        int[] target = {-1};
        Position[] which = {null};
        boolean[] cancel = {false};
        int[] before = {0};
        j.then("wait to open orders", 2000, t -> gapPassed())
            .then("open /orders", 2, t -> command("orders"))
            .then("orders", 120, t -> settled(t, "Orders", 12))
            .then("open your orders", 2, t -> clickNamed("Your Orders"))
            .then("your orders", 120, t -> settled(t, "Your Orders", 10))
            .then("read your orders", 2, t -> {
                GenericContainerScreenHandler h = container();
                Map<Position, Integer> slots = matchOrders(h);
                // One action per visit: a cancel first, then the biggest collect that fits.
                for (Map.Entry<Position, Integer> e : slots.entrySet()) {
                    if (e.getKey().cancelWanted && e.getKey().state.equals("OPEN")) {
                        which[0] = e.getKey();
                        target[0] = e.getValue();
                        cancel[0] = true;
                        return true;
                    }
                }
                if (freeSlots() > 1) {
                    for (Map.Entry<Position, Integer> e : slots.entrySet()) {
                        if (e.getKey().delivered > e.getKey().collected) {
                            which[0] = e.getKey();
                            target[0] = e.getValue();
                            return true;
                        }
                    }
                }
                return true;
            })
            .then("open the order", 2, t -> {
                if (target[0] < 0) {
                    job.steps.subList(job.index + 1, job.steps.size()).clear();
                    job.then("pause", 15, x -> x >= 10).then("close", 2, x -> closeMenu());
                    return true;
                }
                click(container(), target[0], SlotActionType.PICKUP);
                return true;
            })
            .then("edit order", 120, t -> settled(t, "Edit Order", 8))
            .then("cancel or collect", 2, t -> {
                Position p = which[0];
                if (cancel[0]) {
                    GenericContainerScreenHandler h = container();
                    int slot = findSlot(h, s -> s.getName().getString().toLowerCase(Locale.ROOT).contains("cancel"));
                    if (slot < 0) slot = findSlot(h, s -> s.isOf(Items.RED_CONCRETE) || s.isOf(Items.RED_WOOL) || s.isOf(Items.BARRIER));
                    if (slot < 0) throw new Fail("no cancel button");
                    click(h, slot, SlotActionType.PICKUP);
                    job.steps.subList(job.index + 1, job.steps.size()).clear();
                    job.then("confirm cancel", 60, x -> {
                        if (x < 20) return false;
                        // A confirmation menu or dialog, if DonutSMP asks.
                        if (mc.currentScreen instanceof DialogScreen<?>) {
                            press(b -> b.toLowerCase(Locale.ROOT).matches(".*(confirm|yes|cancel order).*"));
                            return true;
                        }
                        GenericContainerScreenHandler c = container();
                        if (c != null && !mc.currentScreen.getTitle().getString().contains("Edit Order")) {
                            int ok = findSlot(c, s -> s.getName().getString().toLowerCase(Locale.ROOT).matches(".*(confirm|yes).*") || s.isOf(Items.LIME_STAINED_GLASS_PANE) || s.isOf(Items.LIME_CONCRETE));
                            if (ok >= 0) click(c, ok, SlotActionType.PICKUP);
                        }
                        return true;
                    }).pause(20).then("close", 2, x -> closeMenu());
                    j.onDone = () -> {
                        p.cancelWanted = false;
                        p.state = "CLOSED";
                        p.closedAt = System.currentTimeMillis();
                        trade("cancel", item(p.item), p.amount - p.delivered, (long) p.priceEach * (p.amount - p.delivered), "refund for undelivered");
                        nextOrderCheck = System.currentTimeMillis() + 5000;
                        save();
                    };
                    return true;
                }
                return clickNamed("Collect");
            })
            .then("collect items", 120, t -> settled(t, "Collect", 8))
            .then("take items", 400, t -> {
                if (t == 0) before[0] = count(item(which[0].item));
                if (t % 5 != 4) return false;
                GenericContainerScreenHandler h = container();
                if (h == null) throw new Fail("collect menu closed");
                if (freeSlots() <= 1) return true;
                Item item = item(which[0].item);
                int got = count(item) - before[0];
                if (got >= which[0].delivered - which[0].collected) return true;
                int slot = findSlot(h, s -> s.isOf(item));
                if (slot < 0 || slot >= h.getRows() * 9) return true;
                click(h, slot, SlotActionType.QUICK_MOVE);
                return false;
            })
            .pause(10)
            .then("close", 2, t -> closeMenu())
            .then("count", 20, t -> {
                if (t < 10) return false;
                Position p = which[0];
                int got = Math.max(0, count(item(p.item)) - before[0]);
                got = Math.min(got, p.delivered - p.collected);
                p.collected += got;
                if (got > 0) {
                    trade("collect", item(p.item), got, (long) p.priceEach * got, "");
                    info("Collected %d %s (%d/%d delivered so far).", got, name(item(p.item)), p.delivered, p.amount);
                }
                // More waiting, and room for it: come back soon.
                if (p.delivered > p.collected && freeSlots() > 1) nextOrderCheck = System.currentTimeMillis() + 5000;
                save();
                return true;
            });
        start(j);
    }

    /** Your Orders: updates each position from its order and returns where each one sits. */
    private Map<Position, Integer> matchOrders(GenericContainerScreenHandler h) {
        Map<Position, Integer> slots = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Position p : state.positions) {
            if (p.state.equals("DONE") || p.state.equals("CLOSED") && p.delivered <= p.collected) continue;
            Item item = item(p.item);
            for (int i = 0; i < Math.min(45, h.getInventory().size()); i++) {
                if (used.contains(i)) continue;
                ItemStack stack = h.getInventory().getStack(i);
                if (!stack.isOf(item)) continue;
                OrderLine line = orderLine(stack);
                if (line == null || line.amount != p.amount || Math.abs(line.each - p.priceEach) > roundingSlack(p.priceEach) + 1) continue;
                used.add(i);
                slots.put(p, i);
                int newly = line.delivered - p.delivered;
                p.delivered = Math.max(p.delivered, line.delivered);
                p.lastSeen = now;
                p.missedChecks = 0;
                if (p.state.equals("PENDING")) {
                    p.state = "OPEN";
                    info("The %s order is up.", name(item));
                }
                if (newly > 0) MeteorClient.LOG.info("[Ember FlipBot] {} order: {}/{} delivered", name(item), p.delivered, p.amount);
                if (p.state.equals("OPEN") && (line.completed || line.cancelled || p.delivered >= p.amount)) {
                    p.state = "CLOSED";
                    p.closedAt = now;
                    p.cancelWanted = false;
                    info("The %s order is %s: %d delivered.", name(item), line.cancelled ? "over" : "complete", p.delivered);
                }
                break;
            }
            if (!slots.containsKey(p)) {
                // Placed but never seen: the order didn't go through. Seen before but gone: collected out and removed.
                p.missedChecks++;
                if (p.state.equals("PENDING") && p.missedChecks >= 2) {
                    p.state = "DONE";
                    warning("The %s order never showed up in Your Orders; forgetting it.", name(item));
                } else if ((p.state.equals("OPEN") || p.state.equals("CLOSED")) && p.missedChecks >= 2) {
                    // Gone from Your Orders: nothing more to collect from it.
                    if (p.state.equals("OPEN")) p.closedAt = now;
                    p.state = "CLOSED";
                    p.delivered = p.collected;
                }
            }
        }
        finishPositions();
        save();
        return slots;
    }

    /** A closed order whose stock is all sold is done; its profit is booked. */
    private void finishPositions() {
        for (Position p : state.positions) {
            if (!p.state.equals("CLOSED") || p.delivered > p.collected || p.sold < p.collected) continue;
            p.state = "DONE";
            long profit = p.revenue - p.priceEach * p.collected;
            info("Finished flipping %s: bought %d, sold for %s, profit %s.", name(item(p.item)), p.collected, money(p.revenue), money(profit));
            trade("done", item(p.item), p.collected, profit, "revenue " + p.revenue);
        }
        state.positions.removeIf(p -> p.state.equals("DONE") && System.currentTimeMillis() - Math.max(p.closedAt, p.createdAt) > 7 * 86_400_000L);
    }

    // ------------------------------------------------------------------ jobs: selling

    private final ArrayDeque<int[]> moves = new ArrayDeque<>();
    private volatile boolean listedMessage;
    private long lastListAttempt;
    private volatile String listFail;

    private void startSell(Item item, long now) {
        ItemInfo info = info(item);
        boolean fresh = now - info.lastAhScan < priceCheck.get() * 1000L && latest(item) != null;
        if (!fresh) {
            // Look the price up first; the next plan() lists with it.
            startScan(item, true);
            return;
        }
        Obs o = latest(item);
        int units = Math.min(perListing.get(), Math.min(unlisted(item), count(item)));
        long floor = floorEach(item) * units;
        // During a crash (and its cooldown) don't sell under the pre-crash level, however far above cost that is:
        // the dip is panic, and selling into it only feeds it.
        double ref = reference(item);
        boolean panic = now < info.cooldownUntil && ref > 0;
        // Wait for most of the way back (half the crash-drop under usual), not just the edge of the crash.
        if (panic) floor = Math.max(floor, (long) (ref * (1 - crashDrop.get() / 200.0) * units));
        long at;
        if (o.ahCount == 0) {
            // Nothing else listed: the item's usual price, or twice the floor if it has none yet.
            at = ref > 0 ? (long) (ref * units) : floor * 2;
        } else {
            at = listPrice(sellAgainst(o), units);
        }
        if (at < floor) {
            // Don't follow a crash down (that's what feeds a panic): hold the stock and look again later.
            info.holdUntil = now + 5 * 60_000L;
            status = "holding " + name(item);
            MeteorClient.LOG.info("[Ember FlipBot] Holding {}: market {} under floor {}", name(item), at, floor);
            if (!info.holdNotified) {
                info("Holding %s: the auction house is at %s, under the %s it must make%s. Checking again every 5 minutes.", name(item), money(at), money(floor),
                    panic ? " until the panic passes" : "");
                info.holdNotified = true;
            }
            return;
        }
        info.holdNotified = false;
        long price = at;
        Job j = new Job("selling " + name(item));
        int slot = sellSlot.get() - 1;
        moves.clear();
        j.then("open inventory", 2, t -> {
            InvUtils.swap(slot, false);
            mc.setScreen(new InventoryScreen(mc.player));
            return true;
        })
            .pause(6)
            .then("move item", 200, t -> {
                if (!(mc.currentScreen instanceof InventoryScreen)) throw new Fail("inventory closed");
                if (t % 3 != 0) return false;
                if (moves.isEmpty()) {
                    planMove(item, units);
                    if (moves.isEmpty()) return true;
                }
                int[] c = moves.removeFirst();
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, c[0], c[1], SlotActionType.PICKUP, mc.player);
                return false;
            })
            .then("close inventory", 2, t -> {
                mc.player.closeHandledScreen();
                InvUtils.swap(slot, false);
                return true;
            })
            .pause(10)
            .then("list", 2, t -> {
                ItemStack held = mc.player.getInventory().getStack(slot);
                if (!held.isOf(item) || held.getCount() != units) throw new Fail("sell slot holds " + held);
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
                listedMessage = false;
                listFail = null;
                serverLinesToLog = 3;
                mc.player.networkHandler.sendChatCommand("ah sell " + price);
                return true;
            })
            .then("listed", 70, t -> {
                if (listFail != null) throw new Fail(listFail);
                return listedMessage || !mc.player.getInventory().getStack(slot).isOf(item);
            });
        lastListAttempt = now;
        j.onDone = () -> {
            // Listed past the count's limit: an older listing must have sold unseen. Book the oldest as sold.
            if (activeListings() >= maxListings.get() && !state.listings.isEmpty()) {
                Listing oldest = state.listings.getFirst();
                MeteorClient.LOG.info("[Ember FlipBot] Slot free although {} listings are counted: booking the oldest ({}) as sold", activeListings(), oldest.item);
                sold(name(item(oldest.item)), oldest.price);
            }
            int left = units;
            for (Position p : state.positions) {
                if (!p.item.equals(id(item)) || p.state.equals("DONE")) continue;
                int take = Math.min(left, p.collected - p.listed);
                p.listed += take;
                left -= take;
                if (left <= 0) break;
            }
            Listing l = new Listing();
            l.item = id(item);
            l.units = units;
            l.price = price;
            l.at = System.currentTimeMillis();
            state.listings.add(l);
            MeteorClient.LOG.info("[Ember FlipBot] Listed {} {} for {}", units, name(item), price);
            backoffUntil = System.currentTimeMillis() + 2000;
            save();
        };
        j.onFail = () -> {
            listingsFullUntil = System.currentTimeMillis() + 2 * 60_000L;
            info("A %s listing didn't go through (auction slots full?): trying again in 2 minutes.", name(item));
        };
        start(j);
    }

    /** Clicks towards `want` of the item in the sell slot, with the inventory open (see AH Sell). */
    private void planMove(Item item, int want) {
        PlayerInventory inv = mc.player.getInventory();
        int sell = sellSlot.get() - 1;
        int sellId = SlotUtils.indexToId(sell);
        ItemStack current = inv.getStack(sell);
        if (!current.isEmpty() && !current.isOf(item)) {
            int free = emptyMainSlot(inv);
            if (free < 0) throw new Fail("sell slot taken and inventory full");
            moves.add(new int[] {sellId, 0});
            moves.add(new int[] {SlotUtils.indexToId(free), 0});
            return;
        }
        if (current.isOf(item) && current.getCount() > want) {
            int free = emptyMainSlot(inv);
            if (free < 0) throw new Fail("inventory full");
            moves.add(new int[] {sellId, 0});
            for (int i = 0; i < want; i++) moves.add(new int[] {sellId, 1});
            moves.add(new int[] {SlotUtils.indexToId(free), 0});
            return;
        }
        int have = current.isOf(item) ? current.getCount() : 0;
        if (have >= want) return;
        for (int i = 0; i < 36; i++) {
            if (i == sell || !inv.getStack(i).isOf(item)) continue;
            int count = inv.getStack(i).getCount(), take = Math.min(want - have, count);
            moves.add(new int[] {SlotUtils.indexToId(i), 0});
            for (int k = 0; k < take; k++) moves.add(new int[] {sellId, 1});
            if (take < count) moves.add(new int[] {SlotUtils.indexToId(i), 0});
            return;
        }
        throw new Fail("out of " + name(item));
    }

    private static int emptyMainSlot(PlayerInventory inv) {
        for (int i = 9; i < 36; i++) if (inv.getStack(i).isEmpty()) return i;
        return -1;
    }

    // ------------------------------------------------------------------ server messages

    private static final Pattern SOLD = Pattern.compile("^(\\S+) bought your (.+?) for \\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?\\s*[kmbtKMBT]?)");

    /** Network thread: queue what the server says, read on the next tick. */
    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        Text text = null;
        if (event.packet instanceof GameMessageS2CPacket p) text = p.content();
        else if (event.packet instanceof OverlayMessageS2CPacket p) text = p.text();
        if (text == null) return;
        String s = text.getString().trim();
        if (s.isEmpty()) return;
        if (s.startsWith("You listed")) listedMessage = true;
        String lower = s.toLowerCase(Locale.ROOT);
        if (job != null && job.name.startsWith("ordering") && (lower.contains("enough money") || lower.contains("order limit") || lower.contains("cannot") || lower.contains("can't"))) orderFail = s;
        if (job != null && job.name.startsWith("selling") && (lower.contains("listing limit") || lower.contains("maximum") || lower.contains("must hold"))) listFail = s;
        serverLines.add(s);
    }

    private void drainServerLines() {
        String s;
        while ((s = serverLines.poll()) != null) {
            if (serverLinesToLog > 0) {
                serverLinesToLog--;
                MeteorClient.LOG.info("[Ember FlipBot] Server: {}", s);
            }
            Matcher m = SOLD.matcher(s);
            if (m.find()) sold(m.group(2).trim(), parsePrice(m.group(3)));
            else if (job != null && job.name.startsWith("ordering")) MeteorClient.LOG.info("[Ember FlipBot] Server: {}", s);
        }
    }

    /** "x bought your Dispenser for $69.8K": book the sale against the oldest matching listing. */
    private void sold(String itemName, long shown) {
        Listing match = null;
        for (Listing l : state.listings) {
            if (!name(item(l.item)).equalsIgnoreCase(itemName)) continue;
            if (match == null) match = l;
            if (Math.abs(l.price - shown) <= roundingSlack(l.price) + 1) {
                match = l;
                break;
            }
        }
        if (match == null) return;
        state.listings.remove(match);
        Item item = item(match.item);
        long net = Math.round(match.price * (1 - tax.get() / 100));
        int left = match.units;
        for (Position p : state.positions) {
            if (!p.item.equals(match.item) || p.state.equals("DONE")) continue;
            int take = Math.min(left, p.listed - p.sold);
            if (take <= 0) continue;
            p.sold += take;
            p.revenue += net * take / match.units;
            state.profit += (net * take / match.units) - p.priceEach * take;
            left -= take;
            if (left <= 0) break;
        }
        ItemInfo info = info(item);
        info.soldUnits += match.units;
        info.soldValue += net;
        if (info.firstSale == 0) info.firstSale = System.currentTimeMillis();
        trade("sold", item, match.units, net, "listed " + match.price);
        finishPositions();
        save();
    }

    // ------------------------------------------------------------------ menu helpers

    private boolean gapPassed() {
        if (mc.currentScreen != null) return false;
        return System.currentTimeMillis() - lastMenuAt >= menuGap.get() * 1000L;
    }

    private boolean command(String command) {
        lastMenuAt = System.currentTimeMillis();
        serverLinesToLog = 2;
        mc.player.networkHandler.sendChatCommand(command);
        return true;
    }

    private String settleSig = "";
    private int settleStable;

    /**
     * True once a menu with the title is open and its contents have stopped changing for a moment (DonutSMP fills
     * search results in a few ticks after the menu opens). minTicks is the least time to wait at all.
     */
    private boolean settled(int t, String title, int minTicks) {
        if (t == 0) {
            settleSig = "";
            settleStable = 0;
        }
        GenericContainerScreenHandler h = container();
        if (h == null || !mc.currentScreen.getTitle().getString().contains(title)) return false;
        String sig = signatureOf(h);
        if (sig.equals(settleSig)) settleStable++;
        else {
            settleSig = sig;
            settleStable = 0;
        }
        return t >= minTicks && settleStable >= 8;
    }

    private boolean closeMenu() {
        if (mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
        lastMenuAt = Math.max(lastMenuAt, System.currentTimeMillis() - menuGap.get() * 1000L + 1500);
        return true;
    }

    private GenericContainerScreenHandler container() {
        return mc.currentScreen instanceof GenericContainerScreen screen ? screen.getScreenHandler() : null;
    }

    private void click(GenericContainerScreenHandler h, int slot, SlotActionType action) {
        mc.interactionManager.clickSlot(h.syncId, slot, 0, action, mc.player);
    }

    private boolean clickNamed(String name) {
        GenericContainerScreenHandler h = container();
        if (h == null) throw new Fail("menu closed");
        int slot = findSlot(h, s -> s.getName().getString().contains(name));
        if (slot < 0) throw new Fail("no \"" + name + "\" button");
        click(h, slot, SlotActionType.PICKUP);
        return true;
    }

    private static int findSlot(GenericContainerScreenHandler h, java.util.function.Predicate<ItemStack> p) {
        if (h == null) return -1;
        int size = Math.min(h.getInventory().size(), h.getRows() * 9);
        for (int i = 0; i < size; i++) {
            ItemStack stack = h.getInventory().getStack(i);
            if (!stack.isEmpty() && p.test(stack)) return i;
        }
        return -1;
    }

    private static String signatureOf(GenericContainerScreenHandler h) {
        StringBuilder sb = new StringBuilder();
        int size = Math.min(h.getInventory().size(), h.getRows() * 9);
        for (int i = 0; i < size; i++) {
            ItemStack stack = h.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            sb.append(i).append(stack.getItem()).append(stack.getCount()).append(priceOf(stack));
            if (stack.isOf(Items.HOPPER)) sb.append(selectedOption(stack));
            sb.append('|');
        }
        return sb.toString();
    }

    private static String loreText(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Text line : lore.lines()) sb.append(line.getString()).append('\n');
        return sb.toString();
    }

    /** The highlighted option of a filter button's lore: the "â€¢" line that isn't greyed out. */
    private static String selectedOption(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return "";
        String fallback = "";
        for (Text line : lore.lines()) {
            String s = line.getString().trim();
            if (!s.startsWith("â€¢") && !s.startsWith("-") && !s.startsWith("Â»") && !s.startsWith("â–¶")) continue;
            Integer rgb = firstColor(line);
            if (rgb == null || !isGrey(rgb)) return s;
            if (s.startsWith("Â»") || s.startsWith("â–¶")) fallback = s;
        }
        return fallback;
    }

    private static Integer firstColor(Text text) {
        Integer[] out = {null};
        text.visit((Style style, String string) -> {
            if (string.isBlank()) return Optional.empty();
            TextColor color = style.getColor();
            out[0] = color == null ? null : color.getRgb();
            return Optional.of(Boolean.TRUE);
        }, Style.EMPTY);
        return out[0];
    }

    private static boolean isGrey(int rgb) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
        return Math.abs(r - g) < 12 && Math.abs(g - b) < 12 && r < 200;
    }

    // dialogs

    private static void collect(Element e, List<Element> out) {
        out.add(e);
        if (e instanceof ParentElement p) for (Element child : p.children()) collect(child, out);
    }

    private static List<Element> elements(Screen screen) {
        List<Element> out = new ArrayList<>();
        if (screen != null) for (Element e : screen.children()) collect(e, out);
        return out;
    }

    private DialogScreen<?> dialogWith(String buttonLabel) {
        if (!(mc.currentScreen instanceof DialogScreen<?> d)) return null;
        for (Element e : elements(d)) {
            if (e instanceof ButtonWidget b && clean(b.getMessage().getString()).equalsIgnoreCase(buttonLabel)) return d;
        }
        return null;
    }

    private TextFieldWidget textField(int index) {
        if (!(mc.currentScreen instanceof DialogScreen<?> d)) return null;
        int i = 0;
        for (Element e : elements(d)) if (e instanceof TextFieldWidget f && i++ == index) return f;
        return null;
    }

    private boolean press(java.util.function.Predicate<String> label) {
        for (Element e : elements(mc.currentScreen)) {
            if (e instanceof ButtonWidget b && b.active && label.test(clean(b.getMessage().getString()))) return pressWidget(b);
        }
        throw new Fail("no matching button on " + (mc.currentScreen == null ? "no screen" : mc.currentScreen.getTitle().getString()));
    }

    private boolean pressWidget(ButtonWidget b) {
        if (b == null) throw new Fail("button gone");
        b.onPress(null);
        return true;
    }

    /** The Choose Item dialog's button for exactly this item. */
    private ButtonWidget itemButton(Item item) {
        if (!(mc.currentScreen instanceof DialogScreen<?> d)) return null;
        String want = searchName(item);
        ButtonWidget loose = null;
        int looseCount = 0;
        for (Element e : elements(d)) {
            if (!(e instanceof ButtonWidget b)) continue;
            String label = clean(b.getMessage().getString()).toLowerCase(Locale.ROOT);
            // The label may start with the item's icon, which reads as one word of sprite name.
            String withoutIcon = label.contains(" ") ? label.substring(label.indexOf(' ') + 1).trim() : label;
            if (label.equals(want) || withoutIcon.equals(want)) return b;
            if (label.endsWith(" " + want)) {
                loose = b;
                looseCount++;
            }
        }
        return looseCount == 1 ? loose : null;
    }

    private static String screenText(Screen screen) {
        StringBuilder sb = new StringBuilder();
        for (Element e : elements(screen)) if (e instanceof ClickableWidget w) sb.append(w.getMessage().getString()).append(" | ");
        return sb.toString();
    }

    /** A label without icon glyphs and formatting leftovers. */
    private static String clean(String s) {
        return s.replaceAll("[^\\p{L}\\p{N} ()'.,$-]", "").trim();
    }

    // ------------------------------------------------------------------ inventory and items

    private int count(Item item) {
        PlayerInventory inv = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) if (inv.getStack(i).isOf(item)) n += inv.getStack(i).getCount();
        return n;
    }

    /** Empty inventory slots, not counting the sell slot. */
    private int freeSlots() {
        PlayerInventory inv = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) if (i != sellSlot.get() - 1 && inv.getStack(i).isEmpty()) n++;
        return n;
    }

    private ItemInfo info(Item item) {
        return state.items.computeIfAbsent(id(item), k -> new ItemInfo());
    }

    private static String id(Item item) {
        return Registries.ITEM.getId(item).toString();
    }

    private static Item item(String id) {
        Identifier identifier = Identifier.tryParse(id);
        return identifier == null ? Items.AIR : Registries.ITEM.get(identifier);
    }

    private static String name(Item item) {
        return item.getName().getString();
    }

    private static String searchName(Item item) {
        return name(item).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim();
    }

    /** A plain one of the item on the auction house (the plugin names every listing; renamed or enchanted copies don't count). */
    private static boolean sameItem(ItemStack listing, Item item) {
        if (!listing.isOf(item)) return false;
        if (!listing.getName().getString().trim().equalsIgnoreCase(name(item))) return false;
        var enchants = listing.get(DataComponentTypes.ENCHANTMENTS);
        return enchants == null || enchants.isEmpty();
    }

    private static final Pattern PRICE_LINE = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?\\s*[kmbtKMBT]?)");

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

    static long parsePrice(String text) {
        return AHSell.parsePrice(text);
    }

    private static double parsePercent(String text) {
        try {
            return Double.parseDouble(text.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ balance

    private static final Pattern MONEY = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?\\s*[kmbtKMBT]?)");

    /** Your balance from the sidebar's money line, if it has one. */
    private Long readBalance() {
        if (mc.world == null) return null;
        var scoreboard = mc.world.getScoreboard();
        var objective = scoreboard.getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
        if (objective == null) return null;
        for (var entry : scoreboard.getScoreboardEntries(objective)) {
            var team = scoreboard.getScoreHolderTeam(entry.owner());
            String line = net.minecraft.scoreboard.Team.decorateName(team, entry.name()).getString();
            String lower = line.toLowerCase(Locale.ROOT);
            if (!lower.contains("money") && !lower.contains("balance")) continue;
            Matcher m = MONEY.matcher(line);
            if (m.find()) return parsePrice(m.group(1));
            m = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?[kmbtKMBT]?)").matcher(line);
            if (m.find()) return parsePrice(m.group(1));
        }
        return null;
    }

    // ------------------------------------------------------------------ text

    private String openSummary() {
        StringBuilder sb = new StringBuilder();
        for (Position p : state.positions) {
            if (p.state.equals("DONE")) continue;
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(name(item(p.item))).append(' ').append(p.delivered).append('/').append(p.amount).append(" got, ").append(p.sold).append(" sold");
        }
        return sb.isEmpty() ? "nothing open" : sb.toString();
    }

    static String money(double value) {
        double abs = Math.abs(value);
        String s;
        if (abs >= 1e9) s = String.format(Locale.ROOT, "%.2fb", value / 1e9);
        else if (abs >= 1e6) s = String.format(Locale.ROOT, "%.2fm", value / 1e6);
        else if (abs >= 1e3) s = String.format(Locale.ROOT, "%.1fk", value / 1e3);
        else s = String.format(Locale.ROOT, "%.0f", value);
        return "$" + s;
    }
}
