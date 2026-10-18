# Config library and sharing

Open **Configs** from Ember's main menu. Select **New snapshot**, enter a name, choose sections, and use **Save current setup**. Appearance, modules, and HUD are selected for a new snapshot; macros and waypoints are optional. Replacing a named snapshot or deleting it takes a second click.

Appearance includes the current interface theme, accent, world lighting, atmosphere, weather, and interaction effects. Music credentials, accounts, and proxy settings are not part of an appearance snapshot.

Select a saved config and choose **Export file** to share one `.ember` file. The recipient uses **Import file**, then **Load**. Alternatively, use **Copy code** and send the whole `ember:1:...` code; the recipient uses **Paste share code**. Export shares the saved snapshot. To share your latest changes, save them first.

Import creates a separate saved copy and never applies it automatically. A duplicate name receives a number. Existing folders copied into `ember-client/profiles` are discovered when opening the library without being overwritten by the current session. Old folders cannot recover settings already overwritten by an older version.

Only selected sections are applied. New snapshots store full visual values, including defaults, so they do not depend on the recipient's previous appearance. Replacing a snapshot removes deselected sections and retains a hidden `.previous-...` recovery folder.

`python scripts/check_profile_sharing.py` runs the production profile lifecycle in separate producer and recipient JVMs. It checks discovery without overwrites, exact file transfer, appearance/module restoration, duplicate names, malformed import cleanup, and section replacement. Archive and geometry checks also run as part of `./gradlew build`.
