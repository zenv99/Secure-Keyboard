// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * On-device store for text replacements.
 *
 * The file is AES-GCM ciphertext. The key lives in the Android Keystore and never leaves
 * the phone. Android backup is already disabled for the app, so the file is not copied off
 * the device either. Phrases are never written to the log.
 *
 * Before the first unlock after boot the keystore may be unavailable. In that case the list
 * stays empty and the next call tries again. A file that does not decrypt is renamed aside
 * rather than overwritten with an empty list.
 */
object TextReplacements {
    private const val FILE_NAME = "text_replacements.bin"
    private const val KEY_ALIAS = "securekeyboard_text_replacements"

    @Volatile
    private var entries: List<TextReplacement> = emptyList()
    @Volatile
    private var loaded = false

    @JvmStatic
    fun expand(typed: String?): String? {
        if (typed.isNullOrEmpty() || !loaded) return null
        return TextReplacementRules.lookup(entries, typed)
    }

    fun all(): List<TextReplacement> = entries

    @JvmStatic
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val file = file(context)
            if (!file.exists()) {
                entries = emptyList()
                loaded = true
                return
            }
            val key = try {
                keystoreKey()
            } catch (_: Exception) {
                // Device still locked, or keystore unavailable. Try again later.
                return
            }
            val plain = try {
                TextReplacementCrypto.open(file.readBytes(), key)
            } catch (_: Exception) {
                file.renameTo(File(file.parentFile, "$FILE_NAME.bak"))
                entries = emptyList()
                loaded = true
                return
            }
            val decoded = runCatching { TextReplacementRules.decode(plain.toString(Charsets.UTF_8)) }.getOrNull()
            if (decoded == null) {
                file.renameTo(File(file.parentFile, "$FILE_NAME.bak"))
                entries = emptyList()
            } else {
                entries = decoded
            }
            loaded = true
        }
    }

    /** @return false when the keystore could not store the list. The previous list is kept. */
    fun replaceAll(context: Context, updated: List<TextReplacement>): Boolean {
        val cleaned = updated.map { TextReplacement(it.shortcut.trim(), it.phrase.trim()) }
        for (entry in cleaned) {
            if (TextReplacementRules.validate(entry.shortcut, entry.phrase) != null) return false
        }
        val key = try {
            keystoreKey()
        } catch (_: Exception) {
            return false
        }
        val sealed = TextReplacementCrypto.seal(
            TextReplacementRules.encode(cleaned).toByteArray(Charsets.UTF_8),
            key,
        )
        val dest = file(context)
        val tmp = File(dest.parentFile, "$FILE_NAME.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(dest)) {
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return false
            }
        }
        entries = cleaned
        loaded = true
        return true
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUnlockedDeviceRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
