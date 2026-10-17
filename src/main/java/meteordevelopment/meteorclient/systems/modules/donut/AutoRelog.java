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
        if (relogged) {
            relogged = false;
            EmberNotificationsHud.push("Auto relog", "Rejoined below the deepslate", EmberNotificationsHud.GOOD);
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

        // Right after a join the player sits at a placeholder position until the server sends the real
        // one; acting on it re-armed and fired again in testing, so the first seconds are ignored.
        if (joinGrace > 0) {
            // Joined below the deepslate: the server already sent everything down here.
            if (--joinGrace == 0 && mc.player.getY() < triggerY.get()) armed = false;
            return;
        }

        double y = mc.player.getY();
        aboveTicks = y >= triggerY.get() + REARM_MARGIN ? aboveTicks + 1 : 0;
        if (aboveTicks >= REARM_TICKS) armed = true;
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

        armed = false;
        relogged = true;
        attempts = 0;
        // Auto Reconnect, when on, rejoins from the disconnect screen itself; doing it here too would race it.
        if (!Modules.get().isActive(AutoReconnect.class)) reconnectAt = System.currentTimeMillis() + (long) (delay.get() * 1000);
        mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(Text.literal("[Auto relog] Relogging to load below the deepslate")));
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
        return armed ? null : "used";
    }
}
