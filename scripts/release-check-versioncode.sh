#!/usr/bin/env bash
#
# Release versionCode gate.
#
# Resolves the newest STABLE release OTHER than the current tag via the GitHub
# Releases API (draft=false, prerelease=false, tag_name != current), downloads
# its APK, reads both versionCodes with aapt2, and requires the new one to be
# strictly greater.
#
# Usage:
#   release-check-versioncode.sh <owner/repo> <current-tag> <apk-path> [aapt2-path]
#
# Explicit logs (auditable):
#   previous release=v0.1.2
#   previous versionCode=23
#   new versionCode=24
#
set -euo pipefail

REPO="${1:?repo required (owner/name)}"
CURRENT_TAG="${2:?current tag required}"
APK="${3:?apk path required}"
AAPT2="${4:-aapt2}"

# Newest stable release that is not the current tag (Releases API is newest-first).
GH_OUT=$(gh api "repos/$REPO/releases" \
  --jq "[.[] | select(.draft == false and .prerelease == false and .tag_name != \"$CURRENT_TAG\")][0].tag_name" 2>&1) || {
  echo "ERROR: gh api failed:"
  echo "$GH_OUT"
  exit 1
}
PREV_RELEASE=$(echo "$GH_OUT" | tail -1)

if [ -z "$PREV_RELEASE" ] || [ "$PREV_RELEASE" = "null" ]; then
  echo "ERROR: no previous stable release found (current tag: $CURRENT_TAG)"
  echo "gh api output was: $GH_OUT"
  exit 1
fi

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

gh release download "$PREV_RELEASE" --repo "$REPO" --pattern '*.apk' -D "$WORK_DIR" > /dev/null 2>&1
PREV_APK=$(ls "$WORK_DIR"/*.apk 2>/dev/null | head -1 || true)
if [ -z "$PREV_APK" ]; then
  echo "ERROR: previous release $PREV_RELEASE has no APK asset"
  exit 1
fi

NEW_VC=$("$AAPT2" dump badging "$APK" 2>/dev/null | grep -o "versionCode='[0-9]*'" | head -1 | grep -o '[0-9]*' || true)
OLD_VC=$("$AAPT2" dump badging "$PREV_APK" 2>/dev/null | grep -o "versionCode='[0-9]*'" | head -1 | grep -o '[0-9]*' || true)

echo "previous release=$PREV_RELEASE"
echo "previous versionCode=$OLD_VC"
echo "new versionCode=$NEW_VC"

if [ -z "$NEW_VC" ] || [ -z "$OLD_VC" ]; then
  echo "ERROR: could not read versionCode from APKs"
  exit 1
fi
if [ "$NEW_VC" -le "$OLD_VC" ]; then
  echo "ERROR: versionCode did not increase ($NEW_VC <= $OLD_VC)"
  exit 1
fi
echo "OK: versionCode increased ($OLD_VC -> $NEW_VC)"
