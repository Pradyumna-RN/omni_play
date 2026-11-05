package labs.rtensor.music

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.AccentChoice
import labs.rtensor.music.data.LibraryStore
import labs.rtensor.music.data.LookChoice
import labs.rtensor.music.ui.MusicApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings → Appearance → Look: stored beside theme and accent, Listening room unless Classic was chosen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LookSettingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun prefs() = compose.activity.getSharedPreferences("music_library", Context.MODE_PRIVATE)

    @Test fun lookDefaultsToTheListeningRoomAndSurvivesReopeningTheStore() {
        // What 0.3.33 left behind: a theme and an accent, but no look.
        prefs().edit().clear().putString("theme", "dark").putString("accent", "cobalt").commit()
        val store = LibraryStore(compose.activity)
        assertEquals(LookChoice.ROOM, store.look)
        assertNull("reading the default writes nothing", prefs().getString("look", null))
        store.look = LookChoice.CLASSIC
        assertEquals("classic", prefs().getString("look", null))
        assertEquals(LookChoice.CLASSIC, LibraryStore(compose.activity).look)
        LibraryStore(compose.activity).look = LookChoice.SOFT
        assertEquals("soft", prefs().getString("look", null))
        assertEquals(LookChoice.SOFT, LibraryStore(compose.activity).look)
        LibraryStore(compose.activity).look = LookChoice.ROOM
        assertEquals(LookChoice.ROOM, LibraryStore(compose.activity).look)
        // Theme and accent live in the same store and are untouched.
        assertEquals("dark", LibraryStore(compose.activity).theme)
        assertEquals(AccentChoice.COBALT, LibraryStore(compose.activity).accent)
    }

    @Test fun unknownOrRetiredLooksMeanTheListeningRoom() {
        for (value in listOf("signal-0.3.33", "liner", "", "ROOM", "Classic", "SOFT", "0.3.31")) {
            prefs().edit().putString("look", value).commit()
            assertEquals("stored \"$value\"", LookChoice.ROOM, LibraryStore(compose.activity).look)
        }
        assertEquals(LookChoice.ROOM, LookChoice.parse(null))
        assertEquals(LookChoice.ROOM, MusicState().look)
    }

    @Test fun settingsChoosesTheLookAndClassicExplainsTheMissingAccentPicker() {
        val state = mutableStateOf(MusicState(theme = "light", reducedMotion = true, accent = AccentChoice.COBALT))
        val chosen = mutableListOf<LookChoice>()
        compose.setContent { MusicApp(state.value, initialPage = "settings", onLook = { chosen += it; state.value = state.value.copy(look = it) }) }
        val room = compose.onNodeWithContentDescription("Listening room look").performScrollTo()
        room.assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.onNodeWithContentDescription("Classic look").assertIsNotSelected()
        compose.onNodeWithContentDescription("Cobalt accent colour").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Classic look").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(LookChoice.CLASSIC), chosen) }
        compose.onNodeWithContentDescription("Classic look").assertIsSelected()
        compose.onNodeWithContentDescription("Listening room look").assertIsNotSelected()
        // Classic forces the Signal orange, hides the picker and says so.
        compose.onNodeWithText("Accent colour").assertExists()
        compose.onNodeWithText("Classic always uses Signal orange", substring = true).assertExists()
        for (choice in AccentChoice.entries) compose.onNodeWithContentDescription("${choice.label} accent colour").assertDoesNotExist()
        // The page itself takes the Classic look at once: a full-stop headline and the mono caption.
        compose.onNodeWithText("Settings.").assertExists()
        compose.onNodeWithText("SETTINGS").assertExists()
        // Going back keeps the accent chosen before.
        compose.onNodeWithContentDescription("Listening room look").performClick()
        compose.runOnIdle { assertEquals(listOf(LookChoice.CLASSIC, LookChoice.ROOM), chosen) }
        compose.onNodeWithContentDescription("Cobalt accent colour").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Settings.").assertDoesNotExist()
    }
}
