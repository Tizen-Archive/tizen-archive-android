# Merge request for fdroiddata

Title: `New app: Tizen Companion Installer`

Label: `~"New App"` (added by the template). Branch: `dev.tizenarchive.companion`. One file added:
`metadata/dev.tizenarchive.companion.yml` (copy of `dev.tizenarchive.companion.yml` next to this file).

Body (paste into the "App inclusion" template):

```markdown
## Abstract

Tizen Companion Installer installs Tizen watch packages (.wgt and .tpk) on Samsung Galaxy Watch / Gear watches
through the Galaxy Wearable app, without a computer and without developer mode on the watch. For every package it
builds a tiny code-free helper app that carries the package, signs it with a key kept in the Android Keystore and
asks Android to install it; Samsung's Gear Plugin then delivers the package to the watch over Bluetooth. It can also
handle the "Install on the watch" link of the Tizen Archive catalog (https://tizen-archive.com) and verifies the
downloaded file (SHA-256 and size) before installing it.

Source: https://github.com/Tizen-Archive/tizen-companion-installer (Apache-2.0). Screenshots are in the repository
(`fastlane/metadata/android/*/images/phoneScreenshots`).

## Why this app

Samsung shut down the Galaxy Store for Tizen watches, and the official way to put a package on a watch now needs a
computer, sdb and developer mode. I found no F-Droid app that installs Tizen packages through Galaxy Wearable. The
approach was verified on a Galaxy Watch (SM-R800, Tizen 5.5) with Galaxy Wearable 2.2.72 on Android 17.

## Notes for reviewers

* The app is useless without Samsung's proprietary Galaxy Wearable and Gear Plugin, so the recipe has the `NonFreeDep`
  anti-feature.
* No ads, analytics or trackers. The INTERNET permission is used only to download a package from the Tizen Archive
  catalog after the user opens an install link and confirms it. `REQUEST_INSTALL_PACKAGES` installs the helper apps.
* Reproducible build: `Binaries` and `AllowedAPKSigningKeys` are set. I checked with apksigcopier that the signature of
  the GitHub release APK can be copied onto the unsigned APK built by fdroidserver (version 0.1.1, commit
  0633d395f4a71326fd9f772b52206fbe4d0040cf).
* `fdroid lint` passes and `fdroid build` succeeds in the official fdroidserver container.
* Dependencies are verified by Gradle (`gradle/verification-metadata.xml`), the wrapper distribution is pinned.

## Checklist

(the template's checklist, with the boxes ticked that apply)
```
