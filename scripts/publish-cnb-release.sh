#!/usr/bin/env bash
# Publish StoryNote Android APK + latest.json to the public domestic CNB release channel
# (https://cnb.cool/lzb-prod/story-note-releases) without storing or logging any secrets.
#
# Uses a single-commit squashed `main` branch in the dedicated distribution repo so:
# - Repo size stays bounded (~17MB) across unlimited releases
# - Android clients in mainland China fetch both `latest.json` and the APK directly via
#   unauthenticated `https://cnb.cool/.../-/git/raw/main/...` with zero tokens
#
# Usage:
#   ./scripts/publish-cnb-release.sh v1.1.0 dist/StoryNote-v1.1.0.apk "更新说明"
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: $0 <tag, e.g. v1.1.0> <apk_path> [release_notes]" >&2
  exit 1
fi

# Inject CNB git password strictly via child-process env (never stdout / argv / disk)
if [ -z "${CNB_GIT_PASSWORD:-}" ]; then
  exec jt resolve jt://secret/4wMo2U4l --env CNB_GIT_PASSWORD --exec "$0" "$@"
fi

TAG="$1"
VERSION="${TAG#v}"
APK_PATH="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
NOTES="${3:-StoryNote $TAG 更新}"
CNB_REPO="${CNB_RELEASE_REPO:-lzb-prod/story-note-releases}"

if [ ! -f "$APK_PATH" ]; then
  echo "APK not found: $APK_PATH" >&2
  exit 1
fi

ASSET_NAME="$(basename "$APK_PATH")"
APK_SIZE="$(stat -f%z "$APK_PATH" 2>/dev/null || stat -c%s "$APK_PATH")"
APK_SHA256="$(shasum -a 256 "$APK_PATH" | awk '{print $1}')"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

GIT_ASKPASS_SCRIPT="$TMP_DIR/askpass.sh"
cat >"$GIT_ASKPASS_SCRIPT" <<'EOF'
#!/usr/bin/env sh
case "$1" in
  *Username*) printf '%s\n' "cnb" ;;
  *) printf '%s\n' "$CNB_GIT_PASSWORD" ;;
esac
EOF
chmod 700 "$GIT_ASKPASS_SCRIPT"

REPO_DIR="$TMP_DIR/repo"
mkdir -p "$REPO_DIR/apk"
git -C "$REPO_DIR" init -b main >/dev/null
git -C "$REPO_DIR" remote add origin "https://cnb.cool/${CNB_REPO}.git"
git -C "$REPO_DIR" config user.name "StoryNote Release Bot"
git -C "$REPO_DIR" config user.email "release@storynote.local"
git -C "$REPO_DIR" config http.postBuffer 524288000

cp "$APK_PATH" "$REPO_DIR/apk/${ASSET_NAME}"

APK_URL="https://cnb.cool/${CNB_REPO}/-/git/raw/main/apk/${ASSET_NAME}"
RELEASE_URL="https://cnb.cool/${CNB_REPO}"

cat >"$REPO_DIR/README.md" <<EOF
# StoryNote（小作家故事本）国内自动更新发布通道

- 当前版本：**v${VERSION}**
- 安装包直链：${APK_URL}
- SHA-256：\`${APK_SHA256}\`

本仓库仅存放最新签名 Release APK 与 \`latest.json\` 更新清单，供客户端在国内网络下免登录直连检测更新与一键升级。
EOF

python3 - "$REPO_DIR/latest.json" "$VERSION" "$TAG" "$APK_URL" "$RELEASE_URL" "$NOTES" "$APK_SIZE" "$APK_SHA256" <<'PY'
import json, sys, datetime
out, ver, tag, apk_url, rel_url, notes, size_s, sha256 = sys.argv[1:9]
data = {
    "version": ver,
    "tag_name": tag,
    "apk_url": apk_url,
    "download_url": apk_url,
    "release_url": rel_url,
    "notes": notes,
    "size_bytes": int(size_s),
    "sha256": sha256,
    "published_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY

echo "[1/2] Pushing ${ASSET_NAME} (${APK_SIZE} bytes) + latest.json to CNB (${CNB_REPO})..."
git -C "$REPO_DIR" add README.md latest.json "apk/${ASSET_NAME}"
git -C "$REPO_DIR" commit -m "release: StoryNote ${TAG}" >/dev/null
git -C "$REPO_DIR" tag -f "$TAG"
GIT_TERMINAL_PROMPT=0 GIT_ASKPASS="$GIT_ASKPASS_SCRIPT" \
  git -C "$REPO_DIR" push --force -u origin main
GIT_TERMINAL_PROMPT=0 GIT_ASKPASS="$GIT_ASKPASS_SCRIPT" \
  git -C "$REPO_DIR" push --force origin "refs/tags/${TAG}"

echo "[2/2] Verifying unauthenticated domestic manifest + APK download..."
MANIFEST_URL="https://cnb.cool/${CNB_REPO}/-/git/raw/main/latest.json"
curl -fsSL "$MANIFEST_URL"
echo "---"
curl -fsSLI "$APK_URL" | head -n 12
VERIFY_APK="$TMP_DIR/verify.apk"
curl -fsSL "$APK_URL" -o "$VERIFY_APK"
GOT_SHA="$(shasum -a 256 "$VERIFY_APK" | awk '{print $1}')"
if [ "$GOT_SHA" != "$APK_SHA256" ]; then
  echo "SHA-256 mismatch! expected=$APK_SHA256 got=$GOT_SHA" >&2
  exit 1
fi
echo "VERIFIED_OK sha256=${GOT_SHA} manifest=${MANIFEST_URL} apk=${APK_URL}"
