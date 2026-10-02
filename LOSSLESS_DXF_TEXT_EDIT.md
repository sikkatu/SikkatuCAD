# Lossless DXF text editing

This version keeps the original DXF text stream when a DXF is opened. The viewer may still use its simplified
geometry model for display/editing, but saving an edited DXF no longer serializes that simplified model.

For existing TEXT/MTEXT entities, the save path:
- keeps HEADER, TABLES, BLOCKS, ENTITIES, OBJECTS and unsupported entities untouched;
- identifies text entities by DXF handle when available;
- preserves MTEXT formatting/style and non-text group codes;
- replaces only the text payload (group 1 / group 3);
- preserves block-defined text by editing the source entity in the BLOCKS section.

The new "保存 DXF（保留原图结构）" action is shown for an opened DXF.
