package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.SpawnerType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Vector3d;

import java.util.*;

/** Client-thread index of server-provided spawners. No world scans or NBT reads during rendering. */
public final class SpawnerESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder().name("range")
        .description("Maximum distance for spawner boxes and labels.").defaultValue(128).range(8, 512).sliderRange(16, 256).build());
    private final Setting<Boolean> trialSpawners = sgGeneral.add(new BoolSetting.Builder().name("trial-spawners")
        .description("Also show trial spawners and their current mob, when sent by the server.").defaultValue(true).build());
    private final Setting<Boolean> labels = sgRender.add(new BoolSetting.Builder().name("labels")
        .description("Show mob type and distance above each spawner.").defaultValue(true).build());
    private final Setting<Double> labelScale = sgRender.add(new DoubleSetting.Builder().name("label-scale")
        .defaultValue(1).range(.5, 2).sliderRange(.5, 2).visible(labels::get).build());
    private final Setting<Integer> maxLabels = sgRender.add(new IntSetting.Builder().name("max-labels")
        .description("Label only the closest spawners to keep crowded farms readable.").defaultValue(32).range(1, 128).sliderRange(1, 64).visible(labels::get).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder().name("tracers").defaultValue(false).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode")
        .defaultValue(ShapeMode.Both).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder().name("side-color")
        .defaultValue(new SettingColor(178, 138, 255, 35)).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder().name("line-color")
        .defaultValue(new SettingColor(178, 138, 255, 220)).build());

    private static final Color BACKGROUND = new Color(18, 20, 28, 200), TEXT = new Color(245, 245, 250);
    private static final Comparator<Found> NEAREST = Comparator.comparingDouble(found -> found.distanceSquared);
    private final Map<Long, Map<BlockPos, Found>> chunks = new HashMap<>();
    private final LinkedHashMap<Long, WorldChunk> scans = new LinkedHashMap<>();
    private final List<Found> visible = new ArrayList<>();
    private final PriorityQueue<Found> nearest = new PriorityQueue<>(257, NEAREST.reversed());
    private final Vector3d screen = new Vector3d();
    private ClientWorld world;
    private volatile long generation;
    private int ticks;

    private static final class Found {
        final BlockPos pos;
        final boolean trial;
        Identifier mob;
        String title, label;
        int distance = -1;
        double distanceSquared;
        Found(BlockPos pos, boolean trial) { this.pos = pos.toImmutable(); this.trial = trial; }
    }

    public SpawnerESP() {
        super(Categories.Render, "spawner-esp", "Highlights spawners and labels the mob they spawn. Uses only data sent by the server.");
    }

    private void clear() {
        generation++;
        chunks.clear(); scans.clear(); visible.clear(); nearest.clear(); world = null; ticks = 0;
    }
    @Override public void onActivate() { clear(); loadWorld(); }
    @Override public void onDeactivate() { clear(); }
    @EventHandler private void onLeft(GameLeftEvent event) { clear(); }

    private void loadWorld() {
        if (mc.world == null) return;
        world = mc.world;
        for (var chunk : Utils.chunks()) if (chunk instanceof WorldChunk loaded) queue(loaded);
    }
    private void queue(WorldChunk chunk) {
        // Existing block entities provide immediate labels; bounded palette scans also
        // catch server/plugin spawner blocks whose block entity was omitted.
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
            if (entity instanceof MobSpawnerBlockEntity || entity instanceof TrialSpawnerBlockEntity) {
                put(entity.getPos(), entity instanceof TrialSpawnerBlockEntity,
                    SpawnerType.mobId(entity.toInitialChunkDataNbt(mc.world.getRegistryManager())));
            }
        }
        scans.put(chunk.getPos().toLong(), chunk);
    }

    @EventHandler private void onChunk(ChunkDataEvent event) {
        if (world != mc.world) { clear(); loadWorld(); }
        queue(event.chunk());
    }

    @EventHandler private void onPacket(PacketEvent.Receive event) {
        if (mc.world == null) return;
        var packet = event.packet;
        if (!(packet instanceof ChunkDataS2CPacket || packet instanceof BlockEntityUpdateS2CPacket || packet instanceof UnloadChunkS2CPacket)) return;
        ClientWorld receivedWorld = mc.world;
        long token = generation;
        mc.execute(() -> {
            if (!isActive() || generation != token || mc.world != receivedWorld) return;
            if (world != receivedWorld) { clear(); loadWorld(); }
            if (packet instanceof ChunkDataS2CPacket data) {
                // A replacement packet supersedes every previous find in this chunk.
                chunks.remove(ChunkPos.toLong(data.getChunkX(), data.getChunkZ()));
                visible.removeIf(found -> ChunkPos.toLong(found.pos) == ChunkPos.toLong(data.getChunkX(), data.getChunkZ()));
                data.getChunkData().getBlockEntities(data.getChunkX(), data.getChunkZ()).accept(this::accept);
            } else if (packet instanceof BlockEntityUpdateS2CPacket data) {
                accept(data.getPos(), data.getBlockEntityType(), data.getNbt());
            } else if (packet instanceof UnloadChunkS2CPacket unload) {
                long key = unload.pos().toLong();
                chunks.remove(key); scans.remove(key);
                visible.removeIf(found -> ChunkPos.toLong(found.pos) == key);
            }
        });
    }

    private void accept(BlockPos pos, BlockEntityType<?> type, NbtCompound nbt) {
        if (type != BlockEntityType.MOB_SPAWNER && type != BlockEntityType.TRIAL_SPAWNER) { remove(pos); return; }
        put(pos, type == BlockEntityType.TRIAL_SPAWNER, SpawnerType.mobId(nbt));
    }

    private void put(BlockPos pos, boolean trial, Identifier mob) {
        var entries = chunks.computeIfAbsent(ChunkPos.toLong(pos), ignored -> new HashMap<>());
        Found found = entries.get(pos);
        if (found == null || found.trial != trial) {
            found = new Found(pos, trial); entries.put(found.pos, found);
        }
        if (found.title == null || mob != null && !mob.equals(found.mob)) {
            if (mob != null) found.mob = mob;
            String name = found.mob == null ? null : Registries.ENTITY_TYPE.getOptionalValue(found.mob)
                .map(type -> type.getName().getString()).orElse(found.mob.toString());
            String kind = trial ? "Trial Spawner" : "Spawner";
            found.title = name == null ? kind + " (Unknown mob)" : name + " " + kind;
            found.distance = -1;
        }
    }

    private void remove(BlockPos pos) {
        var entries = chunks.get(ChunkPos.toLong(pos));
        if (entries == null || entries.remove(pos) == null) return;
        if (entries.isEmpty()) chunks.remove(ChunkPos.toLong(pos));
        visible.removeIf(found -> found.pos.equals(pos));
    }

    @EventHandler private void onBlock(BlockUpdateEvent event) {
        boolean spawner = event.newState.isOf(Blocks.SPAWNER), trial = event.newState.isOf(Blocks.TRIAL_SPAWNER);
        if (spawner || trial) {
            put(event.pos, trial, null);
            if (mc.world != null) queue(mc.world.getWorldChunk(event.pos));
        } else remove(event.pos);
    }

    private void scan(WorldChunk chunk) {
        if (world != mc.world || !mc.world.getChunkManager().isChunkLoaded(chunk.getPos().x, chunk.getPos().z)) return;
        // Palette rejection makes ordinary chunks cheap. Read block entities only at spawner blocks.
        var sections = chunk.getSectionArray();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            var section = sections[sectionIndex];
            if (section == null || section.isEmpty() || !section.hasAny(state -> state.isOf(Blocks.SPAWNER) || state.isOf(Blocks.TRIAL_SPAWNER))) continue;
            int baseY = chunk.getBottomY() + (sectionIndex << 4);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                var state = section.getBlockState(x, y, z);
                boolean trial = state.isOf(Blocks.TRIAL_SPAWNER);
                if (!trial && !state.isOf(Blocks.SPAWNER)) continue;
                pos.set(chunk.getPos().getStartX() + x, baseY + y, chunk.getPos().getStartZ() + z);
                BlockEntity entity = chunk.getBlockEntity(pos);
                Identifier mob = entity instanceof MobSpawnerBlockEntity || entity instanceof TrialSpawnerBlockEntity
                    ? SpawnerType.mobId(entity.toInitialChunkDataNbt(mc.world.getRegistryManager())) : null;
                put(pos, trial, mob);
            }
        }
    }

    @EventHandler private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) { clear(); return; }
        if (world != mc.world) { clear(); loadWorld(); }
        // Bounded client-thread scanning avoids reading live chunk palettes on a worker thread.
        for (int budget = 0; budget < 2 && !scans.isEmpty(); budget++) {
            var iterator = scans.values().iterator();
            WorldChunk next = iterator.next(); iterator.remove(); scan(next);
        }
        if (++ticks % 20 == 0) chunks.keySet().removeIf(key -> !mc.world.getChunkManager().isChunkLoaded(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key)));
        visible.clear();
        nearest.clear();
        double max = (double) range.get() * range.get();
        for (var entries : chunks.values()) for (Found found : entries.values()) {
            if (found.trial && !trialSpawners.get()) continue;
            found.distanceSquared = found.pos.getSquaredDistance(mc.player.getEntityPos());
            if (found.distanceSquared > max) continue;
            if (nearest.size() == 256 && found.distanceSquared >= nearest.peek().distanceSquared) continue;
            int meters = (int) Math.round(Math.sqrt(found.distanceSquared));
            if (meters != found.distance) { found.distance = meters; found.label = found.title + " | " + meters + "m"; }
            if (nearest.size() == 256) nearest.poll();
            nearest.add(found);
        }
        visible.addAll(nearest);
        visible.sort(NEAREST);
    }

    @EventHandler private void onRender(Render3DEvent event) {
        if (world != mc.world || mc.player == null) return;
        for (Found found : visible) {
            event.renderer.box(found.pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            if (tracers.get()) event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                found.pos.getX() + .5, found.pos.getY() + .5, found.pos.getZ() + .5, lineColor.get());
        }
    }

    @EventHandler private void onRender2D(Render2DEvent event) {
        if (!labels.get() || world != mc.world || mc.player == null || mc.options.hudHidden) return;
        TextRenderer text = TextRenderer.get();
        for (int i = 0, count = Math.min(maxLabels.get(), visible.size()); i < count; i++) {
            Found found = visible.get(i);
            screen.set(found.pos.getX() + .5, found.pos.getY() + 1.25, found.pos.getZ() + .5);
            if (!NametagUtils.to2D(screen, labelScale.get())) continue;
            NametagUtils.begin(screen);
            try {
                text.beginBig();
                double width = text.getWidth(found.label), height = text.getHeight();
                Renderer2D.COLOR.begin();
                Renderer2D.COLOR.quad(-width / 2 - 5, -height - 3, width + 10, height + 6, BACKGROUND);
                Renderer2D.COLOR.render();
                text.render(found.label, -width / 2, -height, TEXT, false);
                text.end();
            } finally { NametagUtils.end(); }
        }
    }

    @Override public String getInfoString() { return visible.isEmpty() ? null : Integer.toString(visible.size()); }
}
