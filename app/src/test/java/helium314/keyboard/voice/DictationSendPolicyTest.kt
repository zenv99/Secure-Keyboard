// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.utils.InputTypeUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DictationSendPolicyTest {
    @Test fun pressNeverSendsAndOnlyAnEmptyFieldStartsTheMic() {
        assertEquals(false, DictationSendPolicy.pressSends())
        assertEquals(true, DictationSendPolicy.startsMicOnPress(sendFace = false))
        assertEquals(false, DictationSendPolicy.startsMicOnPress(sendFace = true))
    }

    @Test fun releaseOnAnEmptyFieldTranscribesAndDoesNotSend() {
        assertEquals(DictationSendPolicy.Release.TRANSCRIBE, DictationSendPolicy.onRelease(false, 30, true, true))
        assertEquals(DictationSendPolicy.Release.TRANSCRIBE, DictationSendPolicy.onRelease(false, 900, true, true))
    }

    @Test fun shortTapOnTheSendFaceSendsOnce() {
        assertEquals(
            DictationSendPolicy.Release.SEND,
            DictationSendPolicy.onRelease(true, DictationSendPolicy.HOLD_TO_DICTATE_MS - 1, true, false),
        )
    }

    @Test fun aHoldOnTheSendFaceDictatesAndNeverSends() {
        assertEquals(
            DictationSendPolicy.Release.TRANSCRIBE,
            DictationSendPolicy.onRelease(true, DictationSendPolicy.HOLD_TO_DICTATE_MS, true, true),
        )
        assertEquals(
            DictationSendPolicy.Release.TRANSCRIBE,
            DictationSendPolicy.onRelease(true, DictationSendPolicy.HOLD_TO_DICTATE_MS, false, false),
        )
    }

    @Test fun aTapThatSlidesOffTheSendFaceDoesNothing() {
        assertEquals(DictationSendPolicy.Release.NONE, DictationSendPolicy.onRelease(true, 80, false, false))
    }

    @Test fun emptyFieldIsTheMic() {
        assertEquals(DictationSendPolicy.FACE_MIC, DictationSendPolicy.face(hasText = false))
        assertEquals("sym_keyboard_voice", DictationSendPolicy.drawableName(hasText = false))
    }

    @Test fun typedTextIsSend() {
        assertSendFace()
    }

    @Test fun textFromDictationIsSend() {
        // Dictation leaves the field non-empty, which is the same signal as typing.
        assertSendFace()
    }

    @Test fun deletingEverythingReturnsToTheMic() {
        assertEquals(DictationSendPolicy.FACE_MIC, DictationSendPolicy.face(hasText = false))
    }

    @Test fun sendIconIsNeverTheReturnArrow() {
        assertNotEquals(DictationSendPolicy.RETURN_DRAWABLE, DictationSendPolicy.drawableName(true))
        assertNotEquals(DictationSendPolicy.RETURN_DRAWABLE, DictationSendPolicy.drawableName(false))
        assertTrue(DictationSendPolicy.SEND_DRAWABLE.contains("send"))
    }

    @Test fun multilineChatWithActionNoneStillSends() {
        val options = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertEquals(EditorInfo.IME_ACTION_SEND, DictationSendPolicy.actionId(options, 0, false))
        assertEquals(DictationSendPolicy.FACE_SEND, DictationSendPolicy.face(true))
    }

    @Test fun declaredSendSurvivesTheNoEnterFlag() {
        val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        val info = editor(options, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertEquals(EditorInfo.IME_ACTION_SEND, DictationSendPolicy.actionId(info.imeOptions, info.actionId, false))
        // The keyboard's own enter key follows the declared action too, so a chat field
        // does not draw a return arrow just because the app also set the no-enter flag.
        assertEquals(EditorInfo.IME_ACTION_SEND, InputTypeUtils.getImeOptionsActionIdFromEditorInfo(info))
    }

    @Test fun unspecifiedMultilineDoesNotBecomeANewlineFromThisButton() {
        val options = EditorInfo.IME_ACTION_UNSPECIFIED or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertEquals(EditorInfo.IME_ACTION_SEND, DictationSendPolicy.actionId(options, 0, false))
    }

    @Test fun searchFieldKeepsTheAppsAction() {
        val options = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertEquals(EditorInfo.IME_ACTION_SEARCH, DictationSendPolicy.actionId(options, 0, false))
    }

    @Test fun doneAndGoBecomeSendSoTheKeyboardStaysUp() {
        // Android's text field hides the IME on Done. Go and Next move focus. The big button uses Send.
        assertEquals(EditorInfo.IME_ACTION_SEND, DictationSendPolicy.actionId(EditorInfo.IME_ACTION_DONE, 0, false))
        assertEquals(EditorInfo.IME_ACTION_SEND, DictationSendPolicy.actionId(EditorInfo.IME_ACTION_GO, 0, false))
        assertEquals(EditorInfo.IME_ACTION_SEND, DictationSendPolicy.actionId(EditorInfo.IME_ACTION_NEXT, 0, false))
        assertEquals(EditorInfo.IME_ACTION_SEARCH, DictationSendPolicy.actionId(EditorInfo.IME_ACTION_SEARCH, 0, true))
    }

    @Test fun whatsAppStyleFieldSendsAndDoesNotReturnEnter() {
        val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        val input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertTrue(DictationSendPolicy.shouldPerformAction(input, options))
        val action = DictationSendPolicy.actionId(options, 0, false)
        assertEquals(EditorInfo.IME_ACTION_SEND, action)
        assertNotEquals(66, action) // KEYCODE_ENTER is not an editor action this button performs
    }

    @Test fun passwordAndBareUrlDoNotAutoSend() {
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertEquals(false, DictationSendPolicy.shouldPerformAction(password, EditorInfo.IME_ACTION_DONE))
        val url = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        assertEquals(false, DictationSendPolicy.shouldPerformAction(url, EditorInfo.IME_ACTION_GO))
    }

    @Test fun searchPerformsSearch() {
        val input = InputType.TYPE_CLASS_TEXT
        val options = EditorInfo.IME_ACTION_SEARCH
        assertTrue(DictationSendPolicy.shouldPerformAction(input, options))
        assertEquals(EditorInfo.IME_ACTION_SEARCH, DictationSendPolicy.actionId(options, 0, false))
    }

    @Test fun aPlainNoteFieldStillUsesNewlineOnTheEnterKey() {
        val info = editor(EditorInfo.IME_ACTION_NONE, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertEquals(EditorInfo.IME_ACTION_NONE, InputTypeUtils.getImeOptionsActionIdFromEditorInfo(info))
    }

    private fun assertSendFace() {
        assertEquals(DictationSendPolicy.FACE_SEND, DictationSendPolicy.face(hasText = true))
        assertEquals(DictationSendPolicy.SEND_DRAWABLE, DictationSendPolicy.drawableName(hasText = true))
    }

    private fun editor(imeOptions: Int, inputType: Int) = EditorInfo().apply {
        this.imeOptions = imeOptions
        this.inputType = inputType
    }
}
