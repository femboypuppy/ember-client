package meteordevelopment.meteorclient.systems.modules.donut;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.RandomSplitter;
import net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.OrePlacedFeatures;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.feature.util.PlacedFeatureIndexer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Shows where ancient debris generated, worked out from the world seed the way the game places it: every nether
 * chunk runs the same seeded random for its two debris features (a small and a large scatter), so the seed gives
 * every spot without seeing a single block. Spots the loaded terrain rules out (mined, or open to air, which debris
 * never generates next to) are dropped.
 *
 * A wrong seed draws convincing nonsense, so the seed is checked against the nether's bedrock, which is also
 * generated from it: the floor and roof patterns either match nearly block for block, or about half the time.
 */
public class NetheriteFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<String> seed = sgGeneral.add(new StringSetting.Builder()
        .name("seed")
        .description("The server's world seed. DonutSMP doesn't publish theirs: paste one here and the seed check says if it's right.")
        .defaultValue("")
        .placeholder("world seed")
        .wide()
        .onChanged(v -> reset())
        .build()
    );

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("How many chunks around you to show debris in.")
        .defaultValue(8)
        .range(1, 24)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Boolean> needMatch = sgGeneral.add(new BoolSetting.Builder()
        .name("only-if-seed-matches")
        .description("Only show debris once the seed check has confirmed the seed against this server's bedrock.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> predictedColor = sgRender.add(new ColorSetting.Builder()
        .name("predicted-color")
        .description("Where debris should be. Anti-xray can hide it, so it may look like netherrack until you dig.")
        .defaultValue(new SettingColor(255, 120, 60, 200))
        .build()
    );

    private final Setting<SettingColor> foundColor = sgRender.add(new ColorSetting.Builder()
        .name("found-color")
        .description("Debris you can already see in the world.")
        .defaultValue(new SettingColor(120, 255, 140, 230))
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Line to the nearest few debris spots.")
        .defaultValue(true)
        .build()
    );

    /** Decorator step of both debris features, and their index in it (worked out once from vanilla's registries). */
    private static final int STEP = GenerationStep.Feature.UNDERGROUND_DECORATION.ordinal();
    private static int largeIndex = -1, smallIndex = -1;

    private static final int MAX_TRACERS = 6;
    private static final int SEED_UNKNOWN = 0, SEED_GOOD = 1, SEED_BAD = 2;

    private final Long2ObjectOpenHashMap<int[]> predicted = new Long2ObjectOpenHashMap<>();
    private final List<BlockPos> shownPredicted = new ArrayList<>(), shownFound = new ArrayList<>();
    private long seedValue;
    private boolean seedSet;
    private int timer, seedState = SEED_UNKNOWN;
    private long ticks;
    private long bedrockMatch, bedrockTotal;
    private final Long2ObjectOpenHashMap<Boolean> bedrockChecked = new Long2ObjectOpenHashMap<>();
    /** World time each chunk arrived at. */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap arrived = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private String error;

    public NetheriteFinder() {
        super(Categories.Donut, "netherite-finder", "Shows ancient debris in the nether from the world seed, checked against the server's bedrock.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    private void reset() {
        predicted.clear();
        shownPredicted.clear();
        shownFound.clear();
        bedrockChecked.clear();
        bedrockMatch = bedrockTotal = 0;
        seedState = SEED_UNKNOWN;
        error = null;

        // Chunks already loaded count as arrived now.
        if (mc.world != null) {
            for (net.minecraft.world.chunk.Chunk chunk : meteordevelopment.meteorclient.utils.Utils.chunks()) {
                arrived.putIfAbsent(chunk.getPos().toLong(), ticks);
            }
        }

        String text = seed.get().trim();
        seedSet = !text.isEmpty();
        if (!seedSet) return;
        // Same as the game: a number is the seed, any other text is hashed.
        try {
            seedValue = Long.parseLong(text);
        } catch (NumberFormatException e) {
            seedValue = text.hashCode();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Its own clock: the world's time jumps when the server first sends it, right as chunks arrive.
        ticks++;
        if (mc.player == null || mc.world == null || ++timer < 10) return;
        timer = 0;
        shownPredicted.clear();
        shownFound.clear();
        if (!seedSet || mc.world.getRegistryKey() != World.NETHER) return;
        if (!indexesReady()) return;

        checkSeed();
        if (needMatch.get() && seedState != SEED_GOOD) return;

        ChunkPos here = mc.player.getChunkPos();
        int r = range.get();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = here.x + dx, cz = here.z + dz;
                int[] spots = predicted.computeIfAbsent(ChunkPos.toLong(cx, cz), k -> predict(seedValue, cx, cz));
                WorldChunk chunk = mc.world.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof WorldChunk c ? c : null;
                for (int i = 0; i < spots.length; i += 3) {
                    BlockPos pos = new BlockPos(spots[i], spots[i + 1], spots[i + 2]);
                    if (chunk == null) {
                        shownPredicted.add(pos);
                        continue;
                    }
                    BlockState state = mc.world.getBlockState(pos);
                    if (state.isOf(Blocks.ANCIENT_DEBRIS)) shownFound.add(pos);
                    // Debris never generates beside air, so open spots were mined or never got it. Anything solid can
                    // hide it: anti-xray sends hidden blocks as netherrack, or as fake ores.
                    else if (!state.isAir() && state.getFluidState().isEmpty() && !exposed(pos)) shownPredicted.add(pos);
                }
            }
        }
        if (predicted.size() > 20_000) predicted.clear();
    }

    @EventHandler
    private void onChunkData(meteordevelopment.meteorclient.events.world.ChunkDataEvent event) {
        if (mc.world == null) return;
        if (arrived.size() > 50_000) arrived.clear();
        arrived.put(event.chunk().getPos().toLong(), ticks);
    }

    private boolean exposed(BlockPos pos) {
        for (Direction side : Direction.values()) {
            if (mc.world.getBlockState(pos.offset(side)).isAir()) return true;
        }
        return false;
    }

    /**
     * The debris of one chunk, as the game generates it: ChunkGenerator.generateFeatures seeds a ChunkRandom per
     * chunk and feature, then the square, height range and scattered-ore steps draw from it in this order.
     *
     * @return x, y, z of every spot, before the terrain decides which ones actually took
     */
    static int[] predict(long worldSeed, int chunkX, int chunkZ) {
        ChunkRandom random = new ChunkRandom(new Xoroshiro128PlusPlusRandom(0));
        long population = random.setPopulationSeed(worldSeed, chunkX << 4, chunkZ << 4);
        List<Integer> out = new ArrayList<>();

        // ore_ancient_debris_large: 0-3 blocks, trapezoid height 8-24. ore_debris_small: 0-2 blocks, height 8-119.
        for (int f = 0; f < 2; f++) {
            boolean large = f == 0;
            random.setDecoratorSeed(population, large ? largeIndex : smallIndex, STEP);
            int x = random.nextInt(16) + (chunkX << 4);
            int z = random.nextInt(16) + (chunkZ << 4);
            int y = large ? 8 + MathHelper.nextBetween(random, 0, 8) + MathHelper.nextBetween(random, 0, 8) : MathHelper.nextBetween(random, 8, 119);

            int count = random.nextInt((large ? 3 : 2) + 1);
            for (int j = 0; j < count; j++) {
                int spread = Math.min(j, 7);
                int ox = Math.round((random.nextFloat() - random.nextFloat()) * spread);
                int oy = Math.round((random.nextFloat() - random.nextFloat()) * spread);
                int oz = Math.round((random.nextFloat() - random.nextFloat()) * spread);
                out.add(x + ox);
                out.add(y + oy);
                out.add(z + oz);
            }
        }

        int[] spots = new int[out.size()];
        for (int i = 0; i < spots.length; i++) spots[i] = out.get(i);
        return spots;
    }

    /** Where the two debris features sit in the nether's decoration order, from vanilla's own registries. */
    private boolean indexesReady() {
        if (largeIndex >= 0) return true;
        if (error != null) return false;
        try {
            RegistryWrapper.WrapperLookup lookup = BuiltinRegistries.createWrapperLookup();
            RegistryWrapper.Impl<Biome> biomes = lookup.getOrThrow(RegistryKeys.BIOME);
            RegistryWrapper.Impl<PlacedFeature> placed = lookup.getOrThrow(RegistryKeys.PLACED_FEATURE);
            // The nether biome source lists them in this order.
            List<RegistryEntry<Biome>> nether = Stream.of(BiomeKeys.NETHER_WASTES, BiomeKeys.SOUL_SAND_VALLEY, BiomeKeys.CRIMSON_FOREST,
                BiomeKeys.WARPED_FOREST, BiomeKeys.BASALT_DELTAS).<RegistryEntry<Biome>>map(biomes::getOrThrow).toList();
            List<PlacedFeatureIndexer.IndexedFeatures> steps = PlacedFeatureIndexer.collectIndexedFeatures(nether,
                biome -> biome.value().getGenerationSettings().getFeatures(), true);
            PlacedFeatureIndexer.IndexedFeatures step = steps.get(STEP);
            largeIndex = step.indexMapping().applyAsInt(placed.getOrThrow(OrePlacedFeatures.ORE_ANCIENT_DEBRIS_LARGE).value());
            smallIndex = step.indexMapping().applyAsInt(placed.getOrThrow(OrePlacedFeatures.ORE_DEBRIS_SMALL).value());
            MeteorClient.LOG.info("[Ember Netherite] Debris features at decoration step {}: large {}, small {}", STEP, largeIndex, smallIndex);
            return largeIndex >= 0 && smallIndex >= 0;
        } catch (Throwable t) {
            error = "couldn't read the game's world generation";
            MeteorClient.LOG.warn("[Ember Netherite] Couldn't index the debris features", t);
            return false;
        }
    }

    // Seed check: the nether's bedrock floor (Y 1-4) and roof (Y 123-126) come from the seed too.

    private void checkSeed() {
        if (seedState != SEED_UNKNOWN && bedrockTotal > 20_000) return;
        RandomSplitter base = new CheckedRandom(seedValue).nextSplitter();
        RandomSplitter floor = base.split(Identifier.ofVanilla("bedrock_floor")).nextSplitter();
        RandomSplitter roof = base.split(Identifier.ofVanilla("bedrock_roof")).nextSplitter();

        ChunkPos here = mc.player.getChunkPos();
        int checkedNow = 0;
        for (int dx = -4; dx <= 4 && checkedNow < 4; dx++) {
            for (int dz = -4; dz <= 4 && checkedNow < 4; dz++) {
                long key = ChunkPos.toLong(here.x + dx, here.z + dz);
                if (bedrockChecked.containsKey(key)) continue;
                if (!(mc.world.getChunk(here.x + dx, here.z + dz, ChunkStatus.FULL, false) instanceof WorldChunk chunk)) continue;
                // Chunks read the moment they arrive gave far worse matches in testing; let them settle first.
                long since = arrived.getOrDefault(key, -1L);
                if (since < 0 || ticks - since < 60) continue;
                bedrockChecked.put(key, Boolean.TRUE);
                checkedNow++;

                int x0 = chunk.getPos().getStartX(), z0 = chunk.getPos().getStartZ();
                BlockPos.Mutable pos = new BlockPos.Mutable();
                for (int x = x0; x < x0 + 16; x++) {
                    for (int z = z0; z < z0 + 16; z++) {
                        // Only columns the server really sent: the bottom layer is always bedrock.
                        if (!chunk.getBlockState(pos.set(x, 0, z)).isOf(Blocks.BEDROCK)) continue;
                        for (int y = 1; y <= 4; y++) {
                            boolean want = floor.split(x, y, z).nextFloat() < MathHelper.map(y, 0.0, 5.0, 1.0, 0.0);
                            count(want, chunk.getBlockState(pos.set(x, y, z)).isOf(Blocks.BEDROCK));
                        }
                        for (int y = 123; y <= 126; y++) {
                            boolean want = !(roof.split(x, y, z).nextFloat() < MathHelper.map(y, 122.0, 127.0, 1.0, 0.0));
                            count(want, chunk.getBlockState(pos.set(x, y, z)).isOf(Blocks.BEDROCK));
                        }
                    }
                }
            }
        }

        if (bedrockTotal < 6000) return;
        // The right seed matched 94-96% of the bedrock in testing, wrong ones 50-70%.
        double share = (double) bedrockMatch / bedrockTotal;
        int state = share >= 0.88 ? SEED_GOOD : share < 0.8 ? SEED_BAD : SEED_UNKNOWN;
        if (state != seedState && state != SEED_UNKNOWN) {
            String percent = Math.round(share * 1000) / 10.0 + "%";
            MeteorClient.LOG.info("[Ember Netherite] Seed {} vs bedrock: {} of {} blocks match ({})", seedValue, bedrockMatch, bedrockTotal, percent);
            if (state == SEED_GOOD) EmberNotificationsHud.push("Netherite finder", "Seed matches this nether's bedrock (" + percent + ")", EmberNotificationsHud.GOOD);
            else EmberNotificationsHud.push("Netherite finder", "Wrong seed: only " + percent + " of the bedrock matches", EmberNotificationsHud.BAD);
        }
        seedState = state;
    }

    private void count(boolean want, boolean is) {
        bedrockTotal++;
        if (want == is) bedrockMatch++;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;
        Color predictedLine = predictedColor.get(), foundLine = foundColor.get();
        Color predictedSide = new Color(predictedLine.r, predictedLine.g, predictedLine.b, predictedLine.a / 4);
        Color foundSide = new Color(foundLine.r, foundLine.g, foundLine.b, foundLine.a / 4);
        for (BlockPos pos : shownPredicted) event.renderer.box(pos, predictedSide, predictedLine, ShapeMode.Both, 0);
        for (BlockPos pos : shownFound) event.renderer.box(pos, foundSide, foundLine, ShapeMode.Both, 0);

        if (!tracers.get()) return;
        List<BlockPos> all = new ArrayList<>(shownFound);
        all.addAll(shownPredicted);
        BlockPos me = mc.player.getBlockPos();
        all.sort((a, b) -> Double.compare(a.getSquaredDistance(me), b.getSquaredDistance(me)));
        for (int i = 0; i < all.size() && i < MAX_TRACERS; i++) {
            BlockPos pos = all.get(i);
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                shownFound.contains(pos) ? foundLine : predictedLine);
        }
    }

    @Override
    public String getInfoString() {
        if (!seedSet) return "no seed";
        if (error != null) return "error";
        if (mc.world != null && mc.world.getRegistryKey() != World.NETHER) return "nether only";
        String check = switch (seedState) {
            case SEED_GOOD -> "seed ok";
            case SEED_BAD -> "wrong seed";
            default -> "checking";
        };
        return check + " " + (shownPredicted.size() + shownFound.size());
    }
}
