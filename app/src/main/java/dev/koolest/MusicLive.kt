package dev.koolest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The music on the home screen: what's playing (Spotify or any player) on a glass card with spectrum bars, and a
 * background that pulses with it. The sound comes from Android's Visualizer on the output mix (session 0), which
 * needs RECORD_AUDIO, granted once over adb: pm grant dev.koolest android.permission.RECORD_AUDIO.
 * Some TVs hand over only silence (or nothing): then [live] stays false and the bars and background move gently on
 * their own instead of standing still.
 */
internal class Spectrum {
    val bands = FloatArray(BANDS) // 0..1 per band, low to high, smoothed for drawing
    var bass = 0f // 0..1, the low bands, for the background's pulse
        private set
    var level = 0f // 0..1, everything
        private set
    var live = false // real sound is arriving
        private set
    val running get() = started

    /**
     * Everything that draws the music, redrawn together on one 20-per-second tick: a full-screen redraw is the
     * costly part on a TV, and two views on their own timers made two of them.
     */
    val views = mutableListOf<View>()
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!started) return
            val now = SystemClock.uptimeMillis()
            // The sound can be busy for a moment (the home screen's own spectrum still holds it as the screensaver
            // starts): keep asking every 2 s until it's free.
            if (viz == null && now - lastTry > 2000) open()
            step(now)
            for (v in views) if (v.isShown) v.invalidate()
            ticker.postDelayed(this, 50)
        }
    }

    private val target = FloatArray(BANDS)
    private var viz: Visualizer? = null
    private var started = false
    private var lastSound = 0L // when the capture last had any sound in it
    private var lastStep = 0L
    private var peak = 0.35f // running loudness peak, so quiet and loud songs both fill the bars
    private var lastTry = 0L
    private var warned = false

    fun start() {
        if (started) return
        started = true
        lastSound = 0L
        ticker.post(tick)
        open()
    }

    private fun open() {
        lastTry = SystemClock.uptimeMillis()
        var v: Visualizer? = null
        try {
            v = Visualizer(0)
            v.captureSize = min(1024, Visualizer.getCaptureSizeRange()[1])
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer, wave: ByteArray, rate: Int) {}
                override fun onFftDataCapture(v: Visualizer, fft: ByteArray, rate: Int) = take(fft, rate)
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            viz = v
        } catch (e: Throwable) { // no RECORD_AUDIO, the TV doesn't offer the output mix, or it's busy right now
            if (!warned) Log.w("Koolest", "visualizer unavailable, retrying ($e)")
            warned = true
            try { v?.release() } catch (_: Throwable) {}
            viz = null
        }
    }

    fun stop() {
        started = false
        live = false
        ticker.removeCallbacks(tick)
        viz?.let {
            try { it.enabled = false } catch (_: Throwable) {}
            it.release()
        }
        viz = null
    }

    /** One FFT frame: [fft] is Visualizer's packed real/imaginary pairs, [rate] the sample rate in mHz. */
    private fun take(fft: ByteArray, rate: Int) {
        val n = fft.size / 2
        if (n < 8) return
        val hzPerBin = rate / 1000f / fft.size
        var loud = 0f
        for (b in 0 until BANDS) {
            // Log-spaced bands from 40 Hz to 12 kHz: as many bars for the bass as for the treble, like our hearing.
            val lo = (40f * (300f).pow(b / BANDS.toFloat()) / hzPerBin).toInt().coerceIn(1, n - 1)
            val hi = (40f * (300f).pow((b + 1) / BANDS.toFloat()) / hzPerBin).toInt().coerceIn(lo + 1, n)
            var m = 0f
            for (k in lo until hi) m = max(m, hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
            val v = ln(1f + m) / ln(129f)
            target[b] = v
            loud = max(loud, v)
        }
        if (loud > 0.02f) lastSound = SystemClock.uptimeMillis()
        peak = max(loud, peak * 0.995f).coerceAtLeast(0.2f)
        for (b in 0 until BANDS) target[b] = (target[b] / peak).coerceIn(0f, 1f)
    }

    /** Moves the drawn values toward the sound; once per [tick]. */
    private fun step(now: Long) {
        if (now - lastStep < 8) return
        lastStep = now
        live = viz != null && lastSound != 0L && now - lastSound < 2500
        if (!live) {
            // No sound to show: a slow, gentle wave so the screen still breathes while music plays.
            val t = now / 1000f
            for (b in 0 until BANDS) {
                target[b] = 0.18f + 0.14f * (sin(t * 2.1f + b * 0.55f) * 0.5f + 0.5f) +
                    0.12f * (sin(t * 3.7f - b * 0.31f) * 0.5f + 0.5f) * (1f - b / BANDS.toFloat())
            }
        }
        var sumLow = 0f
        var sum = 0f
        for (b in 0 until BANDS) {
            val to = if (started) target[b] else 0f
            // Rise fast, fall slowly: bars jump with the beat and settle between beats.
            bands[b] += (to - bands[b]) * if (to > bands[b]) 0.55f else 0.14f
            sum += bands[b]
            if (b < BANDS / 4) sumLow += bands[b]
        }
        bass = (sumLow / (BANDS / 4)).coerceIn(0f, 1f)
        level = (sum / BANDS).coerceIn(0f, 1f)
    }

    companion object {
        const val BANDS = 28
    }
}

