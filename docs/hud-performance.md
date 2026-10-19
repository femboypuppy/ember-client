# HUD font reuse in menus

Opening a WidgetScreen renders the HUD and a separate notification overlay. Previously, an atlas not visited by the second pass moved out of the active-font map into a four-entry idle cache. Layouts using more than four atlas sizes could repeatedly evict, rasterize, and upload fonts while the menu stayed open. Even an empty notification overlay performs text measurements.

The font cache now owns the atlases across passes; the per-pass map owns only the current text draw batch. Measurements and rendering use the same cached atlas, and an empty overlay does not invalidate HUD fonts or redraw the previous batch. Font changes close each cached texture once and discard pending old-font text.

The existing size ladder has 13 possible atlas heights, each using a 2048×2048 RED8 texture. The cache can retain that complete ladder (at most 52 MiB of atlas textures), expires unused entries after ten minutes, and is cleared when the font changes. Normal layouts load only the sizes they use. This avoids unbounded allocation while preserving text size and sharpness.

`python scripts/check_hud_fonts.py` exercises production cache and batching methods with lightweight font/GPU collaborators. It checks 1,000 HUD/menu pairs, notification-only batches, every atlas size, and font changes. Against the previous renderer, its six-size scenario recreates two atlases by the second frame. The fixed renderer retains the same six across all 1,000 pairs. This is a regression check, not an FPS benchmark.

To investigate a user's FPS drop, capture the client Render thread with `/sparkc profiler start --timeout 30` and keep the affected menu/HUD visible during the recording. A `/spark` profile containing only the integrated Server thread cannot identify rendering costs, frame caps, GPU stalls, or the FPS improvement from this change.
