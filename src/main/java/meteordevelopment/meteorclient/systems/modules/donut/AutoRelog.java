/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.donut;

import it.unimi.dsi.fastutil.Pair;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.Locale;

/**
 * The relog method, automated. DonutSMP only sends what is below the deepslate to players who join
 * while already down there, so this leaves and rejoins once you dig below trigger-y. After that it
 * waits until you are back above before it can fire again, so a relog never loops.
 */
public class AutoRelog extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum Method {
        /** Leave and rejoin (needs a few minutes between joins). */
        Relog,
        /** /sethome here, /rtp away, /home back: arriving reloads the chunks. No disconnect, no join cooldown. */
        Home,
        /** Pearl straight up a shaft, /rtp away, the landing pearl brings you back (home as a backup). */
        Pearl
    }

    private final Setting<Method> method = sgGeneral.add(new EnumSetting.Builder<Method>()
        .name("method")
        .description("How to get the real blocks below the deepslate. Relog: leave and rejoin. Home: /sethome, /rtp, /home. Pearl: throw a pearl up the shaft you dug, /rtp, the pearl brings you back (needs 12+ blocks of air above you, else Home).")
        .defaultValue(Method.Relog)
        .build()
    );

    private final Setting<String> homeName = sgGeneral.add(new StringSetting.Builder()
        .name("home")
        .description("Which home Home and Pearl use (it's replaced each time): a number or a name. Pick one you don't need.")
        .defaultValue("3")
        .visible(() -> method.get() != Method.Relog)
        .build()
    );

    private final Setting<Integer> triggerY = sgGeneral.add(new IntSetting.Builder()
        .name("trigger-y")
        .description("Relog once your feet go below this height. 0 is where the deepslate starts.")
        .defaultValue(0)
        .sliderRange(-64, 64)
        .build()
    );

    private final Setting<Double> delay = sgGeneral.add(new DoubleSetting.Builder()
        .name("reconnect-delay")
        .description("Seconds to wait before rejoining. Too fast and the server can refuse it.")
        .defaultValue(3.0)
        .range(0.5, 30)
        .sliderRange(0.5, 10)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Integer> joinGap = sgGeneral.add(new IntSetting.Builder()
        .name("join-gap")
        .description("Seconds since your last join before it relogs. DonutSMP seems to send the real blocks only if you haven't joined in the last ~3 minutes: rejoins 1.4 and 2.4 minutes apart came back hidden, 3.7 and 9 minutes apart came back real.")
        .defaultValue(210)
        .range(0, 1800)
        .sliderRange(0, 600)
        .build()
    );

    private final Setting<Boolean> skipInCombat = sgGeneral.add(new BoolSetting.Builder()
        .name("skip-in-combat")
        .description("Don't relog if you took damage in the last 20 seconds: leaving in combat gets you killed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> donutOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("donutsmp-only")
        .description("Only relog on DonutSMP.")
        .defaultValue(true)
        .build()
    );

    private static final long COMBAT_MS = 20_000;
    private static final int REARM_MARGIN = 4;
    private static final int MAX_ATTEMPTS = 3;
    private static final int JOIN_GRACE_TICKS = 100;
    /** Above trigger-y + margin for this long (2 s) before it can fire again. */
    private static final int REARM_TICKS = 40;
    private int joinGrace, aboveTicks;

    /** Cleared by a relog; set again once you are back above trigger-y. Survives the rejoin on purpose. */
    private boolean armed = true;
    private long lastHurt;
    private long reconnectAt = -1;
    private int attempts;
    private boolean relogged;
    private long combatNoticeAt;

    public AutoRelog() {
        super(Categories.Donut, "auto-relog", "Leaves and rejoins once you dig below the deepslate, so the server sends what is down there.");
        // Keeps ticking while disconnected so it can rejoin, and keeps Meteor from re-running onActivate on
        // every join, which re-armed the relog and made it loop in testing.
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        armed = true;
        reconnectAt = -1;
        attempts = 0;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        reconnectAt = -1;
        attempts = 0;
        joinGrace = JOIN_GRACE_TICKS;
        aboveTicks = 0;
        long now = System.currentTimeMillis();
        sinceLastJoin = lastJoinAt == 0 ? -1 : now - lastJoinAt;
        lastJoinAt = now;
        checkAfterJoin = relogged;
        relogged = false;
        // A Home or Pearl run cut short by a disconnect: start over from scratch later.
        if (seq != null) {
            meteordevelopment.meteorclient.MeteorClient.LOG.info("[Ember AutoRelog] {} method interrupted by a rejoin", seq.how);
            seq = null;
        }
    }

    /** When you last joined (any join: relog, reconnect or shard switch), and the gap before this one (-1 unknown). */
    private static long lastJoinAt;
    private long sinceLastJoin = -1;
    /** This join was its relog: once the chunks are in, check the deep data really came. */
    private boolean checkAfterJoin;
    /** Relogs in a row that came back hidden, so a dig spot isn't relogged forever. */
    private int hiddenStreak;
    private long waitNoticeAt;

    /** Milliseconds until join-gap has passed since the last join; 0 when a relog would work now (or isn't a relog). */
    public long joinWait() {
        if (lastJoinAt == 0 || method.get() != Method.Relog) return 0;
        return Math.max(0, lastJoinAt + joinGap.get() * 1000L - System.currentTimeMillis());
    }

    /**
     * After a relog: how many of the loaded chunks around you arrived with real blocks below Y 0. Logged every time,
     * so the gap DonutSMP needs between joins can be read off the log; a hidden result re-arms for one more try.
     */
    private void checkDeepData() {
        checkAfterJoin = false;
        int real = 0, hidden = 0;
        int cx = mc.player.getBlockPos().getX() >> 4, cz = mc.player.getBlockPos().getZ() >> 4;
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int z = cz - 3; z <= cz + 3; z++) {
                var chunk = mc.world.getChunkManager().getWorldChunk(x, z);
                if (chunk == null) continue;
                if (DeepslateHiding.realFloor(chunk) == chunk.getBottomY()) real++;
                else hidden++;
            }
        }
        String gap = sinceLastJoin < 0 ? "unknown" : String.format(Locale.ROOT, "%.1f min", sinceLastJoin / 60_000.0);
        String how = seq != null ? seq.how + " method" : "Rejoined " + gap + " after the previous join";
        meteordevelopment.meteorclient.MeteorClient.LOG.info("[Ember AutoRelog] {}, at Y {}: deep data {} ({} of {} nearby chunks real)",
            how, (int) mc.player.getY(), real > hidden ? "REAL" : "HIDDEN", real, real + hidden);
        if (real + hidden == 0) return;
        if (real > hidden) {
            hiddenStreak = 0;
            EmberNotificationsHud.push("Auto relog", "Rejoined: the blocks below the deepslate are real", EmberNotificationsHud.GOOD);
        } else if (++hiddenStreak <= 2 && mc.player.getY() < triggerY.get()) {
            // Most likely rejoined too soon: try once more when join-gap has passed.
            armed = true;
            EmberNotificationsHud.push("Auto relog", method.get() == Method.Relog
                ? "Came back hidden (rejoined too soon?): trying again in " + (joinWait() / 1000) + "s"
                : "Came back hidden: trying once more", EmberNotificationsHud.WARN);
        } else {
            EmberNotificationsHud.push("Auto relog", "Still hidden after relogging", EmberNotificationsHud.WARN);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (reconnectAt >= 0) {
            tickReconnect();
            return;
        }
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        if (mc.player.hurtTime > 0) lastHurt = System.currentTimeMillis();

        if (seq != null) {
            tickSequence();
            return;
        }

        // Right after a join the player sits at a placeholder position until the server sends the real
        // one; acting on it re-armed and fired again in testing, so the first seconds are ignored.
        if (joinGrace > 0) {
            // Joined below the deepslate: the server already sent everything down here.
            if (--joinGrace == 0) {
                if (mc.player.getY() < triggerY.get()) armed = false;
                if (checkAfterJoin) checkDeepData();
            }
            return;
        }

        double y = mc.player.getY();
        aboveTicks = y >= triggerY.get() + REARM_MARGIN ? aboveTicks + 1 : 0;
        if (aboveTicks >= REARM_TICKS) {
            armed = true;
            hiddenStreak = 0;
        }
        if (!armed || y >= triggerY.get() || mc.world.getRegistryKey() != World.OVERWORLD) return;
        if (donutOnly.get() && !onDonut()) return;
        if (server() == null) return;

        if (skipInCombat.get() && System.currentTimeMillis() - lastHurt < COMBAT_MS) {
            // Stay armed and try again once the combat window passes; tell once, not every tick.
            if (System.currentTimeMillis() - combatNoticeAt > 10_000) {
                combatNoticeAt = System.currentTimeMillis();
                EmberNotificationsHud.push("Auto relog", "Waiting: you took damage recently", EmberNotificationsHud.WARN);
            }
            return;
        }

        if (method.get() != Method.Relog) {
            startSequence();
            return;
        }

        // Too soon after the last join and the server sends the deepslate hidden anyway: wait it out.
        long wait = joinWait();
        if (wait > 0) {
            if (System.currentTimeMillis() - waitNoticeAt > 60_000) {
                waitNoticeAt = System.currentTimeMillis();
                EmberNotificationsHud.push("Auto relog", "Relogging in " + (wait / 1000) + "s: rejoining sooner gets the deepslate hidden", EmberNotificationsHud.WARN);
            }
            return;
        }

        relog("Relogging to load below the deepslate");
    }

    /**
     * Relogs now on another module's behalf: the auto miner, when the deepslate ahead of it arrived hidden. Only
     * while this module is on, and never in combat.
     *
     * @return whether the relog started
     */
    public boolean relogNow(String why) {
        if (!isActive() || reconnectAt >= 0 || mc.player == null || mc.getNetworkHandler() == null || server() == null) return false;
        if (donutOnly.get() && !onDonut()) return false;
        if (skipInCombat.get() && System.currentTimeMillis() - lastHurt < COMBAT_MS) return false;
        if (seq != null) return true;
        if (method.get() != Method.Relog) {
            startSequence();
            return seq != null;
        }
        // Too soon after the last join: it would come back hidden. Callers wait on joinWait() first.
        if (joinWait() > 0) return false;
        relog(why);
        return true;
    }

    /** Whether the disconnect under way is this module's relog, so other modules know you'll be right back. */
    public boolean relogging() {
        return isActive() && relogged;
    }

    /** A Home or Pearl method under way: you're about to be, or are, somewhere else. Auto Miner pauses meanwhile. */
    public boolean busy() {
        return isActive() && seq != null;
    }

    // Home and Pearl methods

    private enum Phase { DELHOME, SETHOME, AIM, THROW, RTP, AWAY, HOME, BACK, CHECK }

    /** One run of the Home or Pearl method. */
    private static final class Sequence {
        final String how;
        final boolean pearl;
        final net.minecraft.util.math.Vec3d origin;
        Phase phase = Phase.DELHOME;
        int ticks, pearlSlot = -1;
        float pitch;
        boolean homeSent;
        long startedAt = System.currentTimeMillis();

        Sequence(String how, boolean pearl, net.minecraft.util.math.Vec3d origin) {
            this.how = how;
            this.pearl = pearl;
            this.origin = origin;
        }
    }

    private Sequence seq;

    private void startSequence() {
        armed = false;
        boolean pearl = method.get() == Method.Pearl;
        int slot = -1;
        if (pearl) {
            for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isOf(net.minecraft.item.Items.ENDER_PEARL)) slot = i;
            int air = airAbove();
            // A pearl that hits a ceiling a block up lands before /rtp, and /rtp then strands you: only up a shaft.
            if (slot < 0 || air < 12) {
                info("Pearl method needs %s: using the Home method this time.", slot < 0 ? "an ender pearl in your hotbar" : "12+ blocks of air above you (only " + air + ")");
                pearl = false;
            }
        }
        seq = new Sequence(pearl ? "Pearl" : "Home", pearl, mc.player.getEntityPos());
        seq.pearlSlot = slot;
        meteordevelopment.meteorclient.MeteorClient.LOG.info("[Ember AutoRelog] {} method from {} {} {}", seq.how, (int) seq.origin.x, (int) seq.origin.y, (int) seq.origin.z);
        EmberNotificationsHud.push("Auto relog", seq.how + " method: home " + homeName.get() + " set here, /rtp, then back", EmberNotificationsHud.WARN);
    }

    /** Air blocks straight above your head, up to 40. */
    private int airAbove() {
        net.minecraft.util.math.BlockPos head = mc.player.getBlockPos().up(2);
        int n = 0;
        while (n < 40 && mc.world.getBlockState(head.up(n)).isAir()) n++;
        return n;
    }

    private void command(String c) {
        meteordevelopment.meteorclient.MeteorClient.LOG.info("[Ember AutoRelog] /{}", c);
        mc.player.networkHandler.sendChatCommand(c);
    }

    private void endSequence(String why, boolean ok) {
        meteordevelopment.meteorclient.MeteorClient.LOG.info("[Ember AutoRelog] {} method {} after {}s: {}", seq.how, ok ? "done" : "stopped",
            (System.currentTimeMillis() - seq.startedAt) / 1000, why);
        if (!ok) EmberNotificationsHud.push("Auto relog", seq.how + " method: " + why, EmberNotificationsHud.WARN);
        seq = null;
    }

    private void tickSequence() {
        Sequence s = seq;
        s.ticks++;
        double away = mc.player.getEntityPos().distanceTo(s.origin);
        switch (s.phase) {
            case DELHOME -> {
                command("delhome " + homeName.get());
                next(Phase.SETHOME);
            }
            case SETHOME -> {
                if (s.ticks < 10) return;
                command("sethome " + homeName.get());
                next(s.pearl ? Phase.AIM : Phase.RTP);
            }
            case AIM -> {
                if (s.ticks < 10) return;
                s.pitch = mc.player.getPitch();
                mc.player.setPitch(-90);
                meteordevelopment.meteorclient.utils.player.InvUtils.swap(s.pearlSlot, true);
                next(Phase.THROW);
            }
            case THROW -> {
                // A few ticks so the server has the new look and slot before the throw.
                mc.player.setPitch(-90);
                if (s.ticks < 6) return;
                mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND);
                mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                meteordevelopment.meteorclient.utils.player.InvUtils.swapBack();
                mc.player.setPitch(s.pitch);
                next(Phase.RTP);
            }
            case RTP -> {
                if (s.ticks < (s.pearl ? 2 : 10)) return;
                command("rtp");
                next(Phase.AWAY);
            }
            case AWAY -> {
                if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> screen) {
                    // Don't guess in a menu: say what it was, so it can be handled.
                    meteordevelopment.meteorclient.MeteorClient.LOG.info("[Ember AutoRelog] /rtp opened \"{}\"", screen.getTitle().getString());
                    mc.player.closeHandledScreen();
                    endSequence("/rtp opened a menu (" + screen.getTitle().getString() + ")", false);
                    return;
                }
                if (away > 64) {
                    next(s.pearl ? Phase.BACK : Phase.HOME);
                } else if (s.ticks > 600) {
                    // Never left (an /rtp cooldown?): you're still where you were, nothing lost.
                    endSequence("/rtp didn't move you within 30 s (cooldown?)", false);
                }
            }
            case HOME -> {
                if (s.ticks < 40) return;
                command("home " + homeName.get());
                s.homeSent = true;
                next(Phase.BACK);
            }
            case BACK -> {
                if (away < 4) {
                    next(Phase.CHECK);
                } else if (s.pearl && !s.homeSent && s.ticks > 400) {
                    // The pearl didn't bring you back within 20 s: the home is the backup.
                    command("home " + homeName.get());
                    s.homeSent = true;
                    s.ticks = 0;
                } else if (s.ticks > 600) {
                    endSequence("didn't get back within 30 s: use /home " + homeName.get(), false);
                }
            }
            case CHECK -> {
                // The chunks around you come in again on arrival; give them three seconds.
                if (s.ticks < 60) return;
                checkDeepData();
                endSequence("back where you were", true);
            }
        }
    }

    private void next(Phase phase) {
        seq.phase = phase;
        seq.ticks = 0;
    }

    private void relog(String why) {
        armed = false;
        relogged = true;
        attempts = 0;
        // Auto Reconnect, when on, rejoins from the disconnect screen itself; doing it here too would race it.
        if (!Modules.get().isActive(AutoReconnect.class)) reconnectAt = System.currentTimeMillis() + (long) (delay.get() * 1000);
        mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(Text.literal("[Auto relog] " + why)));
    }

    private void tickReconnect() {
        if (mc.world != null) {
            reconnectAt = -1;
            return;
        }
        if (System.currentTimeMillis() < reconnectAt) return;
        // Only from the disconnect screen or a menu, never on top of a connection already under way.
        if (mc.currentScreen instanceof ConnectScreen) return;

        var server = server();
        if (server == null || attempts >= MAX_ATTEMPTS) {
            reconnectAt = -1;
            return;
        }
        attempts++;
        // If this attempt fails too, try again from the next disconnect screen.
        reconnectAt = System.currentTimeMillis() + (long) (delay.get() * 1000) + 2000;
        ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()), mc, server.left(), server.right(), false, null);
    }

    private boolean onDonut() {
        var server = server();
        return server != null && server.left().getAddress().toLowerCase(Locale.ROOT).contains("donutsmp");
    }

    /** The server you are on, as recorded by Auto Reconnect whether or not that module is on. */
    private static Pair<ServerAddress, ServerInfo> server() {
        return Modules.get().get(AutoReconnect.class).lastServerConnection;
    }

    @Override
    public String getInfoString() {
        long wait = joinWait();
        if (armed && wait > 0) return String.format(Locale.ROOT, "wait %d:%02d", wait / 60_000, wait / 1000 % 60);
        return armed ? null : "used";
    }
}
