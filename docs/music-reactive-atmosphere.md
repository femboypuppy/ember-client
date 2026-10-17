# Atmosphere and bass reaction

Open **Gear → Appearance**. Atmosphere controls are grouped into **World atmosphere**, **World lighting**, **Stars & haze**, and **Bass reaction**.

## Lighting

- **Vanilla**, **Soft accent**, and **Cinematic** apply lighting presets. Adjust the sliders afterward for a custom look.
- **Vanilla light mix** blends the accent daylight and gamma back to their original values. At 100%, Ember's daylight changes are disabled.
- **Daylight brightness** controls sky illumination independently of the color strength and darkness sliders. Torches retain their own colors and unlit caves do not receive extra sunlight.
- **Sky stars**, **Floating motes**, and **Star brightness** control world starlight separately. Density and drift controls remain available above.

## Bass reaction

**React to music** is off by default. On Windows it starts an output-only [WASAPI loopback](https://learn.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording) analyzer for the default multimedia output. No microphone is used. Raw audio is processed in memory; only bass/RMS measurements reach the mod. No audio is saved or uploaded.

Play music through that output, then select **Darken** or **Glow**. Tune **Effect strength**, **Bass sensitivity**, and **Fade smoothing**. Separate toggles apply the response to the sky and its fog, world daylight, ground light, and starlight. Menu ambience follows the sky reaction too. World-light reaction requires **Match world lighting** and a vanilla mix below 100%.

The analyzer measures approximately 30–180 Hz energy, with a quiet-signal floor, fast attack and adjustable release. It follows actual sound rather than lyric timings or an estimated beat. Pausing, silence, or losing the audio device returns the visual response to neutral. The helper reconnects when the default output changes. Turning the option off stops capture.

This analyzes the whole desktop output, so Minecraft, notification sounds, and other apps on that output may also trigger it. It needs no Spotify account, API key, Premium, or Spicetify. Other operating systems retain the manual controls and show that desktop audio analysis is unavailable.

Audio capture and analysis run outside the render thread. Daylight response is sampled at most 20 times per second and quantized to avoid rebuilding the lightmap for tiny changes. The sky uses smooth per-frame exposure, and particle counts remain bounded.
