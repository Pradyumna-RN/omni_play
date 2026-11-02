package labs.rtensor.music.data

import java.net.URI

/** Request real provider renditions sized for where they are drawn; never upscale the downloaded bitmap. */
object ArtworkUrl {
    private val googleHosts = setOf("lh3.googleusercontent.com", "yt3.googleusercontent.com", "yt3.ggpht.com")
    private val ytimgHosts = setOf("i.ytimg.com", "img.youtube.com")
    private val ytimgName = Regex("/(?:default|mqdefault|hqdefault|sddefault|maxresdefault|hq720)\\.(jpg|webp)(?=\\?|$)")

    fun original(value: String): String = sized(value, 1200)

    /**
     * A rendition close to [px] pixels on its longest side. Small list thumbnails then download a
     * few kilobytes instead of a full-size cover, which is what keeps fast scrolling filled in.
     * Sizes are bucketed so the same image is shared across screens in the cache.
     */
    fun sized(value: String, px: Int): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        if (uri.scheme != "https" || uri.userInfo != null) return value
        val target = when { px <= 120 -> 120; px <= 240 -> 240; px <= 544 -> 544; else -> 1200 }
        if (uri.host in googleHosts) {
            val marker = value.lastIndexOf('=')
            if (marker < 0) return value
            val sizing = value.substring(marker + 1)
            if (!Regex("(?:w|h|s)\\d+").containsMatchIn(sizing)) return value
            return value.substring(0, marker + 1) + sizing.split('-').joinToString("-") {
                if (it.matches(Regex("[whs]\\d+"))) it.take(1) + target else it
            }
        }
        if (uri.host in ytimgHosts) {
            // hqdefault (480x360) always exists; maxresdefault is missing for some uploads.
            val name = when { target <= 240 -> "mqdefault"; target <= 544 -> "hqdefault"; else -> "maxresdefault" }
            return value.replace(ytimgName, "/$name.$1")
        }
        return value
    }

    /** A rendition that every YouTube video has, used when a larger one is missing. */
    fun safe(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        return if (uri.host in ytimgHosts) value.replace(ytimgName, "/hqdefault.$1") else value
    }
}
