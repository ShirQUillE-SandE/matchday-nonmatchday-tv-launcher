package dev.koolest

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor

// ---------- match day (this build) ----------
// Two home looks that switch by themselves: the usual Koolest home (non-match day), and on days with a match in
// one of the five big European leagues or the Champions League, from the day's first kick-off until its last
// match ends, the match-day home:
// Scenic Glass, a picture (or the playing album's art, blurred) with a floating glass dock and the day's scores.
// Fixtures and scores come from ESPN's public scoreboard (no key, unofficial): when it can't be reached the last
// fixtures saved are used, and with none the usual home stays. Times are the TV's own time zone.
//
// Testing over adb (one-time, like the setup extras; see MainActivity.takeSetup):
//   am start -n dev.koolest/.MainActivity --es setup_stamp <unique> --es matchday on|off|auto
//   am start -n dev.koolest/.MainActivity --es setup_stamp <unique> --es matchday_at 2026-11-04T22:30   (or "now")
// "matchday_at" pretends it's that local time for the match-day decision and the scores (the clock stays real).

/** One match: [state] is ESPN's "pre", "in" or "post"; [clock] the minute ("67'"), "HT" or "FT"; [src] where it came from. */
internal class Game(val league: String, val start: Long, val state: String, val clock: String, val home: Side, val away: Side, val src: String) {
    val live get() = state == "in"
}

internal class Side(val abbr: String, val score: String, val logo: String?, val color: Int)

internal object MatchDay {
    /** Europe's five big leagues and the Champions League: a match in any of them makes it match day. */
    private val LEAGUES = linkedMapOf("eng.1" to "Premier League", "esp.1" to "LaLiga", "ger.1" to "Bundesliga",
        "ita.1" to "Serie A", "fra.1" to "Ligue 1", "uefa.champions" to "Champions League")
    private const val HOUR = 3_600_000L
    /** Without a "post" from the feed a match counts as over this long after kick-off; one seen "in", a bit longer. */
    private const val LENGTH = 135 * 60_000L
    private const val LIVE_LENGTH = 3 * HOUR
    private val lock = Any()

    /** Now, or the pretend time set for testing. */
    fun now(prefs: SharedPreferences) = System.currentTimeMillis() + prefs.getLong("md_shift", 0L)

