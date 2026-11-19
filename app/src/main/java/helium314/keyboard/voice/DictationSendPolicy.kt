// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.text.InputType
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
        if (hasActionLabel && editorActionId == EditorInfo.IME_ACTION_SEARCH) return editorActionId
        val declared = imeOptions and EditorInfo.IME_MASK_ACTION
        return if (declared == EditorInfo.IME_ACTION_SEARCH) EditorInfo.IME_ACTION_SEARCH
        else EditorInfo.IME_ACTION_SEND
    }

    /**
     * After dictation inserts text, perform the field's editor action.
     * Password fields and a bare URL field do not. A search field performs search.
     * The action is never a newline.
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
