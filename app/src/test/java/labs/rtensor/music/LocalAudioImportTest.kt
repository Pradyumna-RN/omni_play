package labs.rtensor.music

import android.net.Uri
import labs.rtensor.music.data.LocalAudioImporter
import labs.rtensor.music.data.LocalAudioMetadata
import labs.rtensor.music.data.Source
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalAudioImportTest {
    @Test fun readableLocalFileSurvivesAnUnsupportedPlatformMetadataCodec() {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "Own recording.eac3").apply { writeBytes(byteArrayOf(0x0b, 0x77, 0, 0)) }
        val track = LocalAudioImporter.read(app, Uri.fromFile(file)) { _, _ -> throw IllegalArgumentException("Unsupported platform codec") }
        assertEquals("Own recording", track.title); assertEquals(Source.LOCAL, track.source)
        assertEquals(Uri.fromFile(file).toString(), track.url); assertEquals(0L, track.durationMs)
    }
    @Test fun anUnreadableFileIsNotSavedAsAPlayableRecording() {
        val app = RuntimeEnvironment.getApplication()
        val missing = File(app.cacheDir, "missing.eac3").apply { delete() }
        assertThrows(FileNotFoundException::class.java) {
            LocalAudioImporter.read(app, Uri.fromFile(missing)) { _, _ -> error("Do not query metadata for an unreadable file") }
        }
    }
    @Test fun supportedMetadataIsKeptWithoutReplacingTheLocalSource() {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "recording.m4a").apply { writeBytes(byteArrayOf(1)) }
        val track = LocalAudioImporter.read(app, Uri.fromFile(file)) { _, _ -> LocalAudioMetadata("Owned title", "Owned artist", 123000) }
        assertEquals("Owned title", track.title); assertEquals("Owned artist", track.artist)
        assertEquals(123000L, track.durationMs); assertEquals(Source.LOCAL, track.source)
    }
    @Test fun thePickerOffersAudioMp4VideoContainersAndUntypedFiles() {
        val types = LocalAudioImporter.PICKER_TYPES.toList()
        assertTrue("audio/*" in types); assertTrue("video/mp4" in types); assertTrue("application/octet-stream" in types)
        assertEquals(types.size, types.distinct().size)
    }
}
