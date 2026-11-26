//! Scene export from a CadDocument — Part 2: primitive emission.
//! Walks model-space entities, explodes composites through acadrust
//! 0.5.5 (`EntityType::explode`, `Insert::explode`) and emits scene
//! primitives per spec section 5.1 (LINE/POLY/CIRCLE/ARC/TEXT/POINT/FACE).
use acadrust::document::CadDocument;
use acadrust::entities::{EntityType, Face3D, Solid};
use acadrust::types::Vector3;

use super::scene::{LayerInfo, Meta, Prim, Scene};
use super::scene_build::{guid_from_xdata, plain_text};

/// Handle of a whole spec: DXF code 5 uppercase hex without 0x prefix.
/// For INSERT descendants this becomes "<insert handle>/<inner handle>"
/// per spec section 5.1.
pub fn entity_handle(e: &EntityType) -> String {
    format!("{:X}", e.common().handle.value())
}

/// Original DXF entity type tag (group code 0 name) as spec `ty`.
pub fn type_tag(e: &EntityType) -> String {
    match e {
        EntityType::Line(_) => "LINE",
        EntityType::Arc(_) => "ARC",
        EntityType::Circle(_) => "CIRCLE",
        EntityType::Ellipse(_) => "ELLIPSE",
        EntityType::Point(_) => "POINT",
        EntityType::Text(_) => "TEXT",
        EntityType::MText(_) => "MTEXT",
        EntityType::AttributeEntity(_) => "ATTRIB",
        EntityType::AttributeDefinition(_) => "ATTDEF",
        EntityType::Insert(_) => "INSERT",
        EntityType::LwPolyline(_) => "LWPOLYLINE",
        EntityType::Polyline(_) => "POLYLINE",
        EntityType::Polyline2D(_) => "POLYLINE2D",
        EntityType::Polyline3D(_) => "POLYLINE3D",
        EntityType::Spline(_) => "SPLINE",
        EntityType::Helix(_) => "HELIX",
        EntityType::Dimension(_) => "DIMENSION",
        EntityType::Hatch(_) => "HATCH",
        EntityType::Solid(_) => "SOLID",
        EntityType::Face3D(_) => "3DFACE",
        EntityType::Mesh(_) => "MESH",
        EntityType::PolyfaceMesh(_) => "POLYFACEMESH",
        EntityType::PolygonMesh(_) => "POLYGONMESH",
        EntityType::Solid3D(_) => "3DSOLID",
        EntityType::Region(_) => "REGION",
        EntityType::Body(_) => "BODY",
        EntityType::Ole2Frame(_) => "OLE2FRAME",
        EntityType::RasterImage(_) => "IMAGE",
        EntityType::Leader(_) => "LEADER",
        EntityType::MultiLeader(_) => "MLEADER",
        EntityType::MLine(_) => "MLINE",
        EntityType::Ray(_) => "RAY",
        EntityType::XLine(_) => "XLINE",
        EntityType::Light(_) => "LIGHT",
        EntityType::Table(_) => "TABLE",
        EntityType::Shape(_) => "SHAPE",
        EntityType::SectionSymbol(_) => "SECTIONSYMBOL",
        EntityType::Surface(_) => "SURFACE",
        EntityType::Tolerance(_) => "TOLERANCE",
        EntityType::Wipeout(_) => "WIPEOUT",
        EntityType::Underlay(_) => "UNDERLAY",
        EntityType::Viewport(_) => "VIEWPORT",
        EntityType::ViewBorder(_) => "VIEWBORDER",
        EntityType::Extended(_) => "EXTENDED",
        EntityType::Unknown(_) => "UNKNOWN",
        // Block bookkeeping entries never appear in model space walks, but
        // tag rather than panic if they do.
        EntityType::Block(_) => "BLOCK",
        EntityType::BlockEnd(_) => "BLOCKEND",
        EntityType::Seqend(_) => "SEQEND",
    }
    .to_string()
}

