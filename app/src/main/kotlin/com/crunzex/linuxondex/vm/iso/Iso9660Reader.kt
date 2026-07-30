package com.crunzex.linuxondex.vm.iso

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/** One entry in an ISO9660 directory. */
data class IsoEntry(
    val name: String,
    val startLogicalBlock: Long,
    val sizeBytes: Long,
    val isDirectory: Boolean,
)

/**
 * Minimal read-only ISO9660 reader: enough to locate and extract the kernel
 * and initrd an installer ISO ships, so the VM can boot them directly
 * instead of going through the ISO's own bootloader.
 *
 * Handles the base standard only — uppercase identifiers with a `;1`
 * version suffix, which is what every installer ISO writes. Rock Ridge and
 * Joliet extensions add nicer names but never remove these, so ignoring
 * them costs nothing here.
 */
class Iso9660Reader(private val isoFile: File) : AutoCloseable {

    private val image = RandomAccessFile(isoFile, "r")

    /** Locates a file by absolute path, e.g. "/casper/vmlinuz". Null if absent. */
    fun findFile(absolutePath: String): IsoEntry? {
        val segments = absolutePath.trim('/').split('/').filter(String::isNotEmpty)
        if (segments.isEmpty()) return null

        var current = readRootDirectory() ?: return null
        for ((index, segment) in segments.withIndex()) {
            val isLast = index == segments.lastIndex
            val match = listDirectory(current).firstOrNull { entry ->
                entry.name.equals(segment, ignoreCase = true) && entry.isDirectory != isLast
            } ?: return null
            if (isLast) return match
            current = match
        }
        return null
    }

    /** Copies an entry's contents into [output]. */
    fun extract(entry: IsoEntry, output: OutputStream) {
        image.seek(entry.startLogicalBlock * LOGICAL_BLOCK_BYTES)
        var remaining = entry.sizeBytes
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = image.read(buffer, 0, toRead)
            if (read < 0) throw IllegalStateException("unexpected end of ${isoFile.name}")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    /** The Primary Volume Descriptor lives at a fixed sector and names the root. */
    private fun readRootDirectory(): IsoEntry? {
        val descriptor = ByteArray(LOGICAL_BLOCK_SIZE)
        image.seek(PRIMARY_VOLUME_DESCRIPTOR_SECTOR * LOGICAL_BLOCK_BYTES)
        image.readFully(descriptor)
        val isIso9660 = descriptor[0].toInt() == PRIMARY_DESCRIPTOR_TYPE &&
            String(descriptor, 1, 5) == "CD001"
        if (!isIso9660) return null
        return parseDirectoryRecord(descriptor, ROOT_DIRECTORY_RECORD_OFFSET)
    }

    private fun listDirectory(directory: IsoEntry): List<IsoEntry> {
        val directorySize = directory.sizeBytes.toInt()
        val bytes = ByteArray(directorySize)
        image.seek(directory.startLogicalBlock * LOGICAL_BLOCK_BYTES)
        image.readFully(bytes)

        val entries = mutableListOf<IsoEntry>()
        var offset = 0
        while (offset < bytes.size) {
            val recordLength = bytes[offset].toInt() and 0xFF
            if (recordLength == 0) {
                // Records never straddle a logical block; skip to the next.
                offset = (offset / LOGICAL_BLOCK_SIZE + 1) * LOGICAL_BLOCK_SIZE
                if (offset >= bytes.size) break
                continue
            }
            parseDirectoryRecord(bytes, offset)
                ?.takeIf { it.name.isNotEmpty() }
                ?.let(entries::add)
            offset += recordLength
        }
        return entries
    }

    private fun parseDirectoryRecord(bytes: ByteArray, offset: Int): IsoEntry? {
        if (offset + MIN_RECORD_BYTES > bytes.size) return null
        val startBlock = readLittleEndianInt(bytes, offset + EXTENT_LBA_OFFSET)
        val size = readLittleEndianInt(bytes, offset + DATA_LENGTH_OFFSET)
        val flags = bytes[offset + FILE_FLAGS_OFFSET].toInt()
        val identifierLength = bytes[offset + IDENTIFIER_LENGTH_OFFSET].toInt() and 0xFF
        val identifierStart = offset + IDENTIFIER_OFFSET
        if (identifierStart + identifierLength > bytes.size) return null

        val rawName = String(bytes, identifierStart, identifierLength, Charsets.ISO_8859_1)
        // "." and ".." are single 0x00/0x01 bytes; drop them.
        if (identifierLength == 1 && (rawName[0].code == 0 || rawName[0].code == 1)) {
            return IsoEntry("", startBlock, size, true)
        }
        return IsoEntry(
            name = rawName.substringBefore(';').trimEnd('.'),
            startLogicalBlock = startBlock,
            sizeBytes = size,
            isDirectory = (flags and DIRECTORY_FLAG) != 0,
        )
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    override fun close() {
        runCatching { image.close() }
    }

    companion object {
        const val LOGICAL_BLOCK_BYTES = 2048L
        private const val LOGICAL_BLOCK_SIZE = 2048
        private const val PRIMARY_VOLUME_DESCRIPTOR_SECTOR = 16L
        private const val PRIMARY_DESCRIPTOR_TYPE = 1
        private const val ROOT_DIRECTORY_RECORD_OFFSET = 156
        private const val MIN_RECORD_BYTES = 34
        private const val EXTENT_LBA_OFFSET = 2
        private const val DATA_LENGTH_OFFSET = 10
        private const val FILE_FLAGS_OFFSET = 25
        private const val IDENTIFIER_LENGTH_OFFSET = 32
        private const val IDENTIFIER_OFFSET = 33
        private const val DIRECTORY_FLAG = 0x02
        private const val COPY_BUFFER_BYTES = 1 shl 20
    }
}
