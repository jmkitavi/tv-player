# Android TV Streaming App (vidfast.pro) — Implementation Plan

## Problem & Goal
Build a native **Android TV** app to stream movies and shows from **vidfast.pro**.
vidfast serves content keyed by IMDB/TMDB IDs:
- Movie: `https://vidfast.pro/movie/{id}` (also embed form `https://vidfast.pro/embed/movie?tmdb={id}`)
- TV:    `https://vidfast.pro/tv/{id}/{season}/{episode}`

The app must let the user discover content (title search + browse), open a details
screen, and play with a remote-friendly experience on the TV.

## Stream Extraction Feasibility (investigated)
Probed `https://vidfast.pro/movie/tt1645170` and its JS bundles:
- Site is **Next.js behind Cloudflare**; page HTML contains **no stream URLs**.
- Stream URLs are resolved in **heavily obfuscated JS** (obfuscator.io-style identifiers)
  using client-side **`decrypt` / `atob`** on an **encrypted playlist** fetched from a
  runtime-constructed endpoint, behind **Cloudflare anti-bot**.
- The visible `.m3u8` handling is mostly the bundled **hls.js** player.

**Conclusion:** Native extraction is **technically possible but NOT confirmed working**.
It requires replicating their obfuscated, frequently-changing decryption and bypassing
Cloudflare — fragile and high-maintenance. Therefore the strategy is **WebView-primary**.

## WebView Validation (tested via headless+headful Chromium = Android System WebView engine)
Loaded `https://vidfast.pro/movie/tt1645170` in Chrome (same Chromium engine as Android
WebView), Android & desktop UAs, with autoplay enabled and anti-automation evasion:
- ✅ The vidfast **player UI mounts correctly** in the WebView engine (backdrop + spinner).
- ✅ It reaches the source-resolution stage and contacts obfuscated resolver/ad domains:
  `*.cfd`, `*.cyou` (e.g. `jackie.bleounshipsmachi.cfd`, `unwrapsstow.cyou`).
- ⚠️ A popup/new-window (`about:blank`) is spawned → **ads/popups must be blocked**.
- ❌ Playback did **not** complete to a playing `<video>` in this **datacenter** environment,
  even non-headless. Most likely **datacenter-IP / geo / anti-bot** blocking of the resolver
  (the site plays fine in ordinary home browsers).

**Implication:** WebView rendering is confirmed; true end-to-end playback can only be
confirmed on a **real Android TV on the user's home (residential) network**. The plan
therefore front-loads an **on-device WebView spike (Phase 0)** to validate playback before
investing in the full app.

## Smooth UX Handling (WebView player) — how playback feels native
To make the WebView feel like a native TV player, the `WebViewPlayerScreen` will:
- **Engine config:** `WebView` with `javaScriptEnabled`, `domStorageEnabled`,
  `mediaPlaybackRequiresUserGesture=false` (autoplay), hardware acceleration, modern UA,
  fullscreen via `WebChromeClient.onShowCustomView`, `loadWithOverviewMode`.
- **Ad/popup hardening (critical for smoothness):**
  - Block new windows: `setSupportMultipleWindows(false)` + `onCreateWindow → return false`.
  - `shouldOverrideUrlLoading`: keep navigations on `vidfast.pro`; **cancel** redirects to
    ad/resolver popup hosts (e.g. `*.cfd`, `*.cyou`, unknown 3rd-party top-level navigations).
  - `shouldInterceptRequest`: block a denylist of ad/track hosts so they never load.
  - Disable target=_blank / `window.open` via injected JS shim.
- **D-pad / remote mapping:** intercept key events (OK/Center, ←/→ seek, ↑/↓, Play/Pause,
  Back) and forward them to the in-page player via injected JS (dispatch click/keyboard on
  the `<video>`/player), so the TV remote drives the embedded player smoothly.
- **Loading & focus UX:** show a Compose loading overlay until the page/video is ready
  (JS bridge posts a "ready"/"playing" event), hide the mouse cursor, keep screen on
  (`FLAG_KEEP_SCREEN_ON`), immersive fullscreen, graceful error overlay with Retry.
- **Resume:** a `@JavascriptInterface` bridge periodically reports `currentTime`/`duration`;
  saved to Room (ContinueWatching) and seeked on next open.
- **Back handling:** single Back exits the player cleanly to details (not the embedded site).

## Confirmed Decisions
- **Playback (revised): WebView-primary hybrid.**
  - **Primary (reliable):** WebView loads the vidfast embed and plays in-page (hls.js).
    Apply ad/popup hardening (block popups, new-window navigations, known ad hosts).
  - **Experimental (best-effort):** A pluggable `StreamExtractor` attempts to resolve a
    direct HLS URL for **Media3/ExoPlayer** (better remote UX). If it fails/breaks, the
    app silently uses the WebView path. Shipping native extraction is a stretch goal, not
    a release blocker.
