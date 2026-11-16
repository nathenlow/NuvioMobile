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

- Bulk downloads: download a whole season or series, with a queue, pause, resume and cancel all, and reordering.
- An offline library for finished downloads.
- Downloads that keep running with the app closed, on Android and iOS, with a Live Activity on iOS.
- Download settings: concurrent download limit, preferred resolution (cached first, then your resolution, then the addon ranking), and SD card or USB storage on Android.

It follows upstream `cmp-rewrite` and is not affiliated with the Nuvio team.

## Get Nuvio Mobile

- [Android APK](https://github.com/nathenlow/NuvioMobile/releases/latest)
- iOS via AltStore or SideStore: add [this source URL](https://raw.githubusercontent.com/nathenlow/NuvioMobile/cmp-rewrite/store.json) in the app's Sources section, then install Nuvio. Updates are published here as unsigned IPAs, so AltStore or SideStore signs them with your own Apple ID.

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
