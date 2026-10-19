# Optional pill hotbar

Open **Gear → HUD widgets → Pill hotbar** to enable the reference-inspired hotbar: a dark translucent rounded bar, no slot dividers, and a darker rounded selected slot. It starts **off**. Disable it at any time to restore Minecraft's hotbar skin.

The skin uses antialiased GUI sprites at four times their displayed resolution. It stays at Minecraft's usual bottom-center position and uses normal GUI scaling. Offhand backgrounds match the same style on either side, including left-handed mode.

Only the hotbar background, selected-slot background, and offhand backgrounds are replaced. Minecraft still draws the nine items, stack counts, durability bars, cooldowns, attack indicator, health, hunger, armor, and experience. Hiding the HUD, spectator mode, and the normal hotbar controls retain their existing behavior. The sprites go through Minecraft's GUI atlas, so cached HUD renderers keep the same drawing order as the native hotbar.

This preference is part of shared appearance configs. `scripts/generate_hotbar_assets.py` reproduces the four sprite assets without external image libraries.
