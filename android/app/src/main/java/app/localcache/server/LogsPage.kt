package app.localcache.server

import android.content.Context
import app.localcache.BuildConfig
import app.localcache.DiagLog
import app.localcache.Prefs
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response

object LogsPage {

    fun serve(context: Context, session: IHTTPSession, plain: Boolean): Response {
        if (session.method == Method.POST) {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val action = session.parameters["action"]?.firstOrNull()?.trim().orEmpty()
            if (action == "clear") DiagLog.clear()
        }

        val body = DiagLog.dump()
        if (plain) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK,
                "text/plain; charset=utf-8",
                body,
            ).apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Cache-Control", "no-store")
            }
        }

        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            html(context, body),
        ).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun html(context: Context, dump: String): String {
        val on = DiagLog.enabled
        val port = Prefs.serverPort(context)
        val host = Prefs.lanHost(context)
        val state = if (on) "ON" else "OFF — turn it on in Settings and Save"
        val escaped = dump
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>Local Cache — Log</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 900px; margin: 24px auto; padding: 0 16px;
           background: #0f1115; color: #e8eaed; }
    h1 { font-size: 1.3rem; margin-bottom: 4px; }
    .sub, .hint { color: #9aa0a6; }
    a { color: #8ab4f8; }
    textarea {
      width: 100%; min-height: 70vh; box-sizing: border-box; margin-top: 12px;
      padding: 10px; border-radius: 8px; border: 1px solid #3c4043;
      background: #1a1d23; color: #e8eaed; font: 12px/1.4 ui-monospace, monospace;
      white-space: pre; overflow: auto;
    }
    button, .btn {
      margin: 8px 8px 0 0; padding: 12px 18px; border: 0; border-radius: 8px;
      background: #8ab4f8; color: #0f1115; font-weight: 700; cursor: pointer;
      display: inline-block; text-decoration: none;
    }
    button.secondary { background: #5f6368; color: #e8eaed; }
    .ok { color: #81c995; font-size: 0.9rem; margin-left: 8px; }
  </style>
</head>
<body>
  <h1>Diagnostic log</h1>
  <p class="sub">App v${BuildConfig.VERSION_NAME} · recording is <b>$state</b></p>
  <p class="hint">
    Copy this if a download failed or never started. Plain text:
    <a href="/logs.txt">http://$host:$port/logs.txt</a>
  </p>
    <p>
    <button type="button" onclick="copyLog()">Copy all</button>
    <a class="btn secondary" href="/logs">Refresh</a>
    <a class="btn secondary" href="/logs.txt">Open as text</a>
    <a class="btn secondary" href="/settings">Settings</a>
    <form method="POST" action="/logs" style="display:inline">
      <button class="secondary" type="submit" name="action" value="clear">Clear</button>
    </form>
    <span id="copied" class="ok"></span>
  </p>
  <textarea id="log" readonly>$escaped</textarea>
  <script>
    var el = document.getElementById('log');
    el.scrollTop = el.scrollHeight;
    function copyLog() {
      el.focus();
      el.select();
      var ok = false;
      try { ok = document.execCommand('copy'); } catch (e) {}
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(el.value).then(function() {
          document.getElementById('copied').textContent = 'Copied';
        }, function() {
          document.getElementById('copied').textContent = 'Select the text and copy it';
        });
      } else {
        document.getElementById('copied').textContent = ok ? 'Copied' : 'Select the text and copy it';
      }
    }
  </script>
</body>
</html>
        """.trimIndent()
    }
}
