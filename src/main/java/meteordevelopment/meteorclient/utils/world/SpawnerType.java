package meteordevelopment.meteorclient.utils.world;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;

/** Reads only the current spawn entry. Missing data is unknown, never an assumed pig. */
public final class SpawnerType {
    private SpawnerType() {}

    public static Identifier mobId(NbtCompound nbt) {
        if (nbt == null) return null;
        NbtCompound data = nbt.getCompoundOrEmpty("SpawnData");
        String id = data.getCompoundOrEmpty("entity").getString("id", "");
        if (id.isEmpty()) id = nbt.getCompoundOrEmpty("spawn_data").getCompoundOrEmpty("entity").getString("id", "");
        if (id.isEmpty()) id = data.getString("id", "");
        if (id.isEmpty()) id = nbt.getString("EntityId", "");
        return id.isEmpty() ? null : Identifier.tryParse(id);
    }
}
