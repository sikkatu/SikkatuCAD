//! C-ABI bridge between Kotlin (Android) and the acadrust CAD library.
//! Session-based: open once, extract texts, apply edits, save as DXF or DWG.
mod jni_api;
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Mutex;

use acadrust::document::CadDocument;
use acadrust::entities::EntityType;
use acadrust::types::Vector3;
use acadrust::io::dwg::{DwgReader, DwgReadOptions, DwgWriter};
use acadrust::io::dxf::{DxfReader, DxfWriter};

struct Session {
    doc: CadDocument,
    source_format: &'static str, // "dwg" | "dxf"
}

static SESSIONS: Mutex<Option<HashMap<u64, Session>>> = Mutex::new(None);
static NEXT_ID: Mutex<u64> = Mutex::new(1);

fn with_sessions<F, R>(f: F) -> R
where
    F: FnOnce(&mut HashMap<u64, Session>) -> R,
{
    let mut guard = SESSIONS.lock().unwrap();
    if guard.is_none() {
        *guard = Some(HashMap::new());
    }
    f(guard.as_mut().unwrap())
}

fn cstr_to_string(p: *const c_char) -> Result<String, String> {
    if p.is_null() {
        return Err("null path".into());
    }
    unsafe { CStr::from_ptr(p) }
        .to_str()
        .map(|s| s.to_string())
        .map_err(|e| e.to_string())
}

fn make_err(msg: &str) -> *mut c_char {
    CString::new(format!("ERR:{msg}")).unwrap().into_raw()
}

/// Release a string previously returned by the bridge.
#[no_mangle]
pub extern "C" fn cb_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe { drop(CString::from_raw(ptr)); }
    }
}

/// Open a CAD file. `path` is a UTF-8 filesystem path.
/// Returns session id (>0) as decimal string, or "ERR:...".
#[no_mangle]
pub extern "C" fn cb_open(path: *const c_char) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<String, String> {
        let path = cstr_to_string(path)?;
        let bytes = std::fs::read(&path).map_err(|e| format!("read: {e}"))?;
        let is_dwg = bytes.len() >= 6 && &bytes[..2] == b"AC";
        let doc = if is_dwg {
            let cursor = std::io::Cursor::new(bytes.as_slice());
            let mut reader = DwgReader::from_stream_with_options(cursor, DwgReadOptions::default());
            match reader.read() {
                Ok(d) => d,
                Err(_) => {
                    let cursor = std::io::Cursor::new(bytes.as_slice());
                    let mut reader = DwgReader::from_stream_with_options(cursor, DwgReadOptions::failsafe());
                    reader.read().map_err(|e| format!("dwg: {e}"))?
                }
            }
        } else {
            let cursor = std::io::Cursor::new(bytes.clone());
            DxfReader::from_reader(cursor)
                .map_err(|e| format!("dxf open: {e}"))?
                .read()
                .map_err(|e| format!("dxf: {e}"))?
        };
        let source_format = if is_dwg { "dwg" } else { "dxf" };
        let id = {
            let mut next = NEXT_ID.lock().unwrap();
            let id = *next;
            *next += 1;
            id
        };
        with_sessions(|m| {
            m.insert(id, Session { doc, source_format });
        });
        Ok(id.to_string())
    }));
    match result {
        Ok(Ok(id)) => CString::new(id).unwrap().into_raw(),
        Ok(Err(msg)) => make_err(&msg),
        Err(_) => make_err("panic in cb_open"),
    }
}

#[no_mangle]
pub extern "C" fn cb_close(session_id: u64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        with_sessions(|m| {
            m.remove(&session_id);
        });
    }));
}

#[no_mangle]
pub extern "C" fn cb_source_format(session_id: u64) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<String, String> {
        with_sessions(|m| {
            m.get(&session_id)
                .map(|s| s.source_format.to_string())
                .ok_or_else(|| "no session".to_string())
        })
    }));
    match result {
        Ok(Ok(s)) => CString::new(s).unwrap().into_raw(),
        Ok(Err(msg)) => make_err(&msg),
        Err(_) => make_err("panic"),
    }
}

