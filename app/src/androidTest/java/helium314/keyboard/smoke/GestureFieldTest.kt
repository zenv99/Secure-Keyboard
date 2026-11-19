// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smoke

import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.upgradeToolbarPrefs
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.latin.LatinIME
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same gestures on every kind of field: a WhatsApp chat, a URL bar, a page in a browser,
 * search, a plain line, a password, and an email address.
 */
class GestureFieldTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @After fun close() {
        device.pressBack()
        KeyboardTestActivity.instance?.finish()
    }

    @Test fun sendActionMatchesTheField() {
        expect(KeyboardTestActivity.FIELD_WHATSAPP, EditorInfo.IME_ACTION_SEND, "hello")
        expect(KeyboardTestActivity.FIELD_URL, EditorInfo.IME_ACTION_GO, "example.com")
        expect(KeyboardTestActivity.FIELD_SEARCH, EditorInfo.IME_ACTION_SEARCH, "kittens")
        expect(KeyboardTestActivity.FIELD_PLAIN, EditorInfo.IME_ACTION_SEND, "hello")
        expect(KeyboardTestActivity.FIELD_EMAIL, EditorInfo.IME_ACTION_NEXT, "a@b.co")
        expect(KeyboardTestActivity.FIELD_WEB, EditorInfo.IME_ACTION_SEND, "hello")
        expect(KeyboardTestActivity.FIELD_X, EditorInfo.IME_ACTION_SEND, "hello")
        expect(KeyboardTestActivity.FIELD_X_REPLY, EditorInfo.IME_ACTION_SEND, "hello", dropSelection = true)
        expect(KeyboardTestActivity.FIELD_PASSWORD, EditorInfo.IME_ACTION_GO, "secret")
    }

    @Test fun trackpadMovesTheCaretAndDoesNotTypeASpace() {
        open(KeyboardTestActivity.FIELD_WHATSAPP)
        val ime = LatinIME.instanceForTest ?: throw AssertionError("keyboard service was not created")
        instrumentation.runOnMainSync {
            val field = KeyboardTestActivity.instance!!.field
            field.setText("hello\nworld")
            field.setSelection(5, 5)
        }
        val before = text()
        instrumentation.runOnMainSync {
            ime.mKeyboardActionListener.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_ON)
            ime.mKeyboardActionListener.onTouchpadDelta(-80f, 0f, System.nanoTime())
        }
        Thread.sleep(80)
        shot("trackpad")
        instrumentation.runOnMainSync {
            ime.mKeyboardActionListener.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_OFF)
        }
        assertEquals("trackpad typed into the field", before, text())
        val caret = selection()
        assertTrue("caret stayed at $caret", caret in 0..4)
        assertTrue(shown(ime))
    }

    @Test fun webFieldTrackpadAlsoMovesWithoutTyping() {
        open(KeyboardTestActivity.FIELD_WEB)
        val ime = LatinIME.instanceForTest ?: throw AssertionError("keyboard service was not created")
        instrumentation.runOnMainSync {
            val field = KeyboardTestActivity.instance!!.field
            field.setText("hello")
            field.setSelection(5, 5)
        }
        val before = text()
        instrumentation.runOnMainSync {
            ime.mKeyboardActionListener.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_ON)
            ime.mKeyboardActionListener.onTouchpadDelta(-60f, 0f, System.nanoTime())
        }
        Thread.sleep(80)
        instrumentation.runOnMainSync {
            ime.mKeyboardActionListener.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_OFF)
        }
        assertEquals(before, text())
        assertTrue("web caret stayed at ${selection()}", selection() < 5)
    }

    @Test fun plusMenuShowsTheWhatsAppSet() {
        open(KeyboardTestActivity.FIELD_PLAIN)
        val ime = LatinIME.instanceForTest ?: throw AssertionError("keyboard service was not created")
        instrumentation.runOnMainSync {
            val prefs = DeviceProtectedUtils.getSharedPreferences(instrumentation.targetContext)
            prefs.edit().putString(
                Settings.PREF_TOOLBAR_KEYS,
                "SETTINGS:true|UNDO:true|REDO:true|LEFT:true|RIGHT:true|NUMPAD:true",
            ).commit()
            upgradeToolbarPrefs(prefs)
            ime.reloadToolbarForTest()
        }
        val labels = mutableListOf<String>()
        instrumentation.runOnMainSync { labels.addAll(ime.toolbarLabelsForTest()) }
        val expected = listOf(
            "Photos and Video", "Camera", "Location", "Contact", "Document",
            "Emoji", "Clipboard", "Text shortcuts", "Settings",
        )
        for (label in expected) {
            assertTrue("menu is $labels", labels.contains(label))
        }
        assertFalse(labels.contains("Undo"))
        val plus = device.wait(Until.findObject(By.desc("More keys")), 15000)
            ?: throw AssertionError("plus button not shown")
        plus.click()
        Thread.sleep(400)
        shot("plus-menu")
        instrumentation.runOnMainSync { ime.scrollToolbarForTest(2000) }
        Thread.sleep(200)
        shot("plus-menu-scrolled")
    }

    private fun expect(field: String, action: Int, sample: String, dropSelection: Boolean = false) {
        open(field)
        val ime = LatinIME.instanceForTest ?: throw AssertionError("$field: keyboard service was not created")
        instrumentation.runOnMainSync {
            KeyboardTestActivity.instance!!.field.setText(sample)
            KeyboardTestActivity.instance!!.field.setSelection(sample.length, sample.length)
        }
        Thread.sleep(300)
        if (dropSelection) {
            instrumentation.runOnMainSync {
                ime.onUpdateSelection(sample.length, sample.length, -1, -1, -1, -1)
            }
        }
        instrumentation.runOnMainSync { ime.onSendKey() }
        Thread.sleep(200)
        val actions = KeyboardTestActivity.instance!!.editorActions.toList()
        println("GESTURE_FIELD $field actions=$actions shown=${shown(ime)}")
        assertEquals("$field", action, actions.firstOrNull())
        assertTrue("$field hid the keyboard", shown(ime))
        close()
    }

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

    private fun text(): String {
        var value = ""
        instrumentation.runOnMainSync { value = KeyboardTestActivity.instance!!.field.text.toString() }
        return value
    }

    private fun selection(): Int {
        var value = 0
        instrumentation.runOnMainSync { value = KeyboardTestActivity.instance!!.field.selectionStart }
        return value
    }

    private fun shot(name: String) {
        // App-private dirs are not visible to a non-root adb pull on this image.
        // screencap writes a world-readable file under /data/local/tmp.
        val remote = "/data/local/tmp/sk-$name.png"
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("screencap -p $remote"),
        ).use { it.readBytes() }
        println("SHOT $name remote=$remote")
    }

    private fun shown(ime: LatinIME): Boolean {
        var value = false
        instrumentation.runOnMainSync { value = ime.isInputViewShown && KeyboardTestActivity.instance!!.field.hasFocus() }
        return value
    }
}
