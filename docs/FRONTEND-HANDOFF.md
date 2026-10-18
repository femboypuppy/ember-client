# Front-end handoff: Ember backgrounds, logo, loading screens and the old Meteor UI

Written for the front-end work on `dev-1.21.11`. Two tasks are open:

- **A.** Ember's own menu background, title-screen logo and server joining/loading screens, built
  around the Ember logo (not just the image pasted on a background).
- **B.** Every screen that still opens in Meteor's old window UI should look like Ember.

The rebrand (commit "Rebrand to Ember Client") already removed every user-visible Meteor
reference. Please keep it that way; the rules are at the end.

---

## A. Background, title logo and joining screens

### What is wanted

Like Anubis Client's custom join screen and background, but Ember's own: something built
**around** the logo (`assets/ember-client/textures/ember.png`), not the logo on a flat
background. It should feel like the logo: a dark forge, ember orange `#FF6A2B`, crimson
`#C42621`, maroon `#6E1620`, ash `#2A292E`, diamond-shaped sparks, and meteor trails coming
in from the upper left at about 32 degrees (the angle of the logo's own trails).

Screens to cover:

1. **Every menu without a world** (title screen, multiplayer list, options...): background.
2. **Title screen**: the Ember logo/wordmark in place of the "MINECRAFT" logo. The splash
   text and the vanilla buttons should stay where they are.
3. **Joining a server**: connecting / logging in, configuring, and loading the world.

A config toggle (Config > Visual) to turn it off is a good idea.

### Assets

| File | What |
|---|---|
| `assets/ember-client/textures/ember.png` | 512x512, transparent, logo cropped to its content with 4% padding |
| `assets/ember-client/icon.png` | 500x500 original logo (also the mod icon) |
| `assets/ember-client/textures/icons/chat/ember.png` | 64x64, chat prefix icon |

Identifier: `MeteorClient.identifier("textures/ember.png")` (namespace `ember-client`).

### Hook points (Minecraft 1.21.11, Yarn names)

| Where | Method / field | Notes |
|---|---|---|
| All menus without a world | `Screen#renderPanoramaBackground(DrawContext, float)` | Cancel and draw instead. `Screen#renderBackground` then still applies blur + darkening on normal menus. |
| Title screen | `TitleScreen#render` | Order: `renderPanoramaBackground`, then buttons (`super.render`), then `logoDrawer.draw(...)`, then the splash. `TitleScreen#renderBackground` is empty. |
| Title logo | `LogoDrawer#draw(DrawContext, int screenWidth, float alpha, int y)` | Cancel **only when `mc.currentScreen instanceof TitleScreen`**: the end credits use it too. `y` is the logo top in GUI pixels (base 30). |
| Connecting / logging in | `ConnectScreen#render` | Draws the status text itself; field `private Text status`; has a Cancel button. |
| Configuring | `ReconfiguringScreen` | No `render` override: its text and Disconnect button are `TextWidget`/`ButtonWidget` drawables in `Screen#drawables`. |
| Loading the world | `LevelLoadingScreen#render` and `#renderBackground` | Both overridden. `private float loadProgress` (0-1) is real progress. Also draws the nether portal background when changing dimension. |
| Buttons of a screen | `Screen#drawables` | `private final List<Drawable>`; reachable through an `@Accessor`. Rendering only the `ButtonWidget`s keeps Cancel/Disconnect usable. |

`ConnectScreenMixin` already exists (it posts `ServerConnectBeginEvent`); keep that inject.

### Rendering gotchas (each cost a test round)

1. **`HudRenderer` needs the unscaled projection.** It works in framebuffer pixels. Call
   `Utils.unscaledProjection()` before `HudRenderer.INSTANCE.begin(context)` and
   `Utils.scaledProjection()` after `end()`. Without it nothing appears: the title screen was
   plain black.
2. **Textures draw immediately, shapes at `end()`.** `HudRenderer.texture(...)` is drawn the
   moment it is called; quads, triangles, glows and text are queued and drawn at `end()`. A
   full-screen gradient in the same batch as the logo therefore paints over the logo. Use
   separate `begin`/`end` batches: background, then the logo, then what goes in front.
3. **Draw order against vanilla.** `HudRenderer` draws straight into the framebuffer while
   the screen renders; vanilla GUI elements are drawn later in the frame by the GUI pass. So
   anything drawn from the background hook ends up under vanilla buttons and text, which is
   what you want for a background.
4. **Size things from the framebuffer**, not the GUI-scaled width; dev windows are small
   (870x519) and anything sized in absolute pixels gets tiny there.
5. Shader includes use the `ember-client:` namespace (`#moj_import <ember-client:...>`).

### A working prototype

Branch **`wip/ember-backdrop-prototype`** has a complete, working version, checked in a dev
client on the title screen and the connecting screen:

- `utils/render/EmberBackdrop.java`: dark forge gradient, heat glow, rising diamond embers,
  meteor streaks at the logo's angle, the logo floating inside a tilted segmented ring with
  orbiting shards (drawn behind/logo/in-front), an `EMBER` / `CLIENT` wordmark, status
  line and progress bar (real progress on world loading, a sliding ember otherwise).
- `mixin/EmberBackdropScreenMixin`, `LevelLoadingScreenMixin`, `LogoDrawerMixin`,
  `ConnectScreenAccessor`, `ScreenDrawablesAccessor`, and a render inject in
  `ConnectScreenMixin`; toggle `Config.emberBackgrounds`.

Use it as a starting point or just as a reference for the hooks; the visual design is yours.

---

## B. The old Meteor window UI

Ember's click GUI (`EmberClickGui`) and its settings popup (`EmberSettingsPopup` +
`EmberSubEditor`) are Ember-styled. But 45 screens still extend Meteor's
`WidgetScreen`/`WindowScreen`/`TabScreen` and render with Meteor's widget theme: square
boxes and Meteor purple. The ones users actually reach:

