package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AntiVanish extends Module {
    private static final long SIGNAL_WINDOW_MS = 15000L;
    private static final long DETECTION_TTL_MS = 20000L;
    private static final long CRITICAL_COOLDOWN_MS = 12000L;
    private static final long ANNOUNCE_COOLDOWN_MS = 1500L;
    private static final long ANNOUNCE_WINDOW_MS = 4000L;
    private static final int MAX_ANNOUNCE_PER_WINDOW = 6;
    private static final int MAX_OBSERVATIONS_PER_TICK = 512;
    private static final int CRITICAL_SCORE = 35;
    private static final long SELF_PLACE_TTL_MS = 6000L;
    private static final long CONTAINER_SELF_GRACE_MS = 2500L;
    private static final long RECENT_MESSAGE_TTL_MS = 8000L;
    private static final long TRUSTED_LISTED_MS = 1500L;
    private static final double SENSOR_RANGE = 64.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAlerts = settings.createGroup("Alerts");

    private final Setting<Boolean> completionProbe = sgGeneral.add(new BoolSetting.Builder()
        .name("completion-probe")
        .description("Every 5 seconds, silently asks your region's server which players it hosts (/minecraft:msg tab completion). Finds staff on your shard, including ones hidden from tab.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> soundAndParticleSensors = sgGeneral.add(new BoolSetting.Builder()
        .name("ghost-sensors")
        .description("Detect vanished players near you via unattributed chest/door sounds, invisible entities, and ghost particles.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sensorHints = sgGeneral.add(new BoolSetting.Builder()
        .name("sensor-hints")
        .description("Show a small notification for single ghost-sensor hits. They are weak evidence on their own.")
        .defaultValue(false)
        .visible(soundAndParticleSensors::get)
        .build()
    );

    private final Setting<Boolean> debugLog = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-log")
        .description("Write staff packets, shard changes and probe results to latest.log so detections can be verified.")
        .defaultValue(true)
        .onChanged(v -> StaffIntel.get().debug = v && isActive())
        .build()
    );

    private final Setting<Boolean> elderAnimation = sgAlerts.add(new BoolSetting.Builder()
        .name("elder-guardian-animation")
        .description("Show the staff alert overlay: amber card for hidden staff in your region, the elder guardian face when they are near you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifications = sgAlerts.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show bottom-right Ember notifications on vanish detections.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatNotify = sgAlerts.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Print vanish detections in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> testAnimation = sgAlerts.add(new BoolSetting.Builder()
        .name("test-elder-animation")
        .description("Click to immediately test the Elder Guardian staff-head curse animation and sound.")
        .defaultValue(false)
        .onChanged(v -> {
            if (v) {
                triggerTestAnimation();
            }
        })
        .build()
    );

    private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();
    private final Map<UUID, KnownPlayer> knownPlayers = new HashMap<>();
    private final Map<String, Detection> detections = new LinkedHashMap<>();
    private final Map<String, Long> signalCooldowns = new HashMap<>();
    private final Map<String, Long> announceCooldowns = new HashMap<>();
    private final Deque<Long> announceTimes = new ArrayDeque<>();
    private final Map<Long, Long> selfPlacedBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> confirmedDepartures = new HashMap<>();
    private final Map<Long, Long> automatedMechanisms = new HashMap<>();
    private final Map<String, Deque<Long>> weakParticleBursts = new HashMap<>();
    private final Deque<Signal> signals = new ArrayDeque<>();
    private final Deque<ExplosionEvent> recentExplosions = new ArrayDeque<>();
    private final Deque<PendingVanish> pendingVanishes = new ArrayDeque<>();
    private final Map<String, HiddenSpell> hiddenSpells = new LinkedHashMap<>();
    private final Deque<RecentMessage> recentMessages = new ArrayDeque<>();
    private final Map<UUID, Long> listedSinceMs = new HashMap<>();
    private final Set<UUID> staffIds = new HashSet<>();

    private volatile long lastLocalActionMs;
    private boolean serverSendsLeaveMessages;
    private int tickCounter;
    private long lastContainerActivityMs;
    private long lastCriticalMs;
    private long criticalUntilMs;
    private String criticalWatcher = "";
    private int seenProbeSeq;
    private int trackedShard = Integer.MIN_VALUE;

    public AntiVanish() {
        super(Categories.Donut, "anti-vanish", "Detects staff on your region's server, hidden staff, and staff near you.");
        StaffIntel.get();
    }

    private void triggerTestAnimation() {
        String sampleStaff = "DrDonutt";
        UUID sampleId = null;

        StaffDetector detector = Modules.get() != null ? Modules.get().get(StaffDetector.class) : null;
        if (detector != null) {
            for (StaffDetector.StaffEntry entry : detector.getTrackedEntries()) {
                if (entry.online && entry.name != null && !entry.name.isBlank()) {
                    sampleStaff = entry.name;
                    break;
                }
            }
        }

        StaffWatchingEffect.get().play(sampleId, sampleStaff, StaffWatchingEffect.Level.WATCHING, "Preview \u00B7 12 blocks away");
        testAnimation.set(false);
    }

    @Override
    public String getInfoString() {
        long now = System.currentTimeMillis();
        int live = 0;
        for (Detection detection : detections.values()) {
            if (detection.expiresAt > now && detectionWorthShowing(detection)) live++;
        }
        return live == 0 ? null : Integer.toString(live);
    }

    @Override
    public void onActivate() {
        resetRuntime();
        trackedShard = Integer.MIN_VALUE;
        StaffIntel.get().debug = debugLog.get();
        // Preload known DonutSMP staff skins so they are ready immediately if any vanish occurs
        for (String staff : StaffDetector.DEFAULT_DONUT_STAFF) {
            StaffFaceSkins.preload(staff);
        }
        if (mc.player != null && mc.world != null) trackListedPlayers();
    }

    @Override
    public void onDeactivate() {
        resetRuntime();
        StaffIntel.get().debug = false;
    }

    public boolean probeEnabled() {
        return completionProbe.get();
    }

    public boolean isWatchingNow(String name) {
        if (name == null || name.isBlank()) return false;
        long now = System.currentTimeMillis();
        if (criticalUntilMs > now && name.equalsIgnoreCase(criticalWatcher)) return true;

        for (Detection detection : detections.values()) {
            if (detection.expiresAt > now && name.equalsIgnoreCase(detection.name) && detectionWorthShowing(detection)) {
                return true;
            }
        }
        return false;
    }

    /** Shard-local state (hidden spells, pending vanishes, signals) is meaningless after moving to another goliath. */
    private boolean checkShardMoved() {
        int now = StaffIntel.get().currentShard();
        boolean moved = trackedShard != Integer.MIN_VALUE && now != trackedShard;
        trackedShard = now;
        return moved;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            resetRuntime();
            trackedShard = Integer.MIN_VALUE;
            return;
        }

        if (checkShardMoved()) {
            resetRuntime();
        }

        tickCounter++;

        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            lastContainerActivityMs = System.currentTimeMillis();
        }

        drainObservations();
        processPendingVanishes();

        // StaffIntel sends the shard probe; read each new answer once.
        StaffIntel intel = StaffIntel.get();
        if (completionProbe.get() && intel.probeSeq() != seenProbeSeq) {
            seenProbeSeq = intel.probeSeq();
            processCompletionProbe(intel.lastProbeNames());
        }

        if (tickCounter % 10 == 0) {
            trackListedPlayers();
            reviewSpells();
        }

        if (soundAndParticleSensors.get() && tickCounter % 5 == 0) {
            scanInvisiblePlayers();
        }

        pruneState();
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        handleOutbound(event.packet);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (handleInbound(event.packet)) event.cancel();
    }

    private void handleOutbound(Packet<?> packet) {
        if (packet == null) return;

        if (packet instanceof PlayerInteractBlockC2SPacket useOn) {
            lastLocalActionMs = System.currentTimeMillis();

            BlockHitResult hitResult = useOn.getBlockHitResult();
            if (hitResult != null && hitResult.getBlockPos() != null) {
                long until = System.currentTimeMillis() + SELF_PLACE_TTL_MS;
                BlockPos hit = hitResult.getBlockPos();
                BlockPos adjacent = hit.offset(hitResult.getSide());

                selfPlacedBlocks.put(hit.asLong(), until);
                selfPlacedBlocks.put(adjacent.asLong(), until);

                String itemPath = "";
                if (mc.player != null && useOn.getHand() != null) {
                    Identifier itemId = Registries.ITEM.getId(mc.player.getStackInHand(useOn.getHand()).getItem());
                    itemPath = shortPath(itemId == null ? "" : itemId.toString());
                }

                markSelfMultiBlockFootprint(hit, itemPath, selfPlacedBlocks, until);
                markSelfMultiBlockFootprint(adjacent, itemPath, selfPlacedBlocks, until);
            }
            return;
        }

        if (packet instanceof PlayerActionC2SPacket
            || packet instanceof PlayerInteractItemC2SPacket
            || packet instanceof PlayerInteractEntityC2SPacket
            || packet instanceof HandSwingC2SPacket) {
            lastLocalActionMs = System.currentTimeMillis();
        }
    }

    private static void markSelfMultiBlockFootprint(BlockPos pos, String path, Map<Long, Long> targets, long until) {
        if (pos == null || targets == null) return;
        targets.put(pos.asLong(), until);

        String id = path == null ? "" : path;
        boolean vertical = (id.endsWith("_door") && !id.endsWith("trapdoor"))
            || id.contains("sunflower")
            || id.contains("lilac")
            || id.contains("rose_bush")
            || id.contains("peony")
            || id.contains("tall_grass")
            || id.contains("large_fern")
            || id.contains("pitcher_plant");

        if (vertical) {
            targets.put(pos.up().asLong(), until);
            targets.put(pos.down().asLong(), until);
        }

        if (id.endsWith("_bed")) {
            targets.put(pos.add(1, 0, 0).asLong(), until);
            targets.put(pos.add(-1, 0, 0).asLong(), until);
            targets.put(pos.add(0, 0, 1).asLong(), until);
            targets.put(pos.add(0, 0, -1).asLong(), until);
        }
    }

    private boolean handleInbound(Packet<?> packet) {
        if (packet instanceof BundleS2CPacket bundle) {
            for (Packet<?> sub : bundle.getPackets()) handleInbound(sub);
            return false;
        }

        if (packet instanceof PlayerRemoveS2CPacket remove) {
            if (remove.profileIds().size() < 4) {
                for (UUID id : remove.profileIds()) observations.offer(Observation.tabRemove(id));
            }
        } else if (packet instanceof PlayerListS2CPacket info) {
            if (info.getActions().contains(PlayerListS2CPacket.Action.UPDATE_LISTED)) {
                int unlisted = 0;
                for (PlayerListS2CPacket.Entry entry : info.getEntries()) {
                    if (entry == null || entry.listed()) continue;
                    unlisted++;
                }

                if (unlisted > 0 && unlisted < 4) {
                    for (PlayerListS2CPacket.Entry entry : info.getEntries()) {
                        if (entry == null || entry.listed()) continue;
                        observations.offer(Observation.tabHide(entry.profileId()));
                    }
                }
            }
            if (info.getActions().contains(PlayerListS2CPacket.Action.UPDATE_GAME_MODE)
                && !info.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
                for (PlayerListS2CPacket.Entry entry : info.getEntries()) {
                    if (entry == null || entry.gameMode() == null || entry.profileId() == null) continue;
                    observations.offer(Observation.gamemode(entry.profileId(), entry.gameMode().asString()));
                }
            }
        } else if (packet instanceof GameMessageS2CPacket chat) {
            String text = chat.content() == null ? "" : chat.content().getString();
            if (!text.isBlank()) observations.offer(Observation.systemChat(text));

            String departedName = departedPlayerName(chat.content());
            if (!departedName.isBlank()) observations.offer(Observation.playerLeft(departedName));
        } else if (soundAndParticleSensors.get()) {
            if (packet instanceof EntityTrackerUpdateS2CPacket metadata) {
                observations.offer(Observation.entityMetadata(metadata.id()));
            } else if (packet instanceof ExplosionS2CPacket explosion) {
                Vec3d c = explosion.center();
                observations.offer(Observation.explosion(c, 5.0f));
            } else if (packet instanceof PlaySoundFromEntityS2CPacket sound) {
                Identifier id = sound.getSound().value().id();
                observations.offer(Observation.entitySound(sound.getEntityId(), id == null ? "" : id.toString()));
            } else if (packet instanceof ParticleS2CPacket particles) {
                Identifier id = Registries.PARTICLE_TYPE.getId(particles.getParameters().getType());
                observations.offer(Observation.position(ObservationType.PARTICLE, particles.getX(), particles.getY(), particles.getZ(), id == null ? "" : id.toString()));
            } else if (packet instanceof PlaySoundS2CPacket sound) {
                Identifier id = sound.getSound().value().id();
                observations.offer(Observation.position(ObservationType.POSITIONAL_SOUND, sound.getX(), sound.getY(), sound.getZ(), id == null ? "" : id.toString()));
            }
        }

        return false;
    }

    private void drainObservations() {
        Observation observation;
        for (int i = 0; i < MAX_OBSERVATIONS_PER_TICK && (observation = observations.poll()) != null; i++) {
            processObservation(observation);
        }
        while (observations.size() > 4096) observations.poll();
    }

    private void processObservation(Observation observation) {
        switch (observation.type) {
            case TAB_REMOVE -> handleTabRemoval(observation.profileId);
            case TAB_HIDE -> handleTabHidden(observation.profileId);
            case PLAYER_LEFT -> {
                serverSendsLeaveMessages = true;
                confirmDeparture(observation.detail);
            }
            case SYSTEM_CHAT -> cacheRecentMessage(observation.detail);
            case ENTITY_METADATA -> inspectInvisibleEntity(observation.entityId);
            case POSITIONAL_SOUND -> inspectPositionalSound(observation);
            case ENTITY_SOUND -> inspectEntitySound(observation);
            case PARTICLE -> inspectParticle(observation);
            case EXPLOSION -> rememberExplosion(observation);
            case GAMEMODE -> announceGamemode(observation.profileId, observation.detail);
        }
    }

    private void trackListedPlayers() {
        if (mc.getNetworkHandler() == null) return;

        long now = System.currentTimeMillis();

        for (PlayerListEntry info : mc.getNetworkHandler().getListedPlayerListEntries()) {
            if (info == null || info.getProfile() == null || info.getProfile().id() == null) continue;

            UUID uuid = info.getProfile().id();
            if (mc.player != null && mc.player.getUuid().equals(uuid)) continue;

            listedSinceMs.putIfAbsent(uuid, now);

            String name = info.getProfile().name();
            if (name != null) {
                if (StaffIntel.get().isStaff(info) && staffIds.add(uuid)) StaffFaceSkins.preload(name);
                if (!knownPlayers.containsKey(uuid)) {
                    knownPlayers.put(uuid, new KnownPlayer(uuid, name));
                }
                if (!hiddenSpells.isEmpty()) {
                    endSpell(name, false, 1000L);
                }
            }
        }

        if (listedSinceMs.size() > 1024 || knownPlayers.size() > 1024) {
            Set<UUID> connected = new HashSet<>();
            for (PlayerListEntry info : mc.getNetworkHandler().getPlayerList()) {
                if (info == null || info.getProfile() == null) continue;
                connected.add(info.getProfile().id());
            }
            listedSinceMs.keySet().retainAll(connected);
            knownPlayers.keySet().retainAll(connected);
        }
    }

    private boolean isKnownStaffName(String name) {
        return StaffIntel.get().isStaffName(name);
    }

    private boolean trustedListed(UUID uuid) {
        Long since = uuid == null ? null : listedSinceMs.get(uuid);
        return since != null && System.currentTimeMillis() - since >= TRUSTED_LISTED_MS;
    }

    private boolean credibleSubject(UUID uuid, String name) {
        // Team-detected staff have no tab entry left once removed, so remember them by UUID.
        return uuid != null && isPlausiblePlayerName(name) && (staffIds.contains(uuid) || isKnownStaffName(name)) && trustedListed(uuid);
    }

    private void handleTabRemoval(UUID uuid) {
        if (uuid == null || (mc.player != null && mc.player.getUuid().equals(uuid))) return;

        String name = knownName(uuid);
        if (!credibleSubject(uuid, name)) return;

        pendingVanishes.removeIf(pending -> pending.uuid.equals(uuid));
        pendingVanishes.addLast(new PendingVanish(uuid, name, tickCounter + 20));
    }

    private void handleTabHidden(UUID uuid) {
        if (uuid == null || (mc.player != null && mc.player.getUuid().equals(uuid))) return;

        KnownPlayer known = knownPlayers.get(uuid);
        String name = known != null && known.name != null ? known.name : knownName(uuid);

        if (!credibleSubject(uuid, name)) return;
        if (recentMessageNames(name)) return;

        reportHidden(uuid, name, false, "Vanish Event: hidden from TAB", "Hidden from tab", 100);
    }

    private String knownName(UUID uuid) {
        KnownPlayer known = knownPlayers.get(uuid);
        if (known != null && known.name != null && !known.name.isBlank()) return known.name;

        if (mc.getNetworkHandler() != null) {
            PlayerListEntry info = mc.getNetworkHandler().getPlayerListEntry(uuid);
            if (info != null && info.getProfile() != null) return info.getProfile().name();
        }
        return null;
    }

    private UUID knownUuid(String name) {
        for (KnownPlayer known : knownPlayers.values()) {
            if (known.name.equalsIgnoreCase(name)) return known.uuid;
        }
        return null;
    }

    private void confirmDeparture(String displayedName) {
        long now = System.currentTimeMillis();

        for (KnownPlayer known : knownPlayers.values()) {
            if (!containsPlayerName(displayedName, known.name)) continue;

            confirmedDepartures.put(known.uuid, now + 5000L);
            pendingVanishes.removeIf(pending -> pending.uuid.equals(known.uuid));
            hiddenSpells.remove(spellKey(known.name));

            Detection detection = detections.get(known.uuid.toString());
            if (detection != null && tabDepartureReason(detection.reason)) detections.remove(known.uuid.toString());

            signals.removeIf(signal -> signal.type == SignalType.VANISH
                && signal.subject.equalsIgnoreCase(known.name)
                && tabDepartureReason(signal.reason));
        }
    }

    private void processPendingVanishes() {
        while (!pendingVanishes.isEmpty() && pendingVanishes.peekFirst().dueTick <= tickCounter) {
            PendingVanish pending = pendingVanishes.removeFirst();

            if (mc.getNetworkHandler().getPlayerListEntry(pending.uuid) != null) continue;

            long now = System.currentTimeMillis();
            if (confirmedDepartures.getOrDefault(pending.uuid, 0L) > now) continue;

            if (recentMessageNames(pending.name)) {
                serverSendsLeaveMessages = true;
                continue;
            }

            PlayerEntity remaining = mc.world.getPlayerByUuid(pending.uuid);
            if (remaining != null && !remaining.isRemoved()) {
                reportHidden(pending.uuid, pending.name, false, "Vanish Event: entity remained", "Hidden from tab, still nearby", 100);
                continue;
            }

            String reason = "Vanish Event: silent TAB disappearance";
            int score = serverSendsLeaveMessages ? 100 : 30;

            if (serverSendsLeaveMessages) {
                reportHidden(pending.uuid, pending.name, false, reason, "Left tab without a leave message", score);
                continue;
            }

            RegionProof proof = regionProof(pending.uuid, pending.name);
            if (proof == null) continue;

            signals.addLast(new Signal(SignalType.VANISH, pending.name, proof, reason, score, now));
            evaluateCritical();
        }
    }

    private static boolean tabDepartureReason(String reason) {
        if (reason == null) return false;
        String lower = reason.toLowerCase(Locale.ROOT);
        return lower.startsWith("vanish event:") && (lower.contains("tab") || lower.contains("entity remained") || lower.contains("no leave packet"));
    }

    private void cacheRecentMessage(String text) {
        if (text == null || text.isBlank()) return;
        long now = System.currentTimeMillis();
        recentMessages.addLast(new RecentMessage(text, now));
        while (!recentMessages.isEmpty() && !(now - recentMessages.peekFirst().atMs <= RECENT_MESSAGE_TTL_MS && recentMessages.size() <= 64)) {
            recentMessages.removeFirst();
        }
    }

    private boolean recentMessageNames(String name) {
        if (name == null || name.isBlank()) return false;
        long now = System.currentTimeMillis();
        for (RecentMessage message : recentMessages) {
            if (now - message.atMs > RECENT_MESSAGE_TTL_MS) continue;
            if (looksLikeLeaveMessage(message.text, name)) return true;
        }
        return false;
    }

    /**
     * The shard probe lists the players your region's backend hosts. Staff in it are on your shard; staff in it but
     * missing from the tab list are hidden there.
     */
    private void processCompletionProbe(List<String> current) {
        if (mc.getNetworkHandler() == null || mc.player == null || current.isEmpty()) return;

        Set<String> tabNames = new HashSet<>();
        for (PlayerListEntry info : mc.getNetworkHandler().getPlayerList()) {
            if (info == null || info.getProfile() == null || info.getProfile().name() == null) continue;
            tabNames.add(info.getProfile().name().toLowerCase(Locale.ROOT));
        }

        String self = mc.player.getName().getString();
        List<String> hidden = new ArrayList<>();

        for (String name : current) {
            if (!isKnownStaffName(name)
                || !isPlausiblePlayerName(name)
                || name.equalsIgnoreCase(self)
                || recentMessageNames(name)) continue;

            if (tabNames.contains(name.toLowerCase(Locale.ROOT))) {
                watchingYou(name, regionProof(null, name), StaffIntel.AlertKind.REGION,
                    StaffIntel.get().currentShardLabel());
                continue;
            }

            hidden.add(name);
        }

        if (!hidden.isEmpty()) StaffIntel.get().log("completion probe found staff off tab: %s", hidden);
        if (hidden.isEmpty() || hidden.size() > 3) return;

        for (String name : hidden) {
            reportHidden(knownUuid(name), name, false, "Vanish Event: hidden but targetable", "Hidden from tab, still on this server", 95);
        }
    }

    private static String departedPlayerName(Text component) {
        if (component == null || !(component.getContent() instanceof TranslatableTextContent translated)) return "";
        if (!"multiplayer.player.left".equals(translated.getKey())) return "";

        Object[] args = translated.getArgs();
        if (args.length == 0 || args[0] == null) return "";

        return args[0] instanceof Text name ? name.getString() : String.valueOf(args[0]);
    }

    private void reportHidden(UUID uuid, String name, boolean spectator, String reason, String detail, int score) {
        upsertDetection(detectionKey(uuid, name), name, reason, score, System.currentTimeMillis() + DETECTION_TTL_MS);

        HiddenSpell spell = hiddenSpells.get(spellKey(name));
        if (spell != null && spell.spectator == spectator) return;

        RegionProof proof = regionProof(uuid, name);
        if (proof == null && reason.contains("targetable")) {
            // /minecraft:msg completion proof means they are targetable on this shard
            proof = new RegionProof(uuid, -1);
        }

        hiddenSpells.put(spellKey(name), new HiddenSpell(uuid, name, spectator, detail, proof, tickCounter));
    }

    private void reviewSpells() {
        Iterator<HiddenSpell> it = hiddenSpells.values().iterator();

        while (it.hasNext()) {
            HiddenSpell spell = it.next();

            if (spell.spectator && spell.uuid != null && mc.getNetworkHandler().getPlayerListEntry(spell.uuid) == null) {
                it.remove();
                continue;
            }

            if (spell.watched || spell.sinceTick >= tickCounter) continue;

            RegionProof proof = regionProof(spell.uuid, spell.name);
            if (proof == null && !spell.told) proof = spell.proof;

            if (proof != null) {
                spell.told = true;
                spell.watched = true;
                watchingYou(spell.name, proof,
                    spell.spectator ? StaffIntel.AlertKind.SPECTATOR : StaffIntel.AlertKind.VANISHED,
                    spell.spectator ? "Spectator mode \u00B7 " + StaffIntel.get().currentShardLabel() : spell.detail + " \u00B7 " + StaffIntel.get().currentShardLabel());
                continue;
            }

            if (spell.told) continue;

            spell.told = true;
            notifyVanishEvent(spell.name, spell.spectator ? "switched to SPECTATOR" : "entered VANISH", spell.detail);
        }
    }

    private boolean endSpell(String name, boolean spectator, long settleMs) {
        HiddenSpell spell = hiddenSpells.get(spellKey(name));
        if (spell == null || spell.spectator != spectator || System.currentTimeMillis() - spell.sinceMs < settleMs) return false;

        hiddenSpells.remove(spellKey(name));
        detections.remove(detectionKey(spell.uuid, spell.name));

        if (spell.told) {
            notifyVanishEvent(spell.name, "is visible again", "");
        }
        return true;
    }

    /** Proof that the player is on your goliath: a loaded entity (with distance) or a shard-local game mode update. */
    private RegionProof regionProof(UUID uuid, String name) {
        if (uuid == null && name != null && mc.getNetworkHandler() != null) {
            PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(name);
            if (entry != null && entry.getProfile() != null) uuid = entry.getProfile().id();
        }
        if (uuid == null) return null;

        StaffIntel intel = StaffIntel.get();
        PlayerEntity body = intel.entity(uuid);
        if (body != null) return new RegionProof(uuid, Math.round(body.distanceTo(mc.player)));

        return intel.isOnYourShard(uuid) ? new RegionProof(uuid, -1) : null;
    }

    private void watchingYou(String name, RegionProof proof, StaffIntel.AlertKind kind, String subtitle) {
        if (proof != null && proof.distance() >= 0) kind = StaffIntel.AlertKind.NEARBY;
        if (!StaffIntel.get().tryAlert(name, kind)) return;

        long now = System.currentTimeMillis();
        lastCriticalMs = now;
        criticalUntilMs = now + 10_000L;
        criticalWatcher = name;

        UUID id = proof != null ? proof.id() : knownUuid(name);
        StaffWatchingEffect.Level level = StaffWatchingEffect.Level.of(kind);
        String title = switch (level) {
            case REGION -> name + " is in your region";
            case HIDDEN -> name + " is hidden in your region";
            case WATCHING -> name + " is near you";
        };
        if (proof != null && proof.distance() >= 0) subtitle = proof.distance() + " blocks away \u00B7 " + subtitle;

        if (elderAnimation.get()) {
            StaffWatchingEffect.get().play(id, name, level, subtitle);
        } else if (notifications.get()) {
            EmberNotificationsHud.push(title, subtitle, level == StaffWatchingEffect.Level.REGION ? EmberNotificationsHud.WARN : EmberNotificationsHud.BAD);
        }
        if (chatNotify.get() && !EmberNotificationsHud.shouldInterceptChat()) {
            info("%s%s%s (%s)", Formatting.RED, title, Formatting.GRAY, subtitle);
        }
    }

    private void notifyVanishEvent(String name, String action, String detail) {
        String sub = detail == null || detail.isEmpty() ? action : action + " (" + detail + ")";
        StaffIntel.get().log("vanish event: %s %s", name, sub);
        if (notifications.get()) {
            EmberNotificationsHud.push("Staff: " + name, sub, EmberNotificationsHud.WARN);
        }
        if (chatNotify.get() && !EmberNotificationsHud.shouldInterceptChat()) {
            info("%s%s%s %s", Formatting.AQUA, name, Formatting.GRAY, sub);
        }
    }

    /**
     * A game mode update without ADD_PLAYER only comes from the goliath you are on, so it proves the staff member is
     * on your shard (StaffIntel records the proof). Spectator gets its own, stronger alert.
     */
    private void announceGamemode(UUID uuid, String gameMode) {
        if (uuid == null || (mc.player != null && mc.player.getUuid().equals(uuid))) return;

        String name = knownName(uuid);
        if (name == null || !(staffIds.contains(uuid) || isKnownStaffName(name))) return;

        if (GameMode.SPECTATOR.asString().equalsIgnoreCase(gameMode)) {
            reportHidden(uuid, name, true, "Vanish Event: switched to spectator", "Spectator Mode", 100);
            watchingYou(name, new RegionProof(uuid, -1), StaffIntel.AlertKind.SPECTATOR, "Spectator mode \u00B7 " + StaffIntel.get().currentShardLabel());
        } else {
            endSpell(name, true, 0L);
            watchingYou(name, regionProof(uuid, name), StaffIntel.AlertKind.REGION, StaffIntel.get().currentShardLabel());
        }
    }

    private void scanInvisiblePlayers() {
        double rangeSq = SENSOR_RANGE * SENSOR_RANGE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == null || player == mc.player || !player.isInvisible()) continue;
            if (player.squaredDistanceTo(mc.player) > rangeSq) continue;
            if (!realPlayer(player)) continue;

            triggerSensor(SignalType.INVISIBLE, player, "Invisible Entity: metadata flag", 35, 5000L);
        }
    }

    private void inspectInvisibleEntity(int entityId) {
        Entity entity = mc.world.getEntityById(entityId);
        if (!(entity instanceof PlayerEntity player) || player == mc.player || !player.isInvisible()) return;
        if (player.squaredDistanceTo(mc.player) > SENSOR_RANGE * SENSOR_RANGE) return;
        if (!realPlayer(player)) return;

        triggerSensor(SignalType.INVISIBLE, player, "Invisible Entity: metadata flag", 35, 5000L);
    }

    private boolean realPlayer(PlayerEntity player) {
        String name = player.getGameProfile() != null ? player.getGameProfile().name() : player.getName().getString();
        return credibleSubject(player.getUuid(), name);
    }

    private void inspectPositionalSound(Observation observation) {
        if (!suspiciousSound(observation.detail)) return;

        Vec3d source = observation.position();
        long now = System.currentTimeMillis();

        if (!nearPlayer(source)
            || hasVisibleCause(source)
            || isExplosionRelated(source)
            || isPoweredMechanism(source, observation.detail)
            || now - lastLocalActionMs < 1000L
            || recentlySelfPlaced(source)
            || villagerToggledDoor(observation.detail, source)
            || (selfContainerActive() && isContainerSignal(observation.detail))) return;

        triggerSensor(SignalType.SOUND, locatedSubject(source), "Suspicious Sound: " + shortPath(observation.detail), 18, 3000L);
    }

    private void inspectEntitySound(Observation observation) {
        if (!suspiciousSound(observation.detail)) return;

        Entity entity = mc.world.getEntityById(observation.entityId);
        if (entity instanceof PlayerEntity player
            && player != mc.player
            && player.isInvisible()
            && player.squaredDistanceTo(mc.player) <= SENSOR_RANGE * SENSOR_RANGE
            && realPlayer(player)) {
            triggerSensor(SignalType.SOUND, player, "Suspicious Sound: invisible source", 20, 3000L);
        }
    }

    private void inspectParticle(Observation observation) {
        if (!suspiciousParticle(observation.detail)) return;

        Vec3d source = observation.position();
        long now = System.currentTimeMillis();

        if (!nearPlayer(source)
            || hasVisibleCause(source)
            || isExplosionRelated(source)
            || now - lastLocalActionMs < 900L
            || hasAmbientParticleSource(source, observation.detail)) return;

        String particle = shortPath(observation.detail);
        if ((particle.equals("block") || particle.contains("smoke")) && nearSelf(source, 6.25)) return;
        if ((particle.equals("block") || particle.contains("smoke")) && !particleBurstReady(particle, now)) return;

        triggerSensor(SignalType.PARTICLE, locatedSubject(source), "Ghost Particle: " + particle, 18, 3000L);
    }

    private void triggerSensor(SignalType type, String where, String reason, int weight, long cooldownMs) {
        addSignal(type, where, null, reason, weight, cooldownMs);
    }

    private void triggerSensor(SignalType type, PlayerEntity player, String reason, int weight, long cooldownMs) {
        String name = player.getGameProfile() != null ? player.getGameProfile().name() : player.getName().getString();
        addSignal(type, name, regionProof(player.getUuid(), name), reason, weight, cooldownMs);
    }

    private void addSignal(SignalType type, String subject, RegionProof proof, String reason, int weight, long cooldownMs) {
        long now = System.currentTimeMillis();
        String cooldownKey = type.name() + "|" + subject.toLowerCase(Locale.ROOT);
        long last = signalCooldowns.getOrDefault(cooldownKey, 0L);
        if (now - last < cooldownMs) return;

        signalCooldowns.put(cooldownKey, now);
        signals.addLast(new Signal(type, subject, proof, reason, weight, now));
        upsertDetection("signal:" + cooldownKey, subject, reason, weight, now + DETECTION_TTL_MS);
        announceTrigger(cooldownKey, subject, reason);
        evaluateCritical();
    }

    private void evaluateCritical() {
        long now = System.currentTimeMillis();
        while (!signals.isEmpty() && now - signals.peekFirst().timeMs > SIGNAL_WINDOW_MS) signals.removeFirst();

        EnumMap<SignalType, Integer> strongest = new EnumMap<>(SignalType.class);
        Signal watcher = null;

        for (Signal signal : signals) {
            strongest.merge(signal.type, signal.weight, Math::max);
            if (signal.proof != null) watcher = signal;
        }

        int score = strongest.values().stream().mapToInt(Integer::intValue).sum();
        if (watcher == null || strongest.size() < 2 || score < CRITICAL_SCORE) return;
        if (now - lastCriticalMs < CRITICAL_COOLDOWN_MS) return;

        watchingYou(watcher.subject, watcher.proof, StaffIntel.AlertKind.VANISHED, watcher.reason);
    }

    private void announceTrigger(String eventKey, String subject, String reason) {
        long now = System.currentTimeMillis();
        Long last = announceCooldowns.get(eventKey);
        if (last != null && now - last < ANNOUNCE_COOLDOWN_MS) return;

        while (!announceTimes.isEmpty() && now - announceTimes.peekFirst() > ANNOUNCE_WINDOW_MS) announceTimes.removeFirst();
        if (announceTimes.size() >= MAX_ANNOUNCE_PER_WINDOW) return;

        announceCooldowns.put(eventKey, now);
        announceTimes.addLast(now);

        StaffIntel.get().log("sensor: %s (%s)", reason, subject);
        if (!sensorHints.get()) return;

        if (notifications.get()) {
            EmberNotificationsHud.push("AntiVanish Sensor", reason + " (" + subject + ")", EmberNotificationsHud.WARN);
        }
        if (chatNotify.get() && !EmberNotificationsHud.shouldInterceptChat()) {
            info("%s%s %s(%s)", Formatting.YELLOW, reason, Formatting.GRAY, subject);
        }
    }

    private void upsertDetection(String key, String name, String reason, int score, long expiresAt) {
        Detection existing = detections.get(key);
        if (existing == null) {
            detections.put(key, new Detection(name, reason, score, expiresAt));
            return;
        }
        existing.name = name;
        existing.expiresAt = Math.max(existing.expiresAt, expiresAt);
        if (score >= existing.score) {
            existing.reason = reason;
            existing.score = score;
        }
    }

    private void pruneState() {
        long now = System.currentTimeMillis();
        detections.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        signalCooldowns.entrySet().removeIf(entry -> now - entry.getValue() > 60000L);
        announceCooldowns.entrySet().removeIf(entry -> now - entry.getValue() > 60000L);
        selfPlacedBlocks.entrySet().removeIf(entry -> entry.getValue() <= now);
        confirmedDepartures.entrySet().removeIf(entry -> entry.getValue() <= now);
        automatedMechanisms.entrySet().removeIf(entry -> entry.getValue() <= now);

        weakParticleBursts.values().removeIf(burst -> {
            while (!burst.isEmpty() && now - burst.peekFirst() > 2000L) burst.removeFirst();
            return burst.isEmpty();
        });

        while (!recentExplosions.isEmpty() && now - recentExplosions.peekFirst().timeMs > 3000L) recentExplosions.removeFirst();
        while (!signals.isEmpty() && now - signals.peekFirst().timeMs > SIGNAL_WINDOW_MS) signals.removeFirst();
    }

    private boolean villagerToggledDoor(String blockId, Vec3d source) {
        String path = shortPath(blockId);
        boolean doorLike = (path.contains("door") && !path.contains("trapdoor")) || path.contains("fence_gate");
        if (!doorLike || source == null || mc.world == null) return false;

        for (Entity entity : mc.world.getEntities()) {
            if (entity.getType() == EntityType.VILLAGER && entity.squaredDistanceTo(source) <= 9.0) return true;
        }
        return false;
    }

    private boolean recentlySelfPlaced(Vec3d source) {
        if (source == null) return false;
        Long until = selfPlacedBlocks.get(BlockPos.ofFloored(source).asLong());
        return until != null && until > System.currentTimeMillis();
    }

    private void rememberExplosion(Observation observation) {
        long now = System.currentTimeMillis();
        recentExplosions.addLast(new ExplosionEvent(observation.position(), 8.0, now));
        while (recentExplosions.size() > 8) recentExplosions.removeFirst();
    }

    private boolean particleBurstReady(String particle, long now) {
        Deque<Long> burst = weakParticleBursts.computeIfAbsent(particle, ignored -> new ArrayDeque<>());
        burst.addLast(now);
        while (!burst.isEmpty() && now - burst.peekFirst() > 2000L) burst.removeFirst();
        if (burst.size() < 2) return false;
        burst.clear();
        return true;
    }

    private boolean nearPlayer(Vec3d source) {
        return source != null && mc.player != null && source.squaredDistanceTo(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ())) <= SENSOR_RANGE * SENSOR_RANGE;
    }

    private boolean nearSelf(Vec3d source, double maxDistSq) {
        return source != null && mc.player != null && source.squaredDistanceTo(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ())) < maxDistSq;
    }

    private boolean selfContainerActive() {
        return System.currentTimeMillis() - lastContainerActivityMs < CONTAINER_SELF_GRACE_MS;
    }

    private static boolean isContainerSignal(String id) {
        String path = shortPath(id);
        return path.contains("chest") || path.contains("barrel") || path.contains("shulker");
    }

    private String locatedSubject(Vec3d source) {
        if (source == null || mc.player == null) return "nearby";
        double dx = source.x - mc.player.getX();
        double dy = source.y - mc.player.getY();
        double dz = source.z - mc.player.getZ();
        long dist = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
        String ns = dz < -1.0 ? "N" : (dz > 1.0 ? "S" : "");
        String ew = dx > 1.0 ? "E" : (dx < -1.0 ? "W" : "");
        String dir = ns + ew;
        return dir.isEmpty() ? dist + "m" : dist + "m " + dir;
    }

    private boolean hasVisibleCause(Vec3d source) {
        if (source == null || mc.world == null) return true;
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player) continue;
            // Interaction reach is ~4.5 blocks from the eyes, so a visible player within 6 blocks can be the cause.
            boolean cause = entity instanceof PlayerEntity player
                ? !player.isInvisible() && entity.squaredDistanceTo(source) <= 36.0
                : entity instanceof ProjectileEntity && entity.squaredDistanceTo(source) <= 16.0;
            if (cause) return true;
        }
        return false;
    }

    private boolean isExplosionRelated(Vec3d source) {
        if (source == null) return false;
        long now = System.currentTimeMillis();
        while (!recentExplosions.isEmpty() && now - recentExplosions.peekFirst().timeMs > 3000L) recentExplosions.removeFirst();
        for (ExplosionEvent explosion : recentExplosions) {
            if (explosion.center.squaredDistanceTo(source) <= explosion.radius * explosion.radius) return true;
        }
        return false;
    }

    private boolean hasAmbientParticleSource(Vec3d source, String particleId) {
        if (source == null || mc.world == null || !shortPath(particleId).contains("smoke")) return false;
        BlockPos center = BlockPos.ofFloored(source);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    Identifier id = Registries.BLOCK.getId(mc.world.getBlockState(pos).getBlock());
                    String path = shortPath(id == null ? "" : id.toString());
                    if (path.contains("campfire") || path.contains("furnace") || path.contains("smoker")
                        || path.contains("torch") || path.contains("fire") || path.contains("candle")
                        || path.contains("respawn_anchor")) return true;
                }
            }
        }
        return false;
    }

    private boolean isPoweredMechanism(Vec3d source, String soundId) {
        String path = shortPath(soundId);
        if (!path.contains("door") && !path.contains("trapdoor")) return false;
        BlockPos center = BlockPos.ofFloored(source);
        long now = System.currentTimeMillis();
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos pos = center.add(0, dy, 0);
            BlockState state = mc.world.getBlockState(pos);
            long key = pos.asLong();
            boolean powered = state.contains(Properties.POWERED) && Boolean.TRUE.equals(state.get(Properties.POWERED));
            if (powered || mc.world.isReceivingRedstonePower(pos)) {
                automatedMechanisms.put(key, now + 5000L);
                return true;
            }
            if (automatedMechanisms.getOrDefault(key, 0L) > now) return true;
        }
        return false;
    }

    private static boolean suspiciousSound(String id) {
        String path = shortPath(id);
        return path.contains("chest.open")
            || path.contains("chest.close")
            || path.contains("chest.locked")
            || path.contains("barrel.open")
            || path.contains("barrel.close")
            || path.contains("shulker_box.open")
            || path.contains("shulker_box.close")
            || path.contains("door.open")
            || path.contains("door.close")
            || path.contains("fence_gate.open")
            || path.contains("fence_gate.close")
            || path.contains("button.click")
            || path.contains("lever.click");
    }

    private static boolean suspiciousParticle(String id) {
        String path = shortPath(id);
        return path.contains("crit")
            || path.contains("enchanted_hit")
            || path.contains("damage_indicator")
            || path.contains("smoke")
            || path.equals("block");
    }

    private static String shortPath(String id) {
        if (id == null) return "unknown";
        int split = id.indexOf(':');
        return (split >= 0 && split + 1 < id.length() ? id.substring(split + 1) : id).toLowerCase(Locale.ROOT);
    }

    private static boolean detectionWorthShowing(Detection detection) {
        if (detection == null) return false;
        String name = detection.name == null ? "" : detection.name.trim();
        return !name.isBlank() && !"Unknown".equalsIgnoreCase(name) && !"You".equalsIgnoreCase(name) && !"CRITICAL".equalsIgnoreCase(name);
    }

    private static String spellKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String detectionKey(UUID uuid, String name) {
        return uuid != null ? uuid.toString() : name;
    }

    private static boolean isPlausiblePlayerName(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        int len = trimmed.length();
        return len >= 3 && len <= 16 && trimmed.indexOf(' ') < 0;
    }

    private static boolean containsPlayerName(String displayedName, String playerName) {
        if (displayedName == null || playerName == null || playerName.isBlank()) return false;
        String rawDisplay = displayedName.replaceAll("(?i)\\u00a7[0-9A-FK-ORX]", "").toLowerCase(Locale.ROOT);
        String rawPlayer = playerName.trim().toLowerCase(Locale.ROOT);
        return rawDisplay.contains(rawPlayer);
    }

    private static boolean looksLikeLeaveMessage(String message, String playerName) {
        if (!containsPlayerName(message, playerName)) return false;
        String lower = message.replaceAll("(?i)\\u00a7[0-9A-FK-ORX]", "").toLowerCase(Locale.ROOT);
        return lower.contains("left the game") || lower.contains("has left") || lower.contains("disconnected") || lower.contains("logged out");
    }

    private void resetRuntime() {
        observations.clear();
        knownPlayers.clear();
        detections.clear();
        signalCooldowns.clear();
        announceCooldowns.clear();
        announceTimes.clear();
        selfPlacedBlocks.clear();
        confirmedDepartures.clear();
        automatedMechanisms.clear();
        weakParticleBursts.clear();
        signals.clear();
        recentExplosions.clear();
        pendingVanishes.clear();
        hiddenSpells.clear();
        recentMessages.clear();
        listedSinceMs.clear();
        staffIds.clear();

        lastLocalActionMs = 0L;
        serverSendsLeaveMessages = false;
        tickCounter = 0;
        lastContainerActivityMs = 0L;
        lastCriticalMs = 0L;
        criticalUntilMs = 0L;
        criticalWatcher = "";
    }

    private record Observation(ObservationType type, UUID profileId, int entityId, double x, double y, double z, String detail) {
        static Observation tabRemove(UUID id) {
            return new Observation(ObservationType.TAB_REMOVE, id, -1, 0.0, 0.0, 0.0, "");
        }
        static Observation tabHide(UUID id) {
            return new Observation(ObservationType.TAB_HIDE, id, -1, 0.0, 0.0, 0.0, "");
        }
        static Observation gamemode(UUID id, String mode) {
            return new Observation(ObservationType.GAMEMODE, id, -1, 0.0, 0.0, 0.0, mode);
        }
        static Observation playerLeft(String name) {
            return new Observation(ObservationType.PLAYER_LEFT, null, -1, 0.0, 0.0, 0.0, name);
        }
        static Observation systemChat(String text) {
            return new Observation(ObservationType.SYSTEM_CHAT, null, -1, 0.0, 0.0, 0.0, text);
        }
        static Observation entityMetadata(int id) {
            return new Observation(ObservationType.ENTITY_METADATA, null, id, 0.0, 0.0, 0.0, "");
        }
        static Observation explosion(Vec3d center, float radius) {
            return new Observation(ObservationType.EXPLOSION, null, -1, center.x, center.y, center.z, Float.toString(radius));
        }
        static Observation entitySound(int id, String sound) {
            return new Observation(ObservationType.ENTITY_SOUND, null, id, 0.0, 0.0, 0.0, sound);
        }
        static Observation position(ObservationType type, double x, double y, double z, String detail) {
            return new Observation(type, null, -1, x, y, z, detail);
        }
        Vec3d position() {
            return new Vec3d(x, y, z);
        }
    }

    private enum ObservationType {
        TAB_REMOVE,
        TAB_HIDE,
        PLAYER_LEFT,
        SYSTEM_CHAT,
        ENTITY_METADATA,
        POSITIONAL_SOUND,
        ENTITY_SOUND,
        PARTICLE,
        EXPLOSION,
        GAMEMODE
    }

    private static final class Detection {
        String name;
        String reason;
        int score;
        long expiresAt;

        Detection(String name, String reason, int score, long expiresAt) {
            this.name = name;
            this.reason = reason;
            this.score = score;
            this.expiresAt = expiresAt;
        }
    }

    private record KnownPlayer(UUID uuid, String name) {}
    private record PendingVanish(UUID uuid, String name, int dueTick) {}
    private record RegionProof(UUID id, int distance) {}
    private record Signal(SignalType type, String subject, RegionProof proof, String reason, int weight, long timeMs) {}

    private enum SignalType {
        VANISH,
        INVISIBLE,
        PARTICLE,
        SOUND
    }

    private record RecentMessage(String text, long atMs) {}
    private record ExplosionEvent(Vec3d center, double radius, long timeMs) {}

    private static final class HiddenSpell {
        final UUID uuid;
        final String name;
        final boolean spectator;
        final String detail;
        final RegionProof proof;
        final int sinceTick;
        final long sinceMs = System.currentTimeMillis();
        boolean told;
        boolean watched;

        HiddenSpell(UUID uuid, String name, boolean spectator, String detail, RegionProof proof, int sinceTick) {
            this.uuid = uuid;
            this.name = name;
            this.spectator = spectator;
            this.detail = detail;
            this.proof = proof;
            this.sinceTick = sinceTick;
        }
    }
}
