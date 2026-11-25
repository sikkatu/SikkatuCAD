package com.sikkatu.sikkatucad

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class DxfCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onMeasureUpdated(text: String?)
        fun onSelectionChanged(selection: GeometrySelection?)
        /** Called on a tap on empty drawing area with model coordinates. */
        fun onCanvasTap(x: Double, y: Double) {}
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.canvasBg)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.canvasGrid)
        strokeWidth = 1f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.entityLine)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.entityAccent)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val axisXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ff5757")
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val axisYPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#62ff6a")
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val axisZPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4f86ff")
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
    }
    private val overlayFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.entityAccent)
        style = Paint.Style.FILL
        alpha = 50
    }
    private val selectionEndPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ff5d5d")
        style = Paint.Style.FILL
    }
    private val snapMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6ee7ff")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val dimensionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ffcf5a")
        strokeWidth = 2.6f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val dimensionSolidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ffcf5a")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val dimensionTextBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#dd101820")
        style = Paint.Style.FILL
    }

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var document: DxfDocument? = null
    private var hiddenLayers = emptySet<String>()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var measureMode = MeasureMode.NONE
    private var autoDimensionEnabled = false
    private var measureStart: Point2? = null
    private var measureEnd: Point2? = null
    private var anglePoints = mutableListOf<Point2>()
    private var areaPoints = mutableListOf<Point2>()
    private var areaAutoClose = true
    private var selectedGeometry: GeometrySelection? = null
    private var snapPreview: SnapPreview? = null
    private var measurePanActive = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    var listener: Listener? = null

    fun setDocument(document: DxfDocument) {
        this.document = document
        hiddenLayers = emptySet()
        selectedGeometry = null
        clearMeasurement()
        resetViewport()
        invalidate()
    }

    fun setHiddenLayers(hiddenLayers: Set<String>) {
        this.hiddenLayers = hiddenLayers
        invalidate()
    }


    fun clearSelection(notify: Boolean = true) {
        selectedGeometry = null
        if (notify) {
            listener?.onSelectionChanged(null)
        }
        invalidate()
    }

    fun setSelection(selection: GeometrySelection?) {
        selectedGeometry = selection
        invalidate()
    }

    fun setMeasureMode(mode: MeasureMode) {
        measureMode = mode
        clearMeasurement(notify = false)
        snapPreview = null
        if (mode == MeasureMode.NONE) {
            listener?.onMeasureUpdated(null)
        } else {
            listener?.onMeasureUpdated(measureHint(mode))
        }
        invalidate()
    }

    fun setAutoDimensionEnabled(enabled: Boolean) {
        autoDimensionEnabled = enabled
        invalidate()
    }

    fun isAutoDimensionEnabled(): Boolean = autoDimensionEnabled

    fun autoDimensionSummary(): String? {
        val doc = document ?: return null
        val bounds = activeBounds(doc)
        return context.getString(R.string.s0008, formatDimensionValue(bounds.width), formatDimensionValue(bounds.height))
    }


    fun setAreaAutoClose(enabled: Boolean) {
        areaAutoClose = enabled
        if (measureMode == MeasureMode.AREA) {
            listener?.onMeasureUpdated(formatAreaState())
        }
        invalidate()
    }

    fun completeAreaMeasurement() {
        if (measureMode == MeasureMode.AREA) {
            listener?.onMeasureUpdated(formatAreaState(forceClosed = true))
            invalidate()
        }
    }

    fun undoMeasurementStep() {
        when (measureMode) {
            MeasureMode.DISTANCE -> {
                if (measureEnd != null) {
                    measureEnd = null
                } else {
                    measureStart = null
                }
                listener?.onMeasureUpdated(
                    if (measureStart != null) {
                        context.getString(R.string.s0009).format(measureStart!!.x, measureStart!!.y)
                    } else {
                        measureHint(MeasureMode.DISTANCE)
                    }
                )
            }

            MeasureMode.ANGLE -> {
                if (anglePoints.isNotEmpty()) {
                    anglePoints.removeAt(anglePoints.lastIndex)
                }
                listener?.onMeasureUpdated(formatAngleState())
            }

            MeasureMode.AREA -> {
                if (areaPoints.isNotEmpty()) {
                    areaPoints.removeAt(areaPoints.lastIndex)
                }
                listener?.onMeasureUpdated(formatAreaState())
            }

            MeasureMode.NONE -> Unit
        }
        invalidate()
    }

    fun clearMeasurement(notify: Boolean = true) {
        measureStart = null
        measureEnd = null
        anglePoints.clear()
        areaPoints.clear()
        snapPreview = null
        if (notify) {
            listener?.onMeasureUpdated(if (measureMode == MeasureMode.NONE) null else measureHint(measureMode))
        }
        invalidate()
    }

    fun resetToBounds() {
        resetViewport()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 || oldh == 0) {
            resetViewport()
        } else {
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        drawGrid(canvas)
        val doc = document ?: return
        val viewBounds = activeBounds(doc)
        val baseScale = baseScale(viewBounds)
        doc.entities.forEach { entity ->
            if (isLayerHidden(entity.layer)) {
                return@forEach
            }
            when (entity) {
                is DxfLine -> {
                    val a = mapPoint(entity.start, viewBounds, baseScale)
                    val b = mapPoint(entity.end, viewBounds, baseScale)
                        canvas.drawLine(a.first, a.second, b.first, b.second, paintForLayer(entity.layer))
                }

                is DxfPolyline -> {
                    val paint = paintForLayer(entity.layer)
                    for (index in 0 until entity.points.lastIndex) {
                        val a = mapPoint(entity.points[index], viewBounds, baseScale)
                        val b = mapPoint(entity.points[index + 1], viewBounds, baseScale)
                            canvas.drawLine(a.first, a.second, b.first, b.second, paint)
                    }
                    if (entity.closed && entity.points.size > 2) {
                        val a = mapPoint(entity.points.first(), viewBounds, baseScale)
                        val b = mapPoint(entity.points.last(), viewBounds, baseScale)
                            canvas.drawLine(a.first, a.second, b.first, b.second, paint)
                    }
                }

                is DxfCircle -> {
                    val center = mapPoint(entity.center, viewBounds, baseScale)
                    canvas.drawCircle(center.first, center.second, (entity.radius * baseScale * zoom).toFloat(), paintForLayer(entity.layer))
                }

                is DxfArc -> {
                    val sweep = normalizeSweep(entity.startAngle, entity.endAngle)
                    val center = mapPoint(entity.center, viewBounds, baseScale)
                    val radius = (entity.radius * baseScale * zoom).toFloat()
                    val rect = RectF(
                        center.first - radius,
                        center.second - radius,
                        center.first + radius,
                        center.second + radius
                    )
                    val screenStart = cadAngleToCanvasAngle(entity.startAngle)
                    val screenSweep = cadSweepToCanvasSweep(sweep)
                    canvas.drawArc(rect, screenStart.toFloat(), screenSweep.toFloat(), false, paintForLayer(entity.layer))
                }

                is DxfText -> {
                    val p = mapPoint(entity.position, viewBounds, baseScale)
                    val size = if (isPdfTextEntity(doc, entity)) {
                        max(10f, (entity.height * baseScale * zoom * 0.22f).toFloat())
                    } else {
                        max(18f, (entity.height * baseScale * zoom * 0.45f).toFloat())
                    }
                    textPaint.textSize = size
                    textPaint.color = textColorForLayer(entity.layer)
                    drawOrientedText(canvas, entity, p.first, p.second)
                }
            }
        }
        drawSelectionOverlay(canvas, viewBounds, baseScale)
        drawSnapOverlay(canvas, viewBounds, baseScale)
        drawDimensionOverlay(canvas, viewBounds, baseScale)
        drawMeasureOverlay(canvas, viewBounds, baseScale)
    }

    private fun isPdfTextEntity(document: DxfDocument, entity: DxfText): Boolean {
        return document.metadata["source_type"] == "pdf" ||
            entity.layer.orEmpty().uppercase().startsWith("PDF_TEXT")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                touchDownX = event.x
                touchDownY = event.y
                measurePanActive = false
                if (measureMode != MeasureMode.NONE) {
                    snapPreview = snapMeasurementPoint(screenToModel(event.x, event.y))
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Switch to zoom/move priority immediately when a multi-finger gesture starts, to avoid measurement adsorption and single-finger panning states from interfering with the zoom focus.
                measurePanActive = true
                lastTouchX = event.getX(event.actionIndex)
                lastTouchY = event.getY(event.actionIndex)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || event.pointerCount > 1) {
                    // Scaling is handled exclusively by ScaleGestureDetector; the current position is synchronized to prevent the next single-finger movement after scaling ends from using old coordinates and causing jitter.
                    lastTouchX = event.x
                    lastTouchY = event.y
                    return true
                }

                if (measureMode != MeasureMode.NONE) {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    if (!measurePanActive && (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12)) {
                        measurePanActive = true
                    }
                    if (measurePanActive) {
                        panX += event.x - lastTouchX
                        panY += event.y - lastTouchY
                        val bounds = activeBounds(document ?: return true)
                        clampPan(bounds, baseScale(bounds))
                        invalidate()
                    } else if (event.pointerCount == 1) {
                        snapPreview = snapMeasurementPoint(screenToModel(event.x, event.y))
                        invalidate()
                    }
                } else if (event.pointerCount == 1) {
                    panX += event.x - lastTouchX
                    panY += event.y - lastTouchY
                    val bounds = activeBounds(document ?: return true)
                    clampPan(bounds, baseScale(bounds))
                    invalidate()
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Record the position of the remaining fingers to avoid sudden changes in displacement when zooming from two fingers back to one finger.
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    lastTouchX = event.getX(remainingIndex)
                    lastTouchY = event.getY(remainingIndex)
                    touchDownX = lastTouchX
                    touchDownY = lastTouchY
                }
                measurePanActive = true
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
        }
        return true
    }

    private fun commitMeasurementPoint(point: Point2) {
        when (measureMode) {
            MeasureMode.DISTANCE -> {
                if (measureStart == null || measureEnd != null) {
                    measureStart = point
                    measureEnd = null
                    listener?.onMeasureUpdated(context.getString(R.string.s0010).format(point.x, point.y))
                } else {
                    measureEnd = point
                    listener?.onMeasureUpdated(formatDistance())
                }
            }

            MeasureMode.ANGLE -> {
                if (anglePoints.size == 3) {
                    anglePoints.clear()
                }
                anglePoints.add(point)
                listener?.onMeasureUpdated(formatAngleState())
            }

            MeasureMode.AREA -> {
                areaPoints.add(point)
                listener?.onMeasureUpdated(formatAreaState())
            }

            MeasureMode.NONE -> Unit
        }
        invalidate()
    }

    private fun resetViewport() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    private fun drawGrid(canvas: Canvas) {
        val step = 64
        var x = 0
        while (x <= width) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), gridPaint)
            x += step
        }
        var y = 0
        while (y <= height) {
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), gridPaint)
            y += step
        }
    }

    private fun baseScale(bounds: RectBounds): Double {
        if (width == 0 || height == 0) return 1.0
        val sx = (width - 64.0) / bounds.width
        val sy = (height - 64.0) / bounds.height
        return minOf(sx, sy).coerceAtLeast(0.0005)
    }

    private fun activeBounds(document: DxfDocument): RectBounds {
        return document.bounds
    }

    private fun isLayerHidden(layer: String?): Boolean {
        val value = layer?.trim().orEmpty()
        if (value.isEmpty()) return false
        return hiddenLayers.contains(value) || hiddenLayers.contains(value.uppercase())
    }

    private fun mapPoint(point: Point2, bounds: RectBounds, baseScale: Double): Pair<Float, Float> {
        val scaledWidth = bounds.width * baseScale * zoom
        val scaledHeight = bounds.height * baseScale * zoom
        val contentOffsetX = ((width - scaledWidth) / 2.0).coerceAtLeast(32.0)
        val contentOffsetY = ((height - scaledHeight) / 2.0).coerceAtLeast(32.0)
        val x = ((point.x - bounds.minX) * baseScale * zoom + contentOffsetX + panX).toFloat()
        val y = (height - ((point.y - bounds.minY) * baseScale * zoom + contentOffsetY) + panY).toFloat()
        return x to y
    }

    private fun clampPan(bounds: RectBounds, baseScale: Double) {
        val scaledWidth = bounds.width * baseScale * zoom
        val scaledHeight = bounds.height * baseScale * zoom
        val contentOffsetX = ((width - scaledWidth) / 2.0).coerceAtLeast(32.0)
        val contentOffsetY = ((height - scaledHeight) / 2.0).coerceAtLeast(32.0)
        val minX = -scaledWidth * 0.75
        val maxX = width - contentOffsetX * 0.25
        val minY = -scaledHeight * 0.75
        val maxY = height - contentOffsetY * 0.25
        panX = panX.coerceIn(minX.toFloat(), maxX.toFloat())
        panY = panY.coerceIn(minY.toFloat(), maxY.toFloat())
    }

    private fun drawFlat2DEntity(canvas: Canvas, entity: DxfEntity, bounds: RectBounds, baseScale: Double) {
        when (entity) {
            is DxfLine -> {
                val a = mapPoint(entity.start, bounds, baseScale)
                val b = mapPoint(entity.end, bounds, baseScale)
                canvas.drawLine(a.first, a.second, b.first, b.second, paintForLayer(entity.layer))
            }
            is DxfPolyline -> {
                val paint = paintForLayer(entity.layer)
                for (index in 0 until entity.points.lastIndex) {
                    val a = mapPoint(entity.points[index], bounds, baseScale)
                    val b = mapPoint(entity.points[index + 1], bounds, baseScale)
                    canvas.drawLine(a.first, a.second, b.first, b.second, paint)
                }
                if (entity.closed && entity.points.size > 2) {
                    val a = mapPoint(entity.points.first(), bounds, baseScale)
                    val b = mapPoint(entity.points.last(), bounds, baseScale)
                    canvas.drawLine(a.first, a.second, b.first, b.second, paint)
                }
            }
            is DxfCircle -> {
                val center = mapPoint(entity.center, bounds, baseScale)
                canvas.drawCircle(center.first, center.second, (entity.radius * baseScale * zoom).toFloat(), paintForLayer(entity.layer))
            }
            is DxfArc -> {
                val center = mapPoint(entity.center, bounds, baseScale)
                val radius = (entity.radius * baseScale * zoom).toFloat()
                val rect = RectF(
                    center.first - radius,
                    center.second - radius,
                    center.first + radius,
                    center.second + radius
                )
                val screenStart = cadAngleToCanvasAngle(entity.startAngle)
                val screenSweep = cadSweepToCanvasSweep(normalizeSweep(entity.startAngle, entity.endAngle))
                canvas.drawArc(rect, screenStart.toFloat(), screenSweep.toFloat(), false, paintForLayer(entity.layer))
            }
            is DxfText -> {
                val p = mapPoint(entity.position, bounds, baseScale)
                val size = max(18f, (entity.height * baseScale * zoom * 0.45f).toFloat())
                textPaint.textSize = size
                textPaint.color = textColorForLayer(entity.layer)
                drawOrientedText(canvas, entity, p.first, p.second)
            }
        }
    }

    private fun drawOrientedText(canvas: Canvas, entity: DxfText, x: Float, y: Float) {
        if (abs(entity.rotationDegrees) < 0.1) {
            canvas.drawText(entity.text, x, y, textPaint)
            return
        }
        canvas.save()
        canvas.rotate((-entity.rotationDegrees).toFloat(), x, y)
        canvas.drawText(entity.text, x, y, textPaint)
        canvas.restore()
    }

    private fun normalizeSweep(start: Double, end: Double): Double {
        var sweep = end - start
        if (sweep < 0) {
            sweep += 360.0
        }
        if (sweep == 0.0) {
            sweep = 360.0
        }
        return sweep
    }

    private fun cadAngleToCanvasAngle(angle: Double): Double {
        val converted = -angle
        return ((converted % 360.0) + 360.0) % 360.0
    }

    private fun cadSweepToCanvasSweep(sweep: Double): Double {
        return -sweep
    }

    private fun paintForLayer(layer: String?): Paint {
        val value = layer.orEmpty().uppercase()
        return when {
            value.contains("MARK") -> accentPaint
            else -> linePaint
        }
    }

    private fun textColorForLayer(layer: String?): Int {
        return when {
            layer.orEmpty().uppercase().startsWith("GEN_RAW") -> Color.parseColor("#c8d2dc")
            else -> Color.WHITE
        }
    }

    private fun drawDimensionOverlay(canvas: Canvas, bounds: RectBounds, baseScale: Double) {
        if (autoDimensionEnabled) {
            drawOverallBoundsDimension(canvas, bounds, baseScale)
            drawDetailedAutoDimensions(canvas, bounds, baseScale)
        }
        drawSelectedGeometryDimension(canvas, bounds, baseScale)
    }

    private fun drawOverallBoundsDimension(canvas: Canvas, bounds: RectBounds, baseScale: Double) {
        val widthValue = bounds.width
        val heightValue = bounds.height
        if (!widthValue.isFinite() || !heightValue.isFinite()) return

        val bottomLeft = mapPoint(Point2(bounds.minX, bounds.minY), bounds, baseScale)
        val bottomRight = mapPoint(Point2(bounds.maxX, bounds.minY), bounds, baseScale)
        val topLeft = mapPoint(Point2(bounds.minX, bounds.maxY), bounds, baseScale)
        val topRight = mapPoint(Point2(bounds.maxX, bounds.maxY), bounds, baseScale)

        val horizontalOffset = 36f
        val verticalOffset = 42f
        val tick = 14f
        val labelPaddingX = 12f
        val labelPaddingY = 7f
        val screenLeft = minOf(bottomLeft.first, topLeft.first)
        val screenRight = maxOf(bottomRight.first, topRight.first)
        val screenTop = minOf(topLeft.second, topRight.second)
        val screenBottom = maxOf(bottomLeft.second, bottomRight.second)
        val hY = (screenBottom + horizontalOffset).coerceAtMost(height - 16f)
        val vX = (screenRight + verticalOffset).coerceAtMost(width - 16f)

        canvas.drawLine(bottomLeft.first, bottomLeft.second, bottomLeft.first, hY, dimensionPaint)
        canvas.drawLine(bottomRight.first, bottomRight.second, bottomRight.first, hY, dimensionPaint)
        canvas.drawLine(bottomLeft.first, hY, bottomRight.first, hY, dimensionSolidPaint)
        canvas.drawLine(bottomLeft.first, hY - tick, bottomLeft.first, hY + tick, dimensionSolidPaint)
        canvas.drawLine(bottomRight.first, hY - tick, bottomRight.first, hY + tick, dimensionSolidPaint)
        drawDimensionLabel(canvas, context.getString(R.string.s0011, formatDimensionValue(widthValue)), (screenLeft + screenRight) / 2f, hY - 10f, labelPaddingX, labelPaddingY)

        canvas.drawLine(topRight.first, topRight.second, vX, topRight.second, dimensionPaint)
        canvas.drawLine(bottomRight.first, bottomRight.second, vX, bottomRight.second, dimensionPaint)
        canvas.drawLine(vX, screenTop, vX, screenBottom, dimensionSolidPaint)
        canvas.drawLine(vX - tick, screenTop, vX + tick, screenTop, dimensionSolidPaint)
        canvas.drawLine(vX - tick, screenBottom, vX + tick, screenBottom, dimensionSolidPaint)
        drawDimensionLabel(canvas, context.getString(R.string.s0012, formatDimensionValue(heightValue)), vX - 10f, (screenTop + screenBottom) / 2f, labelPaddingX, labelPaddingY, alignRight = true)
    }

    private fun drawDetailedAutoDimensions(canvas: Canvas, bounds: RectBounds, baseScale: Double) {
        val doc = document ?: return
        var drawnCount = 0
        val maxAutoLabels = 90
        doc.entities.forEach { entity ->
            if (drawnCount >= maxAutoLabels) return@forEach
            if (entity.layer != null && hiddenLayers.contains(entity.layer)) return@forEach
            if (!isSelectableLayer(entity.layer)) return@forEach
            when (entity) {
                is DxfLine -> {
                    val len = distance(entity.start, entity.end)
                    if (len > 0.0001) {
                        drawLineDimension(canvas, bounds, baseScale, entity.start, entity.end, formatDimensionValue(len), 22f)
                        drawnCount++
                    }
                }
                is DxfPolyline -> {
                    for (index in 0 until entity.points.lastIndex) {
                        if (drawnCount >= maxAutoLabels) break
                        val start = entity.points[index]
                        val end = entity.points[index + 1]
                        val len = distance(start, end)
                        if (len > 0.0001) {
                            drawLineDimension(canvas, bounds, baseScale, start, end, formatDimensionValue(len), 18f)
                            drawnCount++
                        }
                    }
                    if (entity.closed && entity.points.size > 2 && drawnCount < maxAutoLabels) {
                        val start = entity.points.last()
                        val end = entity.points.first()
                        val len = distance(start, end)
                        if (len > 0.0001) {
                            drawLineDimension(canvas, bounds, baseScale, start, end, formatDimensionValue(len), 18f)
                            drawnCount++
                        }
                    }
                }
                is DxfArc -> {
                    val sweep = normalizeSweep(entity.startAngle, entity.endAngle)
                    val arcLength = entity.radius * Math.toRadians(sweep)
                    val label = context.getString(R.string.s0013, formatDimensionValue(entity.radius), formatDimensionValue(arcLength))
                    drawRadialDimension(canvas, bounds, baseScale, entity.center, entity.radius, entity.startAngle + sweep / 2.0, label, 28f)
                    drawnCount++
                }
                is DxfCircle -> {
                    drawRadialDimension(canvas, bounds, baseScale, entity.center, entity.radius, 45.0, "Φ ${formatDimensionValue(entity.radius * 2.0)}", 28f)
                    drawnCount++
                }
                else -> Unit
            }
        }
    }

    private fun drawSelectedGeometryDimension(canvas: Canvas, bounds: RectBounds, baseScale: Double) {
        val selection = selectedGeometry ?: return
        val entity = document?.entities?.getOrNull(selection.entityIndex) ?: return
        when (entity) {
            is DxfLine -> drawLineDimension(canvas, bounds, baseScale, entity.start, entity.end, context.getString(R.string.s0014, formatDimensionValue(distance(entity.start, entity.end))), 42f)
            is DxfPolyline -> drawLineDimension(canvas, bounds, baseScale, selection.start, selection.end, context.getString(R.string.s0015, formatDimensionValue(distance(selection.start, selection.end))), 42f)
            is DxfArc -> {
                val sweep = normalizeSweep(entity.startAngle, entity.endAngle)
                val arcLength = entity.radius * Math.toRadians(sweep)
                drawRadialDimension(
                    canvas,
                    bounds,
                    baseScale,
                    entity.center,
                    entity.radius,
                    entity.startAngle + sweep / 2.0,
                    context.getString(R.string.s0016, formatDimensionValue(entity.radius), formatDimensionValue(arcLength)),
                    46f
                )
            }
            is DxfCircle -> drawRadialDimension(
                canvas,
                bounds,
                baseScale,
                entity.center,
                entity.radius,
                45.0,
                "Φ ${formatDimensionValue(entity.radius * 2.0)}  R ${formatDimensionValue(entity.radius)}",
                46f
            )
            else -> Unit
        }
    }

    private fun drawLineDimension(
        canvas: Canvas,
        bounds: RectBounds,
        baseScale: Double,
        start: Point2,
        end: Point2,
        label: String,
        offset: Float
    ) {
        val a = mapPoint(start, bounds, baseScale)
        val b = mapPoint(end, bounds, baseScale)
        val dx = b.first - a.first
        val dy = b.second - a.second
        val screenLength = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (screenLength <= 1f) return
        val nx = -dy / screenLength
        val ny = dx / screenLength
        val ax = a.first + nx * offset
        val ay = a.second + ny * offset
        val bx = b.first + nx * offset
        val by = b.second + ny * offset
        val tick = 10f
        canvas.drawLine(a.first, a.second, ax, ay, dimensionPaint)
        canvas.drawLine(b.first, b.second, bx, by, dimensionPaint)
        canvas.drawLine(ax, ay, bx, by, dimensionSolidPaint)
        canvas.drawLine(ax - nx * tick, ay - ny * tick, ax + nx * tick, ay + ny * tick, dimensionSolidPaint)
        canvas.drawLine(bx - nx * tick, by - ny * tick, bx + nx * tick, by + ny * tick, dimensionSolidPaint)
        drawDimensionLabel(canvas, label, (ax + bx) / 2f, (ay + by) / 2f - 8f, 10f, 6f)
    }

    private fun drawRadialDimension(
        canvas: Canvas,
        bounds: RectBounds,
        baseScale: Double,
        center: Point2,
        radius: Double,
        angleDegrees: Double,
        label: String,
        extensionPx: Float
    ) {
        if (radius <= 0.0) return
        val angle = Math.toRadians(angleDegrees)
        val edge = Point2(center.x + radius * cos(angle), center.y + radius * sin(angle))
        val c = mapPoint(center, bounds, baseScale)
        val e = mapPoint(edge, bounds, baseScale)
        val dx = e.first - c.first
        val dy = e.second - c.second
        val length = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (length <= 1f) return
        val ux = dx / length
        val uy = dy / length
        val lx = e.first + ux * extensionPx
        val ly = e.second + uy * extensionPx
        canvas.drawCircle(c.first, c.second, 5f, dimensionSolidPaint)
        canvas.drawLine(c.first, c.second, e.first, e.second, dimensionSolidPaint)
        canvas.drawLine(e.first, e.second, lx, ly, dimensionPaint)
        drawDimensionLabel(canvas, label, lx + ux * 10f, ly + uy * 10f, 10f, 6f)
    }


    private fun drawDimensionLabel(
        canvas: Canvas,
        label: String,
        anchorX: Float,
        anchorY: Float,
        paddingX: Float,
        paddingY: Float,
        alignRight: Boolean = false
    ) {
        textPaint.color = Color.parseColor("#ffcf5a")
        textPaint.textSize = 24f
        val metrics = textPaint.fontMetrics
        val textWidth = textPaint.measureText(label)
        val left = if (alignRight) anchorX - textWidth - paddingX * 2f else anchorX - textWidth / 2f - paddingX
        val top = anchorY + metrics.ascent - paddingY
        val right = left + textWidth + paddingX * 2f
        val bottom = anchorY + metrics.descent + paddingY
        canvas.drawRoundRect(RectF(left, top, right, bottom), 10f, 10f, dimensionTextBgPaint)
        canvas.drawText(label, left + paddingX, anchorY, textPaint)
    }

    private fun formatDimensionValue(value: Double): String {
        return if (abs(value) >= 1000.0) {
            "%.1f".format(value)
        } else {
            "%.2f".format(value)
        }
    }

    private fun drawMeasureOverlay(canvas: Canvas, bounds: RectBounds, baseScale: Double) {
        when (measureMode) {
            MeasureMode.DISTANCE -> {
                val start = measureStart ?: return
                val end = measureEnd ?: return
                val a = mapPoint(start, bounds, baseScale)
                val b = mapPoint(end, bounds, baseScale)
                canvas.drawLine(a.first, a.second, b.first, b.second, accentPaint)
                canvas.drawCircle(a.first, a.second, 8f, accentPaint)
                canvas.drawCircle(b.first, b.second, 8f, accentPaint)
            }

            MeasureMode.ANGLE -> {
                anglePoints.forEach { point ->
                    val mapped = mapPoint(point, bounds, baseScale)
                    canvas.drawCircle(mapped.first, mapped.second, 8f, accentPaint)
                }
                if (anglePoints.size >= 2) {
                    val a = mapPoint(anglePoints[0], bounds, baseScale)
                    val b = mapPoint(anglePoints[1], bounds, baseScale)
                    canvas.drawLine(a.first, a.second, b.first, b.second, accentPaint)
                }
                if (anglePoints.size == 3) {
                    val b = mapPoint(anglePoints[1], bounds, baseScale)
                    val c = mapPoint(anglePoints[2], bounds, baseScale)
                    canvas.drawLine(b.first, b.second, c.first, c.second, accentPaint)
                }
            }

            MeasureMode.AREA -> {
                if (areaPoints.isEmpty()) return
                val path = Path()
                areaPoints.forEachIndexed { index, point ->
                    val mapped = mapPoint(point, bounds, baseScale)
                    if (index == 0) {
                        path.moveTo(mapped.first, mapped.second)
                    } else {
                        path.lineTo(mapped.first, mapped.second)
                    }
                    canvas.drawCircle(mapped.first, mapped.second, 7f, accentPaint)
                }
                val shouldClose = areaAutoClose && areaPoints.size >= 3
                if (shouldClose) {
                    path.close()
                    canvas.drawPath(path, overlayFillPaint)
                }
                canvas.drawPath(path, accentPaint)
                if (shouldClose) {
                    val first = mapPoint(areaPoints.first(), bounds, baseScale)
                    val last = mapPoint(areaPoints.last(), bounds, baseScale)
                    canvas.drawLine(last.first, last.second, first.first, first.second, accentPaint)
                }
            }

            MeasureMode.NONE -> Unit
        }
    }

    private fun drawSnapOverlay(canvas: Canvas, bounds: RectBounds, baseScale: Double) {
        val preview = snapPreview ?: return
        val mapped = mapPoint(preview.point, bounds, baseScale)
        canvas.drawCircle(mapped.first, mapped.second, 11f, snapMarkerPaint)
        canvas.drawLine(mapped.first - 14f, mapped.second, mapped.first + 14f, mapped.second, snapMarkerPaint)
        canvas.drawLine(mapped.first, mapped.second - 14f, mapped.first, mapped.second + 14f, snapMarkerPaint)
        textPaint.color = Color.parseColor("#9fe8ff")
        textPaint.textSize = 24f
        canvas.drawText(preview.label, mapped.first + 16f, mapped.second - 12f, textPaint)
    }

    private fun snapMeasurementPoint(rawPoint: Point2): SnapPreview {
        val doc = document ?: return SnapPreview(rawPoint, context.getString(R.string.s0017))
        val tolerance = selectionTolerance() * 1.25
        var bestVertex: Point2? = null
        var bestVertexDistance = Double.MAX_VALUE
        var bestMidpoint: Point2? = null
        var bestMidpointDistance = Double.MAX_VALUE
        var bestPointOnSegment: Point2? = null
        var bestSegmentDistance = Double.MAX_VALUE
        doc.entities.forEach { entity ->
            if (entity.layer != null && hiddenLayers.contains(entity.layer)) return@forEach
            when (entity) {
                is DxfLine -> {
                    listOf(entity.start, entity.end).forEach { vertex ->
                        val distance = distance(rawPoint, vertex)
                        if (distance < bestVertexDistance) {
                            bestVertexDistance = distance
                            bestVertex = vertex
                        }
                    }
                    val midpoint = Point2((entity.start.x + entity.end.x) / 2.0, (entity.start.y + entity.end.y) / 2.0)
                    val midpointDistance = distance(rawPoint, midpoint)
                    if (midpointDistance < bestMidpointDistance) {
                        bestMidpointDistance = midpointDistance
                        bestMidpoint = midpoint
                    }
                    val projection = projectPointToSegment(rawPoint, entity.start, entity.end)
                    val projectionDistance = distance(rawPoint, projection)
                    if (projectionDistance < bestSegmentDistance) {
                        bestSegmentDistance = projectionDistance
                        bestPointOnSegment = projection
                    }
                }
                is DxfPolyline -> {
                    entity.points.forEach { vertex ->
                        val distance = distance(rawPoint, vertex)
                        if (distance < bestVertexDistance) {
                            bestVertexDistance = distance
                            bestVertex = vertex
                        }
                    }
                    for (index in 0 until entity.points.lastIndex) {
                        val midpoint = Point2(
                            (entity.points[index].x + entity.points[index + 1].x) / 2.0,
                            (entity.points[index].y + entity.points[index + 1].y) / 2.0
                        )
                        val midpointDistance = distance(rawPoint, midpoint)
                        if (midpointDistance < bestMidpointDistance) {
                            bestMidpointDistance = midpointDistance
                            bestMidpoint = midpoint
                        }
                        val projection = projectPointToSegment(rawPoint, entity.points[index], entity.points[index + 1])
                        val projectionDistance = distance(rawPoint, projection)
                        if (projectionDistance < bestSegmentDistance) {
                            bestSegmentDistance = projectionDistance
                            bestPointOnSegment = projection
                        }
                    }
                    if (entity.closed && entity.points.size > 2) {
                        val midpoint = Point2(
                            (entity.points.last().x + entity.points.first().x) / 2.0,
                            (entity.points.last().y + entity.points.first().y) / 2.0
                        )
                        val midpointDistance = distance(rawPoint, midpoint)
                        if (midpointDistance < bestMidpointDistance) {
                            bestMidpointDistance = midpointDistance
                            bestMidpoint = midpoint
                        }
                        val projection = projectPointToSegment(rawPoint, entity.points.last(), entity.points.first())
                        val projectionDistance = distance(rawPoint, projection)
                        if (projectionDistance < bestSegmentDistance) {
                            bestSegmentDistance = projectionDistance
                            bestPointOnSegment = projection
                        }
                    }
                }
                else -> Unit
            }
        }
        return when {
            bestVertex != null && bestVertexDistance <= tolerance -> SnapPreview(bestVertex!!, context.getString(R.string.s0018))
            bestMidpoint != null && bestMidpointDistance <= tolerance -> SnapPreview(bestMidpoint!!, context.getString(R.string.s0019))
            bestPointOnSegment != null && bestSegmentDistance <= tolerance -> SnapPreview(bestPointOnSegment!!, context.getString(R.string.s0020))
            else -> SnapPreview(rawPoint, context.getString(R.string.s0017))
        }
    }

    private fun drawSelectionOverlay(canvas: Canvas, bounds: RectBounds, baseScale: Double) {
        val selection = selectedGeometry ?: return
        val a = mapPoint(selection.start, bounds, baseScale)
        val b = mapPoint(selection.end, bounds, baseScale)
        accentPaint.strokeWidth = 9f
        accentPaint.color = Color.parseColor("#8be9fd")
        canvas.drawLine(a.first, a.second, b.first, b.second, accentPaint)
        accentPaint.strokeWidth = 4f
        accentPaint.color = context.getColor(R.color.entityAccent)
        canvas.drawLine(a.first, a.second, b.first, b.second, accentPaint)
        canvas.drawCircle(a.first, a.second, 7f, selectionEndPaint)
        canvas.drawCircle(b.first, b.second, 7f, selectionEndPaint)
        accentPaint.strokeWidth = 2f
        accentPaint.color = context.getColor(R.color.entityAccent)
    }

    private fun screenToModel(x: Float, y: Float): Point2 {
        val doc = document ?: return Point2(0.0, 0.0)
        val bounds = activeBounds(doc)
        val baseScale = baseScale(bounds)
        val scaledWidth = bounds.width * baseScale * zoom
        val scaledHeight = bounds.height * baseScale * zoom
        val contentOffsetX = ((width - scaledWidth) / 2.0).coerceAtLeast(32.0)
        val contentOffsetY = ((height - scaledHeight) / 2.0).coerceAtLeast(32.0)
        val modelX = ((x - contentOffsetX.toFloat() - panX) / (baseScale * zoom)) + bounds.minX
        val modelY = (((height - y) + panY - contentOffsetY.toFloat()) / (baseScale * zoom)) + bounds.minY
        return Point2(modelX, modelY)
    }

    private fun formatDistance(): String? {
        val start = measureStart ?: return null
        val end = measureEnd ?: return null
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dist = sqrt(dx * dx + dy * dy)
        return context.getString(R.string.s0021).format(dist)
    }

    private fun formatAngleState(): String {
        return when (anglePoints.size) {
            0 -> measureHint(MeasureMode.ANGLE)
            1 -> context.getString(R.string.s0022).format(anglePoints[0].x, anglePoints[0].y)
            2 -> context.getString(R.string.s0023)
            else -> context.getString(R.string.s0024).format(computeAngle(anglePoints[0], anglePoints[1], anglePoints[2]))
        }
    }

    private fun formatAreaState(forceClosed: Boolean = false): String {
        if (areaPoints.isEmpty()) return measureHint(MeasureMode.AREA)
        if (areaPoints.size < 2) return context.getString(R.string.s0025, areaPoints.size)
        val closed = forceClosed || (areaAutoClose && areaPoints.size >= 3)
        if (!closed) {
            return context.getString(R.string.s0026, areaPoints.size)
        }
        val area = polygonArea(areaPoints)
        val perimeter = polygonPerimeter(areaPoints, closed = true)
        return context.getString(R.string.s0027).format(area, perimeter)
    }

    private fun measureHint(mode: MeasureMode): String {
        return when (mode) {
            MeasureMode.NONE -> ""
            MeasureMode.DISTANCE -> context.getString(R.string.s0028)
            MeasureMode.ANGLE -> context.getString(R.string.s0029)
            MeasureMode.AREA -> context.getString(R.string.s0030)
        }
    }

    private fun computeAngle(a: Point2, b: Point2, c: Point2): Double {
        val abX = a.x - b.x
        val abY = a.y - b.y
        val cbX = c.x - b.x
        val cbY = c.y - b.y
        val dot = abX * cbX + abY * cbY
        val magA = sqrt(abX * abX + abY * abY)
        val magC = sqrt(cbX * cbX + cbY * cbY)
        if (magA == 0.0 || magC == 0.0) return 0.0
        val cosValue = (dot / (magA * magC)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(cosValue))
    }

    private fun polygonArea(points: List<Point2>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        points.indices.forEach { index ->
            val next = (index + 1) % points.size
            sum += points[index].x * points[next].y
            sum -= points[next].x * points[index].y
        }
        return kotlin.math.abs(sum) / 2.0
    }

    private fun polygonPerimeter(points: List<Point2>, closed: Boolean): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (index in 0 until points.lastIndex) {
            total += distance(points[index], points[index + 1])
        }
        if (closed && points.size > 2) {
            total += distance(points.last(), points.first())
        }
        return total
    }

    private fun distance(a: Point2, b: Point2): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun findNearestSelection(point: Point2): GeometrySelection? {
        val doc = document ?: return null
        var best: GeometrySelection? = null
        var bestDistance = Double.MAX_VALUE
        doc.entities.forEachIndexed { entityIndex, entity ->
            if (entity.layer != null && hiddenLayers.contains(entity.layer)) return@forEachIndexed
            if (!isSelectableLayer(entity.layer)) return@forEachIndexed
            when (entity) {
                is DxfLine -> {
                    val distance = pointToSegmentDistance(point, entity.start, entity.end)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = GeometrySelection(entityIndex, 0, entity.start, entity.end, entity.layer, "LINE")
                    }
                }
                is DxfPolyline -> {
                    for (index in 0 until entity.points.lastIndex) {
                        val start = entity.points[index]
                        val end = entity.points[index + 1]
                        val distance = pointToSegmentDistance(point, start, end)
                        if (distance < bestDistance) {
                            bestDistance = distance
                            best = GeometrySelection(entityIndex, index, start, end, entity.layer, "POLYLINE")
                        }
                    }
                    if (entity.closed && entity.points.size > 2) {
                        val start = entity.points.last()
                        val end = entity.points.first()
                        val distance = pointToSegmentDistance(point, start, end)
                        if (distance < bestDistance) {
                            bestDistance = distance
                            best = GeometrySelection(entityIndex, entity.points.lastIndex, start, end, entity.layer, "POLYLINE")
                        }
                    }
                }
                is DxfArc -> {
                    val radialDistance = distance(point, entity.center)
                    val distanceToArc = abs(radialDistance - entity.radius)
                    val pointAngle = ((Math.toDegrees(kotlin.math.atan2(point.y - entity.center.y, point.x - entity.center.x)) % 360.0) + 360.0) % 360.0
                    val onSweep = isAngleInsideDxfArc(pointAngle, entity.startAngle, entity.endAngle)
                    val effectiveDistance = if (onSweep) {
                        distanceToArc
                    } else {
                        minOf(
                            distance(point, dxfArcPoint(entity.center, entity.radius, entity.startAngle)),
                            distance(point, dxfArcPoint(entity.center, entity.radius, entity.endAngle))
                        )
                    }
                    if (effectiveDistance < bestDistance) {
                        bestDistance = effectiveDistance
                        best = GeometrySelection(
                            entityIndex,
                            0,
                            dxfArcPoint(entity.center, entity.radius, entity.startAngle),
                            dxfArcPoint(entity.center, entity.radius, entity.endAngle),
                            entity.layer,
                            "ARC"
                        )
                    }
                }
                is DxfCircle -> {
                    val radialDistance = distance(point, entity.center)
                    val distanceToCircle = abs(radialDistance - entity.radius)
                    if (distanceToCircle < bestDistance) {
                        bestDistance = distanceToCircle
                        best = GeometrySelection(
                            entityIndex,
                            0,
                            Point2(entity.center.x - entity.radius, entity.center.y),
                            Point2(entity.center.x + entity.radius, entity.center.y),
                            entity.layer,
                            "CIRCLE"
                        )
                    }
                }
                else -> Unit
            }
        }
        return best?.takeIf { bestDistance <= selectionTolerance() }
    }

    private fun dxfArcPoint(center: Point2, radius: Double, angleDegrees: Double): Point2 {
        val angle = Math.toRadians(angleDegrees)
        return Point2(center.x + radius * cos(angle), center.y + radius * sin(angle))
    }

    private fun isAngleInsideDxfArc(angle: Double, start: Double, end: Double): Boolean {
        val normalizedAngle = ((angle % 360.0) + 360.0) % 360.0
        val normalizedStart = ((start % 360.0) + 360.0) % 360.0
        val sweep = normalizeSweep(start, end)
        val delta = ((normalizedAngle - normalizedStart) % 360.0 + 360.0) % 360.0
        return delta <= sweep + 0.0001
    }

    private fun selectionTolerance(): Double {
        val doc = document ?: return 24.0
        val scale = baseScale(doc.bounds) * zoom
        return (40.0 / scale.coerceAtLeast(0.0001))
    }

    private fun isSelectableLayer(layer: String?): Boolean {
        return true
    }

    private fun pointToSegmentDistance(point: Point2, start: Point2, end: Point2): Double {
        return distance(point, projectPointToSegment(point, start, end))
    }

    private fun projectPointToSegment(point: Point2, start: Point2, end: Point2): Point2 {
        val dx = end.x - start.x
        val dy = end.y - start.y
        if (dx == 0.0 && dy == 0.0) return start
        val t = (((point.x - start.x) * dx) + ((point.y - start.y) * dy)) / (dx * dx + dy * dy)
        val clamped = t.coerceIn(0.0, 1.0)
        return Point2(start.x + dx * clamped, start.y + dy * clamped)
    }

    private fun approximateArcSamples(
        start: Point2,
        end: Point2,
        origin: Point2,
        sweepRadians: Double,
        radius: Double
    ): List<Point2> {
        val startAngle = kotlin.math.atan2(start.y - origin.y, start.x - origin.x)
        val steps = maxOf(8, (abs(sweepRadians) / (Math.PI / 18.0)).toInt())
        val points = mutableListOf<Point2>()
        points += start
        for (index in 1 until steps) {
            val t = index.toDouble() / steps.toDouble()
            val angle = startAngle + sweepRadians * t
            points += Point2(
                origin.x + radius * kotlin.math.cos(angle),
                origin.y + radius * kotlin.math.sin(angle)
            )
        }
        points += end
        return points
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val doc = document ?: return true
            val bounds = activeBounds(doc)
            val baseScale = baseScale(bounds)
            val focusModel = screenToModel(detector.focusX, detector.focusY)
            val newZoom = (zoom * detector.scaleFactor).coerceIn(0.25f, 20f)

            val scaledWidth = bounds.width * baseScale * newZoom
            val scaledHeight = bounds.height * baseScale * newZoom
            val contentOffsetX = ((width - scaledWidth) / 2.0).coerceAtLeast(32.0)
            val contentOffsetY = ((height - scaledHeight) / 2.0).coerceAtLeast(32.0)

            panX = (detector.focusX - ((focusModel.x - bounds.minX) * baseScale * newZoom + contentOffsetX)).toFloat()
            panY = (detector.focusY - (height - ((focusModel.y - bounds.minY) * baseScale * newZoom + contentOffsetY))).toFloat()
            zoom = newZoom
            clampPan(bounds, baseScale)
            invalidate()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (measureMode != MeasureMode.NONE) {
                if (measurePanActive || scaleDetector.isInProgress) return true
                val snapped = snapMeasurementPoint(screenToModel(e.x, e.y))
                snapPreview = snapped
                commitMeasurementPoint(snapped.point)
                return true
            }
            val model = screenToModel(e.x, e.y)
            val selection = findNearestSelection(model)
            selectedGeometry = selection
            listener?.onSelectionChanged(selection)
            if (selection == null) {
                listener?.onCanvasTap(model.x, model.y)
            }
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetViewport()
            invalidate()
            return true
        }
    }
}

private data class SnapPreview(
    val point: Point2,
    val label: String
)
