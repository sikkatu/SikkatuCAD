package com.sikkatu.sikkatucad

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.sikkatu.sikkatucad.databinding.ActivityMainBinding
import androidx.core.widget.addTextChangedListener
import java.io.File
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : AppCompatActivity(), DxfCanvasView.Listener {

    private lateinit var binding: ActivityMainBinding
    private val parser by lazy { DocumentParser(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val libraryStore by lazy { FileLibraryStore(this) }
    private var currentParsed: ParsedCadFile? = null
    private var currentUri: Uri? = null
    private var currentFileName: String? = null
    private var currentPanelTab = FilePanelTab.IMPORT
    private var lastSearchQuery = ""
    private var measureMode = MeasureMode.NONE
    private var areaAutoClose = true
    private var currentPreviewDocument: DxfDocument? = null
    private var currentSelection: GeometrySelection? = null
    private var pendingExportBytes: ByteArray? = null
    private var clipboardEntity: DxfEntity? = null
    private var expandedHistoryContent: View? = null
    private val selectionHiddenLayers = linkedSetOf<String>()
    private var simulationProgressText = "0%"
    private var simulationSpeedText = "0.35x"
    private var simulationStateText = ""
    private var simulationProgressValueView: TextView? = null
    private var simulationSpeedValueView: TextView? = null
    private var simulationStateValueView: TextView? = null
    private var simulationSpeedSeekBar: SeekBar? = null
    private var simulationToggleButtonView: TextView? = null
    private var suppressSpeedSeekCallback = false
    private val historyDocuments = mutableListOf<DxfDocument>()
    private val historyLabels = mutableListOf<String>()
    private var historyIndex = -1
    private val hiddenLayers = linkedSetOf<String>()
    private var ncBevelEnabled = true
    private var ncConsoleLines: List<String> = emptyList()
    private var nestingWorkspace: DxfDocument? = null
    private val nestingParts = mutableListOf<NestPart>()
    private var nestingSheetThickness = 10.0

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importAndOpenUri(it, persistPermission = true) }
    }

    private val openDocumentTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            libraryStore.setTreeRootUri(it)
            libraryStore.setBrowseUri(it)
            currentPanelTab = FilePanelTab.ALL_FILES
            renderFilePanel()
        }
    }

    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri ?: return@registerForActivityResult
        val generatedBytes = pendingExportBytes
        if (generatedBytes != null) {
            worker.execute {
                runCatching {
                    contentResolver.openOutputStream(uri).use { output ->
                        requireNotNull(output) { getString(R.string.s0038) }
                        output.write(generatedBytes)
                    }
                }.onSuccess {
                    runOnUiThread {
                        binding.statusText.text = getString(R.string.s0039)
                        pendingExportBytes = null
                    }
                }.onFailure { error ->
                    runOnUiThread {
                        binding.statusText.text = getString(R.string.s0040, error.message ?: getString(R.string.s0475))
                    }
                }
            }
            return@registerForActivityResult
        }
        val sourceUri = currentUri ?: return@registerForActivityResult
        worker.execute {
            runCatching {
                contentResolver.openInputStream(sourceUri).use { input ->
                    requireNotNull(input) { getString(R.string.s0041) }
                    contentResolver.openOutputStream(uri).use { output ->
                        requireNotNull(output) { getString(R.string.s0038) }
                        input.copyTo(output)
                    }
                }
            }.onSuccess {
                runOnUiThread {
                    binding.statusText.text = getString(R.string.s0039)
                }
            }.onFailure { error ->
                runOnUiThread {
                    binding.statusText.text = getString(R.string.s0040, error.message ?: getString(R.string.s0475))
                }
            }
        }
    }

    private val importNestingDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        worker.execute {
            runCatching {
                val fileName = queryFileName(uri) ?: getString(R.string.s0042)
                parser.parse(uri, fileName)
            }.onSuccess { parsed ->
                val doc = when (parsed) {
                    is ParsedCadFile.Dxf -> parsed.document
                    is ParsedCadFile.Nc -> parsed.document
                    is ParsedCadFile.Gen -> buildGenPreview(parsed.document)
                    else -> null
                }
                doc?.let {
                    nestingWorkspace = it.copy(fileName = it.fileName)
                    nestingParts.clear()
                    nestingParts += NestPart(it.fileName, it.entities)
                    runOnUiThread {
                        binding.statusText.text = getString(R.string.s0043, it.fileName)
                        showNestingPanel()
                    }
                }
            }.onFailure { error ->
                runOnUiThread { binding.statusText.text = getString(R.string.s0044, error.message) }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "cad_prefs"
        private const val KEY_LANGUAGE = "app_language"
        /** "" = system default, "ru", "en", "zh" */
        fun savedLanguage(context: Context): String {
            return prefs(context).getString(KEY_LANGUAGE, "").orEmpty()
        }
        /** URI файла, который нужно переоткрыть после смены языка (recreate). */
        @Volatile private var pendingReopenUri: Uri? = null
        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        private fun wrapLocale(context: Context, lang: String): Context {
            val locale = when (lang) {
                "ru" -> Locale("ru")
                "zh" -> Locale("zh")
                "en" -> Locale("en")
                else -> Locale.getDefault()
            }
            Locale.setDefault(locale)
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(locale)
            return context.createConfigurationContext(config)
        }
    }
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapLocale(newBase, savedLanguage(newBase)))
    }
    /** Переключатель языка интерфейса: English / Русский / 中文 / System. */
    private fun showLanguageDialog() {
        val labels = arrayOf("English", "Русский", "中文", "System default")
        val codes = arrayOf("en", "ru", "zh", "")
        val current = savedLanguage(this)
        val checked = codes.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Interface language / Язык интерфейса")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val code = codes[which]
                if (code != current) {
                    prefs(this).edit().putString(KEY_LANGUAGE, code).apply()
                    pendingReopenUri = currentUri
                    dialog.dismiss()
                    recreate()
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppRes.init(this)
        simulationStateText = getString(R.string.s0037)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        libraryStore.pruneMissingManagedFiles()
        binding.dxfCanvasView.listener = this

        binding.panelClose.setOnClickListener { hideSidePanel() }
        binding.sidePanel.setOnClickListener { /* consume panel clicks: browsing files should never close it */ }
        binding.viewerHost.setOnClickListener {
            if (binding.sidePanel.visibility == View.VISIBLE) hideSidePanel()
        }
        binding.dxfCanvasView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && binding.sidePanel.visibility == View.VISIBLE) {
                hideSidePanel()
            }
            false
        }
        binding.openButton.setOnClickListener { openDocument.launch(arrayOf("*/*")) }
        binding.languageButton.setOnClickListener { showLanguageDialog() }
        binding.autoDimensionChip.setOnClickListener {
            val doc = currentPreviewDocument
            if (doc == null) {
                binding.statusText.text = getString(R.string.s0045)
                return@setOnClickListener
            }
            val enabled = !binding.dxfCanvasView.isAutoDimensionEnabled()
            binding.dxfCanvasView.setAutoDimensionEnabled(enabled)
            updateViewModeChips()
            binding.statusText.text = if (enabled) {
                getString(R.string.s0046, binding.dxfCanvasView.autoDimensionSummary().orEmpty())
            } else {
                getString(R.string.s0047)
            }
        }
        binding.view2dChip.setOnClickListener {
            binding.dxfCanvasView.setViewMode(DxfCanvasView.ViewMode.TWO_D)
            binding.dxfCanvasView.resetToBounds()
            binding.dxfCanvasView.clearSelection(notify = false)
            updateViewModeChips()
            binding.statusText.text = getString(R.string.s0048)
        }
        binding.view3dChip.setOnClickListener {
            binding.dxfCanvasView.setViewMode(DxfCanvasView.ViewMode.THREE_D)
            updateViewModeChips()
            binding.statusText.text = getString(R.string.s0049)
        }
        binding.bevelChip.setOnClickListener {
            if (currentParsed !is ParsedCadFile.Nc) return@setOnClickListener
            ncBevelEnabled = !ncBevelEnabled
            binding.dxfCanvasView.setNcBevelEnabled(ncBevelEnabled)
            updateViewModeChips()
            binding.statusText.text = if (ncBevelEnabled) getString(R.string.s0050) else getString(R.string.s0051)
        }
        binding.rapidChip.setOnClickListener {
            if (currentParsed !is ParsedCadFile.Nc) return@setOnClickListener
            val layer = "NC_RAPID"
            if (hiddenLayers.contains(layer)) {
                hiddenLayers.remove(layer)
                binding.statusText.text = getString(R.string.s0052)
            } else {
                hiddenLayers.add(layer)
                binding.statusText.text = getString(R.string.s0053)
            }
            binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
            updateViewModeChips()
        }
        binding.actionFile.setOnClickListener {
            currentPanelTab = FilePanelTab.IMPORT
            renderFilePanel()
        }
        binding.actionLayers.setOnClickListener { showLayerPanel() }
        binding.actionMeasure.setOnClickListener { showMeasurePanel() }
        binding.actionMeasure.setOnLongClickListener {
            showCadEditPanel()
            true
        }
        binding.actionTexts.setOnClickListener {
            if (currentUri != null) showTranslationPanel() else {
                binding.statusText.text = getString(R.string.s0065, "")
            }
        }
        binding.actionSettings.setOnClickListener { showSettingsPanel() }
        updateBottomNavState(binding.actionFile)
        binding.btnGenNcPreview.setOnClickListener {
            currentPreviewDocument?.let {
                pendingExportBytes = serializeAsNc(it).toByteArray(Charsets.UTF_8)
                exportDocument.launch("preview.nc")
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.sidePanel.visibility == View.VISIBLE -> {
                    hideSidePanel()
                }
                currentParsed != null || currentPreviewDocument != null -> returnToHome()
                else -> finish()
            }
        }

        handleIntent(intent)
        updateViewModeChips()
        // Переоткрытие файла после смены языка (recreate): чертёж не должен теряться.
        pendingReopenUri?.let { uri ->
            pendingReopenUri = null
            binding.root.post { openUri(uri, persistPermission = false) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        importAndOpenUri(data, persistPermission = true)
    }

    private fun showSidePanel(active: View?, title: String) {
        updateBottomNavState(active)
        binding.sideTitle.text = title
        binding.sidePanel.animate().cancel()
        if (binding.sidePanel.visibility != View.VISIBLE) {
            binding.sidePanel.translationX = resources.displayMetrics.widthPixels.toFloat()
            binding.sidePanel.alpha = 0f
            binding.sidePanel.visibility = View.VISIBLE
        }
        binding.sidePanel.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(140L)
            .start()
    }

    private fun hideSidePanel() {
        if (binding.sidePanel.visibility != View.VISIBLE) return
        binding.sidePanel.animate().cancel()
        binding.sidePanel.animate()
            .translationX(resources.displayMetrics.widthPixels.toFloat())
            .alpha(0f)
            .setDuration(120L)
            .withEndAction {
                binding.sidePanel.visibility = View.GONE
                binding.sidePanel.translationX = 0f
                binding.sidePanel.alpha = 1f
                updateBottomNavState(null)
            }
            .start()
    }

    private fun updateBottomNavState(active: View?) {
        val navItems = listOf(
            binding.actionFile,
            binding.actionLayers,
            binding.actionMeasure,
            binding.actionTexts,
            binding.actionSettings
        )
        val normalBg = ColorStateList.valueOf(getColor(R.color.navItemBg))
        val selectedText = getColor(R.color.textOnDark)
        val normalText = getColor(R.color.textMutedOnDark)
        navItems.forEach { item ->
            val selected = item == active
            if (selected) {
                item.setBackgroundResource(R.drawable.bg_nav_selected)
                item.backgroundTintList = null
            } else {
                item.setBackgroundResource(0)
                item.backgroundTintList = normalBg
            }
            item.setTextColor(if (selected) selectedText else normalText)
            item.iconTint = ColorStateList.valueOf(if (selected) selectedText else normalText)
            item.alpha = if (selected) 1f else 0.78f
            item.animate().scaleX(if (selected) 1.04f else 1f).scaleY(if (selected) 1.04f else 1f).setDuration(120L).start()
        }
    }

    private fun updateViewModeChips() {
        val is3d = binding.dxfCanvasView.viewMode() == DxfCanvasView.ViewMode.THREE_D
        val isNc = currentParsed is ParsedCadFile.Nc
        binding.view2dChip.alpha = if (is3d) 0.65f else 1f
        binding.view3dChip.visibility = View.GONE
        binding.view3dChip.visibility = View.GONE
        binding.bevelChip.visibility = View.GONE
        binding.bevelChip.isEnabled = isNc
        binding.bevelChip.alpha = if (ncBevelEnabled) 1f else 0.65f
        binding.rapidChip.visibility = View.GONE
        binding.rapidChip.isEnabled = isNc
        binding.rapidChip.alpha = if (hiddenLayers.contains("NC_RAPID")) 0.65f else 1f
        val hasDocument = currentPreviewDocument != null
        binding.autoDimensionChip.isEnabled = hasDocument
        binding.autoDimensionChip.alpha = when {
            !hasDocument -> 0.45f
            binding.dxfCanvasView.isAutoDimensionEnabled() -> 1f
            else -> 0.65f
        }
        binding.autoDimensionChip.text = if (binding.dxfCanvasView.isAutoDimensionEnabled()) getString(R.string.s0056) else getString(R.string.s0057)
    }

    private fun importAndOpenUri(uri: Uri, persistPermission: Boolean) {
        if (persistPermission && uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val sourceName = queryFileName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.s0033)
        binding.statusText.text = if (libraryStore.isArchiveName(sourceName)) getString(R.string.s0058, sourceName) else getString(R.string.s0059, sourceName)
        showEmpty()
        worker.execute {
            runCatching {
                if (libraryStore.isArchiveName(sourceName)) {
                    libraryStore.importArchiveToManagedStorage(contentResolver, uri, sourceName)
                } else {
                    listOf(libraryStore.importToManagedStorage(contentResolver, uri, sourceName))
                }
            }.onSuccess { refs ->
                runOnUiThread {
                    val firstRef = refs.firstOrNull()
                    if (firstRef == null) {
                        binding.statusText.text = getString(R.string.s0060)
                        renderFilePanel()
                    } else {
                        val suffix = if (refs.size > 1) getString(R.string.s0061, refs.size) else ""
                        binding.statusText.text = if (libraryStore.isArchiveName(sourceName)) {
                            getString(R.string.s0062, firstRef.name, suffix)
                        } else {
                            getString(R.string.s0063, firstRef.name)
                        }
                        openUri(Uri.parse(firstRef.uri), persistPermission = false)
                        currentPanelTab = FilePanelTab.RECENT
                        renderFilePanel()
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    binding.statusText.text = getString(R.string.s0064, error.message ?: getString(R.string.s0475))
                    openUri(uri, persistPermission = false)
                }
            }
        }
    }

    private fun openUri(uri: Uri, persistPermission: Boolean) {
        if (persistPermission && uri.scheme == "content") {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val fileName = queryFileName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.s0033)
        currentUri = uri
        currentFileName = fileName
        libraryStore.addRecentFile(uri, fileName)
        binding.fileNameText.text = fileName
        binding.statusText.text = getString(R.string.s0065, fileName)
        showEmpty()
        worker.execute {
            runCatching {
                parser.parse(uri, fileName)
            }.onSuccess { parsed ->
                runOnUiThread { render(parsed) }
            }.onFailure { error ->
                runOnUiThread {
                    binding.statusText.text = getString(R.string.s0066, error.message ?: getString(R.string.s0475))
                    renderMessageCard(
                        title = getString(R.string.s0067),
                        body = listOfNotNull(error.message, getString(R.string.s0068)).joinToString("\n\n"),
                        resetDrawingState = true
                    )
                }
            }
        }
    }

    private fun reloadCurrentFile() {
        val uri = currentUri ?: return
        val name = currentFileName ?: queryFileName(uri) ?: getString(R.string.s0033)
        openUri(uri, persistPermission = false)
        binding.statusText.text = getString(R.string.s0070, name)
    }

    private fun render(parsed: ParsedCadFile) {
        currentParsed = parsed
        currentSelection = null
        clearSelectionBaseView()
        resetHistory()
        binding.sidePanel.visibility = View.GONE
        updateBottomNavState(null)
        when (parsed) {
            is ParsedCadFile.Dxf -> {
                if (parsed.document.entities.isEmpty() && parsed.document.rawDxf != null) {
                    binding.statusText.text = getString(R.string.s0071, 0)
                    binding.fileTypeChip.text = "DXF"
                    binding.metaChip.text = null
                    renderMessageCard(
                        title = getString(R.string.s0067),
                        body = getString(R.string.s0503),
                        resetDrawingState = false
                    )
                    updateViewModeChips()
                    return
                }
                ncBevelEnabled = true
                binding.statusText.text = getString(R.string.s0071, parsed.document.entities.size)
                binding.fileTypeChip.text = "DXF"
                binding.metaChip.text = parsed.document.units?.let { getString(R.string.s0072, parsed.document.layers.size, it) }
                    ?: getString(R.string.s0073, parsed.document.layers.size)
                binding.emptyPanel.visibility = View.GONE
                binding.infoScrollView.visibility = View.GONE
                binding.dxfCanvasView.visibility = View.VISIBLE
                hiddenLayers.clear()
                hiddenLayers.addAll(parsed.document.layerInfos.filter { it.isOff || it.isFrozen }.map { it.name })
                measureMode = MeasureMode.NONE
                binding.dxfCanvasView.setMeasureMode(MeasureMode.NONE)
                binding.dxfCanvasView.setViewMode(DxfCanvasView.ViewMode.TWO_D)
                currentPreviewDocument = parsed.document
                pushHistorySnapshot(parsed.document, getString(R.string.s0074))
                binding.dxfCanvasView.setDocument(parsed.document)
                binding.dxfCanvasView.setNcBevelEnabled(ncBevelEnabled)
                binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
                binding.dxfCanvasView.setSimulationOperations(buildSimulationOperationsForDocument(parsed.document))
                binding.dxfCanvasView.setSimulationNcProgram(null)
                binding.dxfCanvasView.setNc3DSource(null)
                setNcConsole(null)
            }

            is ParsedCadFile.Gen -> {
                ncBevelEnabled = true
                binding.statusText.text = getString(R.string.s0075, parsed.document.operations.size)
                binding.fileTypeChip.text = "GEN"
                binding.metaChip.text = formatGenMeta(parsed.document)
                binding.emptyPanel.visibility = View.GONE
                binding.infoScrollView.visibility = View.GONE
                binding.dxfCanvasView.visibility = View.VISIBLE
                measureMode = MeasureMode.NONE
                hiddenLayers.clear()
                binding.dxfCanvasView.setMeasureMode(MeasureMode.NONE)
                binding.dxfCanvasView.setViewMode(DxfCanvasView.ViewMode.TWO_D)
                currentPreviewDocument = buildGenPreview(parsed.document)
                pushHistorySnapshot(requireNotNull(currentPreviewDocument), getString(R.string.s0076))
                binding.dxfCanvasView.setDocument(requireNotNull(currentPreviewDocument))
                binding.dxfCanvasView.setNcBevelEnabled(ncBevelEnabled)
                binding.dxfCanvasView.setHiddenLayers(emptySet())
            binding.dxfCanvasView.setSimulationOperations(parsed.document.operations)
            binding.dxfCanvasView.setSimulationNcProgram(null)
            binding.dxfCanvasView.setNc3DSource(null)
                setNcConsole(null)
        }

            is ParsedCadFile.Nc -> {
                ncBevelEnabled = true
                binding.statusText.text = getString(R.string.s0077, parsed.program.moves.size)
                binding.fileTypeChip.text = "NC"
                binding.metaChip.text = formatNcMeta(parsed.program)
                binding.emptyPanel.visibility = View.GONE
                binding.infoScrollView.visibility = View.GONE
                binding.dxfCanvasView.visibility = View.VISIBLE
                measureMode = MeasureMode.NONE
                hiddenLayers.clear()
                hiddenLayers.add("NC_RAPID")
                binding.dxfCanvasView.setMeasureMode(MeasureMode.NONE)
                binding.dxfCanvasView.setViewMode(DxfCanvasView.ViewMode.TWO_D)
                currentPreviewDocument = parsed.document
                pushHistorySnapshot(parsed.document, getString(R.string.s0078))
                binding.dxfCanvasView.setDocument(parsed.document)
                binding.dxfCanvasView.setNcBevelEnabled(ncBevelEnabled)
                binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
        binding.dxfCanvasView.setSimulationOperations(emptyList())
        binding.dxfCanvasView.setSimulationNcProgram(parsed.program)
        binding.dxfCanvasView.setNc3DSource(parsed.program)
        setNcConsole(parsed.program)
    }

            is ParsedCadFile.Dwg -> {
                ncBevelEnabled = true
                val doc = parsed.document
                if (doc != null) {
                    // DWG сконвертирован в DXF через Rust-мост — рисуем полноценный чертёж.
                    binding.statusText.text = getString(R.string.s0071, doc.entities.size)
                    binding.fileTypeChip.text = "DWG"
                    binding.metaChip.text = parsed.preview.readableVersion
                    binding.emptyPanel.visibility = View.GONE
                    binding.infoScrollView.visibility = View.GONE
                    binding.dxfCanvasView.visibility = View.VISIBLE
                    hiddenLayers.clear()
                    hiddenLayers.addAll(doc.layerInfos.filter { it.isOff || it.isFrozen }.map { it.name })
                    measureMode = MeasureMode.NONE
                    binding.dxfCanvasView.setMeasureMode(MeasureMode.NONE)
                    binding.dxfCanvasView.setViewMode(DxfCanvasView.ViewMode.TWO_D)
                    currentPreviewDocument = doc
                    pushHistorySnapshot(doc, getString(R.string.s0074))
                    binding.dxfCanvasView.setDocument(doc)
                    binding.dxfCanvasView.setNcBevelEnabled(ncBevelEnabled)
                    binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
                    binding.dxfCanvasView.setSimulationOperations(buildSimulationOperationsForDocument(doc))
                    binding.dxfCanvasView.setSimulationNcProgram(null)
                    binding.dxfCanvasView.setNc3DSource(null)
                    setNcConsole(null)
                } else {
                    currentPreviewDocument = null
                    binding.statusText.text = getString(R.string.s0079, parsed.preview.readableVersion)
                    binding.fileTypeChip.text = "DWG"
                    binding.metaChip.text = parsed.preview.readableVersion
                    binding.dxfCanvasView.setSimulationOperations(emptyList())
                    binding.dxfCanvasView.setSimulationNcProgram(null)
                    binding.dxfCanvasView.setViewMode(DxfCanvasView.ViewMode.TWO_D)
                    binding.dxfCanvasView.setNcBevelEnabled(ncBevelEnabled)
                    binding.dxfCanvasView.setNc3DSource(null)
                    renderMessageCard(
                        title = getString(R.string.s0080),
                        body = getString(R.string.s0081, parsed.preview.versionCode, parsed.preview.readableVersion, parsed.preview.note)
                    )
                }
            }
        }
        updateViewModeChips()
    }

    private fun renderMessageCard(title: String, body: String, resetDrawingState: Boolean = false) {
        if (resetDrawingState) {
            currentParsed = null
            currentPreviewDocument = null
            currentSelection = null
            clearSelectionBaseView()
            resetHistory()
            binding.dxfCanvasView.stopSimulation()
            binding.dxfCanvasView.setSimulationOperations(emptyList())
            binding.dxfCanvasView.setSimulationNcProgram(null)
            binding.dxfCanvasView.setNc3DSource(null)
        }
        binding.dxfCanvasView.setViewMode(DxfCanvasView.ViewMode.TWO_D)
        binding.dxfCanvasView.setNcBevelEnabled(true)
        binding.dxfCanvasView.visibility = View.GONE
        binding.emptyPanel.visibility = View.GONE
        binding.infoScrollView.visibility = View.VISIBLE
        binding.infoContainer.removeAllViews()
        binding.infoContainer.addView(sectionTitle(title))
        binding.infoContainer.addView(entryView(getString(R.string.s0082), body))
    }

    private fun showEmpty() {
        currentParsed = null
        currentPreviewDocument = null
        currentSelection = null
        clearSelectionBaseView()
        resetHistory()
        binding.dxfCanvasView.stopSimulation()
        binding.dxfCanvasView.setSimulationOperations(emptyList())
        binding.dxfCanvasView.setSimulationNcProgram(null)
        binding.dxfCanvasView.setViewMode(DxfCanvasView.ViewMode.TWO_D)
        binding.dxfCanvasView.setNcBevelEnabled(true)
        binding.dxfCanvasView.setNc3DSource(null)
        binding.dxfCanvasView.visibility = View.GONE
        binding.infoScrollView.visibility = View.GONE
        binding.emptyPanel.visibility = View.VISIBLE
        binding.sidePanel.visibility = View.GONE
        updateBottomNavState(null)
        binding.fileTypeChip.text = "-"
        binding.metaChip.text = "-"
        ncBevelEnabled = true
        updateViewModeChips()
        // Кнопка возврата к последнему чертежу
        val reopen = lastOpenedUri
        binding.reopenFileButton.visibility = if (reopen != null) View.VISIBLE else View.GONE
        binding.reopenFileButton.setOnClickListener {
            reopen?.let { uri -> openUri(uri, persistPermission = false) }
        }
    }

    private fun renderFilePanel() {
        showSidePanel(binding.actionFile, getString(R.string.s0083))
        binding.sideContent.removeAllViews()
        binding.sideContent.addView(tabRow())
        when (currentPanelTab) {
            FilePanelTab.IMPORT -> renderImportPanel()
            FilePanelTab.RECENT -> renderRecentFilesPanel()
            FilePanelTab.FAVORITES -> renderStoredRefs(getString(R.string.s0084), libraryStore.getFavorites(), getString(R.string.s0085))
            FilePanelTab.ALL_FILES -> renderAllFiles()
            FilePanelTab.SEARCH -> renderSearchPanel()
        }
    }

    private fun renderImportPanel() {
        binding.sideContent.addView(sectionTitle(getString(R.string.s0086)))
        binding.sideContent.addView(entryView(getString(R.string.s0082), getString(R.string.s0087)))
        binding.sideContent.addView(entryView(getString(R.string.s0088), libraryStore.storageSummary()))
        binding.sideContent.addView(primaryActionRow(getString(R.string.s0089)) { showNewDrawingDialog() })
        binding.sideContent.addView(primaryActionRow(getString(R.string.s0090)) { openDocument.launch(arrayOf("*/*")) })
        currentPreviewDocument?.takeIf { currentParsed is ParsedCadFile.Dxf && it.rawDxf != null }?.let { document ->
            binding.sideContent.addView(actionRow(getString(R.string.s0091)) {
                exportEditedDxf(document)
            })
        }
        if (currentUri != null) {
            binding.sideContent.addView(actionRow("Перевод надписей (EN / TH)") { showTranslationPanel() })
        }
        binding.sideContent.addView(actionRow(getString(R.string.s0092)) { openDocumentTree.launch(null) })

        currentUri?.let { uri ->
            val fileName = currentFileName ?: queryFileName(uri) ?: getString(R.string.s0093)
            binding.sideContent.addView(sectionTitle(getString(R.string.s0093)))
            binding.sideContent.addView(filePreviewRow(
                title = fileName,
                subtitle = currentFileSummary(),
                badge = fileTypeBadge(fileName),
                onOpen = { showCurrentFileDetail() },
                onDetail = { showCurrentFileDetail() },
                onDelete = null
            ))
            binding.sideContent.addView(actionRow(if (libraryStore.isFavorite(uri)) getString(R.string.s0094) else getString(R.string.s0095)) {
                val added = libraryStore.toggleFavorite(uri, fileName)
                binding.statusText.text = if (added) getString(R.string.s0096, fileName) else getString(R.string.s0097, fileName)
                renderFilePanel()
            })
        }

        val recentFiles = libraryStore.getRecentFiles()
        binding.sideContent.addView(sectionTitle(getString(R.string.s0098)))
        if (recentFiles.isEmpty()) {
            binding.sideContent.addView(entryView(getString(R.string.s0099), getString(R.string.s0100)))
        } else {
            recentFiles.take(4).forEach { ref ->
                binding.sideContent.addView(filePreviewRow(
                    title = ref.name,
                    subtitle = buildString {
                        if (ref.pinned) append(getString(R.string.s0101))
                        append(formatHistoryTime(ref.openedAt))
                    },
                    badge = fileTypeBadge(ref.name),
                    onOpen = { openUri(Uri.parse(ref.uri), persistPermission = false) },
                    onDetail = { showStoredFileDetail(ref, allowHistoryActions = true) },
                    onDelete = {
                        libraryStore.removeRecentFile(ref.uri)
                        binding.statusText.text = getString(R.string.s0102)
                        renderFilePanel()
                    }
                ))
            }
            binding.sideContent.addView(actionRow(getString(R.string.s0103)) {
                currentPanelTab = FilePanelTab.RECENT
                renderFilePanel()
            })
        }

        val grouped = recentFiles.groupBy { fileTypeBadge(it.name) }
        binding.sideContent.addView(sectionTitle(getString(R.string.s0104)))
        if (grouped.isEmpty()) {
            binding.sideContent.addView(entryView(getString(R.string.s0105), getString(R.string.s0106)))
        } else {
            grouped.toSortedMap().forEach { (type, files) ->
                binding.sideContent.addView(groupSummaryRow(type, files.size) {
                    currentPanelTab = FilePanelTab.RECENT
                    renderFilePanel()
                })
            }
        }
    }

    private fun renderRecentFilesPanel() {
        binding.sideContent.addView(sectionTitle(getString(R.string.s0098)))
        val recentFiles = libraryStore.getRecentFiles()
        if (recentFiles.isEmpty()) {
            binding.sideContent.addView(entryView(getString(R.string.s0099), getString(R.string.s0100)))
            return
        }
        binding.sideContent.addView(actionRow(getString(R.string.s0107)) {
            libraryStore.clearRecentFiles()
            binding.statusText.text = getString(R.string.s0108)
            renderFilePanel()
        })
        recentFiles.groupBy { fileTypeBadge(it.name) }.toSortedMap().forEach { (type, files) ->
            binding.sideContent.addView(sectionTitle(getString(R.string.s0109, type, files.size)))
            files.forEach { ref ->
                binding.sideContent.addView(filePreviewRow(
                    title = ref.name,
                    subtitle = buildString {
                        if (ref.pinned) append(getString(R.string.s0101))
                        append(formatHistoryTime(ref.openedAt))
                    },
                    badge = type,
                    onOpen = { openUri(Uri.parse(ref.uri), persistPermission = false) },
                    onDetail = { showStoredFileDetail(ref, allowHistoryActions = true) },
                    onDelete = {
                        if (ref.managed) {
                            val deleted = libraryStore.deleteManagedFile(ref)
                            binding.statusText.text = if (deleted) getString(R.string.s0110) else getString(R.string.s0111)
                        } else {
                            libraryStore.removeRecentFile(ref.uri)
                            binding.statusText.text = getString(R.string.s0102)
                        }
                        renderFilePanel()
                    }
                ))
            }
        }
    }

    private fun renderStoredRefs(title: String, refs: List<StoredFileRef>, emptyText: String = getString(R.string.s0112)) {
        binding.sideContent.addView(sectionTitle(title))
        if (refs.isEmpty()) {
            binding.sideContent.addView(entryView(getString(R.string.s0099), emptyText))
            return
        }
        refs.groupBy { fileTypeBadge(it.name) }.toSortedMap().forEach { (type, files) ->
            binding.sideContent.addView(sectionTitle(getString(R.string.s0109, type, files.size)))
            files.forEach { ref ->
                binding.sideContent.addView(filePreviewRow(
                    title = ref.name,
                    subtitle = formatHistoryTime(ref.openedAt),
                    badge = type,
                    onOpen = { openUri(Uri.parse(ref.uri), persistPermission = false) },
                    onDetail = { showStoredFileDetail(ref, allowHistoryActions = false) },
                    onDelete = null
                ))
            }
        }
    }

    private fun showNcExportPanel(document: DxfDocument, fileName: String) {
        showSidePanel(null, getString(R.string.s0113))
        binding.sideContent.removeAllViews()
        val bounds = document.bounds
        binding.sideContent.addView(infoLine(getString(R.string.s0114), "%.2f x %.2f".format(bounds.width, bounds.height)))
        val thicknessInput = EditText(this).apply {
            hint = getString(R.string.s0115)
            val defaultThickness = document.metadata["sheet_thickness"]?.toDoubleOrNull()
                ?: (currentParsed as? ParsedCadFile.Nc)?.program?.header?.get("Thickness")?.toDoubleOrNull()
                ?: 10.0
            setText("%.2f".format(defaultThickness).trimEnd('0').trimEnd('.'))
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
            background = getDrawable(R.drawable.bg_panel)
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
        }
        val materialInput = EditText(this).apply {
            hint = getString(R.string.s0116)
            setText("Q235B")
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
            background = getDrawable(R.drawable.bg_panel)
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
        }
        val sheetWInput = EditText(this).apply {
            hint = getString(R.string.s0117)
            setText("%.0f".format(document.bounds.width))
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
            background = getDrawable(R.drawable.bg_panel)
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
        }
        val sheetHInput = EditText(this).apply {
            hint = getString(R.string.s0118)
            setText("%.0f".format(document.bounds.height))
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
            background = getDrawable(R.drawable.bg_panel)
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.s0119)
            setText(fileName.substringBeforeLast('.', fileName) + "_auto")
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
            background = getDrawable(R.drawable.bg_panel)
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
        }
        listOf(
            getString(R.string.s0115) to thicknessInput,
            getString(R.string.s0116) to materialInput,
            getString(R.string.s0117) to sheetWInput,
            getString(R.string.s0118) to sheetHInput,
            getString(R.string.s0120) to nameInput
        ).forEach { (label, input) ->
            binding.sideContent.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(getColor(R.color.panelTextSecondary))
                setPadding(4.dp, 12.dp, 4.dp, 6.dp)
            })
            binding.sideContent.addView(input)
        }
        binding.sideContent.addView(actionRow(getString(R.string.s0121)) {
            val thick = thicknessInput.text.toString().toDoubleOrNull()
            val mat = materialInput.text.toString().trim()
            val w = sheetWInput.text.toString().toDoubleOrNull()
            val h = sheetHInput.text.toString().toDoubleOrNull()
            val program = nameInput.text.toString().trim().ifBlank { fileName.substringBeforeLast('.', fileName) }
            val ncText = serializeAsNc(document, thick, mat, w, h, program)
            pendingExportBytes = ncText.toByteArray(Charsets.UTF_8)
            exportDocument.launch("$program.nc")
            binding.statusText.text = getString(R.string.s0122)
        })
    }

    private fun exportEditedDxf(document: DxfDocument) {
        val raw = document.rawDxf
        if (raw == null) {
            binding.statusText.text = getString(R.string.s0123)
            return
        }
        worker.execute {
            runCatching {
                DxfLosslessEditor.applyTextEdits(raw, document)
                    ?: error(getString(R.string.s0124))
            }.onSuccess { text ->
                pendingExportBytes = text.toByteArray(Charset.forName(document.rawDxfCharset ?: "UTF-8"))
                runOnUiThread {
                    val baseName = (currentFileName ?: document.fileName).substringBeforeLast('.')
                    exportDocument.launch("${baseName}_edited.dxf")
                    binding.statusText.text = "DXF подготовлен: сохраняется только изменение текста"
                }
            }.onFailure { error ->
                runOnUiThread {
                    binding.statusText.text = "Экспорт DXF не удался: ${error.message ?: "неизвестная ошибка"}"
                }
            }
        }
    }

    // ==================== Перевод надписей (Rust bridge) ====================

    private var translationSession: Long? = null
    private var translationItems: List<CadBridge.TextItem> = emptyList()
    private val translationEn = linkedMapOf<String, String>()
    private val translationTh = linkedMapOf<String, String>()
    private lateinit var translationRowsContainer: LinearLayout

    private val translationImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        worker.execute {
            runCatching {
                val body = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Не удалось прочитать файл")
                CadBridge.parseTranslationJson(String(body, Charsets.UTF_8))
            }.onSuccess { (en, th) ->
                runOnUiThread {
                    translationEn.putAll(en)
                    translationTh.putAll(th)
                    renderTranslationRows()
                    binding.statusText.text = "Перевод загружен: ${en.size} EN / ${th.size} TH"
                }
            }.onFailure { error ->
                runOnUiThread {
                    binding.statusText.text = "Ошибка JSON: ${error.message ?: "неизвестно"}"
                }
            }
        }
    }

    /** Копирует текущий файл во временный и открывает сессию моста. */
    private fun openTranslationSession(): Long {
        translationSession?.let { return it }
        val uri = currentUri ?: error("Файл не открыт")
        val name = currentFileName ?: "drawing.dwg"
        val ext = name.substringAfterLast('.', "dwg")
        val tmp = File(cacheDir, "cad_translate_src.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Не удалось прочитать файл")
        val sid = CadBridge.open(tmp.absolutePath).getOrThrow()
        translationSession = sid
        return sid
    }

    private fun extractTranslationTexts() {
        binding.statusText.text = "Извлечение текстов…"
        worker.execute {
            runCatching {
                val sid = openTranslationSession()
                val items = CadBridge.extractTexts(sid).getOrThrow()
                sid to items
            }.onSuccess { (sid, items) ->
                runOnUiThread {
                    translationItems = items
                    renderTranslationRows()
                    val cyr = items.count { it.text.any { c -> c.code in 0x0400..0x04FF } }
                    binding.statusText.text =
                        "Текстов: ${items.size} (из них с кириллицей: $cyr) · формат: ${
                            CadBridge.sourceFormat(sid).getOrDefault("?")
                        }"
                }
            }.onFailure { error ->
                runOnUiThread {
                    binding.statusText.text = "Извлечение не удалось: ${error.message ?: "неизвестно"}"
                }
            }
        }
    }

    private fun renderTranslationRows() {
        if (!::translationRowsContainer.isInitialized) return
        translationRowsContainer.removeAllViews()
        if (translationItems.isEmpty()) {
            translationRowsContainer.addView(
                entryView("Тексты", "Нажмите «Извлечь тексты» или загрузите JSON перевода.")
            )
            return
        }
        translationItems.forEach { item ->
            translationRowsContainer.addView(translationRowView(item))
        }
    }

    private fun translationRowView(item: CadBridge.TextItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = getDrawable(R.drawable.bg_panel_compact)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp }
        }
        val header = TextView(this).apply {
            text = "${item.type} · ${item.layer.ifBlank { "-" }} · #${item.id}"
            textSize = 10f
            setTextColor(getColor(R.color.panelTextSecondary))
        }
        val original = TextView(this).apply {
            text = parser.plainTextForDisplay(item.text)
            textSize = 13f
            setTextColor(getColor(R.color.panelTextPrimary))
            setPadding(0, 2.dp, 0, 6.dp)
        }
        row.addView(header)
        row.addView(original)
        row.addView(fieldLabel("EN"))
        val enField = translationField(item, translationEn)
        row.addView(enField)
        row.addView(fieldLabel("TH"))
        val thField = translationField(item, translationTh)
        row.addView(thField)
        return row
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 10f
        setTextColor(getColor(R.color.panelTextSecondary))
        setPadding(0, 4.dp, 0, 1.dp)
    }

    private fun translationField(item: CadBridge.TextItem, store: MutableMap<String, String>): EditText {
        return EditText(this).apply {
            setText(store[item.id].orEmpty())
            hint = "— без перевода —"
            textSize = 13f
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
            background = getDrawable(R.drawable.bg_panel)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            addTextChangedListener { store[item.id] = it?.toString().orEmpty() }
        }
    }

    private fun exportTranslationJson() {
        if (translationItems.isEmpty()) {
            binding.statusText.text = "Сначала извлеките тексты."
            return
        }
        val rows = translationItems.map {
            CadBridge.TranslationRowJson(
                id = it.id, type = it.type, text = it.text, layer = it.layer,
                en = translationEn[it.id].orEmpty(), th = translationTh[it.id].orEmpty()
            )
        }
        pendingExportBytes = CadBridge.buildTranslationJson(rows).toByteArray(Charsets.UTF_8)
        exportDocument.launch("translations.json")
    }

    /**
     * Сохраняет чертёж с переводом: [format] = "dxf" | "dwg", [lang] = "en" | "th".
     * Пустое поле перевода означает «оставить оригинал».
     */
    private fun saveTranslated(format: String, lang: String) {
        if (translationItems.isEmpty()) {
            binding.statusText.text = "Сначала извлеките тексты."
            return
        }
        val edits = translationItems.associate { item ->
            val value = if (lang == "en") translationEn[item.id].orEmpty() else translationTh[item.id].orEmpty()
            item.id to value.ifBlank { item.text }
        }
        binding.statusText.text = "Подготовка файла ($format, ${lang.uppercase()})…"
        worker.execute {
            runCatching {
                val sid = openTranslationSession()
                CadBridge.applyTexts(sid, edits).getOrThrow()
                val out = File(cacheDir, "cad_translated_out.$format")
                if (format == "dwg") {
                    CadBridge.saveDwg(sid, out.absolutePath).getOrThrow()
                } else {
                    CadBridge.saveDxf(sid, out.absolutePath).getOrThrow()
                }
                out.readBytes()
            }.onSuccess { bytes ->
                pendingExportBytes = bytes
                runOnUiThread {
                    val base = (currentFileName ?: "drawing").substringBeforeLast('.')
                    val suffix = if (format == "dwg") "DWG (экспериментально)" else "DXF"
                    exportDocument.launch("${base}_${lang}.${format}")
                    binding.statusText.text =
                        "Готово ($suffix, ${lang.uppercase()}): проверьте результат" +
                            if (format == "dwg") " в AutoCAD — запись DWG экспериментальная." else "."
                }
            }.onFailure { error ->
                runOnUiThread {
                    binding.statusText.text = "Сохранение не удалось: ${error.message ?: "неизвестно"}"
                }
            }
        }
    }

    /** Панель перевода надписей: извлечение текстов, JSON, сохранение EN/TH. */
    /** Панель настроек: выбор языка интерфейса и краткая справка. */
    /**
     * Сохраняет текущий вид чертежа в PNG (для самопроверки рендера).
     * Файл кладётся в /sdcard/Download/cad_preview_<имя>_<время>.png —
     * его можно открыть и проверить визуально, в т.ч. автоматически.
     */
    private fun exportCanvasPng() {
        val canvasView = binding.dxfCanvasView
        if (canvasView.width == 0 || canvasView.height == 0) {
            binding.statusText.text = getString(R.string.s0178)
            return
        }
        val bitmap = android.graphics.Bitmap.createBitmap(
            canvasView.width, canvasView.height, android.graphics.Bitmap.Config.ARGB_8888
        )
        val c = android.graphics.Canvas(bitmap)
        c.drawColor(android.graphics.Color.rgb(18, 28, 44))
        canvasView.draw(c)
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val baseName = (currentFileName ?: "drawing").substringBeforeLast('.')
        val safeName = baseName.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        pendingExportBytes = bytes
        exportDocument.launch("cad_preview_${safeName}_${ts}.png")
        binding.statusText.text = "PNG готов — выберите папку для сохранения"
    }
    private fun showSettingsPanel() {
        showSidePanel(binding.actionSettings, getString(R.string.action_settings))
        binding.sideContent.removeAllViews()
        binding.sideContent.addView(sectionTitle(getString(R.string.language_picker)))
        val langLabels = arrayOf("Русский", "English", "中文", "System")
        val langCodes = arrayOf("ru", "en", "zh", "")
        val current = savedLanguage(this)
        val currentIndex = langCodes.indexOf(current).coerceAtLeast(0)
        langLabels.forEachIndexed { index, label ->
            val selected = index == currentIndex
            val row = actionRow((if (selected) "✓ " else "") + label) {
                val code = langCodes[index]
                if (code != current) {
                    prefs(this).edit().putString(KEY_LANGUAGE, code).apply()
                    pendingReopenUri = currentUri
                    recreate()
                }
            }
            binding.sideContent.addView(row)
        }
        binding.sideContent.addView(sectionTitle("Проверка рендера"))
        binding.sideContent.addView(actionRow("📸 Сохранить вид чертежа в PNG") { exportCanvasPng() })
        binding.sideContent.addView(infoCard("Зачем" to "PNG сохраняется в Загрузки — по нему можно проверить отображение чертежа"))
        binding.sideContent.addView(sectionTitle("О программе"))
        binding.sideContent.addView(infoCard(
            "Редактор" to "SikkatuCAD — чертежи DXF/DWG + перевод надписей",
            "Форматы" to "DXF, DWG — открытие и сохранение"
        ))
    }
    private fun showTranslationPanel() {
        showSidePanel(null, "Перевод надписей")
        binding.sideContent.removeAllViews()
        binding.sideContent.addView(sectionTitle("Перевод надписей (EN / TH)"))
        binding.sideContent.addView(entryView("Файл", currentFileName ?: "-"))
        binding.sideContent.addView(actionRow("Извлечь тексты из чертежа") { extractTranslationTexts() })
        binding.sideContent.addView(actionRow("Загрузить JSON перевода") {
            translationImport.launch(arrayOf("application/json", "text/plain", "*/*"))
        })
        binding.sideContent.addView(actionRow("Экспорт JSON перевода") { exportTranslationJson() })
        binding.sideContent.addView(sectionTitle("Сохранить с переводом"))
        binding.sideContent.addView(primaryActionRow("Сохранить DXF (EN)") { saveTranslated("dxf", "en") })
        binding.sideContent.addView(actionRow("Сохранить DXF (TH)") { saveTranslated("dxf", "th") })
        binding.sideContent.addView(actionRow("Сохранить DWG (эксперим.) EN") { saveTranslated("dwg", "en") })
        binding.sideContent.addView(actionRow("Сохранить DWG (эксперим.) TH") { saveTranslated("dwg", "th") })
        binding.sideContent.addView(
            TextView(this).apply {
                text = "⚠ Запись DWG экспериментальная — обязательно проверьте результат в AutoCAD. " +
                    "Надёжный формат — DXF.\nПереводятся только видимые надписи (TEXT/MTEXT/ATTRIB); " +
                    "имена слоёв и блоков не переводятся — они не видны на экране и в печати."
                textSize = 11f
                setTextColor(getColor(R.color.panelTextSecondary))
                setPadding(12.dp, 6.dp, 12.dp, 10.dp)
            }
        )
        binding.sideContent.addView(sectionTitle("Тексты чертежа"))
        translationRowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        binding.sideContent.addView(translationRowsContainer)
        renderTranslationRows()
    }

    private fun isCurrentPdfSource(): Boolean {
        return currentFileName?.substringAfterLast('.', "")?.equals("pdf", ignoreCase = true) == true
    }

    private fun showPdfScaleDialog(document: DxfDocument) {
        val original = document
        val bounds = document.bounds
        val baseBounds = original.bounds
        val detectedRatio = original.metadata["pdf_scale_ratio"]?.toDoubleOrNull()
        val currentScale = document.metadata["pdf_applied_scale"]?.toDoubleOrNull() ?: 1.0
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        }
        val currentSize = TextView(this).apply {
            text = getString(R.string.s0125, "%.3f".format(bounds.width), "%.3f".format(bounds.height))
            setTextColor(getColor(R.color.panelTextPrimary))
        }
        val sourceSize = TextView(this).apply {
            text = getString(R.string.s0126, "%.3f".format(baseBounds.width), "%.3f".format(baseBounds.height))
            setTextColor(getColor(R.color.panelTextSecondary))
        }
        val ratioInfo = TextView(this).apply {
            text = detectedRatio?.let { getString(R.string.s0127, "%.3f".format(it), "%.4f".format(currentScale)) }
                ?: getString(R.string.s0128)
            setTextColor(getColor(R.color.panelTextSecondary))
        }
        val widthField = EditText(this).apply {
            hint = getString(R.string.s0129)
            setText("%.3f".format(bounds.width))
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
            background = getDrawable(R.drawable.bg_panel)
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
        }
        val heightField = EditText(this).apply {
            hint = getString(R.string.s0130)
            setText("%.3f".format(bounds.height))
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
            background = getDrawable(R.drawable.bg_panel)
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
        }
        content.addView(currentSize)
        content.addView(sourceSize)
        content.addView(ratioInfo)
        content.addView(widthField)
        content.addView(heightField)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0131))
            .setView(content)
            .setNeutralButton(getString(R.string.s0132)) { _, _ ->
                val ratio = detectedRatio
                if (ratio == null || ratio <= 0.0) {
                    binding.statusText.text = getString(R.string.s0133)
                    return@setNeutralButton
                }
                applyScaledPreview(ratio)
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .setPositiveButton(getString(R.string.s0135)) { _, _ ->
                val targetWidth = widthField.text?.toString()?.toDoubleOrNull()
                val targetHeight = heightField.text?.toString()?.toDoubleOrNull()
                val scale = when {
                    targetWidth != null && targetWidth > 0.0 && baseBounds.width > 0.0 -> targetWidth / baseBounds.width
                    targetHeight != null && targetHeight > 0.0 && baseBounds.height > 0.0 -> targetHeight / baseBounds.height
                    else -> null
                }
                if (scale == null || !scale.isFinite() || scale <= 0.0) {
                    binding.statusText.text = getString(R.string.s0136)
                    return@setPositiveButton
                }
                applyScaledPreview(scale)
            }
            .show()
    }

    private fun applyScaledPreview(scale: Double) {
        val source = currentPreviewDocument ?: return
        val scaled = scaleDocument(source, scale)
        currentPreviewDocument = scaled
        currentParsed = when (val parsed = currentParsed) {
            is ParsedCadFile.Dxf -> ParsedCadFile.Dxf(scaled)
            is ParsedCadFile.Nc -> parsed.copy(document = scaled)
            else -> parsed
        }
        pushHistorySnapshot(scaled, getString(R.string.s0137))
        binding.dxfCanvasView.setDocument(scaled)
        binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
        binding.dxfCanvasView.setSimulationOperations(buildSimulationOperationsForDocument(scaled))
        binding.dxfCanvasView.setSimulationNcProgram((currentParsed as? ParsedCadFile.Nc)?.program)
        binding.statusText.text = getString(R.string.s0138)
        showInfoPanel()
    }

    private fun scaleDocument(document: DxfDocument, scale: Double): DxfDocument {
        fun scalePoint(point: Point2): Point2 = Point2(point.x * scale, point.y * scale)
        val scaledEntities = document.entities.map { entity ->
            when (entity) {
                is DxfLine -> entity.copy(start = scalePoint(entity.start), end = scalePoint(entity.end))
                is DxfPolyline -> entity.copy(points = entity.points.map(::scalePoint))
                is DxfCircle -> entity.copy(center = scalePoint(entity.center), radius = entity.radius * scale)
                is DxfArc -> entity.copy(center = scalePoint(entity.center), radius = entity.radius * scale)
                is DxfText -> entity.copy(position = scalePoint(entity.position), height = entity.height * scale)
            }
        }
        return document.copy(
            entities = scaledEntities,
            metadata = document.metadata + mapOf(
                "pdf_applied_scale" to scale.toString(),
                "pdf_scaled_width" to (document.bounds.width * scale).toString(),
                "pdf_scaled_height" to (document.bounds.height * scale).toString()
            )
        )
    }

    private fun filterVisibleLayers(document: DxfDocument): DxfDocument {
        val filteredEntities = document.entities.filter { entity ->
            entity.layer == null || entity.layer !in hiddenLayers
        }
        val filteredLayerInfos = document.layerInfos.filter { it.name !in hiddenLayers }
        return document.copy(
            entities = filteredEntities,
            layerInfos = filteredLayerInfos
        )
    }

    private fun renderAllFiles() {
        val root = libraryStore.resolveTreeRoot(this)
        if (root == null) {
            binding.sideContent.addView(entryView(getString(R.string.s0139), getString(R.string.s0140)))
            return
        }
        val directory = libraryStore.resolveBrowseDirectory(this) ?: root
        binding.sideContent.addView(entryView(getString(R.string.s0141), directory.uri.toString()))
        if (directory.uri != root.uri) {
            binding.sideContent.addView(actionRow(getString(R.string.s0142)) {
                val parent = findParentDocument(root, directory.uri)
                libraryStore.setBrowseUri(parent?.uri ?: root.uri)
                renderFilePanel()
            })
        }
        val children = directory.listFiles()
            .sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase(Locale.getDefault()) ?: "" })
        if (children.isEmpty()) {
            binding.sideContent.addView(entryView(getString(R.string.s0143), getString(R.string.s0144)))
            return
        }
        children.forEach { child ->
            val label = if (child.isDirectory) getString(R.string.s0145, child.name) else child.name ?: getString(R.string.s0033)
            if (child.isDirectory) {
                binding.sideContent.addView(actionRow(label) {
                    libraryStore.setBrowseUri(child.uri)
                    renderFilePanel()
                })
            } else if (isSupportedCadFile(child.name)) {
                binding.sideContent.addView(actionRow(label) {
                    openUri(child.uri, persistPermission = false)
                })
            }
        }
    }

    private fun renderSearchPanel() {
        binding.sideContent.addView(sectionTitle(getString(R.string.s0146)))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 10.dp
            layoutParams = params
        }
        val input = EditText(this).apply {
            hint = getString(R.string.s0147)
            setText(lastSearchQuery)
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
            background = getDrawable(R.drawable.bg_panel)
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val button = TextView(this).apply {
            text = getString(R.string.s0148)
            gravity = Gravity.CENTER
            setPadding(18.dp, 14.dp, 18.dp, 14.dp)
            setTextColor(getColor(R.color.panelTextPrimary))
            background = getDrawable(R.drawable.bg_panel)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginStart = 8.dp
            layoutParams = params
            setOnClickListener {
                lastSearchQuery = input.text?.toString().orEmpty().trim()
                performSearch(lastSearchQuery)
            }
        }
        row.addView(input)
        row.addView(button)
        binding.sideContent.addView(row)
        if (lastSearchQuery.isBlank()) {
            binding.sideContent.addView(entryView(getString(R.string.s0149), getString(R.string.s0150)))
        }
    }

    private fun performSearch(query: String) {
        val root = libraryStore.resolveTreeRoot(this)
        if (root == null) {
            binding.statusText.text = getString(R.string.s0151)
            return
        }
        if (query.isBlank()) {
            binding.statusText.text = getString(R.string.s0152)
            return
        }
        binding.statusText.text = getString(R.string.s0153, query)
        worker.execute {
            val results = mutableListOf<StoredFileRef>()
            searchDocuments(root, query.lowercase(Locale.getDefault()), results)
            runOnUiThread {
                renderFilePanel()
                binding.sideContent.addView(sectionTitle(getString(R.string.s0154)))
                if (results.isEmpty()) {
                    binding.sideContent.addView(entryView(getString(R.string.s0155), getString(R.string.s0156)))
                } else {
                    results.take(80).forEach { ref ->
                        binding.sideContent.addView(actionRow(ref.name) {
                            openUri(Uri.parse(ref.uri), persistPermission = false)
                        })
                    }
                }
                binding.statusText.text = getString(R.string.s0157, results.size)
            }
        }
    }

    private fun searchDocuments(directory: DocumentFile, query: String, results: MutableList<StoredFileRef>) {
        directory.listFiles().forEach { file ->
            val name = file.name.orEmpty()
            if (file.isDirectory) {
                searchDocuments(file, query, results)
            } else if (isSupportedCadFile(name) && name.lowercase(Locale.getDefault()).contains(query)) {
                results += StoredFileRef(file.uri.toString(), name)
            }
        }
    }

    private fun toggleOrientation() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        binding.statusText.text = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            getString(R.string.s0158)
        } else {
            getString(R.string.s0159)
        }
    }

    private var lastOpenedUri: android.net.Uri? = null
    private var lastOpenedFileName: String? = null
    private fun returnToHome() {
        // Запоминаем последний файл, чтобы можно было вернуться к чертежу
        lastOpenedUri = currentUri
        lastOpenedFileName = currentFileName
        currentParsed = null
        currentPreviewDocument = null
        currentSelection = null
        currentUri = null
        currentFileName = null
        hiddenLayers.clear()
        pendingExportBytes = null
        binding.fileNameText.text = getString(R.string.no_file)
        binding.statusText.text = getString(R.string.status_idle)
        binding.dxfCanvasView.clearSelection(notify = false)
        binding.dxfCanvasView.setViewMode(DxfCanvasView.ViewMode.TWO_D)
        binding.dxfCanvasView.setSimulationNcProgram(null)
        binding.dxfCanvasView.setNc3DSource(null)
        updateViewModeChips()
        showEmpty()
    }

    private fun showLayerPanel() {
        val nc = currentParsed as? ParsedCadFile.Nc
        if (nc != null) {
            showSidePanel(binding.actionLayers, getString(R.string.panel_layers))
            binding.sideContent.removeAllViews()
            listOf(
                "NC_SHEET" to getString(R.string.s0160),
                "NC_RAPID" to getString(R.string.s0161),
                "NC_CUT" to getString(R.string.s0162),
                "NC_BEVEL_A_POS" to getString(R.string.s0163),
                "NC_BEVEL_A_NEG" to getString(R.string.s0164),
                "NC_BEVEL_B_POS" to getString(R.string.s0165),
                "NC_BEVEL_B_NEG" to getString(R.string.s0166)
            ).forEach { (layer, label) ->
                val check = CheckBox(this).apply {
            setTextColor(getColor(R.color.panelTextPrimary))
                    text = label
                    setTextColor(getColor(R.color.panelTextPrimary))
                    isChecked = !hiddenLayers.contains(layer)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) hiddenLayers.remove(layer) else hiddenLayers.add(layer)
                        val bevelLayers = setOf("NC_BEVEL_A_POS", "NC_BEVEL_A_NEG", "NC_BEVEL_B_POS", "NC_BEVEL_B_NEG")
                        if (layer in bevelLayers) {
                            val anyBevelVisible = bevelLayers.any { it !in hiddenLayers }
                            if (anyBevelVisible) hiddenLayers.remove("NC_BEVEL_LABEL") else hiddenLayers.add("NC_BEVEL_LABEL")
                        }
                        binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
                    }
                }
                binding.sideContent.addView(check)
            }
            return
        }
        val document = currentPreviewDocument ?: run {
            binding.statusText.text = getString(R.string.s0178)
            return
        }
        showSidePanel(binding.actionLayers, getString(R.string.panel_layers))
        binding.sideContent.removeAllViews()
        binding.sideContent.addView(actionRow(getString(R.string.s0172)) {
            hiddenLayers.clear()
            binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
            showLayerPanel()
        })
        binding.sideContent.addView(actionRow(getString(R.string.s0173)) {
            hiddenLayers.clear()
            hiddenLayers.addAll(document.layers)
            binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
            showLayerPanel()
        })
        document.layers.forEach { layer ->
            val layerInfo = document.layerInfos.firstOrNull { it.name == layer }
            val entityCount = document.entityCountByLayer[layer] ?: 0
            val check = CheckBox(this).apply {
                setTextColor(getColor(R.color.panelTextPrimary))
                text = buildString {
                    append(layer)
                    append("  ·  ")
                    append(entityCount)
                    append(getString(R.string.s0174))
                    if (layerInfo?.isOff == true) append(getString(R.string.s0175))
                    if (layerInfo?.isFrozen == true) append(getString(R.string.s0176))
                }
                isChecked = !hiddenLayers.contains(layer)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) hiddenLayers.remove(layer) else hiddenLayers.add(layer)
                    binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
                }
            }
            binding.sideContent.addView(check)
        }
    }

    private fun showMeasurePanel() {
        val parsed = currentPreviewDocument ?: run {
            if (currentParsed is ParsedCadFile.Gen) {
                binding.statusText.text = getString(R.string.s0177)
            } else {
                binding.statusText.text = getString(R.string.s0178)
            }
            return
        }
        showSidePanel(binding.actionMeasure, getString(R.string.s0179))
        binding.sideContent.removeAllViews()
        binding.sideContent.addView(sectionTitle(getString(R.string.s0180)))
        val autoDimensionCheck = CheckBox(this).apply {
            text = getString(R.string.s0181)
            isChecked = binding.dxfCanvasView.isAutoDimensionEnabled()
            setTextColor(getColor(R.color.panelTextPrimary))
            setOnCheckedChangeListener { _, checked ->
                binding.dxfCanvasView.setAutoDimensionEnabled(checked)
                binding.statusText.text = if (checked) {
                    getString(R.string.s0046, binding.dxfCanvasView.autoDimensionSummary().orEmpty())
                } else {
                    getString(R.string.s0047)
                }
                showMeasurePanel()
            }
        }
        binding.sideContent.addView(autoDimensionCheck)
        binding.dxfCanvasView.autoDimensionSummary()?.let { summary ->
            binding.sideContent.addView(infoLine(getString(R.string.s0182), summary))
        }
        binding.sideContent.addView(sectionTitle(getString(R.string.s0183)))
        binding.sideContent.addView(actionRowIconized("📏", getString(R.string.s0184), "Линейка: две точки — длина отрезка") {
            measureMode = MeasureMode.DISTANCE
            binding.dxfCanvasView.setMeasureMode(measureMode)
            binding.statusText.text = getString(R.string.s0185)
            showMeasurePanel()
        })
        binding.sideContent.addView(actionRowIconized("📐", getString(R.string.s0186), "Угол между тремя точками") {
            measureMode = MeasureMode.ANGLE
            binding.dxfCanvasView.setMeasureMode(measureMode)
            binding.statusText.text = getString(R.string.s0187)
            showMeasurePanel()
        })
        binding.sideContent.addView(actionRowIconized("⬠", getString(R.string.s0188), "Площадь по контуру точек") {
            measureMode = MeasureMode.AREA
            binding.dxfCanvasView.setMeasureMode(measureMode)
            binding.dxfCanvasView.setAreaAutoClose(areaAutoClose)
            binding.statusText.text = getString(R.string.s0189)
            showMeasurePanel()
        })
        binding.sideContent.addView(actionRowIconized("✋", getString(R.string.s0190), "Выключить режим замеров") {
            measureMode = MeasureMode.NONE
            binding.dxfCanvasView.setMeasureMode(MeasureMode.NONE)
            binding.statusText.text = getString(R.string.s0191)
            showMeasurePanel()
        })
        if (measureMode == MeasureMode.AREA) {
            binding.sideContent.addView(actionRow(if (areaAutoClose) getString(R.string.s0192) else getString(R.string.s0193)) {
                areaAutoClose = !areaAutoClose
                binding.dxfCanvasView.setAreaAutoClose(areaAutoClose)
                showMeasurePanel()
            })
            binding.sideContent.addView(actionRow(getString(R.string.s0194)) {
                binding.dxfCanvasView.completeAreaMeasurement()
            })
        }
        if (measureMode != MeasureMode.NONE) {
            binding.sideContent.addView(actionRowIconized("↩", getString(R.string.s0195), "Убрать последнюю точку") { binding.dxfCanvasView.undoMeasurementStep() })
            binding.sideContent.addView(actionRowIconized("🧹", getString(R.string.s0196), "Сбросить текущий замер") { binding.dxfCanvasView.clearMeasurement() })
        }
        binding.sideContent.addView(sectionTitle(getString(R.string.s0197)))
        binding.sideContent.addView(primaryActionRow(getString(R.string.s0198)) { showCadEditPanel() })
        binding.sideContent.addView(infoLine(getString(R.string.s0149), getString(R.string.s0199)))
        binding.sideContent.addView(infoLine(getString(R.string.s0200), when (measureMode) {
            MeasureMode.NONE -> getString(R.string.s0201)
            MeasureMode.DISTANCE -> getString(R.string.s0202)
            MeasureMode.ANGLE -> getString(R.string.s0203)
            MeasureMode.AREA -> getString(R.string.s0204)
        }))
        binding.sideContent.addView(infoLine(getString(R.string.s0205), parsed.layers.size.toString()))
    }

    private fun showSimulationPanel() {
        val gen = currentParsed as? ParsedCadFile.Gen
        if (gen != null) {
            // Make sure GEN operations are fed into the canvas emulation engine
            binding.dxfCanvasView.stopSimulation()
            simulationProgressText = "0%"
            simulationStateText = getString(R.string.s0037)
            binding.dxfCanvasView.setSimulationNcProgram(null)
            binding.dxfCanvasView.setSimulationOperations(gen.document.operations)
            showGenSimulationPanel(gen.document)
            return
        }
        val parsed = currentPreviewDocument ?: run {
            binding.statusText.text = getString(R.string.s0206)
            return
        }
        showSidePanel(null, getString(R.string.s0207))
        binding.sideContent.removeAllViews()
        val label = when (currentParsed) {
            is ParsedCadFile.Nc -> "NC"
            else -> getString(R.string.s0208)
        }
        // Make sure preview data is in sync with the current document
        binding.dxfCanvasView.stopSimulation()
        simulationProgressText = "0%"
        simulationStateText = getString(R.string.s0037)
        if (currentParsed is ParsedCadFile.Nc) {
            binding.dxfCanvasView.setSimulationOperations(emptyList())
            binding.dxfCanvasView.setSimulationNcProgram((currentParsed as ParsedCadFile.Nc).program)
        } else {
            val ops = buildSimulationOperationsForDocument(parsed)
            binding.dxfCanvasView.setSimulationNcProgram(null)
            binding.dxfCanvasView.setSimulationOperations(ops)
        }
        binding.sideContent.addView(simulationControlsCard(label))
        val ops = buildSimulationOperationsForDocument(parsed)
        binding.sideContent.addView(infoLine(getString(R.string.s0209), ops.count { it.type == GenOperationType.IDLE }.toString()))
        binding.sideContent.addView(infoLine(getString(R.string.s0210), ops.count { it.type == GenOperationType.MARKING }.toString()))
        binding.sideContent.addView(infoLine(getString(R.string.s0211), ops.count { it.type == GenOperationType.BURNING }.toString()))
    }

    private fun showSelectionEditor(selection: GeometrySelection) {
        applySelectionBaseView(selection)
        showSidePanel(null, getString(R.string.s0212))
        binding.sideContent.removeAllViews()
        binding.sideContent.addView(infoLine(getString(R.string.s0213), selection.kind))
        binding.sideContent.addView(infoLine(getString(R.string.s0214), selection.layer ?: getString(R.string.s0215)))
        binding.sideContent.addView(infoLine(getString(R.string.s0216), "%.3f".format(segmentLength(selection.start, selection.end))))
        binding.sideContent.addView(infoLine(getString(R.string.s0217), "ΔX %.3f / ΔY %.3f".format(selection.end.x - selection.start.x, selection.end.y - selection.start.y)))
        val fields = editablePointFields(selection)
        val lengthField = lengthInputRow(getString(R.string.s0218), segmentLength(selection.start, selection.end), "segmentLength")
        binding.sideContent.addView(fields)
        binding.sideContent.addView(lengthField)
        binding.sideContent.addView(actionRow(getString(R.string.s0219)) {
            val startX = fields.findViewWithTag<EditText>("startX").text.toString().toDoubleOrNull()
            val startY = fields.findViewWithTag<EditText>("startY").text.toString().toDoubleOrNull()
            val endX = fields.findViewWithTag<EditText>("endX").text.toString().toDoubleOrNull()
            val endY = fields.findViewWithTag<EditText>("endY").text.toString().toDoubleOrNull()
            val newLength = lengthField.findViewWithTag<EditText>("segmentLength").text.toString().toDoubleOrNull()
            if (listOf(startX, startY, endX, endY, newLength).any { it == null }) {
                binding.statusText.text = getString(R.string.s0220)
                return@actionRow
            }
            val adjusted = adjustSegmentToLength(
                start = Point2(startX!!, startY!!),
                end = Point2(endX!!, endY!!),
                targetLength = newLength!!
            )
            applySelectionEdit(selection, adjusted.first, adjusted.second)
        })
        binding.sideContent.addView(actionRow(getString(R.string.s0221)) {
            currentSelection = null
            binding.dxfCanvasView.clearSelection()
            showInfoPanel()
        })
        binding.sideContent.addView(infoLine(getString(R.string.s0222), historySummary()))
        binding.sideContent.addView(historyListView())
    }

    private fun editablePointFields(selection: GeometrySelection): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pointInputRow(getString(R.string.s0223), selection.start.x, "startX"))
            addView(pointInputRow(getString(R.string.s0224), selection.start.y, "startY"))
            addView(pointInputRow(getString(R.string.s0225), selection.end.x, "endX"))
            addView(pointInputRow(getString(R.string.s0226), selection.end.y, "endY"))
        }
    }

    private fun pointInputRow(label: String, value: Double, tagValue: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val input = EditText(this@MainActivity).apply {
                tag = tagValue
                setText("%.4f".format(value))
                setTextColor(getColor(R.color.panelTextPrimary))
                setHintTextColor(getColor(R.color.panelTextSecondary))
                background = getDrawable(R.drawable.bg_panel)
                setPadding(14.dp, 14.dp, 14.dp, 14.dp)
            }
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTextColor(getColor(R.color.panelTextSecondary))
            })
            addView(input)
        }
    }

    private fun lengthInputRow(label: String, value: Double, tagValue: String): LinearLayout {
        return pointInputRow(label, value, tagValue)
    }

    private fun applySelectionEdit(selection: GeometrySelection, newStart: Point2, newEnd: Point2) {
        val document = currentPreviewDocument ?: return
        val updatedEntities = document.entities.toMutableList()
        val entity = updatedEntities.getOrNull(selection.entityIndex) ?: return
        updatedEntities[selection.entityIndex] = when (entity) {
            is DxfLine -> entity.copy(start = newStart, end = newEnd)
            is DxfPolyline -> {
                val points = entity.points.toMutableList()
                if (selection.segmentIndex < points.lastIndex) {
                    points[selection.segmentIndex] = newStart
                    points[selection.segmentIndex + 1] = newEnd
                } else if (entity.closed && points.isNotEmpty()) {
                    points[points.lastIndex] = newStart
                    points[0] = newEnd
                }
                entity.copy(points = points)
            }
            else -> entity
        }
        val updated = document.copy(entities = updatedEntities)
        applyDocumentMutation(updated, getString(R.string.s0227))
        currentSelection = selection.copy(start = newStart, end = newEnd)
        binding.dxfCanvasView.setSelection(currentSelection)
        binding.statusText.text = getString(R.string.s0228)
        currentSelection?.let(::showSelectionEditor)
    }

    private fun showCadEditPanel() {
        val document = currentPreviewDocument ?: run {
            binding.statusText.text = getString(R.string.s0229)
            return
        }
        showSidePanel(binding.actionMeasure, getString(R.string.s0230))
        binding.sideContent.removeAllViews()
        binding.sideContent.addView(sectionTitle(getString(R.string.s0231)))
        binding.sideContent.addView(primaryActionRow("+ " + getString(R.string.s0232)) { showNewDrawingDialog() })
        binding.sideContent.addView(gridRow(
            gridButton("\u2571", getString(R.string.s0233)) { showCreateLineDialog(document) },
            gridButton("\u25AD", getString(R.string.s0234)) { showCreateRectangleDialog(document) }
        ))
        binding.sideContent.addView(gridRow(
            gridButton("\u25CB", getString(R.string.s0235)) { showCreateCircleDialog(document) },
            gridButton("\u25D7", getString(R.string.s0236)) { showCreateArcDialog(document) }
        ))
        binding.sideContent.addView(gridRow(
            gridButton("\u29A1", getString(R.string.s0237)) { showCreatePolylineDialog(document) },
            gridButton("\u270E", getString(R.string.s0238)) { showCreateTextDialog(document) }
        ))
        binding.sideContent.addView(sectionTitle(getString(R.string.s0239)))
        binding.sideContent.addView(gridRow(
            gridButton("\u27E0", getString(R.string.s0241)) { showInsertTemplateDialog(document, getString(R.string.s0241)) },
            gridButton("\u229F", getString(R.string.s0243)) { showInsertTemplateDialog(document, getString(R.string.s0243)) }
        ))
        binding.sideContent.addView(gridRow(
            gridButton("\u2295", getString(R.string.s0245)) { showInsertTemplateDialog(document, getString(R.string.s0245)) },
            gridButton("\u2299", getString(R.string.s0247)) { showInsertTemplateDialog(document, getString(R.string.s0247)) }
        ))
        binding.sideContent.addView(gridRow(
            gridButton("\u229E", getString(R.string.s0248)) { showInsertTemplateDialog(document, getString(R.string.s0248)) }
        ))
        binding.sideContent.addView(sectionTitle(getString(R.string.s0249)))
        binding.sideContent.addView(actionRow(getString(R.string.s0250)) { showEditSelectedEntityDialog() })
        binding.sideContent.addView(actionRow(getString(R.string.s0251)) { copySelectedEntity() })
        binding.sideContent.addView(actionRow(getString(R.string.s0252)) { pasteClipboardEntity() })
        binding.sideContent.addView(actionRow(getString(R.string.s0253)) { trimSelectedSegment(0.1) })
        binding.sideContent.addView(actionRow(getString(R.string.s0254)) { deleteSelectedEntity() })
        binding.sideContent.addView(sectionTitle(getString(R.string.s0255)))
        binding.sideContent.addView(actionRow(getString(R.string.s0256)) { undoHistory() })
        binding.sideContent.addView(actionRow(getString(R.string.s0257)) { redoHistory() })
        binding.sideContent.addView(infoLine(getString(R.string.s0258), currentSelection?.let { "${it.kind} · #${it.entityIndex}" } ?: getString(R.string.s0259)))
        binding.sideContent.addView(infoLine(getString(R.string.s0260), if (clipboardEntity != null) getString(R.string.s0261) else getString(R.string.s0262)))
        binding.sideContent.addView(infoLine(getString(R.string.s0263), document.entities.size.toString()))
    }

    private fun activateDxfDocument(document: DxfDocument, status: String) {
        currentParsed = ParsedCadFile.Dxf(document)
        currentPreviewDocument = document
        currentUri = null
        currentFileName = document.fileName
        currentSelection = null
        clearSelectionBaseView()
        resetHistory()
        pushHistorySnapshot(document, getString(R.string.s0264))
        hiddenLayers.clear()
        measureMode = MeasureMode.NONE
        binding.emptyPanel.visibility = View.GONE
        binding.infoScrollView.visibility = View.GONE
        binding.dxfCanvasView.visibility = View.VISIBLE
        binding.fileTypeChip.text = "DXF"
        binding.metaChip.text = getString(R.string.s0265, document.layers.size, document.units ?: "mm")
        binding.dxfCanvasView.setViewMode(DxfCanvasView.ViewMode.TWO_D)
        binding.dxfCanvasView.setMeasureMode(MeasureMode.NONE)
        binding.dxfCanvasView.setDocument(document)
        binding.dxfCanvasView.setHiddenLayers(emptySet())
        binding.dxfCanvasView.setNcBevelEnabled(ncBevelEnabled)
        binding.dxfCanvasView.setSimulationOperations(buildSimulationOperationsForDocument(document))
        binding.dxfCanvasView.setSimulationNcProgram(null)
        binding.dxfCanvasView.setNc3DSource(null)
        binding.statusText.text = status
    }

    private fun showNewDrawingDialog() {
        val form = editForm(
            getString(R.string.s0266) to getString(R.string.s0267), getString(R.string.s0268) to "1000", getString(R.string.s0269) to "700", getString(R.string.s0214) to "0"
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0270))
            .setView(form)
            .setPositiveButton(getString(R.string.s0271)) { _, _ ->
                val name = form.valueAt(getString(R.string.s0266)).ifBlank { getString(R.string.s0267) }
                val w = form.valueAt(getString(R.string.s0268)).toDoubleOrNull() ?: 1000.0
                val h = form.valueAt(getString(R.string.s0269)).toDoubleOrNull() ?: 700.0
                activateDxfDocument(DxfDocument(name, emptyList(), units = "mm", declaredBounds = RectBounds(0.0, 0.0, w, h)), getString(R.string.s0272, name))
                showCadEditPanel()
            }
            .setNeutralButton(getString(R.string.s0273)) { _, _ ->
                val name = form.valueAt(getString(R.string.s0266)).ifBlank { getString(R.string.s0267) }
                val w = form.valueAt(getString(R.string.s0268)).toDoubleOrNull() ?: 1000.0
                val h = form.valueAt(getString(R.string.s0269)).toDoubleOrNull() ?: 700.0
                val layer = form.valueAt(getString(R.string.s0214)).ifBlank { "0" }
                val entities = listOf(rectPolyline(0.0, 0.0, w, h, layer), DxfText(Point2(20.0, h - 40.0), name, 24.0, "TITLE"))
                activateDxfDocument(DxfDocument(name, entities, units = "mm", declaredBounds = RectBounds(0.0, 0.0, w, h)), getString(R.string.s0274, name))
                showCadEditPanel()
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .show()
    }

    private fun showCreateLineDialog(document: DxfDocument) {
        val form = editForm(getString(R.string.s0275) to "0", getString(R.string.s0276) to "0", getString(R.string.s0277) to "100", getString(R.string.s0278) to "0", getString(R.string.s0214) to "CAD_EDIT")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0233))
            .setView(form)
            .setPositiveButton(getString(R.string.s0279)) { _, _ ->
                val sx = form.valueAt(getString(R.string.s0275)).toDoubleOrNull() ?: return@setPositiveButton
                val sy = form.valueAt(getString(R.string.s0276)).toDoubleOrNull() ?: return@setPositiveButton
                val ex = form.valueAt(getString(R.string.s0277)).toDoubleOrNull() ?: return@setPositiveButton
                val ey = form.valueAt(getString(R.string.s0278)).toDoubleOrNull() ?: return@setPositiveButton
                val layer = form.valueAt(getString(R.string.s0214)).ifBlank { "CAD_EDIT" }
                applyDocumentMutation(document.copy(entities = document.entities + DxfLine(Point2(sx, sy), Point2(ex, ey), layer)), getString(R.string.s0233))
                showCadEditPanel()
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .show()
    }
