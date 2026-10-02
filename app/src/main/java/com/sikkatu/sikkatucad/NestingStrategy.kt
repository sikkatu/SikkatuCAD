package com.sikkatu.sikkatucad

import kotlin.math.*

/**
 * Nesting strategy interface
 */
interface NestingStrategy {
    fun nest(
        parts: List<NestPart>,
        sheetWidth: Double,
        sheetHeight: Double,
        minGap: Double
    ): NestingResult

    val name: String
    val description: String
}

/**
 * FirstFitDecreasing (FFD) strategy - sort parts by area, largest first
 */
class FirstFitDecreasingStrategy : NestingStrategy {
    override val name = AppRes.getString(R.string.s0468)
    override val description = AppRes.getString(R.string.s0469)

    override fun nest(
        parts: List<NestPart>,
        sheetWidth: Double,
        sheetHeight: Double,
        minGap: Double
    ): NestingResult {
        val collisionChecker = CollisionChecker()
        val placedParts = mutableListOf<PlacedPart>()
        val failedParts = mutableListOf<String>()

        // Sort by area from largest to smallest
        val sortedParts = parts.sortedByDescending { part ->
            val bounds = RectBounds.fromEntities(part.entities)
            NestingUtils.calculatePartArea(bounds) * part.quantity
        }

        // Arrange each part type
        sortedParts.forEach { part ->
            repeat(part.quantity) {
                var placed = false

                // Try 4 rotation angles
                for (angle in listOf(0.0, 90.0, 180.0, 270.0)) {
                    if (placed) break

                    val bounds = RectBounds.fromEntities(part.entities)
                    val (rotatedW, rotatedH) = NestingUtils.getRotatedSize(bounds, angle)

                    // Scan from left to right, top to bottom
                    var y = 0.0
                    while (y + rotatedH <= sheetHeight && !placed) {
                        var x = 0.0
                        while (x + rotatedW <= sheetWidth && !placed) {
                            val placedPart = NestingUtils.tryPlacePart(
                                part, x, y, angle, sheetWidth, sheetHeight,
                                collisionChecker, minGap
                            )

                            if (placedPart != null) {
                                placedParts.add(placedPart)
                                collisionChecker.addPlacedPart(placedPart)
                                placed = true
                            } else {
                                x += rotatedW / 4  // step by step
                            }
                        }
                        y += rotatedH / 4  // step by step
                    }
                }

                if (!placed) {
                    failedParts.add(AppRes.getString(R.string.s0470, part.name, it + 1))
                }
            }
        }

        return buildNestingResult(
            placedParts, failedParts, sheetWidth, sheetHeight, parts
        )
    }

    private fun buildNestingResult(
        placedParts: List<PlacedPart>,
        failedParts: List<String>,
        sheetWidth: Double,
        sheetHeight: Double,
        originalParts: List<NestPart>
    ): NestingResult {
        val entities = mutableListOf<DxfEntity>()
        var totalPlacedArea = 0.0

        placedParts.forEach { placed ->
            val bounds = placed.bounds
            placed.entities.forEach { entity ->
                val transformed = transformNestEntity(
                    entity, placed.rotationDeg, placed.mirrorX,
                    placed.x, placed.y, bounds.minX, bounds.minY
                )
                entities.add(transformed)
            }
            totalPlacedArea += NestingUtils.calculatePartArea(bounds)
        }

        val sheetArea = sheetWidth * sheetHeight
        val utilization = NestingUtils.calculateUtilization(sheetArea, totalPlacedArea)
        val wastedArea = sheetArea - totalPlacedArea

        return NestingResult(
            entities = entities,
            utilization = utilization,
            wastedArea = wastedArea,
            placedCount = placedParts.size,
            failedParts = failedParts,
            placedPartsList = placedParts
        )
    }

    private fun transformNestEntity(
        entity: DxfEntity,
        angleDeg: Double,
        mirrorX: Boolean,
        offsetX: Double,
        offsetY: Double,
        baseMinX: Double,
        baseMinY: Double
    ): DxfEntity {
        val angleRad = Math.toRadians(angleDeg)
        fun tx(p: Point2): Point2 {
            var x = p.x - baseMinX
            var y = p.y - baseMinY
            if (mirrorX) x = -x
            val rx = x * cos(angleRad) - y * sin(angleRad) + offsetX
            val ry = x * sin(angleRad) + y * cos(angleRad) + offsetY
            return Point2(rx, ry)
        }
        return when (entity) {
            is DxfLine -> entity.copy(start = tx(entity.start), end = tx(entity.end))
            is DxfPolyline -> entity.copy(points = entity.points.map(::tx))
            is DxfCircle -> entity.copy(center = tx(entity.center))
            is DxfArc -> entity.copy(
                center = tx(entity.center),
                startAngle = entity.startAngle + angleDeg,
                endAngle = entity.endAngle + angleDeg
            )
            is DxfText -> entity.copy(position = tx(entity.position))
        }
    }
}