/** The spectrum as rounded bars standing on a baseline, in [color]. Redrawn by [spectrum]'s tick while it runs. */
internal class SpectrumBars(context: Context, private val spectrum: Spectrum) : View(context) {
    init {
        spectrum.views += this
    }

    var color = Color.WHITE
        set(v) {
            field = v
            paint.color = v
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@SpectrumBars.color }
    private val bar = RectF()

    override fun onDraw(c: Canvas) {
        val n = Spectrum.BANDS
        val gap = width / (n * 4f)
        val w = (width - gap * (n - 1)) / n
        val minH = w // never below a dot
        for (b in 0 until n) {
            val v = spectrum.bands[b]
            val h = minH + v * (height - minH)
            val x = b * (w + gap)
            bar.set(x, height - h, x + w, height.toFloat())
            paint.alpha = (110 + 145 * v).toInt().coerceIn(0, 255)
            c.drawRoundRect(bar, w / 2, w / 2, paint)
        }
    }
}

/**
 * The live background while music plays: soft colour clouds taken from the cover, drifting slowly and swelling
 * with the bass. Drawn small (96 px wide) and scaled up filtered, which blurs it for free and keeps it light on a TV.
 */
internal class MusicBackdrop(context: Context, private val spectrum: Spectrum) : View(context) {
    private val small = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
    private val sc = Canvas(small)
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val up = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    private var colors = intArrayOf(0xFF1DB954.toInt(), 0xFF7B4DFF.toInt(), 0xFF2D7FF9.toInt(), 0xFFFF4E8A.toInt())
    private var base = 0xFF07080C.toInt()

    init {
        spectrum.views += this
    }

    /** Takes the clouds' colours from the album cover (Spotify green and friends when there's none). */
    fun setArt(art: Bitmap?) {
        colors = palette(art) ?: intArrayOf(0xFF1DB954.toInt(), 0xFF7B4DFF.toInt(), 0xFF2D7FF9.toInt(), 0xFFFF4E8A.toInt())
        invalidate()
    }

    /** The ground under the clouds: the theme's ground, darkened, so the glass and white text keep their contrast. */
    fun setGround(ground: Int) {
        base = Color.rgb(Color.red(ground) / 4, Color.green(ground) / 4, Color.blue(ground) / 4)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        paintSmall(SystemClock.uptimeMillis() / 1000f)
        dst.set(0, 0, width, height)
        c.drawBitmap(small, null, dst, up)
    }

