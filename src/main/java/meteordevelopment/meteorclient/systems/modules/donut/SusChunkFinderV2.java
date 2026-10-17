/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.donut;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
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
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.SimulationDistanceS2CPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds where a player stood still for a long time: the square of chunks they kept ticking.
 *
 * Plants only grow in chunks within the server's simulation distance of a player, so someone AFK at
 * a base leaves a (2r+1) by (2r+1) square of grown plants with a sharp edge, centred on where they
 * stood. That shows from the surface even while the base itself is hidden below the deepslate.
 *
 * Growth is read from plants whose natural state is known exactly (1.21.11 world generation):
 * kelp tops generate at age 20-23 and at most 11 tall, cactus and sugar cane at age 0, and geodes
 * with every bud size mixed (a quarter clusters). Anything past that took random ticks.
 *
 * Experimental, alongside the regular sus chunk finder.
 */
public class SusChunkFinderV2 extends Module {
    private static final String FIND_TYPE = "sus-zone";

    public enum Sensitivity {
        Strict,
        Balanced,
        Loose
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlants = settings.createGroup("Plants");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Sensitivity> sensitivity = sgGeneral.add(new EnumSetting.Builder<Sensitivity>()
        .name("sensitivity")
        .description("Strict: only clear, fully grown squares. Balanced: the recommended middle. Loose: shorter stays too, with more false finds.")
        .defaultValue(Sensitivity.Balanced)
        .onChanged(v -> rebuild())
        .build()
    );

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance")
        .description("How many chunks around a player the server keeps growing. 4 on DonutSMP. 0 uses what the server reports.")
        .defaultValue(4)
        .range(0, 12)
        .sliderRange(0, 12)
        .onChanged(v -> rebuild())
        .build()
    );

    private final Setting<Boolean> kelp = sgPlants.add(new BoolSetting.Builder()
        .name("kelp")
        .description("Kelp taller or older than world generation makes it. Oceans.")
        .defaultValue(true)
        .onChanged(v -> remeasure())
        .build()
    );

    private final Setting<Boolean> cactus = sgPlants.add(new BoolSetting.Builder()
        .name("cactus")
        .description("Cactus tops that aged, which every random tick does. Deserts and badlands.")
        .defaultValue(true)
        .onChanged(v -> remeasure())
        .build()
    );

    private final Setting<Boolean> sugarCane = sgPlants.add(new BoolSetting.Builder()
        .name("sugar-cane")
        .description("Two-tall sugar cane that aged. Rivers, beaches and swamps.")
        .defaultValue(true)
        .onChanged(v -> remeasure())
        .build()
    );

    private final Setting<Boolean> amethyst = sgPlants.add(new BoolSetting.Builder()
        .name("amethyst")
        .description("Geodes with more full clusters than they generate with. Slow to show, so it only adds to the others.")
        .defaultValue(true)
        .onChanged(v -> remeasure())
        .build()
    );

    private final Setting<SettingColor> zoneColor = sgRender.add(new ColorSetting.Builder()
        .name("zone-color")
        .description("Color of grown chunks in a zone. Chunks with nothing to measure are drawn fainter.")
        .defaultValue(new SettingColor(235, 60, 60, 45))
        .build()
    );

