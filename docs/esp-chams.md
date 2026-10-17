# ESP surfaces and chams materials

Open **Render → Block ESP**, **Storage ESP**, or **Chams**, then click
**Open 3D preview** at the top of the settings popup. This opens the full settings
screen with a live 3D preview above the controls. Drag to rotate it, scroll over it to zoom, or
double-click to reset. In Chams, click the Player/Hand switch at the top right
to preview each independently. You can preview a module while it is disabled.

## Block and storage ESP

The **Ember Surface** group controls rounded 3D corners, an optional Etched or
Scanlines fill, texture strength, and matching the current Ember accent. Setting
roundness to zero restores straight corners; setting the texture to None removes
surface detail. Existing block-specific colors, opacity, shape modes and tracers
remain available. Neighboring highlighted blocks omit their shared faces.

Storage ESP's **Ember** mode uses the new rounded geometry. Existing profiles
retain their selected mode: choose **Mode → Ember** for the full new style.
Classic Box mode remains available; Shader mode has softer outlines and a subtle
textured fill. The preview uses the chest color, or the default block config for
Block ESP. Individual block overrides can still differ from that default sample.

Detailed geometry defaults to **64 boxes per frame within 48 blocks**. Farther
boxes and objects beyond that budget retain simple geometry, and all finds remain
tracked. Both limits are adjustable. Geometry is cached by roundness and neighbor
mask and prepared on a background worker; simple boxes remain visible while a new
surface is being prepared. Changing the style does not restart chunk scanning.

Entity ESP's **Ember** mode now uses rounded frames/corner brackets, an optional
etched gradient fill, rounded health bars, and cleaner name/distance cards.
**Interface font** follows Ember's smooth interface typeface. Radius, thickness,
fill, glow, health and label controls remain adjustable.

## Chams

Enable **Players** or **Hand → Enabled**, disable its original **Texture**, and
choose **Solid**, **Galaxy**, **Aurora**, or **Opal**. Galaxy draws drifting nebula
clouds and stars, Aurora draws flowing ribbons, and Opal draws soft pearl-like
veins. These are procedural GPU materials, so no image downloads are needed.
The preview uses the same material shader and model UV scale as the live effect.

**Use Ember accent** follows the current theme or album-art accent. Switch it off
to use the player/hand color picker as a custom accent. Each picker's alpha always
controls opacity. **Animate materials** freezes or animates the surface. Original
textures retain their existing tint behavior and take priority over materials.

The Through Walls shader selector also offers Galaxy, Aurora, Opal and Solid;
the original resource-pack Image mode and None remain available. Entity selection
and ignore-self controls continue to apply to through-wall effects.
