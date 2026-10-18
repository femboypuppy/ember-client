/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.profiles;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.NbtCompound;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Profiles extends System<Profiles> implements Iterable<Profile> {
    public static final File FOLDER = new File(MeteorClient.FOLDER, "profiles");

    private List<Profile> profiles = new ArrayList<>();

    public Profiles() {
        super("profiles");
    }

    public static Profiles get() {
        return Systems.get(Profiles.class);
    }

    public void add(Profile profile) {
        if (!profiles.contains(profile)) profiles.add(profile);
        profile.save();
        save();
    }

    /** Register files already on disk. Never replace their contents with the current session. */
    public void registerExisting(Profile profile) {
        Profile previous=get(profile.name.get());
        if(previous!=null)profiles.set(profiles.indexOf(previous),profile);
        else profiles.add(profile);
        save();
    }

    @Override
    public void load(File folder) {
        FOLDER.mkdirs();
        super.load(folder);
        discover();
    }

    public void discover() {
        File[] folders = FOLDER.listFiles();
        if (folders == null) return;
        boolean changed = false;
        for (File folder : folders) {
            if (!folder.isDirectory() || folder.getName().startsWith(".") || get(folder.getName()) != null) continue;
            Profile p = describe(folder);
            if (p != null) { profiles.add(p); changed = true; }
        }
        if (changed) save();
    }

    public static Profile describe(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return null;
        Profile p = new Profile();
        p.name.set(folder.getName());
        boolean found = false;
        for (File file : files) {
            if (!file.isFile()) continue;
            switch (file.getName()) {
                case "appearance.nbt", "theme.nbt" -> { p.appearance.set(true); found = true; }
                case "hud.nbt" -> { p.hud.set(true); found = true; }
                case "modules.nbt" -> { p.modules.set(true); found = true; }
                case "macros.nbt" -> { p.macros.set(true); found = true; }
                default -> { if (file.getName().endsWith(".nbt") && !file.getName().equals("config.nbt")) { p.waypoints.set(true); found = true; } }
            }
        }
        return found ? p : null;
    }

    public void remove(Profile profile) {
        if (profiles.remove(profile)) profile.delete();
        save();
    }

    public Profile get(String name) {
        for (Profile profile : this) {
            if (profile.name.get().equalsIgnoreCase(name)) {
                return profile;
            }
        }

        return null;
    }

    public List<Profile> getAll() {
        return profiles;
    }

    @Override
    public File getFile() {
        return new File(FOLDER, "profiles.nbt");
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        for (Profile profile : this) {
            if (profile.loadOnJoin.get().contains(Utils.getWorldName())) {
                profile.load();
            }
        }
    }

    public boolean isEmpty() {
        return profiles.isEmpty();
    }

    @Override
    public @NotNull Iterator<Profile> iterator() {
        return profiles.iterator();
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.put("profiles", NbtUtils.listToTag(profiles));
        return tag;
    }

    @Override
    public Profiles fromTag(NbtCompound tag) {
        profiles = NbtUtils.listFromTag(tag.getListOrEmpty("profiles"), Profile::new);

        return this;
    }
}
