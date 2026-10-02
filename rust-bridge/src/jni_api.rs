//! JNI layer for the Kotlin `com.sikkatu.sikkatucad.CadBridge` class.
//! Each function mirrors a C-ABI `cb_*` entry: it converts jstring args to
//! Rust, delegates to the same core functions, and returns the result as a
//! jstring (errors are returned inline as "ERR:...").
use std::ffi::{CStr, CString};

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

use crate::{cb_apply_texts, cb_close, cb_extract_texts, cb_free_string, cb_open, cb_probe_dump,
            cb_save_dwg, cb_save_dxf, cb_source_format};

/// Convert a `*mut c_char` returned by the C-ABI layer into a `jstring`,
/// freeing the C string in the process.
fn ret<'local>(env: &mut JNIEnv<'local>, ptr: *mut std::os::raw::c_char) -> jstring {
    if ptr.is_null() {
        return env.new_string("").map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut());
    }
    let s = unsafe { CStr::from_ptr(ptr) }.to_string_lossy().into_owned();
    cb_free_string(ptr);
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn jstr_to_c(env: &mut JNIEnv, s: &JString) -> CString {
    let raw: String = env.get_string(s).map(|r| r.into()).unwrap_or_default();
    CString::new(raw).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_com_sikkatu_sikkatucad_CadBridge_cbOpen<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    let c = jstr_to_c(&mut env, &path);
    let ptr = cb_open(c.as_ptr());
    ret(&mut env, ptr)
}

#[no_mangle]
pub extern "system" fn Java_com_sikkatu_sikkatucad_CadBridge_cbClose(
    _env: JNIEnv,
    _class: JClass,
    session_id: jni::sys::jlong,
) {
    cb_close(session_id as u64);
}

#[no_mangle]
pub extern "system" fn Java_com_sikkatu_sikkatucad_CadBridge_cbSourceFormat<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: jni::sys::jlong,
) -> jstring {
    let ptr = cb_source_format(session_id as u64);
    ret(&mut env, ptr)
}

#[no_mangle]
pub extern "system" fn Java_com_sikkatu_sikkatucad_CadBridge_cbExtractTexts<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: jni::sys::jlong,
) -> jstring {
    let ptr = cb_extract_texts(session_id as u64);
    ret(&mut env, ptr)
}

#[no_mangle]
pub extern "system" fn Java_com_sikkatu_sikkatucad_CadBridge_cbApplyTexts<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: jni::sys::jlong,
    json: JString<'local>,
) -> jstring {
    let c = jstr_to_c(&mut env, &json);
    let ptr = cb_apply_texts(session_id as u64, c.as_ptr());
    ret(&mut env, ptr)
}

#[no_mangle]
pub extern "system" fn Java_com_sikkatu_sikkatucad_CadBridge_cbSaveDxf<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: jni::sys::jlong,
    path: JString<'local>,
) -> jstring {
    let c = jstr_to_c(&mut env, &path);
    let ptr = cb_save_dxf(session_id as u64, c.as_ptr());
    ret(&mut env, ptr)
}

#[no_mangle]
pub extern "system" fn Java_com_sikkatu_sikkatucad_CadBridge_cbSaveDwg<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: jni::sys::jlong,
    path: JString<'local>,
) -> jstring {
    let c = jstr_to_c(&mut env, &path);
    let ptr = cb_save_dwg(session_id as u64, c.as_ptr());
    ret(&mut env, ptr)
}

#[no_mangle]
pub extern "system" fn Java_com_sikkatu_sikkatucad_CadBridge_cbProbeDump<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: jni::sys::jlong,
) -> jstring {
    let ptr = cb_probe_dump(session_id as u64);
    ret(&mut env, ptr)
}