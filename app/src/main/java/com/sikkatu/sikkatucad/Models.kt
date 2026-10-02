package com.sikkatu.sikkatucad

data class GenSection(
    val title: String,
    val entries: List<Pair<String, String>>
)

data class GenDocument(
    val fileName: String,
    val sections: List<GenSection>,
    val generalData: Map<String, String> = emptyMap(),
    val partSummaries: List<GenPartSummary> = emptyList(),
    val operations: List<GenOperation> = emptyList()
)

data class GenPartSummary(
    val name: String,
    val mirrored: Boolean,
    val extensionU: Double?,
    val extensionV: Double?,
    val cogU: Double?,
    val cogV: Double?
)

enum class GenOperationType {
    IDLE,
    MARKING,
    BURNING
}

data class GenBevel(
    val bevel: String = "NONE",
    val bevelCode: Double? = null,
    val bevelType: Int? = null,
    val bevelVariant: Int? = null,
    val chamferWidthTs: Double? = null,
    val chamferWidthOs: Double? = null,
    val chamferHeightTs: Double? = null,
    val chamferHeightOs: Double? = null
)

data class GenContourSegment(
    val end: Point2,
    val amp: Point2? = null,
    val radius: Double = 0.0,
    val sweepRadians: Double = 0.0,
    val origin: Point2? = null
)

data class GenContour(
    val start: Point2,
    val segments: List<GenContourSegment>,
    val bevel: GenBevel? = null
)

data class GenOperation(
    val type: GenOperationType,
    val partName: String? = null,
    val shape: String? = null,
    val label: String? = null,
    val contours: List<GenContour> = emptyList(),
    val bevel: GenBevel? = null
)

data class NcMove(
    val start: Point2,
    val end: Point2,
    val rapid: Boolean,
    val centerOffset: Point2? = null,
    val clockwise: Boolean = false,
    val bevelA: Double? = null,
    val bevelB: Double? = null,
    val cutting: Boolean = false
)

data class NcProgram(
    val fileName: String,
    val header: Map<String, String> = emptyMap(),
    val moves: List<NcMove> = emptyList()
)

data class GeometrySelection(
    val entityIndex: Int,
    val segmentIndex: Int,
    val start: Point2,
    val end: Point2,
    val layer: String?,
    val kind: String
)

data class Point2(
    val x: Double,
    val y: Double
)

data class DxfLayerInfo(
    val name: String,
    val colorIndex: Int? = null,
    val lineType: String? = null,
    val isOff: Boolean = false,
    val isFrozen: Boolean = false
)

sealed interface DxfEntity {
    val layer: String?
}

data class DxfLine(
    val start: Point2,
    val end: Point2,
    override val layer: String?
) : DxfEntity

data class DxfPolyline(
    val points: List<Point2>,
    val closed: Boolean,
    override val layer: String?
) : DxfEntity

data class DxfCircle(
    val center: Point2,
    val radius: Double,
    override val layer: String?
) : DxfEntity

data class DxfArc(
    val center: Point2,
    val radius: Double,
    val startAngle: Double,
    val endAngle: Double,
    override val layer: String?
) : DxfEntity

data class DxfText(
    val position: Point2,
    val text: String,
    val height: Double,
    override val layer: String?,
    val rotationDegrees: Double = 0.0,
    val dxfType: String = "TEXT",
    val sourceHandle: String? = null
) : DxfEntity

data class DxfDocument(
    val fileName: String,
    val entities: List<DxfEntity>,
    val units: String? = null,
    val declaredBounds: RectBounds? = null,
    val layerInfos: List<DxfLayerInfo> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val rawDxf: String? = null,
    val rawDxfCharset: String? = null
) {
    val bounds: RectBounds by lazy { declaredBounds ?: RectBounds.fromEntities(entities) }
    val entityBounds: RectBounds by lazy { RectBounds.fromEntities(entities) }
    val layers: List<String> by lazy {
        (layerInfos.map { it.name } + entities.mapNotNull { it.layer?.takeIf(String::isNotBlank) })
            .distinct()
            .sorted()
    }
    val entityCountByLayer: Map<String, Int> by lazy {
        entities.groupingBy { it.layer?.takeIf(String::isNotBlank) ?: "0" }.eachCount()
    }
}

data class RectBounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double
) {
    val width: Double get() = (maxX - minX).coerceAtLeast(1.0)
    val height: Double get() = (maxY - minY).coerceAtLeast(1.0)

    companion object {
        fun fromEntities(entities: List<DxfEntity>): RectBounds {
            val xs = mutableListOf<Double>()
            val ys = mutableListOf<Double>()
            entities.forEach { entity ->
                when (entity) {
                    is DxfLine -> {
                        xs += entity.start.x
                        xs += entity.end.x
                        ys += entity.start.y
                        ys += entity.end.y
                    }

                    is DxfPolyline -> {
                        entity.points.forEach {
                            xs += it.x
                            ys += it.y
                        }
                    }

                    is DxfCircle -> {
                        xs += entity.center.x - entity.radius
                        xs += entity.center.x + entity.radius
                        ys += entity.center.y - entity.radius
                        ys += entity.center.y + entity.radius
                    }

                    is DxfArc -> {
                        xs += entity.center.x - entity.radius
                        xs += entity.center.x + entity.radius
                        ys += entity.center.y - entity.radius
                        ys += entity.center.y + entity.radius
                    }

                    is DxfText -> {
                        xs += entity.position.x
                        ys += entity.position.y
                    }
                }
            }
            if (xs.isEmpty() || ys.isEmpty()) {
                return RectBounds(0.0, 0.0, 100.0, 100.0)
            }
            return RectBounds(xs.min(), ys.min(), xs.max(), ys.max())
        }
    }
}

data class DwgPreview(
    val fileName: String,
    val versionCode: String,
    val readableVersion: String,
    val note: String
)

sealed interface ParsedCadFile {
    data class Gen(val document: GenDocument) : ParsedCadFile
    data class Dxf(val document: DxfDocument) : ParsedCadFile
    data class Nc(val program: NcProgram, val document: DxfDocument) : ParsedCadFile
    data class Dwg(val preview: DwgPreview, val document: DxfDocument? = null) : ParsedCadFile
}

data class NestPart(
    val name: String,
    val entities: List<DxfEntity>,
    var quantity: Int = 1,
    var rotationDeg: Double = 0.0,
    var mirrorX: Boolean = false
)