/// Extract all text-bearing entities as a JSON array:
/// [{"id":"<handle>","type":"TEXT|MTEXT|ATTRIB|ATTDEF","text":"...","layer":"...","block":"..."}]
#[no_mangle]
pub extern "C" fn cb_extract_texts(session_id: u64) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<String, String> {
        with_sessions(|m| {
            let sess = m.get(&session_id).ok_or_else(|| "no session".to_string())?;
            let mut out: Vec<serde_json::Value> = Vec::new();
            for entity in sess.doc.entities() {
                let (kind, text) = match entity {
                    EntityType::Text(t) => ("TEXT", Some(t.value.clone())),
                    EntityType::MText(t) => ("MTEXT", Some(t.value.clone())),
                    EntityType::AttributeEntity(a) => ("ATTRIB", Some(a.value.clone())),
                    EntityType::AttributeDefinition(a) => ("ATTDEF", Some(a.default_value.clone())),
                    _ => continue,
                };
                let text = match text {
                    Some(t) if !t.is_empty() => t,
                    _ => continue,
                };
                let handle = entity.common().handle;
                let layer = entity.common().layer.clone();
                out.push(serde_json::json!({
                    "id": format!("{}", handle),
                    "type": kind,
                    "text": text,
                    "layer": layer,
                }));
            }
            serde_json::to_string(&out).map_err(|e| e.to_string())
        })
    }));
    match result {
        Ok(Ok(json)) => CString::new(json).unwrap().into_raw(),
        Ok(Err(msg)) => make_err(&msg),
        Err(_) => make_err("panic in cb_extract_texts"),
    }
}

/// Apply text edits from a JSON array: [{"id":"<handle>","text":"..."}, ...]
/// Returns "OK:<n>" where n = number of entities actually modified.
#[no_mangle]
pub extern "C" fn cb_apply_texts(session_id: u64, json: *const c_char) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<String, String> {
        let json = cstr_to_string(json)?;
        let edits: Vec<serde_json::Value> =
            serde_json::from_str(&json).map_err(|e| format!("json: {e}"))?;
        // map handle-string -> new text
        let mut map: HashMap<String, String> = HashMap::new();
        for e in edits {
            let id = e.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let text = e.get("text").and_then(|v| v.as_str()).unwrap_or("").to_string();
            if !id.is_empty() {
                map.insert(id, text);
            }
        }
        with_sessions(|m| {
            let sess = m.get_mut(&session_id).ok_or_else(|| "no session".to_string())?;
            let mut changed = 0usize;
            for entity in sess.doc.entities_mut() {
                let handle_key = format!("{}", entity.common().handle);
                let Some(new_text) = map.get(&handle_key) else { continue };
                match entity {
                    EntityType::Text(t) => {
                        if t.value != *new_text {
                            t.value = new_text.clone();
                            changed += 1;
                        }
                    }
                    EntityType::MText(t) => {
                        if t.value != *new_text {
                            t.value = new_text.clone();
                            changed += 1;
                        }
                    }
                    EntityType::AttributeEntity(a) => {
                        if a.value != *new_text {
                            a.value = new_text.clone();
                            changed += 1;
                        }
                    }
                    EntityType::AttributeDefinition(a) => {
                        if a.default_value != *new_text {
                            a.default_value = new_text.clone();
                            changed += 1;
                        }
                    }
                    _ => {}
                }
            }
            Ok(format!("OK:{changed}"))
        })
    }));
    match result {
        Ok(Ok(s)) => CString::new(s).unwrap().into_raw(),
        Ok(Err(msg)) => make_err(&msg),
        Err(_) => make_err("panic in cb_apply_texts"),
    }
}

/// Save the document as DXF. Returns "OK" or "ERR:...".
#[no_mangle]
pub extern "C" fn cb_save_dxf(session_id: u64, path: *const c_char) -> *mut c_char {
    save_impl(session_id, path, false)
}

/// Save the document as DWG (experimental). Returns "OK" or "ERR:...".
#[no_mangle]
pub extern "C" fn cb_save_dwg(session_id: u64, path: *const c_char) -> *mut c_char {
    save_impl(session_id, path, true)
}

