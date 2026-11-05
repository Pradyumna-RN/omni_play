package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Synthetic covers, drawn here so no third-party artwork is committed. Each one reproduces a
 * property of real artwork that mattered for "From the cover" (measured locally on 24 real
 * YouTube Music, YouTube and Spotify covers: 18 gave colours with the old picker, 24 with the
 * new one; those images are not in the repository):
 *
 * - `song-*` / `spotify-*`: square covers at the 120px (YouTube Music `=w120-h120`) and 300px
 *   (Spotify) renditions: plainly colourful ones, a dark and muted one, a black-and-white one,
 *   and one where a small saturated logo competes with a large, less vivid teal field.
 * - `video-*`: 320x180 mqdefault video frames, several dark or greyscale.
 * - `atv-*`: an "art track" thumbnail: the square cover between flat ~70/320px side panels.
 * - `hq-*`: 480x360 hqdefault, with black letterbox bars above and below as well.
 *
 * Every pixel gets a little seeded noise and the covers are served as JPEG, as the CDNs do.
 */
internal object CoverFixtures {
    val names: List<String> = listOf(
        "song-amber-portrait", "song-red-numeral", "song-teal-logo", "song-gold-helmet", "song-bw-portrait",
        "song-sepia-face", "song-black-cream", "song-pale-band", "song-orange-moon", "spotify-blue-photo",
        "video-dark-stage", "video-bw-photo", "video-dark-red", "video-dark-blue", "video-olive",
        "atv-teal-logo", "atv-amber-portrait", "atv-black-cream", "atv-gold-helmet",
        "hq-atv-gold-helmet", "hq-letterbox-dark-blue",
    ).map { "$it.jpg" }

