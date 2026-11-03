package labs.rtensor.music.sources

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import labs.rtensor.music.data.AccountSession
import org.json.JSONArray

class SourceSettings(context: Context, private val vault: AccountSession = AccountSession(context, "music_source_connections")) {
    private val mutable = MutableStateFlow(load())
    val connections = mutable.asStateFlow()
    private fun load(): List<SourceConnection> = runCatching {
        val a = JSONArray(vault.load() ?: "[]")
        (0 until a.length()).map { SourceConnection.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    @Synchronized fun save(connection: SourceConnection) {
        SourceConnection.validateUrl(connection.url)
        require(connection.name.length in 1..80 && connection.url.length <= 2048 && connection.token.length <= 4096 && !connection.token.any { it == '\r' || it == '\n' }) { "Check the server name, URL and access token." }
        val list = (mutable.value.filterNot { it.id == connection.id } + connection).takeLast(4)
        vault.save(JSONArray().apply { list.forEach { put(it.json()) } }.toString())
        mutable.value = list
    }
    @Synchronized fun remove(id: String) {
        val list = mutable.value.filterNot { it.id == id }
        if (list.isEmpty()) vault.clear() else vault.save(JSONArray().apply { list.forEach { put(it.json()) } }.toString())
        mutable.value = list
    }
}
