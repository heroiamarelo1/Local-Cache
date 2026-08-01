package app.localcache.server

import android.content.Context
import app.localcache.Prefs
import app.localcache.config.AddonConfig
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response

object SettingsPage {

    fun serve(context: Context, session: IHTTPSession): Response {
        return when (session.method) {
            Method.POST -> handlePost(context, session)
            else -> html(renderForm(context, AddonConfig.load(context), saved = false))
        }
    }

    private fun handlePost(context: Context, session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val params = session.parameters

        fun all(name: String): List<String> =
            params[name]?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

        fun first(name: String): String =
            params[name]?.firstOrNull()?.trim().orEmpty()

        val selected = AddonConfig.ALL_DEBRID_SERVICES.filter { name ->
            params.containsKey("debrid_$name")
        }

        val snapshot = AddonConfig.Snapshot(
            torrentioManifestUrls = all("torrentioManifestUrl"),
            cometManifestUrls = all("cometManifestUrl"),
            localCometManifestUrls = all("localCometManifestUrl"),
            debridServices = selected.ifEmpty { AddonConfig.ALL_DEBRID_SERVICES },
            streamQuality = first("streamQuality").ifBlank { AddonConfig.QUALITY_1080P },
            cacheMaxGb = first("cacheMaxGb").toIntOrNull() ?: Prefs.DEFAULT_CACHE_MAX_GB,
            resultMode = first("resultMode").ifBlank { AddonConfig.RESULT_FAST },
        )

        val status = AddonConfig.save(context, snapshot, writeUsb = true)
        return html(renderForm(context, AddonConfig.load(context), saved = true, status = status))
    }

