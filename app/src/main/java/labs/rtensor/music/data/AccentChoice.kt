package labs.rtensor.music.data

/**
 * The accent the Signal design uses for its one strong colour. [COVER] is the default, for
 * new installs and for anyone who never picked one; it shows [SIGNAL], the original orange,
 * whenever there is no artwork to take a colour from. The colours themselves live with the
 * theme (ui/Accent.kt); this is only what is stored.
 */
enum class AccentChoice(val wire: String, val label: String) {
    SIGNAL("signal", "Signal"),
    COBALT("cobalt", "Cobalt"),
    MOSS("moss", "Moss"),
    OXBLOOD("oxblood", "Oxblood"),
    INK("ink", "Ink"),
    COVER("cover", "From the cover");

    companion object {
        val DEFAULT = COVER
        /** Missing, unknown or retired values mean nobody chose: use the default. */
        fun parse(value: String?): AccentChoice = entries.firstOrNull { it.wire == value } ?: DEFAULT
    }
}
