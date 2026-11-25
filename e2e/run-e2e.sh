#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

STATIC_ONLY=false
SKIP_STATIC=false
GRADLE_ARGS=()

for arg in "$@"; do
    case "$arg" in
        --static-only)
            STATIC_ONLY=true
            ;;
        --skip-static)
            SKIP_STATIC=true
            ;;
        *)
            GRADLE_ARGS+=("$arg")
            ;;
    esac
done

if [ "$SKIP_STATIC" = false ]; then
    "$ROOT_DIR/e2e/check_static_hygiene.sh"
fi

if [ "$STATIC_ONLY" = true ]; then
    exit 0
fi

cd "$ROOT_DIR"
if [ ${#GRADLE_ARGS[@]} -gt 0 ]; then
    exec ./gradlew :app:e2eTest "${GRADLE_ARGS[@]}"
else
    exec ./gradlew :app:e2eTest
fi
