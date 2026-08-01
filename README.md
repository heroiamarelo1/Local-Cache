# Local Cache

Android TV app that runs a **Stremio-compatible add-on on the TV**. It fetches streams from your **Torrentio** and **Comet** manifests, ranks them (prefer debrid-cached results, quality modes), starts playback, and **downloads the file to a USB drive** so later plays can come from the stick instead of the internet.

**Latest release:** [v0.4.14](https://github.com/heroiamarelo1/Local-Cache/releases/tag/v0.4.14)

## Features

- On-device Stremio add-on at `http://127.0.0.1:7100/manifest.json`
- Multiple Torrentio / Comet manifests (Torrentio is one debrid per URL — add two for AllDebrid + TorBox)
- Phone settings on the same Wi‑Fi: `http://TV_LAN_IP:7100/settings` (use **+** to add more manifests)
- Fast vs complete result modes (fast waits for all instances of at least one provider, 8s cap)
- Stream ranking + clear Stremio card labels
- USB download with progress in the app

## Requirements

- Android TV / Google TV (or similar Android device)
- [Stremio](https://www.stremio.com/) (or a compatible client)
- USB storage formatted **exFAT** (FAT32 cannot store large movie files)
- Your own Torrentio / Comet configure URLs and debrid accounts

## Install (TV)

1. Install the APK from [Releases](https://github.com/heroiamarelo1/Local-Cache/releases).
2. Open **Local Cache**, choose the USB drive, start the server.
3. On your phone (same Wi‑Fi), open the settings URL shown in the app and paste your **final** `manifest.json` URLs.
4. In Stremio on the TV: **Add-ons → Add Add-on** →  
   `http://127.0.0.1:7100/manifest.json`

## Build from source

```bash
cd android
./gradlew :app:assembleDebug
```

APK output: `android/app/build/outputs/apk/debug/app-debug.apk`

Package id: `app.localcache.release` (can sit beside other Local Cache builds).

## Config

See `android/app/src/main/assets/local-cache-config.README.txt`, or edit via phone `/settings`.

Useful fields:

| Field | Meaning |
|--------|---------|
| `torrentioManifestUrls` | One or more Torrentio final manifest URLs |
| `cometManifestUrls` | Optional ElfHosted / public Comet URLs |
| `localCometManifestUrls` | Optional LAN self-hosted Comet URLs |
| `streamQuality` | `1080p` or `4k_sound` |
| `resultMode` | `fast` (default) or `complete` |
| `debridServices` | Services you actually use |

## License

[MIT](LICENSE) — free to use, modify, and redistribute.

## Disclaimer

Not affiliated with Stremio, Torrentio, Comet, or any debrid provider. Use your own accounts and follow their terms of service.
