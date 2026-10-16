// Optional Spicetify extension: exact local playback metadata for Ember's lyrics pill.
// No Spotify tokens, lyric text, or API keys leave Spotify through this bridge.
(async function emberLyricsBridge() {
    while (!globalThis.Spicetify?.Player?.data) {
        await new Promise(resolve => setTimeout(resolve, 1000));
    }
    let busy = false;
    async function publish() {
        if (busy) return;
        const player = Spicetify.Player;
        const item = player.data?.item;
        const match = /^spotify:track:([A-Za-z0-9]{22})$/.exec(item?.uri || "");
        busy = true;
        try {
            await fetch("http://127.0.0.1:18527/playback", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    id: match?.[1] || "",
                    title: item?.name || item?.metadata?.title || "",
                    artist: item?.artists?.[0]?.name || item?.metadata?.artist_name || "",
                    positionMs: player.getProgress(),
                    durationMs: player.getDuration(),
                    playing: player.isPlaying()
                }),
                signal: AbortSignal.timeout(1500)
            });
        } catch (_) { /* Minecraft can be closed. Keep the player quiet. */ }
        finally { busy = false; }
    }
    playerReady();
    function playerReady() {
        Spicetify.Player.addEventListener("songchange", publish);
        Spicetify.Player.addEventListener("onplaypause", publish);
        setInterval(publish, 250);
        publish();
    }
})();
