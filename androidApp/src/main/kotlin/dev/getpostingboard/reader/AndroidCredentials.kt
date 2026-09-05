package dev.getpostingboard.reader

import android.content.Context
import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.getpostingboard.reader.data.CredentialStore
import dev.getpostingboard.reader.data.OAuthCredentials
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only ciphertext is persisted; the encryption key is non-exportable in Android Keystore. */
internal class AndroidCredentials(context: Context) : CredentialStore {
    override val persistent = true
    private val preferences = context.getSharedPreferences("posting_board_credentials", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "posting_board_api_key_v1"
    private var cached: String? = readSecret("encrypted")
    private var oauth: OAuthCredentials? = runCatching {
        readSecret("oauth_encrypted")?.let(OAuthCredentials::decodeFromStorage)
    }.getOrNull()

    private fun readSecret(name: String): String? = runCatching {
        preferences.getString(name, null)?.let { encrypted ->
            val parts = encrypted.split(":")
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyStore.getKey(alias, null), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }.getOrNull()

    override fun read(): String? = cached
    override fun readOAuth(): OAuthCredentials? = oauth

    override fun writeOAuth(value: OAuthCredentials?) {
        writeSecret("oauth_encrypted", value?.encodeForStorage())
        oauth = value
    }

    override fun write(key: String?) {
        writeSecret("encrypted", key)
        cached = key
    }

    // Observe commit's result before updating the in-memory credential. KTX edit returns Unit.
    @SuppressLint("UseKtx")
    private fun writeSecret(name: String, key: String?) {
        if (key == null) {
            check(preferences.edit().remove(name).commit())
        } else {
            val secret = keyStore.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            val encrypted = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            check(preferences.edit().putString(name, encrypted).commit())
        }
    }
}
