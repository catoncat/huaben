#!/usr/bin/env bash
# One-command end-to-end StoryNote release pipeline (~25-35s warm).
#
# Automates:
#   1. Version bump (app/build.gradle.kts + AppUpdateManager.kt, idempotent if already bumped)
#   2. Static hygiene check + optional unit tests + Android Release APK build
#   3. Domestic CNB auto-update channel publish (versioned + stable StoryNote-latest.apk + ETag verify)
#   4. Website release metadata sync (website/releases.json; HTML/QR stay permanently bound to latest)
#   5. Git commit & push + lightweight GitHub Release (with CNB direct link; skips slow uploads.github.com)
#
# Usage:
#   ./scripts/release.sh <version, e.g. 1.2.1> "<note1>" ["<note2>" ...]
#   Flags:
#     --skip-tests      Skip :app:desktopTest when tests already passed on the same code
#     --skip-build      Reuse existing dist/StoryNote-v<ver>.apk without rebuilding
#     --no-commit       Skip git commit/push and GitHub Release creation
#     --github-apk      Also upload APK asset to GitHub Releases (off by default; slow on CN links)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

SKIP_TESTS=0
SKIP_BUILD=0
NO_COMMIT=0
UPLOAD_GH_APK=0
POSITIONAL=()

for arg in "$@"; do
  case "$arg" in
    --skip-tests) SKIP_TESTS=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    --no-commit) NO_COMMIT=1 ;;
    --github-apk) UPLOAD_GH_APK=1 ;;
    -h|--help)
      sed -n '2,18p' "$0"
      exit 0
      ;;
    *) POSITIONAL+=("$arg") ;;
  esac
done

if [ "${#POSITIONAL[@]}" -lt 1 ]; then
  echo "Usage: $0 <version, e.g. 1.2.1> [release_notes...] [--skip-tests] [--skip-build] [--no-commit] [--github-apk]" >&2
  exit 1
fi

RAW_VER="${POSITIONAL[0]}"
VERSION="${RAW_VER#v}"
TAG="v${VERSION}"
NOTES_LIST=("${POSITIONAL[@]:1}")
if [ "${#NOTES_LIST[@]}" -eq 0 ]; then
  NOTES_LIST=("StoryNote ${TAG} 更新")
fi

T0="$(date +%s)"
step_ts() { date +%s; }

echo "=== StoryNote One-Shot Release: ${TAG} ==="

# 1. Idempotent version bump across app/build.gradle.kts and AppUpdateManager.kt
T_STEP="$(step_ts)"
python3 - "$VERSION" <<'PY'
import re, sys
from pathlib import Path

target_ver = sys.argv[1]
gradle_path = Path("app/build.gradle.kts")
mgr_path = Path("app/src/commonMain/kotlin/com/luna/storynote/update/AppUpdateManager.kt")

gradle_src = gradle_path.read_text("utf-8")
mgr_src = mgr_path.read_text("utf-8")

cur_ver_m = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_src)
cur_code_m = re.search(r'versionCode\s*=\s*(\d+)', gradle_src)
if not cur_ver_m or not cur_code_m:
    sys.exit("Cannot parse versionName/versionCode in app/build.gradle.kts")

cur_ver = cur_ver_m.group(1)
cur_code = int(cur_code_m.group(1))
next_code = cur_code if cur_ver == target_ver else cur_code + 1

new_gradle = re.sub(r'versionCode\s*=\s*\d+', f'versionCode = {next_code}', gradle_src, count=1)
new_gradle = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{target_ver}"', new_gradle, count=1)

new_mgr = re.sub(r'const val VERSION_NAME\s*=\s*"[^"]+"', f'const val VERSION_NAME = "{target_ver}"', mgr_src, count=1)
new_mgr = re.sub(r'const val VERSION_CODE\s*=\s*\d+', f'const val VERSION_CODE = {next_code}', new_mgr, count=1)

if new_gradle != gradle_src:
    gradle_path.write_text(new_gradle, "utf-8")
if new_mgr != mgr_src:
    mgr_path.write_text(new_mgr, "utf-8")

print(f"[1/5] Version synced: {cur_ver} (code {cur_code}) -> {target_ver} (code {next_code})")
PY
echo "      done in $(( $(step_ts) - T_STEP ))s"

# 2. Static hygiene + build Release APK
T_STEP="$(step_ts)"
APK_OUT="dist/StoryNote-${TAG}.apk"
mkdir -p dist

if [ "$SKIP_BUILD" -eq 0 ]; then
  bash e2e/check_static_hygiene.sh >/dev/null
  GRADLE_TASKS=(":app:assembleRelease")
  if [ "$SKIP_TESTS" -eq 0 ]; then
    GRADLE_TASKS=(":app:desktopTest" ":app:assembleRelease")
  fi
  echo "[2/5] Running Gradle: ${GRADLE_TASKS[*]}..."
  ./gradlew "${GRADLE_TASKS[@]}" --console=plain --quiet
  cp app/build/outputs/apk/release/app-release.apk "$APK_OUT"