    private final Setting<SettingColor> centerColor = sgRender.add(new ColorSetting.Builder()
        .name("center-color")
        .description("Outline of the zone and its center chunk, where to dig down.")
        .defaultValue(new SettingColor(255, 90, 90, 230))
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Line from your crosshair to each zone's center.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notify = sgRender.add(new BoolSetting.Builder()
        .name("notify")
        .description("Announce new zones.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> saveFinds = sgRender.add(new BoolSetting.Builder()
        .name("save-finds")
        .description("Remember zones for this server, so they still show after relogging.")
        .defaultValue(true)
        .build()
    );

    /** Kept loaded by you for this long and a chunk's plants say nothing about anyone else. */
    private static final int SELF_TICKS_LIMIT = 20 * 120;
    private static final int MAP_CAP = 400_000;
    /** Natural share of full clusters among geode buds: generation picks the four sizes evenly. */
    private static final double AMETHYST_NATURAL = 0.25;
    /** Random ticks a cactus or cane top needs to reach this age: about nine minutes loaded. */
    private static final int AGED = 8;

    // Worker-thread state. Measurements of the current place, kept across relogs.
    private final Long2IntOpenHashMap grown = new Long2IntOpenHashMap(), samples = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap clusters = new Long2IntOpenHashMap(), buds = new Long2IntOpenHashMap();
    private final LongSet measured = new LongOpenHashSet();
    private final Long2IntOpenHashMap winG = new Long2IntOpenHashMap(), winN = new Long2IntOpenHashMap(),
        winC = new Long2IntOpenHashMap(), winB = new Long2IntOpenHashMap(), winM = new Long2IntOpenHashMap(),
        ringG = new Long2IntOpenHashMap(), ringN = new Long2IntOpenHashMap(),
        ringC = new Long2IntOpenHashMap(), ringB = new Long2IntOpenHashMap();
    /** Per centre: grown and ungrown chunks inside the square, and just outside its border. */
    private final Long2IntOpenHashMap fitIn = new Long2IntOpenHashMap(), fitOut = new Long2IntOpenHashMap(),
        borderIn = new Long2IntOpenHashMap(), borderOut = new Long2IntOpenHashMap();
    private final Long2DoubleOpenHashMap candidates = new Long2DoubleOpenHashMap();
    private final List<Zone> announced = new ArrayList<>();
    private int windowRadius = -1;
    private Sensitivity windowSensitivity;
    private long publishedAt;
    private boolean zonesDirty;

    // Main-thread state.
    private final Long2IntOpenHashMap selfTicks = new Long2IntOpenHashMap();
    private final LongSet selfLoaded = new LongOpenHashSet();
    private final LongSet arrived = new LongOpenHashSet();
    private boolean selfDirty;
    private int tickTimer, statsTimer;
    private String place = "";
    private FindsStorage.Context context = new FindsStorage.Context("", "");
    private List<Zone> saved = List.of();

    private volatile int serverSimulation;
    /** Published by the worker, drawn by the render thread. */
    private volatile List<Zone> zones = List.of();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Ember-SusChunkV2");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * A centre chunk and what its square looks like.
     *
     * @param cells per chunk of the square, row by row: -1 nothing to measure, else grown share 0-100
     */
    public record Zone(int centerX, int centerZ, int radius, int percent, int grownPlants, int plants, int edge, int[] cells) {
        String note() {
            return grownPlants + "/" + plants + " plants grown, " + (edge < 0 ? "edge unknown" : "edge " + edge + "%")
                + " [zone " + percent + "%, r " + radius + "]";
        }
    }

    public SusChunkFinderV2() {
        super(Categories.Donut, "sus-chunk-v2", "Experimental: finds where someone stood for a long time from the square of grown plants around them.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null) return;
        context = FindsStorage.context();
        selectPlace(context.world() + "|" + context.dimension());

        List<Zone> fromSave = new ArrayList<>();
        for (FindsStorage.Find find : FindsStorage.get(context, FIND_TYPE)) {
            fromSave.add(new Zone(find.x >> 4, find.z >> 4, radius(), 0, 0, 0, -1, null));
        }
        saved = fromSave;

        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk worldChunk) arrive(worldChunk);
        }
    }

