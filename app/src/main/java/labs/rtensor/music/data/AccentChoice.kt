package labs.rtensor.music.data

/**
 * The accent the Signal design uses for its one strong colour. [SIGNAL] is the original
 * orange and the default, so existing installs look the same until someone picks another.
 * The colours themselves live with the theme (ui/Accent.kt); this is only what is stored.
 */
enum class AccentChoice(val wire: String, val label: String) {
    SIGNAL("signal", "Signal"),
    COBALT("cobalt", "Cobalt"),
    MOSS("moss", "Moss"),
    OXBLOOD("oxblood", "Oxblood"),
    INK("ink", "Ink"),
    COVER("cover", "From the cover");

    companion object {
        /** Unknown, missing or retired values fall back to Signal rather than failing. */
        fun parse(value: String?): AccentChoice = entries.firstOrNull { it.wire == value } ?: SIGNAL
    }
}
