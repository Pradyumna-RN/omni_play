package labs.rtensor.music.data

/**
 * How the whole app looks. [ROOM], the Listening room, is the default for everyone; [SOFT] is the
 * original rounded design from before Signal (0.3.31); [CLASSIC] is the 0.3.32 Signal look. Only the choice is stored here; the
 * tokens, shapes and type for each look live with the theme (ui/Look.kt).
 */
enum class LookChoice(val wire: String, val label: String) {
    ROOM("room", "Listening room"),
    SOFT("soft", "Soft"),
    CLASSIC("classic", "Classic");

    companion object {
        val DEFAULT = ROOM
        /** Missing, unknown or retired values (such as an in-between look) mean the Listening room. */
        fun parse(value: String?): LookChoice = entries.firstOrNull { it.wire == value } ?: DEFAULT
    }
}
