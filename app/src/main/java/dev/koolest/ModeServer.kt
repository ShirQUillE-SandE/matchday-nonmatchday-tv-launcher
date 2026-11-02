package dev.koolest

import android.content.Context
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * The phone remote for the two homes (this build): a one-page web server on the home Wi-Fi with three buttons,
 * Match day, Weekday and Automatic (see MatchDay.setManual). Saved to the phone's home screen it works without
 * the computer. Only answers on /<code> (a random code made once), so others on the Wi-Fi can't use it.
 * Lives in the home screen's process, started at boot and by the home screen; a thread blocked on accept().
 */
internal object ModeServer {
    const val PORT = 8765
    /** Set by the home screen to show a change straight away. */
    @Volatile var changed: (() -> Unit)? = null
    private var socket: ServerSocket? = null

    fun code(c: Context): String {
        val prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE)
        prefs.getString("remote_code", null)?.let { return it }
        val made = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("remote_code", made).apply()
        return made
    }

    @Synchronized
    fun start(c: Context) {
        if (socket != null) return
        val app = c.applicationContext
        val s = try {
            ServerSocket(PORT)
        } catch (e: IOException) {
            android.util.Log.w("Koolest", "phone remote: port $PORT busy ($e)")
            return
        }
        socket = s
        val code = code(app)
        android.util.Log.i("Koolest", "phone remote: http://<this TV's address>:$PORT/$code")
        Thread({
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: IOException) { break }
                try {
                    client.use { handle(app, it, code) }
                } catch (e: Exception) {
                    android.util.Log.w("Koolest", "phone remote request failed ($e)")
                }
            }
        }, "phone-remote").apply { isDaemon = true }.start()
    }

    private fun handle(c: Context, s: Socket, code: String) {
        s.soTimeout = 10_000
        val request = BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: return
        val path = request.substringAfter(' ').substringBefore(' ')
        val prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE)
        val out = s.getOutputStream()
        when {
            path == "/$code" -> reply(out, 200, "text/html; charset=utf-8", page(MatchDay.describe(prefs)))
            path.startsWith("/$code/set/") -> {
                val mode = path.substringAfterLast('/')
                if (mode !in setOf("matchday", "weekday", "auto")) return reply(out, 400, "text/plain", "?")
                MatchDay.setManual(prefs, mode)
                changed?.invoke()
                reply(out, 200, "text/plain; charset=utf-8", MatchDay.describe(prefs))
            }
            else -> reply(out, 404, "text/plain", "Not found")
        }
    }

    private fun reply(out: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        out.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun page(now: String) = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="apple-mobile-web-app-capable" content="yes"><meta name="theme-color" content="#0b0d12">
<title>Matchday TV</title>
<style>
body{margin:0;min-height:100vh;background:#0b0d12;color:#f2f2f2;font:16px -apple-system,system-ui,sans-serif;display:flex;flex-direction:column;padding:28px 20px;box-sizing:border-box}
h1{font-size:22px;margin:0 0 6px}#now{opacity:.7;margin-bottom:28px}
button{display:block;width:100%;margin:0 0 14px;padding:22px;border-radius:18px;border:1px solid rgba(255,255,255,.18);font-size:19px;font-weight:600;color:#fff;background:rgba(255,255,255,.08)}
#m{background:linear-gradient(90deg,#2c6e3f,#1d4f8f)}button:active{opacity:.6}
p{opacity:.5;font-size:13px;line-height:1.5}
</style></head><body>
<h1>Matchday TV</h1><div id="now">Now: $now</div>
<button id="m" onclick="go('matchday')">&#9917; Match day</button>
<button onclick="go('weekday')">Weekday</button>
<button onclick="go('auto')">Automatic</button>
<p>A switch holds until the next kick-off or final whistle, then the TV goes back to automatic. The TV shows it on its home screen.</p>
<script>
function go(m){document.getElementById('now').textContent='Switching…';
fetch(location.pathname+'/set/'+m).then(r=>r.text()).then(t=>document.getElementById('now').textContent='Now: '+t)
.catch(()=>document.getElementById('now').textContent='The TV did not answer. Is it on, on this Wi-Fi?')}
</script></body></html>"""
}
