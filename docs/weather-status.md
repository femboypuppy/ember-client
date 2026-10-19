# Rain status and settings categories

Open **Gear → Weather**. These controls default to on and are independent of custom atmospheric effects:

- **Rain notifications:** one notification when rain starts, begins fading out, and stops.
- **Rain ending notification:** disable the fade-out warning while keeping start/stop notifications.
- **Rain status icon:** a cloud-and-rain badge in the top status bar, with a short appearance/disappearance animation in Liquid Glass. Other status-bar layouts show a Rain/Easing segment.

The icon stays present throughout visible rain fade-out. The ending notification follows Minecraft's server weather flag as the fade begins; it does not predict the server's rain timer. Rain already in progress when joining a world is shown without a false “started” alert. Disconnections and dimension/world changes clear the previous state. Weather is sampled once per game tick and rendering reads the cached result.

Server rain is tracked independently of Ember's custom particle style. Selecting
Snow, Ash, Leaves or Petals does not hide real server rain, its fade-out, or its
notifications. Custom Rain also activates the badge in clear server weather,
including sky dimensions without native weather. Other custom styles do not
invent rain when the server is clear. While either source is raining, the badge
stays visible; turning custom Rain off during server rain does not create a false
stop. Ordinary server alerts work with atmospheric effects disabled. Sheltering
under a roof or temporary blindness does not switch the chosen local rain off.

Settings now have separate pages for **Appearance**, **Start menu**, **Atmosphere**, **Stars & meteors**, **World lighting**, **Weather**, **Effects**, **Scene visuals**, **Player cosmetics**, **Bass reaction**, **Typography**, **HUD widgets**, and **Music & lyrics**. Weather also contains local precipitation, wind, impacts and fog controls. Scroll the sidebar on small windows, or use Ctrl+Tab / Ctrl+Shift+Tab to change categories. The existing setting keys and config-sharing sections are preserved.

After building the client, `python scripts/check_rain_status.py` exercises the
production observer with the same Orbit event bus as Minecraft. It discovers its
post-init registration and posts real tick/disconnect events. Checks cover server
rain with every particle style, overlapping rain sources, transitions and fading,
independent toggles, reconnects, skylit weatherless dimensions, glyph bounds,
settings controls, sidebar scrolling/clipping, and category navigation.
