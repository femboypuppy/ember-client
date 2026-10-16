package meteordevelopment.meteorclient.systems.modules.donut;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
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
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;

/**
 * Finds chunks showing signs of a player, for base hunting. Three kinds of evidence:
 * <ul>
 *     <li>Growth that only happens while a player keeps the area loaded: amethyst geodes with every bud grown into a
 *     cluster, kelp grown to the surface, long vines and cave vines, tall bamboo, ripe cocoa, bee nests with honey.</li>
 *     <li>Blocks that only come from players: sideways deepslate, cobbled deepslate below Y 0, storage deep
 *     underground, beehives.</li>
 *     <li>Mined geodes: plenty of bare budding amethyst with every crystal broken off.</li>
 * </ul>
 * Growth happens everywhere within the server's simulation distance of a player, so by default it is added up over
 * that radius around each chunk and only the chunk where the total peaks is marked: the spot a player would stand
 * on to keep all of it growing. A geode in range that is still growing rules a spot out, because a player standing
 * there for long would have finished it.
 */
public class SusChunkFinder extends Module {
    private static final String FIND_TYPE = "sus-chunk";
    private static final String MINED_TYPE = "mined-geode";
    // A hotspot has to hold this long before it is announced, so it does not jump around while chunks load.
    private static final int SETTLE_TICKS = 40;
    private static final int GEODE_MAX_Y = 47;

    public enum Sensitivity {
        Low(1.5, 10),
        Medium(1.0, 6),
        High(0.6, 3);

        /** Multiplier for every count threshold (higher = stricter). */
        final double scale;
        /** Combined growth needed around a chunk to call it a base. */
        final int heat;

        Sensitivity(double scale, int heat) {
            this.scale = scale;
            this.heat = heat;
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
        .description("High finds more chunks but also more false alarms. Low only flags strong evidence.")
        .defaultValue(Sensitivity.Medium)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> amethyst = sgGeneral.add(new BoolSetting.Builder()
        .name("amethyst")
        .description("Geodes where the buds have grown into full clusters, which only happens while a player keeps the area loaded.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> plants = sgGeneral.add(new BoolSetting.Builder()
        .name("plant-growth")
        .description("Kelp grown to the surface, long vines and cave vines, tall bamboo, ripe cocoa and bee nests filled with honey.")
        .defaultValue(true)
        .onChanged(v -> rescanLoadedChunks())
        .build()
    );

    private final Setting<Boolean> playerBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("player-blocks")
        .description("Sideways deepslate, cobbled deepslate below Y 0, storage deep underground and beehives. These mark the exact chunk.")
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

    private final Setting<Boolean> pinpoint = sgGeneral.add(new BoolSetting.Builder()
        .name("find-base-center")
        .description("Add up growth around each chunk and only mark the chunk at the center, where a player would stand. Off marks every chunk with enough growth.")
        .defaultValue(true)
        .onChanged(v -> rebuildFields())
        .build()
    );

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance")
        .description("The server's simulation distance in chunks. Things only grow this close to a player.")
        .defaultValue(5)
        .range(2, 12)
        .sliderRange(2, 12)
        .visible(pinpoint::get)
        .onChanged(v -> rebuildFields())
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

    /** What one chunk scan found. Growth is spread over the area by the main thread. */
    private record Report(int amethystGrowth, int plantGrowth, boolean stillGrowing, String growthWhy, String directWhy, String minedWhy) {
        boolean growthQualifies() {
            return growthWhy != null;
        }
    }

    // Main thread only from here on.
    private final Long2ObjectOpenHashMap<Report> reports = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap amethystHeat = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap plantHeat = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap vetoes = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap candidateSince = new Long2IntOpenHashMap();
    private int appliedRadius = -1;

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
    private boolean dirty;
    private int ticks;
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

        for (FindsStorage.Find find : FindsStorage.get(context, FIND_TYPE)) saved.add(ChunkPos.toLong(find.x >> 4, find.z >> 4));
        for (FindsStorage.Find find : FindsStorage.get(context, MINED_TYPE)) savedMined.add(ChunkPos.toLong(find.x >> 4, find.z >> 4));

        rescanLoadedChunks();
    }

    @Override
    public void onDeactivate() {
        clear();
    }

    private void clear() {
        generation++;
        reports.clear();
        amethystHeat.clear();
        plantHeat.clear();
        vetoes.clear();
        candidateSince.clear();
        flagged.clear();
        mined.clear();
        saved.clear();
        savedMined.clear();
        announced.clear();
        pendingRescan.clear();
        appliedRadius = radius();
        dirty = false;
    }

    private int radius() {
        return pinpoint.get() ? simulationDistance.get() : 0;
    }

    private void rescanLoadedChunks() {
        if (!isActive() || mc.world == null) return;
        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk levelChunk) queueScan(levelChunk);
        }
    }

