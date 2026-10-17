# Spotify lyric pill

Open Ember's gear menu and select **Spotify & lyrics**, then enable **Lyrics pill**.
The pill follows the main status bar in every appearance. Liquid Glass uses the same
scene-colored surface as the other HUD widgets.

## Spicy Lyrics access

Create a publishable client key (`sl_pk_…`) in the
[Spicy Lyrics developer dashboard](https://developers.spicylyrics.org/dashboard/applications).
Enable native-client access **without an Origin header** for that key, then enter
it in Ember's **Spicy Lyrics client key** field. A server secret (`sl_sk_…`) is
not accepted by this client. Ember does not bundle somebody else's API key.

Word timing depends on the sync available for the exact recording. Syllable syncs
keep their individual timing inside joined words, including pauses and punctuation
between syllables. A white fill, soft glow, and small vertical lift follow each
syllable without changing the font size. Line syncs
advance by line. Static or missing lyrics show a status rather than invented timing.
Provider credits remain visible under the lyrics; community contributor credits
link to their profiles when clicked while a Minecraft menu is open.

## Regular Spotify

1. Create a Spotify application in the [developer dashboard](https://developer.spotify.com/dashboard).
2. Set its redirect URI to `http://127.0.0.1:18527/callback` and add your listening
   account as an app user if your Spotify application is in development mode.
3. Enter the public **client ID** in Ember. No client secret is needed.
4. Click **Connect Spotify**, grant the playback-read permission in your
   browser, and return to Minecraft.

The connection uses Spotify's authorization-code flow with PKCE. The refresh token
is stored only in your game folder at `meteor-client/spotify-session.json`; do not
share that file. **Disconnect Spotify** removes it. Spotify developer-app and
account restrictions still apply.

## Spicetify alternative

If you already use Spicetify, the local bridge provides the exact track ID and
millisecond playback position without a Spotify developer application:

1. Copy `integrations/spicetify/ember-lyrics-bridge.js` to your Spicetify `Extensions` folder.
2. Enable it using `spicetify config extensions ember-lyrics-bridge.js`, then
   `spicetify apply`.
3. On Ember's **Spotify & lyrics** page, click **Enable local bridge**.

The bridge posts only track ID, title, artist, artwork URL, position, duration, and playback
state to `127.0.0.1:18527`. It sends no Spotify access token or lyric API key.
It is not installed automatically. Enable the bridge again after restarting
Minecraft. The Spicy Lyrics client key is required with either connection method.

## Timing and checks

Pause freezes the highlight; seeking and changing tracks replace the timeline.
Small Spotify polling discrepancies correct gradually without reversing playback.
The last lead phrase stays on screen through breaths and instrumentals instead
of flashing the song title. New lines slide and crossfade at a fixed font size;
long phrases scroll continuously with the vocal timing. Short phrases fit their
text instead of stretching the panel to its maximum width. Use **Maximum width**
and **Text size** on the music settings page to adjust the limit and font size.
Backing vocals use their own syllable timestamps and appear below the lead, with up
to two simultaneous backing lanes. Disable **Backing vocals** to hide those rows.
Backing rows expand and fade in only while they are needed, then fully collapse.
Provider and contributor credits share a single quiet row that scrolls when it
cannot fit, so attribution does not force a short lyric into a wide or tall box.

**Timing offset** compensates for audio-output delay. Positive values advance
the display. Requests run off the render thread, are cached for up to 32 songs,
and honor provider rate-limit delays.

Run `./gradlew checkLyrics` for the synthetic timing and parser checks. These
checks cover held phrases, backwards seeks, syllable cuts, fitted width,
collapsing backing rows, artwork colors, and transitions at different frame rates.
They require no Spotify login, API key,
or copyrighted lyric fixtures.

## Settings navigation

The gear opens one settings window with four pages: **Appearance**, **Typography**,
**HUD widgets**, and **Spotify & lyrics**. Each page scrolls independently of its
header and navigation. Music connection status and lyric-provider status are shown
separately. Paste a key into its masked field with Ctrl+V; press Enter or click
outside the field to save it. Existing connection settings are retained.

## Song artwork accent

Enable **Match song artwork** in **Appearance** or **Spotify & lyrics** to recolor
Ember's accents from the current track's album cover. The algorithm favors colors
with substantial coverage, avoids letting white/black borders dominate colorful
artwork, and keeps the resulting accent bright enough for controls and highlights.
Colors blend smoothly between songs. This uses cover art, not analysis of the audio.

The regular Spotify connection supplies cover metadata automatically; no new
permissions or reconnect are needed. If you use the optional Spicetify bridge,
replace its extension with the current `ember-lyrics-bridge.js` and reapply it so
it sends artwork URLs. Old bridge versions still provide lyrics.

Cover downloads and image decoding run on a worker thread and cache 32 artwork
colors. Missing artwork falls back to the chosen manual theme. Selecting a manual
color swatch disables artwork matching; turning the option off restores that
manual accent. The setting also works while the lyric pill is disabled.

References: [Spicy Lyrics API](https://developers.spicylyrics.org/docs/reference/get.lyrics),
[client keys](https://developers.spicylyrics.org/docs/keys),
[attribution](https://developers.spicylyrics.org/docs/attribution),
[Spotify PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow),
[Spicetify Player API](https://spicetify.app/docs/development/api-wrapper/methods/player).
