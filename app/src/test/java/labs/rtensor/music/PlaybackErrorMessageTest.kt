package labs.rtensor.music

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class PlaybackErrorMessageTest {
    private fun error(code: Int) = PlaybackException("test", null, code)

    @Test fun onlyNetworkFailuresSuggestCheckingTheConnection() {
        assertTrue("connection" in playbackErrorMessage(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        for (code in listOf(PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED))
            assertFalse("code $code", "connection" in playbackErrorMessage(error(code)))
    }

    @Test fun decodingAndOutputFailuresAreDistinguished() {
        assertNotEquals(playbackErrorMessage(error(PlaybackException.ERROR_CODE_DECODING_FAILED)),
            playbackErrorMessage(error(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)))
    }
}