private fun showCreateRectangleDialog(document: DxfDocument) {
        val form = editForm(
            "X" to "0", "Y" to "0", getString(R.string.s0268) to "100", getString(R.string.s0269) to "60", getString(R.string.s0214) to "CAD_EDIT"
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0234))
            .setView(form)
            .setPositiveButton(getString(R.string.s0279)) { _, _ ->
                val x = form.valueAt("X").toDoubleOrNull() ?: return@setPositiveButton
                val y = form.valueAt("Y").toDoubleOrNull() ?: return@setPositiveButton
                val w = form.valueAt(getString(R.string.s0268)).toDoubleOrNull() ?: return@setPositiveButton
                val h = form.valueAt(getString(R.string.s0269)).toDoubleOrNull() ?: return@setPositiveButton
                val layer = form.valueAt(getString(R.string.s0214)).ifBlank { "CAD_EDIT" }
                val entity = rectPolyline(x, y, x + w, y + h, layer)
                applyDocumentMutation(document.copy(entities = document.entities + entity), getString(R.string.s0234))
                showCadEditPanel()
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .show()
    }

    private fun showCreateCircleDialog(document: DxfDocument) {
        val form = editForm(getString(R.string.s0280) to "0", getString(R.string.s0281) to "0", getString(R.string.s0282) to "50", getString(R.string.s0214) to "CAD_EDIT")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0235))
            .setView(form)
            .setPositiveButton(getString(R.string.s0279)) { _, _ ->
                val x = form.valueAt(getString(R.string.s0280)).toDoubleOrNull() ?: return@setPositiveButton
                val y = form.valueAt(getString(R.string.s0281)).toDoubleOrNull() ?: return@setPositiveButton
                val r = form.valueAt(getString(R.string.s0282)).toDoubleOrNull() ?: return@setPositiveButton
                val layer = form.valueAt(getString(R.string.s0214)).ifBlank { "CAD_EDIT" }
                applyDocumentMutation(document.copy(entities = document.entities + DxfCircle(Point2(x, y), r, layer)), getString(R.string.s0235))
                showCadEditPanel()
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .show()
    }

    private fun showCreateArcDialog(document: DxfDocument) {
        val form = editForm(getString(R.string.s0280) to "0", getString(R.string.s0281) to "0", getString(R.string.s0282) to "50", getString(R.string.s0283) to "0", getString(R.string.s0284) to "90", getString(R.string.s0214) to "CAD_EDIT")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0236))
            .setView(form)
            .setPositiveButton(getString(R.string.s0279)) { _, _ ->
                val x = form.valueAt(getString(R.string.s0280)).toDoubleOrNull() ?: return@setPositiveButton
                val y = form.valueAt(getString(R.string.s0281)).toDoubleOrNull() ?: return@setPositiveButton
                val r = form.valueAt(getString(R.string.s0282)).toDoubleOrNull() ?: return@setPositiveButton
                val start = form.valueAt(getString(R.string.s0283)).toDoubleOrNull() ?: 0.0
                val end = form.valueAt(getString(R.string.s0284)).toDoubleOrNull() ?: 90.0
                val layer = form.valueAt(getString(R.string.s0214)).ifBlank { "CAD_EDIT" }
                applyDocumentMutation(document.copy(entities = document.entities + DxfArc(Point2(x, y), r, start, end, layer)), getString(R.string.s0236))
                showCadEditPanel()
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .show()
    }

    private fun showCreatePolylineDialog(document: DxfDocument) {
        val form = editForm(getString(R.string.s0285) to "0,0;100,0;100,80", getString(R.string.s0286) to "0", getString(R.string.s0214) to "CAD_EDIT")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0237))
            .setView(form)
            .setPositiveButton(getString(R.string.s0279)) { _, _ ->
                val points = parsePointList(form.valueAt(getString(R.string.s0285)))
                if (points.size < 2) {
                    binding.statusText.text = getString(R.string.s0287)
                    return@setPositiveButton
                }
                val closed = form.valueAt(getString(R.string.s0286)) == "1"
                val layer = form.valueAt(getString(R.string.s0214)).ifBlank { "CAD_EDIT" }
                applyDocumentMutation(document.copy(entities = document.entities + DxfPolyline(points, closed, layer)), getString(R.string.s0237))
                showCadEditPanel()
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .show()
    }

    private fun showInsertTemplateDialog(document: DxfDocument, template: String) {
        val form = editForm(getString(R.string.s0288) to "0", getString(R.string.s0289) to "0", getString(R.string.s0268) to "200", getString(R.string.s0269) to "120", getString(R.string.s0290) to "20", getString(R.string.s0214) to "STD_PART")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0291, template))
            .setView(form)
            .setPositiveButton(getString(R.string.s0292)) { _, _ ->
                val x = form.valueAt(getString(R.string.s0288)).toDoubleOrNull() ?: 0.0
                val y = form.valueAt(getString(R.string.s0289)).toDoubleOrNull() ?: 0.0
                val w = form.valueAt(getString(R.string.s0268)).toDoubleOrNull() ?: 200.0
                val h = form.valueAt(getString(R.string.s0269)).toDoubleOrNull() ?: 120.0
                val t = form.valueAt(getString(R.string.s0290)).toDoubleOrNull() ?: 20.0
                val layer = form.valueAt(getString(R.string.s0214)).ifBlank { "STD_PART" }
                val entities = standardTemplateEntities(template, x, y, w, h, t, layer)
                applyDocumentMutation(document.copy(entities = document.entities + entities), getString(R.string.s0291, template))
                showCadEditPanel()
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .show()
    }

    private fun parsePointList(raw: String): List<Point2> {
        return raw.split(';', '\n')
            .mapNotNull { token ->
                val parts = token.trim().split(',', ' ' ).filter { it.isNotBlank() }
                val x = parts.getOrNull(0)?.toDoubleOrNull()
                val y = parts.getOrNull(1)?.toDoubleOrNull()
                if (x != null && y != null) Point2(x, y) else null
            }
    }

    private fun standardTemplateEntities(template: String, x: Double, y: Double, w: Double, h: Double, t: Double, layer: String): List<DxfEntity> {
        return when (template) {
            getString(R.string.s0241) -> listOf(DxfPolyline(listOf(Point2(x, y), Point2(x + w, y), Point2(x + w, y + t), Point2(x + t, y + t), Point2(x + t, y + h), Point2(x, y + h)), true, layer))
            getString(R.string.s0243) -> listOf(DxfPolyline(listOf(Point2(x, y), Point2(x + w, y), Point2(x + w, y + t), Point2(x + t, y + t), Point2(x + t, y + h - t), Point2(x + w, y + h - t), Point2(x + w, y + h), Point2(x, y + h)), true, layer))
            getString(R.string.s0245) -> listOf(
                rectPolyline(x, y, x + w, y + t, layer),
                rectPolyline(x, y + h - t, x + w, y + h, layer),
                rectPolyline(x + w / 2 - t / 2, y + t, x + w / 2 + t / 2, y + h - t, layer)
            )
            getString(R.string.s0247) -> buildList {
                add(DxfCircle(Point2(x + w / 2, y + h / 2), kotlin.math.min(w, h) / 2, layer))
                add(DxfCircle(Point2(x + w / 2, y + h / 2), kotlin.math.min(w, h) / 5, layer))
                val boltRadius = kotlin.math.min(w, h) * 0.36
                repeat(8) { idx ->
                    val a = Math.toRadians(idx * 45.0)
                    add(DxfCircle(Point2(x + w / 2 + boltRadius * cos(a), y + h / 2 + boltRadius * sin(a)), t / 2, layer))
                }
            }
            getString(R.string.s0248) -> listOf(
                rectPolyline(x, y, x + w, y + h, layer),
                DxfPolyline(capsulePoints(x + w * 0.25, y + h / 2, x + w * 0.75, y + h / 2, t), true, layer)
            )
            else -> listOf(rectPolyline(x, y, x + w, y + h, layer))
        }
    }

    private fun capsulePoints(x1: Double, y1: Double, x2: Double, y2: Double, radius: Double): List<Point2> {
        val points = mutableListOf<Point2>()
        val steps = 12
        for (i in 0..steps) {
            val a = Math.PI / 2 - i * Math.PI / steps
            points += Point2(x2 + radius * cos(a), y2 + radius * sin(a))
        }
        for (i in 0..steps) {
            val a = -Math.PI / 2 - i * Math.PI / steps
            points += Point2(x1 + radius * cos(a), y1 + radius * sin(a))
        }
        return points
    }

    private fun showCreateTextDialog(document: DxfDocument) {
        val form = editForm("X" to "0", "Y" to "0", getString(R.string.s0293) to "TEXT", getString(R.string.s0269) to "30", getString(R.string.s0214) to "CAD_TEXT")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0238))
            .setView(form)
            .setPositiveButton(getString(R.string.s0294)) { _, _ ->
                val x = form.valueAt("X").toDoubleOrNull() ?: return@setPositiveButton
                val y = form.valueAt("Y").toDoubleOrNull() ?: return@setPositiveButton
                val text = form.valueAt(getString(R.string.s0293)).ifBlank { "TEXT" }
                val height = form.valueAt(getString(R.string.s0269)).toDoubleOrNull() ?: 30.0
                val layer = form.valueAt(getString(R.string.s0214)).ifBlank { "CAD_TEXT" }
                applyDocumentMutation(document.copy(entities = document.entities + DxfText(Point2(x, y), text, height, layer)), getString(R.string.s0238))
                showCadEditPanel()
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .show()
    }

    private fun showEditSelectedEntityDialog() {
        val document = currentPreviewDocument ?: return
        val selection = currentSelection ?: run {
            binding.statusText.text = getString(R.string.s0295)
            return
        }
        val entity = document.entities.getOrNull(selection.entityIndex) ?: return
        val fields = when (entity) {
            is DxfLine -> arrayOf(getString(R.string.s0275) to entity.start.x.toString(), getString(R.string.s0276) to entity.start.y.toString(), getString(R.string.s0277) to entity.end.x.toString(), getString(R.string.s0278) to entity.end.y.toString(), getString(R.string.s0214) to (entity.layer ?: "0"))
            is DxfCircle -> arrayOf(getString(R.string.s0280) to entity.center.x.toString(), getString(R.string.s0281) to entity.center.y.toString(), getString(R.string.s0282) to entity.radius.toString(), getString(R.string.s0214) to (entity.layer ?: "0"))
            is DxfArc -> arrayOf(getString(R.string.s0280) to entity.center.x.toString(), getString(R.string.s0281) to entity.center.y.toString(), getString(R.string.s0282) to entity.radius.toString(), getString(R.string.s0283) to entity.startAngle.toString(), getString(R.string.s0284) to entity.endAngle.toString(), getString(R.string.s0214) to (entity.layer ?: "0"))
            is DxfText -> arrayOf("X" to entity.position.x.toString(), "Y" to entity.position.y.toString(), getString(R.string.s0293) to entity.text, getString(R.string.s0269) to entity.height.toString(), getString(R.string.s0214) to (entity.layer ?: "0"))
            is DxfPolyline -> arrayOf(getString(R.string.s0285) to entity.points.joinToString(";") { "${it.x},${it.y}" }, getString(R.string.s0286) to if (entity.closed) "1" else "0", getString(R.string.s0214) to (entity.layer ?: "0"))
        }
        val form = editForm(*fields)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0250))
            .setView(form)
            .setPositiveButton(getString(R.string.s0135)) { _, _ ->
                val updatedEntity: DxfEntity = when (entity) {
                    is DxfLine -> entity.copy(
                        start = Point2(form.valueAt(getString(R.string.s0275)).toDoubleOrNull() ?: entity.start.x, form.valueAt(getString(R.string.s0276)).toDoubleOrNull() ?: entity.start.y),
                        end = Point2(form.valueAt(getString(R.string.s0277)).toDoubleOrNull() ?: entity.end.x, form.valueAt(getString(R.string.s0278)).toDoubleOrNull() ?: entity.end.y),
                        layer = form.valueAt(getString(R.string.s0214)).ifBlank { entity.layer ?: "0" }
                    )
                    is DxfCircle -> entity.copy(
                        center = Point2(form.valueAt(getString(R.string.s0280)).toDoubleOrNull() ?: entity.center.x, form.valueAt(getString(R.string.s0281)).toDoubleOrNull() ?: entity.center.y),
                        radius = form.valueAt(getString(R.string.s0282)).toDoubleOrNull() ?: entity.radius,
                        layer = form.valueAt(getString(R.string.s0214)).ifBlank { entity.layer ?: "0" }
                    )
                    is DxfArc -> entity.copy(
                        center = Point2(form.valueAt(getString(R.string.s0280)).toDoubleOrNull() ?: entity.center.x, form.valueAt(getString(R.string.s0281)).toDoubleOrNull() ?: entity.center.y),
                        radius = form.valueAt(getString(R.string.s0282)).toDoubleOrNull() ?: entity.radius,
                        startAngle = form.valueAt(getString(R.string.s0283)).toDoubleOrNull() ?: entity.startAngle,
                        endAngle = form.valueAt(getString(R.string.s0284)).toDoubleOrNull() ?: entity.endAngle,
                        layer = form.valueAt(getString(R.string.s0214)).ifBlank { entity.layer ?: "0" }
                    )
                    is DxfText -> entity.copy(
                        position = Point2(form.valueAt("X").toDoubleOrNull() ?: entity.position.x, form.valueAt("Y").toDoubleOrNull() ?: entity.position.y),
                        text = form.valueAt(getString(R.string.s0293)).ifBlank { entity.text },
                        height = form.valueAt(getString(R.string.s0269)).toDoubleOrNull() ?: entity.height,
                        layer = form.valueAt(getString(R.string.s0214)).ifBlank { entity.layer ?: "0" }
                    )
                    is DxfPolyline -> {
                        val pts = parsePointList(form.valueAt(getString(R.string.s0285))).ifEmpty { entity.points }
                        entity.copy(points = pts, closed = form.valueAt(getString(R.string.s0286)) == "1", layer = form.valueAt(getString(R.string.s0214)).ifBlank { entity.layer ?: "0" })
                    }
                }
                val updated = document.entities.toMutableList()
                updated[selection.entityIndex] = updatedEntity
                currentSelection = null
                binding.dxfCanvasView.clearSelection(notify = false)
                applyDocumentMutation(document.copy(entities = updated), getString(R.string.s0296))
                binding.statusText.text = getString(R.string.s0297)
                showCadEditPanel()
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .show()
    }

    private fun copySelectedEntity() {
        val selection = currentSelection ?: run {
            binding.statusText.text = getString(R.string.s0298)
            return
        }
        val entity = currentPreviewDocument?.entities?.getOrNull(selection.entityIndex) ?: return
        clipboardEntity = entity
        binding.statusText.text = getString(R.string.s0299)
        showCadEditPanel()
    }

    private fun pasteClipboardEntity() {
        val document = currentPreviewDocument ?: return
        val entity = clipboardEntity ?: run {
            binding.statusText.text = getString(R.string.s0300)
            return
        }
        applyDocumentMutation(document.copy(entities = document.entities + offsetEntity(entity, 25.0, 25.0)), getString(R.string.s0301))
        binding.statusText.text = getString(R.string.s0302)
        showCadEditPanel()
    }

    private fun deleteSelectedEntity() {
        val document = currentPreviewDocument ?: return
        val selection = currentSelection ?: run {
            binding.statusText.text = getString(R.string.s0303)
            return
        }
        val updated = document.entities.toMutableList()
        if (selection.entityIndex !in updated.indices) return
        updated.removeAt(selection.entityIndex)
        currentSelection = null
        applyDocumentMutation(document.copy(entities = updated), getString(R.string.s0304))
        binding.dxfCanvasView.clearSelection(notify = false)
        binding.statusText.text = getString(R.string.s0305)
        showCadEditPanel()
    }

    private fun trimSelectedSegment(ratio: Double) {
        val selection = currentSelection ?: run {
            binding.statusText.text = getString(R.string.s0306)
            return
        }
        val dx = selection.end.x - selection.start.x
        val dy = selection.end.y - selection.start.y
        applySelectionEdit(selection, selection.start, Point2(selection.end.x - dx * ratio, selection.end.y - dy * ratio))
        binding.statusText.text = getString(R.string.s0307)
    }

    private fun applyDocumentMutation(updated: DxfDocument, label: String) {
        currentPreviewDocument = updated
        currentParsed = when (val parsed = currentParsed) {
            is ParsedCadFile.Dxf -> ParsedCadFile.Dxf(updated)
            is ParsedCadFile.Nc -> parsed.copy(document = updated)
            else -> parsed
        }
        pushHistorySnapshot(updated, label)
        binding.dxfCanvasView.setDocument(updated)
        binding.dxfCanvasView.setNcBevelEnabled(ncBevelEnabled)
        binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
        binding.dxfCanvasView.setSimulationOperations(buildSimulationOperationsForDocument(updated))
        binding.dxfCanvasView.setSimulationNcProgram((currentParsed as? ParsedCadFile.Nc)?.program)
        binding.dxfCanvasView.setNc3DSource((currentParsed as? ParsedCadFile.Nc)?.program)
    }

    private fun offsetEntity(entity: DxfEntity, dx: Double, dy: Double): DxfEntity {
        fun move(point: Point2) = Point2(point.x + dx, point.y + dy)
        return when (entity) {
            is DxfLine -> entity.copy(start = move(entity.start), end = move(entity.end))
            is DxfPolyline -> entity.copy(points = entity.points.map(::move))
            is DxfCircle -> entity.copy(center = move(entity.center))
            is DxfArc -> entity.copy(center = move(entity.center))
            is DxfText -> entity.copy(position = move(entity.position))
        }
    }

    private fun editForm(vararg fields: Pair<String, String>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 8.dp, 18.dp, 0)
            fields.forEach { (label, value) ->
                addView(pointInputRow(label, value.toDoubleOrNull() ?: 0.0, label).apply {
                    findViewWithTag<EditText>(label).setText(value)
                })
            }
        }
    }

    private fun LinearLayout.valueAt(tag: String): String = findViewWithTag<EditText>(tag)?.text?.toString().orEmpty().trim()

    private fun resetHistory() {
        historyDocuments.clear()
        historyLabels.clear()
        historyIndex = -1
    }

    private fun pushHistorySnapshot(document: DxfDocument, label: String) {
        if (historyIndex in historyDocuments.indices && historyDocuments[historyIndex].entities == document.entities) {
            if (historyLabels.isNotEmpty()) historyLabels[historyIndex] = label
            return
        }
        if (historyIndex < historyDocuments.lastIndex) {
            historyDocuments.subList(historyIndex + 1, historyDocuments.size).clear()
            historyLabels.subList(historyIndex + 1, historyLabels.size).clear()
        }
        historyDocuments += document.copy(entities = document.entities.toList())
        historyLabels += label
        historyIndex = historyDocuments.lastIndex
    }

    private fun undoHistory() {
        if (historyIndex <= 0) {
            binding.statusText.text = getString(R.string.s0308)
            return
        }
        historyIndex--
        restoreHistoryState(getString(R.string.s0309, historyLabels.getOrNull(historyIndex).orEmpty()))
    }

    private fun redoHistory() {
        if (historyIndex >= historyDocuments.lastIndex) {
            binding.statusText.text = getString(R.string.s0310)
            return
        }
        historyIndex++
        restoreHistoryState(getString(R.string.s0311, historyLabels.getOrNull(historyIndex).orEmpty()))
    }

    private fun restoreHistoryState(status: String) {
        val document = historyDocuments.getOrNull(historyIndex) ?: return
        currentPreviewDocument = document
        currentParsed = when (val parsed = currentParsed) {
            is ParsedCadFile.Dxf -> ParsedCadFile.Dxf(document)
            is ParsedCadFile.Nc -> parsed.copy(document = document)
            else -> parsed
        }
        currentSelection = null
        clearSelectionBaseView()
        binding.dxfCanvasView.setDocument(document)
        binding.dxfCanvasView.setNcBevelEnabled(ncBevelEnabled)
        binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
        binding.dxfCanvasView.clearSelection(notify = false)
        binding.dxfCanvasView.setSimulationNcProgram((currentParsed as? ParsedCadFile.Nc)?.program)
        binding.dxfCanvasView.setNc3DSource((currentParsed as? ParsedCadFile.Nc)?.program)
        binding.statusText.text = status
        showInfoPanel()
    }

    private fun historySummary(): String {
        return if (historyDocuments.isEmpty()) "0 / 0" else "${historyIndex + 1} / ${historyDocuments.size}"
    }

    private fun applySelectionBaseView(selection: GeometrySelection) {
        clearSelectionBaseView()
        val isNcBase = currentParsed is ParsedCadFile.Nc && selection.layer.orEmpty().uppercase().startsWith("NC_CUT")
        if (!isNcBase) return
        selectionHiddenLayers += setOf("NC_BEVEL_A_POS", "NC_BEVEL_A_NEG", "NC_BEVEL_B_POS", "NC_BEVEL_B_NEG", "NC_BEVEL_LABEL")
        hiddenLayers.addAll(selectionHiddenLayers)
        binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
    }

    private fun clearSelectionBaseView() {
        if (selectionHiddenLayers.isEmpty()) return
        hiddenLayers.removeAll(selectionHiddenLayers)
        selectionHiddenLayers.clear()
        binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
    }

    private fun historyListView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (historyLabels.isEmpty()) {
                addView(entryView(getString(R.string.s0312), getString(R.string.s0313)))
                return@apply
            }
            historyLabels.forEachIndexed { index, label ->
                addView(
                    entryView(
                        if (index == historyIndex) getString(R.string.s0314, index + 1) else getString(R.string.s0315, index + 1),
                        label
                    )
                )
            }
        }
    }

    private fun showGenSimulationPanel(document: GenDocument) {
        showSidePanel(null, getString(R.string.s0316))
        binding.sideContent.removeAllViews()
        binding.sideContent.addView(simulationControlsCard("GEN"))
        binding.sideContent.addView(infoLine(getString(R.string.s0209), document.operations.count { it.type == GenOperationType.IDLE }.toString()))
        binding.sideContent.addView(infoLine(getString(R.string.s0210), document.operations.count { it.type == GenOperationType.MARKING }.toString()))
        binding.sideContent.addView(infoLine(getString(R.string.s0211), document.operations.count { it.type == GenOperationType.BURNING }.toString()))
        binding.sideContent.addView(
            infoLine(
                getString(R.string.s0317),
                document.operations.count {
                    it.type == GenOperationType.BURNING && it.bevel?.bevel?.uppercase() !in setOf("", "NONE")
                }.toString()
            )
        )
    }

    private fun simulationControlsCard(labelPrefix: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_panel)
            setPadding(18.dp, 16.dp, 18.dp, 16.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dp
            }
            addView(TextView(context).apply {
                text = getString(R.string.s0318)
                textSize = 16f
                setTextColor(getColor(R.color.panelTextPrimary))
            })
            addView(TextView(context).apply {
                text = getString(R.string.s0319)
                textSize = 12f
                setTextColor(getColor(R.color.panelTextSecondary))
                setPadding(0, 10.dp, 0, 6.dp)
            })
            val speedLabel = TextView(context).apply {
                text = simulationSpeedText
                textSize = 14f
                setTextColor(getColor(R.color.panelTextPrimary))
                gravity = Gravity.END
            }
            simulationSpeedValueView = speedLabel
            addView(speedLabel)
            val seekBar = SeekBar(context).apply {
                max = 240
                progress = speedToSeekProgress(binding.dxfCanvasView.simulationSpeedMultiplier())
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser || suppressSpeedSeekCallback) return
                        binding.dxfCanvasView.setSimulationSpeedMultiplier(seekProgressToSpeed(progress))
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            simulationSpeedSeekBar = seekBar
            addView(seekBar)
            addView(simulationButtonRow(labelPrefix))
            val stateRow = simulationInfoRow(getString(R.string.s0099), simulationStateText)
            val progressRow = simulationInfoRow(getString(R.string.s0320), simulationProgressText)
            simulationStateValueView = stateRow.second
            simulationProgressValueView = progressRow.second
            addView(stateRow.first)
            addView(progressRow.first)
            refreshSimulationViews()
        }
    }

    private fun simulationButtonRow(labelPrefix: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12.dp, 0, 6.dp)
            addView(simulationToggleButton(labelPrefix))
            addView(simulationMiniButton(getString(R.string.s0321), {
                binding.dxfCanvasView.stopSimulation()
                binding.statusText.text = getString(R.string.s0322, labelPrefix)
                refreshSimulationViews()
            }, false))
        }
    }

    private fun simulationToggleButton(labelPrefix: String): TextView {
        return simulationMiniButton(
            label = when {
                binding.dxfCanvasView.isSimulationRunning() -> getString(R.string.s0323)
                binding.dxfCanvasView.isSimulationPaused() -> getString(R.string.s0324)
                else -> getString(R.string.s0325)
            },
            action = {
            if (binding.dxfCanvasView.isSimulationRunning()) {
                binding.dxfCanvasView.pauseSimulation()
                binding.statusText.text = getString(R.string.s0326, labelPrefix)
            } else {
                binding.dxfCanvasView.startSimulation()
                binding.statusText.text = getString(R.string.s0327, labelPrefix)
            }
            refreshSimulationViews()
        }).also {
            simulationToggleButtonView = it
        }
    }

    private fun simulationMiniButton(label: String, action: () -> Unit, withEndMargin: Boolean = true): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(0, 12.dp, 0, 12.dp)
            setTextColor(getColor(R.color.panelTextPrimary))
            background = getDrawable(R.drawable.bg_chip)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (withEndMargin) 8.dp else 0
            }
            setOnClickListener { action() }
        }
    }

    private fun simulationInfoRow(label: String, value: String): Pair<LinearLayout, TextView> {
        val valueView = TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(getColor(R.color.panelTextPrimary))
            gravity = Gravity.END
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp, 0, 0)
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(getColor(R.color.panelTextSecondary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(valueView)
        }
        return row to valueView
    }

    private fun refreshSimulationViews() {
        simulationStateText = when {
            binding.dxfCanvasView.isSimulationRunning() -> getString(R.string.s0328)
            binding.dxfCanvasView.isSimulationPaused() -> getString(R.string.s0329)
            else -> getString(R.string.s0037)
        }
        simulationStateValueView?.text = simulationStateText
        simulationProgressValueView?.text = simulationProgressText
        simulationSpeedValueView?.text = simulationSpeedText
        simulationToggleButtonView?.text = when {
            binding.dxfCanvasView.isSimulationRunning() -> getString(R.string.s0323)
            binding.dxfCanvasView.isSimulationPaused() -> getString(R.string.s0324)
            else -> getString(R.string.s0325)
        }
        val targetProgress = speedToSeekProgress(binding.dxfCanvasView.simulationSpeedMultiplier())
        if (simulationSpeedSeekBar?.progress != targetProgress) {
            suppressSpeedSeekCallback = true
            simulationSpeedSeekBar?.progress = targetProgress
            suppressSpeedSeekCallback = false
        }
    }

    private fun speedToSeekProgress(speed: Float): Int = ((speed - 0.1f) * 100f).roundToInt().coerceIn(0, 240)

    private fun seekProgressToSpeed(progress: Int): Float = (0.1f + progress / 100f).coerceIn(0.1f, 2.5f)

    private fun showInfoPanel() {
        showSidePanel(null, getString(R.string.panel_info))
        binding.sideContent.removeAllViews()
        when (val parsed = currentParsed) {
            is ParsedCadFile.Dxf -> {
                binding.sideContent.addView(infoLine(getString(R.string.s0213), "DXF"))
                binding.sideContent.addView(infoLine(getString(R.string.s0263), parsed.document.entities.size.toString()))
                binding.sideContent.addView(infoLine(getString(R.string.s0205), parsed.document.layers.size.toString()))
                binding.sideContent.addView(infoLine(getString(R.string.s0330), "W ${"%.2f".format(parsed.document.bounds.width)} / H ${"%.2f".format(parsed.document.bounds.height)}"))
                binding.sideContent.addView(infoLine(getString(R.string.s0331), parsed.document.units ?: getString(R.string.s0332)))
                parsed.document.metadata["pdf_scale_label"]?.let {
                    binding.sideContent.addView(infoLine(getString(R.string.s0333), it))
                }
                parsed.document.metadata["pdf_applied_scale"]?.let {
                    binding.sideContent.addView(infoLine(getString(R.string.s0334), "${"%.4f".format(it.toDoubleOrNull() ?: 1.0)}x"))
                }
                parsed.document.metadata["sheet_thickness"]?.let {
                    binding.sideContent.addView(infoLine(getString(R.string.s0335), "${"%.2f".format(it.toDoubleOrNull() ?: 0.0)} mm"))
                }
                parsed.document.metadata["nest_strategy"]?.let {
                    binding.sideContent.addView(infoLine(getString(R.string.s0336), it))
                }
                binding.sideContent.addView(
                    infoLine(
                        getString(R.string.s0337),
                        "X ${"%.2f".format(parsed.document.bounds.minX)} ~ ${"%.2f".format(parsed.document.bounds.maxX)}\nY ${"%.2f".format(parsed.document.bounds.minY)} ~ ${"%.2f".format(parsed.document.bounds.maxY)}"
                    )
                )
                currentUri?.let { binding.sideContent.addView(infoLine("URI", it.toString())) }
            }

            is ParsedCadFile.Gen -> {
                binding.sideContent.addView(infoLine(getString(R.string.s0213), "GEN"))
                binding.sideContent.addView(infoLine(getString(R.string.s0341), parsed.document.sections.size.toString()))
                binding.sideContent.addView(infoLine(getString(R.string.s0342), parsed.document.operations.size.toString()))
                binding.sideContent.addView(infoLine(getString(R.string.s0343), parsed.document.partSummaries.size.toString()))
                parsed.document.generalData["RAW_LENGTH"]?.let { rawLength ->
                    binding.sideContent.addView(infoLine(getString(R.string.s0344), "$rawLength x ${parsed.document.generalData["RAW_WIDTH"].orEmpty()}"))
                }
            }

            is ParsedCadFile.Nc -> {
                binding.sideContent.addView(infoLine(getString(R.string.s0213), "NC"))
                binding.sideContent.addView(infoLine(getString(R.string.s0345), parsed.program.moves.size.toString()))
                parsed.program.header["Material"]?.let { binding.sideContent.addView(infoLine(getString(R.string.s0116), it)) }
                parsed.program.header["Thickness"]?.let { binding.sideContent.addView(infoLine(getString(R.string.s0346), it)) }
                val bevelMoves = parsed.program.moves.filter { (it.bevelA ?: 0.0) != 0.0 || (it.bevelB ?: 0.0) != 0.0 }
                if (bevelMoves.isNotEmpty()) {
                    val aValues = bevelMoves.mapNotNull { it.bevelA }.distinct().sorted()
                    val bValues = bevelMoves.mapNotNull { it.bevelB }.distinct().sorted()
                    binding.sideContent.addView(infoLine(getString(R.string.s0347), ncBevelSummary(parsed.program)))
                    binding.sideContent.addView(infoLine(getString(R.string.s0317), bevelMoves.size.toString()))
                    binding.sideContent.addView(infoLine(getString(R.string.s0348), aValues.joinToString(", ") { "%.1f".format(it) }))
                    binding.sideContent.addView(infoLine(getString(R.string.s0349), bValues.joinToString(", ") { "%.1f".format(it) }))
                }
                parsed.program.header["SheetX"]?.let { sx ->
                    binding.sideContent.addView(infoLine(getString(R.string.s0344), "$sx x ${parsed.program.header["SheetY"].orEmpty()}"))
                }
            }

            is ParsedCadFile.Dwg -> {
                binding.sideContent.addView(infoLine(getString(R.string.s0213), "DWG"))
                binding.sideContent.addView(infoLine(getString(R.string.s0350), parsed.preview.readableVersion))
                binding.sideContent.addView(infoLine(getString(R.string.s0351), parsed.preview.versionCode))
            }

            null -> binding.sideContent.addView(infoLine(getString(R.string.s0099), getString(R.string.s0352)))
        }
    }



    private fun tabRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 10.dp
            layoutParams = params
            addView(tabButton(getString(R.string.s0353), FilePanelTab.IMPORT))
            addView(tabButton(getString(R.string.s0255), FilePanelTab.RECENT))
            addView(tabButton(getString(R.string.s0354), FilePanelTab.FAVORITES))
            addView(tabButton(getString(R.string.s0355), FilePanelTab.ALL_FILES))
            addView(tabButton(getString(R.string.s0148), FilePanelTab.SEARCH))
        }
    }

    private fun tabButton(label: String, tab: FilePanelTab): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
            setTextColor(getColor(if (currentPanelTab == tab) R.color.textOnDark else R.color.textSecondary))
            background = getDrawable(if (currentPanelTab == tab) R.drawable.bg_chip else R.drawable.bg_panel_compact)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 5.dp
            }
            setOnClickListener {
                currentPanelTab = tab
                renderFilePanel()
            }
        }
    }

    private fun findParentDocument(root: DocumentFile, targetUri: Uri): DocumentFile? {
        if (root.uri == targetUri) return null
        root.listFiles().forEach { child ->
            if (child.uri == targetUri) return root
            if (child.isDirectory) {
                val found = findParentDocument(child, targetUri)
                if (found != null) return found
            }
        }
        return null
    }

    private fun isSupportedCadFile(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.getDefault()).orEmpty()
        return ext in setOf("dxf", "dwg", "gen", "nc", "tap", "cnc", "mpf", "pdf")
    }

    private fun actionRow(label: String, block: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = 42.dp
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(12.dp, 8.dp, 10.dp, 8.dp)
            setTextColor(getColor(R.color.textPrimary))
            background = getDrawable(R.drawable.bg_panel_compact)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 5.dp
            layoutParams = params
            setOnClickListener { block() }
        }
    }

    /** Компактная кнопка-плитка с иконкой (для сетки 2 колонки). */
    private fun gridButton(icon: String, label: String, block: () -> Unit): TextView {
        return TextView(this).apply {
            text = icon + "\n" + label
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(6.dp, 10.dp, 6.dp, 10.dp)
            setTextColor(getColor(R.color.textPrimary))
            background = getDrawable(R.drawable.bg_panel_compact)
            setOnClickListener { block() }
        }
    }
    /** Ряд из двух компактных плиток. */
    private fun gridRow(a: TextView, b: TextView? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 5.dp }
            addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 5.dp })
            b?.let { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
                ?: addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        }
    }
    /** Строка-действие с поясняющей иконкой слева. */
    private fun actionRowIconized(icon: String, label: String, hint: String? = null, block: () -> Unit): TextView {
        return TextView(this).apply {
            text = if (hint == null) icon + "   " + label else icon + "   " + label + "  -  " + hint
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = 46.dp
            setPadding(12.dp, 9.dp, 10.dp, 9.dp)
            setTextColor(getColor(R.color.textPrimary))
            background = getDrawable(R.drawable.bg_panel_compact)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 5.dp }
            setOnClickListener { block() }
        }
    }
    private fun primaryActionRow(label: String, block: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = 44.dp
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(12.dp, 9.dp, 12.dp, 9.dp)
            setTextColor(getColor(R.color.textOnDark))
            background = getDrawable(R.drawable.bg_chip)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp }
            setOnClickListener { block() }
        }
    }

    private fun filePreviewRow(
        title: String,
        subtitle: String,
        badge: String,
        onOpen: () -> Unit,
        onDetail: () -> Unit,
        onDelete: (() -> Unit)?
    ): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(11.dp, 8.dp, 11.dp, 8.dp)
            background = getDrawable(R.drawable.bg_panel_compact)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp }
            setOnClickListener { onDetail() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = title
            textSize = 13.5f
            setTextColor(getColor(R.color.textPrimary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = badge
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.textOnDark))
            background = getDrawable(R.drawable.bg_chip)
            setPadding(8.dp, 4.dp, 8.dp, 4.dp)
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = subtitle.ifBlank { getString(R.string.s0356) }
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.textSecondary))
            setPadding(0, 4.dp, 0, 0)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 5.dp, 0, 0)
        }
        actions.addView(compactAction(getString(R.string.s0357), onOpen))
        actions.addView(compactAction(getString(R.string.s0358), onDetail))
        onDelete?.let { delete -> actions.addView(compactAction(getString(R.string.s0359), delete)) }
        root.addView(actions)
        return root
    }

    private fun compactAction(label: String, block: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.textSecondary))
            background = getDrawable(R.drawable.bg_panel_compact)
            setPadding(10.dp, 7.dp, 10.dp, 7.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 6.dp }
            setOnClickListener { block() }
        }
    }

    private fun groupSummaryRow(type: String, count: Int, block: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            background = getDrawable(R.drawable.bg_panel_compact)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp }
            addView(TextView(context).apply {
                text = type
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.textPrimary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = getString(R.string.s0360, count)
                textSize = 12f
                setTextColor(getColor(R.color.textSecondary))
            })
            setOnClickListener { block() }
        }
    }

    private fun showStoredFileDetail(ref: StoredFileRef, allowHistoryActions: Boolean) {
        showSidePanel(binding.actionFile, getString(R.string.s0361))
        binding.sideContent.removeAllViews()
        binding.sideContent.addView(actionRow(getString(R.string.s0362)) { renderFilePanel() })
        binding.sideContent.addView(sectionTitle(ref.name))
        binding.sideContent.addView(infoLine(getString(R.string.s0213), fileTypeBadge(ref.name)))
        binding.sideContent.addView(infoLine("URI", ref.uri))
        binding.sideContent.addView(infoLine(getString(R.string.s0363), formatHistoryTime(ref.openedAt)))
        binding.sideContent.addView(infoLine(getString(R.string.s0099), if (ref.pinned) getString(R.string.s0364) else getString(R.string.s0365)))
        binding.sideContent.addView(infoLine(getString(R.string.s0366), if (ref.managed) getString(R.string.s0367) else getString(R.string.s0368)))
        ref.localPath?.let { binding.sideContent.addView(infoLine(getString(R.string.s0369), it)) }
        ref.sourceUri?.let { binding.sideContent.addView(infoLine(getString(R.string.s0370), it)) }
        val document = DocumentFile.fromSingleUri(this, Uri.parse(ref.uri))
        document?.let {
            binding.sideContent.addView(infoLine(getString(R.string.s0371), it.name ?: ref.name))
            binding.sideContent.addView(infoLine(getString(R.string.s0372), formatFileSize(it.length())))
            val modified = it.lastModified().takeIf { value -> value > 0L }?.let { value -> formatHistoryTime(value) } ?: getString(R.string.s0006)
            binding.sideContent.addView(infoLine(getString(R.string.s0373), modified))
        }
        binding.sideContent.addView(primaryActionRow(getString(R.string.s0374)) { openUri(Uri.parse(ref.uri), persistPermission = false) })
        if (allowHistoryActions) {
            binding.sideContent.addView(actionRow(if (ref.pinned) getString(R.string.s0375) else getString(R.string.s0376)) {
                libraryStore.togglePinRecentFile(ref.uri)
                binding.statusText.text = getString(R.string.s0377)
                renderFilePanel()
            })
            binding.sideContent.addView(actionRow(getString(R.string.s0378)) {
                libraryStore.removeRecentFile(ref.uri)
                binding.statusText.text = getString(R.string.s0102)
                renderFilePanel()
            })
            if (ref.managed) {
                binding.sideContent.addView(actionRow(getString(R.string.s0379)) {
                    val deleted = libraryStore.deleteManagedFile(ref)
                    binding.statusText.text = if (deleted) getString(R.string.s0110) else getString(R.string.s0111)
                    renderFilePanel()
                })
            }
        }
    }

    private fun showCurrentFileDetail() {
        val uri = currentUri ?: return
        val fileName = currentFileName ?: queryFileName(uri) ?: getString(R.string.s0093)
        showSidePanel(binding.actionFile, getString(R.string.s0380))
        binding.sideContent.removeAllViews()
        binding.sideContent.addView(actionRow(getString(R.string.s0381)) {
            currentPanelTab = FilePanelTab.IMPORT
            renderFilePanel()
        })
        binding.sideContent.addView(sectionTitle(fileName))
        binding.sideContent.addView(infoLine(getString(R.string.s0213), fileTypeBadge(fileName)))
        binding.sideContent.addView(infoLine(getString(R.string.s0382), currentFileSummary()))
        binding.sideContent.addView(infoLine("URI", uri.toString()))
        val document = DocumentFile.fromSingleUri(this, uri)
        document?.let {
            binding.sideContent.addView(infoLine(getString(R.string.s0372), formatFileSize(it.length())))
            val modified = it.lastModified().takeIf { value -> value > 0L }?.let { value -> formatHistoryTime(value) } ?: getString(R.string.s0006)
            binding.sideContent.addView(infoLine(getString(R.string.s0373), modified))
        }
        binding.sideContent.addView(actionRow(if (libraryStore.isFavorite(uri)) getString(R.string.s0383) else getString(R.string.s0084)) {
            val added = libraryStore.toggleFavorite(uri, fileName)
            binding.statusText.text = if (added) getString(R.string.s0096, fileName) else getString(R.string.s0097, fileName)
            showCurrentFileDetail()
        })
        binding.sideContent.addView(primaryActionRow(getString(R.string.s0384)) { openDocument.launch(arrayOf("*/*")) })
    }

    private fun fileTypeBadge(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase(Locale.getDefault())) {
            "dxf" -> "DXF"
            "dwg" -> "DWG"
            "pdf" -> "PDF"
            "gen" -> "GEN"
            "nc", "tap", "cnc", "mpf" -> "NC"
            else -> getString(R.string.s0083)
        }
    }

    private fun currentFileSummary(): String {
        return when (val parsed = currentParsed) {
            is ParsedCadFile.Dxf -> {
                val document = parsed.document
                val type = getString(R.string.s0386)
                getString(R.string.s0387, type, document.entities.size, document.layers.size, "%.2f".format(document.bounds.width), "%.2f".format(document.bounds.height))
            }
            is ParsedCadFile.Gen -> getString(R.string.s0388, parsed.document.sections.size, parsed.document.operations.size)
            is ParsedCadFile.Nc -> getString(R.string.s0389, parsed.program.moves.size, ncBevelSummary(parsed.program))
            is ParsedCadFile.Dwg -> getString(R.string.s0390, parsed.preview.readableVersion, parsed.preview.versionCode)
            null -> getString(R.string.s0391)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return getString(R.string.s0006)
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) "%.2f MB".format(mb) else "%.1f KB".format(kb)
    }

    private fun swipeableHistoryRow(
        name: String,
        subtitle: String,
        removable: Boolean,
        onOpen: () -> Unit,
        onPin: () -> Unit,
        onDelete: () -> Unit
    ): FrameLayout {
        val actionWidth = 176.dp.toFloat()
        val root = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dp
            }
        }
        val actionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(actionWidth.toInt(), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
        }
        val pinView = TextView(this).apply {
            text = getString(R.string.s0392)
            gravity = Gravity.CENTER
            setTextColor(getColor(android.R.color.white))
            setBackgroundColor(0xFF4F8EF7.toInt())
            layoutParams = LinearLayout.LayoutParams(88.dp, LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { onPin() }
            visibility = if (removable) View.VISIBLE else View.GONE
        }
        val deleteView = TextView(this).apply {
            text = getString(R.string.s0359)
            gravity = Gravity.CENTER
            setTextColor(getColor(android.R.color.white))
            setBackgroundColor(0xFFD9534F.toInt())
            layoutParams = LinearLayout.LayoutParams(88.dp, LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { onDelete() }
            visibility = if (removable) View.VISIBLE else View.GONE
        }
        val contentView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(18.dp, 16.dp, 18.dp, 16.dp)
            background = getDrawable(R.drawable.bg_panel)
            addView(TextView(context).apply {
                text = name
                textSize = 16f
                setTextColor(getColor(R.color.panelTextPrimary))
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 12f
                setTextColor(getColor(R.color.panelTextSecondary))
            })
            addView(TextView(context).apply {
                text = getString(R.string.s0357)
                textSize = 12f
                setTextColor(getColor(R.color.panelTextPrimary))
                gravity = Gravity.END
                setPadding(0, 10.dp, 0, 0)
                background = getDrawable(R.drawable.bg_chip)
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                setOnClickListener { onOpen() }
            })
            translationX = 0f
            var startX = 0f
            var startY = 0f
            var startTranslation = 0f
            var dragged = false
            var swiping = false
            val touchSlop = 40.dp.toFloat()
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (removable && expandedHistoryContent != null && expandedHistoryContent != this) {
                            expandedHistoryContent?.translationX = 0f
                            expandedHistoryContent = null
                        }
                        startX = event.rawX
                        startY = event.rawY
                        startTranslation = translationX
                        dragged = false
                        swiping = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!removable) {
                            return@setOnTouchListener false
                        }
                        val deltaX = event.rawX - startX
                        val deltaY = event.rawY - startY
                        if (!swiping) {
                            if (kotlin.math.abs(deltaX) < touchSlop) {
                                return@setOnTouchListener false
                            }
                            if (deltaX >= 0f || kotlin.math.abs(deltaX) <= kotlin.math.abs(deltaY) * 1.4f) {
                                parent.requestDisallowInterceptTouchEvent(false)
                                return@setOnTouchListener false
                            }
                            parent.requestDisallowInterceptTouchEvent(true)
                            swiping = true
                        }
                        if (!dragged && kotlin.math.abs(deltaX) < touchSlop * 1.2f) {
                            return@setOnTouchListener true
                        }
                        val target = (startTranslation + deltaX).coerceIn(-actionWidth, 0f)
                        translationX = target
                        dragged = true
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!removable) {
                            val totalDx = event.rawX - startX
                            val totalDy = event.rawY - startY
                            if (
                                event.actionMasked == MotionEvent.ACTION_UP &&
                                kotlin.math.abs(totalDx) < touchSlop &&
                                kotlin.math.abs(totalDy) < touchSlop
                            ) {
                                onOpen()
                            }
                            return@setOnTouchListener true
                        }
                        if (dragged) {
                            translationX = if (translationX <= -actionWidth / 2f) -actionWidth else 0f
                            expandedHistoryContent = if (translationX != 0f) this else null
                            parent.requestDisallowInterceptTouchEvent(false)
                            true
                        } else {
                            if (translationX != 0f) {
                                translationX = 0f
                                expandedHistoryContent = null
                                true
                            } else {
                                parent.requestDisallowInterceptTouchEvent(false)
                                true
                            }
                        }
                    }
                    else -> false
                }
            }
        }
        actionContainer.addView(pinView)
        actionContainer.addView(deleteView)
        root.addView(actionContainer)
        root.addView(contentView)
        return root
    }

    private fun formatHistoryTime(timestamp: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private fun infoLine(key: String, value: String): LinearLayout = entryView(key, value)

    private fun sectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(getColor(R.color.panelTextSecondary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(2.dp, 14.dp, 0, 7.dp)
        }
    }

    /** Информационная карточка: несколько строк в ОДНОЙ рамке (не похоже на кнопки). */
    private fun infoCard(vararg rows: Pair<String, String>): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            background = getDrawable(R.drawable.bg_info_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp }
        }
        rows.forEachIndexed { index, (key, value) ->
            if (index > 0) {
                card.addView(TextView(this).apply {
                    text = ""
                    setBackgroundColor(getColor(R.color.panelTextSecondary))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1.dp
                    ).apply { topMargin = 8.dp; bottomMargin = 8.dp }
                    alpha = 0.15f
                })
            }
            card.addView(TextView(this).apply {
                text = key
                textSize = 11f
                setTextColor(getColor(R.color.panelTextSecondary))
            })
            card.addView(TextView(this).apply {
                text = value
                textSize = 14f
                setTextColor(getColor(R.color.panelTextPrimary))
                setPadding(0, 3.dp, 0, 0)
            })
        }
        return card
    }
    private fun entryView(key: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp, 10.dp, 4.dp, 10.dp)
            // Информативный блок — НЕ кнопка: плоский фон без обводки
            background = getDrawable(R.drawable.bg_info_card)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 8.dp
            layoutParams = params
        }
        val label = TextView(this).apply {
            text = key
            textSize = 11f
            setTextColor(getColor(R.color.panelTextSecondary))
        }
        val content = TextView(this).apply {
            text = value.ifBlank { " " }
            textSize = 14f
            setTextColor(getColor(R.color.panelTextPrimary))
            gravity = Gravity.START
            setPadding(0, 3.dp, 0, 0)
        }
        row.addView(label)
        row.addView(content)
        return row
    }

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment?.substringAfterLast('/')
        }
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }

    private fun formatGenMeta(document: GenDocument): String {
        val parts = document.partSummaries.size
        val rawLength = document.generalData["RAW_LENGTH"]?.toDoubleOrNull()
        val rawWidth = document.generalData["RAW_WIDTH"]?.toDoubleOrNull()
        return if (rawLength != null && rawWidth != null) {
            getString(R.string.s0393, parts, rawLength.toInt(), rawWidth.toInt(), document.operations.size)
        } else {
            getString(R.string.s0394, document.sections.size, parts)
        }
    }

    private fun formatNcMeta(program: NcProgram): String {
        val sx = program.header["SheetX"]
        val sy = program.header["SheetY"]
        val bevelCount = program.moves.count { (it.bevelA ?: 0.0) != 0.0 || (it.bevelB ?: 0.0) != 0.0 }
        val summary = ncBevelSummary(program)
        return if (!sx.isNullOrBlank() && !sy.isNullOrBlank()) {
            if (bevelCount > 0) {
                getString(R.string.s0395, program.moves.size, summary, sx, sy)
            } else {
                getString(R.string.s0396, program.moves.size, sx, sy)
            }
        } else {
            getString(R.string.s0397, program.moves.size)
        }
    }

    private fun ncBevelSummary(program: NcProgram): String {
        val aValues = program.moves.mapNotNull { it.bevelA }.filter { it != 0.0 }.distinct().sorted()
        val bValues = program.moves.mapNotNull { it.bevelB }.filter { it != 0.0 }.distinct().sorted()
        val symmetricA = aValues.size == 2 && kotlin.math.abs(aValues.first() + aValues.last()) < 0.01
        val symmetricB = bValues.size == 2 && kotlin.math.abs(bValues.first() + bValues.last()) < 0.01
        return when {
            symmetricA && symmetricB -> getString(R.string.s0398, "%.0f".format(kotlin.math.abs(aValues.last())))
            symmetricA -> getString(R.string.s0399, "%.0f".format(kotlin.math.abs(aValues.last())))
            symmetricB -> getString(R.string.s0400, "%.0f".format(kotlin.math.abs(bValues.last())))
            (aValues.isNotEmpty() || bValues.isNotEmpty()) -> getString(R.string.s0401, aValues.plus(bValues).joinToString("/") { "%.0f".format(it) })
            else -> getString(R.string.s0402)
        }
    }

    private fun buildGenPreview(document: GenDocument): DxfDocument {
        val rawLength = document.generalData["RAW_LENGTH"]?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 6000.0
        val rawWidth = document.generalData["RAW_WIDTH"]?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 2400.0
        val entities = mutableListOf<DxfEntity>()
        entities += rectPolyline(0.0, 0.0, rawLength, rawWidth, "GEN_RAW")
        entities += DxfText(
            position = Point2(rawLength * 0.03, rawWidth * 0.95),
            text = document.generalData["NEST_NAME"] ?: document.fileName,
            height = maxOf(rawWidth * 0.035, 42.0),
            layer = "GEN_RAW"
        )
        document.operations.forEach { operation ->
            operation.contours.forEach { contour ->
                entities += contourToEntities(contour, layerForOperation(operation.type))
                if (operation.type == GenOperationType.BURNING && contour.bevel?.bevel?.uppercase() !in setOf("", "NONE")) {
                    entities += contourToEntities(contour, "GEN_BEVEL")
                }
            }
        }

        return DxfDocument(
            fileName = document.fileName,
            entities = entities,
            units = getString(R.string.s0003)
        )
    }

    private fun contourToEntities(contour: GenContour, layer: String): List<DxfEntity> {
        val entities = mutableListOf<DxfEntity>()
        var cursor = contour.start
        contour.segments.forEach { segment ->
            if (segment.amp != null && (segment.amp.x != 0.0 || segment.amp.y != 0.0)) {
                entities += DxfPolyline(
                    points = approximateAmpCurvePoints(
                        start = cursor,
                        end = segment.end,
                        amp = segment.amp
                    ),
                    closed = false,
                    layer = layer
                )
            } else if (segment.radius != 0.0 && segment.origin != null && segment.sweepRadians != 0.0) {
                entities += DxfPolyline(
                    points = approximateArcPoints(
                        start = cursor,
                        end = segment.end,
                        origin = segment.origin,
                        sweepRadians = segment.sweepRadians,
                        radius = segment.radius
                    ),
                    closed = false,
                    layer = layer
                )
            } else {
                entities += DxfLine(cursor, segment.end, layer)
            }
            cursor = segment.end
        }
        return entities
    }

    private fun layerForOperation(type: GenOperationType): String {
        return when (type) {
            GenOperationType.IDLE -> "GEN_IDLE"
            GenOperationType.MARKING -> "GEN_MARK"
            GenOperationType.BURNING -> "GEN_BURN"
        }
    }

    private fun approximateAmpCurvePoints(start: Point2, end: Point2, amp: Point2): List<Point2> {
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

    private fun approximateArcPoints(
        start: Point2,
        end: Point2,
        origin: Point2,
        sweepRadians: Double,
        radius: Double
    ): List<Point2> {
        val startAngle = atan2(start.y - origin.y, start.x - origin.x)
        val steps = maxOf(8, (kotlin.math.abs(sweepRadians) / (Math.PI / 18.0)).toInt())
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

    private fun buildSimulationOperationsForDocument(document: DxfDocument): List<GenOperation> {
        return document.entities.mapIndexedNotNull { index, entity ->
            if (isVisualOnlyLayer(entity.layer)) return@mapIndexedNotNull null
            when (entity) {
                is DxfLine -> GenOperation(
                    type = simulationTypeForLayer(entity.layer),
                    label = entity.layer,
                    contours = listOf(
                        GenContour(
                            start = entity.start,
                            segments = listOf(GenContourSegment(end = entity.end))
                        )
                    )
                )
                is DxfPolyline -> {
                    if (entity.points.size < 2) return@mapIndexedNotNull null
                    val segments = mutableListOf<GenContourSegment>()
                    for (i in 1 until entity.points.size) {
                        segments += GenContourSegment(end = entity.points[i])
                    }
                    if (entity.closed) {
                        segments += GenContourSegment(end = entity.points.first())
                    }
                    GenOperation(
                        type = simulationTypeForLayer(entity.layer),
                        label = entity.layer ?: "polyline-$index",
                        contours = listOf(GenContour(start = entity.points.first(), segments = segments))
                    )
                }
                is DxfArc -> {
                    val start = Point2(
                        entity.center.x + entity.radius * kotlin.math.cos(Math.toRadians(entity.startAngle)),
                        entity.center.y + entity.radius * kotlin.math.sin(Math.toRadians(entity.startAngle))
                    )
                    val end = Point2(
                        entity.center.x + entity.radius * kotlin.math.cos(Math.toRadians(entity.endAngle)),
                        entity.center.y + entity.radius * kotlin.math.sin(Math.toRadians(entity.endAngle))
                    )
                    val sweepDegrees = if (entity.endAngle >= entity.startAngle) {
                        entity.endAngle - entity.startAngle
                    } else {
                        entity.endAngle + 360.0 - entity.startAngle
                    }
                    GenOperation(
                        type = simulationTypeForLayer(entity.layer),
                        label = entity.layer,
                        contours = listOf(
                            GenContour(
                                start = start,
                                segments = listOf(
                                    GenContourSegment(
                                        end = end,
                                        radius = entity.radius,
                                        sweepRadians = Math.toRadians(sweepDegrees),
                                        origin = entity.center
                                    )
                                )
                            )
                        )
                    )
                }
                is DxfCircle -> {
                    val start = Point2(entity.center.x + entity.radius, entity.center.y)
                    GenOperation(
                        type = simulationTypeForLayer(entity.layer),
                        label = entity.layer,
                        contours = listOf(
                            GenContour(
                                start = start,
                                segments = listOf(
                                    GenContourSegment(
                                        end = start,
                                        radius = entity.radius,
                                        sweepRadians = Math.PI * 2.0,
                                        origin = entity.center
                                    )
                                )
                            )
                        )
                    )
                }
                is DxfText -> null
            }
        }
    }

    private fun simulationTypeForLayer(layer: String?): GenOperationType {
        val value = layer.orEmpty().uppercase()
        return when {
            value.contains("RAPID") || value.contains("IDLE") -> GenOperationType.IDLE
            value.contains("MARK") -> GenOperationType.MARKING
            else -> GenOperationType.BURNING
        }
    }

    private fun rectPolyline(minX: Double, minY: Double, maxX: Double, maxY: Double, layer: String): DxfPolyline {
        return DxfPolyline(
            points = listOf(
                Point2(minX, minY),
                Point2(maxX, minY),
                Point2(maxX, maxY),
                Point2(minX, maxY)
            ),
            closed = true,
            layer = layer
        )
    }

    private fun exportRouteEntities(document: DxfDocument): List<DxfEntity> {
        val ordered = mutableListOf<DxfEntity>()
        buildSimulationOperationsForDocument(document).forEach { operation ->
            operation.contours.forEach { contour ->
                var cursor = contour.start
                contour.segments.forEach { segment ->
                    val layer = operation.label
                    ordered += if (segment.origin != null && segment.radius > 0.0 && segment.sweepRadians != 0.0) {
                        val startAngle = Math.toDegrees(atan2(cursor.y - segment.origin.y, cursor.x - segment.origin.x))
                        DxfArc(
                            center = segment.origin,
                            radius = segment.radius,
                            startAngle = startAngle,
                            endAngle = normalizeExportEndAngle(startAngle, segment.sweepRadians),
                            layer = layer
                        )
                    } else {
                        DxfLine(cursor, segment.end, layer)
                    }
                    cursor = segment.end
                }
            }
        }
        return ordered
    }

    private fun normalizeExportEndAngle(startAngle: Double, sweepRadians: Double): Double {
        val sweepDegrees = Math.toDegrees(sweepRadians)
        return startAngle + sweepDegrees
    }

    private fun serializeAsDxf(document: DxfDocument): String {
        val orderedEntities = exportRouteEntities(document)
        val body = buildString {
            appendLine("0")
            appendLine("SECTION")
            appendLine("2")
            appendLine("HEADER")
            appendLine("9")
            appendLine("\$INSUNITS")
            appendLine("70")
            appendLine("4")
            appendLine("0")
            appendLine("ENDSEC")
            appendLine("0")
            appendLine("SECTION")
            appendLine("2")
            appendLine("ENTITIES")
            orderedEntities.forEach { entity ->
                when (entity) {
                    is DxfLine -> {
                        appendLine("0"); appendLine("LINE")
                        appendLine("8"); appendLine(entity.layer ?: "0")
                        appendLine("10"); appendLine(entity.start.x.toString())
                        appendLine("20"); appendLine(entity.start.y.toString())
                        appendLine("11"); appendLine(entity.end.x.toString())
                        appendLine("21"); appendLine(entity.end.y.toString())
                    }
                    is DxfPolyline -> {
                        appendLine("0"); appendLine("LWPOLYLINE")
                        appendLine("8"); appendLine(entity.layer ?: "0")
                        appendLine("90"); appendLine(entity.points.size.toString())
                        appendLine("70"); appendLine(if (entity.closed) "1" else "0")
                        entity.points.forEach {
                            appendLine("10"); appendLine(it.x.toString())
                            appendLine("20"); appendLine(it.y.toString())
                        }
                    }
                    is DxfCircle -> {
                        appendLine("0"); appendLine("CIRCLE")
                        appendLine("8"); appendLine(entity.layer ?: "0")
                        appendLine("10"); appendLine(entity.center.x.toString())
                        appendLine("20"); appendLine(entity.center.y.toString())
                        appendLine("40"); appendLine(entity.radius.toString())
                    }
                    is DxfArc -> {
                        appendLine("0"); appendLine("ARC")
                        appendLine("8"); appendLine(entity.layer ?: "0")
                        appendLine("10"); appendLine(entity.center.x.toString())
                        appendLine("20"); appendLine(entity.center.y.toString())
                        appendLine("40"); appendLine(entity.radius.toString())
                        appendLine("50"); appendLine(entity.startAngle.toString())
                        appendLine("51"); appendLine(entity.endAngle.toString())
                    }
                    is DxfText -> {
                        appendLine("0"); appendLine("TEXT")
                        appendLine("8"); appendLine(entity.layer ?: "0")
                        appendLine("10"); appendLine(entity.position.x.toString())
                        appendLine("20"); appendLine(entity.position.y.toString())
                        appendLine("40"); appendLine(entity.height.toString())
                        appendLine("1"); appendLine(entity.text)
                    }
                }
            }
            appendLine("0")
            appendLine("ENDSEC")
            appendLine("0")
            appendLine("EOF")
        }
        return body
    }

    private fun serializeAsNc(
        document: DxfDocument,
        thickness: Double? = null,
        material: String? = null,
        sheetWidth: Double? = null,
        sheetHeight: Double? = null,
        programName: String? = null
    ): String {
        val orderedEntities = exportRouteEntities(document)
        return buildString {
            appendLine(";Generated by SikkatuCAD")
            thickness?.let { appendLine(";THICKNESS=${it.formatNc()}") }
            material?.takeIf { it.isNotBlank() }?.let { appendLine(";MATERIAL=$it") }
            if (sheetWidth != null && sheetHeight != null) {
                appendLine(";SHEET=${sheetWidth.formatNc()}x${sheetHeight.formatNc()}")
            }
            programName?.takeIf { it.isNotBlank() }?.let { appendLine(";PROGRAM=$it") }
            appendLine("G90")
            appendLine("G21")
            var index = 10
            orderedEntities.forEach { entity ->
                if (isVisualOnlyLayer(entity.layer)) return@forEach
                when (entity) {
                    is DxfLine -> {
                        appendLine("N$index G00 X${entity.start.x.formatNc()} Y${entity.start.y.formatNc()}")
                        index += 10
                        appendLine("N$index G01 X${entity.end.x.formatNc()} Y${entity.end.y.formatNc()}")
                        index += 10
                    }
                    is DxfPolyline -> {
                        val points = if (entity.closed) entity.points + entity.points.first() else entity.points
                        if (points.size >= 2) {
                            appendLine("N$index G00 X${points.first().x.formatNc()} Y${points.first().y.formatNc()}")
                            index += 10
                            points.drop(1).forEach { point ->
                                appendLine("N$index G01 X${point.x.formatNc()} Y${point.y.formatNc()}")
                                index += 10
                            }
                        }
                    }
                    is DxfArc -> {
                        val start = Point2(
                            entity.center.x + entity.radius * kotlin.math.cos(Math.toRadians(entity.startAngle)),
                            entity.center.y + entity.radius * kotlin.math.sin(Math.toRadians(entity.startAngle))
                        )
                        val end = Point2(
                            entity.center.x + entity.radius * kotlin.math.cos(Math.toRadians(entity.endAngle)),
                            entity.center.y + entity.radius * kotlin.math.sin(Math.toRadians(entity.endAngle))
                        )
                        appendLine("N$index G00 X${start.x.formatNc()} Y${start.y.formatNc()}")
                        index += 10
                        val i = entity.center.x - start.x
                        val j = entity.center.y - start.y
                        val clockwise = entity.endAngle < entity.startAngle
                        appendLine("N$index ${if (clockwise) "G02" else "G03"} X${end.x.formatNc()} Y${end.y.formatNc()} I${i.formatNc()} J${j.formatNc()}")
                        index += 10
                    }
                    else -> Unit
                }
            }
            appendLine("M30")
        }
    }

    private fun setNcConsole(program: NcProgram?) {
        if (program == null || (!binding.dxfCanvasView.isSimulationRunning() && currentParsed !is ParsedCadFile.Nc)) {
            ncConsoleLines = emptyList()
            binding.ncConsole.visibility = View.GONE
            return
        }
        ncConsoleLines = buildNcConsoleLines(program)
        binding.ncConsole.visibility = View.VISIBLE
        refreshNcConsole(-1)
    }

    private fun buildNcConsoleLines(program: NcProgram): List<String> {
        val lines = mutableListOf<String>()
        lines += ";THICKNESS=${program.header["Thickness"] ?: "-"}"
        lines += ";MATERIAL=${program.header["Material"] ?: "-"}"
        program.moves.forEachIndexed { idx, move ->
            val num = (idx + 1) * 10
            val g = if (move.rapid) "G00" else if (move.centerOffset != null) "G02/03" else "G01"
            val bevel = listOfNotNull(
                move.bevelA?.let { "A${"%.0f".format(it)}" },
                move.bevelB?.let { "B${"%.0f".format(it)}" }
            ).joinToString(" ")
            lines += "N${"%04d".format(num)} $g X${move.end.x.formatNc()} Y${move.end.y.formatNc()}" +
                (move.centerOffset?.let { " I${it.x.formatNc()} J${it.y.formatNc()}" } ?: "") +
                if (bevel.isNotBlank()) "  $bevel" else ""
        }
        return lines
    }

    private fun refreshNcConsole(cursorIndex: Int) {
        if (ncConsoleLines.isEmpty()) {
            binding.ncConsole.visibility = View.GONE
            return
        }
        binding.ncConsole.visibility = View.VISIBLE
        val text = buildString {
            ncConsoleLines.forEachIndexed { index, line ->
                if (index == cursorIndex) append("> ") else append("  ")
                append(line)
                if (index == cursorIndex) append("  ◀")
                append('\n')
            }
        }
        binding.ncConsoleText.text = text
        binding.ncConsoleText.post {
            val layout = binding.ncConsoleText.layout ?: return@post
            val targetLine = cursorIndex.coerceAtLeast(0).coerceAtMost(layout.lineCount - 1)
            val y = layout.getLineTop(targetLine)
            binding.ncConsoleScroll.smoothScrollTo(0, y)
        }
    }

    private fun renderNestPartList(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (nestingParts.isEmpty()) {
                addView(entryView(getString(R.string.s0099), getString(R.string.s0403)))
                return@apply
            }
            nestingParts.forEachIndexed { index, part ->
                addView(compactRow("${part.name} x${part.quantity}") {
                    showEditPartDialog(index, part)
                })
            }
        }
    }

    private fun showEditPartDialog(idx: Int, part: NestPart) {
        val qtyInput = EditText(this).apply {
            setText(part.quantity.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
        }
        val rotInput = EditText(this).apply {
            setText(part.rotationDeg.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
        }
        val mirrorBox = CheckBox(this).apply {
            setTextColor(getColor(R.color.panelTextPrimary))
            text = getString(R.string.s0404)
            isChecked = part.mirrorX
            setTextColor(getColor(R.color.panelTextPrimary))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0405))
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 12, 24, 0)
                addView(TextView(context).apply { text = getString(R.string.s0406) })
                addView(qtyInput)
                addView(TextView(context).apply { text = getString(R.string.s0407) })
                addView(rotInput)
                addView(mirrorBox)
            })
            .setPositiveButton(getString(R.string.s0408)) { _, _ ->
                part.quantity = qtyInput.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: part.quantity
                part.rotationDeg = rotInput.text.toString().toDoubleOrNull() ?: part.rotationDeg
                part.mirrorX = mirrorBox.isChecked
                showNestingPanel()
            }
            .setNegativeButton(getString(R.string.s0359)) { _, _ ->
                nestingParts.removeAt(idx)
                showNestingPanel()
            }
            .setNeutralButton(getString(R.string.s0134), null)
            .show()
    }

    private fun compactRow(text: String, action: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6.dp, 0, 6.dp)
            addView(TextView(context).apply {
                this.text = text
                textSize = 12f
                setTextColor(getColor(R.color.panelTextPrimary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                this.text = getString(R.string.s0409)
                textSize = 12f
                setTextColor(getColor(R.color.entityAccent))
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                background = getDrawable(R.drawable.bg_chip)
                setOnClickListener { action() }
            })
        }
    }

    private fun addLibraryShapeRect() {
        val widthInput = EditText(this).apply {
            hint = getString(R.string.s0268)
            setText("200")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val heightInput = EditText(this).apply {
            hint = getString(R.string.s0269)
            setText("100")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0410))
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 12, 24, 0)
                addView(widthInput)
                addView(heightInput)
            })
            .setPositiveButton(getString(R.string.s0294)) { _, _ ->
                val w = widthInput.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val h = heightInput.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val rect = rectPolyline(0.0, 0.0, w, h, "LIB_RECT")
                nestingParts += NestPart(getString(R.string.s0411, nestingParts.size + 1), listOf(rect))
                showNestingPanel()
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .show()
    }

    private fun addLibraryShapeCircle() {
        val radiusInput = EditText(this).apply {
            hint = getString(R.string.s0282)
            setText("50")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0412))
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 12, 24, 0)
                addView(radiusInput)
            })
            .setPositiveButton(getString(R.string.s0294)) { _, _ ->
                val r = radiusInput.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val circle = DxfCircle(Point2(0.0, 0.0), r, "LIB_CIRCLE")
                nestingParts += NestPart(getString(R.string.s0413, nestingParts.size + 1), listOf(circle))
                showNestingPanel()
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .show()
    }

    private fun updateSheetOutline(existing: List<DxfEntity>, width: Double, height: Double): List<DxfEntity> {
        val others = existing.filterNot { it.layer.orEmpty() == "NC_SHEET" }
        val sheet = rectPolyline(0.0, 0.0, width, height, "NC_SHEET")
        return others + sheet
    }

    private fun applyAutoNest(
        sheetW: Double,
        sheetH: Double,
        gap: Double,
        sheetThickness: Double,
        strategy: NestingStrategy = FirstFitDecreasingStrategy(),
        resultView: LinearLayout? = null
    ) {
        if (nestingParts.isEmpty()) {
            binding.statusText.text = getString(R.string.s0414)
            return
        }

        // Execute typesetting algorithm
        val result = strategy.nest(nestingParts, sheetW, sheetH, gap)

        // Update workspace
        val sheetOutline = rectPolyline(0.0, 0.0, sheetW, sheetH, "NC_SHEET")
        nestingWorkspace = DxfDocument(
            "NESTING.dxf",
            listOf(sheetOutline) + result.entities,
            metadata = mapOf(
                "sheet_width" to sheetW.toString(),
                "sheet_height" to sheetH.toString(),
                "sheet_thickness" to sheetThickness.toString(),
                "nest_strategy" to strategy.name
            )
        )

        // Update results display
        val suggestions = NestingOptimizer.analyzeAndSuggest(result, sheetW, sheetH, gap)
        resultView?.removeAllViews()
        resultView?.apply {
            addView(sectionTitle(getString(R.string.s0415)))
            addView(entryView(getString(R.string.s0416), "${"%.1f".format(result.utilization)}%"))
            addView(entryView(getString(R.string.s0417), "${result.placedCount}/${nestingParts.sumOf { it.quantity }}"))
            addView(entryView(getString(R.string.s0418), "${"%.0f".format(result.wastedArea)}"))

            if (result.failedParts.isNotEmpty()) {
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.s0419)
                    textSize = 11f
                    setTextColor(getColor(R.color.panelTextSecondary))
                    setPadding(0, 8.dp, 0, 4.dp)
                })
                result.failedParts.forEach { part ->
                    addView(TextView(this@MainActivity).apply {
                        text = "• $part"
                        textSize = 10f
                        setTextColor(getColor(R.color.entityAccent))
                        setPadding(8.dp, 2.dp, 0, 2.dp)
                    })
                }
            }
            if (suggestions.isNotEmpty()) {
                addView(sectionTitle(getString(R.string.s0420)))
                suggestions.forEach { suggestion ->
                    addView(entryView(getString(R.string.s0421), suggestion))
                }
            }
        }

        binding.statusText.text = getString(R.string.s0422, result.placedCount, nestingParts.sumOf { it.quantity }, "%.1f".format(result.utilization))
    }

    private fun compareNestingStrategies(sheetW: Double, sheetH: Double, gap: Double) {
        if (nestingParts.isEmpty()) {
            binding.statusText.text = getString(R.string.s0414)
            return
        }

        val strategies = listOf(
            FirstFitDecreasingStrategy(),
            BestFitStrategy(),
            StripeStrategy()
        )

        val results = strategies.map { strategy ->
            strategy to strategy.nest(nestingParts, sheetW, sheetH, gap)
        }

        // Find the optimal solution
        val bestResult = results.maxByOrNull { it.second.utilization }

        val message = buildString {
            appendLine(getString(R.string.s0423))
            appendLine()
            results.forEach { (strategy, result) ->
                val isBest = result == bestResult?.second
                val marker = if (isBest) "✓ " else "  "
                appendLine("$marker${strategy.name}")
                appendLine(getString(R.string.s0424, "%.1f".format(result.utilization)))
                appendLine(getString(R.string.s0425, result.placedCount, nestingParts.sumOf { it.quantity }))
                appendLine(getString(R.string.s0426, "%.0f".format(result.wastedArea)))
                appendLine()
            }
            appendLine(getString(R.string.s0427, bestResult?.first?.name))
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0428))
            .setMessage(message)
            .setPositiveButton(getString(R.string.s0429)) { _, _ ->
                bestResult?.let { (strategy, result) ->
                    val sheetOutline = rectPolyline(0.0, 0.0, sheetW, sheetH, "NC_SHEET")
                    nestingWorkspace = DxfDocument(
                        "NESTING.dxf",
                        listOf(sheetOutline) + result.entities,
                        metadata = mapOf(
                            "sheet_width" to sheetW.toString(),
                            "sheet_height" to sheetH.toString(),
                            "sheet_thickness" to nestingSheetThickness.toString(),
                            "nest_strategy" to strategy.name
                        )
                    )
                    applyNestingWorkspaceToView(getString(R.string.s0430, strategy.name))
                }
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .show()
    }

    private fun autoSplitNesting(
        sheetW: Double,
        sheetH: Double,
        gap: Double,
        sheetThickness: Double,
        strategy: NestingStrategy
    ) {
        if (nestingParts.isEmpty()) {
            binding.statusText.text = getString(R.string.s0414)
            return
        }

        val results = NestingOptimizer.autoSplitToMultipleSheets(
            nestingParts, sheetW, sheetH, gap, strategy
        )

        if (results.isEmpty()) {
            binding.statusText.text = getString(R.string.s0431)
            return
        }

        // Merge layout results for all panels
        val allEntities = mutableListOf<DxfEntity>()
        var currentY = 0.0

        results.forEachIndexed { index, result ->
            val sheetOutline = rectPolyline(0.0, currentY, sheetW, currentY + sheetH, "NC_SHEET_${index + 1}")
            allEntities.add(sheetOutline)

            // Translate all entities of this sheet
            result.entities.forEach { entity ->
                val translated = when (entity) {
                    is DxfLine -> entity.copy(
                        start = entity.start.copy(y = entity.start.y + currentY),
                        end = entity.end.copy(y = entity.end.y + currentY)
                    )
                    is DxfPolyline -> entity.copy(
                        points = entity.points.map { it.copy(y = it.y + currentY) }
                    )
                    is DxfCircle -> entity.copy(
                        center = entity.center.copy(y = entity.center.y + currentY)
                    )
                    is DxfArc -> entity.copy(
                        center = entity.center.copy(y = entity.center.y + currentY)
                    )
                    is DxfText -> entity.copy(
                        position = entity.position.copy(y = entity.position.y + currentY)
                    )
                }
                allEntities.add(translated)
            }

            currentY += sheetH + gap
        }

        nestingWorkspace = DxfDocument(
            "NESTING_MULTI.dxf",
            allEntities,
            metadata = mapOf(
                "sheet_width" to sheetW.toString(),
                "sheet_height" to sheetH.toString(),
                "sheet_thickness" to sheetThickness.toString(),
                "nest_strategy" to strategy.name
            )
        )

        val totalRequested = nestingParts.sumOf { it.quantity }
        val message = buildString {
            appendLine(getString(R.string.s0432))
            appendLine(getString(R.string.s0433, results.size))
            appendLine()
            results.forEachIndexed { index, result ->
                appendLine(getString(R.string.s0434, index + 1))
                appendLine(getString(R.string.s0435, result.placedCount))
                appendLine(getString(R.string.s0436, "%.1f".format(result.utilization)))
                appendLine()
            }
            appendLine(getString(R.string.s0437, results.sumOf { it.placedCount }, totalRequested))
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s0438))
            .setMessage(message)
            .setPositiveButton(getString(R.string.s0135)) { _, _ ->
                applyNestingWorkspaceToView(getString(R.string.s0439, results.size))
            }
            .setNegativeButton(getString(R.string.s0134), null)
            .show()
    }

    private fun applyNestingWorkspaceToView(status: String = getString(R.string.s0440)) {
        val nested = nestingWorkspace ?: run {
            binding.statusText.text = getString(R.string.s0441)
            return
        }
        pushHistorySnapshot(nested, getString(R.string.s0442))
        currentPreviewDocument = nested
        currentParsed = ParsedCadFile.Dxf(nested)
        binding.emptyPanel.visibility = View.GONE
        binding.infoScrollView.visibility = View.GONE
        binding.dxfCanvasView.visibility = View.VISIBLE
        binding.dxfCanvasView.setViewMode(DxfCanvasView.ViewMode.TWO_D)
        binding.dxfCanvasView.setDocument(nested)
        binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
        binding.dxfCanvasView.setNcBevelEnabled(ncBevelEnabled)
        binding.dxfCanvasView.setSimulationOperations(buildSimulationOperationsForDocument(nested))
        binding.dxfCanvasView.setSimulationNcProgram(null)
        binding.dxfCanvasView.setNc3DSource(null)
        setNcConsole(null)
        binding.fileTypeChip.text = "DXF"
        binding.metaChip.text = getString(R.string.s0443, nested.layers.size, nested.entities.size)
        binding.statusText.text = status
        updateViewModeChips()
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
            val rx = x * kotlin.math.cos(angleRad) - y * kotlin.math.sin(angleRad) + offsetX
            val ry = x * kotlin.math.sin(angleRad) + y * kotlin.math.cos(angleRad) + offsetY
            return Point2(rx, ry)
        }
        return when (entity) {
            is DxfLine -> entity.copy(start = tx(entity.start), end = tx(entity.end))
            is DxfPolyline -> entity.copy(points = entity.points.map(::tx))
            is DxfCircle -> entity.copy(center = tx(entity.center))
            is DxfArc -> entity.copy(center = tx(entity.center), startAngle = entity.startAngle + angleDeg, endAngle = entity.endAngle + angleDeg)
            is DxfText -> entity.copy(position = tx(entity.position))
        }
    }

    private fun serializeAsGen(document: DxfDocument): String {
        val bounds = document.bounds
        val orderedEntities = exportRouteEntities(document)
        return buildString {
            appendLine("GENERAL_DATA")
            appendLine("TYPE_OF_GENERIC_FILE=PLATE_PART")
            appendLine("TYPE_OF_MANUFACT=2AXIS")
            appendLine("NEST_NAME=${document.fileName.substringBeforeLast('.')}")
            appendLine("RAW_LENGTH=${"%.2f".format(bounds.width)}")
            appendLine("RAW_WIDTH=${"%.2f".format(bounds.height)}")
            appendLine("RAW_THICKNESS=0.00")
            appendLine("NO_OF_PARTS=1")
            appendLine("END_OF_GENERAL_DATA")
            appendLine("PART_DATA")
            appendLine("NAME=${document.fileName.substringBeforeLast('.')}")
            appendLine("POSNO=1")
            appendLine("EXTENSION_U=${"%.2f".format(bounds.width)}")
            appendLine("EXTENSION_V=${"%.2f".format(bounds.height)}")
            appendLine("PART_COG_U=${"%.2f".format((bounds.minX + bounds.maxX) / 2.0)}")
            appendLine("PART_COG_V=${"%.2f".format((bounds.minY + bounds.maxY) / 2.0)}")
            appendLine("END_OF_PART_DATA")
            orderedEntities.forEach { entity ->
                if (isVisualOnlyLayer(entity.layer)) return@forEach
                when (entity) {
                    is DxfLine -> {
                        appendLine("BURNING_DATA")
                        appendLine("SHAPE=OUTER_CONTOUR")
                        appendLine("START_OF_CONTOUR")
                        appendLine("NO_OF_SEG=1")
                        appendLine("START_U=${"%.5f".format(entity.start.x)}")
                        appendLine("START_V=${"%.5f".format(entity.start.y)}")
                        appendLine("AMP_U=0.00000")
                        appendLine("AMP_V=0.00000")
                        appendLine("AMP=0.00000")
                        appendLine("RADIUS=0.00000")
                        appendLine("SWEEP=0.00000")
                        appendLine("ORIGIN_U=0.00000")
                        appendLine("ORIGIN_V=0.00000")
                        appendLine("U=${"%.5f".format(entity.end.x)}")
                        appendLine("V=${"%.5f".format(entity.end.y)}")
                        appendLine("END_OF_CONTOUR")
                        appendLine("END_OF_BURNING_DATA")
                    }
                    is DxfPolyline -> {
                        val pts = if (entity.closed && entity.points.isNotEmpty()) entity.points + entity.points.first() else entity.points
                        for (index in 0 until pts.lastIndex) {
                            val start = pts[index]
                            val end = pts[index + 1]
                            appendLine("BURNING_DATA")
                            appendLine("SHAPE=OUTER_CONTOUR")
                            appendLine("START_OF_CONTOUR")
                            appendLine("NO_OF_SEG=1")
                            appendLine("START_U=${"%.5f".format(start.x)}")
                            appendLine("START_V=${"%.5f".format(start.y)}")
                            appendLine("AMP_U=0.00000")
                            appendLine("AMP_V=0.00000")
                            appendLine("AMP=0.00000")
                            appendLine("RADIUS=0.00000")
                            appendLine("SWEEP=0.00000")
                            appendLine("ORIGIN_U=0.00000")
                            appendLine("ORIGIN_V=0.00000")
                            appendLine("U=${"%.5f".format(end.x)}")
                            appendLine("V=${"%.5f".format(end.y)}")
                            appendLine("END_OF_CONTOUR")
                            appendLine("END_OF_BURNING_DATA")
                        }
                    }
                    is DxfArc -> {
                        val start = Point2(
                            entity.center.x + entity.radius * kotlin.math.cos(Math.toRadians(entity.startAngle)),
                            entity.center.y + entity.radius * kotlin.math.sin(Math.toRadians(entity.startAngle))
                        )
                        val end = Point2(
                            entity.center.x + entity.radius * kotlin.math.cos(Math.toRadians(entity.endAngle)),
                            entity.center.y + entity.radius * kotlin.math.sin(Math.toRadians(entity.endAngle))
                        )
                        val sweep = Math.toRadians(entity.endAngle - entity.startAngle)
                        appendLine("BURNING_DATA")
                        appendLine("SHAPE=OUTER_CONTOUR")
                        appendLine("START_OF_CONTOUR")
                        appendLine("NO_OF_SEG=1")
                        appendLine("START_U=${"%.5f".format(start.x)}")
                        appendLine("START_V=${"%.5f".format(start.y)}")
                        appendLine("AMP_U=0.00000")
                        appendLine("AMP_V=0.00000")
                        appendLine("AMP=0.00000")
                        appendLine("RADIUS=${"%.5f".format(entity.radius)}")
                        appendLine("SWEEP=${"%.5f".format(sweep)}")
                        appendLine("ORIGIN_U=${"%.5f".format(entity.center.x)}")
                        appendLine("ORIGIN_V=${"%.5f".format(entity.center.y)}")
                        appendLine("U=${"%.5f".format(end.x)}")
                        appendLine("V=${"%.5f".format(end.y)}")
                        appendLine("END_OF_CONTOUR")
                        appendLine("END_OF_BURNING_DATA")
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun isVisualOnlyLayer(layer: String?): Boolean {
        val value = layer.orEmpty().uppercase()
        return value.startsWith("NC_BEVEL") ||
            value.startsWith("NC_BEVEL_LABEL") ||
            value.startsWith("NC_SHEET")
    }

    private fun Double.formatNc(): String = "%.3f".format(this)

    private fun segmentLength(start: Point2, end: Point2): Double {
        val dx = end.x - start.x
        val dy = end.y - start.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun adjustSegmentToLength(start: Point2, end: Point2, targetLength: Double): Pair<Point2, Point2> {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val currentLength = kotlin.math.sqrt(dx * dx + dy * dy)
        if (currentLength <= 0.0001 || targetLength <= 0.0) {
            return start to end
        }
        val scale = targetLength / currentLength
        return start to Point2(
            start.x + dx * scale,
            start.y + dy * scale
        )
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    override fun onMeasureUpdated(text: String?) {
        if (text != null) {
            binding.metaChip.text = text
        } else {
            val parsed = currentParsed
            binding.metaChip.text = when (parsed) {
                is ParsedCadFile.Dxf -> parsed.document.units?.let { getString(R.string.s0072, parsed.document.layers.size, it) }
                    ?: getString(R.string.s0073, parsed.document.layers.size)
                is ParsedCadFile.Gen -> formatGenMeta(parsed.document)
                is ParsedCadFile.Nc -> formatNcMeta(parsed.program)
                is ParsedCadFile.Dwg -> parsed.preview.readableVersion
                null -> "-"
            }
        }
    }

    override fun onSelectionChanged(selection: GeometrySelection?) {
        currentSelection = selection
        if (selection != null) {
            showSelectionEditor(selection)
            binding.statusText.text = getString(R.string.s0444)
        } else {
            clearSelectionBaseView()
        }
    }

    override fun onSimulationUpdated(progress: Double, total: Double, speedMultiplier: Float) {
        simulationProgressText = if (total > 0.0) "${"%.0f".format((progress / total * 100.0).coerceIn(0.0, 100.0))}%" else "0%"
        simulationSpeedText = "%.2fx".format(speedMultiplier)
        refreshSimulationViews()
    }

    override fun onSimulationStateChanged(running: Boolean, paused: Boolean) {
        simulationStateText = when {
            running -> getString(R.string.s0328)
            paused -> getString(R.string.s0329)
            else -> getString(R.string.s0037)
        }
        refreshSimulationViews()
    }

    override fun onSimulationCompleted() {
        simulationProgressText = "100%"
        simulationProgressValueView?.text = simulationProgressText
        simulationToggleButtonView?.text = getString(R.string.s0325)
        simulationStateText = getString(R.string.s0445)
        binding.statusText.text = getString(R.string.s0446)
        refreshSimulationViews()
    }

    override fun onSimulationCursorChanged(segmentIndex: Int) {
        refreshNcConsole(segmentIndex)
    }

    private fun showNestingPanel() {
        showSidePanel(null, getString(R.string.s0447))
        if (nestingWorkspace == null) {
            nestingWorkspace = DxfDocument("NESTING.dxf", emptyList())
        }
        binding.sideContent.removeAllViews()

        // Board size and thickness
        binding.sideContent.addView(sectionTitle(getString(R.string.s0448)))
        val workspaceMetadata = nestingWorkspace?.metadata.orEmpty()
        val sheetW = EditText(this).apply {
            hint = getString(R.string.s0449)
            setText(workspaceMetadata["sheet_width"]?.toDoubleOrNull()?.let { "%.0f".format(it) } ?: "3000")
            textSize = 12f
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
            background = getDrawable(R.drawable.bg_panel)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val sheetH = EditText(this).apply {
            hint = getString(R.string.s0268)
            setText(workspaceMetadata["sheet_height"]?.toDoubleOrNull()?.let { "%.0f".format(it) } ?: "1500")
            textSize = 12f
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
            background = getDrawable(R.drawable.bg_panel)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val sheetT = EditText(this).apply {
            hint = getString(R.string.s0346)
            setText(workspaceMetadata["sheet_thickness"]?.toDoubleOrNull()?.let { "%.0f".format(it) } ?: "10")
            textSize = 12f
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
            background = getDrawable(R.drawable.bg_panel)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        listOf(sheetW to getString(R.string.s0449), sheetH to getString(R.string.s0268), sheetT to getString(R.string.s0346)).forEach { (view, label) ->
            binding.sideContent.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(getColor(R.color.panelTextSecondary))
            })
            binding.sideContent.addView(view)
        }
        if (nestingParts.isNotEmpty()) {
            val (suggestedW, suggestedH) = NestingOptimizer.suggestOptimalSheetSize(nestingParts, minGap = 10.0)
            binding.sideContent.addView(infoLine(getString(R.string.s0450), "${"%.0f".format(suggestedW)} x ${"%.0f".format(suggestedH)}"))
            binding.sideContent.addView(compactRow(getString(R.string.s0451)) {
                sheetW.setText("%.0f".format(suggestedW))
                sheetH.setText("%.0f".format(suggestedH))
            })
        }
        val refreshSheet: () -> Unit = {
            val wVal = sheetW.text.toString().toDoubleOrNull()
            val hVal = sheetH.text.toString().toDoubleOrNull()
            val tVal = sheetT.text.toString().toDoubleOrNull() ?: nestingSheetThickness
            if (wVal != null && hVal != null) {
                nestingSheetThickness = tVal
                nestingWorkspace = (nestingWorkspace ?: DxfDocument("NESTING.dxf", emptyList()))
                    .copy(
                        entities = updateSheetOutline(nestingWorkspace?.entities.orEmpty(), wVal, hVal),
                        metadata = nestingWorkspace?.metadata.orEmpty() + mapOf(
                            "sheet_width" to wVal.toString(),
                            "sheet_height" to hVal.toString(),
                            "sheet_thickness" to nestingSheetThickness.toString()
                        )
                    )
                binding.statusText.text = getString(R.string.s0452, "%.0f".format(wVal), "%.0f".format(hVal))
            }
        }
        sheetW.addTextChangedListener { refreshSheet.invoke() }
        sheetH.addTextChangedListener { refreshSheet.invoke() }
        sheetT.addTextChangedListener { refreshSheet.invoke() }

        binding.sideContent.addView(sectionTitle(getString(R.string.s0453)))
        binding.sideContent.addView(compactRow(getString(R.string.s0454)) { importNestingDocument.launch(arrayOf("*/*")) })
        binding.sideContent.addView(compactRow(getString(R.string.s0455)) { addLibraryShapeRect() })
        binding.sideContent.addView(compactRow(getString(R.string.s0456)) { addLibraryShapeCircle() })

        binding.sideContent.addView(sectionTitle(getString(R.string.s0457)))
        binding.sideContent.addView(renderNestPartList())

        binding.sideContent.addView(sectionTitle(getString(R.string.s0458)))
        val gapInput = EditText(this).apply {
            hint = getString(R.string.s0459)
            setText("10")
            textSize = 12f
            setTextColor(getColor(R.color.panelTextPrimary))
            setHintTextColor(getColor(R.color.panelTextSecondary))
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
            background = getDrawable(R.drawable.bg_panel)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        binding.sideContent.addView(TextView(this).apply {
            text = getString(R.string.s0459)
            textSize = 11f
            setTextColor(getColor(R.color.panelTextSecondary))
        })
        binding.sideContent.addView(gapInput)

        // Typesetting strategy selection
        binding.sideContent.addView(TextView(this).apply {
            text = getString(R.string.s0336)
            textSize = 11f
            setTextColor(getColor(R.color.panelTextSecondary))
            setPadding(0, 8.dp, 0, 4.dp)
        })
        val strategies = listOf(
            FirstFitDecreasingStrategy(),
            BestFitStrategy(),
            StripeStrategy()
        )
        var selectedStrategy: NestingStrategy = strategies[0]
        val strategyButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        strategies.forEachIndexed { idx, strategy ->
            strategyButtons.addView(TextView(this).apply {
                text = strategy.name.split("(")[0].trim()
                textSize = 10f
                setTextColor(if (idx == 0) getColor(R.color.textOnDark) else getColor(R.color.panelTextSecondary))
                setPadding(8.dp, 6.dp, 8.dp, 6.dp)
                background = getDrawable(R.drawable.bg_chip)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (idx < strategies.size - 1) 4.dp else 0
                }
                setOnClickListener {
                    selectedStrategy = strategy
                    for (i in 0 until strategyButtons.childCount) {
                        val view = strategyButtons.getChildAt(i)
                        if (view is TextView) {
                            view.setTextColor(if (i == idx) getColor(R.color.textOnDark) else getColor(R.color.panelTextSecondary))
                        }
                    }
                }
            })
        }
        binding.sideContent.addView(strategyButtons)

        // Typesetting result display
        val resultView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 8.dp, 0, 0)
        }
        binding.sideContent.addView(resultView)

        binding.sideContent.addView(compactRow(getString(R.string.s0460)) {
            val gap = gapInput.text.toString().toDoubleOrNull() ?: 0.0
            val sheetWVal = sheetW.text.toString().toDoubleOrNull() ?: return@compactRow
            val sheetHVal = sheetH.text.toString().toDoubleOrNull() ?: return@compactRow
            val sheetTVal = sheetT.text.toString().toDoubleOrNull() ?: nestingSheetThickness
            applyAutoNest(sheetWVal, sheetHVal, gap, sheetTVal, selectedStrategy, resultView)
        })
        binding.sideContent.addView(compactRow(getString(R.string.s0461)) {
            val gap = gapInput.text.toString().toDoubleOrNull() ?: 0.0
            val sheetWVal = sheetW.text.toString().toDoubleOrNull() ?: return@compactRow
            val sheetHVal = sheetH.text.toString().toDoubleOrNull() ?: return@compactRow
            compareNestingStrategies(sheetWVal, sheetHVal, gap)
        })
        binding.sideContent.addView(compactRow(getString(R.string.s0462)) {
            val gap = gapInput.text.toString().toDoubleOrNull() ?: 0.0
            val sheetWVal = sheetW.text.toString().toDoubleOrNull() ?: return@compactRow
            val sheetHVal = sheetH.text.toString().toDoubleOrNull() ?: return@compactRow
            val sheetTVal = sheetT.text.toString().toDoubleOrNull() ?: nestingSheetThickness
            autoSplitNesting(sheetWVal, sheetHVal, gap, sheetTVal, selectedStrategy)
        })
        binding.sideContent.addView(compactRow(getString(R.string.s0463)) {
            applyNestingWorkspaceToView()
        })
    }

    private fun nestDocument(
        base: DxfDocument,
        count: Int,
        angleDeg: Double,
        mirrorX: Boolean,
        gap: Double
    ): DxfDocument {
        val entities = mutableListOf<DxfEntity>()
        val baseBounds = base.bounds
        val w = baseBounds.width + gap
        val h = baseBounds.height + gap
        val angleRad = Math.toRadians(angleDeg)
        val cols = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt().coerceAtLeast(1)
        fun transformPoint(p: Point2, offsetIndex: Int): Point2 {
            var x = p.x - baseBounds.minX
            var y = p.y - baseBounds.minY
            if (mirrorX) x = -x
            val rx = x * kotlin.math.cos(angleRad) - y * kotlin.math.sin(angleRad)
            val ry = x * kotlin.math.sin(angleRad) + y * kotlin.math.cos(angleRad)
            val row = offsetIndex / cols
            val col = offsetIndex % cols
            val ox = baseBounds.minX + col * w
            val oy = baseBounds.minY + row * h
            return Point2(rx + ox, ry + oy)
        }
        repeat(count) { idx ->
            base.entities.forEach { entity ->
                when (entity) {
                    is DxfLine -> {
                        val a = transformPoint(entity.start, idx)
                        val b = transformPoint(entity.end, idx)
                        entities += entity.copy(start = a, end = b)
                    }
                    is DxfPolyline -> {
                        val pts = entity.points.map { transformPoint(it, idx) }
                        entities += entity.copy(points = pts)
                    }
                    is DxfCircle -> {
                        val c = transformPoint(entity.center, idx)
                        entities += entity.copy(center = c)
                    }
                    is DxfArc -> {
                        val c = transformPoint(entity.center, idx)
                        entities += entity.copy(center = c)
                    }
                    is DxfText -> {
                        val p = transformPoint(entity.position, idx)
                        entities += entity.copy(position = p)
                    }
                }
            }
        }
        return base.copy(
            fileName = base.fileName,
            entities = entities
        )
    }
}