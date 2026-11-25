# Development notes

Runtime findings, sizes, and timings recorded while working under the Studio
spec. Newest entries on top.

## 2026-11-25 — Phase 0 (build and baseline)

- CI run 45540012658 (commit 65398df) is green: host `cargo test --release`
  (1 passed), `cargo ndk` cross-build, `./gradlew :app:assembleDebug`.
  The built APK contains `lib/arm64-v8a/libcadbridge.so`
  (5 615 944 bytes) produced in the same run. CI toolchain is
  rustc 1.100.0 (>= 1.99 as required).
- Host `rustc` in the development sandbox is 1.98.1, so `cargo test` was run
  locally with `--ignore-rust-version` (the crate now declares
  `rust-version = "1.99"` per spec). CI toolchain confirmed at 1.100.0.
- `rust-bridge/examples/` no longer compiles against acadrust 0.5.5:
  `probe_star.rs` references `Dimension` fields that were renamed
  (`insertion_point`, `definition_point`, `text_middle_point`). These are
  one-shot development probes from the original project and are deleted in
  phase 1A; autodiscovery of examples is disabled in Cargo.toml
  (`autoexamples = false`) until then, so `cargo test --release` runs the
  lib and integration tests only. `cbtest.rs` / `e2e_translate.rs` compile
  and are migrated to `tests/legacy_text.rs` in phase 1A.
- `libcadbridge.so` committed to git was 5 619 712 bytes (5.6 MB). It is now
  removed from the repository and built by CI via `cargo ndk`.
