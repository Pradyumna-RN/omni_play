package labs.rtensor.music.apple

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import labs.rtensor.music.ui.SavedMusicTheme
import labs.rtensor.music.BuildConfig

internal class AppleNativeSetupActivity : ComponentActivity() {
    private val account by lazy { AppleAccount.get(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { SavedMusicTheme {
            val state by account.state.collectAsStateWithLifecycle()
            var user by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
            var code by remember { mutableStateOf("") }; var importing by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var prepare by remember { mutableIntStateOf(0) }
            LaunchedEffect(prepare) {
                if (BuildConfig.APPLE_RUNTIME_INCLUDED) {
                    importing = true; error = null
                    try { account.prepareBundledRuntime() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = failure.message?.take(240) ?: "Apple Music setup could not finish." }
                    finally { importing = false }
                }
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) lifecycleScope.launch {
                    importing = true; error = null
                    try { account.importRuntime(uri) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = failure.message?.take(240) ?: "The installer could not be imported." }
                    finally { importing = false }
                }
            }
            Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = { account.cancel(); finish() }) { Text("Back") }
                Text("Apple Music", style = MaterialTheme.typography.headlineSmall)
                Text("Advanced Apple Music connection. Requires an active subscription and compatible playback access. Signing in does not guarantee that a song is available as lossless or surround audio.")
                if (state.connected && !state.native) Text("AAC stereo is already connected. This additional setup will keep that connection until a new session is verified.")
                if (!BuildConfig.APPLE_RUNTIME_INCLUDED) {
                Text("Import a compatible Apple Music 3.6.0-beta installer (build 1109, APK or APKM) once. Only verified runtime libraries are kept privately; the installer is never installed or uploaded. Current Apple Music installers use a different runtime.")
                OutlinedButton(enabled = !importing && !state.connecting && !state.needsCode,
                    // Document providers assign several incompatible MIME types to APKM.
                    // Verify the archive contents and pinned library hashes after selection.
                    onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(if (importing) "Verifying runtime…" else if (state.runtimeImported) "Replace compatible runtime" else "Import runtime installer")
                }
                } else if (importing) Text("Preparing Apple Music…")
                else if (!state.runtimeImported && error != null) OutlinedButton(onClick = { prepare++ }) { Text("Retry setup") }
                if (state.runtimeImported && !importing) {
                    if (!state.needsCode) {
                        OutlinedTextField(user, { user = it }, label = { Text("Apple Account email") }, singleLine = true,
                            enabled = !state.connecting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                            enabled = !state.connecting, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        Button(enabled = !state.connecting && user.trim().length >= 3 && password.isNotEmpty(), onClick = {
                            val secret = password; password = ""; code = ""; error = null; account.signInNative(user.trim(), secret)
                        }) { Text(if (state.connecting) "Connecting…" else "Sign in with subscription") }
                    } else {
                        Text("Enter the six-digit Apple verification code from your trusted device or phone.")
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Verification code") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                        Button(enabled = code.matches(Regex("[0-9]{6}")), onClick = { val next = code; code = ""; account.verificationCode(next) }) { Text("Verify") }
                    }
                }
                if (state.connecting || importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.connected && state.native && !state.connecting && !state.needsCode) {
                    Text("Advanced Apple account connected. Matching songs and actual formats will be checked when you play; see Audio source in the player.")
                    Button(onClick = { finish() }) { Text("Done") }
                }
                Text("Availability depends on this runtime, the account and the recording. The player reports the actual codec and decoded channels. The original DMR processes the decoded surround bed; stereo plays unchanged.", style = MaterialTheme.typography.bodySmall)
            } }
            BackHandler { account.cancel(); finish() }
        } }
    }
}
