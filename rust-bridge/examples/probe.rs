use std::ffi::{CStr, CString};
use cadbridge::{cb_open, cb_close, cb_extract_texts, cb_free_string};
fn take(p: *mut std::os::raw::c_char) -> String {
    if p.is_null() { return "<null>".into(); }
    let s = unsafe { CStr::from_ptr(p).to_string_lossy().into_owned() };
    cb_free_string(p); s
}
fn main() {
    let src = std::env::args().nth(1).unwrap();
    let cs = CString::new(src).unwrap();
    let id: u64 = take(cb_open(cs.as_ptr())).parse().unwrap();
    let json = take(cb_extract_texts(id));
    let items: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
    // type distribution
    let mut by_type = std::collections::BTreeMap::new();
    for it in &items {
        *by_type.entry(it["type"].as_str().unwrap().to_string()).or_insert(0usize) += 1;
    }
    println!("total={} by_type={:?}", items.len(), by_type);
    // strings containing Cyrillic
    let cyr = items.iter().filter(|it| {
        it["text"].as_str().map(|t| t.chars().any(|c| ('\u{0400}'..='\u{04FF}').contains(&c))).unwrap_or(false)
    }).count();
    println!("texts with Cyrillic: {}", cyr);
    for it in items.iter().filter(|it| {
        it["text"].as_str().map(|t| t.chars().any(|c| ('\u{0400}'..='\u{04FF}').contains(&c))).unwrap_or(false)
    }) {
        println!("  [{}] {} layer={} text={:?}", it["id"].as_str().unwrap_or("?"), it["type"].as_str().unwrap_or("?"), it["layer"].as_str().unwrap_or("?"), it["text"].as_str().unwrap_or(""));
    }
    cb_close(id);
}
