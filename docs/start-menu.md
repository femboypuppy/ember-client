# Ember desktop menu

The startup screen uses Minecraft 1.21.11 **Yarn mappings**, Java 21, Fabric Loader and Fabric API. It is implemented with `Screen`, `DrawContext`, native text/font providers and resource-managed textures. No external UI toolkit or legacy OpenGL UI path is used.

Click anywhere or press Enter/Space once to unlock. The five-item dock offers **Play**, **Accounts**, **Settings**, **Display** and **Quit**. Play opens Singleplayer/Multiplayer choices. Tab/Shift+Tab and arrow keys navigate; Enter/Space activate; Escape closes a sheet/settings or returns to the lock screen. Held activation keys cannot unlock and launch a world at once. Multiplayer honors the saved warning preference.

The dock magnifies the hovered icon and its neighbors, fades tooltips, bounces on activation and marks the last action with an accent dot. Intro and settings transitions use time-based easing and analytic springs. Returning from a Minecraft child screen retains the unlocked home screen. Reduce motion disables parallax, breathing, magnification, bounce and scale/slide motion while keeping short fades.

## System Settings

- **General:** 12/24-hour time, date, menu-only System/English/French language, Minecraft options.
- **Appearance:** Nebula Live, Nebula, Silk, Obsidian, Summit, Aurora, Dunes, Forest and Ocean thumbnails; HSV accent picker; blur amount; UI scale; light/dark glass.
- **Animations:** enable animations, speed, wallpaper parallax, dock magnification.
- **Audio:** Minecraft menu soundtrack and volume; UI click sounds. Minecraft's global sound preferences remain in their own store.
- **Performance:** 30-240 menu FPS cap, blur, reduced motion. Global FPS and minimized/AFK limits still apply; VSync can impose a lower cap.
- **Accounts:** list, add, switch and confirm removal; Microsoft browser sign-in plus existing Offline/Session/Altening backends. The existing Ember account store handles credentials; menu preferences never contain them. Account operations are blocked while playing a world.
- **About:** client/game/mod versions, credits, classic-menu switch. The vanilla title screen retains its Ember-menu button.

The sidebar search filters sections. Content scrolls in compact windows. Tab focuses enabled controls and scrolls them into view; Ctrl/Cmd+F focuses search. Changes apply live and save to **`config/ember/menu.json`**, resolved through Fabric Loader. Writes are debounced, serialized from client-thread snapshots, and atomically replaced where supported. Invalid input is clamped; malformed files load defaults and are preserved until the menu next saves preferences. Existing clock/wallpaper preferences migrate on first use. Menu preferences are separate from gameplay appearance profiles.

## Rendering and assets

All new assets live under `src/main/resources/assets/ember/`:

- `font/ui.json`, `font/inter.ttf`: native Inter regular font, 16 logical-pixel base, 4x oversampling.
- `font/clock.json`, `font/inter_display_semibold.ttf`: native Inter Display SemiBold, 96 logical-pixel base, 3x oversampling.
- `font/ofl.txt`: Inter license. SF Pro is not bundled.
- `textures/menu/nebula.png`, `silk.png`, `obsidian.png`, `summit.png`, `aurora.png`, `dunes.png`, `forest.png`, `ocean.png`: bundled 2560 x 1440 wallpapers.
- `textures/menu/<art>_blur.png`: 480 x 270 prefiltered versions, sampled behind rounded glass panels and during settings transitions.
- `textures/menu/<art>_thumb.png`: 384 x 216 picker thumbnails.
- `textures/menu/icon/<action>.png`, `<action>_64.png`: white dock glyph masks at 128 and 64 pixels, tinted at draw time.
- `textures/menu/ui/corners.png`: antialiased quarter-disc masks (4-128 px) for every rounded shape; `ui/shadow.png`: a blurred rounded rectangle drawn as a nine-slice soft shadow.
- Every menu texture has a `.png.mcmeta` with `"blur": true`, so Minecraft samples it with linear filtering instead of nearest-neighbour blocks. `scripts/generate_menu_assets.py` regenerates all of them.
- `textures/menu/credits.md`: source links and wallpaper licensing.
- `menu_lang/en_us.json`, `fr_fr.json`: menu text (English source labels are the fallback).
- `sounds.json`: `ember:ui.click` aliases Minecraft's existing UI click event; no external sound download is required.

