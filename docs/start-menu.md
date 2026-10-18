# Ember startup menu

On first boot, Ember opens a lock-screen-style menu with the local date and clock over a bundled wallpaper. Click anywhere or press Enter/Space to reveal the glass dock. The unlocking input is consumed, so it cannot also activate Play. Returning from Options, Multiplayer, or world selection keeps the same unlocked screen; returning to the title after leaving a world skips the initial lock step for that session.

The dock has **Play** (singleplayer worlds), **Multiplayer**, **Settings** (Minecraft options), **Change menu**, and **Quit**. Hover labels identify the icons. Arrow keys and Tab/Shift+Tab navigate; Enter/Space activate. Escape closes customization or returns to the clock.

The dock and customization panel use Ember's existing Liquid Glass material and follow its opacity, blur, refraction, lightness, and accent settings. The clock is compact and shadow-free. Menu text has its own linear scaling, independent of HUD font preferences. It prefers a locally installed **SF Pro Display Regular** face, then SF Pro, and otherwise uses bundled Inter. Installed `.ttf` and `.otf` faces are supported; restart Minecraft after installing a font. SF Pro is not bundled, following [Apple's font distribution guidance](https://developer.apple.com/documentation/technologyoverviews/fonts).

The clock uses a real **Bold** face: locally installed SF Pro Display Bold/SF Pro Bold, or bundled **Inter Display Bold** from the [official Inter 4.1 release](https://github.com/rsms/inter/releases/tag/v4.1). It is rasterized at its displayed pixel size, including at 4K, instead of enlarging a small glyph atlas. The texture is reused between frames and replaced when the minute, font, or window size changes. Inter's [OFL license](../src/main/resources/assets/ember-client/fonts/Inter-OFL.txt) ships with the font.

**Change menu** offers Chrome/Midnight wallpapers, a 12/24-hour clock, and Classic Minecraft. The classic title screen has an **Ember menu** button to switch back. These preferences also live under **Gear → Start menu** and travel with appearance configs. The selected wallpaper is used on out-of-world submenus. In-world menus retain their normal background behavior.

Both 2560 × 1440 wallpapers ship inside the JAR; booting never downloads an image. Cover scaling preserves image proportions on normal, tall, and ultrawide windows. Photo credits in the lower corner link to the original images; source and license information are in [the asset credits](../src/main/resources/assets/ember-client/textures/menu/CREDITS.md).

`python scripts/check_start_menu.py` runs the production screen against lightweight game collaborators and a recording renderer. It covers input isolation, held Enter, mouse/keyboard actions, parent return, three GUI scales, classic mode, out-of-world guards, wallpaper cover, and asset packaging. It writes layout previews to `build/start-menu`. Rendering is also checked in the development client.
