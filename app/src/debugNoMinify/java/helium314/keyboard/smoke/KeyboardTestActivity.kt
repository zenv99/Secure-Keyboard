// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smoke

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout

/** A text field that hosts the real keyboard. The default field is unchanged for the older smoke tests. */
class KeyboardTestActivity : Activity() {
    lateinit var field: EditText
    val editorActions = java.util.Collections.synchronizedList(mutableListOf<Int>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        field = EditText(this).apply {
            when (intent.getStringExtra(EXTRA_FIELD)) {
                FIELD_WHATSAPP -> {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                }
                FIELD_DONE -> {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
                }
                FIELD_URL -> {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    imeOptions = EditorInfo.IME_ACTION_GO
                }
                FIELD_SEARCH -> {
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_SEARCH
                }
                FIELD_PLAIN -> {
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_NONE
                }
                FIELD_PASSWORD -> {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    imeOptions = EditorInfo.IME_ACTION_GO
                }
                FIELD_EMAIL -> {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                }
                FIELD_X, FIELD_X_REPLY -> {
                    // X compose and reply are React Native text fields. Reply also drops the selection.
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                }
                FIELD_WEB -> {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
                }
                else -> inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            contentDescription = "smoke-field"
            // Consume the action. Returning false makes the framework also report action 0,
            // and a Done action would hide the keyboard. The test checks the recorded id.
            setOnEditorActionListener { _, actionId, _ ->
                editorActions.add(actionId)
                true
            }
        }
        setContentView(LinearLayout(this).apply { addView(field) })
        instance = this
    }

    override fun onResume() {
        super.onResume()
        field.requestFocus()
        // The first showSoftInput often runs before the view is served ("is not served") and is ignored.
        field.post { showKeyboard(8) }
    }

    private fun showKeyboard(triesLeft: Int) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) || triesLeft <= 0) return
        field.postDelayed({ showKeyboard(triesLeft - 1) }, 250)
    }

    companion object {
        const val EXTRA_FIELD = "field"
        const val FIELD_WHATSAPP = "whatsapp"
        const val FIELD_DONE = "done"
        const val FIELD_URL = "url"
        const val FIELD_SEARCH = "search"
        const val FIELD_PLAIN = "plain"
        const val FIELD_PASSWORD = "password"
        const val FIELD_EMAIL = "email"
        const val FIELD_WEB = "web"
        const val FIELD_X = "x"
        const val FIELD_X_REPLY = "x-reply"
        @Volatile var instance: KeyboardTestActivity? = null
    }
}
