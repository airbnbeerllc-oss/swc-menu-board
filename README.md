# SWC Menu Board (Fire TV app)

Shows https://southportsmoothies.com/cafe-tv full screen on a Fire TV, all day.

- **Change the web address or timings:** edit `app/src/main/res/values/settings.xml`, then click **Commit changes**.
- **A new APK builds automatically** (Actions tab, about 5 minutes).
- **Fire TV download link (never changes):**
  `https://github.com/YOUR-GITHUB-NAME/swc-menu-board/releases/latest/download/swc-menu-board.apk`

On the TV: press **Back twice** to exit. Anything else on the remote is ignored.

Do not delete the `signing` folder. It holds the app's signing key, which the first build creates. Without that key, the Fire TV won't accept updates.
