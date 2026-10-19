# Scene visuals in 1.9.0

The new effects use the current accent color, including song artwork colors when enabled.
**Atmospheric ambience** and **World atmosphere** under **Gear → Atmosphere** are the master switches.

Open **Gear → Scene visuals** for:

- **Bloom:** brightness extraction, a four-level blur chain, and additive glow. Adjust strength, brightness threshold, and glow radius. It runs before the HUD and glass capture so UI text stays sharp. Zero strength disables the passes and frees their textures.
- **Geometric particles:** rotating translucent cubes, crystals, and wireframe shapes. Density and size are independent. Shapes stay anchored to nearby world cells and fade at their volume boundary.
- **Curved trails:** short curved comets with bright heads and fading tails, with a separate density slider.
- Shared particle brightness and animation speed. Zero speed holds animation still while you can move through the volume.

Open **Gear → Player cosmetics** for independently switchable **Player auras**, **Bright halos**, and **Swirling rings & ribbons**. Auras use rising starlight sparks and a small ground ring. Halos float above the head; ribbons spiral around the body with an orbiting waist ring. Controls include aura/ribbon brightness, halo radius, viewing distance, and separate self/other-player toggles. Your own cosmetics appear in third person. Invisible players, spectators, and dead players are excluded. These effects are local to your client; they do not equip items or send changes to other players.

Effects use depth tests, leave terrain depth intact, and do not reveal players through walls. At most twelve nearby players are decorated, chosen by distance. The ambient volume samples a fixed 1,331-cell neighborhood, capped at 192 shapes and 96 curves, and the shared mesh is capped at 180,000 vertices, independent of chunk render distance. Camera-relative vertices preserve precision far from spawn. Blindness and darkness hide the new geometry.

**Gear → Weather → Accent fog** now defaults to **Depth-based fog**. It reconstructs distance from the rendered depth buffer and applies colored haze between **Fog start** and **Fog end**, capped by **Fog density**. Other mods' native fog-distance controls cannot move this pass to the chunk boundary. It leaves the sky clear and runs before first-person hands. Water, lava, powder snow, blindness, and darkness retain their medium-specific behavior. Disable Depth-based fog to use Ember's previous native fog hooks. Shader packs and distant-terrain renderers can supply their own depth; compatibility with every such renderer is not guaranteed.

`checkVisuals` exercises the production procedural geometry, including styles, independent toggles, halos above heads, large coordinates, deterministic frames, and particle/player mesh budgets. `scripts/check_scene_rendering.py` is an optional local GPU check for production bloom/fog shaders and exported geometry, including terrain occlusion and zero-strength behavior. `scripts/check_rain_status.py` verifies all thirteen settings pages and the new controls.
