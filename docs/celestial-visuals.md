# Celestial visuals

The atmosphere, Wings and Jump Circles share Ember's accent and pearlescent,
translucent surfaces. Enable **Match song artwork** to transition their colors
with the current cover, or choose a manual accent in **Appearance**.

## Atmosphere and colored ground light

Open **Gear → Appearance → Atmosphere**. Menu ambience is enabled by default:
two diffuse aurora fields, slow ribbons, softly twinkling stars, sparse
constellations and gently damped pointer parallax. The background is flushed
before glass and controls so stars cannot cover menu labels. Adjust intensity,
star density and drift speed. Zero speed freezes the atmosphere; Liquid Glass's
zero-motion setting freezes it too.

**Show while playing** enables a much fainter screen atmosphere outside menus.
It is off by default and follows HUD visibility. **Ground lighting** is enabled
by default and adds colored light around your feet, independently of that screen
overlay. Brightness and radius have separate controls. Red accents produce red
illumination; artwork and manual accent changes use the same smooth palette.

Ground illumination uses an additive, depth-tested surface pass rather than
replacing the block texture. Nearby collision and outline shapes are cached
three times per ten ticks, with immediate refresh after larger movement or a
world/radius change. Slabs and stairs receive light at their actual surface
heights. Exposed block sides, stair risers and nearby walls receive the same
smooth accent light as the tops. Faces hidden inside adjoining blocks are
removed, including partially covered faces next to slabs and stairs. A small
outward offset prevents z-fighting, shared per-vertex falloff keeps adjacent
surfaces continuous, and unloaded chunks are skipped.
This is a local projected lighting effect: it does not change Minecraft's light
levels, mob spawning, cast shadows, or light entities like a full
colored-light shader pack. It works without installing a shader pack.

## World atmosphere and custom skyboxes

In **Gear → Appearance → World atmosphere**, **World atmosphere** is enabled
by default. This is a 3D world effect and stays visible with the menu closed
or HUD hidden. The optional **Screen ambience while playing** control remains
a separate, faint 2D overlay.

- **Accent skybox:** choose **Nebula**, **Aurora**, or **Dusk**, with a separate
  intensity control. The procedural sky changes color smoothly with Ember's
  accent or the current album cover. Nebula uses layered clouds of light;
  Aurora uses luminous curtains; Dusk gives a softer gradient. No downloaded
  textures or shader pack are required.
- **3D starlight:** distant, softly twinkling stars plus nearby light motes.
  Motes are anchored to world-space cells and drift slowly. Walking produces
  parallax rather than moving a screen overlay. They fade at the range limit
  and stay hidden behind blocks.
- **Accent fog:** subtly colors existing fog and adds distant atmospheric haze.
  Adjust **Fog amount** independently; zero restores the original fog settings.
  Nearby terrain remains clear and the render-distance cutoff is retained.
- **Light shimmer:** slow brightness waves travel across the existing terrain
  light, including exposed block sides. Zero gives steady lighting. **Drift
  speed** controls sky animation, mote drift and the shimmer; zero freezes them.

Sky and fog effects apply in dimensions with skylight and pause their overrides
under water, lava, powder snow, blindness or darkness. Motes also work in other
dimensions when the camera is in clear air. The existing Ambience module's
explicit sky/fog color overrides take precedence over automatic color tinting.
No Render's fog switch still controls whether fog is rendered. Shader packs or
other mods that replace the sky/fog renderer may change the result; each effect
can be disabled independently.

The sky uses a direction-only sphere pinned to the far depth plane. It ignores
camera translation and is occluded by scene depth at any view distance. The
starfield uses the same sky-depth rule; motes use actual scene depth. These are
local visual effects, not global light propagation or server-side particles.

## Wings

Enable **Render → Wings**, then use **F5** to view the local player.

- **Astral:** translucent, curved feathers and scattered star tips.
- **Seraph:** softer pearlescent plumage with luminous quills.
- **Prism:** seven separated crystal blades per side.

Choose style, size, opacity, glow, feather count, spread, animation speed and
flutter. Accent matching is on by default; turn it off for an independent color.
Movement response folds the wings while sneaking/gliding and flares them while
sprinting. The attachment follows interpolated player position and smoothly
tracks body yaw. Wings are hidden in first person by default. This cosmetic is
rendered locally and is not sent to other players.

## Custom circles / Jump Circles

The existing **Jump Circles** module retains its saved ID and setting names.
Search aliases include **custom circles**, **custom-circles**, and **circles**.

- **Ripple:** soft concentric liquid-light bands.
- **Orbit:** broken luminous ribbons and orbiting four-point stars.
- **Sigil:** a celestial dial with radial markings and a quiet center star.

Pulses are pinned to takeoff/landing ground positions and use a smooth expansion,
short fade-in and complete fade-out. Each delayed trailing pulse has its full
lifetime. Ribbon width is real mesh geometry rather than driver-dependent line
width. Settings cover size, duration, rise, detail, trails, thickness, glow,
rotation and accent/custom color. World transitions clear old effects.

## Rendering and verification

All geometry is procedural and capped: 18,000 vertices per wing mesh/menu
atmosphere, 90,000 circle vertices per frame, 60,000 terrain-light vertices,
3,072 sky-shell vertices, 60,000 sky-star vertices and 24,000 mote vertices.
Buffers are reused. World effects test against scene depth and do not write it;
their pipelines own their blend state. No direct OpenGL state manipulation or
framebuffer copying is used for these effects. Animation uses elapsed time and
exponential pose damping rather than FPS-dependent steps.

`./gradlew checkVisuals` checks symmetry, folding, pulse endpoints, stable
particles, colored terrain illumination, finite vertices, FPS independence and
geometry budgets, exposed terrain faces and stable world-space stars.
`python scripts/preview_celestial.py` exports the production
meshes to `build/visual-preview/index.html`, an offline WebGL sheet with accent
selection and wing rotation. Add `--screenshot` for an isolated headless Chromium
capture. This sheet previews geometry, not a running Minecraft world.

After exporting, `python scripts/check_world_atmosphere.py` compiles and renders
the production sky/star shaders with the real exported meshes using ModernGL.
It verifies color response, sky styles, motion, camera-translation invariance,
zero intensity and terrain-depth occlusion, and writes
`build/visual-preview/world-sky-preview.png`.

`./gradlew checkMeshCapacity` exercises the production CPU mesh allocator with
the reported startup-crash reservation, maximum effect sizes, independent vertex
and index reservations, and buffer growth with existing data. It requires no GPU
uploads. Both regression tasks run as part of `./gradlew build`.