/// Original entity type name for handle-tag pairing inside inserts: after
/// explode everything becomes a primitive whose Meta.ty stays the *source
/// composite's* tag (spec uses original type, e.g. DIMENSION even for its
/// exploded lines).
fn meta_for(e: &EntityType, insert_parent: &str) -> Meta {
    let h = entity_handle(e);
    let h = if insert_parent.is_empty() {
        h
    } else {
        format!("{insert_parent}/{h}")
    };
    Meta {
        h,
        l: e.common().layer.clone(),
        col: 0xFFFFFF, // fill via resolve_color when a doc-level lookup exists
        g: guid_from_xdata(e),
        ty: type_tag(e),
    }
}

/// Geometry primitives for the elementary entity types the scene supports;
/// `src_tag` preserves the original type of a composite that exploded into
/// these primitives (`src_tag` == type_tag(e) for faith-full conversions).
fn elementary_prims(e: &EntityType, insert_parent: &str, src_tag: &str) -> Option<Vec<Prim>> {
    let mut meta = meta_for(e, insert_parent);
    meta.ty = src_tag.to_string();
    match e {
        EntityType::Line(line) => {
            let p0 = line.start;
            let p1 = line.end;
            Some(vec![Prim::Line {
                p: [p0.x, p0.y, p0.z, p1.x, p1.y, p1.z],
                meta,
            }])
        }
        EntityType::Circle(circle) => {
            let c = circle.center;
            Some(vec![Prim::Circle {
                c: [c.x, c.y, c.z],
                r: circle.radius,
                meta,
            }])
        }
        EntityType::Arc(arc) => {
            let c = arc.center;
            let a0 = arc.start_angle.to_degrees();
            let a1 = arc.end_angle.to_degrees();
            Some(vec![Prim::Arc {
                c: [c.x, c.y, c.z],
                r: arc.radius,
                a0,
                a1,
                meta,
            }])
        }
        EntityType::Point(pt) => {
            let p = pt.location;
            Some(vec![Prim::Point {
                p: [p.x, p.y, p.z],
                meta,
            }])
        }
        EntityType::Text(text) => {
            let p = text.insertion_point;
            Some(vec![Prim::Text {
                p: [p.x, p.y, p.z],
                s: plain_text(&text.value),
                h: text.height,
                rot: text.rotation.to_degrees(),
                meta,
            }])
        }
        EntityType::MText(mtext) => {
            let p = mtext.insertion_point;
            Some(vec![Prim::Text {
                p: [p.x, p.y, p.z],
                s: plain_text(&mtext.value),
                h: mtext.height,
                rot: 0.0, // rotation lives on attachment_point / direction
                meta,
            }])
        }
        EntityType::Solid(solid) => {
            let corners = Solid::boundary_corners(solid);
            Some(face_prim(&corners, true, 0, meta))
        }
        EntityType::Face3D(face) => {
            let corners = Face3D::corners(face);
            let mut inv = 0u32;
            if face.invisible_edges.is_first_invisible() {
                inv |= 1;
            }
            if face.invisible_edges.is_second_invisible() {
                inv |= 2;
            }
            if face.invisible_edges.is_third_invisible() {
                inv |= 4;
            }
            if face.invisible_edges.is_fourth_invisible() {
                inv |= 8;
            }
            Some(face_prim(&corners, false, inv, meta))
        }
        _ => None,
    }
}

fn face_prim(corners: &[Vector3], fill: bool, inv: u32, meta: Meta) -> Vec<Prim> {
    // 3 or 4 corners expected; pad to 4 identical for triangles.
    let pts: Vec<f64> = corners.iter().flat_map(|c| [c.x, c.y, c.z]).collect();
    if pts.len() < 9 {
        return Vec::new();
    }
    // spec: FACE p is 9 or 12 numbers across all corners; if only 3 unique
    // corners we emit 9 numbers (triangle).
    let is_quad = pts.len() >= 12
        && !(corners.len() == 4
            && (corners[2].x - corners[3].x).abs() < 1e-9
            && (corners[2].y - corners[3].y).abs() < 1e-9
            && (corners[2].z - corners[3].z).abs() < 1e-9);
    let trimmed = if is_quad {
        pts[..12].to_vec()
    } else {
        pts[..9].to_vec()
    };
    vec![Prim::Face {
        p: trimmed,
        fill,
        inv,
        meta,
    }]
}

