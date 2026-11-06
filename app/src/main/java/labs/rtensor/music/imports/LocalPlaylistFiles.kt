package labs.rtensor.music.imports

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import labs.rtensor.music.data.LocalAudioImporter
import labs.rtensor.music.data.Track

internal object LocalPlaylistFiles {
    private val extensions = setOf("m4a", "mp3", "flac", "ogg", "opus", "wav", "aac", "aiff", "aif", "wma", "eac3", "ec3", "ac3", "mka", "mp4", "alac", "ape")
    fun index(context: Context, tree: Uri, checkCancelled: () -> Unit = {}): Map<String, Uri> {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val rootName = context.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, root), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: root.substringAfterLast(':').substringAfterLast('/')
        val pending = ArrayDeque(listOf(root to rootName)); val visited = HashSet<String>(); val files = linkedMapOf<String, Uri>()
        while (pending.isNotEmpty()) {
            checkCancelled()
            val (id, path) = pending.removeFirst()
            if (!visited.add(id)) continue
            require(visited.size <= 12000 && path.count { it == '/' } <= 40) { "Choose a smaller audio folder." }
            context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    checkCancelled()
                    val child = c.getString(0); val name = c.getString(1); val mime = c.getString(2)
                    val relative = if (path.isBlank()) name else "$path/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) pending += child to relative
                    else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in extensions) files[relative] = DocumentsContract.buildDocumentUriUsingTree(tree, child)
                }
            }
        }
        return files
    }
    fun find(reference: String, files: Map<String, Uri>): Uri? {
        val path = Uri.decode(reference.removePrefix("file://")).replace('\\', '/').trim().trimStart('/')
        if (path.split('/').any { it == ".." }) return null
        val matches = if ('/' !in path) files.entries.filter { it.key.substringAfterLast('/').equals(path, true) }
            else files.entries.filter { path.equals(it.key, true) || path.endsWith("/${it.key}", true) || it.key.endsWith("/$path", true) }
        return matches.map { it.value }.distinct().singleOrNull()
    }
    fun read(context: Context, uri: Uri, song: ImportSong): Track = LocalAudioImporter.read(context, uri).let { track ->
        track.copy(title = song.title.ifBlank { track.title }, artist = track.artist.ifBlank { song.artist }, durationMs = track.durationMs.takeIf { it > 0 } ?: song.durationMs)
    }
}
