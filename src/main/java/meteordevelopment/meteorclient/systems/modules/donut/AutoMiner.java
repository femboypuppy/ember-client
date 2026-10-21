package meteordevelopment.meteorclient.systems.modules.donut;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.ClientPlayerInteractionManagerAccessor;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Falling;
import net.minecraft.block.InfestedBlock;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * Tunnels for you like a player would, for base hunting under the deepslate.
 *
 * Digs a 1x2 tunnel one way, planned ten blocks ahead around lava, water, caves, falling gravel and sculk sensors.
 * It all goes through the game's own input: the crosshair is put on the block and the attack key held, so the server
 * gets the same packets, swings and break times as from a hand on a mouse. The aim moves the way a mouse does (eased
 * flicks in sensitivity-sized steps, an overshoot now and then, a little tremor). Walking on, switching tools and
 * starting a block wait a human reaction time while the next block is already being dug, so the waits cost little.
 *
 * It turns itself off when you get hurt, when another player comes close, at blocks players place, and whenever
 * there is no safe way on. Deepslate that arrives hidden (DonutSMP) gets a relog through Auto Relog first.
 */
public class AutoMiner extends Module {
    public enum Heading {
        Facing,
        North,
        East,
        South,
        West
    }

    public enum StopOn {
        AnyDamage("Any damage"),
        PlayerHits("Player hits");

        private final String title;