**Nebula Live** animates the Nebula image with slow breathing/translation; it does not decode a video. Static wallpapers still support separately controlled mouse parallax. Wallpaper switching crossfades cached resource identifiers; vignette, shadows, corners and borders use native GUI commands and mask textures. Glass is a cheap wallpaper-based approximation, not real-time background refraction. Disabling blur removes filtered sampling and leaves tinted panels. No framebuffer capture, raw GL call or old RenderSystem shader binding is required.

Shapes are placed on the window's physical pixel grid rather than the GUI-scaled grid: each `MenuCanvas` primitive reads the current matrix, multiplies by `Window.getScaleFactor()`, and draws integer physical-pixel quads under a `1/scale` matrix. Rounded corners use the mask atlas; textured rounded regions (glass samples, thumbnails) are drawn row by row in their corners with coverage-faded edge pixels. Because Minecraft orders overlapping GUI elements by bounds rounded to the GUI grid, each primitive starts a new root layer (`DrawContext.createNewRootLayer()`) so painter's order stays exact.

Text height is normalized to the bundled Inter cap heights (12 body / 70 clock), so small-window labels retain their intended visible size. The providers use vertical shifts of 5 (body) and 64 (clock) to align Inter cap heights with the top-based layout despite Minecraft's fixed text baseline. Font JSON IDs are `ember:ui` and `ember:clock`. In 1.21.11, `Style.withFont` takes `new StyleSpriteSource.Font(Identifier.of(...))`; DrawContext matrices are a `Matrix3x2fStack`. Text uses `drawText(TextRenderer, Text, x, y, color, false)`. Texture commands use `RenderPipelines.GUI_TEXTURED` and the current `drawTexture` overload with source region and texture dimensions. Font providers automatically resolve `ember:inter.ttf` below `assets/ember/font/`.

The native Minecraft resource system caches/owns textures and glyph atlases. The menu caches labels/widths and control objects. A Fabric resource-loader-v1 synchronous reloader invalidates menu labels/layout after a resource-pack reload; it does not allocate or upload textures itself.

Minecraft 1.21.11's `GlyphGuiElementRenderState.textureSetup()` selects a nearest sampler directly. `EmberMenuGlyphMixin` changes that selection to the cached linear sampler while an Ember `MenuScreen` is open, allowing the oversampled Inter glyphs to blend smoothly at fractional scales. Vanilla screens and gameplay keep their original font sampling. The default UI scale is 90%; schema-1 preferences at the former 100% default migrate to 90%, while custom values are retained. Sidebar focus is cleared through the focus setter on page changes, so previously clicked sections cannot retain an accent fill.

## Integration

`EmberMenuClient` is an additional **client** entrypoint in `fabric.mod.json`. Existing metadata and entrypoints are preserved. `EmberMenuMinecraftMixin` replaces an incoming vanilla `TitleScreen` through `MinecraftClient.setScreen` with an `@ModifyVariable` at HEAD (`argsOnly=true`). The existing `TitleScreenMixin` retains a render-time fallback for early startup and the classic-menu return button. The replacement is disabled while in a world or when classic mode is selected.

