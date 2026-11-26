//! Scene builder: turns an acadrust CadDocument into flat render primitives
//! (spec section 5.1). Coordinates are WCS (OCS->WCS by arbitrary-axis
//! algorithm). No JNI/Android here — shared by JVM, HTTP API and CLI.
use acadrust::types::Vector3;
use serde_json::{json, Value};
use std::collections::HashMap;

/// Common metadata carried by every primitive (spec 5.1): handle (DXF code 5,
/// uppercase hex without 0x; INSERT descendants get "<insert>/<inner>"),
/// layer, resolved color (0xRRGGBB int), GUID from XDATA SKT, source type.
#[derive(Debug, Clone)]
pub struct Meta {
    pub h: String,
    pub l: String,
    pub col: u32,
    pub g: Option<String>,
    pub ty: String,
}

#[derive(Debug, Clone)]
pub enum Prim {
    Line { p: [f64; 6], meta: Meta },
    Poly { p: Vec<f64>, closed: bool, meta: Meta },
    Circle { c: [f64; 3], r: f64, meta: Meta },
    Arc { c: [f64; 3], r: f64, a0: f64, a1: f64, meta: Meta },
    Text { p: [f64; 3], s: String, h: f64, rot: f64, meta: Meta },
    Point { p: [f64; 3], meta: Meta },
    Face { p: Vec<f64>, fill: bool, inv: u32, meta: Meta },
}

fn meta_json(m: &Meta) -> Value {
    json!({"h": m.h, "l": m.l, "col": m.col, "g": m.g, "ty": m.ty})
}

fn merge(mut v: Value, m: &Meta) -> Value {
    if let Some(obj) = v.as_object_mut() {
        if let Some(meta) = meta_json(m).as_object() {
            obj.extend(meta.clone());
        }
    }
    v
}

impl Prim {
    pub fn to_json(&self) -> Value {
        match self {
            Prim::Line { p, meta } => merge(json!({"k":"LINE","p":p.to_vec()}), meta),
            Prim::Poly { p, closed, meta } => merge(json!({"k":"POLY","p":p,"closed":closed}), meta),
            Prim::Circle { c, r, meta } => merge(json!({"k":"CIRCLE","c":c.to_vec(),"r":r}), meta),
            Prim::Arc { c, r, a0, a1, meta } => {
                merge(json!({"k":"ARC","c":c.to_vec(),"r":r,"a0":a0,"a1":a1}), meta)
            }
            Prim::Text { p, s, h, rot, meta } => {
                merge(json!({"k":"TEXT","p":p.to_vec(),"s":s,"h":h,"rot":rot}), meta)
            }
            Prim::Point { p, meta } => merge(json!({"k":"POINT","p":p.to_vec()}), meta),
            Prim::Face { p, fill, inv, meta } => {
                merge(json!({"k":"FACE","p":p,"fill":fill,"inv":inv}), meta)
            }
        }
    }
}

#[derive(Debug, Clone)]
pub struct LayerInfo {
    pub name: String,
    pub color: u32,
    pub ltype: String,
    pub off: bool,
    pub frozen: bool,
}

impl LayerInfo {
    pub fn to_json(&self) -> Value {
        json!({
            "name": self.name,
            "color": self.color,
            "type": self.ltype,
            "off": self.off,
            "frozen": self.frozen,
        })
    }
}

#[derive(Debug, Clone)]
pub struct Scene {
    pub prims: Vec<Prim>,
    pub layers: Vec<LayerInfo>,
    pub counts: HashMap<String, usize>,
    pub unsupported: HashMap<String, usize>,
    pub units: Option<String>,
    pub bounds: [f64; 6],
    pub declared_bounds: Option<[f64; 6]>,
    pub truncated: bool,
}

impl Scene {
    pub fn to_json(&self) -> Value {
        let prims: Vec<Value> = self.prims.iter().map(|p| p.to_json()).collect();
        let layers: Vec<Value> = self.layers.iter().map(|l| l.to_json()).collect();
        json!({
            "prims": prims,
            "layers": layers,
            "counts": self.counts,
            "unsupported": self.unsupported,
            "units": self.units,
            "bounds": self.bounds.to_vec(),
            "declared_bounds": self.declared_bounds,
            "truncated": self.truncated,
        })
    }
}

fn normalize(v: Vector3) -> Vector3 {
    let len = (v.x * v.x + v.y * v.y + v.z * v.z).sqrt();
    if len < 1e-12 { Vector3::new(0.0, 0.0, 1.0) } else { Vector3::new(v.x / len, v.y / len, v.z / len) }
}

fn cross(a: Vector3, b: Vector3) -> Vector3 {
    Vector3::new(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
}

/// OCS point -> WCS (DXF arbitrary axis algorithm applied to `normal`).
pub fn ocs_to_wcs(p: Vector3, normal: Vector3) -> Vector3 {
    let n = normalize(normal);
    if (n.x * n.x + n.y * n.y) < 1e-9 && (n.z - 1.0).abs() < 1e-9 {
        return p;
    }
    let w = Vector3::new(1.0, 0.0, 0.0);
    let v = Vector3::new(0.0, 1.0, 0.0);
    let s = if n.x.abs() < 0.01194 && n.y.abs() < 0.01194 { cross(v, n) } else { cross(w, n) };
    let ax = normalize(s);
    let ay = cross(n, ax);
    Vector3::new(
        p.x * ax.x + p.y * ay.x + p.z * n.x,
        p.x * ax.y + p.y * ay.y + p.z * n.y,
        p.x * ax.z + p.y * ay.z + p.z * n.z,
    )
}