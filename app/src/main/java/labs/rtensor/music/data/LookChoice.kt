package labs.rtensor.music.data

/**
 * How the whole app looks. Hum is the only look the app offers. The older looks stay defined so the
 * theme code that still describes them compiles, but nothing selects them: a stored choice of any
 * other value is read as Hum.
 */
enum class LookChoice(val wire: String, val label: String) {
    HUM("hum", "Hum"),
    ROOM("room", "Listening room"),
    SOFT("soft", "Soft"),
    CLASSIC("classic", "Classic");

    companion object {
        val DEFAULT = HUM
        @Suppress("UNUSED_PARAMETER")
        fun parse(value: String?): LookChoice = HUM
    }
}
