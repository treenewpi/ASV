package com.icode.asmviewer.data

class EngineNative {
    init {
        System.loadLibrary("icode_jni")
    }

    fun open(path: String): Long {
        val err = StringBuilder()
        val handle = nativeOpen(path, err)
        if (handle == 0L) throw EngineException(err.toString().ifBlank { "打开失败" })
        return handle
    }

    fun close(handle: Long) {
        if (handle != 0L) nativeClose(handle)
    }

    fun isArm64(handle: Long): Boolean = nativeIsArm64(handle)
    fun archName(handle: Long): String = nativeArchName(handle) ?: "unknown"
    fun entry(handle: Long): Long = nativeEntry(handle)
    fun fileSize(handle: Long): Long = nativeFileSize(handle)
    fun path(handle: Long): String = nativePath(handle) ?: ""
    fun fingerprint(handle: Long): String = nativeFingerprint(handle) ?: ""

    fun listFunctions(handle: Long): String {
        val err = StringBuilder()
        return nativeListFunctions(handle, err)
            ?: throw EngineException(err.toString().ifBlank { "无法列出函数" })
    }

    fun buildCfg(handle: Long, functionStart: Long, maxBlocks: Long, timeoutMs: Long): String {
        val err = StringBuilder()
        return nativeBuildCfg(handle, functionStart, maxBlocks, timeoutMs, err)
            ?: throw EngineException(err.toString().ifBlank { "CFG 构建失败" })
    }

    fun cancel(handle: Long) {
        if (handle != 0L) nativeCancel(handle)
    }

    fun buildCfgDot(handle: Long, functionStart: Long, maxBlocks: Long, timeoutMs: Long): String {
        val err = StringBuilder()
        return nativeBuildCfgDot(handle, functionStart, maxBlocks, timeoutMs, err)
            ?: throw EngineException(err.toString().ifBlank { "DOT 导出失败" })
    }

    fun replayTo(handle: Long, functionStart: Long, cursorAddress: Long, timeoutMs: Long): String {
        val err = StringBuilder()
        return nativeReplayTo(handle, functionStart, cursorAddress, timeoutMs, err)
            ?: throw EngineException(err.toString().ifBlank { "路径重放失败" })
    }

    fun disasmWindow(handle: Long, origin: Long, backward: Boolean, maxItems: Long): String {
        val err = StringBuilder()
        return nativeDisasmWindow(handle, origin, backward, maxItems, err)
            ?: throw EngineException(err.toString().ifBlank { "无法反汇编该窗口" })
    }

    fun simCreate(handle: Long, startPc: Long): Long {
        val err = StringBuilder()
        val sim = nativeSimCreate(handle, startPc, err)
        if (sim == 0L) throw EngineException(err.toString().ifBlank { "无法创建模拟会话" })
        return sim
    }

    fun simClose(sim: Long) {
        if (sim != 0L) nativeSimClose(sim)
    }

    fun simSetReg(sim: Long, name: String, value: Long) {
        val err = StringBuilder()
        if (!nativeSimSetReg(sim, name, value, err)) {
            throw EngineException(err.toString().ifBlank { "无法设置寄存器" })
        }
    }

    fun simSetMem(sim: Long, address: Long, data: ByteArray) {
        val err = StringBuilder()
        if (!nativeSimSetMem(sim, address, data, err)) {
            throw EngineException(err.toString().ifBlank { "无法写入内存" })
        }
    }

    fun simStep(sim: Long, mode: Int, limit: Int): Boolean {
        val err = StringBuilder()
        val ok = nativeSimStep(sim, mode, limit, err)
        if (!ok) throw EngineException(err.toString().ifBlank { "单步失败" })
        return ok
    }

    fun simReset(sim: Long, startPc: Long) = nativeSimReset(sim, startPc)
    fun simCancel(sim: Long) {
        if (sim != 0L) nativeSimCancel(sim)
    }

    fun simState(sim: Long): String = nativeSimState(sim) ?: "{}"

    private external fun nativeOpen(path: String, errorOut: StringBuilder): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeIsArm64(handle: Long): Boolean
    private external fun nativeArchName(handle: Long): String?
    private external fun nativeEntry(handle: Long): Long
    private external fun nativeFileSize(handle: Long): Long
    private external fun nativePath(handle: Long): String?
    private external fun nativeFingerprint(handle: Long): String?
    private external fun nativeListFunctions(handle: Long, errorOut: StringBuilder): String?
    private external fun nativeBuildCfg(
        handle: Long,
        functionStart: Long,
        maxBlocks: Long,
        timeoutMs: Long,
        errorOut: StringBuilder,
    ): String?
    private external fun nativeBuildCfgDot(
        handle: Long,
        functionStart: Long,
        maxBlocks: Long,
        timeoutMs: Long,
        errorOut: StringBuilder,
    ): String?
    private external fun nativeReplayTo(
        handle: Long,
        functionStart: Long,
        cursorAddress: Long,
        timeoutMs: Long,
        errorOut: StringBuilder,
    ): String?
    private external fun nativeDisasmWindow(
        handle: Long,
        origin: Long,
        backward: Boolean,
        maxItems: Long,
        errorOut: StringBuilder,
    ): String?
    private external fun nativeCancel(handle: Long)
    private external fun nativeSimCreate(handle: Long, startPc: Long, errorOut: StringBuilder): Long
    private external fun nativeSimClose(sim: Long)
    private external fun nativeSimSetReg(sim: Long, name: String, value: Long, errorOut: StringBuilder): Boolean
    private external fun nativeSimSetMem(sim: Long, address: Long, data: ByteArray, errorOut: StringBuilder): Boolean
    private external fun nativeSimStep(sim: Long, mode: Int, limit: Int, errorOut: StringBuilder): Boolean
    private external fun nativeSimReset(sim: Long, startPc: Long): Boolean
    private external fun nativeSimCancel(sim: Long)
    private external fun nativeSimState(sim: Long): String?
}

class EngineException(message: String) : Exception(message)
