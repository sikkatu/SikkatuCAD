#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER="$SCRIPT_DIR/gradlew"

pick_first_existing_dir() {
  for p in "$@"; do
    if [[ -n "${p:-}" && -d "$p" ]]; then
      echo "$p"
      return 0
    fi
  done
  return 1
}

pick_first_valid_jdk() {
  for p in "$@"; do
    if [[ -n "${p:-}" && -x "$p/bin/java" ]]; then
      echo "$p"
      return 0
    fi
  done
  return 1
}

pick_best_sdk() {
  local fallback=""
  local partial=""
  for p in "$@"; do
    if [[ -z "${p:-}" || ! -d "$p" ]]; then
      continue
    fi
    if [[ -z "$fallback" ]]; then
      fallback="$p"
    fi
    if [[ -d "$p/platforms/android-34" && -d "$p/build-tools/34.0.0" ]]; then
      echo "$p"
      return 0
    fi
    if [[ -z "$partial" && -d "$p/platforms" && -d "$p/build-tools" ]]; then
      partial="$p"
    fi
  done
  if [[ -n "$partial" ]]; then
    echo "$partial"
    return 0
  fi
  if [[ -n "$fallback" ]]; then
    echo "$fallback"
    return 0
  fi
  return 1
}

JAVA_CANDIDATE="$(pick_first_valid_jdk \
  "${JAVA_HOME:-}" \
  "/usr/lib/jvm/java-17-openjdk-arm64" \
  "/usr/lib/jvm/java-17-openjdk" \
  "/cefiles/runtime/jdk17" \
)"
if [[ -z "${JAVA_CANDIDATE:-}" ]]; then
  echo "No usable JDK found. Please set JAVA_HOME." >&2
  exit 1
fi
export JAVA_HOME="$JAVA_CANDIDATE"

SDK_CANDIDATE="$(pick_best_sdk \
  "${ANDROID_SDK_ROOT:-}" \
  "${ANDROID_HOME:-}" \
  "$HOME/Android/Sdk" \
  "/usr/lib/android-sdk" \
  "/opt/android-sdk" \
  "/cefiles/android-sdk" \
  "/cefiles/work-android-sdk" \
)"
if [[ -z "${SDK_CANDIDATE:-}" ]]; then
  echo "No usable Android SDK found. Please set ANDROID_SDK_ROOT/ANDROID_HOME." >&2
  exit 1
fi
export ANDROID_HOME="$SDK_CANDIDATE"
export ANDROID_SDK_ROOT="$SDK_CANDIDATE"

if [[ ! -d "$SDK_CANDIDATE/platforms/android-34" ]]; then
  echo "Missing Android platform: $SDK_CANDIDATE/platforms/android-34" >&2
  echo "Install package: platforms;android-34" >&2
  exit 1
fi

if [[ ! -d "$SDK_CANDIDATE/build-tools/34.0.0" ]]; then
  echo "Missing Android build-tools: $SDK_CANDIDATE/build-tools/34.0.0" >&2
  echo "Install package: build-tools;34.0.0" >&2
  exit 1
fi

if [[ ! -f "$SCRIPT_DIR/local.properties" ]] || ! grep -q "^sdk.dir=$SDK_CANDIDATE$" "$SCRIPT_DIR/local.properties"; then
  printf "sdk.dir=%s\n" "$SDK_CANDIDATE" > "$SCRIPT_DIR/local.properties"
fi

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$SCRIPT_DIR/.gradle-home}"

if [[ ! -f "$WRAPPER" ]]; then
  echo "Missing Gradle wrapper: $WRAPPER" >&2
  exit 1
fi

AAPT2_BIN="$SDK_CANDIDATE/build-tools/34.0.0/aapt2"
EXTRA_GRADLE_ARGS=()

if [[ "$(uname -m)" == "aarch64" && -x "$AAPT2_BIN" ]]; then
  if command -v qemu-x86_64 >/dev/null 2>&1; then
    AAPT2_WRAPPER="/tmp/aapt2"
    cat > "$AAPT2_WRAPPER" <<EOF
#!/usr/bin/env bash
set -euo pipefail
exec qemu-x86_64 -L / "$AAPT2_BIN" "\$@"
EOF
    chmod 755 "$AAPT2_WRAPPER"
    EXTRA_GRADLE_ARGS+=("-Pandroid.aapt2FromMavenOverride=$AAPT2_WRAPPER")
  fi
fi

exec bash "$WRAPPER" --no-daemon "${EXTRA_GRADLE_ARGS[@]}" "$@"
