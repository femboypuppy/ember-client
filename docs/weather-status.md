# Rain status and settings categories

Open **Gear → Weather**. These controls default to on and are independent of custom atmospheric effects:

- **Rain notifications:** one notification when rain starts, begins fading out, and stops.
- **Rain ending notification:** disable the fade-out warning while keeping start/stop notifications.
- **Rain status icon:** a cloud-and-rain badge in the top status bar, with a short appearance/disappearance animation in Liquid Glass. Other status-bar layouts show a Rain/Easing segment.

The icon stays present throughout visible rain fade-out. The ending notification follows Minecraft's server weather flag as the fade begins; it does not predict the server's rain timer. Rain already in progress when joining a world is shown without a false “started” alert. Disconnections and dimension/world changes clear the previous state. Weather is sampled once per game tick and rendering reads the cached result.

When Ember's custom visual weather is enabled, the status follows its selected effect: local Rain counts as rain, while Snow, Ash, Leaves and Petals do not. Rain alerts for ordinary server weather work with atmospheric effects disabled. Sheltering under a roof or temporary blindness does not switch local rain off in the tracker.

Settings now have separate pages for **Appearance**, **Start menu**, **Atmosphere**, **Stars & meteors**, **World lighting**, **Weather**, **Effects**, **Bass reaction**, **Typography**, **HUD widgets**, and **Music & lyrics**. Weather also contains local precipitation, wind, impacts and fog controls. Scroll the sidebar on small windows, or use Ctrl+Tab / Ctrl+Shift+Tab to change categories. The existing setting keys and config-sharing sections are preserved.

`python scripts/check_rain_status.py` exercises the production observer, rain glyph and settings screen. It checks rain transitions and fading, independent toggles, reconnects, local weather, weatherless dimensions, glyph bounds, real settings controls, sidebar scrolling/clipping, and category navigation.