    /** Re-spreads every stored report, after the radius or mode changed. */
    private void rebuildFields() {
        if (!isActive()) return;
        amethystHeat.clear();
        plantHeat.clear();
        vetoes.clear();
        candidateSince.clear();
        appliedRadius = radius();
        for (var entry : reports.long2ObjectEntrySet()) spread(entry.getLongKey(), entry.getValue(), 1);
        dirty = true;
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
        if (mc.world == null || mc.player == null) return;

        if (mc.world.getDimension() != lastDimension) {
            onActivate();
            return;
        }

        ticks++;
        if (++rescanTimer >= 20) {
            rescanTimer = 0;
            for (long key : pendingRescan) {
                Chunk chunk = mc.world.getChunk(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key), ChunkStatus.FULL, false);
                if (chunk instanceof WorldChunk levelChunk) queueScan(levelChunk);
            }
            pendingRescan.clear();
            pruneFarReports();
            dirty = true;
        }

        if (dirty && ticks % 5 == 0) {
            dirty = false;
            recompute();
        }
    }

    private void queueScan(WorldChunk chunk) {
        int gen = generation;
        Sensitivity sens = sensitivity.get();

        worker.submit(() -> {
            if (!isActive()) return;
            Report report = scan(chunk, sens);
            long key = chunk.getPos().toLong();
            mc.execute(() -> {
                if (gen == generation && isActive()) apply(key, report);
            });
        });
    }

    private void apply(long key, Report report) {
        Report old = reports.put(key, report);
        if (old != null) spread(key, old, -1);
        spread(key, report, 1);
        dirty = true;
    }

    /** Adds (sign 1) or removes (sign -1) a chunk's growth and veto over the area around it. */
    private void spread(long key, Report report, int sign) {
        int r = appliedRadius;
        int amethystPoints = report.stillGrowing ? 0 : report.amethystGrowth;
        int plantPoints = report.plantGrowth;
        if (amethystPoints == 0 && plantPoints == 0 && !report.stillGrowing) return;

        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
        forArea(cx, cz, r, area -> {
            if (amethystPoints > 0) add(amethystHeat, area, sign * amethystPoints);
            if (plantPoints > 0) add(plantHeat, area, sign * plantPoints);
            if (report.stillGrowing) add(vetoes, area, sign);
        });
    }

    private static void forArea(int cx, int cz, int r, LongConsumer action) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) action.accept(ChunkPos.toLong(cx + dx, cz + dz));
        }
    }

    private static void add(Long2IntOpenHashMap map, long key, int amount) {
        int value = map.get(key) + amount;
        if (value > 0) map.put(key, value);
        else map.remove(key);
    }

    /** Forget scans far outside the loaded area so a long flight does not keep growing memory. */
    private void pruneFarReports() {
        int limit = mc.options.getViewDistance().getValue() + appliedRadius + 8;
        ChunkPos at = mc.player.getChunkPos();
        var it = reports.long2ObjectEntrySet().iterator();
        boolean removed = false;
        while (it.hasNext()) {
            var entry = it.next();
            long key = entry.getLongKey();
            if (Math.max(Math.abs(ChunkPos.getPackedX(key) - at.x), Math.abs(ChunkPos.getPackedZ(key) - at.z)) <= limit) continue;
            spread(key, entry.getValue(), -1);
            it.remove();
            removed = true;
        }
        if (removed) candidateSince.keySet().removeIf(key -> !reports.containsKey(key));
    }

    private void recompute() {
        Long2ObjectOpenHashMap<String> nowFlagged = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<String> nowMined = new Long2ObjectOpenHashMap<>();

        for (var entry : reports.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            Report report = entry.getValue();
            if (report.directWhy != null) nowFlagged.put(key, report.directWhy);
            if (report.minedWhy != null) nowMined.put(key, report.minedWhy);
            if (!pinpoint.get() && report.growthQualifies() && !nowFlagged.containsKey(key)) {
                nowFlagged.put(key, report.growthWhy);
            }
        }

        if (pinpoint.get()) findCenters(nowFlagged);

        flagged.clear();
        flagged.putAll(nowFlagged);
        mined.clear();
        mined.putAll(nowMined);

        for (var entry : flagged.long2ObjectEntrySet()) announce(entry.getLongKey(), entry.getValue(), false);
        for (var entry : mined.long2ObjectEntrySet()) announce(entry.getLongKey(), entry.getValue(), true);
    }

    /** Marks chunks where the growth around them peaks, once enough of their surroundings has been scanned. */
    private void findCenters(Long2ObjectOpenHashMap<String> out) {
        int r = appliedRadius;
        int threshold = sensitivity.get().heat;
        Long2IntOpenHashMap score = new Long2IntOpenHashMap();

        LongOpenHashSet keys = new LongOpenHashSet(amethystHeat.keySet());
        keys.addAll(plantHeat.keySet());
        for (long key : keys) {
            int value = (vetoes.containsKey(key) ? 0 : amethystHeat.get(key)) + plantHeat.get(key);
            if (value >= threshold && loaded(key) && mostlyScanned(key, r)) score.put(key, value);
        }

        LongOpenHashSet live = new LongOpenHashSet();
        for (var entry : score.long2IntEntrySet()) {
            long key = entry.getLongKey();
            int value = entry.getIntValue();
            if (!isPeak(score, key, value, r)) continue;

            live.add(key);
            int since = candidateSince.containsKey(key) ? candidateSince.get(key) : ticks;
            candidateSince.put(key, since);
            if (ticks - since < SETTLE_TICKS) continue;

            int am = vetoes.containsKey(key) ? 0 : amethystHeat.get(key);
            int pl = plantHeat.get(key);
            List<String> parts = new ArrayList<>(2);
            if (am > 0) parts.add(am + " grown amethyst");
            if (pl > 0) parts.add(pl + " plant growth");
            out.putIfAbsent(key, "growth center (" + String.join(", ", parts) + " within " + r + " chunks)");
        }
        candidateSince.keySet().retainAll(live);
    }

    private static boolean isPeak(Long2IntOpenHashMap field, long key, int value, int reach) {
        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx == 0 && dz == 0) continue;
                long other = ChunkPos.toLong(cx + dx, cz + dz);
                int v = field.get(other);
                // Ties go to the lower key so a flat plateau gives one mark, not a cluster.
                if (v > value || (v == value && other < key)) return false;
            }
        }
        return true;
    }

    private boolean loaded(long key) {
        return mc.world != null && mc.world.getChunkManager().isChunkLoaded(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key));
    }

    /** Most of the surrounding area has been scanned, so the total is not missing half its evidence. */
    private boolean mostlyScanned(long key, int r) {
        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
        int scanned = 0, total = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                total++;
                if (reports.containsKey(ChunkPos.toLong(cx + dx, cz + dz))) scanned++;
            }
        }
        return scanned * 10 >= total * 8;
    }

    private void announce(long key, String reason, boolean isMined) {
        LongSet savedSet = isMined ? savedMined : saved;
        long announceKey = isMined ? ~key : key;
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

    private Report scan(WorldChunk chunk, Sensitivity sens) {
        ChunkSection[] sections = chunk.getSectionArray();
        int minY = chunk.getBottomY();
        int topY = minY + chunk.getHeight() - 1;
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        BlockPos.Mutable probe = new BlockPos.Mutable();

        boolean wantAmethyst = amethyst.get() || minedGeodes.get();
        boolean wantPlants = plants.get();
        boolean wantBlocks = playerBlocks.get();

        int rotated = 0, cobbled = 0, storage = 0, beehives = 0;
        boolean ancientCity = false;

        int grownAmethyst = 0, growingAmethyst = 0, exposedBudding = 0;

        int[] kelpBottom = new int[256], kelpTop = new int[256];
        int[] bambooBottom = new int[256], bambooTop = new int[256];
        Arrays.fill(kelpBottom, Integer.MAX_VALUE);
        Arrays.fill(kelpTop, Integer.MIN_VALUE);
        Arrays.fill(bambooBottom, Integer.MAX_VALUE);
        Arrays.fill(bambooTop, Integer.MIN_VALUE);
        boolean anyKelp = false, anyBamboo = false;
        List<BlockPos> caveVineTips = new ArrayList<>();
        List<BlockPos> vines = new ArrayList<>();
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
                        int column = (lx << 4) | lz;

                        if (block == Blocks.DEEPSLATE) {
                            if (state.get(Properties.AXIS) != Direction.Axis.Y) rotated++;
                        } else if (block == Blocks.COBBLED_DEEPSLATE) {
                            if (y < 0) cobbled++;
                        } else if (block == Blocks.REINFORCED_DEEPSLATE || block == Blocks.SCULK_SHRIEKER) {
                            ancientCity = true;
                        } else if (y <= 20 && isStorage(block)) {
                            storage++;
                        } else if (block == Blocks.BEEHIVE) {
                            beehives++;
                        } else if (block == Blocks.AMETHYST_CLUSTER) {
                            grownAmethyst++;
                        } else if (isBud(block)) {
                            // A bud still sitting on budding amethyst keeps growing; anything else is done.
                            Direction facing = state.get(Properties.FACING);
                            int sx = lx - facing.getOffsetX(), sy = y - facing.getOffsetY(), sz = lz - facing.getOffsetZ();
                            if (sx < 0 || sx > 15 || sz < 0 || sz > 15 || sy < minY || sy > topY) continue;
                            probe.set(startX + sx, sy, startZ + sz);
                            if (chunk.getBlockState(probe).isOf(Blocks.BUDDING_AMETHYST)) growingAmethyst++;
                            else grownAmethyst++;
                        } else if (block == Blocks.BUDDING_AMETHYST) {
                            if (y <= GEODE_MAX_Y && exposed(chunk, startX + lx, y, startZ + lz, probe, minY, topY)) exposedBudding++;
                        } else if (block == Blocks.KELP || block == Blocks.KELP_PLANT) {
                            kelpBottom[column] = Math.min(kelpBottom[column], y);
                            kelpTop[column] = Math.max(kelpTop[column], y);
                            anyKelp = true;
                        } else if (block == Blocks.BAMBOO) {
                            bambooBottom[column] = Math.min(bambooBottom[column], y);
                            bambooTop[column] = Math.max(bambooTop[column], y);
                            anyBamboo = true;
                        } else if (block == Blocks.CAVE_VINES) {
                            if (state.getOrEmpty(Properties.AGE_25).orElse(0) >= 22) caveVineTips.add(new BlockPos(startX + lx, y, startZ + lz));
                        } else if (block == Blocks.VINE) {
                            vines.add(new BlockPos(startX + lx, y, startZ + lz));
                        } else if (block == Blocks.COCOA) {
                            cocoa++;
                            if (state.getOrEmpty(Properties.AGE_2).orElse(0) >= 2) ripeCocoa++;
                        } else if (block == Blocks.BEE_NEST) {
                            if (state.getOrEmpty(Properties.HONEY_LEVEL).orElse(0) >= 1) honeyNests++;
                        }
                    }
                }
            }
        }

        // Player blocks: mark this exact chunk. Ancient cities have all of these naturally.
        List<String> direct = new ArrayList<>(4);
        if (wantBlocks && !ancientCity) {
            if (rotated >= sens.count(2)) direct.add(rotated + " sideways deepslate");
            if (cobbled >= sens.count(6)) direct.add(cobbled + " cobbled deepslate below Y 0");
            if (storage >= sens.count(2)) direct.add(storage + " storage blocks underground");
            if (beehives > 0) direct.add(beehives + (beehives == 1 ? " beehive" : " beehives"));
        }

        // Growth
        List<String> growth = new ArrayList<>(6);
        int amethystPoints = 0, plantPoints = 0;
        boolean stillGrowing = amethyst.get() && growingAmethyst > 0;

        if (amethyst.get() && grownAmethyst > 0 && growingAmethyst == 0) {
            amethystPoints = grownAmethyst;
            if (grownAmethyst >= sens.count(8)) growth.add(grownAmethyst + " grown amethyst");
        }

        if (wantPlants) {
            int kelp = anyKelp ? kelpToSurface(chunk, kelpBottom, kelpTop, sens, probe, topY) : 0;
            if (kelp > 0) { growth.add(kelp + " kelp grown to the surface"); plantPoints += kelp; }

            int strands = longCaveVines(chunk, caveVineTips, probe, topY) ;
            if (strands >= sens.count(3)) { growth.add(strands + " long cave vines"); plantPoints += strands; }

            int vineColumns = longVines(chunk, vines, sens, probe, minY, topY);
            if (vineColumns > 0) { growth.add(vineColumns + " long vines"); plantPoints += vineColumns; }

            int bamboo = 0;
            if (anyBamboo) {
                for (int c = 0; c < 256; c++) {
                    if (bambooTop[c] != Integer.MIN_VALUE && bambooTop[c] - bambooBottom[c] + 1 >= 12) bamboo++;
                }
            }
            if (bamboo >= sens.count(6)) { growth.add(bamboo + " tall bamboo"); plantPoints += bamboo; }

            if (ripeCocoa >= sens.count(3) && ripeCocoa >= cocoa * 0.75) { growth.add(ripeCocoa + " ripe cocoa"); plantPoints += ripeCocoa; }

            if (honeyNests >= sens.count(1)) { growth.add(honeyNests + " bee nests with honey"); plantPoints += honeyNests * 3; }
        }

        // Mined geode: lots of bare budding amethyst and not a single crystal left.
        String minedWhy = null;
        if (minedGeodes.get() && exposedBudding >= sens.count(10) && grownAmethyst == 0 && growingAmethyst == 0) {
            minedWhy = exposedBudding + " bare budding amethyst, every crystal broken off";
        }

        return new Report(amethystPoints, plantPoints, stillGrowing,
            growth.isEmpty() ? null : String.join(", ", growth),
            direct.isEmpty() ? null : String.join(", ", direct),
            minedWhy);
    }

    private static boolean exposed(WorldChunk chunk, int x, int y, int z, BlockPos.Mutable probe, int minY, int topY) {
        for (Direction side : Direction.values()) {
            int nx = x + side.getOffsetX(), ny = y + side.getOffsetY(), nz = z + side.getOffsetZ();
            if (ny < minY || ny > topY || (nx >> 4) != chunk.getPos().x || (nz >> 4) != chunk.getPos().z) continue;
            if (chunk.getBlockState(probe.set(nx, ny, nz)).isAir()) return true;
        }
        return false;
    }

    /** Kelp columns grown right up to the surface: worldgen leaves most kelp short of it. */
    private static int kelpToSurface(WorldChunk chunk, int[] bottom, int[] top, Sensitivity sens, BlockPos.Mutable probe, int topY) {
        int tall = 0, atSurface = 0;
        for (int c = 0; c < 256; c++) {
            if (top[c] == Integer.MIN_VALUE || top[c] - bottom[c] + 1 < 8) continue;
            tall++;
            if (top[c] + 1 > topY) continue;
            probe.set(chunk.getPos().getStartX() + (c >> 4), top[c] + 1, chunk.getPos().getStartZ() + (c & 15));
            if (chunk.getBlockState(probe).getFluidState().isEmpty()) atSurface++;
        }
        if (tall < sens.count(8) || atSurface < tall * 0.6) return 0;
        return atSurface;
    }

    private static int longCaveVines(WorldChunk chunk, List<BlockPos> tips, BlockPos.Mutable probe, int topY) {
        int strands = 0;
        for (BlockPos tip : tips) {
            int length = 1;
            for (int y = tip.getY() + 1; y <= topY; y++) {
                Block b = chunk.getBlockState(probe.set(tip.getX(), y, tip.getZ())).getBlock();
                if (b != Blocks.CAVE_VINES_PLANT && b != Blocks.CAVE_VINES) break;
                length++;
            }
            if (length >= 10) strands++;
        }
        return strands;
    }

    /** Vine columns long enough to have grown down while loaded, mostly reaching the ground. */
    private static int longVines(WorldChunk chunk, List<BlockPos> vines, Sensitivity sens, BlockPos.Mutable probe, int minY, int topY) {
        int longColumns = 0, grounded = 0;
        for (BlockPos pos : vines) {
            // Measure each run once, from its lowest vine.
            if (pos.getY() > minY && chunk.getBlockState(probe.set(pos.getX(), pos.getY() - 1, pos.getZ())).isOf(Blocks.VINE)) continue;

            int length = 1;
            for (int y = pos.getY() + 1; y <= topY && chunk.getBlockState(probe.set(pos.getX(), y, pos.getZ())).isOf(Blocks.VINE); y++) length++;
            if (length < 12) continue;

            longColumns++;
            if (pos.getY() > minY) {
                BlockState below = chunk.getBlockState(probe.set(pos.getX(), pos.getY() - 1, pos.getZ()));
                if (!below.isAir()) grounded++;
            }
        }
        if (longColumns < sens.count(4) || grounded < longColumns * 0.5) return 0;
        return longColumns;
    }

    private boolean isRelevant(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.DEEPSLATE
            || block == Blocks.COBBLED_DEEPSLATE
            || block == Blocks.REINFORCED_DEEPSLATE
            || block == Blocks.SCULK_SHRIEKER
            || block == Blocks.BEEHIVE
            || block == Blocks.BUDDING_AMETHYST
            || block == Blocks.AMETHYST_CLUSTER
            || isBud(block)
            || block == Blocks.KELP
            || block == Blocks.KELP_PLANT
            || block == Blocks.BAMBOO
            || block == Blocks.CAVE_VINES
            || block == Blocks.CAVE_VINES_PLANT
            || block == Blocks.VINE
            || block == Blocks.COCOA
            || block == Blocks.BEE_NEST
            || isStorage(block);
    }

    private static boolean isBud(Block block) {
        return block == Blocks.SMALL_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.LARGE_AMETHYST_BUD;
    }

    private static boolean isStorage(Block block) {
        return block instanceof ShulkerBoxBlock
            || block == Blocks.BARREL
            || block == Blocks.FURNACE
            || block == Blocks.CRAFTING_TABLE
            || block == Blocks.ENDER_CHEST;
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
