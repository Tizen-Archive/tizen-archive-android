# Tizen Companion Installer

Android app that installs Tizen watch packages (`.wgt`, `.tpk`) on a Samsung Galaxy Watch or Gear watch through
Galaxy Wearable, without a computer and without developer mode on the watch.

For every package the app builds a tiny code-free companion APK that carries the package in `assets/`, signs it with
a key kept in the Android Keystore and asks Android to install it. Samsung's Gear Plugin notices the companion and
sends the package to the watch over Bluetooth.

## Features

- Pick a folder, search, select packages and install them in one queue.
- Handles the "Install on the watch" link of the [Tizen Archive](https://tizen-archive.com) catalog
  (`tizen-archive://install?package=<id>[&version=<v>]`): the package is looked up, downloaded and verified (SHA-256 and
  size) before it is installed. The catalog address is fixed in the app; a link can never change it.
- No ads, analytics or trackers. The network is used only for the catalog link above.
- English and Russian interface.

## Requirements

- Android 8.0 or newer.
- Galaxy Wearable with Samsung's Gear Plugin and a watch connected over Bluetooth.
- Permission to install apps from this app (Android asks once).
- A package the watch accepts: a `.tpk` has to be signed with a certificate the watch trusts. The app cannot check
  this; when the watch refuses a package, Galaxy Wearable shows an error.

## Install

Download the APK from the [latest release](https://github.com/Tizen-Archive/tizen-companion-installer/releases/latest)
and open it on the phone (Android asks to allow installs from your browser or file manager).

Verify the download:

- SHA-256 of the file: in the `.sha256` file next to the APK and in the release notes;
- signing certificate SHA-256:
  `82:F1:DB:D6:FB:4E:F4:B6:8F:3B:07:C6:2A:95:C4:DC:D2:94:C0:B0:F1:E7:C4:E5:56:89:1F:45:2E:7B:8B:D9`
  (`apksigner verify --print-certs`). Every release is signed with this key, so updates install in place.

F-Droid: planned. F-Droid only takes apps that have been developed for at least three months, so the submission is
postponed to early 2027; see [docs/RELEASING.md](docs/RELEASING.md).

## Good to know

- Android asks you to confirm the installation of every companion.
- Gear Plugin handles one package at a time and silently drops deliveries that arrive while it is busy, so the app
  waits between installations (15 seconds plus a time that grows with the package size; a large package can take
  minutes over Bluetooth).
- The app cannot see whether the watch accepted the package; it only knows that the companion is installed on the
  phone.
- Do not uninstall a companion while the watch is connected: that removes the app from the watch as well.

## How it is built

- `core` (JVM, no Android API): binary `AndroidManifest.xml` writer, companion APK writer, Tizen package reader,
  naming, signing with `apksig`, delivery pacing, catalog client.
- `app`: Compose UI, Storage Access Framework folder scan, Android Keystore signing key, `PackageInstaller` queue.

Design notes (in Russian) are in the sibling `tizen-docs` of the workspace: `spec-android-companion-installer.md`,
`spike-s1-s2-results.md` and `plan-android-companion-installer.md`.

## Build

```
make check
```

Needs JDK 17+ (the Makefile defaults to `~/.local/share/jdks/temurin-21`) and the Android SDK
(`local.properties` with `sdk.dir`, platform 37, build-tools 36/37). Set `AAPT2=<path to aapt2>`
to additionally cross-check the generated manifest against Google's `aapt2`.

`make reproducible` builds the unsigned release APK twice from clean clones of `HEAD` and compares them.

For a catalog on your machine build with `./gradlew :app:assembleDebug -PcatalogUrl=http://<host>:8080` (debug builds
allow plain HTTP).

To write a signed sample companion for manual installation:

```
S4_OUT_DIR=/tmp/out S4_PACKAGE=/path/app.wgt ./gradlew :core:test --tests '*DeviceSamplesTest*'
```

Releases: [docs/RELEASING.md](docs/RELEASING.md).

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
