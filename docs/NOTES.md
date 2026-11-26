# Development notes

Runtime findings, sizes, and timings recorded while working under the Studio
spec. Newest entries on top.

## 2026-11-26 — Phase 2 scene build: mep_histogram on real DWG fixtures

- `cargo test --test mep_histogram -- --nocapture` over the owner-provided
  `rust-bridge/tests/data/mep_dwg/*.dwg` + `roundtrip/*.dwg` (4 + 3 files):
  - `SONSBEEK-BG Water+Gas+CV.dwg`: prims=6505, counts
    {LINE:6093, LWPOLYLINE:84, HATCH:22, INSERT:48, DIMENSION:16, MTEXT:39,
    ARC:40, CIRCLE:33, OLE2FRAME:2}, unsupported
    {OLE2FRAME:2, INSERT:32, ELLIPSE:40} — matches the fixture README's
    entity inventory exactly (Line 6093, LwPolyline 84, Hatch 22, Insert 48,
    Dimension 16, MText 39, Ole2Frame 2).
  - `SONSBEEK-V1 Sparingen.dwg`: prims=478, counts {LINE:176, INSERT:48,
    ARC:40, DIMENSION:16, MTEXT:31, OLE2FRAME:2, HATCH:22, CIRCLE:15} — the
    "линий 176" from the README.
  - `example_2000.dwg` / `example_2018.dwg` (roundtrip suite, R2000/R2018):
    prims=575 each, rich feature mix (SPLINE 2, POLYFACEMESH 1, MLINE 1,
    SOLID 1, DIMENSION 10, ATTDEF 2, RAY, XLINE, TABLE, TOLERANCE…).
    Unsupported yet: `3DSOLID:1, REGION:2` (ACIS/B-rep meshing pending 5.6)
    `WIPEOUT:2, ATTDEF:2, LIGHT:1, RAY:1, XLINE:1, TABLE:1, TOLERANCE:1`.
  - `Point.dwg` (minimal): prims=1, {POINT:1}, no unsupported.
- Open questions carried into later commits of this phase:
  - `ELLIPSE:40` shows up in `unsupported` on the MEP DWGs. `AbstractEllipse`
    should decompose via `EntityType::explode()` per spec 5.1. Not yet
    root-caused — TODO for a Phase-2 follow-up commit. `INSERT:32` in
    `unsupported` likely means nested insert cycle/depth cut triggered on
    this fixture set (documents 48 INSERT, of which 16 dispatched per level).
  - ACIS tessellation for 3DSOLID/REGION is explicitly deferred to 5.6
    (SolidMesher trait + NoMesher), matching the spec.

## 2026-11-25 — Phase 0 (build and baseline)

- CI run #14 (id 45543970022, commit cece20f0, branch studio) is green: host
  `cargo test --release` (1 passed), `cargo ndk` cross-build,
  `./gradlew :app:assembleDebug`. The built APK contains
  `lib/arm64-v8a/libcadbridge.so` (5 615 944 bytes) produced in the same
  run. CI toolchain is rustc 1.100.0 (>= 1.99 as required).
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
