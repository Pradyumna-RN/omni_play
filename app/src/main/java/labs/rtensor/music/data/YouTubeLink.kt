package labs.rtensor.music.data

import java.net.URI

/** Stable catalogue links only. Expiring CDN URLs must never be persisted. */
object YouTubeLink {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val playlistId = Regex("[A-Za-z0-9_-]{10,100}")
    private val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "www.youtube-nocookie.com")

    fun normalize(input: String): String? {
        val text = input.trim()
        if (videoId.matches(text)) return "https://www.youtube.com/watch?v=$text"
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("https", "http") || uri.userInfo != null || uri.port != -1) return null
        val host = uri.host?.lowercase() ?: return null
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
        val segments = uri.path.orEmpty().trim('/').split('/')
        val id = when {
            host in setOf("youtu.be", "www.youtu.be") && segments.size == 1 -> segments[0]
            host !in hosts -> return null
            uri.path == "/watch" -> query["v"]
            segments.size == 2 && segments[0] in setOf("shorts", "embed", "live") -> segments[1]
            else -> null
        }
        if (id != null && videoId.matches(id)) return "https://www.youtube.com/watch?v=$id"
        val list = query["list"]
        return if (host in hosts && uri.path == "/playlist" && list != null && playlistId.matches(list)) {
            "https://www.youtube.com/playlist?list=$list"
        } else null
    }
}
