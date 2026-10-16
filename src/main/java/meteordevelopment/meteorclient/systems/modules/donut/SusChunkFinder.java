package meteordevelopment.meteorclient.systems.modules.donut;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Flags chunks with evidence of a player, for base hunting. Every signal is something world generation cannot
 * produce, so a flag means someone built there or kept the area loaded for a long time:
 * <ul>
 *     <li>Player blocks: blocks that never generate (hoppers, observers, beacons, ender chests, shulkers...),
 *     sideways deepslate, cobbled deepslate and torches below Y 0, storage deep underground.</li>
 *     <li>Growth: kelp taller than the 11 blocks worldgen allows, or with a tip older than worldgen gives it; bee
 *     nests holding honey; cocoa all ripe.</li>
 *     <li>Amethyst: geode slices where every bud has finished growing.</li>
 *     <li>Mined geodes (optional): plenty of bare budding amethyst with every crystal broken off.</li>
 * </ul>
 */
public class SusChunkFinder extends Module {
    private static final String FIND_TYPE = "sus-chunk";
    private static final String MINED_TYPE = "mined-geode";
    private static final int GEODE_MAX_Y = 47;
    // Worldgen kelp: at most 11 blocks, tip age 20-23. Growing pushes past both.
    private static final int NATURAL_KELP_HEIGHT = 11;
    private static final int NATURAL_KELP_AGE = 23;
    // Old saved finds from checks that turned out to fire on natural terrain; they are no longer shown.
    private static final Pattern DISCREDITED = Pattern.compile(
        "kelp grown to (the )?surface|vines|bamboo|growth center|\\d+/\\d+ grown (amethyst|cocoa)");

    public enum Sensitivity {
        Low(1.5),
        Medium(1.0),
        High(0.6);

        final double scale;

        Sensitivity(double scale) {
            this.scale = scale;
        }

