package labs.rtensor.music.data

import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/** Lyrics in one language plus the language they were detected as ("hi", "ko", ...). */
data class TranslatedLyrics(val text: String, val sourceLanguage: String) {
    /** The detected language's name for display ("Hindi"), or blank when unknown. */
    val sourceName: String get() = LyricsTranslator.languageName(sourceLanguage)
}

/**
 * YouTube Music shows a song's lyrics only in the language it was written in. The "English" version
 * is the same words written in English letters ("ನಾನು" becomes "nanu"), so a Kannada, Hindi or Tamil
 * song can be read and sung along to without knowing the script. It asks Google Translate's public
 * web endpoint for the transliteration, line by line so stanza breaks and timings survive. Lyrics
 * that are already in Latin letters come back unchanged. Only the lyric text is sent.
 */
class LyricsTranslator(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    private val endpoint: String = "https://translate.googleapis.com/translate_a/single",
) {
    suspend fun toEnglish(text: String): TranslatedLyrics = withContext(Dispatchers.IO) {
        var language = ""
        val romanised = chunks(text).joinToString("") { part ->
            val body = request(part)
            if (language.isEmpty()) language = parse(body).second
            parseRomanisation(body)?.let(::plainLetters) ?: part
        }
        TranslatedLyrics(romanised.trimEnd(), language)
    }

    private fun request(text: String): String {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx").addQueryParameter("sl", "auto").addQueryParameter("tl", "en")
            .addQueryParameter("dt", "t").addQueryParameter("dt", "rm").build()
        val call = Request.Builder().url(url).post(FormBody.Builder().add("q", text).build()).build()
        client.newCall(call).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Transliteration failed (${response.code}).")
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        /** The original's text in Latin letters, from the response's transliteration segment; null when the text needs none. */
        internal fun parseRomanisation(body: String): String? {
            val segments = JSONArray(body).optJSONArray(0) ?: return null
            for (i in 0 until segments.length()) {
                val segment = segments.optJSONArray(i) ?: continue
                val roman = segment.optString(3, "").takeUnless { it == "null" || it.isBlank() } ?: continue
                if (segment.isNull(0) && segment.isNull(1)) return roman
            }
            return null
        }

        private val marks = Regex("\\p{M}+")
        /** "Nānu" → "Nanu": drop the accents Google adds, and start each line with a capital. */
        internal fun plainLetters(text: String): String =
            java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(marks, "")
                .lines().joinToString("\n") { line -> line.replaceFirstChar { it.uppercase() } }

        /** The endpoint answers up to about 5,000 characters; stay well below it, breaking at line ends. */
        internal fun chunks(text: String, limit: Int = 3_500): List<String> {
            val out = mutableListOf<String>(); val current = StringBuilder()
            text.lines().forEach { line ->
                if (current.isNotEmpty() && current.length + line.length + 1 > limit) { out += current.toString(); current.clear() }
                current.append(line).append('\n')
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }

        /** `[[["translated","original",...],...],null,"hi",...]` → translated text and detected language. */
        internal fun parse(body: String): Pair<String, String> {
            val root = JSONArray(body)
            val segments = root.optJSONArray(0) ?: throw java.io.IOException("Translation had no text.")
            val text = buildString { for (i in 0 until segments.length()) append(segments.optJSONArray(i)?.optString(0).orEmpty()) }
            if (text.isBlank()) throw java.io.IOException("Translation had no text.")
            return text to root.optString(2).takeUnless { it == "null" }.orEmpty()
        }

        fun languageName(code: String): String {
            if (code.isBlank()) return ""
            val locale = Locale.forLanguageTag(code)
            return locale.getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && !it.equals(code, true) } ?: ""
        }
    }
}
