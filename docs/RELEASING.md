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

Without the properties the release APK stays unsigned, which is what F-Droid builds. Keep the keystore outside the
repository.

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

**Status.** The recipe `docs/fdroid/dev.tizenarchive.companion.yml` was submitted as
[fdroiddata !53448](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53448) on 2026-10-20 and closed the same day
by a maintainer: F-Droid's Inclusion Policy asks for at least three months of active development unless the app has
proven users, the author already maintains apps on F-Droid, or the app is for an event. The build, recipe and metadata
raised no objections, and the maintainer invited us to re-open the MR once the criteria are met. The repository was
created on 2026-10-20, so the next attempt is planned for **late January 2027** or later. GitHub Releases is the only
other distribution channel.

**Until then:** keep releasing as usual (checklist above) and keep the signing key and the GitHub App key safe. Evidence
of users helps (release downloads, the "Android app" line in the Tizen Archive download statistics).

**Resubmitting:**

1. Release the current version (the recipe points at a version, a full commit hash and the GitHub release APK).
2. Update `docs/fdroid/dev.tizenarchive.companion.yml`: `versionName`, `versionCode`, `commit` (full hash),
   `CurrentVersion`, `CurrentVersionCode`. Normalize it with `fdroid rewritemeta`, run `fdroid lint` and the local
   build below, then `apksigcopier compare <github apk> --unsigned <fdroid-built apk>`.
3. Sync the fork `gitlab.com/tizen-archive/fdroiddata` with upstream, update the branch `dev.tizenarchive.companion`
   (`metadata/dev.tizenarchive.companion.yml`, committed as "New app: Tizen Companion Installer" by Tizen Archive) and
   re-open !53448 or open a new MR. Text for the description: `docs/fdroid/merge-request.md`; add the development
   history, the releases and the evidence of users.
4. GitLab asks the fork for identity verification (phone or card) before it runs CI; do not submit anything, the
   F-Droid maintainers run the CI themselves (say so in the MR, the text already does).

Things F-Droid checks:

- only free dependencies, no Google Play Services, no tracking: see `gradle/verification-metadata.xml` for the full
  list of resolved artifacts (checksums are verified on every build);
- dependency metadata blobs are switched off (`dependenciesInfo`);
- the Gradle distribution and wrapper are pinned (`distributionSha256Sum`);
- the app needs Samsung's proprietary Galaxy Wearable, hence the `NonFreeDep` anti-feature in the recipe;
- the build is reproducible, so the recipe sets `Binaries` (the APK of the GitHub release) and `AllowedAPKSigningKeys`
  (SHA-256 of our release certificate): when F-Droid's rebuild matches the GitHub APK it publishes **our signed APK**,
  and users can switch between GitHub and F-Droid. The F-Droid build is `unsigned/<id>_<versionCode>.apk` in the
  scratch directory of the local build.

Store images live in `fastlane/metadata/android/<locale>/images/` (`icon.png`, `phoneScreenshots/`).

## Checking the F-Droid build locally

`fdroid lint` and `fdroid build` run in the official container. Put `docs/fdroid/dev.tizenarchive.companion.yml`
into `metadata/` of a scratch directory next to the `config/` folder of fdroiddata (`antiFeatures.yml`,
`categories.yml` and the icons they reference), clone the repository into `build/dev.tizenarchive.companion`, then:

```
docker run --rm -u $(id -u):$(id -g) -e HOME=/tmp -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory \
  -e GIT_CONFIG_VALUE_0='*' -v "$PWD":/repo registry.gitlab.com/fdroid/docker-executable-fdroidserver:master \
  build -v -l --no-tarball dev.tizenarchive.companion
```

The build resolves dependencies with an empty Gradle cache, so metadata regenerated only from incremental local
builds can be incomplete. Always refresh it with a fresh Gradle user home (see below).

## Updating dependencies

After changing a version in `gradle/libs.versions.toml` refresh the checksums:

```
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --write-verification-metadata sha256 \
  assembleRelease check :app:assembleDebugAndroidTest
```

and review the diff of `gradle/verification-metadata.xml`.
