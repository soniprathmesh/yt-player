YT PLAYER - bundle
==================

android-app/        Android Studio project (open this folder in Android Studio)
web/re/yt-v3/       Website files -> upload to  https://academichub.dev/re/yt-v3/

ORDER
1) Upload the files inside web/re/yt-v3/ so that
   https://academichub.dev/re/yt-v3/  opens the player.
2) Open android-app/ in Android Studio, let it sync, press Run (or Build APK).

If your domain or path ever changes, edit these two lines in
android-app/app/src/main/java/dev/academichub/ytplayer/MainActivity.kt :
    private val appUrl  = "https://academichub.dev/re/yt-v3/"
    private val appHost = "academichub.dev"

BUILD WITHOUT ANDROID STUDIO
============================
android-app/.github/workflows/build-apk.yml builds the APK on GitHub for free.
Upload the CONTENTS of android-app/ to a new GitHub repository, open the
Actions tab, run "Build APK", then download "yt-player-apk" from the run page.

UPDATE v1.1
===========
- Notification now has Play / Pause / Previous / Next (MediaSession).
- Picture-in-Picture: auto-enters on Home / swipe-up (Android 12+), and shows
  the reason + opens the phone's PiP setting if it is blocked.
- Re-upload web/re/yt-v3/index.html to your server AND rebuild the APK.

UPDATE v1.2
===========
- Seek bar in the notification / lock screen (drag to change the timestamp).
- App name changed to "Prathmesh YouTube".
- Re-upload web/re/yt-v3/index.html AND rebuild the APK.

UPDATE v1.3
===========
Deep links:
  prathmeshyt://play?code=VIDEO_ID
  prathmeshyt://play?list=PLAYLIST_ID
  prathmeshyt://play?code=VIDEO_ID&list=PLAYLIST_ID
  prathmeshyt://play?url=<encoded youtube link>
Rebuild the APK (no web change needed).

UPDATE v1.4 (web only)
======================
- Search panel (YouTube Data API v3). Re-upload web/re/yt-v3/index.html. No APK rebuild needed.

UPDATE v1.5 (web only)
======================
- Search suggestions while typing. Re-upload web/re/yt-v3/index.html. No APK rebuild needed.

UPDATE v1.6 (web only)
======================
- "Playlists" toggle in the Search panel (search only playlists).
- Auto-next: video results play one after another; notification Prev/Next work with the results.
- Re-upload web/re/yt-v3/index.html. No APK rebuild needed.

UPDATE v1.7 (web + Android)
===========================
- Android: always loads the newest page (no WebView cache), keyboard no longer covers the search box,
  Back button closes the search panel first, tapping the notification no longer reloads the page
  (which wiped the playing video / results / queue).
- Web: sw.js revalidates index.html; page answers the Back button.
- Upload web/re/yt-v3/index.html + sw.js, then rebuild the APK.
- In the app, tap the key button in Search once (the app has its own storage, separate from Chrome),
  or hard-code YT_API_KEY in index.html.
