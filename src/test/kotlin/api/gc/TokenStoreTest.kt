package api.gc

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class TokenStoreTest {
    @Test
    fun `token survives replacement in a private file`() {
        val dir = Files.createTempDirectory("steam-state-test")
        try {
            val file = dir.resolve("nested/token.txt").toFile()
            saveRefreshToken(file, "test-token-one")
            saveRefreshToken(file, "test-token-two")
            assertEquals("test-token-two", file.readText())
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath()))
            assertEquals(listOf("token.txt"), file.parentFile.list()!!.toList())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