    fun bytes(name: String): ByteArray = java.io.ByteArrayOutputStream().also { draw(name).compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    fun bitmap(name: String): Bitmap = bytes(name).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    /** The fixture at the size the accent works from (Coil's size(128) request). */
    fun small(name: String): Bitmap = bitmap(name).let { raw ->
        val scale = 128f / maxOf(raw.width, raw.height)
        Bitmap.createScaledBitmap(raw, maxOf(1, (raw.width * scale).toInt()), maxOf(1, (raw.height * scale).toInt()), true)
    }

    private class Art(val w: Int, val h: Int) {
        val bitmap: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun box(l: Float, t: Float, r: Float, b: Float) = RectF(l * w, t * h, r * w, b * h)
        fun fill(c: Long) = canvas.drawColor(c.toInt())
        fun rect(l: Float, t: Float, r: Float, b: Float, c: Long) { paint.shader = null; paint.color = c.toInt(); canvas.drawRect(box(l, t, r, b), paint) }
        fun grad(l: Float, t: Float, r: Float, b: Float, top: Long, bottom: Long) {
            paint.shader = LinearGradient(0f, t * h, 0f, b * h, top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(box(l, t, r, b), paint); paint.shader = null
        }
        fun circle(x: Float, y: Float, radius: Float, c: Long) { paint.shader = null; paint.color = c.toInt(); canvas.drawCircle(x * w, y * h, radius * minOf(w, h), paint) }
        fun glow(x: Float, y: Float, radius: Float, inner: Long, outer: Long) {
            paint.shader = RadialGradient(x * w, y * h, radius * minOf(w, h), inner.toInt(), outer.toInt(), Shader.TileMode.CLAMP)
            canvas.drawCircle(x * w, y * h, radius * minOf(w, h), paint); paint.shader = null
        }
        fun beam(c: Long) {
            paint.shader = null; paint.color = c.toInt()
            canvas.drawPath(Path().apply { moveTo(w * .55f, 0f); lineTo(w * .7f, 0f); lineTo(w * .45f, h.toFloat()); lineTo(w * .25f, h.toFloat()); close() }, paint)
        }
        fun place(cover: Bitmap, l: Int, t: Int, size: Int) = canvas.drawBitmap(cover, null, Rect(l, t, l + size, t + size), Paint(Paint.FILTER_BITMAP_FLAG))
        /** Photo and JPEG grain, the same every run. */
        fun grain(amount: Int, seed: Long): Bitmap {
            val random = java.util.Random(seed)
            val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            for (i in px.indices) {
                val n = random.nextInt(amount * 2 + 1) - amount
                fun ch(v: Int) = (v + n).coerceIn(0, 255)
                val c = px[i]
                px[i] = (0xFF shl 24) or (ch(c shr 16 and 0xFF) shl 16) or (ch(c shr 8 and 0xFF) shl 8) or ch(c and 0xFF)
            }
            bitmap.setPixels(px, 0, w, 0, 0, w, h)
            return bitmap
        }
    }

    private fun square(name: String, size: Int = 120): Bitmap = Art(size, size).run {
        when (name) {
            // Warm amber field, a dark head and a dark jacket.
            "amber-portrait" -> { grad(0f, 0f, 1f, 1f, 0xFFE09030, 0xFFC87818); circle(.5f, .42f, .22f, 0xFF2A1A12); rect(.32f, .8f, .68f, 1f, 0xFF3A1A1A); rect(.35f, .8f, .65f, .86f, 0xFFF0E0D0) }
            // Flat red with a big yellow numeral.
            "red-numeral" -> { fill(0xFFE01810); rect(.4f, .2f, .62f, .8f, 0xFFF8E020); rect(.08f, .06f, .25f, .12f, 0xFFF0F0F0) }
            // Mostly deep and muted teal, a dark figure, and two small salmon logos.
            "teal-logo" -> {
                grad(0f, 0f, 1f, 1f, 0xFF0E5A5A, 0xFF0A4040); rect(.28f, .25f, .72f, 1f, 0xFF507880); rect(.42f, .3f, .6f, 1f, 0xFF181818)
                rect(.25f, .12f, .75f, .2f, 0xFFB8D0D0); rect(.04f, .03f, .12f, .1f, 0xFFD07870); rect(.88f, .03f, .96f, .1f, 0xFFD85850)
            }
            // Black, a bluish silver helmet and a gold half.
            "gold-helmet" -> { fill(0xFF0C0C0C); grad(.25f, .2f, .5f, .85f, 0xFFC8D0E0, 0xFF5A6070); grad(.5f, .2f, .75f, .85f, 0xFFE0B050, 0xFF806020) }
            // Black and white: a lit wall, a dark face and a white shirt.
            "bw-portrait" -> { grad(0f, 0f, 1f, 1f, 0xFFE8E8E8, 0xFF606060); circle(.5f, .38f, .18f, 0xFF202020); rect(.25f, .6f, .75f, 1f, 0xFFF0F0F0); rect(.1f, .7f, .3f, .82f, 0xFF101010) }
            // Dark and muted: a sepia face in near black, no colour above 15% saturation.
            "sepia-face" -> { fill(0xFF201E16); glow(.5f, .5f, .45f, 0xFF9A9078, 0xFF302C22) }
            // Near black with a cream bed.
            "black-cream" -> { fill(0xFF120E0E); rect(.25f, .58f, .78f, .74f, 0xFFF0E8D8); circle(.5f, .5f, .08f, 0xFFD0C8B8) }
            // A pale band, a blue-grey side, a skin-toned face and a wine shadow.
            "pale-band" -> { fill(0xFFE8E4E4); rect(.05f, .3f, .45f, .95f, 0xFF487098); rect(.45f, .4f, 1f, 1f, 0xFFC89080); rect(.6f, .75f, .85f, 1f, 0xFF502030) }
            // Black with an orange moon.
            "orange-moon" -> { fill(0xFF080808); glow(.5f, .55f, .3f, 0xFFE08818, 0xFF904808) }
            // Blue sky, a dark suit and a small red and yellow logo.
            "blue-photo" -> { grad(0f, 0f, 1f, 1f, 0xFF3090C8, 0xFF082038); rect(.3f, .35f, .7f, 1f, 0xFF101418); circle(.5f, .28f, .1f, 0xFFD8A888); rect(.6f, .05f, .95f, .17f, 0xFFE02020); rect(.62f, .08f, .93f, .14f, 0xFFF8D020) }
            else -> error(name)
        }
        grain(5, name.hashCode().toLong())
    }

    private fun video(name: String): Bitmap = Art(320, 180).run {
        when (name) {
            // A dark stage with a pale grey light beam.
            "dark-stage" -> { fill(0xFF0A0C0E); beam(0xFF707878); rect(.46f, .55f, .5f, .9f, 0xFF9A9A9A) }
            // A black and white concert photo.
            "bw-photo" -> { glow(.7f, .3f, .9f, 0xFFF0F0F0, 0xFF202020); rect(.2f, .3f, .5f, 1f, 0xFF383838); circle(.35f, .3f, .14f, 0xFF585858) }
            // A dark red room with lamps and a red figure.
            "dark-red" -> { fill(0xFF280810); circle(.25f, .3f, .06f, 0xFFF0C060); circle(.7f, .25f, .05f, 0xFFF0C060); rect(.35f, .5f, .7f, .8f, 0xFFB01830) }
            // Night-blue stage, figures barely lit.
            "dark-blue" -> { fill(0xFF101818); rect(.15f, .25f, .35f, 1f, 0xFF202838); rect(.45f, .2f, .6f, 1f, 0xFF202838); rect(.7f, .25f, .85f, 1f, 0xFF202838); rect(.47f, .3f, .58f, .4f, 0xFF606890) }
            // Olive tint with a pale face.
            "olive" -> { grad(0f, 0f, 1f, 1f, 0xFF202808, 0xFF686838); glow(.5f, .45f, .3f, 0xFFD0C888, 0xFF686838) }
            else -> error(name)
        }
        grain(5, name.hashCode().toLong())
    }

    private val panels = mapOf("teal-logo" to 0xFF0A4040, "amber-portrait" to 0xFF6A4010, "black-cream" to 0xFF404040, "gold-helmet" to 0xFF403820)

    fun draw(file: String): Bitmap {
        val name = file.removeSuffix(".jpg")
        return when {
            name.startsWith("song-") -> square(name.removePrefix("song-"))
            name.startsWith("spotify-") -> square(name.removePrefix("spotify-"), 300)
            name.startsWith("video-") -> video(name.removePrefix("video-"))
            name.startsWith("atv-") -> name.removePrefix("atv-").let { cover ->
                Art(320, 180).run { fill(panels.getValue(cover)); place(square(cover, 180), 70, 0, 180); grain(3, name.hashCode().toLong()) }
            }
            name == "hq-atv-gold-helmet" -> Art(480, 360).run {
                fill(0xFF000000); rect(0f, .125f, 1f, .875f, panels.getValue("gold-helmet")); place(square("gold-helmet", 270), 105, 45, 270); grain(3, 7)
            }
            name == "hq-letterbox-dark-blue" -> Art(480, 360).run {
                fill(0xFF000000); canvas.drawBitmap(video("dark-blue"), null, Rect(0, 45, 480, 315), Paint(Paint.FILTER_BITMAP_FLAG)); grain(3, 11)
            }
            else -> error(file)
        }
    }
}

/** A loopback image host. While [online] is false every request gets HTTP 503, as a flaky CDN or captive portal would. */
internal class CoverServer : Closeable {
    @Volatile var online = true
    /** Held before answering, like a slow mobile connection. */
    @Volatile var delayMs = 0L
    val requests = AtomicInteger()
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    fun url(name: String) = "http://127.0.0.1:${socket.localPort}/$name"
    init {
        thread(isDaemon = true, name = "cover-server") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { client.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val line = reader.readLine() ?: return@use
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    requests.incrementAndGet()
                    if (delayMs > 0) Thread.sleep(delayMs)
                    val out = s.getOutputStream()
                    val body = if (online) runCatching { CoverFixtures.bytes(line.split(' ')[1].trimStart('/')) }.getOrNull() else null
                    if (body == null) out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    else { out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body) }
                    out.flush()
                } } }
            }
        }
    }
    override fun close() = socket.close()
}
