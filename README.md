# Claire's TV

A small, fast native Android TV player for an IPTV subscription you already pay for. It browses live
channels, films and series from an Xtream Codes account or an M3U playlist, and is built for remote-control
use on low-memory TV hardware: plain Java, Android Views, SQLite and Media3, with no third-party UI or image
libraries.

**No content, playlists, accounts or provider details are included.** You supply your own source on the
device; it is stored encrypted with the Android Keystore in the app's private database.

## What it does

- **Live TV** — categories, channels and an Information column. Independent category and channel search,
  favorites, reorderable categories, and an on-demand Xtream guide for the selected channel.
- **Films** and **Series** — categories beside a poster grid. Opening a title gives it a page with artwork,
  release, genre, rating, duration, plot, cast and director, and a **Watch** button that already has focus.
  A series page continues into its seasons and the chosen season's episodes.
- **Continue watching** — a category in both libraries listing whatever you stopped partway through, newest
  first. Opening a series from it lands on the episode you stopped on. A title watched to within a minute of
  the end counts as finished and leaves the list.
- **Playback** — fullscreen, no controller scrim on live. The channel name appears in a slim translucent
  strip at the top on start and on every channel change. Connection state (`Fetching the stream…`,
  `Retrying the connection…`, errors with their HTTP status) is reported quietly along the bottom. Up/Down
  change channel within the current filter; films and episodes get on-demand controls, seek and resume.

### Built for small TV boxes

- The catalog is streamed into an indexed SQLite database in one transaction — never held in memory.
- Lists are virtual: at most eight 250-row pages are cached, queries run on a worker, and row binding only
  touches cached data.
- Posters decode straight to the cell size in RGB_565, capped at 16 MB in memory and 600 files on disk, on
  three minimum-priority threads. A poster that arrives after its cell was recycled is discarded.
- The guide uses its own worker so it can never hold up channel queries.
- No analytics, no telemetry, no background services, no ads.

## Build

Requirements: **JDK 17**, Android SDK platform 36, build-tools, and internet access for the pinned
Gradle/Maven dependencies.

Create `local.properties` pointing at your SDK (this file is deliberately not committed):

```properties
sdk.dir=/path/to/Android/sdk
```

Then:

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleRelease
```

The release APK lands in `app/build/outputs/apk/release/`. It is about 1 MB and contains no debug fixtures.

> The build signs with the local Android **debug** key. That is fine for sideloading onto your own device,
> but do not use it for public distribution. Keep whatever key you do use — Android only allows in-place
> updates when the signature matches.

### Install on a TV

```sh
adb connect <tv-ip>
adb install -r app/build/outputs/apk/release/app-release.apk
```

Open the app, choose **Xtream Codes** or **M3U playlist URL**, and enter the details your provider gave you.
The first load downloads the catalog once; later opens use the saved copy. **Refresh** updates it, and a
failed refresh keeps the previous catalog.

## Using it with the remote

- **Live TV**: Right moves from the categories to the channels. OK plays the selected channel; holding OK
  toggles a favorite. The Information column is read-only and takes no focus.
- **Categories**: hold OK on one to reorder it — the full list appears with it selected, Up/Down moves it,
  OK saves and Back cancels. The order survives restarts and ordinary refreshes.
- **Films / Series**: OK on a poster opens its page; holding OK toggles a favorite without opening it. Back
  returns to the grid where you left it.
- **Search**: in either dialog the keyboard's Search/Done key or Enter submits, hides the keyboard and
  returns focus to the results.

## Tests

```sh
./gradlew --no-daemon testDebugUnitTest          # JVM: parsing, URL building, guide formatting
./gradlew --no-daemon assembleDebugAndroidTest   # on-device suite
adb shell am instrument -w tv.local.fast.test/tv.local.fast.CatalogInstrumentation
```

The on-device suite is a plain `Instrumentation` runner rather than a test framework. It builds its own
synthetic catalog in an isolated database (`benchmark.db`) — it never touches a real one — and checks
paging, search, category reorder and its persistence, schema migrations, favorites, the poster grid and
detail pages, Continue watching, playback controls and resume, and D-pad navigation. It prints timings
alongside the results. **`INSTRUMENTATION_CODE: -1` means the run passed.**

## Notes from building this

Things that only showed up against a real provider, kept here because they are easy to hit again:

- Panels commonly answer **HTTP 406** on `/movie` and `/series` unless the request's User-Agent looks like a
  media player, even when the catalog API accepted the same agent happily.
- A whole-catalog response can run far past a single reply — one series list was over 64 MB decompressed — so
  catalog streams get their own byte budget, separate from ordinary API replies, with a row cap as the real
  bound.
- A category id the provider never names must not fall back to its bare number: numeric names sort above
  every real one and bury the actual genres. They are labelled and sorted last instead.
- On a TV, geometric focus search will not always step across nested rows. Where the remote has to reach a
  list below a row of buttons, the Down path is wired explicitly with `nextFocusDownId`.

## Not included

Catch-up, recording, multiple profiles, parental controls, DRM, XMLTV import, custom HTTP headers, external
players, trailers, casting, and any form of VPN. Provider URLs that redirect are rejected — use the final
direct address. `minSdk` is 26.

## Privacy

The app asks for `INTERNET` and nothing else. Your source credentials are encrypted with the Android
Keystore and stored only in the app's private database; backups are disabled. Nothing is sent anywhere
except your own provider. The only logging is a count or an error code — never a title, id, URL or
credential.

## Licence

[MIT](LICENSE).
