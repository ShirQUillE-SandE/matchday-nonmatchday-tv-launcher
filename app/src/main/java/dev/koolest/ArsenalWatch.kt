package dev.koolest

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// ---------- Arsenal on the IPTV app (this build, part of match day) ----------
// 30 minutes before every Arsenal kick-off (any competition), the TV opens the IPTV app (OttPlay, shown as
// "Televizo") on the channel showing the game, full screen, whatever was on. Fixtures: ESPN's team schedule.
// Channel: LiveSoccerTV lists every broadcaster worldwide (Great Britain's first), live-footballontv.com the UK's;
// each is searched in the app's "All channels" and tried until one plays cleanly, FHD versions first, then any
// quality. Not found: the app opens on its channels and a note names the broadcasters.
// The app has no way to be told a channel, so this drives its screens like the remote does, through the TV's own
// adb (SelfAdb: input taps and text, uiautomator to read the screen). Timing is an exact alarm, re-set after
// every run, at boot and whenever the home screen shows; nothing runs in between.
//
// Testing: am start -n dev.koolest/.MainActivity --es setup_stamp <unique> --es arsenal_test "Sky Sports Main Event"
// runs the opening now with that channel (or the next game's channels with "next").

internal object ArsenalWatch {
    private const val APP = "com.ottplay.ottplay"
    private const val ARSENAL = 359 // ESPN's team id
    private const val LEAD = 30 * 60_000L
    private const val HOUR = 3_600_000L
    /** The app's group with every channel, where the search looks. */
    private const val ALL = "All channels"
    private const val TRACE = "/sdcard/koolest_arsenal.log"
    private val ui = Handler(Looper.getMainLooper())
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun prefs(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    /** Fetches the next game if what's saved is old, and sets the alarm. Safe to call often; off the UI thread. */
    fun schedule(c: Context) {
        val app = c.applicationContext
        Thread {
            try {
                if (System.currentTimeMillis() - prefs(app).getLong("ars_at", 0L) > 6 * HOUR) refresh(app)
            } catch (e: Exception) {
                Log.w("Koolest", "arsenal fetch failed ($e)")
            }
            setAlarm(app)
        }.start()
    }

    /** The alarm went off: refresh near kick-off (channels can change), open the game if it's time, set the next. */
    internal fun alarm(c: Context) {
        val app = c.applicationContext
        Thread {
            runCatching { refresh(app) }.onFailure { Log.w("Koolest", "arsenal fetch failed ($it)") }
            val p = prefs(app)
            val kickoff = p.getLong("ars_next", 0L)
            val now = System.currentTimeMillis()
            if (kickoff > 0 && now >= kickoff - LEAD - 60_000L && now < kickoff + 100 * 60_000L && p.getLong("ars_done", 0L) != kickoff) {
                p.edit().putLong("ars_done", kickoff).apply()
                open(app, p.getString("ars_channels", "")!!.split('|').filter { it.isNotBlank() }, p.getString("ars_name", "Arsenal")!!)
            }
            setAlarm(app)
        }.start()
    }

    private fun setAlarm(c: Context) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val kickoff = p.getLong("ars_next", 0L)
        val openAt = kickoff - LEAD
        // The opening time if one's coming (or already due: the TV was off at that moment), else a refresh later.
        val at = when {
            kickoff > 0 && p.getLong("ars_done", 0L) != kickoff && now < kickoff + 100 * 60_000L ->
                if (now >= openAt) now + 5_000L else minOf(openAt, now + 6 * HOUR)
            else -> now + 6 * HOUR
        }
        val pi = PendingIntent.getBroadcast(c, 7, Intent(c, ArsenalAlarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = c.getSystemService(AlarmManager::class.java)
        // Exact needs a permission from Android 12 (this TV is 11); without it, a few minutes late is still fine.
        if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        Log.i("Koolest", "arsenal: next ${p.getString("ars_name", "?")} at $kickoff, alarm at $at")
    }

    // ---------- the next game and its channels ----------

    private fun refresh(c: Context) {
        val events = getJson("https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/$ARSENAL/schedule?fixture=true").optJSONArray("events") ?: return
        val now = System.currentTimeMillis()
        val next = (0 until events.length()).map { events.getJSONObject(it) }.mapNotNull { e ->
            val state = e.getJSONArray("competitions").getJSONObject(0).optJSONObject("status")?.optJSONObject("type")?.optString("state")
                ?: e.optJSONObject("status")?.optJSONObject("type")?.optString("state")
            val t = runCatching { iso(e.getString("date")) }.getOrNull() ?: return@mapNotNull null
            if (state == "post" || t < now - 2 * HOUR) null else t to e.optString("name", "Arsenal")
        }.minByOrNull { it.first }
        val e = prefs(c).edit().putLong("ars_at", now)
        if (next == null) {
            e.remove("ars_next").apply()
            return
        }
        // Every broadcaster worldwide (Great Britain first), then the UK listings' own names.
        val channels = (runCatching { worldChannels(next.first) }.getOrDefault(emptyList()) +
            runCatching { ukChannels(next.first) }.getOrDefault(emptyList())).distinctBy { words(it) }
        // Keep channels found earlier for the same game if the listings can't be read now.
        val keep = if (channels.isEmpty() && prefs(c).getLong("ars_next", 0L) == next.first) prefs(c).getString("ars_channels", "") else channels.joinToString("|")
        e.putLong("ars_next", next.first).putString("ars_name", next.second).putString("ars_channels", keep).apply()
    }

    /**
     * Every TV channel showing Arsenal's game kicking off at [kickoff], worldwide, from LiveSoccerTV (its team page
     * finds the match, the match page lists the broadcasters as schema.org data): Great Britain's first, then the
     * rest in the page's order. Streaming services and radio are left out: they're not IPTV channels.
     */
    private fun worldChannels(kickoff: Long): List<String> {
        fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) Koolest")
        }.inputStream.use { it.readBytes().decodeToString() }
        val team = get("https://www.livesoccertv.com/teams/england/arsenal/")
        val link = Regex("""<a href="(/match/[^"#]+)[^"]*"[^>]*>\s*<span class="lc-tm-dt">\s*<span class="lc-tm-date ts" dv="(\d+)"""").findAll(team)
            .map { it.groupValues[1] to it.groupValues[2].toLong() }
            .filter { "arsenal" in it.first && abs(it.second - kickoff) < 3 * HOUR } // the page lists other clubs' games too
            .minByOrNull { abs(it.second - kickoff) }?.first ?: return emptyList()
        val match = get("https://www.livesoccertv.com" + link.trimEnd('/'))
        val skip = Regex("(?i)radio|paramount\\+|disney\\+|hbo max|\\bmax\\b|oneplay|fubo|mycanal|peacock|prime video|\\bapp\\b")
        return Regex(""""publishedOn":\{"@type":"BroadcastService","name":"([^"]+)","url":"[^"]*","areaServed":\{"@type":"Country","name":"([^"]+)"""")
            .findAll(match).map { it.groupValues[1] to it.groupValues[2] }
            .filter { !skip.containsMatchIn(it.first) }
            .sortedBy { if (it.second == "Great Britain" || it.second == "United Kingdom") 0 else 1 }
            .map { android.text.Html.fromHtml(it.first, 0).toString() }.distinct().toList()
    }

    /** The UK channels showing Arsenal's game kicking off at [kickoff], from live-footballontv.com's Arsenal page. */
    private fun ukChannels(kickoff: Long): List<String> {
        val page = (URL("https://www.live-footballontv.com/arsenal-on-tv.html").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) Koolest")
        }.inputStream.use { it.readBytes().decodeToString() }
        val day = SimpleDateFormat("yyyyMMMdd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Europe/London") }
        var date: String? = null
        // Walk the page in order: a date anchor, then that day's fixtures (time, teams, channel pills).
        val token = Regex("""<a id="(\d{4}[A-Z][a-z]{2}\d{2})">|fixture__time">([^<]*)<|fixture__teams">([^<]*)<|channel-pill"[^>]*>([^<]+)<""")
        var time: String? = null
        var teams: String? = null
        var channels = mutableListOf<String>()
        val found = mutableListOf<Pair<Long, List<String>>>()
        fun close() {
            val t = runCatching { day.parse("$date $time")!!.time }.getOrNull()
            if (t != null && teams?.contains("Arsenal") == true) found += t to channels.toList()
        }
        for (m in token.findAll(page)) {
            val (d, tm, tt, ch) = m.destructured
            when {
                d.isNotEmpty() -> date = d
                tm.isNotEmpty() -> { if (time != null) close(); time = tm.trim(); teams = null; channels = mutableListOf() }
                tt.isNotEmpty() -> teams = tt.trim()
                ch.isNotEmpty() -> channels += android.text.Html.fromHtml(ch, 0).toString().trim()
            }
        }
        if (time != null) close()
        return found.filter { abs(it.first - kickoff) < 3 * HOUR }.minByOrNull { abs(it.first - kickoff) }?.second.orEmpty()
    }

