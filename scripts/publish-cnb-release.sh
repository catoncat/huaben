#!/usr/bin/env bash
# Publish 话本本 (Huaben) Android APK + latest.json to the public domestic CNB release channel
# (https://cnb.cool/lzb-prod/story-note-releases) without storing or logging any secrets.
#
# Uses a single-commit squashed `main` branch in the dedicated distribution repo so:
# - Repo size stays bounded (~17MB) across unlimited releases
# - `apk/huaben-v<ver>.apk`, `apk/huaben-latest.apk`, and legacy `apk/StoryNote-latest.apk`
#   all share the exact same Git blob (0 extra upload bytes) for zero-breakage compatibility
# - Integrity is verified in ~0.2s via HTTP HEAD ETag (Git blob SHA-1) + Content-Length
#
# Usage:
#   ./scripts/publish-cnb-release.sh v1.2.0 dist/huaben-v1.2.0.apk "更新说明" [--full-download-verify]
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: $0 <tag, e.g. v1.2.0> <apk_path> [release_notes] [--full-download-verify]" >&2
  exit 1
fi

# Inject CNB git password strictly via child-process env (never stdout / argv / disk)
if [ -z "${CNB_GIT_PASSWORD:-}" ]; then
  exec jt resolve jt://secret/4wMo2U4l --env CNB_GIT_PASSWORD --exec "$0" "$@"
fi

TAG="$1"
VERSION="${TAG#v}"
APK_PATH="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
NOTES="${3:-话本本 $TAG 更新}"
FULL_DOWNLOAD_VERIFY=0
for arg in "$@"; do
  if [ "$arg" = "--full-download-verify" ]; then
    FULL_DOWNLOAD_VERIFY=1
  fi
done
CNB_REPO="${CNB_RELEASE_REPO:-lzb-prod/story-note-releases}"

if [ ! -f "$APK_PATH" ]; then
  echo "APK not found: $APK_PATH" >&2
  exit 1
fi

ASSET_NAME="$(basename "$APK_PATH")"
APK_SIZE="$(stat -f%z "$APK_PATH" 2>/dev/null || stat -c%s "$APK_PATH")"
APK_SHA256="$(shasum -a 256 "$APK_PATH" | awk '{print $1}')"
APK_GIT_BLOB="$(git hash-object "$APK_PATH")"

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
git -C "$REPO_DIR" config user.name "Huaben Release Bot"
git -C "$REPO_DIR" config user.email "release@m0m.app"
git -C "$REPO_DIR" config http.postBuffer 524288000

cp "$APK_PATH" "$REPO_DIR/apk/${ASSET_NAME}"
cp "$APK_PATH" "$REPO_DIR/apk/huaben-latest.apk"
cp "$APK_PATH" "$REPO_DIR/apk/StoryNote-latest.apk"

APK_URL="https://cnb.cool/${CNB_REPO}/-/git/raw/main/apk/${ASSET_NAME}"
LATEST_APK_URL="https://cnb.cool/${CNB_REPO}/-/git/raw/main/apk/huaben-latest.apk"
LEGACY_LATEST_URL="https://cnb.cool/${CNB_REPO}/-/git/raw/main/apk/StoryNote-latest.apk"
RELEASE_URL="https://cnb.cool/${CNB_REPO}"

cat >"$REPO_DIR/README.md" <<EOF
# 话本本（Huaben）国内自动更新发布通道

- 当前版本：**v${VERSION}**
- 安装包直链：${APK_URL}
- 最新版固定直链：${LATEST_APK_URL}
- SHA-256：\`${APK_SHA256}\`

本仓库仅存放最新签名 Release APK 与 \`latest.json\` 更新清单，供客户端在国内网络下免登录直连检测更新与一键升级。
EOF

python3 - "$REPO_DIR/latest.json" "$VERSION" "$TAG" "$APK_URL" "$LATEST_APK_URL" "$RELEASE_URL" "$NOTES" "$APK_SIZE" "$APK_SHA256" <<'PY'
import json, sys, datetime
out, ver, tag, apk_url, latest_url, rel_url, notes, size_s, sha256 = sys.argv[1:10]
data = {
    "version": ver,
    "tag_name": tag,
    "apk_url": apk_url,
    "download_url": apk_url,
    "latest_apk_url": latest_url,
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

echo "[1/2] Pushing ${ASSET_NAME} + huaben-latest.apk (+ legacy alias, ${APK_SIZE} bytes deduplicated) to CNB (${CNB_REPO})..."
git -C "$REPO_DIR" add README.md latest.json "apk/${ASSET_NAME}" "apk/huaben-latest.apk" "apk/StoryNote-latest.apk"
git -C "$REPO_DIR" commit -m "release: 话本本 ${TAG}" >/dev/null
git -C "$REPO_DIR" tag -f "$TAG"
GIT_TERMINAL_PROMPT=0 GIT_ASKPASS="$GIT_ASKPASS_SCRIPT" \
  git -C "$REPO_DIR" push --force -u origin main "refs/tags/${TAG}"

echo "[2/2] Fast-verifying manifest + APK headers (ETag == Git blob SHA-1)..."
MANIFEST_URL="https://cnb.cool/${CNB_REPO}/-/git/raw/main/latest.json"
MANIFEST_BODY="$(curl -fsSL "$MANIFEST_URL")"
python3 - "$MANIFEST_BODY" "$VERSION" "$APK_SHA256" <<'PY'
import json, sys
doc = json.loads(sys.argv[1])
assert doc["version"] == sys.argv[2], f"version mismatch: {doc['version']} != {sys.argv[2]}"
assert doc["sha256"] == sys.argv[3], f"sha256 mismatch: {doc['sha256']} != {sys.argv[3]}"
PY

verify_head() {
  local url="$1"
  local headers
  headers="$(curl -fsSLI "$url")"
  local remote_len remote_etag
  remote_len="$(printf '%s\n' "$headers" | awk -F': ' 'tolower($1)=="content-length"{gsub(/\r/,"",$2); print $2}' | tail -n1)"
  remote_etag="$(printf '%s\n' "$headers" | awk -F': ' 'tolower($1)=="etag"{gsub(/[\r"]/,"",$2); print $2}' | tail -n1)"
  if [ "$remote_len" != "$APK_SIZE" ]; then
    echo "Content-Length mismatch for $url: expected=$APK_SIZE got=$remote_len" >&2
    exit 1
  fi
  if [ -n "$remote_etag" ] && [ "$remote_etag" != "$APK_GIT_BLOB" ]; then
    echo "ETag mismatch for $url: expected=$APK_GIT_BLOB got=$remote_etag" >&2
    exit 1
  fi
}

verify_head "$APK_URL"
verify_head "$LATEST_APK_URL"
verify_head "$LEGACY_LATEST_URL"

if [ "$FULL_DOWNLOAD_VERIFY" -eq 1 ]; then
  VERIFY_APK="$TMP_DIR/verify.apk"
  curl -fsSL "$APK_URL" -o "$VERIFY_APK"
  GOT_SHA="$(shasum -a 256 "$VERIFY_APK" | awk '{print $1}')"
  if [ "$GOT_SHA" != "$APK_SHA256" ]; then
    echo "SHA-256 mismatch! expected=$APK_SHA256 got=$GOT_SHA" >&2
    exit 1
  fi
fi

echo "VERIFIED_OK sha256=${APK_SHA256} blob=${APK_GIT_BLOB} manifest=${MANIFEST_URL} apk=${APK_URL}"
