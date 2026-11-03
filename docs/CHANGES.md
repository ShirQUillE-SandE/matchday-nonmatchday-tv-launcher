# Changes from Koolest TV Launcher 2.5.1

This is a modified version of [Koolest TV Launcher](https://github.com/KoolestTVLauncher/Koolest-TV-Launcher)
2.5.1 (GPL-3.0). Version name: `2.5.1-matchday`. Changes, November 2026:

## New files

- `app/src/main/java/dev/koolest/MatchDay.kt`: match-day detection (ESPN scoreboard, 6 leagues, TV time zone,
  manual switch that expires at the next automatic change), the match-day home `MatchHome` (Scenic Glass: blurred
  album art or wallpaper, weather, clock, switch and settings buttons, music player with transport controls, glass
  dock, live score cards), box blur, drawn transport icons.
- `app/src/main/java/dev/koolest/ModeServer.kt`: the phone page (Match day / Weekday / Automatic) on port 8765.
- `app/src/main/java/dev/koolest/ArsenalWatch.kt`: the team auto-open (fixtures, broadcasters, exact alarm, driving
  the IPTV app through the TV's own adb, clean-playback check, step log).
- `app/src/main/java/dev/koolest/MusicLive.kt`: spectrum from Android's Visualizer on one shared 20 fps tick,
  spectrum bars, music-reactive cloud background, glass music card.
- `app/src/main/java/dev/koolest/DreamOverlay.kt`: the music card at 50 % over the screensaver; keeps the
  screensaver delay.
- `app/src/main/res/drawable/ic_s_ball.xml`: the switch button's icon (Material "sports_soccer", Apache 2.0).

## Changed files

- `MainActivity.kt`: one-time adb setup extras (`setup_*`, `matchday*`, `arsenal_test`); match-day layer, checks
  and switching; dock filled from the home rows; weather shown on match day; music handed to whichever home shows;
  guards against work arriving after the screen was rebuilt.
- `HomeOverride.kt` (`Booted`): at boot also re-sets the team alarm and starts the phone page.
- `GlassInfo.kt`: small changes for the music features.
- `AndroidManifest.xml`: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `SCHEDULE_EXACT_ALARM`, the `ArsenalAlarm` receiver.
- `res/layout/activity_main.xml`: the switch button in the top bar.
- `README.md`: rewritten for this fork; Koolest's README moved to `docs/KOOLEST-README.md`.

## TV settings log

- 2026-11-03: the screensaver never appeared after 3 minutes idle. TCL's app manager blocked Aerial Views from
  starting (no auto-start permission). Fixed with `cmd appops set <pkg> APP_AUTO_START allow` for Aerial Views and
  Koolest (now in [SETUP.md](SETUP.md), step 4). Undo: the same command with `ignore`.
