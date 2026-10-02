#!/usr/bin/env bash
# AAPT2 wrapper: the maven aapt2 is x86-64, this host is aarch64 -> run via qemu-x86_64.
set -uo pipefail
for REAL in \
  "${AAPT2_REAL:-}" \
  /root/.gradle/caches/transforms-4/2bba49eee1d64d073cb89b5f3891cf2c/transformed/aapt2-8.5.2-11315950-linux/aapt2-real \
  /opt/android-sdk/build-tools/34.0.0/aapt2 \
  /root/.gradle/caches/transforms-4/2bba49eee1d64d073cb89b5f3891cf2c/transformed/aapt2-8.5.2-11315950-linux/aapt2; do
  [ -n "$REAL" ] && [ -x "$REAL" ] && break
  REAL=""
done
if [ -z "$REAL" ]; then
  echo "aapt2 wrapper: no real aapt2 found" >&2
  exit 127
fi
exec /usr/local/bin/qemu-x86_64 -cpu max "$REAL" "$@"
