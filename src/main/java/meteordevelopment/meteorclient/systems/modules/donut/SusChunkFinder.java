package meteordevelopment.meteorclient.systems.modules.donut;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import meteordevelopment.meteorclient.MeteorClient;
import it.unimi.dsi.fastutil.longs.LongIterator;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.ChunkFindIndex;
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.EmberStrip;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import org.joml.Vector3d;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flags chunks showing signs of player activity or extended chunk loading, for finding bases.
 *
 * Signals include player-placed blocks (rotated/cobbled deepslate, packed storage, heavy redstone)
 * and random-tick growth that only progresses while a player keeps the chunk loaded (kelp, cave
 * vines, vines, amethyst, bamboo, cocoa and bee nests).
 *
 * On DonutSMP nearly every chunk players have passed through is griefed and littered with ender
 * chests, respawn anchors, crafting tables and furnaces, so single utility blocks never flag a chunk
 * on their own. They only add to the scores of a chunk something stronger already flagged.
 *
 * Which blocks count as natural comes from the 1.21.11 structure files: trial chambers ship barrels,
 * chests, dispensers, crafting tables, a few hoppers and redstone dust; ancient cities ship chests,
 * furnaces, lecterns, comparators, repeaters, sticky pistons and dust; igloos a brewing stand,
 * furnace, crafting table and cauldron. None of them contain observers, crafters, shulker boxes,
 * enchanting tables, beacons, undamaged anvils, beehives or respawn anchors.
 */
public class SusChunkFinder extends Module {
    private static final String FIND_TYPE = "sus-chunk";
    private static final Pattern SAVED_METRICS = Pattern.compile("\\s*\\[base (\\d+)%, fp (\\d+)%, grief (\\d+)%, y (-?\\d+)]\\s*$");
    private static final Pattern SAVED_GRIEF = Pattern.compile("\\s*\\{grief: ([^}]*)}");
    /** Marks a saved find made before anyone looked below the deepslate. */
    private static final String SAVED_DEEP_HIDDEN = "{deep unchecked}";

    /** Structure context a chunk shares with its neighbours, since these structures span several chunks. */
    private static final int CTX_TRIAL = 1, CTX_ANCIENT = 2, CTX_SPAWNER = 4;

    /** Trial chambers start between Y -40 and -20, but their upper rooms were seen reaching Y -3 in testing. */
    private static final int TRIAL_MIN_Y = -60, TRIAL_MAX_Y = 0;

    /** Storage blocks at most this many blocks apart on every axis belong to the same room. */
    private static final int CLUSTER_LINK = 3;
    /** Keeps the pairwise cluster pass cheap in shop districts packed with chests. */
    private static final int CLUSTER_MAX_POINTS = 2048;

    private static final Color EMERALD = new Color(34, 197, 94, 255);
    private static final Color AMBER = new Color(245, 158, 11, 255);
    private static final Color CORAL = new Color(239, 68, 68, 255);
    private static final Color SLATE = new Color(100, 116, 139, 255);
    private static final Color MUTED_GREEN = new Color(110, 190, 130, 255);

    public enum DotYMode {
        AnomalyY,
        PlayerEyeY,
        FixedY
    }

    private final SettingGroup sgHeight = settings.createGroup("Height");
    private final SettingGroup sgBlocks = settings.createGroup("Block Signals");
    private final SettingGroup sgGrowth = settings.createGroup("Growth Signals");
    private final SettingGroup sgIntelligent = settings.createGroup("Intelligent Chunks");
    private final SettingGroup sgStorage = settings.createGroup("Saving");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // Height

    private final Setting<Boolean> limitHeight = sgHeight.add(new BoolSetting.Builder()
        .name("limit-height")
        .description("Only detect built things (storage, redstone, farms, deepslate) between min-y and max-y, so a farm on the surface is ignored. Natural growth like kelp or amethyst is still checked at any height, since it shows where to dig.")
        .defaultValue(false)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> heightMin = sgHeight.add(new IntSetting.Builder()
        .name("min-y")
        .description("Lowest height that is checked.")
        .defaultValue(-64)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .visible(limitHeight::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> heightMax = sgHeight.add(new IntSetting.Builder()
        .name("max-y")
        .description("Highest height that is checked.")
        .defaultValue(10)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .visible(limitHeight::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

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
        .description("Chests, barrels and shulker boxes packed together into one storage room. Ender chests don't count.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> storageThreshold = sgBlocks.add(new IntSetting.Builder()
        .name("storage-threshold")
        .description("Storage blocks that must sit together (at most 3 blocks apart) to flag a chunk. A double chest counts as 2.")
        .defaultValue(10)
        .min(2)
        .sliderRange(2, 64)
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

    private final Setting<Boolean> lightLeak = sgBlocks.add(new BoolSetting.Builder()
        .name("light-leak")
        .description("Spots dug-out rooms from light data the server sends even for blocks you can't see. On DonutSMP natural terrain almost never triggers it (2 in 96,000 chunks in testing); on other servers natural caves can. Chunks you walk through are remembered and never flagged for this, so your own digging doesn't count.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> redstone = sgBlocks.add(new BoolSetting.Builder()
        .name("redstone")
        .description("Lots of redstone parts in one chunk (pistons, observers, repeaters, comparators, hoppers, droppers...), which marks a farm.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> redstoneThreshold = sgBlocks.add(new IntSetting.Builder()
        .name("redstone-threshold")
        .description("Redstone parts needed to flag a chunk. Three redstone dust count as one part.")
        .defaultValue(12)
        .min(3)
        .sliderRange(3, 64)
        .visible(redstone::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    // Growth Signals: Kelp

    private final Setting<Boolean> grownKelp = sgGrowth.add(new BoolSetting.Builder()
        .name("kelp")
        .description("Kelp that grew to the open surface, or a kelp farm under a glass ceiling. Kelp only grows while the chunk is loaded.")
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
        .description("Grown cave vines (glow berries). Natural lush caves are ignored unless the vines hang from one flat farm ceiling.")
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
        .description("Long hanging vines that spread downward while the chunk was kept loaded. Vines on natural trunks and cliffs are ignored.")
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
        .description("Amethyst geodes with grown clusters or harvesting contraptions. Untouched geodes get a high false positive score.")
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
        .description("Farmed bamboo: underground, planted on sand/moss/mud or over hoppers, or a field cut to one height. Natural bamboo jungles are ignored.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> bambooMinHeight = sgGrowth.add(new IntSetting.Builder()
        .name("bamboo-min-height")
        .description("Minimum stalk height for bamboo planted on sand, moss or mud to count.")
        .defaultValue(12)
        .min(1)
        .sliderRange(1, 16)
        .visible(bamboo::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> bambooMinColumns = sgGrowth.add(new IntSetting.Builder()
        .name("bamboo-min-columns")
        .description("Number of farmed bamboo stalks needed to flag a chunk.")
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
        .description("Ripe cocoa pods (6+ at once), or pods on stripped logs, underground, or on trunks with no canopy.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> cocoaMinPods = sgGrowth.add(new IntSetting.Builder()
        .name("cocoa-min-pods")
        .description("Minimum grown cocoa pods in the chunk needed to flag it. Never fewer than 6, since a third of natural pods start ripe.")
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
        .description("Honey-filled bee nests with backup (2+ full nests, a campfire/hopper/dispenser nearby, or underground) and crafted beehives.")
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

    // Intelligent Chunks

    private final Setting<Boolean> intelligentChunks = sgIntelligent.add(new BoolSetting.Builder()
        .name("intelligent-chunks")
        .description("Score every flagged chunk for base confidence, false positive and griefed probability, using the smaller clues around it.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> hideLikelyFalsePositives = sgIntelligent.add(new BoolSetting.Builder()
        .name("hide-likely-false-positives")
        .description("Hide chunks whose false positive score is above the limit while their base confidence is below the minimum.")
        .defaultValue(true)
        .visible(intelligentChunks::get)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> maxFalsePositiveProb = sgIntelligent.add(new IntSetting.Builder()
        .name("max-false-positive")
        .description("Highest false positive % a low-confidence chunk may have before it is hidden.")
        .defaultValue(65)
        .range(10, 100)
        .sliderRange(10, 100)
        .visible(() -> intelligentChunks.get() && hideLikelyFalsePositives.get())
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Integer> minBaseConfidence = sgIntelligent.add(new IntSetting.Builder()
        .name("min-base-confidence")
        .description("Chunks below this base confidence are hidden when their false positive score is above the limit.")
        .defaultValue(25)
        .range(5, 95)
        .sliderRange(5, 95)
        .visible(() -> intelligentChunks.get() && hideLikelyFalsePositives.get())
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> detectGriefedBases = sgIntelligent.add(new BoolSetting.Builder()
        .name("detect-griefed-bases")
        .description("Look for raid and grief damage: blast craters, orphaned hoppers, broken redstone, fluid grief, raid shafts and broken grinders.")
        .defaultValue(true)
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

    private final Setting<Integer> planeRange = sgRender.add(new IntSetting.Builder()
        .name("plane-range").description("Draw chunk planes within this range. Distant finds remain saved, counted and available to tracers.")
        .defaultValue(512).range(64, 8192).sliderRange(64, 2048).build());

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
        .description("Height of the chunk plane, and of the center dot in Fixed Y mode.")
        .defaultValue(63)
        .sliderRange(-64, 320)
        .visible(() -> !followPlayerY.get() || renderCenterDotVisibleFixed())
        .build()
    );

    private final Setting<Boolean> renderCenterDot = sgRender.add(new BoolSetting.Builder()
        .name("render-center-dot")
        .description("Draw a marker in the middle of every flagged chunk.")
        .defaultValue(true)
        .build()
    );

    private final Setting<DotYMode> dotYMode = sgRender.add(new EnumSetting.Builder<DotYMode>()
        .name("dot-y-mode")
        .description("Height of the center dot: where the suspicious blocks are, your eye level, or render-y.")
        .defaultValue(DotYMode.AnomalyY)
        .visible(renderCenterDot::get)
        .build()
    );

    private final Setting<Boolean> lookAtCard = sgRender.add(new BoolSetting.Builder()
        .name("look-at-card")
        .description("Show an inspection card when your crosshair is on a chunk's center dot.")
        .defaultValue(true)
        .visible(renderCenterDot::get)
        .build()
    );

    private final Setting<Double> lookAtAngle = sgRender.add(new DoubleSetting.Builder()
        .name("look-at-angle")
        .description("How close (in degrees) the crosshair must be to a center dot to open its card.")
        .defaultValue(6.0)
        .range(1.5, 15.0)
        .sliderRange(1.5, 15.0)
        .visible(() -> renderCenterDot.get() && lookAtCard.get())
        .build()
    );

    private final Setting<Double> cardScale = sgRender.add(new DoubleSetting.Builder()
        .name("card-scale")
        .description("Size of the inspection card.")
        .defaultValue(1.0)
        .range(0.5, 2.5)
        .sliderRange(0.5, 2.5)
        .visible(() -> renderCenterDot.get() && lookAtCard.get())
        .build()
    );

    private final Setting<Integer> dotRange = sgRender.add(new IntSetting.Builder()
        .name("dot-range")
        .description("Only draw center dots for chunks within this many blocks.")
        .defaultValue(384)
        .range(32, 2048)
        .sliderRange(64, 1024)
        .visible(renderCenterDot::get)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from your crosshair to every flagged chunk, including ones saved from earlier sessions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> tracerRange = sgRender.add(new IntSetting.Builder()
        .name("tracer-range")
        .description("Only draw tracers to chunks within this many blocks.")
        .defaultValue(4096)
        .range(64, 100000)
        .sliderRange(256, 20000)
        .visible(tracers::get)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Tracer color. High-confidence and griefed chunks keep their dot color.")
        .defaultValue(new SettingColor(170, 120, 255, 200))
        .visible(tracers::get)
        .build()
    );

    private final Setting<Boolean> chatNotify = sgRender.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Announce newly flagged chunks in chat.")
        .defaultValue(true)
        .build()
    );

    /** Guarded by itself, together with every collection below it. */
    private final Long2ObjectMap<FlaggedChunk> flagged = new Long2ObjectOpenHashMap<>();
    /** Chunks flagged in earlier sessions; drawn even when not loaded. */
    private final Long2ObjectMap<FlaggedChunk> saved = new Long2ObjectOpenHashMap<>();
    /** Saved chunks that were loaded and rescanned clean this session. */
    private final LongSet cleared = new LongOpenHashSet();
    private final LongSet announced = new LongOpenHashSet();
    private final LongSet pendingRescan = new LongOpenHashSet();
    private final Long2IntMap structureContext = new Long2IntOpenHashMap();
    private final ChunkFindIndex<FlaggedChunk> renderIndex = new ChunkFindIndex<>();
    private int indexedFilter;

    /** Bumped whenever results are thrown away, so scans queued before that point drop their result. */
    private final AtomicInteger scanGeneration = new AtomicInteger();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Ember-SusChunkFinder");
        thread.setDaemon(true);
        return thread;
    });

    private DimensionType lastDimension;
    private FindsStorage.Context context = new FindsStorage.Context("", "");
    private int rescanTimer;
    private boolean loggedScanError;
    private volatile boolean loggedHiddenDeep;

    // Render-thread state for the center dot and inspection card.
    private final EmberAnim.Clock clock = new EmberAnim.Clock();
    private final EmberAnim.Clock cardClock = new EmberAnim.Clock();
    private double smoothEyeY = Double.NaN;
    private FlaggedChunk hovered;
    private FlaggedChunk cardChunk;
    private float hoverAnim;
    private float shellAnim;

    // Light data, see onPacket. Kept across the relog (a rejoin) and only reset for another world.
    // The maps are guarded by flagged; the protection bookkeeping is main-thread only.

    /** Per chunk, light sections (bit = light section index) sent as a sky-light array of all zeros. */
    private final Long2IntMap lightZeroed = new Long2IntOpenHashMap();
    /** Per chunk, light sections seen lit or untouched at some point. */
    private final Long2IntMap lightSentNotDark = new Long2IntOpenHashMap();
    /** Chunks that had a light signal this session; protection doesn't take that back. */
    private final LongSet lightFlaggedOnce = new LongOpenHashSet();
    /** Chunks you have been around, never flagged for light so your own digging doesn't count. Saved per world. */
    private final LongSet dugProtected = new LongOpenHashSet();
    private final LongSet protectPending = new LongOpenHashSet();
    private long protectCenter = Long.MIN_VALUE;
    private boolean protectEntryPending, protectDirty;
    private int protectSaveTimer;
    private String lightPlace = "";
    private int lightStatChunks, lightStatDark, lightStatLogged, lightStatTimer;
    private volatile boolean loggedLightError;
    /** Chunks left to describe in the log this session, to learn how the server sends what it hides. */
    private final AtomicInteger blockDiagLeft = new AtomicInteger(), lightDiagLeft = new AtomicInteger();
    private static final int DIAG_CHUNKS = 3;
    /** Well above the deepslate, so whatever hiding there is applies. */
    private static final double DIAG_MIN_PLAYER_Y = 40;

    private static final int LIGHT_MAP_CAP = 250_000;
    /** Light sections reaching above this are surface, where sky light is ordinary. */
    private static final int LIGHT_CEILING_Y = 62;
    private static final int PROTECT_RADIUS = 2;

    public SusChunkFinder() {
        super(Categories.Donut, "sus-chunk-finder", "Highlights chunks showing signs of player activity.");
    }

    private boolean renderCenterDotVisibleFixed() {
        return renderCenterDot != null && dotYMode != null && renderCenterDot.get() && dotYMode.get() == DotYMode.FixedY;
    }

    @Override
    public void onActivate() {
        clear();
        if (mc.world == null) return;

        lastDimension = mc.world.getDimension();
        context = FindsStorage.context();
        selectLightPlace(context.world() + "|" + context.dimension());
        blockDiagLeft.set(DIAG_CHUNKS);
        lightDiagLeft.set(DIAG_CHUNKS);

        synchronized (flagged) {
            for (FindsStorage.Find find : FindsStorage.get(context, FIND_TYPE)) {
                long key = ChunkPos.toLong(find.x >> 4, find.z >> 4);
                if (!saved.containsKey(key)) {
                    var fc = FlaggedChunk.fromSave(find.x >> 4, find.z >> 4, find.note);
                    saved.put(key, fc);
                    if (savedVisible(fc)) renderIndex.put(fc.chunkX, fc.chunkZ, fc);
                }
            }
        }

        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk levelChunk) queueScan(levelChunk);
        }
    }

    @Override
    public void onDeactivate() {
        saveProtected();
        clear();
    }

    private void clear() {
        scanGeneration.incrementAndGet();
        synchronized (flagged) {
            flagged.clear();
            saved.clear();
            cleared.clear();
            announced.clear();
            pendingRescan.clear();
            structureContext.clear();
            renderIndex.clear();
            indexedFilter = filterState();
        }
        hovered = null;
        cardChunk = null;
        hoverAnim = 0;
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
        WorldChunk chunk = event.chunk();
        queueScan(chunk);
        if (mc.world == null) return;

        if (mc.player != null && mc.player.getY() > DIAG_MIN_PLAYER_Y && mc.world.getRegistryKey() == World.OVERWORLD
            && blockDiagLeft.getAndDecrement() > 0) {
            StringBuilder blocks = new StringBuilder();
            ChunkSection[] sections = chunk.getSectionArray();
            for (int i = 0; i < sections.length && chunk.getBottomY() + (i << 4) <= 0; i++) {
                if (blocks.length() > 0) blocks.append(" | ");
                blocks.append("Y ").append(chunk.getBottomY() + (i << 4)).append(": ").append(describe(sections[i]));
            }
            MeteorClient.LOG.info("[Ember SusChunk] Diagnostic, blocks of chunk {} with you at Y {}: {}", chunk.getPos(), (int) mc.player.getY(), blocks);
        }

        // Neighbours scanned before this chunk arrived only saw part of a storage room on the border.
        boolean[] sides = new boolean[9];
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!isStorage(be)) continue;
            int lx = be.getPos().getX() & 15, lz = be.getPos().getZ() & 15;
            // -1, 0 or 1: which side of the chunk the block is within linking distance of.
            int sx = lx < CLUSTER_LINK ? -1 : lx > 15 - CLUSTER_LINK ? 1 : 0;
            int sz = lz < CLUSTER_LINK ? -1 : lz > 15 - CLUSTER_LINK ? 1 : 0;
            if (sx != 0) sides[(sx + 1) * 3 + 1] = true;
            if (sz != 0) sides[3 + sz + 1] = true;
            if (sx != 0 && sz != 0) sides[(sx + 1) * 3 + sz + 1] = true;
        }

        ChunkPos pos = chunk.getPos();
        for (int i = 0; i < 9; i++) {
            if (sides[i] && mc.world.getChunk(pos.x + i / 3 - 1, pos.z + i % 3 - 1, ChunkStatus.FULL, false) instanceof WorldChunk neighbor
                && hasStorage(neighbor)) queueScan(neighbor);
        }
    }

    /** The four commonest blocks of a section with their counts, for the diagnostic log. */
    private static String describe(ChunkSection section) {
        if (section == null || section.isEmpty()) return "empty";

        Reference2IntOpenHashMap<Block> counts = new Reference2IntOpenHashMap<>();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) counts.addTo(section.getBlockState(x, y, z).getBlock(), 1);
            }
        }

        List<Block> top = new ArrayList<>(counts.keySet());
        top.sort((a, b) -> Integer.compare(counts.getInt(b), counts.getInt(a)));
        StringBuilder out = new StringBuilder().append(counts.size()).append(" kinds: ");
        for (int i = 0; i < Math.min(4, top.size()); i++) {
            if (i > 0) out.append(", ");
            out.append(Registries.BLOCK.getId(top.get(i)).getPath()).append(' ').append(counts.getInt(top.get(i)));
        }
        return out.toString();
    }

    /** Blocks that make up a storage room. Ender chests are left out: on DonutSMP they are everywhere. */
    private static boolean isStorage(BlockEntity be) {
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity;
    }

    private static boolean isStorageBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock;
    }

    private static boolean hasStorage(WorldChunk chunk) {
        for (BlockEntity be : chunk.getBlockEntities().values()) if (isStorage(be)) return true;
        return false;
    }

    /**
     * Storage positions in a chunk and the 8 around it, this chunk's first, so a storage room on a
     * chunk border is counted whole. Block entities are only safe to read on the main thread.
     */
    private long[] storageAround(WorldChunk chunk) {
        LongArrayList out = new LongArrayList();
        addStorage(chunk, out);
        if (mc.world == null) return out.toLongArray();

        ChunkPos pos = chunk.getPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && mc.world.getChunk(pos.x + dx, pos.z + dz, ChunkStatus.FULL, false) instanceof WorldChunk neighbor) {
                    addStorage(neighbor, out);
                }
            }
        }
        return out.toLongArray();
    }

    private static void addStorage(WorldChunk chunk, LongArrayList out) {
        for (BlockEntity be : chunk.getBlockEntities().values()) if (isStorage(be)) out.add(be.getPos().asLong());
    }

    // Light data
    //
    // The server hides the blocks below the deepslate while you are above it, but not their light.
    // A section nobody touched has no sky-light array at all and is sent as "empty". Once blocks
    // there change, the light engine gives it a real array, which deep underground is all zeros.
    // So an all-zero array below the surface means someone dug or built there, even with the blocks
    // themselves hidden.

    private static final int LIGHT_UNKNOWN = 0, LIGHT_ZERO = 1, LIGHT_LIT = 2;

    /** Network thread: ChunkData and LightUpdate packets, read before the client applies them. */
    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        // An exception here would end up in the network handler and disconnect you.
        try {
            readLight(event);
        } catch (Throwable t) {
            if (!loggedLightError) {
                loggedLightError = true;
                MeteorClient.LOG.warn("Sus chunk light read failed", t);
            }
        }
    }

    private void readLight(PacketEvent.Receive event) {
        LightData data;
        int chunkX, chunkZ;
        boolean fresh;
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            data = packet.getLightData();
            chunkX = packet.getChunkX();
            chunkZ = packet.getChunkZ();
            fresh = true;
        } else if (event.packet instanceof LightUpdateS2CPacket packet) {
            data = packet.getData();
            chunkX = packet.getChunkX();
            chunkZ = packet.getChunkZ();
            fresh = false;
        } else {
            return;
        }

        ClientWorld world = mc.world;
        if (data == null || world == null || world.getRegistryKey() != World.OVERWORLD) return;

        int count = world.countVerticalSections(), bottom = world.getBottomSectionCoord();
        BitSet inited = data.getInitedSky(), uninited = data.getUninitedSky();
        List<byte[]> nibbles = data.getSkyNibbles();

        int zeroSet = 0, zeroClear = 0, notDark = 0, next = 0;
        // Light sections run from one below the world to one above it, so index i is section bottom + i - 1.
        for (int i = 0; i < count + 2 && i < 32; i++) {
            boolean sent = inited.get(i);
            byte[] payload = sent && next < nibbles.size() ? nibbles.get(next) : null;
            if (sent) next++;
            if (i < 1 || i > count || !lightCandidate(i, bottom)) continue;

            if (sent) {
                int status = classifyLight(payload);
                if (status == LIGHT_ZERO) zeroSet |= 1 << i;
                else if (status == LIGHT_LIT) {
                    zeroClear |= 1 << i;
                    notDark |= 1 << i;
                }
            } else if (uninited.get(i)) {
                zeroClear |= 1 << i;
                notDark |= 1 << i;
            }
        }

        long key = ChunkPos.toLong(chunkX, chunkZ);
        // Below the deepslate the blocks are real anyway, and the light around you is your own digging.
        boolean below = mc.player != null && mc.player.getY() < 0;

        if (fresh && mc.player != null && mc.player.getY() > DIAG_MIN_PLAYER_Y && lightDiagLeft.getAndDecrement() > 0) {
            StringBuilder sky = new StringBuilder();
            int n = 0;
            for (int i = 0; i < count + 2 && i < 32; i++) {
                boolean sent = inited.get(i);
                byte[] payload = sent && n < nibbles.size() ? nibbles.get(n) : null;
                if (sent) n++;
                int y = (bottom + i - 1) * 16;
                if (i < 1 || y > 48) continue;
                if (sky.length() > 0) sky.append(", ");
                sky.append("Y ").append(y).append(": ").append(sent ? (classifyLight(payload) == LIGHT_ZERO ? "sent dark" : "sent lit") : uninited.get(i) ? "empty" : "absent");
            }
            MeteorClient.LOG.info("[Ember SusChunk] Diagnostic, sky light of chunk {}, {} with you at Y {}: {}", chunkX, chunkZ, (int) mc.player.getY(), sky);
        }

        synchronized (flagged) {
            // How often dark deep sections turn up at all, logged now and then (see onTick): in untouched
            // terrain this is the false positive rate of the light-leak signal.
            if (fresh && !below) {
                lightStatChunks++;
                if (zeroSet != 0) lightStatDark++;
            }

            if (notDark != 0) {
                if (lightSentNotDark.size() >= LIGHT_MAP_CAP) lightSentNotDark.clear();
                lightSentNotDark.put(key, lightSentNotDark.get(key) | notDark);
            }
            if (below) return;

            // Sections a packet leaves out keep what was known about them.
            int old = lightZeroed.get(key);
            int now = (old & ~zeroClear) | zeroSet;
            if (now == 0) lightZeroed.remove(key);
            else {
                if (lightZeroed.size() >= LIGHT_MAP_CAP) lightZeroed.clear();
                lightZeroed.put(key, now);
            }
            // A full chunk gets scanned when it is applied; a light update on its own needs a rescan.
            if (!fresh && now != old) pendingRescan.add(key);
        }
    }

    /** Every five minutes while chunks keep arriving, so a play session shows how noisy the signal would be. */
    private void logLightStats() {
        int chunks, dark;
        synchronized (flagged) {
            chunks = lightStatChunks;
            dark = lightStatDark;
        }
        if (chunks == lightStatLogged) return;
        lightStatLogged = chunks;
        MeteorClient.LOG.info("[Ember SusChunk] Light stats: {} of {} chunks loaded from above had dark deep sections ({}%); light-leak is {}",
            dark, chunks, chunks == 0 ? 0 : Math.round(dark * 1000.0 / chunks) / 10.0, lightLeak.get() ? "on" : "off");
    }

    private static boolean lightCandidate(int index, int bottomSection) {
        return (bottomSection + index - 1) * 16 + 15 < LIGHT_CEILING_Y;
    }

    private static int classifyLight(byte[] payload) {
        if (payload == null || payload.length != 2048) return LIGHT_UNKNOWN;
        for (byte b : payload) if (b != 0) return LIGHT_LIT;
        return LIGHT_ZERO;
    }

    /**
     * Whether a section and the six around it hold no air, as when the server sends hidden blocks as
     * solid fill. A dark array there is less certain, since nothing around it can be checked.
     */
    private boolean airlessAround(int chunkX, int sectionY, int chunkZ) {
        if (!airless(chunkX, sectionY, chunkZ)) return false;
        return airless(chunkX, sectionY + 1, chunkZ) && airless(chunkX, sectionY - 1, chunkZ)
            && airless(chunkX + 1, sectionY, chunkZ) && airless(chunkX - 1, sectionY, chunkZ)
            && airless(chunkX, sectionY, chunkZ + 1) && airless(chunkX, sectionY, chunkZ - 1);
    }

    private boolean airless(int chunkX, int sectionY, int chunkZ) {
        if (sectionY < mc.world.getBottomSectionCoord() || sectionY > mc.world.getTopSectionCoord()) return true;
        if (!(mc.world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) instanceof WorldChunk chunk)) return false;

        ChunkSection section = chunk.getSection(chunk.sectionCoordToIndex(sectionY));
        if (section.isEmpty()) return false;
        // The palette can keep an air entry no block uses any more, so only a hit there needs the full look.
        if (!section.hasAny(BlockState::isAir)) return true;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (section.getBlockState(x, y, z).isAir()) return false;
                }
            }
        }
        return true;
    }

