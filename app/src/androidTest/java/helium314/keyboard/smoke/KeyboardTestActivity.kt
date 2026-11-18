// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smoke

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout

/** A plain text field that hosts the real keyboard for the on-emulator smoke tests. */
class KeyboardTestActivity : Activity() {
    lateinit var field: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            contentDescription = "smoke-field"
        }
        setContentView(LinearLayout(this).apply { addView(field) })
        instance = this
    }

    override fun onResume() {
        super.onResume()
        field.requestFocus()
        field.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0) }
    }

    companion object { @Volatile var instance: KeyboardTestActivity? = null }
}
