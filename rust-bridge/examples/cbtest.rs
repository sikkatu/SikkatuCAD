// Host-side end-to-end test of the C-ABI bridge against a real DWG/DXF.
use std::ffi::{CStr, CString};
use cadbridge::{cb_open, cb_close, cb_source_format, cb_extract_texts, cb_save_dxf, cb_save_dwg, cb_free_string};

fn take(p: *mut std::os::raw::c_char) -> String {
    if p.is_null() { return "<null>".into(); }
    let s = unsafe { CStr::from_ptr(p).to_string_lossy().into_owned() };
    cb_free_string(p);
    s
}

fn main() {
    let src = std::env::args().nth(1).expect("usage: cbtest <file.dwg|dxf>");
    let cs = CString::new(src.clone()).unwrap();
    let id_str = take(cb_open(cs.as_ptr()));
    println!("open: {id_str}");
    if id_str.starts_with("ERR:") { std::process::exit(1); }
    let id: u64 = id_str.parse().unwrap();

    println!("source_format: {}", take(cb_source_format(id)));

    let json = take(cb_extract_texts(id));
    if json.starts_with("ERR:") { println!("{json}"); std::process::exit(1); }
    let items: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
    println!("texts found: {}", items.len());
    for it in items.iter().take(300) {
        println!("  [{}] {} layer={} text={:?}",
            it["id"].as_str().unwrap_or("?"),
            it["type"].as_str().unwrap_or("?"),
            it["layer"].as_str().unwrap_or("?"),
            it["text"].as_str().unwrap_or(""));
    }

    let base = std::path::Path::new(&src).file_stem().unwrap().to_string_lossy().into_owned();
    let outdir = std::path::Path::new("/tmp/cbtest");
    std::fs::create_dir_all(outdir).unwrap();
    let dxf_path = outdir.join(format!("{base}_rt.dxf"));
    let dwg_path = outdir.join(format!("{base}_rt.dwg"));

    let p = CString::new(dxf_path.to_string_lossy().into_owned()).unwrap();
    println!("save_dxf: {}", take(cb_save_dxf(id, p.as_ptr())));
    let p = CString::new(dwg_path.to_string_lossy().into_owned()).unwrap();
    println!("save_dwg: {}", take(cb_save_dwg(id, p.as_ptr())));

    for f in [&dxf_path, &dwg_path] {
        let cs = CString::new(f.to_string_lossy().into_owned()).unwrap();
        let id2s = take(cb_open(cs.as_ptr()));
        if id2s.starts_with("ERR:") {
            println!("reopen {:?}: {}", f, id2s);
            continue;
        }
        let id2: u64 = id2s.parse().unwrap();
        let j2 = take(cb_extract_texts(id2));
        let n2: usize = serde_json::from_str::<Vec<serde_json::Value>>(&j2).map(|v| v.len()).unwrap_or(0);
        println!("reopen {:?}: OK, texts={}", f, n2);
        cb_close(id2);
    }
    cb_close(id);
}
