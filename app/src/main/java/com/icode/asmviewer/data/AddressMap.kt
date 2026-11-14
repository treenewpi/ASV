package com.icode.asmviewer.data

enum class AddressKind { Rva, FileOffset, Va }

enum class ViewportRequest { None, FitAll, CenterSelection }

enum class AddressError {
    InvalidHex,
    OutOfFile,
    UnmappedFileOffset,
    UnmappedRva,
    UnmappedVa,
    NotExecutable,
    Unaligned,
}

data class LoadSegment(
    val vaddr: Long,
    val memSize: Long,
    val fileOffset: Long,
    val fileSize: Long,
    val executable: Boolean,
    val readable: Boolean,
    val writable: Boolean,
) {
    fun containsVa(va: Long): Boolean = memSize > 0 && va >= vaddr && va < vaddr + memSize
    fun containsFileOffset(off: Long): Boolean = fileSize > 0 && off >= fileOffset && off < fileOffset + fileSize
}

data class AddressMap(
    val imageBase: Long,
    val fileSize: Long,
    val elfType: String,
    val segments: List<LoadSegment>,
    val arm64: Boolean,
) {
    fun rvaToVa(rva: Long): Long = imageBase + rva

    fun vaToRva(va: Long): Long = va - imageBase

    fun vaToFileOffset(va: Long): Long? {
        val seg = segments.firstOrNull { it.containsVa(va) } ?: return null
        val delta = va - seg.vaddr
        if (delta < 0 || delta >= seg.fileSize) return null
        return seg.fileOffset + delta
    }

    fun fileOffsetToVa(off: Long): Long? {
        val seg = segments.firstOrNull { it.containsFileOffset(off) } ?: return null
        return seg.vaddr + (off - seg.fileOffset)
    }

    fun segmentForVa(va: Long): LoadSegment? = segments.firstOrNull { it.containsVa(va) }

    fun executableSegmentForVa(va: Long): LoadSegment? =
        segments.firstOrNull { it.executable && it.containsVa(va) }

    fun resolve(kind: AddressKind, value: Long, requireCode: Boolean = true, requireAligned: Boolean = arm64): AddressResolve {
        val va = when (kind) {
            AddressKind.Rva -> rvaToVa(value)
            AddressKind.Va -> value
            AddressKind.FileOffset -> {
                if (fileSize > 0 && (value < 0 || value >= fileSize)) {
                    return AddressResolve.Err(AddressError.OutOfFile)
                }
                fileOffsetToVa(value) ?: return AddressResolve.Err(AddressError.UnmappedFileOffset)
            }
        }
        val seg = segmentForVa(va)
        if (seg == null) {
            return AddressResolve.Err(
                when (kind) {
                    AddressKind.Rva -> AddressError.UnmappedRva
                    AddressKind.FileOffset -> AddressError.UnmappedFileOffset
                    AddressKind.Va -> AddressError.UnmappedVa
                },
            )
        }
        if (requireCode && !seg.executable) return AddressResolve.Err(AddressError.NotExecutable)
        if (requireAligned && (va and 0x3L) != 0L) return AddressResolve.Err(AddressError.Unaligned)
        return AddressResolve.Ok(va = va, rva = vaToRva(va), fileOffset = vaToFileOffset(va))
    }
}

sealed class AddressResolve {
    data class Ok(val va: Long, val rva: Long, val fileOffset: Long?) : AddressResolve()
    data class Err(val reason: AddressError) : AddressResolve()
}

fun parseHexOffset(text: String): Long? {
    val t = text.trim().replace(" ", "").replace("_", "")
    if (t.isEmpty()) return null
    val h = if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2) else t
    if (h.isEmpty() || h.length > 16) return null
    if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return h.toULongOrNull(16)?.toLong()
}

fun formatWideHex(value: Long): String =
    "0x" + value.toULong().toString(16).padStart(16, '0')

fun parseAddressMap(obj: org.json.JSONObject, arm64: Boolean): AddressMap {
    val map = obj.optJSONObject("addressMap")
    if (map == null) {
        return AddressMap(0, obj.optLong("fileSize"), obj.optString("elfType"), emptyList(), arm64)
    }
    val arr = map.optJSONArray("segments")
    val segs = buildList {
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                add(
                    LoadSegment(
                        vaddr = s.optLong("vaddr"),
                        memSize = s.optLong("memSize"),
                        fileOffset = s.optLong("fileOffset"),
                        fileSize = s.optLong("fileSize"),
                        executable = s.optBoolean("executable"),
                        readable = s.optBoolean("readable", true),
                        writable = s.optBoolean("writable"),
                    ),
                )
            }
        }
    }
    return AddressMap(
        imageBase = map.optLong("imageBase"),
        fileSize = map.optLong("fileSize", obj.optLong("fileSize")),
        elfType = map.optString("elfType"),
        segments = segs,
        arm64 = arm64,
    )
}