The mixin list additionally contains `EmberMenuFpsMixin`, `EmberMenuSoundMixin` and `EmberMenuScreenMixin` for the menu cap, menu-only music multiplier and incoming native-screen fade. `EmberMenuBackgroundMixin` replaces the complete out-of-world `Screen.renderBackground` path on vanilla child screens, including Singleplayer, Multiplayer, Direct Connection and Add Server. They use the same saved wallpaper, parallax and reduced-motion settings as the desktop, with a subtle dim and prefiltered blur for list readability. This bypasses the deferred vanilla panorama and fullscreen blur, while retaining native screen controls. The panorama hook remains a fallback for screens that invoke it directly. Classic-menu mode and screens opened inside a world keep their normal backgrounds. `GameRendererMixin` skips Ember's older glass-capture path for native MenuScreen instances.

No new Gradle dependency is needed: the existing build already supplies Fabric API resource-loader-v1, Gson, MixinExtras and Minecraft text rendering. Keep **Java 21** and the repository's **1.21.11 Yarn mappings**. The full-code export includes the actual build and metadata files, with existing dependencies preserved; the menu module relies on Ember's existing account/authentication services rather than inventing a second credential store.

Build and checks (PowerShell):

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
./gradlew.bat build --no-daemon -Prelease_version=1.9.11-dev
python scripts/check_start_menu.py
python scripts/check_accounts.py
python scripts/check_account_login.py
python scripts/check_rain_status.py
```

The headless menu check runs production springs/dock/config code, compares 15/30/60/144/240 FPS, checks magnification/hit areas/bounce/reduced motion, round-trips preferences across JVMs and validates assets/font providers/metadata. Gradle compiles native GUI classes against the real game. In-client checks are still needed for a new renderer/resource-pack combination.

## Customization

- **Colors/layout/timing:** `MenuTheme.java`, `Dock.java`, `EmberTitleScreen.java`.
- **Defaults/ranges:** `MenuConfig.java`.
- **Settings sections/controls:** `MenuSettingsScreen.java`, `MenuControl.java`.
- **Extra wallpapers:** add a `MenuConfig.Wallpaper` entry (texture identifiers are derived from its art file name), its art name to `ART` in `scripts/generate_menu_assets.py` with a matching source JPG, and `menu_lang` labels; the picker wraps to three tiles per row.
- **Wallpaper art:** put 2560 x 1440 JPGs named after the art files in a folder and run `python scripts/generate_menu_assets.py --wallpapers <folder>` (needs Pillow) to rebuild the sharp, blur and thumbnail PNGs.
- **Typography:** replace the licensed TTF files or edit native font providers; retain an appropriate font license and Minecraft fallback.
- **Click sounds:** replace the event in `sounds.json`, or add `assets/ember/sounds/menu/click.ogg` and a file sound named `ember:menu/click`.
- **Additional languages:** add `menu_lang/<locale>.json` and extend `MenuLanguage.locale()` and the language segmented control.

Verified API references: [DrawContext 1.21.11](https://maven.fabricmc.net/docs/yarn-1.21.11+build.3/net/minecraft/client/gui/DrawContext.html), [Style 1.21.11](https://maven.fabricmc.net/docs/yarn-1.21.11+build.3/net/minecraft/text/Style.html). The code uses Yarn names, not Mojang mappings.

## Validation of this revision

The Java 21 build and visual checks passed against Minecraft 1.21.11/Yarn build 3. Startup, held-Enter isolation, all seven native settings layouts at maximum UI scale, resource reload, uninterrupted/reversing wallpaper fades, clock/date positioning, and compact-window rendering were checked in an isolated development client. Current compiled input bytecode also passed Home/End, cursor insertion, select-all replacement and Unicode backspace checks. Existing account UI/authentication, rain/settings and profile-sharing regression checks passed. Microsoft authentication itself was not performed with a real account during this menu preview.

The wallpaper expansion passed the full Gradle build and menu persistence/resource checks for all nine selections. An isolated 1920 x 1080 development client verified the expanded Appearance picker, Aurora behind Multiplayer and Create World, and Ocean behind the populated Select World list. The preview did not connect to a server or load a world.
