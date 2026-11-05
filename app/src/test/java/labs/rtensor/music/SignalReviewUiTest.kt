package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.dsp.*
import labs.rtensor.music.data.*
import labs.rtensor.music.playback.AudioProcessingState
import labs.rtensor.music.ui.MusicApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignalReviewUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val current=Track("aaaaaaaaaaa","A long song title in the current recording","A familiar artist", "https://music.youtube.com/watch?v=aaaaaaaaaaa",durationMs=180000)
    private val next=Track("bbbbbbbbbbb","The next recommendation","A familiar artist","https://music.youtube.com/watch?v=bbbbbbbbbbb",durationMs=200000)
    private fun capture(name:String) {compose.runOnIdle {
        val v=compose.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
        File("build/reports/signal-review/$name.png").apply{parentFile!!.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }}
    @Test fun transportIsCentredAndNextSongRemainsVisibleInBothThemes() {
        val s=mutableStateOf(MusicState(current=current,queue=listOf(current,next),queueIndex=0,durationMs=180000,reducedMotion=true,
            routeLabel="YouTube · AAC compressed 48.0 kHz · stereo · unchanged",
            audioProcessing=AudioProcessingState(available=true,report=DownmixReport(48000,listOf(SpeakerPosition.FL,SpeakerPosition.FR),processingEnabled=false,comparisonAvailable=false))))
        compose.setContent{MusicApp(s.value,initialPage="now")}
        for(theme in listOf("light","dark")) {
            compose.runOnIdle{s.value=s.value.copy(theme=theme)}
            val play=compose.onNodeWithText("Play").getUnclippedBoundsInRoot();val seek=compose.onNodeWithContentDescription("Playback position").getUnclippedBoundsInRoot()
            assertEquals("Play must be on the screen centre",(seek.left.value+seek.right.value)/2,(play.left.value+play.right.value)/2,1f)
            assertTrue(compose.onNodeWithContentDescription("Save").getUnclippedBoundsInRoot().top<play.top)
            compose.onNodeWithText(next.title).assertIsDisplayed();capture("player-$theme")
        }
    }
    @Test fun homeShowsIndividualUnheardSongsAndKeepsCollectionsInExplore() {
        val album=FeedEntry("album","Genre playlist","Collection","","playlist","album-browse","")
        val state=MusicState(history=listOf(current),forYou=listOf(next),feed=listOf(FeedSection("Genres",listOf(album))),
            explore=listOf(FeedSection("Collections",listOf(album))),reducedMotion=true)
        var selected:Track?=null;var queued=emptyList<Track>()
        compose.setContent{MusicApp(state,onPlay={t,q->selected=t;queued=q})}
        compose.onNodeWithText("Genre playlist").assertDoesNotExist()
        compose.onNodeWithText("New to your ears").performScrollTo().assertIsDisplayed();capture("home")
        compose.onNodeWithText(next.title).performScrollTo().performClick()
        compose.runOnIdle{assertEquals(next,selected);assertEquals(listOf(next),queued)}
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Explore").performClick()
        compose.onNodeWithText("Genre playlist").assertExists()
    }
    @Test fun disabledHistoryUsesDiscoveryWordingWithoutInventingUnheardClaims() {
        compose.setContent{MusicApp(MusicState(forYou=listOf(next),trackHistory=false,reducedMotion=true))}
        compose.onNodeWithText("Discover more").performScrollTo().assertExists()
        compose.onNodeWithText("0 PLAYS HERE").assertDoesNotExist()
        compose.onNodeWithText("New to your ears").assertDoesNotExist()
    }
}
