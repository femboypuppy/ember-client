package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.XAnchor;
import meteordevelopment.meteorclient.systems.hud.YAnchor;
import meteordevelopment.meteorclient.systems.hud.elements.EmberRegionMapHud;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.World;

/**
 * DonutSMP 9x9 Region Map module and region lookup data.
 * World bounds span -225,000 to +225,000 on X (Columns A..I) and Z (Rows 1..9),
 * with each region covering a 50,000 x 50,000 block sector.
 */
public class RegionMap extends Module {
    public static final int MIN_COORD = -225_000;
    public static final int MAX_COORD = 225_000;
    public static final int REGION_SIZE = 50_000;
    public static final int GRID_SIZE = 9;

    public enum Datacenter {
        EU_CENTRAL("EU Central", new Color(156, 206, 94, 255), new Color(175, 222, 114, 255)),
        EU_WEST("EU West", new Color(26, 178, 114, 255), new Color(46, 204, 136, 255)),
        NA_EAST("NA East", new Color(74, 168, 230, 255), new Color(104, 190, 245, 255)),
        NA_WEST("NA West", new Color(43, 112, 212, 255), new Color(82, 148, 242, 255)),
        ASIA("Asia", new Color(230, 186, 62, 255), new Color(245, 204, 88, 255)),
        OCEANIA("Oceania", new Color(228, 130, 33, 255), new Color(245, 152, 62, 255));

        public final String displayName;
        public final Color tileColor;
        public final Color textColor;

        Datacenter(String displayName, Color tileColor, Color textColor) {
            this.displayName = displayName;
            this.tileColor = tileColor;
            this.textColor = textColor;
        }
    }

    public record RegionCell(int row, int col, int id, Datacenter datacenter) {
        public char colLetter() {
            return (char) ('A' + col);
        }

        public int rowNumber() {
            return row + 1;
        }

        public int minX() {
            return MIN_COORD + col * REGION_SIZE;
        }

        public int maxX() {
            return minX() + REGION_SIZE;
        }

        public int minZ() {
            return MIN_COORD + row * REGION_SIZE;
        }

        public int maxZ() {
            return minZ() + REGION_SIZE;
        }

        public String xRangeLabel() {
            return formatK(minX()) + ".." + formatK(maxX());
        }

        public String zRangeLabel() {
            return formatK(minZ()) + ".." + formatK(maxZ());
        }

        private static String formatK(int coord) {
            return (coord / 1000) + "k";
        }
    }

    private static final int[][] REGION_IDS = {
        // A    B    C    D    E    F    G    H    I
        { 82, 100, 101, 102, 103, 104, 105, 106,  91 }, // Row 1 (-225k..-175k)
        { 83,  44,  75,  42,  41,  40,  39,  38,  92 }, // Row 2 (-175k..-125k)
        { 84,  45,  14,  13,  12,  11,  10,  37,  93 }, // Row 3 (-125k..-75k)
        { 85,  46,  74,   3,   2,   1,  25,  36,  94 }, // Row 4 (-75k..-25k)
        { 86,  47,  72,  71,   5,   4,  24,  35,  95 }, // Row 5 (-25k..25k)
        { 87,  51,  17,   9,   8,   7,  23,  34,  96 }, // Row 6 (25k..75k)
        { 88,  54,  18,  61,  62,  21,  22,  33,  97 }, // Row 7 (75k..125k)
        { 89,  26,  27,  28,  29,  30,  59,  32,  98 }, // Row 8 (125k..175k)
        { 90, 107, 108, 109, 110, 111, 112, 113,  99 }  // Row 9 (175k..225k)
    };

    private static final Datacenter OC = Datacenter.OCEANIA;
    private static final Datacenter AS = Datacenter.ASIA;
    private static final Datacenter NW = Datacenter.NA_WEST;
    private static final Datacenter NE = Datacenter.NA_EAST;
    private static final Datacenter EC = Datacenter.EU_CENTRAL;
    private static final Datacenter EW = Datacenter.EU_WEST;

    private static final Datacenter[][] REGION_DCS = {
        // A   B   C   D   E   F   G   H   I
        { OC, NW, NW, NW, NE, NE, NE, NE, NE }, // Row 1
        { OC, NW, NW, NW, NE, NE, NE, NE, NE }, // Row 2
        { OC, NW, NW, NW, NE, NE, NE, NE, NE }, // Row 3
        { OC, OC, OC, NW, NE, NE, NE, NE, NE }, // Row 4
        { AS, AS, AS, AS, NE, NE, NE, NE, NE }, // Row 5
        { AS, EW, EW, EC, EC, EC, EC, EC, NE }, // Row 6
        { AS, EW, EW, EC, EC, EC, EC, EC, EC }, // Row 7
        { EC, EW, EC, EC, EC, EC, EC, EC, EC }, // Row 8
        { EC, EW, EW, EW, EW, EW, EW, EW, EC }  // Row 9
    };

    private static final RegionCell[][] GRID = new RegionCell[GRID_SIZE][GRID_SIZE];

