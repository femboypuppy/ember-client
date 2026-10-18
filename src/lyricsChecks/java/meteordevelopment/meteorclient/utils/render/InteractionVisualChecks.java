package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.systems.profiles.ProfileArchive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public final class InteractionVisualChecks {
    public static void run() throws Exception {
        archive();
        var weather = new WeatherSimulation();
        weather.tick(
                0,
                0,
                0,
                0,
                WeatherSimulation.Kind.Leaves,
                1,
                16,
                0,
                0,
                1,
                true,
                (x, z) -> new WeatherSimulation.Surface(-2, false));
        var nearby = weather.particles()[0];
        nearby.active = true;
        nearby.x = .6;
        nearby.y = 1;
        nearby.z = 0;
        weather.disturb(0, 0, 0, .2, 0, .8);
        require(nearby.vx > 0, "Movement must push nearby airborne leaves away from the player");
        double velocity = nearby.vx;
        weather.disturb(0, 0, 0, .2, 0, 0);
        require(nearby.vx == velocity, "Zero movement strength must not disturb weather");
        int green = 0x73A33C, balanced = CelestialLighting.foliage(green, .85);
        require(
                CelestialLighting.foliage(green, 0) == green,
                "Zero foliage balance restores the exact biome color");
        require(
                CelestialLighting.foliage(0xDD7744, 1) == 0xDD7744,
                "Warm autumn palettes must remain unchanged");
        require(
                (balanced & 255) > (green & 255) * 1.7,
                "Blue texture energy must survive the green biome dye");
        require(
                (balanced >> 8 & 255) / (double) (balanced & 255) < 1.3,
                "Balanced foliage must accept blue and yellow illumination");
        var effects = new InteractionEffects();
        var mesh = new CelestialMesh(60000);
        for (var kind : InteractionEffects.Kind.values()) {
            effects.clear();
            effects.emit(kind, 0, 0, -4, .8, .7, 1);
            effects.tick(.05, true, true);
            effects.build(mesh, 0, 1, 0, .5, 0x6898E8);
            valid(mesh);
            require(mesh.size > 0, "Every interaction style must render");
            mesh.clear();
            for (int i = 0; i < 5; i++) effects.tick(.1, true, true);
            effects.build(mesh, 0, 1, 0, 0, 0x6898E8);
            require(
                    Arrays.stream(mesh.colors, 0, mesh.size).anyMatch(c -> (c >>> 24) > 0),
                    "Effects must remain visible during their fade");
            mesh.clear();
            for (int i = 0; i < 20; i++) effects.tick(.1, true, true);
            effects.build(mesh, 0, 1, 0, 0, 0x6898E8);
            require(mesh.size == 0, "Expired effects must disappear");
        }
        for (int i = 0; i < 10000; i++)
            effects.emit(InteractionEffects.Kind.Break, 0, 0, -4, 1, 1.5, 2);
        effects.tick(.05, true, true);
        effects.build(mesh, 0, 0, 0, .5, 0x6898E8);
        valid(mesh);
        require(
                Arrays.stream(effects.effects()).filter(e -> e.active).count() <= 128,
                "Burst storms must stay in the fixed pool");
        mesh.clear();
        effects.tick(.05, false, false);
        effects.build(mesh, 0, 0, 0, 0, 0x6898E8);
        require(mesh.size == 0, "Disabling effects clears them");
        int visible = 0, births = 0;
        boolean previous = false;
        int max = 0;
        for (int i = 0; i < 7200; i++) {
            mesh.clear();
            CelestialWorldGeometry.shootingStars(mesh, i / 60.0, 4, .75, .65, 0x6898E8);
            valid(mesh);
            boolean active = mesh.size > 0;
            if (active) {
                visible++;
                max = Math.max(max, mesh.size);
            }
            if (active && !previous) births++;
            previous = active;
        }
        require(
                births == 8,
                "Four meteors per minute must produce eight distinct events in two minutes");
        require(
                visible > 100 && visible < 1200,
                "Shooting stars should be occasional, rather than continuous");
        mesh.clear();
        CelestialWorldGeometry.shootingStars(mesh, 12, 4, 0, 1, 0x6898E8);
        require(mesh.size == 0, "Zero meteor brightness clears geometry");
        for (int i = 0; i < 500; i++) {
            mesh.clear();
            CelestialWorldGeometry.constellations(mesh, i * .1, 1, 1, 0x6898E8, 8);
            CelestialWorldGeometry.skyStars(mesh, i * .1, 1, 1, 0x6898E8);
            CelestialWorldGeometry.shootingStars(mesh, i * .1, 20, 1, 1, 0x6898E8);
            valid(mesh);
            require(
                    mesh.size < 60000,
                    "Maximum stars, constellations and meteors must fit the shared sky mesh");
        }
        System.out.println(
                "Interaction checks passed: portable archive round trips, malformed imports, pool"
                        + " limits, fades, disable cleanup and sparse bounded meteors (max "
                        + max
                        + " vertices).");
    }

    private static void archive() throws Exception {
        byte[] data =
                "A saved configuration, not the recipient's current settings"
                        .getBytes(StandardCharsets.UTF_8);
        var original =
                Map.of(
                        "modules.nbt",
                        data,
                        "appearance.nbt",
                        new byte[] {1, 2, 3},
                        "theme.nbt",
                        new byte[] {4, 5});
        byte[] encoded = ProfileArchive.encode("Blue evening", original);
        var decoded = ProfileArchive.decode(encoded);
        require(decoded.name().equals("Blue evening"), "Shared names must round trip");
        for (var entry : original.entrySet())
            require(
                    Arrays.equals(entry.getValue(), decoded.files().get(entry.getKey())),
                    "Every saved byte must survive sharing");
        for (String entry :
                List.of(
                        "../modules.nbt",
                        "/modules.nbt",
                        "..\\modules.nbt",
                        "accounts.nbt",
                        "config.nbt")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(data);
                zip.closeEntry();
            }
            reject(bytes.toByteArray());
        }
        reject(new byte[] {1, 2, 3});
        reject(new byte[ProfileArchive.LIMIT + 1]);
        require(
                !ProfileArchive.cleanName("../../Blue\\night").contains("."),
                "Config names cannot escape the profile folder");
    }

    private static void reject(byte[] bytes) throws Exception {
        try {
            ProfileArchive.decode(bytes);
            throw new AssertionError("Malformed config accepted");
        } catch (IOException expected) {
        }
    }

    private static void valid(CelestialMesh mesh) {
        require(
                mesh.size % 3 == 0 && mesh.size <= mesh.colors.length,
                "Bounded complete triangles");
        for (int i = 0; i < mesh.size * 3; i++)
            require(Float.isFinite(mesh.positions[i]), "Finite geometry even at the camera");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
