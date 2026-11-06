package labs.rtensor.music

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties

/** Settings is split into tabs; tests open the one that holds the control they exercise. */
internal fun ComposeTestRule.settingsTab(label: String) {
    onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
}
