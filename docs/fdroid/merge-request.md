# Merge request for fdroiddata

Status: submitted as <https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53448> on 2026-10-20 and closed by a
maintainer the same day (the app must have been developed for at least three months). Planned: re-open it, or open a
new one, from late January 2027. Before that update the recipe (see `docs/RELEASING.md`, "Resubmitting") and
this text: replace the version and commit below, add the development history, the list of releases and any evidence of
users.

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
* CI note: GitLab asks for identity verification (phone or card) before it runs pipelines in my fork, and I did not submit
  any. As the template suggests, please trigger the CI on your side. Locally `fdroid lint` and `fdroid rewritemeta` are
  clean and the build succeeds in the official fdroidserver container.
* `fdroid lint` passes and `fdroid build` succeeds in the official fdroidserver container.
* Dependencies are verified by Gradle (`gradle/verification-metadata.xml`), the wrapper distribution is pinned.

## Checklist

Reproducible builds are enabled, so no reason is needed there.

## Checklist

### Policy

* [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy).
* [x] The original app author has been notified (and does not oppose the inclusion). If you are not the author, please paste the link of the reply from the author.
* [x] The upstream app source code repo contains the app metadata in a [Fastlane](https://gitlab.com/snippets/1895688) or [Triple-T](https://gitlab.com/snippets/1901490) folder structure. The summary and description must be included and images, icon, and changelog should also be provided for better user experience. The `en-US` locale must be included.

### Docs

* [x] Please read [the guide](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md) first if this is your first contribution.
* [x] Please make sure your metadata follows the best practice in [our templates](https://gitlab.com/fdroid/fdroiddata/tree/master/templates).
* [x] Please read the [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/) and make sure your metadata is valid.
* [x] Please read the [Quick Start Guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/).

### Merge Request Setup

* [x] The title of this merge request should follow "New app: app name" format.
* [ ] Please make sure your fdroiddata fork is public and your branch is not protected. See <https://docs.gitlab.com/user/project/repository/branches/protected/>.
* [x] Please read [our Git guide](https://gitlab.com/fdroid/wiki/-/wikis/Tips-for-fdroiddata-contributors/Git-Usage) if you don't know how to rebase your branch. Don't rebase your branch if there is no conflict.
* [x] All related [fdroiddata](https://gitlab.com/fdroid/fdroiddata/issues) and [RFP issues](https://gitlab.com/fdroid/rfp/issues) have been referenced in this merge request
* [x] Please only submit one app in one MR.

### Metadata

* [x] Metadata must be put in `metadata/<applicationId>.yml`.
* [x] Metadata must be a valid YAML file.
* [x] Metadata must use LF as line ending.
* [x] Don't add summary/description/changelog/images or anything that should be provided in upstream repo. Please check the Changes tab to make sure there is no other unrelated files added in the MR.
* [x] Releases are tagged and auto update is enabled unless there is a special reason.
* [x] There is an issue tracker and contact info of the author so that we can report bugs and contact the author.
* [x] An AuthorName must be added. It doesn't need to be the real name.
* [x] External repos are added as git submodules instead of srclibs. You can update git submodules without opening an MR in this repo and the submodule is covered by our scanner.
* [x] Enable [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds). We'll use your signature for improved security/reliability, also allowing users to switch between different channels. Do note that if you don't enable reproducible build then the apk will be signed with our key so you can't enable it later. If you can't enable this, please add the reasons here.
* [x] Setup abi split if the APK is large and the splitted ones can be much smaller.
* [x] Only the latest versions should be kept in the metadata before it's merged. If you update the metadata, please replace the old versions with the new ones.
* [x] Don't add any disabled versions in the metadata.
* [x] The `commit` field should be the full hash. Please don't use tag or branch in commit.

### Pipeline

* [ ] All pipelines should pass.
* [ ] All warnings and errors in the Reports tab should be fixed or explained.
* [ ] F-Droid CI runners are under GitLab's FOSS program, so there's no need for you to pay for any CI time. If Gitlab starts asking for phone numbers or credit cards don't submit anything, just leave a note in the MR so we know we need to trigger the CI.
```
