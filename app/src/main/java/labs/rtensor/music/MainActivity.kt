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
import androidx.core.content.FileProvider
import android.net.Uri
import android.provider.Settings
import java.io.File
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.GetCredentialException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import labs.rtensor.music.auth.DeviceYouTubeSignIn
import labs.rtensor.music.auth.GoogleAccountSignIn
import androidx.media3.common.util.UnstableApi
import labs.rtensor.music.ui.MusicApp

@UnstableApi
class MainActivity : ComponentActivity() {
    private val model: MusicViewModel by viewModels()
    private var sharedQuery by mutableStateOf<String?>(null)
    private var pendingInstall: String? = null
    private val googleSignIn by lazy { GoogleAccountSignIn(applicationContext) }
    private var googleJob: Job? = null
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingInstall?.let { path ->
            pendingInstall = null
            if (packageManager.canRequestPackageInstalls()) installApk(path)
            else model.updateMessage("Allow updates from Music in Android Settings, then tap Install again.")
        }
    }

    private val youtubeSignIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.cancelAccountConnection()
    }

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
                    model.importLocal(uri)
                }
            }
            MusicApp(
                state = state,
                onQuery = model::setQuery, onSearch = model::search,
                onPlay = model::play, onToggle = model::toggle,
                onPrevious = model::previous, onNext = model::next, onSkipTo = model::skipTo,
                onSeek = model::seek, onSave = model::save,
                onTheme = model::theme, onReducedMotion = model::reducedMotion,
                onOpenLocal = { local.launch(arrayOf("audio/*")) },
                onClearHistory = model::clearHistory, onRetry = model::retry, onDismissError = model::clearError,
                sharedQuery = sharedQuery,
                onShareConsumed = { sharedQuery = null },
                onRefreshFeed = { model.refreshFeed() }, onMoreFeed = { model.refreshFeed(true) },
                onBrowse = model::browse, onRetryCollection = model::retryCollection,
                onConnect = ::connectYouTube, onWebSignIn = { openYouTubeBrowser() },
                onGoogleSignIn = ::chooseGoogleAccount,
                onGoogleSignOut = {
                    model.forgetGoogleProfile()
                    lifecycleScope.launch { try { googleSignIn.clearSelection() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Local sign-out is complete. */ } }
                }, onCancelGoogle = { googleJob?.cancel(); model.googleSelectionEnded() },
                onDisconnect = model::disconnectAccount,
                onCancelConnect = { googleJob?.cancel(); model.cancelAccountConnection() },
                onRefreshExplore = model::refreshExplore,
                onCheckUpdates = model::checkUpdates, onDownloadUpdate = model::downloadUpdate,
                onInstallUpdate = { state.update.apkPath?.let(::requestInstall) },
                onAutoplay = model::autoplay, onTrackHistory = model::trackHistory,
                onRemovePlay = model::removePlay, onRefreshForYou = model::refreshForYou,
            )
        }
    }

    private fun openYouTubeBrowser(deviceUrl: String? = null) {
        model.connectAccount()
        youtubeSignIn.launch(Intent(this, YouTubeLoginActivity::class.java).apply {
            model.state.value.googleProfile?.email?.let { putExtra(YouTubeLoginActivity.LOGIN_HINT, it) }
            deviceUrl?.let { putExtra(YouTubeLoginActivity.DEVICE_LOGIN_URL, it) }
        })
    }
    private suspend fun connectSelectedAccount(email: String) {
        model.connectAccount()
        // Some Google providers disallow web-session tickets for third-party apps.
        // Never treat an identity token as a YouTube session or invent a ticket.
        val url = withTimeoutOrNull(12_000) {
            try { DeviceYouTubeSignIn().loginUrl(this@MainActivity, email) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        openYouTubeBrowser(url)
    }
    private fun connectYouTube() {
        val profile = model.state.value.googleProfile ?: return chooseGoogleAccount()
        if (googleJob?.isActive == true) return
        googleJob = lifecycleScope.launch {
            try { connectSelectedAccount(profile.email) }
            catch (e: CancellationException) { model.cancelAccountConnection(); throw e }
        }
    }
    private fun chooseGoogleAccount() {
        if (googleJob?.isActive == true) return
        model.googleSelecting()
        googleJob = lifecycleScope.launch {
            try {
                val profile = googleSignIn.select(this@MainActivity)
                val changed = model.googleSelected(profile)
                if (changed || !model.state.value.account.connected) connectSelectedAccount(profile.email)
            } catch (_: GetCredentialCancellationException) { model.googleSelectionEnded(); model.cancelAccountConnection() }
            catch (e: CancellationException) { model.googleSelectionEnded(); model.cancelAccountConnection(); throw e }
            catch (_: NoCredentialException) { model.googleSelectionEnded("No Google account is available. Add one in Android Settings, or use YouTube sign-in below.") }
            catch (_: GetCredentialProviderConfigurationException) { model.googleSelectionEnded("Update Google Play services to choose a device account, or use YouTube sign-in below.") }
            catch (error: GetCredentialException) {
                val status = Regex("""\[(\d{1,5})\]""").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                val type = error.type.substringAfterLast('.').takeIf { it.matches(Regex("[A-Za-z_0-9]{1,70}")) } ?: "UNKNOWN"
                model.googleSelectionEnded(if (status == 10) "Google rejected the app registration (10). Check the package name and signing fingerprint in Google Cloud."
                    else "Google account selection failed ($type). Retry, or use YouTube sign-in below.")
            }
            catch (_: Exception) { model.googleSelectionEnded("Google sign-in could not be verified. Check your connection and retry, or use YouTube sign-in below.") }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptShare(intent) }

    private fun acceptShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedQuery = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(400)
        }
    }

    private fun requestInstall(path: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstall = path
            installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } else installApk(path)
    }
    private fun installApk(path: String) {
        runCatching {
            val file = File(path)
            require(file.canonicalFile.parentFile == File(cacheDir, "updates").canonicalFile && file.isFile)
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { model.updateMessage("Android could not open the installer. Retry the download.") }
    }
}
