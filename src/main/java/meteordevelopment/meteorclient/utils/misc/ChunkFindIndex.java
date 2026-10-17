package meteordevelopment.meteorclient.utils.misc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Chunk-keyed spatial buckets. Owner supplies synchronization; HUD count reads need no scan or lock. */
public final class ChunkFindIndex<T> {
    private final Map<Long, Map<Long, T>> regions = new HashMap<>();
    private int size;
    private long revision, queriedRevision = -1;
    private int queryX, queryZ, queryRadius;
    private List<T> query = List.of();
    private volatile String info;

    public void put(int x, int z, T value) {
        var bucket = regions.computeIfAbsent(key(x >> 4, z >> 4), ignored -> new HashMap<>());
        if (bucket.put(key(x, z), value) == null) { size++; info = Integer.toString(size); }
        revision++;
    }
    public void remove(long chunkKey) {
        int x = (int) chunkKey, z = (int) (chunkKey >> 32);
        long region = key(x >> 4, z >> 4);
        var bucket = regions.get(region);
        if (bucket == null || bucket.remove(chunkKey) == null) return;
        if (bucket.isEmpty()) regions.remove(region);
        size--; info = size == 0 ? null : Integer.toString(size); revision++;
    }
    public void clear() {
        regions.clear(); size = 0; info = null; revision++; query = List.of(); queriedRevision = -1;
    }
    public String info() { return info; }
    public int size() { return size; }
    public List<T> nearby(int x, int z, int radius) {
        radius = Math.clamp(radius, 0, 8192);
        if (queriedRevision == revision && queryX == x && queryZ == z && queryRadius == radius) return query;
        List<T> result = new ArrayList<>();
        int x0 = (x - radius) >> 4, x1 = (x + radius) >> 4, z0 = (z - radius) >> 4, z1 = (z + radius) >> 4;
        if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > regions.size()) {
            for (var region : regions.entrySet()) {
                int rx = (int) (long) region.getKey(), rz = (int) (region.getKey() >> 32);
                if (rx >= x0 && rx <= x1 && rz >= z0 && rz <= z1) collect(result, region.getValue(), x, z, radius);
            }
        } else for (int rx = x0; rx <= x1; rx++) for (int rz = z0; rz <= z1; rz++) {
            var bucket = regions.get(key(rx, rz));
            if (bucket != null) collect(result, bucket, x, z, radius);
        }
        queryX = x; queryZ = z; queryRadius = radius; queriedRevision = revision;
        return query = List.copyOf(result);
    }
    private void collect(List<T> out, Map<Long, T> bucket, int x, int z, int radius) {
        for (var entry : bucket.entrySet()) {
            int cx = (int) (long) entry.getKey(), cz = (int) (entry.getKey() >> 32);
            if (Math.abs((long) cx - x) <= radius && Math.abs((long) cz - z) <= radius) out.add(entry.getValue());
        }
    }
    public static long key(int x, int z) { return (x & 0xFFFFFFFFL) | (long) z << 32; }
}
