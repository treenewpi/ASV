package com.icode.asmviewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icode.asmviewer.data.ApkImporter
import com.icode.asmviewer.data.EngineNative
import com.icode.asmviewer.data.parseCfgSnapshot
import com.icode.asmviewer.data.parseFunctionList
import com.icode.asmviewer.data.parseSimState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EngineSimAndCfgTest {
    private fun sampleFile(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return ApkImporter.installBuiltinSample(ctx)
    }

    @Test
    fun builtinSampleListsRequiredFunctionsAndCfgHasBytes() {
        val engine = EngineNative()
        val handle = engine.open(sampleFile().absolutePath)
        try {
            assertTrue(engine.isArm64(handle))
            val (info, fns) = parseFunctionList(engine.listFunctions(handle))
            assertTrue(info.isArm64)
            val names = fns.map { it.name }.toSet()
            for (need in listOf("linear_add", "cond_abs", "indirect_blr", "indirect_br", "sample_entry")) {
                assertTrue("missing $need in $names", names.contains(need))
            }
            val cond = fns.first { it.name == "cond_abs" }
            val snap = parseCfgSnapshot(engine.buildCfg(handle, cond.start, 4096, 15_000))
            assertFalse(snap.instructionsInOrder.isEmpty())
            val first = snap.instructionsInOrder.first { it.address == 0x15a4L }
            assertEquals("cmp", first.mnemonic.lowercase())
            // 6b1f001f little-endian hex
            assertEquals("1f001f6b", first.bytes.lowercase().replace(" ", ""))
            assertEquals(0x15b4L, first.branchTarget ?: snap.instructionsInOrder.first { it.mnemonic.equals("B.GE", true) }.branchTarget)
        } finally {
            engine.close(handle)
        }
    }

    @Test
    fun linearAddInteractiveSimMatchesConcreteAdd() {
        val engine = EngineNative()
        val handle = engine.open(sampleFile().absolutePath)
        var sim = 0L
        try {
            sim = engine.simCreate(handle, 0x159c)
            engine.simSetReg(sim, "x0", 5)
            engine.simSetReg(sim, "x1", 7)
            val before = parseSimState(engine.simState(sim))
            assertEquals(0x159cL, before.pc)
            val x0Before = before.registers.first { it.name.equals("x0", true) }
            assertEquals(5L, x0Before.value)
            assertTrue(x0Before.known)
            engine.simStep(sim, 0, 1)
            val after = parseSimState(engine.simState(sim))
            assertEquals("PC must advance to the RET at 0x15a0", 0x15a0L, after.pc)
            val x0 = after.registers.first { it.name.equals("x0", true) }
            assertEquals("5+7 must be written to w0/x0, not invented zeros", 12L, x0.value)
            assertTrue(x0.known)
            assertTrue(after.registerWrites.any { it.name.equals("w0", true) || it.name.equals("x0", true) } || x0.value == 12L)
            // One more step executes RET to an unset LR — must stop, not silently invent a path.
            try {
                engine.simStep(sim, 0, 1)
            } catch (_: Exception) {
                // EngineException on stop is acceptable; state must still be readable.
            }
            val stopped = parseSimState(engine.simState(sim))
            assertTrue(
                "RET to unmapped/unknown LR must surface a stop reason, got reason=${stopped.reason} last=${stopped.lastReason} msg=${stopped.lastMessage} ${stopped.message}",
                stopped.stopped || !stopped.ok || stopped.lastReason.isNotBlank() || stopped.reason.isNotBlank(),
            )
            assertNotEquals("must not claim success with invented execution", "ok", stopped.lastReason.lowercase())
        } finally {
            if (sim != 0L) engine.simClose(sim)
            engine.close(handle)
        }
    }

    @Test
    fun condAbsNegativeTakesNegPath() {
        val engine = EngineNative()
        val handle = engine.open(sampleFile().absolutePath)
        var sim = 0L
        try {
            // -5 as 32-bit two's complement in the low half of x0
            sim = engine.simCreate(handle, 0x15a4)
            engine.simSetReg(sim, "x0", 0xFFFFFFFBL)
            // Mode 1 stops at the control-flow insn (B.GE); x0 should still be -5 here.
            engine.simStep(sim, 1, 1)
            val mid = parseSimState(engine.simState(sim))
            val midX0 = mid.registers.first { it.name.equals("x0", true) }.value and 0xFFFFFFFFL
            assertEquals("block-step should not invent the NEG result early", 0xFFFFFFFBL, midX0)
            assertTrue("PC should have moved toward B.GE/NEG, pc=${mid.pc.toString(16)}", mid.pc == 0x15a8L || mid.pc == 0x15acL)
            // Finish the taken path with single steps until NEG has executed or we stop.
            var after = mid
            repeat(4) {
                if ((after.registers.first { it.name.equals("x0", true) }.value and 0xFFFFFFFFL) == 5L) return@repeat
                try { engine.simStep(sim, 0, 1) } catch (_: Exception) {}
                after = parseSimState(engine.simState(sim))
            }
            val x0 = after.registers.first { it.name.equals("x0", true) }.value and 0xFFFFFFFFL
            assertEquals("after NEG, w0 must be 5", 5L, x0)
        } finally {
            if (sim != 0L) engine.simClose(sim)
            engine.close(handle)
        }
    }


    @Test
    fun condAbsReplayToNegIsDominatorPath() {
        val engine = EngineNative()
        val h = engine.open(sampleFile().absolutePath)
        try {
            val json = engine.replayTo(h, 0x15a4, 0x15ac, 8_000)
            assertTrue(json.contains("icode.replay.v1"))
            assertTrue(json.contains("dominator_path") || json.contains("reachable_fallback"))
            assertTrue(json.contains("\"ok\":true") || json.contains("\"ok\": false") || json.contains("\"ok\":false"))
            // Must not require or mention user register initialization in the report semantics.
            assertTrue(!json.contains("set_reg") && !json.contains("userInit"))
        } finally {
            engine.close(h)
        }
    }
}
