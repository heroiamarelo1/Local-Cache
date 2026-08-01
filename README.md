# Local Cache

**Watch now. Save it to the USB. Watch again without buffering.**

Local Cache is a tiny Android TV app that runs a Stremio add-on *on your TV*.  
You pick a stream like usual (Torrentio / Comet + your debrid). While it plays, the file gets copied to a USB stick. Next time? It plays from the stick — smooth, local, no drama.

**Grab the APK:** [v0.4.14](https://github.com/heroiamarelo1/Local-Cache/releases/tag/v0.4.14)

---

## Why bother?

Internet hiccups mid-movie suck.  
If the file is already on USB, your TV doesn’t care what the debrid link is doing tonight.

---

## What you get

- Stremio add-on living on the TV (`http://127.0.0.1:7100/manifest.json`)
- Smart picks — prefers cached debrid links, respects 1080p or 4K+sound mode
- Multiple Torrentio / Comet URLs (Torrentio = one debrid per link, so add two if you use AD + TorBox)
- Setup from your phone on the same Wi‑Fi — tap **+** to add more manifests
- Download progress right in the app

---

## What you need

- Android TV / Google TV
- [Stremio](https://www.stremio.com/) (or something compatible)
- A USB drive formatted **exFAT** (FAT32 chokes on big movie files)
- Your own Torrentio / Comet links and debrid accounts

---

## Setup in 4 steps

1. Install the APK from [Releases](https://github.com/heroiamarelo1/Local-Cache/releases)
2. Open **Local Cache** → pick your USB → start the server
3. On your phone (same Wi‑Fi), open the settings link from the app and paste your **final** `manifest.json` URLs
4. In Stremio: **Add-ons → Add Add-on** →  
   `http://127.0.0.1:7100/manifest.json`

That’s it. Pick a movie and let it fill the stick while you watch.

---

## Build it yourself

```bash
cd android
./gradlew :app:assembleDebug
```

APK lands at: `android/app/build/outputs/apk/debug/app-debug.apk`  
Package id: `app.localcache.release`

---

## Config cheat sheet

Easiest path: phone → `/settings`.  
Or read `android/app/src/main/assets/local-cache-config.README.txt`.

| Field | What it’s for |
|--------|----------------|
| `torrentioManifestUrls` | Your Torrentio manifest(s) |
| `cometManifestUrls` | Optional public / ElfHosted Comet |
| `localCometManifestUrls` | Optional Comet on your LAN |
| `streamQuality` | `1080p` or `4k_sound` |
| `resultMode` | `fast` (default) or `complete` |
| `debridServices` | Only the debrids you actually use |

---

## License

[MIT](LICENSE) — do whatever you want with it.

## Disclaimer

Not affiliated with Stremio, Torrentio, Comet, or any debrid. Use your own keys, follow their rules.