        StopOn(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgHuman = settings.createGroup("Humanize");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<Heading> heading = sgGeneral.add(new EnumSetting.Builder<Heading>()
        .name("direction")
        .description("Which way to tunnel. Facing takes the direction you look in when you turn it on.")
        .defaultValue(Heading.Facing)
        .build()
    );

    private final Setting<Boolean> useTargetY = sgGeneral.add(new BoolSetting.Builder()
        .name("go-to-y")
        .description("Dig a staircase down (or up) to target-y first, then tunnel along it. Off keeps the height you start at.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> targetY = sgGeneral.add(new IntSetting.Builder()
        .name("target-y")
        .description("Height to tunnel at.")
        .defaultValue(-50)
        .range(-62, 300)
        .sliderRange(-62, 64)
        .visible(useTargetY::get)
        .build()
    );

    private final Setting<Integer> maxDistance = sgGeneral.add(new IntSetting.Builder()
        .name("max-distance")
        .description("Stop after tunnelling this many blocks. 0 for no limit.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 2000)
        .build()
    );

    private final Setting<Boolean> avoidCaves = sgGeneral.add(new BoolSetting.Builder()
        .name("avoid-caves")
        .description("Go around caves instead of breaking into them. Small air pockets are fine.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> stopAtBuilt = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-at-built-blocks")
        .description("Stop when the tunnel reaches blocks players place (planks, glass, chests, cobblestone...): you have likely hit a base.")
        .defaultValue(true)
        .build()
    );

    // Safety

    private final Setting<StopOn> stopOn = sgSafety.add(new EnumSetting.Builder<StopOn>()
        .name("stop-on")
        .description("Turn off when you get hurt by anything, or only when a player hits you. Burning, drowning and low health always stop it.")
        .defaultValue(StopOn.AnyDamage)
        .build()
    );

    private final Setting<Boolean> stopNearPlayers = sgSafety.add(new BoolSetting.Builder()
        .name("stop-near-players")
        .description("Turn off when another player, not a friend, comes close.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> playerRange = sgSafety.add(new IntSetting.Builder()
        .name("player-range")
        .description("How close another player may come.")
        .defaultValue(24)
        .range(4, 128)
        .sliderRange(8, 64)
        .visible(stopNearPlayers::get)
        .build()
    );

    private final Setting<Integer> keepDurability = sgSafety.add(new IntSetting.Builder()
        .name("keep-durability")
        .description("Never use a tool with this many uses left or fewer; stop when no pickaxe has more.")
        .defaultValue(15)
        .range(0, 300)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Boolean> relogHidden = sgSafety.add(new BoolSetting.Builder()
        .name("relog-for-hidden")
        .description("When the deepslate ahead arrived hidden (DonutSMP), relog through Auto Relog to load it. Needs Auto Relog on, otherwise it stops.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> alertSound = sgSafety.add(new BoolSetting.Builder()
        .name("alert-sound")
        .description("Play a sound when it stops by itself.")
        .defaultValue(true)
        .build()
    );

    // Humanize

    private final Setting<Double> aimSpeed = sgHuman.add(new DoubleSetting.Builder()
        .name("aim-speed")
        .description("How quickly the aim moves between blocks. 1 is an average player.")
        .defaultValue(1.0)
        .range(0.4, 2.5)
        .sliderRange(0.4, 2.5)
        .build()
    );

    private final Setting<Integer> reaction = sgHuman.add(new IntSetting.Builder()
        .name("reaction-ms")
        .description("Typical reaction time before walking on, switching tools or hitting the next block. Each one varies around it.")
        .defaultValue(140)
        .range(40, 600)
        .sliderRange(60, 400)
        .build()
    );

    private final Setting<Boolean> hesitate = sgHuman.add(new BoolSetting.Builder()
        .name("hesitate")
        .description("Now and then stop for a moment, like a player glancing at chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> glances = sgHuman.add(new BoolSetting.Builder()
        .name("look-around")
        .description("Now and then look around for a moment between blocks.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> renderPath = sgRender.add(new BoolSetting.Builder()
        .name("render-path")
        .description("Show the planned tunnel and the block being dug.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> pathColor = sgRender.add(new ColorSetting.Builder()
        .name("path-color")
        .description("Color of the planned tunnel.")
        .defaultValue(new SettingColor(255, 106, 43, 170))
        .visible(renderPath::get)
        .build()
    );

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final Direction[] SIDES = Direction.values();

    /** How far ahead each plan reaches, in blocks along the tunnel. */
    private static final int HORIZON = 10;
    private static final int MAX_NODES = 5000;
    /** An open space at least this big next to the tunnel is a cave, not a pocket. */
    private static final int CAVE_SIZE = 10;
    /** Anything slower to dig than this (obsidian, or stone by hand) isn't worth it. */
    private static final int MAX_BREAK_TICKS = 120;
    /** Plan costs, in ticks: walking a block, aiming plus the game's pause after each block, and detours. */
    private static final double WALK_COST = 5, DIG_OVERHEAD = 7, TURN_COST = 8, BACK_COST = 25, OFF_LINE_COST = 3;
    private static final int REPLAN_TICKS = 40;
    /** Sculk sensors hear digging and footsteps this far, calibrated ones further. */
    private static final int SENSOR_RANGE = 9, CALIBRATED_RANGE = 17;
    private static final long REJOIN_WINDOW_MS = 90_000, USER_PAUSE_MS = 1500, CHUNK_WAIT_MS = 15_000;
    private static final long STUCK_REPLAN_MS = 8_000, STUCK_STOP_MS = 25_000;
    private static final int JOIN_GRACE_TICKS = 100, MAX_HIDDEN_RELOGS = 2;
    private static final float LOW_HEALTH = 8;
    /** Close enough to the middle of a block to count as there. */
    private static final double ARRIVE = 0.2;
    /** Size of the hand tremor while holding on a point, in degrees. */
    private static final double TREMOR = 0.05;
    private static final int NO_DAMAGE = Integer.MIN_VALUE, UNLOADED = Integer.MIN_VALUE;

    /** What the ground down here is made of. Anything else in the way was most likely put there by someone. */
    private static final Set<Block> NATURAL = new ReferenceOpenHashSet<>(List.of(
        Blocks.GRAVEL, Blocks.CLAY, Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST,
        Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD,
        Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE, Blocks.MOSS_BLOCK, Blocks.MOSS_CARPET, Blocks.GLOW_LICHEN,
        Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.SPORE_BLOSSOM, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM,
        Blocks.SMALL_DRIPLEAF, Blocks.HANGING_ROOTS, Blocks.ROOTED_DIRT, Blocks.SCULK, Blocks.SCULK_VEIN,
        Blocks.SCULK_CATALYST, Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER, Blocks.MAGMA_BLOCK, Blocks.OBSIDIAN,
        Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK, Blocks.BONE_BLOCK, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
        Blocks.POWDER_SNOW, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.GLOWSTONE, Blocks.NETHER_WART_BLOCK,
        Blocks.WARPED_WART_BLOCK, Blocks.SHROOMLIGHT, Blocks.ANCIENT_DEBRIS, Blocks.NETHER_GOLD_ORE,
        Blocks.NETHER_QUARTZ_ORE, Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
        Blocks.DEAD_BUSH, Blocks.VINE, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM
    ));

    private static final Set<Block> BAD_FLOORS = new ReferenceOpenHashSet<>(List.of(
        Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH,
        Blocks.WITHER_ROSE, Blocks.POWDER_SNOW, Blocks.POINTED_DRIPSTONE, Blocks.FIRE, Blocks.SOUL_FIRE,
        Blocks.SCULK_SENSOR, Blocks.CALIBRATED_SCULK_SENSOR, Blocks.SCULK_SHRIEKER
    ));

    private enum Move {
        Walk,
        Down,
        Up
    }

    /** One move: where your feet end up, which way (index into HORIZONTAL), and what to dig for it, in digging order. */
    private record Step(BlockPos feet, int dir, Move move, BlockPos[] dig) {
        boolean digs(BlockPos pos) {
            for (BlockPos b : dig) if (b.equals(pos)) return true;
            return false;
        }
    }

    private static final class Node {
        final BlockPos pos;
        final int dir, depth;
        final Node parent;
        final Step step;
        final double g, f;

        Node(BlockPos pos, int dir, Node parent, Step step, double g, double f) {
            this.pos = pos;
            this.dir = dir;
            this.parent = parent;
            this.step = step;
            this.g = g;
            this.f = f;
            this.depth = parent == null ? 0 : parent.depth + 1;
        }
    }

    private record Sensor(BlockPos pos, int range) {
    }

    private final Random random = new Random();
    /** Where you have been and what you dug this run; never a cave to avoid. */
    private final LongOpenHashSet trail = new LongOpenHashSet();
    private final List<Step> path = new ArrayList<>();
    private final Set<KeyBinding> held = Collections.newSetFromMap(new IdentityHashMap<>());

    private Direction dir;
    private BlockPos origin, anchor, lastSpot;
    private RegistryKey<World> dimension;
    private FindsStorage.Context context = new FindsStorage.Context("", "");
    private Plan lastPlan;
    private int lastDir, lineY, maxDeviation, progress, hiddenRelogs, relogProgress, replanTimer, joinGrace, saveTimer, switchSlot = -1;
    private long relogWaitNotice;
    private boolean replan, walking, holdStyle, waitingRejoin;
    private long walkAt, settledAt, switchAt, hesitateUntil, nextGlance, glanceUntil, userAt, lastProgressAt, rejoinDeadline, chunkWaitSince;
    private float lastHealth;
    private volatile int damagedBy = NO_DAMAGE;

    // Aim, moved every frame.
    private BlockPos miningTarget, lookAheadFor;
    private Vec3d aimPoint, lookAhead, glancePoint, segAim;
    /** Aiming at a face right above or below you, see {@link #pickAimPoint}. */
    private boolean aimOverhead;
    private Box aimBox;
    private double aimFaceY, aimReach;
    private boolean segActive;
    private double segFromYaw, segFromPitch, segOverYaw, segOverPitch, segTime, segDuration, residualX, residualY;
    private float expectYaw = Float.NaN, expectPitch;
    private long lastFrame;
    /** Tremor frequencies (rad/s) and phases, two per axis. */
    private final double[] tremor = new double[8];

    public AutoMiner() {
        super(Categories.Donut, "auto-miner", "Digs a tunnel for you like a player would, around lava, water and caves, to find bases under the deepslate. Stops when you get hurt.");
        // Rides through Auto Relog's relogs. It is never saved as on (see toTag), so a restart doesn't start it.
        runInMainMenu = true;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = super.toTag();
        // Coming back on at startup would start digging the moment you join a server.
        if (tag != null) tag.putBoolean("active", false);
        return tag;
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            error("Join a world first.");
            toggle();
            return;
        }
        if (usablePickaxe() < 0) {
            error("Put a pickaxe in your hotbar first.");
            toggle();
            return;
        }

        dir = switch (heading.get()) {
            case Facing -> mc.player.getHorizontalFacing();
            case North -> Direction.NORTH;
            case East -> Direction.EAST;
            case South -> Direction.SOUTH;
            case West -> Direction.WEST;
        };
        lastDir = indexOf(dir);
        origin = anchor = lastSpot = mc.player.getBlockPos();
        lineY = useTargetY.get() ? targetY.get() : origin.getY();
        maxDeviation = Math.max(4, Math.abs(origin.getY() - lineY));
        dimension = mc.world.getRegistryKey();
        context = FindsStorage.context();

        trail.clear();
        trail.add(origin.asLong());
        trail.add(origin.up().asLong());
        path.clear();
        lastPlan = null;
        progress = hiddenRelogs = relogProgress = replanTimer = joinGrace = saveTimer = 0;
        switchSlot = -1;
        replan = true;
        walking = holdStyle = waitingRejoin = false;

        long now = System.currentTimeMillis();
        walkAt = settledAt = switchAt = hesitateUntil = glanceUntil = userAt = chunkWaitSince = 0;
        lastProgressAt = now;
        nextGlance = now + 20_000 + random.nextInt(40_000);
        damagedBy = NO_DAMAGE;
        lastHealth = health();

        miningTarget = lookAheadFor = null;
        aimPoint = lookAhead = glancePoint = segAim = null;
        segActive = false;
        expectYaw = Float.NaN;
        residualX = residualY = 0;
        for (int i = 0; i < 4; i++) {
            tremor[i] = (0.5 + random.nextDouble()) * Math.PI * 2;
            tremor[i + 4] = random.nextDouble() * Math.PI * 2;
        }

        EmberNotificationsHud.push("Auto miner", "Tunnelling " + dir.asString() + (useTargetY.get() ? " at Y " + lineY : ""), EmberNotificationsHud.GOOD);
    }

    @Override
    public void onDeactivate() {
        releaseAll();
        OwnDigging.save();
        path.clear();
        waitingRejoin = false;
        miningTarget = null;
        aimPoint = lookAhead = glancePoint = segAim = null;
    }

    // Relogs

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        releaseAll();
        OwnDigging.save();
        if (Modules.get().get(AutoRelog.class).relogging()) {
            waitingRejoin = true;
            rejoinDeadline = System.currentTimeMillis() + REJOIN_WINDOW_MS;
        } else {
            info("Turned off: you left the server.");
            toggle();
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!waitingRejoin) return;
        waitingRejoin = false;
        // The player sits at a placeholder spot and the chunks are still coming, so wait a moment.
        joinGrace = JOIN_GRACE_TICKS;
        path.clear();
        context = FindsStorage.context();
    }

    private void afterRejoin() {
        if (mc.world.getRegistryKey() != dimension || mc.player.getBlockPos().getManhattanDistance(lastSpot) > 24) {
            stop("you came back somewhere else", EmberNotificationsHud.WARN);
            return;
        }
        lastHealth = health();
        damagedBy = NO_DAMAGE;
        lastProgressAt = System.currentTimeMillis();
        replan = true;
    }

    /** Network thread: only notes who hurt you, for the next tick to act on. */
    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof EntityDamageS2CPacket packet && mc.player != null && packet.entityId() == mc.player.getId()) {
            // -1 when nothing caused it: a fall, lava, hunger.
            damagedBy = packet.sourceCauseId() >= 0 ? packet.sourceCauseId() : packet.sourceDirectId();
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.player == null || path.isEmpty() || waitingRejoin) return;
        BlockPos pos = event.pos.toImmutable();

        for (int i = 0; i < Math.min(4, path.size()); i++) {
            if (!path.get(i).digs(pos)) continue;
            if (event.newState.isAir()) {
                trail.add(pos.asLong());
                OwnDigging.add(context, pos);
                lastProgressAt = System.currentTimeMillis();
            } else {
                // Filled back in: gravel, water, or the server undoing the break.
                replan = true;
            }
            return;
        }

        // Lava flowing in, gravel dropping, someone building: look at the way ahead again.
        for (int i = 0; i < Math.min(4, path.size()); i++) {
            for (BlockPos b : path.get(i).dig()) {
                if (Math.abs(b.getX() - pos.getX()) <= 1 && Math.abs(b.getY() - pos.getY()) <= 1 && Math.abs(b.getZ() - pos.getZ()) <= 1) {
                    replan = true;
                    return;
                }
            }
        }
    }

    // Each tick

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        long now = System.currentTimeMillis();
        if (waitingRejoin) {
            if (now > rejoinDeadline) {
                info("Turned off: didn't get back on the server.");
                toggle();
            }
            return;
        }
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (joinGrace > 0) {
            releaseAll();
            if (--joinGrace == 0) afterRejoin();
            return;
        }

        BlockPos cell = mc.player.getBlockPos();
        lastSpot = cell;
        trail.add(cell.asLong());
        trail.add(cell.up().asLong());
        if (++saveTimer >= 600) {
            saveTimer = 0;
            OwnDigging.save();
        }

        String danger = danger();
        if (danger != null) {
            stop(danger, EmberNotificationsHud.WARN);
            return;
        }
        if (paused(now)) {
            releaseAll();
            // Time spent paused isn't time stuck.
            lastProgressAt = now;
            return;
        }

        // Steps you have reached.
        while (!path.isEmpty() && cell.equals(path.get(0).feet()) && mc.player.isOnGround()) {
            Step done = path.removeFirst();
            anchor = done.feet();
            lastDir = done.dir();
            progress = Math.max(progress, forward(done.feet()));
            lastProgressAt = now;
            // Far enough past the last relog that it clearly worked.
            if (progress >= relogProgress + 16) hiddenRelogs = 0;
            if (hesitate.get() && random.nextDouble() < 0.02) hesitateUntil = now + 300 + random.nextInt(700);
        }

        if (maxDistance.get() > 0 && progress >= maxDistance.get()) {
            stop("tunnelled " + progress + " blocks", EmberNotificationsHud.GOOD);
            return;
        }
        if (now - lastProgressAt > STUCK_STOP_MS) {
            stop("stuck: something keeps blocking the way", EmberNotificationsHud.WARN);
            return;
        }
        if (now - lastProgressAt > STUCK_REPLAN_MS && replanTimer > REPLAN_TICKS / 2) replan = true;

        if (mc.player.isOnGround() && needsPlan(cell) && !makePlan(cell, now)) return;
        if (path.isEmpty()) {
            releaseAll();
            return;
        }

        Step first = path.getFirst();

        // The block to dig: the first one left on the way, in order.
        BlockPos target = null;
        find:
        for (int i = 0; i < Math.min(3, path.size()); i++) {
            for (BlockPos b : path.get(i).dig()) {
                if (!mc.world.getBlockState(b).isAir()) {
                    target = b;
                    break find;
                }
            }
        }

        if (target == null ? miningTarget != null : !target.equals(miningTarget)) {
            if (miningTarget != null && hesitate.get() && random.nextDouble() < 0.035) hesitateUntil = now + 350 + random.nextInt(800);
            miningTarget = target;
            aimPoint = target == null ? null : pickAimPoint(target);
            settledAt = 0;
        } else if (target != null && (aimPoint == null || !aimOverhead && mc.player.age % 2 == 0 && underOrOver(target)
            || mc.player.age % 5 == 0 && !sees(mc.player.getEyePos(), miningAim(mc.player.getEyePos()), target))) {
            // Picked before you stood under (or over) it: aim again so the view doesn't swing round.
            aimPoint = pickAimPoint(target);
        }

        boolean toolReady = target == null || selectTool(mc.world.getBlockState(target), now);

        if (glancePoint != null && now >= glanceUntil) glancePoint = null;
        if (glances.get() && glancePoint == null && now >= nextGlance && !mc.options.attackKey.isPressed()) {
            glancePoint = glanceSpot();
            glanceUntil = now + 350 + random.nextInt(450);
            nextGlance = now + 25_000 + random.nextInt(45_000);
        }

        // Hold the attack key once the crosshair has settled on the block, after a click's worth of reaction.
        boolean onIt = target != null && aimPoint != null && glancePoint == null && aimSettled() && crosshairOn(target);
        // Between two blocks a player keeps the button down while moving to the next one. Nothing gets dug during
        // the game's pause after a break, so holding on through it is safe, and the pause passes during the move.
        int cooldown = ((ClientPlayerInteractionManagerAccessor) mc.interactionManager).meteor$getBlockBreakingCooldown();
        boolean carryOn = cooldown > 0 && held.contains(mc.options.attackKey) && target != null && glancePoint == null
            && mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK;
        if (onIt && toolReady) {
            if (settledAt == 0) settledAt = carryOn ? now : now + reactionMs(0.4);
            // After a screen closes the game ignores the key until it was let go once.
            press(mc.options.attackKey, (now >= settledAt || carryOn) && mc.attackCooldown < 100);
        } else {
            if (!onIt) settledAt = 0;
            press(mc.options.attackKey, carryOn && toolReady);
        }

        // Nothing to dig: look down the tunnel the way you walk.
        if (target == null) {
            Step look = path.get(Math.min(1, path.size() - 1));
            if (!look.feet().equals(lookAheadFor)) {
                lookAheadFor = look.feet();
                Direction d = HORIZONTAL[look.dir()];
                lookAhead = Vec3d.ofBottomCenter(look.feet()).add(d.getOffsetX() * 2.0 + random.nextGaussian() * 0.15,
                    1.25 + random.nextGaussian() * 0.12, d.getOffsetZ() * 2.0 + random.nextGaussian() * 0.15);
            }
        } else {
            lookAheadFor = null;
        }

        walk(first, now);
    }

    private boolean needsPlan(BlockPos cell) {
        if (replan || path.isEmpty() || ++replanTimer >= REPLAN_TICKS || path.size() < HORIZON / 2) return true;
        // Pushed, fallen or walked past the plan: start again from here.
        Step first = path.getFirst();
        return !cell.equals(anchor) && !cell.equals(first.feet()) && !first.digs(cell);
    }

    /** @return false when this tick is over: stopped, relogging or waiting for chunks */
    private boolean makePlan(BlockPos cell, long now) {
        replanTimer = 0;
        replan = false;
        List<Step> next = plan(cell);
        Plan plan = lastPlan;

        if (stopAtBuilt.get() && plan.built != null && plan.builtDistance <= 3.5) {
            stop("found " + plan.builtState.getBlock().getName().getString() + " at " + plan.built.toShortString()
                + ": someone built here", EmberNotificationsHud.GOOD);
            return false;
        }

        int gain = next == null || next.isEmpty() ? 0 : forward(next.getLast().feet()) - forward(cell);
        if (plan.hitHidden && gain < HORIZON / 2) {
            hiddenAhead();
            return false;
        }

        if (next == null || next.isEmpty()) {
            if (plan.hitUnloaded) {
                if (chunkWaitSince == 0) chunkWaitSince = now;
                if (now - chunkWaitSince > CHUNK_WAIT_MS) stop("the chunks ahead didn't load", EmberNotificationsHud.WARN);
                else releaseAll();
                return false;
            }
            stop("no safe way on (" + plan.blockedBy() + ")", EmberNotificationsHud.WARN);
            return false;
        }

        chunkWaitSince = 0;
        path.clear();
        path.addAll(next);
        anchor = cell;
        return true;
    }

    /** DonutSMP sent the deepslate ahead as plain fill, so nothing down there can be checked. */
    private void hiddenAhead() {
        releaseAll();
        boolean below = mc.player.getY() < 0;
        if (!below) {
            stop("the deepslate ahead is hidden: dig below Y 0 and relog, then start again", EmberNotificationsHud.WARN);
            return;
        }
        if (hiddenRelogs >= MAX_HIDDEN_RELOGS) {
            stop("the deepslate ahead stayed hidden after relogging", EmberNotificationsHud.WARN);
            return;
        }
        AutoRelog autoRelog = Modules.get().get(AutoRelog.class);
        long wait = autoRelog.isActive() ? autoRelog.joinWait() : 0;
        if (relogHidden.get() && wait > 0) {
            // Rejoining this soon after the last join gets the deepslate hidden again: wait here, then relog.
            // Time spent waiting isn't time stuck.
            lastProgressAt = System.currentTimeMillis();
            if (System.currentTimeMillis() - relogWaitNotice > 60_000) {
                relogWaitNotice = System.currentTimeMillis();
                EmberNotificationsHud.push("Auto miner", "The deepslate ahead is hidden: relogging in " + (wait / 1000) + "s", EmberNotificationsHud.WARN);
            }
            return;
        }
        if (relogHidden.get() && autoRelog.relogNow("Relogging to load the deepslate ahead")) {
            hiddenRelogs++;
            relogProgress = progress;
            EmberNotificationsHud.push("Auto miner", "The deepslate ahead is hidden: relogging to load it", EmberNotificationsHud.WARN);
            return;
        }
        stop("the deepslate ahead is hidden: turn on Auto Relog (out of combat) or relog yourself", EmberNotificationsHud.WARN);
    }

    // Safety

    /** @return why the miner must stop now, or null */
    private String danger() {
        int from = damagedBy;
        if (from != NO_DAMAGE) {
            damagedBy = NO_DAMAGE;
            Entity source = from >= 0 ? mc.world.getEntityById(from) : null;
            if (source instanceof PlayerEntity player && player != mc.player) return player.getName().getString() + " hit you";
            if (stopOn.get() == StopOn.AnyDamage) return source != null ? source.getName().getString() + " hurt you" : "you took damage";
        }

        float health = health();
        boolean hurt = health < lastHealth - 0.01f;
        lastHealth = health;
        if (hurt && stopOn.get() == StopOn.AnyDamage) return "you took damage";
        if (mc.player.getHealth() <= LOW_HEALTH) return "your health is low";
        if (mc.player.isInLava() || mc.player.isOnFire()) return "you're burning";
        if (mc.player.isSubmergedInWater()) return "you're under water";

        if (stopNearPlayers.get()) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player || Friends.get().isFriend(player)) continue;
                float distance = player.distanceTo(mc.player);
                if (distance <= playerRange.get()) return player.getName().getString() + " is " + Math.round(distance) + " blocks away";
            }
        }

        if (!Modules.get().isActive(AutoEat.class) && mc.player.getHungerManager().getFoodLevel() <= 6) return "you're hungry (Auto Eat keeps you fed)";
        if (usablePickaxe() < 0) return "no pickaxe with uses left in your hotbar";
        if (mc.world.getRegistryKey() != dimension) return "you changed dimension";
        return null;
    }

    /** Hands off while you use the keyboard, mouse or a screen, while Auto Eat eats, and during a hesitation. */
    private boolean paused(long now) {
        if (mc.currentScreen != null || Modules.get().isActive(Freecam.class)) return true;
        // Auto Relog's Home or Pearl method is taking you away and back: never dig wherever /rtp put you.
        if (Modules.get().get(AutoRelog.class).busy()) return true;
        AutoEat autoEat = Modules.get().get(AutoEat.class);
        if (autoEat.isActive() && autoEat.eating) return true;
        if (userInput()) userAt = now;
        return now - userAt < USER_PAUSE_MS || now < hesitateUntil;
    }

    private boolean userInput() {
        for (KeyBinding key : new KeyBinding[] {mc.options.forwardKey, mc.options.backKey, mc.options.leftKey, mc.options.rightKey,
            mc.options.jumpKey, mc.options.sneakKey, mc.options.attackKey, mc.options.useKey}) {
            if (Input.isPressed(key)) return true;
        }
        return false;
    }

    private float health() {
        return mc.player.getHealth() + mc.player.getAbsorptionAmount();
    }

    private void stop(String why, Color tone) {
        releaseAll();
        String text = "Stopped: " + why;
        MeteorClient.LOG.info("[Ember AutoMiner] {} after {} blocks at {}", text, progress, mc.player != null ? mc.player.getBlockPos().toShortString() : "?");
        EmberNotificationsHud.push("Auto miner", text, tone);
        if (!EmberNotificationsHud.shouldInterceptChat()) info(text);
        if (alertSound.get()) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.4f, 1.0f));
        toggle();
    }

