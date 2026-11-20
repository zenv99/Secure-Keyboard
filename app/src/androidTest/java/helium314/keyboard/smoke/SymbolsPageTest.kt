// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smoke

import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.LayoutDirective
import helium314.keyboard.latin.LatinIME
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Screenshots of the iOS 123 page and the #+= page. The big dictation button stays put. */
class SymbolsPageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @After fun close() {
        device.pressBack()
        KeyboardTestActivity.instance?.finish()
    }

    @Test fun symbolsPagesKeepTheDictationButton() {
        open()
        val pkg = instrumentation.targetContext.packageName
        instrumentation.runOnMainSync {
            KeyboardSwitcher.getInstance().toggleLayout(LayoutDirective.Utility.SYMBOLS, 0, null)
        }
        Thread.sleep(400)
        assertNotNull(device.findObject(By.res(pkg, "sk_voice_key")))
        shot("symbols")
        instrumentation.runOnMainSync {
            KeyboardSwitcher.getInstance().toggleLayout(LayoutDirective.Utility.SYMBOLS_SHIFTED, 0, null)
        }
        Thread.sleep(400)
        assertNotNull(device.findObject(By.res(pkg, "sk_voice_key")))
        shot("symbols-shifted")
    }

    private fun open() {
        KeyboardTestActivity.instance?.finish()
        val context = instrumentation.targetContext
        val intent = Intent().apply {
            component = ComponentName(context.packageName, KeyboardTestActivity::class.java.name)
            putExtra(KeyboardTestActivity.EXTRA_FIELD, KeyboardTestActivity.FIELD_PLAIN)
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
        throw AssertionError("keyboard did not open")
    }

    private fun shot(name: String) {
        val remote = "/data/local/tmp/sk-$name.png"
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("screencap -p $remote"),
        ).use { it.readBytes() }
        println("SHOT $name remote=$remote")
    }
}
