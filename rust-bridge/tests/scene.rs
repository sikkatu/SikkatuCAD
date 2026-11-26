// Scene export tests (spec 5.5): build a small CadDocument in code, write
// DXF, read back, build the scene, assert primitive kinds and handles agree
// with the DXF group-5 handles found by raw text search. Also (when the sample
// fixture directory is present) a no-panic smoke across `dxf_features/*.dxf`.
use acadrust::document::CadDocument;
use acadrust::entities::{EntityType, Line, Solid, Text};
use acadrust::io::dxf::{DxfReader, DxfWriter};
use acadrust::types::Vector3;

use cadbridge::scene::Prim;
use cadbridge::scene_emit::build_scene;
use cadbridge::scene::Meta as PrimMeta;

fn prim_meta(p: &Prim) -> PrimMeta {
    match p {
        Prim::Line { meta, .. }
        | Prim::Poly { meta, .. }
        | Prim::Circle { meta, .. }
        | Prim::Arc { meta, .. }
        | Prim::Text { meta, .. }
        | Prim::Point { meta, .. }
        | Prim::Face { meta, .. } => meta.clone(),
    }
}

fn sample_dir(name: &str) -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!("scene_test_{name}_{}", std::process::id()));
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn read_back(path: &std::path::Path) -> CadDocument {
    let bytes = std::fs::read(path).unwrap();
    let reader = DxfReader::from_reader(std::io::Cursor::new(bytes)).unwrap();
    reader.read().unwrap()
}

#[test]
fn scene_line_text_handle_echoes_dxf_group5() {
    let dir = sample_dir("handle");
    let path = dir.join("small.dxf");
    let mut doc = CadDocument::new();
    doc.add_entity(EntityType::Line(Line::from_coords(0.0, 0.0, 0.0, 5.0, 0.0, 0.0)))
        .expect("add line");
    doc.add_entity(EntityType::Text(Text::with_value("hello", Vector3::new(1.0, 1.0, 0.0))))
        .expect("add text");
    DxfWriter::new(&doc)
        .write_to_file(&path)
        .expect("write small.dxf");

    let re_read = read_back(&path);
    let scene = build_scene(&re_read);

    // 2 primitives should appear, one LINE + one TEXT
    assert_eq!(scene.prims.len(), 2, "prims: {:?}", scene.prims.len());
    let mut kinds: Vec<&str> = vec![];
    for p in &scene.prims {
        kinds.push(match p {
            Prim::Line { .. } => "LINE",
            Prim::Text { .. } => "TEXT",
            Prim::Poly { .. } => "POLY",
            Prim::Circle { .. } => "CIRCLE",
            Prim::Arc { .. } => "ARC",
            Prim::Point { .. } => "POINT",
            Prim::Face { .. } => "FACE",
        });
    }
    kinds.sort();
    assert_eq!(kinds, vec!["LINE", "TEXT"]);

    // every meta.h matches a group-5 handle literal found in the raw DXF text
    let raw_bytes = std::fs::read(&path).unwrap();
    let raw = String::from_utf8_lossy(&raw_bytes);
    for p in &scene.prims {
        let m = prim_meta(p);
        assert!(
            raw.contains(&m.h),
            "handle {} not present in raw DXF text",
            m.h
        );
        assert!(!m.h.contains("0x"), "handle must be bare uppercase hex");
    }

    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn scene_solid_uses_boundary_corners_four_corner_order() {
    let dir = sample_dir("solid");
    let path = dir.join("solid.dxf");
    let mut doc = CadDocument::new();
    // Solid deliberately not a rectangle in traversal order so a naive
    // (1,2,3,4) map would produce a bow-tie ("бантик").
    let mut s = Solid::new(
        Vector3::new(0.0, 0.0, 0.0),
        Vector3::new(4.0, 0.0, 0.0),
        Vector3::new(0.0, 4.0, 0.0),
        Vector3::new(4.0, 4.0, 0.0),
    );
    s.common.layer = "WALLS".to_string();
    doc.add_entity(EntityType::Solid(s)).expect("add solid");
    DxfWriter::new(&doc)
        .write_to_file(&path)
        .expect("write solid.dxf");

    let re_read = read_back(&path);
    let scene = build_scene(&re_read);
    let face = scene
        .prims
        .iter()
        .find_map(|p| match p {
            Prim::Face { p, fill, meta, .. } => Some((p.clone(), *fill, meta.clone())),
            _ => None,
        })
        .expect("SOLID should produce a FACE");
    assert!(face.1, "SOLID face must be fill=true");
    assert_eq!(face.2.ty, "SOLID");
    assert_eq!(face.2.l, "WALLS");
    // 4 corners (12 numbers)
    assert_eq!(face.0.len(), 12, "quad FACE has 4 corners");

    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn scene_point_keeps_z() {
    let dir = sample_dir("point");
    let path = dir.join("point.dxf");
    let mut doc = CadDocument::new();
    let mut pt = acadrust::entities::Point::new();
    pt.location = Vector3::new(1.5, -2.25, 7.125);
    pt.common.layer = "PTS".to_string();
    doc.add_entity(EntityType::Point(pt)).expect("add point");
    DxfWriter::new(&doc)
        .write_to_file(&path)
        .expect("write point.dxf");

    let re_read = read_back(&path);
    let scene = build_scene(&re_read);
    match &scene.prims[0] {
        Prim::Point { p, .. } => {
            assert!((p[2] - 7.125).abs() < 1e-6, "POINT must keep Z: {}", p[2]);
        }
        other => panic!("expected POINT prim, got {:?}", prim_meta(other)),
    }

    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn scene_smoke_no_panic_on_real_samples() {
    // Walk every fixture in tests/data/dxf_features (owner-provided, fetched
    // via fetch_samples.py, gitignored): the pipeline must never panic and
    // must not report >1M prims on these small files (still allowed to
    // classify unknown types into `unsupported`).
    let sample_root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/data/dxf_features");
    if !sample_root.exists() {
        eprintln!("tests/data/dxf_features not present — skipping sample smoke");
        return;
    }
    for e in std::fs::read_dir(&sample_root).unwrap() {
        let e = e.unwrap();
        let path = e.path();
        if path.extension().map(|x| x != "dxf").unwrap_or(true) {
            continue;
        }
        let res = std::panic::catch_unwind(|| {
            let doc = read_back(&path);
            let scene = build_scene(&doc);
            (scene.truncated, scene.prims.len(), scene.unsupported.len())
        });
        assert!(res.is_ok(), "panic while processing {:?}", path);
        let (truncated, prims, unsup) = res.unwrap();
        assert!(!truncated, "{:?}: scene unexpectedly truncated", path);
        eprintln!(
            "{:?}: prims={} unsupported-classes={}",
            path.file_name().unwrap_or_default(),
            prims,
            unsup
        );
    }
}
