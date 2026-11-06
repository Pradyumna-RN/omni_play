package labs.rtensor.music.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

internal data class LocalAudioMetadata(val title: String = "", val artist: String = "", val durationMs: Long = 0)

/** Metadata support is optional: Media3's software decoder may support a file
 * the platform retriever cannot open. Read permission is still required. */
internal object LocalAudioImporter {
    /** Document picker types. Owned Dolby Digital Plus test material and many
     * recordings arrive as .mp4/.ec3 typed as video/mp4 or octet-stream; the
     * player only takes the audio track. */
    val PICKER_TYPES = arrayOf("audio/*", "video/mp4", "application/octet-stream")

    fun read(context: Context, uri: Uri,
        metadata: (Context, Uri) -> LocalAudioMetadata = ::readMetadata): Track {
        context.contentResolver.openInputStream(uri).use {
            if (it == null || it.read() < 0) throw IOException("The audio file is empty or unreadable")
        }
        val filename = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('\\') ?: "Local recording"
        val details = runCatching { metadata(context, uri) }.getOrDefault(LocalAudioMetadata())
        return Track("local:$uri", details.title.ifBlank { filename.substringBeforeLast('.', filename) },
            details.artist, uri.toString(), durationMs = details.durationMs.coerceAtLeast(0), source = Source.LOCAL)
    }
    private fun readMetadata(context: Context, uri: Uri): LocalAudioMetadata {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(context, uri)
            LocalAudioMetadata(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)
        } finally { reader.release() }
    }
}
