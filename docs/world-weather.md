# Horizon, constellations and local weather

Open **Gear → Appearance**. **Atmospheric ambience** and **World atmosphere** are the master switches.

**Horizon glow** adds a soft accent-colored band behind mountains. Enable **Accent skybox**, then adjust its brightness and width. **Slow Spotify pulses** requires **React to Spotify** in Bass Reaction. Its slower attack and release keep the horizon from flashing on every beat. Weather does not react to music.

**Constellations** connects selected existing sky stars. The lines follow the same sky rotation as their endpoints and slowly fade. Enable **Sky stars**, then choose brightness and the number of constellation groups. The graph is cached while star density stays fixed.

**Accent fog** now has independent **Fog density**, **Fog start** and **Fog end** controls. Defaults are **30%**, **48 blocks** and **224 blocks**. Density caps the haze opacity; the distances do not move farther away when you increase render distance. At 24 chunks, the selected haze is already visible well before the horizon. Start and end remain separated by at least eight blocks. Turning Accent fog off restores native/module behavior. The explicit atmospheric haze can remain active when No Render removes vanilla fog; water, lava, powder snow and vision status effects keep their native distance rules.

**Custom weather** is off by default. Turn it on and choose **Rain**, **Glowing snow**, **Autumn leaves**, **Ash** or **Petals**, independently of the server's weather and biome. Controls cover density, radius, speed, wind strength/heading, surface impacts, accent matching, and local rain sound. Wind heading is 0° east and 90° south. Turning custom weather off restores the server's precipitation rendering and sounds.

Rain splashes against solid surfaces and makes brief rings on water. Snow, leaves and petals briefly settle, then fade; snow melts quickly on water. Loaded column heightmaps and per-point collision shapes keep particles above roofs and align impacts with slabs and stair steps. Nearby roof shelter muffles rain sound; deep underground, it is silent. These effects are local visuals and do not place permanent snow, change server weather or change block behavior.

The simulation reuses a **512-particle pool**, permits at most **96 simultaneous impacts**, refreshes at most **48 cached surface columns per tick**, and renders within a **36,000-vertex mesh**. It never requests unloaded chunks. Particle density and render distance do not create an unbounded workload. World transitions and teleports discard the old weather volume.

`./gradlew checkVisuals` covers roof/cave shelter, per-point stair heights, waterlogged steps, impacts, wind, style changes, unloaded support, teleports, particle/mesh budgets, constellations and fog ranges. `python scripts/check_atmosphere_fog.py` executes the production fog hooks at 8, 24 and 64 chunks. After exporting visual geometry, `scripts/check_world_atmosphere.py` checks the actual sky shader, horizon pulses and constellation meshes; `scripts/check_weather_rendering.py` checks all five weather meshes, scene-depth occlusion and Minecraft's actual fog shader. In-game checks with Iris/Sodium and other mods are still needed.