    @Override
    public void onDeactivate() {
        saveSelfLoaded();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        // A relog keeps the measurements: chunks are only measured the first time they arrive, before
        // you had time to grow them yourself.
        onActivate();
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof GameJoinS2CPacket packet) serverSimulation = packet.simulationDistance();
        else if (event.packet instanceof SimulationDistanceS2CPacket packet) serverSimulation = packet.simulationDistance();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        arrive(event.chunk());
    }

    private int radius() {
        int r = simulationDistance.get() > 0 ? simulationDistance.get() : serverSimulation > 0 ? serverSimulation : 4;
        return Math.max(2, Math.min(12, r));
    }

    /** Main thread. Measures a chunk once per place, unless you kept it loaded long enough to have grown it. */
    private void arrive(WorldChunk chunk) {
        if (mc.world == null || mc.world.getRegistryKey() != World.OVERWORLD) return;
        long key = chunk.getPos().toLong();
        if (!arrived.add(key)) return;
        if (arrived.size() > MAP_CAP) arrived.clear();
        if (selfLoaded.contains(key) || selfTicks.get(key) >= SELF_TICKS_LIMIT) return;

        ChunkSection[] sections = chunk.getSectionArray();
        int minY = chunk.getBottomY();
        boolean k = kelp.get(), c = cactus.get(), s = sugarCane.get(), a = amethyst.get();
        int r = radius();
        Sensitivity sens = sensitivity.get();

        worker.submit(() -> {
            try {
                int[] m = measure(sections, minY, k, c, s, a);
                record(key, m, r, sens);
            } catch (Throwable t) {
                MeteorClient.LOG.warn("Sus chunk v2 measure failed", t);
            }
        });
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.world.getRegistryKey() != World.OVERWORLD) return;

        // Chunks you keep loaded yourself, so your own AFK spot or dig site never turns into a zone.
        if (++tickTimer >= 20) {
            tickTimer = 0;
            int r = radius();
            ChunkPos here = mc.player.getChunkPos();
            if (selfTicks.size() > MAP_CAP) selfTicks.clear();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    long key = ChunkPos.toLong(here.x + dx, here.z + dz);
                    if (selfTicks.addTo(key, 20) + 20 >= SELF_TICKS_LIMIT && selfLoaded.add(key)) selfDirty = true;
                }
            }

            // Zones held back by the publish throttle while chunks were pouring in.
            worker.submit(() -> {
                if (zonesDirty && windowRadius > 0) publish(windowRadius, true);
            });
        }

        if (++statsTimer >= 6000) {
            statsTimer = 0;
            saveSelfLoaded();
            int server = serverSimulation;
            worker.submit(() -> logStats(server));
        }
    }

    // Measuring

    private static boolean isRelevant(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.KELP || b == Blocks.KELP_PLANT || b == Blocks.CACTUS || b == Blocks.SUGAR_CANE
            || b == Blocks.AMETHYST_CLUSTER || b == Blocks.LARGE_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD
            || b == Blocks.SMALL_AMETHYST_BUD;
    }

    private static BlockState at(ChunkSection[] sections, int minY, int x, int y, int z) {
        int index = (y - minY) >> 4;
        if (index < 0 || index >= sections.length || sections[index] == null) return Blocks.AIR.getDefaultState();
        return sections[index].getBlockState(x, (y - minY) & 15, z);
    }

    /**
     * Worker thread.
     *
     * @return {grown plants, plants that could have grown, geode clusters, geode buds of any size}
     */
    private static int[] measure(ChunkSection[] sections, int minY, boolean useKelp, boolean useCactus, boolean useCane, boolean useAmethyst) {
        int[] kelpBottom = new int[256], kelpTop = new int[256], kelpAge = new int[256];
        int[] cactusTop = new int[256], cactusAge = new int[256];
        int[] caneTop = new int[256], caneRun = new int[256], caneAge = new int[256];
        java.util.Arrays.fill(kelpTop, Integer.MIN_VALUE);
        java.util.Arrays.fill(cactusTop, Integer.MIN_VALUE);
        java.util.Arrays.fill(caneTop, Integer.MIN_VALUE);
        int clusterCount = 0, budCount = 0;

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty() || !section.hasAny(SusChunkFinderV2::isRelevant)) continue;
            int baseY = minY + (i << 4);

            for (int ly = 0; ly < 16; ly++) {
                int y = baseY + ly;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, ly, z);
                        Block b = state.getBlock();
                        int column = (x << 4) | z;

                        if (b == Blocks.KELP || b == Blocks.KELP_PLANT) {
                            if (kelpTop[column] == Integer.MIN_VALUE || y > kelpTop[column] + 1) kelpBottom[column] = y;
                            kelpTop[column] = y;
                            kelpAge[column] = b == Blocks.KELP ? state.get(Properties.AGE_25) : -1;
                        } else if (b == Blocks.CACTUS) {
                            cactusTop[column] = y;
                            cactusAge[column] = state.get(Properties.AGE_15);
                        } else if (b == Blocks.SUGAR_CANE) {
                            caneRun[column] = caneTop[column] == y - 1 ? caneRun[column] + 1 : 1;
                            caneTop[column] = y;
                            caneAge[column] = state.get(Properties.AGE_15);
                        } else if (b == Blocks.AMETHYST_CLUSTER) {
                            clusterCount++;
                            budCount++;
                        } else if (b == Blocks.LARGE_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.SMALL_AMETHYST_BUD) {
                            budCount++;
                        }
                    }
                }
            }
        }

        int g = 0, n = 0;
        for (int column = 0; column < 256; column++) {
            int x = column >> 4, z = column & 15;

            // Kelp generates at most 11 tall with its top at age 20-23, and grows one block per step.
            if (useKelp && kelpTop[column] != Integer.MIN_VALUE && kelpAge[column] >= 0) {
                int height = kelpTop[column] - kelpBottom[column] + 1;
                if (kelpAge[column] >= 24 || height >= 12) {
                    g++;
                    n++;
                } else {
                    // Still able to grow (water above) yet hasn't: evidence nobody stayed. At the surface
                    // it could be either, so it doesn't count.
                    BlockState above = at(sections, minY, x, kelpTop[column] + 1, z);
                    if (above.isOf(Blocks.WATER) && above.getFluidState().isStill()) n++;
                }
            }

            // Cactus generates at age 0 and its top ages on every random tick, about once a minute.
            // A flower on top stops it, so those say nothing.
            if (useCactus && cactusTop[column] != Integer.MIN_VALUE && at(sections, minY, x, cactusTop[column] + 1, z).isAir()) {
                n++;
                if (cactusAge[column] >= AGED) g++;
            }

            // Sugar cane generates 2-4 tall at age 0; only a two-tall top ages (taller ones are done).
            if (useCane && caneTop[column] != Integer.MIN_VALUE && caneRun[column] == 2
                && at(sections, minY, x, caneTop[column] + 1, z).isAir()) {
                n++;
                if (caneAge[column] >= AGED) g++;
            }
        }

        if (!useAmethyst) clusterCount = budCount = 0;
        return new int[] {g, n, clusterCount, budCount};
    }

    // Zones (worker thread)

    private void record(long key, int[] m, int r, Sensitivity sens) {
        if (r != windowRadius || sens != windowSensitivity) rebuildWindows(r, sens);
        if (!measured.add(key)) return;
        if (measured.size() > MAP_CAP) {
            clearMeasurements();
            return;
        }
        if (m[1] > 0) {
            grown.put(key, m[0]);
            samples.put(key, m[1]);
        }
        if (m[3] > 0) {
            clusters.put(key, m[2]);
            buds.put(key, m[3]);
        }
        add(key, m, r);

        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
        for (int dx = -r - 2; dx <= r + 2; dx++) {
            for (int dz = -r - 2; dz <= r + 2; dz++) evaluate(ChunkPos.toLong(cx + dx, cz + dz), r, sens);
        }
        publish(r, false);
    }

    /** Adds a chunk's measurement to the squares it sits in, and to the rings it borders. */
    private void add(long key, int[] m, int r) {
        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
        // The chunk as a whole: mostly grown, mostly not, or nothing to tell.
        int plants = m[1] + m[3];
        int state = plants == 0 ? 0 : pooledGrown(m[0], m[2], m[3]) * 2 >= plants ? 1 : -1;

        for (int dx = -r - 2; dx <= r + 2; dx++) {
            for (int dz = -r - 2; dz <= r + 2; dz++) {
                long center = ChunkPos.toLong(cx + dx, cz + dz);
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                if (state != 0) {
                    if (d <= r) (state > 0 ? fitIn : fitOut).addTo(center, 1);
                    else if (d == r + 1) (state > 0 ? borderIn : borderOut).addTo(center, 1);
                }
                if (d <= r) {
                    winM.addTo(center, 1);
                    if (m[1] > 0) {
                        winG.addTo(center, m[0]);
                        winN.addTo(center, m[1]);
                    }
                    if (m[3] > 0) {
                        winC.addTo(center, m[2]);
                        winB.addTo(center, m[3]);
                    }
                } else if (m[1] > 0 || m[3] > 0) {
                    ringG.addTo(center, m[0]);
                    ringN.addTo(center, m[1]);
                    ringC.addTo(center, m[2]);
                    ringB.addTo(center, m[3]);
                }
            }
        }
    }

    /** Clean plants count one each; geodes only count the clusters beyond the natural quarter. */
    private static double pooledGrown(int g, int c, int b) {
        return g + Math.max(0, c - AMETHYST_NATURAL * b) / (1 - AMETHYST_NATURAL);
    }

    private record Limits(int minPlants, int minGrown, double minShare, int minRing, double maxRingShare, boolean needEdge) {
    }

    private static Limits limits(Sensitivity sens) {
        return switch (sens) {
            case Strict -> new Limits(40, 20, 0.6, 12, 0.35, true);
            case Balanced -> new Limits(20, 10, 0.45, 8, 0.55, false);
            case Loose -> new Limits(10, 5, 0.3, 8, 0.8, false);
        };
    }

    private void evaluate(long center, int r, Sensitivity sens) {
        Limits lim = limits(sens);
        int n = winN.get(center) + winB.get(center);
        double g = pooledGrown(winG.get(center), winC.get(center), winB.get(center));
        if (n < lim.minPlants || g < lim.minGrown) {
            candidates.remove(center);
            return;
        }

        double inside = g / n;
        int ringPlants = ringN.get(center) + ringB.get(center);
        boolean ringKnown = ringPlants >= lim.minRing;
        double ring = ringKnown ? pooledGrown(ringG.get(center), ringC.get(center), ringB.get(center)) / ringPlants : 0;

        // The sharp edge is what tells a player who stood still from people passing through.
        boolean edgeOk = ringKnown ? ring <= inside * lim.maxRingShare : !lim.needEdge;
        if (inside < lim.minShare || !edgeOk) {
            candidates.remove(center);
            return;
        }

        // Ranking: how well the square fits the grown chunks first (grown inside, nothing grown just
        // outside), which puts the centre on the chunk the player stood in; then how strong it is.
        double contrast = ringKnown ? 1 - ring / inside : 0.5;
        double fit = fitIn.get(center) - fitOut.get(center) - borderIn.get(center) + 0.5 * borderOut.get(center);
        candidates.put(center, fit * 10_000 + g * inside * (0.5 + contrast));
    }

    /** The best centre of each area, as zones for the render thread. Throttled unless forced. */
    private void publish(int r, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - publishedAt < 400) {
            zonesDirty = true;
            return;
        }
        publishedAt = now;
        zonesDirty = false;

        Limits lim = limits(windowSensitivity);
        List<Zone> out = new ArrayList<>();
        for (Long2DoubleOpenHashMap.Entry e : candidates.long2DoubleEntrySet()) {
            long center = e.getLongKey();
            double score = e.getDoubleValue();
            int cx = ChunkPos.getPackedX(center), cz = ChunkPos.getPackedZ(center);

            boolean best = true;
            for (int dx = -r; dx <= r && best; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    long other = ChunkPos.toLong(cx + dx, cz + dz);
                    double s = candidates.getOrDefault(other, -1);
                    if (s > score || (s == score && other < center)) {
                        best = false;
                        break;
                    }
                }
            }
            if (best) out.add(zone(center, r, lim));
        }

        zones = List.copyOf(out);
        // Drawn while forming, announced once most of the square has arrived: an early guess from the
        // first few chunks often moves a few chunks over as the rest come in.
        int fullSquare = (2 * r + 1) * (2 * r + 1);
        for (Zone zone : out) {
            if (winM.get(ChunkPos.toLong(zone.centerX, zone.centerZ)) >= fullSquare * 3 / 4) announce(zone);
        }
    }

    private Zone zone(long center, int r, Limits lim) {
        int n = winN.get(center) + winB.get(center);
        double g = pooledGrown(winG.get(center), winC.get(center), winB.get(center));
        double inside = n == 0 ? 0 : g / n;
        int ringPlants = ringN.get(center) + ringB.get(center);
        double ring = ringPlants >= lim.minRing ? pooledGrown(ringG.get(center), ringC.get(center), ringB.get(center)) / ringPlants : -1;

        double coverage = Math.min(1, n / (2.0 * lim.minPlants));
        double edgeFactor = ring < 0 ? 0.75 : 0.6 + 0.4 * (1 - ring / inside);
        int percent = (int) Math.round(Math.max(5, Math.min(99, 100 * Math.min(1, inside) * coverage * edgeFactor)));
        int edge = ring < 0 ? -1 : (int) Math.round(100 * Math.max(0, 1 - ring / inside));

        int cx = ChunkPos.getPackedX(center), cz = ChunkPos.getPackedZ(center);
        int size = 2 * r + 1;
        int[] cells = new int[size * size];
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                long key = ChunkPos.toLong(cx + dx, cz + dz);
                int cn = samples.get(key) + buds.get(key);
                cells[(dz + r) * size + dx + r] = cn == 0 ? -1
                    : (int) Math.round(100 * Math.min(1, pooledGrown(grown.get(key), clusters.get(key), buds.get(key)) / cn));
            }
        }
        return new Zone(cx, cz, r, percent, (int) Math.round(g), n, edge, cells);
    }

    private void announce(Zone zone) {
        for (Zone a : announced) {
            if (Math.abs(a.centerX - zone.centerX) <= zone.radius && Math.abs(a.centerZ - zone.centerZ) <= zone.radius) return;
        }
        announced.add(zone);

        int x = (zone.centerX << 4) + 8, z = (zone.centerZ << 4) + 8;
        MeteorClient.LOG.info("[Ember SusChunkV2] Zone at {}, {}: {}", x, z, zone.note());
        FindsStorage.Context ctx = context;
        boolean save = saveFinds.get(), tell = notify.get();
        mc.execute(() -> {
            if (save) FindsStorage.add(ctx, FIND_TYPE, new BlockPos(x, 64, z), zone.note());
            if (!tell) return;
            String text = zone.percent + "% - " + zone.grownPlants + "/" + zone.plants + " plants grown around it";
            if (!EmberNotificationsHud.shouldInterceptChat()) info("Sus zone at (highlight)%d, %d(default): %s", x, z, text);
            EmberNotificationsHud.push("Sus zone found", x + ", " + z + " - " + text, EmberNotificationsHud.GOOD);
        });
    }

    private void rebuildWindows(int r, Sensitivity sens) {
        windowRadius = r;
        windowSensitivity = sens;
        for (Long2IntOpenHashMap map : List.of(winG, winN, winC, winB, winM, ringG, ringN, ringC, ringB, fitIn, fitOut, borderIn, borderOut)) map.clear();
        candidates.clear();

        LongOpenHashSet keys = new LongOpenHashSet(measured);
        for (long key : keys) add(key, new int[] {grown.get(key), samples.get(key), clusters.get(key), buds.get(key)}, r);
        for (long center : new LongOpenHashSet(winM.keySet())) evaluate(center, r, sens);
        publish(r, true);
    }

    private void clearMeasurements() {
        grown.clear();
        samples.clear();
        clusters.clear();
        buds.clear();
        measured.clear();
        announced.clear();
        windowRadius = -1;
        for (Long2IntOpenHashMap map : List.of(winG, winN, winC, winB, winM, ringG, ringN, ringC, ringB, fitIn, fitOut, borderIn, borderOut)) map.clear();
        candidates.clear();
        zones = List.of();
    }

    private void logStats(int server) {
        int withPlants = 0, active = 0;
        for (Long2IntOpenHashMap.Entry e : samples.long2IntEntrySet()) {
            withPlants++;
            if (e.getIntValue() >= 3 && grown.get(e.getLongKey()) * 2 >= e.getIntValue()) active++;
        }
        MeteorClient.LOG.info("[Ember SusChunkV2] Stats: {} chunks measured, {} with plants, {} mostly grown; {} candidate centres, {} zones; radius {} (server says {})",
            measured.size(), withPlants, active, candidates.size(), zones.size(), windowRadius, server);
    }

    /** Main thread: settings that change how squares are judged. */
    private void rebuild() {
        int r = radius();
        Sensitivity sens = sensitivity.get();
        worker.submit(() -> rebuildWindows(r, sens));
    }

    /** Main thread: settings that change what a chunk's plants count as. Everything is measured again. */
    private void remeasure() {
        arrived.clear();
        worker.submit(this::clearMeasurements);
        if (isActive() && mc.world != null) {
            for (Chunk chunk : Utils.chunks()) {
                if (chunk instanceof WorldChunk worldChunk) arrive(worldChunk);
            }
        }
    }

    // Your own chunks, saved per world

    private void selectPlace(String newPlace) {
        if (newPlace.equals(place)) return;
        saveSelfLoaded();
        place = newPlace;
        arrived.clear();
        selfTicks.clear();
        selfLoaded.clear();
        selfDirty = false;
        worker.submit(this::clearMeasurements);

        File file = selfFile(newPlace);
        if (file == null || !file.isFile()) return;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file.toPath()));
            while (buffer.remaining() >= Long.BYTES) selfLoaded.add(buffer.getLong());
        } catch (IOException e) {
            MeteorClient.LOG.warn("Couldn't read {}", file, e);
        }
    }

    private void saveSelfLoaded() {
        if (!selfDirty) return;
        selfDirty = false;
        File file = selfFile(place);
        if (file == null) return;
        long[] keys = selfLoaded.toLongArray();
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

    private static File selfFile(String place) {
        if (place.isEmpty() || place.startsWith("|")) return null;
        return new File(new File(MeteorClient.FOLDER, "ember-finds"), place.replaceAll("[^A-Za-z0-9._-]", "_") + ".self-loaded");
    }

    // Rendering

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;
        double y = Math.floor(mc.player.getY());
        List<Zone> live = zones;

        for (Zone zone : live) drawZone(event, zone, y);
        for (Zone zone : saved) {
            boolean shown = false;
            for (Zone l : live) {
                if (Math.abs(l.centerX - zone.centerX) <= l.radius && Math.abs(l.centerZ - zone.centerZ) <= l.radius) {
                    shown = true;
                    break;
                }
            }
            if (!shown) drawZone(event, zone, y);
        }
    }

    private void drawZone(Render3DEvent event, Zone zone, double y) {
        Color fill = zoneColor.get(), line = centerColor.get();
        int r = zone.radius, size = 2 * r + 1;

        if (zone.cells != null) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int cell = zone.cells[(dz + r) * size + dx + r];
                    // Grown chunks full color, chunks with nothing to measure faint, ungrown ones empty.
                    int alpha = cell < 0 ? fill.a / 3 : (int) (fill.a * Math.min(1, cell / 60.0));
                    if (alpha <= 2) continue;
                    double x0 = (zone.centerX + dx) * 16.0, z0 = (zone.centerZ + dz) * 16.0;
                    event.renderer.box(x0, y, z0, x0 + 16, y, z0 + 16, new Color(fill.r, fill.g, fill.b, alpha), line, ShapeMode.Sides, 0);
                }
            }
        }

        double x0 = (zone.centerX - r) * 16.0, z0 = (zone.centerZ - r) * 16.0, x1 = (zone.centerX + r + 1) * 16.0, z1 = (zone.centerZ + r + 1) * 16.0;
        event.renderer.line(x0, y, z0, x1, y, z0, line);
        event.renderer.line(x1, y, z0, x1, y, z1, line);
        event.renderer.line(x1, y, z1, x0, y, z1, line);
        event.renderer.line(x0, y, z1, x0, y, z0, line);

        double cx = zone.centerX * 16.0, cz = zone.centerZ * 16.0;
        event.renderer.box(cx, y, cz, cx + 16, y + 1, cz + 16, new Color(line.r, line.g, line.b, 60), line, ShapeMode.Both, 0);

        if (tracers.get()) {
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, cx + 8, y, cz + 8, line);
        }
    }

    @Override
    public String getInfoString() {
        int n = zones.size();
        return n == 0 ? null : String.valueOf(n);
    }
}
