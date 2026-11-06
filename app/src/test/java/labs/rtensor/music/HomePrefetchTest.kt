package labs.rtensor.music

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** Home warms the cache only for covers it shows, never for collections it leaves to Explore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
class HomePrefetchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @After fun restore() = Coil.reset()

    @Test fun homePrefetchesOnlyTheSongsItShows() {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requested += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("No").body(ByteArray(0).toResponseBody(null)).build()
        }).build()
        Coil.setImageLoader(ImageLoader.Builder(context).okHttpClient(client).build())
        val playlist = FeedEntry("VLmix", "A genre playlist", "", "https://example.test/collection.jpg", "playlist", "VLmix", "")
        val songs = (1..4).map { FeedEntry("yt:s$it", "Song $it", "Artist", "https://example.test/song$it.jpg", "song", "", "song0000000$it") }
        compose.setContent { labs.rtensor.music.ui.MusicApp(MusicState(feed = listOf(FeedSection("Picks", listOf(playlist) + songs)), reducedMotion = true), initialPage = "explore") }
        repeat(40) { Thread.sleep(20); compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }
        assertTrue("the shown songs' covers are fetched: $requested", requested.any { it.contains("song") })
        assertFalse("the collection's cover is not: $requested", requested.any { it.contains("collection") })
    }
}
