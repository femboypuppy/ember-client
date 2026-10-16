package meteordevelopment.meteorclient.systems.modules.donut;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DonutSMP overworld shard ("goliath") layout. Each goliath is its own backend server, so it is the
 * unit that matters for "is this staff member on my server": the 9x9 {@link RegionMap} cells are
 * coarser and several goliaths share one cell.
 * <p>
 * The world is a 36x36 grid of 12,500 block cells from -225k to +225k; every goliath is a rectangle of
 * those cells. Layout taken from the Glazed addon (realnnpg/glazed, GoliathMap).
 */
public final class DonutShards {
    public static final int GRID = 36;
    public static final double CELL_BLOCKS = 12500.0;
    public static final double MAP_OFFSET = 225000.0;
    public static final int OFF_MAP = -1;

    public record Shard(String region, int number, int col, int row, int width, int height) {
        public String label() {
            return region + " " + number;
        }
    }

    private static final Map<String, int[][]> REGIONS = new LinkedHashMap<>();
    private static final int[][] SHARD_IDS = new int[GRID][GRID];
    private static final List<Shard> SHARDS = new ArrayList<>();

    static {
        REGIONS.put("NA West", new int[][]{
            {4, 0, 2, 4}, {6, 0, 2, 4}, {8, 0, 2, 4}, {10, 0, 2, 4}, {12, 0, 2, 4},
            {14, 0, 2, 4}, {4, 4, 2, 4}, {6, 4, 2, 4}, {8, 4, 2, 4}, {10, 4, 2, 4},
            {12, 4, 2, 4}, {14, 4, 2, 4}, {4, 8, 2, 4}, {6, 8, 2, 4}, {8, 8, 2, 4},
            {10, 8, 2, 4}, {12, 8, 2, 4}, {14, 8, 2, 4}, {12, 12, 2, 4}, {14, 12, 2, 4},
        });
        REGIONS.put("NA East", new int[][]{
            {16, 0, 2, 4}, {18, 0, 2, 4}, {20, 0, 2, 4}, {22, 0, 2, 4}, {24, 0, 4, 4},
            {28, 0, 2, 4}, {30, 0, 2, 4}, {32, 0, 2, 4}, {34, 0, 2, 4}, {16, 4, 2, 2},
            {18, 4, 2, 2}, {20, 4, 2, 2}, {22, 4, 2, 2}, {24, 4, 2, 2}, {26, 4, 2, 4},
            {28, 4, 2, 2}, {30, 4, 2, 2}, {32, 4, 2, 4}, {34, 4, 2, 4}, {16, 6, 2, 2},
            {18, 6, 2, 2}, {20, 6, 2, 2}, {22, 6, 2, 2}, {24, 6, 2, 2}, {28, 6, 2, 2},
            {30, 6, 2, 2}, {16, 8, 2, 4}, {18, 8, 2, 4}, {20, 8, 4, 4}, {24, 8, 2, 4},
            {26, 8, 2, 4}, {28, 8, 2, 4}, {30, 8, 2, 4}, {32, 8, 2, 4}, {34, 8, 2, 4},
            {16, 12, 2, 4}, {18, 12, 2, 4}, {20, 12, 4, 4}, {24, 12, 2, 4}, {26, 12, 2, 4},
            {28, 12, 2, 4}, {30, 12, 2, 4}, {32, 12, 2, 4}, {34, 12, 2, 4}, {16, 16, 4, 4},
            {20, 16, 2, 4}, {22, 16, 2, 2}, {24, 16, 4, 4}, {28, 16, 2, 4}, {30, 16, 2, 4},
            {32, 16, 2, 4}, {34, 16, 2, 4}, {22, 18, 2, 2}, {32, 20, 2, 4}, {34, 20, 2, 4},
            {32, 24, 2, 4}, {34, 24, 2, 4},
        });
        REGIONS.put("Oceania", new int[][]{
            {0, 0, 2, 2}, {2, 0, 2, 4}, {0, 2, 2, 2}, {0, 4, 2, 2}, {2, 4, 2, 4},
            {0, 6, 2, 2}, {0, 8, 2, 2}, {2, 8, 2, 4}, {0, 10, 2, 2}, {0, 12, 2, 2},
            {2, 12, 2, 4}, {4, 12, 2, 4}, {6, 12, 2, 4}, {8, 12, 2, 4}, {10, 12, 2, 4},
            {0, 14, 2, 2},
        });
        REGIONS.put("Asia", new int[][]{
            {0, 16, 2, 4}, {2, 16, 2, 4}, {4, 16, 2, 4}, {6, 16, 2, 4}, {8, 16, 2, 2},
            {10, 16, 2, 2}, {12, 16, 2, 2}, {14, 16, 2, 2}, {8, 18, 2, 2}, {10, 18, 2, 2},
            {12, 18, 2, 2}, {14, 18, 2, 2}, {0, 20, 2, 4}, {2, 20, 2, 4}, {0, 24, 2, 4},
            {2, 24, 2, 4},
        });
        REGIONS.put("EU West", new int[][]{
            {4, 20, 2, 4}, {6, 20, 2, 4}, {8, 20, 2, 4}, {10, 20, 2, 4}, {4, 24, 2, 4},
            {6, 24, 2, 4}, {8, 24, 2, 4}, {10, 24, 2, 4}, {4, 28, 2, 4}, {6, 28, 2, 4},
            {4, 32, 2, 4}, {6, 32, 2, 2}, {8, 32, 2, 2}, {10, 32, 2, 2}, {12, 32, 2, 2},
            {14, 32, 2, 4}, {16, 32, 2, 4}, {18, 32, 2, 2}, {20, 32, 2, 4}, {22, 32, 2, 4},
            {24, 32, 2, 4}, {26, 32, 2, 4}, {6, 34, 2, 2}, {8, 34, 2, 2}, {10, 34, 2, 2},
            {12, 34, 2, 2}, {18, 34, 2, 2},
        });
        REGIONS.put("EU Central", new int[][]{
            {12, 20, 2, 4}, {14, 20, 2, 4}, {16, 20, 2, 4}, {18, 20, 2, 4}, {20, 20, 2, 4},
            {22, 20, 2, 2}, {26, 20, 2, 4}, {28, 20, 2, 4}, {30, 20, 2, 2}, {22, 22, 2, 2},
            {24, 22, 1, 2}, {25, 22, 1, 2}, {12, 24, 2, 4}, {14, 24, 2, 4}, {16, 24, 2, 4},
            {18, 24, 2, 4}, {20, 24, 2, 4}, {22, 24, 2, 4}, {24, 24, 2, 4}, {28, 24, 2, 2},
            {30, 24, 2, 4}, {26, 26, 2, 2}, {0, 28, 4, 1}, {8, 28, 2, 2}, {10, 28, 2, 4},
            {12, 28, 2, 2}, {14, 28, 2, 2}, {16, 28, 2, 2}, {18, 28, 2, 2}, {20, 28, 2, 2},
            {22, 28, 2, 2}, {24, 28, 2, 2}, {26, 28, 2, 4}, {28, 28, 2, 2}, {30, 28, 2, 2},
            {32, 28, 2, 2}, {0, 29, 4, 1}, {0, 30, 2, 2}, {2, 30, 2, 2}, {16, 30, 2, 2},
            {18, 30, 2, 2}, {22, 30, 2, 2}, {24, 30, 2, 2}, {32, 30, 2, 2}, {34, 30, 2, 2},
            {0, 32, 2, 2}, {28, 32, 2, 4}, {32, 32, 2, 4}, {0, 34, 4, 2}, {30, 34, 2, 2},
            {34, 34, 2, 2}, {26, 24, 2, 2},
        });
        REGIONS.put("Europe", new int[][]{
            {24, 20, 2, 2}, {30, 22, 2, 2}, {28, 26, 2, 2}, {34, 28, 2, 2}, {8, 30, 2, 2},
            {12, 30, 2, 2}, {14, 30, 2, 2}, {20, 30, 2, 2}, {28, 30, 2, 2}, {30, 30, 2, 2},
            {2, 32, 2, 2}, {30, 32, 2, 2}, {34, 32, 2, 2},
        });

        for (int[] row : SHARD_IDS) Arrays.fill(row, OFF_MAP);

        for (Map.Entry<String, int[][]> entry : REGIONS.entrySet()) {
            int[][] rects = entry.getValue().clone();
            Arrays.sort(rects, Comparator.<int[]>comparingInt(r -> r[1]).thenComparingInt(r -> r[0]));

            int number = 1;
            for (int[] rect : rects) {
                int id = SHARDS.size();
                SHARDS.add(new Shard(entry.getKey(), number++, rect[0], rect[1], rect[2], rect[3]));

                for (int r = rect[1]; r < rect[1] + rect[3]; r++) {
                    for (int c = rect[0]; c < rect[0] + rect[2]; c++) {
                        SHARD_IDS[r][c] = id;
                    }
                }
            }
        }
    }

    private DonutShards() {
    }

    /** Goliath id at the given overworld block position, or {@link #OFF_MAP}. */
    public static int shardAt(double x, double z) {
        int col = (int) Math.floor((x + MAP_OFFSET) / CELL_BLOCKS);
        int row = (int) Math.floor((z + MAP_OFFSET) / CELL_BLOCKS);
        if (col < 0 || row < 0 || col >= GRID || row >= GRID) return OFF_MAP;
        return SHARD_IDS[row][col];
    }

    public static String label(int shard) {
        if (shard < 0 || shard >= SHARDS.size()) return "spawn/off-map";
        return SHARDS.get(shard).label();
    }
}
