package labs.rtensor.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.LibraryStore
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clear() { context.getSharedPreferences("music_library", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun roundTripsSavedCatalogueItemsAndLocalUrisInOrder() {
        val items = listOf(
            Track("yt:jNQXAC9IVRw", "A recording", "An artist", "https://www.youtube.com/watch?v=jNQXAC9IVRw"),
            Track("local:1", "Local song", "On device", "content://audio/document/1", source = Source.LOCAL),
        )
        LibraryStore(context).writeTracks("saved", items)
        assertEquals(items, LibraryStore(context).tracks("saved"))
        val raw = context.getSharedPreferences("music_library", Context.MODE_PRIVATE).getString("saved", "")!!
        assertFalse(raw.contains("googlevideo.com"))
    }

    @Test fun malformedEntryDoesNotDiscardTheRestOfTheLibrary() {
        val good = Track("1", "Still here", "Artist", "https://www.youtube.com/watch?v=jNQXAC9IVRw")
        context.getSharedPreferences("music_library", Context.MODE_PRIVATE).edit().putString("saved", "[{\"invalid\":true},${good.json()}]").commit()
        assertEquals(listOf(good), LibraryStore(context).tracks("saved"))
    }

    @Test fun savedRecordingsDoNotSilentlyDisappearWhenTheLibraryPassesOneHundredItems() {
        val items = (1..125).map { Track("local:$it", "Recording $it", "Me", "content://audio/document/$it", source = Source.LOCAL) }
        LibraryStore(context).writeTracks("saved", items)
        assertEquals(items, LibraryStore(context).tracks("saved"))
    }
}
