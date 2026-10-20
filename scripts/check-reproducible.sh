#!/usr/bin/env bash
# Builds the unsigned release APK from two clean clones of HEAD in different directories and compares them.
# F-Droid rebuilds releases the same way, so any difference here would also fail its verification.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/jdks/temurin-21}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/.local/share/android-sdk}
export PATH=$JAVA_HOME/bin:$PATH

if [ -n "$(git -C "$root" status --porcelain)" ]; then
    echo "warning: uncommitted changes are not part of the clones" >&2
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

for name in first second; do
    git clone -q "$root" "$work/$name/src"
    (cd "$work/$name/src" && ./gradlew :app:assembleRelease --no-daemon -q)
done

apk=app/build/outputs/apk/release/app-release-unsigned.apk
a=$(sha256sum "$work/first/src/$apk" | cut -d' ' -f1)
b=$(sha256sum "$work/second/src/$apk" | cut -d' ' -f1)
echo "first : $a"
echo "second: $b"

if [ "$a" = "$b" ]; then
    echo "reproducible"
else
    echo "NOT reproducible; differing entries:" >&2
    diff <(unzip -lv "$work/first/src/$apk" | awk '{print $7, $8}') <(unzip -lv "$work/second/src/$apk" | awk '{print $7, $8}') >&2 || true
    exit 1
fi
