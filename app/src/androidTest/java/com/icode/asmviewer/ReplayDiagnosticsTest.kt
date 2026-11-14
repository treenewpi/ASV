package com.icode.asmviewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.icode.asmviewer.data.ApkImporter
import com.icode.asmviewer.data.EngineNative
import com.icode.asmviewer.data.changedRegisters
import com.icode.asmviewer.data.parseFunctionList
import com.icode.asmviewer.data.parseReplayReport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class ReplayDiagnosticsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private fun outDir(): File {
        val dir = ctx.getExternalFilesDir("replay-diag") ?: ctx.filesDir
        dir.mkdirs()
        return dir
    }

    private fun copyFromPublic(name: String): File {
        val dest = File(ctx.cacheDir, name)
        val pfd = inst.uiAutomation.executeShellCommand(
            "cat /sdcard/Download/icode-viewer-test/$name",
        )
        FileInputStream(pfd.fileDescriptor).use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        pfd.close()
        check(dest.length() > 64) { "failed to copy $name size=${dest.length()}" }
        return dest
    }

    private fun summarize(label: String, json: String): String {
        val obj = JSONObject(json)
        val steps = obj.optJSONArray("steps") ?: JSONArray()
        val sb = StringBuilder()
        sb.append("==== $label ====\n")
        sb.append("ok=${obj.optBoolean("ok")} cursor=${obj.optLong("cursorAddress").toString(16)} stop=${obj.optLong("stopAddress").toString(16)} targetBlock=${obj.optLong("targetBlockStart").toString(16)} steps=${steps.length()}\n")
        sb.append("uniquePath=${obj.opt("uniquePath")} pathAmbiguous=${obj.opt("pathAmbiguous")}\n")
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            val writes = s.optJSONArray("registerWrites") ?: JSONArray()
            sb.append(String.format("  step[%d] pc=0x%x %s %s skipped=%s writes=%d\n",
                i, s.optLong("pc"), s.optString("mnemonic"), s.optString("operands"),
                s.optBoolean("skipped"), writes.length()))
            for (j in 0 until writes.length()) {
                val w = writes.getJSONObject(j)
                sb.append("    write $w\n")
            }
        }
        val observed = obj.optJSONArray("observedRegisters") ?: JSONArray()
        sb.append("observedRegisters=$observed\n")
        val report = parseReplayReport(json)
        val changed = report.changedRegisters()
        sb.append("kotlin changedRegisters(${changed.size}): " +
            changed.joinToString { "${it.name} known=${it.known} before=${it.before}/${it.beforeKnown} after=${it.value}" } + "\n")
        return sb.toString()
    }

    @Test
    fun dumpRawReplayJson() {
        val engine = EngineNative()
        val dir = outDir()
        val log = StringBuilder()

        val builtin = ApkImporter.installBuiltinSample(ctx)
        val h1 = engine.open(builtin.absolutePath)
        try {
            val (_, fns) = parseFunctionList(engine.listFunctions(h1))
            val cond = fns.first { it.name == "cond_abs" }
            log.append("cond_abs start=0x${cond.start.toString(16)} size=${cond.size}\n")
            engine.buildCfg(h1, cond.start, 4096, 15_000)
            for (addr in listOf(0x15a4L, 0x15a8L, 0x15acL, 0x15b0L, 0x15b4L)) {
                val json = engine.replayTo(h1, cond.start, addr, 15_000)
                File(dir, "cond_${addr.toString(16)}.json").writeText(json)
                log.append(summarize("cond_abs @ 0x${addr.toString(16)}", json))
            }
        } finally {
            engine.close(h1)
        }

        val constFile = copyFromPublic("libconst_twelve.so")
        val h2 = engine.open(constFile.absolutePath)
        try {
            val (_, fns) = parseFunctionList(engine.listFunctions(h2))
            val fn = fns.first { it.name == "const_twelve" }
            log.append("const_twelve start=0x${fn.start.toString(16)} size=${fn.size}\n")
            engine.buildCfg(h2, fn.start, 4096, 15_000)
            val addrs = listOf(fn.start, fn.start + 4, fn.start + 8)
            for (addr in addrs) {
                val json = engine.replayTo(h2, fn.start, addr, 15_000)
                File(dir, "const_${addr.toString(16)}.json").writeText(json)
                log.append(summarize("const_twelve @ 0x${addr.toString(16)}", json))
            }
        } finally {
            engine.close(h2)
        }

        File(dir, "summary.txt").writeText(log.toString())
        // Also copy a short form to logcat-friendly length via file only.
        check(File(dir, "summary.txt").length() > 0)
    }
}
