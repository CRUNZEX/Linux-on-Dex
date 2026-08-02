package com.crunzex.linuxondex.vm.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/**
 * The writer is verified by reading its output back with the reader the app
 * already uses on real installer ISOs — if a Linux kernel can parse those,
 * a round trip through the same parser is meaningful evidence.
 */
class Iso9660WriterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun writeIso(vararg files: Pair<String, String>): java.io.File {
        val output = temporaryFolder.newFile("test-${files.hashCode()}.iso")
        Iso9660Writer.write(
            outputFile = output,
            volumeLabel = "cidata",
            files = files.map { (name, body) -> IsoFileEntry(name, body.toByteArray()) },
        )
        return output
    }

    private fun readBack(iso: java.io.File, path: String): String? =
        Iso9660Reader(iso).use { reader ->
            val entry = reader.findFile(path) ?: return null
            val output = ByteArrayOutputStream()
            reader.extract(entry, output)
            output.toString()
        }

    @Test
    fun `a written file reads back byte for byte`() {
        val body = "#cloud-config\nhostname: dex\n"
        val iso = writeIso("user-data" to body)

        assertEquals(body, readBack(iso, "/user-data"))
    }

    @Test
    fun `both seed files survive the round trip`() {
        val iso = writeIso(
            "user-data" to "#cloud-config\nusers: []\n",
            "meta-data" to "instance-id: linux-on-dex-001\n",
        )

        assertEquals("#cloud-config\nusers: []\n", readBack(iso, "/user-data"))
        assertEquals("instance-id: linux-on-dex-001\n", readBack(iso, "/meta-data"))
    }

    @Test
    fun `file names keep their exact lower case spelling`() {
        // cloud-init will not find "USER_DATA"; the names must survive as-is.
        val iso = writeIso("user-data" to "x", "meta-data" to "y")

        Iso9660Reader(iso).use { reader ->
            assertNotNull(reader.findFile("/user-data"))
            assertNotNull(reader.findFile("/meta-data"))
        }
    }

    @Test
    fun `content larger than one sector is preserved`() {
        val body = "line\n".repeat(2_000) // ~10 KB, spans several sectors
        val iso = writeIso("user-data" to body)

        assertEquals(body, readBack(iso, "/user-data"))
    }

    @Test
    fun `the volume label is written for cloud-init to match`() {
        val iso = writeIso("user-data" to "x")
        val bytes = iso.readBytes()
        val descriptorStart = 16 * 2048

        val volumeLabel = String(bytes, descriptorStart + 40, 32).trim()
        assertEquals("CIDATA", volumeLabel)
    }

    @Test
    fun `the image is a whole number of sectors`() {
        val iso = writeIso("user-data" to "x", "meta-data" to "y")

        assertEquals(0L, iso.length() % 2048)
        assertTrue("must hold at least the descriptors", iso.length() >= 20 * 2048)
    }

    @Test
    fun `writing no files is rejected`() {
        val output = temporaryFolder.newFile("empty.iso")

        assertThrows(IllegalArgumentException::class.java) {
            Iso9660Writer.write(output, "cidata", emptyList())
        }
    }

    @Test
    fun `an over-long file name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IsoFileEntry("x".repeat(64), ByteArray(1))
        }
    }
}
