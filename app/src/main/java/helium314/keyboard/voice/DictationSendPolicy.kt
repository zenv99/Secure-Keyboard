// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.view.inputmethod.EditorInfo

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
     * Editor action the big button performs. A field with no declared action still sends.
     * The result is never "insert a newline".
     */
    fun actionId(imeOptions: Int, editorActionId: Int, hasActionLabel: Boolean): Int {
        if (hasActionLabel) return editorActionId
        val declared = imeOptions and EditorInfo.IME_MASK_ACTION
        return if (declared == EditorInfo.IME_ACTION_NONE || declared == EditorInfo.IME_ACTION_UNSPECIFIED)
            EditorInfo.IME_ACTION_SEND
        else
            declared
    }
}
