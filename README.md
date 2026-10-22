# Tizen Companion Installer

[![check](https://github.com/Tizen-Archive/tizen-companion-installer/actions/workflows/check.yml/badge.svg)](https://github.com/Tizen-Archive/tizen-companion-installer/actions/workflows/check.yml)

Android app that installs Tizen watch packages (`.wgt`, `.tpk`) on a Samsung Galaxy Watch or Gear watch, without a
computer. It has two ways of sending, chosen with a switch at the top of the screen:

- **Galaxy Wearable** (no developer mode needed). For every package the app builds a tiny code-free companion APK that
  carries the package in `assets/`, signs it with a key kept in the Android Keystore and asks Android to install it.
  Samsung's Gear Plugin notices the companion and sends the package to the watch over Bluetooth.
- **Wi-Fi** (faster, shows the real result). The phone talks `sdb` to the watch over your Wi-Fi network and installs the
  package with the watch's own package manager (`pkgcmd -w`, the mode that accepts store-signed packages). It needs
  Debugging turned on in the watch's developer options.

Both ways install only what the watch's own package manager accepts (a valid store signature is still required): the app never modifies a package or its signatures.

## Features

- Two ways of sending (see above). The switch, a one-line status ("Ready · 192.168.0.188", "Setup needed") and the setup
  behind it (a bottom sheet) sit above the tabs; the header slides away when you scroll.
- Pick a folder, search, select packages and install them in one queue.
- Handles the "Install on the watch" link of the [Tizen Archive](https://tizen-archive.com) catalog
  (`tizen-archive://install?package=<id>[&version=<v>]`): the package is looked up, downloaded and verified (SHA-256 and
  size) before it is installed. The catalog address is fixed in the app; a link can never change it.
- Open a downloaded `.wgt`/`.tpk` from a file manager ("Open with") or share it to the app.
- Package names, versions and icons are shown in the list; packages already sent are skipped.
- A package the watch is expected to refuse (no distributor signature, a forged or untrusted certificate, an expired or
  device-bound developer certificate) is marked in the list before you send it. This is a prediction from the
  certificate chain (known Samsung CAs), not a full signature check, and it never blocks sending.
- The "On the watch" tab lists what this phone sent to the watch (read from the helper apps themselves, so the list
  survives app updates), sends a package again, checks the catalog for newer versions and updates them, and removes helper
  apps.
- Wi-Fi way: **Find the watch** looks for it in your Wi-Fi network (no other app is needed to learn its IP address), or type
  the address by hand. The first time, the watch asks you to allow this phone. The queue shows sending and installation
  progress and the exact answer of the watch if it refuses a package (for example `Check certificate error -12`).
  A version the watch already has, or a newer one, is skipped (an option installs it again). The "On the watch" tab then shows the
  packages the watch itself reports, with search, and removes the ones this app installed (the watch does not tell
  system packages apart, so only the app's own can be removed).
- Galaxy Wearable way: a card tells what is missing (Galaxy Wearable, Gear Plugin, the install permission); the screen stays on while a queue
  runs and a notification reports its end. While a queue runs a foreground service keeps the app alive when you switch
  away, with a progress notification (and Cancel); when the app is in the background, Android's install confirmation
  arrives as a notification you tap, because Android does not let a background app open windows.
- No ads, analytics or trackers. The internet is used only for the catalog link above and for the update check you
  start. The Wi-Fi way connects only to the watch in your local network (a private address you type or find with
  the button, port 26101) and never to anything else.
- English and Russian interface.

## Requirements

- Android 8.0 or newer.
- A package the watch accepts: a `.tpk` has to be signed with a certificate the watch trusts. The app only predicts this
  from the certificate chain; when the watch refuses a package, the Wi-Fi way shows its answer and the Galaxy Wearable
  way shows an error in Galaxy Wearable.

Galaxy Wearable way:

- Galaxy Wearable with Samsung's Gear Plugin and a watch connected over Bluetooth.
- Permission to install apps from this app (Android asks once).

Wi-Fi way:

- Debugging on in the watch's developer options, and the watch on the same Wi-Fi network as the phone (not a guest
  network that isolates devices). To turn it on: Settings → About watch → Software, tap the software version five
  times, then Developer options → Debugging. Keep the watch's screen on while you connect: its Wi-Fi sleeps.
- On Android 17 the permission to use the local network (Android asks when you search or send).
- No other `sdb` tool connected to the watch at the same moment (the watch serves one host at a time), and no VPN
  running on the phone (it can capture the traffic to the watch).
- Tested on a Galaxy Watch (SM-R800, Tizen 5.5) with Android 17.

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

Wi-Fi way:

- The watch asks once to allow the phone ("Allow debugging"). The phone's key lives in the Android Keystore and never
  leaves the device; the watch keeps it in its list of allowed computers, which the app cannot clear.
- The address of the watch can change (the router hands out addresses); if the watch is not found at the saved address,
  press **Find the watch** again.
- Removing a package from the "On the watch" tab also deletes its data on the watch.
- Not supported: `sdb` over Bluetooth. The watches' sdb daemon closes such connections at once (checked on SM-R800), so
  the app uses Wi-Fi.

Galaxy Wearable way:

- Android asks you to confirm the installation of every companion.
- Gear Plugin handles one package at a time and silently drops deliveries that arrive while it is busy, so the app
  waits between installations (15 seconds plus a time that grows with the package size; a large package can take
  minutes over Bluetooth).
- The app cannot see whether the watch accepted the package; it only knows that the companion is installed on the
  phone.
- The "On the watch" tab lists the companions on the phone (they carry the name and version of the Tizen package) and can
  remove them. Removing a companion asks Gear Plugin to remove the app from the watch too, but Gear Plugin does not
  always do it; check the app list in Galaxy Wearable (the app offers to open it).

## How it is built

- `core` (JVM, no Android API): binary `AndroidManifest.xml` writer, companion APK writer, Tizen package reader,
  naming, signing with `apksig`, delivery pacing, catalog client, and an `sdb` host (`sdb` package: packets, RSA
  authorization, the `shell` and `sync` services, `pkgcmd` output parsing, the installer, the scanner that finds watches).
- `app`: Compose UI, Storage Access Framework folder scan, Android Keystore signing key, `PackageInstaller` queue, and
  the Wi-Fi way (`wifi` package: Keystore host key, network binding that avoids VPNs, settings and history).

Design notes (in Russian) are in the sibling `tizen-docs` of the workspace: `spec-android-companion-installer.md`,
`spike-s1-s2-results.md` and `plan-android-companion-installer.md` for the Galaxy Wearable way, and
`spec-wifi-install-mode.md` and `spike-wifi-sdb-results.md` for the Wi-Fi way (`spike-bt-sdb-results.md` records why
`sdb` over Bluetooth does not work).

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

## Testing

`make check` runs everything on the JVM, with no phone or emulator:

- `core`: JUnit 5 unit tests (binary manifest writer, APK layout and signing, package reader, catalog client,
  link parser, pacing) plus fuzz-style robustness tests. The `sdb` client is tested against `FakeSdbd`, a small sdbd on
  loopback written from the protocol (authorization with key confirmation, busy and resetting watches, `shell` and
  `sync`, stale packets), and the scanner against it and against stand-in services.
- `app`: Robolectric tests. `MainViewModelTest` drives the real `MainViewModel` on fakes (`Dependencies`: installer,
  catalog, folder, clock), including the pacing between operations; `MainContentTest` renders the screen with
  Compose test APIs against a recording `MainActions` (the way-of-sending header, the setup sheet, the Wi-Fi rows and
  tabs included), in English and Russian.

GitHub Actions (`.github/workflows/check.yml`) runs `./gradlew check` with an empty Gradle cache on every push and pull
request, and `make reproducible` on `v*` tags.

Debug builds use the application id suffix `.debug`, so they can be installed next to a release build without touching
it. Instrumented tests (`app/src/androidTest`, Keystore and scale) need a device: `./gradlew :app:connectedDebugAndroidTest`.

Releases: [docs/RELEASING.md](docs/RELEASING.md).

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
