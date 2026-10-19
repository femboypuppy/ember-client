# Nametag cards

Open **Render → Nametags** and choose **Open nametag designer** at the top of the settings popup. The settings screen has a live preview using the same card renderer as player tags. It uses your skin and protected name, with sample health and distance so all detail switches can be previewed.

- **Glass** uses Ember's existing Liquid Glass surface and scene capture. It falls back to a rounded slate surface when glass is unavailable.
- **Slate** uses a dark flat surface with a subtle accent edge.
- **Compact** removes the avatar and health-bar space while keeping the name, health badge, and enabled detail chips.

Appearance controls include style, opacity, avatar, health bar, shadow, and maximum width. Existing scale and entity filters remain under General. Player details include health, ping, distance, game mode, friend badges, server display names, and friend/name colors. Armor and held-item settings are grouped under Equipment, with a separate tray above the player card. Existing enchantment and durability options remain available; hiding equipment no longer resets the enchantment preference.

Long names are truncated by Unicode code point within their reserved space. The health badge cannot overlap the name. Health is green, amber, or red according to current health; absorption occupies a gold segment of the health bar. Player skins keep the hat layer. Name Protect and friend colors retain their existing behavior. Item, mob, and TNT labels have matching rounded backgrounds.

Old configs that stored equipment options under Players are migrated on load. Explicit values in the new Equipment group take priority. New style settings live in module configs and are included when sharing modules.

The card renderer reuses Ember's existing font atlases and blurred scene. It never rasterizes a new font or captures another framebuffer per player. Shape and text passes are bounded for each tag, and the existing range/count culling controls remain available. `scripts/check_nametags.py` covers layout limits, independent font measurement scopes, projected glass transforms, detail switches, Unicode truncation, and config migration.
