# Music lyric pill

Open Ember's gear menu and select **Music & lyrics**, then enable **Lyrics pill**.
The pill follows the main status bar in every appearance. Liquid Glass uses the same
scene-colored surface as the other HUD widgets.

## Automatic desktop detection (Windows)

**Detect desktop music**, both lyric sources, and **Find Spotify track IDs** are
enabled by default.
Play music in Spotify, YouTube Music, SoundCloud, or another app/browser tab that
publishes its media session to Windows. Ember reads the title, artist, album,
playback position, pause state and thumbnail locally. No Spotify Premium,
developer application, Spotify login or Spicetify installation is needed for
this route. It does not record system audio or inspect private browser data.

In **Music & lyrics → Lyric sources**, toggle **Spicy Lyrics** and **LRCLIB**
independently. Spicy Lyrics always has priority when enabled and configured.
LRCLIB takes over if the primary has no verified track ID, no timed lyrics, a
missing client key or a service error. Initial requests wait for the primary;
existing cached LRCLIB lyrics remain visible during later primary retries.
Disabled sources do not start new requests or display cached lyrics.

Once Spicy Lyrics has loaded for the current recording, Ember keeps that timed
timeline until the track changes, romanization changes, or Spicy Lyrics is
explicitly disabled. Temporary playback/API failures cannot downgrade it to
LRCLIB. Small duration corrections and late album metadata do not restart the
search. Verified Spotify IDs survive a matching desktop session's reconnect;
another player or recording still needs its own verification.

