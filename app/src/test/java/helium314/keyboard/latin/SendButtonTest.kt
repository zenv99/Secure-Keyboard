// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.Event
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * After typing, the big button sends. Dictation's send uses the same action.
 * Neither path asks the keyboard to hide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class,
    ShadowFacilitator2::class,
])
class SendButtonTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)

    @BeforeTest fun reset() {
        LatinIME.hideRequestsForTest = 0
        LatinIME.hideWindowsForTest = 0
        ShadowInputMethodService.reset()
    }

    @Test fun typedChatTextSendsAndDoesNotHide() {
        startField(whatsAppOptions(), chatInput())
        type("hello")
        val hides = LatinIME.hideRequestsForTest
        latinIME.onSendKey()
        assertEquals(EditorInfo.IME_ACTION_SEND, ShadowInputMethodService.lastEditorAction)
        assertEquals(hides, LatinIME.hideRequestsForTest)
    }

    @Test fun trueReturnThatLeavesTheTextStillPressesEnter() {
        ShadowInputMethodService.editorActionIgnoresText = true
        startField(whatsAppOptions(), chatInput())
        type("hello")
        latinIME.onSendKey()
        assertEquals(EditorInfo.IME_ACTION_SEND, ShadowInputMethodService.lastEditorAction)
        assertTrue(ShadowInputMethodService.enterPresses >= 1)
        assertStillShown()
    }

    @Test fun ignoredSendFallsBackToEnterOnAWhatsAppField() {
        for (action in listOf(EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_SEND)) {
            ShadowInputMethodService.reset()
            LatinIME.hideRequestsForTest = 0
            ShadowInputMethodService.editorActionHandled = false
            startField(action or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI, chatInput())
            type("hello")
            latinIME.onSendKey()
            assertEquals(EditorInfo.IME_ACTION_SEND, ShadowInputMethodService.lastEditorAction)
            assertEquals(1, ShadowInputMethodService.enterPresses)
            assertStillShown()
        }
    }

    @Test fun urlBarGoesAndSearchSearches() {
        startField(EditorInfo.IME_ACTION_GO, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        type("example.com")
        latinIME.onSendKey()
        assertEquals(EditorInfo.IME_ACTION_GO, ShadowInputMethodService.lastEditorAction)
        assertEquals(0, ShadowInputMethodService.enterPresses)
        assertStillShown()
        ShadowInputMethodService.reset()
        LatinIME.hideRequestsForTest = 0
        startField(EditorInfo.IME_ACTION_SEARCH, InputType.TYPE_CLASS_TEXT)
        type("kittens")
        latinIME.onSendKey()
        assertEquals(EditorInfo.IME_ACTION_SEARCH, ShadowInputMethodService.lastEditorAction)
        assertEquals(0, ShadowInputMethodService.enterPresses)
        assertStillShown()
    }

    @Test fun unknownSelectionDoesNotDropTheWordBeingTyped() {
        val x = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        startField(x, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        type("teh")
        latinIME.onUpdateSelection(3, 3, -1, -1, -1, -1)
        ShadowLooper.idleMainLooper()
        latinIME.onEvent(helium314.keyboard.event.Event.createEventForCodePointFromUnknownSource(' '.code))
        ShadowLooper.idleMainLooper()
        val text = ShadowInputMethodService.text.lowercase()
        assertTrue(text.contains("teh") || text.contains("the"), text)
    }

    @Test fun lostSelectionStillSendsAndDoesNotHide() {
        val x = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        startField(x, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        type("hello")
        latinIME.onUpdateSelection(5, 5, -1, -1, -1, -1)
        ShadowLooper.idleMainLooper()
        latinIME.onSendKey()
        assertEquals(EditorInfo.IME_ACTION_SEND, ShadowInputMethodService.lastEditorAction)
        assertEquals(1, ShadowInputMethodService.editorActionCount)
        assertStillShown()
    }

    @Test fun emptyFieldDoesNotSend() {
        startField(whatsAppOptions(), chatInput())
        latinIME.onSendKey()
        assertNull(ShadowInputMethodService.lastEditorAction)
        assertEquals(0, LatinIME.hideRequestsForTest)
    }

    @Test fun dictationInsertsTextAndDoesNotSendOrHide() {
        startField(whatsAppOptions(), chatInput())
        latinIME.onDictatedText("on my way")
        latinIME.onDictationSessionFinished(true)
        assertEquals("on my way", ShadowInputMethodService.text.trim())
        assertEquals(0, ShadowInputMethodService.editorActionCount)
        assertNull(ShadowInputMethodService.lastEditorAction)
        assertStillShown()
    }

    @Test fun aSecondHoldAppendsAndStillDoesNotSend() {
        startField(whatsAppOptions(), chatInput())
        latinIME.onDictatedText("on my way")
        latinIME.onDictationSessionFinished(true)
        latinIME.onDictatedText("thanks")
        latinIME.onDictationSessionFinished(true)
        assertEquals("on my way thanks", ShadowInputMethodService.text.trim())
        assertEquals(0, ShadowInputMethodService.editorActionCount)
        assertStillShown()
    }

    @Test fun oneShortTapAfterDictationSendsExactlyOnce() {
        startField(whatsAppOptions(), chatInput())
        latinIME.onDictatedText("hello")
        latinIME.onDictationSessionFinished(true)
        latinIME.onSendKey()
        assertEquals(1, ShadowInputMethodService.editorActionCount)
        assertEquals(EditorInfo.IME_ACTION_SEND, ShadowInputMethodService.lastEditorAction)
        assertStillShown()
    }

    @Test fun doneFieldDictationDoesNotSendDone() {
        startField(EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION, chatInput())
        latinIME.onDictatedText("hello")
        latinIME.onDictationSessionFinished(true)
        assertEquals(0, ShadowInputMethodService.editorActionCount)
        latinIME.onSendKey()
        assertEquals(EditorInfo.IME_ACTION_SEND, ShadowInputMethodService.lastEditorAction)
        assertEquals(1, ShadowInputMethodService.editorActionCount)
        assertStillShown()
    }

    @Test fun doneFieldStillSendsSoDictationDoesNotCloseTheKeyboard() {
        startField(EditorInfo.IME_ACTION_DONE, chatInput())
        type("hello")
        val hides = LatinIME.hideRequestsForTest
        latinIME.onSendKey()
        assertEquals(EditorInfo.IME_ACTION_SEND, ShadowInputMethodService.lastEditorAction)
        assertEquals(hides, LatinIME.hideRequestsForTest)
    }

    private fun startField(imeOptions: Int, inputType: Int) {
        ShadowInputMethodService.currentInputType = inputType
        ShadowInputMethodService.currentImeOptions = imeOptions
        val info = EditorInfo()
        info.inputType = inputType
        info.imeOptions = imeOptions
        latinIME.mHandler.onStartInput(info, false)
        latinIME.mHandler.onStartInputView(info, true)
        ShadowLooper.idleMainLooper()
    }

    private fun type(word: String) {
        for (ch in word) {
            latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(ch.code))
            ShadowLooper.idleMainLooper()
        }
    }

    private fun assertStillShown() {
        assertEquals(0, LatinIME.hideRequestsForTest)
        assertEquals(0, LatinIME.hideWindowsForTest)
        assertEquals(true, latinIME.isInputViewShown)
    }

    private fun whatsAppOptions() =
        EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI

    private fun chatInput() =
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
}