/**
 * BestFit strategy - pick the placement that wastes the least space
 */
class BestFitStrategy : NestingStrategy {
    override val name = AppRes.getString(R.string.s0471)
    override val description = AppRes.getString(R.string.s0472)

    override fun nest(
        parts: List<NestPart>,
        sheetWidth: Double,
        sheetHeight: Double,
        minGap: Double
    ): NestingResult {
        val collisionChecker = CollisionChecker()
        val placedParts = mutableListOf<PlacedPart>()
        val failedParts = mutableListOf<String>()

        parts.forEach { part ->
            repeat(part.quantity) {
                var bestPlacement: PlacedPart? = null
                var minWastedArea = Double.MAX_VALUE

                val bounds = RectBounds.fromEntities(part.entities)

                // Try 4 rotation angles
                for (angle in listOf(0.0, 90.0, 180.0, 270.0)) {
                    val (rotatedW, rotatedH) = NestingUtils.getRotatedSize(bounds, angle)

                    // Try placement on the grid
                    val step = minOf(rotatedW, rotatedH) / 2
                    var y = 0.0
                    while (y + rotatedH <= sheetHeight) {
                        var x = 0.0
                        while (x + rotatedW <= sheetWidth) {
                            val placedPart = NestingUtils.tryPlacePart(
                                part, x, y, angle, sheetWidth, sheetHeight,
                                collisionChecker, minGap
                            )

                            if (placedPart != null) {
                                // Calculate wasted area (simplified: use bottom right space)
                                val wastedArea = (sheetWidth - x - rotatedW) * (sheetHeight - y - rotatedH)
                                if (wastedArea < minWastedArea) {
                                    minWastedArea = wastedArea
                                    bestPlacement = placedPart
                                }
                            }
                            x += step
                        }
                        y += step
                    }
                }

                if (bestPlacement != null) {
                    placedParts.add(bestPlacement)
                    collisionChecker.addPlacedPart(bestPlacement)
                } else {
                    failedParts.add(AppRes.getString(R.string.s0470, part.name, it + 1))
                }
            }
        }

        return buildNestingResult(
            placedParts, failedParts, sheetWidth, sheetHeight, parts
        )
    }

    private fun buildNestingResult(
        placedParts: List<PlacedPart>,
        failedParts: List<String>,
        sheetWidth: Double,
        sheetHeight: Double,
        originalParts: List<NestPart>
    ): NestingResult {
        val entities = mutableListOf<DxfEntity>()
        var totalPlacedArea = 0.0

        placedParts.forEach { placed ->
            val bounds = placed.bounds
            placed.entities.forEach { entity ->
                val transformed = transformNestEntity(
                    entity, placed.rotationDeg, placed.mirrorX,
                    placed.x, placed.y, bounds.minX, bounds.minY
                )
                entities.add(transformed)
            }
            totalPlacedArea += NestingUtils.calculatePartArea(bounds)
        }

        val sheetArea = sheetWidth * sheetHeight
        val utilization = NestingUtils.calculateUtilization(sheetArea, totalPlacedArea)
        val wastedArea = sheetArea - totalPlacedArea

        return NestingResult(
            entities = entities,
            utilization = utilization,
            wastedArea = wastedArea,
            placedCount = placedParts.size,
            failedParts = failedParts,
            placedPartsList = placedParts
        )
    }

    private fun transformNestEntity(
        entity: DxfEntity,
        angleDeg: Double,
        mirrorX: Boolean,
        offsetX: Double,
        offsetY: Double,
        baseMinX: Double,
        baseMinY: Double
    ): DxfEntity {
        val angleRad = Math.toRadians(angleDeg)
        fun tx(p: Point2): Point2 {
            var x = p.x - baseMinX
            var y = p.y - baseMinY
            if (mirrorX) x = -x
            val rx = x * cos(angleRad) - y * sin(angleRad) + offsetX
            val ry = x * sin(angleRad) + y * cos(angleRad) + offsetY
            return Point2(rx, ry)
        }
        return when (entity) {
            is DxfLine -> entity.copy(start = tx(entity.start), end = tx(entity.end))
            is DxfPolyline -> entity.copy(points = entity.points.map(::tx))
            is DxfCircle -> entity.copy(center = tx(entity.center))
            is DxfArc -> entity.copy(
                center = tx(entity.center),
                startAngle = entity.startAngle + angleDeg,
                endAngle = entity.endAngle + angleDeg
            )
            is DxfText -> entity.copy(position = tx(entity.position))
        }
    }
}

/**
 * Stripe arrangement strategy
 */
class StripeStrategy : NestingStrategy {
    override val name = AppRes.getString(R.string.s0473)
    override val description = AppRes.getString(R.string.s0474)

