//! Phase 2 review item 4: LwPolyline bulge expansion must preserve the
//! polyline's `elevation` on all intermediate arc points (Z=0 was a bug).
use acadrust::document::CadDocument;
use acadrust::entities::{EntityType, LwPolyline, LwVertex};
use acadrust::io::dxf::{DxfReader, DxfWriter};
use acadrust::types::{Vector2, Vector3};

use cadbridge::scene::Prim;
use cadbridge::scene_emit::build_scene;

const VECTOR_Z_UP: Vector3 = Vector3 { x: 0.0, y: 0.0, z: 1.0 };

fn sample_dir() -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!(
        "bulge_elev_test_{}",
        std::process::id()
    ));
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn read_back(path: &std::path::Path) -> CadDocument {
    let bytes = std::fs::read(path).unwrap();
    DxfReader::from_reader(std::io::Cursor::new(bytes))
        .unwrap()
        .read()
        .unwrap()
}

/// LwPolyline with two bulged segments (positive + negative) at elevation 7.25.
#[test]
fn bulge_intermediate_points_keep_elevation() {
    let dir = sample_dir();
    let path = dir.join("bulge.dxf");

    let pl = LwPolyline {
        vertices: vec![
            LwVertex {
                location: Vector2::new(0.0, 0.0),
                bulge: 0.5, // positive bulge (CCW sweep)
                start_width: 0.0,
                end_width: 0.0,
                vertex_id: 0,
            },
            LwVertex {
                location: Vector2::new(10.0, 0.0),
                bulge: -0.5, // negative bulge (CW sweep)
                start_width: 0.0,
                end_width: 0.0,
                vertex_id: 1,
            },
            LwVertex {
                location: Vector2::new(20.0, 0.0),
                bulge: 0.0,
                start_width: 0.0,
                end_width: 0.0,
                vertex_id: 2,
            },
        ],
        is_closed: false,
        plinegen: false,
        constant_width: 0.0,
        elevation: 7.25,
        thickness: 0.0,
        normal: VECTOR_Z_UP,
        common: Default::default(),
    };

    let mut doc = CadDocument::new();
    doc.add_entity(EntityType::LwPolyline(pl)).expect("add lpoly");
    DxfWriter::new(&doc).write_to_file(&path).expect("write");

    let re = read_back(&path);
    let scene = build_scene(&re);
    let poly = scene
        .prims
        .iter()
        .find_map(|p| match p {
            Prim::Poly { p, .. } => Some(p.clone()),
            _ => None,
        })
        .expect("POLY present");

    // every coordinate triple's Z must equal elevation 7.25 (review item 4:
    // no artificial Z=0 in intermediate points).
    assert!(poly.len() >= 9, "poly too short: {}", poly.len());
    for (i, z) in poly.chunks_exact(3).map(|c| c[2]).enumerate() {
        assert!(
            (z - 7.25).abs() < 1e-9,
            "vertex {i} has Z={z}, expected 7.25 (must not be 0)"
        );
    }
    let _ = std::fs::remove_dir_all(&dir);
}