/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.network;

/**
 * Used to report every session to the upstream project's online-player counter. Ember reports
 * nothing; the calls are kept so callers don't change.
 */
public class OnlinePlayers {
    private OnlinePlayers() {
    }

    public static void update() {
    }

    public static void leave() {
    }
}
