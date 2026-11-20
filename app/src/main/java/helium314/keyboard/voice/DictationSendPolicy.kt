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
     * declared action. The flag is ignored for that action.
     * Flutter and some React Native fields (Revolut, X) return true from performEditorAction and
     * then ignore it. [trustsTrueReturn] is false for Send and Done, so the caller keeps going
     * when the text did not change. The rest of the chain is the enter key, then DPAD_CENTER and
     * NUMPAD_ENTER (FlorisBoard and AOSP use arrow and enter key events when the connection will
     * not honour an editor action), then a newline only in a multiline field.
     */
    fun sendPlan(imeOptions: Int, inputType: Int, editorActionId: Int, hasActionLabel: Boolean): List<SendStep> {
        val declared = imeOptions and EditorInfo.IME_MASK_ACTION
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val clazz = inputType and InputType.TYPE_MASK_CLASS
        val multiline = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (InputTypeUtils.isAnyPasswordInputType(inputType)) {
            if (declared == EditorInfo.IME_ACTION_NONE || declared == EditorInfo.IME_ACTION_UNSPECIFIED)
                return emptyList()
            return listOf(SendStep.action(declared))
        }
        val steps = ArrayList<SendStep>(6)
        if (hasActionLabel && editorActionId != 0) steps.add(SendStep.action(editorActionId))
        // A class of 0 is what some React Native fields report. Treat them as chat, not as a URL bar.
        if (clazz == InputType.TYPE_CLASS_TEXT || clazz == 0) {
            if (variation == InputType.TYPE_TEXT_VARIATION_URI || declared == EditorInfo.IME_ACTION_GO) {
                val action = if (declared == EditorInfo.IME_ACTION_SEARCH) EditorInfo.IME_ACTION_SEARCH
                else EditorInfo.IME_ACTION_GO
                addAction(steps, action)
                addKeyFallbacks(steps, newline = false)
                return steps
            }
            if (declared == EditorInfo.IME_ACTION_SEARCH) {
                addAction(steps, EditorInfo.IME_ACTION_SEARCH)
                addKeyFallbacks(steps, newline = false)
                return steps
            }
            if (clazz == InputType.TYPE_CLASS_TEXT && (
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                    )
            ) {
                val action = if (declared == EditorInfo.IME_ACTION_NONE || declared == EditorInfo.IME_ACTION_UNSPECIFIED)
                    EditorInfo.IME_ACTION_NEXT else declared
                addAction(steps, if (action == EditorInfo.IME_ACTION_DONE) EditorInfo.IME_ACTION_NEXT else action)
                addKeyFallbacks(steps, newline = false)
                return steps
            }
        }
        // Chat, including a field that declared Done. Send first so a Done action, which hides
        // the keyboard, is only a fallback when Send did not change the text.
        addAction(steps, EditorInfo.IME_ACTION_SEND)
        if (declared == EditorInfo.IME_ACTION_DONE) addAction(steps, EditorInfo.IME_ACTION_DONE)
        else if (!multiline && declared != EditorInfo.IME_ACTION_SEND
            && declared != EditorInfo.IME_ACTION_NONE && declared != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            addAction(steps, declared)
        }
        addKeyFallbacks(steps, newline = multiline)
        return steps
    }

    /**
     * A true return is enough for Next, and for a single-line Go or Search (the query stays).
     * Send and Done are not trusted: Flutter's input connection returns true and drops the action.
     */
    fun trustsTrueReturn(actionId: Int, inputType: Int): Boolean {
        if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_PREVIOUS) return true
        if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
            return inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0
        }
        return false
    }

    /**
     * Runs [plan] until a step is accepted. [snapshot] is the field text, or null when the
     * connection will not say. A null snapshot never counts as a change, so the chain continues.
     */
    fun executeSendPlan(
        plan: List<SendStep>,
        inputType: Int,
        snapshot: () -> String?,
        performAction: (Int) -> Boolean,
        sendKey: (Int) -> Boolean,
        commitNewline: () -> Boolean,
    ) {
        for (step in plan) {
            val before = snapshot()
            when {
                step.isEditorAction -> {
                    if (!performAction(step.actionId)) continue
                    if (trustsTrueReturn(step.actionId, inputType)) return
                    if (changed(before, snapshot())) return
                }
                step.isEnter -> {
                    sendKey(KeyEvent.KEYCODE_ENTER)
                    if (changed(before, snapshot())) return
                }
                step.isDpadCenter -> {
                    sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
                    if (changed(before, snapshot())) return
                }
                step.isNumpadEnter -> {
                    sendKey(KeyEvent.KEYCODE_NUMPAD_ENTER)
                    if (changed(before, snapshot())) return
                }
                step.isNewline -> {
                    if (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) {
                        commitNewline()
                        if (changed(before, snapshot())) return
                    }
                }
            }
        }
    }

    private fun changed(before: String?, after: String?): Boolean = before != null && after != null && before != after

    private fun addAction(steps: MutableList<SendStep>, actionId: Int) {
        if (steps.any { it.isEditorAction && it.actionId == actionId }) return
        steps.add(SendStep.action(actionId))
    }

    private fun addKeyFallbacks(steps: MutableList<SendStep>, newline: Boolean) {
        steps.add(SendStep.enter())
        steps.add(SendStep.dpadCenter())
        steps.add(SendStep.numpadEnter())
        if (newline) steps.add(SendStep.newline())
    }

    class SendStep private constructor(val actionId: Int) {
        val isEditorAction: Boolean get() = actionId >= 0
        val isEnter: Boolean get() = actionId == -KeyEvent.KEYCODE_ENTER
        val isDpadCenter: Boolean get() = actionId == -KeyEvent.KEYCODE_DPAD_CENTER
        val isNumpadEnter: Boolean get() = actionId == -KeyEvent.KEYCODE_NUMPAD_ENTER
        val isNewline: Boolean get() = actionId == NEWLINE
        companion object {
            private const val NEWLINE = -2
            fun action(id: Int) = SendStep(id)
            fun enter() = SendStep(-KeyEvent.KEYCODE_ENTER)
            fun dpadCenter() = SendStep(-KeyEvent.KEYCODE_DPAD_CENTER)
            fun numpadEnter() = SendStep(-KeyEvent.KEYCODE_NUMPAD_ENTER)
            fun newline() = SendStep(NEWLINE)
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