        int count(int base) {
            return Math.max(1, (int) Math.ceil(base * scale));
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgAlerts = settings.createGroup("Alerts");

    // General

    private final Setting<Sensitivity> sensitivity = sgGeneral.add(new EnumSetting.Builder<Sensitivity>()
        .name("sensitivity")
        .description("How much evidence a chunk needs. High finds more, Low only flags plenty of it.")
        .defaultValue(Sensitivity.Medium)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> playerBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("player-blocks")
        .description("Blocks that never generate (hoppers, observers, beacons, ender chests, shulkers, beehives...), sideways deepslate, cobbled deepslate and torches below Y 0, storage deep underground.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> growth = sgGeneral.add(new BoolSetting.Builder()
        .name("growth")
        .description("Kelp grown past its natural height, bee nests holding honey, cocoa all ripe. These only happen while a player keeps the area loaded.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> amethyst = sgGeneral.add(new BoolSetting.Builder()
        .name("amethyst")
        .description("Geodes where every bud has grown into a full cluster.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> minedGeodes = sgGeneral.add(new BoolSetting.Builder()
        .name("mined-geodes")
        .description("Geodes with lots of bare budding amethyst and every crystal broken off: someone farmed them.")
        .defaultValue(false)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    // Render

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of sus chunks.")
        .defaultValue(new SettingColor(170, 120, 255, 220))
        .build()
    );

    private final Setting<SettingColor> minedColor = sgRender.add(new ColorSetting.Builder()
        .name("mined-color")
        .description("Color of mined geodes.")
        .defaultValue(new SettingColor(255, 170, 60, 220))
        .visible(minedGeodes::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How flagged chunks are drawn.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from your crosshair to each flagged chunk.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> followPlayerY = sgRender.add(new BoolSetting.Builder()
        .name("follow-player-y")
        .description("Draw the chunk plane at your height so it stays visible at any depth. Off draws it at sea level.")
        .defaultValue(true)
        .build()
    );

    // Alerts

    private final Setting<Boolean> chatNotify = sgAlerts.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Announce newly flagged chunks in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> saveFinds = sgAlerts.add(new BoolSetting.Builder()
        .name("save-finds")
        .description("Remember flagged chunks for this server, so they still show after relogging.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoWaypoint = sgAlerts.add(new BoolSetting.Builder()
        .name("auto-waypoint")
        .description("Add a waypoint for every newly flagged chunk.")
        .defaultValue(false)
        .build()
    );

    private record Result(String susWhy, String minedWhy) {}

    // Main thread only.
    private final Long2ObjectOpenHashMap<String> flagged = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<String> mined = new Long2ObjectOpenHashMap<>();
    private final LongSet saved = new LongOpenHashSet();
    private final LongSet savedMined = new LongOpenHashSet();
    private final LongSet announced = new LongOpenHashSet();
    private final LongSet pendingRescan = new LongOpenHashSet();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Ember-SusChunkFinder");
        thread.setDaemon(true);
        return thread;
    });

    private DimensionType lastDimension;
    private FindsStorage.Context context = new FindsStorage.Context("", "");
    private int rescanTimer;
    private int generation;

    public SusChunkFinder() {
        super(Categories.Donut, "sus-chunk-finder", "Highlights chunks showing signs of player activity.");
    }

    @Override
    public void onActivate() {
        clear();
        if (mc.world == null) return;

        lastDimension = mc.world.getDimension();
        context = FindsStorage.context();

        for (FindsStorage.Find find : FindsStorage.get(context, FIND_TYPE)) {
            if (stillCredible(find.note)) saved.add(ChunkPos.toLong(find.x >> 4, find.z >> 4));
        }
        for (FindsStorage.Find find : FindsStorage.get(context, MINED_TYPE)) savedMined.add(ChunkPos.toLong(find.x >> 4, find.z >> 4));

        rescanLoadedChunks();
    }

    /** True if at least one reason in a saved note comes from a check that is still trusted. */
    private static boolean stillCredible(String note) {
        if (note == null || note.isBlank()) return true;
        for (String part : note.split(", ")) {
            if (!DISCREDITED.matcher(part).find()) return true;
        }
        return false;
    }

    @Override
    public void onDeactivate() {
        clear();
    }

    private void clear() {
        generation++;
        flagged.clear();
        mined.clear();
        saved.clear();
        savedMined.clear();
        announced.clear();
        pendingRescan.clear();
    }

    private void rescanLoadedChunks() {
        if (!isActive() || mc.world == null) return;
        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk levelChunk) queueScan(levelChunk);
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        onActivate();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        queueScan(event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        // Batched: an explosion or a player mining can update hundreds of blocks in one chunk.
        if (isRelevant(event.newState) || isRelevant(event.oldState)) pendingRescan.add(ChunkPos.toLong(event.pos));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;

        if (mc.world.getDimension() != lastDimension) {
            onActivate();
            return;
        }

        if (++rescanTimer < 20) return;
        rescanTimer = 0;

        for (long key : pendingRescan) {
            Chunk chunk = mc.world.getChunk(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key), ChunkStatus.FULL, false);
            if (chunk instanceof WorldChunk levelChunk) queueScan(levelChunk);
        }
        pendingRescan.clear();
    }

    private void queueScan(WorldChunk chunk) {
        int gen = generation;
        Sensitivity sens = sensitivity.get();
        boolean end = mc.world != null && mc.world.getRegistryKey() == World.END;

        worker.submit(() -> {
            if (!isActive()) return;
            Result result = scan(chunk, sens, end);
            long key = chunk.getPos().toLong();
            mc.execute(() -> {
                if (gen == generation && isActive()) apply(key, result);
            });
        });
    }

    private void apply(long key, Result result) {
        if (result.susWhy == null) flagged.remove(key);
        else if (flagged.put(key, result.susWhy) == null) announce(key, result.susWhy, false);

        if (result.minedWhy == null) mined.remove(key);
        else if (mined.put(key, result.minedWhy) == null) announce(key, result.minedWhy, true);
    }

    private void announce(long key, String reason, boolean isMined) {
        LongSet savedSet = isMined ? savedMined : saved;
        long announceKey = isMined ? ~key : key;
        // Chunks remembered from earlier sessions were already announced back then.
        if (savedSet.contains(key) || !announced.add(announceKey)) return;

        int blockX = ChunkPos.getPackedX(key) * 16 + 8;
        int blockZ = ChunkPos.getPackedZ(key) * 16 + 8;
        BlockPos center = new BlockPos(blockX, 64, blockZ);
        String label = isMined ? "Mined geode" : "Sus chunk";

        if (saveFinds.get()) {
            savedSet.add(key);
            FindsStorage.add(context, isMined ? MINED_TYPE : FIND_TYPE, center, reason);
        }
        if (autoWaypoint.get()) FindsStorage.waypoint(label, center);
        if (chatNotify.get() && !EmberNotificationsHud.shouldInterceptChat()) {
            info("%s at (highlight)%d, %d(default): %s", label, blockX, blockZ, reason);
        }
        EmberNotificationsHud.push(label + " found", blockX + ", " + blockZ + " - " + reason, EmberNotificationsHud.GOOD);
    }

    // Scanning (worker thread)

    private Result scan(WorldChunk chunk, Sensitivity sens, boolean end) {
        ChunkSection[] sections = chunk.getSectionArray();
        int minY = chunk.getBottomY();
        int topY = minY + chunk.getHeight() - 1;
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        BlockPos.Mutable probe = new BlockPos.Mutable();

        int rotated = 0, cobbled = 0, storage = 0, deepTorches = 0;
        int artificial = 0;
        String artificialName = null;
        boolean ancientCity = false, stronghold = false, trialChamber = false;

        int grownAmethyst = 0, growingAmethyst = 0, exposedBudding = 0;
        int[] kelpBottom = new int[256], kelpTop = new int[256], kelpTipAge = new int[256];
        java.util.Arrays.fill(kelpBottom, Integer.MAX_VALUE);
        java.util.Arrays.fill(kelpTop, Integer.MIN_VALUE);
        boolean anyKelp = false;
        int cocoa = 0, ripeCocoa = 0, honeyNests = 0;

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty() || !section.hasAny(this::isRelevant)) continue;
            int baseY = minY + (i << 4);

            for (int ly = 0; ly < 16; ly++) {
                int y = baseY + ly;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        Block block = state.getBlock();

                        if (block == Blocks.DEEPSLATE) {
                            if (state.get(Properties.AXIS) != Direction.Axis.Y) rotated++;
                        } else if (block == Blocks.COBBLED_DEEPSLATE) {
                            if (y < 0) cobbled++;
                        } else if (block == Blocks.REINFORCED_DEEPSLATE || block == Blocks.SCULK_SHRIEKER) {
                            ancientCity = true;
                        } else if (block == Blocks.END_PORTAL_FRAME || block == Blocks.MOSSY_STONE_BRICKS || block == Blocks.CRACKED_STONE_BRICKS) {
                            stronghold = true;
                        } else if (block == Blocks.TRIAL_SPAWNER || block == Blocks.VAULT) {
                            trialChamber = true;
                        } else if (block == Blocks.TORCH || block == Blocks.WALL_TORCH) {
                            if (y < 0) deepTorches++;
                        } else if (isArtificial(block, end)) {
                            artificial++;
                            if (artificialName == null) artificialName = block.getName().getString().toLowerCase(java.util.Locale.ROOT);
                        } else if (y <= 20 && isStorage(block)) {
                            storage++;
                        } else if (block == Blocks.AMETHYST_CLUSTER) {
                            grownAmethyst++;
                        } else if (isBud(block)) {
                            // A bud still sitting on budding amethyst keeps growing; anything else is done.
                            Direction facing = state.get(Properties.FACING);
                            int sx = lx - facing.getOffsetX(), sy = y - facing.getOffsetY(), sz = lz - facing.getOffsetZ();
                            if (sx < 0 || sx > 15 || sz < 0 || sz > 15 || sy < minY || sy > topY) continue;
                            if (chunk.getBlockState(probe.set(startX + sx, sy, startZ + sz)).isOf(Blocks.BUDDING_AMETHYST)) growingAmethyst++;
                            else grownAmethyst++;
                        } else if (block == Blocks.BUDDING_AMETHYST) {
                            if (y <= GEODE_MAX_Y && exposed(chunk, startX + lx, y, startZ + lz, probe, minY, topY)) exposedBudding++;
                        } else if (block == Blocks.KELP || block == Blocks.KELP_PLANT) {
                            int column = (lx << 4) | lz;
                            kelpBottom[column] = Math.min(kelpBottom[column], y);
                            if (y > kelpTop[column]) {
                                kelpTop[column] = y;
                                kelpTipAge[column] = block == Blocks.KELP ? state.get(Properties.AGE_25) : -1;
                            }
                            anyKelp = true;
                        } else if (block == Blocks.COCOA) {
                            cocoa++;
                            if (state.get(Properties.AGE_2) >= 2) ripeCocoa++;
                        } else if (block == Blocks.BEE_NEST) {
                            if (state.get(Properties.HONEY_LEVEL) >= 1) honeyNests++;
                        }
                    }
                }
            }
        }

        List<String> reasons = new ArrayList<>(6);

        if (playerBlocks.get()) {
            // Trial chambers, ancient cities and strongholds have redstone, storage, deepslate and torches of their own.
            if (artificial >= 1 && !trialChamber) reasons.add(artificial == 1 ? artificialName : artificial + " player-only blocks (" + artificialName + "...)");
            if (!ancientCity) {
                if (rotated >= sens.count(2)) reasons.add(rotated + " sideways deepslate");
                if (cobbled >= sens.count(6)) reasons.add(cobbled + " cobbled deepslate below Y 0");
                if (!trialChamber && storage >= sens.count(2)) reasons.add(storage + " storage blocks underground");
                if (!stronghold && deepTorches >= sens.count(2)) reasons.add(deepTorches + " torches below Y 0");
            }
        }

        if (growth.get()) {
            int kelp = anyKelp ? grownKelp(kelpBottom, kelpTop, kelpTipAge) : 0;
            if (kelp >= sens.count(2)) reasons.add(kelp + " kelp grown past natural height");
            if (honeyNests >= 1) reasons.add(honeyNests + (honeyNests == 1 ? " bee nest with honey" : " bee nests with honey"));
            if (ripeCocoa >= sens.count(8) && ripeCocoa == cocoa) reasons.add(ripeCocoa + " cocoa all ripe");
        }

        if (amethyst.get() && growingAmethyst == 0 && grownAmethyst >= sens.count(8)) {
            reasons.add(grownAmethyst + " grown amethyst");
        }

        String minedWhy = null;
        if (minedGeodes.get() && exposedBudding >= sens.count(10) && grownAmethyst == 0 && growingAmethyst == 0) {
            minedWhy = exposedBudding + " bare budding amethyst, every crystal broken off";
        }

        return new Result(reasons.isEmpty() ? null : String.join(", ", reasons), minedWhy);
    }

