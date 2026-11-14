package app.securekeyboard.asr

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal class AsrException(val code: Int) : Exception()

/**
 * Copies the bundled model out of the APK once, verifying its SHA-256 against the hash that the
 * build recorded next to it. Nothing is ever downloaded: the app has no INTERNET permission.
 */
internal class ModelStore(private val context: Context) {

    /** Blocking. Call from a worker thread. */
    fun ensure(): File {
        val expected = try {
            context.assets.open(ASSET_HASH).bufferedReader().use { it.readText() }.trim().lowercase()
        } catch (_: IOException) {
            throw AsrException(AsrService.ERR_NO_MODEL)
        }
        if (expected.length != 64) throw AsrException(AsrService.ERR_NO_MODEL)

        val dir = File(context.filesDir, "asr").apply { mkdirs() }
        val model = File(dir, "model.bin")
        val marker = File(dir, "model.sha256")
        if (model.isFile && marker.isFile && marker.readText().trim() == expected) return model

        val tmp = File(dir, "model.bin.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            context.assets.open(ASSET_MODEL).use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (_: IOException) {
            tmp.delete()
            throw AsrException(AsrService.ERR_NO_MODEL)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            tmp.delete()
            throw AsrException(AsrService.ERR_MODEL_INTEGRITY)
        }
        model.delete()
        if (!tmp.renameTo(model)) {
            tmp.delete()
            throw AsrException(AsrService.ERR_MODEL_LOAD)
        }
        marker.writeText(expected)
        return model
    }

    private companion object {
        const val ASSET_MODEL = "asr/model.bin"
        const val ASSET_HASH = "asr/model.sha256"
    }
}