    /** Main thread. {zeroed, turned dark, airless} section bits for a chunk's light signal, or null without one. */
    private int[] lightInputs(WorldChunk chunk) {
        if (!lightLeak.get() || mc.world == null || mc.world.getRegistryKey() != World.OVERWORLD) return null;

        long key = chunk.getPos().toLong();
        int zeroed, turnedDark;
        synchronized (flagged) {
            zeroed = lightZeroed.get(key);
            if (zeroed == 0 || (dugProtected.contains(key) && !lightFlaggedOnce.contains(key))) return null;
            turnedDark = zeroed & lightSentNotDark.get(key);
        }

        int airless = 0, bottom = mc.world.getBottomSectionCoord();
        for (int i = 1; i < 32; i++) {
            if ((zeroed & ~turnedDark & (1 << i)) != 0 && airlessAround(chunk.getPos().x, bottom + i - 1, chunk.getPos().z)) airless |= 1 << i;
        }
        return new int[] {zeroed, turnedDark, airless};
    }

    /**
     * Main thread. Once the chunk you are in has arrived without a light signal, the chunks around it
     * get protected as they arrive: whatever digging shows up there later is most likely your own.
     */
    private void updateProtection() {
        if (!lightLeak.get() || mc.player == null || mc.world.getRegistryKey() != World.OVERWORLD) return;

        long here = mc.player.getChunkPos().toLong();
        if (here != protectCenter) {
            protectCenter = here;
            protectEntryPending = true;
            protectPending.clear();
        }

        if (protectEntryPending) {
            if (!isLoaded(here)) return;
            protectEntryPending = false;

            boolean hereFlagged;
            synchronized (flagged) {
                hereFlagged = lightZeroed.get(here) != 0;
            }
            if (hereFlagged) return;

            int cx = ChunkPos.getPackedX(here), cz = ChunkPos.getPackedZ(here);
            for (int dx = -PROTECT_RADIUS; dx <= PROTECT_RADIUS; dx++) {
                for (int dz = -PROTECT_RADIUS; dz <= PROTECT_RADIUS; dz++) protectPending.add(ChunkPos.toLong(cx + dx, cz + dz));
            }
        }

        if (protectPending.isEmpty()) return;
        synchronized (flagged) {
            LongIterator it = protectPending.iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                if (!isLoaded(key)) continue;
                it.remove();
                if (lightZeroed.get(key) == 0 && dugProtected.add(key)) protectDirty = true;
            }
        }
    }

    private boolean isLoaded(long key) {
        return mc.world.getChunk(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key), ChunkStatus.FULL, false) != null;
    }

    /** Switches the light state to another world or dimension; the relog keeps the same place. */
    private void selectLightPlace(String place) {
        if (place.equals(lightPlace)) return;
        saveProtected();

        synchronized (flagged) {
            lightPlace = place;
            lightZeroed.clear();
            lightSentNotDark.clear();
            lightFlaggedOnce.clear();
            dugProtected.clear();
            protectDirty = false;

            File file = protectedFile(place);
            if (file != null && file.isFile()) {
                try {
                    ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file.toPath()));
                    while (buffer.remaining() >= Long.BYTES) dugProtected.add(buffer.getLong());
                } catch (IOException e) {
                    MeteorClient.LOG.warn("Couldn't read {}", file, e);
                }
            }
        }
        protectCenter = Long.MIN_VALUE;
        protectPending.clear();
    }

    /** Writes the protected chunks on the scan thread, so the game never waits for the disk. */
    private void saveProtected() {
        long[] keys;
        File file;
        synchronized (flagged) {
            if (!protectDirty) return;
            protectDirty = false;
            keys = dugProtected.toLongArray();
            file = protectedFile(lightPlace);
        }
        if (file == null) return;

        worker.submit(() -> {
            try {
                ByteBuffer buffer = ByteBuffer.allocate(keys.length * Long.BYTES);
                for (long key : keys) buffer.putLong(key);
                Files.createDirectories(file.getParentFile().toPath());
                Files.write(file.toPath(), buffer.array());
            } catch (IOException e) {
                MeteorClient.LOG.warn("Couldn't save {}", file, e);
            }
        });
    }

    private static File protectedFile(String place) {
        if (place.isEmpty() || place.startsWith("|")) return null;
        return new File(new File(MeteorClient.FOLDER, "ember-finds"), place.replaceAll("[^A-Za-z0-9._-]", "_") + ".dug-protected");
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!isUpdateRelevant(event.newState) && !isUpdateRelevant(event.oldState)) return;

        // Batched: an explosion or a player mining can update hundreds of blocks in one chunk.
        synchronized (flagged) {
            pendingRescan.add(ChunkPos.toLong(event.pos));

            // A storage room can reach over the border, so the chunk next door counts it too.
            if (isStorageBlock(event.newState) || isStorageBlock(event.oldState)) {
                int lx = event.pos.getX() & 15, lz = event.pos.getZ() & 15;
                int sx = lx < CLUSTER_LINK ? -1 : lx > 15 - CLUSTER_LINK ? 1 : 0;
                int sz = lz < CLUSTER_LINK ? -1 : lz > 15 - CLUSTER_LINK ? 1 : 0;
                int cx = event.pos.getX() >> 4, cz = event.pos.getZ() >> 4;
                if (sx != 0) pendingRescan.add(ChunkPos.toLong(cx + sx, cz));
                if (sz != 0) pendingRescan.add(ChunkPos.toLong(cx, cz + sz));
                if (sx != 0 && sz != 0) pendingRescan.add(ChunkPos.toLong(cx + sx, cz + sz));
            }
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

        int filter = filterState();
        if (filter != indexedFilter) {
            synchronized (flagged) {
                renderIndex.clear();
                for (var entry : saved.long2ObjectEntrySet()) {
                    if (!flagged.containsKey(entry.getLongKey()) && !cleared.contains(entry.getLongKey()) && savedVisible(entry.getValue())) {
                        var fc = entry.getValue(); renderIndex.put(fc.chunkX, fc.chunkZ, fc);
                    }
                }
                for (var fc : flagged.values()) renderIndex.put(fc.chunkX, fc.chunkZ, fc);
                indexedFilter = filter;
            }
        }

        updateProtection();
        if (++protectSaveTimer >= 200) {
            protectSaveTimer = 0;
            saveProtected();
        }
        if (++lightStatTimer >= 6000) {
            lightStatTimer = 0;
            logLightStats();
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
            Chunk chunk = mc.world.getChunk(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key), ChunkStatus.FULL, false);
            if (chunk instanceof WorldChunk levelChunk) queueScan(levelChunk);
        }
    }

    private void queueScan(WorldChunk chunk) {
        FindsStorage.Context ctx = context;
        int generation = scanGeneration.get();
        boolean nether = mc.world != null && mc.world.getRegistryKey() == World.NETHER;
        boolean end = mc.world != null && mc.world.getRegistryKey() == World.END;
        long[] storage = storageAround(chunk);
        int[] light = lightInputs(chunk);

        worker.submit(() -> {
            if (!isActive() || generation != scanGeneration.get()) return;

            long key = chunk.getPos().toLong();
            int neighborCtx;
            synchronized (flagged) {
                neighborCtx = neighborContext(key);
            }

            Scan scan;
            FlaggedChunk result;
            try {
                scan = new Scan(chunk, nether, end, storage, light);
                result = scan.run(neighborCtx);
            } catch (Throwable t) {
                if (!loggedScanError) {
                    loggedScanError = true;
                    MeteorClient.LOG.warn("Sus chunk scan failed for {}", chunk.getPos(), t);
                }
                return;
            }

            if (scan.hiddenSections > 0 && !loggedHiddenDeep) {
                loggedHiddenDeep = true;
                MeteorClient.LOG.info("[Ember SusChunk] Chunk {} arrived with {} sections below Y 0 hidden ({}); judging such chunks from above the deepslate only",
                    chunk.getPos(), scan.hiddenSections, scan.blankLook);
            }

            boolean isNewFind = false, firstFullLook;
            String checkedNote;
            synchronized (flagged) {
                if (generation != scanGeneration.get()) return;

                // Neighbours scanned before this chunk revealed its structure may have been flagged on
                // the structure's natural blocks, so look at them again.
                int oldCtx = structureContext.get(key);
                // With the deep part hidden, a structure seen in a full view earlier is still there.
                if (scan.hiddenSections > 0) scan.ownCtx |= oldCtx;
                if (scan.ownCtx != oldCtx) {
                    if (scan.ownCtx == 0) structureContext.remove(key);
                    else structureContext.put(key, scan.ownCtx);

                    if ((scan.ownCtx & ~oldCtx) != 0) {
                        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                long n = ChunkPos.toLong(cx + dx, cz + dz);
                                if (n != key && flagged.containsKey(n)) pendingRescan.add(n);
                            }
                        }
                    }
                }

                // Sent with the deep part hidden (you are above the deepslate), the chunk only shows what
                // is above it. That can't overrule a full view from after a relog, and "nothing found"
                // doesn't clear a saved find, which may well be down there.
                FlaggedChunk old = flagged.get(key);
                boolean partial = scan.hiddenSections > 0;
                if (partial && old != null && !old.deepHidden) return;

                // The first full view of a find that was only seen from above: the relog worked.
                FlaggedChunk prior = old != null ? old : saved.get(key);
                firstFullLook = !partial && prior != null && prior.deepHidden;

                if (result == null) {
                    flagged.remove(key);
                    if (!partial) cleared.add(key);
                    if (firstFullLook && saved.containsKey(key)) saved.put(key, prior.checked());
                } else {
                    flagged.put(key, result);
                    cleared.remove(key);
                    if (scan.lightLabel != null && result.reasons.contains(scan.lightLabel) && lightFlaggedOnce.add(key)) {
                        MeteorClient.LOG.info("[Ember SusChunk] Light leak at {}: {} (dark sections {}, went dark {}, in hidden fill {}, deep part hidden {})",
                            chunk.getPos(), scan.lightLabel, Integer.toBinaryString(scan.light[0]), Integer.toBinaryString(scan.light[1]),
                            Integer.toBinaryString(scan.light[2]), scan.hiddenSections > 0);
                    }
                    // Chunks remembered from earlier sessions were already announced back then.
                    isNewFind = !saved.containsKey(key) && announced.add(key);
                    if (isNewFind ? saveFinds.get() : firstFullLook && saved.containsKey(key)) saved.put(key, result);
                }
                checkedNote = firstFullLook ? (result != null ? result : prior.checked()).toNote() : null;
                refreshIndexedFind(key);
            }

            int blockX = chunk.getPos().getStartX() + 8;
            int blockZ = chunk.getPos().getStartZ() + 8;
            BlockPos center = new BlockPos(blockX, 64, blockZ);

            if (firstFullLook) {
                if (saveFinds.get()) FindsStorage.update(ctx, FIND_TYPE, center, checkedNote);
                String found = result == null ? "nothing down there" : result.reason;
                Color checkedTone = result == null ? EmberNotificationsHud.WARN : EmberNotificationsHud.GOOD;
                mc.execute(() -> {
                    if (chatNotify.get() && !EmberNotificationsHud.shouldInterceptChat()) info("Sus chunk checked at (highlight)%d, %d(default): %s", blockX, blockZ, found);
                    EmberNotificationsHud.push("Sus chunk checked", blockX + ", " + blockZ + " \u2014 " + found, checkedTone);
                });
                return;
            }

            if (!isNewFind) return;

            if (saveFinds.get()) FindsStorage.add(ctx, FIND_TYPE, center, result.toNote());

            boolean scored = intelligentChunks.get();
            String summary = (scored ? result.reason + " (base " + result.baseConfidence + "%)" : result.reason)
                + (result.deepHidden ? " - dig below Y 0 and relog to see the rest" : "");
            Color tone = result.griefedProb >= 60 ? EmberNotificationsHud.WARN : EmberNotificationsHud.GOOD;

            mc.execute(() -> {
                if (autoWaypoint.get()) FindsStorage.waypoint("Sus chunk", center);
                if (chatNotify.get() && !EmberNotificationsHud.shouldInterceptChat()) info("Sus chunk at (highlight)%d, %d(default): %s", blockX, blockZ, summary);
                EmberNotificationsHud.push(result.griefedProb >= 60 ? "Griefed base found" : "Sus chunk found", blockX + ", " + blockZ + " \u2014 " + summary, tone);
            });
        });
    }

    /** Context bits of the 8 surrounding chunks. Caller holds the lock. */
    private int neighborContext(long key) {
        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
        int bits = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                bits |= structureContext.get(ChunkPos.toLong(cx + dx, cz + dz));
            }
        }
        return bits;
    }

    /** Wording of the reasons that can still flag a chunk on their own. */
    private static final String[] MAIN_REASONS = {"rotated deepslate", "cobbled deepslate", "in one room", "redstone parts (",
        "spawner grinder", "kelp", "vines", "amethyst", "bamboo", "cocoa", "bee nest", "(light data)"};

    /** Whether a saved find from an earlier session passes the current filters. */
    private boolean savedVisible(FlaggedChunk fc) {
        // Finds saved for ender chests, anchors, loose hoppers or old storage counts aren't finds any more.
        // They stay in the finds file, just hidden.
        boolean main = false;
        for (String reason : fc.reasons) {
            for (String word : MAIN_REASONS) {
                if (reason.contains(word)) {
                    main = true;
                    break;
                }
            }
            if (main) break;
        }
        if (!main) return false;

        // Only saves that recorded where the find was can be held to the height range, and natural
        // growth ("... grown ...") is exempt from it, as in the scan.
        if (limitHeight.get() && fc.measured && fc.reasons.stream().noneMatch(r -> r.contains("grown"))) {
            int lo = Math.min(heightMin.get(), heightMax.get()), hi = Math.max(heightMin.get(), heightMax.get());
            if (fc.focusY < lo || fc.focusY > hi) return false;
        }

        if (!intelligentChunks.get() || !hideLikelyFalsePositives.get()) return true;
        return !(fc.falsePositiveProb > maxFalsePositiveProb.get() && fc.baseConfidence < minBaseConfidence.get());
    }

    private int filterState() {
        int state = 31 * (31 * (31 * Boolean.hashCode(intelligentChunks.get()) + Boolean.hashCode(hideLikelyFalsePositives.get()))
            + maxFalsePositiveProb.get()) + minBaseConfidence.get();
        state = 31 * state + Boolean.hashCode(limitHeight.get());
        state = 31 * state + heightMin.get();
        return 31 * state + heightMax.get();
    }

    /** Called under the result lock. Partial scans keep an eligible saved fallback visible. */
    private void refreshIndexedFind(long key) {
        FlaggedChunk fc = flagged.get(key);
        if (fc == null && !cleared.contains(key)) {
            fc = saved.get(key);
            if (fc != null && !savedVisible(fc)) fc = null;
        }
        if (fc == null) renderIndex.remove(key);
        else renderIndex.put(fc.chunkX, fc.chunkZ, fc);
    }

    // Block kinds. Looked up once per block through an identity map, which keeps a full chunk pass cheap.

    private static final int K_DEEPSLATE = 1, K_COBBLED_DEEPSLATE = 2, K_KELP = 3, K_KELP_PLANT = 4,
        K_CAVE_VINES = 5, K_CAVE_VINES_PLANT = 6, K_VINE = 7, K_BUDDING = 8, K_AM_CLUSTER = 9, K_AM_LARGE = 10,
        K_AM_SMALL = 11, K_BAMBOO = 12, K_COCOA = 13, K_BEE_NEST = 14, K_BEEHIVE = 15, K_REINFORCED = 16,
        K_ANCIENT_BUILD = 17, K_SCULK = 18, K_TRIAL_DEF = 19, K_TRIAL_BUILD = 20, K_SHULKER = 21,
        K_ENDER_CHEST = 22, K_CHEST = 23, K_TRAPPED_CHEST = 24, K_BARREL = 25, K_FURNACE = 26, K_CRAFTING = 27,
        K_BREWING = 28, K_LECTERN = 29, K_CAULDRON = 30, K_BEACON = 31, K_CONDUIT = 32, K_ENCHANTING = 33,
        K_ANVIL = 34, K_DAMAGED_ANVIL = 35, K_CRAFTER = 36, K_HOPPER = 37, K_PISTON = 38, K_OBSERVER = 39,
        K_REDSTONE_COMP = 40, K_REDSTONE_WIRE = 41, K_DISPENSER = 42, K_PORTAL = 43, K_ANCHOR = 44,
        K_PLAYER_ONLY = 45, K_SPAWNER = 46, K_SOUL_SAND = 47, K_TINTED_GLASS = 48, K_SLAB = 49, K_SIGN = 50,
        K_OBSIDIAN = 51, K_NETHERRACK = 52, K_WATER = 53, K_LAVA = 54, K_TORCH = 55, K_LAMP = 56, K_GLOW = 57,
        K_LADDER = 58, K_SLIME = 59, K_CAMPFIRE = 60, K_HONEYCOMB = 61, K_CROP = 62, K_RAIL = 63, K_COBWEB = 64,
        K_MS_FENCE = 65, K_COBBLESTONE = 66, K_MOSSY_COBBLE = 67, K_STONE_BRICKS = 68, K_AGED_BRICKS = 69,
        K_IRON_BARS = 70, K_RED_CARPET = 71, K_END_FRAME = 72, K_INFESTED = 73, K_TRAIL = 74, K_LUSH = 75,
        K_JUNGLE = 76, K_NETHER_STRUCT = 77, K_PURPUR = 78, K_TNT = 79, K_BELL = 80, K_JOBSITE = 81, K_BED = 82,
        K_SUSPICIOUS = 83, K_REDSTONE_MISC = 84;
    private static final int KIND_COUNT = 85;
    private static final int UNLIMITED = 1 << 20;

    private static final Reference2ByteOpenHashMap<Block> KINDS = new Reference2ByteOpenHashMap<>();

    /** Kinds kept with their position for the second pass. */
    private static final boolean[] NOTABLE = new boolean[KIND_COUNT];
    /** Kinds that mark where a player actually built, for the grief and shaft checks. */
    private static final boolean[] PLAYER_BLOCK = new boolean[KIND_COUNT];
    private static final boolean[] AUTOMATION = new boolean[KIND_COUNT];

    // What each structure generates on its own, from the 1.21.11 structure files.
    private static final boolean[] TRIAL_NATURAL = new boolean[KIND_COUNT];
    private static final boolean[] ANCIENT_NATURAL = new boolean[KIND_COUNT];
    private static final boolean[] STRONGHOLD_NATURAL = new boolean[KIND_COUNT];
    private static final boolean[] NETHER_NATURAL = new boolean[KIND_COUNT];
    private static final boolean[] END_NATURAL = new boolean[KIND_COUNT];
    private static final boolean[] VILLAGE_NATURAL = new boolean[KIND_COUNT];
    private static final boolean[] TRAIL_NATURAL = new boolean[KIND_COUNT];
    private static final boolean[] TEMPLE_NATURAL = new boolean[KIND_COUNT];
    private static final boolean[] IGLOO_NATURAL = new boolean[KIND_COUNT];

    static {
        KINDS.defaultReturnValue((byte) 0);

        for (Block block : Registries.BLOCK) {
            if (block instanceof ShulkerBoxBlock) kind(K_SHULKER, block);
            else if (block instanceof ChestBlock) kind(K_CHEST, block);
            else if (block instanceof SlabBlock) kind(K_SLAB, block);
            else if (block instanceof AbstractSignBlock) kind(K_SIGN, block);
            else if (block instanceof InfestedBlock) kind(K_INFESTED, block);
            else if (block instanceof BedBlock) kind(K_BED, block);
            else if (block instanceof AbstractCauldronBlock) kind(K_CAULDRON, block);
        }

        kind(K_DEEPSLATE, Blocks.DEEPSLATE);
        kind(K_COBBLED_DEEPSLATE, Blocks.COBBLED_DEEPSLATE);
        kind(K_KELP, Blocks.KELP);
        kind(K_KELP_PLANT, Blocks.KELP_PLANT);
        kind(K_CAVE_VINES, Blocks.CAVE_VINES);
        kind(K_CAVE_VINES_PLANT, Blocks.CAVE_VINES_PLANT);
        kind(K_VINE, Blocks.VINE);
        kind(K_BUDDING, Blocks.BUDDING_AMETHYST);
        kind(K_AM_CLUSTER, Blocks.AMETHYST_CLUSTER);
        kind(K_AM_LARGE, Blocks.LARGE_AMETHYST_BUD);
        kind(K_AM_SMALL, Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD);
        kind(K_BAMBOO, Blocks.BAMBOO, Blocks.BAMBOO_SAPLING);
        kind(K_COCOA, Blocks.COCOA);
        kind(K_BEE_NEST, Blocks.BEE_NEST);
        kind(K_BEEHIVE, Blocks.BEEHIVE);
        kind(K_REINFORCED, Blocks.REINFORCED_DEEPSLATE, Blocks.SCULK_SHRIEKER);
        kind(K_ANCIENT_BUILD, Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS, Blocks.DEEPSLATE_TILES,
            Blocks.CRACKED_DEEPSLATE_TILES, Blocks.CHISELED_DEEPSLATE, Blocks.SOUL_LANTERN, Blocks.GRAY_WOOL);
        kind(K_SCULK, Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.SCULK_CATALYST, Blocks.SCULK_SENSOR);
        kind(K_TRIAL_DEF, Blocks.VAULT, Blocks.TRIAL_SPAWNER);
        kind(K_TRIAL_BUILD, Blocks.TUFF_BRICKS, Blocks.CHISELED_TUFF, Blocks.CHISELED_TUFF_BRICKS, Blocks.POLISHED_TUFF,
            Blocks.WAXED_COPPER_BLOCK, Blocks.WAXED_CUT_COPPER, Blocks.WAXED_OXIDIZED_CUT_COPPER, Blocks.WAXED_OXIDIZED_COPPER,
            Blocks.WAXED_COPPER_GRATE, Blocks.WAXED_OXIDIZED_COPPER_GRATE, Blocks.WAXED_COPPER_BULB, Blocks.WAXED_OXIDIZED_COPPER_BULB);
        kind(K_ENDER_CHEST, Blocks.ENDER_CHEST);
        kind(K_TRAPPED_CHEST, Blocks.TRAPPED_CHEST);
        kind(K_BARREL, Blocks.BARREL);
        kind(K_FURNACE, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER);
        kind(K_CRAFTING, Blocks.CRAFTING_TABLE);
        kind(K_BREWING, Blocks.BREWING_STAND);
        kind(K_LECTERN, Blocks.LECTERN);
        kind(K_BEACON, Blocks.BEACON);
        kind(K_CONDUIT, Blocks.CONDUIT);
        kind(K_ENCHANTING, Blocks.ENCHANTING_TABLE);
        kind(K_ANVIL, Blocks.ANVIL, Blocks.CHIPPED_ANVIL);
        kind(K_DAMAGED_ANVIL, Blocks.DAMAGED_ANVIL);
        kind(K_CRAFTER, Blocks.CRAFTER);
        kind(K_HOPPER, Blocks.HOPPER);
        kind(K_PISTON, Blocks.PISTON, Blocks.STICKY_PISTON);
        kind(K_OBSERVER, Blocks.OBSERVER);
        kind(K_REDSTONE_COMP, Blocks.REPEATER, Blocks.COMPARATOR);
        kind(K_REDSTONE_WIRE, Blocks.REDSTONE_WIRE);
        kind(K_REDSTONE_MISC, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.REDSTONE_BLOCK, Blocks.TARGET,
            Blocks.DAYLIGHT_DETECTOR);
        kind(K_DISPENSER, Blocks.DISPENSER, Blocks.DROPPER);
        kind(K_PORTAL, Blocks.NETHER_PORTAL);
        kind(K_ANCHOR, Blocks.RESPAWN_ANCHOR);
        kind(K_PLAYER_ONLY, Blocks.LODESTONE, Blocks.NETHERITE_BLOCK);
        kind(K_SPAWNER, Blocks.SPAWNER);
        kind(K_SOUL_SAND, Blocks.SOUL_SAND);
        kind(K_TINTED_GLASS, Blocks.TINTED_GLASS);
        kind(K_OBSIDIAN, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN);
        kind(K_NETHERRACK, Blocks.NETHERRACK, Blocks.MAGMA_BLOCK);
        kind(K_WATER, Blocks.WATER);
        kind(K_LAVA, Blocks.LAVA);
        kind(K_TORCH, Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH);
        kind(K_LAMP, Blocks.LANTERN, Blocks.REDSTONE_LAMP, Blocks.OCHRE_FROGLIGHT, Blocks.VERDANT_FROGLIGHT,
            Blocks.PEARLESCENT_FROGLIGHT, Blocks.JACK_O_LANTERN, Blocks.END_ROD);
        kind(K_GLOW, Blocks.GLOWSTONE, Blocks.SHROOMLIGHT);
        kind(K_LADDER, Blocks.LADDER, Blocks.SCAFFOLDING);
        kind(K_SLIME, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK);
        kind(K_CAMPFIRE, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE);
        kind(K_HONEYCOMB, Blocks.HONEYCOMB_BLOCK);
        kind(K_CROP, Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS, Blocks.SUGAR_CANE,
            Blocks.SWEET_BERRY_BUSH, Blocks.MELON_STEM, Blocks.PUMPKIN_STEM, Blocks.ATTACHED_MELON_STEM, Blocks.ATTACHED_PUMPKIN_STEM);
        kind(K_RAIL, Blocks.RAIL);
        kind(K_COBWEB, Blocks.COBWEB);
        kind(K_MS_FENCE, Blocks.OAK_FENCE, Blocks.DARK_OAK_FENCE);
        kind(K_COBBLESTONE, Blocks.COBBLESTONE);
        kind(K_MOSSY_COBBLE, Blocks.MOSSY_COBBLESTONE);
        kind(K_STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS);
        kind(K_AGED_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS);
        kind(K_IRON_BARS, Blocks.IRON_BARS);
        kind(K_RED_CARPET, Blocks.RED_CARPET);
        kind(K_END_FRAME, Blocks.END_PORTAL_FRAME);
        kind(K_TRAIL, Blocks.MUD_BRICKS);
        kind(K_SUSPICIOUS, Blocks.SUSPICIOUS_GRAVEL, Blocks.SUSPICIOUS_SAND);
        kind(K_LUSH, Blocks.MOSS_BLOCK, Blocks.MOSS_CARPET, Blocks.CLAY, Blocks.AZALEA, Blocks.FLOWERING_AZALEA,
            Blocks.SPORE_BLOSSOM, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.SMALL_DRIPLEAF, Blocks.ROOTED_DIRT);
        kind(K_JUNGLE, Blocks.JUNGLE_LEAVES, Blocks.JUNGLE_LOG);
        kind(K_NETHER_STRUCT, Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.POLISHED_BLACKSTONE_BRICKS,
            Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE);
        kind(K_PURPUR, Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR);
        kind(K_TNT, Blocks.TNT);
        kind(K_BELL, Blocks.BELL);
        kind(K_JOBSITE, Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.SMITHING_TABLE, Blocks.LOOM,
            Blocks.STONECUTTER, Blocks.GRINDSTONE, Blocks.COMPOSTER);

        mark(NOTABLE, K_BEEHIVE, K_SHULKER, K_CHEST, K_TRAPPED_CHEST, K_BARREL, K_FURNACE, K_CRAFTING,
            K_BREWING, K_LECTERN, K_CAULDRON, K_BEACON, K_CONDUIT, K_ENCHANTING, K_ANVIL, K_DAMAGED_ANVIL, K_CRAFTER,
            K_HOPPER, K_PISTON, K_OBSERVER, K_REDSTONE_COMP, K_REDSTONE_WIRE, K_REDSTONE_MISC, K_DISPENSER, K_PORTAL, K_ANCHOR,
            K_PLAYER_ONLY, K_SPAWNER, K_TINTED_GLASS, K_SIGN, K_TORCH, K_LAMP, K_GLOW, K_LADDER, K_SLIME, K_CAMPFIRE, K_CROP);
        // Ender chests and respawn anchors are left out: griefers and crystal fights leave them all over DonutSMP.
        mark(PLAYER_BLOCK, K_BEEHIVE, K_SHULKER, K_CHEST, K_TRAPPED_CHEST, K_BARREL, K_FURNACE, K_CRAFTING,
            K_BREWING, K_LECTERN, K_BEACON, K_CONDUIT, K_ENCHANTING, K_ANVIL, K_CRAFTER, K_HOPPER, K_PISTON, K_OBSERVER,
            K_REDSTONE_COMP, K_REDSTONE_MISC, K_DISPENSER, K_PLAYER_ONLY, K_LAMP, K_GLOW, K_CROP, K_TINTED_GLASS, K_SIGN);
        mark(AUTOMATION, K_PISTON, K_OBSERVER, K_HOPPER, K_REDSTONE_WIRE, K_REDSTONE_COMP, K_REDSTONE_MISC, K_SLIME, K_LADDER);

        mark(TRIAL_NATURAL, K_CHEST, K_TRAPPED_CHEST, K_BARREL, K_CRAFTING, K_DISPENSER, K_HOPPER, K_REDSTONE_WIRE, K_REDSTONE_MISC, K_SPAWNER, K_LADDER);
        mark(ANCIENT_NATURAL, K_CHEST, K_FURNACE, K_LECTERN, K_REDSTONE_COMP, K_REDSTONE_WIRE, K_REDSTONE_MISC, K_PISTON, K_TORCH, K_LAMP, K_CAMPFIRE, K_LADDER);
        mark(STRONGHOLD_NATURAL, K_CHEST, K_TORCH, K_LADDER, K_SPAWNER);
        mark(NETHER_NATURAL, K_CHEST, K_SPAWNER, K_LAMP, K_LADDER);
        mark(END_NATURAL, K_ENDER_CHEST, K_CHEST, K_BREWING, K_LADDER, K_LAMP);
        mark(VILLAGE_NATURAL, K_CHEST, K_BARREL, K_FURNACE, K_CRAFTING, K_LECTERN, K_BREWING, K_CAULDRON, K_TORCH, K_LAMP, K_CAMPFIRE, K_LADDER, K_CROP);
        mark(TRAIL_NATURAL, K_CRAFTING, K_FURNACE, K_DAMAGED_ANVIL, K_CAULDRON, K_CAMPFIRE, K_LADDER);
        mark(TEMPLE_NATURAL, K_PISTON, K_REDSTONE_COMP, K_REDSTONE_WIRE, K_REDSTONE_MISC, K_DISPENSER, K_CHEST);
        mark(IGLOO_NATURAL, K_BREWING, K_FURNACE, K_CRAFTING, K_CHEST, K_CAULDRON, K_TORCH, K_LADDER, K_SIGN);
    }

    private static void kind(int kind, Block... blocks) {
        for (Block block : blocks) KINDS.put(block, (byte) kind);
    }

    private static void mark(boolean[] set, int... kinds) {
        for (int kind : kinds) set[kind] = true;
    }

    private static boolean isRelevant(BlockState state) {
        return KINDS.containsKey(state.getBlock());
    }

    private static boolean isUpdateRelevant(BlockState state) {
        int kind = KINDS.getByte(state.getBlock());
        return kind != 0 && kind != K_WATER && kind != K_LAVA;
    }

    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private static final int CAT_STORAGE = 0, CAT_UTILITY = 1, CAT_REDSTONE = 2, CAT_BUILD = 3, CAT_LIGHT = 4,
        CAT_FARM = 5, CAT_GROWTH = 6, CAT_PORTAL = 7, CAT_GRINDER = 8;

    /**
     * One piece of evidence.
     *
     * @param definitive a block world generation never places, which caps the false positive score
     * @param legacy one of the classic signals with its own setting, which flags a chunk on its own
     */
    private record Signal(String label, int category, double confidence, boolean definitive, boolean legacy, int y) {
    }

    /** One chunk's scan. Built and run on the worker thread, then thrown away. */
    private final class Scan {
        final ChunkSection[] sections;
        final int startX, startZ, chunkX, chunkZ;
        final int minY, topY, height;
        final boolean nether, end;

        final int[] surface = new int[256];

        final int[] count = new int[KIND_COUNT];
        final int[] countUG = new int[KIND_COUNT];
        final int[] countDeep = new int[KIND_COUNT];
        /** (kind << 17) | packed position, for kinds that need a second look once the context is known. */
        final IntArrayList notable = new IntArrayList();

        int rotated, rotatedDeep, cobbledLow;
        long rotatedYSum, cobbledYSum;
        int ancientBuildLow, sculkLow, trialBand;
        int flowingFluids;

        final int[] kelpBottom = new int[256], kelpTop = new int[256], kelpTipAge = new int[256];
        boolean anyKelp;
        final IntArrayList caveVineTips = new IntArrayList();
        final IntArrayList vinePositions = new IntArrayList();
        final IntArrayList cocoaPositions = new IntArrayList();

        int geoMinX = 16, geoMinY = Integer.MAX_VALUE, geoMinZ = 16, geoMaxX = -1, geoMaxY = Integer.MIN_VALUE, geoMaxZ = -1;

        final int[] bambooBottom = new int[256], bambooTop = new int[256];
        final boolean[] bambooMaxStage = new boolean[256];
        boolean anyBamboo;

        int cocoaTotal, cocoaMature, cocoaRipe;
        int nestsHoney, nestsFull, nestsUG;
        long growthYSum;
        int growthYCount;

        // Second pass results.
        final int[] eff = new int[KIND_COUNT];
        final int[] effAll = new int[KIND_COUNT];
        final long[] effYSum = new long[KIND_COUNT];
        final int[] playerY;
        final IntArrayList playerBlocks = new IntArrayList();
        int playerUG;
        int neighborCtx;

        int ownCtx;

        /** Storage block positions in this chunk and its neighbours, from {@link #storageAround}. */
        final long[] storage;

        /** Height range the signals may come from, from the Height settings. Structure detection ignores it. */
        final int lowY, highY;

        /** Sections below Y 0 the server sent blank, and how many; see {@link #isBlank}. */
        final boolean[] blank;
        final int hiddenSections;
        /** What the hidden part was sent as, for the log. */
        String blankLook = "";
        /** Lowest Y with real data. */
        final int floorY;

        /** From {@link #lightInputs}: zeroed, turned dark and airless light section bits, or null. */
        final int[] light;
        String lightLabel;

        Scan(WorldChunk chunk, boolean nether, boolean end, long[] storage, int[] light) {
            this.storage = storage;
            this.light = light;
            this.lowY = limitHeight.get() ? Math.min(heightMin.get(), heightMax.get()) : Integer.MIN_VALUE;
            this.highY = limitHeight.get() ? Math.max(heightMin.get(), heightMax.get()) : Integer.MAX_VALUE;
            this.sections = chunk.getSectionArray();
            this.startX = chunk.getPos().getStartX();
            this.startZ = chunk.getPos().getStartZ();
            this.chunkX = chunk.getPos().x;
            this.chunkZ = chunk.getPos().z;
            this.minY = chunk.getBottomY();
            this.height = chunk.getHeight();
            this.topY = minY + height - 1;
            this.nether = nether;
            this.end = end;
            this.playerY = new int[height];

            // DonutSMP leaves out everything below the deepslate while you are above it. Deep terrain can
            // generate one solid cube of plain deepslate now and then (seen in testing), but not three
            // of the four sections below Y 0, so that marks a chunk sent that way.
            this.blank = new boolean[sections.length];
            int blanks = 0;
            StringBuilder look = new StringBuilder();
            if (!nether && !end) {
                for (int i = 0; i < sections.length && minY + (i << 4) + 15 < 0; i++) {
                    ChunkSection section = sections[i];
                    blank[i] = isBlank(section);
                    if (blank[i]) blanks++;
                    if (look.length() > 0) look.append(", ");
                    look.append("Y ").append(minY + (i << 4)).append(": ").append(!blank[i] ? "real terrain"
                        : section == null || section.isEmpty() ? "empty" : Registries.BLOCK.getId(section.getBlockState(0, 0, 0).getBlock()).toString());
                }
            }
            if (blanks < 3) Arrays.fill(blank, false);
            else blankLook = look.toString();
            this.hiddenSections = blanks < 3 ? 0 : blanks;
            this.floorY = hiddenSections > 0 ? 0 : minY;

            Arrays.fill(kelpBottom, Integer.MAX_VALUE);
            Arrays.fill(kelpTop, Integer.MIN_VALUE);
            Arrays.fill(kelpTipAge, -1);
            Arrays.fill(bambooBottom, Integer.MAX_VALUE);
            Arrays.fill(bambooTop, Integer.MIN_VALUE);
        }

        /** Empty, or one block throughout. Checks the palette, not the blocks, so it is cheap. */
        boolean isBlank(ChunkSection section) {
            if (section == null || section.isEmpty()) return true;
            BlockState first = section.getBlockState(0, 0, 0);
            return !section.hasAny(state -> state != first);
        }

        boolean inHeight(int y) {
            return y >= lowY && y <= highY;
        }

        int pack(int lx, int y, int lz) {
            return ((y - minY) << 8) | (lx << 4) | lz;
        }

        int px(int p) { return (p >> 4) & 15; }
        int pz(int p) { return p & 15; }
        int py(int p) { return ((p >> 8) & 511) + minY; }

        /** @return the state inside this chunk, or null when the position is outside it */
        BlockState at(int lx, int y, int lz) {
            if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || y < minY || y > topY) return null;
            ChunkSection section = sections[(y - minY) >> 4];
            return section == null ? AIR : section.getBlockState(lx, (y - minY) & 15, lz);
        }

        boolean underground(int lx, int y, int lz) {
            return y < surface[(lx << 4) | lz] - 6;
        }

        FlaggedChunk run(int neighborCtx) {
            computeSurface();
            collect();
            return evaluate(neighborCtx);
        }

        /** Highest non-air, non-leaf block per column. Ourselves rather than the heightmap, which the client may not keep. */
        void computeSurface() {
            Arrays.fill(surface, minY - 1);
            int remaining = 256;

            for (int i = sections.length - 1; i >= 0 && remaining > 0; i--) {
                ChunkSection section = sections[i];
                if (section == null || section.isEmpty()) continue;
                int baseY = minY + (i << 4);

                for (int ly = 15; ly >= 0 && remaining > 0; ly--) {
                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            int column = (lx << 4) | lz;
                            if (surface[column] >= minY) continue;

                            BlockState state = section.getBlockState(lx, ly, lz);
                            if (state.isAir() || state.getBlock() instanceof LeavesBlock) continue;

                            surface[column] = baseY + ly;
                            remaining--;
                        }
                    }
                }
            }
        }

        void collect() {
            int minCaveVineAge = caveVinesMinAge.get();
            int minCocoaAge = cocoaMinAge.get();
            int minHoney = beeNestMinHoney.get();

            for (int i = 0; i < sections.length; i++) {
                ChunkSection section = sections[i];
                if (blank[i] || section == null || section.isEmpty() || !section.hasAny(SusChunkFinder::isRelevant)) continue;

                int baseY = minY + (i << 4);

                for (int ly = 0; ly < 16; ly++) {
                    int y = baseY + ly;

                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            BlockState state = section.getBlockState(lx, ly, lz);
                            int k = KINDS.getByte(state.getBlock());
                            if (k == 0) continue;

                            int column = (lx << 4) | lz;
                            boolean ug = y < surface[column] - 6;

                            count[k]++;
                            if (ug) countUG[k]++;
                            if (y < 0) countDeep[k]++;
                            // Glowstone fills nether ceilings; keeping every block would only waste memory.
                            if (NOTABLE[k] && !(nether && k == K_GLOW)) notable.add((k << 17) | pack(lx, y, lz));

                            switch (k) {
                                case K_DEEPSLATE -> {
                                    if (state.get(Properties.AXIS) != Direction.Axis.Y && inHeight(y)) {
                                        rotated++;
                                        rotatedYSum += y;
                                        if (y < 0) rotatedDeep++;
                                    }
                                }
                                case K_COBBLED_DEEPSLATE -> {
                                    if (y < 0 && inHeight(y)) {
                                        cobbledLow++;
                                        cobbledYSum += y;
                                    }
                                }
                                case K_KELP, K_KELP_PLANT -> {
                                    if (y < kelpBottom[column]) kelpBottom[column] = y;
                                    if (y >= kelpTop[column]) {
                                        kelpTop[column] = y;
                                        kelpTipAge[column] = k == K_KELP ? state.getOrEmpty(Properties.AGE_25).orElse(0) : -1;
                                    }
                                    anyKelp = true;
                                    growth(y);
                                }
                                case K_CAVE_VINES -> {
                                    if (state.getOrEmpty(Properties.AGE_25).orElse(0) >= minCaveVineAge) {
                                        caveVineTips.add(pack(lx, y, lz));
                                    }
                                }
                                case K_VINE -> vinePositions.add(pack(lx, y, lz));
                                case K_BUDDING, K_AM_CLUSTER, K_AM_LARGE, K_AM_SMALL -> {
                                    geoMinX = Math.min(geoMinX, lx);
                                    geoMaxX = Math.max(geoMaxX, lx);
                                    geoMinY = Math.min(geoMinY, y);
                                    geoMaxY = Math.max(geoMaxY, y);
                                    geoMinZ = Math.min(geoMinZ, lz);
                                    geoMaxZ = Math.max(geoMaxZ, lz);
                                    growth(y);
                                }
                                case K_BAMBOO -> {
                                    if (y < bambooBottom[column]) bambooBottom[column] = y;
                                    if (y >= bambooTop[column]) {
                                        bambooTop[column] = y;
                                        bambooMaxStage[column] = state.getOrEmpty(Properties.STAGE).orElse(0) >= 1;
                                    }
                                    anyBamboo = true;
                                    growth(y);
                                }
                                case K_COCOA -> {
                                    cocoaTotal++;
                                    int age = state.getOrEmpty(Properties.AGE_2).orElse(0);
                                    if (age >= minCocoaAge) cocoaMature++;
                                    if (age >= 2) cocoaRipe++;
                                    cocoaPositions.add(pack(lx, y, lz));
                                    growth(y);
                                }
                                case K_BEE_NEST -> {
                                    int honey = state.getOrEmpty(Properties.HONEY_LEVEL).orElse(0);
                                    if (honey >= minHoney) {
                                        nestsHoney++;
                                        if (ug) nestsUG++;
                                    }
                                    if (honey >= 5) nestsFull++;
                                    growth(y);
                                }
                                case K_ANCIENT_BUILD -> {
                                    if (y < -25) ancientBuildLow++;
                                }
                                case K_SCULK -> {
                                    if (y < -25) sculkLow++;
                                }
                                case K_TRIAL_BUILD -> {
                                    if (y >= TRIAL_MIN_Y && y <= TRIAL_MAX_Y) trialBand++;
                                }
                                case K_WATER, K_LAVA -> {
                                    if (ug && !state.getFluidState().isStill()) flowingFluids++;
                                }
                                default -> {
                                }
                            }
                        }
                    }
                }
            }
        }

        void growth(int y) {
            growthYSum += y;
            growthYCount++;
        }

        FlaggedChunk evaluate(int neighborCtx) {
            this.neighborCtx = neighborCtx;

            // Structure context.
            boolean ownTrial = count[K_TRIAL_DEF] > 0 || trialBand >= 12;
            // Sculk covers the whole deep dark, so it only confirms a city when the city's bricks, tiles,
            // wool and soul lanterns are there too.
            boolean ownAncient = count[K_REINFORCED] > 0 || (sculkLow >= 3 && ancientBuildLow >= 8);
            ownCtx = (ownTrial ? CTX_TRIAL : 0) | (ownAncient ? CTX_ANCIENT : 0) | (count[K_SPAWNER] > 0 ? CTX_SPAWNER : 0);

            boolean trial = ownTrial || (neighborCtx & CTX_TRIAL) != 0;
            boolean ancient = ownAncient || (neighborCtx & CTX_ANCIENT) != 0;

            int chestsTotal = count[K_CHEST] + count[K_TRAPPED_CHEST] + count[K_BARREL];
            // Blocks no structure ever places. With one of these present the structure's own blocks count again.
            // Ender chests and anchors are left behind everywhere on DonutSMP, so they don't count here.
            boolean override = count[K_SHULKER] > 0 || count[K_HOPPER] >= 3 || count[K_ENCHANTING] > 0
                || count[K_ANVIL] > 0 || count[K_BEACON] > 0 || count[K_OBSERVER] > 0 || count[K_CRAFTER] > 0
                || count[K_PLAYER_ONLY] > 0 || chestsTotal >= 12;

            boolean stronghold = count[K_END_FRAME] > 0 || count[K_INFESTED] >= 2 || (countUG[K_AGED_BRICKS] >= 12 && countUG[K_STONE_BRICKS] >= 20);
            boolean mineshaft = countUG[K_RAIL] >= 6 && (count[K_COBWEB] >= 2 || count[K_MS_FENCE] >= 3);
            // The spawner is often just over the chunk border, so the mossy floor alone marks a dungeon.
            boolean dungeon = countUG[K_MOSSY_COBBLE] >= 6;
            boolean igloo = count[K_BREWING] > 0 && count[K_IRON_BARS] > 0 && count[K_RED_CARPET] > 0;
            boolean lush = count[K_LUSH] >= 24;
            boolean jungle = count[K_JUNGLE] >= 24;
            boolean temple = jungle && count[K_MOSSY_COBBLE] >= 10 && countDeep[K_MOSSY_COBBLE] == 0;
            boolean pyramid = count[K_TNT] >= 4;
            // Bases have beds and job blocks too, so only above-ground ones suggest a village.
            boolean village = count[K_BELL] > 0
                || (count[K_JOBSITE] - countUG[K_JOBSITE] >= 2 && count[K_BED] - countUG[K_BED] >= 2);
            boolean trail = count[K_SUSPICIOUS] >= 3 || (count[K_SUSPICIOUS] > 0 && count[K_TRAIL] >= 4);
            boolean netherStruct = count[K_NETHER_STRUCT] >= 16;
            boolean endCity = count[K_PURPUR] >= 16;
            boolean ruinedPortal = !nether && count[K_NETHERRACK] >= 3;

            // Spawner positions, so dungeon chests next to them can be told apart from a stash.
            IntArrayList spawners = new IntArrayList();
            for (int i = 0; i < notable.size(); i++) {
                int e = notable.getInt(i);
                if ((e >>> 17) == K_SPAWNER) spawners.add(e & 0x1FFFF);
            }

            // Strongholds, fortresses, bastions and end cities are built from blocks players use too, so
            // they only explain away as many chests as they actually generate, not a whole stash.
            int[] budget = new int[KIND_COUNT];
            if (stronghold) addBudget(budget, STRONGHOLD_NATURAL, K_CHEST, 3);
            if (netherStruct) addBudget(budget, NETHER_NATURAL, K_CHEST, 4);
            if (endCity) {
                addBudget(budget, END_NATURAL, K_CHEST, 3);
                budget[K_ENDER_CHEST] += 2;
                budget[K_BREWING] += 2;
            }
            if (trail) addBudget(budget, TRAIL_NATURAL, -1, 0);
            // Dungeons generate up to two chests each.
            if (dungeon) budget[K_CHEST] += 2 * Math.max(1, count[K_SPAWNER]);

            // Second pass: count what is left after each structure's natural blocks are taken out.
            for (int i = 0; i < notable.size(); i++) {
                int e = notable.getInt(i);
                int k = e >>> 17;
                int p = e & 0x1FFFF;
                int lx = px(p), lz = pz(p), y = py(p);

                if (!override) {
                    if (trial && y >= TRIAL_MIN_Y && y <= TRIAL_MAX_Y && TRIAL_NATURAL[k]) continue;
                    if (ancient && y < 0 && ANCIENT_NATURAL[k]) continue;
                }
                if (budget[k] > 0) {
                    budget[k]--;
                    continue;
                }
                if (village && y >= 40 && VILLAGE_NATURAL[k]) continue;
                if (temple && TEMPLE_NATURAL[k]) continue;
                if (igloo && IGLOO_NATURAL[k]) continue;
                if (pyramid && k == K_CHEST) continue;
                if (nether && (k == K_GLOW || k == K_ANCHOR)) continue;

                if (!inHeight(y)) continue;

                boolean ug = underground(lx, y, lz);
                effAll[k]++;
                effYSum[k] += y;
                if (ug) eff[k]++;

                if (ug && PLAYER_BLOCK[k]) {
                    playerY[y - minY]++;
                    playerUG++;
                    playerBlocks.add(p);
                }
            }

            List<Signal> signals = new ArrayList<>();
            boolean naturalAmethystOnly = false;

            // Classic signals.

            int rotatedCount = ancient && !override ? rotated - rotatedDeep : rotated;
            if (rotatedDeepslate.get() && rotatedCount >= rotatedThreshold.get()) {
                signals.add(new Signal(rotatedCount + " rotated deepslate", CAT_BUILD, 0.55, false, true, mean(rotatedYSum, rotated)));
            }

            if (cobbledDeepslate.get() && !(ancient && !override) && cobbledLow >= cobbledThreshold.get()) {
                signals.add(new Signal(cobbledLow + " cobbled deepslate", CAT_BUILD, 0.4, false, true, mean(cobbledYSum, cobbledLow)));
            }

            int[] room = storageRoom(spawners, trial, ancient, override, dungeon);
            int roomSize = room[0];
            if (undergroundStorage.get() && roomSize >= storageThreshold.get()) {
                double c = Math.min(0.95, 0.6 + 0.02 * (roomSize - storageThreshold.get()));
                signals.add(new Signal(roomSize + " storage blocks in one room (Y " + room[1] + ")", CAT_STORAGE, c, true, true, room[1]));
            } else if (roomSize >= 4) {
                // Shipwrecks, dungeons and buried treasure leave one to three chests, so this only supports.
                signals.add(new Signal(roomSize + " storage blocks together", CAT_STORAGE, 0.15, false, false, room[1]));
            }

            // Farms: lots of redstone parts in one chunk. Dust is cheap to lay, so three count as one part.
            int parts = effAll[K_PISTON] + effAll[K_OBSERVER] + effAll[K_REDSTONE_COMP] + effAll[K_HOPPER]
                + effAll[K_DISPENSER] + effAll[K_CRAFTER] + effAll[K_REDSTONE_MISC] + effAll[K_SLIME] + effAll[K_REDSTONE_WIRE] / 3;
            if (redstone.get() && parts >= redstoneThreshold.get()) {
                double c = Math.min(0.95, 0.6 + 0.02 * (parts - redstoneThreshold.get()));
                signals.add(new Signal(parts + " redstone parts (" + redstoneBreakdown() + ")", CAT_REDSTONE, c, true, true, redstoneY()));
            } else if (parts >= 4) {
                signals.add(new Signal(parts + " redstone parts", CAT_REDSTONE, 0.2, false, false, redstoneY()));
            }

            if (grownKelp.get() && anyKelp) addIfPresent(signals, checkKelp());
            if (caveVines.get() && !caveVineTips.isEmpty()) addIfPresent(signals, checkCaveVines(lush));
            if (vines.get() && !vinePositions.isEmpty()) addIfPresent(signals, checkVines());

            if (amethyst.get() && geoMaxX >= 0) {
                Signal am = checkAmethyst();
                if (am != null) {
                    signals.add(am);
                    naturalAmethystOnly = am.category == CAT_GROWTH;
                }
            }

            if (bamboo.get() && anyBamboo) addIfPresent(signals, checkBamboo());
            if (cocoa.get() && cocoaTotal > 0) addIfPresent(signals, checkCocoa());

            boolean hivesCounted = false;
            if (beeNest.get()) {
                Signal bees = checkBees();
                if (bees != null) {
                    signals.add(bees);
                    hivesCounted = includeBeehives.get();
                }
            }

            // Dug-out space from light data. Sure when some dark section can't be explained away; only
            // possible when every one went dark after being seen lit, or sits in hidden solid fill.
            if (light != null) {
                int zeroed = light[0];
                int sure = zeroed & ~light[1] & ~light[2];
                int bits = sure != 0 ? sure : zeroed;
                int bottomSection = minY >> 4;
                int yLo = (bottomSection + Integer.numberOfTrailingZeros(bits) - 1) * 16;
                int yHi = (bottomSection + 31 - Integer.numberOfLeadingZeros(bits) - 1) * 16 + 15;
                String where = "Y " + yLo + " to " + yHi + " (light data)";
                lightLabel = (sure != 0 ? "dug-out space at " : "possible dug-out space at ") + where;
                signals.add(new Signal(lightLabel, CAT_BUILD, sure != 0 ? 0.6 : 0.35, false, true, (yLo + yHi) / 2));
            }

            // Supporting clues. None of these flag a chunk alone, since griefed chunks all over DonutSMP
            // still have them. They only add to the scores of a chunk a signal above already flagged.
            // Ender chests and respawn anchors aren't even that: they are everywhere.

            if (effAll[K_SHULKER] > 0) signals.add(soft(plural(effAll[K_SHULKER], "shulker box", "shulker boxes"), CAT_STORAGE, 0.25, K_SHULKER));
            if (effAll[K_BEACON] > 0) signals.add(soft(plural(effAll[K_BEACON], "beacon", "beacons"), CAT_UTILITY, 0.3, K_BEACON));
            if (effAll[K_CONDUIT] > 0) signals.add(soft(plural(effAll[K_CONDUIT], "conduit", "conduits"), CAT_UTILITY, 0.25, K_CONDUIT));
            if (effAll[K_ENCHANTING] > 0) signals.add(soft("enchanting table", CAT_UTILITY, 0.2, K_ENCHANTING));
            if (effAll[K_ANVIL] > 0) signals.add(soft(plural(effAll[K_ANVIL], "anvil", "anvils"), CAT_UTILITY, 0.2, K_ANVIL));
            if (effAll[K_PLAYER_ONLY] > 0) signals.add(soft(plural(effAll[K_PLAYER_ONLY], "lodestone/netherite block", "lodestones/netherite blocks"), CAT_UTILITY, 0.2, K_PLAYER_ONLY));
            if (!hivesCounted && effAll[K_BEEHIVE] > 0) signals.add(soft(plural(effAll[K_BEEHIVE], "beehive", "beehives"), CAT_FARM, 0.25, K_BEEHIVE));

            if (effAll[K_PORTAL] > 0) {
                int portalY = meanEff(K_PORTAL);
                if (portalY < 45 && eff[K_PORTAL] > 0) signals.add(new Signal("nether portal underground (Y " + portalY + ")", CAT_PORTAL, 0.25, false, false, portalY));
                else signals.add(new Signal("nether portal", CAT_PORTAL, 0.1, false, false, portalY));
            }

            int hoppers = effAll[K_HOPPER];

            Signal grinder = checkGrinder(spawners, ancient, stronghold);
            if (grinder != null) signals.add(grinder);

            int crops = eff[K_CROP];
            if (crops >= 6) {
                boolean lit = eff[K_LAMP] + eff[K_GLOW] + eff[K_TORCH] > 0;
                signals.add(new Signal(crops + " crops underground" + (lit ? " (lit)" : ""), CAT_FARM, lit ? 0.6 : 0.4, false, false, meanEff(K_CROP)));
            }

            int walls = (stronghold || dungeon ? 0 : countDeep[K_COBBLESTONE] + countDeep[K_STONE_BRICKS]);
            if (walls >= 12) signals.add(new Signal(walls + " cobblestone/stone bricks below Y 0", CAT_BUILD, 0.1, false, false, Integer.MIN_VALUE));

            if (eff[K_FURNACE] >= 2) signals.add(soft(eff[K_FURNACE] + " furnaces underground", CAT_UTILITY, 0.3, K_FURNACE));
            else if (eff[K_FURNACE] == 1) signals.add(soft("furnace underground", CAT_UTILITY, 0.15, K_FURNACE));
            if (eff[K_BREWING] > 0) signals.add(soft("brewing stand underground", CAT_UTILITY, 0.3, K_BREWING));
            if (eff[K_LECTERN] > 0) signals.add(soft("lectern underground", CAT_UTILITY, 0.2, K_LECTERN));
            if (eff[K_CRAFTING] > 0) signals.add(soft("crafting table underground", CAT_UTILITY, 0.2, K_CRAFTING));

            int lamps = eff[K_LAMP] + eff[K_GLOW];
            if (lamps >= 3) signals.add(new Signal(lamps + " lamps underground", CAT_LIGHT, 0.35, false, false, meanOf(K_LAMP, K_GLOW)));
            // Cavers torch every cave, so torches alone only support other evidence.
            if (eff[K_TORCH] >= 6) signals.add(soft(eff[K_TORCH] + " torches underground", CAT_LIGHT, 0.15, K_TORCH));

            int[] shafts = playerUG >= 2 ? findShafts() : null;
            if (shafts != null && shafts[1] > 0) {
                signals.add(new Signal("access shaft to Y " + shafts[3], CAT_BUILD, 0.3, false, false, shafts[3]));
            }

            // Farms measure their own height, so they are held to the range here. Natural growth isn't:
            // kelp or amethyst that kept growing is the from-the-surface clue that someone stayed in
            // the chunk, which is what tells you where to dig down and relog.
            signals.removeIf(s -> s.category != CAT_GROWTH && s.y != Integer.MIN_VALUE && !inHeight(s.y));
            if (signals.stream().noneMatch(Signal::legacy)) return null;

            // Base confidence: independent evidence combined, plus a bonus when unrelated kinds agree.
            double miss = 1;
            int definitive = 0, growthSignals = 0;
            double strongestBuilt = 0;
            boolean anchorEvidence = false;
            int categories = 0;
            boolean legacyFired = false;

            for (Signal s : signals) {
                miss *= 1 - s.confidence;
                if (s.definitive) definitive++;
                if (s.legacy) legacyFired = true;
                if (s.category == CAT_GROWTH) growthSignals++;
                else strongestBuilt = Math.max(strongestBuilt, s.confidence);
                if (s.category != CAT_GROWTH && s.category != CAT_LIGHT && s.confidence >= 0.25) anchorEvidence = true;
                if (s.confidence >= 0.15) categories |= 1 << s.category;
            }

            int distinct = Integer.bitCount(categories);
            int confidence = (int) Math.round((1 - miss) * 100);
            if (anchorEvidence && distinct >= 3) confidence += 25;
            else if (anchorEvidence && distinct >= 2) confidence += 15;
            confidence = MathHelper.clamp(confidence, 1, 99);

            // False positive: how likely world generation alone explains what was seen.
            int falsePositive;
            if (definitive > 0) {
                falsePositive = Math.max(2, 10 - 3 * (definitive - 1));
            } else {
                if (strongestBuilt >= 0.3) falsePositive = 30;
                else if (strongestBuilt > 0) falsePositive = 45;
                else if (naturalAmethystOnly && growthSignals == 1) falsePositive = 82;
                else falsePositive = Math.max(50, 72 - 7 * (growthSignals - 1));

                int contexts = count(trial, ancient, mineshaft, lush, dungeon, stronghold, igloo, jungle, temple,
                    village, trail, netherStruct, endCity, pyramid, ruinedPortal);
                falsePositive += Math.min(36, contexts * 12);

                int geometry = (lamps + eff[K_TORCH] >= 3 ? 1 : 0) + (hoppers > 0 ? 1 : 0) + (roomSize >= 2 ? 1 : 0)
                    + (walls >= 8 ? 1 : 0) + (rotatedCount >= rotatedThreshold.get() ? 1 : 0)
                    + (cobbledLow >= cobbledThreshold.get() && !(ancient && !override) ? 1 : 0);
                if (geometry >= 2) falsePositive -= Math.min(50, 25 + (geometry - 2) * 12);

                falsePositive = MathHelper.clamp(falsePositive, 5, 97);
            }

            // Griefed: raid damage around what was built.
            List<String> griefReasons = new ArrayList<>();
            int griefed = 0;
            if (detectGriefedBases.get() && (confidence >= 20 || playerUG >= 2)) {
                double intact = checkGrief(griefReasons, shafts, ruinedPortal);
                griefed = (int) Math.round((1 - intact) * 100);
                if (confidence < 30) griefed = (int) Math.round(griefed * 0.6);
                griefed = MathHelper.clamp(griefed, 0, 97);
            }

            // Only a main signal (one with its own setting) flags a chunk; the clues just score it.
            boolean keep = legacyFired;
            if (intelligentChunks.get() && hideLikelyFalsePositives.get()
                && falsePositive > maxFalsePositiveProb.get() && confidence < minBaseConfidence.get()) keep = false;
            if (!keep) return null;

            signals.sort((a, b) -> Double.compare(b.confidence, a.confidence));
            List<String> reasons = new ArrayList<>(signals.size());
            double ySum = 0, yWeight = 0;
            for (Signal s : signals) {
                reasons.add(s.label);
                if (s.y != Integer.MIN_VALUE) {
                    ySum += s.y * s.confidence;
                    yWeight += s.confidence;
                }
            }

            int focusY = yWeight > 0 ? (int) Math.round(ySum / yWeight) : (growthYCount > 0 ? (int) (growthYSum / growthYCount) : 64);
            focusY = MathHelper.clamp(focusY, minY + 1, topY);

            return new FlaggedChunk(chunkX, chunkZ, reasons, confidence, falsePositive, griefed, griefReasons, focusY, false, true, hiddenSections > 0);
        }

        /** Lets a structure explain all of its natural kinds, except {@code limitedKind}, which it explains {@code limit} of. */
        void addBudget(int[] budget, boolean[] natural, int limitedKind, int limit) {
            for (int k = 0; k < KIND_COUNT; k++) {
                if (!natural[k]) continue;
                budget[k] = k == limitedKind ? budget[k] + limit : UNLIMITED;
            }
        }

        Signal soft(String label, int category, double confidence, int kind) {
            return new Signal(label, category, confidence, false, false, meanEff(kind));
        }

        int meanEff(int kind) {
            return effAll[kind] == 0 ? Integer.MIN_VALUE : (int) (effYSum[kind] / effAll[kind]);
        }

        int meanOf(int... kinds) {
            long sum = 0;
            int n = 0;
            for (int kind : kinds) {
                sum += effYSum[kind];
                n += effAll[kind];
            }
            return n == 0 ? Integer.MIN_VALUE : (int) (sum / n);
        }

        /**
         * The biggest storage room reaching into this chunk: storage blocks chained together, each at
         * most {@link #CLUSTER_LINK} blocks from the next, across chunk borders. Chests the structures
         * around generate on their own are left out first.
         *
         * @return {blocks in the room, its average Y}
         */
        int[] storageRoom(IntArrayList spawners, boolean trial, boolean ancient, boolean override, boolean dungeon) {
            int maxY = storageMaxY.get();
            int cap = Math.min(storage.length, CLUSTER_MAX_POINTS);
            int[] xs = new int[cap], ys = new int[cap], zs = new int[cap];
            int n = 0;

            for (long packed : storage) {
                if (n == cap) break;
                int x = BlockPos.unpackLongX(packed), y = BlockPos.unpackLongY(packed), z = BlockPos.unpackLongZ(packed);
                if (y > maxY || !inHeight(y)) continue;
                if (!override && trial && y >= TRIAL_MIN_Y && y <= TRIAL_MAX_Y) continue;
                if (!override && ancient && y < 0) continue;
                // Dungeon chests sit right next to the spawner.
                if (dungeon && (x >> 4) == chunkX && (z >> 4) == chunkZ && nearAny(spawners, x & 15, y, z & 15, 5)) continue;
                xs[n] = x;
                ys[n] = y;
                zs[n] = z;
                n++;
            }
            if (n == 0) return new int[] {0, 0};

            int[] parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (Math.abs(xs[i] - xs[j]) <= CLUSTER_LINK && Math.abs(ys[i] - ys[j]) <= CLUSTER_LINK
                        && Math.abs(zs[i] - zs[j]) <= CLUSTER_LINK) {
                        parent[root(parent, i)] = root(parent, j);
                    }
                }
            }

            int[] size = new int[n];
            long[] ySum = new long[n];
            boolean[] reachesHere = new boolean[n];
            for (int i = 0; i < n; i++) {
                int r = root(parent, i);
                size[r]++;
                ySum[r] += ys[i];
                if ((xs[i] >> 4) == chunkX && (zs[i] >> 4) == chunkZ) reachesHere[r] = true;
            }

            int best = -1;
            for (int i = 0; i < n; i++) {
                if (reachesHere[i] && (best < 0 || size[i] > size[best])) best = i;
            }
            return best < 0 ? new int[] {0, 0} : new int[] {size[best], (int) (ySum[best] / size[best])};
        }

        int root(int[] parent, int i) {
            while (parent[i] != i) {
                parent[i] = parent[parent[i]];
                i = parent[i];
            }
            return i;
        }

        /** The three most common redstone parts, for the reason text. No commas: saved reasons are split on them. */
        String redstoneBreakdown() {
            int[] kinds = {K_PISTON, K_OBSERVER, K_REDSTONE_COMP, K_HOPPER, K_DISPENSER, K_CRAFTER, K_REDSTONE_MISC, K_SLIME, K_REDSTONE_WIRE};
            String[] names = {"pistons", "observers", "repeaters/comparators", "hoppers", "droppers/dispensers", "crafters",
                "redstone torches/blocks", "slime/honey", "dust"};
            Integer[] order = {0, 1, 2, 3, 4, 5, 6, 7, 8};
            Arrays.sort(order, (a, b) -> Integer.compare(effAll[kinds[b]], effAll[kinds[a]]));

            List<String> parts = new ArrayList<>(3);
            for (int i = 0; i < 3 && effAll[kinds[order[i]]] > 0; i++) parts.add(effAll[kinds[order[i]]] + " " + names[order[i]]);
            return String.join(" + ", parts);
        }

        int redstoneY() {
            return meanOf(K_PISTON, K_OBSERVER, K_REDSTONE_COMP, K_HOPPER, K_DISPENSER, K_CRAFTER, K_REDSTONE_MISC, K_SLIME, K_REDSTONE_WIRE);
        }

        int mean(long sum, int n) {
            return n == 0 ? Integer.MIN_VALUE : (int) (sum / n);
        }

        boolean nearAny(IntArrayList positions, int lx, int y, int lz, int radius) {
            for (int i = 0; i < positions.size(); i++) {
                int p = positions.getInt(i);
                if (Math.abs(px(p) - lx) <= radius && Math.abs(py(p) - y) <= radius && Math.abs(pz(p) - lz) <= radius) return true;
            }
            return false;
        }

        /**
         * Kelp only counts as reaching the surface with open air above it, and only as grown when the
         * tip aged past what world generation places (20-23) or the stalk is taller than generation
         * allows (11). An overhang, iceberg or shipwreck above the tip no longer counts.
         */
        Signal checkKelp() {
            int tall = 0, atSurface = 0, grown = 0;
            int[] glassCeiling = new int[height];
            int bestCeiling = 0, bestCeilingY = 0;
            long ySum = 0;

            for (int column = 0; column < 256; column++) {
                if (kelpTop[column] == Integer.MIN_VALUE) continue;
                int lx = column >> 4, lz = column & 15;
                int top = kelpTop[column];
                int h = top - kelpBottom[column] + 1;
                BlockState above = at(lx, top + 1, lz);

                if (above != null && above.getBlock() instanceof TransparentBlock) {
                    int n = ++glassCeiling[top - minY];
                    if (n > bestCeiling) {
                        bestCeiling = n;
                        bestCeilingY = top;
                    }
                }

                if (h < kelpMinHeight.get()) continue;
                tall++;

                if (above == null || !above.isAir() || h < 6) continue;
                atSurface++;
                ySum += top;
                if (kelpTipAge[column] >= 24 || h > 11) grown++;
            }

            if (bestCeiling >= 8) {
                return new Signal(bestCeiling + " kelp under a glass ceiling", CAT_FARM, 0.5, false, true, bestCeilingY);
            }

            if (tall < kelpMinColumns.get() || tall == 0) return null;
            if ((double) atSurface / tall < kelpSurfaceRatio.get()) return null;
            if (grown < 10) return null;

            return new Signal(atSurface + "/" + tall + " kelp grown to surface", CAT_GROWTH, 0.18, false, true, (int) (ySum / Math.max(1, atSurface)));
        }

        /** Lush caves generate long cave vines, so there it takes a farm's flat ceiling to count. */
        Signal checkCaveVines(boolean lush) {
            int minLen = caveVinesMinLength.get();
            int longStrands = 0;
            int[] ceilings = new int[height];
            int bestCeiling = 0, bestCeilingY = 0;
            long ySum = 0;

            for (int i = 0; i < caveVineTips.size(); i++) {
                int p = caveVineTips.getInt(i);
                int lx = px(p), lz = pz(p), y = py(p);
                int len = 1;
                int ceilingY = y + 1;

                for (int yy = y + 1; yy <= topY; yy++) {
                    Block b = at(lx, yy, lz).getBlock();
                    if (b == Blocks.CAVE_VINES_PLANT || b == Blocks.CAVE_VINES) {
                        len++;
                    } else {
                        ceilingY = yy;
                        break;
                    }
                }

                if (len < minLen) continue;
                longStrands++;
                ySum += y;
                int n = ++ceilings[ceilingY - minY];
                if (n > bestCeiling) {
                    bestCeiling = n;
                    bestCeilingY = ceilingY;
                }
            }

            if (longStrands < caveVinesMinColumns.get()) return null;

            boolean farm = bestCeiling >= Math.max(6, caveVinesMinColumns.get());
            if (farm) return new Signal(bestCeiling + " cave vines from one flat ceiling", CAT_FARM, 0.45, false, true, bestCeilingY);
            if (lush) return null;
            return new Signal(longStrands + " grown cave vines", CAT_GROWTH, 0.15, false, true, (int) (ySum / longStrands));
        }

        /**
         * Jungle trunks and cliffs generate long vine runs pressed against logs, leaves or stone.
         * Vines that grew while loaded hang free, so mostly-attached runs above Y 55 are skipped.
         */
        Signal checkVines() {
            int minLen = vinesMinLength.get();
            int longColumns = 0, groundedColumns = 0;
            long ySum = 0;

            for (int i = 0; i < vinePositions.size(); i++) {
                int p = vinePositions.getInt(i);
                int lx = px(p), lz = pz(p), y = py(p);

                // Only start measuring from the bottom-most vine of a vertical run.
                BlockState below = at(lx, y - 1, lz);
                if (below != null && below.getBlock() == Blocks.VINE) continue;

                int len = 0, attached = 0;
                for (int yy = y; yy <= topY; yy++) {
                    if (at(lx, yy, lz).getBlock() != Blocks.VINE) break;
                    len++;
                    if (naturallyAttached(lx, yy, lz)) attached++;
                }

                if (len < minLen) continue;
                if (y + len - 1 > 55 && attached >= len * 0.6) continue;

                longColumns++;
                ySum += y;
                if (below != null && !below.isAir()) groundedColumns++;
            }

            if (longColumns < vinesMinColumns.get() || longColumns == 0) return null;
            if (vinesGroundRatio.get() > 0 && (double) groundedColumns / longColumns < vinesGroundRatio.get()) return null;

            return new Signal(longColumns + " grown vines", CAT_GROWTH, 0.15, false, true, (int) (ySum / longColumns));
        }

        boolean naturallyAttached(int lx, int y, int lz) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockState side = at(lx + dir.getOffsetX(), y, lz + dir.getOffsetZ());
                if (side == null) continue;
                if (side.isIn(BlockTags.LOGS) || side.getBlock() instanceof LeavesBlock
                    || side.isIn(BlockTags.BASE_STONE_OVERWORLD) || side.isIn(BlockTags.DIRT)) return true;
            }
            return false;
        }

        /** Geodes generate with every bud size mixed; a harvesting setup inside or beside one is what marks a farm. */
        Signal checkAmethyst() {
            int automation = 0;
            for (int i = 0; i < notable.size(); i++) {
                int e = notable.getInt(i);
                if (!AUTOMATION[e >>> 17]) continue;
                int p = e & 0x1FFFF;
                int lx = px(p), lz = pz(p), y = py(p);
                if (lx >= geoMinX - 3 && lx <= geoMaxX + 3 && lz >= geoMinZ - 3 && lz <= geoMaxZ + 3
                    && y >= geoMinY - 3 && y <= geoMaxY + 3) automation++;
            }

            int geodeY = (geoMinY + geoMaxY) / 2;
            if (automation >= 2) {
                return new Signal("amethyst farm (" + automation + " redstone/collection blocks)", CAT_FARM, 0.55, false, true, geodeY);
            }

            int clusters = count[K_AM_CLUSTER];
            int grownCount = clusters + (amethystIncludeLargeBuds.get() ? count[K_AM_LARGE] : 0);
            int totalGrowth = clusters + count[K_AM_LARGE] + count[K_AM_SMALL];
            if (grownCount < amethystMinClusters.get() || totalGrowth == 0) return null;

            double ratio = (double) grownCount / totalGrowth;
            if (ratio >= amethystClusterRatio.get() || count[K_BUDDING] == 0) {
                return new Signal(grownCount + "/" + totalGrowth + " grown amethyst", CAT_GROWTH, 0.12, false, true, geodeY);
            }
            return null;
        }

        /**
         * Bamboo jungles generate stalks 5-16 tall that already carry the finished stage, so height
         * and stage say nothing. Farms show up underground, on soil bamboo jungles do not use, over
         * collection hoppers or rails, or as a field cut to one height.
         */
        Signal checkBamboo() {
            int farmed = 0, total = 0;
            boolean anyUnderground = false;
            int[] tops = new int[height];
            int bestTop = 0, bestTopY = 0;
            long ySum = 0;

            for (int column = 0; column < 256; column++) {
                if (bambooTop[column] == Integer.MIN_VALUE) continue;
                int lx = column >> 4, lz = column & 15;
                int top = bambooTop[column], bottom = bambooBottom[column];
                total++;

                int n = ++tops[top - minY];
                if (n > bestTop) {
                    bestTop = n;
                    bestTopY = top;
                }

                if (bambooRequireMaxStage.get() && !bambooMaxStage[column]) continue;

                boolean underground = bottom < 50 && stoneCeiling(lx, top, lz);
                BlockState soil = at(lx, bottom - 1, lz);
                boolean farmSoil = soil != null && (soil.isOf(Blocks.SAND) || soil.isOf(Blocks.RED_SAND)
                    || soil.isOf(Blocks.MOSS_BLOCK) || soil.isOf(Blocks.MUD));
                boolean collector = collectorNear(lx, bottom, lz);

                if (underground || collector || (farmSoil && top - bottom + 1 >= bambooMinHeight.get())) {
                    farmed++;
                    ySum += bottom;
                    anyUnderground |= underground;
                }
            }

            if (bestTop >= 16 && bestTop >= total * 0.6) {
                return new Signal(bestTop + " bamboo stalks cut to one height", CAT_FARM, 0.5, false, true, bestTopY);
            }
            if (farmed < bambooMinColumns.get() || farmed == 0) return null;
            return new Signal(farmed + (anyUnderground ? " bamboo farmed underground" : " farmed bamboo"), CAT_FARM, 0.45, false, true, (int) (ySum / farmed));
        }

        boolean stoneCeiling(int lx, int top, int lz) {
            for (int y = top + 1; y <= Math.min(topY, top + 24); y++) {
                BlockState state = at(lx, y, lz);
                if (state.isAir()) continue;
                return state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.STONE_BRICKS)
                    || state.isOf(Blocks.COBBLED_DEEPSLATE) || state.isOf(Blocks.DEEPSLATE_BRICKS);
            }
            return false;
        }

        boolean collectorNear(int lx, int bottom, int lz) {
            BlockState under = at(lx, bottom - 2, lz);
            if (isCollector(under)) return true;
            for (Direction dir : Direction.Type.HORIZONTAL) {
                if (isCollector(at(lx + dir.getOffsetX(), bottom, lz + dir.getOffsetZ()))) return true;
                if (isCollector(at(lx + dir.getOffsetX(), bottom - 1, lz + dir.getOffsetZ()))) return true;
            }
            return false;
        }

        boolean isCollector(BlockState state) {
            return state != null && (state.isOf(Blocks.HOPPER) || state.isOf(Blocks.RAIL) || state.isOf(Blocks.POWERED_RAIL));
        }

        /**
         * Natural pods start at a random age, a third of them ripe. Six or more ripe at once, or pods
         * on stripped logs, underground, or on a trunk with no canopy above, mean someone was there.
         */
        Signal checkCocoa() {
            int farmPods = 0;
            for (int i = 0; i < cocoaPositions.size(); i++) {
                int p = cocoaPositions.getInt(i);
                int lx = px(p), lz = pz(p), y = py(p);
                BlockState pod = at(lx, y, lz);
                if (!pod.isOf(Blocks.COCOA)) continue;

                Direction facing = pod.get(CocoaBlock.FACING);
                int logX = lx + facing.getOffsetX(), logZ = lz + facing.getOffsetZ();
                BlockState log = at(logX, y, logZ);
                if (log == null) continue;

                boolean stripped = log.isOf(Blocks.STRIPPED_JUNGLE_LOG) || log.isOf(Blocks.STRIPPED_JUNGLE_WOOD);
                if (stripped || y < 50 || !canopyAbove(logX, y, logZ)) farmPods++;
            }

            if (farmPods >= 4) {
                return new Signal(farmPods + " farmed cocoa pods", CAT_FARM, 0.45, false, true, mean(growthYSum, growthYCount));
            }

            int minPods = Math.max(6, cocoaMinPods.get());
            if (cocoaRipe >= minPods && (double) cocoaMature / cocoaTotal >= cocoaMatureRatio.get()) {
                return new Signal(cocoaMature + "/" + cocoaTotal + " grown cocoa", CAT_GROWTH, 0.2, false, true, mean(growthYSum, growthYCount));
            }
            return null;
        }

        boolean canopyAbove(int lx, int y, int lz) {
            for (int yy = y + 1; yy <= Math.min(topY, y + 16); yy++) {
                BlockState state = at(lx, yy, lz);
                if (state.isIn(BlockTags.LOGS)) continue;
                return state.getBlock() instanceof LeavesBlock;
            }
            return false;
        }

        /** Natural nests start empty. Honey alone only means the chunk ticked; it takes backup to count. */
        Signal checkBees() {
            int hives = includeBeehives.get() ? effAll[K_BEEHIVE] : 0;
            boolean helpers = count[K_CAMPFIRE] > 0 || effAll[K_DISPENSER] > 0 || effAll[K_HOPPER] > 0 || count[K_HONEYCOMB] > 0;
            boolean nestsCount = nestsFull >= 2 || (nestsHoney > 0 && helpers) || nestsUG > 0;
            int matched = (nestsCount ? nestsHoney : 0) + hives;

            if (matched == 0 || matched < beeNestMinCount.get()) return null;

            String label = matched + (matched == 1 ? " active bee nest" : " active bee nests");
            if (hives > 0) return new Signal(label, CAT_FARM, 0.55, true, true, mean(growthYSum, growthYCount));
            return new Signal(label, CAT_FARM, nestsUG > 0 || helpers ? 0.4 : 0.25, false, true, mean(growthYSum, growthYCount));
        }

        /**
         * A spawner with hoppers, soul sand or tinted glass, which never generate near one, or with
         * signs, a slab floor or torches, which structures do have and so only count together.
         */
        Signal checkGrinder(IntArrayList spawners, boolean ancient, boolean stronghold) {
            if (effAll[K_SPAWNER] == 0) return null;

            List<String> strong = new ArrayList<>(3);
            List<String> weak = new ArrayList<>(3);
            if (effAll[K_HOPPER] > 0) strong.add("hoppers");
            if (!ancient && !nether && countUG[K_SOUL_SAND] > 0) strong.add("soul sand");
            if (effAll[K_TINTED_GLASS] > 0) strong.add("tinted glass");
            if (eff[K_SIGN] >= 2) weak.add("signs");
            if (!stronghold && countUG[K_SLAB] >= 8) weak.add("slabs");

            int torches = 0;
            for (int i = 0; i < notable.size(); i++) {
                int e = notable.getInt(i);
                if ((e >>> 17) != K_TORCH) continue;
                int p = e & 0x1FFFF;
                if (nearAny(spawners, px(p), py(p), pz(p), 4)) torches++;
            }
            if (torches >= 4) weak.add("torches");

            double confidence;
            if (!strong.isEmpty()) confidence = Math.min(0.85, 0.55 + 0.1 * (strong.size() + weak.size() - 1));
            else if (weak.size() >= 2) confidence = 0.4;
            else if (weak.size() == 1) confidence = 0.18;
            else return null;

            // One stray hopper near a dungeon is common on a griefed server, so it takes more to flag alone.
            boolean built = strong.size() >= 2 || (!strong.isEmpty() && strong.size() + weak.size() >= 3);

            List<String> mods = new ArrayList<>(strong);
            mods.addAll(weak);
            return new Signal("spawner grinder (" + String.join(" + ", mods) + ")", CAT_GRINDER, confidence, false, built, meanEff(K_SPAWNER));
        }

        /**
         * Vertical 1x1 or 2x1 runs of at least 18 enclosed blocks that end next to something built.
         *
         * @return {raid columns, access columns, raid bottom Y, access bottom Y}
         */
        int[] findShafts() {
            int[] result = new int[4];
            int[] prefix = new int[height + 1];
            for (int i = 0; i < height; i++) prefix[i + 1] = prefix[i] + playerY[i];

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    // An open shaft lowers its own column's surface, so start from the highest neighbour.
                    int start = surface[(lx << 4) | lz];
                    for (Direction dir : Direction.Type.HORIZONTAL) {
                        int nx = lx + dir.getOffsetX(), nz = lz + dir.getOffsetZ();
                        if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16) start = Math.max(start, surface[(nx << 4) | nz]);
                    }

                    int run = 0;
                    boolean climbable = false;

                    for (int y = start; y > floorY; y--) {
                        BlockState state = at(lx, y, lz);
                        boolean ladder = state.isOf(Blocks.LADDER) || state.isOf(Blocks.SCAFFOLDING) || state.isOf(Blocks.BUBBLE_COLUMN);
                        boolean open = state.isAir() || ladder;

                        if (open && enclosed(lx, y, lz)) {
                            run++;
                            climbable |= ladder;
                            continue;
                        }

                        if (run >= 18) recordShaft(result, prefix, y + 1, climbable);
                        run = 0;
                        climbable = false;
                    }

                    if (run >= 18) recordShaft(result, prefix, floorY + 1, climbable);
                }
            }
            return result;
        }

        boolean enclosed(int lx, int y, int lz) {
            int known = 0, solid = 0;
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockState side = at(lx + dir.getOffsetX(), y, lz + dir.getOffsetZ());
                if (side == null) continue;
                known++;
                if (!side.isAir() && side.getFluidState().isEmpty() && !side.isOf(Blocks.LADDER) && !side.isOf(Blocks.SCAFFOLDING)) solid++;
            }
            return known >= 2 && solid >= known - 1;
        }

        void recordShaft(int[] result, int[] prefix, int bottomY, boolean climbable) {
            int lo = Math.max(0, bottomY - 6 - minY), hi = Math.min(height, bottomY + 7 - minY);
            if (lo >= hi || prefix[hi] - prefix[lo] == 0) return;

            if (climbable) {
                result[1]++;
                result[3] = bottomY;
            } else {
                result[0]++;
                result[2] = bottomY;
            }
        }

        /** @return the chance the base is intact (1 = no damage seen) */
        double checkGrief(List<String> reasons, int[] shafts, boolean ruinedPortal) {
            double intact = 1;

            // Blast crater: anchors are the overworld's bomb, and obsidian away from any lava is a crystal pad.
            int anchors = effAll[K_ANCHOR];
            int obsidian = countUG[K_OBSIDIAN];
            double crater = 0;
            if (anchors > 0) crater = Math.min(0.45, 0.3 + 0.05 * (anchors - 1));
            if (obsidian >= 3 && count[K_LAVA] == 0 && !ruinedPortal && playerUG >= 2) crater = Math.max(crater, 0.2 + Math.min(0.15, obsidian * 0.01));
            if (crater > 0) {
                reasons.add("Blast Crater" + (anchors > 0 ? " (" + plural(anchors, "anchor", "anchors") + ")" : " (" + obsidian + " obsidian)"));
                intact *= 1 - crater;
            }

            // Hoppers emptying into air: whatever they fed was broken or blown up.
            int orphaned = 0;
            for (int i = 0; i < notable.size(); i++) {
                int e = notable.getInt(i);
                if ((e >>> 17) != K_HOPPER) continue;
                int p = e & 0x1FFFF;
                int lx = px(p), lz = pz(p), y = py(p);
                BlockState hopper = at(lx, y, lz);
                if (!(hopper.getBlock() instanceof HopperBlock)) continue;
                Direction out = hopper.get(HopperBlock.FACING);
                BlockState target = at(lx + out.getOffsetX(), y + out.getOffsetY(), lz + out.getOffsetZ());
                if (target != null && target.isAir()) orphaned++;
            }
            int stash = effAll[K_CHEST] + effAll[K_TRAPPED_CHEST] + effAll[K_BARREL];
            double hopperGrief = orphaned >= 2 ? Math.min(0.4, 0.25 + 0.05 * (orphaned - 2)) : (orphaned == 1 && stash <= 1 ? 0.15 : 0);
            if (hopperGrief > 0) {
                reasons.add("Orphaned Hoppers / Broken Storage (" + orphaned + ")");
                intact *= 1 - hopperGrief;
            }

            // Redstone parts left floating in the air after the machine around them went.
            int floating = 0;
            for (int i = 0; i < notable.size(); i++) {
                int e = notable.getInt(i);
                int k = e >>> 17;
                if (k != K_PISTON && k != K_OBSERVER && k != K_REDSTONE_COMP) continue;
                int p = e & 0x1FFFF;
                if (isFloating(px(p), py(p), pz(p))) floating++;
            }
            if (floating >= 2) {
                reasons.add("Broken Redstone (" + floating + " loose parts)");
                intact *= 1 - Math.min(0.35, 0.25 + 0.05 * (floating - 2));
            }

            // Flowing water or lava washing over what someone built. Caves are full of natural flows, so
            // it only counts where the flow actually touches the built blocks.
            int soaked = 0;
            boolean lavaSoaked = false;
            if (flowingFluids > 0) {
                for (int i = 0; i < playerBlocks.size(); i++) {
                    int p = playerBlocks.getInt(i);
                    int touch = flowTouching(px(p), py(p), pz(p));
                    if (touch != 0) {
                        soaked++;
                        if (touch == 2) lavaSoaked = true;
                    }
                }
            }
            if (soaked >= 3 || (soaked >= 2 && lavaSoaked)) {
                reasons.add("Fluid Grief (" + soaked + " blocks under " + (lavaSoaked ? "lava" : "water") + ")");
                intact *= 1 - (lavaSoaked ? 0.3 : 0.2);
            }

            if (shafts != null && shafts[0] > 0) {
                reasons.add("Raid Shaft to Y " + shafts[2]);
                intact *= 1 - (shafts[0] >= 3 ? 0.35 : 0.25);
            }

            // A dungeon floor with collection blocks but no spawner left in it, here or next door.
            if (countUG[K_MOSSY_COBBLE] >= 10 && count[K_SPAWNER] == 0 && (neighborCtx & CTX_SPAWNER) == 0
                && (effAll[K_HOPPER] > 0 || countUG[K_SLAB] >= 4 || eff[K_SIGN] > 0)) {
                reasons.add("Raided Grinder (spawner gone)");
                intact *= 1 - 0.35;
            }

            return intact;
        }

        /** @return 0 for no flowing fluid around the block, 1 for water, 2 for lava */
        int flowTouching(int lx, int y, int lz) {
            int found = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockState state = at(lx + dx, y + dy, lz + dz);
                        if (state == null || state.getFluidState().isEmpty() || state.getFluidState().isStill()) continue;
                        if (state.isOf(Blocks.LAVA)) return 2;
                        found = 1;
                    }
                }
            }
            return found;
        }

        boolean isFloating(int lx, int y, int lz) {
            int known = 0, air = 0;
            for (Direction dir : Direction.values()) {
                BlockState side = at(lx + dir.getOffsetX(), y + dir.getOffsetY(), lz + dir.getOffsetZ());
                if (side == null) continue;
                known++;
                if (side.isAir()) air++;
            }
            return known >= 4 && air >= known - 1;
        }
    }

    private static void addIfPresent(List<Signal> signals, Signal signal) {
        if (signal != null) signals.add(signal);
    }

    private static int count(boolean... flags) {
        int n = 0;
        for (boolean flag : flags) if (flag) n++;
        return n;
    }

    private static String plural(int n, String one, String many) {
        return n == 1 ? one : n + " " + many;
    }

    /** A flagged chunk and why. Immutable, so it can be handed from the scan thread to the render thread as is. */
    public static final class FlaggedChunk {
        public final int chunkX, chunkZ;
        /** Every reason joined, as shown in notifications and saved finds. */
        public final String reason;
        public final List<String> reasons;
        public final int baseConfidence;
        public final int falsePositiveProb;
        public final int griefedProb;
        public final List<String> griefReasons;
        /** Where the suspicious blocks are, weighted by how strong each signal is. */
        public final int focusY;
        /** Restored from an earlier session rather than scanned now. */
        public final boolean fromSave;
        /** False when the scores were guessed from an old save that did not record them. */
        public final boolean measured;
        /**
         * Scanned while the server was hiding everything below the deepslate (DonutSMP does this while
         * you are above it), so only what is above was seen. Dig down and relog to load the rest.
         */
        public final boolean deepHidden;

        FlaggedChunk(int chunkX, int chunkZ, List<String> reasons, int baseConfidence, int falsePositiveProb,
                     int griefedProb, List<String> griefReasons, int focusY, boolean fromSave, boolean measured, boolean deepHidden) {
            this.deepHidden = deepHidden;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.reasons = List.copyOf(reasons);
            this.reason = String.join(", ", this.reasons);
            this.baseConfidence = baseConfidence;
            this.falsePositiveProb = falsePositiveProb;
            this.griefedProb = griefedProb;
            this.griefReasons = List.copyOf(griefReasons);
            this.focusY = focusY;
            this.fromSave = fromSave;
            this.measured = measured;
        }

        /** The same find, marked as looked at below the deepslate. */
        FlaggedChunk checked() {
            return new FlaggedChunk(chunkX, chunkZ, reasons, baseConfidence, falsePositiveProb, griefedProb, griefReasons,
                focusY, fromSave, measured, false);
        }

        String toNote() {
            StringBuilder note = new StringBuilder(reason);
            if (!griefReasons.isEmpty()) note.append(" {grief: ").append(String.join("; ", griefReasons)).append('}');
            if (deepHidden) note.append(' ').append(SAVED_DEEP_HIDDEN);
            note.append(" [base ").append(baseConfidence).append("%, fp ").append(falsePositiveProb)
                .append("%, grief ").append(griefedProb).append("%, y ").append(focusY).append(']');
            return note.toString();
        }

        /** Saves written before scoring existed only have the reasons, so score those from their wording. */
        static FlaggedChunk fromSave(int chunkX, int chunkZ, String note) {
            String text = note == null ? "" : note;
            int base, fp, grief, y;
            boolean measured;

            boolean deepHidden = text.contains(SAVED_DEEP_HIDDEN);
            text = text.replace(" " + SAVED_DEEP_HIDDEN, "");

            Matcher metrics = SAVED_METRICS.matcher(text);
            if (metrics.find()) {
                base = Integer.parseInt(metrics.group(1));
                fp = Integer.parseInt(metrics.group(2));
                grief = Integer.parseInt(metrics.group(3));
                y = Integer.parseInt(metrics.group(4));
                text = text.substring(0, metrics.start());
                measured = true;
            } else {
                boolean growthOnly = !text.isBlank();
                for (String part : text.split(",")) {
                    String p = part.toLowerCase();
                    if (!(p.contains("kelp") || p.contains("vine") || p.contains("bamboo") || p.contains("cocoa")
                        || p.contains("amethyst") || p.contains("bee nest"))) {
                        growthOnly = false;
                        break;
                    }
                }
                base = growthOnly ? 20 : 50;
                fp = growthOnly ? 70 : 30;
                grief = 0;
                y = 64;
                measured = false;
            }

            List<String> griefReasons = new ArrayList<>();
            Matcher griefMatch = SAVED_GRIEF.matcher(text);
            if (griefMatch.find()) {
                for (String g : griefMatch.group(1).split(";")) if (!g.isBlank()) griefReasons.add(g.trim());
                text = text.substring(0, griefMatch.start()) + text.substring(griefMatch.end());
            }

            List<String> reasons = new ArrayList<>();
            for (String part : text.split(",")) if (!part.isBlank()) reasons.add(part.trim());
            if (reasons.isEmpty()) reasons.add("saved find");

            return new FlaggedChunk(chunkX, chunkZ, reasons, base, fp, grief, griefReasons, y, true, measured, deepHidden);
        }
    }

    // Rendering

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        float dt = clock.tick();
        double eyeY = mc.player.getEyeY();
        smoothEyeY = Double.isNaN(smoothEyeY) ? eyeY : EmberAnim.approach(smoothEyeY, eyeY, dt, 0.25);

        double planeY = followPlayerY.get() ? Math.floor(mc.player.getY()) : renderY.get();

        double queryRange = Math.max(planeRange.get(), Math.max(renderCenterDot.get() ? dotRange.get() : 0, tracers.get() ? tracerRange.get() : 0));
        List<FlaggedChunk> visible;
        synchronized (flagged) {
            visible = renderIndex.nearby(mc.player.getChunkPos().x, mc.player.getChunkPos().z, (int) Math.ceil(queryRange / 16) + 2);
        }
        double planeDistance = (double) planeRange.get() * planeRange.get();
        for (var fc : visible) {
            double dx = (fc.chunkX * 16.0 + 8) - mc.player.getX(), dz = (fc.chunkZ * 16.0 + 8) - mc.player.getZ();
            if (dx * dx + dz * dz <= planeDistance) drawChunk(event, fc, planeY);
        }

        if (tracers.get()) drawTracers(event, visible);

        if (!renderCenterDot.get()) {
            hovered = null;
            return;
        }

        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        Vec3d look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        double range = dotRange.get();
        double bestAngle = lookAtCard.get() ? lookAtAngle.get() : -1;
        FlaggedChunk best = null;

        for (FlaggedChunk fc : visible) {
            double cx = (fc.chunkX << 4) + 8.0, cz = (fc.chunkZ << 4) + 8.0;
            double dx = cx - cam.x, dz = cz - cam.z;
            if (dx * dx + dz * dz > range * range) continue;

            double cy = dotY(fc);
            double dy = cy - cam.y;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < 0.5) continue;

            double cos = (look.x * dx + look.y * dy + look.z * dz) / dist;
            double angle = Math.toDegrees(Math.acos(MathHelper.clamp(cos, -1, 1)));
            if (angle < bestAngle) {
                bestAngle = angle;
                best = fc;
            }
        }

        if (best != hovered) shellAnim = 0;
        hovered = best;
        if (best != null) cardChunk = best;
        shellAnim = EmberAnim.approach(shellAnim, best != null ? 1f : 0f, dt, 0.08);

        for (FlaggedChunk fc : visible) {
            double cx = (fc.chunkX << 4) + 8.0, cz = (fc.chunkZ << 4) + 8.0;
            double dx = cx - cam.x, dz = cz - cam.z;
            if (dx * dx + dz * dz > range * range) continue;
            drawDot(event, fc, cx, dotY(fc), cz, cam, fc == best);
        }
    }

    private double dotY(FlaggedChunk fc) {
        return switch (dotYMode.get()) {
            case AnomalyY -> fc.focusY + 0.5;
            case PlayerEyeY -> Double.isNaN(smoothEyeY) ? fc.focusY + 0.5 : smoothEyeY;
            case FixedY -> renderY.get() + 0.5;
        };
    }

    /** Lines from the crosshair to each chunk's center dot, so a find stays easy to get back to. */
    private void drawTracers(Render3DEvent event, List<FlaggedChunk> visible) {
        double range = tracerRange.get();
        double px = mc.player.getX(), pz = mc.player.getZ();

        for (FlaggedChunk fc : visible) {
            double cx = (fc.chunkX << 4) + 8.0, cz = (fc.chunkZ << 4) + 8.0;
            double dx = cx - px, dz = cz - pz;
            if (dx * dx + dz * dz > range * range) continue;

            Color tone = dotColor(fc);
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, cx, dotY(fc), cz,
                tone != null ? tone : tracerColor.get());
        }
    }

    private void drawChunk(Render3DEvent event, FlaggedChunk fc, double y) {
        double x = fc.chunkX * 16.0;
        double z = fc.chunkZ * 16.0;
        event.renderer.box(x, y, z, x + 16, y, z + 16, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    private Color dotColor(FlaggedChunk fc) {
        if (fc.griefedProb >= 60) return fc.griefedProb >= 80 ? CORAL : AMBER;
        if (fc.baseConfidence >= 70) return EmberPalette.accent();
        return null;
    }

    private void drawDot(Render3DEvent event, FlaggedChunk fc, double cx, double cy, double cz, Vec3d cam, boolean target) {
        double dist = Math.sqrt((cx - cam.x) * (cx - cam.x) + (cy - cam.y) * (cy - cam.y) + (cz - cam.z) * (cz - cam.z));
        // Grows slowly with distance so it stays findable far away without filling the view up close.
        double r = MathHelper.clamp(0.2 + dist * 0.0045, 0.2, 1.6);

        Color tone = dotColor(fc);
        Color side = tone != null ? tone : sideColor.get();
        Color line = tone != null ? tone : lineColor.get();

        float hover = target ? shellAnim : 0f;
        Color core = new Color(side.r, side.g, side.b, tone != null ? 150 : Math.max(110, side.a * 2));
        Color edge = new Color(line.r, line.g, line.b, Math.min(255, (int) (line.a * (0.75 + 0.25 * hover))));
        Color tick = new Color(line.r, line.g, line.b, (int) (line.a * 0.45));

        event.renderer.box(cx - r, cy - r, cz - r, cx + r, cy + r, cz + r, core, edge, ShapeMode.Both, 0);

        // Diamond shell around the core.
        double s = r * (1.8 + 0.55 * hover);
        double top = cy + s, bottom = cy - s;
        double[][] ring = {{cx + s, cz}, {cx, cz + s}, {cx - s, cz}, {cx, cz - s}};
        for (int i = 0; i < 4; i++) {
            double[] a = ring[i], b = ring[(i + 1) % 4];
            event.renderer.line(a[0], cy, a[1], b[0], cy, b[1], edge);
            event.renderer.line(a[0], cy, a[1], cx, top, cz, edge);
            event.renderer.line(a[0], cy, a[1], cx, bottom, cz, edge);
        }

        double reach = s * 2.4;
        event.renderer.line(cx, top, cz, cx, top + reach, cz, tick);
        event.renderer.line(cx, bottom, cz, cx, bottom - reach, cz, tick);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        float dt = cardClock.tick();
        boolean show = lookAtCard.get() && renderCenterDot.get() && hovered != null
            && mc.currentScreen == null && !mc.options.hudHidden;
        hoverAnim = EmberAnim.approach(hoverAnim, show ? 1f : 0f, dt, 0.07);

        FlaggedChunk fc = cardChunk;
        if (fc == null || hoverAnim <= 0.01f || mc.player == null) return;
        if (mc.currentScreen != null || mc.options.hudHidden) return;

        double cx = (fc.chunkX << 4) + 8.0, cz = (fc.chunkZ << 4) + 8.0, cy = dotY(fc);
        Vector3d pos = new Vector3d(cx, cy, cz);
        if (!NametagUtils.to2D(pos, 1, false)) return;

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        double dist = Math.sqrt((cx - cam.x) * (cx - cam.x) + (cy - cam.y) * (cy - cam.y) + (cz - cam.z) * (cz - cam.z));

        HudRenderer r = HudRenderer.INSTANCE;
        r.begin(event.drawContext);
        try {
            drawCard(r, fc, pos.x, pos.y, dist, hoverAnim);
        } finally {
            r.end();
        }
    }

    private void drawCard(HudRenderer r, FlaggedChunk fc, double dotX, double dotY, double distance, float a) {
        // Read at a glance mid-flight, so it sits a size above the regular HUD text.
        double s = cardScale.get() * 1.25;
        double pad = 9 * s;
        double titleScale = 0.8 * s, bodyScale = 0.68 * s, smallScale = 0.6 * s;
        double titleH = r.textHeight(true, titleScale);
        double bodyH = r.textHeight(false, bodyScale);
        double smallH = r.textHeight(false, smallScale);

        // The theme's dim text tones vanish on frosted glass, so secondary text is bright text faded instead.
        Color accent = EmberPalette.accent();
        Color bright = EmberPalette.textBright();
        Color dim = fade(bright, 0.72f);
        Color faint = fade(bright, 0.55f);

        String title = "CHUNK [" + fc.chunkX + ", " + fc.chunkZ + "]";
        String distLabel = Math.round(distance) + "m";
        String yLabel = "Y " + fc.focusY;

        String status;
        Color statusColor;
        if (fc.griefedProb >= 60) {
            status = "LIKELY GRIEFED / RAIDED";
            statusColor = fc.griefedProb >= 80 ? CORAL : AMBER;
        } else if (fc.baseConfidence >= 70) {
            status = "HIGH CONFIDENCE BASE";
            statusColor = EMERALD;
        } else if (fc.baseConfidence >= 45) {
            status = "LIKELY BASE";
            statusColor = accent;
        } else if (fc.falsePositiveProb >= 60) {
            status = "POSSIBLE WORLDGEN FP";
            statusColor = CORAL;
        } else {
            status = "WORTH A LOOK";
            statusColor = SLATE;
        }
        if (fc.fromSave) status += fc.measured ? "  \u00B7  SAVED" : "  \u00B7  OLD SAVE";
        // Only what is above the deepslate was sent; the base itself, if any, is still unseen.
        if (fc.deepHidden) status += "  \u00B7  DIG BELOW Y 0 + RELOG";

        int maxSignals = 5;
        List<String> signals = fc.reasons.size() > maxSignals ? fc.reasons.subList(0, maxSignals) : fc.reasons;
        int hiddenSignals = fc.reasons.size() - signals.size();

        // Measure.
        double pillPadX = 5 * s;
        double badgeH = smallH + 3 * s;
        double distW = r.textWidth(distLabel, smallScale) + pillPadX * 2;
        double yW = r.textWidth(yLabel, smallScale) + pillPadX * 2;
        double dotSize = 6 * s;
        double headerW = dotSize + 6 * s + r.textWidth(title, true, titleScale) + 12 * s + distW + 4 * s + yW;

        double w = Math.max(232 * s, pad * 2 + headerW);
        for (String sig : signals) w = Math.max(w, pad * 2 + 10 * s + r.textWidth(sig, bodyScale));
        for (String g : fc.griefReasons) w = Math.max(w, pad * 2 + 12 * s + r.textWidth(g, bodyScale));
        w = Math.min(w, 330 * s);

        double metricH = bodyH + 3 * s + 3 * s + 6 * s;
        double statusH = smallH + 5 * s;
        double signalRowH = bodyH + 3 * s;

        double h = pad + titleH + 6 * s + 1 + 7 * s + statusH + 8 * s + metricH * 3
            + smallH + 5 * s + signalRowH * (signals.size() + (hiddenSignals > 0 ? 1 : 0)) + pad;
        if (!fc.griefReasons.isEmpty()) h += 6 * s + smallH + 5 * s + signalRowH * fc.griefReasons.size();

        double radius = 7 * s;
        int fbW = mc.getWindow().getFramebufferWidth(), fbH = mc.getWindow().getFramebufferHeight();
        double slide = (1 - EmberAnim.easeOut(a)) * 10 * s;

        double x = dotX + 18 * s + slide;
        if (x + w > fbW - 8) x = dotX - 18 * s - w - slide;
        x = MathHelper.clamp(x, 8, Math.max(8, fbW - w - 8));
        double y = MathHelper.clamp(dotY - h / 2, 8, Math.max(8, fbH - h - 8));

        // Leader from the dot to the card.
        double anchorX = x > dotX ? x : x + w;
        r.line(dotX, dotY, anchorX, y + h / 2, fade(accent, a * 0.55f));
        double ring = 4 * s;
        r.roundedQuad(dotX - ring, dotY - ring, ring * 2, ring * 2, ring, fade(accent, a * 0.35f));
        r.roundedQuad(dotX - ring / 2, dotY - ring / 2, ring, ring, ring / 2, fade(accent, a));

        r.dropShadow(x, y, w, h, radius, 16 * s, a);
        EmberStrip.panel(r, x, y, w, h, radius, a);

        double cy = y + pad;

        // Header.
        double dotY0 = cy + (titleH - dotSize) / 2;
        r.softGlow(x + pad, dotY0, dotSize, dotSize, dotSize / 2, 4 * s, fade(accent, a * 0.6f));
        r.roundedQuad(x + pad, dotY0, dotSize, dotSize, dotSize / 2, fade(accent, a));
        r.text(title, x + pad + dotSize + 6 * s, cy, fade(bright, a), true, titleScale);

        double bx = x + w - pad - yW;
        double by = cy + (titleH - badgeH) / 2;
        badge(r, yLabel, bx, by, yW, badgeH, smallScale, bright, a);
        bx -= 4 * s + distW;
        badge(r, distLabel, bx, by, distW, badgeH, smallScale, bright, a);

        cy += titleH + 6 * s;
        divider(r, x + pad, cy, w - pad * 2, accent, a);
        cy += 1 + 7 * s;

        // Classification.
        double statusW = r.textWidth(status, smallScale) + 12 * s;
        r.roundedQuad(x + pad, cy, statusW, statusH, statusH / 2, fade(statusColor, a * 0.16f));
        r.roundedQuad(x + pad + 5 * s, cy + (statusH - 3 * s) / 2, 3 * s, 3 * s, 1.5 * s, fade(statusColor, a));
        r.text(status, x + pad + 10 * s, cy + (statusH - smallH) / 2, fade(lighten(statusColor), a), false, smallScale);
        cy += statusH + 8 * s;

        // Scores.
        Color baseColor = fc.baseConfidence >= 70 ? EMERALD : accent;
        Color fpColor = fc.falsePositiveProb < 30 ? MUTED_GREEN : (fc.falsePositiveProb >= 60 ? CORAL : AMBER);
        Color griefColor = fc.griefedProb < 25 ? MUTED_GREEN : (fc.griefedProb >= 60 ? CORAL : AMBER);
        String suffix = fc.measured ? "%" : "% (est.)";

        metric(r, "Base confidence", fc.baseConfidence, suffix, baseColor, x + pad, cy, w - pad * 2, bodyScale, bodyH, s, dim, bright, a);
        cy += metricH;
        metric(r, "False positive", fc.falsePositiveProb, suffix, fpColor, x + pad, cy, w - pad * 2, bodyScale, bodyH, s, dim, bright, a);
        cy += metricH;
        metric(r, "Griefed", fc.griefedProb, suffix, griefColor, x + pad, cy, w - pad * 2, bodyScale, bodyH, s, dim, bright, a);
        cy += metricH;

        // Signals.
        r.text("SIGNALS", x + pad, cy, fade(faint, a), false, smallScale);
        cy += smallH + 5 * s;
        double textMax = w - pad * 2 - 10 * s;
        for (String sig : signals) {
            double bullet = 3 * s;
            r.roundedQuad(x + pad + 1 * s, cy + (bodyH - bullet) / 2, bullet, bullet, bullet / 2, fade(accent, a * 0.85f));
            r.text(fit(r, sig, textMax, bodyScale), x + pad + 10 * s, cy, fade(bright, a * 0.92f), false, bodyScale);
            cy += signalRowH;
        }
        if (hiddenSignals > 0) {
            r.text("+" + hiddenSignals + " more", x + pad + 10 * s, cy, fade(faint, a), false, bodyScale);
            cy += signalRowH;
        }

        // Grief indicators.
        if (!fc.griefReasons.isEmpty()) {
            cy += 6 * s;
            Color warn = fc.griefedProb >= 60 ? CORAL : AMBER;
            r.roundedQuad(x + pad - 4 * s, cy - 3 * s, w - pad * 2 + 8 * s,
                smallH + 5 * s + signalRowH * fc.griefReasons.size() + 3 * s, 4 * s, fade(warn, a * 0.08f));
            r.text("GRIEF INDICATORS", x + pad, cy, fade(lighten(warn), a), false, smallScale);
            cy += smallH + 5 * s;
            for (String g : fc.griefReasons) {
                double t = 6 * s;
                double ty = cy + (bodyH - t) / 2;
                r.triangle(x + pad, ty + t, x + pad + t, ty + t, x + pad + t / 2, ty, fade(warn, a));
                r.text(fit(r, g, w - pad * 2 - 12 * s, bodyScale), x + pad + 12 * s, cy, fade(bright, a * 0.92f), false, bodyScale);
                cy += signalRowH;
            }
        }
    }

    private void metric(HudRenderer r, String label, int value, String suffix, Color fill, double x, double y, double w,
                        double scale, double textH, double s, Color dim, Color bright, float a) {
        String text = value + suffix;
        r.text(label, x, y, fade(dim, a), false, scale);
        double vw = r.textWidth(text, scale);
        r.text(text, x + w - vw, y, fade(bright, a), false, scale);

        double barY = y + textH + 3 * s;
        double barH = 3 * s;
        r.roundedQuad(x, barY, w, barH, barH / 2, fade(EmberPalette.track(), a * 0.9f));
        double fw = w * MathHelper.clamp(value / 100.0, 0, 1);
        if (fw >= barH) r.roundedQuad(x, barY, fw, barH, barH / 2, fade(fill, a));
    }

    private void badge(HudRenderer r, String text, double x, double y, double w, double h, double scale, Color color, float a) {
        r.roundedQuad(x, y, w, h, h / 2, fade(EmberPalette.control(), a * 0.9f));
        double tw = r.textWidth(text, scale);
        r.text(text, x + (w - tw) / 2, y + (h - r.textHeight(false, scale)) / 2, fade(color, a), false, scale);
    }

    private void divider(HudRenderer r, double x, double y, double w, Color accent, float a) {
        Color base = fade(EmberPalette.divider(), a * 0.7f);
        r.quad(x, y, w, 1, base);
        double lead = Math.min(w * 0.35, 60);
        Color hot = fade(accent, a * 0.8f);
        Color clear = new Color(accent.r, accent.g, accent.b, 0);
        r.quad(x, y, lead, 1, hot, clear, clear, hot);
    }

    private static String fit(HudRenderer r, String text, double max, double scale) {
        if (r.textWidth(text, scale) <= max) return text;
        String out = text;
        while (out.length() > 1 && r.textWidth(out + "...", scale) > max) out = out.substring(0, out.length() - 1);
        return out + "...";
    }

    /** Halfway to white, so coloured text stays readable on a tinted pill of the same colour. */
    private static Color lighten(Color c) {
        return new Color((c.r + 255) / 2, (c.g + 255) / 2, (c.b + 255) / 2, c.a);
    }

    private static Color fade(Color c, float a) {
        return new Color(c.r, c.g, c.b, MathHelper.clamp((int) (c.a * a), 0, 255));
    }

    @Override
    public String getInfoString() {
        return renderIndex.info();
    }
}
