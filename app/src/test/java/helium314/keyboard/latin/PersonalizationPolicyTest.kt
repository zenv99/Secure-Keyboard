// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalizationPolicyTest {
    @Test fun normalTextMayLearn() {
        assertFalse(PersonalizationPolicy.blocksPersonalization(false, false, false))
    }

    @Test fun passwordBlocksLearning() {
        assertTrue(PersonalizationPolicy.blocksPersonalization(true, false, false))
    }

    @Test fun noPersonalizedLearningFlagBlocksLearning() {
        assertTrue(PersonalizationPolicy.blocksPersonalization(false, true, false))
    }

    @Test fun alwaysIncognitoBlocksLearning() {
        assertTrue(PersonalizationPolicy.blocksPersonalization(false, false, true))
    }
}
