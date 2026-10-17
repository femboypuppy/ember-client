# Chunk-loading performance

The 20-second Spark capture `C0gkEvAh8H` sampled roughly 19.86 seconds of render
thread time. Sus Chunk Finder's two `getInfoString()` branches accounted for
2.468 seconds (12.4%); its world rendering accounted for another 1.152 seconds
(5.8%). Those branches repeatedly iterated the full saved-find map, including
reason filtering, while holding the worker's result lock. The module-list HUD
called the count once for width and again for drawing each frame.

The count now comes from a spatial index maintained when results are added,
updated, cleared or loaded. Reading it takes one volatile reference read, with
no saved-find iteration, allocation or result lock. The HUD samples each
module's information once and uses the same value for measurement and drawing.

World rendering queries chunk-region buckets and reuses immutable candidates
until the player changes chunks, the configured range changes, or results
change. Geometry is submitted after releasing the result lock. Chunk planes
have a separate **Plane range** control, defaulting to 512 blocks; distant finds
remain in storage and in the total count. Existing dot and tracer ranges are
preserved, including tracers up to 100,000 blocks. Changing saved-find filters
rebuilds the derived index; clean rescans remove entries immediately. Switching
servers or dimensions clears the old index.

`ChunkFindIndexChecks` compares indexed queries with a brute-force oracle and
checks negative coordinates, saved/live replacements, clean removals, world
resets and long tracer ranges. It also times one million cached HUD reads with
200,000 indexed finds. These checks run in `./gradlew build`.

This removes the measured repeated work; it is not a measured end-to-end FPS
increase. The same profile also contains substantial Xaero map writing and
Sodium terrain setup/rendering. A fresh capture while loading similar terrain
is needed to quantify the remaining stutter with the optimized build.
