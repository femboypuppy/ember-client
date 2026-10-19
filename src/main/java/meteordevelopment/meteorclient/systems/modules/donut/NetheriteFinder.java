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
        .defaultValue(8)
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

    private final Setting<String> seed = sgGeneral.add(new StringSetting.Builder()
        .name("seed")
        .description("Optional. The world seed: if the areas agree with it, every area shows its exact debris blocks. A wrong seed is ignored.")
        .defaultValue("")
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
            if (area != null) areas.put(ChunkSectionPos.asLong(cx, sy, cz), area);
        }
        listed.put(key, hit);
        readable.put(key, read);

        if (statLogged < 4 && hit != 0) {
            statLogged++;
            MeteorClient.LOG.info("[Ember Netherite] Chunk {}, {}: debris listed in sections {} ({} of {} sections so far)", cx, cz,
                Integer.toBinaryString(hit), statListed, statSections);
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
        if (event.newState.isOf(Blocks.ANCIENT_DEBRIS)) {
            if (found.size() < 5000) found.add(event.pos.toImmutable());
        } else if (event.oldState.isOf(Blocks.ANCIENT_DEBRIS) || found.contains(event.pos)) {
            found.remove(event.pos);
            // The server's list only updates when the chunk is sent again, so this area may be empty now.
            mined.add(ChunkSectionPos.asLong(event.pos.getX() >> 4, event.pos.getY() >> 4, event.pos.getZ() >> 4));
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

        if (seedSet && indexesReady()) {
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
                        if (!found.contains(pos) && isListed(pos) && canHold(pos)) pinnedSet.add(pos);
                    }
                }
            }
            if (predicted.size() > 20_000) predicted.clear();
        }
        List<BlockPos> pinned = new ArrayList<>(pinnedSet);
        pinned.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(me)));

        shownAreas = near;
        shownFound = foundNear.size() > MAX_BLOCKS ? foundNear.subList(0, MAX_BLOCKS) : foundNear;
        shownPinned = pinned.size() > MAX_BLOCKS ? pinned.subList(0, MAX_BLOCKS) : pinned;
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
            reason = real ? "Real block: the server showed it" : "Exact spot from the seed (" + pinPercent + "% match)";
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
        String text = shownAreas.size() + (shownAreas.size() == 1 ? " area" : " areas");
        int exact = shownFound.size() + shownPinned.size();
        if (exact > 0) text += " · " + exact + " exact";
        if (pinState == PIN_SEARCHING) text += " · checking seed";
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
        shownPinned = List.of();
        bedrockChecked.clear();
        bedrockMatch = bedrockTotal = 0;
        seedState = SEED_UNKNOWN;
        if (pinState != PIN_SEARCHING) pinState = PIN_UNKNOWN;

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
            EmberNotificationsHud.push("Netherite finder", "This server's debris doesn't follow the seed: showing areas", EmberNotificationsHud.WARN);
        }
        if (seedState != SEED_GOOD || pinState != PIN_UNKNOWN || ticks - pinCheckAt < 40) return;
        pinCheckAt = ticks;

        List<Snap> snaps = snapshot();
        int[] score = score(seedValue, snaps, largeIndex + indexShift, smallIndex + indexShift, Integer.MAX_VALUE);
        int[] other = score(seedValue ^ (0x5A5AL << 48), snaps, largeIndex + indexShift, smallIndex + indexShift, Integer.MAX_VALUE);
        MeteorClient.LOG.info("[Ember Netherite] Seed {} vs the listed sections: {} of {} spots ({} chunks); a wrong seed gets {} of {}",
            seedValue, score[0], score[0] + score[1], snaps.size(), other[0], other[0] + other[1]);
        int total = score[0] + score[1];
        if (total < 40) return;
        if (matches(score, 40)) {
            pinState = PIN_GOOD;
            pinPercent = Math.round(100f * score[0] / total);
            EmberNotificationsHud.push("Netherite finder", "Seed matches (" + pinPercent + "%): exact debris shown", EmberNotificationsHud.GOOD);
            return;
        }

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
            if (state == SEED_BAD) EmberNotificationsHud.push("Netherite finder", "Wrong seed (" + percent + " of the bedrock matches): showing areas only", EmberNotificationsHud.WARN);
        }
        seedState = state;
    }

    private void count(boolean want, boolean is) {
        bedrockTotal++;
        if (want == is) bedrockMatch++;
    }
}
