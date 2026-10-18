# Celestial visuals

The atmosphere, Wings and Jump Circles share Ember's accent and pearlescent,
translucent surfaces. Enable **Match song artwork** to transition their colors
with the current cover, or choose a manual accent in **Appearance**.

## Atmosphere and colored ground light

Open **Gear → Atmosphere**. Menu ambience is enabled by default:
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

In **Gear → Atmosphere → World atmosphere**, **World atmosphere** is enabled
by default. This is a 3D world effect and stays visible with the menu closed
or HUD hidden. The optional **Screen ambience while playing** control remains
a separate, faint 2D overlay.

- **Accent skybox:** choose **Nebula**, **Aurora**, or **Dusk**, with a separate
  intensity control. The procedural sky changes color smoothly with Ember's
  accent or the current album cover. Nebula uses layered clouds of light;
  Aurora uses luminous curtains; Dusk gives a softer gradient. No downloaded
  textures or shader pack are required. Intensity changes its brightness rather
  than its opacity: the original blue sky cannot bleed through at lower values.
  Zero restores the original sky.
- **Match world lighting:** changes Minecraft's actual daylight lightmap so
  blocks and entities take the accent color. **World darkness** dims full daytime
  lighting (42% by default); **Light color strength** controls the tint (80% by
  default). Torch and other block-light colors remain independent. Turning the
  option off restores the ordinary lightmap. This mode takes precedence over
  Ember's gamma Fullbright shortcut and caps gamma boosting while active.
- **3D starlight:** distant, softly twinkling stars plus nearby light motes.
  Motes are anchored to world-space cells and drift slowly. Walking produces
  parallax rather than moving a screen overlay. They fade at the range limit
  and stay hidden behind blocks.
- **Accent fog:** subtly colors existing fog and adds distant atmospheric haze.
  Adjust **Fog amount** independently; zero removes the extra haze. With the
  accent sky enabled, the regular fog and clear color still match its horizon
  to avoid blue distance fringes.
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

The sky replaces the background inside Minecraft's sky pass, before terrain,
cutouts and translucency are composited. This prevents grass and block edges
from retaining the old blue background. It uses the current native projection
and view uniforms rather than a previous frame's overlay matrices.
The direction-only sphere is pinned to the far depth plane and ignores camera
translation. The
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

## Hats

Enable **Render → Hats** and press **F5**. The cosmetic is local, like Wings, and starts disabled.

- **Hologram:** a translucent cone with a bright brim, fine scan bands and wireframe seams.
- **Halo:** two floating light rings with orbiting diamond details.
- **Crown:** eight luminous points, small gems and a translucent band.
- **Witch:** a wide brim and a tall, bent tip.
- **TopHat:** a flat brim, tall crown and glowing hatband.

Every style supports accent matching or a custom color, size, height, opacity, glow,
scanlines, color shifting, animation speed and gentle floating movement. Set color
shift to zero for a consistent accent; set animation speed to zero to freeze the
effect. Raise height to clear a helmet, or enable **Hide with helmet**.

The hat attaches to the rendered head, including head turns, crouching, swimming,
gliding and player scaling. It is hidden in first person, for invisible players
and spectators. It respects scene depth and does not change equipment or send
cosmetics to the server. Each renderer reuses a 24,000-vertex mesh.

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

`python scripts/check_world_lighting.py` renders the actual cached Minecraft
1.21.11 lightmap shader with parameters exported by Ember's production lighting
math. It checks daytime tint and darkness, preserves torch colors under changing
accents, and verifies that unlit caves receive no fabricated skylight. It writes
`build/visual-preview/world-lightmap-preview.png`.
The BadOptimizations lightmap-cache hook also invalidates on accent, lighting
control and enabled-state changes, including the transition back to vanilla.

`./gradlew checkMeshCapacity` exercises the production CPU mesh allocator with
the reported startup-crash reservation, maximum effect sizes, independent vertex
and index reservations, and buffer growth with existing data. It requires no GPU
uploads. Both regression tasks run as part of `./gradlew build`.
