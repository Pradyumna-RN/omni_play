package labs.rtensor.music

import labs.rtensor.music.ui.offered
import labs.rtensor.music.updates.AppRelease
import labs.rtensor.music.updates.UpdateState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateBannerTest {
    private val release = AppRelease("9.9.9", "https://example.test/a.apk", "https://example.test/a.sha", "a.apk", 1)

    @Test fun noReleaseMeansNoBanner() {
        assertFalse(UpdateState().offered(null))
    }

    @Test fun aNewReleaseIsOffered() {
        assertTrue(UpdateState(release = release).offered(null))
        assertTrue(UpdateState(release = release).offered("9.9.8"))
    }

    @Test fun laterHidesOnlyThatVersion() {
        assertFalse(UpdateState(release = release).offered("9.9.9"))
    }
}
