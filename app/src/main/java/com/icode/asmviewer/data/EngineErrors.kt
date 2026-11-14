package com.icode.asmviewer.data

import android.content.Context
import com.icode.asmviewer.R

object EngineErrors {
    fun localize(context: Context, error: Throwable): String {
        val raw = error.message.orEmpty()
        return localize(context, raw)
    }

    fun localize(context: Context, raw: String): String {
        val msg = raw.trim()
        if (msg.isEmpty()) return context.getString(R.string.err_generic)
        val lower = msg.lowercase()
        return when {
            "session is null" in lower -> context.getString(R.string.err_generic)
            "cfg exploration returned no graph" in lower -> context.getString(R.string.err_cfg_failed)
            "interactive simulation" in lower -> context.getString(R.string.replay_need_arm64)
            msg.contains("选中位置不在当前函数") || "not in" in lower && "control-flow" in lower ->
                context.getString(R.string.err_not_in_cfg)
            msg.startsWith("仅支持 ARM64") || ( "arm64" in lower && "only" in lower && "elf" in lower) ->
                context.getString(R.string.not_arm64_open, extractArch(msg))
            "无法读取所选文件" in msg || "couldn't read the selected file" in lower ->
                context.getString(R.string.err_read_file)
            msg.startsWith("无法读取安装包") || msg.startsWith("Couldn't read the install") ->
                context.getString(R.string.err_read_apk, msg.substringAfter("：").ifBlank { msg.substringAfter(": ").ifBlank { "" } })
            "不安全" in msg || "无效的路径" in msg || "unsafe" in lower || "invalid path" in lower ->
                context.getString(R.string.err_unsafe_path)
            "超过 96" in msg || "exceeds the 96" in lower || ("96" in msg && "MiB" in msg && ("限制" in msg || "limit" in lower)) -> {
                val name = msg.substringBefore("超过").substringBefore("exceeds").trim().ifBlank { "file" }
                context.getString(R.string.err_size_limit, name)
            }
            "找不到" in msg || "couldn't find" in lower ->
                context.getString(R.string.err_missing_entry, msg.substringAfter("找不到 ").ifBlank { msg.substringAfter("find ").ifBlank { "" } })
            "没有 arm64" in msg || "no arm64-v8a" in lower ->
                context.getString(
                    R.string.err_no_arm64_libs,
                    if ("仅发现" in msg || "Found only" in msg) msg else "",
                    if ("DEX" in msg) context.getString(R.string.err_dex_ignored) else context.getString(R.string.err_native_only),
                )
            "打开失败" in msg || "couldn't open" in lower -> context.getString(R.string.err_open_failed)
            "无法列出函数" in msg || "couldn't list functions" in lower -> context.getString(R.string.err_list_functions)
            "CFG 构建失败" in msg || "couldn't build the control-flow" in lower -> context.getString(R.string.err_cfg_failed)
            "路径重放失败" in msg || "couldn't replay" in lower -> context.getString(R.string.err_replay_failed)
            "文件超过" in msg || "exceeds the" in lower && "mib" in lower -> {
                val n = Regex("(\\d+)").find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 96
                context.getString(R.string.err_file_too_large, n)
            }
            looksLikeStackOrEnglishDump(msg) -> context.getString(R.string.err_generic)
            else -> if (hasCjk(msg)) msg else context.getString(R.string.err_generic)
        }
    }

    fun message(context: Context, error: AddressError): String = when (error) {
        AddressError.InvalidHex -> context.getString(R.string.jump_bad_hex)
        AddressError.OutOfFile -> context.getString(R.string.jump_out_of_file)
        AddressError.UnmappedFileOffset -> context.getString(R.string.jump_unmapped_file)
        AddressError.UnmappedRva -> context.getString(R.string.jump_unmapped_rva)
        AddressError.UnmappedVa -> context.getString(R.string.jump_unmapped_va)
        AddressError.NotExecutable -> context.getString(R.string.jump_not_code)
        AddressError.Unaligned -> context.getString(R.string.jump_unaligned)
    }

    private fun extractArch(msg: String): String {
        val m = Regex("当前架构：([^）。]+)").find(msg)
        if (m != null) return m.groupValues[1]
        val e = Regex("this file is ([^).]+)").find(msg)
        return e?.groupValues?.getOrNull(1) ?: "unknown"
    }

    private fun hasCjk(s: String) = s.any { it.code in 0x4E00..0x9FFF }

    private fun looksLikeStackOrEnglishDump(msg: String): Boolean {
        if (msg.contains('\n') && (msg.contains("at ") || msg.contains("Exception"))) return true
        if (msg.contains("Triton") || msg.contains("triton") || msg.contains("dominator") || msg.contains("Z3")) return true
        val letters = msg.count { it.isLetter() }
        val cjk = msg.count { it.code in 0x4E00..0x9FFF }
        return cjk == 0 && letters > 40
    }
}