    private fun getJson(url: String) = JSONObject((URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 8000
    }.inputStream.use { it.readBytes().decodeToString() })

    private fun iso(s: String): Long {
        for (p in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            runCatching { return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time }
        }
        throw IllegalArgumentException(s)
    }

    // ---------- driving the IPTV app ----------

    private class Node(val text: String, val desc: String, val bounds: Rect) {
        val label get() = text.ifEmpty { desc }
    }

    /**
     * Opens [game] full screen on the first of [channels] (broadcaster names, best first) that plays cleanly: each
     * is searched in the app's "All channels", FHD versions first; a channel that errors or keeps buffering is
     * left for the next. With no clean FHD one, any quality that plays. Blocks: call off the UI thread.
     */
    fun open(c: Context, channels: List<String>, game: String) {
        if (!SelfAdb.AVAILABLE || !busy.compareAndSet(false, true)) return
        val until = System.currentTimeMillis() + 12 * 60_000L // never more than 12 minutes of trying
        try {
            sh(c, "rm -f $TRACE")
            trace(c, "opening $game on $channels")
            if (!openGroup(c, ALL)) {
                trace(c, "couldn't open All channels")
                note(c, "Arsenal: $game — couldn't open the IPTV app")
                return
            }
            val failed = mutableSetOf<String>()
            for (fhdOnly in listOf(true, false)) {
                for (listing in channels) {
                    if (System.currentTimeMillis() > until) break
                    val found = search(c, listing).filter { (!fhdOnly || quality(it.text) == 3) && it.text !in failed }
                        .sortedByDescending { quality(it.text) }
                    for (ch in found.take(4)) {
                        if (System.currentTimeMillis() > until) break
                        trace(c, "trying ${ch.text}")
                        tap(c, ch.bounds)
                        Thread.sleep(4000)
                        sh(c, "input tap 883 1020") // the channel page's full-screen button
                        if (playsClean(c)) {
                            trace(c, "playing ${ch.text}")
                            note(c, "Arsenal: $game — ${ch.text}")
                            return
                        }
                        failed += ch.text
                        trace(c, "not clean: ${ch.text}")
                        // Back to the search results (full screen, then the channel page), for the next one.
                        sh(c, "input keyevent KEYCODE_BACK")
                        Thread.sleep(1500)
                        sh(c, "input keyevent KEYCODE_BACK")
                        Thread.sleep(2500)
                    }
                }
            }
            // Nothing found or nothing plays: the app's channels, and a note saying where it's on.
            trace(c, "nothing played")
            openGroup(c, ALL)
            note(c, "Arsenal: $game" + if (channels.isEmpty()) "" else " — on ${channels.take(4).joinToString(", ")} (no working channel found)")
        } catch (e: Exception) {
            Log.w("Koolest", "arsenal: opening failed ($e)")
            runCatching { trace(c, "failed: $e") }
        } finally {
            busy.set(false)
        }
    }

