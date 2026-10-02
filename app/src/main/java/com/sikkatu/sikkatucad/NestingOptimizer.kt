package com.sikkatu.sikkatucad

/**
 * Nesting optimizer - provides optimization suggestions and auto sheet splitting
 */
object NestingOptimizer {

    /**
     * Analyze the nesting result and provide optimization suggestions
     */
    fun analyzeAndSuggest(
        result: NestingResult,
        sheetWidth: Double,
        sheetHeight: Double,
        minGap: Double
    ): List<String> {
        val suggestions = mutableListOf<String>()

        // Check utilization
        if (result.utilization < 50.0) {
            suggestions.add(AppRes.getString(R.string.s0464, String.format("%.1f", result.utilization)))
        }

        // Check for parts that cannot be placed
        if (result.failedParts.isNotEmpty()) {
            suggestions.add(AppRes.getString(R.string.s0465, result.failedParts.size))
        }

        // Check spacing
        if (minGap > 20.0) {
            suggestions.add(AppRes.getString(R.string.s0466, minGap))
        }

        // Check whether splitting is required
        val totalArea = result.placedPartsList.sumOf { part ->
            NestingUtils.calculatePartArea(part.bounds)
        }
        val sheetArea = sheetWidth * sheetHeight
        if (totalArea > sheetArea * 0.8) {
            suggestions.add(AppRes.getString(R.string.s0467))
        }

        return suggestions
    }

    /**
     * Auto sheet splitting - when a single sheet cannot hold all parts
     */
    fun autoSplitToMultipleSheets(
        parts: List<NestPart>,
        sheetWidth: Double,
        sheetHeight: Double,
        minGap: Double,
        strategy: NestingStrategy = FirstFitDecreasingStrategy()
    ): List<NestingResult> {
        val results = mutableListOf<NestingResult>()
        var remainingParts = parts.map { it.copy() }
        var sheetIndex = 0

        while (remainingParts.isNotEmpty()) {
            val result = strategy.nest(remainingParts, sheetWidth, sheetHeight, minGap)

            if (result.placedCount == 0) {
                // Unable to place any part, maybe the part is too big
                break
            }

            results.add(result)

            // Deduct quantity by name to avoid accidentally deleting parts with the same name from the entire group
            val placedCountByName = result.placedPartsList
                .groupingBy { it.name }
                .eachCount()
            val updated = remainingParts.mapNotNull { part ->
                val remainingQuantity = part.quantity - (placedCountByName[part.name] ?: 0)
                if (remainingQuantity > 0) part.copy(quantity = remainingQuantity) else null
            }
            if (updated.sumOf { it.quantity } >= remainingParts.sumOf { it.quantity }) break
            remainingParts = updated

            sheetIndex++
            if (sheetIndex > 100) break  // Prevent infinite loop
        }

        return results
    }

    /**
     * Compute the optimal sheet size
     */
    fun suggestOptimalSheetSize(
        parts: List<NestPart>,
        minGap: Double,
        maxSheetWidth: Double = 3000.0,
        maxSheetHeight: Double = 1500.0
    ): Pair<Double, Double> {
        val totalArea = parts.sumOf { part ->
            val bounds = RectBounds.fromEntities(part.entities)
            NestingUtils.calculatePartArea(bounds) * part.quantity
        }

        // Consider spacing
        val areaWithGap = totalArea * 1.2  // Add 20% spacing

        // Try to find the closest plate size
        val aspectRatio = maxSheetWidth / maxSheetHeight
        val suggestedHeight = kotlin.math.sqrt(areaWithGap / aspectRatio)
        val suggestedWidth = suggestedHeight * aspectRatio

        return Pair(
            suggestedWidth.coerceAtMost(maxSheetWidth),
            suggestedHeight.coerceAtMost(maxSheetHeight)
        )
    }
}

/**
 * Nesting history record
 */
data class NestingHistory(
    val timestamp: Long,
    val strategyName: String,
    val result: NestingResult,
    val sheetWidth: Double,
    val sheetHeight: Double,
    val minGap: Double
)

/**
 * Nesting history manager
 */
class NestingHistoryManager {
    private val history = mutableListOf<NestingHistory>()
    private var currentIndex = -1

    fun addHistory(
        strategyName: String,
        result: NestingResult,
        sheetWidth: Double,
        sheetHeight: Double,
        minGap: Double
    ) {
        // Remove history after current location
        if (currentIndex < history.size - 1) {
            history.subList(currentIndex + 1, history.size).clear()
        }

        history.add(NestingHistory(
            timestamp = System.currentTimeMillis(),
            strategyName = strategyName,
            result = result,
            sheetWidth = sheetWidth,
            sheetHeight = sheetHeight,
            minGap = minGap
        ))
        currentIndex = history.size - 1
    }

    fun undo(): NestingHistory? {
        if (currentIndex > 0) {
            currentIndex--
            return history[currentIndex]
        }
        return null
    }

    fun redo(): NestingHistory? {
        if (currentIndex < history.size - 1) {
            currentIndex++
            return history[currentIndex]
        }
        return null
    }

    fun canUndo(): Boolean = currentIndex > 0
    fun canRedo(): Boolean = currentIndex < history.size - 1

    fun clear() {
        history.clear()
        currentIndex = -1
    }
}
