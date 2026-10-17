package meteordevelopment.meteorclient.systems.modules.donut;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
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
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Flags chunks showing signs of player activity or extended chunk loading, for finding bases.
 *
 * Signals include player-placed blocks (rotated/cobbled deepslate, underground storage) and
 * random-tick / entity-driven growth that only progresses while a player keeps the chunk loaded
 * (kelp, cave vines, vines, amethyst geodes, bamboo, cocoa pods, and honey-filled bee nests).
 */
public class SusChunkFinder extends Module {
    private static final String FIND_TYPE = "sus-chunk";

    private final SettingGroup sgBlocks = settings.createGroup("Block Signals");
    private final SettingGroup sgGrowth = settings.createGroup("Growth Signals");
    private final SettingGroup sgStorage = settings.createGroup("Saving");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // Block Signals (Deepslate & Storage)

    private final Setting<Boolean> rotatedDeepslate = sgBlocks.add(new BoolSetting.Builder()
        .name("rotated-deepslate")
        .description("Deepslate lying sideways. Terrain only generates it upright, so it was placed by a player.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> rotatedThreshold = sgBlocks.add(new IntSetting.Builder()
        .name("rotated-threshold")
        .description("Sideways deepslate blocks needed to flag a chunk.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 20)
        .visible(rotatedDeepslate::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> cobbledDeepslate = sgBlocks.add(new BoolSetting.Builder()
        .name("cobbled-deepslate")
        .description("Cobbled deepslate below Y=0, which only comes from mining or placing.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> cobbledThreshold = sgBlocks.add(new IntSetting.Builder()
        .name("cobbled-threshold")
        .description("Cobbled deepslate blocks needed to flag a chunk.")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 64)
        .visible(cobbledDeepslate::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> undergroundStorage = sgBlocks.add(new BoolSetting.Builder()
        .name("underground-storage")
        .description("Shulkers, barrels, furnaces, crafting tables or ender chests deep underground.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> storageThreshold = sgBlocks.add(new IntSetting.Builder()
        .name("storage-threshold")
        .description("Storage blocks needed to flag a chunk.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 20)
        .visible(undergroundStorage::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> storageMaxY = sgBlocks.add(new IntSetting.Builder()
        .name("storage-max-y")
        .description("Only count storage at or below this height, so surface villages are ignored.")
        .defaultValue(20)
        .sliderRange(-64, 64)
        .visible(undergroundStorage::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    // Growth Signals: Kelp

    private final Setting<Boolean> grownKelp = sgGrowth.add(new BoolSetting.Builder()
        .name("kelp")
        .description("Tall kelp mostly reaching the surface. Kelp only grows while the chunk is loaded.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> kelpMinColumns = sgGrowth.add(new IntSetting.Builder()
        .name("kelp-min-columns")
        .description("Tall kelp columns needed before the kelp signal is considered.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 64)
        .visible(grownKelp::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> kelpMinHeight = sgGrowth.add(new IntSetting.Builder()
        .name("kelp-min-height")
        .description("Minimum kelp column height. Filters out shallow water, where reaching the surface is trivial.")
        .defaultValue(8)
        .min(2)
        .sliderRange(2, 25)
        .visible(grownKelp::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Double> kelpSurfaceRatio = sgGrowth.add(new DoubleSetting.Builder()
        .name("kelp-surface-ratio")
        .description("Fraction of tall kelp columns that must reach the water surface.")
        .defaultValue(0.6)
        .min(0.1)
        .max(1)
        .sliderRange(0.1, 1)
        .visible(grownKelp::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    // Growth Signals: Cave Vines

    private final Setting<Boolean> caveVines = sgGrowth.add(new BoolSetting.Builder()
        .name("cave-vines")
        .description("Grown cave vines (glow berries) hanging in lush caves that have ticked while loaded.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> caveVinesMinLength = sgGrowth.add(new IntSetting.Builder()
        .name("cave-vines-min-length")
        .description("Minimum vertical length of a cave vine strand to count as grown.")
        .defaultValue(10)
        .min(2)
        .sliderRange(2, 26)
        .visible(caveVines::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> caveVinesMinColumns = sgGrowth.add(new IntSetting.Builder()
        .name("cave-vines-min-columns")
        .description("Grown cave vine strands needed to flag a chunk.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 32)
        .visible(caveVines::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> caveVinesMinAge = sgGrowth.add(new IntSetting.Builder()
        .name("cave-vines-min-age")
        .description("Minimum tip growth age (0-25) required to count a cave vine strand.")
        .defaultValue(22)
        .min(0)
        .max(25)
        .sliderRange(0, 25)
        .visible(caveVines::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    // Growth Signals: Vines

    private final Setting<Boolean> vines = sgGrowth.add(new BoolSetting.Builder()
        .name("vines")
        .description("Long hanging vines that have spread downward while the chunk was kept loaded.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> vinesMinLength = sgGrowth.add(new IntSetting.Builder()
        .name("vines-min-length")
        .description("Minimum vertical length of a continuous vine column to count as grown.")
        .defaultValue(12)
        .min(2)
        .sliderRange(2, 40)
        .visible(vines::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> vinesMinColumns = sgGrowth.add(new IntSetting.Builder()
        .name("vines-min-columns")
        .description("Number of long vine columns needed to flag a chunk.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 32)
        .visible(vines::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Double> vinesGroundRatio = sgGrowth.add(new DoubleSetting.Builder()
        .name("vines-ground-ratio")
        .description("Fraction of long vine columns that must reach a floor or water surface below.")
        .defaultValue(0.5)
        .min(0.0)
        .max(1.0)
        .sliderRange(0.0, 1.0)
        .visible(vines::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    // Growth Signals: Amethyst

    private final Setting<Boolean> amethyst = sgGrowth.add(new BoolSetting.Builder()
        .name("amethyst")
        .description("Amethyst geodes with grown clusters. Budding amethyst only grows buds into full clusters while loaded.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> amethystMinClusters = sgGrowth.add(new IntSetting.Builder()
        .name("amethyst-min-clusters")
        .description("Minimum grown amethyst clusters in the chunk to flag it.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 64)
        .visible(amethyst::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Double> amethystClusterRatio = sgGrowth.add(new DoubleSetting.Builder()
        .name("amethyst-cluster-ratio")
        .description("Minimum ratio of mature clusters to total amethyst buds + clusters in the geode.")
        .defaultValue(0.55)
        .min(0.0)
        .max(1.0)
        .sliderRange(0.0, 1.0)
        .visible(amethyst::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> amethystIncludeLargeBuds = sgGrowth.add(new BoolSetting.Builder()
        .name("amethyst-include-large-buds")
        .description("Count large amethyst buds as grown alongside full clusters.")
        .defaultValue(false)
        .visible(amethyst::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    // Growth Signals: Bamboo

    private final Setting<Boolean> bamboo = sgGrowth.add(new BoolSetting.Builder()
        .name("bamboo")
        .description("Grown bamboo stalks that have reached full height while the chunk was loaded.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> bambooMinHeight = sgGrowth.add(new IntSetting.Builder()
        .name("bamboo-min-height")
        .description("Minimum bamboo stalk height to count as grown.")
        .defaultValue(12)
        .min(1)
        .sliderRange(1, 16)
        .visible(bamboo::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> bambooMinColumns = sgGrowth.add(new IntSetting.Builder()
        .name("bamboo-min-columns")
        .description("Number of tall bamboo stalks needed to flag a chunk.")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 64)
        .visible(bamboo::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> bambooRequireMaxStage = sgGrowth.add(new BoolSetting.Builder()
        .name("bamboo-require-max-stage")
        .description("Only count bamboo stalks whose top block has reached growth stage 1 (stopped growing).")
        .defaultValue(false)
        .visible(bamboo::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    // Growth Signals: Cocoa

    private final Setting<Boolean> cocoa = sgGrowth.add(new BoolSetting.Builder()
        .name("cocoa")
        .description("Mature cocoa pods on jungle logs that have ripened while the chunk was loaded.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> cocoaMinPods = sgGrowth.add(new IntSetting.Builder()
        .name("cocoa-min-pods")
        .description("Minimum grown cocoa pods in the chunk needed to flag it.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 32)
        .visible(cocoa::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> cocoaMinAge = sgGrowth.add(new IntSetting.Builder()
        .name("cocoa-min-age")
        .description("Minimum cocoa growth age (0 = small, 1 = medium, 2 = fully ripe).")
        .defaultValue(2)
        .min(0)
        .max(2)
        .sliderRange(0, 2)
        .visible(cocoa::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Double> cocoaMatureRatio = sgGrowth.add(new DoubleSetting.Builder()
        .name("cocoa-mature-ratio")
        .description("Fraction of cocoa pods in the chunk that must be at or above the minimum age.")
        .defaultValue(0.75)
        .min(0.0)
        .max(1.0)
        .sliderRange(0.0, 1.0)
        .visible(cocoa::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    // Growth Signals: Bee Nest

    private final Setting<Boolean> beeNest = sgGrowth.add(new BoolSetting.Builder()
        .name("bee-nest")
        .description("Bee nests with honey or player-crafted beehives. Natural bee nests spawn at honey level 0 and only fill while loaded.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> beeNestMinCount = sgGrowth.add(new IntSetting.Builder()
        .name("bee-nest-min-count")
        .description("Number of matching bee nests or beehives needed to flag a chunk.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 10)
        .visible(beeNest::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> beeNestMinHoney = sgGrowth.add(new IntSetting.Builder()
        .name("bee-nest-min-honey")
        .description("Minimum honey level (0-5) on a natural bee nest to count (1+ means bees produced honey while loaded).")
        .defaultValue(1)
        .min(0)
        .max(5)
        .sliderRange(0, 5)
        .visible(beeNest::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> includeBeehives = sgGrowth.add(new BoolSetting.Builder()
        .name("include-beehives")
        .description("Always count player-crafted beehives regardless of honey level.")
        .defaultValue(true)
        .visible(beeNest::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    // Saving

    private final Setting<Boolean> saveFinds = sgStorage.add(new BoolSetting.Builder()
        .name("save-finds")
        .description("Remember flagged chunks for this server, so they still show after relogging.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoWaypoint = sgStorage.add(new BoolSetting.Builder()
        .name("auto-waypoint")
        .description("Add a waypoint for every newly flagged chunk.")
        .defaultValue(false)
        .build()
    );

    // Render

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How flagged chunks are drawn.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill color of flagged chunks.")
        .defaultValue(new SettingColor(170, 120, 255, 45))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline color of flagged chunks.")
        .defaultValue(new SettingColor(170, 120, 255, 220))
        .build()
    );

    private final Setting<Boolean> followPlayerY = sgRender.add(new BoolSetting.Builder()
        .name("follow-player-y")
        .description("Draw the chunk plane at your height so it stays visible at any depth.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> renderY = sgRender.add(new IntSetting.Builder()
        .name("render-y")
        .description("Height of the chunk plane.")
        .defaultValue(63)
        .sliderRange(-64, 320)
        .visible(() -> !followPlayerY.get())
        .build()
    );

    private final Setting<Boolean> chatNotify = sgRender.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Announce newly flagged chunks in chat.")
        .defaultValue(true)
        .build()
    );

    private final Long2ObjectMap<String> flagged = new Long2ObjectOpenHashMap<>();
    /** Chunks flagged in earlier sessions; drawn even when not loaded. */
    private final LongSet saved = new LongOpenHashSet();
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

    public SusChunkFinder() {
        super(Categories.Donut, "sus-chunk-finder", "Highlights chunks showing signs of player activity.");
    }

    @Override
    public void onActivate() {
        clear();
        if (mc.world == null) return;

        lastDimension = mc.world.getDimension();
        context = FindsStorage.context();

        synchronized (flagged) {
            for (FindsStorage.Find find : FindsStorage.get(context, FIND_TYPE)) {
                saved.add(ChunkPos.toLong(find.x >> 4, find.z >> 4));
            }
        }

        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk levelChunk) queueScan(levelChunk);
        }
    }

    @Override
    public void onDeactivate() {
        clear();
    }

    private void clear() {
        synchronized (flagged) {
            flagged.clear();
            saved.clear();
            announced.clear();
            pendingRescan.clear();
        }
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
        if (!isRelevant(event.newState) && !isRelevant(event.oldState)) return;

        // Batched: an explosion or a player mining can update hundreds of blocks in one chunk.
        synchronized (flagged) {
            pendingRescan.add(ChunkPos.toLong(event.pos));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;

        DimensionType dimension = mc.world.getDimension();
        if (dimension != lastDimension) {
            onActivate();
            return;
        }

        if (++rescanTimer < 20) return;
        rescanTimer = 0;

        long[] keys;
        synchronized (flagged) {
            if (pendingRescan.isEmpty()) return;
            keys = pendingRescan.toLongArray();
            pendingRescan.clear();
        }

        for (long key : keys) {
            Chunk chunk = mc.world.getChunk(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key), net.minecraft.world.chunk.ChunkStatus.FULL, false);
            if (chunk instanceof WorldChunk levelChunk) queueScan(levelChunk);
        }
    }

    private void queueScan(WorldChunk chunk) {
        FindsStorage.Context ctx = context;

        worker.submit(() -> {
            if (!isActive()) return;

            String reason = scan(chunk);
            long key = chunk.getPos().toLong();

            boolean isNewFind;
            synchronized (flagged) {
                if (reason == null) {
                    flagged.remove(key);
                    return;
                }

                flagged.put(key, reason);
                // Chunks remembered from earlier sessions were already announced back then.
                isNewFind = !saved.contains(key) && announced.add(key);
                if (isNewFind && saveFinds.get()) saved.add(key);
            }

            if (!isNewFind) return;

            int blockX = chunk.getPos().getStartX() + 8;
            int blockZ = chunk.getPos().getStartZ() + 8;
            BlockPos center = new BlockPos(blockX, 64, blockZ);

            if (saveFinds.get()) FindsStorage.add(ctx, FIND_TYPE, center, reason);

            mc.execute(() -> {
                if (autoWaypoint.get()) FindsStorage.waypoint("Sus chunk", center);
                if (chatNotify.get() && !EmberNotificationsHud.shouldInterceptChat()) info("Sus chunk at (highlight)%d, %d(default): %s", blockX, blockZ, reason);
                EmberNotificationsHud.push("Sus chunk found", blockX + ", " + blockZ + " — " + reason, EmberNotificationsHud.GOOD);
            });
        });
    }

    /** @return why the chunk is suspicious, or null if it looks natural */
    private String scan(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        int minY = chunk.getBottomY();
        int maxStorageY = storageMaxY.get();

        int rotated = 0;
        int cobbled = 0;
        int storage = 0;
        boolean ancientCity = false;

        // Kelp tracking per (x, z) column
        int[] kelpBottom = new int[256];
        int[] kelpTop = new int[256];
        Arrays.fill(kelpBottom, Integer.MAX_VALUE);
        Arrays.fill(kelpTop, Integer.MIN_VALUE);
        boolean anyKelp = false;

        // Cave vines tracking (tips and vertical strand runs)
        List<BlockPos> caveVineTips = new ArrayList<>();
        int caveVineTipAgeMatches = 0;

        // Vines tracking
        List<BlockPos> vinePositions = new ArrayList<>();

        // Amethyst tracking
        int buddingAmethyst = 0;
        int amethystClusters = 0;
        int amethystLargeBuds = 0;
        int amethystSmallMediumBuds = 0;

        // Bamboo tracking per (x, z) column
        int[] bambooBottom = new int[256];
        int[] bambooTop = new int[256];
        boolean[] bambooMaxStage = new boolean[256];
        Arrays.fill(bambooBottom, Integer.MAX_VALUE);
        Arrays.fill(bambooTop, Integer.MIN_VALUE);
        boolean anyBamboo = false;

        // Cocoa tracking
        int totalCocoa = 0;
        int matureCocoa = 0;
        int minCocoaAge = cocoaMinAge.get();

        // Bee nest / beehive tracking
        int matchedBeeNests = 0;
        int minHoney = beeNestMinHoney.get();
        boolean countBeehives = includeBeehives.get();

        int chunkStartX = chunk.getPos().getStartX();
        int chunkStartZ = chunk.getPos().getStartZ();

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
                        } else if (block == Blocks.KELP || block == Blocks.KELP_PLANT) {
                            int column = (lx << 4) | lz;
                            if (y < kelpBottom[column]) kelpBottom[column] = y;
                            if (y > kelpTop[column]) kelpTop[column] = y;
                            anyKelp = true;
                        } else if (block == Blocks.COBBLED_DEEPSLATE) {
                            if (y < 0) cobbled++;
                        } else if (block == Blocks.REINFORCED_DEEPSLATE || block == Blocks.SCULK_SHRIEKER) {
                            ancientCity = true;
                        } else if (y <= maxStorageY && isStorage(block)) {
                            storage++;
                        } else if (block == Blocks.CAVE_VINES) {
                            int age = state.getOrEmpty(Properties.AGE_25).orElse(0);
                            if (age >= caveVinesMinAge.get()) {
                                caveVineTipAgeMatches++;
                                caveVineTips.add(new BlockPos(chunkStartX + lx, y, chunkStartZ + lz));
                            }
                        } else if (block == Blocks.VINE) {
                            vinePositions.add(new BlockPos(chunkStartX + lx, y, chunkStartZ + lz));
                        } else if (block == Blocks.BUDDING_AMETHYST) {
                            buddingAmethyst++;
                        } else if (block == Blocks.AMETHYST_CLUSTER) {
                            amethystClusters++;
                        } else if (block == Blocks.LARGE_AMETHYST_BUD) {
                            amethystLargeBuds++;
                        } else if (block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.SMALL_AMETHYST_BUD) {
                            amethystSmallMediumBuds++;
                        } else if (block == Blocks.BAMBOO || block == Blocks.BAMBOO_SAPLING) {
                            int column = (lx << 4) | lz;
                            if (y < bambooBottom[column]) bambooBottom[column] = y;
                            if (y >= bambooTop[column]) {
                                bambooTop[column] = y;
                                bambooMaxStage[column] = state.getOrEmpty(Properties.STAGE).orElse(0) >= 1;
                            }
                            anyBamboo = true;
                        } else if (block == Blocks.COCOA) {
                            totalCocoa++;
                            int age = state.getOrEmpty(Properties.AGE_2).orElse(0);
                            if (age >= minCocoaAge) matureCocoa++;
                        } else if (block == Blocks.BEE_NEST) {
                            int honey = state.getOrEmpty(Properties.HONEY_LEVEL).orElse(0);
                            if (honey >= minHoney) matchedBeeNests++;
                        } else if (block == Blocks.BEEHIVE) {
                            if (countBeehives) matchedBeeNests++;
                        }
                    }
                }
            }
        }

        List<String> reasons = new ArrayList<>(6);

        // Ancient cities contain deepslate variants, cobbled deepslate and storage naturally.
        if (!ancientCity) {
            if (rotatedDeepslate.get() && rotated >= rotatedThreshold.get()) reasons.add(rotated + " rotated deepslate");
            if (cobbledDeepslate.get() && cobbled >= cobbledThreshold.get()) reasons.add(cobbled + " cobbled deepslate");
            if (undergroundStorage.get() && storage >= storageThreshold.get()) reasons.add(storage + " underground storage");
        }

        if (grownKelp.get() && anyKelp) {
            String kelpReason = checkKelp(chunk, kelpBottom, kelpTop);
            if (kelpReason != null) reasons.add(kelpReason);
        }

        if (caveVines.get() && !caveVineTips.isEmpty()) {
            String cvReason = checkCaveVines(chunk, caveVineTips, caveVineTipAgeMatches);
            if (cvReason != null) reasons.add(cvReason);
        }

        if (vines.get() && !vinePositions.isEmpty()) {
            String vineReason = checkVines(chunk, vinePositions);
            if (vineReason != null) reasons.add(vineReason);
        }

        if (amethyst.get()) {
            int grownCount = amethystClusters + (amethystIncludeLargeBuds.get() ? amethystLargeBuds : 0);
            int totalGrowth = amethystClusters + amethystLargeBuds + amethystSmallMediumBuds;
            if (grownCount >= amethystMinClusters.get() && totalGrowth > 0) {
                double ratio = (double) grownCount / totalGrowth;
                if (ratio >= amethystClusterRatio.get() || (buddingAmethyst == 0 && grownCount >= amethystMinClusters.get())) {
                    reasons.add(grownCount + "/" + totalGrowth + " grown amethyst");
                }
            }
        }

        if (bamboo.get() && anyBamboo) {
            String bambooReason = checkBamboo(bambooBottom, bambooTop, bambooMaxStage);
            if (bambooReason != null) reasons.add(bambooReason);
        }

        if (cocoa.get() && matureCocoa >= cocoaMinPods.get() && totalCocoa > 0) {
            double ratio = (double) matureCocoa / totalCocoa;
            if (ratio >= cocoaMatureRatio.get()) {
                reasons.add(matureCocoa + "/" + totalCocoa + " grown cocoa");
            }
        }

        if (beeNest.get() && matchedBeeNests >= beeNestMinCount.get()) {
            reasons.add(matchedBeeNests + (matchedBeeNests == 1 ? " active bee nest" : " active bee nests"));
        }

        return reasons.isEmpty() ? null : String.join(", ", reasons);
    }

    private String checkKelp(WorldChunk chunk, int[] kelpBottom, int[] kelpTop) {
        int chunkMinX = chunk.getPos().getStartX();
        int chunkMinZ = chunk.getPos().getStartZ();
        int topLimit = chunk.getBottomY() + chunk.getHeight() - 1;
        BlockPos.Mutable probe = new BlockPos.Mutable();

        int tallColumns = 0;
        int atSurface = 0;

        for (int column = 0; column < 256; column++) {
            if (kelpTop[column] == Integer.MIN_VALUE) continue;
            if (kelpTop[column] - kelpBottom[column] + 1 < kelpMinHeight.get()) continue;

            tallColumns++;

            int aboveY = kelpTop[column] + 1;
            if (aboveY > topLimit) continue;

            probe.set(chunkMinX + (column >> 4), aboveY, chunkMinZ + (column & 15));
            if (chunk.getBlockState(probe).getFluidState().isEmpty()) atSurface++;
        }

        if (tallColumns < kelpMinColumns.get()) return null;
        if ((double) atSurface / tallColumns < kelpSurfaceRatio.get()) return null;

        return atSurface + "/" + tallColumns + " kelp grown to surface";
    }

    private String checkCaveVines(WorldChunk chunk, List<BlockPos> tips, int ageMatches) {
        int topLimit = chunk.getBottomY() + chunk.getHeight() - 1;
        int minLen = caveVinesMinLength.get();
        BlockPos.Mutable probe = new BlockPos.Mutable();
        int longStrands = 0;

        for (BlockPos tip : tips) {
            int len = 1;
            int x = tip.getX();
            int z = tip.getZ();
            for (int y = tip.getY() + 1; y <= topLimit; y++) {
                probe.set(x, y, z);
                Block b = chunk.getBlockState(probe).getBlock();
                if (b == Blocks.CAVE_VINES_PLANT || b == Blocks.CAVE_VINES) {
                    len++;
                } else {
                    break;
                }
            }
            if (len >= minLen) longStrands++;
        }

        if (longStrands < caveVinesMinColumns.get()) return null;
        return longStrands + " grown cave vines";
    }

    private String checkVines(WorldChunk chunk, List<BlockPos> vinePositions) {
        int bottomLimit = chunk.getBottomY();
        int minLen = vinesMinLength.get();
        BlockPos.Mutable probe = new BlockPos.Mutable();

        int longColumns = 0;
        int groundedColumns = 0;

        for (BlockPos pos : vinePositions) {
            // Only start measuring from the bottom-most vine of a vertical run
            if (pos.getY() > bottomLimit) {
                probe.set(pos.getX(), pos.getY() - 1, pos.getZ());
                if (chunk.getBlockState(probe).getBlock() == Blocks.VINE) continue;
            }

            int len = 1;
            int topLimit = chunk.getBottomY() + chunk.getHeight() - 1;
            for (int y = pos.getY() + 1; y <= topLimit; y++) {
                probe.set(pos.getX(), y, pos.getZ());
                if (chunk.getBlockState(probe).getBlock() == Blocks.VINE) {
                    len++;
                } else {
                    break;
                }
            }

            if (len >= minLen) {
                longColumns++;
                if (pos.getY() > bottomLimit) {
                    probe.set(pos.getX(), pos.getY() - 1, pos.getZ());
                    BlockState below = chunk.getBlockState(probe);
                    if (!below.isAir() && below.getBlock() != Blocks.VINE) {
                        groundedColumns++;
                    }
                }
            }
        }

        if (longColumns < vinesMinColumns.get()) return null;
        if (vinesGroundRatio.get() > 0 && (double) groundedColumns / longColumns < vinesGroundRatio.get()) return null;

        return longColumns + " grown vines";
    }

    private String checkBamboo(int[] bambooBottom, int[] bambooTop, boolean[] bambooMaxStage) {
        int minHeight = bambooMinHeight.get();
        boolean requireStage = bambooRequireMaxStage.get();
        int matchedColumns = 0;

        for (int column = 0; column < 256; column++) {
            if (bambooTop[column] == Integer.MIN_VALUE) continue;
            int height = bambooTop[column] - bambooBottom[column] + 1;
            if (height < minHeight) continue;
            if (requireStage && !bambooMaxStage[column]) continue;
            matchedColumns++;
        }

        if (matchedColumns < bambooMinColumns.get()) return null;
        return matchedColumns + " grown bamboo";
    }

    private boolean isRelevant(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.DEEPSLATE
            || block == Blocks.KELP
            || block == Blocks.KELP_PLANT
            || block == Blocks.COBBLED_DEEPSLATE
            || block == Blocks.REINFORCED_DEEPSLATE
            || block == Blocks.SCULK_SHRIEKER
            || block == Blocks.CAVE_VINES
            || block == Blocks.CAVE_VINES_PLANT
            || block == Blocks.VINE
            || block == Blocks.BUDDING_AMETHYST
            || block == Blocks.SMALL_AMETHYST_BUD
            || block == Blocks.MEDIUM_AMETHYST_BUD
            || block == Blocks.LARGE_AMETHYST_BUD
            || block == Blocks.AMETHYST_CLUSTER
            || block == Blocks.BAMBOO
            || block == Blocks.BAMBOO_SAPLING
            || block == Blocks.COCOA
            || block == Blocks.BEE_NEST
            || block == Blocks.BEEHIVE
            || isStorage(block);
    }

    private static boolean isStorage(Block block) {
        return block instanceof ShulkerBoxBlock
            || block == Blocks.BARREL
            || block == Blocks.FURNACE
            || block == Blocks.CRAFTING_TABLE
            || block == Blocks.ENDER_CHEST;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        double y = followPlayerY.get() ? Math.floor(mc.player.getY()) : renderY.get();

        synchronized (flagged) {
            for (long key : flagged.keySet()) drawChunk(event, key, y);
            for (long key : saved) {
                if (!flagged.containsKey(key)) drawChunk(event, key, y);
            }
        }
    }

    private void drawChunk(Render3DEvent event, long key, double y) {
        double x = (double) ChunkPos.getPackedX(key) * 16;
        double z = (double) ChunkPos.getPackedZ(key) * 16;
        event.renderer.box(x, y, z, x + 16, y, z + 16, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    @Override
    public String getInfoString() {
        synchronized (flagged) {
            LongSet all = new LongOpenHashSet(flagged.keySet());
            all.addAll(saved);
            return all.isEmpty() ? null : String.valueOf(all.size());
        }
    }
}
