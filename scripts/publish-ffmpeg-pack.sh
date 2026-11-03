#!/usr/bin/env bash
# Publishes the on-demand FFmpeg video decoder pack (nextlib-media3ext's native libraries, unchanged) as assets of the
# prerelease the app downloads it from: github.com/<repo>/releases/tag/ffmpeg-video-<nextlib version>.
#
# Run it once whenever `nextlib` changes in gradle/libs.versions.toml, before releasing an app that uses that version.
# It's a prerelease, so the in-app updater (which reads /releases/latest) never sees it. The app checks every file's
# size and SHA-256 against values generated from the same AAR, so only these exact files will load.
#
# Usage:  scripts/publish-ffmpeg-pack.sh
# Needs:  gh (https://cli.github.com) logged in with push access.
set -euo pipefail

cd "$(dirname "$0")/.."
command -v gh >/dev/null || { echo "Install the GitHub CLI first: https://cli.github.com"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "Run 'gh auth login' first."; exit 1; }

version="$(sed -nE 's/^nextlib = "([^"]+)"/\1/p' gradle/libs.versions.toml)"
[ -n "$version" ] || { echo "No nextlib version in gradle/libs.versions.toml"; exit 1; }
tag="ffmpeg-video-$version"

./gradlew -q :core:media:exportFfmpegPack
dir=core/media/build/ffmpeg-pack
ls "$dir"/*.so >/dev/null

if gh release view "$tag" >/dev/null 2>&1; then
    echo "Release $tag exists; replacing its assets."
else
    gh release create "$tag" --prerelease --latest=false --target main --title "Video decoder pack $version" --notes \
"FFmpeg video decoders that QuickTube downloads on demand to play videos the phone's own decoders can't (such as 10-bit HEVC).

These are the native libraries of [nextlib-media3ext](https://github.com/anilbeesetti/nextlib) $version (GPL-3.0), unchanged, one set per CPU type. Not an app release."
fi
gh release upload "$tag" "$dir"/*.so --clobber
echo "Published $(ls "$dir"/*.so | wc -l) files to $tag."
