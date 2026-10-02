// End-to-end translation test: open DWG -> extract -> apply EN/TH edits ->
// save DXF & DWG -> reopen -> verify translated text is present.
use std::ffi::{CStr, CString};
use cadbridge::{cb_open, cb_close, cb_extract_texts, cb_apply_texts, cb_save_dxf, cb_save_dwg, cb_free_string};

fn take(p: *mut std::os::raw::c_char) -> String {
    if p.is_null() { return "<null>".into(); }
    let s = unsafe { CStr::from_ptr(p).to_string_lossy().into_owned() };
    cb_free_string(p);
    s
}
fn c(s: &str) -> CString { CString::new(s).unwrap() }

fn main() {
    let src = std::env::args().nth(1).expect("usage: e2e_translate <file.dwg|dxf>");
    let id: u64 = take(cb_open(c(&src).as_ptr())).parse().expect("open failed");
    let json = take(cb_extract_texts(id));
    let items: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
    println!("extracted {} texts", items.len());

    let en_map = [("декоративные балки", "decorative beams")];
    let th_map = [("декоративные балки", "คานตกแต่ง")];

    let outdir = std::path::Path::new("/tmp/cbtest_e2e");
    std::fs::create_dir_all(outdir).unwrap();
    let base = std::path::Path::new(&src).file_stem().unwrap().to_string_lossy().into_owned();

    for (lang, map) in [("en", &en_map), ("th", &th_map)] {
        let mut edits: Vec<serde_json::Value> = Vec::new();
        for it in &items {
            let text = it["text"].as_str().unwrap_or("");
            let id_s = it["id"].as_str().unwrap_or("");
            let mut new_text = text.to_string();
            let mut changed = false;
            for (ru, tr) in map.iter() {
                if new_text.contains(ru) {
                    new_text = new_text.replace(ru, tr);
                    changed = true;
                }
            }
            if new_text.contains("\\Sо^ ;") {
                let repl = if lang == "en" { "\\So^ ;" } else { "\\Sโอ^ ;" };
                new_text = new_text.replace("\\Sо^ ;", repl);
                changed = true;
            }
            if changed {
                edits.push(serde_json::json!({"id": id_s, "text": new_text}));
            }
        }
        let edits_json = serde_json::to_string(&edits).unwrap();
        let r = take(cb_apply_texts(id, c(&edits_json).as_ptr()));
        println!("[{lang}] apply: {r} (edits prepared: {})", edits.len());

        let dxf_path = outdir.join(format!("{base}_{lang}.dxf"));
        let dwg_path = outdir.join(format!("{base}_{lang}.dwg"));
        println!("[{lang}] save_dxf: {}", take(cb_save_dxf(id, c(&dxf_path.to_string_lossy()).as_ptr())));
        println!("[{lang}] save_dwg: {}", take(cb_save_dwg(id, c(&dwg_path.to_string_lossy()).as_ptr())));

        for f in [&dxf_path, &dwg_path] {
            let id2: u64 = match take(cb_open(c(&f.to_string_lossy()).as_ptr())).parse() {
                Ok(v) => v,
                Err(e) => { println!("[{lang}] reopen {:?}: FAIL {e}", f); continue; }
            };
            let j2 = take(cb_extract_texts(id2));
            let items2: Vec<serde_json::Value> = serde_json::from_str(&j2).unwrap();
            let expected: Vec<&str> = if lang == "en" {
                vec!["decorative beams", "\\So^ ;"]
            } else {
                vec!["คานตกแต่ง", "\\Sโอ^ ;"]
            };
            for exp in &expected {
                let found = items2.iter().any(|t| t["text"].as_str().unwrap_or("").contains(exp));
                println!("[{lang}] reopen {:?}: texts={}, contains {exp:?}: {found}", f, items2.len());
            }
            let leaked = items2.iter().any(|t| t["text"].as_str().unwrap_or("").contains("декоративные балки"));
            println!("[{lang}] reopen {:?}: russian leaked: {leaked} (expect false)", f);
            cb_close(id2);
        }
    }
    cb_close(id);
}