    /** Starts the app afresh and opens [group]'s channel list. */
    private fun openGroup(c: Context, group: String): Boolean {
        sh(c, "am force-stop $APP", "am start -n $APP/.StartActivity") ?: return false
        val node = waitFor(c, 25_000) { n -> n.firstOrNull { it.text.equals(group, true) } } ?: return false
        tap(c, node.bounds)
        return waitFor(c, 15_000) { n -> n.firstOrNull { it.label.startsWith("Search") } } != null
    }

    /** Searches the open list for [listing] ("TNT Sports 1"): the channels that are it, in any quality or country. */
    private fun search(c: Context, listing: String): List<Node> {
        val want = words(listing)
        if (want.isEmpty()) return emptyList()
        val queries = listOf(listing.uppercase(), listing.uppercase().replace("SPORTS", "SPORT"), want.joinToString(" ").uppercase())
            .map { q -> q.replace(Regex("[^A-Z0-9 ]"), "").trim() }.filter { it.isNotEmpty() }.distinct()
        for (q in queries) {
            val screen = dump(c)
            val box = screen.firstOrNull { it.label == "Clear query" } ?: screen.firstOrNull { it.label.startsWith("Search") } ?: return emptyList()
            tap(c, box.bounds) // opens the search box, or empties it
            Thread.sleep(800)
            sh(c, "input text '${q.replace(" ", "%s")}'")
            // Typing and filtering take a few seconds on this TV: look until the results are in. Results only,
            // under the search bar (the box itself holds the query).
            waitFor(c, 8000) { screen -> screen.filter { it.bounds.top >= 100 && same(want, it.text) }.takeIf { it.isNotEmpty() } }?.let { return it }
        }
        return emptyList()
    }

