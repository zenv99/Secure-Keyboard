// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.widget.Toast
import app.securekeyboard.asr.AsrService
import helium314.keyboard.latin.R

/**
 * Hold-to-talk dictation. Press starts recording in the isolated ASR process, release transcribes
 * on device and hands the text to [onText]. Everything stays on the phone.
 */
class PushToTalkController(
    private val context: Context,
    private val onText: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var service: Messenger? = null
    private var bound = false
    private var held = false // finger is currently down on the mic key
    private var sessionStarted = false // MSG_START was sent for the current hold
    private var lastErrorToast = 0L

    private val replyTo = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            AsrService.MSG_FINAL -> {
                val text = message.data.getString(AsrService.KEY_TEXT).orEmpty()
                if (text.isNotBlank()) onText(text)
                true
            }
            AsrService.MSG_ERROR -> {
                held = false
                sessionStarted = false
                showError(message.data.getInt(AsrService.KEY_CODE))
                true
            }
            else -> false
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            if (held) startSession() // finger went down while we were still binding
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // the :asr process died (e.g. out of memory); the next press rebinds
            service = null
            bound = false
            sessionStarted = false
        }
    }

    /** Mic key went down. Returns false if the permission screen was opened instead. */
    fun onPress(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            context.startActivity(
                Intent(context, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        held = true
        if (service != null) startSession() else bind()
        if (!bound && service == null) Toast.makeText(context, R.string.voice_error_generic, Toast.LENGTH_SHORT).show()
        else Toast.makeText(context, R.string.voice_listening, Toast.LENGTH_SHORT).show()
        return true
    }

    /** Mic key went up (or the touch was cancelled). */
    fun onRelease() {
        held = false
        if (sessionStarted) send(AsrService.MSG_STOP)
        sessionStarted = false
    }

    fun release() {
        onRelease()
        if (bound) {
            try { context.unbindService(connection) } catch (_: IllegalArgumentException) { }
        }
        bound = false
        service = null
    }

    private fun bind() {
        if (bound) return
        bound = context.bindService(
            Intent(context, AsrService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    private fun startSession() {
        sessionStarted = send(AsrService.MSG_START)
    }

    private fun send(what: Int): Boolean {
        val target = service ?: return false
        return try {
            target.send(Message.obtain(null, what).also { it.replyTo = replyTo })
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun showError(code: Int) {
        val now = System.currentTimeMillis()
        if (now - lastErrorToast < 2000) return
        lastErrorToast = now
        when (code) {
            AsrService.ERR_NO_PERMISSION ->
                context.startActivity(Intent(context, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> {
                val message = when (code) {
                    AsrService.ERR_NO_MODEL -> R.string.voice_error_no_model
                    AsrService.ERR_MODEL_INTEGRITY -> R.string.voice_error_model_integrity
                    AsrService.ERR_NATIVE -> R.string.voice_error_unsupported
                    AsrService.ERR_SILENT -> R.string.voice_error_silent
                    AsrService.ERR_NO_SPEECH -> R.string.voice_error_no_speech
                    else -> R.string.voice_error_generic
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
