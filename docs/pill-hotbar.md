# Optional pill hotbar

Open **Gear → HUD widgets → Pill hotbar** to enable the reference-inspired hotbar: a dark translucent rounded bar, no slot dividers, and a darker rounded selected slot. It starts **off**. Disable it at any time to restore Minecraft's hotbar skin.

The skin uses antialiased GUI sprites at four times their displayed resolution. It stays at Minecraft's usual bottom-center position and uses normal GUI scaling. Offhand backgrounds match the same style on either side, including left-handed mode.

Only the hotbar background, selected-slot background, and offhand backgrounds are replaced. Minecraft still draws the nine items, stack counts, durability bars, cooldowns, attack indicator, health, hunger, armor, and experience. Hiding the HUD, spectator mode, and the normal hotbar controls retain their existing behavior. The sprites go through Minecraft's GUI atlas, so cached HUD renderers keep the same drawing order as the native hotbar.

The pill now has a broad, soft black shadow extending 18 GUI pixels beyond its edges, with matching offhand and selected-slot shadows. **Hotbar shadow** adjusts its strength; zero removes the shadows. **Animate hotbar selection** enables a 160 ms eased slide between slots. Both controls are under the Pill hotbar toggle, which remains off by default. Shadows and animation start enabled when the pill is on.

The selection animation follows elapsed time, including cached HUD updates, and restarts smoothly from its current position when interrupted. Opening a world, returning after the HUD has been hidden, and disabling the skin reset its position. Screen resizing retains the selected slot without sliding across the screen. Item icons and their overlays stay in their native slots.

These preferences are part of shared appearance configs. `scripts/generate_hotbar_assets.py` reproduces the seven sprite assets without external image libraries. `scripts/check_hotbar.py` checks the selection transitions and native GUI submission order.
