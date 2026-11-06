package labs.rtensor.music.data

/**
 * The accent the app uses for its one strong colour. [SIGNAL] is stored under its original name
 * but is Hum's Berry pink: it is the default for new installs and for anyone who never picked one.
 * [COVER] takes the colour from the artwork that is playing, and shows Berry when there is none.
 * The colours themselves live with the theme (ui/Accent.kt); this is only what is stored.
 */
enum class AccentChoice(val wire: String, val label: String) {
    SIGNAL("signal", "Berry"),
    COBALT("cobalt", "Cobalt"),
    MOSS("moss", "Moss"),
    OXBLOOD("oxblood", "Oxblood"),
    INK("ink", "Ink"),
    COVER("cover", "Cover");

    companion object {
        val DEFAULT = SIGNAL
        /** Missing, unknown or retired values mean nobody chose: use the default. */
        fun parse(value: String?): AccentChoice = entries.firstOrNull { it.wire == value } ?: DEFAULT
    }
}
