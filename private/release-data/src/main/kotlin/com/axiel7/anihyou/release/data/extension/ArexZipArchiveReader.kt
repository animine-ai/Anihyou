package com.axiel7.anihyou.release.data.extension

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal enum class ArexZipFailure {
    INVALID,
    SIZE_LIMIT,
}

internal class ArexZipArchiveException(
    val failure: ArexZipFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Minimal ZIP32 reader for the frozen AREX container.
 *
 * Production Android must not depend on Commons Compress ZipFile: current versions initialize
 * java.nio.file.StandardOpenOption, which is unavailable on API 24. The central-directory parser
 * below is intentionally narrow and fail-closed: one disk, no archive or entry comments, no
 * ZIP64/AES/unicode-path aliases, exact root entries, STORED/DEFLATED only, no gaps/overlaps, and
 * explicit local-header/data-descriptor consistency. java.util.zip.ZipFile is used only after the
 * raw structure has been authenticated so decompression remains platform-native and API-24-safe.
 */
internal class ArexZipArchiveReader(
    expectedEntries: Set<String>,
    entryLimits: Map<String, Int>,
    private val maxUncompressedBytes: Long,
) {
    private val expectedEntries = expectedEntries.toSet()
    private val entryLimits = entryLimits.toMap()

    init {
        require(this.expectedEntries.isNotEmpty())
        require(this.entryLimits.keys == this.expectedEntries)
        require(this.entryLimits.values.all { it > 0 })
        require(maxUncompressedBytes > 0)
    }

    fun read(file: File): Map<String, ByteArray> = try {
        val directory = parseDirectory(file)
        ZipFile(file).use { zip ->
            val listed = ArrayList<ZipEntry>(directory.size)
            val entries = zip.entries()
            while (entries.hasMoreElements()) listed += entries.nextElement()
            if (listed.size != directory.size ||
                listed.mapTo(LinkedHashSet()) { it.name } != expectedEntries ||
                listed.any { it.isDirectory }
            ) {
                invalid("platform ZIP view differs from authenticated directory")
            }

            val result = LinkedHashMap<String, ByteArray>(directory.size)
            for (metadata in directory) {
                val entry = zip.getEntry(metadata.name)
                    ?: invalid("archive entry disappeared from platform ZIP view")
                if (entry.method != metadata.method ||
                    entry.size != metadata.uncompressedSize ||
                    entry.compressedSize != metadata.compressedSize ||
                    entry.crc != metadata.crc
                ) {
                    invalid("platform ZIP metadata differs from authenticated directory")
                }
                result[metadata.name] = readEntry(zip, entry, metadata)
            }
            if (result.keys != expectedEntries) invalid("archive root entries changed while reading")
            result
        }
    } catch (failure: ArexZipArchiveException) {
        throw failure
    } catch (error: Exception) {
        throw ArexZipArchiveException(
            ArexZipFailure.INVALID,
            "package archive is malformed or unreadable",
            error,
        )
    }

    private fun parseDirectory(file: File): List<EntryMetadata> =
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length < EOCD_BYTES) invalid("archive is shorter than the ZIP end record")
            val eocdOffset = length - EOCD_BYTES
            input.seek(eocdOffset)
            if (input.u32() != EOCD_SIGNATURE) invalid("archive has no canonical ZIP end record")

            val diskNumber = input.u16()
            val centralDisk = input.u16()
            val entriesOnDisk = input.u16()
            val entryCount = input.u16()
            val centralSize = input.u32()
            val centralOffset = input.u32()
            val commentBytes = input.u16()
            if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != entryCount ||
                entryCount == ZIP64_U16 || centralSize == ZIP64_U32 || centralOffset == ZIP64_U32 ||
                commentBytes != 0 || input.filePointer != length
            ) {
                invalid("multi-disk, ZIP64, or commented archives are outside the AREX profile")
            }
            if (entryCount != expectedEntries.size) invalid("archive entry count differs from frozen layout")
            if (checkedAdd(centralOffset, centralSize) != eocdOffset) {
                invalid("central directory is not contiguous with the ZIP end record")
            }

            input.seek(centralOffset)
            val entries = ArrayList<EntryMetadata>(entryCount)
            var totalUncompressed = 0L
            repeat(entryCount) {
                val metadata = readCentralEntry(input)
                if (entries.any { it.name == metadata.name }) invalid("archive contains a duplicate entry")
                val limit = entryLimits[metadata.name]
                    ?: invalid("archive contains an undeclared root entry")
                if (metadata.uncompressedSize > limit.toLong()) sizeLimit("archive entry exceeds its declared bound")
                totalUncompressed = checkedSizeAdd(totalUncompressed, metadata.uncompressedSize)
                if (totalUncompressed > maxUncompressedBytes) sizeLimit("archive exceeds total uncompressed bound")
                entries += metadata
            }
            if (input.filePointer != checkedAdd(centralOffset, centralSize)) {
                invalid("central directory length is inconsistent")
            }
            if (entries.mapTo(LinkedHashSet()) { it.name } != expectedEntries) {
                invalid("archive does not contain the exact required root entries")
            }

            validateLocalLayout(input, entries, centralOffset)
            entries
        }

    private fun readCentralEntry(input: RandomAccessFile): EntryMetadata {
        if (input.u32() != CENTRAL_SIGNATURE) invalid("invalid central directory entry")
        val versionMadeBy = input.u16()
        val versionNeeded = input.u16()
        val flags = input.u16()
        val method = input.u16()
        input.skipFully(4)
        val crc = input.u32()
        val compressedSize = input.u32()
        val uncompressedSize = input.u32()
        val nameLength = input.u16()
        val extraLength = input.u16()
        val commentLength = input.u16()
        val diskStart = input.u16()
        input.skipFully(2)
        val externalAttributes = input.u32()
        val localHeaderOffset = input.u32()

        validateCommonHeader(versionNeeded, flags, method, compressedSize, uncompressedSize)
        if (commentLength != 0 || diskStart != 0 || localHeaderOffset == ZIP64_U32) {
            invalid("entry comments, split entries, or ZIP64 offsets are outside the AREX profile")
        }

        val name = input.asciiName(nameLength)
        validateName(name)
        val extra = input.bytes(extraLength)
        validateExtra(extra)
        if (isUnixSymlink(versionMadeBy, externalAttributes)) invalid("symbolic links are forbidden")
        if (externalAttributes and DOS_DIRECTORY_ATTRIBUTE != 0L) invalid("directory entries are forbidden")

        return EntryMetadata(
            name = name,
            flags = flags,
            method = method,
            crc = crc,
            compressedSize = compressedSize,
            uncompressedSize = uncompressedSize,
            localHeaderOffset = localHeaderOffset,
        )
    }

    private fun validateLocalLayout(
        input: RandomAccessFile,
        directory: List<EntryMetadata>,
        centralOffset: Long,
    ) {
        val ordered = directory.sortedBy { it.localHeaderOffset }
        if (ordered.firstOrNull()?.localHeaderOffset != 0L) {
            invalid("self-extracting prefixes or leading archive bytes are forbidden")
        }

        for (index in ordered.indices) {
            val metadata = ordered[index]
            val boundary = ordered.getOrNull(index + 1)?.localHeaderOffset ?: centralOffset
            if (metadata.localHeaderOffset >= boundary) invalid("archive entries overlap or are out of order")
            input.seek(metadata.localHeaderOffset)
            if (input.u32() != LOCAL_SIGNATURE) invalid("invalid local ZIP header")
            val versionNeeded = input.u16()
            val flags = input.u16()
            val method = input.u16()
            input.skipFully(4)
            val localCrc = input.u32()
            val localCompressedSize = input.u32()
            val localUncompressedSize = input.u32()
            val nameLength = input.u16()
            val extraLength = input.u16()

            validateCommonHeader(versionNeeded, flags, method, metadata.compressedSize, metadata.uncompressedSize)
            if (flags != metadata.flags || method != metadata.method) {
                invalid("local and central ZIP flags or methods differ")
            }
            if (input.asciiName(nameLength) != metadata.name) invalid("local and central entry names differ")
            validateExtra(input.bytes(extraLength))

            val dataOffset = input.filePointer
            val dataEnd = checkedAdd(dataOffset, metadata.compressedSize)
            if (dataEnd > boundary) invalid("compressed entry data overlaps the next ZIP structure")

            val usesDescriptor = flags and DATA_DESCRIPTOR_FLAG != 0
            if (!usesDescriptor) {
                if (localCrc != metadata.crc ||
                    localCompressedSize != metadata.compressedSize ||
                    localUncompressedSize != metadata.uncompressedSize ||
                    dataEnd != boundary
                ) {
                    invalid("local ZIP sizes, CRC, or entry boundary differ from central directory")
                }
            } else {
                validateDescriptor(
                    input = input,
                    start = dataEnd,
                    boundary = boundary,
                    metadata = metadata,
                    localCrc = localCrc,
                    localCompressedSize = localCompressedSize,
                    localUncompressedSize = localUncompressedSize,
                )
            }
        }
    }

    private fun validateDescriptor(
        input: RandomAccessFile,
        start: Long,
        boundary: Long,
        metadata: EntryMetadata,
        localCrc: Long,
        localCompressedSize: Long,
        localUncompressedSize: Long,
    ) {
        if (localCrc !in setOf(0L, metadata.crc) ||
            localCompressedSize !in setOf(0L, metadata.compressedSize) ||
            localUncompressedSize !in setOf(0L, metadata.uncompressedSize)
        ) {
            invalid("local header conflicts with data descriptor")
        }

        val length = boundary - start
        if (length != DATA_DESCRIPTOR_BYTES.toLong() && length != SIGNED_DATA_DESCRIPTOR_BYTES.toLong()) {
            invalid("data descriptor has a non-canonical length")
        }
        input.seek(start)
        val first = input.u32()
        val crc = if (length == SIGNED_DATA_DESCRIPTOR_BYTES.toLong()) {
            if (first != DATA_DESCRIPTOR_SIGNATURE) invalid("data descriptor signature is invalid")
            input.u32()
        } else {
            first
        }
        val compressedSize = input.u32()
        val uncompressedSize = input.u32()
        if (crc != metadata.crc ||
            compressedSize != metadata.compressedSize ||
            uncompressedSize != metadata.uncompressedSize ||
            input.filePointer != boundary
        ) {
            invalid("data descriptor differs from central directory")
        }
    }

    private fun validateCommonHeader(
        versionNeeded: Int,
        flags: Int,
        method: Int,
        compressedSize: Long,
        uncompressedSize: Long,
    ) {
        if (versionNeeded > MAX_ZIP_VERSION || compressedSize == ZIP64_U32 || uncompressedSize == ZIP64_U32) {
            invalid("ZIP64 or unsupported ZIP versions are outside the AREX profile")
        }
        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
            invalid("unsupported ZIP compression method")
        }
        val allowedFlags = UTF8_FLAG or DATA_DESCRIPTOR_FLAG or if (method == ZipEntry.DEFLATED) DEFLATE_OPTION_FLAGS else 0
        if (flags and allowedFlags.inv() and 0xffff != 0 || flags and ENCRYPTION_FLAGS != 0) {
            invalid("unsupported or encrypted ZIP flags")
        }
    }

    private fun validateName(name: String) {
        if (name !in expectedEntries || '/' in name || '\\' in name || name.isEmpty()) {
            invalid("archive entry violates the frozen root layout")
        }
    }

    private fun validateExtra(extra: ByteArray) {
        var offset = 0
        while (offset < extra.size) {
            if (extra.size - offset < 4) invalid("truncated ZIP extra field")
            val id = extra.u16(offset)
            val size = extra.u16(offset + 2)
            offset += 4
            if (size > extra.size - offset) invalid("truncated ZIP extra field payload")
            if (id == ZIP64_EXTRA_ID || id == AES_EXTRA_ID || id == UNICODE_PATH_EXTRA_ID) {
                invalid("ZIP64, AES, or alternate Unicode path metadata is forbidden")
            }
            offset += size
        }
    }

    private fun readEntry(zip: ZipFile, entry: ZipEntry, metadata: EntryMetadata): ByteArray {
        val limit = entryLimits.getValue(metadata.name)
        val output = ByteArrayOutputStream(metadata.uncompressedSize.toInt())
        val crc = CRC32()
        val buffer = ByteArray(8192)
        var count = 0L
        zip.getInputStream(entry).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                count += read
                if (count > limit || count > metadata.uncompressedSize) {
                    sizeLimit("decompressed entry exceeds its bound")
                }
                crc.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        if (count != metadata.uncompressedSize || crc.value != metadata.crc) {
            invalid("entry size or CRC does not match its central directory record")
        }
        return output.toByteArray()
    }

    private fun RandomAccessFile.asciiName(length: Int): String {
        if (length <= 0 || length > MAX_NAME_BYTES) invalid("invalid ZIP entry name length")
        val raw = bytes(length)
        if (raw.any { (it.toInt() and 0xff) !in 0x21..0x7e }) invalid("ZIP entry name is not canonical ASCII")
        return String(raw, StandardCharsets.US_ASCII)
    }

    private fun RandomAccessFile.bytes(length: Int): ByteArray {
        if (length < 0) invalid("negative ZIP length")
        val value = ByteArray(length)
        readFully(value)
        return value
    }

    private fun RandomAccessFile.u16(): Int {
        val low = read()
        val high = read()
        if (low < 0 || high < 0) invalid("truncated ZIP integer")
        return low or (high shl 8)
    }

    private fun RandomAccessFile.u32(): Long =
        u16().toLong() or (u16().toLong() shl 16)

    private fun RandomAccessFile.skipFully(count: Int) {
        if (count < 0 || filePointer + count > length()) invalid("truncated ZIP header")
        seek(filePointer + count)
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun checkedAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw ArexZipArchiveException(ArexZipFailure.INVALID, "ZIP offset overflow", error)
    }

    private fun checkedSizeAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw ArexZipArchiveException(ArexZipFailure.SIZE_LIMIT, "ZIP size overflow", error)
    }

    private fun isUnixSymlink(versionMadeBy: Int, externalAttributes: Long): Boolean {
        val platform = versionMadeBy ushr 8
        if (platform != UNIX_PLATFORM) return false
        val mode = (externalAttributes ushr 16).toInt() and 0xffff
        return mode and UNIX_FILE_TYPE_MASK == UNIX_SYMLINK
    }

    private fun invalid(message: String): Nothing =
        throw ArexZipArchiveException(ArexZipFailure.INVALID, message)

    private fun sizeLimit(message: String): Nothing =
        throw ArexZipArchiveException(ArexZipFailure.SIZE_LIMIT, message)

    private data class EntryMetadata(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    private companion object {
        const val EOCD_BYTES = 22L
        const val DATA_DESCRIPTOR_BYTES = 12
        const val SIGNED_DATA_DESCRIPTOR_BYTES = 16
        const val MAX_NAME_BYTES = 256
        const val MAX_ZIP_VERSION = 20

        const val LOCAL_SIGNATURE = 0x04034b50L
        const val CENTRAL_SIGNATURE = 0x02014b50L
        const val EOCD_SIGNATURE = 0x06054b50L
        const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50L

        const val ZIP64_U16 = 0xffff
        const val ZIP64_U32 = 0xffff_ffffL
        const val UTF8_FLAG = 0x0800
        const val DATA_DESCRIPTOR_FLAG = 0x0008
        const val DEFLATE_OPTION_FLAGS = 0x0006
        const val ENCRYPTION_FLAGS = 0x2041

        const val ZIP64_EXTRA_ID = 0x0001
        const val UNICODE_PATH_EXTRA_ID = 0x7075
        const val AES_EXTRA_ID = 0x9901

        const val UNIX_PLATFORM = 3
        const val UNIX_FILE_TYPE_MASK = 0xf000
        const val UNIX_SYMLINK = 0xa000
        const val DOS_DIRECTORY_ATTRIBUTE = 0x10L
    }
}