    /** Kelp columns that grew: taller than worldgen makes them, or with a tip older than worldgen gives. */
    private static int grownKelp(int[] bottom, int[] top, int[] tipAge) {
        int grown = 0;
        for (int c = 0; c < 256; c++) {
            if (top[c] == Integer.MIN_VALUE) continue;
            if (top[c] - bottom[c] + 1 > NATURAL_KELP_HEIGHT || tipAge[c] > NATURAL_KELP_AGE) grown++;
        }
        return grown;
    }

    private static boolean exposed(WorldChunk chunk, int x, int y, int z, BlockPos.Mutable probe, int minY, int topY) {
        for (Direction side : Direction.values()) {
            int nx = x + side.getOffsetX(), ny = y + side.getOffsetY(), nz = z + side.getOffsetZ();
            if (ny < minY || ny > topY || (nx >> 4) != chunk.getPos().x || (nz >> 4) != chunk.getPos().z) continue;
            if (chunk.getBlockState(probe.set(nx, ny, nz)).isAir()) return true;
        }
        return false;
    }

    private boolean isRelevant(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.DEEPSLATE
            || block == Blocks.COBBLED_DEEPSLATE
            || block == Blocks.REINFORCED_DEEPSLATE
            || block == Blocks.SCULK_SHRIEKER
            || block == Blocks.END_PORTAL_FRAME
            || block == Blocks.TRIAL_SPAWNER
            || block == Blocks.VAULT
            || block == Blocks.MOSSY_STONE_BRICKS
            || block == Blocks.CRACKED_STONE_BRICKS
            || block == Blocks.TORCH
            || block == Blocks.WALL_TORCH
            || block == Blocks.BUDDING_AMETHYST
            || block == Blocks.AMETHYST_CLUSTER
            || isBud(block)
            || block == Blocks.KELP
            || block == Blocks.KELP_PLANT
            || block == Blocks.COCOA
            || block == Blocks.BEE_NEST
            || isArtificial(block, false)
            || isStorage(block);
    }

