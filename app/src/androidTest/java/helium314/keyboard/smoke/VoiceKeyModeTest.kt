// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smoke

import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.Constants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The big button follows the text in a Settings search field and in a field that never reports a selection. */
class VoiceKeyModeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @After fun close() {
        device.pressBack()
        KeyboardTestActivity.instance?.finish()
    }

    @Test fun settingsSearchFlipsToSendAfterOneLetterAndBackWhenCleared() {
        open(KeyboardTestActivity.FIELD_SETTINGS_SEARCH)
        val ime = ime()
        type(ime, "s")
        Thread.sleep(120)
        println("VOICE_SEARCH desc=${description()} send=${LatinIME.voiceKeyIsSendForTest}")
        assertEquals("Send", description())
        shot("settings-search-send")
        delete(ime)
        Thread.sleep(120)
        println("VOICE_SEARCH_CLEARED desc=${description()} send=${LatinIME.voiceKeyIsSendForTest}")
        assertEquals("Voice input", description())
        shot("settings-search-mic")
    }

    @Test fun silentFieldFlipsWithoutSelectionUpdates() {
        open(KeyboardTestActivity.FIELD_SILENT)
        val ime = ime()
        type(ime, "s")
        Thread.sleep(120)
        val silent = KeyboardTestActivity.instance!!.silent
        println("VOICE_SILENT text=${silent?.contents()} desc=${description()} send=${LatinIME.voiceKeyIsSendForTest}")
        assertEquals("Send", description())
        delete(ime)
        Thread.sleep(120)
        println("VOICE_SILENT_CLEARED text=${silent?.contents()} desc=${description()} send=${LatinIME.voiceKeyIsSendForTest}")
        assertEquals("Voice input", description())
    }

    @Test fun shopeeMultilineShowsSendAndSendsEnterInsteadOfANewline() {
        open(KeyboardTestActivity.FIELD_SHOPEE)
        val ime = ime()
        val field = KeyboardTestActivity.instance!!.shopee ?: throw AssertionError("shopee field missing")
        type(ime, "hi")
        Thread.sleep(120)
        println("VOICE_SHOPEE desc=${description()} send=${LatinIME.voiceKeyIsSendForTest}")
        assertEquals("Send", description())
        assertFalse(description().contains("Return"))
        shot("shopee-send")
        clear(ime, field)
        println("VOICE_SHOPEE_CLEARED desc=${description()} text=${field.text}")
        assertEquals("Voice input", description())
        shot("shopee-mic")
        type(ime, "hi")
        Thread.sleep(80)
        field.events.clear()
        instrumentation.runOnMainSync { ime.onSendKey() }
        Thread.sleep(200)
        val events = field.events.toList()
        println("VOICE_SHOPEE_TAP $events")
        val enter = events.indexOf("key:${KeyEvent.KEYCODE_ENTER}")
        val newline = events.indexOf("newline")
        assertTrue("enter key was not sent: $events", enter >= 0)
        assertTrue("newline was committed before enter: $events", newline == -1 || newline > enter)
    }

    @Test fun unspecifiedSingleLineShowsSendThenTheMic() {
        open(KeyboardTestActivity.FIELD_UNSPECIFIED)
        val ime = ime()
        type(ime, "s")
        Thread.sleep(120)
        println("VOICE_UNSPECIFIED desc=${description()} send=${LatinIME.voiceKeyIsSendForTest}")
        assertEquals("Send", description())
        shot("unspecified-send")
        delete(ime)
        Thread.sleep(120)
        println("VOICE_UNSPECIFIED_CLEARED desc=${description()}")
        assertEquals("Voice input", description())
        shot("unspecified-mic")
    }

    private fun shot(name: String) {
        val remote = "/data/local/tmp/sk-$name.png"
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("screencap -p $remote"),
        ).use { it.readBytes() }
    }

    private fun type(ime: LatinIME, word: String) {
        for (ch in word) {
            instrumentation.runOnMainSync {
                ime.onEvent(Event.createEventForCodePointFromUnknownSource(ch.code))
            }
            Thread.sleep(40)
        }
    }

    private fun clear(ime: LatinIME, field: android.widget.EditText) {
        repeat(8) {
            val empty = java.util.concurrent.atomic.AtomicBoolean(false)
            instrumentation.runOnMainSync { empty.set(field.text.isNullOrEmpty()) }
            if (empty.get()) return
            delete(ime)
            Thread.sleep(40)
        }
    }

    private fun delete(ime: LatinIME) {
        instrumentation.runOnMainSync {
            ime.onEvent(Event.createSoftwareKeypressEvent(
                KeyCode.DELETE, 0, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false,
            ))
        }
    }

    private fun description(): String {
        val pkg = instrumentation.targetContext.packageName
        val key = device.findObject(By.res(pkg, "sk_voice_key"))
            ?: throw AssertionError("voice key missing")
        return key.contentDescription?.toString() ?: ""
    }

    private fun ime(): LatinIME = LatinIME.instanceForTest ?: throw AssertionError("keyboard was not created")

    private fun open(field: String) {
        KeyboardTestActivity.instance?.finish()
        val context = instrumentation.targetContext
        val intent = Intent().apply {
            component = ComponentName(context.packageName, KeyboardTestActivity::class.java.name)
            putExtra(KeyboardTestActivity.EXTRA_FIELD, field)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        instrumentation.startActivitySync(intent)
        val pkg = context.packageName
        val end = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < end) {
            device.findObject(By.desc("smoke-field"))?.click()
            if (device.wait(Until.findObject(By.res(pkg, "sk_voice_key")), 1500) != null
                && LatinIME.instanceForTest != null) return
        }
        throw AssertionError("keyboard did not open for $field")
    }
}
