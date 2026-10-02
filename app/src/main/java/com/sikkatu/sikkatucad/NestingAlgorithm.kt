package com.sikkatu.sikkatucad

import kotlin.math.*

/**
 * Data structure for an already-placed part
 */
data class PlacedPart(
    val name: String,
    val entities: List<DxfEntity>,
    val bounds: RectBounds,
    val x: Double,  // Place position X
    val y: Double,  // Place position Y
    val rotationDeg: Double = 0.0,
    val mirrorX: Boolean = false
)

/**
 * Collision detector - uses AABB (Axis-Aligned Bounding Box)
 */
object PolygonCollider {

    /**
     * Check whether two rectangles collide
     */
    fun checkAABBCollision(rect1: RectBounds, rect2: RectBounds): Boolean {
        return !(rect1.maxX < rect2.minX || rect1.minX > rect2.maxX ||
                rect1.maxY < rect2.minY || rect1.minY > rect2.maxY)
    }

    /**
     * Get the minimum distance between two rectangles
     */
    fun getMinDistance(rect1: RectBounds, rect2: RectBounds): Double {
        val dx = maxOf(0.0, rect2.minX - rect1.maxX, rect1.minX - rect2.maxX)
        val dy = maxOf(0.0, rect2.minY - rect1.maxY, rect1.minY - rect2.maxY)
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Compute the bounding box after rotation
     */
    fun getRotatedBounds(bounds: RectBounds, angleDeg: Double): RectBounds {
        if (angleDeg == 0.0 || angleDeg == 360.0) return bounds

        val angleRad = Math.toRadians(angleDeg)
        val cos = cos(angleRad)
        val sin = sin(angleRad)

        val corners = listOf(
            Point2(bounds.minX, bounds.minY),
            Point2(bounds.maxX, bounds.minY),
            Point2(bounds.maxX, bounds.maxY),
            Point2(bounds.minX, bounds.maxY)
        )

        val rotatedCorners = corners.map { p ->
            val x = p.x * cos - p.y * sin
            val y = p.x * sin + p.y * cos
            Point2(x, y)
        }

        val xs = rotatedCorners.map { it.x }
        val ys = rotatedCorners.map { it.y }

        return RectBounds(xs.minOrNull() ?: 0.0, ys.minOrNull() ?: 0.0,
                         xs.maxOrNull() ?: 0.0, ys.maxOrNull() ?: 0.0)
    }
}

/**
 * Collision checker
 */
class CollisionChecker {
    private val placedParts = mutableListOf<PlacedPart>()

    fun addPlacedPart(part: PlacedPart) {
        placedParts.add(part)
    }

    fun clear() {
        placedParts.clear()
    }

    /**
     * Check whether a new part collides with already-placed parts
     */
    fun checkCollision(newPart: PlacedPart, minGap: Double = 0.0): Boolean {
        val newBounds = getTransformedBounds(newPart)

        return placedParts.any { placed ->
            val placedBounds = getTransformedBounds(placed)
            val expandedNew = RectBounds(
                newBounds.minX - minGap,
                newBounds.minY - minGap,
                newBounds.maxX + minGap,
                newBounds.maxY + minGap
            )
            PolygonCollider.checkAABBCollision(expandedNew, placedBounds)
        }
    }

    /**
     * Get the transformed bounding box
     */
    private fun getTransformedBounds(part: PlacedPart): RectBounds {
        val rotatedBounds = PolygonCollider.getRotatedBounds(part.bounds, part.rotationDeg)
        return RectBounds(
            rotatedBounds.minX + part.x,
            rotatedBounds.minY + part.y,
            rotatedBounds.maxX + part.x,
            rotatedBounds.maxY + part.y
        )
    }

    /**
     * Get the list of bounding boxes of all placed parts
     */
    fun getPlacedBounds(): List<RectBounds> {
        return placedParts.map { getTransformedBounds(it) }
    }
}

/**
 * Data class for a nesting result
 */
data class NestingResult(
    val entities: List<DxfEntity>,
    val utilization: Double,  // Utilization rate 0-100%
    val wastedArea: Double,   // Wasted area
    val placedCount: Int,     // Number of successfully placed parts
    val failedParts: List<String>,  // Parts that cannot be placed
    val placedPartsList: List<PlacedPart> = emptyList()  // Placed parts list
)

/**
 * Nesting utility class
 */
object NestingUtils {

    /**
     * Compute the actual part size at a given rotation angle
     */
    fun getRotatedSize(bounds: RectBounds, angleDeg: Double): Pair<Double, Double> {
        val angleRad = Math.toRadians(angleDeg)
        val w = bounds.width
        val h = bounds.height

        val rotatedW = abs(w * cos(angleRad)) + abs(h * sin(angleRad))
        val rotatedH = abs(w * sin(angleRad)) + abs(h * cos(angleRad))

        return Pair(rotatedW, rotatedH)
    }

    /**
     * Compute sheet utilization
     */
    fun calculateUtilization(
        sheetArea: Double,
        placedArea: Double
    ): Double {
        return if (sheetArea > 0) (placedArea / sheetArea * 100.0).coerceIn(0.0, 100.0) else 0.0
    }

    /**
     * Compute the total area of the parts
     */
    fun calculatePartArea(bounds: RectBounds): Double {
        return bounds.width * bounds.height
    }

    /**
     * Try to place a part at the given position
     */
    fun tryPlacePart(
        part: NestPart,
        x: Double,
        y: Double,
        rotationDeg: Double,
        sheetWidth: Double,
        sheetHeight: Double,
        collisionChecker: CollisionChecker,
        minGap: Double = 0.0
    ): PlacedPart? {
        val bounds = RectBounds.fromEntities(part.entities)
        val rotatedBounds = PolygonCollider.getRotatedBounds(bounds, rotationDeg)

        // Check if plate boundary is exceeded
        if (x + rotatedBounds.width > sheetWidth || y + rotatedBounds.height > sheetHeight) {
            return null
        }

        val placedPart = PlacedPart(
            name = part.name,
            entities = part.entities,
            bounds = bounds,
            x = x,
            y = y,
            rotationDeg = rotationDeg,
            mirrorX = part.mirrorX
        )

        // Check for collisions
        if (collisionChecker.checkCollision(placedPart, minGap)) {
            return null
        }

        return placedPart
    }
}
