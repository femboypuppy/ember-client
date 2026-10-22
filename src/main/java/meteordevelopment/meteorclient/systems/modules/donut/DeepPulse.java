package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EntityType;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Live activity under the deepslate, from what DonutSMP has to send even though it hides the blocks there:
 * explosions (TNT farms, dupers), primed TNT and falling blocks (sand and concrete farms), and light that keeps
 * changing in a deep section (redstone lamps, torches on a clock). None of it happens on its own deep underground
 * with nobody there. Events near you are yours and are ignored.
 */
public class DeepPulse extends Module {
    private static final String FIND_TYPE = "deep-pulse";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Only count activity below this height. DonutSMP hides everything up to Y 15; above that you can see it anyway.")
        .defaultValue(16)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .build()
    );

    private final Setting<Integer> minDistance = sgGeneral.add(new IntSetting.Builder()
        .name("min-distance")
        .description("Ignore activity this close to you: your own TNT, torches and digging.")
        .defaultValue(24)
        .range(0, 256)
        .sliderRange(0, 64)
        .build()
    );

    private final Setting<Boolean> explosions = sgGeneral.add(new BoolSetting.Builder()
        .name("explosions")
        .description("Explosions below max-y (sent from about 64 blocks away).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> tnt = sgGeneral.add(new BoolSetting.Builder()
        .name("primed-tnt")
        .description("Lit TNT below max-y (sent from about 160 blocks away): TNT farms and dupers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> fallingBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("falling-blocks")
        .description("Falling sand, gravel and concrete powder below max-y: sand and concrete farms.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> blockChanges = sgGeneral.add(new BoolSetting.Builder()
        .name("block-changes")
        .description("Blocks that change below max-y away from you: redstone lamps switching, pistons, redstone, someone digging. Each change also says which block, the real one.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> light = sgGeneral.add(new BoolSetting.Builder()
        .name("light-changes")
        .description("Light updates the server sends for a section below max-y. Servers rarely send these (the client works light out itself), so it's a bonus signal.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> alertScore = sgGeneral.add(new IntSetting.Builder()
        .name("alert-score")
        .description("How much activity a chunk needs before it's marked: an explosion or lit TNT counts 3, a falling block or light change 1.")
        .defaultValue(3)
        .range(1, 50)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Notify when a spot is marked.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> saveFinds = sgGeneral.add(new BoolSetting.Builder()
        .name("save-finds")
        .description("Remember marked spots for this server.")
        .defaultValue(true)
        .build()
    );

    private final SettingGroup sgPlayers = settings.createGroup("Player Chunks");

    private final Setting<Boolean> playerChunks = sgPlayers.add(new BoolSetting.Builder()
        .name("player-chunks")
        .description("Find players you can't see: chunks far outside your own simulation distance that are still running (plants growing, mobs moving) have someone near them, maybe AFK at a base under the deepslate.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> presenceChunks = sgPlayers.add(new IntSetting.Builder()
        .name("min-chunks")
        .description("Running chunks that must group together before it reports a player there.")
        .defaultValue(3)
        .range(1, 50)
        .sliderRange(1, 10)
        .visible(playerChunks::get)
        .build()
    );

    private final Setting<SettingColor> runningColor = sgPlayers.add(new ColorSetting.Builder()
        .name("running-color")
        .description("Color of running chunks and of where the unseen player probably is.")
        .defaultValue(new SettingColor(60, 230, 160, 60))
        .visible(playerChunks::get)
        .build()
    );

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of marked spots.")
        .defaultValue(new SettingColor(60, 230, 100, 90))
        .build()
    );

    private final Setting<Integer> renderRange = sgRender.add(new IntSetting.Builder()
        .name("render-range")
        .description("Blocks from you within which marked spots are drawn.")
        .defaultValue(512)
        .range(16, 4096)
        .sliderRange(64, 2048)
        .build()
    );

    /** One thing that happened, from the network thread; detail is the block, for block changes. */
    private record Event(String kind, double x, double y, double z, int weight, String detail) {
        Event(String kind, double x, double y, double z, int weight) {
            this(kind, x, y, z, weight, null);
        }
    }

    /** A chunk with activity: what, how much, where last. */
    private static final class Spot {
        final int chunkX, chunkZ;
        int score, x, y, z;
        final Map<String, Integer> kinds = new LinkedHashMap<>();
        final Map<String, Long> lastByKind = new java.util.HashMap<>();
        /** Which blocks changed there (a few), the real ones even under the hiding. */
        final java.util.Set<String> blocks = new java.util.LinkedHashSet<>();
        long first, last;
        boolean marked;

        Spot(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        String summary() {
            StringBuilder sb = new StringBuilder();
            kinds.forEach((k, n) -> sb.append(sb.isEmpty() ? "" : ", ").append(n).append(' ').append(k));
            if (!blocks.isEmpty()) sb.append(" (").append(String.join(", ", blocks)).append(')');
            return sb.toString();
        }
    }

    private final ConcurrentLinkedQueue<Event> incoming = new ConcurrentLinkedQueue<>();
    private final Map<Long, Spot> spots = new ConcurrentHashMap<>();
    private final Map<String, Integer> stats = new LinkedHashMap<>();
    private long statsAt;

    public DeepPulse() {
        super(Categories.Donut, "deep-pulse", "Spots live bases under the deepslate from what the server can't hide (explosions, lit TNT, falling sand, block changes) and unseen players from the chunks they keep running.");
    }

    @Override
    public void onActivate() {
        statsAt = System.currentTimeMillis();
        // Spots marked before, so they still show.
        if (mc.world != null && spots.isEmpty()) {
            for (FindsStorage.Find find : FindsStorage.get(FindsStorage.context(), FIND_TYPE)) {
                Spot s = new Spot(find.x >> 4, find.z >> 4);
                s.x = find.x;
                s.y = find.y;
                s.z = find.z;
                s.marked = true;
                s.kinds.put("saved", 1);
                spots.put(ChunkPos.toLong(s.chunkX, s.chunkZ), s);
            }
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        spots.clear();
        // A new connection (or shard) starts its running chunks and your trail over.
        running.clear();
        presences.clear();
        selfTrail.clear();
        onActivate();
    }

    /** Network thread: only read the packet and queue what matters. */
    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof ExplosionS2CPacket p) {
            if (explosions.get()) incoming.add(new Event("explosion", p.center().x, p.center().y, p.center().z, 3));
        } else if (event.packet instanceof EntitySpawnS2CPacket p) {
            EntityType<?> type = p.getEntityType();
            if (type == EntityType.TNT || type == EntityType.TNT_MINECART) {
                if (tnt.get()) incoming.add(new Event("lit TNT", p.getX(), p.getY(), p.getZ(), 3));
            } else if (type == EntityType.FALLING_BLOCK) {
                if (fallingBlocks.get()) incoming.add(new Event("falling block", p.getX(), p.getY(), p.getZ(), 1));
            }
        } else if (event.packet instanceof net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket p) {
            if (blockChanges.get() || playerChunks.get()) blockChange(p.getPos(), p.getState());
        } else if (event.packet instanceof net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket p) {
            if (blockChanges.get() || playerChunks.get()) p.visitUpdates(this::blockChange);
        } else if (event.packet instanceof net.minecraft.network.packet.s2c.play.EntityS2CPacket p) {
            if (playerChunks.get() && p.isPositionChanged()) moved.add(p);
        } else if (event.packet instanceof net.minecraft.network.packet.s2c.play.GameJoinS2CPacket p) {
            simDistance = p.simulationDistance();
        } else if (event.packet instanceof net.minecraft.network.packet.s2c.play.SimulationDistanceS2CPacket p) {
            simDistance = p.simulationDistance();
        } else if (event.packet instanceof LightUpdateS2CPacket p) {
            if (!light.get()) return;
            // Bit i is the section (i - 1) above the world's bottom one: the light data has one extra section below.
            BitSet changed = p.getData().getInitedBlock();
            int bottom = mc.world != null ? mc.world.getBottomY() : -64;
            for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1)) {
                int sectionMinY = bottom + (i - 1) * 16;
                if (sectionMinY + 15 >= maxY.get()) continue;
                incoming.add(new Event("light change", p.getChunkX() * 16 + 8, sectionMinY + 8, p.getChunkZ() * 16 + 8, 1));
            }
        }
    }

    /** Network thread. The block's name says what's down there: a lamp, a piston, or just stone being dug. */
    private void blockChange(BlockPos pos, net.minecraft.block.BlockState state) {
        String block = state.isAir() ? "air" : net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).getPath();
        if (natural(block)) {
            // Natural changes only happen in chunks someone keeps running: far from you, that someone isn't you.
            if (playerChunks.get()) incoming.add(new Event("running", pos.getX(), pos.getY(), pos.getZ(), 0, block));
            return;
        }
        if (blockChanges.get() && pos.getY() < maxY.get()) incoming.add(new Event("block change", pos.getX(), pos.getY(), pos.getZ(), 1, block));
    }

    /** Parts of names of blocks that change by themselves (growth, spreading, aging, flowing, mobs stepping on them). */
    private static final String[] NATURAL = {"grass", "dirt", "mycelium", "podzol", "vine", "lichen", "kelp", "amethyst", "dripstone",
        "copper", "sculk", "leaves", "ice", "snow", "fire", "farmland", "sugar_cane", "cactus", "bamboo", "coral", "berry", "pickle",
        "egg", "dripleaf", "redstone_ore", "water", "lava", "moss", "azalea", "spore", "seagrass", "frogspawn"};

    /**
     * A change that happens near any player with nobody doing anything (your own simulation distance reaches further
     * than min-distance), so it says nothing about a base.
     */
    static boolean natural(String block) {
        for (String n : NATURAL) if (block.contains(n)) return true;
        return false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();

        // Where you've been lately: the chunks you kept running yourself (they keep running a moment after you leave).
        if (now - selfAt > 2000) {
            selfAt = now;
            ChunkPos here = mc.player.getChunkPos();
            selfTrail.addLast(new long[] {now, here.x, here.z});
            while (!selfTrail.isEmpty() && now - selfTrail.peekFirst()[0] > 120_000) selfTrail.removeFirst();
        }

        // Mobs and items that moved: whose entity, and where.
        net.minecraft.network.packet.s2c.play.EntityS2CPacket moveP;
        while ((moveP = moved.poll()) != null) {
            net.minecraft.entity.Entity entity = moveP.getEntity(mc.world);
            if (entity == null || entity instanceof net.minecraft.entity.player.PlayerEntity || entity == mc.player.getVehicle()) continue;
            double d = Math.sqrt(entity.squaredDistanceTo(mc.player));
            if (d > farthestMove) farthestMove = d;
            String type = net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()).getPath();
            take(new Event("running", entity.getX(), entity.getY(), entity.getZ(), 0, type), now);
        }

        Event e;
        while ((e = incoming.poll()) != null) take(e, now);

        // Running chunks and presences fade once nothing more is seen there.
        running.values().removeIf(r -> now - r.last > 5 * 60_000L);
        presences.removeIf(p -> now - p.last > 10 * 60_000L);

        // Every 5 minutes, what arrived: tells from the log whether DonutSMP sends these from below at all.
        if (now - statsAt > 300_000) {
            statsAt = now;
            if (farthestMove > 0) stats.put("farthest mob move (m)", (int) farthestMove);
            stats.put("simulation distance", sim());
            MeteorClient.LOG.info("[Ember DeepPulse] Last 5 minutes: {}", stats);
            stats.clear();
            farthestMove = 0;
        }
    }

    // Player chunks

    /** The server's simulation distance in chunks (how far around a player chunks run); 10 until it says. */
    private volatile int simDistance;
    private final ConcurrentLinkedQueue<net.minecraft.network.packet.s2c.play.EntityS2CPacket> moved = new ConcurrentLinkedQueue<>();
    private final java.util.ArrayDeque<long[]> selfTrail = new java.util.ArrayDeque<>();
    private long selfAt;
    private double farthestMove;

    /** A chunk seen running far from you: when, and what (a plant growing, a mob moving). */
    private static final class Running {
        final int chunkX, chunkZ;
        int y, count;
        long last;
        final java.util.Set<String> what = new java.util.LinkedHashSet<>();

        Running(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    /** A group of running chunks: one player keeping them running, about at their middle. */
    private static final class Presence {
        final java.util.Set<Long> chunks = new java.util.HashSet<>();
        double centerX, centerZ;
        int y;
        long last;
        boolean told;
    }

    private final Map<Long, Running> running = new ConcurrentHashMap<>();
    private final List<Presence> presences = new java.util.concurrent.CopyOnWriteArrayList<>();

    private int sim() {
        return simDistance > 0 ? simDistance : 10;
    }

    /** Within your own running area now or in the last two minutes (plus a chunk of margin). */
    private boolean yours(int chunkX, int chunkZ) {
        int r = sim() + 1;
        for (long[] p : selfTrail) if (Math.abs(p[1] - chunkX) <= r && Math.abs(p[2] - chunkZ) <= r) return true;
        ChunkPos here = mc.player.getChunkPos();
        return Math.abs(here.x - chunkX) <= r && Math.abs(here.z - chunkZ) <= r;
    }

    private void running(Event e, long now) {
        int cx = (int) Math.floor(e.x) >> 4, cz = (int) Math.floor(e.z) >> 4;
        if (yours(cx, cz)) {
            stats.merge("running near you", 1, Integer::sum);
            return;
        }
        stats.merge("running far", 1, Integer::sum);
        long key = ChunkPos.toLong(cx, cz);
        Running r = running.computeIfAbsent(key, k -> new Running(cx, cz));
        boolean fresh = r.count == 0;
        r.count++;
        r.last = now;
        r.y = (int) Math.floor(e.y);
        if (r.what.size() < 5) r.what.add(e.detail);
        if (fresh) MeteorClient.LOG.info("[Ember DeepPulse] Running chunk {}, {} ({}) far from you", cx, cz, e.detail);

        // The player keeping it running is within their simulation distance of it: join the group it fits in.
        Presence group = null;
        for (Presence p : presences) {
            if (Math.abs(p.centerX - cx) <= 2 * sim() + 1 && Math.abs(p.centerZ - cz) <= 2 * sim() + 1) {
                group = p;
                break;
            }
        }
        if (group == null) {
            group = new Presence();
            presences.add(group);
        }
        group.chunks.add(key);
        group.last = now;
        group.y = r.y;
        double sx = 0, sz = 0;
        for (long k : group.chunks) {
            sx += ChunkPos.getPackedX(k);
            sz += ChunkPos.getPackedZ(k);
        }
        group.centerX = sx / group.chunks.size();
        group.centerZ = sz / group.chunks.size();

        if (!group.told && group.chunks.size() >= presenceChunks.get()) {
            group.told = true;
            int x = (int) Math.round(group.centerX * 16 + 8), z = (int) Math.round(group.centerZ * 16 + 8);
            String seen = visiblePlayerNear(x, z);
            MeteorClient.LOG.info("[Ember DeepPulse] Player chunks: {} running chunks around {}, {}{}", group.chunks.size(), x, z,
                seen == null ? " (no player visible there)" : " (" + seen + " is there)");
            if (seen == null && notify.get()) {
                String text = "around " + x + ", " + z + " (" + group.chunks.size() + " chunks running)";
                if (!EmberNotificationsHud.shouldInterceptChat()) info("Unseen player (highlight)%s", text);
                EmberNotificationsHud.push("Unseen player", text, EmberNotificationsHud.WARN);
            }
        }
    }

    /** A player you can see near there, by name, or null. */
    private String visiblePlayerNear(int x, int z) {
        double r = (sim() + 1) * 16.0;
        for (net.minecraft.entity.player.PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (Math.abs(p.getX() - x) <= r && Math.abs(p.getZ() - z) <= r) return p.getName().getString();
        }
        return null;
    }

    private void take(Event e, long now) {
        if (mc.world.getRegistryKey() != World.OVERWORLD) return;
        if (e.kind.equals("running")) {
            running(e, now);
            return;
        }
        boolean deep = e.y < maxY.get();
        double dx = e.x - mc.player.getX(), dy = e.y - mc.player.getY(), dz = e.z - mc.player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean near = distance < minDistance.get();
        stats.merge(e.kind + (deep ? " deep" : " high") + (near ? " near" : ""), 1, Integer::sum);
        if (!deep || near) return;

        int bx = (int) Math.floor(e.x), by = (int) Math.floor(e.y), bz = (int) Math.floor(e.z);
        long key = ChunkPos.toLong(bx >> 4, bz >> 4);
        Spot s = spots.computeIfAbsent(key, k -> new Spot(bx >> 4, bz >> 4));
        if (s.first == 0) s.first = now;
        if (e.detail != null && s.blocks.size() < 6) s.blocks.add(e.detail);
        // Light changes count once per 20 seconds per chunk, block changes once per 5: a burst (one lamp switched,
        // a few blocks dug) is one event, a clock or a farm keeps adding up.
        Long lastOfKind = s.lastByKind.get(e.kind);
        long gap = e.kind.equals("light change") ? 20_000 : e.kind.equals("block change") ? 5_000 : 0;
        if (lastOfKind != null && now - lastOfKind < gap) return;
        s.lastByKind.put(e.kind, now);
        s.last = now;
        s.score += e.weight;
        s.kinds.merge(e.kind, 1, Integer::sum);
        // A real position beats the middle of a lit section.
        if (!e.kind.equals("light change") || s.y == 0) {
            s.x = bx;
            s.y = by;
            s.z = bz;
        }
        MeteorClient.LOG.info("[Ember DeepPulse] {}{} at {} {} {} ({}m away), chunk score {}", e.kind, e.detail == null ? "" : " (" + e.detail + ")",
            bx, by, bz, Math.round(distance), s.score);

        if (!s.marked && s.score >= alertScore.get()) {
            s.marked = true;
            String text = s.summary() + " at " + s.x + ", " + s.y + ", " + s.z;
            MeteorClient.LOG.info("[Ember DeepPulse] Marked: {}", text);
            if (saveFinds.get()) FindsStorage.add(FindsStorage.context(), FIND_TYPE, new BlockPos(s.x, s.y, s.z), s.summary());
            if (notify.get()) {
                if (!EmberNotificationsHud.shouldInterceptChat()) info("Activity underground: (highlight)%s", text);
                EmberNotificationsHud.push("Deep pulse", text, EmberNotificationsHud.WARN);
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null || mc.world.getRegistryKey() != World.OVERWORLD) return;
        Color fill = color.get();
        Color line = new Color(fill.r, fill.g, fill.b, Math.min(255, fill.a * 3));
        double range = (double) renderRange.get() * renderRange.get();

        if (playerChunks.get()) {
            Color rf = runningColor.get();
            Color rl = new Color(rf.r, rf.g, rf.b, Math.min(255, rf.a * 3));
            for (Running r : running.values()) {
                double x0 = r.chunkX * 16.0, z0 = r.chunkZ * 16.0;
                double dx = x0 + 8 - mc.player.getX(), dz = z0 + 8 - mc.player.getZ();
                if (dx * dx + dz * dz > range) continue;
                event.renderer.box(x0, r.y, z0, x0 + 16, r.y, z0 + 16, rf, rl, ShapeMode.Both, 0);
            }
            // Where the unseen player probably is: a tall column at the middle of their running chunks.
            for (Presence p : presences) {
                if (p.chunks.size() < presenceChunks.get()) continue;
                double x = p.centerX * 16 + 8, z = p.centerZ * 16 + 8;
                double dx = x - mc.player.getX(), dz = z - mc.player.getZ();
                if (dx * dx + dz * dz > range) continue;
                event.renderer.box(x - 1, mc.world.getBottomY(), z - 1, x + 1, p.y + 40, z + 1, new Color(rf.r, rf.g, rf.b, 40), rl, ShapeMode.Both, 0);
            }
        }
        for (Spot s : spots.values()) {
            if (!s.marked) continue;
            double dx = s.x + 0.5 - mc.player.getX(), dz = s.z + 0.5 - mc.player.getZ();
            if (dx * dx + dz * dz > range) continue;
            // The spot itself, and the chunk around it at that height.
            event.renderer.box(s.x, s.y, s.z, s.x + 1, s.y + 1, s.z + 1, fill, line, ShapeMode.Both, 0);
            double x0 = s.chunkX * 16.0, z0 = s.chunkZ * 16.0;
            event.renderer.box(x0, s.y, z0, x0 + 16, s.y + 1, z0 + 16, new Color(fill.r, fill.g, fill.b, fill.a / 3), line, ShapeMode.Lines, 0);
        }
    }

    @Override
    public String getInfoString() {
        int n = 0;
        for (Spot s : spots.values()) if (s.marked) n++;
        return n == 0 ? null : String.valueOf(n);
    }

    /** For other modules: marked spots, as positions. */
    public List<BlockPos> marked() {
        List<BlockPos> out = new ArrayList<>();
        for (Spot s : spots.values()) if (s.marked) out.add(new BlockPos(s.x, s.y, s.z));
        return out;
    }
}
