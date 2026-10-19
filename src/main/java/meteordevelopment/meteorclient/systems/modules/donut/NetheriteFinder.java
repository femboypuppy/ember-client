package meteordevelopment.meteorclient.systems.modules.donut;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
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
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.RandomSplitter;
import net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.OrePlacedFeatures;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.feature.util.PlacedFeatureIndexer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds ancient debris through anti-xray, no seed needed.
 *
 * A chunk is sent as 16x16x16 sections, and each section carries a palette: the list of block types it uses. Paper's
 * anti-xray swaps hidden blocks for netherrack but sends the palette as it is, so a section whose list still names
 * ancient debris has at least one real piece inside. Those sections are drawn as areas. Debris the server shows for
 * real (you mined next to it) is drawn as an exact block.
 *
 * With the world seed, the areas also check the seed: the right one puts its debris in exactly the listed sections,
 * and then each area shows its exact blocks.
 */
public class NetheriteFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("How many chunks around you to show areas in.")
        .defaultValue(16)
        .range(1, 24)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Integer> maxAreas = sgGeneral.add(new IntSetting.Builder()
        .name("max-areas")
        .description("Most areas drawn at once, nearest first.")
        .defaultValue(24)
        .range(1, 500)
        .sliderRange(1, 100)
        .build()
    );

    private final SettingGroup sgScan = settings.createGroup("Reveal Scan");

    private final Setting<Boolean> revealScan = sgScan.add(new BoolSetting.Builder()
        .name("reveal-scan")
        .description("Checks every hidden-looking block within reach for debris: starts digging it, then the next one, and the server answers with what the first one really is. Nothing gets broken. Sends dig packets, so an anticheat could notice.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> scanSpeed = sgScan.add(new IntSetting.Builder()
        .name("scan-speed")
        .description("Blocks checked per tick. Everything within reach takes a few seconds at 3.")
        .defaultValue(3)
        .range(1, 10)
        .sliderRange(1, 6)
        .visible(revealScan::get)
        .build()
    );

    private final Setting<String> seed = sgGeneral.add(new StringSetting.Builder()
        .name("seed")
        .description("The world seed (DonutSMP's is filled in). It's proven against the world before anything is drawn: the bedrock, then the section lists or the hidden magma the reveal scan uncovers. A wrong seed is ignored.")
        .defaultValue(DONUT_SEED)
        .placeholder("world seed (optional)")
        .wide()
        .onChanged(v -> resetSeed())
        .build()
    );

    private final Setting<SettingColor> areaColor = sgRender.add(new ColorSetting.Builder()
        .name("area-color")
        .description("Areas with at least one ancient debris inside.")
        .defaultValue(new SettingColor(255, 128, 44, 210))
        .build()
    );

    private final Setting<SettingColor> foundColor = sgRender.add(new ColorSetting.Builder()
        .name("found-color")
        .description("Debris the server has shown you: a real block.")
        .defaultValue(new SettingColor(110, 255, 160, 235))
        .build()
    );

    private final Setting<SettingColor> pinnedColor = sgRender.add(new ColorSetting.Builder()
        .name("exact-color")
        .description("Exact debris spots from a seed that matches the areas.")
        .defaultValue(new SettingColor(255, 222, 110, 235))
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Lines to the nearest finds.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> lookAtInfo = sgRender.add(new BoolSetting.Builder()
        .name("look-at-info")
        .description("Look at an area or block to see what it is and how far.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> infoScale = sgRender.add(new DoubleSetting.Builder()
        .name("info-scale")
        .description("Size of the look-at info.")
        .defaultValue(1)
        .range(0.5, 2)
        .sliderRange(0.6, 1.6)
        .visible(lookAtInfo::get)
        .build()
    );

    /** Debris only generates from Y 6 (the large one's lowest spread) to 121; above section 7 there's none. */
    private static final int MIN_Y = 6, MAX_Y = 121, TOP_SECTION = 7;
    private static final int MAX_TRACERS = 4, MAX_BLOCKS = 64;
    private static final double LOOK_ANGLE = 5;

    /** One section the server listed ancient debris in, shrunk to the blocks that could be hiding it. */
    private record Area(int cx, int sy, int cz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        double centerX() { return (minX + maxX + 1) / 2.0; }
        double centerY() { return (minY + maxY + 1) / 2.0; }
        double centerZ() { return (minZ + maxZ + 1) / 2.0; }

        boolean contains(Vec3d p) {
            return p.x >= minX && p.x <= maxX + 1 && p.y >= minY && p.y <= maxY + 1 && p.z >= minZ && p.z <= maxZ + 1;
        }
    }

    private final Long2ObjectOpenHashMap<Area> areas = new Long2ObjectOpenHashMap<>();
    /** Per chunk: sections listing debris, and sections whose list could be read at all (huge lists name every block). */
    private final Long2IntOpenHashMap listed = new Long2IntOpenHashMap(), readable = new Long2IntOpenHashMap();
    /** Real debris blocks the server has sent. */
    private final Set<BlockPos> found = new LinkedHashSet<>();

    private List<Area> shownAreas = List.of();
    private List<BlockPos> shownFound = List.of(), shownPinned = List.of();
    private World lastWorld;
    private int timer;
    private long ticks;

    public NetheriteFinder() {
        super(Categories.Donut, "netherite-finder", "Shows areas with ancient debris through anti-xray, and the exact blocks once you get close or have the seed.");
    }

    @Override
    public void onActivate() {
        clearWorld();
        resetSeed();
        // Chunks already here were read before the module was on.
        if (mc.world != null && mc.world.getRegistryKey() == World.NETHER) {
            for (net.minecraft.world.chunk.Chunk chunk : meteordevelopment.meteorclient.utils.Utils.chunks()) {
                if (chunk instanceof WorldChunk c) readChunk(c);
            }
        }
        lastWorld = mc.world;
    }

    @Override
    public void onDeactivate() {
        FindBeacon.withdraw(this);
    }

    private void clearWorld() {
        areas.clear();
        listed.clear();
        readable.clear();
        found.clear();
        mined.clear();
        announced.clear();
        watched.clear();
        empty.clear();
        scannedAt.clear();
        revealedMagma.clear();
        magmaChecked = 0;
        revealed.clear();
        shownAreas = List.of();
        shownFound = List.of();
        shownPinned = List.of();
        bedrockChecked.clear();
        bedrockMatch = bedrockTotal = 0;
        seedState = SEED_UNKNOWN;
        if (pinState != PIN_SEARCHING) pinState = PIN_UNKNOWN;
        statSections = statListed = statLogged = 0;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        clearWorld();
    }

    // Reading the server's chunks.

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.world == null || mc.world.getRegistryKey() != World.NETHER) return;
        readChunk(event.chunk());
    }

    private long areaNotifiedAt = -100_000;
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet announced = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    private void readChunk(WorldChunk chunk) {
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        long key = chunk.getPos().toLong();
        if (arrived.size() > 50_000) arrived.clear();
        arrived.put(key, ticks);
        for (int sy = 0; sy <= TOP_SECTION; sy++) {
            areas.remove(ChunkSectionPos.asLong(cx, sy, cz));
            mined.remove(ChunkSectionPos.asLong(cx, sy, cz));
        }
        // A reload resends what the server has now: debris seen before stays known unless it's gone for real.
        found.removeIf(p -> p.getX() >> 4 == cx && p.getZ() >> 4 == cz && !mightBeDebris(chunk.getBlockState(p)));

        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord(), hit = 0, read = 0;
        for (int i = 0; i < sections.length; i++) {
            int sy = bottom + i;
            if (sy < 0 || sy > TOP_SECTION) continue;
            PalettedContainer<BlockState> blocks = sections[i].getBlockStateContainer();
            // The global palette answers yes to everything, so it can't tell.
            if (blocks.hasAny(s -> false)) continue;
            read |= 1 << sy;
            statSections++;
            if (!blocks.hasAny(s -> s.isOf(Blocks.ANCIENT_DEBRIS))) continue;
            hit |= 1 << sy;
            statListed++;
            Area area = scan(chunk, sy);
            if (area != null && areas.put(ChunkSectionPos.asLong(cx, sy, cz), area) == null && mc.player != null && ticks - areaNotifiedAt > 60
                && announced.add(ChunkSectionPos.asLong(cx, sy, cz))) {
                areaNotifiedAt = ticks;
                EmberNotificationsHud.push("Netherite finder", "Debris area at " + (int) area.centerX() + " " + area.minY + "-" + area.maxY + " " + (int) area.centerZ()
                    + " (" + Math.round(Math.sqrt(distanceSq(area, mc.player.getEyePos()))) + "m)", EmberNotificationsHud.GOOD);
            }
        }
        listed.put(key, hit);
        readable.put(key, read);

        if (statLogged < 40 && hit != 0) {
            statLogged++;
            // Visible debris lists itself; a listed section with none in view hides some (the palette leak).
            int visible = 0;
            for (BlockPos p : found) if (p.getX() >> 4 == cx && p.getZ() >> 4 == cz && (hit & (1 << (p.getY() >> 4))) != 0) visible++;
            MeteorClient.LOG.info("[Ember Netherite] Chunk {}, {} (x {} z {}): debris listed in sections {}, {} of it in view ({} of {} sections so far)", cx, cz,
                cx << 4, cz << 4, Integer.toBinaryString(hit), visible, statListed, statSections);
        }
        if (areas.size() > 50_000) areas.clear();
    }

    /**
     * Debris in view, plus the box of blocks that could be hiding one: netherrack (what anti-xray shows instead) with no
     * air beside it, since debris never generates next to air.
     *
     * @return null when nothing in the section could still be debris: the listed one was mined while the chunk stayed loaded
     */
    private Area scan(WorldChunk chunk, int sy) {
        int x0 = chunk.getPos().getStartX(), z0 = chunk.getPos().getStartZ();
        int y0 = Math.max(sy << 4, MIN_Y), y1 = Math.min((sy << 4) + 15, MAX_Y);
        int minX = Integer.MAX_VALUE, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable(), side = new BlockPos.Mutable();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x < x0 + 16; x++) {
                for (int z = z0; z < z0 + 16; z++) {
                    BlockState state = chunk.getBlockState(pos.set(x, y, z));
                    boolean debris = state.isOf(Blocks.ANCIENT_DEBRIS);
                    if (debris) found.add(pos.toImmutable());
                    else if (!state.isOf(Blocks.NETHERRACK) || touchesAir(chunk, pos, side)) continue;
                    if (minX == Integer.MAX_VALUE) {
                        minX = maxX = x;
                        minY = maxY = y;
                        minZ = maxZ = z;
                    } else {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                        minZ = Math.min(minZ, z);
                        maxZ = Math.max(maxZ, z);
                    }
                }
            }
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new Area(chunk.getPos().x, sy, chunk.getPos().z, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Next to air, looking only inside this chunk (blocks over the edge count as solid). */
    private static boolean touchesAir(WorldChunk chunk, BlockPos pos, BlockPos.Mutable side) {
        int x0 = chunk.getPos().getStartX(), z0 = chunk.getPos().getStartZ();
        for (Direction d : Direction.values()) {
            side.set(pos, d);
            if (side.getX() < x0 || side.getX() > x0 + 15 || side.getZ() < z0 || side.getZ() > z0 + 15) continue;
            if (chunk.getBlockState(side).isAir()) return true;
        }
        return false;
    }

    private static boolean mightBeDebris(BlockState state) {
        return state.isOf(Blocks.ANCIENT_DEBRIS) || state.isOf(Blocks.NETHERRACK);
    }

    /** Debris the server reveals when someone digs near it, and debris that got mined. */
    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.world == null || mc.world.getRegistryKey() != World.NETHER) return;
        noteReveal(event);
        if (event.newState.isOf(Blocks.ANCIENT_DEBRIS)) {
            BlockPos pos = event.pos.toImmutable();
            if (found.size() < 5000 && found.add(pos) && mc.player != null) {
                MeteorClient.LOG.info("[Ember Netherite] Ancient debris revealed at {} ({} taps so far)", pos.toShortString(), taps);
                if (ticks - notifiedAt > 40) {
                    notifiedAt = ticks;
                    EmberNotificationsHud.push("Netherite finder", "Ancient debris at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + " (" + Math.round(Math.sqrt(pos.getSquaredDistance(mc.player.getEyePos()))) + "m)", EmberNotificationsHud.GOOD);
                }
            }
        } else if (event.oldState.isOf(Blocks.ANCIENT_DEBRIS) || found.contains(event.pos)) {
            found.remove(event.pos);
            // The server's list only updates when the chunk is sent again, so this area may be empty now.
            mined.add(ChunkSectionPos.asLong(event.pos.getX() >> 4, event.pos.getY() >> 4, event.pos.getZ() >> 4));
        }
    }

    // Reveal scan. When a player starts digging one block and then starts another without finishing, the server corrects
    // the first with its real state ("abort destroying since another started": ServerPlayerGameMode sends
    // getBlockState(destroyPos)). That isn't anti-xray's reveal list, so it shows hidden debris too, which DonutSMP never
    // reveals the usual way (0 debris in ~1,700 dig reveals, 2026-10-19). One start per block: the next start shows it.

    private static final double TAP_REACH = 5.0;
    /** Blocks already asked about. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet revealed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private int taps;
    private long handWarnedAt = -100_000;

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (!revealScan.get() || mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) return;
        if (mc.world.getRegistryKey() != World.NETHER || mc.currentScreen != null || mc.player.isUsingItem() || mc.interactionManager.isBreakingBlock()) return;
        if (mc.player.isSpectator() || mc.player.getAbilities().creativeMode) return;
        if (revealed.size() > 3_000_000) revealed.clear();

        Vec3d eye = mc.player.getEyePos();
        // Hidden-looking blocks in reach: netherrack with no air beside it (anti-xray's stand-in; open blocks are sent as
        // they are). The debris layer goes first.
        List<BlockPos> order = new ArrayList<>();
        int r = (int) Math.ceil(TAP_REACH);
        BlockPos center = BlockPos.ofFloored(eye);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (pos.getY() < MIN_Y || pos.getY() > MAX_Y || revealed.contains(pos.asLong())) continue;
                    if (boxDistanceSq(eye, pos) > TAP_REACH * TAP_REACH || !mc.world.getBlockState(pos).isOf(Blocks.NETHERRACK)) continue;
                    if (touchesAir(pos)) continue;
                    order.add(pos.toImmutable());
                }
            }
        }
        if (order.isEmpty()) return;
        order.sort(Comparator.<BlockPos>comparingInt(p -> p.getY() >= 8 && p.getY() <= 24 ? 0 : 1).thenComparingDouble(p -> boxDistanceSq(eye, p)));
        // Taps in one tick at least 3 apart: then a real block coming back at a tapped spot can only be the server's
        // correction (dig reveals cover the blocks within 2 of a tap, never the tapped one), which the log counts.
        List<BlockPos> picks = new ArrayList<>();
        for (BlockPos p : order) {
            if (picks.size() >= scanSpeed.get()) break;
            boolean near = false;
            for (BlockPos q : picks) near |= p.getManhattanDistance(q) < 3;
            if (!near) picks.add(p);
        }

        // A pickaxe would break netherrack on the spot: ask from a slot that holds no tool.
        int selected = mc.player.getInventory().getSelectedSlot(), slot = tapSlot(selected);
        if (slot < 0) {
            if (ticks - handWarnedAt > 1200) {
                handWarnedAt = ticks;
                EmberNotificationsHud.push("Netherite finder", "Reveal scan needs a hotbar slot without a tool", EmberNotificationsHud.WARN);
            }
            return;
        }
        if (slot != selected) mc.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(slot));
        for (BlockPos tap : picks) {
            startDigging(tap, eye);
            revealed.add(tap.asLong());
            tappedAt.put(tap.asLong(), ticks);
            if (watched.containsKey(tap)) scannedAt.putIfAbsent(tap, ticks);
            taps++;
            if (tap.getY() >= 8 && tap.getY() <= 24) tapsLow++;
        }
        if (tappedAt.size() > 200_000) tappedAt.clear();
        // Starting the first one again shows the last one, then stop: nothing is left half dug.
        BlockPos first = picks.get(0);
        startDigging(first, eye);
        mc.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket(
            net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, first, Direction.UP));
        if (slot != selected) mc.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(selected));
    }

    private void startDigging(BlockPos tap, Vec3d eye) {
        Direction side = Direction.getFacing(eye.x - (tap.getX() + 0.5), eye.y - (tap.getY() + 0.5), eye.z - (tap.getZ() + 0.5));
        mc.interactionManager.sendSequencedPacket(mc.world, sequence -> new net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket(
            net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, tap, side, sequence));
    }

    private int tapSlot(int selected) {
        var inventory = mc.player.getInventory();
        if (!inventory.getStack(selected).contains(net.minecraft.component.DataComponentTypes.TOOL)) return selected;
        for (int i = 0; i < 9; i++) if (inventory.getStack(i).isEmpty()) return i;
        for (int i = 0; i < 9; i++) if (!inventory.getStack(i).contains(net.minecraft.component.DataComponentTypes.TOOL)) return i;
        return -1;
    }

    private boolean touchesAir(BlockPos pos) {
        for (Direction d : Direction.values()) if (mc.world.getBlockState(pos.offset(d)).isAir()) return true;
        return false;
    }

    private static double boxDistanceSq(Vec3d p, BlockPos pos) {
        double dx = Math.max(0, Math.max(pos.getX() - p.x, p.x - (pos.getX() + 1)));
        double dy = Math.max(0, Math.max(pos.getY() - p.y, p.y - (pos.getY() + 1)));
        double dz = Math.max(0, Math.max(pos.getZ() - p.z, p.z - (pos.getZ() + 1)));
        return dx * dx + dy * dy + dz * dz;
    }

    // How this server reveals hidden blocks: something that showed as netherrack turning into an ore, measured from the
    // last block you broke. Paper reveals 2 blocks around every block you start digging.

    private long notifiedAt = -100_000;
    private BlockPos lastDig;
    private long lastDigAt;
    private final java.util.Map<String, Integer> revealTypes = new java.util.TreeMap<>();
    private final int[] revealDistance = new int[6];
    private final java.util.Map<String, Integer> answerTypes = new java.util.TreeMap<>();
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap tappedAt = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private int tapsLow;
    private int revealsFar;
    private long revealLoggedAt;

    private void noteReveal(BlockUpdateEvent event) {
        if (mc.player == null) return;
        BlockPos pos = event.pos.toImmutable();
        if (event.newState.isAir() && !event.oldState.isAir() && mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) < 49) {
            lastDig = pos;
            lastDigAt = ticks;
            return;
        }
        if (!event.oldState.isOf(Blocks.NETHERRACK) || event.newState.isAir() || event.newState.isOf(Blocks.NETHERRACK) || !event.newState.getFluidState().isEmpty()) return;
        revealTypes.merge(name(event.newState), 1, Integer::sum);
        long at = tappedAt.getOrDefault(pos.asLong(), Long.MIN_VALUE);
        if (ticks - at <= 10) answerTypes.merge(name(event.newState), 1, Integer::sum);
        if (event.newState.isOf(Blocks.MAGMA_BLOCK) && revealedMagma.size() < 5000) revealedMagma.add(pos);
        if (lastDig != null && ticks - lastDigAt <= 40) {
            int d = pos.getManhattanDistance(lastDig);
            revealDistance[Math.min(d, 5)]++;
        } else {
            revealsFar++;
        }
        if (ticks - revealLoggedAt > 600) {
            revealLoggedAt = ticks;
            MeteorClient.LOG.info("[Ember Netherite] Reveals so far {}; blocks from your last dig 0-5+: {}; with no dig of yours just before: {}",
                revealTypes, java.util.Arrays.toString(revealDistance), revealsFar);
            MeteorClient.LOG.info("[Ember Netherite] Real-state answers at tapped blocks {} ({} taps, {} at Y 8-24)", answerTypes, taps, tapsLow);
        }
    }

    /** Sections someone mined debris in since the server last sent them. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet mined = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /** Mined in, and no debris known to be left: the listing may be out of date. */
    private boolean maybeEmpty(Area a) {
        if (!mined.contains(ChunkSectionPos.asLong(a.cx, a.sy, a.cz))) return false;
        for (BlockPos pos : found) if (inArea(a, pos)) return false;
        for (BlockPos pos : shownPinned) if (inArea(a, pos)) return false;
        return true;
    }

    // What to draw, worked out a few times a second.

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ticks++;
        if (mc.world != lastWorld) {
            clearWorld();
            lastWorld = mc.world;
        }
        if (mc.player == null || mc.world == null || ++timer < 10) return;
        timer = 0;
        if (mc.world.getRegistryKey() != World.NETHER) {
            shownAreas = List.of();
            shownFound = List.of();
            shownPinned = List.of();
            return;
        }

        if (seedSet && !onDonut() && indexesReady()) {
            checkSeed();
            checkPins();
        }

        BlockPos me = mc.player.getBlockPos();
        int r = range.get() * 16;
        long rangeSq = (long) r * r;

        List<Area> near = new ArrayList<>();
        for (Area area : areas.values()) {
            double dx = area.centerX() - mc.player.getX(), dz = area.centerZ() - mc.player.getZ();
            if (dx * dx + dz * dz <= rangeSq) near.add(area);
        }
        near.sort(Comparator.comparingDouble(a -> distanceSq(a, mc.player.getEyePos())));
        if (near.size() > maxAreas.get()) near = new ArrayList<>(near.subList(0, maxAreas.get()));

        List<BlockPos> foundNear = new ArrayList<>();
        for (BlockPos pos : found) {
            if (horizontalSq(pos, me) <= rangeSq) foundNear.add(pos);
        }
        foundNear.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(me)));

        Set<BlockPos> pinnedSet = new LinkedHashSet<>();
        if (pinState == PIN_GOOD) {
            ChunkPos here = mc.player.getChunkPos();
            int rc = range.get();
            for (int dx = -rc; dx <= rc; dx++) {
                for (int dz = -rc; dz <= rc; dz++) {
                    int[] spots = predicted.computeIfAbsent(ChunkPos.toLong(here.x + dx, here.z + dz), k -> predict(seedValue, ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k)));
                    for (int i = 0; i < spots.length; i += 3) {
                        BlockPos pos = new BlockPos(spots[i], spots[i + 1], spots[i + 2]);
                        if (found.contains(pos) || empty.contains(pos) || !canHold(pos)) continue;
                        if (pinMode == MODE_LISTED && !isListed(pos)) continue;
                        pinnedSet.add(pos);
                        if (watched.size() < 5000) watched.putIfAbsent(pos, -1L);
                    }
                }
            }
            if (predicted.size() > 20_000) predicted.clear();
        }
        checkOpened();
        List<BlockPos> pinned = new ArrayList<>(pinnedSet);
        pinned.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(me)));

        shownAreas = near;
        shownFound = foundNear.size() > MAX_BLOCKS ? foundNear.subList(0, MAX_BLOCKS) : foundNear;
        shownPinned = pinned.size() > MAX_BLOCKS ? pinned.subList(0, MAX_BLOCKS) : pinned;
    }

    // Keeping score: every spot shown gets checked once it's opened up, since anti-xray has to show the real block then.

    /** Spots shown, and since when they've been open to air (-1 = still closed). */
    private final java.util.Map<BlockPos, Long> watched = new java.util.HashMap<>();
    /** Opened up and turned out to be plain netherrack. */
    private final Set<BlockPos> empty = new java.util.HashSet<>();
    private int hits, misses;
    /** Shown spots the reveal scan covered, and when. */
    private final java.util.Map<BlockPos, Long> scannedAt = new java.util.HashMap<>();

    /**
     * DonutSMP's magma follows the world seed but its debris doesn't (a secret per-feature seed, it looks like): spots the
     * scan checks keep coming up netherrack. Stop drawing seed spots then; the scan keeps finding the real ones.
     */
    private void giveUpOnSpotsIfWrong() {
        if (pinState != PIN_GOOD || hits * 4 >= misses || misses < 4) return;
        pinState = PIN_NONE;
        shownPinned = List.of();
        MeteorClient.LOG.info("[Ember Netherite] Seed spots off: {} of {} checked had debris", hits, hits + misses);
        EmberNotificationsHud.push("Netherite finder", "Debris here doesn't follow the seed (" + hits + " of " + (hits + misses)
            + " spots): seed spots off, the scan finds real debris", EmberNotificationsHud.WARN);
    }

    private void checkOpened() {
        var it = watched.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            BlockPos pos = entry.getKey();
            if (!(mc.world.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) instanceof WorldChunk)) continue;
            BlockState state = mc.world.getBlockState(pos);
            if (state.isOf(Blocks.ANCIENT_DEBRIS)) {
                hits++;
                found.add(pos);
                it.remove();
                MeteorClient.LOG.info("[Ember Netherite] Spot {} opened: ancient debris ({} of {} so far)", pos.toShortString(), hits, hits + misses);
                continue;
            }
            if (!state.isOf(Blocks.NETHERRACK)) {
                // Mined or changed before we saw what it was.
                if (state.isAir() && entry.getValue() >= 0) it.remove();
                continue;
            }
            Long scanned = scannedAt.get(pos);
            if (scanned != null && ticks - scanned >= 30) {
                // The reveal scan made the server send its surroundings, and it stayed netherrack: no debris.
                misses++;
                empty.add(pos);
                it.remove();
                scannedAt.remove(pos);
                MeteorClient.LOG.info("[Ember Netherite] Spot {} scanned: netherrack ({} of {} had debris)", pos.toShortString(), hits, hits + misses);
                giveUpOnSpotsIfWrong();
                continue;
            }
            boolean open = false;
            for (Direction d : Direction.values()) {
                BlockPos side = pos.offset(d);
                // A neighbour in a chunk that isn't loaded reads as air: that's not an opening.
                if (!(mc.world.getChunk(side.getX() >> 4, side.getZ() >> 4, ChunkStatus.FULL, false) instanceof WorldChunk)) continue;
                open |= mc.world.getBlockState(side).isAir();
            }
            if (!open) {
                entry.setValue(-1L);
            } else if (entry.getValue() < 0) {
                entry.setValue(ticks);
            } else if (ticks - entry.getValue() >= 40) {
                // Open for 2 seconds and still netherrack: the server would have shown debris by now.
                misses++;
                empty.add(pos);
                it.remove();
                MeteorClient.LOG.info("[Ember Netherite] Spot {} opened: netherrack ({} of {} had debris)", pos.toShortString(), hits, hits + misses);
                giveUpOnSpotsIfWrong();
            }
        }
    }

    private boolean isListed(BlockPos pos) {
        int sy = pos.getY() >> 4;
        return sy >= 0 && sy <= TOP_SECTION && (listed.get(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4)) & (1 << sy)) != 0;
    }

    private boolean isReadable(BlockPos pos) {
        int sy = pos.getY() >> 4;
        return sy >= 0 && sy <= TOP_SECTION && (readable.get(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4)) & (1 << sy)) != 0;
    }

    /** Shows as debris, or as netherrack with no air beside it (anti-xray's stand-in for a hidden block). */
    private boolean canHold(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isOf(Blocks.ANCIENT_DEBRIS)) return true;
        if (!state.isOf(Blocks.NETHERRACK)) return false;
        for (Direction d : Direction.values()) {
            if (mc.world.getBlockState(pos.offset(d)).isAir()) return false;
        }
        return true;
    }

    private static double distanceSq(Area a, Vec3d p) {
        double dx = MathHelper.clamp(p.x, a.minX, a.maxX + 1) - p.x;
        double dy = MathHelper.clamp(p.y, a.minY, a.maxY + 1) - p.y;
        double dz = MathHelper.clamp(p.z, a.minZ, a.maxZ + 1) - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static long horizontalSq(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    // Drawing: areas as corner brackets, blocks as solid boxes.

    private Object hovered;
    private Object infoTarget;
    private float hoverAnim;
    private final EmberAnim.Clock clock = new EmberAnim.Clock();

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;
        Color area = areaColor.get(), foundLine = foundColor.get(), pinnedLine = pinnedColor.get();

        for (Area a : shownAreas) brackets(event, a, maybeEmpty(a) ? FindBeacon.alpha(area, area.a / 3) : area);
        for (BlockPos pos : shownPinned) event.renderer.box(pos, FindBeacon.alpha(pinnedLine, pinnedLine.a / 3), pinnedLine, ShapeMode.Both, 0);
        for (BlockPos pos : shownFound) event.renderer.box(pos, FindBeacon.alpha(foundLine, foundLine.a / 3), foundLine, ShapeMode.Both, 0);

        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        Vec3d look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());

        if (tracers.get()) {
            // Real blocks first, then exact spots, then the nearest areas.
            List<Vec3d> targets = new ArrayList<>();
            for (BlockPos pos : shownFound) targets.add(Vec3d.ofCenter(pos));
            for (BlockPos pos : shownPinned) targets.add(Vec3d.ofCenter(pos));
            for (Area a : shownAreas) {
                if (targets.size() >= MAX_TRACERS) break;
                if (!a.contains(cam)) targets.add(new Vec3d(a.centerX(), a.centerY(), a.centerZ()));
            }
            for (int i = 0; i < targets.size() && i < MAX_TRACERS; i++) {
                Vec3d t = targets.get(i);
                Color c = i < shownFound.size() ? foundLine : i < shownFound.size() + shownPinned.size() ? pinnedLine : area;
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, t.x, t.y, t.z, c);
            }
        }

        // What the crosshair is on: a block beats an area, the area you're in beats the rest.
        Object best = null;
        double bestAngle = lookAtInfo.get() ? LOOK_ANGLE : -1;
        for (BlockPos pos : shownFound) {
            double angle = angle(cam, look, Vec3d.ofCenter(pos));
            if (angle < bestAngle) {
                bestAngle = angle;
                best = pos;
            }
        }
        for (BlockPos pos : shownPinned) {
            double angle = angle(cam, look, Vec3d.ofCenter(pos));
            if (angle < bestAngle) {
                bestAngle = angle;
                best = pos;
            }
        }
        if (best == null && lookAtInfo.get()) {
            for (Area a : shownAreas) {
                double angle = a.contains(cam) ? 0 : angle(cam, look, new Vec3d(a.centerX(), a.centerY(), a.centerZ()));
                if (angle < bestAngle) {
                    bestAngle = angle;
                    best = a;
                }
            }
        }
        if (best != null) {
            FindBeacon.offer(this, bestAngle);
            infoTarget = best;
        } else {
            FindBeacon.withdraw(this);
        }
        hovered = best;
    }

    /** Short lines at the eight corners, plus a faint fill. */
    private static void brackets(Render3DEvent event, Area a, Color color) {
        double x0 = a.minX, y0 = a.minY, z0 = a.minZ, x1 = a.maxX + 1, y1 = a.maxY + 1, z1 = a.maxZ + 1;
        event.renderer.box(x0, y0, z0, x1, y1, z1, FindBeacon.alpha(color, color.a / 14), color, ShapeMode.Sides, 0);
        double lx = Math.min(3, (x1 - x0) / 3), ly = Math.min(3, (y1 - y0) / 3), lz = Math.min(3, (z1 - z0) / 3);
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? x0 : x1, y = (i & 2) == 0 ? y0 : y1, z = (i & 4) == 0 ? z0 : z1;
            double sx = x == x0 ? lx : -lx, sy = y == y0 ? ly : -ly, sz = z == z0 ? lz : -lz;
            event.renderer.line(x, y, z, x + sx, y, z, color);
            event.renderer.line(x, y, z, x, y + sy, z, color);
            event.renderer.line(x, y, z, x, y, z + sz, color);
        }
    }

    private static double angle(Vec3d cam, Vec3d look, Vec3d target) {
        Vec3d to = target.subtract(cam);
        double length = to.length();
        if (length < 0.5) return 0;
        return Math.toDegrees(Math.acos(MathHelper.clamp(look.dotProduct(to) / length, -1, 1)));
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        float dt = clock.tick();
        boolean show = hovered != null && FindBeacon.shows(this) && mc.currentScreen == null && !mc.options.hudHidden;
        hoverAnim = EmberAnim.approach(hoverAnim, show ? 1f : 0f, dt, 0.07);
        Object target = infoTarget;
        if (target == null || hoverAnim <= 0.01f || mc.player == null || mc.currentScreen != null || mc.options.hudHidden) return;

        Vec3d eye = mc.player.getEyePos();
        Color color;
        String verdict, where, reason;
        if (target instanceof Area a) {
            boolean inside = a.contains(eye);
            int exact = 0;
            for (BlockPos pos : shownPinned) if (inArea(a, pos)) exact++;
            for (BlockPos pos : shownFound) if (inArea(a, pos)) exact++;
            color = areaColor.get();
            verdict = inside ? "YOU'RE IN A DEBRIS AREA" : "DEBRIS AREA";
            where = inside ? "Y " + a.minY + "-" + a.maxY : Math.round(Math.sqrt(distanceSq(a, eye))) + "m  ·  " + FindBeacon.dig(a.centerY());
            reason = exact > 0 ? exact + " exact " + (exact == 1 ? "spot" : "spots") + " marked inside"
                : maybeEmpty(a) ? "Debris was just mined here: there may be none left"
                : "At least 1 ancient debris in here  ·  mine through it, it shows when you're 2 blocks away";
        } else {
            BlockPos pos = (BlockPos) target;
            boolean real = found.contains(pos);
            color = real ? foundColor.get() : pinnedColor.get();
            verdict = "ANCIENT DEBRIS";
            where = Math.round(Math.sqrt(pos.getSquaredDistance(eye))) + "m  ·  " + FindBeacon.dig(pos.getY() + 0.5);
            if (real) reason = "Real block: the server showed it";
            else if (pinMode == MODE_MAGMA) reason = "Debris spot from the seed (proven by hidden magma, " + pinPercent + "%)  ·  " + (hits + misses > 0 ? hits + " of " + (hits + misses) + " checked had debris" : "the reveal scan confirms it when you get close");
            else reason = "Exact spot from the seed (" + pinPercent + "% match)";
        }

        HudRenderer r = HudRenderer.INSTANCE;
        r.begin(event.drawContext);
        try {
            FindBeacon.info(r, color, verdict, where, reason, hoverAnim, infoScale.get());
        } finally {
            r.end();
        }
    }

    private static boolean inArea(Area a, BlockPos pos) {
        return pos.getX() >= a.minX && pos.getX() <= a.maxX && pos.getY() >= a.minY && pos.getY() <= a.maxY && pos.getZ() >= a.minZ && pos.getZ() <= a.maxZ;
    }

    @Override
    public String getInfoString() {
        if (mc.world != null && mc.world.getRegistryKey() != World.NETHER) return "nether only";
        int exact = shownFound.size() + shownPinned.size();
        String areasText = shownAreas.isEmpty() ? "" : " · " + shownAreas.size() + (shownAreas.size() == 1 ? " area" : " areas");
        if (revealScan.get()) {
            // Most debris is in the large clusters, Y 8-24: scanning higher mostly turns up magma.
            if (shownFound.isEmpty() && mc.player != null && (mc.player.getY() > 26 || mc.player.getY() < 6)) return "go to Y 15 for debris" + areasText;
            return shownFound.size() + " debris · " + (revealed.size() >= 1000 ? revealed.size() / 1000 + "k" : revealed.size()) + " checked" + areasText;
        }
        String text = shownAreas.size() + (shownAreas.size() == 1 ? " area" : " areas");
        if (exact > 0) text += " · " + exact + " exact";
        if (pinState == PIN_SEARCHING || seedSet && pinState == PIN_UNKNOWN) text += " · checking seed";
        return text;
    }

    // The optional seed. The bedrock checks its low 48 bits (it uses the old Java random); the areas check the rest,
    // and fix the top 16 bits if they're off.

    private static final int SEED_UNKNOWN = 0, SEED_GOOD = 1, SEED_BAD = 2;
    private static final int PIN_UNKNOWN = 0, PIN_GOOD = 1, PIN_SEARCHING = 2, PIN_NONE = 3;
    private static final int STEP = GenerationStep.Feature.UNDERGROUND_DECORATION.ordinal();
    private static int largeIndex = -1, smallIndex = -1;
    /** Index shift found by the search: a server on another version can list the features differently. */
    private static int indexShift;

    private long seedValue;
    private boolean seedSet;
    private int seedState = SEED_UNKNOWN;
    private volatile int pinState = PIN_UNKNOWN;
    private int pinPercent;
    private long pinCheckAt;
    private long bedrockMatch, bedrockTotal;
    private final Long2ObjectOpenHashMap<Boolean> bedrockChecked = new Long2ObjectOpenHashMap<>();
    /** The module's tick each chunk arrived at (its own clock: the world's time jumps right as chunks arrive). */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap arrived = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<int[]> predicted = new Long2ObjectOpenHashMap<>();
    private volatile Long foundSeed;
    private volatile boolean searchFailed;
    private String error;
    private int statSections, statListed, statLogged;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Ember-NetheriteFinder");
        thread.setDaemon(true);
        return thread;
    });

    private void resetSeed() {
        predicted.clear();
        watched.clear();
        shownPinned = List.of();
        bedrockChecked.clear();
        bedrockMatch = bedrockTotal = 0;
        seedState = SEED_UNKNOWN;
        if (pinState != PIN_SEARCHING) pinState = PIN_UNKNOWN;
        pinMode = MODE_LISTED;
        magmaChecked = 0;

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

    /** Whether the seed's debris lands in the listed sections, and if not, which top 16 bits would. */
    private void checkPins() {
        Long fixed = foundSeed;
        if (fixed != null) {
            foundSeed = null;
            pinState = PIN_UNKNOWN;
            MeteorClient.LOG.info("[Ember Netherite] Seed corrected: {}", fixed);
            EmberNotificationsHud.push("Netherite finder", "Seed fixed from the debris areas: " + fixed, EmberNotificationsHud.GOOD);
            seed.set(String.valueOf(fixed));
            return;
        }
        if (pinState == PIN_SEARCHING && searchFailed) {
            pinState = PIN_NONE;
            MeteorClient.LOG.info("[Ember Netherite] No seed with these low 48 bits puts its debris in the listed sections");
            EmberNotificationsHud.push("Netherite finder", "This server's ores don't follow the seed", EmberNotificationsHud.WARN);
        }
        if (seedState != SEED_GOOD || pinState != PIN_UNKNOWN || ticks - pinCheckAt < 40) return;
        pinCheckAt = ticks;

        List<Snap> snaps = snapshot();
        int[] score = score(seedValue, snaps, largeIndex + indexShift, smallIndex + indexShift, Integer.MAX_VALUE);
        int[] other = score(seedValue ^ (0x5A5AL << 48), snaps, largeIndex + indexShift, smallIndex + indexShift, Integer.MAX_VALUE);
        String line = score[0] + " of " + (score[0] + score[1]) + " spots; a wrong seed gets " + other[0] + " of " + (other[0] + other[1]);
        if (!line.equals(lastScoreLine)) {
            lastScoreLine = line;
            MeteorClient.LOG.info("[Ember Netherite] Seed {} vs the listed sections: {} ({} chunks)", seedValue, line, snaps.size());
        }
        if (ticks - diagnosedAt > 6000) {
            diagnosedAt = ticks;
            diagnose();
        }
        int total = score[0] + score[1];
        if (total >= 40 && matches(score, 40)) {
            pinState = PIN_GOOD;
            pinPercent = Math.round(100f * score[0] / total);
            EmberNotificationsHud.push("Netherite finder", "Seed matches (" + pinPercent + "%): exact debris shown", EmberNotificationsHud.GOOD);
            return;
        }

        // DonutSMP leaves debris out of the lists altogether: there the seed is checked against hidden magma the server
        // reveals (the reveal scan uncovers hundreds a minute), since magma veins come from the same seeded decoration.
        boolean anyListed = false;
        for (Snap s : snaps) anyListed |= s.listed != 0;
        if (!anyListed) {
            checkByMagma();
            return;
        }
        if (total < 40) return;

        pinState = PIN_SEARCHING;
        searchFailed = false;
        long low = seedValue & 0xFFFF_FFFF_FFFFL;
        int baseLarge = largeIndex, baseSmall = smallIndex;
        worker.submit(() -> {
            long start = System.currentTimeMillis();
            for (int shift : new int[] {0, -1, 1, -2, 2, -3, 3}) {
                for (long hi = 0; hi < 65536; hi++) {
                    long candidate = low | (hi << 48);
                    if (matches(score(candidate, snaps, baseLarge + shift, baseSmall + shift, 2), 60)) {
                        MeteorClient.LOG.info("[Ember Netherite] Seed search: {} with index shift {} after {} ms", candidate, shift, System.currentTimeMillis() - start);
                        indexShift = shift;
                        foundSeed = candidate;
                        return;
                    }
                }
            }
            MeteorClient.LOG.info("[Ember Netherite] Seed search: nothing fits ({} ms)", System.currentTimeMillis() - start);
            searchFailed = true;
        });
    }

    private static final int MODE_LISTED = 0, MODE_MAGMA = 1;
    private static final String DONUT_SEED = "6608149111735331168";
    private int pinMode = MODE_LISTED;
    private static int magmaIndex = -1;
    /** Hidden magma the server revealed: real blocks, placed by the seeded decoration like debris. */
    private final List<BlockPos> revealedMagma = new ArrayList<>();
    private int magmaChecked;

    /**
     * DonutSMP's magma follows its world seed but its debris doesn't (measured 2026-10-19: 0 of 11 seed spots), so the seed
     * is no use for debris there and isn't checked.
     */
    private boolean onDonut() {
        var server = mc.getCurrentServerEntry();
        return server != null && server.address.toLowerCase(java.util.Locale.ROOT).contains("donutsmp");
    }

    private void checkByMagma() {
        // Only magma from veins (Y 27-36, reaching a few blocks out): lower magma comes from other features.
        revealedMagma.removeIf(p -> p.getY() < 22 || p.getY() > 41);
        int n = revealedMagma.size();
        if (magmaIndex < 0 || n < 60 || n - magmaChecked < 20) return;
        magmaChecked = n;
        int index = magmaIndex + indexShift;
        double me = insideVeins(seedValue, revealedMagma, index);
        double wrong = (insideVeins(seedValue ^ (0x5A5AL << 48), revealedMagma, index) + insideVeins(seedValue ^ (0x1234L << 48), revealedMagma, index)) / 2;
        MeteorClient.LOG.info("[Ember Netherite] Seed {} vs {} revealed magma blocks: {}% inside its veins, a wrong seed {}%", seedValue, n,
            Math.round(me * 100), Math.round(wrong * 100));
        if (me >= 0.8 && me >= wrong * 3) {
            pinState = PIN_GOOD;
            pinMode = MODE_MAGMA;
            pinPercent = (int) Math.round(me * 100);
            EmberNotificationsHud.push("Netherite finder", "Seed proven by " + n + " hidden magma blocks (" + pinPercent + "%): debris spots shown", EmberNotificationsHud.GOOD);
        } else if (n >= 150 && me < 0.5) {
            // The bedrock proved the low 48 bits; try the 65,536 top halves against the magma before giving up.
            pinState = PIN_SEARCHING;
            searchFailed = false;
            List<BlockPos> sample = List.copyOf(revealedMagma.subList(0, Math.min(200, n)));
            long low = seedValue & 0xFFFF_FFFF_FFFFL;
            worker.submit(() -> {
                long start = System.currentTimeMillis();
                List<BlockPos> quick = sample.subList(0, 30);
                for (long hi = 0; hi < 65536; hi++) {
                    long candidate = low | (hi << 48);
                    if (insideVeins(candidate, quick, index) >= 0.8 && insideVeins(candidate, sample, index) >= 0.8) {
                        MeteorClient.LOG.info("[Ember Netherite] Magma seed search: {} after {} ms", candidate, System.currentTimeMillis() - start);
                        foundSeed = candidate;
                        return;
                    }
                }
                MeteorClient.LOG.info("[Ember Netherite] Magma seed search: nothing fits ({} ms)", System.currentTimeMillis() - start);
                searchFailed = true;
            });
        }
    }

    /** Share of the magma blocks that lie inside the seed's magma veins (from their chunk or a neighbour's). */
    private static double insideVeins(long worldSeed, List<BlockPos> magma, int index) {
        Long2ObjectOpenHashMap<double[]> veins = new Long2ObjectOpenHashMap<>();
        int inside = 0;
        for (BlockPos pos : magma) {
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            search:
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int vx = cx + dx, vz = cz + dz;
                    double[] spheres = veins.computeIfAbsent(ChunkPos.toLong(vx, vz), k -> veins(worldSeed, vx, vz, index));
                    for (int i = 0; i < spheres.length; i += 4) {
                        double r = spheres[i + 3];
                        double ax = (pos.getX() + 0.5 - spheres[i]) / r, ay = (pos.getY() + 0.5 - spheres[i + 1]) / r, az = (pos.getZ() + 0.5 - spheres[i + 2]) / r;
                        if (ax * ax + ay * ay + az * az < 1) {
                            inside++;
                            break search;
                        }
                    }
                }
            }
        }
        return magma.isEmpty() ? 0 : (double) inside / magma.size();
    }

    /**
     * The spheres of one chunk's four magma veins, as OreFeature draws them: square, height 27-36, angle, the two end
     * heights, then one size per sphere along the vein.
     *
     * @return x, y, z, radius of each sphere
     */
    private static double[] veins(long worldSeed, int chunkX, int chunkZ, int index) {
        ChunkRandom random = new ChunkRandom(new Xoroshiro128PlusPlusRandom(0));
        long population = random.setPopulationSeed(worldSeed, chunkX << 4, chunkZ << 4);
        random.setDecoratorSeed(population, index, STEP);
        double[] out = new double[4 * 33 * 4];
        int n = 0;
        for (int copy = 0; copy < 4; copy++) {
            int x = random.nextInt(16) + (chunkX << 4);
            int z = random.nextInt(16) + (chunkZ << 4);
            int y = MathHelper.nextBetween(random, 27, 36);
            float f = random.nextFloat() * (float) Math.PI;
            float g = 33 / 8.0F;
            double sx = x + Math.sin(f) * g, ex = x - Math.sin(f) * g, sz = z + Math.cos(f) * g, ez = z - Math.cos(f) * g;
            double sy = y + random.nextInt(3) - 2, ey = y + random.nextInt(3) - 2;
            for (int k = 0; k < 33; k++) {
                float t = (float) k / 33;
                double size = random.nextDouble() * 33 / 16.0;
                out[n++] = MathHelper.lerp(t, sx, ex);
                out[n++] = MathHelper.lerp(t, sy, ey);
                out[n++] = MathHelper.lerp(t, sz, ez);
                out[n++] = ((MathHelper.sin((float) Math.PI * t) + 1.0F) * size + 1.0) / 2.0;
            }
        }
        return out;
    }

    private String lastScoreLine;
    private long diagnosedAt = -100_000;

    /**
     * What the server shows where the seed puts debris, against where a wrong seed puts it, and what the section
     * palettes hold: tells how this server's anti-xray hides debris.
     */
    private void diagnose() {
        ChunkPos here = mc.player.getChunkPos();
        java.util.Map<String, Integer> right = new java.util.TreeMap<>(), wrong = new java.util.TreeMap<>();
        List<String> samples = new ArrayList<>();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                int cx = here.x + dx, cz = here.z + dz;
                if (!(mc.world.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof WorldChunk)) continue;
                for (int pass = 0; pass < 2; pass++) {
                    long s = pass == 0 ? seedValue : seedValue ^ (0x5A5AL << 48);
                    int[] spots = predict(s, cx, cz);
                    for (int i = 0; i < spots.length; i += 3) {
                        BlockPos pos = new BlockPos(spots[i], spots[i + 1], spots[i + 2]);
                        if (!(mc.world.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) instanceof WorldChunk)) continue;
                        int air = 0;
                        for (Direction d : Direction.values()) if (mc.world.getBlockState(pos.offset(d)).isAir()) air++;
                        String key = name(mc.world.getBlockState(pos)) + (air > 0 ? "+air" + air : "");
                        (pass == 0 ? right : wrong).merge(key, 1, Integer::sum);
                        if (pass == 0 && samples.size() < 6) {
                            StringBuilder sb = new StringBuilder(pos.toShortString().replace(" ", "")).append('=').append(name(mc.world.getBlockState(pos))).append(" [");
                            for (Direction d : Direction.values()) sb.append(d.asString().charAt(0)).append(':').append(name(mc.world.getBlockState(pos.offset(d)))).append(' ');
                            samples.add(sb.append(']').toString());
                        }
                    }
                }
            }
        }
        MeteorClient.LOG.info("[Ember Netherite] Diagnose at {}: blocks where the seed puts debris {}", mc.player.getBlockPos().toShortString(), right);
        MeteorClient.LOG.info("[Ember Netherite] Diagnose: blocks where a wrong seed puts debris {}", wrong);
        for (String s : samples) MeteorClient.LOG.info("[Ember Netherite] Diagnose spot {}", s);

        // Palettes of the low sections in the chunk you're in and two neighbours.
        for (int[] c : new int[][] {{0, 0}, {1, 0}, {0, 1}}) {
            if (!(mc.world.getChunk(here.x + c[0], here.z + c[1], ChunkStatus.FULL, false) instanceof WorldChunk chunk)) continue;
            ChunkSection[] sections = chunk.getSectionArray();
            for (int i = 0; i < sections.length && i < 4; i++) {
                PalettedContainer<BlockState> blocks = sections[i].getBlockStateContainer();
                if (blocks.hasAny(s -> false)) {
                    MeteorClient.LOG.info("[Ember Netherite] Diagnose palette {},{} section {}: global", chunk.getPos().x, chunk.getPos().z, i);
                    continue;
                }
                List<String> names = new ArrayList<>();
                blocks.hasAny(s -> {
                    names.add(name(s));
                    return false;
                });
                MeteorClient.LOG.info("[Ember Netherite] Diagnose palette {},{} section {}: {}", chunk.getPos().x, chunk.getPos().z, i, names);
            }
        }
    }

    private static String name(BlockState state) {
        return net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).getPath();
    }

    /** The right seed puts every spot in a listed section; one miss in 50 allows for blocks players changed. */
    private static boolean matches(int[] score, int need) {
        int total = score[0] + score[1];
        return total >= need && score[1] <= Math.max(1, total / 50);
    }

    /** Loaded chunks around you, read into plain arrays the worker can use. */
    private record Snap(int cx, int cz, int listed, int readable, java.util.BitSet canHold) {
    }

    private static final byte OTHER = 0, NETHERRACK = 1, DEBRIS = 2, AIR = 3;

    private List<Snap> snapshot() {
        List<Snap> out = new ArrayList<>();
        ChunkPos here = mc.player.getChunkPos();
        byte[] type = new byte[16 * 16 * 128];
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                long key = ChunkPos.toLong(here.x + dx, here.z + dz);
                if (!readable.containsKey(key)) continue;
                if (!(mc.world.getChunk(here.x + dx, here.z + dz, ChunkStatus.FULL, false) instanceof WorldChunk chunk)) continue;

                // Straight from the sections: going through the world for every neighbour is far too slow here.
                ChunkSection[] sections = chunk.getSectionArray();
                int bottom = chunk.getBottomSectionCoord();
                for (int y = 0; y < 128; y++) {
                    int i = (y >> 4) - bottom;
                    ChunkSection section = i >= 0 && i < sections.length ? sections[i] : null;
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState state = section == null ? Blocks.AIR.getDefaultState() : section.getBlockState(x, y & 15, z);
                            type[(y << 8) | (x << 4) | z] = state.isOf(Blocks.NETHERRACK) ? NETHERRACK : state.isOf(Blocks.ANCIENT_DEBRIS) ? DEBRIS
                                : state.isAir() ? AIR : OTHER;
                        }
                    }
                }

                java.util.BitSet bits = new java.util.BitSet(16 * 16 * 128);
                for (int y = MIN_Y; y <= MAX_Y; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int i = (y << 8) | (x << 4) | z;
                            if (type[i] == DEBRIS || type[i] == NETHERRACK && !airBeside(type, x, y, z)) bits.set(i);
                        }
                    }
                }
                out.add(new Snap(chunk.getPos().x, chunk.getPos().z, listed.get(key), readable.get(key), bits));
            }
        }
        return out;
    }

    /** Air next to a cell; across the chunk edge counts as solid. */
    private static boolean airBeside(byte[] type, int x, int y, int z) {
        return type[((y - 1) << 8) | (x << 4) | z] == AIR || type[((y + 1) << 8) | (x << 4) | z] == AIR
            || x > 0 && type[(y << 8) | ((x - 1) << 4) | z] == AIR || x < 15 && type[(y << 8) | ((x + 1) << 4) | z] == AIR
            || z > 0 && type[(y << 8) | (x << 4) | (z - 1)] == AIR || z < 15 && type[(y << 8) | (x << 4) | (z + 1)] == AIR;
    }

    /**
     * Spots the seed puts debris on that could still hold it, and whether their section lists debris.
     *
     * @return {in a listed section, in a section that doesn't list it}
     */
    private static int[] score(long worldSeed, List<Snap> snaps, int large, int small, int maxMisses) {
        Long2ObjectOpenHashMap<Snap> byKey = new Long2ObjectOpenHashMap<>();
        for (Snap s : snaps) byKey.put(ChunkPos.toLong(s.cx, s.cz), s);
        int hits = 0, misses = 0;
        for (Snap s : snaps) {
            int[] spots = predict(worldSeed, s.cx, s.cz, large, small);
            for (int i = 0; i < spots.length; i += 3) {
                int x = spots[i], y = spots[i + 1], z = spots[i + 2];
                if (y < MIN_Y || y > MAX_Y) continue;
                Snap at = byKey.get(ChunkPos.toLong(x >> 4, z >> 4));
                int bit = 1 << (y >> 4);
                if (at == null || (at.readable & bit) == 0 || !at.canHold.get((y << 8) | ((x & 15) << 4) | (z & 15))) continue;
                if ((at.listed & bit) != 0) hits++;
                else if (++misses > maxMisses) return new int[] {hits, misses};
            }
        }
        return new int[] {hits, misses};
    }

    /**
     * The debris of one chunk, as the game generates it: ChunkGenerator.generateFeatures seeds a ChunkRandom per
     * chunk and feature, then the square, height range and scattered-ore steps draw from it in this order.
     *
     * @return x, y, z of every spot, before the terrain decides which ones actually took
     */
    static int[] predict(long worldSeed, int chunkX, int chunkZ) {
        return predict(worldSeed, chunkX, chunkZ, largeIndex + indexShift, smallIndex + indexShift);
    }

    static int[] predict(long worldSeed, int chunkX, int chunkZ, int large, int small) {
        ChunkRandom random = new ChunkRandom(new Xoroshiro128PlusPlusRandom(0));
        long population = random.setPopulationSeed(worldSeed, chunkX << 4, chunkZ << 4);
        int[] out = new int[(3 + 2) * 3];
        int n = 0;

        // ore_ancient_debris_large: 0-3 blocks, trapezoid height 8-24. ore_debris_small: 0-2 blocks, height 8-119.
        for (int f = 0; f < 2; f++) {
            boolean big = f == 0;
            random.setDecoratorSeed(population, big ? large : small, STEP);
            int x = random.nextInt(16) + (chunkX << 4);
            int z = random.nextInt(16) + (chunkZ << 4);
            int y = big ? 8 + MathHelper.nextBetween(random, 0, 8) + MathHelper.nextBetween(random, 0, 8) : MathHelper.nextBetween(random, 8, 119);

            int count = random.nextInt((big ? 3 : 2) + 1);
            for (int j = 0; j < count; j++) {
                int spread = Math.min(j, 7);
                out[n++] = x + Math.round((random.nextFloat() - random.nextFloat()) * spread);
                out[n++] = y + Math.round((random.nextFloat() - random.nextFloat()) * spread);
                out[n++] = z + Math.round((random.nextFloat() - random.nextFloat()) * spread);
            }
        }
        return java.util.Arrays.copyOf(out, n);
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
            magmaIndex = step.indexMapping().applyAsInt(placed.getOrThrow(OrePlacedFeatures.ORE_MAGMA).value());
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
        // The right seed matched 94-100% of the bedrock in testing, wrong ones 50-70%.
        double share = (double) bedrockMatch / bedrockTotal;
        int state = share >= 0.88 ? SEED_GOOD : share < 0.8 ? SEED_BAD : SEED_UNKNOWN;
        if (state != seedState && state != SEED_UNKNOWN) {
            String percent = Math.round(share * 1000) / 10.0 + "%";
            MeteorClient.LOG.info("[Ember Netherite] Seed {} vs bedrock: {} of {} blocks match ({})", seedValue, bedrockMatch, bedrockTotal, percent);
            if (state == SEED_BAD && !seed.get().trim().equals(DONUT_SEED)) EmberNotificationsHud.push("Netherite finder", "Wrong seed (" + percent + " of the bedrock matches): showing areas only", EmberNotificationsHud.WARN);
        }
        seedState = state;
    }

    private void count(boolean want, boolean is) {
        bedrockTotal++;
        if (want == is) bedrockMatch++;
    }
}