    private fun renderForm(
        context: Context,
        snapshot: AddonConfig.Snapshot,
        saved: Boolean,
        status: String? = null,
    ): String {
        val port = Prefs.serverPort(context)
        val checks = AddonConfig.ALL_DEBRID_SERVICES.joinToString("\n") { name ->
            val checked = if (snapshot.debridServices.any { it.equals(name, ignoreCase = true) }) "checked" else ""
            """<label class="row"><input type="checkbox" name="debrid_$name" $checked> $name</label>"""
        }
        val q1080 = if (snapshot.streamQuality != AddonConfig.QUALITY_4K_SOUND) "checked" else ""
        val q4k = if (snapshot.streamQuality == AddonConfig.QUALITY_4K_SOUND) "checked" else ""
        val mFast = if (!snapshot.isCompleteResults()) "checked" else ""
        val mComplete = if (snapshot.isCompleteResults()) "checked" else ""
        val banner = when {
            saved -> """<div class="ok">Saved. ${escape(status.orEmpty())}</div>"""
            else -> ""
        }

        fun urlRows(name: String, urls: List<String>, placeholder: String): String {
            val list = if (urls.isEmpty()) listOf("") else urls
            return list.joinToString("\n") { url ->
                """
                <div class="url-row">
                  <input type="text" name="$name" value="${escape(url)}" placeholder="$placeholder"/>
                  <button type="button" class="icon" onclick="this.parentElement.remove()" title="Remove">−</button>
                </div>
                """.trimIndent()
            }
        }

        val torrentioRows = urlRows(
            "torrentioManifestUrl",
            snapshot.torrentioManifestUrls,
            "https://torrentio.strem.fun/.../manifest.json",
        )
        val cometRows = urlRows(
            "cometManifestUrl",
            snapshot.cometManifestUrls,
            "https://comet.elfhosted.com/.../manifest.json",
        )
        val localRows = urlRows(
            "localCometManifestUrl",
            snapshot.localCometManifestUrls,
            "http://192.168.1.50:8000/.../manifest.json",
        )

        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>Local Cache — Configure</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 720px; margin: 24px auto; padding: 0 16px;
           background: #0f1115; color: #e8eaed; }
    h1 { font-size: 1.4rem; margin-bottom: 4px; }
    .sub { color: #9aa0a6; margin-bottom: 20px; }
    label { display: block; margin: 12px 0 4px; font-weight: 600; }
    label.row { font-weight: 400; margin: 6px 0; }
    input[type=text], input[type=number] {
      width: 100%; box-sizing: border-box; padding: 10px; border-radius: 8px;
      border: 1px solid #3c4043; background: #1a1d23; color: #e8eaed;
    }
    .box { border: 1px solid #3c4043; border-radius: 10px; padding: 12px; margin-top: 8px; }
    button[type=submit] { margin-top: 20px; padding: 12px 18px; border: 0; border-radius: 8px;
             background: #8ab4f8; color: #0f1115; font-weight: 700; cursor: pointer; }
    button.add { margin-top: 8px; padding: 8px 12px; border: 1px solid #5f6368; border-radius: 8px;
             background: #1a1d23; color: #8ab4f8; font-weight: 600; cursor: pointer; }
    button.icon { flex: 0 0 40px; margin-left: 8px; padding: 8px 0; border: 1px solid #5f6368;
             border-radius: 8px; background: #1a1d23; color: #e8eaed; font-size: 1.2rem; cursor: pointer; }
    .url-row { display: flex; align-items: center; margin-top: 8px; }
    .url-row input { flex: 1; }
    .ok { background: #1e3a2f; border: 1px solid #34a853; padding: 10px; border-radius: 8px; margin-bottom: 16px; }
    a { color: #8ab4f8; }
    .hint { color: #9aa0a6; font-size: 0.9rem; margin-top: 4px; }
  </style>
</head>
<body>
  <h1>Local Cache</h1>
  <p class="sub">Configure upstream manifests and preferences. Same Wi‑Fi only — no router changes needed.</p>
  $banner
  <form method="POST" action="/settings">
    <label>Torrentio manifest.json URL(s)</label>
    <div id="torrentio-list">$torrentioRows</div>
    <button type="button" class="add" onclick="addUrl('torrentio-list','torrentioManifestUrl','https://torrentio.strem.fun/.../manifest.json')">+ Add another Torrentio</button>
    <p class="hint">Torrentio only supports <b>one debrid per configure link</b>. For AllDebrid + TorBox, add two manifests from <a href="https://torrentio.strem.fun/configure" target="_blank">Torrentio configure</a> (paste final manifest URLs, not the configure page).</p>

    <label>Comet (ElfHosted / public) manifest.json URL(s)</label>
    <div id="comet-list">$cometRows</div>
    <button type="button" class="add" onclick="addUrl('comet-list','cometManifestUrl','https://comet.elfhosted.com/.../manifest.json')">+ Add another Comet</button>
    <p class="hint">Optional. From <a href="https://comet.elfhosted.com/configure" target="_blank">comet.elfhosted.com/configure</a> — one URL per debrid setup if needed.</p>

    <label>Comet Local (self-hosted on LAN) manifest.json URL(s)</label>
    <div id="local-comet-list">$localRows</div>
    <button type="button" class="add" onclick="addUrl('local-comet-list','localCometManifestUrl','http://192.168.1.50:8000/.../manifest.json')">+ Add another Local Comet</button>
    <p class="hint">Optional. Run <a href="https://github.com/g0ldyy/comet" target="_blank">Comet</a> on a PC/NAS on the same Wi‑Fi. Leave blank if unused.</p>

    <label>Used Debrid services (uncheck ones you do not use)</label>
    <div class="box">$checks</div>

    <label>Stream quality</label>
    <div class="box">
      <label class="row"><input type="radio" name="streamQuality" value="1080p" $q1080> 1080p (Recommended)</label>
      <label class="row"><input type="radio" name="streamQuality" value="4k_sound" $q4k> 4K high quality sound (requires fast USB and fast internet)</label>
    </div>

    <label>Result speed</label>
    <div class="box">
      <label class="row"><input type="radio" name="resultMode" value="fast" $mFast> Fast (default) — answer sooner, fewer streams</label>
      <label class="row"><input type="radio" name="resultMode" value="complete" $mComplete> Complete — wait for all upstreams, more streams</label>
    </div>

    <label>Cache max (GB)</label>
    <input type="number" name="cacheMaxGb" min="1" max="4096" value="${snapshot.cacheMaxGb}"/>

    <button type="submit">Save</button>
  </form>
  <p class="hint" style="margin-top:24px">
    Install in Stremio on the TV: Add-ons → Add Add-on →
    <code>http://127.0.0.1:$port/manifest.json</code>
    (start the server in the Local Cache app first).
  </p>
  <script>
    function addUrl(listId, name, placeholder) {
      var list = document.getElementById(listId);
      var row = document.createElement('div');
      row.className = 'url-row';
      row.innerHTML = '<input type="text" name="' + name + '" value="" placeholder="' + placeholder + '"/>' +
        '<button type="button" class="icon" onclick="this.parentElement.remove()" title="Remove">−</button>';
      list.appendChild(row);
      row.querySelector('input').focus();
    }
  </script>
</body>
</html>
        """.trimIndent()
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun html(body: String): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
}
