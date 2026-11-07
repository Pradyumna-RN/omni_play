package labs.rtensor.music.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** Finds the audio in a folder the user picked, including every subfolder, so a whole library can be added at once. */
internal object LocalFolderScanner {
    private val extensions = setOf("mp3", "m4a", "aac", "flac", "alac", "wav", "aiff", "aif", "ogg", "oga", "opus", "wma", "mka", "mp4", "ec3", "eac3", "ac3", "ac4", "thd", "dts")

    /** Audio files under [tree] as (document uri, file name), in folder order. Call off the main thread. */
    fun audioFiles(context: Context, tree: Uri, isActive: () -> Boolean = { true }, progress: (Int) -> Unit = {}): List<Pair<Uri, String>> {
        val found = mutableListOf<Pair<Uri, String>>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        while (pending.isNotEmpty() && isActive()) {
            val parent = pending.removeFirst()
            context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0); val mime = cursor.getString(1).orEmpty(); val name = cursor.getString(2).orEmpty()
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> pending.add(id)
                        mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in extensions -> {
                            found += DocumentsContract.buildDocumentUriUsingTree(tree, id) to name; progress(found.size)
                        }
                    }
                }
            }
        }
        return found
    }
}

/** Copies the songs saved for offline listening into a folder the user chose, named "Artist - Title.ext". */
internal object DownloadExporter {
    data class Result(val copied: Int, val failed: Int)

    fun export(context: Context, songs: List<DownloadedSong>, files: (DownloadedSong) -> File?, tree: Uri,
        isActive: () -> Boolean = { true }, progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var copied = 0; var failed = 0
        val used = mutableSetOf<String>()
        songs.forEachIndexed { index, song ->
            if (!isActive()) return Result(copied, failed + songs.size - index)
            progress(index, songs.size)
            val source = files(song)
            if (source == null) { failed++; return@forEachIndexed }
            val extension = source.extension.ifBlank { "m4a" }
            val name = fileName(song.track, extension, used)
            val target = runCatching { DocumentsContract.createDocument(context.contentResolver, root, mimeFor(extension), name) }.getOrNull()
            val ok = target != null && runCatching {
                context.contentResolver.openOutputStream(target)!!.use { out -> source.inputStream().use { it.copyTo(out) } }
            }.isSuccess
            if (ok) copied++ else { failed++; target?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } } }
        }
        return Result(copied, failed)
    }

    internal fun fileName(track: Track, extension: String, used: MutableSet<String> = mutableSetOf()): String {
        fun clean(text: String) = text.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80)
        val base = listOf(clean(track.artist), clean(track.title).ifBlank { "Song" }).filter { it.isNotBlank() }.joinToString(" - ")
        var candidate = "$base.$extension"; var n = 2
        while (!used.add(candidate.lowercase())) candidate = "$base ($n).$extension".also { n++ }
        return candidate
    }

    private fun mimeFor(extension: String) = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"; "m4a", "mp4" -> "audio/mp4"; "webm" -> "audio/webm"; "opus", "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"; "wav" -> "audio/wav"; else -> "application/octet-stream"
    }
}