/// Expand one LwPolyline into a prim: straight segments join a single POLY ;
/// each bulged segment is approximated by arc points with angular step <= 5°
/// per spec 5.1 ("включая bulge"; точный шаг по углу для кривых указан для
/// Hatch — для булавки поли-линии придерживаемся того же 5°).
fn lw_polyline_prim(pl: &acadrust::entities::LwPolyline, insert_parent: &str) -> Option<Prim> {
    let meta = meta_for(
        &EntityType::LwPolyline(pl.clone()),
        insert_parent,
    );
    let mut pts: Vec<f64> = Vec::new();
    let verts = &pl.vertices;
    if verts.is_empty() {
        return None;
    }
    let n = verts.len();
    let seg_count = if pl.is_closed { n } else { n.saturating_sub(1) };
    // helper: arc sampled between p0 and p1 with bulge
    fn sample_arc(
        x0: f64,
        y0: f64,
        x1: f64,
        y1: f64,
        bulge: f64,
        out: &mut Vec<f64>,
    ) {
        // DXF bulge definition: bulge = tan(theta/4), theta included angle
        // (positive = CCW). Compute center/radius/angles, then sample <= 5°.
        let dx = x1 - x0;
        let dy = y1 - y0;
        let chord = (dx * dx + dy * dy).sqrt();
        if chord < 1e-12 || bulge.abs() < 1e-12 {
            out.push(x1);
            out.push(y1);
            out.push(0.0);
            return;
        }
        let theta = 4.0 * bulge.atan(); // included angle, signed
        let r = chord / (2.0 * (theta / 2.0).sin());
        // midpoint + normal offset
        let mid_x = (x0 + x1) / 2.0;
        let mid_y = (y0 + y1) / 2.0;
        // distance from midpoint to center along chord normal
        let h = (r.abs()) * (theta / 2.0).cos();
        let nx = -dy / chord;
        let ny = dx / chord;
        // sign so that center lies on correct side for CCW positive bulge
        let side = if bulge > 0.0 { -1.0 } else { 1.0 };
        let cx = mid_x + side * nx * h;
        let cy = mid_y + side * ny * h;
        let a0 = (y0 - cy).atan2(x0 - cx);
        let a1 = (y1 - cy).atan2(x1 - cx);
        let mut dtheta = a1 - a0;
        if bulge > 0.0 {
            while dtheta <= 0.0 {
                dtheta += std::f64::consts::TAU;
            }
        } else {
            while dtheta >= 0.0 {
                dtheta -= std::f64::consts::TAU;
            }
        }
        let steps = ((dtheta.abs().to_degrees()) / 5.0).ceil().max(2.0) as usize;
        for i in 1..=steps {
            let t = i as f64 / steps as f64;
            let a = a0 + dtheta * t;
            let x = cx + r.abs() * a.cos();
            let y = cy + r.abs() * a.sin();
            out.push(x);
            out.push(y);
            out.push(0.0);
        }
    }

    // First vertex
    let v0 = &verts[0];
    pts.push(v0.location.x);
    pts.push(v0.location.y);
    pts.push(pl.elevation);

    for i in 0..seg_count {
        let v = &verts[i];
        let nxt = &verts[(i + 1) % n];
        if v.bulge.abs() < 1e-12 {
            pts.push(nxt.location.x);
            pts.push(nxt.location.y);
            pts.push(pl.elevation);
        } else {
            sample_arc(
                v.location.x,
                v.location.y,
                nxt.location.x,
                nxt.location.y,
                v.bulge,
                &mut pts,
            );
        }
    }

    Some(Prim::Poly {
        p: pts,
        closed: pl.is_closed,
        meta,
    })
}

