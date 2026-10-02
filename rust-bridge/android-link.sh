#!/bin/bash
exec /usr/bin/clang --target=aarch64-linux-android21 \
  --sysroot=/opt/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/sysroot \
  -fuse-ld=lld -L/opt/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18/lib/linux/aarch64 -L/opt/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/21 "$@"
