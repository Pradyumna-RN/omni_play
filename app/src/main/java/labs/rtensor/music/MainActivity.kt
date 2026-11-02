package labs.rtensor.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import labs.rtensor.music.ui.MusicApp

@UnstableApi
class MainActivity : ComponentActivity() {
    private val model: MusicViewModel by viewModels()
    private var sharedQuery by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptShare(intent)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            var asked by remember { mutableStateOf(false) }
            LaunchedEffect(state.current) {
                if (state.current != null && !asked && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    asked = true
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val local = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    model.openLocal(uri)
                }
            }
            MusicApp(
                state = state,
                onQuery = model::setQuery, onSearch = model::search,
                onPlay = model::play, onToggle = model::toggle,
                onPrevious = model::previous, onNext = model::next,
                onSeek = model::seek, onSave = model::save,
                onTheme = model::theme, onReducedMotion = model::reducedMotion,
                onVolume = model::volume, onOpenLocal = { local.launch(arrayOf("audio/*")) },
                onClearHistory = model::clearHistory, onRetry = model::retry, onDismissError = model::clearError,
                sharedQuery = sharedQuery,
                onShareConsumed = { sharedQuery = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptShare(intent) }

    private fun acceptShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedQuery = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(400)
        }
    }
}
