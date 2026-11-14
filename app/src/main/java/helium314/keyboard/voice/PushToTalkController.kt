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
    private var pendingWarm = false
    private var lastText = ""
    private var lastTextTime = 0L
    private var awaiting = false // a dictation session is open: only then is incoming text accepted

    private val replyTo = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            AsrService.MSG_FINAL -> {
                val text = message.data.getString(AsrService.KEY_TEXT).orEmpty()
                val isLast = message.data.getBoolean(AsrService.KEY_LAST)
                val now = System.currentTimeMillis()
                val accept = awaiting // stray/duplicate deliveries outside a session are dropped
                if (isLast) awaiting = false
                // never insert the same text twice in a row within a few seconds (duplicate delivery guard)
                if (accept && text.isNotBlank() && !(text == lastText && now - lastTextTime < 10_000)) {
                    lastText = text
                    lastTextTime = now
                    onText(text)
                }
                true
            }
            AsrService.MSG_ERROR -> {
                awaiting = false
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
            if (pendingWarm) { pendingWarm = false; send(AsrService.MSG_WARM) }
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
        if (held) return true // repeated down events must not start a second session
        held = true
        if (service != null) startSession() else bind()
        if (!bound && service == null) Toast.makeText(context, R.string.voice_error_generic, Toast.LENGTH_SHORT).show()
        else Toast.makeText(
            context,
            context.getString(R.string.voice_listening) + " (build " + (helium314.keyboard.latin.BuildConfig.VERSION_CODE - 4300) + ")",
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    /** Mic key went up (or the touch was cancelled). */
    fun onRelease() {
        held = false
        if (sessionStarted) {
            send(AsrService.MSG_STOP)
            Toast.makeText(context, R.string.voice_transcribing, Toast.LENGTH_SHORT).show()
        }
        sessionStarted = false
    }

    /** Bind and load the model in the background when the keyboard opens, so the first press is instant. */
    fun warmUp() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (service != null) send(AsrService.MSG_WARM) else { pendingWarm = true; bind() }
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
            Intent(context, AsrService::class.java), connection,
            // Android only lets a background process use the microphone if the visible client passes its
            // "while-in-use" capability on; without this flag the :asr process records pure silence.
            Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES
        )
    }

    private fun startSession() {
        sessionStarted = send(AsrService.MSG_START)
        awaiting = sessionStarted
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
