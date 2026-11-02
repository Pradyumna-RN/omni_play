package labs.rtensor.music

import labs.rtensor.music.data.YouTubeLink
import org.junit.Assert.*
import org.junit.Test

class YouTubeLinkTest {
    @Test fun normalizesSupportedLinkShapesAndDropsTracking() {
        listOf(
            "https://youtu.be/jNQXAC9IVRw?si=tracking",
            "https://music.youtube.com/watch?v=jNQXAC9IVRw&list=RD12345678901",
            "https://www.youtube.com/shorts/jNQXAC9IVRw",
            "https://www.youtube-nocookie.com/embed/jNQXAC9IVRw",
            "jNQXAC9IVRw",
        ).forEach { assertEquals("https://www.youtube.com/watch?v=jNQXAC9IVRw", YouTubeLink.normalize(it)) }
    }

    @Test fun rejectsLookalikeHostsCredentialsAndNonYouTubeResources() {
        listOf(
            "https://youtube.com.attacker.test/watch?v=jNQXAC9IVRw",
            "https://youtube.com@attacker.test/watch?v=jNQXAC9IVRw",
            "https://user:password@youtube.com/watch?v=jNQXAC9IVRw",
            "http://127.0.0.1/watch?v=jNQXAC9IVRw",
            "file:///watch?v=jNQXAC9IVRw",
            "https://youtube.com:444/watch?v=jNQXAC9IVRw",
            "https://youtube.com/watch?v=invalid",
        ).forEach { assertNull(it, YouTubeLink.normalize(it)) }
    }

    @Test fun preservesPlaylistIdentity() {
        assertEquals("https://www.youtube.com/playlist?list=PL1234567890_abc", YouTubeLink.normalize("https://music.youtube.com/playlist?list=PL1234567890_abc&si=tracking"))
    }
}
