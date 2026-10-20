package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.utils.world.SpawnerType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

/** Uses Minecraft's real NBT/Identifier classes; never boots a world or creates a mob entity. */
final class SpawnerTypeChecks {
    static void run() {
        require(SpawnerType.mobId(null) == null, "Missing packet data is unknown");
        require(SpawnerType.mobId(new NbtCompound()) == null, "Empty spawners must not be assumed to spawn pigs");
        for (String key : new String[]{"SpawnData", "spawn_data"}) {
            for (String mob : new String[]{"minecraft:skeleton", "minecraft:blaze", "minecraft:iron_golem", "custom:ice_mob"}) {
                NbtCompound tag = entry(key, mob);
                require(SpawnerType.mobId(tag).toString().equals(mob), "Normal/trial mob data: " + mob);
            }
        }
        NbtCompound legacy = new NbtCompound(); legacy.putString("EntityId", "minecraft:zombie");
        require(SpawnerType.mobId(legacy).toString().equals("minecraft:zombie"), "Legacy mob ids remain readable");
        require(SpawnerType.mobId(entry("SpawnData", "INVALID MOB!")) == null, "Malformed mob ids remain unknown");
        NbtCompound potentials = new NbtCompound();
        NbtList choices = new NbtList(); choices.add(entry("data", "minecraft:zombie")); choices.add(entry("data", "minecraft:skeleton"));
        potentials.put("SpawnPotentials", choices);
        require(SpawnerType.mobId(potentials) == null, "Spawn potentials are not proof of the currently selected mob");
        NbtCompound changed = entry("SpawnData", "minecraft:spider");
        require(SpawnerType.mobId(changed).toString().equals("minecraft:spider"), "A type change is read from fresh data");
        changed.put("SpawnData", entry("SpawnData", "minecraft:skeleton").getCompoundOrEmpty("SpawnData"));
        require(SpawnerType.mobId(changed).toString().equals("minecraft:skeleton"), "Later updates supersede earlier mob data");
        System.out.println("Spawner type checks passed: real NBT, normal/trial/legacy formats, mob updates, missing data, malformed ids and ambiguous potentials.");
    }

    private static NbtCompound entry(String key, String id) {
        NbtCompound root = new NbtCompound(), data = new NbtCompound(), entity = new NbtCompound();
        entity.putString("id", id); data.put("entity", entity); root.put(key, data);
        return root;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
