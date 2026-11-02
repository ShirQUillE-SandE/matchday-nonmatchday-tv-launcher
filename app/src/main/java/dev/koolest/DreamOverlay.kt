package dev.koolest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * While any screensaver runs (Aerial Views here): the music card, with its live spectrum bars, floating at half
 * opacity in the top end corner, and only while something plays. Android tells running apps when a screensaver
 * starts and stops; Koolest is the home screen, so its process is up from boot (see [install]).
 * Needs "display over other apps", allowed once over adb: appops set dev.koolest SYSTEM_ALERT_WINDOW allow.
 */
internal object DreamOverlay {
    private const val OPACITY = 0.5f
    private val ui = Handler(Looper.getMainLooper())
    private val spectrum = Spectrum()
    private var app: Context? = null
    private var card: MusicCard? = null

    private val events = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_DREAMING_STARTED) start() else stop()
        }
    }

    /** What's playing, checked every 2 s while the screensaver shows (as the home screen's top bar does). */
    private val poll = object : Runnable {
        override fun run() {
            update()
            ui.postDelayed(this, 2000)
        }
    }

    /** Listens for the screensaver from now on, in this process; called on every start of the home screen and at boot. */
    fun install(c: Context) {
        keepScreensaverTimer(c)
        if (app != null) return
        val a = c.applicationContext
        app = a
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(events, f, Context.RECEIVER_EXPORTED) else a.registerReceiver(events, f)
    }

    private fun start() {
        val a = app ?: return
        if (!Settings.canDrawOverlays(a)) {
            Log.w("Koolest", "dream overlay: no overlay permission")
            return
        }
        ui.removeCallbacks(poll)
        ui.post(poll)
    }

    private fun stop() {
        ui.removeCallbacks(poll)
        remove()
    }

    private fun update() {
        val a = app ?: return
        val t = nowPlaying(a) ?: return remove()
        val shown = card ?: try {
            val d = a.resources.displayMetrics.density
            MusicCard(a, spectrum, d).also { v ->
                v.setColors(Color.WHITE, LiquidGlass(24 * d, 0xA61C1E25.toInt(), true, d))
                a.getSystemService(WindowManager::class.java).addView(v, WindowManager.LayoutParams(
                    (380 * d).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = (40 * d).toInt()
                    y = (36 * d).toInt()
                    alpha = OPACITY
                    title = "Koolest music"
                })
                card = v
            }
        } catch (e: Exception) { // the permission went away, or the window couldn't be made
            Log.w("Koolest", "dream overlay failed ($e)")
            return
        }
        spectrum.start()
        val name = try { a.packageManager.getApplicationLabel(a.packageManager.getApplicationInfo(t.pkg, 0)).toString() } catch (_: Exception) { null }
        shown.show(t, name, R.drawable.ic_s_music, Color.WHITE)
    }

    private fun remove() {
        spectrum.stop()
        val v = card ?: return
        card = null
        try { app?.getSystemService(WindowManager::class.java)?.removeViewImmediate(v) } catch (_: Exception) {}
    }

    /**
     * Puts the screensaver's start time back to the one chosen with setup_screensaver_ms, if anything changed it
     * (the TV can reset it). Needs "modify system settings": appops set dev.koolest WRITE_SETTINGS allow.
     */
    fun keepScreensaverTimer(c: Context) {
        val ms = c.getSharedPreferences("home", Context.MODE_PRIVATE).getInt("keep_timeout", 0)
        if (ms <= 0 || !Settings.System.canWrite(c)) return
        try {
            if (Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 0) != ms) {
                Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms)
            }
        } catch (e: Exception) {
            Log.w("Koolest", "screensaver timer not kept ($e)")
        }
    }
}
