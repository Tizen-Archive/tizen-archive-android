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
