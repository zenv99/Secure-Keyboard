// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM envelope for the text-replacement file. The key comes from the Android Keystore
 * on device. This class itself only does the cipher, so it can be tested without a phone.
 */
object TextReplacementCrypto {
    private val MAGIC = "SKTR1".toByteArray(Charsets.US_ASCII)
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun seal(plain: ByteArray, key: SecretKey): ByteArray {
        val iv = ByteArray(IV_LEN)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(plain)
        return MAGIC + iv + body
    }

    fun open(blob: ByteArray, key: SecretKey): ByteArray {
        if (blob.size < MAGIC.size + IV_LEN + TAG_BITS / 8) throw IllegalArgumentException("short")
        if (!blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw IllegalArgumentException("magic")
        val iv = blob.copyOfRange(MAGIC.size, MAGIC.size + IV_LEN)
        val body = blob.copyOfRange(MAGIC.size + IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(body)
    }
}
