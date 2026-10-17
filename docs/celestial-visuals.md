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
heights. A small height offset prevents z-fighting, shared per-vertex falloff
keeps adjacent light tiles continuous, and unloaded chunks are skipped.
This is a local projected lighting effect: it does not change Minecraft's light
levels, mob spawning, cast shadows, or light walls and entities like a full
colored-light shader pack. It works without installing a shader pack.

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
atmosphere, 90,000 circle vertices per frame, and 60,000 terrain-light vertices.
Buffers are reused. World effects test against scene depth and do not write it;
their pipelines own their blend state. No direct OpenGL state manipulation or
framebuffer copying is used for these effects. Animation uses elapsed time and
exponential pose damping rather than FPS-dependent steps.

`./gradlew checkVisuals` checks symmetry, folding, pulse endpoints, stable
particles, colored terrain illumination, finite vertices, FPS independence and
geometry budgets. `python scripts/preview_celestial.py` exports the production
meshes to `build/visual-preview/index.html`, an offline WebGL sheet with accent
selection and wing rotation. Add `--screenshot` for an isolated headless Chromium
capture. This sheet previews geometry, not a running Minecraft world.