else
  if [ ! -f "$APK_OUT" ]; then
    echo "APK not found for --skip-build: $APK_OUT" >&2
    exit 1
  fi
  echo "[2/5] Reusing existing APK: $APK_OUT"
fi

APK_SIZE="$(stat -f%z "$APK_OUT" 2>/dev/null || stat -c%s "$APK_OUT")"
APK_SHA256="$(shasum -a 256 "$APK_OUT" | awk '{print $1}')"
SIZE_LABEL="$(python3 -c "print(f'{int($APK_SIZE) / 1048576:.1f} MB')")"
echo "      APK ready: ${APK_OUT} (${SIZE_LABEL}, sha256=${APK_SHA256:0:16}...) in $(( $(step_ts) - T_STEP ))s"

# 3. Publish to CNB domestic auto-update channel
T_STEP="$(step_ts)"
NOTES_ONE_LINE="$(printf '%s；' "${NOTES_LIST[@]}")"
NOTES_ONE_LINE="${NOTES_ONE_LINE%；}"
echo "[3/5] Publishing to CNB domestic update channel..."
./scripts/publish-cnb-release.sh "$TAG" "$APK_OUT" "$NOTES_ONE_LINE"
echo "      CNB published & verified in $(( $(step_ts) - T_STEP ))s"

# 4. Sync website/releases.json
T_STEP="$(step_ts)"
CNB_REPO="${CNB_RELEASE_REPO:-lzb-prod/story-note-releases}"
APK_URL="https://cnb.cool/${CNB_REPO}/-/git/raw/main/apk/StoryNote-${TAG}.apk"
LATEST_APK_URL="https://cnb.cool/${CNB_REPO}/-/git/raw/main/apk/StoryNote-latest.apk"
MANIFEST_URL="https://cnb.cool/${CNB_REPO}/-/git/raw/main/latest.json"
PUB_DATE="$(date +%Y-%m-%d)"

python3 - "$VERSION" "$TAG" "$PUB_DATE" "$APK_SIZE" "$SIZE_LABEL" "$APK_SHA256" "$APK_URL" "$LATEST_APK_URL" "$MANIFEST_URL" "${NOTES_LIST[@]}" <<'PY'
import json, sys
from pathlib import Path

ver, tag, pub_date, size_s, size_label, sha256, apk_url, latest_url, manifest_url = sys.argv[1:10]
notes = sys.argv[10:]
path = Path("website/releases.json")
existing = json.loads(path.read_text("utf-8")) if path.is_file() else {}
data = {
    "appName": existing.get("appName", "话本本"),
    "subtitle": existing.get("subtitle", "小作家故事本"),
    "version": ver,
    "tag": tag,
    "publishedAt": pub_date,
    "sizeBytes": int(size_s),
    "sizeLabel": size_label,
    "sha256": sha256,
    "apkUrl": apk_url,
    "latestApkUrl": latest_url,
    "manifestUrl": manifest_url,
    "releaseNotes": notes,
}
path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", "utf-8")
PY
echo "[4/5] Updated website/releases.json in $(( $(step_ts) - T_STEP ))s"

# 5. Git commit/push + lightweight GitHub Release (skip slow uploads.github.com by default)
T_STEP="$(step_ts)"
if [ "$NO_COMMIT" -eq 0 ]; then
  if [ -n "$(git status --porcelain app/ website/ scripts/ gradle.properties)" ]; then
    git add app/build.gradle.kts app/src/commonMain/kotlin/com/luna/storynote/update/AppUpdateManager.kt website/releases.json
    if [ -n "$(git diff --cached --name-only)" ]; then
      git commit -m "chore: release StoryNote ${TAG}" >/dev/null
    fi
  fi
  git push origin main

  GH_NOTES="$(printf '### 主要更新\n'; for n in "${NOTES_LIST[@]}"; do printf -- '- %s\n' "$n"; done; printf '\n### 下载（国内直连）\n- [StoryNote-%s.apk](%s) (%s)\n- SHA-256: `%s`\n' "$TAG" "$APK_URL" "$SIZE_LABEL" "$APK_SHA256")"
  if gh release view "$TAG" --repo catoncat/story-note >/dev/null 2>&1; then
    gh release edit "$TAG" --repo catoncat/story-note --title "StoryNote ${TAG}" --notes "$GH_NOTES" >/dev/null
  else
    gh release create "$TAG" --repo catoncat/story-note --target main --title "StoryNote ${TAG}" --notes "$GH_NOTES" >/dev/null
  fi

  if [ "$UPLOAD_GH_APK" -eq 1 ]; then
    GODEBUG=http2client=0 gh release upload "$TAG" "$APK_OUT" --repo catoncat/story-note --clobber
  fi
  echo "[5/5] Git pushed & GitHub Release ${TAG} synced in $(( $(step_ts) - T_STEP ))s"
else
  echo "[5/5] Skipped git push & GitHub Release (--no-commit)"
fi

echo "=== RELEASE ${TAG} DONE in $(( $(date +%s) - T0 ))s ==="
