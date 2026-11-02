# Setup guide

Step by step, from a fresh TV to match days that switch by themselves. Commands are for a computer with
[adb](https://developer.android.com/tools/releases/platform-tools); on Windows run them in PowerShell or Git Bash.

## 1. Prepare the TV

1. **Settings → Device Preferences → About → Build**: press OK 7 times to unlock Developer options.
2. **Developer options → USB debugging: on.** On most Android TVs this also opens adb over the network on port 5555.
3. Find the TV's IP address (Settings → Network) and connect:

   ```bash
   adb connect <tv-ip>:5555
   adb devices          # accept "Allow USB debugging?" on the TV, tick "Always allow"
   ```

## 2. Build

Needs JDK 17. Android Studio isn't required.

```bash
./gradlew assembleSideloadRelease        # Windows: gradlew.bat assembleSideloadRelease
```

The APK lands in `app/build/outputs/apk/sideload/release/app-sideload-release.apk`.

**Signing.** Without a `keystore.properties` file the build signs with the debug key, which is fine for your own TV.
To keep one key across updates (Android only updates an app signed with the same key), make a keystore once and
point a git-ignored `keystore.properties` at it:

```bash
keytool -genkeypair -keystore ../my-tv.jks -alias tv -keyalg RSA -keysize 2048 -validity 10000
```

```properties
# keystore.properties (never commit this)
storeFile=../../my-tv.jks
storePassword=<your password>
keyAlias=tv
keyPassword=<your password>
```

Use the `sideload` flavor: the `play` flavor leaves out the self-adb features the team auto-open needs.

## 3. Install and make it the home screen

```bash
adb install -r app/build/outputs/apk/sideload/release/app-sideload-release.apk
adb shell cmd role add-role-holder --user 0 android.app.role.HOME dev.koolest
adb shell cmd package set-home-activity dev.koolest/.MainActivity
```

> **Gotcha:** every install or update of a home app makes Android forget the default home. Run the last two
> commands again after each update.

On Google TV, where the stock launcher can't be replaced, see Koolest's "Keep Home working" in
[KOOLEST-README.md](KOOLEST-README.md).

## 4. Permissions (once)

```bash
# Music on the home screen: sound for the spectrum and the music-reactive background
adb shell pm grant dev.koolest android.permission.RECORD_AUDIO
# What's playing (title, artist, art, controls)
adb shell cmd notification allow_listener dev.koolest/dev.koolest.MediaListener
# The music card over the screensaver, and keeping the screensaver timer
adb shell appops set dev.koolest SYSTEM_ALERT_WINDOW allow
adb shell appops set dev.koolest WRITE_SETTINGS allow
```

## 5. One-time setup from the computer (optional)

Pick the theme, the apps shown, rows turned off and the screensaver delay in one command. `setup_stamp` must be new
each time (it stops Android from re-applying an old setup).

```bash
adb shell am start -n dev.koolest/.MainActivity --es setup_stamp first-1 \
  --es setup_theme dark \
  --es setup_keep "com.spotify.tv.android,org.smarttube.stable,com.netflix.ninja,PlayStation" \
  --es setup_rows_off "youtube,nama" \
  --es setup_screensaver_ms 180000
```

`setup_keep` lists package names or input names (part of the name is enough), in order. Everything else stays in
All apps.

## 6. Match day

Nothing to set up. Out of the box it covers the Premier League, LaLiga, Bundesliga, Serie A, Ligue 1 and the
Champions League, in the TV's own time zone.

- **Change the leagues:** edit `LEAGUES` in `MatchDay.kt`. Keys are ESPN league codes (`eng.1`, `esp.1`, `ger.1`,
  `ita.1`, `fra.1`, `uefa.champions`, also e.g. `uefa.europa`, `usa.1`, `ned.1`, `por.1`).
- **How long a match lasts** without a "final whistle" from the feed: `LENGTH` (2 h 15 min).

### Test it without waiting for a match

```bash
# Force match day on / off / back to automatic
adb shell am start -n dev.koolest/.MainActivity --es setup_stamp t1 --es matchday on
adb shell am start -n dev.koolest/.MainActivity --es setup_stamp t2 --es matchday off
adb shell am start -n dev.koolest/.MainActivity --es setup_stamp t3 --es matchday auto
# Pretend it's another local time (decides match day and scores for that day; the clock stays real)
adb shell am start -n dev.koolest/.MainActivity --es setup_stamp t4 --es matchday_at 2026-11-08T17:30
adb shell am start -n dev.koolest/.MainActivity --es setup_stamp t5 --es matchday_at now
```

## 7. Switching by hand

- **On the TV:** the ⚽ button. On the match-day home it's beside Settings (top right); on the weekday home it's the
  last button in the top bar.
- **On your phone:** the TV runs a small page on your home Wi-Fi. Its address is in the TV's log once:

  ```bash
  adb logcat -d | grep "phone remote"
  # phone remote: http://<this TV's address>:8765/<code>
  ```

  Open `http://<tv-ip>:8765/<code>` on your phone and add it to the home screen. The code is random per TV, so
  others on the Wi-Fi can't use it. Give the TV a fixed IP address in your router so the link keeps working.

A manual switch holds until the automatic answer next changes (a kick-off or the last final whistle).

## 8. Your team on automatically (optional)

30 minutes before every game of your team, the TV opens your IPTV player on the channel showing it.

**What it needs**

1. The `sideload` build (step 2).
2. The TV must allow the launcher's own adb connection: the first time it runs, the TV asks **"Allow USB debugging?"**
   for "Koolest TV Launcher". Tick **Always allow** and press OK.
3. An IPTV player app with a playlist you're licensed to watch. Written for **OttPlay** (`com.ottplay.ottplay`, also
   branded Televizo): it opens "All channels", uses the app's Search, taps the result and its full-screen button.