With a Spicy Lyrics key, **Find Spotify track IDs** uses the public
[ListenBrainz metadata lookup](https://labs.api.listenbrainz.org/spotify-id-from-metadata)
to find candidate IDs from title, artist and album without Spotify credentials.
Ember then checks public Spotify embed metadata against the title, artist and
duration before using an ID. This is a best-effort internet lookup, not an ID
derived locally: catalog coverage is incomplete and Spotify's public metadata
format may change. Invalid, mismatched and music-video results are rejected.
Matching checks up to sixteen regional/re-release IDs, four at a time, in the
background with a shared eight-second budget and bounded responses. One unavailable
embed no longer stops the other candidates. Directly supplied IDs and successful
matches are cached for the session; small duration corrections and late album labels
reuse them. Misses retry after one minute, and temporary network failures retry
after thirty seconds. The desktop player continues to supply timing.
Disable **Find Spotify track IDs** to keep only exact IDs from Spotify/Spicetify.
Your own Spicy Lyrics client key is still required for the lyrics themselves.

The [LRCLIB](https://lrclib.net/docs) fallback searches directly by title and
artist without an API key. It verifies the title, artist and duration (within
three seconds), ignoring cosmetic labels such as `(Official Music Video)` but
preserving `Live`, remixes and other recording versions. Search results already
contain lyrics, so there is no second request to download a matching record.
Successful results and misses are cached for up to 32 recordings in memory.
Only one search runs at a time, requests have timeouts, and rate-limit responses
delay further searches. Nothing waits on HTTP on the render thread.

LRCLIB's LRC fallback uses real **line timestamps**, with an LRCLIB credit in the details row.
It does not invent word/syllable timings or backing vocals. Exact Spotify
connections retain their exact IDs; if the direct connection matches the
desktop song and duration, its exact ID is retained while Windows supplies the
playback clock. Disable **LRCLIB** to use only Spicy Lyrics, or disable
**Spicy Lyrics** to use only LRCLIB.

Compatibility depends on the player exposing accurate metadata and a timeline
to Windows. A browser tab with no artist, an unofficial upload, a live recording,
or a music video with a different intro may not have a safe lyric match. Look at
the **Player** and **Lyrics** status rows for the detected song and search result.
The music must play on this PC; remote phone playback still needs Spotify's
direct connection. Non-Windows systems retain the Spotify/Spicetify paths.
Turning off **Detect desktop music** selects those exact Spotify paths instead.

## Optional Spicy Lyrics access

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
Provider and contributor credits appear for five seconds when each song is detected,
then fade and collapse. Hover over the lyric pill with a Minecraft menu open to
reveal them again; contributor names link to their profiles. Enable **Always show
lyric credits** to keep the row visible. This option is off by default.

## Original script and romanization

Japanese, Korean, other characters outside the selected custom font, curly
apostrophes and dashes use Minecraft's Unicode font fallback. Glyph measurements
and clipping use the same font runs, preserving the fixed text size, scrolling,
syllable fill and glow. Zero-duration punctuation remains attached to a real
syllable instead of being discarded.

Enable **Romanization** in **Music & lyrics → Display** to use Spicy Lyrics'
`TransliteratedText` when available. It is off by default. The selection changes
the cached lyric view immediately, without reconnecting or making another request.
Original text remains visible wherever romanization is absent; LRCLIB search
continues to display its supplied lyrics. Per-syllable romanizations retain the
original timing. A romanization supplied only for a whole phrase displays at
line precision rather than inventing word timings.

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
3. Leave **Automatic local bridge** enabled on Ember's **Music & lyrics** page
   (the default). The extension reconnects when Minecraft starts.

The bridge posts only track ID, title, artist, artwork URL, position, duration, and playback
state to `127.0.0.1:18527`. It sends no Spotify access token or lyric API key.
It is not installed automatically. Ember remembers the bridge setting across
restarts and retries if its local port is temporarily busy. **Disconnect** also
disables the bridge; **Enable local bridge** enables it again. The Spicy Lyrics client key is required for Spicy Lyrics with either
connection method. LRCLIB metadata search does not require a client key.

## Timing and checks

Pause freezes the highlight; seeking and changing tracks replace the timeline.
Small Spotify polling discrepancies correct gradually without reversing playback.
The last lead phrase stays on screen through breaths and instrumentals instead
of flashing the song title. New lines slide and crossfade at a fixed font size;
long phrases scroll continuously with the vocal timing. Short phrases fit their
text instead of stretching the panel to its maximum width. Use **Maximum width**
and **Text size** on the music settings page to adjust the limit and font size.
Backing vocals use their own syllable timestamps and appear below the lead, with up
to two simultaneous backing lanes. **Backing vocals** is enabled by default;
disable it in **Music & lyrics → Display** to hide those rows.
Backing rows expand and fade in only while they are needed, then fully collapse.
Provider and contributor credits share a single quiet row that scrolls when it
cannot fit. When the credits and backing vocals are hidden, the lyric pill has
exactly the same height as the neighboring status pill, with vertically centered
text. Opening the details or a backing lane expands downward without moving the lead.

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
**HUD widgets**, and **Music & lyrics**. Each page scrolls independently of its
header and navigation. Music connection status and lyric-provider status are shown
separately. Paste a key into its masked field with Ctrl+V; press Enter or click
outside the field to save it. Existing connection settings are retained.

## Song artwork accent

Enable **Match song artwork** in **Appearance** or **Music & lyrics** to recolor
Ember's accents from the current track's album cover. The algorithm favors colors
with substantial coverage, avoids letting white/black borders dominate colorful
artwork, and keeps the resulting accent bright enough for controls and highlights.
Colors blend smoothly between songs. This uses cover art, not analysis of the audio.

The regular Spotify connection supplies cover metadata automatically; no new
permissions or reconnect are needed. If you use the optional Spicetify bridge,
replace its extension with the current `ember-lyrics-bridge.js` and reapply it so
it sends artwork URLs. Old bridge versions still provide lyrics.

Desktop players use their Windows-provided thumbnail for the same artwork accent,
decoded locally on a worker thread without downloading a cover from another service.
The music widget associates each thumbnail with its song, hides the previous cover
on a track change, and retries missing or unreadable images. The helper also checks
for delayed thumbnail updates without re-uploading an unchanged cover.

Cover downloads and image decoding run on a worker thread and cache 32 artwork
colors. Missing artwork falls back to the chosen manual theme. Selecting a manual
color swatch disables artwork matching; turning the option off restores that
manual accent. The setting also works while the lyric pill is disabled.

References: [Spicy Lyrics API](https://developers.spicylyrics.org/docs/reference/get.lyrics),
[client keys](https://developers.spicylyrics.org/docs/keys),
[attribution](https://developers.spicylyrics.org/docs/attribution),
[Spotify PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow),
[Spicetify Player API](https://spicetify.app/docs/development/api-wrapper/methods/player).
