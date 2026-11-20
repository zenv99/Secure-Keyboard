// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smoke

import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.ValueCallback
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.latin.LatinIME
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Send and the space-bar trackpad on a native field, a WebView, and a Flutter-style connection
 * that returns true from performEditorAction and jumps to the end on a bad setSelection.
 */
class SendTrackpadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @After fun close() {
        device.pressBack()
        KeyboardTestActivity.instance?.finish()
    }

    @Test fun nativeSingleAndMultilineSend() {
        open(KeyboardTestActivity.FIELD_SINGLE)
        val ime = ime()
        setText("hello", 5)
        instrumentation.runOnMainSync { ime.onSendKey() }
        Thread.sleep(200)
        val single = KeyboardTestActivity.instance!!.editorActions.toList()
        println("SEND_SINGLE actions=$single text=${text()}")
        assertEquals(EditorInfo.IME_ACTION_SEND, single.firstOrNull())
        assertTrue(shown(ime))
        shot("send-single")
        close()

        open(KeyboardTestActivity.FIELD_MULTI)
        val multi = ime()
        setText("hello", 5)
        instrumentation.runOnMainSync { multi.onSendKey() }
        Thread.sleep(200)
        val actions = KeyboardTestActivity.instance!!.editorActions.toList()
        println("SEND_MULTI actions=$actions text=${text()}")
        assertEquals(EditorInfo.IME_ACTION_SEND, actions.firstOrNull())
        assertTrue(shown(multi))
    }

    @Test fun flutterStyleSendIsNotSwallowed() {
        open(KeyboardTestActivity.FIELD_FLUTTER)
        val ime = ime()
        val flutter = KeyboardTestActivity.instance!!.flutter ?: throw AssertionError("flutter field missing")
        for (ch in "pay") {
            instrumentation.runOnMainSync {
                ime.onEvent(helium314.keyboard.event.Event.createEventForCodePointFromUnknownSource(ch.code))
            }
            Thread.sleep(40)
        }
        instrumentation.runOnMainSync { ime.onSendKey() }
        Thread.sleep(300)
        println("SEND_FLUTTER actions=${flutter.actions} keys=${flutter.keyDowns} text=${flutter.text}")
        assertTrue("no send action, actions=${flutter.actions} text=${flutter.text}", flutter.actions.contains(EditorInfo.IME_ACTION_SEND))
        val moved = flutter.keyDowns.any {
            it == KeyEvent.KEYCODE_ENTER || it == KeyEvent.KEYCODE_NUMPAD_ENTER || it == KeyEvent.KEYCODE_DPAD_CENTER
        } || flutter.text.toString().contains("\n")
        assertTrue("ignored action was not followed by a key, text=${flutter.text}", moved)
        assertTrue(shown(ime))
        shot("send-flutter")
    }

    @Test fun trackpadMovesLeftRightUpDownAndDoesNotWrap() {
        open(KeyboardTestActivity.FIELD_MULTI)
        val ime = ime()
        setText("abcdef", 3)
        drag(ime, dx = -500f, dy = 0f)
        val afterLeft = selection()
        println("TRACKPAD_LEFT from=3 to=$afterLeft")
        assertTrue("left stayed or moved forward: $afterLeft", afterLeft < 3)
        assertTrue("left wrapped to the end: $afterLeft", afterLeft < 6)
        shot("trackpad-left")

        setText("abcdef", 0)
        drag(ime, dx = -500f, dy = 0f)
        println("TRACKPAD_CLAMP_START ${selection()}")
        assertEquals(0, selection())

        setText("abcdef", 6)
        drag(ime, dx = 500f, dy = 0f)
        println("TRACKPAD_CLAMP_END ${selection()}")
        assertEquals(6, selection())

        setText("abcdef", 2)
        drag(ime, dx = 500f, dy = 0f)
        val afterRight = selection()
        println("TRACKPAD_RIGHT from=2 to=$afterRight")
        assertTrue("right did not move: $afterRight", afterRight > 2)
        assertTrue(afterRight <= 6)
        shot("trackpad-right")

        setText("hello\nworld", 1)
        drag(ime, dx = 0f, dy = 500f)
        val afterDown = selection()
        println("TRACKPAD_DOWN from=1 to=$afterDown")
        assertTrue("down stayed on the first line: $afterDown", afterDown > "hello\n".length)
        shot("trackpad-down")

        setText("hello\nworld", 8)
        drag(ime, dx = 0f, dy = -500f)
        val afterUp = selection()
        println("TRACKPAD_UP from=8 to=$afterUp")
        assertTrue("up stayed on the second line: $afterUp", afterUp < "hello\n".length)
        shot("trackpad-up")
    }

    @Test fun flutterTrackpadDoesNotJumpToTheEnd() {
        open(KeyboardTestActivity.FIELD_FLUTTER)
        val ime = ime()
        val flutter = KeyboardTestActivity.instance!!.flutter ?: throw AssertionError("flutter field missing")
        instrumentation.runOnMainSync {
            flutter.setText("abcdef")
            flutter.setSelection(0)
        }
        drag(ime, dx = -500f, dy = 0f)
        println("TRACKPAD_FLUTTER_START sel=${flutter.selectionStart} wrapped=${flutter.wrappedSelection}")
        assertEquals(0, flutter.selectionStart)
        assertFalse(flutter.wrappedSelection)

        instrumentation.runOnMainSync { flutter.setSelection(3) }
        drag(ime, dx = -200f, dy = 0f)
        println("TRACKPAD_FLUTTER_LEFT sel=${flutter.selectionStart} wrapped=${flutter.wrappedSelection}")
        assertTrue(flutter.selectionStart < 3)
        assertFalse(flutter.wrappedSelection)
    }

    @Test fun webViewTextareaAndInput() {
        open(KeyboardTestActivity.FIELD_WEB_PAGE)
        val activity = KeyboardTestActivity.instance!!
        val web = activity.web ?: throw AssertionError("webview missing")
        val loaded = System.currentTimeMillis() + 15000
        while (!activity.webLoaded && System.currentTimeMillis() < loaded) Thread.sleep(100)
        assertTrue("webview did not load", activity.webLoaded)
        instrumentation.runOnMainSync {
            web.evaluateJavascript("document.getElementById('box').focus();", null)
            web.requestFocus()
        }
        Thread.sleep(800)
        val ime = LatinIME.instanceForTest
        assertNotNull(ime)
        instrumentation.runOnMainSync { ime!!.onSendKey() }
        Thread.sleep(400)
        val enters = js(web, "String(window.enters)")
        val box = js(web, "document.getElementById('box').value")
        println("SEND_WEB enters=$enters box=$box")
        shot("webview-field")
        assertTrue(
            "webview ignored both the action and enter, enters=$enters box=$box",
            enters != "0" && enters != "\"0\"" || (box != null && box.contains("\\n")),
        )
    }

    private fun drag(ime: LatinIME, dx: Float, dy: Float) {
        instrumentation.runOnMainSync {
            ime.mKeyboardActionListener.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_ON)
            ime.mKeyboardActionListener.onTouchpadDelta(dx, dy, System.nanoTime())
        }
        Thread.sleep(200)
        instrumentation.runOnMainSync {
            ime.mKeyboardActionListener.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_OFF)
        }
        Thread.sleep(80)
    }

    private fun ime(): LatinIME = LatinIME.instanceForTest ?: throw AssertionError("keyboard service was not created")

    private fun setText(value: String, cursor: Int) {
        instrumentation.runOnMainSync {
            val field = KeyboardTestActivity.instance!!.field
            field.setText(value)
            field.setSelection(cursor)
        }
        Thread.sleep(100)
    }

    private fun selection(): Int {
        var value = 0
        instrumentation.runOnMainSync { value = KeyboardTestActivity.instance!!.field.selectionStart }
        return value
    }

    private fun text(): String {
        var value = ""
        instrumentation.runOnMainSync { value = KeyboardTestActivity.instance!!.field.text.toString() }
        return value
    }

    private fun shown(ime: LatinIME): Boolean {
        var value = false
        instrumentation.runOnMainSync {
            value = ime.isInputViewShown && (KeyboardTestActivity.instance!!.web?.hasFocus() == true
                || KeyboardTestActivity.instance!!.field.hasFocus())
        }
        return value
    }

    private fun js(web: android.webkit.WebView, script: String): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync {
            web.evaluateJavascript(script, ValueCallback { value ->
                result.set(value)
                latch.countDown()
            })
        }
        latch.await(3, TimeUnit.SECONDS)
        return result.get()
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
        if (field == KeyboardTestActivity.FIELD_WEB_PAGE) return
        val pkg = context.packageName
        val end = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < end) {
            device.findObject(By.desc("smoke-field"))?.click()
            if (device.wait(Until.findObject(By.res(pkg, "sk_voice_key")), 1500) != null
                && LatinIME.instanceForTest != null) return
        }
        throw AssertionError("keyboard did not open for $field")
    }

    private fun shot(name: String) {
        val remote = "/data/local/tmp/sk-$name.png"
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("screencap -p $remote"),
        ).use { it.readBytes() }
    }
}
