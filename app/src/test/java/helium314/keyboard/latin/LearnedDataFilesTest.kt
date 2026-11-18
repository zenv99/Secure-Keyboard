// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearnedDataFilesTest {
    @Test fun wipeRemovesHistoryFilesAndLeavesTheMainDictionary() {
        val dir = Files.createTempDirectory("learned-wipe").toFile()
        val history = dir.resolve("UserHistoryDictionary_en_US.body").apply { writeText("learned") }
        val historyDir = dir.resolve("UserHistoryDictionary_fr").apply { mkdirs() }
        historyDir.resolve("UserHistoryDictionary_fr.header").writeText("h")
        val main = dir.resolve("main_en.dict").apply { writeText("keep") }

        val removed = LearnedDataFiles.deleteUserHistoryFiles(dir)

        assertEquals(2, removed)
        assertFalse(history.exists())
        assertFalse(historyDir.exists())
        assertTrue(main.exists())
        assertEquals("keep", main.readText())
    }
}
