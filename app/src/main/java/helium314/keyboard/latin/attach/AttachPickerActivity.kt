// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.attach

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import helium314.keyboard.latin.R
import java.io.File

/**
 * Invisible activity that opens a system picker. The keyboard itself cannot show those screens.
 * No storage, camera, or contacts permission is declared: each picker grants one-time access.
 */
class AttachPickerActivity : Activity() {
    private var cameraFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_PHOTO -> startActivityForResult(photoIntent(), REQ_PHOTO)
            MODE_CAMERA -> startCamera()
            MODE_DOCUMENT -> startActivityForResult(documentIntent(), REQ_DOCUMENT)
            MODE_CONTACT -> startActivityForResult(contactIntent(), REQ_CONTACT)
            else -> finish()
        }
    }

    private fun photoIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
        } else {
            Intent(Intent.ACTION_PICK).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            }
        }
    }

    private fun startCamera() {
        val dir = File(cacheDir, "attach").apply { mkdirs() }
        val file = File(dir, "camera-${System.nanoTime()}.jpg")
        cameraFile = file
        val uri = FileProvider.getUriForFile(this, authority(), file)
        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("", uri)
        }
        val handlers = packageManager.queryIntentActivities(capture, 0)
        if (handlers.isEmpty()) {
            Toast.makeText(this, R.string.attach_camera_unavailable, Toast.LENGTH_SHORT).show()
            file.delete()
            finish()
            return
        }
        for (handler in handlers) {
            grantUriPermission(
                handler.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        startActivityForResult(capture, REQ_CAMERA)
    }

    private fun documentIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun contactIntent() = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) {
            cameraFile?.delete()
            finish()
            return
        }
        when (requestCode) {
            REQ_PHOTO, REQ_DOCUMENT -> deliverStream(data?.data, requestCode == REQ_PHOTO)
            REQ_CAMERA -> deliverCamera()
            REQ_CONTACT -> deliverContact(data?.data)
        }
        finish()
    }

    private fun deliverStream(source: Uri?, preferImage: Boolean) {
        if (source == null) return
        val mime = contentResolver.getType(source) ?: if (preferImage) "image/jpeg" else "application/octet-stream"
        val (uri, file) = copyToCache(source, mime)
        AttachBridge.deliver(AttachResult.File(mime, uri, file))
    }

    private fun deliverCamera() {
        val file = cameraFile ?: return
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return
        }
        val uri = FileProvider.getUriForFile(this, authority(), file)
        AttachBridge.deliver(AttachResult.File("image/jpeg", uri, file))
    }

    private fun deliverContact(uri: Uri?) {
        if (uri == null) return
        val line = readContact(uri)
        if (line != null) AttachBridge.deliver(AttachResult.Text(line))
    }

    private fun readContact(uri: Uri): String? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val name = cursor.getString(0)
            val phone = cursor.getString(1)
            return AttachCommit.contactLine(name, phone)
        }
        return null
    }

    private fun copyToCache(source: Uri, mime: String): Pair<Uri, File> {
        val dir = File(cacheDir, "attach").apply { mkdirs() }
        val file = File(dir, "item-${System.nanoTime()}.${extensionFor(mime)}")
        contentResolver.openInputStream(source)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return FileProvider.getUriForFile(this, authority(), file) to file
    }

    private fun authority() = getString(R.string.attach_provider_authority)

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_PHOTO = "photo"
        const val MODE_CAMERA = "camera"
        const val MODE_DOCUMENT = "document"
        const val MODE_CONTACT = "contact"
        private const val REQ_PHOTO = 1
        private const val REQ_CAMERA = 2
        private const val REQ_DOCUMENT = 3
        private const val REQ_CONTACT = 4

        fun extensionFor(mime: String): String = when {
            mime.endsWith("png") -> "png"
            mime.endsWith("webp") -> "webp"
            mime.endsWith("gif") -> "gif"
            mime.endsWith("pdf") -> "pdf"
            mime.endsWith("jpeg") || mime.endsWith("jpg") -> "jpg"
            mime.startsWith("text/") -> "txt"
            else -> "bin"
        }
    }
}

sealed class AttachResult {
    data class File(val mime: String, val uri: Uri, val cacheFile: java.io.File) : AttachResult()
    data class Text(val text: String) : AttachResult()
}

/** Same-process handoff from the picker activity back to the input method. */
object AttachBridge {
    var listener: ((AttachResult) -> Unit)? = null
    fun deliver(result: AttachResult) {
        listener?.invoke(result)
    }
}