/// Entities for elementary types; LwPolyline handled separately via
/// bulge-aware POLY construction. Composites are exploded by the caller.
pub fn prim_for(e: &EntityType, insert_parent: &str, src_tag: &str) -> Option<Vec<Prim>> {
    if let EntityType::LwPolyline(pl) = e {
        return lw_polyline_prim(pl, insert_parent).map(|p| vec![p]);
    }
    elementary_prims(e, insert_parent, src_tag)
}

/// Recursively walk one model-space entity, exploding composites
/// (spec 5.1: `EntityType::explode()` decomposes LwPolyline/Polyline/Circle/
/// Ellipse/Spline/Hatch/Dimension/Mesh/MLine/Leader/Solid/Face3D/MText,
/// bulge included; `Insert::explode` expands block references with
/// scale/rotation/array; nested inserts recurse, depth <= 16, cycles cut).
pub fn emit_walk(
    doc: &CadDocument,
    e: &EntityType,
    insert_parent: &str,
    src_tag: &str,
    out: &mut Vec<Prim>,
    unsupported: &mut std::collections::HashMap<String, usize>,
    depth: usize,
) {
    if depth > 16 {
        *unsupported.entry(format!("DEPTH_EXCEEDED::{src_tag}")).or_insert(0) += 1;
        return;
    }
    if let Some(prims) = prim_for(e, insert_parent, src_tag) {
        out.extend(prims);
        return;
    }
    match e {
        EntityType::Insert(insert) => {
            let block_name = &insert.block_name;
            // Inset's own handle becomes the "<parent>" prefix for children.
            let h = entity_handle(e);
            let parent = if insert_parent.is_empty() {
                h.clone()
            } else {
                format!("{insert_parent}/{h}")
            };
            let block_entities: Vec<EntityType> = doc.entities_in_block(block_name).cloned().collect();
            for child in &block_entities {
                emit_walk(doc, child, &parent, src_tag, out, unsupported, depth + 1);
            }
        }
        _ => {
            // Try generic explode first (LwPolyline/Polyline/Spline/Hatch/
            // Dimension/Mesh/Solid/Face3D decompose here per 5.1).
            let exploded = e.explode();
            if exploded.is_empty() {
                *unsupported.entry(type_tag(e)).or_insert(0) += 1;
                return;
            }
            for part in &exploded {
                emit_walk(doc, part, insert_parent, src_tag, out, unsupported, depth + 1);
            }
        }
    }
}

/// Table of layers for `Scene::layers` (name, resolved color, linetype, off,
/// frozen) straight from the DXF LAYER table.
pub fn scene_layers(doc: &CadDocument) -> Vec<LayerInfo> {
    doc.layers
        .iter()
        .map(|l| LayerInfo {
            name: l.name.clone(),
            color: 0xFFFFFF,
            ltype: l.line_type.clone(),
            off: l.flags.off,
            frozen: l.flags.frozen,
        })
        .collect()
}

/// Assemble a full `Scene` from the model-space entities (spec 5.1).
pub fn build_scene(doc: &CadDocument) -> Scene {
    let mut prims = Vec::new();
    let mut counts = std::collections::HashMap::new();
    let mut unsupported = std::collections::HashMap::new();
    let mut truncated = false;
    let limit = 2_000_000usize;

    for e in doc.model_space_entities() {
        if prims.len() >= limit {
            truncated = true;
            break;
        }
        let tag = type_tag(e);
        *counts.entry(tag.clone()).or_insert(0) += 1;
        emit_walk(doc, e, "", &tag, &mut prims, &mut unsupported, 0);
    }

    Scene {
        prims,
        layers: scene_layers(doc),
        counts,
        unsupported,
        units: None,
        bounds: [0.0; 6],
        declared_bounds: None,
        truncated,
    }
}