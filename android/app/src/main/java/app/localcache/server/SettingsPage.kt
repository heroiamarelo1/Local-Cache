package app.localcache.server

import android.content.Context
import app.localcache.BuildConfig
import app.localcache.Prefs
import app.localcache.config.AddonConfig
import app.localcache.storage.DiskQuota
import app.localcache.storage.DownloadEngine
import app.localcache.storage.StorageMode
import app.localcache.storage.UsbDriveDetector
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.util.Locale

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

        val action = first("action").ifBlank { "save" }
        val messages = mutableListOf<String>()

        when (action) {
            "selectUsb" -> {
                val path = first("usbPath")
                if (path.isBlank()) {
                    messages += "Pick a USB drive first."
                } else {
                    messages += StorageMode.selectUsb(context, path).message
                }
            }
            "deleteCache" -> {
                messages += StorageMode.deleteCache(context).message
            }
            "resumeDownloads" -> {
                messages += DownloadEngine.resumeSelected(context, all("resumeKey"))
            }
            "cancelDownloads" -> {
                messages += DownloadEngine.cancelSelected(context, all("resumeKey"))
            }
            else -> {
                val selected = AddonConfig.ALL_DEBRID_SERVICES.filter { name ->
                    params.containsKey("debrid_$name")
                }
                val wantInternal = params.containsKey("useInternal")
                when {
                    wantInternal && !StorageMode.isInternal(context) ->
                        messages += StorageMode.enableInternal(context).message
                    !wantInternal && StorageMode.isInternal(context) ->
                        messages += StorageMode.disableInternal(context)
                }
                val snapshot = AddonConfig.Snapshot(
                    torrentioManifestUrls = all("torrentioManifestUrl"),
                    cometManifestUrls = all("cometManifestUrl"),
                    localCometManifestUrls = all("localCometManifestUrl"),
                    debridServices = selected,
                    streamQuality = first("streamQuality").ifBlank { AddonConfig.QUALITY_1080P },
                    cacheMaxGb = first("cacheMaxGb").toIntOrNull() ?: Prefs.DEFAULT_CACHE_MAX_GB,
                    resultMode = first("resultMode").ifBlank { AddonConfig.RESULT_FAST },
                )
                messages += AddonConfig.save(context, snapshot, writeUsb = true)
            }
        }

        return html(
            renderForm(
                context,
                AddonConfig.load(context),
                saved = true,
                status = messages.joinToString("\n"),
            ),
        )
    }

    private fun renderForm(
        context: Context,
        snapshot: AddonConfig.Snapshot,
        saved: Boolean,
        status: String? = null,
    ): String {
        val port = Prefs.serverPort(context)
        val internal = StorageMode.isInternal(context)
        val internalChecked = if (internal) "checked" else ""
        val freeGb = "%.1f".format(
            Locale.US,
            StorageMode.freeBytesForInternal(context).toDouble() / (1024 * 1024 * 1024),
        )
        val currentPath = Prefs.cacheDirPath(context) ?: "(none)"
        val currentLabel = when {
            internal -> "Internal storage"
            Prefs.usbLabel(context) != null -> "USB — ${Prefs.usbLabel(context)}"
            else -> "Not selected"
        }
        val quotaLine = DiskQuota.summary(context)

        val drives = UsbDriveDetector.scan(context)
        val selectedUsb = Prefs.usbRootPath(context)
        val usbRows = if (drives.isEmpty()) {
            """<p class="hint">No USB drive detected. Plug one into the TV, refresh this page, then pick it.</p>"""
        } else {
            drives.joinToString("\n") { drive ->
                val fs = UsbDriveDetector.mountInfo(drive.path)?.fsType ?: "?"
                val checked = if (!internal && drive.path == selectedUsb) "checked" else ""
                """
                <label class="row">
                  <input type="radio" name="usbPath" value="${escape(drive.path)}" $checked>
                  ${escape(drive.label)} · $fs · <code>${escape(drive.path)}</code>
                </label>
                """.trimIndent()
            }
        }

        val resumable = DownloadEngine.listResumable(context)
        val resumeRows = if (resumable.isEmpty()) {
            """<p class="hint">No incomplete downloads. When you start another stream, the previous one pauses here.</p>"""
        } else {
            resumable.joinToString("\n") { item ->
                """
                <label class="row">
                  <input type="checkbox" name="resumeKey" value="${escape(item.cacheKey)}">
                  ${escape(item.title)}<br/>
                  <span class="hint">${item.progress}% · ${item.doneGb} / ${item.totalGb} GB · ${escape(item.status)}</span>
                </label>
                """.trimIndent()
            }
        }

        val checks = AddonConfig.ALL_DEBRID_SERVICES.joinToString("\n") { name ->
            val checked = if (snapshot.debridServices.any { it.equals(name, ignoreCase = true) }) "checked" else ""
            """<label class="row"><input type="checkbox" name="debrid_$name" $checked> $name</label>"""
        }
        val q1080 = if (snapshot.streamQuality != AddonConfig.QUALITY_4K_SOUND) "checked" else ""
        val q4k = if (snapshot.streamQuality == AddonConfig.QUALITY_4K_SOUND) "checked" else ""
        val mFast = if (!snapshot.isCompleteResults()) "checked" else ""
        val mComplete = if (snapshot.isCompleteResults()) "checked" else ""
        val banner = when {
            saved -> """<div class="ok">${escape(status.orEmpty()).replace("\n", "<br/>")}</div>"""
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
    h2 { font-size: 1.05rem; margin: 28px 0 8px; color: #c4c7cc; }
    .sub { color: #9aa0a6; margin-bottom: 20px; }
    label { display: block; margin: 12px 0 4px; font-weight: 600; }
    label.row { font-weight: 400; margin: 6px 0; }
    input[type=text], input[type=number] {
      width: 100%; box-sizing: border-box; padding: 10px; border-radius: 8px;
      border: 1px solid #3c4043; background: #1a1d23; color: #e8eaed;
    }
    .box { border: 1px solid #3c4043; border-radius: 10px; padding: 12px; margin-top: 8px; }
    button[type=submit], button.action {
      margin-top: 12px; margin-right: 8px; padding: 12px 18px; border: 0; border-radius: 8px;
      background: #8ab4f8; color: #0f1115; font-weight: 700; cursor: pointer;
    }
    button.danger { background: #f28b82; }
    button.secondary { background: #5f6368; color: #e8eaed; }
    button.add { margin-top: 8px; padding: 8px 12px; border: 1px solid #5f6368; border-radius: 8px;
             background: #1a1d23; color: #8ab4f8; font-weight: 600; cursor: pointer; }
    button.icon { flex: 0 0 40px; margin-left: 8px; padding: 8px 0; border: 1px solid #5f6368;
             border-radius: 8px; background: #1a1d23; color: #e8eaed; font-size: 1.2rem; cursor: pointer; }
    .url-row { display: flex; align-items: center; margin-top: 8px; }
    .url-row input { flex: 1; }
    .ok { background: #1e3a2f; border: 1px solid #34a853; padding: 10px; border-radius: 8px; margin-bottom: 16px; }
    .warn { background: #3a2e1e; border: 1px solid #f9ab00; padding: 10px; border-radius: 8px; margin: 8px 0 0; font-size: 0.9rem; }
    .live { background: #1a1d23; border: 1px solid #3c4043; border-radius: 10px; padding: 12px; margin-bottom: 20px; }
    .live h2 { font-size: 1rem; margin: 0 0 10px; color: #e8eaed; }
    #liveStatus { font-size: 0.95rem; line-height: 1.45; }
    #liveStatus .row { margin: 0 0 8px; }
    #liveStatus .row:last-child { margin-bottom: 0; }
    #liveStatus .k { color: #9aa0a6; font-weight: 600; display: inline; }
    #liveStatus .v { color: #e8eaed; word-break: break-word; }
    a { color: #8ab4f8; }
    .hint { color: #9aa0a6; font-size: 0.9rem; margin-top: 4px; }
    code { font-size: 0.85em; word-break: break-all; }
    .advanced { margin-top: 36px; padding-top: 8px; border-top: 1px solid #3c4043; }
  </style>
</head>
<body>
  <h1>Local Cache</h1>
  <p class="sub">Configure upstream manifests and preferences. Same Wi‑Fi only — no router changes needed.</p>
  $banner

  <div class="live">
    <h2>Now playing / download</h2>
    <div id="liveStatus">Loading…</div>
  </div>

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

    <label>Debrid services you use</label>
    <div class="box">$checks</div>
    <p class="hint">Leave unchecked by default. Check only the debrids in your Torrentio/Comet links so cached badges match.</p>

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

    <button type="submit" name="action" value="save">Save</button>

    <div class="advanced">
      <h2>Storage</h2>
      <p class="hint">Now: <b>${escape(currentLabel)}</b><br/>$quotaLine<br/><code>${escape(currentPath)}</code></p>

      <label>Resume / cancel incomplete downloads</label>
      <div class="box">
        $resumeRows
      </div>
      <button type="submit" class="action" name="action" value="resumeDownloads">Resume selected</button>
      <button type="submit" class="action danger" name="action" value="cancelDownloads"
        onclick="return confirm('Cancel selected downloads and delete their partial files?');">
        Cancel selected
      </button>
      <p class="hint">Starting a new stream pauses the previous download. Resume finishes it in the background (one at a time). Cancel deletes the .part file.</p>

      <label>USB drives on this TV</label>
      <div class="box">
        $usbRows
      </div>
      <button type="submit" class="action" name="action" value="selectUsb">Use selected USB</button>
      <p class="hint">Choosing USB leaves internal mode (and deletes internal cached movies).</p>

      <button type="submit" class="action danger" name="action" value="deleteCache"
        onclick="return confirm('Delete every cached movie on the current storage? This cannot be undone.');">
        Delete cache
      </button>
      <p class="hint">Removes movies from the current cache folder (USB or internal). Does not change settings.</p>

      <label style="margin-top:20px">Internal storage (fallback)</label>
      <div class="box">
        <label class="row"><input type="checkbox" name="useInternal" value="1" $internalChecked>
          Use internal storage (no USB)</label>
        <p class="warn">${escape(StorageMode.LIMITATIONS)}</p>
        <p class="hint">TV reports ~$freeGb GB free. ${
            if (BuildConfig.ALLOW_TINY_INTERNAL) {
                "Personal build: no 2 GB minimum."
            } else {
                "Needs at least 2 GB."
            }
        } Check + Save to enable; uncheck + Save clears internal cache.</p>
      </div>
      <button type="submit" class="action secondary" name="action" value="save">Save storage option</button>
    </div>
  </form>
  <p class="hint" style="margin-top:24px">
    After you Save here: on the TV open Stremio → Add-ons → Add Add-on →
    <code>http://127.0.0.1:$port/manifest.json</code>
    (Local Cache server must be running).
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
    function esc(t) {
      return String(t == null ? '' : t)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }
    function row(label, value) {
      if (!value) return '';
      return '<div class="row"><span class="k">' + esc(label) + '</span> ' +
        '<span class="v">' + esc(value) + '</span></div>';
    }
    function refreshLive() {
      fetch('/status').then(function(r) { return r.json(); }).then(function(s) {
        var el = document.getElementById('liveStatus');
        var title = s.downloadTitle || 'Idle — ready for Stremio';
        var stats = s.downloadStats || '';
        var storage = (s.internal ? 'Internal' : (s.storageMode === 'usb' ? 'USB' : 'None'));
        if (s.quotaSummary) storage += ' · ' + s.quotaSummary;
        var html = '';
        html += row('Download', title);
        if (stats) html += row('Progress', stats);
        html += row('Playback', s.playbackLabel || 'Nothing playing');
        if (s.playbackDetail) html += row('Detail', s.playbackDetail);
        html += row('Storage', storage);
        el.innerHTML = html;
      }).catch(function() {
        document.getElementById('liveStatus').textContent =
          'Status unavailable (is the server running?)';
      });
    }
    refreshLive();
    setInterval(refreshLive, 3000);
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
