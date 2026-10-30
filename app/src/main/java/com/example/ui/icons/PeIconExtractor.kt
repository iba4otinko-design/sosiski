package com.example.ui.icons

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Safe, zero-execution binary parser that reads embedded application icons
 * from Windows Portable Executable (.exe / .dll) files (PE32 and PE32+).
 *
 * Inspects IMAGE_DOS_HEADER -> IMAGE_NT_HEADERS -> Section Headers -> .rsrc Resource Directory
 * (RT_GROUP_ICON = 14 and RT_ICON = 3) and decodes either PNG or DIB (BITMAPINFOHEADER) icon payloads.
 */
object PeIconExtractor {

    private const val MZ_MAGIC = 0x5A4D
    private const val PE_SIGNATURE = 0x00004550
    private const val PE32_MAGIC = 0x010B
    private const val PE32_PLUS_MAGIC = 0x020B
    private const val RT_ICON = 3
    private const val RT_GROUP_ICON = 14
    private const val MAX_RSRC_BYTES = 8 * 1024 * 1024 // Cap resource read to 8 MB for safety
    private const val MAX_ICON_BYTES = 2 * 1024 * 1024 // Cap single icon to 2 MB

    private data class PeSection(
        val virtualAddress: Long,
        val virtualSize: Long,
        val rawOffset: Long,
        val rawSize: Long
    )

    private data class ResourceDataRef(
        val id: Int,
        val dataRva: Long,
        val size: Int
    )

    private data class GroupIconMember(
        val iconId: Int,
        val width: Int,
        val height: Int,
        val bitCount: Int,
        val bytesInRes: Int
    )

