# HUD rendering and Exordium

Ember draws after Minecraft submits its normal GUI state. Each render frame that
calls the vanilla HUD schedules Ember from the caller in `GameRenderer`, including
frames where a caching mod cancels `InGameHud.render` before its tail runs.

Exordium 2.x intercepts every `GuiRenderer.render` submission and shares one HUD
cache. Its cache is disabled while an inventory or other screen is open. Without
compatibility, Ember's extra item/tooltip submissions can replay or replace the
vanilla cache, while cancelled HUD extractions can skip Ember entirely. The result
depends on the world FPS relative to Exordium's GUI FPS limit.

`EmberGuiPass` scopes Ember's overlay work and GUI flushes. An optional mixin into
Exordium's `BufferInstance.enabled()` bypasses caching inside that scope. Vanilla
submissions keep Exordium's configured cache and FPS limit. Nested flushes and
exceptions restore the scope through `finally`. No Exordium settings are changed.
The optional mixin is applied only when Exordium is installed.

With Exordium and Ember's custom hotbar enabled, only the native item row is moved
out of that cache. It renders once per world frame before Ember's HUD, keeping
selection animation and scene blur live while the remaining vanilla HUD stays
cached. Opening a screen restores normal Minecraft layering. F1 and spectator
mode retain their native behavior.

## Regression check

After Gradle has cached the project's dependencies, use Java 21 and an Exordium
2.1.x Fabric jar:

```text
python scripts/check_exordium_compat.py --exordium-jar /path/to/exordium.jar --java-home /path/to/jdk-21
```

This CPU check executes the jar's actual `BufferInstance` and `PacingTracker`
classes with recording framebuffer stubs. It applies the compiled production
return-value handler with ASM, reproduces both the cancelled-tail and shared-cache
failures without compatibility, and verifies the fix at 20, 30, 60, 144 and 360
world FPS with a 30 FPS cached HUD. Cases include inventory transitions, multiple
submissions, a cache deadline crossed between submissions, disabled caching and
exception recovery. The installed jar and game configuration are read only.

This checks cache scheduling and composition; final driver rendering still needs
an in-game check with the built mod.

## Top-bar logo

The brand glyph uses `assets/ember-client/icon.png`, shared with the Fabric icon
and README. The compact Liquid Glass status bar places it in an inner rounded
tile with a gentle float/pulse and a highlight following the rounded perimeter.
In **HUD → Ember Status Bar**, **Animate logo** switches motion off and **Logo
motion** controls its speed. Appearance's global Motion setting also applies.

Artwork draws through a deferred HUD task, after color and text batches finish,
with a white tint and linear sampling. The regular status bar uses the same
artwork without the animated tile. No additional render target is allocated.

`python scripts/check_ember_logo.py` checks the production tile's bounds, reduced
motion, highlight loop, draw budget, fade and deferred texture submission. It also
exports draw commands under `build/logo-preview` for visual review.