**Make it your team** (in `ArsenalWatch.kt`):

| Constant | Arsenal value | Change to |
|---|---|---|
| `ARSENAL` | `359` | Your team's ESPN id (open `https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams?limit=1000` or the team's ESPN page URL) |
| LiveSoccerTV team page | `teams/england/arsenal/` | Your team's page on livesoccertv.com |
| live-footballontv page | `arsenal-on-tv.html` | Your team's page (UK listings) |
| `LEAD` | 30 min | How early to open |
| `APP` | `com.ottplay.ottplay` | Your player's package (its screens will differ: see below) |

**Other players or screen sizes.** The flow reads the screen with `uiautomator` and looks for text ("All channels",
"Search…", "Clear query"). The channel page's full-screen button is a tap at `883 1020` (1080p). For another app,
walk its screens once by hand with `adb shell uiautomator dump` and adjust `openGroup`, `search` and that tap.

**Test it now** with any channel names (`|` between them), or `next` for the next game's broadcasters:

```bash
adb shell am start -n dev.koolest/.MainActivity --es setup_stamp a1 --es arsenal_test "Sky Sports Main Event|Sky Sports Premier League"
adb shell cat /sdcard/koolest_arsenal.log      # every step it took
```

**How it chooses:** broadcasters from LiveSoccerTV (Great Britain first, then every country) plus the UK listings;
each is searched; matching channels are tried FHD first, and one counts only when the player reports playing three
checks in a row with no error. If no FHD channel plays cleanly, any quality that plays. Nothing found: the app opens
on All channels with a note naming the broadcasters.

## 9. Undo

```bash
adb uninstall dev.koolest
# Bring back the stock launcher if you disabled it, e.g. Google TV:
adb shell pm enable com.google.android.tvlauncher
```

To revoke the launcher's adb access: Developer options → Revoke USB debugging authorizations (this also revokes
your computer; accept it again next time).

## Troubleshooting

| Symptom | Fix |
|---|---|
| Home button opens the old launcher | Re-run the two commands at the end of step 3 |
| No scores / never match day | The TV can't reach `site.api.espn.com`; check its internet. It falls back to the normal home |
| Phone page doesn't load | Phone and TV on the same Wi-Fi? TV's IP changed? |
| Auto-open did nothing | `adb shell cat /sdcard/koolest_arsenal.log`; "Allow USB debugging" not accepted with Always allow? |
| Stream says "format not supported" | Usually the IPTV provider refusing too many quick reconnects; the flow moves on to the next channel |
