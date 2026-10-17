package meteordevelopment.meteorclient.utils.misc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

/** The same spatial index used by Sus Chunk Finder, checked against a brute-force oracle. */
public final class ChunkFindIndexChecks {
    private static volatile long consumed;
    public static void run() {
        var index = new ChunkFindIndex<Long>();
        var expected = new HashMap<Long, Long>();
        Random random = new Random(49);
        for (int i = 0; i < 12000; i++) {
            int x = random.nextInt(800) - 400, z = random.nextInt(800) - 400;
            long key = ChunkFindIndex.key(x, z);
            expected.put(key, key); index.put(x, z, key);
        }
        for (int[] q : new int[][]{{0, 0, 24}, {-16, -17, 30}, {399, 399, 20}, {0, 0, 6252}}) {
            var brute = new HashSet<Long>();
            for (long key : expected.keySet()) if (Math.abs((long) (int) key - q[0]) <= q[2]
                && Math.abs((long) (int) (key >> 32) - q[1]) <= q[2]) brute.add(key);
            var actual = index.nearby(q[0], q[1], q[2]);
            require(new HashSet<>(actual).equals(brute), "Spatial results must match the full scan, including negative coordinates and long tracer ranges");
            require(index.nearby(q[0], q[1], q[2]) == actual, "An unchanged view must reuse its immutable cached candidates");
        }
        require(index.info().equals(Integer.toString(expected.size())), "HUD count must include all finds, not just nearby ones");
        var before = index.nearby(0, 0, 24);
        index.put(0, 0, -1L); expected.put(0L, -1L);
        require(index.nearby(0, 0, 24) != before && index.nearby(0, 0, 24).contains(-1L), "Replacing a saved find with a live scan must invalidate candidates without duplicating the count");
        require(index.size() == expected.size(), "Upserts must not inflate the count");
        index.remove(0);
        require(!index.nearby(0, 0, 24).contains(-1L), "A clean rescan must remove the visible find immediately");
        index.clear();
        require(index.info() == null && index.nearby(0, 0, 24).isEmpty(), "Server/world changes must clear counts and render candidates");

        for (int i = 0; i < 200000; i++) index.put(i - 100000, i % 5000 - 2500, (long) i);
        String count = index.info();
        long start = System.nanoTime(), sum = 0;
        for (int i = 0; i < 1000000; i++) { require(index.info() == count, "Repeated HUD reads must reuse the count string"); sum += index.info().length(); }
        consumed = sum;
        double ms = (System.nanoTime() - start) / 1e6;
        require(index.nearby(0, 0, 32).size() < 100, "Render candidates must not include the entire saved-find database");
        System.out.printf("Find index passed: counts, saved/live replacements, removal, negative coordinates, long tracers and query caching; 1M HUD reads across 200k finds in %.2f ms.%n", ms);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
