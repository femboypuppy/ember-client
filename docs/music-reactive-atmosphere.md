# Atmosphere and bass reaction

Open **Gear → Appearance**. Atmosphere controls are grouped into **World atmosphere**, **World lighting**, **Stars & haze**, and **Bass reaction**.

## Lighting

- **Vanilla**, **Soft accent**, and **Cinematic** apply lighting presets. Adjust the sliders afterward for a custom look.
- **Vanilla light mix** blends the accent daylight and gamma back to their original values. At 100%, Ember's daylight changes are disabled.
- **Daylight brightness** controls sky illumination independently of the color strength and darkness sliders. Torches retain their own colors and unlit caves do not receive extra sunlight.
- **Sky stars**, **Floating motes**, and **Star brightness** control world starlight separately. Density and drift controls remain available above.

## Bass reaction

**React to Spotify** is off by default. It uses Windows [application loopback capture](https://github.com/microsoft/Windows-classic-samples/tree/main/Samples/ApplicationLoopback) to analyze only the Spotify desktop process and its child processes. Minecraft, notifications, browsers, and other apps are excluded. No microphone is used. Raw audio is processed in memory; only bass/RMS measurements reach the mod. No audio is saved or uploaded.

Open the Spotify desktop app and play a song, then select **Darken** or **Glow**. Spotify can use a different audio output from Minecraft; capture follows the process instead of one default output. Tune **Effect strength**, **Bass sensitivity**, and **Fade smoothing**. Separate toggles apply the response to the sky and its fog, world daylight, ground light, and starlight. Menu ambience follows the sky reaction too. World-light reaction requires **Match world lighting** and a vanilla mix below 100%.

The analyzer measures approximately 30–180 Hz energy, with a quiet-signal floor, fast attack and adjustable release. It follows actual sound rather than lyric timings or an estimated beat. Pausing, silence, or losing Spotify audio returns the visual response to neutral. The helper reconnects when Spotify restarts; renderer subprocess changes are included automatically. Turning the option off stops capture.

The status distinguishes missing Spotify, connecting, paused/silent playback, detected audio, and music with very little bass. Process capture requires a supported Windows version (Windows 11 or Windows build 20348 and later). It never falls back to capturing the whole desktop. Spotify Web Player is not included. It needs no Spotify API key, Premium, or Spicetify. Other operating systems retain the manual controls.

Audio capture and analysis run outside the render thread. Daylight response is sampled at most 20 times per second and quantized to avoid rebuilding the lightmap for tiny changes. The sky uses smooth per-frame exposure, and particle counts remain bounded.