- **Discovery:** Both — **TMDB API** title search + browse (posters, trending/popular),
  **and** manual IMDB/TMDB ID entry as a fallback. (TMDB credentials provided by user;
  stored in gitignored local config — never committed.)
- **Scope:** MVP + personalization → search/browse, details, play (movies + TV with
  season/episode picker), resume playback, **Watchlist/Favorites**, **Continue Watching**.

## Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose for TV (`androidx.tv:tv-foundation`, `androidx.tv:tv-material`)
- **Player:** AndroidX **Media3** (ExoPlayer + HLS) with WebView fallback
- **Networking:** Retrofit + OkHttp + kotlinx.serialization (TMDB + stream extraction)
- **Images:** Coil (Compose)
- **Persistence:** Room (watchlist + continue-watching), DataStore (settings/API key)
- **DI:** Hilt
- **SDK:** min 23, target/compile 34; Leanback launcher intent + TV banner in manifest

## Architecture (MVVM + Repository)
```
app/
  data/
    tmdb/        Retrofit service, DTOs, TmdbRepository
    vidfast/     UrlBuilder, StreamExtractor (interface + VidfastExtractor)
    local/       Room entities/DAOs (Watchlist, ContinueWatching), AppDatabase
    settings/    DataStore (TMDB api key, autoplay, preferred quality)
  domain/        models (MediaItem, StreamSource, Episode), repository interfaces
  ui/
    home/        browse rows (Trending, Popular, Continue Watching, Watchlist)
    search/      title search + manual ID entry
    details/     movie/show details, season/episode picker, play/watchlist actions
    player/
      webview/   PRIMARY: WebView (hls.js) loading vidfast embed; popup/ad hardening
      native/    EXPERIMENTAL: Media3/ExoPlayer when StreamExtractor yields HLS
      PlaybackCoordinator  picks native if available, else WebView
    components/   reusable TV cards/rows/focus helpers
  di/            Hilt modules
```

## Todos (high level)
0. **webview-spike-on-device** — Build a minimal single-screen WebView APK that loads a
   vidfast embed; install on the **real Android TV** (residential network) to **confirm
   end-to-end playback + popup behavior** before building the full app. Go/No-go gate.
1. **scaffold-project** — Create Gradle/Kotlin Android TV project (manifest with Leanback
   intent + banner, min/target SDK, Compose for TV deps, Hilt, Media3, Room, Retrofit, Coil).
2. **domain-models** — Define MediaItem, MediaType, StreamSource, Episode, repository interfaces.
3. **tmdb-integration** — Retrofit service + DTOs for search/trending/popular/details/season;
   TmdbRepository mapping to domain models; read TMDB token from gitignored local config.
4. **vidfast-urlbuilder** — URL builder for movie/tv embed + path URLs (movie/{id}, tv/{id}/{s}/{e}).
5. **local-persistence** — Room entities/DAOs for Watchlist & ContinueWatching; DataStore settings.
6. **home-screen** — TV browse UI: Continue Watching, Watchlist, Trending, Popular rows; focus/D-pad.
7. **search-screen** — Title search (TMDB) results grid + manual IMDB/TMDB ID entry path.
8. **details-screen** — Poster/overview/metadata; movie vs TV; season/episode picker;
   Play + add/remove Watchlist actions.
9. **player-webview** — PRIMARY player: WebView loads vidfast embed (hls.js); popup/ad
   hardening (block new windows/known ad hosts); map remote keys; resume position via JS bridge.
10. **continue-watching-wiring** — Persist/restore playback position; surface on Home.
11. **player-native-experimental** — Best-effort `StreamExtractor` (resolve direct HLS) +
    Media3/ExoPlayer screen + `PlaybackCoordinator`. Stretch goal; not a release blocker.
12. **polish-build-verify** — App icon/banner, error/empty/loading states, assemble debug APK,
    install + smoke test on emulator/device.

## Dependencies
- 0 stands alone (validation gate; recommended before 9)
- 2 → 1; 3 → 2; 4 → 2; 5 → 2
- 6 → 3,5; 7 → 3; 8 → 3,4,5
- 9 → 4,8 (and informed by 0); 10 → 5,9; 11 → 9 (experimental); 12 → all core

## Risks / Notes
- **Native extraction is NOT confirmed working** (encrypted + obfuscated + Cloudflare).
  It is treated as an experimental stretch goal; **WebView is the reliable primary path**,
  so the app works regardless of extraction success.
- **WebView caveats:** vidfast embeds may show ads/popups; we harden by blocking new-window
  navigations and known ad hosts, but cannot guarantee an entirely ad-free experience.
- **TMDB token** provided by user; stored in **gitignored local config** (e.g.
  `local.properties` / `secrets.properties`), surfaced via BuildConfig — never committed.
- **Legal:** for personal use; respect applicable terms/laws. No DRM circumvention.
- TV input: ensure every interactive element is D-pad focusable with clear focus visuals.
