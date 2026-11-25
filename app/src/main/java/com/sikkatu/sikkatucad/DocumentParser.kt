package com.sikkatu.sikkatucad

import android.graphics.Path
import android.graphics.PointF
import android.net.Uri
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.File
import java.nio.charset.Charset
import kotlin.math.abs

class DocumentParser(private val activity: MainActivity) {

    fun parse(uri: Uri, fileName: String): ParsedCadFile {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "dxf" -> parseDxfByContent(uri, fileName)
            "pdf" -> throw IOException(activity.getString(R.string.s0005))
            "dwg" -> ParsedCadFile.Dwg(parseDwg(uri, fileName), convertDwgToDocument(uri, fileName))
            else -> {
                val header = readHeader(uri, 6)
                when {
                    readHeader(uri, 4) == "%PDF" -> throw IOException(activity.getString(R.string.s0005))
                    header.startsWith("AC10") -> ParsedCadFile.Dwg(parseDwg(uri, fileName), convertDwgToDocument(uri, fileName))
                    else -> parseDxfByContent(uri, fileName)
                }
            }
        }
    }

    /**
     * Dispatch .dxf files by their actual content, not just the extension.
     * A DWG renamed to .dxf would otherwise be fed to the text DXF parser,
     * silently yielding an empty document and a blank canvas.
     */
    private fun parseDxfByContent(uri: Uri, fileName: String): ParsedCadFile {
        val head = readHeadBytes(uri, 64)
        val headAscii = head.toString(Charsets.ISO_8859_1)
        return when {
            headAscii.startsWith("AutoCAD Binary DXF") ->
                throw IOException(activity.getString(R.string.s0502))
            readHeader(uri, 6).startsWith("AC10") ->
                ParsedCadFile.Dwg(parseDwg(uri, fileName), convertDwgToDocument(uri, fileName))
            else ->
                ParsedCadFile.Dxf(parseDxf(uri, fileName))
        }
    }



    private fun normalizeTextRotation(rotation: Double): Double {
        var value = rotation
        while (value <= -180.0) value += 360.0
        while (value > 180.0) value -= 360.0
        return value
    }

    private fun snapRightAngle(rotation: Double): Double {
        val candidates = listOf(-180.0, -90.0, 0.0, 90.0, 180.0)
        return candidates.minByOrNull { abs(it - rotation) } ?: 0.0
    }



    private fun trimTrailingZeros(value: Double): String {
        val text = "%.4f".format(value)
        return text.trimEnd('0').trimEnd('.')
    }

    private fun parseDwg(uri: Uri, fileName: String): DwgPreview {
        val versionCode = readHeader(uri, 6)
        val readable = when (versionCode) {
            "AC1015" -> "AutoCAD 2000"
            "AC1018" -> "AutoCAD 2004"
            "AC1021" -> "AutoCAD 2007"
            "AC1024" -> "AutoCAD 2010"
            "AC1027" -> "AutoCAD 2013"
            "AC1032" -> "AutoCAD 2018"
            else -> if (versionCode.startsWith("AC10")) "AutoCAD DWG" else activity.getString(R.string.s0005)
        }
        return DwgPreview(
            fileName = fileName,
            versionCode = versionCode.ifBlank { activity.getString(R.string.s0006) },
            readableVersion = readable,
            note = activity.getString(R.string.s0007)
        )
    }

    private fun parseDxfPairs(lines: List<String>): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        var index = 0
        while (index < lines.size) {
            val code = lines[index].removePrefix("\uFEFF").trim()
            // A group code is always an integer. Blank/junk lines are NOT codes.
            if (code.toIntOrNull() == null) {
                index++
                continue
            }
            val value = if (index + 1 < lines.size) lines[index + 1].removePrefix("\uFEFF").trim() else ""
            pairs += code to value
            index += 2
        }
        return pairs
    }

    private fun parseDxf(uri: Uri, fileName: String): DxfDocument {
        val raw = readRawDxf(uri)
        return parseDxfText(raw.text, raw.charset.name(), fileName)
    }
    /** Parse DXF text (also used for DWG converted via the bridge). */
    private fun parseDxfText(text: String, charsetName: String, fileName: String): DxfDocument {
        val pairs = parseDxfPairs(text.split(Regex("\r\n|\n|\r")))
        val headerInfo = parseHeaderInfo(pairs)
        val layerInfos = parseLayerInfos(pairs)
        val blocks = parseBlocks(pairs)
        val entities = parseEntities(pairs) { blocks[it] }
        return DxfDocument(
            fileName = fileName,
            entities = entities,
            units = headerInfo.units,
            declaredBounds = headerInfo.bounds,
            layerInfos = layerInfos,
            rawDxf = text,
            rawDxfCharset = charsetName
        )
    }
    /**
     * Конвертирует DWG в DXF через Rust-мост (acadrust) и разбирает штатным DXF-парсером.
     * При любой ошибке возвращает null — тогда DWG открывается как раньше (карточка-заглушка).
     */
    private fun convertDwgToDocument(uri: Uri, fileName: String): DxfDocument? {
        return runCatching {
            val ext = fileName.substringAfterLast('.', "dwg").ifBlank { "dwg" }
            val src = File(activity.cacheDir, "cad_convert_src.$ext")
            activity.contentResolver.openInputStream(uri)?.use { input ->
                src.outputStream().use { out -> input.copyTo(out) }
            } ?: return@runCatching null
            val out = File(activity.cacheDir, "cad_convert_out.dxf")
            if (out.exists()) out.delete()
            val sid = CadBridge.open(src.absolutePath).getOrThrow()
            try {
                CadBridge.saveDxf(sid, out.absolutePath).getOrThrow()
            } finally {
                runCatching { CadBridge.close(sid) }
            }
            if (!out.exists() || out.length() == 0L) return@runCatching null
            val text = out.readText(Charsets.UTF_8)
            if (!text.contains("SECTION")) return@runCatching null
            parseDxfText(text, Charsets.UTF_8.name(), fileName)
        }.getOrNull()
    }
    private fun parseBlocks(pairs: List<Pair<String, String>>): Map<String, Pair<Point2, List<DxfEntity>>> {
        val blockRaw = mutableMapOf<String, Pair<Point2, List<Pair<String, String>>>>()
        var inBlocks = false
        var index = 0
        while (index < pairs.size) {
            val (code, value) = pairs[index]
            if (code == "0" && value == "SECTION" && index + 1 < pairs.size && pairs[index + 1].first == "2") {
                inBlocks = pairs[index + 1].second == "BLOCKS"
                index += 2
                continue
            }
            if (!inBlocks) {
                index++
                continue
            }
            if (code == "0" && value == "ENDSEC") {
                inBlocks = false
                index++
                continue
            }
            if (code == "0" && value == "BLOCK") {
                val blockHeader = collectEntity(pairs, index + 1)
                val blockName = blockHeader.entries.firstOrNull { it.first == "2" }?.second
                var contentStart = blockHeader.nextIndex
                var end = contentStart
                while (end < pairs.size) {
                    if (pairs[end].first == "0" && pairs[end].second == "ENDBLK") {
                        break
                    }
                    end++
                }
                if (!blockName.isNullOrBlank()) {
                    val baseX = blockHeader.doubleOrNull("10") ?: 0.0
                    val baseY = blockHeader.doubleOrNull("20") ?: 0.0
                    blockRaw[blockName] = Pair(Point2(baseX, baseY), pairs.subList(contentStart, end))
                }
                index = end + 1
            } else {
                index++
            }
        }
        // second pass: expand with full block map (allow nested inserts)
        val resolved = mutableMapOf<String, Pair<Point2, List<DxfEntity>>>()
        fun resolve(name: String): Pair<Point2, List<DxfEntity>>? {
            resolved[name]?.let { return it }
            val (basePoint, content) = blockRaw[name] ?: return null
            // placeholder to break potential cycles
            resolved[name] = Pair(basePoint, emptyList())
            val entities = parseEntities(content, ::resolve)
            resolved[name] = Pair(basePoint, entities)
            return Pair(basePoint, entities)
        }
        blockRaw.keys.forEach { resolve(it) }
        return resolved
    }

    private fun parseEntities(
        pairs: List<Pair<String, String>>,
        blockResolver: (String) -> Pair<Point2, List<DxfEntity>>?
    ): MutableList<DxfEntity> {
        val entities = mutableListOf<DxfEntity>()
        val hasSections = pairs.any { it.second == "SECTION" }
        var inEntities = !hasSections
        var index = 0
        while (index < pairs.size) {
            val (code, value) = pairs[index]
            if (code == "0" && value == "SECTION" && index + 1 < pairs.size && pairs[index + 1].first == "2") {
                inEntities = pairs[index + 1].second == "ENTITIES"
                index += 2
                continue
            }
            if (!inEntities && hasSections) {
                index++
                continue
            }
            if (code == "0" && value == "ENDSEC" && inEntities) {
                inEntities = false
                index++
                continue
            }
            if (code != "0") {
                index++
                continue
            }
            when (value.uppercase()) {
                "LINE" -> {
                    val entityBlock = collectEntity(pairs, index + 1)
                    parseLine(entityBlock)?.let(entities::add)
                    index = entityBlock.nextIndex
                }

                "LWPOLYLINE" -> {
                    val entityBlock = collectEntity(pairs, index + 1)
                    parseLwPolyline(entityBlock)?.let(entities::add)
                    index = entityBlock.nextIndex
                }

                "POLYLINE" -> {
                    val polyResult = collectPolyline(pairs, index + 1)
                    parsePolyline(polyResult.entries)?.let(entities::add)
                    index = polyResult.nextIndex
                }

                "CIRCLE" -> {
                    val entityBlock = collectEntity(pairs, index + 1)
                    parseCircle(entityBlock)?.let(entities::add)
                    index = entityBlock.nextIndex
                }

                "ARC" -> {
                    val entityBlock = collectEntity(pairs, index + 1)
                    parseArc(entityBlock)?.let(entities::add)
                    index = entityBlock.nextIndex
                }

                "ELLIPSE" -> {
                    val entityBlock = collectEntity(pairs, index + 1)
                    parseEllipse(entityBlock)?.let(entities::add)
                    index = entityBlock.nextIndex
                }

                "SPLINE" -> {
                    val entityBlock = collectEntity(pairs, index + 1)
                    parseSpline(entityBlock)?.let(entities::add)
                    index = entityBlock.nextIndex
                }

                "HATCH" -> {
                    val entityBlock = collectEntity(pairs, index + 1)
                    entities += parseHatch(entityBlock)
                    index = entityBlock.nextIndex
                }

                "TEXT", "MTEXT", "ATTRIB" -> {
                    val entityBlock = collectEntity(pairs, index + 1)
                    parseText(entityBlock, value.uppercase()).let { parsed -> parsed?.let(entities::add) }
                    index = entityBlock.nextIndex
                }

                "INSERT" -> {
                    val entityBlock = collectEntity(pairs, index + 1)
                    val blockName = entityBlock.single("2")
                    val insertPoint = Point2(entityBlock.doubleOrNull("10") ?: 0.0, entityBlock.doubleOrNull("20") ?: 0.0)
                    val rotation = entityBlock.doubleOrNull("50") ?: 0.0
                    val scaleX = entityBlock.doubleOrNull("41") ?: 1.0
                    val scaleY = entityBlock.doubleOrNull("42") ?: 1.0
                    blockName?.let { name ->
                        val resolvedBlock = blockResolver(name)
                        if (resolvedBlock != null) {
                            // World_point = insert + R*S*(p - base_point).
                            // Block content is stored in world coordinates and the block base point
                            // equals the insert point — so subtract it before transforming.
                            val basePoint = resolvedBlock.first
                            val radBase = Math.toRadians(rotation)
                            val bxs = basePoint.x * scaleX
                            val bys = basePoint.y * scaleY
                            val bxr = bxs * kotlin.math.cos(radBase) - bys * kotlin.math.sin(radBase)
                            val byr = bxs * kotlin.math.sin(radBase) + bys * kotlin.math.cos(radBase)
                            val effectiveOffset = Point2(insertPoint.x - bxr, insertPoint.y - byr)
                            resolvedBlock.second.forEach { original ->
                                transformEntity(original, effectiveOffset, rotation, scaleX, scaleY)?.let(entities::add)
                            }
                        }
                    }
                    index = entityBlock.nextIndex
                }

                else -> {
                    index = collectEntity(pairs, index + 1).nextIndex
                }
            }
        }
        return entities
    }

    private fun transformEntity(
        entity: DxfEntity,
        offset: Point2,
        rotationDeg: Double,
        scaleX: Double,
        scaleY: Double
    ): DxfEntity? {
        val rad = Math.toRadians(rotationDeg)
        fun transform(p: Point2): Point2 {
            val sx = p.x * scaleX
            val sy = p.y * scaleY
            if (rotationDeg == 0.0) return Point2(sx + offset.x, sy + offset.y)
            val x = sx * kotlin.math.cos(rad) - sy * kotlin.math.sin(rad)
            val y = sx * kotlin.math.sin(rad) + sy * kotlin.math.cos(rad)
            return Point2(x + offset.x, y + offset.y)
        }
        return when (entity) {
            is DxfLine -> DxfLine(transform(entity.start), transform(entity.end), entity.layer)
            is DxfPolyline -> DxfPolyline(entity.points.map(::transform), entity.closed, entity.layer)
            is DxfCircle -> {
                val center = transform(entity.center)
                if (kotlin.math.abs(kotlin.math.abs(scaleX) - kotlin.math.abs(scaleY)) > 1e-9) {
                    val steps = 72
                    val points = (0..steps).map { index ->
                        val angle = Math.PI * 2.0 * index.toDouble() / steps.toDouble()
                        transform(Point2(entity.center.x + entity.radius * kotlin.math.cos(angle), entity.center.y + entity.radius * kotlin.math.sin(angle)))
                    }
                    DxfPolyline(points, true, entity.layer)
                } else {
                    val radiusScale = kotlin.math.abs(scaleX).coerceAtLeast(1e-6)
                    DxfCircle(center, entity.radius * radiusScale, entity.layer)
                }
            }
            is DxfArc -> {
                if (kotlin.math.abs(kotlin.math.abs(scaleX) - kotlin.math.abs(scaleY)) > 1e-9) {
                    val sweep = normalizeAngleSweep(entity.startAngle, entity.endAngle)
                    val steps = maxOf(16, (kotlin.math.abs(sweep) / 5.0).toInt())
                    val points = (0..steps).map { index ->
                        val angle = Math.toRadians(entity.startAngle + sweep * index.toDouble() / steps.toDouble())
                        transform(Point2(entity.center.x + entity.radius * kotlin.math.cos(angle), entity.center.y + entity.radius * kotlin.math.sin(angle)))
                    }
                    DxfPolyline(points, false, entity.layer)
                } else {
                    val center = transform(entity.center)
                    val radiusScale = kotlin.math.abs(scaleX).coerceAtLeast(1e-6)
                    DxfArc(center, entity.radius * radiusScale, entity.startAngle + rotationDeg, entity.endAngle + rotationDeg, entity.layer)
                }
            }
            is DxfText -> {
                val pos = transform(entity.position)
                val heightScale = ((kotlin.math.abs(scaleX) + kotlin.math.abs(scaleY)) / 2.0).coerceAtLeast(1e-6)
                DxfText(
                    pos,
                    entity.text,
                    entity.height * heightScale,
                    entity.layer,
                    entity.rotationDegrees + rotationDeg,
                    entity.dxfType,
                    entity.sourceHandle
                )
            }
        }
    }

    private fun normalizeAngleSweep(start: Double, end: Double): Double {
        var sweep = end - start
        if (sweep < 0.0) sweep += 360.0
        if (kotlin.math.abs(sweep) < 1e-9) sweep = 360.0
        return sweep
    }

    private fun parseLine(block: EntityBlock): DxfLine? {
        val start = Point2(block.doubleOrNull("10") ?: return null, block.doubleOrNull("20") ?: return null)
        val end = Point2(block.doubleOrNull("11") ?: return null, block.doubleOrNull("21") ?: return null)
        return DxfLine(start, end, block.single("8"))
    }

    private fun parseLwPolyline(block: EntityBlock): DxfPolyline? {
        val vertices = mutableListOf<Pair<Point2, Double>>()
        var pendingX: Double? = null
        var pendingY: Double? = null
        var pendingBulge = 0.0
        fun flushVertex() {
            val x = pendingX ?: return
            val y = pendingY ?: return
            vertices += Point2(x, y) to pendingBulge
            pendingX = null
            pendingY = null
            pendingBulge = 0.0
        }
        block.entries.forEach { (code, value) ->
            when (code) {
                "10" -> {
                    flushVertex()
                    pendingX = value.toDoubleOrNull()
                }
                "20" -> pendingY = value.toDoubleOrNull()
                "42" -> pendingBulge = value.toDoubleOrNull() ?: 0.0
            }
        }
        flushVertex()
        val points = flattenBulgedPolyline(vertices, block.single("70")?.toIntOrNull()?.and(1) == 1)
        if (points.size < 2) return null
        val closed = block.single("70")?.toIntOrNull()?.and(1) == 1
        return DxfPolyline(points, closed, block.single("8"))
    }

    private fun parsePolyline(entries: List<Pair<String, String>>): DxfPolyline? {
        val vertices = mutableListOf<Pair<Point2, Double>>()
        var layer: String? = null
        var flags = 0
        var index = 0
        while (index < entries.size) {
            val pair = entries[index]
            when {
                pair.first == "8" && layer == null -> layer = pair.second
                pair.first == "70" -> flags = pair.second.toIntOrNull() ?: flags
                pair.first == "0" && pair.second == "VERTEX" -> {
                    val block = mutableListOf<Pair<String, String>>()
                    index++
                    while (index < entries.size && entries[index].first != "0") {
                        block += entries[index]
                        index++
                    }
                    val x = block.firstOrNull { it.first == "10" }?.second?.toDoubleOrNull()
                    val y = block.firstOrNull { it.first == "20" }?.second?.toDoubleOrNull()
                    val bulge = block.firstOrNull { it.first == "42" }?.second?.toDoubleOrNull() ?: 0.0
                    if (x != null && y != null) {
                        vertices += Point2(x, y) to bulge
                    }
                    continue
                }
            }
            index++
        }
        val closed = flags.and(1) == 1
        val points = flattenBulgedPolyline(vertices, closed)
        if (points.size < 2) return null
        return DxfPolyline(points, closed, layer)
    }

    private fun flattenBulgedPolyline(vertices: List<Pair<Point2, Double>>, closed: Boolean): List<Point2> {
        if (vertices.isEmpty()) return emptyList()
        val points = mutableListOf<Point2>()
        fun appendSegment(start: Point2, end: Point2, bulge: Double) {
            if (points.isEmpty()) points += start
            if (bulge == 0.0) {
                points += end
                return
            }
            val theta = 4.0 * kotlin.math.atan(bulge)
            val chord = kotlin.math.sqrt((end.x - start.x) * (end.x - start.x) + (end.y - start.y) * (end.y - start.y))
            if (chord == 0.0) return
            val radius = chord / (2.0 * kotlin.math.sin(kotlin.math.abs(theta) / 2.0).coerceAtLeast(1e-6))
            val mid = Point2((start.x + end.x) / 2.0, (start.y + end.y) / 2.0)
            val dx = end.x - start.x
            val dy = end.y - start.y
            val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
            val nx = -dy / length
            val ny = dx / length
            val sagittaSide = if (bulge >= 0) 1.0 else -1.0
            val distanceToCenter = kotlin.math.sqrt((radius * radius) - (chord * chord / 4.0)).coerceAtLeast(0.0)
            val center = Point2(mid.x + nx * distanceToCenter * sagittaSide, mid.y + ny * distanceToCenter * sagittaSide)
            val startAngle = kotlin.math.atan2(start.y - center.y, start.x - center.x)
            val steps = maxOf(8, (kotlin.math.abs(theta) / (Math.PI / 18.0)).toInt())
            for (i in 1 until steps) {
                val angle = startAngle + theta * (i.toDouble() / steps.toDouble())
                points += Point2(center.x + radius * kotlin.math.cos(angle), center.y + radius * kotlin.math.sin(angle))
            }
            points += end
        }
        for (i in 0 until vertices.lastIndex) {
            appendSegment(vertices[i].first, vertices[i + 1].first, vertices[i].second)
        }
        if (closed && vertices.size > 2) {
            appendSegment(vertices.last().first, vertices.first().first, vertices.last().second)
        }
        return points
    }


    private fun parseEllipse(block: EntityBlock): DxfPolyline? {
        val center = Point2(block.doubleOrNull("10") ?: return null, block.doubleOrNull("20") ?: return null)
        val majorX = block.doubleOrNull("11") ?: return null
        val majorY = block.doubleOrNull("21") ?: return null
        val ratio = (block.doubleOrNull("40") ?: 1.0).coerceAtLeast(1e-6)
        val startParam = block.doubleOrNull("41") ?: 0.0
        val endParam = block.doubleOrNull("42") ?: (Math.PI * 2.0)
        var sweep = endParam - startParam
        if (sweep <= 0.0) sweep += Math.PI * 2.0
        if (kotlin.math.abs(sweep) < 1e-9) sweep = Math.PI * 2.0
        val majorLength = kotlin.math.sqrt(majorX * majorX + majorY * majorY).coerceAtLeast(1e-9)
        val minorX = -majorY / majorLength * majorLength * ratio
        val minorY = majorX / majorLength * majorLength * ratio
        val steps = maxOf(24, (kotlin.math.abs(sweep) / (Math.PI / 36.0)).toInt())
        val points = (0..steps).map { index ->
            val t = startParam + sweep * index.toDouble() / steps.toDouble()
            Point2(
                center.x + majorX * kotlin.math.cos(t) + minorX * kotlin.math.sin(t),
                center.y + majorY * kotlin.math.cos(t) + minorY * kotlin.math.sin(t)
            )
        }
        val closed = kotlin.math.abs(sweep - Math.PI * 2.0) < 1e-6
        return DxfPolyline(points, closed, block.single("8"))
    }

    private fun parseSpline(block: EntityBlock): DxfPolyline? {
        val fitPoints = collectPointPairs(block.entries, "11", "21")
        val controlPoints = collectPointPairs(block.entries, "10", "20")
        val points = when {
            fitPoints.size >= 2 -> fitPoints
            controlPoints.size >= 2 -> controlPoints
            else -> return null
        }
        return DxfPolyline(points, false, block.single("8"))
    }

    private fun parseHatch(block: EntityBlock): List<DxfEntity> {
        val result = mutableListOf<DxfEntity>()
        val entries = block.entries
        val layer = block.single("8")
        var index = 0
        while (index < entries.size) {
            if (entries[index].first != "92") {
                index++
                continue
            }
            val pathFlags = entries[index].second.toIntOrNull() ?: 0
            index++
            if (pathFlags and 2 == 2) {
                var hasBulge = false
                var closed = false
                val vertices = mutableListOf<Pair<Point2, Double>>()
                var pendingX: Double? = null
                var pendingY: Double? = null
                var pendingBulge = 0.0
                fun flush() {
                    val x = pendingX ?: return
                    val y = pendingY ?: return
                    vertices += Point2(x, y) to pendingBulge
                    pendingX = null
                    pendingY = null
                    pendingBulge = 0.0
                }
                while (index < entries.size && entries[index].first != "92" && entries[index].first != "75" && entries[index].first != "76" && entries[index].first != "98") {
                    val (code, value) = entries[index]
                    when (code) {
                        "72" -> hasBulge = value.toIntOrNull() == 1
                        "73" -> closed = value.toIntOrNull() == 1
                        "10" -> {
                            flush()
                            pendingX = value.toDoubleOrNull()
                        }
                        "20" -> pendingY = value.toDoubleOrNull()
                        "42" -> if (hasBulge) pendingBulge = value.toDoubleOrNull() ?: 0.0
                    }
                    index++
                }
                flush()
                val points = flattenBulgedPolyline(vertices, closed)
                if (points.size >= 2) result += DxfPolyline(points, closed, layer)
            } else {
                val edgeCountIndex = entries.indexOfFirstFrom(index) { it.first == "93" }
                if (edgeCountIndex < 0) break
                val edgeCount = entries[edgeCountIndex].second.toIntOrNull() ?: 0
                index = edgeCountIndex + 1
                repeat(edgeCount) {
                    if (index >= entries.size || entries[index].first != "72") return@repeat
                    val edgeType = entries[index].second.toIntOrNull() ?: 0
                    index++
                    val edgeData = mutableListOf<Pair<String, String>>()
                    while (index < entries.size && entries[index].first != "72" && entries[index].first != "92" && entries[index].first != "75" && entries[index].first != "76" && entries[index].first != "98") {
                        edgeData += entries[index]
                        index++
                    }
                    when (edgeType) {
                        1 -> parseLine(EntityBlock(edgeData, 0))?.let(result::add)
                        2 -> parseHatchArc(edgeData, layer)?.let(result::add)
                        3, 4 -> parseHatchEllipse(edgeData, layer)?.let(result::add)
                    }
                }
            }
        }
        if (result.isNotEmpty()) return result
        val points = collectPointPairs(entries, "10", "20").distinct()
        return if (points.size >= 2) listOf(DxfPolyline(points, true, layer)) else emptyList()
    }

    private fun parseHatchArc(entries: List<Pair<String, String>>, layer: String?): DxfEntity? {
        val block = EntityBlock(entries, 0)
        val center = Point2(block.doubleOrNull("10") ?: return null, block.doubleOrNull("20") ?: return null)
        val radius = block.doubleOrNull("40") ?: return null
        val start = block.doubleOrNull("50") ?: 0.0
        val end = block.doubleOrNull("51") ?: 360.0
        return if ((block.single("73")?.toIntOrNull() ?: 1) == 1) {
            DxfArc(center, radius, start, end, layer)
        } else {
            DxfArc(center, radius, end, start, layer)
        }
    }

    private fun parseHatchEllipse(entries: List<Pair<String, String>>, layer: String?): DxfPolyline? {
        val source = EntityBlock(entries + ("8" to (layer ?: "")), 0)
        return parseEllipse(source)
    }

    private fun collectPointPairs(entries: List<Pair<String, String>>, xCode: String, yCode: String): List<Point2> {
        val points = mutableListOf<Point2>()
        var pendingX: Double? = null
        entries.forEach { (code, value) ->
            when (code) {
                xCode -> pendingX = value.toDoubleOrNull()
                yCode -> {
                    val x = pendingX
                    val y = value.toDoubleOrNull()
                    if (x != null && y != null) points += Point2(x, y)
                    pendingX = null
                }
            }
        }
        return points
    }

    private inline fun <T> List<T>.indexOfFirstFrom(startIndex: Int, predicate: (T) -> Boolean): Int {
        for (i in startIndex until size) {
            if (predicate(this[i])) return i
        }
        return -1
    }

    private fun parseCircle(block: EntityBlock): DxfCircle? {
        val center = Point2(block.doubleOrNull("10") ?: return null, block.doubleOrNull("20") ?: return null)
        val radius = block.doubleOrNull("40") ?: return null
        return DxfCircle(center, radius, block.single("8"))
    }

    private fun parseArc(block: EntityBlock): DxfArc? {
        val center = Point2(block.doubleOrNull("10") ?: return null, block.doubleOrNull("20") ?: return null)
        val radius = block.doubleOrNull("40") ?: return null
        val start = block.doubleOrNull("50") ?: 0.0
        val end = block.doubleOrNull("51") ?: 0.0
        return DxfArc(center, radius, start, end, block.single("8"))
    }

    /** Convert MTEXT/TEXT to readable form: strip formatting codes. */
    /** Convert MTEXT/TEXT to readable form: strip AutoCAD formatting codes. */
    /** Convert MTEXT/TEXT to readable form: strip AutoCAD formatting codes. */
    /** Convert MTEXT/TEXT to readable form: strip AutoCAD formatting codes. */
    /** Convert MTEXT/TEXT to readable form: strip AutoCAD formatting codes. */
    /** Public wrapper: clean text for UI display (no MTEXT codes). */
    fun plainTextForDisplay(raw: String): String = plainText(raw)

    private fun plainText(raw: String): String {
        var t = raw
        // Stacking \S top^bottom; -> top/bottom (empty bottom -> just top)
        t = t.replace(Regex("""\\S([^;{}]*);""")) { m ->
            val body = m.groupValues[1]
            val parts = body.split("^")
            val top = parts.firstOrNull().orEmpty().trim()
            val bottom = parts.getOrNull(1).orEmpty().trim()
            if (bottom.isEmpty()) { if (top.equals("о", true) || top.equals("o", true)) "°" else top } else top + "/" + bottom
        }
        // Paragraph line break \P
        t = t.replace("""\P""", "\n")
        // Commands with argument up to ';' (\f..;, \W..;, \H..;, \pqc;, \pt..;)
        t = t.replace(Regex("""\\[A-Za-z][^;\\{}]*;"""), "")
        // \A + attachment code: often without ';' (\A1{...}) — otherwise the digit stays in text
        t = t.replace(Regex("""\\A[0-9]*"""), "")
        // Single commands without ';'
        t = t.replace(Regex("""\\[A-Za-z]"""), "")
        // MTEXT paragraph grouping braces
        t = t.replace("{", "").replace("}", "")
        // AutoCAD special symbols
        t = t.replace("%%d", "°").replace("%%D", "°")
            .replace("%%c", "Ø").replace("%%C", "Ø")
            .replace("%%p", "±").replace("%%P", "±")
        t = t.replace("~", " ")
        return t.trim()
    }
    private fun parseText(block: EntityBlock, type: String): DxfText? {
        val position = Point2(block.doubleOrNull("10") ?: return null, block.doubleOrNull("20") ?: return null)
        val text = plainText(block.entries.filter { it.first == "3" || it.first == "1" }.joinToString("") { it.second })
            .ifBlank { return null }
        val height = block.doubleOrNull("40") ?: 12.0
        val rotation = block.doubleOrNull("50") ?: 0.0
        return DxfText(
            position = position,
            text = text,
            height = height,
            layer = block.single("8"),
            rotationDegrees = rotation,
            dxfType = type,
            sourceHandle = block.single("5")
        )
    }

    private fun collectEntity(pairs: List<Pair<String, String>>, startIndex: Int): EntityBlock {
        val entries = mutableListOf<Pair<String, String>>()
        var index = startIndex
        while (index < pairs.size) {
            val pair = pairs[index]
            if (pair.first == "0") {
                break
            }
            entries += pair
            index++
        }
        return EntityBlock(entries, index)
    }

    private fun collectPolyline(pairs: List<Pair<String, String>>, startIndex: Int): PolylineBlock {
        val entries = mutableListOf<Pair<String, String>>()
        var index = startIndex
        while (index < pairs.size) {
            val pair = pairs[index]
            entries += pair
            if (pair.first == "0" && pair.second == "SEQEND") {
                index++
                break
            }
            index++
        }
        return PolylineBlock(entries, index)
    }

    private fun parseHeaderInfo(pairs: List<Pair<String, String>>): DxfHeaderInfo {
        var inHeader = false
        val values = mutableMapOf<String, String>()
        var currentVariable: String? = null
        var index = 0
        while (index < pairs.size) {
            val (code, value) = pairs[index]
            if (code == "0" && value == "SECTION" && index + 1 < pairs.size && pairs[index + 1] == ("2" to "HEADER")) {
                inHeader = true
                index += 2
                continue
            }
            if (inHeader && code == "0" && value == "ENDSEC") {
                break
            }
            if (inHeader) {
                when (code) {
                    "9" -> currentVariable = value
                    else -> currentVariable?.let { variable ->
                        values.putIfAbsent("$variable:$code", value)
                    }
                }
            }
            index++
        }
        val minX = values["\$EXTMIN:10"]?.toDoubleOrNull()
        val minY = values["\$EXTMIN:20"]?.toDoubleOrNull()
        val maxX = values["\$EXTMAX:10"]?.toDoubleOrNull()
        val maxY = values["\$EXTMAX:20"]?.toDoubleOrNull()
        // AutoCAD writes "not computed" sentinel extents ($EXTMIN=1e20, $EXTMAX=-1e20)
        // into files saved without regenerating the drawing. Accept only sane,
        // correctly-ordered bounds; otherwise fall back to entity-derived bounds.
        val bounds = if (
            listOf(minX, minY, maxX, maxY).all { it != null } &&
            abs(maxX!! - minX!!) > 0.0 &&
            abs(maxY!! - minY!!) > 0.0 &&
            minX < maxX && minY < maxY &&
            listOf(minX, minY, maxX, maxY).all { abs(it!!) < 1.0e15 }
        ) {
            RectBounds(minX, minY, maxX, maxY)
        } else {
            null
        }
        val units = values["\$INSUNITS:70"]?.toIntOrNull()?.let(::insUnitsName)
        return DxfHeaderInfo(bounds = bounds, units = units)
    }

    private fun parseLayerInfos(pairs: List<Pair<String, String>>): List<DxfLayerInfo> {
        val layers = mutableListOf<DxfLayerInfo>()
        var inLayerTable = false
        var index = 0
        while (index < pairs.size) {
            val (code, value) = pairs[index]
            if (!inLayerTable && code == "0" && value == "TABLE" && index + 1 < pairs.size && pairs[index + 1] == ("2" to "LAYER")) {
                inLayerTable = true
                index += 2
                continue
            }
            if (inLayerTable && code == "0" && value == "ENDTAB") {
                break
            }
            if (inLayerTable && code == "0" && value == "LAYER") {
                val block = collectEntity(pairs, index + 1)
                val name = block.single("2").orEmpty()
                if (name.isNotBlank()) {
                    val flags = block.single("70")?.toIntOrNull() ?: 0
                    val color = block.single("62")?.toIntOrNull()
                    layers += DxfLayerInfo(
                        name = name,
                        colorIndex = color,
                        lineType = block.single("6"),
                        isOff = (color ?: 7) < 0,
                        isFrozen = flags and 1 == 1
                    )
                }
                index = block.nextIndex
                continue
            }
            index++
        }
        return layers.distinctBy { it.name }.sortedBy { it.name }
    }

    private data class RawDxfText(val text: String, val charset: Charset)

    private fun readRawDxf(uri: Uri): RawDxfText {
        val bytes = activity.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { activity.getString(R.string.s0476) }
            input.readBytes()
        }
        val fallbackCharsets = listOf(
            Charset.forName("UTF-8"),
            Charset.forName("UTF-16LE"),
            Charset.forName("UTF-16BE"),
            Charset.forName("GB18030"),
            Charset.forName("GBK"),
            Charsets.ISO_8859_1
        )
        val bomCharset = detectBomCharset(bytes)
        val charsets = listOfNotNull(bomCharset) + fallbackCharsets.filterNot { it == bomCharset }
        charsets.forEach { charset ->
            val decoded = bytes.toString(charset)
            if (looksLikeStructuredText(decoded.split(Regex("\\r\\n|\\n|\\r"))) &&
                decoded.contains("\n") && decoded.contains("SECTION")
            ) {
                return RawDxfText(decoded.removePrefix("\uFEFF"), charset)
            }
        }
        return RawDxfText(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"), Charsets.UTF_8)
    }

    private fun readTextLines(uri: Uri): List<String> {
        val bytes = activity.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { activity.getString(R.string.s0477) }
            input.readBytes()
        }
        val fallbackCharsets = listOf(
            Charset.forName("UTF-8"),
            Charset.forName("UTF-16LE"),
            Charset.forName("UTF-16BE"),
            Charset.forName("GB18030"),
            Charset.forName("GBK"),
            Charsets.ISO_8859_1
        )
        val bomCharset = detectBomCharset(bytes)
        val charsets = listOfNotNull(bomCharset) + fallbackCharsets.filterNot { it == bomCharset }
        charsets.forEach { charset ->
            val lines = BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), charset)).readLines()
            if (looksLikeStructuredText(lines)) {
                return lines.map { it.removePrefix("\uFEFF") }
            }
        }
        return BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), Charset.forName("UTF-8"))).readLines()
    }

    private fun readHeader(uri: Uri, count: Int): String {
        activity.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { activity.getString(R.string.s0477) }
            val bytes = ByteArray(count)
            val size = input.read(bytes)
            if (size <= 0) return ""
            return bytes.copyOf(size).toString(Charsets.US_ASCII).replace("\u0000", "").trim()
        }
    }

    private fun readHeadBytes(uri: Uri, count: Int): ByteArray {
        activity.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { activity.getString(R.string.s0477) }
            val bytes = ByteArray(count)
            var total = 0
            while (total < count) {
                val size = input.read(bytes, total, count - total)
                if (size <= 0) break
                total += size
            }
            return if (total == count) bytes else bytes.copyOf(total)
        }
    }
    private fun peekText(uri: Uri): String {
        return readTextLines(uri).firstOrNull().orEmpty()
    }

    private fun detectBomCharset(bytes: ByteArray): Charset? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return Charset.forName("UTF-8")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charset.forName("UTF-16LE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charset.forName("UTF-16BE")
        }
        return null
    }

    private fun looksLikeStructuredText(lines: List<String>): Boolean {
        if (lines.isEmpty()) return false
        val sample = lines.take(24).joinToString("\n").uppercase()
        return sample.contains("SECTION") ||
            sample.contains("ENTITIES") ||
            sample.contains("HEADER") ||
            sample.contains("GENERAL_DATA") ||
            sample.contains("END_OF_")
    }

    private fun insUnitsName(code: Int): String {
        return when (code) {
            0 -> activity.getString(R.string.s0478)
            1 -> activity.getString(R.string.s0479)
            2 -> activity.getString(R.string.s0480)
            3 -> activity.getString(R.string.s0481)
            4 -> activity.getString(R.string.s0482)
            5 -> activity.getString(R.string.s0483)
            6 -> activity.getString(R.string.s0484)
            7 -> activity.getString(R.string.s0485)
            8 -> activity.getString(R.string.s0486)
            9 -> activity.getString(R.string.s0487)
            10 -> activity.getString(R.string.s0488)
            11 -> activity.getString(R.string.s0489)
            12 -> activity.getString(R.string.s0490)
            13 -> activity.getString(R.string.s0491)
            14 -> activity.getString(R.string.s0492)
            15 -> activity.getString(R.string.s0493)
            16 -> activity.getString(R.string.s0494)
            17 -> activity.getString(R.string.s0495)
            18 -> activity.getString(R.string.s0496)
            19 -> activity.getString(R.string.s0497)
            20 -> activity.getString(R.string.s0498)
            21 -> activity.getString(R.string.s0499)
            else -> activity.getString(R.string.s0500, code)
        }
    }
}

private data class EntityBlock(
    val entries: List<Pair<String, String>>,
    val nextIndex: Int
) {
    fun single(code: String): String? = entries.firstOrNull { it.first == code }?.second
    fun all(code: String): List<String> = entries.filter { it.first == code }.map { it.second }
    fun doubleOrNull(code: String): Double? = single(code)?.toDoubleOrNull()
}

private data class PolylineBlock(
    val entries: List<Pair<String, String>>,
    val nextIndex: Int
)

private data class DxfHeaderInfo(
    val bounds: RectBounds?,
    val units: String?
)
