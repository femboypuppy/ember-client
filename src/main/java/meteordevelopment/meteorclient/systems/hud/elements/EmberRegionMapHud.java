package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.donut.RegionMap;
import meteordevelopment.meteorclient.systems.modules.donut.RegionMap.Datacenter;
import meteordevelopment.meteorclient.systems.modules.donut.RegionMap.RegionCell;
import meteordevelopment.meteorclient.utils.render.EmberStrip;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.screen.ChatScreen;
import org.lwjgl.glfw.GLFW;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Movable on-screen DonutSMP 9x9 Region Map HUD widget.
 */
public class EmberRegionMapHud extends HudElement {
    public static final HudElementInfo<EmberRegionMapHud> INFO = new HudElementInfo<>(
        Hud.GROUP,
        "ember-region-map",
        "DonutSMP 9x9 region map showing your current region, datacenter, and sector bounds.",
        EmberRegionMapHud::new
    );

    private static final Color SLATE_BG = new Color(17, 24, 30, 240);
    private static final Color SLATE_BORDER = new Color(56, 76, 92, 165);
    private static final Color HEADER_LABEL = new Color(146, 162, 178, 245);
    private static final Color AXIS_LABEL = new Color(142, 156, 172, 240);
    private static final Color WHITE = new Color(250, 252, 255, 255);
    private static final Color CELL_TEXT = new Color(248, 250, 252, 250);
    private static final Color LEGEND_TEXT = new Color(216, 224, 234, 248);
    private static final Color COORD_TEXT = new Color(238, 242, 248, 255);
    private static final Color BOUNDS_TEXT = new Color(138, 152, 168, 240);
    private static final Color TILE_SHADOW = new Color(8, 12, 16, 120);
    private static final Color TILE_HIGHLIGHT = new Color(255, 255, 255, 38);

