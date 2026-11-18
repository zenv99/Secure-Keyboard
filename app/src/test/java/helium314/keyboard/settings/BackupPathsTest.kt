// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupPathsTest {
    @Test fun normalEntryStaysInsideTheDirectory() {
        val base = Files.createTempDirectory("backup-base").toFile()
        assertTrue(isSafeZipEntry(base, "dicts/en/main_en.user.dict"))
    }

    @Test fun parentTraversalIsRejected() {
        val base = Files.createTempDirectory("backup-base").toFile()
        assertFalse(isSafeZipEntry(base, "../secrets.txt"))
        assertFalse(isSafeZipEntry(base, "dicts/../../etc/passwd"))
        assertFalse(isSafeZipEntry(base, "/etc/passwd"))
        assertFalse(isSafeZipEntry(base, "ok\u0000.txt"))
    }

    @Test fun randomHostileNamesDoNotEscape() {
        val base = Files.createTempDirectory("backup-fuzz").toFile()
        val rng = Random(20261118)
        val alphabet = "../\\:\u0000dicts"
        repeat(200) {
            val name = buildString {
                repeat(rng.nextInt(1, 40)) { append(alphabet[rng.nextInt(alphabet.length)]) }
            }
            if (!isSafeZipEntry(base, name)) return@repeat
            val target = java.io.File(base, name).canonicalFile
            assertTrue(target.path.startsWith(base.canonicalPath))
        }
    }
}
