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
            "gen" -> ParsedCadFile.Gen(parseGen(uri, fileName))
            "dxf" -> parseDxfByContent(uri, fileName)
            "pdf" -> throw IOException(activity.getString(R.string.s0005))
            "nc", "tap", "cnc", "mpf" -> {
                val program = parseNc(uri, fileName)
                ParsedCadFile.Nc(program, buildNcPreview(program))
            }
            "dwg" -> ParsedCadFile.Dwg(parseDwg(uri, fileName), convertDwgToDocument(uri, fileName))
            else -> {
                val header = readHeader(uri, 6)
                when {
                    readHeader(uri, 4) == "%PDF" -> throw IOException(activity.getString(R.string.s0005))
                    header.startsWith("AC10") -> ParsedCadFile.Dwg(parseDwg(uri, fileName), convertDwgToDocument(uri, fileName))
                    peekText(uri).startsWith("GENERAL_DATA") -> ParsedCadFile.Gen(parseGen(uri, fileName))
                    peekText(uri).trimStart().startsWith(";") || peekText(uri).contains("G0") -> {
                        val program = parseNc(uri, fileName)
                        ParsedCadFile.Nc(program, buildNcPreview(program))
                    }
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

    private fun parseGen(uri: Uri, fileName: String): GenDocument {
        val lines = readTextLines(uri)
        val sections = mutableListOf<GenSection>()
        val generalData = linkedMapOf<String, String>()
        val partSummaries = mutableListOf<GenPartSummary>()
        val operations = mutableListOf<GenOperation>()
        var currentTitle: String? = null
        val currentEntries = mutableListOf<Pair<String, String>>()
        var currentPartName: String? = null
        var currentWorkType: String? = null
        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            val line = raw.trimEnd()
            if (line.isBlank()) {
                index++
                continue
            }
            if (line == "PART_INFORMATION") {
                val infoEntries = collectEntriesUntil(lines, index + 1, "END_OF_PART_INFORMATION")
                currentPartName = infoEntries["PART_NAME"]?.takeIf { it.isNotBlank() }
                currentWorkType = infoEntries["TYPE_OF_WORK"]?.takeIf { it.isNotBlank() }
                sections += GenSection("PART_INFORMATION", infoEntries.filterKeys { it != "__NEXT_INDEX__" }.entries.map { it.toPair() })
                index = infoEntries["__NEXT_INDEX__"]?.toIntOrNull() ?: (index + 1)
                continue
            }
            if (line == "IDLE_DATA" || line == "MARKING_DATA" || line == "BURNING_DATA") {
                val parsedBlock = parseOperationBlock(
                    lines = lines,
                    startIndex = index,
                    blockTitle = line,
                    partName = currentPartName,
                    currentWorkType = currentWorkType
                )
                operations += parsedBlock.operation
                sections += GenSection(line, parsedBlock.sectionEntries)
                index = parsedBlock.nextIndex
                continue
            }
            if (!line.contains("=") && !line.startsWith("END_OF_")) {
                if (currentTitle != null && currentEntries.isNotEmpty()) {
                    sections += GenSection(currentTitle, currentEntries.toList())
                    when (currentTitle) {
                        "GENERAL_DATA" -> generalData.putAll(currentEntries.toMap())
                        "PART_DATA" -> partSummaries += currentEntries.toGenPartSummary()
                    }
                    currentEntries.clear()
                }
                currentTitle = line
                index++
                continue
            }
            if (line.startsWith("END_OF_")) {
                if (currentTitle != null) {
                    sections += GenSection(currentTitle, currentEntries.toList())
                    when (currentTitle) {
                        "GENERAL_DATA" -> generalData.putAll(currentEntries.toMap())
                        "PART_DATA" -> partSummaries += currentEntries.toGenPartSummary()
                    }
                }
                currentTitle = null
                currentEntries.clear()
                index++
                continue
            }
            val splitIndex = line.indexOf('=')
            val key = line.substring(0, splitIndex)
            val value = line.substring(splitIndex + 1)
            currentEntries += key to value
            index++
        }
        if (currentTitle != null && currentEntries.isNotEmpty()) {
            sections += GenSection(currentTitle, currentEntries.toList())
            when (currentTitle) {
                "GENERAL_DATA" -> generalData.putAll(currentEntries.toMap())
                "PART_DATA" -> partSummaries += currentEntries.toGenPartSummary()
            }
        }
        return GenDocument(fileName, sections, generalData, partSummaries, operations)
    }

    private fun List<Pair<String, String>>.toGenPartSummary(): GenPartSummary {
        val map = toMap()
        return GenPartSummary(
            name = map["NAME"].orEmpty(),
            mirrored = map["MIRRORED"] == "1",
            extensionU = map["EXTENSION_U"]?.toDoubleOrNull(),
            extensionV = map["EXTENSION_V"]?.toDoubleOrNull(),
            cogU = map["PART_COG_U"]?.toDoubleOrNull(),
            cogV = map["PART_COG_V"]?.toDoubleOrNull()
        )
    }

    private fun collectEntriesUntil(lines: List<String>, startIndex: Int, terminator: String): MutableMap<String, String> {
        val entries = linkedMapOf<String, String>()
        var index = startIndex
        while (index < lines.size) {
            val line = lines[index].trimEnd()
            if (line == terminator) {
                entries["__NEXT_INDEX__"] = (index + 1).toString()
                return entries
            }
            val split = line.indexOf('=')
            if (split >= 0) {
                entries[line.substring(0, split)] = line.substring(split + 1)
            }
            index++
        }
        entries["__NEXT_INDEX__"] = lines.size.toString()
        return entries
    }

    private fun parseOperationBlock(
        lines: List<String>,
        startIndex: Int,
        blockTitle: String,
        partName: String?,
        currentWorkType: String?
    ): ParsedGenBlock {
        val terminator = "END_OF_$blockTitle"
        val sectionEntries = mutableListOf<Pair<String, String>>()
        val contours = mutableListOf<GenContour>()
        val metadata = linkedMapOf<String, String>()
        var shape: String? = null
        var bevel: GenBevel? = null
        var index = startIndex + 1
        while (index < lines.size) {
            val line = lines[index].trimEnd()
            if (line == terminator) {
                return ParsedGenBlock(
                    operation = GenOperation(
                        type = when (blockTitle) {
                            "IDLE_DATA" -> GenOperationType.IDLE
                            "MARKING_DATA" -> GenOperationType.MARKING
                            else -> GenOperationType.BURNING
                        },
                        partName = partName,
                        shape = shape,
                        label = metadata["MARKING_NAME"] ?: currentWorkType,
                        contours = contours,
                        bevel = bevel
                    ),
                    sectionEntries = sectionEntries,
                    nextIndex = index + 1
                )
            }
            if (line == "START_OF_CONTOUR") {
                val parsedContour = parseContour(lines, index + 1)
                contours += parsedContour.contour
                sectionEntries += parsedContour.sectionEntries
                bevel = parsedContour.contour.bevel ?: bevel
                index = parsedContour.nextIndex
                continue
            }
            if (line.contains("=")) {
                val split = line.indexOf('=')
                val key = line.substring(0, split)
                val value = line.substring(split + 1)
                sectionEntries += key to value
                metadata[key] = value
                if (key == "SHAPE") {
                    shape = value
                }
            }
            index++
        }
        return ParsedGenBlock(
            operation = GenOperation(
                type = GenOperationType.IDLE,
                partName = partName,
                shape = shape,
                label = currentWorkType,
                contours = contours,
                bevel = bevel
            ),
            sectionEntries = sectionEntries,
            nextIndex = lines.size
        )
    }

    private fun parseContour(lines: List<String>, startIndex: Int): ParsedGenContour {
        val sectionEntries = mutableListOf<Pair<String, String>>()
        val segmentMaps = mutableListOf<MutableMap<String, String>>()
        var currentSegment = linkedMapOf<String, String>()
        var bevel: GenBevel? = null
        var startU = 0.0
        var startV = 0.0
        var index = startIndex
        while (index < lines.size) {
            val line = lines[index].trimEnd()
            if (line == "END_OF_CONTOUR") {
                if (currentSegment.containsKey("U") && currentSegment.containsKey("V")) {
                    segmentMaps += currentSegment
                }
                return ParsedGenContour(
                    contour = GenContour(
                        start = Point2(startU, startV),
                        segments = segmentMaps.map { segment ->
                            GenContourSegment(
                                end = Point2(
                                    segment["U"]?.toDoubleOrNull() ?: startU,
                                    segment["V"]?.toDoubleOrNull() ?: startV
                                ),
                                amp = if (
                                    (segment["AMP_U"]?.toDoubleOrNull() ?: 0.0) != 0.0 ||
                                    (segment["AMP_V"]?.toDoubleOrNull() ?: 0.0) != 0.0
                                ) {
                                    Point2(
                                        segment["AMP_U"]?.toDoubleOrNull() ?: 0.0,
                                        segment["AMP_V"]?.toDoubleOrNull() ?: 0.0
                                    )
                                } else null,
                                radius = segment["RADIUS"]?.toDoubleOrNull() ?: 0.0,
                                sweepRadians = segment["SWEEP"]?.toDoubleOrNull() ?: 0.0,
                                origin = if (
                                    (segment["ORIGIN_U"]?.toDoubleOrNull() ?: 0.0) != 0.0 ||
                                    (segment["ORIGIN_V"]?.toDoubleOrNull() ?: 0.0) != 0.0
                                ) {
                                    Point2(
                                        segment["ORIGIN_U"]?.toDoubleOrNull() ?: 0.0,
                                        segment["ORIGIN_V"]?.toDoubleOrNull() ?: 0.0
                                    )
                                } else null
                            )
                        },
                        bevel = bevel
                    ),
                    sectionEntries = sectionEntries,
                    nextIndex = index + 1
                )
            }
            if (line == "BEVEL_DATA") {
                val bevelEntries = collectEntriesUntil(lines, index + 1, "END_OF_BEVEL_DATA")
                bevel = GenBevel(
                    bevel = bevelEntries["BEVEL"].orEmpty().ifBlank { "NONE" },
                    bevelCode = bevelEntries["BEVEL_CODE"]?.toDoubleOrNull(),
                    bevelType = bevelEntries["BEVEL_TYPE"]?.toIntOrNull(),
                    bevelVariant = bevelEntries["BEVEL_VARIANT"]?.toIntOrNull(),
                    chamferWidthTs = bevelEntries["CHAMFER_WIDTH_TS"]?.toDoubleOrNull(),
                    chamferWidthOs = bevelEntries["CHAMFER_WIDTH_OS"]?.toDoubleOrNull(),
                    chamferHeightTs = bevelEntries["CHAMFER_HEIGHT_TS"]?.toDoubleOrNull(),
                    chamferHeightOs = bevelEntries["CHAMFER_HEIGHT_OS"]?.toDoubleOrNull()
                )
                bevelEntries
                    .filterKeys { it != "__NEXT_INDEX__" }
                    .forEach { (key, value) -> sectionEntries += key to value }
                index = bevelEntries["__NEXT_INDEX__"]?.toIntOrNull() ?: (index + 1)
                continue
            }
            if (line.contains("=")) {
                val split = line.indexOf('=')
                val key = line.substring(0, split)
                val value = line.substring(split + 1)
                sectionEntries += key to value
                when (key) {
                    "START_U" -> startU = value.toDoubleOrNull() ?: startU
                    "START_V" -> startV = value.toDoubleOrNull() ?: startV
                    "NO_OF_SEG" -> Unit
                    else -> {
                        currentSegment[key] = value
                        if (key == "V" && currentSegment.containsKey("U")) {
                            segmentMaps += currentSegment
                            currentSegment = linkedMapOf()
                        }
                    }
                }
            }
            index++
        }
        return ParsedGenContour(
            contour = GenContour(Point2(startU, startV), emptyList(), bevel),
            sectionEntries = sectionEntries,
            nextIndex = lines.size
        )
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

    private fun parseNc(uri: Uri, fileName: String): NcProgram {
        val lines = readTextLines(uri)
        val header = linkedMapOf<String, String>()
        val moves = mutableListOf<NcMove>()
        var current = Point2(0.0, 0.0)
        var motion = "G00"
        var cuttingActive = false
        lines.forEach { raw ->
            val line = raw.substringBefore(';').trim()
            if (raw.trimStart().startsWith(";")) {
                val body = raw.trim().removePrefix(";").trim()
                val split = body.indexOf('=')
                if (split > 0) header[body.substring(0, split).trim()] = body.substring(split + 1).trim().trim('"')
                return@forEach
            }
            if (line.isBlank()) return@forEach
            val upperLine = line.uppercase()
            val hasCutStart = upperLine.contains("M150")
            val hasCutEnd = upperLine.contains("M159")
            val tokens = Regex("[A-Z][-+]?\\d*\\.?\\d+|G\\d+").findAll(line.uppercase()).map { it.value }.toList()
            if (tokens.isEmpty()) return@forEach
            var x: Double? = null
            var y: Double? = null
            var i: Double? = null
            var j: Double? = null
            var a: Double? = null
            var b: Double? = null
            tokens.forEach { token ->
                when {
                    token.startsWith("G") -> motion = token
                    token.startsWith("X") -> x = token.substring(1).toDoubleOrNull()
                    token.startsWith("Y") -> y = token.substring(1).toDoubleOrNull()
                    token.startsWith("I") -> i = token.substring(1).toDoubleOrNull()
                    token.startsWith("J") -> j = token.substring(1).toDoubleOrNull()
                    token.startsWith("A") -> a = token.substring(1).toDoubleOrNull()
                    token.startsWith("B") -> b = token.substring(1).toDoubleOrNull()
                }
            }
            if (x == null && y == null) return@forEach
            val next = Point2(x ?: current.x, y ?: current.y)
            val cutting = hasCutStart || (cuttingActive && !hasCutEnd)
            moves += NcMove(
                start = current,
                end = next,
                rapid = motion in setOf("G0", "G00"),
                centerOffset = if (i != null || j != null) Point2(i ?: 0.0, j ?: 0.0) else null,
                clockwise = motion in setOf("G2", "G02"),
                bevelA = a,
                bevelB = b,
                cutting = cutting
            )
            cuttingActive = when {
                hasCutEnd -> false
                hasCutStart -> true
                else -> cuttingActive
            }
            current = next
        }
        return NcProgram(fileName = fileName, header = header, moves = moves.filter { it.start != it.end })
    }

    private fun buildNcPreview(program: NcProgram): DxfDocument {
        val entities = mutableListOf<DxfEntity>()
        val cutMoves = program.moves.filter { it.cutting }
        inferNcCutMoves(cutMoves).forEach { move ->
            appendNcMoveEntity(entities, move, "NC_CUT")
        }
        program.moves.forEach { move ->
            if (move.rapid) {
                appendNcMoveEntity(entities, move, "NC_RAPID")
            }
        }
        cutMoves.forEach { move ->
            if (move.rapid) return@forEach
            val baseMove = move.copy(bevelA = null, bevelB = null)
            ncBevelLayers(move).forEachIndexed { index, (layer, bevelValue) ->
                appendNcMoveEntity(entities, baseMove, layer)
                val label = when {
                    layer.contains("A_") -> "A${if (bevelValue > 0) "+" else ""}${"%.0f".format(bevelValue)}"
                    else -> "B${if (bevelValue > 0) "+" else ""}${"%.0f".format(bevelValue)}"
                }
                val midX = (baseMove.start.x + baseMove.end.x) / 2.0
                val midY = (baseMove.start.y + baseMove.end.y) / 2.0
                val textOffset = 8.0 * index
                entities += DxfText(
                    position = Point2(midX, midY + textOffset),
                    text = label,
                    height = 8.0,
                    layer = "NC_BEVEL_LABEL"
                )
            }
        }
        program.header["SheetX"]?.toDoubleOrNull()?.let { w ->
            val h = program.header["SheetY"]?.toDoubleOrNull() ?: return@let
            entities += DxfPolyline(listOf(Point2(0.0, 0.0), Point2(w, 0.0), Point2(w, h), Point2(0.0, h)), true, "NC_SHEET")
        }
        return DxfDocument(fileName = program.fileName, entities = entities, units = activity.getString(R.string.s0482))
    }

    private fun inferNcCutMoves(moves: List<NcMove>): List<NcMove> {
        val baseMoves = moves.filter { !hasBevel(it) && it.centerOffset == null }
        if (baseMoves.isEmpty()) {
            return inferNcBevelPairs(moves).map { it.center }
        }
        val remaining = baseMoves.indices.toMutableSet()
        val result = mutableListOf<NcMove>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.first()
            val component = mutableListOf<Int>()
            val queue = ArrayDeque<Int>()
            queue += seed
            remaining -= seed
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                component += index
                val source = baseMoves[index]
                val iterator = remaining.iterator()
                val connected = mutableListOf<Int>()
                while (iterator.hasNext()) {
                    val candidateIndex = iterator.next()
                    if (areNcMovesConnected(source, baseMoves[candidateIndex])) {
                        connected += candidateIndex
                        iterator.remove()
                    }
                }
                connected.forEach(queue::addLast)
            }
            val group = component.map { baseMoves[it] }
            val minX = group.minOf { minOf(it.start.x, it.end.x) }
            val maxX = group.maxOf { maxOf(it.start.x, it.end.x) }
            val minY = group.minOf { minOf(it.start.y, it.end.y) }
            val maxY = group.maxOf { maxOf(it.start.y, it.end.y) }
            if (maxX - minX < 1.0 || maxY - minY < 1.0) {
                result += group.map { it.copy(bevelA = null, bevelB = null) }
                continue
            }
            result += NcMove(Point2(minX, maxY), Point2(maxX, maxY), rapid = false)
            result += NcMove(Point2(maxX, maxY), Point2(maxX, minY), rapid = false)
            result += NcMove(Point2(maxX, minY), Point2(minX, minY), rapid = false)
            result += NcMove(Point2(minX, minY), Point2(minX, maxY), rapid = false)
        }
        return result
    }

    private fun areNcMovesConnected(a: NcMove, b: NcMove, tolerance: Double = 0.6): Boolean {
        return pointDistance(a.start, b.start) <= tolerance ||
            pointDistance(a.start, b.end) <= tolerance ||
            pointDistance(a.end, b.start) <= tolerance ||
            pointDistance(a.end, b.end) <= tolerance
    }

    private fun pointDistance(a: Point2, b: Point2): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun inferNcBevelPairs(moves: List<NcMove>): List<NcPreviewPair> {
        val result = mutableListOf<NcPreviewPair>()
        val used = mutableSetOf<Int>()
        moves.forEachIndexed { index, move ->
            if (move.rapid) return@forEachIndexed
            if (!hasBevel(move)) {
                result += NcPreviewPair(center = move.copy(bevelA = null, bevelB = null))
                return@forEachIndexed
            }
            if (index in used || move.centerOffset != null) return@forEachIndexed
            val matchIndex = findMatchingBevelMove(moves, index)
            if (matchIndex != null) {
                used += index
                used += matchIndex
                val sourceFamily = bevelFamily(move) ?: return@forEachIndexed
                val sourceValue = bevelValueForFamily(move, sourceFamily) ?: 0.0
                val match = moves[matchIndex]
                val matchValue = bevelValueForFamily(match, sourceFamily) ?: 0.0
                result += NcPreviewPair(
                    center = midpointMove(move, match),
                    layers = listOf(
                        layerForBevelValue(sourceFamily, sourceValue),
                        layerForBevelValue(sourceFamily, matchValue)
                    ).filterNotNull(),
                    values = listOf(sourceValue, matchValue)
                )
            }
        }
        return result
    }

    private fun hasBevel(move: NcMove): Boolean = (move.bevelA ?: 0.0) != 0.0 || (move.bevelB ?: 0.0) != 0.0

    private fun findMatchingBevelMove(moves: List<NcMove>, index: Int): Int? {
        val source = moves[index]
        if (source.centerOffset != null) return null
        val sourceFamily = bevelFamily(source) ?: return null
        val sourceAngle = bevelValueForFamily(source, sourceFamily) ?: return null
        val tangent = normalizedDirection(source.start, source.end)
        val sourceLength = segmentLength(source)
        var bestIndex: Int? = null
        var bestDistance = Double.MAX_VALUE
        for (candidateIndex in index + 1 until moves.size) {
            val candidate = moves[candidateIndex]
            if (candidate.rapid || candidate.centerOffset != null) continue
            if (bevelFamily(candidate) != sourceFamily) continue
            val candidateAngle = bevelValueForFamily(candidate, sourceFamily) ?: continue
            if (sourceAngle * candidateAngle >= 0.0) continue
            if (kotlin.math.abs(kotlin.math.abs(sourceAngle) - kotlin.math.abs(candidateAngle)) > 0.5) continue
            val candidateTangent = normalizedDirection(candidate.start, candidate.end)
            val parallel = kotlin.math.abs(tangent.first * candidateTangent.first + tangent.second * candidateTangent.second)
            if (parallel < 0.995) continue
            val overlapRatio = tangentOverlapRatio(source, candidate, tangent)
            if (overlapRatio < 0.82) continue
            val offset = normalOffsetDistance(source, candidate, tangent)
            if (offset <= 0.01) continue
            val distance = offset + kotlin.math.abs(sourceLength - segmentLength(candidate)) * 0.25
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = candidateIndex
            }
        }
        return bestIndex
    }

    private fun midpointMove(a: NcMove, b: NcMove): NcMove {
        val aDir = normalizedDirection(a.start, a.end)
        val bDir = normalizedDirection(b.start, b.end)
        val alignedB = if (aDir.first * bDir.first + aDir.second * bDir.second >= 0.0) b else b.copy(start = b.end, end = b.start)
        return a.copy(
            start = Point2((a.start.x + alignedB.start.x) / 2.0, (a.start.y + alignedB.start.y) / 2.0),
            end = Point2((a.end.x + alignedB.end.x) / 2.0, (a.end.y + alignedB.end.y) / 2.0),
            centerOffset = null,
            bevelA = null,
            bevelB = null
        )
    }

    private fun normalizedDirection(start: Point2, end: Point2): Pair<Double, Double> {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001)
        return dx / length to dy / length
    }

    private fun segmentMidpointDistance(a: NcMove, b: NcMove): Double {
        val am = Point2((a.start.x + a.end.x) / 2.0, (a.start.y + a.end.y) / 2.0)
        val bm = Point2((b.start.x + b.end.x) / 2.0, (b.start.y + b.end.y) / 2.0)
        val dx = am.x - bm.x
        val dy = am.y - bm.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun segmentLength(move: NcMove): Double {
        val dx = move.end.x - move.start.x
        val dy = move.end.y - move.start.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun tangentOverlapRatio(a: NcMove, b: NcMove, tangent: Pair<Double, Double>): Double {
        val a0 = projectAlongTangent(a.start, tangent)
        val a1 = projectAlongTangent(a.end, tangent)
        val b0 = projectAlongTangent(b.start, tangent)
        val b1 = projectAlongTangent(b.end, tangent)
        val aMin = minOf(a0, a1)
        val aMax = maxOf(a0, a1)
        val bMin = minOf(b0, b1)
        val bMax = maxOf(b0, b1)
        val overlap = (minOf(aMax, bMax) - maxOf(aMin, bMin)).coerceAtLeast(0.0)
        val minLength = minOf(aMax - aMin, bMax - bMin).coerceAtLeast(0.0001)
        return overlap / minLength
    }

    private fun normalOffsetDistance(a: NcMove, b: NcMove, tangent: Pair<Double, Double>): Double {
        val normal = -tangent.second to tangent.first
        val aMid = Point2((a.start.x + a.end.x) / 2.0, (a.start.y + a.end.y) / 2.0)
        val bMid = Point2((b.start.x + b.end.x) / 2.0, (b.start.y + b.end.y) / 2.0)
        return kotlin.math.abs((bMid.x - aMid.x) * normal.first + (bMid.y - aMid.y) * normal.second)
    }

    private fun projectAlongTangent(point: Point2, tangent: Pair<Double, Double>): Double {
        return point.x * tangent.first + point.y * tangent.second
    }

    private fun bevelFamily(move: NcMove): String? {
        return when {
            (move.bevelA ?: 0.0) != 0.0 -> "A"
            (move.bevelB ?: 0.0) != 0.0 -> "B"
            else -> null
        }
    }

    private fun bevelValueForFamily(move: NcMove, family: String): Double? {
        return when (family) {
            "A" -> move.bevelA
            "B" -> move.bevelB
            else -> null
        }
    }

    private fun layerForBevelValue(family: String, value: Double): String? {
        return when (family) {
            "A" -> if (value >= 0.0) "NC_BEVEL_A_POS" else "NC_BEVEL_A_NEG"
            "B" -> if (value >= 0.0) "NC_BEVEL_B_POS" else "NC_BEVEL_B_NEG"
            else -> null
        }
    }

    private fun appendNcMoveEntity(entities: MutableList<DxfEntity>, move: NcMove, layer: String) {
        if (move.centerOffset != null) {
            val center = Point2(move.start.x + move.centerOffset.x, move.start.y + move.centerOffset.y)
            val radius = kotlin.math.sqrt(move.centerOffset.x * move.centerOffset.x + move.centerOffset.y * move.centerOffset.y)
            val startAngle = Math.toDegrees(kotlin.math.atan2(move.start.y - center.y, move.start.x - center.x))
            val endAngle = Math.toDegrees(kotlin.math.atan2(move.end.y - center.y, move.end.x - center.x))
            entities += DxfArc(center, radius, startAngle, endAngle, layer)
        } else {
            entities += DxfLine(move.start, move.end, layer)
        }
    }

    private fun ncBevelLayers(move: NcMove): List<Pair<String, Double>> {
        val layers = mutableListOf<Pair<String, Double>>()
        (move.bevelA ?: 0.0).takeIf { it != 0.0 }?.let {
            layers += (if (it > 0.0) "NC_BEVEL_A_POS" else "NC_BEVEL_A_NEG") to it
        }
        (move.bevelB ?: 0.0).takeIf { it != 0.0 }?.let {
            layers += (if (it > 0.0) "NC_BEVEL_B_POS" else "NC_BEVEL_B_NEG") to it
        }
        return layers
    }

    private data class NcPreviewPair(
        val center: NcMove,
        val layers: List<String> = emptyList(),
        val values: List<Double> = emptyList()
    )


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

private data class ParsedGenBlock(
    val operation: GenOperation,
    val sectionEntries: List<Pair<String, String>>,
    val nextIndex: Int
)

private data class ParsedGenContour(
    val contour: GenContour,
    val sectionEntries: List<Pair<String, String>>,
    val nextIndex: Int
)