    private static final Datacenter[][] LEGEND_ROWS = {
        { Datacenter.EU_CENTRAL, Datacenter.NA_WEST },
        { Datacenter.EU_WEST,    Datacenter.ASIA },
        { Datacenter.NA_EAST,    Datacenter.OCEANIA }
    };

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the Donut Region Map card.")
        .defaultValue(1.0)
        .min(0.5)
        .max(2.5)
        .sliderRange(0.5, 2.5)
        .build()
    );

    private final Setting<Boolean> showLegend = sgGeneral.add(new BoolSetting.Builder()
        .name("show-legend")
        .description("Show the 6 datacenter regions legend below the grid.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showCoords = sgGeneral.add(new BoolSetting.Builder()
        .name("show-coords")
        .description("Show current X and Z coordinates at the bottom.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showBounds = sgGeneral.add(new BoolSetting.Builder()
        .name("show-bounds")
        .description("Show the 50k x 50k coordinate range of the current region.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showActiveCellNumber = sgGeneral.add(new BoolSetting.Builder()
        .name("show-active-cell-number")
        .description("Display the region number inside the currently active cell.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showPlayerDot = sgGeneral.add(new BoolSetting.Builder()
        .name("show-player-dot")
        .description("Show a dot inside the active cell representing your exact position in the region.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> emberGlass = sgGeneral.add(new BoolSetting.Builder()
        .name("ember-glass-background")
        .description("Use Ember's glass/theme panel instead of the Donut slate background.")
        .defaultValue(false)
        .build()
    );

    private boolean directDragging;
    private int dragLastX, dragLastY;
    private boolean wasLeftDown;

    public EmberRegionMapHud() {
        super(INFO);
    }

    @Override
    public void toggle() {
        super.toggle();
        RegionMap mod = Modules.get() != null ? Modules.get().get(RegionMap.class) : null;
        // While the module is the one toggling us, toggling it back would undo its own change.
        if (mod != null && !mod.isSyncingHud() && mod.isActive() != isActive()) {
            mod.toggle();
        }
    }

    @Override
    public void render(HudRenderer renderer) {
        RegionMap mod = Modules.get() != null ? Modules.get().get(RegionMap.class) : null;
        boolean moduleOn = mod != null && mod.isActive();

        if (!isInEditor() && !moduleOn) {
            setSize(0, 0);
            return;
        }

        handleDirectChatDrag();

        double modScale = mod != null ? mod.scale.get() : 1.0;
        double s = scale.get() * modScale;

        boolean drawLegend = showLegend.get() && (mod == null || mod.showLegend.get());
        boolean drawCoords = showCoords.get() && (mod == null || mod.showCoords.get());
        boolean drawBounds = showBounds.get() && (mod == null || mod.showBounds.get());
        boolean drawActiveNum = showActiveCellNumber.get() || (mod != null && mod.showActiveCellNumber.get());
        boolean drawDot = showPlayerDot.get() || (mod != null && mod.showPlayerDot.get());

        int playerX = -172_530;
        int playerZ = -224_670;
        if (mc.player != null) {
            playerX = mod != null ? mod.getEffectiveX() : (int) Math.floor(mc.player.getX());
            playerZ = mod != null ? mod.getEffectiveZ() : (int) Math.floor(mc.player.getZ());
        }

        RegionCell activeCell = RegionMap.getRegionAt(playerX, playerZ);
        boolean inBounds = RegionMap.isWithinBounds(playerX, playerZ);

        // Layout metrics
        double padX = 11.0 * s;
        double padY = 10.0 * s;
        double axisLeftW = 11.5 * s;
        double cellSize = 20.5 * s;
        double cellGap = 2.4 * s;
        double cellRadius = 3.3 * s;

        double gridW = RegionMap.GRID_SIZE * cellSize + (RegionMap.GRID_SIZE - 1) * cellGap;
        double gridH = gridW;

        double headerTitleScale = 0.76 * s;
        double headerNumScale = 1.15 * s;
        double headerDcScale = 0.98 * s;
        double axisScale = 0.74 * s;
        double cellNumScale2 = 0.72 * s;
        double cellNumScale3 = 0.65 * s;
        double legendScale = 0.80 * s;
        double coordScale = 0.88 * s;
        double boundsScale = 0.74 * s;

        double headerH = Math.max(renderer.textHeight(true, headerNumScale), renderer.textHeight(true, headerDcScale)) + 6.0 * s;
        double axisBottomH = renderer.textHeight(true, axisScale) + 6.0 * s;

        double legendRowH = renderer.textHeight(true, legendScale) + 5.0 * s;
        double legendBlockH = drawLegend ? (3 * legendRowH + 6.0 * s) : 0;

        double coordH = drawCoords ? (renderer.textHeight(true, coordScale) + 3.5 * s) : 0;
        double boundsH = drawBounds ? (renderer.textHeight(true, boundsScale) + 2.0 * s) : 0;
        double footerBlockH = (drawCoords || drawBounds) ? (4.0 * s + coordH + boundsH) : 0;

        double w = padX * 2 + axisLeftW + gridW;
        double h = padY * 2 + headerH + gridH + axisBottomH + legendBlockH + footerBlockH;
        double radius = 8.5 * s;

        setSize(w, h);

        // Card shadow & background
        renderer.dropShadow(x, y, w, h, radius, 18 * s);
        if (emberGlass.get()) {
            EmberStrip.panel(renderer, x, y, w, h, radius, 1f);
        } else {
            renderer.roundedQuad(x - 1 * s, y - 1 * s, w + 2 * s, h + 2 * s, radius + 1 * s, SLATE_BORDER);
            renderer.roundedQuad(x, y, w, h, radius, SLATE_BG);
        }

        double cy = y + padY;

        // 1. Header: "DONUT REGIONS" on left, "<id> <Datacenter>" on right
        String title = "DONUT REGIONS";
        double titleH = renderer.textHeight(true, headerTitleScale);
        double headerTextVisualH = renderer.textHeight(true, headerNumScale);
        renderer.text(title, x + padX, cy + (headerTextVisualH - titleH) * 0.5, HEADER_LABEL, true, headerTitleScale);

        if (activeCell != null) {
            String dcLabel = inBounds ? activeCell.datacenter().displayName : "Out of Bounds";
            String idLabel = String.valueOf(activeCell.id());
            Color dcColor = activeCell.datacenter().textColor;

            double dcW = renderer.textWidth(dcLabel, true, headerDcScale);
            double dcH = renderer.textHeight(true, headerDcScale);
            double idW = renderer.textWidth(idLabel, true, headerNumScale);

            double dcX = x + w - padX - dcW;
            double idX = dcX - 5.5 * s - idW;

            renderer.text(idLabel, idX, cy, WHITE, true, headerNumScale);
            renderer.text(dcLabel, dcX, cy + (headerTextVisualH - dcH) * 0.55, dcColor, true, headerDcScale);
        }

        cy += headerH;

        // 2. 9x9 Region Grid + Row labels (1..9)
        double gridOriginX = x + padX + axisLeftW;
        double gridOriginY = cy;
        double axisTextH = renderer.textHeight(true, axisScale);

        for (int r = 0; r < RegionMap.GRID_SIZE; r++) {
            double cellY = gridOriginY + r * (cellSize + cellGap);

            // Row number (1..9)
            String rowStr = String.valueOf(r + 1);
            double rowStrW = renderer.textWidth(rowStr, true, axisScale);
            renderer.text(
                rowStr,
                x + padX + (axisLeftW - 3.5 * s - rowStrW) * 0.5,
                cellY + (cellSize - axisTextH) * 0.5,
                AXIS_LABEL,
                true,
                axisScale
            );

            for (int c = 0; c < RegionMap.GRID_SIZE; c++) {
                double cellX = gridOriginX + c * (cellSize + cellGap);
                RegionCell cell = RegionMap.getCell(r, c);
                if (cell == null) continue;

                boolean isCurrent = activeCell != null && activeCell.row() == r && activeCell.col() == c && inBounds;
                Color tileCol = cell.datacenter().tileColor;

                if (isCurrent) {
                    // White outer border highlight around active cell (matches Image 1)
                    double rim = 1.6 * s;
                    renderer.softGlow(cellX, cellY, cellSize, cellSize, cellRadius, 4.5 * s,
                        new Color(tileCol.r, tileCol.g, tileCol.b, 150));
                    renderer.roundedQuad(
                        cellX - rim,
                        cellY - rim,
                        cellSize + rim * 2,
                        cellSize + rim * 2,
                        cellRadius + rim * 0.7,
                        WHITE
                    );
                    renderer.roundedQuad(cellX, cellY, cellSize, cellSize, cellRadius, tileCol);

                    if (drawDot) {
                        double relX = Math.max(0.12, Math.min(0.88, (double) (playerX - cell.minX()) / RegionMap.REGION_SIZE));
                        double relZ = Math.max(0.12, Math.min(0.88, (double) (playerZ - cell.minZ()) / RegionMap.REGION_SIZE));
                        double dotR = 2.2 * s;
                        double dx = cellX + relX * cellSize - dotR;
                        double dy = cellY + relZ * cellSize - dotR;
                        renderer.roundedQuad(dx - 0.8 * s, dy - 0.8 * s, (dotR + 0.8 * s) * 2, (dotR + 0.8 * s) * 2, dotR + 0.8 * s, TILE_SHADOW);
                        renderer.roundedQuad(dx, dy, dotR * 2, dotR * 2, dotR, WHITE);
                    }
                } else {
                    renderer.roundedQuad(cellX, cellY + 0.8 * s, cellSize, cellSize, cellRadius, TILE_SHADOW);
                    renderer.roundedQuad(cellX, cellY, cellSize, cellSize, cellRadius, tileCol);
                    renderer.roundedQuad(cellX + 0.8 * s, cellY + 0.6 * s, cellSize - 1.6 * s, 1.2 * s, 1.0 * s, TILE_HIGHLIGHT);
                }

                if (!isCurrent || drawActiveNum) {
                    String numStr = String.valueOf(cell.id());
                    double numScale = numStr.length() >= 3 ? cellNumScale3 : cellNumScale2;
                    double numW = renderer.textWidth(numStr, true, numScale);
                    double numH = renderer.textHeight(true, numScale);
                    renderer.text(
                        numStr,
                        cellX + (cellSize - numW) * 0.5,
                        cellY + (cellSize - numH) * 0.5,
                        CELL_TEXT,
                        true,
                        numScale
                    );
                }
            }
        }

        cy += gridH + 4.0 * s;

        // 3. Column letters (A..I)
        for (int c = 0; c < RegionMap.GRID_SIZE; c++) {
            double cellX = gridOriginX + c * (cellSize + cellGap);
            String colStr = String.valueOf((char) ('A' + c));
            double colW = renderer.textWidth(colStr, true, axisScale);
            renderer.text(
                colStr,
                cellX + (cellSize - colW) * 0.5,
                cy,
                AXIS_LABEL,
                true,
                axisScale
            );
        }

        cy += axisBottomH;

        // 4. Datacenter Legend (2 columns x 3 rows)
        if (drawLegend) {
            cy += 3.0 * s;
            double colWidth = (w - padX * 2) * 0.5;
            double dotSize = 6.2 * s;
            double legendTextH = renderer.textHeight(true, legendScale);

            for (int row = 0; row < LEGEND_ROWS.length; row++) {
                for (int col = 0; col < 2; col++) {
                    Datacenter dc = LEGEND_ROWS[row][col];
                    double lx = x + padX + col * colWidth;
                    double dotY = cy + (legendRowH - dotSize) * 0.5;
                    double textY = cy + (legendRowH - legendTextH) * 0.5;

                    renderer.roundedQuad(lx, dotY, dotSize, dotSize, dotSize * 0.5, dc.tileColor);

                    boolean isActiveDc = activeCell != null && activeCell.datacenter() == dc;
                    Color labelCol = isActiveDc ? WHITE : LEGEND_TEXT;
                    renderer.text(dc.displayName, lx + dotSize + 5.5 * s, textY, labelCol, true, legendScale);
                }
                cy += legendRowH;
            }
            cy += 3.0 * s;
        }

        // 5. Footer: Current Coordinates & Region Bounds
        if (drawCoords || drawBounds) {
            cy += 3.0 * s;
            if (drawCoords) {
                String xStr = "X: " + playerX;
                String zStr = "Z: " + playerZ;
                double xStrW = renderer.textWidth(xStr, true, coordScale);
                renderer.text(xStr, x + padX, cy, COORD_TEXT, true, coordScale);
                renderer.text(zStr, x + padX + xStrW + 11.0 * s, cy, COORD_TEXT, true, coordScale);
                cy += coordH;
            }
            if (drawBounds && activeCell != null) {
                String xRange = activeCell.xRangeLabel();
                String zRange = activeCell.zRangeLabel();
                double xRangeW = renderer.textWidth(xRange, true, boundsScale);
                renderer.text(xRange, x + padX, cy, BOUNDS_TEXT, true, boundsScale);
                renderer.text(zRange, x + padX + xRangeW + 9.0 * s, cy, BOUNDS_TEXT, true, boundsScale);
            }
        }
    }

    /** Allows dragging the Region Map widget directly when the chat screen is open. */
    private void handleDirectChatDrag() {
        if (mc == null || mc.getWindow() == null || !(mc.currentScreen instanceof ChatScreen)) {
            directDragging = false;
            wasLeftDown = false;
            return;
        }

        long handle = mc.getWindow().getHandle();
        boolean leftDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        double scaleFactor = mc.getWindow().getScaleFactor();
        int mx = (int) (mc.mouse.getX() * scaleFactor);
        int my = (int) (mc.mouse.getY() * scaleFactor);

        if (leftDown && !wasLeftDown) {
            if (mx >= x && mx <= x + getWidth() && my >= y && my <= y + getHeight()) {
                directDragging = true;
                dragLastX = mx;
                dragLastY = my;
            }
        } else if (leftDown && directDragging) {
            int dx = mx - dragLastX;
            int dy = my - dragLastY;
            if (dx != 0 || dy != 0) {
                move(dx, dy);
                dragLastX = mx;
                dragLastY = my;
            }
        } else if (!leftDown) {
            directDragging = false;
        }

        wasLeftDown = leftDown;
    }
}
