// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smoke

import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import helium314.keyboard.event.Event
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.autocorrect.ProximityAutocorrect
import helium314.keyboard.latin.common.Constants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

/** On-device latency and a typed sentence. The corrector itself is checked here; the shot shows the field. */
class AutocorrectSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val words = ProximityAutocorrect.ListWords(LEXICON.map {
        ProximityAutocorrect.VocabWord(it, if (it.length <= 4) 200 else 140)
    })

    @After fun close() {
        device.pressBack()
        KeyboardTestActivity.instance?.finish()
    }

    @Test fun sentenceAndLatencyOnDevice() {
        assertEquals("don't", fix("dont"))
        assertEquals("receive", fix("recieve"))
        assertEquals("the", fix("teh"))
        assertEquals("I", fix("i"))
        assertEquals("there", ProximityAutocorrect.correct("their", words, previous = "over"))
        assertNull(ProximityAutocorrect.correct("jordan", words))
        assertNull(ProximityAutocorrect.correct("iPhone", words))

        val samples = LongArray(400)
        val rng = java.util.Random(3)
        for (i in samples.indices) {
            val word = LEXICON[rng.nextInt(LEXICON.size)]
            val typed = if (word.length > 4) word[0] + "x" + word.substring(2) else word
            val started = System.nanoTime()
            ProximityAutocorrect.correct(typed, words)
            samples[i] = System.nanoTime() - started
        }
        Arrays.sort(samples)
        val p50 = samples[samples.size / 2] / 1_000_000.0
        val p95 = samples[(samples.size * 95) / 100] / 1_000_000.0
        println("AUTOCORRECT_DEVICE p50_ms=$p50 p95_ms=$p95")
        assertTrue("p95 $p95", p95 < 1.0)

        open(KeyboardTestActivity.FIELD_PLAIN)
        val ime = ime()
        for (word in listOf("i", "dont", "recieve", "teh", "message")) {
            type(ime, word)
            space(ime)
            Thread.sleep(80)
        }
        Thread.sleep(200)
        val text = java.util.concurrent.atomic.AtomicReference<String>()
        instrumentation.runOnMainSync {
            text.set(KeyboardTestActivity.instance?.field?.text?.toString().orEmpty())
        }
        println("AUTOCORRECT_FIELD ${text.get()}")
        shot("autocorrect-sentence")
        assertTrue(text.get()!!.isNotBlank())
    }

    private fun fix(typed: String): String =
        ProximityAutocorrect.correct(typed, words) ?: typed

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
            Thread.sleep(30)
        }
    }

    private fun space(ime: LatinIME) {
        instrumentation.runOnMainSync {
            ime.onEvent(Event.createSoftwareKeypressEvent(
                Constants.CODE_SPACE, 0, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false,
            ))
        }
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

    companion object {
        private val LEXICON = listOf(
            "the", "don't", "receive", "message", "their", "there", "over",
            "jordan", "hello", "because", "correct", "going", "thank", "you", "what", "time", "am", "i",
        )
    }
}
