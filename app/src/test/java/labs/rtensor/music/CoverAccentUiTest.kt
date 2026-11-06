package labs.rtensor.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import labs.rtensor.music.data.AccentChoice
import labs.rtensor.music.data.ArtworkUrl
import labs.rtensor.music.data.LibraryStore
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track
import labs.rtensor.music.ui.CoverAccent
import labs.rtensor.music.ui.CoverColours
import labs.rtensor.music.ui.MusicApp
import labs.rtensor.music.ui.Paper
import labs.rtensor.music.ui.SignalAccent
import labs.rtensor.music.ui.accentColours
import labs.rtensor.music.ui.accentOnTone
import labs.rtensor.music.ui.playerTones
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
import kotlin.math.abs

/**
 * "From the cover" end to end, as on a phone: nothing is primed. Artwork URLs from every source
 * go through [ArtworkUrl.sized] and the app's Coil pipeline; the CDN is a fake that serves the
 * synthetic covers from [CoverFixtures] for the hosts the app really uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverAccentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Host + artwork id → fixture. Any rendition of the id is served, as the real CDNs do. */
    private val cdn = mapOf(
        "yt3.googleusercontent.com/amber" to "song-amber-portrait.jpg",
        "lh3.googleusercontent.com/teal" to "song-teal-logo.jpg",
        "yt3.googleusercontent.com/gold" to "song-gold-helmet.jpg",
        "yt3.googleusercontent.com/greyscale" to "song-bw-portrait.jpg",
        "yt3.googleusercontent.com/pale" to "song-pale-band.jpg",
        "i.ytimg.com/vi/vid00000001" to "video-dark-red.jpg",
        "i.ytimg.com/vi/atv00000001" to "atv-teal-logo.jpg",
        "i.scdn.co/image/ab67616d0000b273blue" to "spotify-blue-photo.jpg",
        "resources.tidal.com/images/1e/01" to "song-red-numeral.jpg",
    )
    @Volatile private var online = true
    private val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before fun fakeCdn() {
        CoverAccent.reset()
        CoverAccent.clock = { compose.mainClock.currentTime }
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url
            requested += url.toString()
            val key = "${url.host}${url.encodedPath}"
            val name = cdn.entries.firstOrNull { key.startsWith(it.key) }?.value
            val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            if (!online || name == null) response.code(503).message("Unavailable").body(ByteArray(0).toResponseBody(null)).build()
            else response.code(200).message("OK").body(CoverFixtures.bytes(name).toResponseBody("image/jpeg".toMediaType())).build()
        }).build()
        // The app's loader settings, with a disk cache of its own: Coil's default disk cache is
        // one per process and would carry covers from one test into the next.
        Coil.setImageLoader(ImageLoader.Builder(context).okHttpClient(client).respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.25).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("artwork-${System.nanoTime()}")).build() }
            .build())
    }
    @After fun restore() { Coil.reset(); CoverAccent.reset(); CoverAccent.clock = { android.os.SystemClock.elapsedRealtime() } }

    private fun track(id: String, artwork: String, source: Source = Source.YOUTUBE) =
        Track(id, "Song $id", "Artist", if (source == Source.LOCAL) artwork else "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            artwork = artwork, durationMs = 200_000, source = source, spotifyId = if (id.startsWith("spotify:")) id.removePrefix("spotify:") else "")

    /** Lets Coil and the colour work finish, advancing the composition clock as it goes. */
    private fun settle(until: () -> Boolean = { false }) {
        repeat(120) {
            Thread.sleep(20); compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
            if (until()) { repeat(4) { compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }; return }
        }
    }
    private fun snapshot(): Bitmap {
        compose.waitForIdle()
        var image: Bitmap? = null
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        return image!!
    }
    private fun close(a: Int, b: Color, tolerance: Int = 6): Boolean {
        val c = b.toArgb()
        return listOf(16, 8, 0).all { abs((a shr it and 0xFF) - (c shr it and 0xFF)) <= tolerance }
    }
    private fun anyPixel(image: Bitmap, r: Rect, colour: Color, tolerance: Int = 8): Boolean {
        for (x in r.left.toInt() until r.right.toInt()) for (y in r.top.toInt() until r.bottom.toInt())
            if (close(image.getPixel(x, y), colour, tolerance)) return true
        return false
    }
    private fun bounds(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().boundsInWindow
    private fun accentUrl(artwork: String) = ArtworkUrl.sized(artwork, 120)

    /** The Play fill, beside the glyph, on the player. */
    private fun playFill(image: Bitmap): Int {
        val play = bounds(compose.onNodeWithContentDescription("Play").performScrollTo())
        return image.getPixel((play.center.x - 22).toInt(), play.center.y.toInt())
    }

    @Test fun thePlayerTakesTheCoverColoursForSongsFromEverySource() {
        val local = File(context.cacheDir, "art/local-cover.jpg").apply { parentFile!!.mkdirs(); writeBytes(CoverFixtures.bytes("song-orange-moon.jpg")) }
        val content = Uri.parse("content://media/external/audio/albumart/42")
        shadowOf(context.contentResolver).registerInputStreamSupplier(content) { ByteArrayInputStream(CoverFixtures.bytes("atv-amber-portrait.jpg")) }
        val songs = listOf(
            "YouTube Music song" to track("yt:a", "https://yt3.googleusercontent.com/amber=w544-h544-l90-rj"),
            "YouTube Music (lh3)" to track("yt:b", "https://lh3.googleusercontent.com/teal=w120-h120-l90-rj"),
            "YouTube video" to track("yt:c", "https://i.ytimg.com/vi/vid00000001/maxresdefault.jpg"),
            "YouTube art track, signed" to track("yt:d", "https://i.ytimg.com/vi/atv00000001/hqdefault.jpg?sqp=-oaymwEWCJADEOEBIAQqCggAEOADGC0guwJIWg&rs=AMzJL3k"),
            "Spotify match" to track("spotify:e", "https://i.scdn.co/image/ab67616d0000b273blue"),
            "TIDAL" to track("tidal:f", "https://resources.tidal.com/images/1e/01/640x640.jpg"),
            "local file" to track("local:g", local.toURI().toString(), Source.LOCAL),
            "local content" to track("local:h", content.toString(), Source.LOCAL),
        )
        val state = mutableStateOf(MusicState(accent = AccentChoice.COVER, look = labs.rtensor.music.data.LookChoice.ROOM, current = songs[0].second, durationMs = 200_000, reducedMotion = true, theme = "light"))
        assertEquals(AccentChoice.COVER, state.value.accent)
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        for ((label, song) in songs) {
            compose.runOnIdle { state.value = state.value.copy(current = song) }
            val url = accentUrl(song.artwork)
            settle { CoverAccent.cached(url) != null }
            val colours = CoverAccent.cached(url)
            assertNotNull("$label: colours for $url (requested $requested)", colours)
            val tones = playerTones(colours!!.deep)
            val image = snapshot()
            val y = bounds(compose.onNodeWithContentDescription("Playback position")).center.y.toInt()
            assertTrue("$label: the player ground is the cover tone", !close(image.getPixel(4, y), Paper, 20))
            assertTrue("$label: Play is the cover accent", close(playFill(image), accentOnTone(colours.accent, tones.top)))
            image.recycle()
        }
        // Network covers are asked for at the small rendition, not the full-size one.
        assertTrue(requested.contains("https://yt3.googleusercontent.com/amber=w120-h120-l90-rj"))
        assertTrue(requested.contains("https://i.ytimg.com/vi/vid00000001/mqdefault.jpg"))
    }

    @Test fun theDockAndMiniPlayerTakeTheCoverAccent() {
        val song = track("spotify:dock", "https://i.scdn.co/image/ab67616d0000b273blue")
        val state = mutableStateOf(MusicState(accent = AccentChoice.COVER, look = labs.rtensor.music.data.LookChoice.ROOM, current = song, durationMs = 200_000, reducedMotion = true, theme = "light"))
        compose.setContent { MusicApp(state.value) }
        val url = accentUrl(song.artwork)
        settle { CoverAccent.cached(url) != null }
        val colours = CoverAccent.cached(url)!!
        val onDock = accentColours(AccentChoice.COVER, false, colours.accent).onDock
        assertNotEquals(accentColours(AccentChoice.SIGNAL, false, null).onDock, onDock)
        for (theme in listOf("light", "dark")) {
            compose.runOnIdle { state.value = state.value.copy(theme = theme) }
            val image = snapshot()
            val home = bounds(compose.onNodeWithContentDescription("Home"))
            assertTrue("$theme: the chosen tab's lights", anyPixel(image, Rect(home.center.x - 12, home.top + 8, home.center.x + 12, home.top + 22), onDock))
            // The mini player sits right above the tabs; its 8dp bar is at its left edge.
            assertTrue("$theme: the mini player's bar", anyPixel(image, Rect(0f, home.top - 64, 64f, home.top - 4), onDock))
            if (theme == "light") File("build/reports/screenshots/home-cover-synthetic-dock.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    @Test fun aSongStartedOfflineTakesItsColoursWhenTheCoverArrives() {
        online = false
        val song = track("yt:offline", "https://yt3.googleusercontent.com/amber=w544-h544-l90-rj")
        val url = accentUrl(song.artwork)
        val state = mutableStateOf(MusicState(accent = AccentChoice.COVER, look = labs.rtensor.music.data.LookChoice.ROOM, current = song, durationMs = 200_000, reducedMotion = true, theme = "light"))
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        settle { requested.contains(url) }
        settle()
        assertNull("no colours while the cover cannot load", CoverAccent.cached(url))
        assertTrue("Signal meanwhile", close(playFill(snapshot()), SignalAccent))
        // The connection comes back. The player tries again by itself; nothing else changes.
        online = true
        compose.mainClock.advanceTimeBy(CoverAccent.MissMs + 1_000)
        settle { CoverAccent.cached(url) != null }
        val colours = CoverAccent.cached(url)
        assertTrue("colours once the cover loads", colours?.accent?.let { it != Color.Unspecified } == true)
        val tones = playerTones(colours!!.deep)
        assertTrue("Play takes the cover accent", close(playFill(snapshot()), accentOnTone(colours.accent, tones.top)))
    }

    /** Skipping to a song whose cover cannot load keeps the last colour instead of flashing to Signal. */
    @Test fun aCoverThatKeepsFailingKeepsThePreviousColour() {
        val first = track("yt:first", "https://yt3.googleusercontent.com/amber=w544-h544-l90-rj")
        val state = mutableStateOf(MusicState(accent = AccentChoice.COVER, look = labs.rtensor.music.data.LookChoice.ROOM, current = first, durationMs = 200_000, reducedMotion = true, theme = "light"))
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        settle { CoverAccent.cached(accentUrl(first.artwork)) != null }
        val colours = CoverAccent.cached(accentUrl(first.artwork))!!
        val expected = accentOnTone(colours.accent, playerTones(colours.deep).top)
        assertTrue("first cover's colour", close(playFill(snapshot()), expected))
        online = false
        val second = track("yt:second", "https://yt3.googleusercontent.com/gold=w544-h544-l90-rj")
        compose.runOnIdle { state.value = state.value.copy(current = second) }
        settle { requested.contains(accentUrl(second.artwork)) }
        // Through several retries the accent stays the first cover's, not Signal.
        repeat(3) {
            compose.mainClock.advanceTimeBy(CoverAccent.MissMs + 1_000); settle()
            val fill = playFill(snapshot())
            assertFalse("not Signal while retrying", close(fill, SignalAccent))
        }
        assertNull(CoverAccent.cached(accentUrl(second.artwork)))
    }

    @Test fun playerScreenshotsWithSyntheticCovers() {
        val covers = listOf(
            "amber-portrait" to "https://yt3.googleusercontent.com/amber=w544-h544-l90-rj",
            "teal-field-small-logo" to "https://lh3.googleusercontent.com/teal=w544-h544-l90-rj",
            "gold-on-black" to "https://yt3.googleusercontent.com/gold=w544-h544-l90-rj",
            "black-and-white" to "https://yt3.googleusercontent.com/greyscale=w544-h544-l90-rj",
            "pale-band" to "https://yt3.googleusercontent.com/pale=w544-h544-l90-rj",
            "dark-red-video" to "https://i.ytimg.com/vi/vid00000001/maxresdefault.jpg",
            "art-track-thumbnail" to "https://i.ytimg.com/vi/atv00000001/sddefault.jpg",
            "spotify" to "https://i.scdn.co/image/ab67616d0000b273blue",
        )
        val state = mutableStateOf(MusicState(accent = AccentChoice.COVER, look = labs.rtensor.music.data.LookChoice.ROOM, durationMs = 224_000, positionMs = 72_000, reducedMotion = true, theme = "light",
            routeLabel = "YouTube · AAC compressed 48.0 kHz · stereo · unchanged"))
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        for ((name, artwork) in covers) {
            val song = track("fixture:$name", artwork).copy(title = "Afterimage", artist = "Serein")
            compose.runOnIdle { state.value = state.value.copy(current = song, saved = listOf(song)) }
            settle { CoverAccent.cached(accentUrl(artwork)) != null }
            // Let the large cover itself arrive too.
            repeat(30) { Thread.sleep(20); compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }
            val colours: CoverColours = CoverAccent.cached(accentUrl(artwork))!!
            val image = snapshot()
            val y = bounds(compose.onNodeWithContentDescription("Playback position")).center.y.toInt()
            assertTrue("$name ground is a cover tone", !close(image.getPixel(4, y), Paper, 20))
            assertTrue("$name Play fill", close(playFill(image), accentOnTone(colours.accent, playerTones(colours.deep).top)))
            File("build/reports/screenshots/player-cover-synthetic-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    @Test fun anUpgradedInstallShowsBerrySelected() {
        // What 0.3.32 left behind: other settings, but no accent (it had no accent setting).
        val prefs = context.getSharedPreferences("music_library", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("theme", "dark").putBoolean("reduced_motion", true).putBoolean("autoplay", true).commit()
        val store = LibraryStore(context)
        assertEquals(AccentChoice.SIGNAL, store.accent)
        assertNull("reading the default writes nothing", prefs.getString("accent", null))
        val state = mutableStateOf(MusicState(look = labs.rtensor.music.data.LookChoice.ROOM, theme = store.theme, accent = store.accent, reducedMotion = true))
        compose.setContent { MusicApp(state.value, initialPage = "settings", onAccent = { state.value = state.value.copy(accent = it) }) }
        compose.settingsTab("Appearance")
        compose.onNodeWithContentDescription("Berry accent colour").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Cover accent colour").assertIsNotSelected()
        // A value this build does not know (a later build's colour) also means "nobody chose".
        prefs.edit().putString("accent", "teal").commit()
        assertEquals(AccentChoice.SIGNAL, LibraryStore(context).accent)
    }
}