    private fun dayStart(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun over(g: Game, t: Long) = g.state == "post" || t >= g.start + if (g.live) LIVE_LENGTH else LENGTH

    /** [t]'s day's games here, and last night's still running past midnight. */
    private fun today(games: List<Game>, t: Long): List<Game> {
        val d = dayStart(t)
        return games.filter { it.start in d until d + 24 * HOUR || it.start in d - 6 * HOUR until d && !over(it, t) }
    }

    /**
     * Match day: from the day's first kick-off until all its matches are over. A manual switch (the button, the
     * phone page) wins until the automatic answer next changes; a forced one (testing) until set back to auto.
     */
    fun active(prefs: SharedPreferences, t: Long = now(prefs)): Boolean {
        val auto = automatic(prefs, t)
        val force = prefs.getString("md_force", "")
        if (force != "on" && force != "off") return auto
        if (prefs.contains("md_force_auto") && prefs.getBoolean("md_force_auto", false) != auto) {
            prefs.edit().remove("md_force").remove("md_force_auto").apply() // a kick-off or final whistle since: back to automatic
            return auto
        }
        return force == "on"
    }

    private fun automatic(prefs: SharedPreferences, t: Long): Boolean {
        val day = today(cached(prefs), t)
        return day.isNotEmpty() && day.minOf { it.start } <= t && day.any { !over(it, t) }
    }

    /** "matchday", "weekday" or "auto", from the switch button or the phone page. */
    fun setManual(prefs: SharedPreferences, mode: String) {
        val e = prefs.edit().remove("md_force").remove("md_force_auto")
        if (mode == "matchday" || mode == "weekday") {
            e.putString("md_force", if (mode == "matchday") "on" else "off").putBoolean("md_force_auto", automatic(prefs, now(prefs)))
        }
        e.apply()
    }

    /** What's showing and why, for the phone page. */
    fun describe(prefs: SharedPreferences): String {
        val on = active(prefs)
        val manual = prefs.getString("md_force", "") in setOf("on", "off")
        return (if (on) "Match day" else "Weekday") + if (manual) " (switched by hand)" else " (automatic)"
    }

    /** The day's games for the score cards: live ones first, then those still to come, then the finished ones. */
    fun shown(prefs: SharedPreferences): List<Game> = today(cached(prefs), now(prefs))
        .sortedWith(compareBy<Game> { when (it.state) { "in" -> 0; "pre" -> 1; else -> 2 } }.thenBy { it.start })

    fun cached(prefs: SharedPreferences): List<Game> = try {
        val a = JSONArray(prefs.getString("md_games", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            fun side(s: JSONObject) = Side(s.getString("a"), s.getString("s"), s.optString("l").takeIf { it.isNotEmpty() }, s.getInt("c"))
            Game(o.getString("league"), o.getLong("start"), o.getString("state"), o.getString("clock"),
                side(o.getJSONObject("h")), side(o.getJSONObject("w")), o.getString("src"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(prefs: SharedPreferences, games: List<Game>, day: Long) {
        val a = JSONArray()
        for (g in games) {
            fun side(s: Side) = JSONObject().put("a", s.abbr).put("s", s.score).put("l", s.logo ?: "").put("c", s.color)
            a.put(JSONObject().put("league", g.league).put("start", g.start).put("state", g.state).put("clock", g.clock)
                .put("h", side(g.home)).put("w", side(g.away)).put("src", g.src))
        }
        prefs.edit().putString("md_games", a.toString()).putLong("md_day", day).putLong("md_at", System.currentTimeMillis())
            .putString("md_leagues", LEAGUES.keys.joinToString(",")).apply()
    }

    /**
     * Fetches if it's time: a new day's fixtures (and every 6 hours, so a few times a day at most), and while it's
     * match day every minute, only the leagues and days still being played. Network: call off the UI thread.
     * True when something new was saved.
     */
    fun refreshIfDue(prefs: SharedPreferences): Boolean = synchronized(lock) {
        val t = now(prefs)
        val real = System.currentTimeMillis()
        val tried = real - prefs.getLong("md_try", 0L)
        val fresh = real - prefs.getLong("md_at", 0L)
        val day = dayStart(t)
        val live = active(prefs, t) && prefs.getString("md_force", "") != "on"
        // A changed league list (a new build) counts as a new day.
        val leagues = prefs.getString("md_leagues", "") != LEAGUES.keys.joinToString(",")
        val full = (prefs.getLong("md_day", 0L) != day || leagues || fresh > 6 * HOUR) && tried > 5 * 60_000L
        if (!full && !(live && fresh > 60_000L && tried > 50_000L)) return false
        prefs.edit().putLong("md_try", real).apply()
        try {
            if (full) {
                val sources = espnDays(t).flatMap { d -> LEAGUES.keys.map { "$it/$d" } }
                val got = sources.mapNotNull { src -> runCatching { load(src) }.getOrNull() }
                if (got.isEmpty()) return false // offline or ESPN gone: keep what's saved
                save(prefs, got.flatten(), day)
            } else {
                val games = cached(prefs)
                val sources = today(games, t).filter { !over(it, t) }.map { it.src }.distinct()
                val got = sources.mapNotNull { src -> runCatching { src to load(src) }.getOrNull() }.toMap()
                if (got.isEmpty()) return false
                save(prefs, games.filter { it.src !in got } + got.values.flatten(), prefs.getLong("md_day", day))
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("Koolest", "match day fetch failed ($e)")
            false
        }
    }

    /** ESPN's days (US Eastern) covering [t]'s day here, from 6 h before it for last night's late matches. */
    private fun espnDays(t: Long): List<String> {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val d = dayStart(t)
        return listOf(d - 6 * HOUR, d, d + 12 * HOUR, d + 24 * HOUR - 1).map { f.format(Date(it)) }.distinct()
    }

    /** One league's scoreboard for one ESPN day; [src] is "league/yyyyMMdd". */
    private fun load(src: String): List<Game> {
        val (slug, day) = src.split('/')
        val j = JSONObject((URL("https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/scoreboard?dates=$day").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }.inputStream.use { it.readBytes().decodeToString() })
        val events = j.optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { i ->
            runCatching {
                val e = events.getJSONObject(i)
                val status = e.getJSONObject("status")
                val type = status.getJSONObject("type")
                val state = type.optString("state", "pre")
                val clock = when {
                    state == "post" -> type.optString("shortDetail", "FT").takeIf { it.length <= 4 } ?: "FT"
                    type.optString("name") == "STATUS_HALFTIME" -> "HT"
                    state == "in" -> status.optString("displayClock")
                    else -> ""
                }
                val teams = e.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors")
                fun side(where: String): Side {
                    val c = (0 until teams.length()).map { teams.getJSONObject(it) }.first { it.optString("homeAway") == where }
                    val team = c.getJSONObject("team")
                    return Side(team.optString("abbreviation", "?"), c.optString("score", "0"),
                        team.optString("logo").takeIf { it.isNotEmpty() }, teamColor(team))
                }
                Game(LEAGUES[slug]!!, iso(e.getString("date")), state, clock, side("home"), side("away"), src)
            }.getOrNull()
        }
    }

    /** The team's colour for its half of the card; a near-white one swaps for its second colour. */
    private fun teamColor(team: JSONObject): Int {
        fun parse(s: String) = runCatching { Color.parseColor("#" + s) }.getOrNull()
        val main = parse(team.optString("color"))
        val alt = parse(team.optString("alternateColor"))
        fun light(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 200
        return listOfNotNull(main, alt).firstOrNull { !light(it) } ?: 0xFF2A3040.toInt()
    }

    private fun iso(s: String): Long {
        for (p in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            runCatching { return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time }
        }
        throw IllegalArgumentException(s)
    }

    /** The testing extras (see the top of this file). */
    fun takeTestExtras(e: SharedPreferences.Editor, force: String?, at: String?) {
        force?.let { e.putString("md_force", if (it == "on" || it == "off") it else "").remove("md_force_auto") }
        if (at == "now") e.remove("md_shift").putLong("md_day", 0L)
        else at?.let { s ->
            runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(s)!!.time }.getOrNull()?.let {
                e.putLong("md_shift", it - System.currentTimeMillis()).putLong("md_day", 0L).putLong("md_try", 0L)
            }
        }
    }
}

/** What a player is doing: the session that's playing, else one that's paused (so it can be played again). */
internal class Playing(val controller: MediaController, val title: String, val artist: String, val art: Bitmap?,
                       val position: Long, val at: Long, val speed: Float, val length: Long, val playing: Boolean)

internal fun playingNow(context: Context): Playing? = try {
    val sessions = context.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(android.content.ComponentName(context, MediaListener::class.java))
    (sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
        ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED && it.metadata != null })?.let { s ->
        val m = s.metadata!!
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return@let null
        val st = s.playbackState!!
        Playing(s, title, m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
            m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            st.position, st.lastPositionUpdateTime, st.playbackSpeed, m.getLong(MediaMetadata.METADATA_KEY_DURATION),
            st.state == PlaybackState.STATE_PLAYING)
    }
} catch (e: Exception) { // no notification access, or a session went away mid-read
    null
}

/**
 * A picture centre-cropped to [w]×[h] and blurred (box blur, [passes] times [r] px), tiny so it's cheap; drawn
 * back up filtered it's a soft, smooth blur. [dim] 0..1 darkens it for the white text over it.
 */
internal fun softened(src: Bitmap, w: Int, h: Int, r: Int, passes: Int, dim: Float = 0f): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val s = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
    val pw = src.width * s
    val ph = src.height * s
    Canvas(out).apply {
        drawBitmap(src, null, RectF((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        if (dim > 0f) drawColor(Color.argb((dim * 255).toInt(), 0, 0, 0))
    }
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    repeat(passes) {
        boxPass(px, tmp, w, h, r, true)
        boxPass(tmp, px, w, h, r, false)
    }
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

/** One box-blur pass along rows ([across]) or columns, clamped at the edges. */
private fun boxPass(from: IntArray, to: IntArray, w: Int, h: Int, r: Int, across: Boolean) {
    val lines = if (across) h else w
    val len = if (across) w else h
    val n = 2 * r + 1
    for (l in 0 until lines) {
        fun at(i: Int) = from[if (across) l * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + l]
        var sr = 0
        var sg = 0
        var sb = 0
        for (i in -r..r) {
            val c = at(i)
            sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
        }
        for (i in 0 until len) {
            to[if (across) l * w + i else i * w + l] = (255 shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
            val add = at(i + r + 1)
            val drop = at(i - r)
            sr += ((add shr 16) and 255) - ((drop shr 16) and 255)
            sg += ((add shr 8) and 255) - ((drop shr 8) and 255)
            sb += (add and 255) - (drop and 255)
        }
    }
}

/** Previous, play, pause and next, drawn: rounded triangles and bars in [color]. */
internal class TransportIcon(private val kind: Int, private val color: Int = Color.WHITE) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@TransportIcon.color
        pathEffect = android.graphics.CornerPathEffect(3f)
    }
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) * 0.36f
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        path.reset()
        fun tri(x0: Float, dir: Float) { // a triangle pointing [dir] (1 right, -1 left) from x0
            path.moveTo(x0, cy - s / 2); path.lineTo(x0 + dir * s * 0.8f, cy); path.lineTo(x0, cy + s / 2); path.close()
        }
        when (kind) {
            PLAY -> tri(cx - s * 0.32f, 1f)
            PAUSE -> {
                c.drawRoundRect(cx - s * 0.42f, cy - s / 2, cx - s * 0.12f, cy + s / 2, 2f, 2f, paint)
                c.drawRoundRect(cx + s * 0.12f, cy - s / 2, cx + s * 0.42f, cy + s / 2, 2f, 2f, paint)
            }
            NEXT -> {
                tri(cx - s * 0.5f, 1f)
                c.drawRoundRect(cx + s * 0.32f, cy - s / 2, cx + s * 0.48f, cy + s / 2, 2f, 2f, paint)
            }
            PREV -> {
                tri(cx + s * 0.5f, -1f)
                c.drawRoundRect(cx - s * 0.48f, cy - s / 2, cx - s * 0.32f, cy + s / 2, 2f, 2f, paint)
            }
        }
        c.drawPath(path, paint)
    }

    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val PREV = 0
        const val PLAY = 1
        const val PAUSE = 2
        const val NEXT = 3
    }
}

/**
 * The match-day home, "Scenic Glass": the picture clear, softly shaded; weather top start, clock and a round glass
 * Settings button top end; the music player when something plays (its album art then fills the screen, blurred);
 * a wide floating glass dock of full-artwork app tiles; and the day's scores (see [MatchDay]).
 * MainActivity fills the dock (its tiles are the home rows' tiles) and wires Settings and All apps.
 */
internal class MatchHome(context: Context, private val io: Executor, private val onSettings: () -> Unit,
                         private val onSwitch: () -> Unit) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val soft = 0xF2FFFFFF.toInt() // soft white, not glaring #FFF

    /** What the dock's glass shows through it: the screen behind, small and blurred. */
    private val glassBack = Backdrop()
    private val artBg = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val wxIcon = ImageView(context)
    private val wxText = text(20f, medium)
    private val clock = TextClock(context).apply {
        textSize = 22f
        typeface = medium
        setTextColor(soft)
        fontFeatureSettings = "tnum"
        includeFontPadding = false
    }
    val settings = ImageView(context)
    /** Back to the weekday home (until the next automatic change). */
    val switch = ImageView(context)
    private val player = LinearLayout(context)
    private val art = ImageView(context)
    private val title = text(30f, medium)
    private val artist = text(17f, regular).apply { alpha = 0.75f }
    private val playPause = ImageView(context)
    private val openApp = TextView(context)
    private val elapsed = text(13f, regular).apply { alpha = 0.75f; fontFeatureSettings = "tnum" }
    private val left = text(13f, regular).apply { alpha = 0.75f; fontFeatureSettings = "tnum" }
    private val barFill = View(context)
    val dock = FrameLayout(context)
    private val dockRow = LinearLayout(context)
    private val scores = LinearLayout(context)
    private val scoresScroll = HorizontalScrollView(context)
    private var shownKey: String? = null
    private var now: Playing? = null
    private var wallpaper: Bitmap? = null
    private val logos = LruCache<String, Bitmap>(40)

    init {
        clipChildren = false
        addView(artBg, LayoutParams(-1, -1))
        // Subtle shading only where text sits (top, and under the dock and scores), the picture otherwise clear.
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x73000000, 0x00000000))
        }, LayoutParams(-1, dp(140)))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x8C000000.toInt(), 0x00000000))
        }, LayoutParams(-1, dp(220), Gravity.BOTTOM))

        // Top start: weather, unframed.
        val wx = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(wxIcon, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(wxText, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            wxIcon.setColorFilter(soft)
        }
        addView(wx, LayoutParams(-2, dp(44), Gravity.TOP or Gravity.START).apply { topMargin = dp(28); marginStart = dp(48) })
        // Top end: the clock, then one round glass Settings button.
        val top = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(clock)
            switch.setImageResource(R.drawable.ic_s_ball)
            switch.setColorFilter(soft)
            switch.setPadding(dp(11), dp(11), dp(11), dp(11))
            switch.contentDescription = "Switch to the weekday home"
            glassButton(switch)
            switch.setOnClickListener { onSwitch() }
            addView(switch, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(18) })
            settings.setImageResource(R.drawable.rail_gear)
            settings.setColorFilter(soft)
            settings.setPadding(dp(11), dp(11), dp(11), dp(11))
            glassButton(settings)
            settings.setOnClickListener { onSettings() }
            addView(settings, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(12) })
        }
        addView(top, LayoutParams(-2, dp(44), Gravity.TOP or Gravity.END).apply { topMargin = dp(28); marginEnd = dp(48) })

        buildPlayer()
        addView(player, LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(88); marginStart = dp(48); marginEnd = dp(48) })

        // The dock's grounding shadow: a soft dark ellipse under its lower edge.
        addView(View(context).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(420).toFloat()
                colors = intArrayOf(0x59000000, 0x00000000)
                setGradientCenter(0.5f, 0.5f)
            }
            scaleY = 0.12f
        }, LayoutParams(dp(880), dp(260), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(32) })
        // The dock: wide floating glass, landscape tiles 144×81 with 16 dp gaps and 18 dp padding, scrolling sideways.
        dock.background = LiquidGlass(dp(30).toFloat(), 0x26FFFFFF, true, density, glassBack)
        // Tiles that scroll past the dock's rounded ends are cut off by its shape.
        dock.clipToOutline = true
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false // a focused tile grows into the padding
            isFillViewport = false
            setPadding(dp(18), dp(18), dp(18), dp(18))
            dockRow.clipChildren = false
            addView(dockRow, LayoutParams(-2, -2))
        }
        dock.addView(scroll, LayoutParams(-2, -2))
        // At most the screen less its margins: more tiles than fit scroll inside it.
        addView(dock, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(140)
            leftMargin = dp(48)
            rightMargin = dp(48)
        })

        // The day's scores, along the bottom.
        scoresScroll.apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(dp(48), dp(6), dp(48), dp(6))
            scores.clipChildren = false
            addView(scores, LayoutParams(-2, -2))
        }
        addView(scoresScroll, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(20) })
    }

    private fun text(size: Float, face: Typeface) = TextView(context).apply {
        textSize = size
        typeface = face
        setTextColor(soft)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    /** A round or pill glass button: clear glass at rest, solid soft white with dark ink on focus, a small lift. */
    private fun glassButton(v: View, ink: ((Int) -> Unit)? = null) {
        val rest = LiquidGlass(dp(40).toFloat(), 0x33FFFFFF, true, density)
        val lit = GradientDrawable().apply { cornerRadius = dp(40).toFloat(); setColor(soft) }
        v.background = rest
        v.isFocusable = true
        v.isClickable = true
        v.setOnFocusChangeListener { _, f ->
            v.background = if (f) lit else rest
            val c = if (f) 0xFF15171C.toInt() else soft
            if (v is ImageView) v.setColorFilter(c) else if (v is TextView) v.setTextColor(c)
            ink?.invoke(c)
            v.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(200).start()
        }
    }

    private fun buildPlayer() {
        player.orientation = LinearLayout.VERTICAL
        player.alpha = 0f
        player.visibility = GONE
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        art.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat())
        }
        art.clipToOutline = true
        art.elevation = dp(10).toFloat()
        row.addView(art, LinearLayout.LayoutParams(dp(120), dp(120)))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title)
        col.addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        fun button(kind: Int, size: Int, action: (MediaController.TransportControls) -> Unit) = ImageView(context).apply {
            setImageDrawable(TransportIcon(kind))
            glassButton(this)
            setOnClickListener { now?.controller?.transportControls?.let(action); ui.postDelayed({ update() }, 400) }
            controls.addView(this, LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(12) })
        }
        button(TransportIcon.PREV, 42) { it.skipToPrevious() }
        playPause.apply {
            setImageDrawable(TransportIcon(TransportIcon.PAUSE))
            glassButton(this)
            setOnClickListener {
                now?.let { p -> if (p.playing) p.controller.transportControls.pause() else p.controller.transportControls.play() }
                ui.postDelayed({ update() }, 400)
            }
            controls.addView(this, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        }
        button(TransportIcon.NEXT, 42) { it.skipToNext() }
        openApp.apply {
            textSize = 14f
            typeface = medium
            setTextColor(soft)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            glassButton(this)
            setOnClickListener { now?.controller?.packageName?.let { open(it) } }
            controls.addView(this, LinearLayout.LayoutParams(-2, dp(38)).apply { marginStart = dp(8) })
        }
        col.addView(controls, LinearLayout.LayoutParams(-2, dp(52)).apply { topMargin = dp(12) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(24) })
        player.addView(row)
        // Times and the progress line under it, full width.
        val times = FrameLayout(context)
        times.addView(elapsed, LayoutParams(-2, -2, Gravity.START))
        times.addView(left, LayoutParams(-2, -2, Gravity.END))
        player.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val bar = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x40FFFFFF) }
            barFill.background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(soft) }
            barFill.pivotX = 0f
            addView(barFill, LayoutParams(-1, -1))
        }
        player.addView(bar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })
    }

    private fun open(pkg: String) {
        val pm = context.packageManager
        (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.let {
            try { context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    // ---------- filled by MainActivity ----------

    fun setDock(tiles: List<View>) {
        dockRow.removeAllViews()
        tiles.forEachIndexed { i, t ->
            dockRow.addView(t, LinearLayout.LayoutParams(dp(144), -2).apply { if (i > 0) marginStart = dp(16) })
        }
    }

    fun focusDock() = dockRow.getChildAt(0)?.requestFocus()

    fun setWeather(icon: Int?, line: String?) {
        wxIcon.visibility = if (icon == null) GONE else VISIBLE
        icon?.let { wxIcon.setImageResource(it) }
        wxText.text = line.orEmpty()
    }

    /** The scenic picture behind, for the dock's glass when no album art is up. */
    fun setWallpaper(bmp: Bitmap?) {
        wallpaper = bmp
        if (now == null) frostFrom(bmp)
    }

    private fun frostFrom(bmp: Bitmap?) {
        if (bmp == null || bmp.isRecycled) return
        io.execute {
            val f = try { softened(bmp, 240, 135, 3, 2) } catch (e: Exception) { null } ?: return@execute
            ui.post { glassBack.frost = f }
        }
    }

    // ---------- music ----------

    private val progressTick = object : Runnable {
        override fun run() {
            showProgress()
            if (now != null && isShown) ui.postDelayed(this, 1000)
        }
    }

    /** Reads the player again (every 2 s from MainActivity's music check, and after a button). */
    fun update() {
        val p = playingNow(context)
        now = p
        if (p == null) {
            shownKey = null
            if (player.visibility == VISIBLE) {
                // Focus was on a player button: it goes to the dock as the player leaves.
                if (player.hasFocus()) focusDock()
                player.animate().alpha(0f).setDuration(300).withEndAction { if (now == null) player.visibility = GONE }.start()
                artBg.animate().alpha(0f).setDuration(600).start()
                frostFrom(wallpaper)
            }
            return
        }
        playPause.setImageDrawable(TransportIcon(if (p.playing) TransportIcon.PAUSE else TransportIcon.PLAY,
            if (playPause.isFocused) 0xFF15171C.toInt() else Color.WHITE))
        val key = p.title + "\u0000" + p.artist + "\u0000" + (p.art?.generationId ?: 0)
        if (key != shownKey) {
            shownKey = key
            title.text = p.title
            artist.text = p.artist
            openApp.text = "Open " + (try {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p.controller.packageName, 0))
            } catch (_: Exception) { "app" })
            if (p.art != null) art.setImageBitmap(p.art) else art.setImageDrawable(ColorDrawable(0x33FFFFFF))
            val src = p.art
            if (src != null) io.execute {
                // The album art, blurred, fills the screen; a little darker so the white text holds.
                val big = try { softened(src, 192, 108, 3, 2, dim = 0.25f) } catch (e: Exception) { null }
                val frost = try { softened(src, 240, 135, 4, 2, dim = 0.25f) } catch (e: Exception) { null }
                ui.post {
                    if (big != null) {
                        val old = artBg.drawable
                        val d = android.graphics.drawable.BitmapDrawable(resources, big).apply { paint.isFilterBitmap = true }
                        if (old != null && artBg.alpha > 0f) {
                            artBg.setImageDrawable(TransitionDrawable(arrayOf(old, d)).apply { isCrossFadeEnabled = true; startTransition(700) })
                        } else artBg.setImageDrawable(d)
                        artBg.animate().alpha(1f).setDuration(700).start()
                    }
                    if (frost != null) glassBack.frost = frost
                }
            }
        }
        if (player.visibility != VISIBLE) {
            player.visibility = VISIBLE
            player.animate().alpha(1f).setDuration(400).start()
        }
        ui.removeCallbacks(progressTick)
        ui.post(progressTick)
    }

    /** The home screen is going away (an app in front): the player's clock stops until the next [update]. */
    fun pause() = ui.removeCallbacks(progressTick)

    private fun showProgress() {
        val p = now ?: return
        val pos = if (p.playing) p.position + ((SystemClock.elapsedRealtime() - p.at) * p.speed).toLong() else p.position
        val at = if (p.length > 0) pos.coerceIn(0, p.length) else pos.coerceAtLeast(0)
        elapsed.text = time(at)
        left.text = if (p.length > 0) "−" + time(p.length - at) else ""
        barFill.scaleX = if (p.length > 0) at.toFloat() / p.length else 0f
    }

    private fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---------- scores ----------

    private var gamesKey: String? = null

    fun setGames(games: List<Game>) {
        val key = games.joinToString("|") { "${it.league}${it.start}${it.state}${it.clock}${it.home.abbr}${it.home.score}${it.away.score}" }
        if (key == gamesKey) return // unchanged: keep the cards (and focus) as they are
        gamesKey = key
        val focused = (0 until scores.childCount).firstOrNull { scores.getChildAt(it).hasFocus() }
        scores.removeAllViews()
        games.forEachIndexed { i, g -> scores.addView(card(g), LinearLayout.LayoutParams(dp(196), dp(92)).apply { if (i > 0) marginStart = dp(14) }) }
        scoresScroll.visibility = if (games.isEmpty()) INVISIBLE else VISIBLE
        if (focused != null) (scores.getChildAt(minOf(focused, scores.childCount - 1))?.requestFocus() ?: focusDock())
    }

    private fun card(g: Game): View {
        fun deep(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
        val card = FrameLayout(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(deep(g.home.color, 0.62f), deep(g.away.color, 0.62f))).apply {
                cornerRadius = dp(16).toFloat()
            }
            val ring = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0x33FFFFFF) }
            foreground = ring
            isFocusable = true
            setOnFocusChangeListener { v, f ->
                ring.setStroke(dp(if (f) 2 else 1), if (f) soft else 0x33FFFFFF)
                v.animate().scaleX(if (f) 1.05f else 1f).scaleY(if (f) 1.05f else 1f).setDuration(200).start()
            }
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        card.addView(text(11f, medium).apply { text = g.league; alpha = 0.9f }, LayoutParams(dp(120), -2, Gravity.TOP or Gravity.START))
        val pill = text(9f, Typeface.DEFAULT_BOLD).apply {
            letterSpacing = 0.08f
            setPadding(dp(7), dp(3), dp(7), dp(3))
            val (label, colour) = when (g.state) {
                "in" -> "LIVE" to 0xFFE5322D.toInt()
                "post" -> g.clock.ifEmpty { "FT" } to 0x40FFFFFF
                else -> android.text.format.DateFormat.getTimeFormat(context).format(Date(g.start)) to 0x40FFFFFF
            }
            text = label
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(colour) }
        }
        card.addView(pill, LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        val mid = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val homeLogo = ImageView(context)
        val awayLogo = ImageView(context)
        mid.addView(homeLogo, LinearLayout.LayoutParams(dp(30), dp(30)))
        mid.addView(text(24f, Typeface.DEFAULT_BOLD).apply {
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            text = if (g.state == "pre") "vs" else "${g.home.score} – ${g.away.score}"
            if (g.state == "pre") { textSize = 18f; alpha = 0.8f }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        mid.addView(awayLogo, LinearLayout.LayoutParams(dp(30), dp(30)))
        card.addView(mid, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { topMargin = dp(4) })
        val bottom = FrameLayout(context)
        bottom.addView(text(12f, medium).apply { text = g.home.abbr }, LayoutParams(-2, -2, Gravity.START))
        bottom.addView(text(12f, medium).apply {
            text = if (g.live) g.clock else ""
            fontFeatureSettings = "tnum"
            alpha = 0.85f
        }, LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        bottom.addView(text(12f, medium).apply { text = g.away.abbr }, LayoutParams(-2, -2, Gravity.END))
        card.addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))
        logo(homeLogo, g.home.logo)
        logo(awayLogo, g.away.logo)
        return card
    }

    /** A team badge: memory, then a file kept in the cache folder, then ESPN (resized to 80 px by its image server). */
    private fun logo(into: ImageView, url: String?) {
        if (url == null) return
        logos.get(url)?.let { into.setImageBitmap(it); return }
        io.execute {
            val bmp = try {
                val file = File(context.cacheDir, "logos/" + Integer.toHexString(url.hashCode()) + ".png")
                if (!file.exists()) {
                    val path = url.substringAfter("espncdn.com", "")
                    val small = if (path.startsWith("/i/")) "https://a.espncdn.com/combiner/i?img=$path&w=80&h=80" else url
                    val bytes = (URL(small).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                        .inputStream.use { it.readBytes() }
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                }
                BitmapFactory.decodeFile(file.path)
            } catch (e: Exception) {
                null
            } ?: return@execute
            ui.post {
                logos.put(url, bmp)
                into.setImageBitmap(bmp)
            }
        }
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(progressTick)
        super.onDetachedFromWindow()
    }
}