    static {
        for (int r = 0; r < GRID_SIZE; r++) {
            for (int c = 0; c < GRID_SIZE; c++) {
                GRID[r][c] = new RegionCell(r, c, REGION_IDS[r][c], REGION_DCS[r][c]);
            }
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the Donut Region Map HUD.")
        .defaultValue(1.0)
        .min(0.5)
        .max(2.5)
        .sliderRange(0.5, 2.5)
        .build()
    );

    public final Setting<Boolean> showLegend = sgGeneral.add(new BoolSetting.Builder()
        .name("show-legend")
        .description("Show the datacenter color legend below the 9x9 grid.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> showCoords = sgGeneral.add(new BoolSetting.Builder()
        .name("show-coords")
        .description("Display current X and Z coordinates at the bottom of the card.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> showBounds = sgGeneral.add(new BoolSetting.Builder()
        .name("show-bounds")
        .description("Display the 50k x 50k coordinate bounds of the current region.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> showActiveCellNumber = sgGeneral.add(new BoolSetting.Builder()
        .name("show-active-cell-number")
        .description("Keep the region number visible inside the currently highlighted cell.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> showPlayerDot = sgGeneral.add(new BoolSetting.Builder()
        .name("show-player-dot")
        .description("Show a subtle dot inside the active cell indicating your relative position in the region.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> netherScaling = sgGeneral.add(new BoolSetting.Builder()
        .name("nether-overworld-scale")
        .description("Multiply Nether coordinates by 8 when computing the Overworld region.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> notifyRegionChange = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-region-change")
        .description("Show a notification when you cross into a different Donut region.")
        .defaultValue(false)
        .build()
    );

    private int lastRegionId = -1;
    /** Set while this module is flipping the HUD element, so the element doesn't flip the module back. */
    private boolean syncingHud;

    public RegionMap() {
        super(Categories.Donut, "region-map", "Displays the DonutSMP 9x9 region map and your current region.");
    }

    /**
     * The HUD element follows real toggles only. Leaving a server calls onDeactivate() without
     * toggling (the module stays active so it resumes on the next join), and Module.toggle() runs
     * onDeactivate() while isActive() is still true, so neither callback can tell the two apart.
     */
    @Override
    public void toggle() {
        super.toggle();
        syncHud(isActive(), Utils.canUpdate());
    }

    @Override
    public void onActivate() {
        lastRegionId = -1;
        syncHud(true, true);
    }

    @Override
    public void onDeactivate() {
        lastRegionId = -1;
    }

    public boolean isSyncingHud() {
        return syncingHud;
    }

    private void syncHud(boolean shouldBeActive, boolean allowAdd) {
        if (syncingHud) return;
        syncingHud = true;
        try {
            ensureHudElement(shouldBeActive, allowAdd);
        } finally {
            syncingHud = false;
        }
    }

    /** @param allowAdd false while configs may still be loading, when adding could duplicate a saved element */
    private void ensureHudElement(boolean shouldBeActive, boolean allowAdd) {
        if (Hud.get() == null) return;
        EmberRegionMapHud found = null;
        for (HudElement el : Hud.get()) {
            if (el instanceof EmberRegionMapHud rm) {
                found = rm;
                break;
            }
        }
        if (found == null && shouldBeActive && allowAdd) {
            Hud.get().add(EmberRegionMapHud.INFO, 8, 224, XAnchor.Left, YAnchor.Top);
            for (HudElement el : Hud.get()) {
                if (el instanceof EmberRegionMapHud rm) {
                    found = rm;
                    break;
                }
            }
        }
        if (found != null && found.isActive() != shouldBeActive) {
            found.toggle();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        int x = getEffectiveX();
        int z = getEffectiveZ();
        RegionCell cell = getRegionAt(x, z);
        int currentId = cell != null ? cell.id() : -1;

        if (lastRegionId != -1 && currentId != -1 && currentId != lastRegionId && notifyRegionChange.get()) {
            info("Entered Region (highlight)%d(default) (%s)", cell.id(), cell.datacenter().displayName);
        }
        if (currentId != -1) {
            lastRegionId = currentId;
        }
    }

    public int getEffectiveX() {
        if (mc.player == null) return 0;
        double x = mc.player.getX();
        if (netherScaling.get() && mc.world != null && mc.world.getRegistryKey() == World.NETHER) {
            x *= 8.0;
        }
        return (int) Math.floor(x);
    }

    public int getEffectiveZ() {
        if (mc.player == null) return 0;
        double z = mc.player.getZ();
        if (netherScaling.get() && mc.world != null && mc.world.getRegistryKey() == World.NETHER) {
            z *= 8.0;
        }
        return (int) Math.floor(z);
    }

    public static RegionCell getCell(int row, int col) {
        if (row < 0 || row >= GRID_SIZE || col < 0 || col >= GRID_SIZE) return null;
        return GRID[row][col];
    }

    /**
     * Returns the DonutSMP RegionCell for the given X and Z coordinates, or the nearest clamped cell if slightly out of bounds.
     */
    public static RegionCell getRegionAt(int x, int z) {
        int col = Math.floorDiv(x - MIN_COORD, REGION_SIZE);
        int row = Math.floorDiv(z - MIN_COORD, REGION_SIZE);
        if (x == MAX_COORD) col = GRID_SIZE - 1;
        if (z == MAX_COORD) row = GRID_SIZE - 1;
        col = Math.max(0, Math.min(GRID_SIZE - 1, col));
        row = Math.max(0, Math.min(GRID_SIZE - 1, row));
        return GRID[row][col];
    }

    public static boolean isWithinBounds(int x, int z) {
        return x >= MIN_COORD && x <= MAX_COORD && z >= MIN_COORD && z <= MAX_COORD;
    }
}
