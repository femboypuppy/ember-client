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
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds skeleton spawners from anywhere above them, even at bedrock while you're on the surface.
 *
 * A chunk packet carries its block entities in a separate list, a spawner's with the mob it spawns. Hiding the blocks
 * underground doesn't remove that list, so the spawner shows in it while the block itself arrives as fill. This reads
 * the list straight from the packet, before the game drops entries whose block doesn't match.
 */
public class SkeletonSpawnerFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Announce each skeleton spawner found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> saveFinds = sgGeneral.add(new BoolSetting.Builder()
        .name("save-finds")
        .description("Remember the spawners for this server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Line to each spawner in range.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> renderRange = sgGeneral.add(new IntSetting.Builder()
        .name("render-range")
        .description("Only draw spawners within this many blocks.")
        .defaultValue(256)
        .range(16, 4096)
        .sliderRange(32, 1024)
        .build()
    );

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Spawner highlight.")
        .defaultValue(new SettingColor(235, 235, 220, 220))
        .build()
    );

    private static final String FIND_TYPE = "skeleton-spawner";
    private static final String SKELETON = "minecraft:skeleton";

    /** Spawner position -> chunk key; filled from the network thread. */
    private final Map<BlockPos, Long> spawners = new ConcurrentHashMap<>();
    private final Map<BlockPos, Boolean> announced = new ConcurrentHashMap<>();
    /** What other spawner packets look like, logged a few times per session to see how the server sends them. */
    private final AtomicInteger samplesLeft = new AtomicInteger();
    private volatile FindsStorage.Context context = new FindsStorage.Context("", "");

    public SkeletonSpawnerFinder() {
        super(Categories.Donut, "skeleton-spawner-finder", "Finds skeleton spawners from the surface, even down at bedrock.");
    }

    @Override
    public void onActivate() {
        spawners.clear();
        announced.clear();
        samplesLeft.set(5);
        context = FindsStorage.context();
        if (mc.world == null) return;
        for (FindsStorage.Find find : FindsStorage.get(context, FIND_TYPE)) {
            BlockPos pos = new BlockPos(find.x, find.y, find.z);
            spawners.put(pos, ChunkPos.toLong(pos));
            announced.put(pos, Boolean.TRUE);
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        onActivate();
    }

    /** Spawners belong to the dimension they were found in. */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world != null && !FindsStorage.context().equals(context)) onActivate();
    }

    /** Network thread. */
    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        try {
            if (event.packet instanceof ChunkDataS2CPacket packet) {
                int cx = packet.getChunkX(), cz = packet.getChunkZ();
                packet.getChunkData().getBlockEntities(cx, cz).accept((pos, type, nbt) -> seen(pos, type, nbt));
            } else if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
                seen(packet.getPos(), packet.getBlockEntityType(), packet.getNbt());
            } else if (event.packet instanceof UnloadChunkS2CPacket) {
                // Spawners stay listed while out of range: they are saved finds now.
            }
        } catch (Throwable t) {
            // Never let a bad packet reach the network handler.
            MeteorClient.LOG.warn("[Ember SkeletonSpawner] Couldn't read a packet", t);
        }
    }

    private void seen(BlockPos pos, BlockEntityType<?> type, NbtCompound nbt) {
        if (type != BlockEntityType.MOB_SPAWNER) return;
        String mob = mobOf(nbt);
        if (!SKELETON.equals(mob)) {
            if (samplesLeft.getAndDecrement() > 0) {
                MeteorClient.LOG.info("[Ember SkeletonSpawner] Spawner at {} spawns {} (data: {})", pos.toShortString(), mob, nbt == null ? "none" : nbt.getKeys());
            }
            return;
        }

        BlockPos fixed = pos.toImmutable();
        spawners.put(fixed, ChunkPos.toLong(fixed));
        if (announced.putIfAbsent(fixed, Boolean.TRUE) != null) return;

        MeteorClient.LOG.info("[Ember SkeletonSpawner] Skeleton spawner at {}", fixed.toShortString());
        FindsStorage.Context ctx = context;
        mc.execute(() -> {
            if (saveFinds.get()) FindsStorage.add(ctx, FIND_TYPE, fixed, "skeleton spawner");
            if (!notify.get()) return;
            String where = fixed.getX() + ", " + fixed.getY() + ", " + fixed.getZ();
            if (!EmberNotificationsHud.shouldInterceptChat()) info("Skeleton spawner at (highlight)%s", where);
            EmberNotificationsHud.push("Skeleton spawner", where, EmberNotificationsHud.GOOD);
        });
    }

    /** The spawned mob's id from SpawnData.entity.id, or null. */
    private static String mobOf(NbtCompound nbt) {
        if (nbt == null) return null;
        NbtCompound data = nbt.getCompoundOrEmpty("SpawnData");
        NbtCompound entity = data.getCompoundOrEmpty("entity");
        return entity.getString("id", null);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;
        Color line = color.get(), side = new Color(line.r, line.g, line.b, line.a / 4);
        double max = (double) renderRange.get() * renderRange.get();
        List<BlockPos> near = new ArrayList<>();
        for (BlockPos pos : spawners.keySet()) {
            double dx = pos.getX() + 0.5 - mc.player.getX(), dz = pos.getZ() + 0.5 - mc.player.getZ();
            if (dx * dx + dz * dz <= max) near.add(pos);
        }
        for (BlockPos pos : near) {
            event.renderer.box(pos, side, line, ShapeMode.Both, 0);
            if (tracers.get()) {
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, line);
            }
        }
    }

    @Override
    public String getInfoString() {
        return spawners.isEmpty() ? null : String.valueOf(spawners.size());
    }
}
