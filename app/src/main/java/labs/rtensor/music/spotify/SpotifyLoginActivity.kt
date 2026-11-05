package labs.rtensor.music.spotify

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

/** The listener signs in on Spotify's page. Password fields never cross the app bridge. */
class SpotifyLoginActivity : ComponentActivity() {
    private var runtime: SpotifyWebRuntime? = null
    private var web: android.webkit.WebView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 32, 16, 0) }
        val title = TextView(this).apply { text = "Connect Spotify for recommendations"; textSize = 18f; setPadding(8, 8, 8, 12) }
        layout.addView(title)
        layout.addView(Button(this).apply { text = "Cancel"; setOnClickListener { finish() } })
        setContentView(layout)
        try {
            val engine = SpotifyWebRuntime.get(applicationContext); runtime = engine
            val view = engine.showLogin(); web = view
            (view.parent as? ViewGroup)?.removeView(view)
            layout.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            lifecycleScope.launch {
                while (isActive) {
                    if (engine.canRequest) try {
                        engine.call("profile", org.json.JSONObject())
                        setResult(RESULT_OK); finish(); return@launch
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* The user may still be completing Spotify's own sign-in. */ }
                    delay(1500)
                }
            }
        } catch (_: Exception) { title.text = "Spotify sign-in needs Android System WebView. Update it and try again." }
    }
    override fun onDestroy() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.settings.blockNetworkImage = true }
        web = null
        super.onDestroy()
    }
}
