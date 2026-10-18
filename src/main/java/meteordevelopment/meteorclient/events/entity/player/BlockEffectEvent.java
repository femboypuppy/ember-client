package meteordevelopment.meteorclient.events.entity.player;

import net.minecraft.util.math.BlockPos;

/** A successful local block operation, after cancellable interaction handlers have run. */
public record BlockEffectEvent(BlockPos pos, boolean placed) {}
