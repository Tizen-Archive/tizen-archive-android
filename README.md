# tizen-companion-installer

Android app that installs Tizen watch packages (`.wgt`, `.tpk`) on a Samsung watch through Galaxy
Wearable. For every package it builds a tiny code-free companion APK that carries the package in
`assets/`, signs it and hands it to the system installer; Gear Plugin then delivers the package to the
watch. Design notes: `../tizen-docs/spec-android-companion-installer.md`,
`../tizen-docs/spike-s1-s2-results.md`, plan in `../tizen-docs/plan-android-companion-installer.md`.

Status: early, works on a device. `core` (JVM) has the binary manifest generator, the companion APK writer,
the Tizen package reader, naming and signing; `app` is a Compose app that picks a folder, builds and signs
one companion per package with an Android Keystore key and installs it through `PackageInstaller`.

## Build

```
make check
```

Needs JDK 17+ (the Makefile defaults to `~/.local/share/jdks/temurin-21`) and the Android SDK
(`local.properties` with `sdk.dir`, platform 37, build-tools 36/37). Set `AAPT2=<path to aapt2>`
to additionally cross-check the generated manifest against Google's `aapt2`.

To write a signed sample companion for manual installation:

```
S4_OUT_DIR=/tmp/out S4_PACKAGE=/path/app.wgt ./gradlew :core:test --tests '*DeviceSamplesTest*'
```

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