    fun extractFromFile(file: File): Bitmap? {
        if (!file.exists() || !file.isFile || file.length() < 128L) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                parsePeFromSource(file.length()) { offset, length ->
                    if (offset < 0L || length <= 0 || offset + length > raf.length()) {
                        null
                    } else {
                        val buf = ByteArray(length)
                        raf.seek(offset)
                        raf.readFully(buf)
                        buf
                    }
                }
            }
        }.getOrNull()
    }

    fun extractFromStream(inputStream: InputStream, maxReadBytes: Int = 12 * 1024 * 1024): Bitmap? {
        return runCatching {
            val bytes = inputStream.readBytesBounded(maxReadBytes)
            if (bytes.size < 128) return null
            parsePeFromSource(bytes.size.toLong()) { offset, length ->
                val start = offset.toInt()
                if (offset < 0L || start < 0 || length <= 0 || start + length > bytes.size) {
                    null
                } else {
                    bytes.copyOfRange(start, start + length)
                }
            }
        }.getOrNull()
    }

    fun extractFromBytes(bytes: ByteArray): Bitmap? {
        if (bytes.size < 128) return null
        return runCatching {
            parsePeFromSource(bytes.size.toLong()) { offset, length ->
                val start = offset.toInt()
                if (offset < 0L || start < 0 || length <= 0 || start + length > bytes.size) {
                    null
                } else {
                    bytes.copyOfRange(start, start + length)
                }
            }
        }.getOrNull()
    }

    private fun parsePeFromSource(
        totalSize: Long,
        readSlice: (offset: Long, length: Int) -> ByteArray?
    ): Bitmap? {
        val dosHeader = readSlice(0L, 64) ?: return null
        val dosBuf = ByteBuffer.wrap(dosHeader).order(ByteOrder.LITTLE_ENDIAN)
        if ((dosBuf.getShort(0).toInt() and 0xFFFF) != MZ_MAGIC) return null

        val peOffset = dosBuf.getInt(0x3C).toLong() and 0xFFFFFFFFL
        if (peOffset < 64L || peOffset + 24L > totalSize) return null

        val ntFixed = readSlice(peOffset, 24) ?: return null
        val ntBuf = ByteBuffer.wrap(ntFixed).order(ByteOrder.LITTLE_ENDIAN)
        if (ntBuf.getInt(0) != PE_SIGNATURE) return null

        val numSections = (ntBuf.getShort(6).toInt() and 0xFFFF).coerceAtMost(96)
        val sizeOfOptionalHeader = ntBuf.getShort(20).toInt() and 0xFFFF
        if (numSections == 0 || sizeOfOptionalHeader < 96) return null

        val optOffset = peOffset + 24L
        val optBytes = readSlice(optOffset, min(sizeOfOptionalHeader, 512)) ?: return null
        val optBuf = ByteBuffer.wrap(optBytes).order(ByteOrder.LITTLE_ENDIAN)
        val optMagic = optBuf.getShort(0).toInt() and 0xFFFF

        // DataDirectory[2] is the Resource Table (each entry is 8 bytes: RVA + Size)
        val dataDirStart = when (optMagic) {
            PE32_MAGIC -> 96
            PE32_PLUS_MAGIC -> 112
            else -> return null
        }
        val rsrcDirEntryOffset = dataDirStart + 2 * 8
        if (rsrcDirEntryOffset + 8 > optBytes.size) return null

        val rsrcRva = optBuf.getInt(rsrcDirEntryOffset).toLong() and 0xFFFFFFFFL
        val rsrcSize = optBuf.getInt(rsrcDirEntryOffset + 4).toLong() and 0xFFFFFFFFL
        if (rsrcRva == 0L || rsrcSize <= 16L) return null

        val sectionTableOffset = optOffset + sizeOfOptionalHeader
        val sectionTableBytes = readSlice(sectionTableOffset, numSections * 40) ?: return null
        val secBuf = ByteBuffer.wrap(sectionTableBytes).order(ByteOrder.LITTLE_ENDIAN)
        val sections = ArrayList<PeSection>(numSections)
        for (i in 0 until numSections) {
            val base = i * 40
            val vSize = secBuf.getInt(base + 8).toLong() and 0xFFFFFFFFL
            val vAddr = secBuf.getInt(base + 12).toLong() and 0xFFFFFFFFL
            val rawSize = secBuf.getInt(base + 16).toLong() and 0xFFFFFFFFL
            val rawPtr = secBuf.getInt(base + 20).toLong() and 0xFFFFFFFFL
            sections.add(PeSection(vAddr, vSize, rawPtr, rawSize))
        }

        fun rvaToFileOffset(rva: Long): Long? {
            for (sec in sections) {
                val span = maxOf(sec.virtualSize, sec.rawSize)
                if (rva >= sec.virtualAddress && rva < sec.virtualAddress + span) {
                    val delta = rva - sec.virtualAddress
                    val filePos = sec.rawOffset + delta
                    if (filePos in 0 until totalSize) return filePos
                }
            }
            return null
        }

        val rsrcFileBase = rvaToFileOffset(rsrcRva) ?: return null
        val safeRsrcLen = min(rsrcSize, min(MAX_RSRC_BYTES.toLong(), totalSize - rsrcFileBase)).toInt()
        if (safeRsrcLen < 16) return null
        val rsrcBytes = readSlice(rsrcFileBase, safeRsrcLen) ?: return null
        val rsrcBuf = ByteBuffer.wrap(rsrcBytes).order(ByteOrder.LITTLE_ENDIAN)

        val iconRefs = LinkedHashMap<Int, ResourceDataRef>()
        val groupRefs = ArrayList<ResourceDataRef>()

        // Level 1: Resource Types (looking for RT_ICON = 3 and RT_GROUP_ICON = 14)
        walkDirectoryEntries(rsrcBuf, 0) { typeId, isSubdir1, offset1 ->
            if (!isSubdir1 || (typeId != RT_ICON && typeId != RT_GROUP_ICON)) return@walkDirectoryEntries
            // Level 2: Resource Name / ID
            walkDirectoryEntries(rsrcBuf, offset1) { nameOrId, isSubdir2, offset2 ->
                if (!isSubdir2) return@walkDirectoryEntries
                // Level 3: Language -> points to IMAGE_RESOURCE_DATA_ENTRY (16 bytes)
                var pickedFirstLang = false
                walkDirectoryEntries(rsrcBuf, offset2) { _, isSubdir3, dataEntryOffset ->
                    if (pickedFirstLang || isSubdir3) return@walkDirectoryEntries
                    if (dataEntryOffset < 0 || dataEntryOffset + 16 > rsrcBytes.size) return@walkDirectoryEntries
                    val dataRva = rsrcBuf.getInt(dataEntryOffset).toLong() and 0xFFFFFFFFL
                    val dataSize = rsrcBuf.getInt(dataEntryOffset + 4)
                    if (dataSize in 16..MAX_ICON_BYTES) {
                        val ref = ResourceDataRef(id = nameOrId, dataRva = dataRva, size = dataSize)
                        if (typeId == RT_ICON) {
                            iconRefs.putIfAbsent(nameOrId, ref)
                        } else if (typeId == RT_GROUP_ICON) {
                            groupRefs.add(ref)
                        }
                        pickedFirstLang = true
                    }
                }
            }
        }

        if (iconRefs.isEmpty()) return null

        // Score icons via RT_GROUP_ICON if available so we pick the sharpest 32-bit icon
        val orderedCandidates = ArrayList<ResourceDataRef>()
        for (groupRef in groupRefs) {
            val groupFileOffset = rvaToFileOffset(groupRef.dataRva) ?: continue
            val groupData = readSlice(groupFileOffset, min(groupRef.size, 4096)) ?: continue
            val members = parseGroupIconDirectory(groupData)
            members.sortedByDescending { scoreIconMember(it) }.forEach { member ->
                iconRefs[member.iconId]?.let { ref ->
                    if (ref !in orderedCandidates) orderedCandidates.add(ref)
                }
            }
        }
        // Fallback: also try remaining RT_ICON entries sorted by payload size descending
        iconRefs.values.sortedByDescending { it.size }.forEach { ref ->
            if (ref !in orderedCandidates) orderedCandidates.add(ref)
        }

        for (candidate in orderedCandidates.take(6)) {
            val iconOffset = rvaToFileOffset(candidate.dataRva) ?: continue
            val payload = readSlice(iconOffset, candidate.size) ?: continue
            val decoded = decodePeIconPayload(payload)
            if (decoded != null) return decoded
        }
        return null
    }

    private inline fun walkDirectoryEntries(
        buf: ByteBuffer,
        dirOffset: Int,
        visitor: (idOrHash: Int, isSubdirectory: Boolean, targetOffset: Int) -> Unit
    ) {
        if (dirOffset < 0 || dirOffset + 16 > buf.limit()) return
        val namedCount = buf.getShort(dirOffset + 12).toInt() and 0xFFFF
        val idCount = buf.getShort(dirOffset + 14).toInt() and 0xFFFF
        val total = (namedCount + idCount).coerceAtMost(256)
        var cursor = dirOffset + 16
        for (i in 0 until total) {
            if (cursor + 8 > buf.limit()) break
            val nameField = buf.getInt(cursor)
            val offsetField = buf.getInt(cursor + 4)
            val idValue = if ((nameField and -0x80000000) != 0) {
                -(i + 1) // Named resource entry
            } else {
                nameField and 0xFFFF
            }
            val isSubdir = (offsetField and -0x80000000) != 0
            val targetOffset = offsetField and 0x7FFFFFFF
            visitor(idValue, isSubdir, targetOffset)
            cursor += 8
        }
    }

    private fun parseGroupIconDirectory(bytes: ByteArray): List<GroupIconMember> {
        if (bytes.size < 6) return emptyList()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val resType = buf.getShort(2).toInt() and 0xFFFF
        val count = (buf.getShort(4).toInt() and 0xFFFF).coerceAtMost(64)
        if (resType != 1 || count == 0) return emptyList()
        val list = ArrayList<GroupIconMember>(count)
        var pos = 6
        for (i in 0 until count) {
            if (pos + 14 > bytes.size) break
            val wRaw = bytes[pos].toInt() and 0xFF
            val hRaw = bytes[pos + 1].toInt() and 0xFF
            val width = if (wRaw == 0) 256 else wRaw
            val height = if (hRaw == 0) 256 else hRaw
            val bitCount = buf.getShort(pos + 6).toInt() and 0xFFFF
            val bytesInRes = buf.getInt(pos + 8)
            val iconId = buf.getShort(pos + 12).toInt() and 0xFFFF
            list.add(GroupIconMember(iconId, width, height, bitCount, bytesInRes))
            pos += 14
        }
        return list
    }

    private fun scoreIconMember(member: GroupIconMember): Int {
        val idealDimScore = when {
            member.width in 48..128 -> 1000 + member.width
            member.width > 128 -> 950
            else -> member.width * 10
        }
        return idealDimScore + member.bitCount * 20
    }

    private fun decodePeIconPayload(payload: ByteArray): Bitmap? {
        if (payload.size < 40) return null

        // 1. Check PNG signature: 89 50 4E 47 0D 0A 1A 0A
        if (
            payload[0] == 0x89.toByte() &&
            payload[1] == 0x50.toByte() &&
            payload[2] == 0x4E.toByte() &&
            payload[3] == 0x47.toByte()
        ) {
            return decodeDownsampled(payload, 128)
        }

        // 2. DIB (BITMAPINFOHEADER) inside PE RT_ICON
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buf.getInt(0)
        if (headerSize != 40) return null
        val width = buf.getInt(4)
        val rawHeight = buf.getInt(8)
        val height = kotlin.math.abs(rawHeight) / 2 // ICO/PE DIB stores XOR + AND mask height * 2
        val bitCount = buf.getShort(14).toInt() and 0xFFFF
        val compression = buf.getInt(16)
        if (width !in 1..512 || height !in 1..512) return null

        // 2a. Pure-Kotlin exact decoder for uncompressed 32-bit BGRA and 24-bit BGR DIBs
        if (compression == 0 && (bitCount == 32 || bitCount == 24)) {
            decodeUncompressedDibFallback(payload, width, height, rawHeight < 0, bitCount)?.let { bmp ->
                return if (bmp.width > 128 || bmp.height > 128) {
                    val ratio = minOf(128f / bmp.width, 128f / bmp.height)
                    Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * ratio).toInt().coerceAtLeast(1),
                        (bmp.height * ratio).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bmp
                }
            }
        }

        // 2b. Fallback: wrap DIB into a standard 22-byte .ico container for BitmapFactory (e.g. paletted icons)
        val icoBytes = ByteArray(22 + payload.size)
        val icoBuf = ByteBuffer.wrap(icoBytes).order(ByteOrder.LITTLE_ENDIAN)
        icoBuf.putShort(0) // Reserved
        icoBuf.putShort(1) // Type = 1 (ICO)
        icoBuf.putShort(1) // Count = 1
        icoBuf.put(if (width >= 256) 0.toByte() else width.toByte())
        icoBuf.put(if (height >= 256) 0.toByte() else height.toByte())
        icoBuf.put(0) // Palette count
        icoBuf.put(0) // Reserved
        icoBuf.putShort(1) // Planes
        icoBuf.putShort(bitCount.toShort())
        icoBuf.putInt(payload.size)
        icoBuf.putInt(22) // Offset to DIB
        System.arraycopy(payload, 0, icoBytes, 22, payload.size)

        return decodeDownsampled(icoBytes, 128)
    }

    private fun decodeUncompressedDibFallback(
        dib: ByteArray,
        width: Int,
        height: Int,
        topDown: Boolean,
        bitCount: Int
    ): Bitmap? {
        val bytesPerPixel = bitCount / 8
        val xorRowStride = ((width * bitCount + 31) / 32) * 4
        val andRowStride = ((width + 31) / 32) * 4
        val xorOffset = 40
        val xorTotal = xorRowStride * height
        if (xorOffset + xorTotal > dib.size) return null
        val andOffset = xorOffset + xorTotal
        val hasAndMask = andOffset + andRowStride * height <= dib.size

        val pixels = IntArray(width * height)
        var anyAlphaNonZero = false

        for (row in 0 until height) {
            val dstY = if (topDown) row else (height - 1 - row)
            val rowStart = xorOffset + row * xorRowStride
            for (col in 0 until width) {
                val p = rowStart + col * bytesPerPixel
                val b = dib[p].toInt() and 0xFF
                val g = dib[p + 1].toInt() and 0xFF
                val r = dib[p + 2].toInt() and 0xFF
                val a = if (bitCount == 32) (dib[p + 3].toInt() and 0xFF) else 0xFF
                if (bitCount == 32 && a != 0) anyAlphaNonZero = true
                pixels[dstY * width + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        // If 24-bit or 32-bit with 0 alpha channel, apply 1-bpp AND transparency mask
        if (bitCount == 24 || !anyAlphaNonZero) {
            for (row in 0 until height) {
                val dstY = if (topDown) row else (height - 1 - row)
                val maskRowStart = andOffset + row * andRowStride
                for (col in 0 until width) {
                    val transparent = if (hasAndMask) {
                        val byteVal = dib[maskRowStart + (col shr 3)].toInt() and 0xFF
                        ((byteVal shr (7 - (col and 7))) and 1) == 1
                    } else {
                        false
                    }
                    val idx = dstY * width + col
                    val rgb = pixels[idx] and 0x00FFFFFF
                    pixels[idx] = if (transparent) 0x00000000 else (0xFF000000.toInt() or rgb)
                }
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun decodeDownsampled(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun InputStream.readBytesBounded(maxBytes: Int): ByteArray {
        val buffer = ByteArray(min(maxBytes, 16 * 1024))
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < maxBytes) {
            val toRead = min(buffer.size, maxBytes - total)
            val read = read(buffer, 0, toRead)
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }
}
