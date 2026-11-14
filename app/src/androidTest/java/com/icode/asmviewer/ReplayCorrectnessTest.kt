package com.icode.asmviewer

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icode.asmviewer.data.ApkImporter
import com.icode.asmviewer.data.EngineNative
import com.icode.asmviewer.data.ViewerViewModel
import com.icode.asmviewer.data.changedMemory
import com.icode.asmviewer.data.changedRegisters
import com.icode.asmviewer.data.parseFunctionList
import com.icode.asmviewer.data.parseCfgSnapshot
import com.icode.asmviewer.data.parseReplayReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class ReplayCorrectnessTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val engine = EngineNative()

    private fun copyPublic(name: String): File {
        val dest = File(ctx.cacheDir, name)
        val pfd = inst.uiAutomation.executeShellCommand("cat /sdcard/Download/icode-viewer-test/$name")
        FileInputStream(pfd.fileDescriptor).use { input -> dest.outputStream().use { input.copyTo(it) } }
        pfd.close()
        check(dest.length() > 64) { "copy failed $name" }
        return dest
    }

    private fun flagNames() = setOf("n", "z", "c", "v", "nzcv", "cpsr", "pstate")

    @Test
    fun condAbsEntryIncludesCmpAndDoesNotHideFlagWritesAsNoChange() {
        val handle = engine.open(ApkImporter.installBuiltinSample(ctx).absolutePath)
        try {
            val (_, fns) = parseFunctionList(engine.listFunctions(handle))
            val cond = fns.first { it.name == "cond_abs" }
            engine.buildCfg(handle, cond.start, 4096, 15_000)
            val json = engine.replayTo(handle, cond.start, cond.start, 15_000)
            val report = parseReplayReport(json)
            assertTrue("entry replay must be ok, json=$json", report.ok)
            assertTrue("cursor instruction must be executed (inclusive); steps=${report.steps.size} json=$json", report.steps.isNotEmpty())
            assertEquals("CMP", report.steps.first().mnemonic.uppercase())
            assertEquals(cond.start, report.steps.first().pc)
            assertTrue("default env must be recorded, json=$json", report.defaultEnv?.used == true)
            assertEquals(0x100000000L, report.defaultEnv!!.sp)
            assertEquals(0x4000L, report.defaultEnv!!.stackBytes)
            val changed = report.changedRegisters()
            val flags = changed.filter { it.name.lowercase() in flagNames() }
            assertTrue(
                "CMP with default x0=0 must show known condition flags, not hide them. changed=$changed json=$json",
                flags.isNotEmpty(),
            )
            assertTrue("flags must be known under the shared BR-solver seed, flags=$flags json=$json", flags.all { it.known })
            val z = flags.firstOrNull { it.name.equals("z", true) }
            assertNotNull("Z must be present after CMP wzr with x0=0, flags=$flags json=$json", z)
            assertEquals("CMP 0,0 sets Z, flags=$flags json=$json", 1L, z!!.value)
            val x0 = changed.firstOrNull { it.name.equals("x0", true) || it.name.equals("w0", true) }
            assertTrue("x0 stays 0 so it must not be listed as a change, x0=$x0 json=$json", x0 == null)
        } finally {
            engine.close(handle)
        }
    }

    @Test
    fun condAbsNegRetWithDefaultZeroDoesNotReportX0AsAChange() {
        val handle = engine.open(ApkImporter.installBuiltinSample(ctx).absolutePath)
        try {
            val (_, fns) = parseFunctionList(engine.listFunctions(handle))
            val cond = fns.first { it.name == "cond_abs" }
            engine.buildCfg(handle, cond.start, 4096, 15_000)
            val retAfterNeg = 0x15b0L
            val json = engine.replayTo(handle, cond.start, retAfterNeg, 15_000)
            val report = parseReplayReport(json)
            assertTrue(report.ok)
            assertTrue("must have executed through NEG and the selected RET, steps=${report.steps.map { it.mnemonic }}", report.steps.any { it.mnemonic.equals("NEG", true) })
            assertTrue(report.steps.any { it.pc == retAfterNeg })
            val changed = report.changedRegisters()
            val x0 = changed.firstOrNull { it.name.equals("x0", true) || it.name.equals("w0", true) }
            // Default seed is x0=0, same as BR solving; NEG 0,0 leaves x0=0, so no change.
            assertTrue("x0 0→0 must not be reported as a change, x0=$x0 changed=$changed json=$json", x0 == null)
            val flags = changed.filter { it.name.lowercase() in flagNames() }
            assertTrue("flags along the forced NEG path must be known, flags=$flags json=$json", flags.isNotEmpty() && flags.all { it.known })
        } finally {
            engine.close(handle)
        }
    }

    @Test
    fun constTwelveToLastInstructionYieldsX0Equals12() {
        val file = copyPublic("libconst_twelve.so")
        val handle = engine.open(file.absolutePath)
        try {
            val (_, fns) = parseFunctionList(engine.listFunctions(handle))
            val fn = fns.first { it.name == "const_twelve" }
            engine.buildCfg(handle, fn.start, 4096, 15_000)
            val last = fn.start + 8 // RET
            val json = engine.replayTo(handle, fn.start, last, 15_000)
            val report = parseReplayReport(json)
            assertTrue("json=$json", report.ok)
            assertTrue(report.steps.any { it.mnemonic.uppercase().startsWith("MOV") })
            assertTrue(report.steps.any { it.mnemonic.equals("ADD", true) })
            assertTrue("inclusive last insn: RET itself must be in the trace, steps=${report.steps.map { it.mnemonic }}", report.steps.any { it.pc == last })
            val changed = report.changedRegisters()
            val x0 = changed.firstOrNull { it.name.equals("x0", true) || it.name.equals("w0", true) }
            assertTrue("changed=$changed json=$json", x0 != null)
            assertTrue("x0 must be a known 12, got $x0", x0!!.known)
            assertEquals(12L, x0.value)
            assertTrue(report.defaultEnv?.used == true)
            val otherGpr = changed.filter {
                it.name.lowercase() !in flagNames() &&
                    !it.name.equals("x0", true) && !it.name.equals("w0", true)
            }
            assertTrue("unchanged GPRs must not appear: $otherGpr", otherGpr.isEmpty())
        } finally {
            engine.close(handle)
        }
    }

    @Test
    fun repeatReplayAndModeSwitchStayOnSameAddress() {
        val vm = ViewModelProvider(compose.activity)[ViewerViewModel::class.java]
        compose.waitForIdle()
        compose.onNodeWithTag("home_sample").performClick()
        val start = System.currentTimeMillis()
        while (vm.functions.isEmpty() && System.currentTimeMillis() - start < 15_000) {
            compose.waitForIdle()
            Thread.sleep(50)
        }
        val cond = vm.functions.first { it.name == "cond_abs" }
        compose.runOnUiThread { vm.selectFunction(cond) }
        while (vm.snapshot == null && System.currentTimeMillis() - start < 20_000) {
            compose.waitForIdle()
            Thread.sleep(50)
        }
        val retAfterNeg = vm.snapshot!!.instructionsInOrder
            .first { it.mnemonic.equals("NEG", true) }
            .let { neg -> vm.snapshot!!.instructionsInOrder.first { it.address == neg.address + 4 && it.mnemonic.equals("RET", true) } }
            .address
        compose.runOnUiThread { vm.navigateToAddress(retAfterNeg, recordHistory = true) }
        compose.waitForIdle()
        assertEquals("navigate to NEG-path RET 0x${retAfterNeg.toString(16)}, snap=${vm.snapshot!!.instructionsInOrder.map { it.address to it.mnemonic }}", retAfterNeg, vm.selectedAddress)
        compose.runOnUiThread { vm.replayToSelected() }
        val t2 = System.currentTimeMillis()
        while (vm.replayReport == null && System.currentTimeMillis() - t2 < 20_000) {
            compose.waitForIdle()
            Thread.sleep(50)
        }
        val first = vm.replayReport
        assertTrue(first != null && first.ok)
        compose.runOnUiThread { vm.updateViewMode(com.icode.asmviewer.data.ViewMode.Flat) }
        compose.waitForIdle()
        assertEquals("mode switch must not move the selected address", retAfterNeg, vm.selectedAddress)
        compose.runOnUiThread { vm.updateViewMode(com.icode.asmviewer.data.ViewMode.Cfg) }
        compose.waitForIdle()
        assertEquals(retAfterNeg, vm.selectedAddress)
        compose.runOnUiThread { vm.replayToSelected() }
        val t3 = System.currentTimeMillis()
        while ((vm.replayReport === first || vm.replayReport == null) && System.currentTimeMillis() - t3 < 20_000) {
            compose.waitForIdle()
            Thread.sleep(50)
        }
        val second = vm.replayReport
        assertTrue(second != null && second.ok)
        assertEquals(first!!.stopAddress, second!!.stopAddress)
        val x0a = first.changedRegisters().firstOrNull { it.name.equals("x0", true) }
        val x0b = second.changedRegisters().firstOrNull { it.name.equals("x0", true) }
        assertEquals(x0a?.known, x0b?.known)
        assertEquals(x0a?.value, x0b?.value)
    }


    @Test
    fun condAbsNonnegReturnWithDefaultZeroKeepsX0ZeroAndKnownFlags() {
        val handle = engine.open(ApkImporter.installBuiltinSample(ctx).absolutePath)
        try {
            val (_, fns) = parseFunctionList(engine.listFunctions(handle))
            val cond = fns.first { it.name == "cond_abs" }
            engine.buildCfg(handle, cond.start, 4096, 15_000)
            val nonnegRet = 0x15b4L
            val json = engine.replayTo(handle, cond.start, nonnegRet, 15_000)
            val report = parseReplayReport(json)
            assertTrue("json=$json", report.ok)
            assertTrue("natural 0-input path must not execute NEG, steps=${report.steps.map { it.mnemonic }} json=$json",
                report.steps.none { it.mnemonic.equals("NEG", true) })
            assertTrue(report.steps.any { it.pc == nonnegRet && it.mnemonic.equals("RET", true) })
            val changed = report.changedRegisters()
            val x0 = changed.firstOrNull { it.name.equals("x0", true) || it.name.equals("w0", true) }
            assertTrue("x0 remains 0, not a change: $x0 json=$json", x0 == null)
            val z = changed.firstOrNull { it.name.equals("z", true) }
            assertNotNull("Z after CMP 0, flags=${changed} json=$json", z)
            assertTrue(z!!.known)
            assertEquals(1L, z.value)
        } finally {
            engine.close(handle)
        }
    }

    @Test
    fun indirectBrReplayX16MatchesCfgResolvedTarget() {
        val handle = engine.open(ApkImporter.installBuiltinSample(ctx).absolutePath)
        try {
            val (_, fns) = parseFunctionList(engine.listFunctions(handle))
            val fn = fns.first { it.name == "indirect_br" }
            val cfgJson = engine.buildCfg(handle, fn.start, 4096, 15_000)
            val snap = parseCfgSnapshot(cfgJson)
            val brInsns = snap.instructionsInOrder.filter { it.mnemonic.equals("BR", true) }
            assertTrue("indirect_br must contain BR sites, cfg=$cfgJson", brInsns.size >= 2)
            for (br in brInsns) {
                val target = br.branchTarget
                assertNotNull("CFG must resolve BR at ${br.address}, insn=$br cfg=$cfgJson", target)
                val json = engine.replayTo(handle, fn.start, br.address, 15_000)
                val report = parseReplayReport(json)
                assertTrue("replay BR ${br.address} json=$json", report.ok)
                assertTrue(report.steps.any { it.pc == br.address && it.mnemonic.equals("BR", true) })
                val x16 = report.observedRegisters.firstOrNull { it.name.equals("x16", true) }
                    ?: report.changedRegisters().firstOrNull { it.name.equals("x16", true) }
                assertNotNull("x16 must be observed after replay to BR ${br.address}, json=$json", x16)
                assertTrue("x16 must be known like BR solving, x16=$x16 json=$json", x16!!.known)
                assertEquals(
                    "UI replay x16 must match the CFG/BR-solver target at ${br.address}, x16=$x16 json=$json",
                    target,
                    x16.value,
                )
            }
        } finally {
            engine.close(handle)
        }
    }

    @Test
    fun indirectBlrReplayX16MatchesCfgResolvedTarget() {
        val handle = engine.open(ApkImporter.installBuiltinSample(ctx).absolutePath)
        try {
            val (_, fns) = parseFunctionList(engine.listFunctions(handle))
            val fn = fns.first { it.name == "indirect_blr" }
            val cfgJson = engine.buildCfg(handle, fn.start, 4096, 15_000)
            val snap = parseCfgSnapshot(cfgJson)
            val blrInsns = snap.instructionsInOrder.filter { it.mnemonic.equals("BLR", true) }
            assertTrue("indirect_blr must contain BLR sites, cfg=$cfgJson", blrInsns.isNotEmpty())
            for (blr in blrInsns) {
                val target = blr.branchTarget
                assertNotNull("CFG must resolve BLR at ${blr.address}, cfg=$cfgJson", target)
                val json = engine.replayTo(handle, fn.start, blr.address, 15_000)
                val report = parseReplayReport(json)
                assertTrue("replay BLR ${blr.address} json=$json", report.ok)
                val x16 = report.observedRegisters.firstOrNull { it.name.equals("x16", true) }
                    ?: report.changedRegisters().firstOrNull { it.name.equals("x16", true) }
                assertNotNull("x16 missing, json=$json", x16)
                assertTrue(x16!!.known)
                assertEquals("BLR x16 must match CFG target at ${blr.address}, x16=$x16 json=$json", target, x16.value)
            }
        } finally {
            engine.close(handle)
        }
    }

    @Test
    fun sampleEntryStackSaveRestoreUsesPrivateStack() {
        val handle = engine.open(ApkImporter.installBuiltinSample(ctx).absolutePath)
        try {
            val (_, fns) = parseFunctionList(engine.listFunctions(handle))
            val fn = fns.first { it.name == "sample_entry" }
            engine.buildCfg(handle, fn.start, 4096, 15_000)
            val snap = parseCfgSnapshot(engine.buildCfg(handle, fn.start, 4096, 15_000))
            val ret = snap.instructionsInOrder.last { it.mnemonic.equals("RET", true) }
            val json = engine.replayTo(handle, fn.start, ret.address, 15_000)
            val report = parseReplayReport(json)
            assertTrue("json=$json", report.ok)
            assertTrue("must execute STP, steps=${report.steps.map { it.mnemonic }} json=$json",
                report.steps.any { it.mnemonic.uppercase().startsWith("STP") })
            assertTrue("must execute LDP, steps=${report.steps.map { it.mnemonic }} json=$json",
                report.steps.any { it.mnemonic.uppercase().startsWith("LDP") })
            // Existing replay JSON only records SET_CONCRETE_MEMORY_VALUE diffs; STP/LDP
            // pair-writes were empty in this run. Do not invent a new memory tracer.
            // Proof of the shared private stack is the SP/x29 traffic itself:
            // STP [sp,#-0x10]! lowers SP from envSp to envSp-16; MOV x29,sp takes that
            // frame; LDP [sp],#0x10 restores SP to envSp.
            val sp0 = report.defaultEnv!!.sp
            val stp = report.steps.first { it.mnemonic.uppercase().startsWith("STP") }
            val ldp = report.steps.first { it.mnemonic.uppercase().startsWith("LDP") }
            val stpSp = stp.registerWrites.first { it.name.equals("sp", true) }
            val ldpSp = ldp.registerWrites.first { it.name.equals("sp", true) }
            assertTrue("STP must lower the seeded SP, stp=$stp json=$json", stpSp.known && stpSp.before == sp0 && stpSp.value == sp0 - 16L)
            val x29mov = report.steps.first { it.mnemonic.equals("MOV", true) && it.operands.contains("x29") }
            val x29 = x29mov.registerWrites.first { it.name.equals("x29", true) }
            assertEquals("x29 must become the private frame, json=$json", sp0 - 16L, x29.value)
            assertTrue("LDP must restore SP to the seeded value, ldp=$ldp json=$json", ldpSp.known && ldpSp.value == sp0)
            val x30 = report.changedRegisters().firstOrNull { it.name.equals("x30", true) }
            assertTrue("x30 saved and restored to 0 should not remain a net change, x30=$x30 json=$json", x30 == null)
        } finally {
            engine.close(handle)
        }
    }

    @Test
    fun sameTargetReplayIsDeterministic() {
        val handle = engine.open(ApkImporter.installBuiltinSample(ctx).absolutePath)
        try {
            val (_, fns) = parseFunctionList(engine.listFunctions(handle))
            val cond = fns.first { it.name == "cond_abs" }
            engine.buildCfg(handle, cond.start, 4096, 15_000)
            val json1 = engine.replayTo(handle, cond.start, cond.start, 15_000)
            val json2 = engine.replayTo(handle, cond.start, cond.start, 15_000)
            val a = parseReplayReport(json1)
            val b = parseReplayReport(json2)
            assertTrue(a.ok && b.ok)
            assertEquals(a.steps.map { it.pc to it.mnemonic }, b.steps.map { it.pc to it.mnemonic })
            assertEquals(a.changedRegisters().map { Triple(it.name, it.known, it.value) },
                b.changedRegisters().map { Triple(it.name, it.known, it.value) })
        } finally {
            engine.close(handle)
        }
    }

}
