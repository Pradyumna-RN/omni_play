package labs.rtensor.music

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.LookChoice
import labs.rtensor.music.spotify.SpotifyLoginActivity
import labs.rtensor.music.spotify.SpotifyWebRuntime
import labs.rtensor.music.ui.Ink
import labs.rtensor.music.ui.RoomNight
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Sign-in pages follow the saved Look and Theme, so Classic users get Classic there too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignInLookTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<SpotifyLoginActivity>? = null
    @After fun close() {
        scenario?.close(); compose.runOnUiThread { SpotifyWebRuntime.get(context).close() }
        CookieManager.getInstance().removeAllCookies(null)
        context.getSharedPreferences("music_library", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Opens the real Spotify sign-in page and returns the most common colour in its header area. */
    private fun ground(look: LookChoice, theme: String, shot: String): Int {
        context.getSharedPreferences("music_library", Context.MODE_PRIVATE).edit().clear()
            .putString("theme", theme).putString("look", look.wire).commit()
        scenario?.close(); compose.runOnUiThread { SpotifyWebRuntime.get(context).close() }
        scenario = ActivityScenario.launch(Intent(context, SpotifyLoginActivity::class.java))
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        var colour = 0
        scenario!!.onActivity { activity ->
            val view = activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            File("build/reports/screenshots/$shot.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val counts = HashMap<Int, Int>()
            for (y in 0 until 150) for (x in 0 until image.width step 3) counts.merge(image.getPixel(x, y), 1, Int::plus)
            colour = counts.maxBy { it.value }.key
            image.recycle()
        }
        return colour
    }

    @Test fun spotifySignInTakesTheSavedLookAndTheme() {
        assertEquals("Classic dark is ink", Ink.toArgb(), ground(LookChoice.CLASSIC, "dark", "look-classic-dark-sign-in-spotify"))
        assertEquals("Listening room dark is its near-black", RoomNight.toArgb(), ground(LookChoice.ROOM, "dark", "look-room-dark-sign-in-spotify"))
        ground(LookChoice.CLASSIC, "light", "look-classic-light-sign-in-spotify")
        ground(LookChoice.ROOM, "light", "look-room-light-sign-in-spotify")
    }
}
