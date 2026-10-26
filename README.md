# AA Lyrics

Shows synced lyrics on the Android Auto Now Playing screen for whatever YouTube Music
(stock, ReVanced or Morphe) is playing. Personal sideload use only.

AA Lyrics shows up in Android Auto as its own media app. It copies YouTube Music's
session (art, progress bar, controls), puts the current lyric line in the title row,
and sends every button press back to YouTube Music. Lyrics come from https://lrclib.net
and are cached on the phone.

## Install

1. Build: `./gradlew assembleDebug` (or open the folder in Android Studio).
2. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Open **AA Lyrics** on the phone → **Grant notification access**.
4. Android Auto on the phone: Settings → tap *Version* 10× to enable developer mode →
   ⋮ → Developer settings → enable **Unknown sources**. (Sideloaded media apps are hidden otherwise.)
5. In the car: start a song in YouTube Music, then open **AA Lyrics** from the Android Auto launcher.

## Layout

- `source/` – reads the player's media session (needs notification access)
- `lyrics/` – title cleanup, LRCLIB lookup, LRC parsing, disk cache
- `LyricsEngine.kt` – picks the current line from playback position
- `mirror/LyricsMirrorService.kt` – the media app Android Auto connects to

Using a different player? Add its package to `PREFERRED_PACKAGES` in `NowPlayingTracker.kt`.
Lyrics showing too early or late? Change `LEAD_MS` in `LyricsEngine.kt`.
