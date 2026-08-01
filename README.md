# Local Cache 🍿💾

**Watch now. Save it to the USB. Watch again without buffering.**

Local Cache is a tiny Android TV app that runs a Stremio add-on *on your TV*.  
You pick a stream like usual (Torrentio / Comet). **You can start watching while it downloads** — no waiting for the whole file. While you chill, it copies the movie to a USB stick. Next time? It plays straight from the stick.

### ✨ The big win

**No more buffering mid-movie.**  
Once it’s on the USB, your TV isn’t fighting your internet anymore. Smooth local playback. That’s the whole point.

**Latest APK:** [v0.4.14](https://github.com/heroiamarelo1/Local-Cache/releases/tag/v0.4.14)

---

## Why bother? 😤➡️😌

Internet hiccups mid-movie suck.  
If the file is already on USB, your TV doesn’t care what the cloud is doing tonight.

---

## Debrid? ⚡

**A debrid service is recommended** (AllDebrid, TorBox, etc.) — faster starts, more “instant” links, happier evenings.

**But it’s not required.** You can still use Local Cache without one; downloads may be slower and less reliable. Debrid just makes life easier.

---

## What you get 🧰

- 📺 Stremio add-on living on the TV (`http://127.0.0.1:7100/manifest.json`)
- ▶️ **Play while downloading** — don’t wait for 100%
- 🧠 Smart picks — prefers cached debrid links, 1080p or 4K+sound mode
- 🔗 Multiple Torrentio / Comet URLs (Torrentio = one debrid per link → add two for AD + TorBox)
- 📱 Setup from your phone on the same Wi‑Fi — tap **+** to add more manifests
- 📊 Download progress right in the app

---

## What you need 📦

- Android TV / Google TV
- [Stremio](https://www.stremio.com/) (or something compatible)
- A USB drive formatted **exFAT** (FAT32 chokes on big movie files)
- Your Torrentio / Comet links  
  👉 Debrid accounts: **recommended, not mandatory**

---

## Setup in 4 steps 🚀

1. Install the APK from [Releases](https://github.com/heroiamarelo1/Local-Cache/releases)
2. Open **Local Cache** → pick your USB → start the server
3. On your phone (same Wi‑Fi), open the settings link from the app and paste your **final** `manifest.json` URLs
4. In Stremio: **Add-ons → Add Add-on** →  
   `http://127.0.0.1:7100/manifest.json`

That’s it. Hit play, watch while it fills the stick, and enjoy buffer-free replays later. 🎬

---

## Build it yourself 🛠️

```bash
cd android
./gradlew :app:assembleDebug
```

APK lands at: `android/app/build/outputs/apk/debug/app-debug.apk`  
Package id: `app.localcache.release`

---

## Config cheat sheet ⚙️

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

[MIT](LICENSE) — do whatever you want with it. 🆓

## Disclaimer

Not affiliated with Stremio, Torrentio, Comet, or any debrid. Use your own keys, follow their rules.
