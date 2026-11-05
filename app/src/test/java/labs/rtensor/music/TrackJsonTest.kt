package labs.rtensor.music

import labs.rtensor.music.data.Track
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackJsonTest {
    private fun json(spotifyId: String) = JSONObject().put("id", "x").put("title", "T").put("url", "u").put("spotifyId", spotifyId)

    @Test fun onlyWellFormedSpotifyIdsSurviveReading() {
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", Track.fromJson(json("4uLU6hMCjMI75M1A2tKUQC")).spotifyId)
        for (bad in listOf("short", "4uLU6hMCjMI75M1A2tKUQC1", "4uLU6hMCjMI75M1A2tKUQ!", "")) assertEquals(bad, "", Track.fromJson(json(bad)).spotifyId)
        val track = Track("a", "b", "c", "d", spotifyId = "0123456789abcdefABCDEF")
        assertEquals(track.spotifyId, Track.fromJson(track.json()).spotifyId)
    }
}
