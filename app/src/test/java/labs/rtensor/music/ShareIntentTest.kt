package labs.rtensor.music

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareIntentTest {
    private fun share(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    @Test fun aFreshShareBecomesTheSearch() {
        assertEquals("https://youtu.be/jNQXAC9IVRw", MainActivity.sharedQuery(share("https://youtu.be/jNQXAC9IVRw"), restored = false))
        assertEquals(400, MainActivity.sharedQuery(share("x".repeat(900)), restored = false)!!.length)
    }

    @Test fun aRecreatedActivityDoesNotSearchTheSameShareAgain() {
        // Rotation and process restore hand the original SEND intent back with saved state.
        assertNull(MainActivity.sharedQuery(share("Serein"), restored = true))
        // Reopening from Recents replays the launching intent too.
        assertNull(MainActivity.sharedQuery(share("Serein").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), restored = false))
    }

    @Test fun otherIntentsAreNotShares() {
        assertNull(MainActivity.sharedQuery(Intent(Intent.ACTION_MAIN), restored = false))
        assertNull(MainActivity.sharedQuery(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_TEXT, "x"), restored = false))
        assertNull(MainActivity.sharedQuery(null, restored = false))
    }
}
