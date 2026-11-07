package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagnostics for DonutSMP's new anticheat (Nov 2026: real blocks only arrive close around you). Logs, once a minute,
 * what the chunks look like by distance, where hidden blocks get revealed as you move (radius and depth), and every
 * channel that still has to report something from a hidden spot: block events, world events, sounds, block entity
 * updates, entities and particles below max-y. Only logs ("[Ember DonutProbe]"), sends nothing.
 */
public class DonutProbe extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Leaks and reveals are only counted below this height (DonutSMP hid everything up to Y 15).")
        .defaultValue(16)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .build()
    );

    private final Setting<Integer> samples = sgGeneral.add(new IntSetting.Builder()
        .name("samples")
        .description("How many examples of each kind to log per minute.")
        .defaultValue(6)
        .range(0, 50)
        .sliderRange(0, 20)
        .build()
    );

    /** Network thread to main thread. */
    private final Queue<String> leaks = new ConcurrentLinkedQueue<>();
    private final Map<String, AtomicInteger> leakCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> leakSampled = new HashMap<>();
    private final List<String> leakSamples = new ArrayList<>();

    /** Chunk shape by ring (chunks from you): [real, hidden at 0, hidden at 16, other]. */
    private final Map<String, int[]> rings = new LinkedHashMap<>();
    private final List<String> nearDetail = new ArrayList<>();
    private final Map<String, Integer> hiddenShapes = new HashMap<>();

    /** Reveals: fake deepslate replaced by something else, relative to you. */
    private final Queue<int[]> reveals = new ConcurrentLinkedQueue<>();
    private final List<int[]> minuteReveals = new ArrayList<>();
    private long lastReport;

    public DonutProbe() {
        super(Categories.Donut, "donut-probe", "Logs what DonutSMP's anticheat sends now: chunk shapes by distance, reveals, and leaks from hidden spots.");
    }

    @Override
    public void onActivate() {
        lastReport = System.currentTimeMillis();
        MeteorClient.LOG.info("[Ember DonutProbe] On. max-y {}", maxY.get());
    }

    // ---------------------------------------------------------------- chunks (main thread)

    @EventHandler
    private void onChunk(ChunkDataEvent event) {
        if (mc.player == null) return;
        WorldChunk chunk = event.chunk();
        int dx = chunk.getPos().x - mc.player.getChunkPos().x, dz = chunk.getPos().z - mc.player.getChunkPos().z;
        int ring = Math.max(Math.abs(dx), Math.abs(dz));
        String ringName = ring == 0 ? "0" : ring == 1 ? "1" : ring == 2 ? "2" : ring <= 5 ? "3-5" : "6+";
        int floor = DeepslateHiding.realFloor(chunk);
        int[] counts = rings.computeIfAbsent(ringName, k -> new int[4]);
        if (floor == chunk.getBottomY()) counts[0]++;
        else if (floor == 0) counts[1]++;
        else if (floor == 16) counts[2]++;
        else counts[3]++;

        String shape = shape(chunk, ring <= 1);
        if (floor != chunk.getBottomY()) hiddenShapes.merge(shape, 1, Integer::sum);
        if (ring <= 1 && nearDetail.size() < 12) {
            nearDetail.add(String.format("chunk %d,%d (ring %d, you at Y %d): floor %d | %s", chunk.getPos().x, chunk.getPos().z, ring,
                mc.player.getBlockY(), floor, shape));
        }
    }

    /** The sections below max-y: uniform ones as their block, mixed ones as the count of distinct states (and top blocks). */
    private String shape(WorldChunk chunk, boolean detailed) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomY();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < sections.length; i++) {
            int minY = bottom + (i << 4);
            if (minY >= maxY.get()) break;
            ChunkSection s = sections[i];
            if (b.length() > 0) b.append(' ');
            b.append(minY).append(':');
            if (s == null || s.isEmpty()) {
                b.append("air");
            } else if (DeepslateHiding.isBlank(s)) {
                b.append(name(s.getBlockState(0, 0, 0)));
            } else if (!detailed) {
                b.append("mixed");
            } else {
                Map<String, Integer> top = new HashMap<>();
                for (int x = 0; x < 16; x += 2) for (int y = 0; y < 16; y += 2) for (int z = 0; z < 16; z += 2)
                    top.merge(name(s.getBlockState(x, y, z)), 1, Integer::sum);
                b.append("mixed[");
                top.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(3)
                    .forEach(e -> b.append(e.getKey()).append(' ').append(e.getValue()).append(','));
                b.setLength(b.length() - 1);
                b.append(']');
            }
        }
        return b.toString();
    }

    private static String name(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).getPath();
    }

    // ---------------------------------------------------------------- packets (network thread)

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;
        switch (event.packet) {
            case BlockUpdateS2CPacket p -> change(p.getPos(), p.getState());
            case ChunkDeltaUpdateS2CPacket p -> p.visitUpdates(this::change);
            case BlockEventS2CPacket p -> leak("block event " + name(p.getBlock().getDefaultState()) + " type " + p.getType(), p.getPos().getX(), p.getPos().getY(), p.getPos().getZ());
            case WorldEventS2CPacket p -> leak("world event " + p.getEventId() + (p.isGlobal() ? " global" : ""), p.getPos().getX(), p.getPos().getY(), p.getPos().getZ());
            case PlaySoundS2CPacket p -> leak("sound " + p.getSound().getIdAsString().replace("minecraft:", ""), p.getX(), p.getY(), p.getZ());
            case BlockEntityUpdateS2CPacket p -> leak("block entity " + Registries.BLOCK_ENTITY_TYPE.getId(p.getBlockEntityType()).getPath(), p.getPos().getX(), p.getPos().getY(), p.getPos().getZ());
            case EntitySpawnS2CPacket p -> leak("entity " + Registries.ENTITY_TYPE.getId(p.getEntityType()).getPath(), p.getX(), p.getY(), p.getZ());
            case ParticleS2CPacket p -> leak("particle " + Registries.PARTICLE_TYPE.getId(p.getParameters().getType()).getPath(), p.getX(), p.getY(), p.getZ());
            default -> {
            }
        }
    }

    /** A block change below max-y: a reveal when the block it replaces is the fake deepslate. */
    private void change(BlockPos pos, BlockState state) {
        if (pos.getY() >= maxY.get()) return;
        BlockState before = mc.world.getBlockState(pos);
        if (before.isOf(Blocks.DEEPSLATE) && !state.isOf(Blocks.DEEPSLATE)) {
            BlockPos me = mc.player.getBlockPos();
            reveals.add(new int[] {pos.getX() - me.getX(), pos.getY() - me.getY(), pos.getZ() - me.getZ(), me.getY()});
        } else {
            leak("block change " + name(before) + " -> " + name(state), pos.getX(), pos.getY(), pos.getZ());
        }
    }

    private void leak(String kind, double x, double y, double z) {
        if (y >= maxY.get()) return;
        double d = Math.sqrt(mc.player.squaredDistanceTo(x, y, z));
        // Next to you it's your own doing; the interesting ones come from hidden spots further away.
        String key = kind + (d < 16 ? " (near you)" : "");
        leakCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        leaks.add(String.format("%s at %d %d %d (%dm, you at Y %d)", key, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), (int) d, mc.player.getBlockY()));
    }

    // ---------------------------------------------------------------- report (main thread)

    @EventHandler
    private void onTick(TickEvent.Post event) {
        for (String s; (s = leaks.poll()) != null; ) {
            String kind = s.substring(0, s.indexOf(" at "));
            int n = leakSampled.merge(kind, 1, Integer::sum);
            if (n <= samples.get()) leakSamples.add(s);
        }
        for (int[] r; (r = reveals.poll()) != null; ) minuteReveals.add(r);

        long now = System.currentTimeMillis();
        if (now - lastReport < 60_000) return;
        lastReport = now;
        report();
    }

    private void report() {
        String pos = mc.player == null ? "?" : mc.player.getBlockX() + " " + mc.player.getBlockY() + " " + mc.player.getBlockZ();
        StringBuilder r = new StringBuilder();
        rings.forEach((k, v) -> r.append(" ring ").append(k).append(": real ").append(v[0]).append(", hidden<0 ").append(v[1])
            .append(", hidden<16 ").append(v[2]).append(v[3] > 0 ? ", other " + v[3] : "").append(';'));
        MeteorClient.LOG.info("[Ember DonutProbe] Minute at {}. Chunks by distance:{}", pos, r.length() == 0 ? " none" : r);
        hiddenShapes.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(4)
            .forEach(e -> MeteorClient.LOG.info("[Ember DonutProbe] Hidden shape x{}: {}", e.getValue(), e.getKey()));
        nearDetail.forEach(s -> MeteorClient.LOG.info("[Ember DonutProbe] Near: {}", s));

        if (!minuteReveals.isEmpty()) {
            int maxDist = 0, minDy = Integer.MAX_VALUE, maxDy = Integer.MIN_VALUE;
            int[] hist = new int[8];
            for (int[] v : minuteReveals) {
                int d = (int) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                maxDist = Math.max(maxDist, d);
                minDy = Math.min(minDy, v[1]);
                maxDy = Math.max(maxDy, v[1]);
                hist[Math.min(7, d / 2)]++;
            }
            MeteorClient.LOG.info("[Ember DonutProbe] Reveals: {} blocks, distance up to {} (by 2m: {}), height relative to your feet {}..{}",
                minuteReveals.size(), maxDist, Arrays.toString(hist), minDy, maxDy);
            minuteReveals.stream().limit(samples.get()).forEach(v -> MeteorClient.LOG.info("[Ember DonutProbe] Reveal at dx {} dy {} dz {} (you at Y {})", v[0], v[1], v[2], v[3]));
        }

        if (!leakCounts.isEmpty()) {
            StringBuilder l = new StringBuilder();
            leakCounts.entrySet().stream().sorted((a, b) -> b.getValue().get() - a.getValue().get())
                .forEach(e -> l.append(' ').append(e.getKey()).append(" x").append(e.getValue().get()).append(';'));
            MeteorClient.LOG.info("[Ember DonutProbe] Below Y {}:{}", maxY.get(), l);
            leakSamples.forEach(s -> MeteorClient.LOG.info("[Ember DonutProbe] Sample: {}", s));
        }

        rings.clear();
        nearDetail.clear();
        hiddenShapes.clear();
        minuteReveals.clear();
        leakCounts.clear();
        leakSampled.clear();
        leakSamples.clear();
    }
}
