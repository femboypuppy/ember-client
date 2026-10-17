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
