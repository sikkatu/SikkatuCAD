//! Source metadata carried explicitly through the whole collision/expansion
//! pipeline. Per spec 5.1 + review item 2: HANDLE and GUID of the *original*
//! DXF entity survive every explosion step; did-use the handle of an exploded
//! entity is unacceptable since `EntityType::explode()` resets it to
//! `Handle::NULL` via `inherit_common()`.
#[derive(Debug, Clone)]
pub struct SrcMeta {
    /// Handle of the original source entity as uppercase hex (no `0x`),
    /// matching DXF group-5. For INSERT descendants the `WalkCtx` joins
    /// "<insert>/<descendant>" before calling back in.
    pub h: String,
    /// First `XDataValue::String("guid:<GUID>")` from the source's SKT XDATA
    /// record, or `None` when no valid GUID exists.
    pub g: Option<String>,
    /// Original DXF type tag of the composite's outermost entity
    /// (LWPOLYLINE, DIMENSION, INSERT, ...).
    pub ty: String,
}

impl SrcMeta {
    /// Quick constructor used only for trivial source types the caller has
    /// not yet built (e.g. top-level `build_scene` before splitting).
    pub fn of(e: &acadrust::entities::EntityType) -> Self {
        use super::scene_build::guid_from_xdata;
        SrcMeta {
            h: super::scene_emit::entity_handle(e),
            g: guid_from_xdata(e),
            ty: super::scene_emit::type_tag(e),
        }
    }
}

/// Per-thread emission state: the 2,000,000 primitive limit spans the
/// *entire* recursive walk (review item 3) and is checked at every emitted
/// primitive, not just at model-space iteration boundaries. Also tracks the
/// block reference expansion path for INSERT cycle detection (review
/// item 5).
pub struct EmitCtx {
    pub prims_emitted: usize,
    pub truncated: bool,
    pub limit: usize,
    /// Names of block records currently being expanded, outermost first.
    pub block_path: Vec<String>,
}

impl EmitCtx {
    pub fn new() -> Self {
        EmitCtx {
            prims_emitted: 0,
            truncated: false,
            limit: 2_000_000,
            block_path: Vec::new(),
        }
    }
    /// Account for `n` freshly produced prims; return `false` (and set
    /// `truncated`) once the cap is exceeded so callers can bail out early.
    pub fn admit(&mut self, n: usize) -> bool {
        if self.truncated {
            return false;
        }
        let total = self.prims_emitted + n;
        if total >= self.limit {
            self.prims_emitted = self.limit;
            self.truncated = true;
            return false;
        }
        self.prims_emitted = total;
        true
    }
    /// Push a block name onto the expansion path, returning `false` when the
    /// name already appears in the current chain (cycle).
    pub fn enter_block(&mut self, name: &str) -> bool {
        if self.block_path.iter().any(|b| b == name) {
            return false;
        }
        self.block_path.push(name.to_string());
        true
    }
    pub fn leave_block(&mut self) {
        self.block_path.pop();
    }
}