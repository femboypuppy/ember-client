# Spawner ESP

Enable **Render > Spawner ESP** to highlight normal and trial spawners. Labels show the current mob type and distance, such as `Skeleton Spawner | 24m`. Settings control range, label scale/count, trial spawners, tracers, colors, and box shape.

The module uses loaded blocks, block entities, and chunk/block-entity updates received from the server. It does not predict hidden spawners. If the server omits the current mob entry, the label says `Unknown mob`; possible future spawn entries are not presented as the current mob.

Names are resolved from Minecraft's entity registry, including translated names and valid custom entity identifiers. Mob changes update cached labels. Block removal, chunk unloads, disabling the module, disconnects, and world changes clear the relevant markers.

Existing block entities are indexed immediately on activation. A bounded queue scans at most two loaded chunks per tick, rejecting sections without spawner blocks through their palettes. Rendering reads cached data; at most 256 nearby boxes and the configured number of nearest labels are submitted.

# Weather indicator

The rain observer supports both normal server start/stop packets and plugin weather expressed through intensity packets. Intensity-only rain no longer inherits a stale clear/rain flag, and a settled intensity does not stay on `Easing`. Repeated clear packets cannot create a fresh rain event or hold an old fade indefinitely. World changes discard previous server weather state.

Custom Ember rain remains an independent visual source. The indicator can describe local rain while server weather is clear. The ending notification describes an observed fade, not a predicted weather timer.
