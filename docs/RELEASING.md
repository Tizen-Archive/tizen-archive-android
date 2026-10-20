# Releasing

## Checklist

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` (`versionCode` grows by one per release).
2. Add `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` for every locale (at most 500 bytes).
3. `make check` and, from a clean commit, `make reproducible` (two clean clones must give the same APK).
4. Tag the commit `v<versionName>`.

## Signed APK for GitHub releases

```
./gradlew :app:assembleRelease --no-daemon \
  -PreleaseStoreFile=/path/to/release.jks -PreleaseStorePassword=... \
  -PreleaseKeyAlias=... -PreleaseKeyPassword=...
```

Without the properties the release APK stays unsigned, which is what F-Droid builds and signs itself. Keep the
keystore outside the repository.

## Publishing the GitHub release as the organization's GitHub App

A release is attributed to the account whose token creates it. To publish it as the organization's GitHub App
(shown as `<app-slug>[bot]`) use an installation token:

```
export GITHUB_APP_CLIENT_ID=Iv23...            # client ID on the app settings page
export GITHUB_APP_KEY=~/.config/tizen-companion-installer/github-app.pem
GH_TOKEN=$(scripts/github-app-token.sh) gh release create v<version> \
  tizen-companion-installer-<version>.apk tizen-companion-installer-<version>.apk.sha256 \
  -R Tizen-Archive/tizen-companion-installer --verify-tag --title "..." --notes-file notes.md
```

The app needs only the repository permission "Contents: Read and write" and must be installed on this repository.
The token lives for one hour; keep the private key outside the repository with mode 600.

## F-Droid

Open a merge request to <https://gitlab.com/fdroid/fdroiddata> with `docs/fdroid/dev.tizenarchive.companion.yml`
(fill the placeholders first). Store texts come from `fastlane/metadata/android`. Things F-Droid checks:

- only free dependencies, no Google Play Services, no tracking: see `gradle/verification-metadata.xml` for the full
  list of resolved artifacts (checksums are verified on every build);
- dependency metadata blobs are switched off (`dependenciesInfo`);
- the Gradle distribution and wrapper are pinned (`distributionSha256Sum`);
- the app needs Samsung's proprietary Galaxy Wearable, hence the `NonFreeDep` anti-feature in the draft.

Screenshots (`fastlane/metadata/android/<locale>/images/phoneScreenshots/`) are not included yet.

## Updating dependencies

After changing a version in `gradle/libs.versions.toml` refresh the checksums:

```
./gradlew --write-verification-metadata sha256 check :app:assembleRelease :app:assembleDebugAndroidTest
```

and review the diff of `gradle/verification-metadata.xml`.
