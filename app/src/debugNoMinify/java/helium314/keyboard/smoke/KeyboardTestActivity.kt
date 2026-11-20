// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smoke

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SearchView

/** A text field that hosts the real keyboard. The default field is unchanged for the older smoke tests. */
class KeyboardTestActivity : Activity() {
    lateinit var field: EditText
    var web: WebView? = null
    var flutter: IgnoringSendField? = null
    var silent: SilentEditor? = null
    @Volatile var webLoaded: Boolean = false
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
                FIELD_PLAIN, FIELD_SINGLE -> {
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_NONE
                }
                FIELD_MULTI -> {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
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
                FIELD_FLUTTER -> {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
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
        val kind = intent.getStringExtra(EXTRA_FIELD)
        if (kind == FIELD_FLUTTER) {
            val ignoring = IgnoringSendField(this).apply {
                inputType = field.inputType
                imeOptions = field.imeOptions
                contentDescription = "smoke-field"
            }
            field = ignoring
            flutter = ignoring
        }
        if (kind == FIELD_SETTINGS_SEARCH) {
            val search = SearchView(this)
            search.isIconified = false
            search.queryHint = "Search settings"
            setContentView(search)
            field = findEditText(search) ?: field
            field.contentDescription = "smoke-field"
            field.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        } else if (kind == FIELD_SILENT) {
            val editor = SilentEditor(this)
            editor.contentDescription = "smoke-field"
            silent = editor
            setContentView(editor)
        } else if (kind == FIELD_WEB_PAGE) {
            web = WebView(this).apply {
                settings.javaScriptEnabled = true
                contentDescription = "smoke-field"
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        webLoaded = true
                    }
                }
                loadDataWithBaseURL(
                    null,
                    """<html><body>
                    <textarea id="box">hello
world</textarea>
                    <input id="line" value="go">
                    <script>
                    window.enters = 0;
                    document.getElementById('line').addEventListener('keydown', function(e) {
                      if (e.key === 'Enter') window.enters++;
                    });
                    document.getElementById('box').addEventListener('keydown', function(e) {
                      if (e.key === 'Enter') window.enters++;
                    });
                    </script>
                    </body></html>""",
                    "text/html",
                    "utf-8",
                    null,
                )
            }
            setContentView(web)
        } else {
            setContentView(LinearLayout(this).apply { addView(field) })
        }
        instance = this
    }

    override fun onResume() {
        super.onResume()
        val target = web ?: silent ?: field
        target.requestFocus()
        // The first showSoftInput often runs before the view is served ("is not served") and is ignored.
        target.post { showKeyboard(8) }
    }

    private fun showKeyboard(triesLeft: Int) {
        val target = web ?: silent ?: field
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT) || triesLeft <= 0) return
        target.postDelayed({ showKeyboard(triesLeft - 1) }, 250)
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
        const val FIELD_SINGLE = "single"
        const val FIELD_MULTI = "multi"
        const val FIELD_FLUTTER = "flutter"
        const val FIELD_WEB_PAGE = "web-page"
        const val FIELD_SETTINGS_SEARCH = "settings-search"
        const val FIELD_SILENT = "silent"
        @Volatile var instance: KeyboardTestActivity? = null
    }
}

/**
 * A Flutter-style field: performEditorAction returns true and does nothing, and setSelection
 * past the text jumps to the end. That is the connection Revolut-like editors present.
 */
class IgnoringSendField(context: Context) : EditText(context) {
    val actions = java.util.Collections.synchronizedList(mutableListOf<Int>())
    val keyDowns = java.util.Collections.synchronizedList(mutableListOf<Int>())
    @Volatile var wrappedSelection: Boolean = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            override fun performEditorAction(editorAction: Int): Boolean {
                actions.add(editorAction)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) keyDowns.add(event.keyCode)
                return super.sendKeyEvent(event)
            }

            override fun setSelection(start: Int, end: Int): Boolean {
                val len = text?.length ?: 0
                if (start < 0 || end < 0 || start > len || end > len) {
                    wrappedSelection = true
                    return super.setSelection(len, len)
                }
                return super.setSelection(start, end)
            }
        }
    }
}

private fun findEditText(view: View): EditText? = when (view) {
    is EditText -> view
    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findEditText(view.getChildAt(it)) }
    else -> null
}

/**
 * An editor that never calls onUpdateSelection and returns null from the cursor reads.
 * The text is only available through getExtractedText, like some search fields.
 */
class SilentEditor(context: Context) : View(context) {
    private val buffer = StringBuilder()
    private var composing = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    fun contents(): String = buffer.toString() + composing

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.initialSelStart = -1
        outAttrs.initialSelEnd = -1
        return object : BaseInputConnection(this, false) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = null
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                // An empty commit ends the composing span. It does not keep that span.
                composing = ""
                if (text.isNotEmpty()) buffer.append(text)
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = text.toString()
                return true
            }

            override fun finishComposingText(): Boolean {
                buffer.append(composing)
                composing = ""
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                    val all = contents()
                    if (all.isNotEmpty()) {
                        buffer.setLength(0)
                        buffer.append(all.dropLast(1))
                        composing = ""
                    }
                    return true
                }
                return false
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val all = contents()
                val keep = (all.length - beforeLength).coerceAtLeast(0)
                buffer.setLength(0)
                buffer.append(all.substring(0, keep))
                composing = ""
                return true
            }

            override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
                return ExtractedText().apply {
                    text = contents()
                    selectionStart = -1
                    selectionEnd = -1
                }
            }
        }
    }
}
