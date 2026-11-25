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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
    private val historyDocuments = mutableListOf<DxfDocument>()
    private val historyLabels = mutableListOf<String>()
    private var historyIndex = -1
    private val hiddenLayers = linkedSetOf<String>()
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

    companion object {
        private const val PREFS_NAME = "cad_prefs"
        private const val KEY_LANGUAGE = "app_language"
        /** "" = system default, "ru", "en", "zh" */
        fun savedLanguage(context: Context): String {
            return prefs(context).getString(KEY_LANGUAGE, "").orEmpty()
        }
        /** File URI to reopen after language change (recreate). */
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
    /** Interface language picker. */
    private fun showLanguageDialog() {
        val labels = arrayOf("English", "Русский", "中文", "System default")
        val codes = arrayOf("en", "ru", "zh", "")
        val current = savedLanguage(this)
        val checked = codes.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Interface language")
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
        installSplashScreen()
        super.onCreate(savedInstanceState)
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
            updateChips()
            binding.statusText.text = if (enabled) {
                getString(R.string.s0046, binding.dxfCanvasView.autoDimensionSummary().orEmpty())
            } else {
                getString(R.string.s0047)
            }
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
        updateChips()
        // Reopen file after language change (recreate): the drawing must not be lost.
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

    private fun updateChips() {
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
                    updateChips()
                    return
                }
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
                currentPreviewDocument = parsed.document
                pushHistorySnapshot(parsed.document, getString(R.string.s0074))
                binding.dxfCanvasView.setDocument(parsed.document)
            }

            is ParsedCadFile.Dwg -> {
                        val doc = parsed.document
                if (doc != null) {
                    // DWG was converted to DXF via the Rust bridge - render the full drawing.
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
                    currentPreviewDocument = doc
                    pushHistorySnapshot(doc, getString(R.string.s0074))
                    binding.dxfCanvasView.setDocument(doc)
                } else {
                    currentPreviewDocument = null
                    binding.statusText.text = getString(R.string.s0079, parsed.preview.readableVersion)
                    binding.fileTypeChip.text = "DWG"
                    binding.metaChip.text = parsed.preview.readableVersion
                    renderMessageCard(
                        title = getString(R.string.s0080),
                        body = getString(R.string.s0081, parsed.preview.versionCode, parsed.preview.readableVersion, parsed.preview.note)
                    )
                }
            }
        }
        updateChips()
    }

    private fun renderMessageCard(title: String, body: String, resetDrawingState: Boolean = false) {
        if (resetDrawingState) {
            currentParsed = null
            currentPreviewDocument = null
            currentSelection = null
            clearSelectionBaseView()
            resetHistory()
        }
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
        binding.dxfCanvasView.visibility = View.GONE
        binding.infoScrollView.visibility = View.GONE
        binding.emptyPanel.visibility = View.VISIBLE
        binding.sidePanel.visibility = View.GONE
        updateBottomNavState(null)
        binding.fileTypeChip.text = "-"
        binding.metaChip.text = "-"
        updateChips()
        // Button to return to the last drawing
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
            binding.sideContent.addView(actionRow(getString(R.string.ui_translation_panel)) { showTranslationPanel() })
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
                    binding.statusText.text = getString(R.string.ui_dxf_ready)
                }
            }.onFailure { error ->
                runOnUiThread {
                    binding.statusText.text = "DXF export failed: ${error.message ?: "unknown error"}"
                }
            }
        }
    }

    // ==================== Text translation (Rust bridge) ====================

    private var translationSession: Long? = null
    private var translationItems: List<CadBridge.TextItem> = emptyList()
    /** id -> lang -> translated text */
    private val translations = linkedMapOf<String, MutableMap<String, String>>()
    /** Language tags shown as columns; user-chosen, editable. */
    private val translationLangs = linkedSetOf("en", "th")
    private var translationSourceLang: String = "auto"
    private lateinit var translationRowsContainer: LinearLayout

    private val translationImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        worker.execute {
            runCatching {
                val body = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error(getString(R.string.ui_cannot_read))
                CadBridge.parseTranslationJson(String(body, Charsets.UTF_8))
            }.onSuccess { rows ->
                runOnUiThread {
                    var count = 0
                    rows.forEach { (id, byLang) ->
                        val store = translations.getOrPut(id) { linkedMapOf() }
                        byLang.forEach { (lang, value) ->
                            store[lang] = value
                            translationLangs.add(lang)
                            count++
                        }
                    }
                    renderTranslationRows()
                    binding.statusText.text = getString(R.string.ui_translation_loaded, count)
                }
            }.onFailure { error ->
                runOnUiThread {
                    binding.statusText.text = "JSON error: ${error.message ?: "unknown"}"
                }
            }
        }
    }

    /** Copy current file to a temp file and open a bridge session. */
    private fun openTranslationSession(): Long {
        translationSession?.let { return it }
        val uri = currentUri ?: error(getString(R.string.ui_no_file))
        val name = currentFileName ?: "drawing.dwg"
        val ext = name.substringAfterLast('.', "dwg")
        val tmp = File(cacheDir, "cad_translate_src.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: error(getString(R.string.ui_cannot_read))
        val sid = CadBridge.open(tmp.absolutePath).getOrThrow()
        translationSession = sid
        return sid
    }

    private fun extractTranslationTexts() {
        binding.statusText.text = getString(R.string.ui_extracting)
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
                        "Texts: ${items.size} (Cyrillic: $cyr) · format: ${
                            CadBridge.sourceFormat(sid).getOrDefault("?")
                        }"
                }
            }.onFailure { error ->
                runOnUiThread {
                    binding.statusText.text = "Extraction failed: ${error.message ?: "unknown"}"
                }
            }
        }
    }

    private fun renderTranslationRows() {
        if (!::translationRowsContainer.isInitialized) return
        translationRowsContainer.removeAllViews()
        if (translationItems.isEmpty()) {
            translationRowsContainer.addView(
                entryView(getString(R.string.ui_texts), getString(R.string.ui_extract))
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
        val store = translations.getOrPut(item.id) { linkedMapOf() }
        translationLangs.forEach { lang ->
            row.addView(fieldLabel(lang.uppercase()))
            row.addView(translationField(store, lang))
        }
        return row
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 10f
        setTextColor(getColor(R.color.panelTextSecondary))
        setPadding(0, 4.dp, 0, 1.dp)
    }

    private fun translationField(
        store: MutableMap<String, String>,
        lang: String
    ): EditText {
        return EditText(this).apply {
            setText(store[lang].orEmpty())
            hint = getString(R.string.ui_no_translation)
            textSize = 13f
            setTextColor(getColor(R.color.textPrimary))
            setHintTextColor(getColor(R.color.textSecondary))
            background = getDrawable(R.drawable.bg_panel)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            addTextChangedListener { store[lang] = it?.toString().orEmpty() }
        }
    }

    private fun exportTranslationJson() {
        if (translationItems.isEmpty()) {
            binding.statusText.text = getString(R.string.ui_extract_first)
            return
        }
        val rows = translationItems.map {
            CadBridge.TranslationRowJson(
                id = it.id, type = it.type, text = it.text, layer = it.layer,
                tr = translations[it.id].orEmpty().toMap()
            )
        }
        pendingExportBytes = CadBridge.buildTranslationJson(rows).toByteArray(Charsets.UTF_8)
        exportDocument.launch("translations.json")
    }

    /**
     * Save drawing with translation: [format] = "dxf" | "dwg", [lang] = any language tag.
     * Empty translation field means "keep original".
     */
    private fun saveTranslated(format: String, lang: String) {
        if (translationItems.isEmpty()) {
            binding.statusText.text = getString(R.string.ui_extract_first)
            return
        }
        val edits = translationItems.associate { item ->
            val value = translations[item.id]?.get(lang).orEmpty()
            item.id to value.ifBlank { item.text }
        }
        binding.statusText.text = getString(R.string.ui_preparing, format, lang.uppercase())
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
                    val suffix = if (format == "dwg") "DWG (experimental)" else "DXF"
                    exportDocument.launch("${base}_${lang}.${format}")
                    binding.statusText.text =
                        "Done ($suffix, ${lang.uppercase()}): check the result" +
                            if (format == "dwg") " in AutoCAD — DWG writing is experimental." else "."
                }
            }.onFailure { error ->
                runOnUiThread {
                    binding.statusText.text = "Save failed: ${error.message ?: "unknown"}"
                }
            }
        }
    }

    /** Text translation panel: extract texts, JSON, save in any target languages. */
    /** Settings panel: interface language picker and brief info. */
    /**
     * Save current drawing view as PNG (for render self-check).
     * The file goes to Downloads/cad_preview_<name>_<time>.png —
     * it can be opened and checked visually, including automatically.
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
        binding.statusText.text = getString(R.string.ui_png_ready)
    }
    private fun showSettingsPanel() {
        showSidePanel(binding.actionSettings, getString(R.string.action_settings))
        binding.sideContent.removeAllViews()
        binding.sideContent.addView(sectionTitle(getString(R.string.language_picker)))
        val langLabels = arrayOf("English", "Русский", "中文", "System")
        val langCodes = arrayOf("en", "ru", "zh", "")
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
        binding.sideContent.addView(sectionTitle(getString(R.string.ui_render_check)))
        binding.sideContent.addView(actionRow("📸 " + getString(R.string.ui_save_png)) { exportCanvasPng() })
        binding.sideContent.addView(infoCard(getString(R.string.ui_render_check) to getString(R.string.ui_png_purpose)))
        binding.sideContent.addView(sectionTitle(getString(R.string.ui_about_title)))
        binding.sideContent.addView(infoCard(
            getString(R.string.ui_about) to getString(R.string.ui_about_text),
            getString(R.string.ui_license_title) to getString(R.string.ui_license_text)
        ))
        binding.sideContent.addView(sectionTitle(getString(R.string.ui_links_title)))
        binding.sideContent.addView(actionRow("📄 " + getString(R.string.ui_view_readme)) { showReadmeDialog() })
        binding.sideContent.addView(actionRow("⭐ " + getString(R.string.ui_link_github)) { openWebLink("https://github.com/sikkatu/SikkatuCAD") })
        binding.sideContent.addView(actionRow("✈ " + getString(R.string.ui_link_tg_channel)) { openWebLink("https://t.me/sikkatu") })
        binding.sideContent.addView(actionRow("💬 " + getString(R.string.ui_link_tg_chat)) { openWebLink("https://t.me/sikkatuCAD") })
    }
    /** Show the full README + license text in a scrollable dialog. */
    private fun showReadmeDialog() {
        val scroll = android.widget.ScrollView(this)
        val text = TextView(this).apply {
            text = getString(R.string.ui_readme_full)
            textSize = 13f
            setTextColor(getColor(R.color.textPrimary))
            setPadding(48, 36, 48, 36)
        }
        scroll.addView(text)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_view_readme))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
    /** Open a web link in the browser. */
    private fun openWebLink(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure {
            binding.statusText.text = "Cannot open link: " + url
        }
    }
    /** Dialog to add a target translation language code (any language). */
    private fun showAddLanguageDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.ui_lang_code_hint)
            textSize = 14f
            setTextColor(getColor(R.color.textPrimary))
            setHintTextColor(getColor(R.color.textSecondary))
            background = getDrawable(R.drawable.bg_panel)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_add_lang))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = input.text.toString().trim().lowercase().replace("_", "-")
                when {
                    code.isEmpty() -> binding.statusText.text = getString(R.string.ui_lang_code_empty)
                    translationLangs.contains(code) ->
                        binding.statusText.text = getString(R.string.ui_lang_already, code.uppercase())
                    else -> {
                        translationLangs.add(code)
                        showTranslationPanel()
                        binding.statusText.text = getString(R.string.ui_lang_added, code.uppercase())
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    private fun showTranslationPanel() {
        showSidePanel(null, getString(R.string.ui_translation_panel))
        binding.sideContent.removeAllViews()
        binding.sideContent.addView(sectionTitle(getString(R.string.ui_translation_panel)))
        binding.sideContent.addView(entryView(getString(R.string.ui_file), currentFileName ?: "-"))
        binding.sideContent.addView(actionRow(getString(R.string.ui_extract)) { extractTranslationTexts() })
        binding.sideContent.addView(actionRow(getString(R.string.ui_load_json)) {
            translationImport.launch(arrayOf("application/json", "text/plain", "*/*"))
        })
        binding.sideContent.addView(actionRow(getString(R.string.ui_export_json)) { exportTranslationJson() })
        binding.sideContent.addView(actionRow("\u2795 " + getString(R.string.ui_add_lang)) { showAddLanguageDialog() })
        binding.sideContent.addView(sectionTitle(getString(R.string.ui_save_with)))
        val saveLangs = translationLangs.toList()
        saveLangs.forEachIndexed { index, lang ->
            val dxfLabel = getString(R.string.ui_save_dxf_lang, lang.uppercase())
            val dwgLabel = getString(R.string.ui_save_dwg_lang, lang.uppercase())
            if (index == 0) {
                binding.sideContent.addView(primaryActionRow(dxfLabel) { saveTranslated("dxf", lang) })
            } else {
                binding.sideContent.addView(actionRow(dxfLabel) { saveTranslated("dxf", lang) })
            }
            binding.sideContent.addView(actionRow(dwgLabel) { saveTranslated("dwg", lang) })
        }
        binding.sideContent.addView(
            TextView(this).apply {
                text = getString(R.string.ui_dwg_warn)
                textSize = 11f
                setTextColor(getColor(R.color.panelTextSecondary))
                setPadding(12.dp, 6.dp, 12.dp, 10.dp)
            }
        )
        binding.sideContent.addView(sectionTitle(getString(R.string.ui_drawing_texts)))
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
            setTextColor(getColor(R.color.textPrimary))
            setHintTextColor(getColor(R.color.textSecondary))
            background = getDrawable(R.drawable.bg_panel)
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
        }
        val heightField = EditText(this).apply {
            hint = getString(R.string.s0130)
            setText("%.3f".format(bounds.height))
            setTextColor(getColor(R.color.textPrimary))
            setHintTextColor(getColor(R.color.textSecondary))
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
            else -> parsed
        }
        pushHistorySnapshot(scaled, getString(R.string.s0137))
        binding.dxfCanvasView.setDocument(scaled)
        binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
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
            setTextColor(getColor(R.color.textPrimary))
            setHintTextColor(getColor(R.color.textSecondary))
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

    private var lastPickedPoint: Point2? = null
    private var lastOpenedUri: android.net.Uri? = null
    private var lastOpenedFileName: String? = null
    private fun returnToHome() {
        // Remember last file so the user can return to the drawing
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
        updateChips()
        showEmpty()
    }

    private fun showLayerPanel() {
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
            binding.statusText.text = getString(R.string.s0178)
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
        binding.sideContent.addView(actionRowIconized("📏", getString(R.string.s0184), getString(R.string.ui_hint_distance)) {
            measureMode = MeasureMode.DISTANCE
            binding.dxfCanvasView.setMeasureMode(measureMode)
            binding.statusText.text = getString(R.string.s0185)
            showMeasurePanel()
        })
        binding.sideContent.addView(actionRowIconized("📐", getString(R.string.s0186), getString(R.string.ui_hint_angle)) {
            measureMode = MeasureMode.ANGLE
            binding.dxfCanvasView.setMeasureMode(measureMode)
            binding.statusText.text = getString(R.string.s0187)
            showMeasurePanel()
        })
        binding.sideContent.addView(actionRowIconized("⬠", getString(R.string.s0188), getString(R.string.ui_hint_area)) {
            measureMode = MeasureMode.AREA
            binding.dxfCanvasView.setMeasureMode(measureMode)
            binding.dxfCanvasView.setAreaAutoClose(areaAutoClose)
            binding.statusText.text = getString(R.string.s0189)
            showMeasurePanel()
        })
        binding.sideContent.addView(actionRowIconized("✋", getString(R.string.s0190), getString(R.string.ui_hint_exit)) {
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
            binding.sideContent.addView(actionRowIconized("↩", getString(R.string.s0195), getString(R.string.ui_hint_undo)) { binding.dxfCanvasView.undoMeasurementStep() })
            binding.sideContent.addView(actionRowIconized("🧹", getString(R.string.s0196), getString(R.string.ui_hint_reset)) { binding.dxfCanvasView.clearMeasurement() })
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

    private fun showSelectionEditor(selection: GeometrySelection) {
        applySelectionBaseView(selection)
        // Tap on a text -> open its edit dialog right away
        val picked = currentPreviewDocument?.entities?.getOrNull(selection.entityIndex)
        if (picked is DxfText) {
            showEditSelectedEntityDialog()
            return
        }
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
                setTextColor(getColor(R.color.textPrimary))
                setHintTextColor(getColor(R.color.textSecondary))
                background = getDrawable(R.drawable.bg_panel)
                setPadding(14.dp, 14.dp, 14.dp, 14.dp)
            }
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                // labels sit on a light AlertDialog background -> dark text
                setTextColor(getColor(R.color.textSecondary))
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
        binding.dxfCanvasView.setMeasureMode(MeasureMode.NONE)
        binding.dxfCanvasView.setDocument(document)
        binding.dxfCanvasView.setHiddenLayers(emptySet())
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
        val form = editForm(getString(R.string.s0275) to (lastPickedPoint?.x ?: 0.0).toString(), getString(R.string.s0276) to (lastPickedPoint?.y ?: 0.0).toString(), getString(R.string.s0277) to ((lastPickedPoint?.x ?: 0.0) + 100).toString(), getString(R.string.s0278) to (lastPickedPoint?.y ?: 0.0).toString(), getString(R.string.s0214) to "CAD_EDIT")
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
            "X" to (lastPickedPoint?.x ?: 0.0).toString(), "Y" to (lastPickedPoint?.y ?: 0.0).toString(), getString(R.string.s0268) to "100", getString(R.string.s0269) to "60", getString(R.string.s0214) to "CAD_EDIT"
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
        val form = editForm(getString(R.string.s0280) to (lastPickedPoint?.x ?: 0.0).toString(), getString(R.string.s0281) to (lastPickedPoint?.y ?: 0.0).toString(), getString(R.string.s0282) to "50", getString(R.string.s0214) to "CAD_EDIT")
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
        val form = editForm(getString(R.string.s0280) to (lastPickedPoint?.x ?: 0.0).toString(), getString(R.string.s0281) to (lastPickedPoint?.y ?: 0.0).toString(), getString(R.string.s0282) to "50", getString(R.string.s0283) to "0", getString(R.string.s0284) to "90", getString(R.string.s0214) to "CAD_EDIT")
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
        val form = editForm("X" to (lastPickedPoint?.x ?: 0.0).toString(), "Y" to (lastPickedPoint?.y ?: 0.0).toString(), getString(R.string.s0293) to "TEXT", getString(R.string.s0269) to "30", getString(R.string.s0214) to "CAD_TEXT")
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
            else -> parsed
        }
        pushHistorySnapshot(updated, label)
        binding.dxfCanvasView.setDocument(updated)
        binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
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
            else -> parsed
        }
        currentSelection = null
        clearSelectionBaseView()
        binding.dxfCanvasView.setDocument(document)
        binding.dxfCanvasView.setHiddenLayers(hiddenLayers)
        binding.dxfCanvasView.clearSelection(notify = false)
        binding.statusText.text = status
        showInfoPanel()
    }

    private fun historySummary(): String {
        return if (historyDocuments.isEmpty()) "0 / 0" else "${historyIndex + 1} / ${historyDocuments.size}"
    }

    private fun applySelectionBaseView(selection: GeometrySelection) {
        clearSelectionBaseView()
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
                binding.sideContent.addView(
                    infoLine(
                        getString(R.string.s0337),
                        "X ${"%.2f".format(parsed.document.bounds.minX)} ~ ${"%.2f".format(parsed.document.bounds.maxX)}\nY ${"%.2f".format(parsed.document.bounds.minY)} ~ ${"%.2f".format(parsed.document.bounds.maxY)}"
                    )
                )
currentUri?.let { binding.sideContent.addView(infoLine("URI", it.toString())) }
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
        return ext in setOf("dxf", "dwg", "pdf")
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

    /** Compact icon tile button (for 2-column grid). */
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
    /** Row of two compact tiles. */
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
    /** Action row with an explanatory icon on the left. */
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

    /** Info card: multiple rows in ONE frame (not button-like). */
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
            // Info block - NOT a button: flat background, no border
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
                is ParsedCadFile.Dwg -> parsed.preview.readableVersion
                null -> "-"
            }
        }
    }

    override fun onCanvasTap(x: Double, y: Double) {
        lastPickedPoint = Point2(x, y)
        binding.statusText.text = getString(R.string.ui_pick_point) + "  X=%.1f  Y=%.1f".format(x, y)
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

}