    // Tools

    private int usablePickaxe() {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isIn(ItemTags.PICKAXES) && !worn(stack)) return slot;
        }
        return -1;
    }

    private boolean worn(ItemStack stack) {
        return stack.isDamageable() && stack.getMaxDamage() - stack.getDamage() <= keepDurability.get();
    }

    /** Switches to the best tool for the block, like a number key, after a reaction. @return whether it is in hand */
    private boolean selectTool(BlockState state, long now) {
        int best = -1;
        double bestDelta = 0;
        for (int slot = 0; slot < 9; slot++) {
            if (worn(mc.player.getInventory().getStack(slot))) continue;
            double delta = BlockUtils.getBreakDelta(slot, state);
            if (delta > bestDelta) {
                bestDelta = delta;
                best = slot;
            }
        }

        int current = mc.player.getInventory().getSelectedSlot();
        // Players don't swap for a small gain, only for a much faster tool or away from a worn one.
        if (best < 0 || best == current || !worn(mc.player.getInventory().getStack(current))
            && bestDelta <= BlockUtils.getBreakDelta(current, state) * 1.3) {
            switchSlot = -1;
            return true;
        }

        if (switchSlot != best) {
            switchSlot = best;
            switchAt = now + reactionMs(0.9);
        } else if (now >= switchAt) {
            InvUtils.swap(best, false);
            switchSlot = -1;
            return true;
        }
        return false;
    }

    // Walking

    private void walk(Step first, long now) {
        int open = -1;
        for (int i = 0; i < path.size() && isOpen(path.get(i)); i++) open = i;

        Vec3d goal;
        boolean push = false, jump = false;
        if (open >= 0) {
            // To the end of the open straight run; a turn waits in the middle of its corner.
            int k = 0;
            while (k < open && path.get(k + 1).dir() == path.get(k).dir()) k++;
            goal = Vec3d.ofBottomCenter(path.get(k).feet());
            if (first.move() == Move.Up && mc.player.isOnGround()) {
                double dx = first.feet().getX() + 0.5 - mc.player.getX(), dz = first.feet().getZ() + 0.5 - mc.player.getZ();
                jump = dx * dx + dz * dz < 1.44;
            }
        } else {
            goal = Vec3d.ofBottomCenter(anchor);
            // Still digging the next block: players keep pressing on into a straight tunnel.
            push = holdStyle && first.move() == Move.Walk && HORIZONTAL[first.dir()] == mc.player.getHorizontalFacing();
        }

        double dx = goal.x - mc.player.getX(), dz = goal.z - mc.player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= ARRIVE && !push) {
            walking = false;
            walkAt = 0;
            move(false, false, false, false, jump);
            return;
        }

        if (!walking) {
            if (walkAt == 0) walkAt = now + reactionMs(1.0);
            if (now < walkAt) {
                move(false, false, false, false, false);
                return;
            }
            walking = true;
            walkAt = 0;
            holdStyle = random.nextFloat() < 0.6f;
        }

        // Keys for the way to go, as seen from where you look.
        double yaw = Math.toRadians(mc.player.getYaw());
        double ahead = distance < 1e-6 ? 0 : (dx * -Math.sin(yaw) + dz * Math.cos(yaw)) / distance;
        double left = distance < 1e-6 ? 0 : (dx * Math.cos(yaw) + dz * Math.sin(yaw)) / distance;
        boolean forward = ahead > 0.38 && ahead * distance > 0.1;
        boolean back = ahead < -0.38 && -ahead * distance > 0.1;
        boolean strafeLeft = left > 0.38 && left * distance > 0.12;
        boolean strafeRight = left < -0.38 && -left * distance > 0.12;
        if (push && distance <= ARRIVE) {
            forward = true;
            back = strafeLeft = strafeRight = false;
        }
        move(forward, back, strafeLeft, strafeRight, jump);
    }

    private boolean isOpen(Step step) {
        for (BlockPos b : step.dig()) if (!mc.world.getBlockState(b).isAir()) return false;
        return true;
    }

    private void move(boolean forward, boolean back, boolean left, boolean right, boolean jump) {
        press(mc.options.forwardKey, forward);
        press(mc.options.backKey, back);
        press(mc.options.leftKey, left);
        press(mc.options.rightKey, right);
        press(mc.options.jumpKey, jump);
    }

    private void press(KeyBinding key, boolean on) {
        if (on) {
            key.setPressed(true);
            held.add(key);
        } else if (held.remove(key)) {
            // Back to what the keyboard says, so a key you hold yourself stays held.
            key.setPressed(Input.isPressed(key));
        }
    }

    private void releaseAll() {
        for (KeyBinding key : List.copyOf(held)) press(key, false);
        walking = false;
        walkAt = 0;
        settledAt = 0;
    }

    /** A reaction time: log-normal around the setting like real ones, so most are quick and a few are slow. */
    private long reactionMs(double scale) {
        double base = reaction.get() * scale;
        return (long) MathHelper.clamp(base * Math.exp(random.nextGaussian() * 0.32), base * 0.4, base * 3);
    }

    // Planning

    private int forward(BlockPos pos) {
        return (pos.getX() - origin.getX()) * dir.getOffsetX() + (pos.getZ() - origin.getZ()) * dir.getOffsetZ();
    }

    private int lateral(BlockPos pos) {
        return (pos.getX() - origin.getX()) * -dir.getOffsetZ() + (pos.getZ() - origin.getZ()) * dir.getOffsetX();
    }

    private static int indexOf(Direction direction) {
        for (int i = 0; i < HORIZONTAL.length; i++) if (HORIZONTAL[i] == direction) return i;
        return 0;
    }

    private static Step step(BlockPos from, int dirIndex, Move move) {
        BlockPos ahead = from.offset(HORIZONTAL[dirIndex]);
        return switch (move) {
            case Walk -> new Step(ahead, dirIndex, move, new BlockPos[] {ahead.up(), ahead});
            case Down -> new Step(ahead.down(), dirIndex, move, new BlockPos[] {ahead.up(), ahead, ahead.down()});
            // Room to jump above your head first, then the new head and feet blocks.
            case Up -> new Step(ahead.up(), dirIndex, move, new BlockPos[] {from.up(2), ahead.up(2), ahead.up()});
        };
    }

    /** A* over the cells your feet can be in, from here to {@link #HORIZON} blocks further along the tunnel. */
    private List<Step> plan(BlockPos start) {
        Plan plan = new Plan(start);
        lastPlan = plan;

        int goal = forward(start) + HORIZON;
        if (maxDistance.get() > 0) goal = Math.min(goal, maxDistance.get());
        int startForward = forward(start);
        if (goal <= startForward) return List.of();

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Long2ObjectOpenHashMap<double[]> bestG = new Long2ObjectOpenHashMap<>();
        Node root = new Node(start, lastDir, null, null, 0, heuristic(start, goal));
        open.add(root);

        Node best = null;
        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node node = open.poll();
            double[] known = bestG.get(node.pos.asLong());
            if (known != null && known[node.dir] < node.g) continue;
            expanded++;

            int along = forward(node.pos);
            if (node != root) {
                if (along >= goal) return build(node);
                if (best == null || along > forward(best.pos) || along == forward(best.pos) && node.g < best.g) best = node;
            }
            if (node.depth >= HORIZON * 3) continue;

            for (int d = 0; d < HORIZONTAL.length; d++) {
                for (Move move : Move.values()) {
                    Step step = step(node.pos, d, move);
                    double cost = stepCost(plan, node, step);
                    if (cost < 0) continue;

                    double g = node.g + cost;
                    double[] costs = bestG.computeIfAbsent(step.feet().asLong(), k -> {
                        double[] fresh = new double[HORIZONTAL.length];
                        Arrays.fill(fresh, Double.MAX_VALUE);
                        return fresh;
                    });
                    if (g >= costs[d]) continue;
                    costs[d] = g;
                    open.add(new Node(step.feet(), d, node, step, g, g + heuristic(step.feet(), goal)));
                }
            }
        }
        return best == null ? null : build(best);
    }

    private double heuristic(BlockPos pos, int goal) {
        // Weighted to head straight on: most steps cost about this much with a decent pickaxe.
        return Math.max(0, goal - forward(pos)) * 18 + (Math.abs(lateral(pos)) + Math.abs(pos.getY() - lineY)) * OFF_LINE_COST;
    }

    private static List<Step> build(Node node) {
        List<Step> steps = new ArrayList<>(node.depth);
        for (Node n = node; n.step != null; n = n.parent) steps.add(n.step);
        Collections.reverse(steps);
        return steps;
    }

    /** What a step costs in ticks, or -1 when it isn't safe. */
    private double stepCost(Plan plan, Node from, Step step) {
        World world = mc.world;
        BlockPos feet = step.feet();
        if (feet.getY() - 1 <= world.getBottomY() || feet.getY() + 2 > world.getTopYInclusive()) return -1;
        if (Math.abs(feet.getY() - lineY) > maxDeviation) return -1;

        BlockPos floor = feet.down();
        if (!plan.known(floor)) return -1;
        if (!standable(world, floor, world.getBlockState(floor))) return -1;

        double cost = WALK_COST + (step.move() == Move.Down ? 2 : step.move() == Move.Up ? 6 : 0);
        for (BlockPos b : step.dig()) {
            if (!plan.known(b)) return -1;
            if (plan.nearSensor(b)) {
                plan.sculk++;
                return -1;
            }

            BlockState state = world.getBlockState(b);
            if (!state.getFluidState().isEmpty()) {
                plan.fluid++;
                return -1;
            }
            if (!state.isAir()) {
                if (!isNatural(state)) {
                    plan.noteBuilt(b, state);
                    if (stopAtBuilt.get()) return -1;
                }
                int ticks = plan.breakTicks(state, b);
                if (ticks < 0) {
                    plan.hard++;
                    return -1;
                }
                cost += ticks + DIG_OVERHEAD;
            } else if (!isOurs(b, from, step)) {
                // Open already and not ours: a pocket is fine, a cave isn't.
                if (avoidCaves.get() && plan.pocket(b, from, step) >= CAVE_SIZE) {
                    plan.cave++;
                    return -1;
                }
                cost += 4;
            }

            // What opening this block lets in.
            for (Direction side : SIDES) {
                BlockPos n = b.offset(side);
                if (isOurs(n, from, step)) continue;
                if (!plan.known(n)) return -1;

                BlockState next = world.getBlockState(n);
                if (!next.getFluidState().isEmpty()) {
                    plan.fluid++;
                    return -1;
                }
                if (side == Direction.UP && next.getBlock() instanceof Falling) {
                    plan.falling++;
                    return -1;
                }
                if (next.isAir()) {
                    if (avoidCaves.get() && plan.pocket(n, from, step) >= CAVE_SIZE) {
                        plan.cave++;
                        return -1;
                    }
                    cost += 6;
                } else if (!isNatural(next)) {
                    plan.noteBuilt(n, next);
                }
            }
        }

        cost += OFF_LINE_COST * (Math.abs(lateral(feet)) + Math.abs(feet.getY() - lineY));
        if (step.dir() != from.dir) cost += TURN_COST;
        if (forward(feet) < forward(from.pos)) cost += BACK_COST;
        return cost;
    }

    /** Dug or stood in by you, now or by an earlier run, or part of the plan up to here. */
    private boolean isOurs(BlockPos pos, Node from, Step step) {
        if (step.digs(pos)) return true;
        long key = pos.asLong();
        if (trail.contains(key)) return true;
        Node n = from;
        for (int i = 0; i < 4 && n != null; i++, n = n.parent) {
            if (pos.equals(n.pos) || pos.equals(n.pos.up())) return true;
            if (n.step != null && n.step.digs(pos)) return true;
        }
        return OwnDigging.contains(context, pos.getX(), pos.getY(), pos.getZ());
    }

    private static boolean standable(World world, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() || BAD_FLOORS.contains(state.getBlock())) return false;
        return state.isSideSolidFullSquare(world, pos, Direction.UP);
    }

    private static boolean isNatural(BlockState state) {
        if (state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.BASE_STONE_NETHER)
            || state.isIn(BlockTags.STONE_ORE_REPLACEABLES) || state.isIn(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
            || state.isIn(BlockTags.COAL_ORES) || state.isIn(BlockTags.IRON_ORES) || state.isIn(BlockTags.COPPER_ORES)
            || state.isIn(BlockTags.GOLD_ORES) || state.isIn(BlockTags.REDSTONE_ORES) || state.isIn(BlockTags.LAPIS_ORES)
            || state.isIn(BlockTags.DIAMOND_ORES) || state.isIn(BlockTags.EMERALD_ORES) || state.isIn(BlockTags.DIRT)
            || state.isIn(BlockTags.SAND) || state.isIn(BlockTags.NYLIUM) || state.isIn(BlockTags.SNOW)
            || state.isIn(BlockTags.CAVE_VINES) || state.isIn(BlockTags.SCULK_REPLACEABLE)) return true;
        return NATURAL.contains(state.getBlock()) || state.getBlock() instanceof InfestedBlock;
    }

    private static boolean isSensor(BlockState state) {
        return state.isOf(Blocks.SCULK_SENSOR) || state.isOf(Blocks.CALIBRATED_SCULK_SENSOR);
    }

    /** One planning pass: caches, and what got in the way, for the message when nothing works. */
    private final class Plan {
        final Long2IntOpenHashMap floors = new Long2IntOpenHashMap();
        final Reference2IntOpenHashMap<BlockState> ticks = new Reference2IntOpenHashMap<>();
        final Long2IntOpenHashMap pockets = new Long2IntOpenHashMap();
        final List<Sensor> sensors = new ArrayList<>();
        final BlockPos center;
        boolean hitHidden, hitUnloaded;
        int fluid, cave, sculk, hard, falling;
        BlockPos built;
        BlockState builtState;
        double builtDistance = Double.MAX_VALUE;

        Plan(BlockPos center) {
            this.center = center;
            floors.defaultReturnValue(Integer.MAX_VALUE);
            ticks.defaultReturnValue(Integer.MIN_VALUE);
            pockets.defaultReturnValue(-1);
            findSensors();
        }

        /** Loaded, and not inside deepslate the server sent hidden. */
        boolean known(BlockPos pos) {
            int floor = floor(pos);
            if (floor == UNLOADED) {
                hitUnloaded = true;
                return false;
            }
            if (pos.getY() < floor) {
                hitHidden = true;
                return false;
            }
            return true;
        }

        private int floor(BlockPos pos) {
            long key = ((long) (pos.getX() >> 4) << 32) | ((pos.getZ() >> 4) & 0xFFFFFFFFL);
            int floor = floors.get(key);
            if (floor != Integer.MAX_VALUE) return floor;
            floor = mc.world.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) instanceof WorldChunk chunk
                ? DeepslateHiding.realFloor(chunk) : UNLOADED;
            floors.put(key, floor);
            return floor;
        }

        int breakTicks(BlockState state, BlockPos pos) {
            int cached = ticks.getInt(state);
            if (cached != Integer.MIN_VALUE) return cached;

            int result;
            float hardness = state.getHardness(mc.world, pos);
            if (hardness < 0) result = -1;
            else if (hardness == 0) result = 1;
            else {
                double best = 0;
                for (int slot = 0; slot < 9; slot++) {
                    if (!worn(mc.player.getInventory().getStack(slot))) best = Math.max(best, BlockUtils.getBreakDelta(slot, state));
                }
                int needed = best <= 0 ? Integer.MAX_VALUE : (int) Math.ceil(1 / best);
                result = needed > MAX_BREAK_TICKS ? -1 : needed;
            }
            ticks.put(state, result);
            return result;
        }

        /** Size of the open space at pos, not counting the tunnel, up to {@link #CAVE_SIZE}. */
        int pocket(BlockPos start, Node from, Step step) {
            long startKey = start.asLong();
            int cached = pockets.get(startKey);
            if (cached >= 0) return cached;

            LongOpenHashSet seen = new LongOpenHashSet();
            List<BlockPos> queue = new ArrayList<>();
            queue.add(start);
            seen.add(startKey);
            int size = 0;
            for (int i = 0; i < queue.size() && size < CAVE_SIZE; i++) {
                BlockPos p = queue.get(i);
                size++;
                for (Direction side : SIDES) {
                    BlockPos n = p.offset(side);
                    if (!seen.add(n.asLong()) || isOurs(n, from, step)) continue;
                    int floor = floor(n);
                    if (floor == UNLOADED || n.getY() < floor) continue;
                    if (mc.world.getBlockState(n).isAir()) queue.add(n);
                }
            }
            pockets.put(startKey, size);
            return size;
        }

        /** Sensors near the plan, found through the section palettes so only sections holding one get read. */
        private void findSensors() {
            int cx = center.getX() >> 4, cz = center.getZ() >> 4;
            int lowSection = (center.getY() - 24) >> 4, highSection = (center.getY() + 24) >> 4;
            for (int x = cx - 2; x <= cx + 2; x++) {
                for (int z = cz - 2; z <= cz + 2; z++) {
                    if (!(mc.world.getChunk(x, z, ChunkStatus.FULL, false) instanceof WorldChunk chunk)) continue;
                    ChunkSection[] sections = chunk.getSectionArray();
                    for (int sy = lowSection; sy <= highSection; sy++) {
                        int index = chunk.sectionCoordToIndex(sy);
                        if (index < 0 || index >= sections.length) continue;
                        ChunkSection section = sections[index];
                        if (section == null || section.isEmpty() || !section.hasAny(AutoMiner::isSensor)) continue;

                        for (int ly = 0; ly < 16; ly++) {
                            for (int lz = 0; lz < 16; lz++) {
                                for (int lx = 0; lx < 16; lx++) {
                                    BlockState state = section.getBlockState(lx, ly, lz);
                                    if (!isSensor(state)) continue;
                                    int range = state.isOf(Blocks.CALIBRATED_SCULK_SENSOR) ? CALIBRATED_RANGE : SENSOR_RANGE;
                                    sensors.add(new Sensor(new BlockPos((x << 4) + lx, (sy << 4) + ly, (z << 4) + lz), range));
                                }
                            }
                        }
                    }
                }
            }
        }

        boolean nearSensor(BlockPos pos) {
            for (Sensor sensor : sensors) {
                if (sensor.pos().getSquaredDistance(pos) <= sensor.range() * sensor.range()) return true;
            }
            return false;
        }

        void noteBuilt(BlockPos pos, BlockState state) {
            double distance = Math.sqrt(pos.getSquaredDistance(mc.player.getBlockPos()));
            if (distance < builtDistance) {
                builtDistance = distance;
                built = pos.toImmutable();
                builtState = state;
            }
        }

        String blockedBy() {
            List<String> reasons = new ArrayList<>(5);
            if (fluid > 0) reasons.add("lava or water");
            if (cave > 0) reasons.add("caves");
            if (sculk > 0) reasons.add("sculk sensors");
            if (falling > 0) reasons.add("sand or gravel above");
            if (hard > 0) reasons.add("blocks too hard to dig");
            if (built != null) reasons.add("built blocks");
            return reasons.isEmpty() ? "solid ground runs out" : String.join(", ", reasons);
        }
    }

    // Aiming

    /** A spot on a face of the block you can see it by, a little off the middle like a real aim. */
    private Vec3d pickAimPoint(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        VoxelShape shape = mc.world.getBlockState(pos).getOutlineShape(mc.world, pos);
        if (shape.isEmpty()) return null;
        Box box = shape.getBoundingBox().offset(pos);

        List<Direction> faces = new ArrayList<>(3);
        for (Direction side : SIDES) {
            Vec3d face = faceCenter(box, side);
            if ((eye.x - face.x) * side.getOffsetX() + (eye.y - face.y) * side.getOffsetY() + (eye.z - face.z) * side.getOffsetZ() > 0) faces.add(side);
        }
        faces.sort(Comparator.comparingDouble(side -> eye.squaredDistanceTo(faceCenter(box, side))));

        aimOverhead = false;
        for (Direction side : faces) {
            // Right above or below you: a spot a little ahead of the eye, kept there as you move, so only the
            // pitch changes. A fixed spot spins the view around once the eye passes under it.
            if (side.getAxis() == Direction.Axis.Y && overhead(eye, box)) {
                aimBox = box;
                aimFaceY = side == Direction.UP ? box.maxY : box.minY;
                aimReach = 0.22 + 0.12 * random.nextDouble();
                Vec3d point = overheadPoint(eye);
                if (sees(eye, point, pos)) {
                    aimOverhead = true;
                    return point;
                }
                continue;
            }
            for (int attempt = 0; attempt < 4; attempt++) {
                Vec3d point = attempt < 3 ? randomPointOn(box, side) : faceCenter(box, side);
                if (sees(eye, point, pos)) return point;
            }
        }
        return null;
    }

    private static boolean overhead(Vec3d eye, Box box) {
        return eye.x > box.minX - 0.2 && eye.x < box.maxX + 0.2 && eye.z > box.minZ - 0.2 && eye.z < box.maxZ + 0.2;
    }

    /** A block right above or below your eye. */
    private boolean underOrOver(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        return pos.getY() != MathHelper.floor(eye.y) && pos.getX() == MathHelper.floor(eye.x) && pos.getZ() == MathHelper.floor(eye.z);
    }

    private Vec3d overheadPoint(Vec3d eye) {
        Vec3d look = Vec3d.fromPolar(0, mc.player.getYaw());
        return new Vec3d(
            MathHelper.clamp(eye.x + look.x * aimReach, aimBox.minX + 0.05, aimBox.maxX - 0.05),
            aimFaceY,
            MathHelper.clamp(eye.z + look.z * aimReach, aimBox.minZ + 0.05, aimBox.maxZ - 0.05)
        );
    }

    /** Where to aim for the block being dug, right now. */
    private Vec3d miningAim(Vec3d eye) {
        return aimOverhead ? overheadPoint(eye) : aimPoint;
    }

    private static Vec3d faceCenter(Box box, Direction side) {
        Vec3d c = box.getCenter();
        return new Vec3d(
            side.getOffsetX() == 0 ? c.x : side.getOffsetX() > 0 ? box.maxX : box.minX,
            side.getOffsetY() == 0 ? c.y : side.getOffsetY() > 0 ? box.maxY : box.minY,
            side.getOffsetZ() == 0 ? c.z : side.getOffsetZ() > 0 ? box.maxZ : box.minZ
        );
    }

    private Vec3d randomPointOn(Box box, Direction side) {
        Vec3d c = faceCenter(box, side);
        return c.add(
            side.getOffsetX() != 0 ? 0 : spread() * box.getLengthX() * 0.32,
            side.getOffsetY() != 0 ? 0 : spread() * box.getLengthY() * 0.32,
            side.getOffsetZ() != 0 ? 0 : spread() * box.getLengthZ() * 0.32
        );
    }

    private double spread() {
        return MathHelper.clamp(random.nextGaussian() * 0.45, -1, 1);
    }

    /** Whether the line from your eye to the point reaches this block first, as the crosshair would. */
    private boolean sees(Vec3d eye, Vec3d point, BlockPos pos) {
        Vec3d end = eye.add(point.subtract(eye).multiply(1.02));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    private boolean crosshairOn(BlockPos pos) {
        return mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    private boolean aimSettled() {
        return !segActive || segTime >= segDuration * 0.85;
    }

    private Vec3d glanceSpot() {
        float yaw = mc.player.getYaw() + (random.nextBoolean() ? 1 : -1) * (30 + random.nextFloat() * 45);
        float pitch = (float) MathHelper.clamp(mc.player.getPitch() * 0.5 + random.nextGaussian() * 12, -35, 30);
        return mc.player.getEyePos().add(Vec3d.fromPolar(pitch, yaw).multiply(4));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        aim(event.tickDelta);
        if (!renderPath.get() || mc.player == null || path.isEmpty() || waitingRejoin || anchor == null) return;

        Color line = pathColor.get();
        Color fill = new Color(line.r, line.g, line.b, Math.max(12, line.a / 5));
        Vec3d prev = Vec3d.ofBottomCenter(anchor).add(0, 0.05, 0);
        for (int i = 0; i < path.size() && i < 16; i++) {
            Vec3d next = Vec3d.ofBottomCenter(path.get(i).feet()).add(0, 0.05, 0);
            event.renderer.line(prev.x, prev.y, prev.z, next.x, next.y, next.z, line);
            prev = next;
        }
        if (miningTarget != null) event.renderer.box(miningTarget, fill, line, ShapeMode.Both, 0);
    }

    /**
     * Moves the view toward what to look at, every frame, the way a hand moves a mouse: a flick that eases in and
     * out and takes longer the further and smaller the target (Fitts' law), sometimes a little past it and back,
     * then a slight tremor while holding still. Every change goes through the same mouse-to-view step as real
     * mouse input, in whole steps of the sensitivity's size, as a mouse makes them.
     */
    private void aim(float tickDelta) {
        long time = System.nanoTime();
        double dt = lastFrame == 0 ? 0.016 : Math.min(0.05, (time - lastFrame) / 1e9);
        lastFrame = time;

        if (!aimAllowed()) {
            expectYaw = Float.NaN;
            segActive = false;
            segAim = null;
            return;
        }

        float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
        if (!Float.isNaN(expectYaw) && (Math.abs(MathHelper.wrapDegrees(yaw - expectYaw)) > 0.001f || Math.abs(pitch - expectPitch) > 0.001f)) {
            // The mouse moved under your hand: let go until you're done.
            userAt = System.currentTimeMillis();
            expectYaw = Float.NaN;
            segActive = false;
            segAim = null;
            return;
        }

        // What is being looked at; a new one starts a new flick.
        Vec3d goal = glancePoint != null ? glancePoint : aimPoint != null ? aimPoint : lookAhead;
        if (goal == null) {
            expectYaw = yaw;
            expectPitch = pitch;
            return;
        }

        Vec3d eye = mc.player.getCameraPosVec(tickDelta);
        Vec3d target = goal == aimPoint ? miningAim(eye) : goal;
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        // Almost straight up or down the heading means nothing; keep it rather than spin.
        double wantYaw = dx * dx + dz * dz < 0.02 ? yaw : Math.toDegrees(Math.atan2(dz, dx)) - 90;
        double wantPitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        double offYaw = MathHelper.wrapDegrees(wantYaw - yaw), offPitch = wantPitch - pitch;

        if (goal != segAim) {
            segAim = goal;
            startFlick(yaw, pitch, offYaw, offPitch, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }

        double cmdYaw, cmdPitch;
        if (segActive) {
            segTime += dt;
            double t = Math.min(1, segTime / segDuration);
            double s = t * t * t * (10 - 15 * t + 6 * t * t);
            double toYaw = segFromYaw + MathHelper.wrapDegrees(wantYaw + segOverYaw - segFromYaw);
            double toPitch = wantPitch + segOverPitch;
            cmdYaw = segFromYaw + (toYaw - segFromYaw) * s;
            cmdPitch = segFromPitch + (toPitch - segFromPitch) * s;
            if (t >= 1) {
                if (segOverYaw != 0 || segOverPitch != 0) {
                    // Went a little past: a short correction back.
                    segFromYaw = cmdYaw;
                    segFromPitch = cmdPitch;
                    segOverYaw = segOverPitch = 0;
                    segTime = 0;
                    segDuration = (0.08 + 0.07 * random.nextDouble()) / aimSpeed.get();
                } else {
                    segActive = false;
                }
            }
        } else {
            double seconds = time / 1e9;
            double shakeYaw = TREMOR * (Math.sin(seconds * tremor[0] + tremor[4]) + 0.6 * Math.sin(seconds * tremor[1] + tremor[5]));
            double shakePitch = TREMOR * (Math.sin(seconds * tremor[2] + tremor[6]) + 0.6 * Math.sin(seconds * tremor[3] + tremor[7]));
            double follow = 1 - Math.exp(-dt / 0.085);
            cmdYaw = yaw + MathHelper.wrapDegrees(wantYaw + shakeYaw - yaw) * follow;
            cmdPitch = pitch + (wantPitch + shakePitch - pitch) * follow;
        }

        turn(MathHelper.wrapDegrees(cmdYaw - yaw), MathHelper.clamp(cmdPitch, -90, 90) - pitch);
        expectYaw = mc.player.getYaw();
        expectPitch = mc.player.getPitch();
    }

    private void startFlick(float yaw, float pitch, double offYaw, double offPitch, double distance) {
        double angle = Math.sqrt(offYaw * offYaw + offPitch * offPitch);
        segFromYaw = yaw;
        segFromPitch = pitch;
        segTime = 0;
        segOverYaw = segOverPitch = 0;
        segActive = angle > 0.6;
        if (!segActive) return;

        // How big the block looks, for how carefully the hand has to land on it.
        double width = Math.toDegrees(2 * Math.atan(0.5 / Math.max(0.5, distance)));
        double fitts = 0.075 + 0.085 * Math.log(1 + angle / Math.max(4, width)) / Math.log(2);
        segDuration = Math.max(0.05, fitts / aimSpeed.get() * (0.85 + 0.35 * random.nextDouble()));

        if (angle > 8 && random.nextDouble() < 0.3) {
            double past = Math.min(3, angle * (0.03 + 0.06 * random.nextDouble()));
            segOverYaw = offYaw / angle * past;
            segOverPitch = offPitch / angle * past;
        }
    }

    /** Turns the view by whole mouse steps, through the same call the game makes for real mouse movement. */
    private void turn(double yawDegrees, double pitchDegrees) {
        double sensitivity = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        double factor = sensitivity * sensitivity * sensitivity * 8.0;
        double step = factor * 0.15;
        residualX += yawDegrees / step;
        residualY += pitchDegrees / step;
        long px = Math.round(residualX), py = Math.round(residualY);
        residualX -= px;
        residualY -= py;
        if (px != 0 || py != 0) mc.player.changeLookDirection(px * factor, py * factor);
    }

    private boolean aimAllowed() {
        if (!isActive() || waitingRejoin || joinGrace > 0 || mc.player == null || mc.world == null || path.isEmpty()) return false;
        if (mc.currentScreen != null || Modules.get().isActive(Freecam.class)) return false;
        if (System.currentTimeMillis() - userAt < USER_PAUSE_MS) return false;
        AutoEat autoEat = Modules.get().get(AutoEat.class);
        return !(autoEat.isActive() && autoEat.eating);
    }

    @Override
    public String getInfoString() {
        if (waitingRejoin || joinGrace > 0) return "relog";
        return dir == null ? null : progress + "m";
    }
}
