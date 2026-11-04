package labs.rtensor.music

import android.util.Base64
import androidx.media3.common.StreamKey
import labs.rtensor.music.data.Master
import labs.rtensor.music.data.PlaybackRoute
import labs.rtensor.music.data.Track
import labs.rtensor.music.sources.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TidalManifestTest {
    private val item = JSONObject("""{"id":42,"title":"Afterimage","artist":"Serein","duration":200}""")
    private val song = Track("yt:abcdefghijk", "Afterimage", "Serein", "https://www.youtube.com/watch?v=abcdefghijk", durationMs = 200000)
    private fun response(manifest: String) = JSONObject().put("assetPresentation", "FULL").put("bitDepth", 24)
        .put("manifest", Base64.encodeToString(manifest.toByteArray(), Base64.NO_WRAP))
    private fun mpd(channels: Int = 6, rate: Int = 44100, extra: String = "", segment: String = "audio-\$Number\$.m4s") = """
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT200S" minBufferTime="PT1S">
          <Period><AdaptationSet mimeType="audio/mp4" codecs="flac" audioSamplingRate="$rate">
          <BaseURL>https://cdn.example/music/</BaseURL>$extra
          <AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="$channels"/>
          <Representation id="surround" bandwidth="4608000"><SegmentTemplate timescale="1" duration="5" initialization="init.mp4" media="$segment"/></Representation>
          </AdaptationSet></Period>
        </MPD>
    """.trimIndent()

    @Test fun dashRetainsDiscreteChannelsAndTheConvertibleSampleRate() {
        val result = TidalManifests.decode(item, response(mpd()).put("channels", 2), "https://api.example/").single()
        assertEquals(6, result.metadata.getInt("channels"))
        assertEquals(44100, result.metadata.getInt("sampleRate"))
        assertEquals("audio/flac", result.metadata.getString("mime"))
        assertEquals(listOf(StreamKey(0, 0, 0)), result.streamKeys)
        assertTrue(result.metadata.getString("url").startsWith("data:application/dash+xml;base64,"))
    }

    @Test fun eachDashRepresentationHasAnExplicitSelectionAndDistinctCacheIdentity() {
        val xml = mpd().replace("</AdaptationSet>", """<Representation id="other" bandwidth="3000000" audioSamplingRate="96000"><SegmentTemplate timescale="1" duration="5" initialization="other-init.mp4" media="other-${'$'}Number${'$'}.m4s"/></Representation></AdaptationSet>""")
        val result = TidalManifests.decode(item, response(xml), "https://api.example/")
        assertEquals(2, result.size)
        assertEquals(listOf(StreamKey(0, 0, 1)), result[1].streamKeys)
        val base = ProviderStream("42", MusicProvider.TIDAL, "https://cdn.example/song", song.title, song.artist, 200000, "audio/flac", 44100, 24, 6)
        assertNotEquals(base.copy(streamKeys = result[0].streamKeys).cacheKey, base.copy(streamKeys = result[1].streamKeys).cacheKey)
    }

    @Test fun btsKeepsChannelsAndDoesNotInventStereoForUnknownMetadata() {
        val bts = """{"codecs":"flac","encryptionType":"NONE","channels":8,"urls":["https://cdn.example/track.flac"]}"""
        assertEquals(8, TidalManifests.decode(item, response(bts), "https://api.example/").single().metadata.getInt("channels"))
        val unknown = JSONObject(bts).apply { remove("channels") }
        assertEquals(0, TidalManifests.decode(item, response(unknown.toString()), "https://api.example/").single().metadata.getInt("channels"))
    }

    @Test fun protectedPartialAndUnsafeManifestsCannotBecomeClearPlaybackCandidates() {
        val content = """<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>"""
        val payloads = listOf(response(mpd(extra = content)), response(mpd(segment = "http://cdn.example/audio.m4s")),
            response(mpd()).put("assetPresentation", "PREVIEW"), response(mpd().replace("PT200S", "PT30S")), response("<!DOCTYPE MPD><MPD/>"),
            response("""{"codecs":"flac","encryptionType":"AES","urls":["https://cdn.example/a.flac"]}"""))
        payloads.forEach { assertTrue(TidalManifests.decode(item, it, "https://api.example/").isEmpty()) }
    }

    @Test fun multiplePeriodsAreRejectedInsteadOfPlayingOnlyPartOfTheTrack() {
        val xml = mpd().replace("</MPD>", "<Period start=\"PT100S\"/></MPD>")
        assertTrue(TidalManifests.decode(item, response(xml), "https://api.example/").isEmpty())
    }

    @Test fun externalManifestsKeepTheirOriginWhileInlineRelativeUrlsAreRejected() {
        val xml = mpd().replace("https://cdn.example/music/", "../audio/")
        val origin = "https://cdn.example/manifests/42.mpd?signature=fixture"
        assertTrue(TidalManifests.decode(item, response(xml), origin).isEmpty())
        val external = TidalManifests.decode(item, response(xml), origin, externalManifest = true).single()
        assertEquals(origin, external.metadata.getString("url"))
        assertEquals(6, external.metadata.getInt("channels"))
        assertEquals(listOf(StreamKey(0, 0, 0)), external.streamKeys)
    }

    @Test fun localAndProviderMultichannelRoutingAgreeOnAllSixSupportedRates() {
        for (rate in listOf(44100, 48000, 88200, 96000, 176400, 192000)) {
            val stream = ProviderStream("42", MusicProvider.TIDAL, "https://cdn.example/audio.flac", song.title, song.artist, 200000, "audio/flac", rate, 24, 6)
            assertTrue("provider $rate", stream.eligible)
            val master = stream.asMaster()
            assertEquals("local $rate", master, PlaybackRoute.choose(song, listOf(master)).master)
        }
        val unsupported = Master("content://audio/1", song.title, song.artist, 200000, 6, 32000, 24, "audio/flac", false)
        assertNull(PlaybackRoute.choose(song, listOf(unsupported)).master)
    }
}
