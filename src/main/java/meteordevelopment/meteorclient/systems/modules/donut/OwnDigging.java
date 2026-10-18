package meteordevelopment.meteorclient.systems.modules.donut;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/**
 * Blocks the auto miner dug, per world and dimension, so the tunnel finder doesn't report your own tunnels back to
 * you once the chunks load again. Saved beside the finds as {@code <world>_<dimension>.own-digging}.
 */
public final class OwnDigging {
    private static final int CAP = 2_000_000;

    private static final LongOpenHashSet dug = new LongOpenHashSet();
    private static String place = "";
    private static boolean dirty;

    private OwnDigging() {
    }

    public static synchronized void add(FindsStorage.Context ctx, BlockPos pos) {
        if (select(ctx) && dug.size() < CAP && dug.add(pos.asLong())) dirty = true;
    }

    /** Safe to call from scan threads. */
    public static synchronized boolean contains(FindsStorage.Context ctx, int x, int y, int z) {
        return select(ctx) && !dug.isEmpty() && dug.contains(BlockPos.asLong(x, y, z));
    }

    public static synchronized void save() {
        if (!dirty) return;
        dirty = false;

        File file = file(place);
        if (file == null) return;
        try {
            ByteBuffer buffer = ByteBuffer.allocate(dug.size() * Long.BYTES);
            for (long key : dug) buffer.putLong(key);
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), buffer.array());
        } catch (IOException e) {
            MeteorClient.LOG.warn("Couldn't save {}", file, e);
        }
    }

    private static boolean select(FindsStorage.Context ctx) {
        if (!ctx.valid()) return false;
        String next = ctx.world() + "|" + ctx.dimension();
        if (next.equals(place)) return true;

        save();
        place = next;
        dug.clear();

        File file = file(next);
        if (file != null && file.isFile()) {
            try {
                ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file.toPath()));
                while (buffer.remaining() >= Long.BYTES) dug.add(buffer.getLong());
            } catch (IOException e) {
                MeteorClient.LOG.warn("Couldn't read {}", file, e);
            }
        }
        return true;
    }

    private static File file(String place) {
        if (place.isEmpty() || place.startsWith("|")) return null;
        return new File(new File(MeteorClient.FOLDER, "ember-finds"), place.replaceAll("[^A-Za-z0-9._-]", "_") + ".own-digging");
    }
}
