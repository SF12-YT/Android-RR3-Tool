# Android RR3 Tool

An Android-native port of the Real Racing 3 parts of [Monkey Tool](https://github.com/zimex25/Monkey-Tool).

## Scope

The first Android releases focus on Real Racing 3. The initial port deliberately leaves out:

- 3D model viewing and conversion.
- Texture extraction, conversion and editing (handled by a separate app).

The remaining RR3 workflows are being ported in stages: game-data browsing and decoding, data-file editing, save inspection/editing, and supported car/mod data workflows where they do not depend on the omitted 3D or texture features.

## Engine and keys

The upstream C++17 engine is kept as a Git submodule at `third_party/Monkey-Tool`, so the Android app can reuse its format readers instead of maintaining a divergent copy.

The upstream RR3 NCT/GUI decoder includes its RC4 initialization table and key material in `src/nct_rc4.inc`. This is separate from the Save Editor's calibration values: hidden save fields such as R$ and Gold are calibrated against values currently displayed by the game and stored locally on the device.

## Android approach

- Kotlin for the Android UI and Storage Access Framework integration.
- C++17 via the Android NDK for the reused parsing and save-format code.
- JNI for the Kotlin/native boundary.
- User-selected document trees for game data and save locations; no hard-coded private storage path.

## Build

Open this repository in Android Studio (JDK 17, Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1), or run:

```sh
git clone --recurse-submodules https://github.com/SF12-YT/Android-RR3-Tool.git
cd Android-RR3-Tool
gradle assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Upstream and licences

Monkey Tool is Copyright (c) 2026 GM25 and is distributed under the MIT License. See [LICENSE-MONKEYTOOL](LICENSE-MONKEYTOOL) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Third-party code retains its original licence terms.

This is an unofficial community tool. It is not affiliated with or endorsed by Electronic Arts or Firemonkeys. Use it only with game files and saves you are entitled to access. Editing online-game data may violate the game's terms or risk account/save integrity.