    /**
     * Whether the app's [name] ("UK: TNT SPORT 1 FHD") is the listing [want] ("TNT Sports 1"): all its words, and
     * at most two more that carry no number (a country tag, "premium"); "TNT Sport 10" is not "TNT Sport 1".
     */
    private fun same(want: Set<String>, name: String): Boolean {
        val have = words(name)
        val extra = have - want
        return have.containsAll(want) && extra.size <= 2 && extra.none { w -> w.any { it.isDigit() } }
    }

    /** A channel's name as comparable words: lower case, "sports" as "sport", quality marks and countries dropped. */
    private fun words(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), " ").split(' ')
        .filter { it.isNotEmpty() && it !in NOISE }
        .map { if (it == "sports") "sport" else it }.toSet()

    private val NOISE = setOf("fhd", "hd", "uhd", "sd", "4k", "hdr", "ultra", "raw", "uk", "gb", "usa", "us", "canada", "ca",
        "germany", "de", "italy", "it", "france", "fr", "spain", "es", "czech", "cz", "portugal", "pt", "tv")

    private fun quality(name: String): Int {
        val w = name.lowercase().replace(Regex("[^a-z0-9]+"), " ").split(' ')
        return when {
            "fhd" in w -> 3
            "hd" in w -> 2
            "uhd" in w || "4k" in w -> 1 // heavy for this TV
            else -> 0
        }
    }

    /**
     * Watches the app's player for up to 25 s: clean once it has reported playing on three checks in a row; not
     * when it errors, or keeps buffering.
     */
    private fun playsClean(c: Context): Boolean {
        val end = System.currentTimeMillis() + 25_000
        var playing = 0
        while (System.currentTimeMillis() < end) {
            // Only the app's own line: the whole dump is large, and other players' states don't count.
            val out = sh(c, "dumpsys media_session | grep -A12 'package=$APP' | grep -m1 'state=PlaybackState'")
            val state = out?.let { Regex("""state=PlaybackState \{state=(\d+)""").find(it)?.groupValues?.get(1)?.toInt() }
            when (state) {
                android.media.session.PlaybackState.STATE_PLAYING -> if (++playing >= 3) return true
                android.media.session.PlaybackState.STATE_ERROR -> return false
                else -> playing = 0
            }
            Thread.sleep(2500)
        }
        return false
    }

    /** Each run's steps, in a file on the TV (adb pull /sdcard/koolest_arsenal.log): this TV drops app log lines. */
    private fun trace(c: Context, line: String) {
        Log.i("Koolest", "arsenal: $line")
        val clean = line.replace(Regex("[^A-Za-z0-9 :,.|()\\[\\]+-]"), " ")
        sh(c, "echo \"$(date +%T) $clean\" >> $TRACE")
    }

    private fun tap(c: Context, b: Rect) = sh(c, "input tap ${b.centerX()} ${b.centerY()}")

    private fun <T> waitFor(c: Context, ms: Long, pick: (List<Node>) -> T?): T? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            pick(dump(c))?.let { return it }
            Thread.sleep(1200)
        }
        return null
    }

    /** What's on screen, from uiautomator. */
    private fun dump(c: Context): List<Node> {
        val xml = sh(c, "uiautomator dump /sdcard/koolest_ui.xml >/dev/null 2>&1; cat /sdcard/koolest_ui.xml") ?: return emptyList()
        val attr = Regex("""(\S+)="([^"]*)"""")
        return Regex("""<node ([^>]*)>""").findAll(xml).mapNotNull { m ->
            val a = attr.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val b = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(a["bounds"].orEmpty())?.groupValues ?: return@mapNotNull null
            Node(unescape(a["text"].orEmpty()), unescape(a["content-desc"].orEmpty()), Rect(b[1].toInt(), b[2].toInt(), b[3].toInt(), b[4].toInt()))
        }.filter { it.label.isNotEmpty() }.toList()
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /** Runs shell commands through the TV's own adb; their output, or null when adb can't be reached. */
    private fun sh(c: Context, vararg commands: String): String? {
        val latch = CountDownLatch(1)
        var out: String? = null
        SelfAdb.run(c, commands.toList()) { r ->
            out = r.getOrNull()
            r.exceptionOrNull()?.let { Log.w("Koolest", "arsenal: adb failed ($it)") }
            latch.countDown()
        }
        latch.await(90, TimeUnit.SECONDS)
        return out
    }

    private fun note(c: Context, text: String) = ui.post { Toast.makeText(c, text, Toast.LENGTH_LONG).show() }
}

/** The Arsenal alarm (see [ArsenalWatch]). */
class ArsenalAlarm : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = ArsenalWatch.alarm(c)
}
