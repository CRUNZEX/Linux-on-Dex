package com.crunzex.linuxondex.engine.proot

import android.system.Os
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Minimal ustar/GNU tar reader — just enough for Linux rootfs archives:
 * regular files, directories, symlinks, hardlinks, long names ('L') and the
 * executable bit. Written here instead of pulling in commons-compress to keep
 * the dependency surface reviewable.
 */
object TarExtractor {

    private const val SCOPE = "TarExtractor"
    private const val BLOCK_SIZE = 512

    fun extract(archive: InputStream, destinationDir: File) {
        destinationDir.mkdirs()
        val destinationRoot = destinationDir.canonicalFile
        var pendingLongName: String? = null
        var entryCount = 0

        while (true) {
            val header = readBlock(archive) ?: break
            if (header.all { it == 0.toByte() }) break // end-of-archive marker

            val entryName = pendingLongName ?: parseName(header)
            pendingLongName = null
            val entrySize = parseOctal(header, offset = 124, length = 12)
            val typeFlag = header[156].toInt().toChar()
            val mode = parseOctal(header, offset = 100, length = 8).toInt()
            val linkTarget = parseString(header, offset = 157, length = 100)

            when (typeFlag) {
                'L' -> pendingLongName = readContentAsString(archive, entrySize)
                '0', Char(0) -> {
                    val file = resolveSafely(destinationRoot, entryName)
                    file.parentFile?.mkdirs()
                    writeContent(archive, entrySize, file)
                    applyExecutableBit(file, mode)
                    entryCount++
                }
                '5' -> {
                    resolveSafely(destinationRoot, entryName).mkdirs()
                    entryCount++
                }
                '2' -> {
                    val link = resolveSafely(destinationRoot, entryName)
                    link.parentFile?.mkdirs()
                    link.delete()
                    // Symlink targets stay as-is: they resolve inside the
                    // rootfs at runtime under PRoot's translated view.
                    Os.symlink(linkTarget, link.absolutePath)
                    entryCount++
                }
                '1' -> {
                    val source = resolveSafely(destinationRoot, linkTarget)
                    val target = resolveSafely(destinationRoot, entryName)
                    target.parentFile?.mkdirs()
                    if (source.exists()) source.copyTo(target, overwrite = true)
                    skipContent(archive, entrySize)
                    entryCount++
                }
                else -> skipContent(archive, entrySize) // devices/fifos: not extractable
            }
        }
        AppLog.info(SCOPE, "extracted $entryCount entries into ${destinationDir.name}")
    }

    /** Rejects entries that would escape the destination (zip-slip guard). */
    private fun resolveSafely(destinationRoot: File, entryName: String): File {
        val resolved = File(destinationRoot, entryName)
        val canonicalPath = resolved.canonicalPath
        if (!canonicalPath.startsWith(destinationRoot.canonicalPath + File.separator) &&
            canonicalPath != destinationRoot.canonicalPath
        ) {
            throw LxdError.PayloadCorrupted("tar entry escapes rootfs: $entryName")
        }
        return resolved
    }

    private fun parseName(header: ByteArray): String {
        val prefix = parseString(header, offset = 345, length = 155)
        val name = parseString(header, offset = 0, length = 100)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun parseString(block: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { block[it] == 0.toByte() }
            ?: (offset + length)
        return String(block, offset, end - offset).trim()
    }

    private fun parseOctal(block: ByteArray, offset: Int, length: Int): Long {
        val text = parseString(block, offset, length).trim { it == ' ' || it == 0.toChar() }
        return if (text.isEmpty()) 0 else text.toLong(radix = 8)
    }

    private fun readBlock(input: InputStream): ByteArray? {
        val block = ByteArray(BLOCK_SIZE)
        var filled = 0
        while (filled < BLOCK_SIZE) {
            val read = input.read(block, filled, BLOCK_SIZE - filled)
            if (read < 0) return if (filled == 0) null else throw IOException("truncated tar header")
            filled += read
        }
        return block
    }

    private fun writeContent(input: InputStream, size: Long, destination: File) {
        destination.outputStream().use { output ->
            var remaining = size
            val buffer = ByteArray(BLOCK_SIZE * 32)
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read < 0) throw IOException("truncated tar content for ${destination.name}")
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        skipPadding(input, size)
    }

    private fun readContentAsString(input: InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        var filled = 0
        while (filled < bytes.size) {
            val read = input.read(bytes, filled, bytes.size - filled)
            if (read < 0) throw IOException("truncated long-name entry")
            filled += read
        }
        skipPadding(input, size)
        return String(bytes).trimEnd(Char(0), '\n')
    }

    private fun skipContent(input: InputStream, size: Long) {
        var remaining = size
        val scratch = ByteArray(BLOCK_SIZE * 32)
        while (remaining > 0) {
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("truncated tar content while skipping")
            remaining -= read
        }
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, contentSize: Long) {
        val remainder = (contentSize % BLOCK_SIZE).toInt()
        if (remainder != 0) {
            var toSkip = BLOCK_SIZE - remainder
            val scratch = ByteArray(BLOCK_SIZE)
            while (toSkip > 0) {
                val read = input.read(scratch, 0, toSkip)
                if (read < 0) throw IOException("truncated tar padding")
                toSkip -= read
            }
        }
    }

    private fun applyExecutableBit(file: File, mode: Int) {
        val ownerExecutable = (mode and 0b001_000_000) != 0
        if (ownerExecutable) file.setExecutable(true, false)
    }
}
