# HUD font reuse in menus

Opening a WidgetScreen renders the HUD and a separate notification overlay. Previously, an atlas not visited by the second pass moved out of the active-font map into a four-entry idle cache. Layouts using more than four atlas sizes could repeatedly evict, rasterize, and upload fonts while the menu stayed open. Even an empty notification overlay performs text measurements.

The font cache now owns the atlases across passes; the per-pass map owns only the current text draw batch. Measurements and rendering use the same cached atlas, and an empty overlay does not invalidate HUD fonts or redraw the previous batch. Font changes close each cached texture once and discard pending old-font text.

The existing size ladder has 13 possible atlas heights, each using a 2048×2048 RED8 texture. The cache can retain that complete ladder (at most 52 MiB of atlas textures), expires unused entries after ten minutes, and is cleared when the font changes. Normal layouts load only the sizes they use. This avoids unbounded allocation while preserving text size and sharpness.

`python scripts/check_hud_fonts.py` exercises production cache and batching methods with lightweight font/GPU collaborators. It checks 1,000 HUD/menu pairs, notification-only batches, every atlas size, and font changes. Against the previous renderer, its six-size scenario recreates two atlases by the second frame. The fixed renderer retains the same six across all 1,000 pairs. This is a regression check, not an FPS benchmark.

To investigate a user's FPS drop, capture the client Render thread with `/sparkc profiler start --timeout 30` and keep the affected menu/HUD visible during the recording. A `/spark` profile containing only the integrated Server thread cannot identify rendering costs, frame caps, GPU stalls, or the FPS improvement from this change.

## Glass batches and smooth hotbar

HUD glass backgrounds share their existing blurred scene and now submit up to 64 panes in one instanced draw, preserving pane order and the existing shadow/background/text layers. Nine panes use one glass draw and one uniform upload instead of nine of each. An empty pass submits no glass. Single-pane menu and world draws retain their existing behavior. Both paths use the same fragment shader. The uniform block is 2,096 bytes, below OpenGL 3.3's minimum uniform-block limit, and batches split at 64 panes.

Exordium caches Minecraft's native HUD, including the item row Ember skins. That also freezes selection animation and scene blur between cache updates. When Exordium and the custom hotbar are enabled with no screen open, the native item row is omitted from the cached extraction and rendered once per world frame in Ember's uncached GUI scope. Other native HUD components keep their cache. Open screens retain normal Minecraft layering; F1, spectator mode, a disabled custom hotbar, and disconnects do not draw an extra item row. No Exordium settings are changed.

Ambient geometry now selects occupied cells before calculating their animated positions. Empty cells avoid position hashes, trigonometry, distance, and fading work. A comparison against the previous implementation produced identical vertices and colors across 320 cases including densities, trail settings, cell crossings, and teleports.

`check_hud_rendering.py` exercises the production batching and hotbar routing, including ordered uploads, batch boundaries, opacity, frame rotation, screens and spectator mode. `check_exordium_compat.py` uses the installed mod's actual cache at 10/30 FPS while hotbar and blur updates run at 60/144/360 FPS. The optional `check_glass_rendering.py` compares the actual single-pane and instanced shaders on a GPU; overlap and odd-size cases differ by at most one color-channel step out of 255.

An isolated Minecraft run with Exordium 2.1.1 and its native HUD cache set to 10 FPS refreshed both the hotbar and scene blur on all 306 sampled render frames. Native items, stack counts, and the offhand slot were also checked. This verifies update cadence rather than a before/after FPS increase.

The `Ijam5Lis5x` Spark capture used Ember 1.9.8 and sampled only the integrated Server thread for 30.32 seconds; 87.05% was `Unsafe.park`. It cannot measure the rendering improvement. Reduced submissions and unchanged images are verified, but an end-to-end FPS claim still requires a client Render-thread capture.
