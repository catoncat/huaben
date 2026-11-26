#!/usr/bin/env bash
# Publish StoryNote Android APK + latest.json to the public domestic CNB release channel
# (https://cnb.cool/lzb-prod/story-note-releases) without storing or logging any secrets.
#
# Usage:
#   ./scripts/publish-cnb-release.sh v1.1.0 dist/StoryNote-v1.1.0.apk "更新说明"
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: $0 <tag, e.g. v1.1.0> <apk_path> [release_notes]" >&2
  exit 1
fi

# Inject CNB credentials strictly via child-process env (never stdout / argv / disk)
if [ -z "${CNB_API_TOKEN:-}" ]; then
  exec jt resolve jt://secret/SccT56gc --env CNB_API_TOKEN --exec "$0" "$@"
fi
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

echo "[1/4] Syncing $CNB_REPO git repo and pushing tag $TAG..."
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
GIT_TERMINAL_PROMPT=0 GIT_ASKPASS="$GIT_ASKPASS_SCRIPT" \
  git clone "https://cnb.cool/${CNB_REPO}.git" "$REPO_DIR" >/dev/null 2>&1 || {
    mkdir -p "$REPO_DIR"
    git -C "$REPO_DIR" init -b main >/dev/null
    git -C "$REPO_DIR" remote add origin "https://cnb.cool/${CNB_REPO}.git"
  }

git -C "$REPO_DIR" config user.name "StoryNote Release Bot"
git -C "$REPO_DIR" config user.email "release@storynote.local"

if ! git -C "$REPO_DIR" rev-parse --verify HEAD >/dev/null 2>&1; then
  git -C "$REPO_DIR" checkout -B main >/dev/null 2>&1 || true
fi

cat >"$REPO_DIR/README.md" <<'EOF'
# StoryNote（小作家故事本）国内自动更新发布通道

本仓库仅存放安卓端签名 Release APK 与 `latest.json` 更新清单，供客户端在国内网络下免登录直连检测更新与一键升级。
EOF

APK_URL="https://cnb.cool/${CNB_REPO}/-/releases/download/${TAG}/${ASSET_NAME}"
RELEASE_URL="https://cnb.cool/${CNB_REPO}/-/releases/tag/${TAG}"

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

git -C "$REPO_DIR" add README.md latest.json
if ! git -C "$REPO_DIR" diff --cached --quiet; then
  git -C "$REPO_DIR" commit -m "release: publish StoryNote ${TAG}" >/dev/null
fi
git -C "$REPO_DIR" tag -f "$TAG"
GIT_TERMINAL_PROMPT=0 GIT_ASKPASS="$GIT_ASKPASS_SCRIPT" \
  git -C "$REPO_DIR" push -u origin main >/dev/null
GIT_TERMINAL_PROMPT=0 GIT_ASKPASS="$GIT_ASKPASS_SCRIPT" \
  git -C "$REPO_DIR" push -f origin "refs/tags/${TAG}" >/dev/null

echo "[2/4] Creating/fetching CNB release ${TAG}..."
REL_ID="$(python3 - "$CNB_REPO" "$TAG" "$NOTES" <<'PY'
import json, os, sys, urllib.request, urllib.error
repo, tag, notes = sys.argv[1:4]
token = os.environ["CNB_API_TOKEN"]
headers = {
    "Authorization": f"Bearer {token}",
    "Accept": "application/vnd.cnb.api+json",
    "Content-Type": "application/json",
}
try:
    req = urllib.request.Request(f"https://api.cnb.cool/{repo}/-/releases/tags/{tag}", headers=headers)
    with urllib.request.urlopen(req, timeout=15) as r:
        d = json.loads(r.read().decode())
        print(d["id"])
        sys.exit(0)
except urllib.error.HTTPError as e:
    if e.code != 404:
        print(f"GET release failed: HTTP {e.code} {e.read().decode()}", file=sys.stderr)
        sys.exit(1)

payload = json.dumps({
    "tag_name": tag,
    "name": f"StoryNote {tag}",
    "body": notes,
    "draft": False,
    "prerelease": False,
    "make_latest": "true",
}).encode("utf-8")
req = urllib.request.Request(f"https://api.cnb.cool/{repo}/-/releases", data=payload, headers=headers, method="POST")
with urllib.request.urlopen(req, timeout=20) as r:
    d = json.loads(r.read().decode())
    print(d["id"])
PY
)"
echo "  Release ID: ${REL_ID}"

echo "[3/4] Uploading ${ASSET_NAME} (${APK_SIZE} bytes) to CNB release..."
python3 - "$CNB_REPO" "$REL_ID" "$ASSET_NAME" "$APK_SIZE" "$APK_PATH" <<'PY'
import json, os, sys, urllib.request
repo, rel_id, asset_name, size_s, apk_path = sys.argv[1:6]
token = os.environ["CNB_API_TOKEN"]
api_headers = {
    "Authorization": f"Bearer {token}",
    "Accept": "application/vnd.cnb.api+json",
    "Content-Type": "application/json",
}
req_body = json.dumps({
    "asset_name": asset_name,
    "size": int(size_s),
    "overwrite": True,
}).encode("utf-8")
req = urllib.request.Request(
    f"https://api.cnb.cool/{repo}/-/releases/{rel_id}/asset-upload-url",
    data=req_body,
    headers=api_headers,
    method="POST",
)
with urllib.request.urlopen(req, timeout=20) as r:
    up = json.loads(r.read().decode())

upload_url = up["upload_url"]
verify_url = up["verify_url"]

with open(apk_path, "rb") as f:
    apk_bytes = f.read()

put_req = urllib.request.Request(
    upload_url,
    data=apk_bytes,
    headers={"Content-Type": "application/octet-stream", "Content-Length": str(len(apk_bytes))},
    method="PUT",
)
with urllib.request.urlopen(put_req, timeout=180) as r:
    if r.status not in (200, 201, 204):
        raise RuntimeError(f"Asset PUT failed: HTTP {r.status}")

ver_req = urllib.request.Request(
    verify_url,
    data=b"",
    headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.cnb.api+json"},
    method="POST",
)
with urllib.request.urlopen(ver_req, timeout=30) as r:
    if r.status not in (200, 201, 204):
        raise RuntimeError(f"Asset verify failed: HTTP {r.status}")
print("  Upload & confirmation OK")
PY

echo "[4/4] Verifying unauthenticated domestic download..."
MANIFEST_URL="https://cnb.cool/${CNB_REPO}/-/git/raw/main/latest.json"
curl -fsSL "$MANIFEST_URL"
echo "---"
curl -fsSLI "$APK_URL" | head -n 12
echo "Published cleanly! Manifest: $MANIFEST_URL"
