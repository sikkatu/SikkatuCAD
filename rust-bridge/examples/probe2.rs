use std::ffi::{CStr, CString};
use cadbridge::{cb_open, cb_close, cb_free_string, cb_probe_dump};
use cadbridge::cb_source_format;
fn take(p: *mut std::os::raw::c_char) -> String {
    if p.is_null() { return "<null>".into(); }
    let s = unsafe { CStr::from_ptr(p).to_string_lossy().into_owned() };
    cb_free_string(p); s
}
fn has_cyr(s: &str) -> bool { s.chars().any(|c| ('\u{0400}'..='\u{04FF}').contains(&c)) }
fn walk(v: &serde_json::Value, path: &str, out: &mut Vec<(String,String)>) {
    match v {
        serde_json::Value::String(s) => { if has_cyr(s) { out.push((path.to_string(), s.clone())); } }
        serde_json::Value::Object(m) => { for (k, vv) in m { walk(vv, &format!("{path}.{k}"), out); } }
        serde_json::Value::Array(a) => { for (i, vv) in a.iter().enumerate() { walk(vv, &format!("{path}[{i}]"), out); } }
        _ => {}
    }
}
fn main() {
    let src = std::env::args().nth(1).unwrap();
    let cs = CString::new(src).unwrap();
    let id: u64 = take(cb_open(cs.as_ptr())).parse().unwrap();
    println!("fmt={}", take(cb_source_format(id)));
    let json = take(cb_probe_dump(id));
    std::fs::write("/tmp/cbtest/dump.json", &json).unwrap();
    println!("dump bytes={}", json.len());
    let v: serde_json::Value = serde_json::from_str(&json).unwrap();
    // count entities by type
    let mut counts = std::collections::BTreeMap::new();
    let mut cyr: Vec<(String,String)> = Vec::new();
    if let serde_json::Value::Array(a) = &v {
        for item in a {
            let t = item["__type"].as_str().unwrap_or("?").to_string();
            *counts.entry(t).or_insert(0usize) += 1;
            walk(item, "", &mut cyr);
        }
    }
    println!("entity counts: {:?}", counts);
    println!("cyrillic string fields: {}", cyr.len());
    for (p, s) in cyr.iter().take(120) { println!("  {p} = {s:?}"); }
    cb_close(id);
}
