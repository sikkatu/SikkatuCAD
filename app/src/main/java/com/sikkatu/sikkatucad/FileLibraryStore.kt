package com.sikkatu.sikkatucad

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream


data class StoredFileRef(
    val uri: String,
    val name: String,
    val openedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val localPath: String? = null,
    val sourceUri: String? = null,
    val managed: Boolean = false
)

enum class FilePanelTab {
    IMPORT,
    RECENT,
    FAVORITES,
    ALL_FILES,
    SEARCH
}

enum class MeasureMode {
    NONE,
    DISTANCE,
    ANGLE,
    AREA
}

class FileLibraryStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("cad_library_store", Context.MODE_PRIVATE)

    fun getRecentFiles(): List<StoredFileRef> = readRefs("recent_files").filter { ref ->
        !ref.managed || ref.localPath?.let { File(it).exists() } == true
    }

    fun addRecentFile(uri: Uri, name: String, localPath: String? = null, sourceUri: Uri? = null, managed: Boolean = false) {
        val key = uri.toString()
        val existing = readRefs("recent_files").associateBy { it.uri }
        val updated = (listOf(
            StoredFileRef(
                uri = key,
                name = name,
                openedAt = System.currentTimeMillis(),
                pinned = existing[key]?.pinned == true,
                localPath = localPath ?: existing[key]?.localPath,
                sourceUri = sourceUri?.toString() ?: existing[key]?.sourceUri,
                managed = managed || existing[key]?.managed == true
            )
        ) + readRefs("recent_files"))
            .distinctBy { it.uri }
            .sortedWith(compareByDescending<StoredFileRef> { it.pinned }.thenByDescending { it.openedAt })
            .take(48)
        writeRefs("recent_files", updated)
    }

    fun removeRecentFile(uri: String) {
        writeRefs("recent_files", readRefs("recent_files").filterNot { it.uri == uri })
    }

    fun deleteManagedFile(ref: StoredFileRef): Boolean {
        if (!ref.managed || ref.localPath.isNullOrBlank()) return false
        val file = File(ref.localPath)
        val deleted = !file.exists() || file.delete()
        if (deleted) {
            removeRecentFile(ref.uri)
            writeRefs("favorite_files", readRefs("favorite_files").filterNot { it.uri == ref.uri })
        }
        return deleted
    }

    fun togglePinRecentFile(uri: String): Boolean {
        val updated = readRefs("recent_files").map {
            if (it.uri == uri) it.copy(pinned = !it.pinned) else it
        }.sortedWith(compareByDescending<StoredFileRef> { it.pinned }.thenByDescending { it.openedAt })
        writeRefs("recent_files", updated)
        return updated.firstOrNull { it.uri == uri }?.pinned == true
    }

    fun clearRecentFiles() {
        writeRefs("recent_files", emptyList())
    }

    fun getFavorites(): List<StoredFileRef> = readRefs("favorite_files")

    fun isFavorite(uri: Uri): Boolean = getFavorites().any { it.uri == uri.toString() }

    fun toggleFavorite(uri: Uri, name: String): Boolean {
        val favorites = getFavorites().toMutableList()
        val existingIndex = favorites.indexOfFirst { it.uri == uri.toString() }
        return if (existingIndex >= 0) {
            favorites.removeAt(existingIndex)
            writeRefs("favorite_files", favorites)
            false
        } else {
            val recentRef = readRefs("recent_files").firstOrNull { it.uri == uri.toString() }
            favorites.add(0, recentRef?.copy(name = name, openedAt = System.currentTimeMillis()) ?: StoredFileRef(uri.toString(), name))
            writeRefs("favorite_files", favorites.distinctBy { it.uri }.take(64))
            true
        }
    }

    fun getTreeRootUri(): String? = prefs.getString("tree_root_uri", null)

    fun setTreeRootUri(uri: Uri?) {
        prefs.edit().putString("tree_root_uri", uri?.toString()).apply()
    }

    fun getBrowseUri(): String? = prefs.getString("browse_uri", null)

    fun setBrowseUri(uri: Uri?) {
        prefs.edit().putString("browse_uri", uri?.toString()).apply()
    }

    fun resolveTreeRoot(context: Context): DocumentFile? {
        val uri = getTreeRootUri()?.let(Uri::parse) ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    fun resolveBrowseDirectory(context: Context): DocumentFile? {
        val root = resolveTreeRoot(context) ?: return null
        val browseUri = getBrowseUri()?.let(Uri::parse) ?: return root
        return if (browseUri == root.uri) root else findDocument(root, browseUri)
    }

    fun managedRootDirectory(): File {
        val publicRoot = File(Environment.getExternalStorageDirectory(), "SikkatuCAD/CachedFiles")
        if (runCatching { publicRoot.mkdirs() || publicRoot.isDirectory }.getOrDefault(false) && publicRoot.canWrite()) {
            return publicRoot
        }
        val fallback = File(appContext.getExternalFilesDir(null), "CachedFiles")
        fallback.mkdirs()
        return fallback
    }

    fun managedDirectoryFor(name: String): File {
        return File(managedRootDirectory(), managedTypeFor(name)).apply { mkdirs() }
    }
    fun importToManagedStorage(resolver: ContentResolver, sourceUri: Uri, originalName: String): StoredFileRef {
        if (isZipArchive(originalName)) {
            val refs = importArchiveToManagedStorage(resolver, sourceUri, originalName)
            return refs.firstOrNull() ?: throw IllegalArgumentException(appContext.getString(R.string.s0031))
        }
        if (sourceUri.scheme == ContentResolver.SCHEME_FILE) {
            val sourceFile = File(requireNotNull(sourceUri.path) { appContext.getString(R.string.s0032) })
            if (sourceFile.absolutePath.startsWith(managedRootDirectory().absolutePath)) {
                addRecentFile(
                    uri = sourceUri,
                    name = sourceFile.name,
                    localPath = sourceFile.absolutePath,
                    sourceUri = sourceUri,
                    managed = true
                )
                return readRefs("recent_files").firstOrNull { it.uri == sourceUri.toString() }
                    ?: StoredFileRef(sourceUri.toString(), sourceFile.name, localPath = sourceFile.absolutePath, sourceUri = sourceUri.toString(), managed = true)
            }
        }
        val safeName = sanitizeFileName(originalName.ifBlank { appContext.getString(R.string.s0033) })
        val targetDir = managedDirectoryFor(safeName)
        val targetFile = uniqueFile(targetDir, safeName)
        resolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { appContext.getString(R.string.s0034) }
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }
        val managedUri = Uri.fromFile(targetFile)
        addRecentFile(
            uri = managedUri,
            name = targetFile.name,
            localPath = targetFile.absolutePath,
            sourceUri = sourceUri,
            managed = true
        )
        return readRefs("recent_files").firstOrNull { it.uri == managedUri.toString() }
            ?: StoredFileRef(managedUri.toString(), targetFile.name, localPath = targetFile.absolutePath, sourceUri = sourceUri.toString(), managed = true)
    }

    fun importArchiveToManagedStorage(resolver: ContentResolver, sourceUri: Uri, originalName: String): List<StoredFileRef> {
        val archiveBase = sanitizeFileName(originalName.substringBeforeLast('.', originalName).ifBlank { "archive" })
        val archiveRoot = uniqueDirectory(File(managedRootDirectory(), "ARCHIVE"), archiveBase)
        archiveRoot.mkdirs()
        val imported = mutableListOf<StoredFileRef>()
        resolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { appContext.getString(R.string.s0035) }
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    val safeEntryName = sanitizeFileName(entryName)
                    if (!entry.isDirectory && isSupportedCadFile(safeEntryName)) {
                        val targetDir = File(archiveRoot, managedTypeFor(safeEntryName)).apply { mkdirs() }
                        val targetFile = uniqueFile(targetDir, safeEntryName)
                        targetFile.outputStream().use { output -> zip.copyTo(output) }
                        val managedUri = Uri.fromFile(targetFile)
                        addRecentFile(
                            uri = managedUri,
                            name = targetFile.name,
                            localPath = targetFile.absolutePath,
                            sourceUri = sourceUri,
                            managed = true
                        )
                        imported += readRefs("recent_files").firstOrNull { it.uri == managedUri.toString() }
                            ?: StoredFileRef(managedUri.toString(), targetFile.name, localPath = targetFile.absolutePath, sourceUri = sourceUri.toString(), managed = true)
                    }
                    zip.closeEntry()
                }
            }
        }
        if (imported.isEmpty()) {
            archiveRoot.deleteRecursively()
        }
        return imported
    }

    fun isArchiveName(name: String): Boolean = isZipArchive(name)

    fun isSupportedManagedFile(name: String): Boolean = isSupportedCadFile(name)

    fun typeLabelFor(name: String): String = managedTypeFor(name)


    fun pruneMissingManagedFiles(): Int {
        val before = readRefs("recent_files")
        val after = before.filter { ref -> !ref.managed || ref.localPath?.let { File(it).exists() } == true }
        if (after.size != before.size) writeRefs("recent_files", after)
        val favoriteBefore = readRefs("favorite_files")
        val favoriteAfter = favoriteBefore.filter { ref -> !ref.managed || ref.localPath?.let { File(it).exists() } == true }
        if (favoriteAfter.size != favoriteBefore.size) writeRefs("favorite_files", favoriteAfter)
        return (before.size - after.size) + (favoriteBefore.size - favoriteAfter.size)
    }

    fun storageSummary(): String {
        val root = managedRootDirectory()
        val files = root.walkTopDown().filter { it.isFile }.toList()
        val size = files.sumOf { it.length() }
        return appContext.getString(R.string.s0036, root.absolutePath, files.size, formatSize(size))
    }

    private fun readRefs(key: String): List<StoredFileRef> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val uri = item.optString("uri")
                val name = item.optString("name")
                val openedAt = item.optLong("opened_at", System.currentTimeMillis())
                val pinned = item.optBoolean("pinned", false)
                val localPath = item.optString("local_path").takeIf { it.isNotBlank() }
                val sourceUri = item.optString("source_uri").takeIf { it.isNotBlank() }
                val managed = item.optBoolean("managed", localPath != null)
                if (uri.isNotBlank() && name.isNotBlank()) {
                    add(StoredFileRef(uri, name, openedAt, pinned, localPath, sourceUri, managed))
                }
            }
        }
    }

    private fun writeRefs(key: String, refs: List<StoredFileRef>) {
        val array = JSONArray()
        refs.forEach { ref ->
            array.put(
                JSONObject().apply {
                    put("uri", ref.uri)
                    put("name", ref.name)
                    put("opened_at", ref.openedAt)
                    put("pinned", ref.pinned)
                    put("local_path", ref.localPath ?: "")
                    put("source_uri", ref.sourceUri ?: "")
                    put("managed", ref.managed)
                }
            )
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun findDocument(directory: DocumentFile, targetUri: Uri): DocumentFile? {
        if (directory.uri == targetUri) return directory
        directory.listFiles().forEach { child ->
            if (child.uri == targetUri) return child
            if (child.isDirectory) {
                val found = findDocument(child, targetUri)
                if (found != null) return found
            }
        }
        return null
    }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifBlank { appContext.getString(R.string.s0033) }
        return sanitized.take(120)
    }

    private fun isZipArchive(name: String): Boolean {
        return name.substringAfterLast('.', "").equals("zip", ignoreCase = true)
    }

    private fun isSupportedCadFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return ext in setOf("dxf", "dwg", "gen", "nc", "tap", "cnc", "mpf", "pdf")
    }

    private fun managedTypeFor(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase(Locale.getDefault())) {
            "dxf" -> "DXF"
            "dwg" -> "DWG"
            "pdf" -> "PDF"
            "gen" -> "GEN"
            "nc", "tap", "cnc", "mpf" -> "NC"
            "zip" -> "ARCHIVE"
            else -> "OTHER"
        }
    }

    private fun uniqueDirectory(directory: File, name: String): File {
        directory.mkdirs()
        val base = name.ifBlank { "archive" }
        var file = File(directory, base)
        var index = 1
        while (file.exists()) {
            file = File(directory, "${base}_${index}")
            index++
        }
        return file
    }

    private fun uniqueFile(directory: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var file = File(directory, name)
        var index = 1
        while (file.exists()) {
            file = File(directory, "${base}_${index}${ext}")
            index++
        }
        return file
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }
}