    private fun paintSmall(t: Float) {
        val w = small.width.toFloat()
        val h = small.height.toFloat()
        sc.drawColor(base)
        val pulse = spectrum.bass
        val glow = spectrum.level
        for (i in colors.indices) {
            // Each cloud circles its own slow path; together they never line up the same way twice.
            val cx = w * (0.5f + 0.38f * sin(t * (0.11f + i * 0.037f) + i * 1.7f))
            val cy = h * (0.5f + 0.36f * sin(t * (0.083f + i * 0.029f) + i * 2.9f))
            val r = h * (0.55f + 0.1f * i % 0.3f) * (1f + 0.45f * pulse)
            val a = (150 + 105 * min(1f, glow * 1.6f)).toInt().coerceIn(0, 255)
            val col = colors[i]
            blob.shader = RadialGradient(cx, cy, r, Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)),
                Color.argb(0, Color.red(col), Color.green(col), Color.blue(col)), Shader.TileMode.CLAMP)
            sc.drawCircle(cx, cy, r, blob)
        }
    }

    companion object {
        /** Four lively colours from [art]: its 4x4 thumbnail's most saturated cells, brightened a little. */
        fun palette(art: Bitmap?): IntArray? {
            if (art == null || art.isRecycled) return null
            val tiny = try { Bitmap.createScaledBitmap(art, 4, 4, true) } catch (_: Throwable) { return null }
            val hsv = FloatArray(3)
            val cells = (0 until 16).map { tiny.getPixel(it % 4, it / 4) }.sortedByDescending {
                Color.colorToHSV(it, hsv)
                hsv[1] * (0.4f + hsv[2])
            }
            if (tiny != art) tiny.recycle()
            return cells.take(4).map {
                Color.colorToHSV(it, hsv)
                hsv[1] = max(hsv[1], 0.45f)
                hsv[2] = max(hsv[2], 0.75f)
                Color.HSVToColor(hsv)
            }.toIntArray()
        }
    }
}

/**
 * The home screen's music widget, Spotify's or any player's: cover, app, title and artist on Liquid Glass, a thin
 * progress line, and the spectrum bars. Shown only while something plays; it never takes focus.
 */
internal class MusicCard(context: Context, spectrum: Spectrum, private val density: Float) : LinearLayout(context) {
    private val art = ImageView(context)
    private val app = TextView(context)
    private val title = TextView(context)
    private val artist = TextView(context)
    private val track = View(context)
    private val fill = View(context)
    private val bars = SpectrumBars(context, spectrum)
    private var shownTitle: String? = null

    private fun dp(v: Int) = (v * density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(14), dp(18), dp(14))
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        art.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat())
        }
        art.clipToOutline = true
        addView(art, LayoutParams(dp(104), dp(104)))

        val text = LinearLayout(context).apply { orientation = VERTICAL }
        app.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        app.letterSpacing = 0.08f
        app.typeface = android.graphics.Typeface.DEFAULT_BOLD
        app.setTextColor(0xFF1ED760.toInt()) // Spotify's green, for the "NOW PLAYING" line
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        artist.alpha = 0.7f
        for (t in listOf(app, title, artist)) {
            t.maxLines = 1
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            t.includeFontPadding = false
        }
        text.addView(app)
        text.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        text.addView(artist, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        text.addView(bars, LayoutParams(LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(8) })
        // The progress line: a faint track and the played part over it.
        val line = android.widget.FrameLayout(context)
        line.addView(track, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(3)))
        line.addView(fill, android.widget.FrameLayout.LayoutParams(0, dp(3)))
        text.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(7) })
        addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
    }

    fun setColors(fg: Int, glass: Drawable) {
        background = glass
        title.setTextColor(fg)
        artist.setTextColor(fg)
        bars.color = fg
        track.setBackgroundColor(Color.argb(46, Color.red(fg), Color.green(fg), Color.blue(fg)))
        fill.setBackgroundColor(fg)
        val r = dp(2).toFloat()
        for (v in listOf(track, fill)) {
            v.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, r)
            }
            v.clipToOutline = true
        }
    }

    /** Fills the card for [t]; [appName] heads it ("NOW PLAYING · SPOTIFY"). True when it's a new song. */
    fun show(t: Track, appName: String?, fallbackArt: Int, fg: Int): Boolean {
        val fresh = shownTitle != t.title + "\u0000" + t.artist
        if (fresh) {
            shownTitle = t.title + "\u0000" + t.artist
            title.text = t.title
            artist.text = t.artist
            app.text = listOfNotNull("NOW PLAYING", appName?.uppercase()).joinToString("  ·  ")
            if (t.art != null) {
                art.setImageBitmap(t.art)
                art.clearColorFilter()
                art.background = null
            } else {
                art.setImageResource(fallbackArt)
                art.setColorFilter(fg)
                art.setBackgroundColor(Color.argb(40, Color.red(fg), Color.green(fg), Color.blue(fg)))
            }
        }
        val p = t.progress
        (fill.parent as View).post {
            val total = (fill.parent as View).width
            fill.layoutParams = fill.layoutParams.apply { width = if (p == null) 0 else (total * p).toInt() }
        }
        bars.invalidate()
        return fresh
    }

    fun clear() {
        shownTitle = null
    }
}