    override fun nest(
        parts: List<NestPart>,
        sheetWidth: Double,
        sheetHeight: Double,
        minGap: Double
    ): NestingResult {
        val collisionChecker = CollisionChecker()
        val placedParts = mutableListOf<PlacedPart>()
        val failedParts = mutableListOf<String>()

        var currentY = 0.0
        var currentRowHeight = 0.0

        parts.forEach { part ->
            repeat(part.quantity) {
                var placed = false
                val bounds = RectBounds.fromEntities(part.entities)

                // Try 4 rotation angles
                for (angle in listOf(0.0, 90.0, 180.0, 270.0)) {
                    if (placed) break

                    val (rotatedW, rotatedH) = NestingUtils.getRotatedSize(bounds, angle)

                    // Try placing at the current line
                    if (currentY + rotatedH <= sheetHeight) {
                        var x = 0.0
                        while (x + rotatedW <= sheetWidth && !placed) {
                            val placedPart = NestingUtils.tryPlacePart(
                                part, x, currentY, angle, sheetWidth, sheetHeight,
                                collisionChecker, minGap
                            )

                            if (placedPart != null) {
                                placedParts.add(placedPart)
                                collisionChecker.addPlacedPart(placedPart)
                                currentRowHeight = maxOf(currentRowHeight, rotatedH)
                                placed = true
                            } else {
                                x += rotatedW / 2
                            }
                        }
                    }

                    // If the current row does not fit, try a new row
                    if (!placed && currentY + currentRowHeight + rotatedH <= sheetHeight) {
                        currentY += currentRowHeight + minGap
                        currentRowHeight = 0.0

                        var x = 0.0
                        while (x + rotatedW <= sheetWidth && !placed) {
                            val placedPart = NestingUtils.tryPlacePart(
                                part, x, currentY, angle, sheetWidth, sheetHeight,
                                collisionChecker, minGap
                            )

                            if (placedPart != null) {
                                placedParts.add(placedPart)
                                collisionChecker.addPlacedPart(placedPart)
                                currentRowHeight = maxOf(currentRowHeight, rotatedH)
                                placed = true
                            } else {
                                x += rotatedW / 2
                            }
                        }
                    }
                }

                if (!placed) {
                    failedParts.add(AppRes.getString(R.string.s0470, part.name, it + 1))
                }
            }
        }

        return buildNestingResult(
            placedParts, failedParts, sheetWidth, sheetHeight, parts
        )
    }

    private fun buildNestingResult(
        placedParts: List<PlacedPart>,
        failedParts: List<String>,
        sheetWidth: Double,
        sheetHeight: Double,
        originalParts: List<NestPart>
    ): NestingResult {
        val entities = mutableListOf<DxfEntity>()
        var totalPlacedArea = 0.0

        placedParts.forEach { placed ->
            val bounds = placed.bounds
            placed.entities.forEach { entity ->
                val transformed = transformNestEntity(
                    entity, placed.rotationDeg, placed.mirrorX,
                    placed.x, placed.y, bounds.minX, bounds.minY
                )
                entities.add(transformed)
            }
            totalPlacedArea += NestingUtils.calculatePartArea(bounds)
        }

        val sheetArea = sheetWidth * sheetHeight
        val utilization = NestingUtils.calculateUtilization(sheetArea, totalPlacedArea)
        val wastedArea = sheetArea - totalPlacedArea

        return NestingResult(
            entities = entities,
            utilization = utilization,
            wastedArea = wastedArea,
            placedCount = placedParts.size,
            failedParts = failedParts,
            placedPartsList = placedParts
        )
    }

    private fun transformNestEntity(
        entity: DxfEntity,
        angleDeg: Double,
        mirrorX: Boolean,
        offsetX: Double,
        offsetY: Double,
        baseMinX: Double,
        baseMinY: Double
    ): DxfEntity {
        val angleRad = Math.toRadians(angleDeg)
        fun tx(p: Point2): Point2 {
            var x = p.x - baseMinX
            var y = p.y - baseMinY
            if (mirrorX) x = -x
            val rx = x * cos(angleRad) - y * sin(angleRad) + offsetX
            val ry = x * sin(angleRad) + y * cos(angleRad) + offsetY
            return Point2(rx, ry)
        }
        return when (entity) {
            is DxfLine -> entity.copy(start = tx(entity.start), end = tx(entity.end))
            is DxfPolyline -> entity.copy(points = entity.points.map(::tx))
            is DxfCircle -> entity.copy(center = tx(entity.center))
            is DxfArc -> entity.copy(
                center = tx(entity.center),
                startAngle = entity.startAngle + angleDeg,
                endAngle = entity.endAngle + angleDeg
            )
            is DxfText -> entity.copy(position = tx(entity.position))
        }
    }
}