/// Нормализация блоков: содержимое блока сдвигается на -base_point, base_point -> (0,0,0).
/// В исходных DWG (экспорт Archicad) содержимое блоков хранится в мировых координатах,
/// а DXF-писатель acadrust в любом случае пишет базовую точку как (0,0) — поэтому без
/// нормализации в сохранённых файлах геометрия блоков сдвигается. После нормализации
/// формула "insert + R*S*(p - base)" даёт корректные мировые координаты для любого
/// просмотрщика (и для парсера приложения, и для AutoCAD).
fn normalize_blocks(doc: &mut CadDocument) {
    let mut jobs: Vec<(Vec<u64>, Vector3)> = Vec::new();
    for br in doc.block_records.iter() {
        if br.name.starts_with("*Model_Space") || br.name.starts_with("*Paper_Space") {
            continue;
        }
        let base = br.base_point;
        if base.x.abs() < 1e-9 && base.y.abs() < 1e-9 && base.z.abs() < 1e-9 {
            continue;
        }
        jobs.push((br.entity_handles.iter().map(|h| h.value()).collect(), base));
    }
    if jobs.is_empty() {
        return;
    }
    for br in doc.block_records.iter_mut() {
        if br.name.starts_with("*Model_Space") || br.name.starts_with("*Paper_Space") {
            continue;
        }
        br.base_point = Vector3::ZERO;
    }
    for (handles, base) in jobs {
        let offset = Vector3::new(-base.x, -base.y, -base.z);
        for raw in handles {
            let h = acadrust::types::Handle::from(raw);
            if let Some(e) = doc.get_entity_mut(h) {
                e.as_entity_mut().translate(offset);
            }
        }
    }
}
fn save_impl(session_id: u64, path: *const c_char, as_dwg: bool) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<String, String> {
        let path = cstr_to_string(path)?;
        with_sessions(|m| {
            let sess = m.get_mut(&session_id).ok_or_else(|| "no session".to_string())?;
            normalize_blocks(&mut sess.doc);
            if as_dwg {
                // Keep the source DWG version so raw blobs stay compatible.
                let version = sess.doc.version;
                sess.doc.dwg_source_version = Some(version);
                DwgWriter::write_to_file(&path, &sess.doc)
                    .map_err(|e| format!("dwg write: {e}"))?;
            } else {
                DxfWriter::new(&sess.doc)
                    .write_to_file(&path)
                    .map_err(|e| format!("dxf write: {e}"))?;
            }
            Ok("OK".to_string())
        })
    }));
    match result {
        Ok(Ok(s)) => CString::new(s).unwrap().into_raw(),
        Ok(Err(msg)) => make_err(&msg),
        Err(_) => make_err("panic in save"),
    }
}

/// DEBUG: dump all entities of a session as a JSON array (serde).
#[no_mangle]
pub extern "C" fn cb_probe_dump(session_id: u64) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<String, String> {
        with_sessions(|m| {
            let sess = m.get(&session_id).ok_or_else(|| "no session".to_string())?;
            let items: Vec<serde_json::Value> = sess
                .doc
                .entities()
                .map(|e| {
                    let mut v = serde_json::to_value(e).unwrap_or(serde_json::Value::Null);
                    if let serde_json::Value::Object(ref mut m) = v {
                        let tag = format!("{:?}", std::mem::discriminant(e));
                        m.insert("__type".into(), serde_json::Value::String(type_name_of(e)));
                        let _ = tag;
                    }
                    v
                })
                .collect();
            serde_json::to_string(&items).map_err(|e| e.to_string())
        })
    }));
    match result {
        Ok(Ok(json)) => CString::new(json).unwrap().into_raw(),
        Ok(Err(msg)) => make_err(&msg),
        Err(_) => make_err("panic in cb_probe_dump"),
    }
}
fn type_name_of(e: &EntityType) -> String {
    match e {
        EntityType::Text(_) => "TEXT".into(),
        EntityType::MText(_) => "MTEXT".into(),
        EntityType::AttributeEntity(_) => "ATTRIB".into(),
        EntityType::AttributeDefinition(_) => "ATTDEF".into(),
        EntityType::Insert(_) => "INSERT".into(),
        EntityType::Line(_) => "LINE".into(),
        EntityType::Circle(_) => "CIRCLE".into(),
        EntityType::Arc(_) => "ARC".into(),
        _ => "OTHER".into(),
    }
}
