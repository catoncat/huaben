#!/usr/bin/env bash
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 1. Locate JDK 17 (required by Kotlin/Compose Multiplatform & Android Gradle Plugin)
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  for candidate in \
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  do
    if [ -x "${candidate}/bin/java" ]; then
      export JAVA_HOME="${candidate}"
      break
    fi
  done
fi

if { [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; } && [ -x /usr/libexec/java_home ]; then
  if jdk17="$(/usr/libexec/java_home -v 17 2>/dev/null)"; then
    export JAVA_HOME="${jdk17}"
  fi
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  echo "Error: JDK 17 not found. Install openjdk@17 (e.g. brew install openjdk@17) or set JAVA_HOME." >&2
  exit 1
fi

# 2. Populate gitignored local.properties for the Android Gradle plugin in fresh worktrees
if [ ! -f local.properties ]; then
  if [ -n "${SUPERSET_ROOT_PATH:-}" ] && [ -f "${SUPERSET_ROOT_PATH}/local.properties" ]; then
    cp "${SUPERSET_ROOT_PATH}/local.properties" local.properties
  else
    for sdk_candidate in \
      "${ANDROID_HOME:-}" \
      "${ANDROID_SDK_ROOT:-}" \
      "/opt/homebrew/share/android-commandlinetools" \
      "/usr/local/share/android-commandlinetools" \
      "${HOME}/Library/Android/sdk" \
      "${HOME}/Android/Sdk"
    do
      if [ -n "${sdk_candidate}" ] && [ -d "${sdk_candidate}" ]; then
        printf 'sdk.dir=%s\n' "${sdk_candidate}" > local.properties
        break
      fi
    done
  fi
fi

# 3. Resolve Gradle dependencies and warm desktop classes so tests & preview start fast
./gradlew :app:desktopMainClasses --quiet
