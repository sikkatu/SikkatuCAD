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
    enum class ViewMode {
        TWO_D,
        THREE_D
    }

    interface Listener {
        fun onMeasureUpdated(text: String?)
        fun onSelectionChanged(selection: GeometrySelection?)
        fun onSimulationUpdated(progress: Double, total: Double, speedMultiplier: Float)
        fun onSimulationStateChanged(running: Boolean, paused: Boolean)
        fun onSimulationCompleted()
        fun onSimulationCursorChanged(segmentIndex: Int)
        /** Called on a tap on empty drawing area with model coordinates. */
        fun onCanvasTap(x: Double, y: Double) {}
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.canvasBg)
    }
    private val scene3dBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#050608")
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
    private val genRawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#69747f")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val genIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8a95a3")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }
    private val genMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4fd18b")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }
    private val genBurnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ffb347")
        strokeWidth = 2.8f
        style = Paint.Style.STROKE
    }
    private val genBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7fdcff")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val ncRapidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8a95a3")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
    }
    private val ncCutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ffd166")
        strokeWidth = 2.8f
        style = Paint.Style.STROKE
    }
    private val ncBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66d9ef")
        strokeWidth = 3.2f
        style = Paint.Style.STROKE
    }
    private val ncBevelANegPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4dd0e1")
        strokeWidth = 3.2f
        style = Paint.Style.STROKE
    }
    private val ncBevelBPosPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#81c784")
        strokeWidth = 3.2f
        style = Paint.Style.STROKE
    }
    private val ncBevelBNegPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ff8a65")
        strokeWidth = 3.2f
        style = Paint.Style.STROKE
    }
    private val bevelShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55303a44")
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val bevelHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99ffffff")
        strokeWidth = 1.6f
        style = Paint.Style.STROKE
    }
    private val bevelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3340c4ff")
        style = Paint.Style.FILL
    }
    private val ncSheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5f6b7a")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val technicalEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#e7dccb")
        strokeWidth = 3.6f
        style = Paint.Style.STROKE
    }
    private val technicalGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#efe4d4")
        strokeWidth = 2.4f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 7f), 0f)
        alpha = 215
    }
    private val faceGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2278a6c8")
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }
    private val faceHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88f4eadf")
        strokeWidth = 1.1f
        style = Paint.Style.STROKE
    }
    private val faceFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val faceDepthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5c8f96a3")
        strokeWidth = 3f
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
    private val simulationPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ffd166")
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val simulationHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ff5d5d")
        style = Paint.Style.FILL
    }
    private val laserHeadBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#d9e4ec")
        style = Paint.Style.FILL
    }
    private val laserHeadNozzlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ff6b35")
        style = Paint.Style.FILL
    }
    private val laserHeadAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55ffb347")
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
    private var simulationOperations = emptyList<GenOperation>()
    private var simulationNcProgram: NcProgram? = null
    private var simulationSegments = emptyList<SimulationSegment>()
    private var simulationTotalLength = 0.0
    private var simulationProgress = 0.0
    private var simulationAnimator: ValueAnimator? = null
    private var simulationSpeedMultiplier = 0.35f
    private var simulationRunning = false
    private var simulationPaused = false
    private var simulationCursorIndex = -1
    private var selectedGeometry: GeometrySelection? = null
    private var snapPreview: SnapPreview? = null
    private var viewMode = ViewMode.TWO_D
    private var rotationYaw = -28f
    private var rotationPitch = 24f
    private var measurePanActive = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var ncProgram: NcProgram? = null
    private var ncThickness = 0.0
    private var ncBevelEnabled = true
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

    fun setNc3DSource(program: NcProgram?) {
        ncProgram = program
        ncThickness = program?.header?.get("Thickness")?.toDoubleOrNull() ?: 0.0
        invalidate()
    }

    fun setNcBevelEnabled(enabled: Boolean) {
        ncBevelEnabled = enabled
        invalidate()
    }

    fun setSimulationOperations(operations: List<GenOperation>) {
        simulationOperations = operations
        simulationNcProgram = null
        simulationSegments = buildSimulationSegments(operations)
        simulationTotalLength = simulationSegments.sumOf { it.length }
        simulationProgress = 0.0
        simulationCursorIndex = -1
        simulationAnimator?.cancel()
        simulationRunning = false
        simulationPaused = false
        listener?.onSimulationUpdated(simulationProgress, simulationTotalLength, simulationSpeedMultiplier)
        listener?.onSimulationStateChanged(simulationRunning, simulationPaused)
        listener?.onSimulationCursorChanged(simulationCursorIndex)
        invalidate()
    }

    fun setSimulationNcProgram(program: NcProgram?) {
        simulationNcProgram = program
        simulationOperations = emptyList()
        simulationSegments = if (program == null) emptyList() else buildNcSimulationSegments(program)
        simulationTotalLength = simulationSegments.sumOf { it.length }
        simulationProgress = 0.0
        simulationCursorIndex = -1
        simulationAnimator?.cancel()
        simulationRunning = false
        simulationPaused = false
        listener?.onSimulationUpdated(simulationProgress, simulationTotalLength, simulationSpeedMultiplier)
        listener?.onSimulationStateChanged(simulationRunning, simulationPaused)
        listener?.onSimulationCursorChanged(simulationCursorIndex)
        invalidate()
    }
    fun startSimulation() {
        if (simulationSegments.isEmpty() || simulationTotalLength <= 0.0) return
        simulationAnimator?.cancel()
        simulationRunning = true
        simulationPaused = false
        listener?.onSimulationStateChanged(simulationRunning, simulationPaused)
        val startProgress = simulationProgress.coerceIn(0.0, simulationTotalLength)
        val remainingLength = (simulationTotalLength - startProgress).coerceAtLeast(0.0)
        simulationAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (((remainingLength / 0.35) / simulationSpeedMultiplier).coerceIn(1200.0, 240000.0)).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                val fraction = (it.animatedFraction).toDouble().coerceIn(0.0, 1.0)
                simulationProgress = startProgress + remainingLength * fraction
                listener?.onSimulationUpdated(simulationProgress, simulationTotalLength, simulationSpeedMultiplier)
                val idx = segmentIndexForProgress(simulationProgress)
                if (idx != simulationCursorIndex) {
                    simulationCursorIndex = idx
                    listener?.onSimulationCursorChanged(idx)
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    val finished = simulationRunning && simulationProgress >= simulationTotalLength - 0.001
                    simulationAnimator = null
                    if (finished) {
                        simulationRunning = false
                        simulationPaused = false
                        simulationProgress = 0.0
                        listener?.onSimulationUpdated(simulationProgress, simulationTotalLength, simulationSpeedMultiplier)
                        listener?.onSimulationStateChanged(simulationRunning, simulationPaused)
                        simulationCursorIndex = -1
                        listener?.onSimulationCursorChanged(simulationCursorIndex)
                        listener?.onSimulationCompleted()
                        invalidate()
                    }
                }
            })
            start()
        }
    }


    fun pauseSimulation() {
        simulationRunning = false
        simulationPaused = simulationProgress > 0.0 && simulationProgress < simulationTotalLength
        simulationAnimator?.cancel()
        simulationAnimator = null
        listener?.onSimulationStateChanged(simulationRunning, simulationPaused)
    }

    fun stopSimulation() {
        simulationRunning = false
        simulationPaused = false
        simulationAnimator?.cancel()
        simulationAnimator = null
        simulationProgress = 0.0
        simulationCursorIndex = -1
        listener?.onSimulationUpdated(simulationProgress, simulationTotalLength, simulationSpeedMultiplier)
        listener?.onSimulationStateChanged(simulationRunning, simulationPaused)
        listener?.onSimulationCursorChanged(simulationCursorIndex)
        invalidate()
    }

    fun speedUpSimulation() {
        setSimulationSpeedMultiplier((simulationSpeedMultiplier * 1.25f).coerceAtMost(2.5f))
    }

    fun slowDownSimulation() {
        setSimulationSpeedMultiplier((simulationSpeedMultiplier / 1.25f).coerceAtLeast(0.1f))
    }

    fun setSimulationSpeedMultiplier(multiplier: Float) {
        simulationSpeedMultiplier = multiplier.coerceIn(0.1f, 2.5f)
        listener?.onSimulationUpdated(simulationProgress, simulationTotalLength, simulationSpeedMultiplier)
        if (simulationAnimator?.isRunning == true) startSimulation()
    }

    fun simulationSpeedMultiplier(): Float = simulationSpeedMultiplier

    fun isSimulationRunning(): Boolean = simulationRunning

    fun isSimulationPaused(): Boolean = simulationPaused

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

    fun setViewMode(mode: ViewMode) {
        viewMode = mode
        if (mode == ViewMode.TWO_D) {
            reset3DOrientation()
            panX = 0f
            panY = 0f
            zoom = 1f
        } else {
            panX = 0f
            panY = 0f
            zoom = 1.35f
            reset3DOrientation()
        }
        invalidate()
    }

    fun viewMode(): ViewMode = viewMode

    fun reset3DOrientation() {
        rotationYaw = -28f
        rotationPitch = 24f
        invalidate()
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
        val sceneBackground = if (viewMode == ViewMode.THREE_D) scene3dBackgroundPaint else backgroundPaint
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sceneBackground)
        drawGrid(canvas)
        val doc = document ?: return
        val viewBounds = activeBounds(doc)
        val baseScale = baseScale(viewBounds)
        if (viewMode == ViewMode.THREE_D && ncThickness > 0.0) {
            drawNc3D(canvas, doc, viewBounds, baseScale)
            drawSimulationOverlay(canvas, viewBounds, baseScale)
            return
        }
        if (viewMode == ViewMode.TWO_D && ncProgram != null && isNcPreviewDocument(doc)) {
            drawNcTechnical2D(canvas, doc, viewBounds, baseScale)
            drawSelectionOverlay(canvas, viewBounds, baseScale)
            drawSnapOverlay(canvas, viewBounds, baseScale)
            drawSimulationOverlay(canvas, viewBounds, baseScale)
            drawDimensionOverlay(canvas, viewBounds, baseScale)
            drawMeasureOverlay(canvas, viewBounds, baseScale)
            return
        }
        doc.entities.forEach { entity ->
            if (isLayerHidden(entity.layer)) {
                return@forEach
            }
            if (viewMode == ViewMode.THREE_D && isSuppressedIn3D(entity.layer, entity)) {
                return@forEach
            }
            when (entity) {
                is DxfLine -> {
                    val a = mapPoint(entity.start, viewBounds, baseScale)
                    val b = mapPoint(entity.end, viewBounds, baseScale)
                    if (isNcBevelLayer(entity.layer)) {
                        drawBevelLine(canvas, a.first, a.second, b.first, b.second, entity.layer)
                    } else {
                        canvas.drawLine(a.first, a.second, b.first, b.second, paintForLayer(entity.layer))
                    }
                }

                is DxfPolyline -> {
                    val paint = paintForLayer(entity.layer)
                    for (index in 0 until entity.points.lastIndex) {
                        val a = mapPoint(entity.points[index], viewBounds, baseScale)
                        val b = mapPoint(entity.points[index + 1], viewBounds, baseScale)
                        if (isNcBevelLayer(entity.layer)) {
                            drawBevelLine(canvas, a.first, a.second, b.first, b.second, entity.layer)
                        } else {
                            canvas.drawLine(a.first, a.second, b.first, b.second, paint)
                        }
                    }
                    if (entity.closed && entity.points.size > 2) {
                        val a = mapPoint(entity.points.first(), viewBounds, baseScale)
                        val b = mapPoint(entity.points.last(), viewBounds, baseScale)
                        if (isNcBevelLayer(entity.layer)) {
                            drawBevelLine(canvas, a.first, a.second, b.first, b.second, entity.layer)
                        } else {
                            canvas.drawLine(a.first, a.second, b.first, b.second, paint)
                        }
                    }
                }

                is DxfCircle -> {
                    if (viewMode == ViewMode.THREE_D) {
                        drawProjectedArc(
                            canvas,
                            center = entity.center,
                            radius = entity.radius,
                            startAngle = 0.0,
                            sweep = 360.0,
                            bounds = viewBounds,
                            baseScale = baseScale,
                            layer = entity.layer
                        )
                    } else {
                        val center = mapPoint(entity.center, viewBounds, baseScale)
                        canvas.drawCircle(center.first, center.second, (entity.radius * baseScale * zoom).toFloat(), paintForLayer(entity.layer))
                    }
                }

                is DxfArc -> {
                    val sweep = normalizeSweep(entity.startAngle, entity.endAngle)
                    if (viewMode == ViewMode.THREE_D) {
                        drawProjectedArc(
                            canvas,
                            center = entity.center,
                            radius = entity.radius,
                            startAngle = entity.startAngle,
                            sweep = sweep,
                            bounds = viewBounds,
                            baseScale = baseScale,
                            layer = entity.layer
                        )
                    } else {
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
                        if (isNcBevelLayer(entity.layer)) {
                            drawBevelArc(canvas, rect, screenStart.toFloat(), screenSweep.toFloat(), entity.layer)
                        } else {
                            canvas.drawArc(rect, screenStart.toFloat(), screenSweep.toFloat(), false, paintForLayer(entity.layer))
                        }
                    }
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
        drawSimulationOverlay(canvas, viewBounds, baseScale)
        drawDimensionOverlay(canvas, viewBounds, baseScale)
        drawMeasureOverlay(canvas, viewBounds, baseScale)
    }

    private fun isNcPreviewDocument(document: DxfDocument): Boolean {
        return document.entities.any { entity ->
            entity.layer.orEmpty().uppercase().startsWith("NC_CUT")
        }
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
                } else if (viewMode == ViewMode.THREE_D && event.pointerCount == 1) {
                    rotationYaw += (event.x - lastTouchX) * 0.45f
                    rotationPitch = (rotationPitch - (event.y - lastTouchY) * 0.35f).coerceIn(-88f, 88f)
                    invalidate()
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
        if (viewMode == ViewMode.THREE_D) {
            return
        }
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
        if (viewMode == ViewMode.THREE_D) {
            val longest = maxOf(bounds.width, bounds.height).coerceAtLeast(1.0)
            return (minOf(width, height) * 0.52) / longest
        }
        val sx = (width - 64.0) / bounds.width
        val sy = (height - 64.0) / bounds.height
        return minOf(sx, sy).coerceAtLeast(0.0005)
    }

    private fun activeBounds(document: DxfDocument): RectBounds {
        val focusEntities = if (viewMode == ViewMode.THREE_D) {
            document.entities.filterNot { entity ->
                isLayerHidden(entity.layer) || isSuppressedIn3D(entity.layer, entity)
            }
        } else if (isNcPreviewDocument(document)) {
            document.entities.filterNot { entity ->
                val layer = entity.layer.orEmpty().uppercase()
                isLayerHidden(entity.layer) ||
                    layer.startsWith("NC_SHEET") ||
                    layer.startsWith("NC_BEVEL_LABEL") ||
                    layer.startsWith("NC_RAPID") ||
                    (!ncBevelEnabled && layer.startsWith("NC_BEVEL"))
            }
        } else {
            return document.bounds
        }
        return if (focusEntities.isEmpty()) document.bounds else RectBounds.fromEntities(focusEntities)
    }

    private fun isSuppressedIn3D(layer: String?, entity: DxfEntity): Boolean {
        val value = layer.orEmpty().uppercase()
        return value.startsWith("NC_SHEET") ||
            value.startsWith("NC_BEVEL_LABEL") ||
            (!ncBevelEnabled && value.startsWith("NC_BEVEL")) ||
            value.startsWith("GEN_RAW") ||
            entity is DxfText
    }

    private fun isLayerHidden(layer: String?): Boolean {
        val value = layer?.trim().orEmpty()
        if (value.isEmpty()) return false
        return hiddenLayers.contains(value) || hiddenLayers.contains(value.uppercase())
    }

    private fun mapPoint(point: Point2, bounds: RectBounds, baseScale: Double): Pair<Float, Float> {
        if (viewMode == ViewMode.THREE_D) {
            return mapPoint3D(point, bounds, baseScale)
        }
        val scaledWidth = bounds.width * baseScale * zoom
        val scaledHeight = bounds.height * baseScale * zoom
        val contentOffsetX = ((width - scaledWidth) / 2.0).coerceAtLeast(32.0)
        val contentOffsetY = ((height - scaledHeight) / 2.0).coerceAtLeast(32.0)
        val x = ((point.x - bounds.minX) * baseScale * zoom + contentOffsetX + panX).toFloat()
        val y = (height - ((point.y - bounds.minY) * baseScale * zoom + contentOffsetY) + panY).toFloat()
        return x to y
    }

    private fun mapPoint3D(point: Point2, bounds: RectBounds, baseScale: Double): Pair<Float, Float> {
        return project3D(point.x, point.y, 0.0, bounds, baseScale)
    }

    private fun project3D(x: Double, y: Double, z: Double, bounds: RectBounds, baseScale: Double): Pair<Float, Float> {
        val rotated = rotatePoint3D(x, y, z, bounds)
        return projectRotatedPoint(rotated, baseScale)
    }

    private fun rotatePoint3D(x: Double, y: Double, z: Double, bounds: RectBounds): RotatedPoint3D {
        val centerX = (bounds.minX + bounds.maxX) / 2.0
        val centerY = (bounds.minY + bounds.maxY) / 2.0
        val localX = (x - centerX) * zoom
        val localY = (y - centerY) * zoom
        val localZ = z * zoom
        val yaw = Math.toRadians(rotationYaw.toDouble())
        val pitch = Math.toRadians(rotationPitch.toDouble())
        val rotatedX = localX * cos(yaw) + localZ * sin(yaw)
        val rotatedZ = -localX * sin(yaw) + localZ * cos(yaw)
        val tiltedY = localY * cos(pitch) - rotatedZ * sin(pitch)
        val tiltedZ = localY * sin(pitch) + rotatedZ * cos(pitch)
        return RotatedPoint3D(rotatedX, tiltedY, tiltedZ)
    }

    private fun projectRotatedPoint(point: RotatedPoint3D, baseScale: Double): Pair<Float, Float> {
        val screenX = (width / 2.0 + point.x * baseScale).toFloat()
        val screenY = (height / 2.0 - point.y * baseScale).toFloat()
        return screenX to screenY
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

    private inline fun <T> withProjectionAngles(yaw: Float, pitch: Float, block: () -> T): T {
        val previousYaw = rotationYaw
        val previousPitch = rotationPitch
        rotationYaw = yaw
        rotationPitch = pitch
        return try {
            block()
        } finally {
            rotationYaw = previousYaw
            rotationPitch = previousPitch
        }
    }

    private fun drawNcTechnical2D(canvas: Canvas, document: DxfDocument, bounds: RectBounds, baseScale: Double) {
        document.entities.forEach { entity ->
            if (isLayerHidden(entity.layer)) return@forEach
            val layer = entity.layer.orEmpty().uppercase()
            if (layer.startsWith("NC_RAPID")) return@forEach
            if (layer.startsWith("NC_CUT") || layer.startsWith("NC_BEVEL")) return@forEach
            drawFlat2DEntity(canvas, entity, bounds, baseScale)
        }
        if (!isLayerHidden("NC_RAPID")) {
            document.entities.forEach { entity ->
                if (entity.layer.orEmpty().uppercase().startsWith("NC_RAPID")) {
                    drawFlat2DEntity(canvas, entity, bounds, baseScale)
                }
            }
        }
        val program = ncProgram
        if (program == null) {
            document.entities.forEach { entity ->
                if (isLayerHidden(entity.layer)) return@forEach
                if (entity.layer.orEmpty().uppercase().startsWith("NC_CUT")) {
                    drawFlat2DEntity(canvas, entity, bounds, baseScale)
                }
            }
            return
        }
        document.entities.forEach { entity ->
            if (isLayerHidden(entity.layer)) return@forEach
            if (entity.layer.orEmpty().uppercase().startsWith("NC_CUT")) {
                drawFlat2DEntity(canvas, entity, bounds, baseScale)
            }
        }
        if (!ncBevelEnabled) {
            return
        }
        val segments = buildNc3DSegments(program)
        segments.forEach { segment ->
            if (segment.topOuter != null && (segment.topLayer == null || !isLayerHidden(segment.topLayer))) {
                val topPaint = paintForLayer(segment.topLayer)
                drawFlatNcMove(canvas, segment.center, bounds, baseScale, topPaint)
            }
            if (segment.bottomOuter != null && (segment.bottomLayer == null || !isLayerHidden(segment.bottomLayer))) {
                val bottomPaint = paintForLayer(segment.bottomLayer)
                drawFlatNcMove(canvas, segment.center, bounds, baseScale, bottomPaint)
            }
        }
        document.entities.filterIsInstance<DxfText>().forEach { text ->
            if (isLayerHidden(text.layer)) return@forEach
            if (!text.layer.orEmpty().uppercase().startsWith("NC_BEVEL_LABEL")) return@forEach
            if (!ncBevelEnabled) return@forEach
            drawFlat2DEntity(canvas, text, bounds, baseScale)
        }
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

    private fun drawFlatNcMove(canvas: Canvas, move: NcMove, bounds: RectBounds, baseScale: Double, paint: Paint) {
        if (move.centerOffset != null) {
            val center = Point2(move.start.x + move.centerOffset.x, move.start.y + move.centerOffset.y)
            val radius = sqrt(move.centerOffset.x * move.centerOffset.x + move.centerOffset.y * move.centerOffset.y)
            val startAngle = Math.toDegrees(kotlin.math.atan2(move.start.y - center.y, move.start.x - center.x))
            val endAngle = Math.toDegrees(kotlin.math.atan2(move.end.y - center.y, move.end.x - center.x))
            val mappedCenter = mapPoint(center, bounds, baseScale)
            val mappedRadius = (radius * baseScale * zoom).toFloat()
            val rect = RectF(
                mappedCenter.first - mappedRadius,
                mappedCenter.second - mappedRadius,
                mappedCenter.first + mappedRadius,
                mappedCenter.second + mappedRadius
            )
            val screenStart = cadAngleToCanvasAngle(startAngle)
            val screenSweep = cadSweepToCanvasSweep(normalizeSweep(startAngle, endAngle))
            canvas.drawArc(rect, screenStart.toFloat(), screenSweep.toFloat(), false, paint)
        } else {
            val a = mapPoint(move.start, bounds, baseScale)
            val b = mapPoint(move.end, bounds, baseScale)
            canvas.drawLine(a.first, a.second, b.first, b.second, paint)
        }
    }

    private fun drawNc3D(canvas: Canvas, document: DxfDocument, bounds: RectBounds, baseScale: Double) {
        val thickness = ncThickness.takeIf { it > 0.0 } ?: 20.0
        val halfThickness = thickness / 2.0
        val program = ncProgram
        val faces = mutableListOf<Face3D>()
        val outerEdges = mutableListOf<Edge3D>()
        if (program != null) {
            if (!ncBevelEnabled) {
                val centerMoves = buildCenterCutMoves(document)
                val cutFaces = centerMoves.map { move ->
                    createFace(move.start, move.end, halfThickness, move.end, move.start, -halfThickness, Color.parseColor("#d7d3cb"))
                }
                drawFaces(canvas, cutFaces, bounds, baseScale)
                centerMoves.forEach { cut ->
                    outerEdges += Edge3D(cut.start, cut.end, halfThickness, halfThickness, technicalEdgePaint)
                    outerEdges += Edge3D(cut.start, cut.end, -halfThickness, -halfThickness, faceDepthPaint)
                    outerEdges += Edge3D(cut.start, cut.start, halfThickness, -halfThickness, faceDepthPaint)
                    outerEdges += Edge3D(cut.end, cut.end, halfThickness, -halfThickness, faceDepthPaint)
                }
                draw3DGuideFrame(canvas, bounds, baseScale, halfThickness)
                draw3DEdges(canvas, bounds, baseScale, outerEdges)
                drawOrientationAxes(canvas)
                return
            }
            val segments = buildNc3DSegments(program)
            segments.forEach { segment ->
                if (segment.topOuter != null && segment.bottomOuter != null) {
                    faces += createFace(
                        segment.topOuter.start,
                        segment.topOuter.end,
                        halfThickness,
                        segment.center.end,
                        segment.center.start,
                        0.0,
                        bevelFaceColor(segment.topLayer, null)
                    )
                    faces += createFace(
                        segment.center.start,
                        segment.center.end,
                        0.0,
                        segment.topOuter.end,
                        segment.topOuter.start,
                        -halfThickness,
                        bevelFaceColor(segment.topLayer, null)
                    )
                    faces += createFace(
                        segment.bottomOuter.start,
                        segment.bottomOuter.end,
                        halfThickness,
                        segment.center.end,
                        segment.center.start,
                        0.0,
                        bevelFaceColor(null, segment.bottomLayer)
                    )
                    faces += createFace(
                        segment.center.start,
                        segment.center.end,
                        0.0,
                        segment.bottomOuter.end,
                        segment.bottomOuter.start,
                        -halfThickness,
                        bevelFaceColor(null, segment.bottomLayer)
                    )
                    outerEdges += Edge3D(segment.topOuter.start, segment.topOuter.end, halfThickness, halfThickness, technicalEdgePaint)
                    outerEdges += Edge3D(segment.topOuter.start, segment.topOuter.end, -halfThickness, -halfThickness, faceDepthPaint)
                    outerEdges += Edge3D(segment.bottomOuter.start, segment.bottomOuter.end, halfThickness, halfThickness, technicalEdgePaint)
                    outerEdges += Edge3D(segment.center.start, segment.center.end, 0.0, 0.0, technicalEdgePaint)
                    outerEdges += Edge3D(segment.bottomOuter.start, segment.bottomOuter.end, -halfThickness, -halfThickness, faceDepthPaint)
                    outerEdges += Edge3D(segment.topOuter.start, segment.center.start, halfThickness, 0.0, faceDepthPaint)
                    outerEdges += Edge3D(segment.topOuter.end, segment.center.end, halfThickness, 0.0, faceDepthPaint)
                    outerEdges += Edge3D(segment.topOuter.start, segment.center.start, -halfThickness, 0.0, faceDepthPaint)
                    outerEdges += Edge3D(segment.topOuter.end, segment.center.end, -halfThickness, 0.0, faceDepthPaint)
                    outerEdges += Edge3D(segment.bottomOuter.start, segment.center.start, halfThickness, 0.0, faceDepthPaint)
                    outerEdges += Edge3D(segment.bottomOuter.end, segment.center.end, halfThickness, 0.0, faceDepthPaint)
                    outerEdges += Edge3D(segment.center.start, segment.bottomOuter.start, 0.0, -halfThickness, faceDepthPaint)
                    outerEdges += Edge3D(segment.center.end, segment.bottomOuter.end, 0.0, -halfThickness, faceDepthPaint)
                } else if (segment.topOuter != null) {
                    faces += createFace(
                        segment.topOuter.start,
                        segment.topOuter.end,
                        halfThickness,
                        segment.center.end,
                        segment.center.start,
                        0.0,
                        bevelFaceColor(segment.topLayer, null)
                    )
                    faces += createFace(
                        segment.center.start,
                        segment.center.end,
                        0.0,
                        segment.center.end,
                        segment.center.start,
                        -halfThickness,
                        Color.parseColor("#d7d3cb")
                    )
                    outerEdges += Edge3D(segment.topOuter.start, segment.topOuter.end, halfThickness, halfThickness, technicalEdgePaint)
                    outerEdges += Edge3D(segment.center.start, segment.center.end, -halfThickness, -halfThickness, faceDepthPaint)
                    outerEdges += Edge3D(segment.topOuter.start, segment.center.start, halfThickness, 0.0, faceDepthPaint)
                    outerEdges += Edge3D(segment.topOuter.end, segment.center.end, halfThickness, 0.0, faceDepthPaint)
                    outerEdges += Edge3D(segment.center.start, segment.center.start, 0.0, -halfThickness, faceDepthPaint)
                    outerEdges += Edge3D(segment.center.end, segment.center.end, 0.0, -halfThickness, faceDepthPaint)
                } else if (segment.bottomOuter != null) {
                    faces += createFace(
                        segment.center.start,
                        segment.center.end,
                        halfThickness,
                        segment.center.end,
                        segment.center.start,
                        0.0,
                        Color.parseColor("#d7d3cb")
                    )
                    faces += createFace(
                        segment.bottomOuter.start,
                        segment.bottomOuter.end,
                        0.0,
                        segment.center.end,
                        segment.center.start,
                        -halfThickness,
                        bevelFaceColor(null, segment.bottomLayer)
                    )
                    outerEdges += Edge3D(segment.center.start, segment.center.end, halfThickness, halfThickness, technicalEdgePaint)
                    outerEdges += Edge3D(segment.bottomOuter.start, segment.bottomOuter.end, -halfThickness, -halfThickness, faceDepthPaint)
                    outerEdges += Edge3D(segment.center.start, segment.bottomOuter.start, 0.0, -halfThickness, faceDepthPaint)
                    outerEdges += Edge3D(segment.center.end, segment.bottomOuter.end, 0.0, -halfThickness, faceDepthPaint)
                    outerEdges += Edge3D(segment.center.start, segment.center.start, halfThickness, 0.0, faceDepthPaint)
                    outerEdges += Edge3D(segment.center.end, segment.center.end, halfThickness, 0.0, faceDepthPaint)
                } else {
                    faces += createFace(segment.center.start, segment.center.end, halfThickness, segment.center.end, segment.center.start, -halfThickness, Color.parseColor("#d7d3cb"))
                    outerEdges += Edge3D(segment.center.start, segment.center.end, halfThickness, halfThickness, technicalEdgePaint)
                    outerEdges += Edge3D(segment.center.start, segment.center.end, -halfThickness, -halfThickness, faceDepthPaint)
                    outerEdges += Edge3D(segment.center.start, segment.center.start, halfThickness, -halfThickness, faceDepthPaint)
                    outerEdges += Edge3D(segment.center.end, segment.center.end, halfThickness, -halfThickness, faceDepthPaint)
                }
            }
            drawFaces(canvas, faces, bounds, baseScale)
            draw3DGuideFrame(canvas, bounds, baseScale, halfThickness)
            draw3DEdges(canvas, bounds, baseScale, outerEdges)
            drawOrientationAxes(canvas)
            return
        }
        val cutLines = document.entities.filterIsInstance<DxfLine>().filter { it.layer.orEmpty().uppercase().startsWith("NC_CUT") }
        val fallbackFaces = cutLines.map {
            createFace(it.start, it.end, halfThickness, it.end, it.start, -halfThickness, Color.parseColor("#666b7682"))
        }
        drawFaces(canvas, fallbackFaces, bounds, baseScale)
        cutLines.forEach { cut ->
            outerEdges += Edge3D(cut.start, cut.end, halfThickness, halfThickness, technicalEdgePaint)
            outerEdges += Edge3D(cut.start, cut.end, -halfThickness, -halfThickness, faceDepthPaint)
            outerEdges += Edge3D(cut.start, cut.start, halfThickness, -halfThickness, faceDepthPaint)
            outerEdges += Edge3D(cut.end, cut.end, halfThickness, -halfThickness, faceDepthPaint)
        }
        draw3DGuideFrame(canvas, bounds, baseScale, halfThickness)
        draw3DEdges(canvas, bounds, baseScale, outerEdges)
        drawOrientationAxes(canvas)
    }

    private fun buildCenterCutMoves(document: DxfDocument): List<NcMove> {
        val moves = mutableListOf<NcMove>()
        document.entities.forEach { entity ->
            if (!entity.layer.orEmpty().uppercase().startsWith("NC_CUT")) return@forEach
            when (entity) {
                is DxfLine -> moves += NcMove(
                    start = entity.start,
                    end = entity.end,
                    rapid = false
                )
                is DxfArc -> {
                    val samples = approximateDxfArcSamples(entity)
                    for (index in 0 until samples.lastIndex) {
                        moves += NcMove(
                            start = samples[index],
                            end = samples[index + 1],
                            rapid = false
                        )
                    }
                }
                else -> Unit
            }
        }
        return moves
    }

    private fun approximateDxfArcSamples(arc: DxfArc): List<Point2> {
        val startAngle = Math.toRadians(arc.startAngle)
        var sweep = Math.toRadians(normalizeSweep(arc.startAngle, arc.endAngle))
        if (abs(sweep) < 0.0001) {
            sweep = Math.PI * 2.0
        }
        val steps = maxOf(12, (abs(sweep) / (Math.PI / 24.0)).toInt())
        val samples = mutableListOf<Point2>()
        for (index in 0..steps) {
            val t = index.toDouble() / steps.toDouble()
            val angle = startAngle + sweep * t
            samples += Point2(
                arc.center.x + arc.radius * cos(angle),
                arc.center.y + arc.radius * sin(angle)
            )
        }
        return samples
    }

    private fun buildNc3DSegments(program: NcProgram): List<Nc3DSegment> {
        val sourceMoves = flattenNcMoves(program.moves.filter { it.cutting })
        val components = buildNcMoveComponents(sourceMoves)
        val componentCentroids = buildNcComponentCentroids(sourceMoves, components)
        val segments = mutableListOf<Nc3DSegment>()
        val used = mutableSetOf<Int>()
        val thickness = ncThickness.takeIf { it > 0.0 } ?: 20.0
        sourceMoves.forEachIndexed { index, move ->
            if (move.rapid || index in used) return@forEachIndexed
            val componentId = components.getOrElse(index) { -1 }
            val componentCentroid = componentCentroids[componentId] ?: moveMidpoint(move)
            if (!hasNcBevel(move)) {
                segments += Nc3DSegment(center = move)
                return@forEachIndexed
            }
            val family = bevelFamily(move) ?: run {
                segments += Nc3DSegment(center = move)
                return@forEachIndexed
            }
            val sourceAngle = bevelValue(move, family) ?: 0.0
            val matchIndex = findMatchingNc3DMove(sourceMoves, components, index, family, sourceAngle, thickness)
            if (matchIndex == null) {
                segments += buildSingleBevelSegment(move, family, sourceAngle, thickness, componentCentroid)
                return@forEachIndexed
            }
            val match = sourceMoves[matchIndex]
            used += index
            used += matchIndex
            val provisionalCenter = midpointNcMove(move, match)
            val alignedSource = alignMoveToReference(provisionalCenter, move)
            val alignedMatch = alignMoveToReference(provisionalCenter, match)
            val sourceDistance = midpointDistanceToPoint(alignedSource, componentCentroid)
            val matchDistance = midpointDistanceToPoint(alignedMatch, componentCentroid)
            val outward = if (sourceDistance >= matchDistance) alignedSource else alignedMatch
            val inward = if (outward === alignedSource) alignedMatch else alignedSource
            val outwardAngle = bevelValue(outward, family) ?: sourceAngle
            segments += if (outwardAngle > 0.0) {
                Nc3DSegment(
                    center = stripNcMoveBevel(inward),
                    topOuter = stripNcMoveBevel(outward),
                    topLayer = layerForBevelValue(family, outwardAngle)
                )
            } else {
                Nc3DSegment(
                    center = stripNcMoveBevel(inward),
                    bottomOuter = stripNcMoveBevel(outward),
                    bottomLayer = layerForBevelValue(family, outwardAngle)
                )
            }
        }
        return segments
    }

    private fun buildSingleBevelSegment(
        move: NcMove,
        family: String,
        angle: Double,
        thickness: Double,
        componentCentroid: Point2
    ): Nc3DSegment {
        val center = stripNcMoveBevel(move)
        val layer = layerForBevelValue(family, angle)
        val outwardSign = outwardNormalSign(center, componentCentroid)
        val outer = offsetNcMove(center, outwardSign * singleBevelOffsetDistance(angle, thickness))
        return if (angle > 0.0) {
            Nc3DSegment(center = center, topOuter = outer, topLayer = layer)
        } else {
            Nc3DSegment(center = center, bottomOuter = outer, bottomLayer = layer)
        }
    }

    private fun flattenNcMoves(moves: List<NcMove>): List<NcMove> {
        val flattened = mutableListOf<NcMove>()
        moves.forEach { move ->
            if (move.centerOffset == null) {
                flattened += move
            } else {
                val samples = approximateNcArcSamples(move)
                for (index in 0 until samples.lastIndex) {
                    flattened += move.copy(
                        start = samples[index],
                        end = samples[index + 1],
                        centerOffset = null
                    )
                }
            }
        }
        return flattened
    }

    private fun approximateNcArcSamples(move: NcMove): List<Point2> {
        val centerOffset = move.centerOffset ?: return listOf(move.start, move.end)
        val center = Point2(move.start.x + centerOffset.x, move.start.y + centerOffset.y)
        val radius = sqrt(centerOffset.x * centerOffset.x + centerOffset.y * centerOffset.y).coerceAtLeast(0.0001)
        val startAngle = kotlin.math.atan2(move.start.y - center.y, move.start.x - center.x)
        var endAngle = kotlin.math.atan2(move.end.y - center.y, move.end.x - center.x)
        var sweep = endAngle - startAngle
        if (move.clockwise && sweep >= 0.0) {
            sweep -= Math.PI * 2.0
        } else if (!move.clockwise && sweep <= 0.0) {
            sweep += Math.PI * 2.0
        }
        val steps = maxOf(12, (abs(sweep) / (Math.PI / 24.0)).toInt())
        val samples = mutableListOf<Point2>()
        for (index in 0..steps) {
            val t = index.toDouble() / steps.toDouble()
            val angle = startAngle + sweep * t
            samples += Point2(
                center.x + radius * cos(angle),
                center.y + radius * sin(angle)
            )
        }
        return samples
    }

    private fun findMatchingNc3DMove(
        moves: List<NcMove>,
        components: IntArray,
        index: Int,
        family: String,
        sourceAngle: Double,
        thickness: Double
    ): Int? {
        val source = moves[index]
        val sourceComponent = components.getOrElse(index) { -1 }
        val tangent = normalizedDirection(source.start, source.end)
        val sourceLength = segmentLength(source)
        var bestIndex: Int? = null
        var bestDistance = Double.MAX_VALUE
        for (candidateIndex in index + 1 until moves.size) {
            val candidate = moves[candidateIndex]
            if (candidate.rapid) continue
             if (components.getOrElse(candidateIndex) { -1 } != sourceComponent) continue
            if (bevelFamily(candidate) != family) continue
            val candidateAngle = bevelValue(candidate, family) ?: continue
            if (sourceAngle * candidateAngle >= 0.0) continue
            if (abs(abs(sourceAngle) - abs(candidateAngle)) > 0.5) continue
            val candidateDir = normalizedDirection(candidate.start, candidate.end)
            val parallel = abs(tangent.first * candidateDir.first + tangent.second * candidateDir.second)
            if (parallel < 0.995) continue
            val overlapRatio = tangentOverlapRatio(source, candidate, tangent)
            if (overlapRatio < 0.82) continue
            val offset = normalOffsetDistance(source, candidate, tangent)
            if (offset <= 0.01) continue
            val maxAllowedOffset = (thickness * 3.6).coerceAtLeast(12.0)
            if (offset > maxAllowedOffset) continue
            val distance = offset + abs(sourceLength - segmentLength(candidate)) * 0.25
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = candidateIndex
            }
        }
        return bestIndex
    }

    private fun buildNcMoveComponents(moves: List<NcMove>): IntArray {
        if (moves.isEmpty()) return IntArray(0)
        val components = IntArray(moves.size) { -1 }
        var nextComponent = 0
        moves.indices.forEach seedLoop@{ seed ->
            if (components[seed] != -1) return@seedLoop
            val queue = ArrayDeque<Int>()
            queue += seed
            components[seed] = nextComponent
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val source = moves[index]
                moves.indices.forEach candidateLoop@{ candidateIndex ->
                    if (components[candidateIndex] != -1) return@candidateLoop
                    if (!areNcMovesConnected(source, moves[candidateIndex])) return@candidateLoop
                    components[candidateIndex] = nextComponent
                    queue += candidateIndex
                }
            }
            nextComponent++
        }
        return components
    }

    private fun buildNcComponentCentroids(moves: List<NcMove>, components: IntArray): Map<Int, Point2> {
        val grouped = linkedMapOf<Int, MutableList<Point2>>()
        moves.forEachIndexed { index, move ->
            val component = components.getOrElse(index) { -1 }
            grouped.getOrPut(component) { mutableListOf() } += moveMidpoint(move)
        }
        return grouped.mapValues { (_, points) ->
            Point2(
                points.sumOf { it.x } / points.size.coerceAtLeast(1),
                points.sumOf { it.y } / points.size.coerceAtLeast(1)
            )
        }
    }

    private fun areNcMovesConnected(a: NcMove, b: NcMove, tolerance: Double = 0.8): Boolean {
        return distance(a.start, b.start) <= tolerance ||
            distance(a.start, b.end) <= tolerance ||
            distance(a.end, b.start) <= tolerance ||
            distance(a.end, b.end) <= tolerance
    }

    private fun singleBevelOffsetDistance(angle: Double, thickness: Double): Double {
        val projected = thickness * tan(Math.toRadians(abs(angle).coerceAtMost(80.0)))
        return projected.coerceIn(thickness * 0.18, thickness * 1.6)
    }

    private fun bevelOffsetSign(angle: Double): Double = if (angle < 0.0) -1.0 else 1.0

    private fun offsetNcMove(move: NcMove, signedOffset: Double): NcMove {
        val direction = normalizedDirection(move.start, move.end)
        val normalX = -direction.second
        val normalY = direction.first
        val dx = normalX * signedOffset
        val dy = normalY * signedOffset
        return move.copy(
            start = Point2(move.start.x + dx, move.start.y + dy),
            end = Point2(move.end.x + dx, move.end.y + dy),
            centerOffset = null,
            bevelA = null,
            bevelB = null
        )
    }

    private fun outwardNormalSign(move: NcMove, centroid: Point2): Double {
        val direction = normalizedDirection(move.start, move.end)
        val normalX = -direction.second
        val normalY = direction.first
        val midpoint = moveMidpoint(move)
        val toMidX = midpoint.x - centroid.x
        val toMidY = midpoint.y - centroid.y
        return if (toMidX * normalX + toMidY * normalY >= 0.0) 1.0 else -1.0
    }

    private fun midpointNcMove(a: NcMove, b: NcMove): NcMove {
        val alignedB = alignMoveToReference(a, b)
        return a.copy(
            start = Point2((a.start.x + alignedB.start.x) / 2.0, (a.start.y + alignedB.start.y) / 2.0),
            end = Point2((a.end.x + alignedB.end.x) / 2.0, (a.end.y + alignedB.end.y) / 2.0),
            centerOffset = null,
            bevelA = null,
            bevelB = null
        )
    }

    private fun alignMoveToReference(reference: NcMove, candidate: NcMove): NcMove {
        val refDir = normalizedDirection(reference.start, reference.end)
        val candidateDir = normalizedDirection(candidate.start, candidate.end)
        return if (refDir.first * candidateDir.first + refDir.second * candidateDir.second >= 0.0) {
            candidate
        } else {
            candidate.copy(start = candidate.end, end = candidate.start)
        }
    }

    private fun normalizedLineDirection(line: DxfLine): Pair<Double, Double> {
        val dx = line.end.x - line.start.x
        val dy = line.end.y - line.start.y
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001)
        return dx / length to dy / length
    }

    private fun midpointDistance(a: DxfLine, b: DxfLine): Double {
        val ax = (a.start.x + a.end.x) / 2.0
        val ay = (a.start.y + a.end.y) / 2.0
        val bx = (b.start.x + b.end.x) / 2.0
        val by = (b.start.y + b.end.y) / 2.0
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private fun normalizedDirection(start: Point2, end: Point2): Pair<Double, Double> {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001)
        return dx / length to dy / length
    }

    private fun segmentLength(move: NcMove): Double {
        val dx = move.end.x - move.start.x
        val dy = move.end.y - move.start.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun moveMidpoint(move: NcMove): Point2 {
        return Point2((move.start.x + move.end.x) / 2.0, (move.start.y + move.end.y) / 2.0)
    }

    private fun midpointDistanceToPoint(move: NcMove, point: Point2): Double {
        return distance(moveMidpoint(move), point)
    }

    private fun stripNcMoveBevel(move: NcMove): NcMove {
        return move.copy(centerOffset = null, bevelA = null, bevelB = null)
    }

    private fun tangentOverlapRatio(source: NcMove, candidate: NcMove, tangent: Pair<Double, Double>): Double {
        val sourceRange = projectedRange(source, tangent)
        val candidateRange = projectedRange(candidate, tangent)
        val overlap = minOf(sourceRange.second, candidateRange.second) - maxOf(sourceRange.first, candidateRange.first)
        if (overlap <= 0.0) return 0.0
        val sourceLength = (sourceRange.second - sourceRange.first).coerceAtLeast(0.0001)
        val candidateLength = (candidateRange.second - candidateRange.first).coerceAtLeast(0.0001)
        return overlap / minOf(sourceLength, candidateLength)
    }

    private fun projectedRange(move: NcMove, tangent: Pair<Double, Double>): Pair<Double, Double> {
        val startProjection = move.start.x * tangent.first + move.start.y * tangent.second
        val endProjection = move.end.x * tangent.first + move.end.y * tangent.second
        return minOf(startProjection, endProjection) to maxOf(startProjection, endProjection)
    }

    private fun normalOffsetDistance(source: NcMove, candidate: NcMove, tangent: Pair<Double, Double>): Double {
        val normal = -tangent.second to tangent.first
        val sourceStart = source.start.x * normal.first + source.start.y * normal.second
        val sourceEnd = source.end.x * normal.first + source.end.y * normal.second
        val candidateStart = candidate.start.x * normal.first + candidate.start.y * normal.second
        val candidateEnd = candidate.end.x * normal.first + candidate.end.y * normal.second
        val sourceAverage = (sourceStart + sourceEnd) / 2.0
        val candidateAverage = (candidateStart + candidateEnd) / 2.0
        return abs(candidateAverage - sourceAverage)
    }

    private fun hasNcBevel(move: NcMove): Boolean = (move.bevelA ?: 0.0) != 0.0 || (move.bevelB ?: 0.0) != 0.0

    private fun bevelFamily(move: NcMove): String? = when {
        (move.bevelA ?: 0.0) != 0.0 -> "A"
        (move.bevelB ?: 0.0) != 0.0 -> "B"
        else -> null
    }

    private fun bevelValue(move: NcMove, family: String): Double? = when (family) {
        "A" -> move.bevelA
        "B" -> move.bevelB
        else -> null
    }

    private fun layerForBevelValue(family: String, angle: Double?): String? {
        val value = angle ?: return null
        return when (family) {
            "A" -> if (value > 0.0) "NC_BEVEL_A_POS" else "NC_BEVEL_A_NEG"
            "B" -> if (value > 0.0) "NC_BEVEL_B_POS" else "NC_BEVEL_B_NEG"
            else -> null
        }
    }

    private fun createFace(
        a: Point2,
        b: Point2,
        z1: Double,
        c: Point2,
        d: Point2,
        z2: Double,
        color: Int
    ): Face3D {
        return Face3D(
            vertices = listOf(
                Point3(a.x, a.y, z1),
                Point3(b.x, b.y, z1),
                Point3(c.x, c.y, z2),
                Point3(d.x, d.y, z2)
            ),
            fillColor = color
        )
    }

    private fun drawFaces(canvas: Canvas, faces: List<Face3D>, bounds: RectBounds, baseScale: Double) {
        faces.sortedBy { face ->
            face.vertices.map { rotatePoint3D(it.x, it.y, it.z, bounds).depth }.average()
        }.forEach { face ->
            val rotatedVertices = face.vertices.map { rotatePoint3D(it.x, it.y, it.z, bounds) }
            val path = Path()
            face.vertices.map { project3D(it.x, it.y, it.z, bounds, baseScale) }.forEachIndexed { index, point ->
                if (index == 0) {
                    path.moveTo(point.first, point.second)
                } else {
                    path.lineTo(point.first, point.second)
                }
            }
            path.close()
            faceFillPaint.color = shadeFaceColor(face.fillColor, rotatedVertices)
            canvas.drawPath(path, faceFillPaint)
        }
    }

    private fun draw3DEdges(canvas: Canvas, bounds: RectBounds, baseScale: Double, outerEdges: List<Edge3D>) {
        outerEdges.forEach { edge ->
            draw3DLine(canvas, edge.start, edge.end, edge.startZ, edge.endZ, bounds, baseScale, faceGlowPaint)
            draw3DLine(canvas, edge.start, edge.end, edge.startZ, edge.endZ, bounds, baseScale, edge.paint)
            draw3DLine(canvas, edge.start, edge.end, edge.startZ, edge.endZ, bounds, baseScale, faceHighlightPaint)
        }
    }

    private fun draw3DGuideFrame(canvas: Canvas, bounds: RectBounds, baseScale: Double, halfThickness: Double) {
        val corners = listOf(
            Point2(bounds.minX, bounds.minY),
            Point2(bounds.maxX, bounds.minY),
            Point2(bounds.maxX, bounds.maxY),
            Point2(bounds.minX, bounds.maxY)
        )
        val top = corners.map { project3D(it.x, it.y, halfThickness, bounds, baseScale) }
        val bottom = corners.map { project3D(it.x, it.y, -halfThickness, bounds, baseScale) }
        drawGuideLoop(canvas, top)
        drawGuideLoop(canvas, bottom)
        corners.indices.forEach { index ->
            val a = top[index]
            val b = bottom[index]
            canvas.drawLine(a.first, a.second, b.first, b.second, technicalGuidePaint)
        }
    }

    private fun drawGuideLoop(canvas: Canvas, points: List<Pair<Float, Float>>) {
        if (points.size < 2) return
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            canvas.drawLine(current.first, current.second, next.first, next.second, technicalGuidePaint)
        }
    }

    private fun drawOrientationAxes(canvas: Canvas) {
        val originX = width - 116f
        val originY = height - 132f
        val axisLength = 42f
        canvas.drawLine(originX, originY, originX + axisLength, originY, axisXPaint)
        canvas.drawLine(originX, originY, originX, originY - axisLength, axisYPaint)
        canvas.drawLine(originX, originY, originX + axisLength * 0.34f, originY + axisLength * 0.82f, axisZPaint)
    }

    private fun shadeFaceColor(baseColor: Int, rotatedVertices: List<RotatedPoint3D>): Int {
        if (rotatedVertices.size < 3) return baseColor
        val a = rotatedVertices[0]
        val b = rotatedVertices[1]
        val c = rotatedVertices[2]
        val abx = b.x - a.x
        val aby = b.y - a.y
        val abz = b.depth - a.depth
        val acx = c.x - a.x
        val acy = c.y - a.y
        val acz = c.depth - a.depth
        val normalZ = abx * acy - aby * acx
        val normalY = abz * acx - abx * acz
        val normalX = aby * acz - abz * acy
        val intensity = (0.46 + abs(normalY) * 0.38 + abs(normalZ) * 0.24 + abs(normalX) * 0.14).coerceIn(0.34, 0.92)
        return multiplyColor(baseColor, intensity.toFloat())
    }

    private fun multiplyColor(color: Int, factor: Float): Int {
        val alpha = Color.alpha(color)
        val red = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val green = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val blue = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, red, green, blue)
    }

    private fun draw3DLine(
        canvas: Canvas,
        start: Point2,
        end: Point2,
        startZ: Double,
        endZ: Double,
        bounds: RectBounds,
        baseScale: Double,
        paint: Paint
    ) {
        val a = project3D(start.x, start.y, startZ, bounds, baseScale)
        val b = project3D(end.x, end.y, endZ, bounds, baseScale)
        val edgePaint = Paint(paint).apply { pathEffect = null }
        canvas.drawLine(a.first, a.second, b.first, b.second, edgePaint)
    }

    private fun bevelFaceColor(topLayer: String?, bottomLayer: String?): Int {
        return when {
            topLayer.orEmpty().contains("A_POS") || bottomLayer.orEmpty().contains("A_POS") -> Color.parseColor("#3e8ea2")
            topLayer.orEmpty().contains("A_NEG") || bottomLayer.orEmpty().contains("A_NEG") -> Color.parseColor("#276f86")
            topLayer.orEmpty().contains("B_POS") || bottomLayer.orEmpty().contains("B_POS") -> Color.parseColor("#558c58")
            topLayer.orEmpty().contains("B_NEG") || bottomLayer.orEmpty().contains("B_NEG") -> Color.parseColor("#9a5c47")
            else -> Color.parseColor("#6f7884")
        }
    }

    private fun drawProjectedArc(
        canvas: Canvas,
        center: Point2,
        radius: Double,
        startAngle: Double,
        sweep: Double,
        bounds: RectBounds,
        baseScale: Double,
        layer: String?
    ) {
        val steps = maxOf(20, (abs(sweep) / 8.0).toInt())
        var previous: Pair<Float, Float>? = null
        for (index in 0..steps) {
            val angle = Math.toRadians(startAngle + sweep * (index.toDouble() / steps.toDouble()))
            val point = Point2(
                center.x + radius * cos(angle),
                center.y + radius * sin(angle)
            )
            val mapped = mapPoint3D(point, bounds, baseScale)
            previous?.let { prev ->
                if (isNcBevelLayer(layer)) {
                    drawBevelLine(canvas, prev.first, prev.second, mapped.first, mapped.second, layer)
                } else {
                    canvas.drawLine(prev.first, prev.second, mapped.first, mapped.second, paintForLayer(layer))
                }
            }
            previous = mapped
        }
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
            value.startsWith("GEN_IDLE") -> genIdlePaint
            value.startsWith("GEN_MARK") -> genMarkPaint
            value.startsWith("GEN_BURN") -> genBurnPaint
            value.startsWith("GEN_BEVEL") -> genBevelPaint
            value.startsWith("GEN_RAW") -> genRawPaint
            value.startsWith("NC_RAPID") -> ncRapidPaint
            value.startsWith("NC_BEVEL_A_POS") -> ncBevelPaint
            value.startsWith("NC_BEVEL_A_NEG") -> ncBevelANegPaint
            value.startsWith("NC_BEVEL_B_POS") -> ncBevelBPosPaint
            value.startsWith("NC_BEVEL_B_NEG") -> ncBevelBNegPaint
            value.startsWith("NC_CUT") -> ncCutPaint
            value.startsWith("NC_SHEET") -> ncSheetPaint
            value.contains("MARK") -> accentPaint
            else -> linePaint
        }
    }

    private fun textColorForLayer(layer: String?): Int {
        return when {
            layer.orEmpty().uppercase().startsWith("NC_BEVEL_LABEL") -> Color.parseColor("#dff7ff")
            layer.orEmpty().uppercase().startsWith("GEN_RAW") -> Color.parseColor("#c8d2dc")
            else -> Color.WHITE
        }
    }

    private fun drawSimulationOverlay(canvas: Canvas, bounds: RectBounds, baseScale: Double) {
        if (simulationSegments.isEmpty() || simulationProgress <= 0.0) return
        var remaining = simulationProgress
        var headPoint: Point2? = null
        var headDirection: Pair<Double, Double>? = null
        var headMode = GenOperationType.BURNING
        simulationSegments.forEach { segment ->
            if (remaining <= 0.0) return@forEach
            val consume = minOf(segment.length, remaining)
            drawPartialSegment(canvas, bounds, baseScale, segment, consume)
            val fraction = consume / segment.length.coerceAtLeast(0.0001)
            headPoint = segment.pointAt(fraction)
            headDirection = segment.directionAt(fraction)
            headMode = segment.type
            remaining -= consume
        }
        headPoint?.let {
            drawLaserHead(canvas, bounds, baseScale, it, headDirection ?: (1.0 to 0.0), headMode)
        }
    }

    private fun drawLaserHead(
        canvas: Canvas,
        bounds: RectBounds,
        baseScale: Double,
        point: Point2,
        direction: Pair<Double, Double>,
        mode: GenOperationType
    ) {
        val mapped = mapPoint(point, bounds, baseScale)
        val dx = direction.first
        val dy = -direction.second
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001)
        val ux = (dx / length).toFloat()
        val uy = (dy / length).toFloat()
        val nx = -uy
        val ny = ux
        laserHeadNozzlePaint.color = when (mode) {
            GenOperationType.IDLE -> Color.parseColor("#8a95a3")
            GenOperationType.MARKING -> Color.parseColor("#4fd18b")
            GenOperationType.BURNING -> Color.parseColor("#ff8c42")
        }
        val auraRadius = if (mode == GenOperationType.BURNING) 12f else 8f
        canvas.drawCircle(mapped.first, mapped.second, auraRadius, laserHeadAuraPaint)
        val body = Path().apply {
            moveTo(mapped.first - ux * 6f + nx * 8f, mapped.second - uy * 6f + ny * 8f)
            lineTo(mapped.first - ux * 6f - nx * 8f, mapped.second - uy * 6f - ny * 8f)
            lineTo(mapped.first + ux * 10f - nx * 5f, mapped.second + uy * 10f - ny * 5f)
            lineTo(mapped.first + ux * 10f + nx * 5f, mapped.second + uy * 10f + ny * 5f)
            close()
        }
        canvas.drawPath(body, laserHeadBodyPaint)
        val nozzle = Path().apply {
            moveTo(mapped.first + ux * 12f, mapped.second + uy * 12f)
            lineTo(mapped.first + ux * 2f + nx * 4f, mapped.second + uy * 2f + ny * 4f)
            lineTo(mapped.first + ux * 2f - nx * 4f, mapped.second + uy * 2f - ny * 4f)
            close()
        }
        canvas.drawPath(nozzle, laserHeadNozzlePaint)
        canvas.drawCircle(mapped.first, mapped.second, 4.5f, simulationHeadPaint)
    }

    private fun isNcBevelLayer(layer: String?): Boolean = layer.orEmpty().uppercase().startsWith("NC_BEVEL")

    private fun drawBevelLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, layer: String?) {
        val dx = x2 - x1
        val dy = y2 - y1
        val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val normalX = -dy / length
        val normalY = dx / length
        val direction = when {
            layer.orEmpty().contains("A_NEG") || layer.orEmpty().contains("B_NEG") -> -1f
            else -> 1f
        }
        val offset = (if (viewMode == ViewMode.THREE_D) 10f else 7f) * direction
        val quad = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
            lineTo(x2 + normalX * offset, y2 + normalY * offset)
            lineTo(x1 + normalX * offset, y1 + normalY * offset)
            close()
        }
        canvas.drawLine(x1, y1, x2, y2, paintForLayer(layer))
        bevelFillPaint.color = when {
            layer.orEmpty().contains("A_POS") -> Color.parseColor("#884dd0e1")
            layer.orEmpty().contains("A_NEG") -> Color.parseColor("#8836c1d6")
            layer.orEmpty().contains("B_POS") -> Color.parseColor("#8881c784")
            else -> Color.parseColor("#88ff8a65")
        }
        canvas.drawPath(quad, bevelFillPaint)
        val bx1 = x1 + normalX * offset
        val by1 = y1 + normalY * offset
        val bx2 = x2 + normalX * offset
        val by2 = y2 + normalY * offset
        canvas.drawLine(bx1, by1, bx2, by2, bevelShadowPaint)
        canvas.drawLine(bx1, by1, bx2, by2, paintForLayer(layer))
        if (viewMode == ViewMode.TWO_D) {
            canvas.drawLine(x1, y1, bx1, by1, paintForLayer(layer))
            canvas.drawLine(x2, y2, bx2, by2, paintForLayer(layer))
        }
    }

    private fun drawBevelArc(canvas: Canvas, rect: RectF, startAngle: Float, sweepAngle: Float, layer: String?) {
        val direction = when {
            layer.orEmpty().contains("A_NEG") || layer.orEmpty().contains("B_NEG") -> -1f
            else -> 1f
        }
        val shadowInset = if (viewMode == ViewMode.THREE_D) -10f else -7f
        val faceInset = if (viewMode == ViewMode.THREE_D) -6f else -4f
        val shadowRect = RectF(rect).apply { inset(shadowInset * direction, shadowInset * direction) }
        val faceRect = RectF(rect).apply { inset(faceInset * direction, faceInset * direction) }
        canvas.drawArc(rect, startAngle, sweepAngle, false, paintForLayer(layer))
        bevelFillPaint.color = when {
            layer.orEmpty().contains("A_POS") -> Color.parseColor("#884dd0e1")
            layer.orEmpty().contains("A_NEG") -> Color.parseColor("#8836c1d6")
            layer.orEmpty().contains("B_POS") -> Color.parseColor("#8881c784")
            else -> Color.parseColor("#88ff8a65")
        }
        val previousStroke = bevelShadowPaint.strokeWidth
        bevelShadowPaint.strokeWidth = 10f
        canvas.drawArc(shadowRect, startAngle, sweepAngle, false, bevelShadowPaint)
        bevelShadowPaint.strokeWidth = previousStroke
        canvas.drawArc(faceRect, startAngle, sweepAngle, false, paintForLayer(layer))
    }

    private fun drawPartialSegment(
        canvas: Canvas,
        bounds: RectBounds,
        baseScale: Double,
        segment: SimulationSegment,
        consume: Double
    ) {
        val fraction = (consume / segment.length.coerceAtLeast(0.0001)).coerceIn(0.0, 1.0)
        val paint = when (segment.type) {
            GenOperationType.IDLE -> ncRapidPaint
            GenOperationType.MARKING -> genMarkPaint
            GenOperationType.BURNING -> simulationPathPaint
        }
        if (segment.samples.size <= 1) {
            val a = mapPoint(segment.start, bounds, baseScale)
            val point = segment.pointAt(fraction)
            val b = mapPoint(point, bounds, baseScale)
            canvas.drawLine(a.first, a.second, b.first, b.second, paint)
            return
        }
        val path = Path()
        var remaining = consume.coerceAtLeast(0.0)
        var lastDrawn = false
        for (index in segment.samples.indices) {
            val point = segment.samples[index]
            val mapped = mapPoint(point, bounds, baseScale)
            if (index == 0) {
                path.moveTo(mapped.first, mapped.second)
                continue
            }
            val prev = segment.samples[index - 1]
            val legLength = distance(prev, point)
            if (legLength <= 1e-6) continue
            if (remaining >= legLength) {
                path.lineTo(mapped.first, mapped.second)
                remaining -= legLength
            } else {
                val localT = (remaining / legLength).coerceIn(0.0, 1.0)
                val partialPoint = Point2(
                    prev.x + (point.x - prev.x) * localT,
                    prev.y + (point.y - prev.y) * localT
                )
                val partialMapped = mapPoint(partialPoint, bounds, baseScale)
                path.lineTo(partialMapped.first, partialMapped.second)
                lastDrawn = true
                break
            }
        }
        if (!lastDrawn && fraction >= 1.0) {
            val end = mapPoint(segment.samples.last(), bounds, baseScale)
            path.lineTo(end.first, end.second)
        }
        canvas.drawPath(path, paint)
    }
    private fun drawDimensionOverlay(canvas: Canvas, bounds: RectBounds, baseScale: Double) {
        if (viewMode != ViewMode.TWO_D) return
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
        canvas.drawCircle(a.first, a.second, 7f, simulationHeadPaint)
        canvas.drawCircle(b.first, b.second, 7f, simulationHeadPaint)
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

    private fun segmentIndexForProgress(progress: Double): Int {
        if (simulationSegments.isEmpty()) return -1
        var remaining = progress
        simulationSegments.forEachIndexed { index, segment ->
            if (remaining <= segment.length) return index
            remaining -= segment.length
        }
        return simulationSegments.lastIndex
    }

    private fun buildSimulationSegments(operations: List<GenOperation>): List<SimulationSegment> {
        val segments = mutableListOf<SimulationSegment>()
        operations.forEach { operation ->
            operation.contours.forEach { contour ->
                var cursor = contour.start
                contour.segments.forEach { segment ->
                    val end = segment.end
                    val samples = if (segment.amp != null && (segment.amp.x != 0.0 || segment.amp.y != 0.0)) {
                        approximateAmpSamples(cursor, end, segment.amp)
                    } else if (segment.origin != null && segment.radius > 0.0 && segment.sweepRadians != 0.0) {
                        approximateArcSamples(cursor, end, segment.origin, segment.sweepRadians, segment.radius)
                    } else {
                        listOf(cursor, end)
                    }
                    val length = samples.zipWithNext { a, b -> distance(a, b) }.sum()
                    if (length > 1e-4) {
                        segments += SimulationSegment(
                            type = operation.type,
                            start = cursor,
                            end = end,
                            origin = segment.origin,
                            radius = segment.radius,
                            sweepRadians = segment.sweepRadians,
                            length = length,
                            samples = samples
                        )
                    }
                    cursor = end
                }
            }
        }
        return segments
    }

    private fun buildNcSimulationSegments(program: NcProgram): List<SimulationSegment> {
        return flattenNcMoves(program.moves).mapNotNull { move ->
            val rawSamples = if (move.centerOffset != null) {
                approximateNcArcSamples(move)
            } else {
                listOf(move.start, move.end)
            }
            val samples = normalizeSimulationSamples(rawSamples, move.start, move.end)
            val length = samples.zipWithNext { a, b -> distance(a, b) }.sum()
            if (length <= 1e-4) {
                null
            } else {
                SimulationSegment(
                    type = when {
                        move.rapid -> GenOperationType.IDLE
                        hasNcBevel(move) -> GenOperationType.BURNING
                        else -> GenOperationType.BURNING
                    },
                    start = samples.first(),
                    end = samples.last(),
                    origin = move.centerOffset?.let { Point2(move.start.x + it.x, move.start.y + it.y) },
                    radius = move.centerOffset?.let { sqrt(it.x * it.x + it.y * it.y) } ?: 0.0,
                    sweepRadians = 0.0,
                    length = length,
                    samples = samples
                )
            }
        }
    }

    private fun normalizeSimulationSamples(rawSamples: List<Point2>, start: Point2, end: Point2): List<Point2> {
        val samples = mutableListOf<Point2>()
        if (rawSamples.isEmpty() || distance(rawSamples.first(), start) > 1e-5) {
            samples += start
        }
        rawSamples.forEach { point ->
            if (samples.isEmpty() || distance(samples.last(), point) > 1e-7) {
                samples += point
            }
        }
        if (samples.isEmpty() || distance(samples.last(), end) > 1e-5) {
            samples += end
        }
        return samples
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
        val value = layer.orEmpty().uppercase()
        return value.isBlank() ||
            (!value.startsWith("NC_BEVEL") &&
                !value.startsWith("NC_SHEET") &&
                !value.startsWith("NC_BEVEL_LABEL"))
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

    private fun approximateAmpSamples(start: Point2, end: Point2, amp: Point2): List<Point2> {
        val midpoint = Point2((start.x + end.x) / 2.0, (start.y + end.y) / 2.0)
        val control = Point2(midpoint.x + amp.x * 2.0, midpoint.y + amp.y * 2.0)
        val steps = 16
        val points = mutableListOf<Point2>()
        for (index in 0..steps) {
            val t = index.toDouble() / steps.toDouble()
            val oneMinusT = 1.0 - t
            points += Point2(
                oneMinusT * oneMinusT * start.x + 2.0 * oneMinusT * t * control.x + t * t * end.x,
                oneMinusT * oneMinusT * start.y + 2.0 * oneMinusT * t * control.y + t * t * end.y
            )
        }
        return points
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

private data class SimulationSegment(
    val type: GenOperationType,
    val start: Point2,
    val end: Point2,
    val origin: Point2?,
    val radius: Double,
    val sweepRadians: Double,
    val length: Double,
    val samples: List<Point2>
) {
    fun pointAt(fraction: Double): Point2 {
        val t = fraction.coerceIn(0.0, 1.0)
        if (samples.size > 1) {
            val target = length * t
            var consumed = 0.0
            for (index in 0 until samples.lastIndex) {
                val a = samples[index]
                val b = samples[index + 1]
                val segmentLength = kotlin.math.sqrt(
                    (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)
                )
                if (consumed + segmentLength >= target) {
                    val localT = ((target - consumed) / segmentLength.coerceAtLeast(0.0001)).coerceIn(0.0, 1.0)
                    return Point2(
                        a.x + (b.x - a.x) * localT,
                        a.y + (b.y - a.y) * localT
                    )
                }
                consumed += segmentLength
            }
            return samples.last()
        }
        return Point2(
            start.x + (end.x - start.x) * t,
            start.y + (end.y - start.y) * t
        )
    }

    fun directionAt(fraction: Double): Pair<Double, Double> {
        if (samples.size > 1) {
            val target = length * fraction.coerceIn(0.0, 1.0)
            var consumed = 0.0
            for (index in 0 until samples.lastIndex) {
                val a = samples[index]
                val b = samples[index + 1]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val segmentLength = kotlin.math.sqrt(dx * dx + dy * dy)
                if (segmentLength <= 1e-6) continue
                if (consumed + segmentLength >= target) {
                    return dx / segmentLength to dy / segmentLength
                }
                consumed += segmentLength
            }
            val a = samples[samples.lastIndex - 1]
            val b = samples.last()
            val dx = b.x - a.x
            val dy = b.y - a.y
            val fallbackLength = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001)
            return dx / fallbackLength to dy / fallbackLength
        }
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001)
        return dx / length to dy / length
    }
}

private data class Point3(
    val x: Double,
    val y: Double,
    val z: Double
)

private data class RotatedPoint3D(
    val x: Double,
    val y: Double,
    val depth: Double
)

private data class Face3D(
    val vertices: List<Point3>,
    val fillColor: Int
)

private data class Edge3D(
    val start: Point2,
    val end: Point2,
    val startZ: Double,
    val endZ: Double,
    val paint: Paint
)

private data class Nc3DSegment(
    val center: NcMove,
    val topOuter: NcMove? = null,
    val bottomOuter: NcMove? = null,
    val topLayer: String? = null,
    val bottomLayer: String? = null
)

private data class SnapPreview(
    val point: Point2,
    val label: String
)
