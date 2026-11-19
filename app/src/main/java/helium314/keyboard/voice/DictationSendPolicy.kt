// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.utils.InputTypeUtils

/**
 * The big button is a dictation key on an empty field and a send key once the field has text,
 * whether that text was typed or dictated. It never shows the return glyph and it never inserts
 * a newline. Chat fields often set [EditorInfo.IME_FLAG_NO_ENTER_ACTION] and
 * [android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE] together with [EditorInfo.IME_ACTION_SEND];
 * those flags must not turn this button into a return key.
 */
object DictationSendPolicy {
    const val FACE_MIC = "mic"
    const val FACE_SEND = "send"

    /** Paper-plane icon. Not the hooked return arrow. */
    const val SEND_DRAWABLE = "sym_keyboard_send_lxx"
    const val RETURN_DRAWABLE = "sym_keyboard_return_lxx"

    fun face(hasText: Boolean): String = if (hasText) FACE_SEND else FACE_MIC

    fun drawableName(hasText: Boolean): String = if (hasText) SEND_DRAWABLE else "sym_keyboard_voice"

    /**
     * Editor action the big button and dictation perform.
     * Android's text field hides the keyboard on [EditorInfo.IME_ACTION_DONE], and moves focus
     * on next/previous. Chat fields, including ones that declare Done or Go, get Send so the
     * keyboard stays up. Search stays search. The result is never a newline.
     */
    fun actionId(imeOptions: Int, editorActionId: Int, hasActionLabel: Boolean): Int {
        val plan = sendPlan(imeOptions, InputType.TYPE_CLASS_TEXT, editorActionId, hasActionLabel)
        return plan.firstOrNull { it.actionId >= 0 }?.actionId ?: EditorInfo.IME_ACTION_SEND
    }

    /**
     * What a short tap tries, in order.
     * HeliBoard issue 1989: apps set [EditorInfo.IME_FLAG_NO_ENTER_ACTION] and still expect the
     * declared action (Send, Go, Search). The flag is ignored for that action.
     * When the field declares nothing (WhatsApp and X compose often do), try Send and, if the
     * connection returns false, the enter key. That is the fallback other IMEs use, not a newline.
     * A URL bar stays Go and search stays Search. Enter is not used for those, or for email or password.
     */
    fun sendPlan(imeOptions: Int, inputType: Int, editorActionId: Int, hasActionLabel: Boolean): List<SendStep> {
        val declared = imeOptions and EditorInfo.IME_MASK_ACTION
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val clazz = inputType and InputType.TYPE_MASK_CLASS
        if (InputTypeUtils.isAnyPasswordInputType(inputType)) {
            if (declared == EditorInfo.IME_ACTION_NONE || declared == EditorInfo.IME_ACTION_UNSPECIFIED)
                return emptyList()
            return listOf(SendStep.action(declared))
        }
        if (hasActionLabel && editorActionId != 0) return listOf(SendStep.action(editorActionId))
        // A class of 0 is what some React Native fields (X compose and reply) report.
        // They are chat fields, not URL bars, even when the variation bits look unset.
        if (clazz == InputType.TYPE_CLASS_TEXT) {
            if (variation == InputType.TYPE_TEXT_VARIATION_URI || declared == EditorInfo.IME_ACTION_GO) {
                val action = if (declared == EditorInfo.IME_ACTION_SEARCH) EditorInfo.IME_ACTION_SEARCH
                else EditorInfo.IME_ACTION_GO
                return listOf(SendStep.action(action))
            }
            if (declared == EditorInfo.IME_ACTION_SEARCH) return listOf(SendStep.action(EditorInfo.IME_ACTION_SEARCH))
            if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            ) {
                val action = if (declared == EditorInfo.IME_ACTION_NONE || declared == EditorInfo.IME_ACTION_UNSPECIFIED)
                    EditorInfo.IME_ACTION_NEXT else declared
                return listOf(SendStep.action(if (action == EditorInfo.IME_ACTION_DONE) EditorInfo.IME_ACTION_NEXT else action))
            }
        }
        val steps = ArrayList<SendStep>(2)
        steps.add(SendStep.action(EditorInfo.IME_ACTION_SEND))
        // Enter is the fallback only for a chat field. A URL or search field stopped above.
        val noEnter = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val enterIsSend = !noEnter || declared == EditorInfo.IME_ACTION_SEND
                || declared == EditorInfo.IME_ACTION_NONE || declared == EditorInfo.IME_ACTION_UNSPECIFIED
        if (enterIsSend) steps.add(SendStep.enter())
        return steps
    }

    class SendStep private constructor(val actionId: Int) {
        val isEnter: Boolean get() = actionId < 0
        companion object {
            fun action(id: Int) = SendStep(id)
            fun enter() = SendStep(-KeyEvent.KEYCODE_ENTER)
        }
    }

    /**
     * A short tap on the send face performs this action. A hold never does: the finger
     * has to come up inside this window, without the mic having started.
     * Dictation itself does not perform an action. That is what was sending the WhatsApp
     * message and closing the keyboard the moment the button was used.
     */
    const val HOLD_TO_DICTATE_MS = 400L

    enum class Release { TRANSCRIBE, SEND, NONE }

    /** Press-down never sends. An empty field starts the mic immediately. A send face waits. */
    fun pressSends(): Boolean = false

    fun startsMicOnPress(sendFace: Boolean): Boolean = !sendFace

    /**
     * What a finger-up means. A hold, or a mic that is already live, transcribes and does not send.
     * Only a short tap on the send face, still on the key, sends.
     */
    fun onRelease(sendFace: Boolean, heldMs: Long, fingerOnKey: Boolean, micLive: Boolean): Release {
        if (micLive || !sendFace || heldMs >= HOLD_TO_DICTATE_MS) return Release.TRANSCRIBE
        if (fingerOnKey) return Release.SEND
        return Release.NONE
    }

    /**
     * A short tap may perform the editor action. Password fields and a bare URL field do not.
     * A search field performs search. The action is never a newline, and dictation never calls this.
     */
    fun shouldPerformAction(inputType: Int, imeOptions: Int): Boolean {
        if (InputTypeUtils.isAnyPasswordInputType(inputType)) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noSuggestions = inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val autoCorrect = inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0
        if (variation == InputType.TYPE_TEXT_VARIATION_URI
            && action != EditorInfo.IME_ACTION_SEARCH
            && !noSuggestions
            && !autoCorrect
        ) {
            return false
        }
        return true
    }
}
