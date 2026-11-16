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
import com.icode.asmviewer.ui.formatReplayTransition
import com.icode.asmviewer.ui.formatReplayInitial
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
    fun condAbsNegPathIsReachedWithAutomaticallySolvedNegativeInput() {
        val handle = engine.open(ApkImporter.installBuiltinSample(ctx).absolutePath)
        try {
            val (_, fns) = parseFunctionList(engine.listFunctions(handle))
            val cond = fns.first { it.name == "cond_abs" }
            engine.buildCfg(handle, cond.start, 4096, 15_000)
            val retAfterNeg = 0x15b0L
            val json = engine.replayTo(handle, cond.start, retAfterNeg, 15_000)
            val report = parseReplayReport(json)
            assertTrue("NEG-path RET must be reached by an automatically solved starting state, json=$json", report.ok)
            assertEquals("kind=$json", "concrete_solved", report.kind)
            assertTrue("must execute NEG, steps=${report.steps.map { it.pc to it.mnemonic }} json=$json",
                report.steps.any { it.mnemonic.equals("NEG", true) })
            assertTrue("must include the selected RET, json=$json",
                report.steps.any { it.pc == retAfterNeg && it.mnemonic.equals("RET", true) })
            assertTrue("must not append a dummy 0 exception after RET, steps=${report.steps.map { it.pc to it.mnemonic }} json=$json",
                report.steps.none { it.pc == 0L || it.mnemonic.equals("exception", true) })
            for (step in report.steps) {
                val names = step.registerWrites.map { it.name.lowercase() }
                assertEquals("flag/register rows must not be duplicated in ${step.mnemonic} $names json=$json", names.distinct(), names)
            }
            val initials = report.initialAssignments
            assertTrue("path-specific starting registers must be listed, json=$json", initials.isNotEmpty())
            val x0init = initials.firstOrNull { it.name.equals("x0", true) || it.name.equals("w0", true) }
            assertNotNull("starting x0/w0 must be present, initials=$initials json=$json", x0init)
            val w0 = (x0init!!.value.toULong() and 0xffffffffuL).toInt()
            assertTrue("starting w0 must be negative to take the NEG path, init=${formatReplayInitial(x0init.name, x0init.value, true, "?")} json=$json", w0 < 0)
            assertTrue(report.defaultEnv?.used != true)
            val unknown = "unknown"
            val shown = formatReplayInitial(x0init.name, x0init.value, true, unknown)
            assertTrue("initial value must be hex, shown=$shown", shown.contains("0x"))
            assertFalse("must not leak a leading decimal minus, shown=$shown", shown.contains(":-") || shown.contains(":-1"))
            val neg = report.steps.first { it.mnemonic.equals("NEG", true) }
            val w0write = neg.registerWrites.first { it.name.equals("x0", true) || it.name.equals("w0", true) }
            val beforeW = (w0write.before ?: x0init.value).toULong() and 0xffffffffuL
            val afterW = w0write.value.toULong() and 0xffffffffuL
            val expectedAbs = ((-beforeW.toInt()).toUInt().toULong()) and 0xffffffffuL
            assertEquals("NEG must write the absolute value, before=$beforeW after=$afterW json=$json", expectedAbs, afterW)
            assertTrue(afterW > 0uL)
            val change = formatReplayTransition(w0write.name, w0write.beforeKnown, w0write.before, w0write.known, w0write.value, unknown, w0write.sizeBytes)
            assertTrue("details change must be hex, change=$change", change.contains("0x") && "→" in change)
            assertTrue("change must not use decimal before/after, change=$change", !change.contains(":5→") && change.contains("0x"))
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
        val takenRet = vm.snapshot!!.instructionsInOrder
            .last { it.mnemonic.equals("RET", true) }
            .address
        compose.runOnUiThread { vm.navigateToAddress(takenRet, recordHistory = true) }
        compose.waitForIdle()
        assertEquals("navigate to taken RET 0x${takenRet.toString(16)}, snap=${vm.snapshot!!.instructionsInOrder.map { it.address to it.mnemonic }}", takenRet, vm.selectedAddress)
        compose.runOnUiThread { vm.replayToSelected() }
        val t2 = System.currentTimeMillis()
        while (vm.replayReport == null && System.currentTimeMillis() - t2 < 20_000) {
            compose.waitForIdle()
            Thread.sleep(50)
        }
        val first = vm.replayReport
        assertTrue("UI replay of the taken path must succeed, report=$first", first != null && first.ok)
        assertTrue(first!!.steps.none { it.mnemonic.equals("NEG", true) })
        compose.runOnUiThread { vm.updateViewMode(com.icode.asmviewer.data.ViewMode.Flat) }
        compose.waitForIdle()
        assertEquals("mode switch must not move the selected address", takenRet, vm.selectedAddress)
        compose.runOnUiThread { vm.updateViewMode(com.icode.asmviewer.data.ViewMode.Cfg) }
        compose.waitForIdle()
        assertEquals(takenRet, vm.selectedAddress)
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
            assertTrue(report.kind == "concrete" || report.kind == "concrete_solved")
            if (report.kind == "concrete") {
                assertTrue(report.initialAssignments.isEmpty())
            }
            assertTrue("must not append a dummy 0 exception, steps=${report.steps.map { it.pc to it.mnemonic }} json=$json",
                report.steps.none { it.pc == 0L || it.mnemonic.equals("exception", true) })
            for (step in report.steps) {
                val names = step.registerWrites.map { it.name.lowercase() }
                assertEquals("duplicate flag/register rows in ${step.mnemonic}: $names json=$json", names.distinct(), names)
            }
            val zChange = report.steps.flatMap { step ->
                step.registerWrites.filter { it.name.equals("z", true) }.map { w ->
                    formatReplayTransition(w.name, w.beforeKnown, w.before, w.known, w.value, "unknown", w.sizeBytes)
                }
            }
            assertTrue("flag changes must be hex, $zChange", zChange.any { it == "z:0x0→0x1" || it.startsWith("z:0x") })
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
            var reached = 0
            for (br in brInsns) {
                val target = br.branchTarget
                assertNotNull("CFG must resolve BR at ${br.address}, insn=$br cfg=$cfgJson", target)
                val json = engine.replayTo(handle, fn.start, br.address, 15_000)
                val report = parseReplayReport(json)
                if (report.ok) {
                    reached += 1
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
                } else {
                    assertEquals("not_reached", report.kind)
                    assertTrue("untaken BR must not be force-executed, json=$json",
                        report.steps.none { it.pc == br.address })
                    assertTrue("the taken BR must still appear, json=$json",
                        report.steps.any { it.mnemonic.equals("BR", true) })
                }
            }
            assertEquals("default w1=0 should take exactly one BR arm, cfg=$cfgJson", 1, reached)
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
            var reached = 0
            for (blr in blrInsns) {
                val target = blr.branchTarget
                assertNotNull("CFG must resolve BLR at ${blr.address}, cfg=$cfgJson", target)
                val json = engine.replayTo(handle, fn.start, blr.address, 15_000)
                val report = parseReplayReport(json)
                if (report.ok) {
                    reached += 1
                    assertTrue(report.steps.any { it.pc == blr.address && it.mnemonic.equals("BLR", true) })
                    val x16 = report.observedRegisters.firstOrNull { it.name.equals("x16", true) }
                        ?: report.changedRegisters().firstOrNull { it.name.equals("x16", true) }
                    assertNotNull("x16 missing, json=$json", x16)
                    assertTrue(x16!!.known)
                    assertEquals("BLR x16 must match CFG target at ${blr.address}, x16=$x16 json=$json", target, x16.value)
                } else {
                    assertEquals("not_reached", report.kind)
                    assertTrue("untaken BLR must not be force-executed, json=$json",
                        report.steps.none { it.pc == blr.address })
                    assertTrue("the taken BLR must still appear, json=$json",
                        report.steps.any { it.mnemonic.equals("BLR", true) })
                    assertTrue("must stop instead of spinning on RET after BLR clobbers LR, steps=${report.steps.size}",
                        report.steps.size < 256)
                }
            }
            assertEquals("default w1=0 should take exactly one BLR arm, cfg=$cfgJson", 1, reached)
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
            // indirect_blr uses BLR without saving x30, so its trailing RET
            // returns to itself. Real execution therefore never reaches
            // sample_entry's LDP/RET or the later *2; do not invent that path.
            assertFalse("sample_entry RET is not reached after BLR clobbers LR, json=$json", report.ok)
            assertEquals("not_reached", report.kind)
            assertTrue("must stop instead of spinning, steps=${report.steps.size} json=$json", report.steps.size < 256)
            assertTrue("must execute STP, steps=${report.steps.map { it.mnemonic }} json=$json",
                report.steps.any { it.mnemonic.uppercase().startsWith("STP") })
            assertTrue("mapped BL must be executed, not skipped, steps=${report.steps.map { it.mnemonic to it.skipped }} json=$json",
                report.steps.any { it.mnemonic.equals("BL", true) && !it.skipped })
            assertTrue("must enter linear_add, steps=${report.steps.map { it.mnemonic }} json=$json",
                report.steps.any { it.mnemonic.equals("ADD", true) })
            assertFalse("must not invent the function epilogue, json=$json",
                report.steps.any { it.mnemonic.uppercase().startsWith("LDP") })
            val x0 = report.changedRegisters().firstOrNull { it.name.equals("x0", true) || it.name.equals("w0", true) }
            assertTrue("following linear_add/cond_abs/plus10 must change x0, x0=$x0 json=$json", x0 != null && x0.known)
            assertEquals("0+3, abs, +10 => 13 (stuck before *2), x0=$x0 json=$json", 13L, x0!!.value)
            val sp0 = report.defaultEnv!!.sp
            val stp = report.steps.first { it.mnemonic.uppercase().startsWith("STP") }
            val stpSp = stp.registerWrites.first { it.name.equals("sp", true) }
            assertTrue("STP must lower the seeded SP, stp=$stp json=$json", stpSp.known && stpSp.before == sp0 && stpSp.value == sp0 - 16L)
            val x29mov = report.steps.first { it.mnemonic.equals("MOV", true) && it.operands.contains("x29") }
            val x29 = x29mov.registerWrites.first { it.name.equals("x29", true) }
            assertEquals("x29 must become the private frame, json=$json", sp0 - 16L, x29.value)
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


    @Test
    fun misalignedCursorIsNotForcedAndDoesNotInventException() {
        val handle = engine.open(ApkImporter.installBuiltinSample(ctx).absolutePath)
        try {
            val (_, fns) = parseFunctionList(engine.listFunctions(handle))
            val cond = fns.first { it.name == "cond_abs" }
            engine.buildCfg(handle, cond.start, 4096, 15_000)
            val json = engine.replayTo(handle, cond.start, cond.start + 1, 5_000)
            val report = parseReplayReport(json)
            assertFalse("an instruction-sized PC never equals a mid-instruction address, json=$json", report.ok)
            assertTrue(
                "must distinguish a failed reach honestly, kind=${report.kind} json=$json",
                report.kind in setOf("infeasible", "not_reached", "timeout", "unsupported"),
            )
            assertTrue("must not append a dummy 0 exception, steps=${report.steps.map { it.pc to it.mnemonic }} json=$json",
                report.steps.none { it.pc == 0L || it.mnemonic.equals("exception", true) })
        } finally {
            engine.close(handle)
        }
    }

    @Test
    fun replayShortTimeoutDoesNotRunAway() {
        val handle = engine.open(ApkImporter.installBuiltinSample(ctx).absolutePath)
        try {
            val (_, fns) = parseFunctionList(engine.listFunctions(handle))
            val fn = fns.first { it.name == "sample_entry" }
            engine.buildCfg(handle, fn.start, 4096, 15_000)
            val started = System.currentTimeMillis()
            val json = engine.replayTo(handle, fn.start, fn.start, 50)
            val elapsed = System.currentTimeMillis() - started
            assertTrue("budget must be honored, elapsed=${elapsed}ms json=$json", elapsed < 8_000)
            val report = parseReplayReport(json)
            if (!report.ok) {
                assertTrue("kind=${report.kind} json=$json", report.kind in setOf("timeout", "cancelled", "not_reached", "unsupported"))
            }
        } finally {
            engine.close(handle)
        }
    }

}
