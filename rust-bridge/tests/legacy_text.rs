// Legacy end-to-end checks migrated from examples/cbtest.rs and
// examples/e2e_translate.rs (deleted in phase 1A per spec 4A.1):
// open -> extract texts -> apply translation edits -> save DXF & DWG ->
// reopen -> verify translated text is present and the original is gone.
// Builds the sample DXF in code so the test runs on CI without external
// sample files; integration touch-points with real DWGs land in a later
// phase (spec 5.4 / tests/data fetch_samples).
use std::ffi::{CStr, CString};

use acadrust::document::CadDocument;
use acadrust::entities::{EntityType, Line, Text};
use acadrust::io::dxf::DxfWriter;
use acadrust::types::Vector3;

use cadbridge::{
    cb_apply_texts, cb_close, cb_extract_texts, cb_free_string, cb_open, cb_save_dxf,
    cb_source_format,
};

fn take(p: *mut std::os::raw::c_char) -> String {
    if p.is_null() {
        return "<null>".into();
    }
    let s = unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned();
    cb_free_string(p);
    s
}

fn c(s: &str) -> CString {
    CString::new(s).unwrap()
}

fn sample_dir() -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("cadbridge_legacy_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn write_sample_dxf(dir: &std::path::Path) -> std::path::PathBuf {
    let mut doc = CadDocument::new();
    doc.add_entity(EntityType::Line(Line::from_coords(0.0, 0.0, 0.0, 10.0, 0.0, 0.0)))
        .expect("add line");
    let mut t = Text::with_value("декоративные балки", Vector3::new(1.0, 2.0, 0.0));
    t.height = 2.5;
    doc.add_entity(EntityType::Text(t)).expect("add text");
    doc.add_entity(EntityType::Text(Text::with_value(
        "second text",
        Vector3::new(5.0, 5.0, 0.0),
    )))
    .expect("add text 2");
    let path = dir.join("legacy.dxf");
    DxfWriter::new(&doc)
        .write_to_file(&path)
        .expect("write sample dxf");
    path
}

#[test]
fn legacy_text_edit_roundtrip() {
    let dir = sample_dir();
    let sample = write_sample_dxf(&dir);

    // open
    let open_raw = take(cb_open(c(sample.to_str().unwrap()).as_ptr()));
    assert!(!open_raw.starts_with("ERR:"), "cb_open failed: {open_raw}");
    let id: u64 = open_raw.parse().unwrap();

    // source format check
    let fmt = take(cb_source_format(id));
    assert_eq!(fmt, "dxf");

    // extract texts
    let json = take(cb_extract_texts(id));
    assert!(!json.starts_with("ERR:"), "cb_extract_texts failed: {json}");
    let items: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
    assert_eq!(items.len(), 2);
    let target = items
        .iter()
        .find(|v| v["text"].as_str() == Some("декоративные балки"))
        .expect("text present before edit");
    let handle = target["id"].as_str().unwrap().to_string();

    // apply translation edit (RU -> EN)
    let edits = serde_json::json!([{ "id": handle, "text": "decorative beams" }]);
    let r = take(cb_apply_texts(id, c(&edits.to_string()).as_ptr()));
    assert!(r.starts_with("OK:1"), "cb_apply_texts: {r}");

    // save DXF
    let out = dir.join("legacy_en.dxf");
    let saved = take(cb_save_dxf(id, c(out.to_str().unwrap()).as_ptr()));
    assert_eq!(saved, "OK", "cb_save_dxf: {saved}");
    cb_close(id);

    // reopen and verify the translation persisted
    let open_raw = take(cb_open(c(out.to_str().unwrap()).as_ptr()));
    assert!(!open_raw.starts_with("ERR:"), "reopen failed: {open_raw}");
    let id2: u64 = open_raw.parse().unwrap();
    let json2 = take(cb_extract_texts(id2));
    let items2: Vec<serde_json::Value> = serde_json::from_str(&json2).unwrap();
    cb_close(id2);

    assert_eq!(items2.len(), 2);
    let texts: Vec<&str> = items2
        .iter()
        .map(|v| v["text"].as_str().unwrap_or(""))
        .collect();
    assert!(
        texts.contains(&"decorative beams"),
        "translated text persisted: {texts:?}"
    );
    assert!(
        !texts.contains(&"декоративные балки"),
        "original text gone: {texts:?}"
    );
    assert!(texts.contains(&"second text"), "other text unchanged: {texts:?}");

    let _ = std::fs::remove_dir_all(&dir);
}