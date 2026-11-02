package labs.rtensor.music.data

import java.net.URI

/** Request real larger provider renditions; never upscale the downloaded bitmap. */
object ArtworkUrl {
    fun original(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        if (uri.scheme != "https" || uri.userInfo != null) return value
        if (uri.host in setOf("lh3.googleusercontent.com", "yt3.googleusercontent.com", "yt3.ggpht.com")) {
            val marker = value.lastIndexOf('=')
            if (marker < 0) return value
            val sizing = value.substring(marker + 1)
            if (!Regex("(?:w|h|s)\\d+").containsMatchIn(sizing)) return value
            return value.substring(0, marker + 1) + sizing.split('-').joinToString("-") {
                if (it.matches(Regex("[whs]\\d+"))) it.take(1) + "1200" else it
            }
        }
        if (uri.host in setOf("i.ytimg.com", "img.youtube.com")) {
            return value.replace(Regex("/(?:default|mqdefault|hqdefault|sddefault)\\.(jpg|webp)(?=\\?|$)"), "/maxresdefault.$1")
        }
        return value
    }
}