    /** Blocks no structure or feature generates, so they were always placed by a player. */
    private static boolean isArtificial(Block block, boolean end) {
        if (block instanceof ShulkerBoxBlock || block == Blocks.ENDER_CHEST) return !end; // End cities have these
        return block == Blocks.HOPPER
            || block == Blocks.OBSERVER
            || block == Blocks.BEACON
            || block == Blocks.RESPAWN_ANCHOR
            || block == Blocks.LODESTONE
            || block == Blocks.NETHERITE_BLOCK
            || block == Blocks.BEEHIVE;
    }

    private static boolean isBud(Block block) {
        return block == Blocks.SMALL_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.LARGE_AMETHYST_BUD;
    }

    private static boolean isStorage(Block block) {
        return block == Blocks.BARREL
            || block == Blocks.FURNACE
            || block == Blocks.CRAFTING_TABLE;
    }

    // Rendering

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;
        double y = followPlayerY.get() ? Math.floor(mc.player.getY()) : 63;

        Color fill = new Color(color.get()).a(color.get().a / 5);
        Color minedFill = new Color(minedColor.get()).a(minedColor.get().a / 5);

        LongOpenHashSet sus = new LongOpenHashSet(flagged.keySet());
        sus.addAll(saved);
        for (long key : sus) drawChunk(event, key, y, fill, color.get());

        LongOpenHashSet geodes = new LongOpenHashSet(mined.keySet());
        geodes.addAll(savedMined);
        for (long key : geodes) drawChunk(event, key, y, minedFill, minedColor.get());
    }

    private void drawChunk(Render3DEvent event, long key, double y, Color side, Color line) {
        double x = ChunkPos.getPackedX(key) * 16.0;
        double z = ChunkPos.getPackedZ(key) * 16.0;
        event.renderer.box(x, y, z, x + 16, y, z + 16, side, line, shapeMode.get(), 0);
        if (tracers.get() && RenderUtils.center != null) {
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, x + 8, y, z + 8, line);
        }
    }

    @Override
    public String getInfoString() {
        LongOpenHashSet all = new LongOpenHashSet(flagged.keySet());
        all.addAll(saved);
        all.addAll(mined.keySet());
        all.addAll(savedMined);
        return all.isEmpty() ? null : String.valueOf(all.size());
    }
}
