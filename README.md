<div align="center">

  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio" width="320" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/nathenlow/NuvioMobile/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## About this fork

This is a fork of [NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile) that adds:

- Bulk downloads: download a whole season or series, or only the episodes you tick, with a queue, pause, resume and cancel all, and reordering. Movies have a Download button too.
- Stream choice for downloads: every stream is shown in full, the selected one is marked, and any unfinished or failed download can be switched to another stream. A finished file under 1 MB is rejected as not a real video.
- One download notification for the whole queue on Android (the first download, its percent and how many more, with Pause), and a Live Activity on iOS.
- An offline library for finished downloads.
- Downloads that keep running with the app closed, on Android and iOS.
- Download settings: concurrent download limit, preferred resolution (cached first, then your resolution, then the addon ranking), and SD card or USB storage on Android.
- Download profiles: pick which stream bulk download chooses with ordered rules and exclusions on cached state, source, resolution, size, addon, quality, codec, HDR, audio, language and edition. Includes built-in profiles, duplicate, import and export, and stream format markers that teach the app what the symbols in an AIOStreams name template mean (for example which one marks an uncached stream).
- A labelled action row on the details page: Library, Watched, Download, Shuffle and Sources.
- Library Downloaded and History views: the History view lists everything you watched, including unfinished titles, as shelves or a grid with sort, status and type filters, View all, paging, refresh and removal from your Nuvio account history.
- Settings > Advanced > Clear image cache, and saved posters and cover images are rechecked with the server after a week, so a replaced picture shows up.
- Audio subtitle sync: when subtitle auto sync finds no match, it listens to the stream's audio, finds the dialogue and aligns the subtitles to it. Progress shows on the subtitle card.

It follows upstream `cmp-rewrite` and is not affiliated with the Nuvio team.

## Get Nuvio Mobile

- [Android APK](https://github.com/nathenlow/NuvioMobile/releases/latest)
- iOS via AltStore or SideStore: add [this source URL](https://raw.githubusercontent.com/nathenlow/NuvioMobile/cmp-rewrite/store.json) in the app's Sources section, then install Nuvio. Updates are published here as unsigned IPAs, so AltStore or SideStore signs them with your own Apple ID.
- iOS beta builds: add [the beta source URL](https://raw.githubusercontent.com/nathenlow/NuvioMobile/cmp-rewrite/store-beta.json) instead to get pre-releases as well.

## Build from source

```bash
git clone https://github.com/nathenlow/NuvioMobile.git
cd NuvioMobile
```

### Android

Android development requires Android Studio and the Android SDK.

```bash
./gradlew :androidApp:assembleFullDebug
```

### iOS

iOS development requires macOS and Xcode.

```bash
env NUVIO_IOS_DISTRIBUTION=full xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -derivedDataPath build/ios-derived-full-simulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```

The shared app is built with Kotlin Multiplatform and Compose Multiplatform.

## License

[GNU General Public License v3.0](./LICENSE)
