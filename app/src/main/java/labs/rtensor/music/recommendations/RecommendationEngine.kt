package labs.rtensor.music.recommendations

enum class RecommendationEngine(val label: String, val wire: String) {
    YOUTUBE("YouTube Music", "youtube"), YOUTUBE_VIDEO("YouTube videos", "youtube_video"), SPOTIFY("Spotify", "spotify");
    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.wire == value } ?: YOUTUBE
    }
}
