package com.sikkatu.sikkatucad

/**
 * Applies text-only edits to the original DXF stream.
 *
 * The viewer/parser intentionally has a simplified geometry model.  It must
 * therefore never be used as the source for a DXF round-trip: doing so drops
 * entities and sections the viewer does not understand.  This helper instead
 * keeps the original DXF and changes only TEXT/MTEXT payloads.
 */
object DxfLosslessEditor {

    fun applyTextEdits(
        originalRaw: String?,
        after: DxfDocument
    ): String? {
        if (originalRaw == null) return null
        val newTexts = after.entities.filterIsInstance<DxfText>()
        if (newTexts.isEmpty()) return originalRaw

        val newline = when {
            originalRaw.contains("\r\n") -> "\r\n"
            originalRaw.contains("\r") -> "\r"
            else -> "\n"
        }
        val lines = originalRaw.split(Regex("\r\n|\n|\r")).toMutableList()
        val blocks = findTextBlocks(lines)
        val changed = blocksChangedAgainstRaw(newTexts, blocks)
        if (changed.isEmpty()) return originalRaw
        val used = mutableSetOf<Int>()
        changed.forEach { newer ->
            val blockIndex = blocks.indexOfFirst { block ->
                if (block.index in used) return@indexOfFirst false
                val handle = block.handle
                if (newer.sourceHandle != null) {
                    handle == newer.sourceHandle
                } else {
                    block.type.equals(newer.dxfType, true) &&
                        samePosition(block, newer)
                }
            }
            if (blockIndex < 0) return@forEach
            val block = blocks[blockIndex]
            if (block.payload() != newer.text) {
                replacePayload(lines, block, newer.text)
                used += blockIndex
            }
        }

        return lines.joinToString(newline)
    }

    private data class TextBlock(
        val index: Int,
        val start: Int,
        val endExclusive: Int,
        val type: String,
        val handle: String?,
        val entries: List<Triple<Int, String, String>>
    )

    private fun blocksChangedAgainstRaw(texts: List<DxfText>, blocks: List<TextBlock>): List<DxfText> {
        return texts.filter { text ->
            val block = blocks.firstOrNull {
                if (text.sourceHandle != null) it.handle == text.sourceHandle
                else it.type.equals(text.dxfType, true) && samePosition(it, text)
            }
            block != null && block.payload() != text.text
        }
    }

    private fun samePosition(block: TextBlock, text: DxfText): Boolean {
        val x = block.value("10")?.toDoubleOrNull()
        val y = block.value("20")?.toDoubleOrNull()
        return x != null && y != null &&
            kotlin.math.abs(x - text.position.x) < 1e-5 &&
            kotlin.math.abs(y - text.position.y) < 1e-5
    }

    private fun findTextBlocks(lines: List<String>): List<TextBlock> {
        val result = mutableListOf<TextBlock>()
        var i = 0
        while (i + 1 < lines.size) {
            if (lines[i].trim() == "0") {
                val type = lines[i + 1].trim()
                if (type.equals("TEXT", true) || type.equals("MTEXT", true)) {
                    var end = i + 2
                    while (end + 1 < lines.size && lines[end].trim() != "0") end += 2
                    val entries = mutableListOf<Triple<Int, String, String>>()
                    var p = i + 2
                    while (p + 1 < end) {
                        entries += Triple(p, lines[p].trim(), lines[p + 1])
                        p += 2
                    }
                    result += TextBlock(
                        index = result.size,
                        start = i,
                        endExclusive = end,
                        type = type,
                        handle = entries.firstOrNull { it.second == "5" }?.third?.trim(),
                        entries = entries
                    )
                    i = end
                    continue
                }
            }
            i++
        }
        return result
    }

    private fun TextBlock.value(code: String): String? =
        entries.firstOrNull { it.second == code }?.third

    private fun TextBlock.payload(): String =
        entries.filter { it.second == "1" || it.second == "3" }
            .joinToString("") { it.third }

    private fun replacePayload(lines: MutableList<String>, block: TextBlock, newText: String) {
        val payload = block.entries.filter { it.second == "1" || it.second == "3" }
        if (payload.isEmpty()) {
            val insertAt = block.endExclusive
            val chunks = splitMTextPayload(newText)
            lines.add(insertAt, "1")
            lines.add(insertAt + 1, chunks.first())
            var at = insertAt + 2
            chunks.drop(1).forEach { chunk ->
                lines.add(at, "3")
                lines.add(at + 1, chunk)
                at += 2
            }
            return
        }

        val first = payload.first().first
        val positions = payload.map { it.first }.sortedDescending()
        positions.forEach { idx ->
            lines.removeAt(idx + 1)
            lines.removeAt(idx)
        }

        val chunks = splitMTextPayload(newText)
        var insertAt = first - positions.count { it < first } * 2
        chunks.forEachIndexed { index, chunk ->
            lines.add(insertAt, if (index == chunks.lastIndex) "1" else "3")
            lines.add(insertAt + 1, chunk)
            insertAt += 2
        }
    }

    /**
     * DXF MTEXT uses group 3 for continuation chunks and group 1 for the final
     * chunk.  Keep each physical value within the traditional 250-character
     * limit so large edited labels remain valid DXF.
     */
    private fun splitMTextPayload(text: String, maxChunk: Int = 250): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            val end = minOf(offset + maxChunk, text.length)
            result += text.substring(offset, end)
            offset = end
        }
        return result
    }

}
