# The prompts

This build was made by talking to an AI coding assistant (Claude Code) with the TV connected over adb. These are the
prompts, in order, lightly cleaned up. Reuse them for your own team, leagues or look; the assistant also needs to
know your TV model, its IP address and that it may ask before each change on the TV.

## 1. The two themes

> Add two automatically-switching themes to my Koolest TV launcher build, then install it on my TV.
>
> 1. **The weekday theme** = my current setup, saved as a named theme: dark theme, home rows and menu showing only
>    Spotify, SmartTube, Netflix and the PlayStation 5 (HDMI tile); YouTube and 30nama rows off; music card and
>    music-reactive background on home; the music card at 50 % over the screensaver. Active Monday–Friday.
> 2. **The match-day theme** = the look described in the prompt below. Active on any day with a Premier League or
>    Champions League match, from the first kick-off of the day until the last match of the day ends. Takes
>    priority over the weekday theme.
>
> Fixtures from ESPN's public scoreboard (`site.api.espn.com/apis/site/v2/sports/soccer/<league>/scoreboard?dates=YYYYMMDD`).
> If it's unreachable, keep the normal theme; never break. Fetch at most a few times a day plus at boot. No new
> libraries (plain HttpURLConnection + org.json), no always-on services. Verify switching on the TV with screenshots,
> restart the TV once and verify again.

Answers given along the way: weekends without a match use the weekday theme; times follow the TV's own time zone;
build the home screen only (Settings and All apps reuse Koolest's screens).

## 2. The match-day look ("Scenic Glass")

A reference screenshot was attached: a blurred album-art background, a large player, a glass dock of app tiles and
a row of live score cards. The prompt:

> Build a native Android/Google TV launcher called Personal TV Home using Kotlin and Compose for TV. Its visual
> identity is "Scenic Glass": spacious photography, a large floating glass app dock, restrained typography, and
> exceptionally fluid motion. It should feel carefully crafted and Apple-inspired while having its own identity.
>
> **HOME AND GLASS DOCK**
> Use a full-screen scenic wallpaper with subtle shading for readability. Keep the wallpaper clear rather than
> covering the bottom with a broad muddy blur. Place a wide floating glass dock in the lower part of the screen,
> leaving generous open space above it.
> The dock must feel like translucent glass: softly blurred scenery within it, a restrained tint, delicate bright
> edges, rounded corners, and a soft grounding shadow. Avoid thick borders, excessive glow, and opaque gray panels.
> Use large landscape app tiles, approximately 144×84 dp, with 16 dp gaps and 18 dp dock padding. Fill each tile edge
> to edge with the app's actual TV artwork. Preserve brand proportions and original background colors. Do not put
> tiny logos inside generic black boxes or add duplicate app labels inside the dock. Favorites scroll horizontally,
> with an All Apps control at the end.
> Place compact, unframed weather at the upper left. At the upper right, show a simple clock and one circular glass
> Settings button. Do not crowd the header with Home, Wi-Fi, Inputs, or navigation tabs.
>
> **TYPOGRAPHY AND SCREENS**
> Use Inter, soft white text, tabular time figures, consistent rounded shapes, and generous spacing. Keep text
> readable from a sofa. Icons should share a clean, consistent style.
> Settings opens as a rounded glass side panel, with Chromecast system settings first. Include routes to Home,
> Discover, My Space, Apps, search, wallpapers, and appearance customization.
> Apps uses a spacious grid of full-artwork tiles with names beneath them. My Space contains saved titles and
> collections. These screens must share Home's materials, typography, focus treatment, and motion. Empty sections
> should be quiet and useful.
>
> **GLASS CURRENT MUSIC PLAYER**
> When music is active, let the actual album artwork become a softly blurred background filling the entire screen.
>
> For the live matches use a source you can access, like LiveScore or Flashscore.

How it was adapted: the launcher is View-based Kotlin (Koolest), not Compose; the system font stands in for Inter
(no font files added); live scores come from ESPN's public scoreboard (LiveScore and Flashscore have no open API).

## 3. More leagues

> For the score cards row add matches, and apply the rules for all matches and match days, for the 5 major leagues
> in Europe: Germany, Italy, England, France, Spain, and an extra one.

(The extra one: the Champions League.)

## 4. My team, on automatically

> For the match-day theme, I need you to automatically open the Televizo IPTV app and open the best channel every
> time Arsenal plays. Opening the app and choosing the channel airing the Arsenal game is the priority. This should
> be done 30 minutes before kick-off.

Answers: switch even if the TV is busy with something else; if no channel is found, open the app anyway with a note.

> The best way to check which channel is showing Arsenal: use a website that lists the channels, then use the
> "All channels" option of the IPTV app on the TV and search there for the channels from the web research. Try
> different options until you find one streaming clean FHD; if not, use any quality.

(Built with public TV-listings sites: LiveSoccerTV for broadcasters worldwide, live-footballontv.com for the UK.)

## 5. A switch button

> I need a button to easily switch the 2 TV modes. If you can make the button on my phone too, that would be
> perfect, so that I don't need to use my computer if I need to switch the themes by hand.

## 6. Publish it

> Put this on my GitHub as a free resource: commit it with the README, instructions and screenshots. Call it
> matchday/nonmatchday TV launcher. Include all the instructions, the prompts and all the code, to help all the
> tech-savvy people out there.
