# TvPlayer

An Android TV app to stream movies and shows from [vidfast.pro](https://vidfast.pro/),
searchable by IMDB/TMDB ID (e.g. `https://vidfast.pro/movie/tt1645170`).

> **Status: Phase 0 — WebView validation spike.**
> The current build is a minimal, sideloadable APK whose only job is to confirm
> end-to-end playback on a real Android TV (home network) before the full app is built.
> See [`PLAN.md`](./PLAN.md) for the full implementation plan and findings.

## What works today
- Single-screen `WebView` that loads a vidfast embed and applies smooth-UX hardening:
  - Autoplay enabled, fullscreen video support.
  - Popup / new-window rejection and ad/resolver host blocking.
  - D-pad **Back** handling (exit fullscreen → web history → activity).
- Android TV ready: `LEANBACK_LAUNCHER` intent, TV banner, touchscreen not required.

## Build

Requires the Android SDK (compileSdk 34) and a JDK 17+ (the JDK bundled with
Android Studio works).

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Install & test on a device / Android TV

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch with the default title (tt1645170) or override the URL:
adb shell am start -n pro.tvplayer.app/.SpikeWebViewActivity \
  -e url "https://vidfast.pro/movie/tt1645170"
```

## Roadmap

See [`PLAN.md`](./PLAN.md). Highlights:
- TMDB-powered search + browse, plus manual IMDB/TMDB ID entry.
- Details screen with season/episode picker.
- WebView-primary playback (reliable), with an experimental native HLS/ExoPlayer path.
- Watchlist/Favorites and Continue Watching.

## Notes
- For personal use; respect the applicable terms and laws of the content sources.
- The TMDB API token is read from gitignored local config and is never committed.