| Screen | Opened from |
|---|---|
| `HandEditorScreen`, `HandMirrorScreen` | Hand View settings popup, `HandView` |
| `HudEditorScreen`, `AddHudElementScreen`, `HudElementPresetsScreen`, `HudElementScreen` | Client settings > "Open HUD layout editor", HUD tab |
| `ESPBlockDataScreen` | `ESPBlockData` (per-block ESP settings) |
| `ChunkScreen` | Stash Finder's chunk list |
| `MarkerScreen` | Base Marker |
| `AccountsScreen`, `AddAccountScreen`, `AccountInfoScreen`, `ProxiesScreen`, `ProxiesImportScreen` | Title screen buttons |
| `FriendsScreen`, `MacrosScreen`, `ProfilesScreen`, `EditProfileScreen`, `ExportProfileScreen`, `PathManagerScreen`, `GuiScreen`, `HudScreen`, `ConfigScreen` | Tabs |
| `PromptScreen` | Confirmations (`Prompt`) |
| `CustomKeySettingScreen` | Keyboard HUD |
| `NotebotSongsScreen`, `EditBookTitleAndAuthorScreen`, `CommitsScreen` | Notebot, book editing, title credits |
| 18 setting sub-screens in `gui/screens/settings` | Only from Meteor screens, through `DefaultSettingsWidgetFactory` |

`EmberClickGui.openModuleSettings` still passes `new ModuleScreen(theme, m)` and
`openHudSettings` passes `new HudElementScreen(...)` as the popup's `openNativeEditor`.
The popup never calls it today, but those are Meteor screens too.

### Suggested approach

Restyle the widget theme itself rather than each screen. Then all 45 screens, and any new
ones, follow automatically:

- `gui/themes/meteor/MeteorGuiTheme.java` (already named "Ember" in the UI) and its 24
  widgets in `gui/themes/meteor/widgets/**`: window + header, button, confirmed button,
  checkbox, slider, text box (+ completions), dropdown (+ list and items), section header,
  separators, label, multi-label, tooltip, view scrollbar, module, plus/minus/confirmed
  minus, triangle, favorite, top bar, account, quad.
- Each widget draws in `onRender(GuiRenderer, ...)` with square `renderer.quad`s and the
  shared `MeteorWidget.renderBackground` (outline + fill). Ember's look comes from
  `gui/screens/EmberUI.java` (palette `bg()`, `raised()`, `WELL`, `DIVIDER`, `TEXT`,
  `TEXT_DIM`, `accent()`; helpers `gloss`, `rim`, `shadow`, `hoverFill`, `toggle`, `check`,
  `chevron`, `cross`) and `GuiRenderer.roundedRect(...)`.
- Theme colours are `MeteorGuiTheme` settings whose defaults are Meteor purple
  (`145, 61, 226`); deriving them from `EmberUI.accent()` / `EmberPalette` keeps the theme
  in step with the user's selected Ember accent.

---

## Branding rules (please keep)

- No user-visible "Meteor": names, chat, descriptions, links, splash texts, theme/HUD names.
- Mod id and asset namespace are **`ember-client`**. Use `MeteorClient.identifier(...)` /
  `MeteorClient.MOD_ID`, never a hard-coded `meteor-client` path (scripts included).
- Data folder is `.minecraft/ember-client` (old `meteor-client` folders migrate on first
  launch in `MeteorClient.dataFolder()`).
- Starscript is `{ember.*}` (`{meteor.*}` is kept only as a silent alias for old configs).
- Chat prefix is `[Ember]`; Discord falls back to Ember's app.
- No calls to meteorclient.com, no links to Meteor's GitHub/wiki.
- Internal Java packages (`meteordevelopment.*`) stay as they are for now: renaming them
  moves every file and would conflict with everyone's work in progress.

## Testing tips

- `gradlew runClient --args="--username Viewer"` for the title screen;
  `--quickPlayMultiplayer 10.255.255.1:25565` keeps the connecting screen up for ~30 s
  (that address never answers), which is long enough to look at it.
- The release workflow runs `scripts/check_sky_submission.py`, `check_music_connection.py`,
  `check_hand_editor.py` and `check_ember_logo.py`; they pass on the rebranded tree.
