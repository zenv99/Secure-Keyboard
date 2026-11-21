// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.glide

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.utils.AutoCorrectionUtils
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Word to emoji comes from the bundled Unicode CLDR English annotations
 * (Unicode License), via emoji_words.tsv. A tap inserts the emoji.
 * Space must not commit it: the suggestion is not an autocorrection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EmojiWordsTest {
    @Test fun commonWordsOfferTheIosEmojiAndDoNotAutocorrect() {
        EmojiWords.init(ApplicationProvider.getApplicationContext())
        assertEquals("🤗", EmojiWords.forWord("hug").first())
        assertTrue(EmojiWords.forWord("love").any { it == "❤" || it == "❤️" })
        assertTrue(EmojiWords.forWord("laugh").contains("😂"))
        assertEquals("😊", EmojiWords.forWord("smile").first())
        assertEquals("🔥", EmojiWords.forWord("fire").first())
        assertTrue(EmojiWords.forWord("thumbs").contains("👍"))
        val hug = SuggestedWordInfo(
            "🤗", "", 900_000, SuggestedWordInfo.KIND_COMPLETION,
            helium314.keyboard.latin.dictionary.Dictionary.DICTIONARY_HARDCODED,
            SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE,
        )
        assertFalse(hug.isAppropriateForAutoCorrection)
        assertFalse(AutoCorrectionUtils.suggestionExceedsThreshold(hug, "hug", 0.15f))
    }
}
