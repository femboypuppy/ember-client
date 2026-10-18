package meteordevelopment.meteorclient.systems.modules.donut;

import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * DonutSMP hides the deepslate from players above it. Such a chunk arrives with every section from the bottom of
 * the world up to Y 15 filled with plain deepslate (the bottom one keeps its bedrock floor): no caves, ores, lava or
 * bases. The sus chunk finder's diagnostics showed it on 2026-10-18; real terrain never has three uniform sections
 * below Y 0, let alone one at Y 0 to 15. Relogging while below Y 0 gets the real blocks.
 */
public final class DeepslateHiding {
    private DeepslateHiding() {
    }

    /** Empty, or one block throughout. Reads the palette, not the blocks, so it is cheap. */
    public static boolean isBlank(ChunkSection section) {
        if (section == null || section.isEmpty()) return true;
        BlockState first = section.getBlockState(0, 0, 0);
        return !section.hasAny(state -> state != first);
    }

    /**
     * @return the lowest Y whose blocks are real: 16 when the chunk was sent hidden including its Y 0 section, 0 when
     * only the part below Y 0 was hidden, or the bottom of the world when nothing was
     */
    public static int realFloor(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        int minY = chunk.getBottomY();

        int blanks = 0;
        for (int i = 0; i < sections.length && minY + (i << 4) + 15 < 0; i++) {
            if (isBlank(sections[i])) blanks++;
        }
        if (blanks < 3) return minY;

        int zero = (0 - minY) >> 4;
        return zero >= 0 && zero < sections.length && isBlank(sections[zero]) ? 16 : 0;
    }
}
