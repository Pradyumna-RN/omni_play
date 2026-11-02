package labs.rtensor.music.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AccountState(val connected: Boolean = false, val connecting: Boolean = false,
    val code: String = "", val verificationUrl: String = "", val error: String? = null)

/** OAuth tokens never enter saved-track JSON, logs, release files, or a server. */
class AccountSession(context: Context, private val keyProvider: () -> SecretKey = ::androidKey) {
    private val prefs = context.getSharedPreferences("youtube_account", Context.MODE_PRIVATE)
    @Synchronized fun load(): String? {
        val stored = prefs.getString("credentials", null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            require(bytes.size > 29 && bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { clear(); null }
    }
    @Synchronized fun save(credentials: String) {
        require(credentials.length in 2..32_768)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val sealed = byteArrayOf(1) + cipher.iv + cipher.doFinal(credentials.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("credentials", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit())
    }
    @Synchronized fun clear() { prefs.edit().clear().commit() }
    companion object {
        private const val ALIAS = "music.youtube.oauth.v1"
        private fun androidKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
}
