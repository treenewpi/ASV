package com.icode.asmviewer

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.icode.asmviewer.data.ViewMode
import com.icode.asmviewer.data.ViewerViewModel
import com.icode.asmviewer.data.changedRegisters
import com.icode.asmviewer.data.formatHex
import com.icode.asmviewer.data.formatWideHex
import com.icode.asmviewer.ui.formatReplayRegLine
import com.icode.asmviewer.ui.formatReplayTransition
import com.icode.asmviewer.ui.formatReplayScalar
import com.icode.asmviewer.ui.formatReplayMemBytes
import com.icode.asmviewer.ui.formatReplayInitial
import com.icode.asmviewer.data.parseJsonU64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class ReplayDetailsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    private fun vm(): ViewerViewModel =
        ViewModelProvider(compose.activity)[ViewerViewModel::class.java]

    private fun waitUntil(timeoutMs: Long = 15_000, label: String, predicate: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (predicate()) return
            compose.waitForIdle()
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $label")
    }

    /** Only operate ASV. If another app took the window, stop this step; do not tap it. */
    private fun requireAsvFocused(step: String): Boolean {
        val pkg = device.currentPackageName ?: ""
        if (pkg == "com.icode.asmviewer" || pkg.endsWith(".test")) return true
        System.err.println("ASV_FOCUS_LOST at $step: currentPackage=$pkg — stopping this step, not tapping the other window")
        return false
    }

    private fun copyPublic(name: String): File {
        val dest = File(ctx.cacheDir, name)
        val candidates = listOf(
            "/sdcard/Download/ASV-test/$name",
            "/sdcard/Download/icode-viewer-test/$name",
        )
        var last = ""
        for (path in candidates) {
            val pfd = inst.uiAutomation.executeShellCommand("cat $path")
            FileInputStream(pfd.fileDescriptor).use { input -> dest.outputStream().use { input.copyTo(it) } }
            pfd.close()
            if (dest.length() > 64) return dest
            last = "$path len=${dest.length()}"
        }
        throw AssertionError("copy failed $name ($last)")
    }

    private fun clipboardText(): String {
        var text = ""
        compose.runOnUiThread {
            val cm = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text = cm.primaryClip?.getItemAt(0)?.coerceToText(compose.activity)?.toString().orEmpty()
        }
        compose.waitForIdle()
        return text
    }

    private fun openConstTwelveAndReplayToRet() {
        val so = copyPublic("libconst_twelve.so")
        compose.runOnUiThread { vm().openElfFromFile(so, "libconst_twelve.so") }
        waitUntil(label = "const_twelve listed") { vm().functions.any { it.name == "const_twelve" } }
        val fn = vm().functions.first { it.name == "const_twelve" }
        compose.runOnUiThread { vm().selectFunction(fn) }
        waitUntil(label = "cfg for const_twelve") { vm().snapshot != null && vm().selectedFunction?.name == "const_twelve" }
        val ret = vm().snapshot!!.instructionsInOrder.last()
        compose.runOnUiThread { vm().selectAddress(ret.address) }
        compose.waitForIdle()
        compose.runOnUiThread { vm().updateViewMode(ViewMode.Flat) }
        compose.runOnUiThread { vm().replayToSelected() }
        waitUntil(label = "replay report") { vm().replayReport != null && vm().busyMessage == null }
        compose.waitForIdle()
    }

    @Test
    fun constTwelveDetailsPageShowsFiveToTwelveAndBackKeepsResult() {
        openConstTwelveAndReplayToRet()
        Assume.assumeTrue("ASV lost focus before assertions", requireAsvFocused("after-replay"))
        val report = vm().replayReport!!
        assertTrue("replay must succeed, ok=${report.ok}", report.ok)
        val unknown = compose.activity.getString(R.string.replay_reg_unknown)
        val detailsLabel = compose.activity.getString(R.string.replay_details)
        val x0 = report.changedRegisters().first { it.name.equals("x0", true) || it.name.equals("w0", true) }
        assertTrue(x0.known)
        assertEquals(12L, x0.value)
        val mainLine = formatReplayRegLine(x0.name, x0.known, x0.value, unknown)
        assertEquals("${x0.name}  ${formatWideHex(12L)}", mainLine)

        val selectedBefore = vm().selectedAddress
        compose.onNodeWithTag("replay_panel").assertIsDisplayed()
        compose.onNodeWithTag("replay_reg_${x0.name}").assertIsDisplayed()
        compose.onNodeWithText(formatWideHex(12L), substring = true).assertIsDisplayed()
        compose.onAllNodesWithText(detailsLabel).fetchSemanticsNodes().let { nodes ->
            assertTrue("right-side $detailsLabel entry should be visible", nodes.isNotEmpty())
        }

        val hiddenPrompts = listOf(
            compose.activity.getString(R.string.replay_status_ok),
            compose.activity.getString(R.string.replay_status_default_env),
            compose.activity.getString(R.string.replay_default_env_title),
            compose.activity.getString(R.string.replay_regs_title),
        )
        hiddenPrompts.forEach { prompt ->
            compose.onAllNodesWithText(prompt, substring = true).fetchSemanticsNodes().let { nodes ->
                assertTrue("compact result must not show '$prompt'", nodes.isEmpty())
            }
        }

        // Details is a separate page, not an inline expansion.
        assertEquals(0, compose.onAllNodesWithTag("replay_details_page").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("replay_details_list").fetchSemanticsNodes().size)
        compose.onNodeWithTag("replay_details_toggle").assertIsDisplayed().performClick()
        compose.waitForIdle()
        if (!requireAsvFocused("open-details")) return
        assertTrue(vm().replayDetailsOpen)
        compose.onNodeWithTag("replay_details_page").assertIsDisplayed()
        compose.onNodeWithTag("replay_details_list").assertIsDisplayed()

        val mov = report.steps.first { it.mnemonic.uppercase().startsWith("MOV") }
        val add = report.steps.first { it.mnemonic.equals("ADD", ignoreCase = true) }
        val movX0 = mov.registerWrites.first { it.name.equals("x0", true) || it.name.equals("w0", true) }
        val addX0 = add.registerWrites.first { it.name.equals("x0", true) || it.name.equals("w0", true) }
        val fiveToTwelve = formatReplayTransition(
            addX0.name, addX0.beforeKnown, addX0.before, addX0.known, addX0.value, unknown,
        )
        assertEquals("${addX0.name}:0x5→0xc", fiveToTwelve)
        assertTrue("MOV must write 5, got $movX0", movX0.known && movX0.value == 5L)
        assertTrue("ADD must write 12 from 5, got $addX0", addX0.known && addX0.value == 12L && addX0.before == 5L)
        compose.onNodeWithText(fiveToTwelve, substring = false).assertIsDisplayed()
        compose.onNodeWithTag("replay_step_${formatHex(mov.pc, 8)}").assertIsDisplayed()
        compose.onNodeWithTag("replay_step_${formatHex(add.pc, 8)}").assertIsDisplayed()
        assertEquals(3, report.steps.size)

        val addChangeTag = "replay_change_${formatHex(add.pc, 8)}_${addX0.name}"
        compose.onNodeWithTag(addChangeTag).assertIsDisplayed().performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(fiveToTwelve, clipboardText())
        compose.runOnUiThread { vm().dismissNotice() }
        compose.waitForIdle()

        val outDir = inst.targetContext.getExternalFilesDir("shots") ?: inst.targetContext.filesDir
        outDir.mkdirs()
        device.waitForIdle()
        Thread.sleep(250)
        check(device.takeScreenshot(File(outDir, "replay-details-page.png"))) { "details page screenshot failed" }

        // Back must keep selection and compact result; it must not re-run the trace.
        val firstStepCount = report.steps.size
        compose.onNodeWithTag("replay_details_back").performClick()
        compose.waitForIdle()
        if (!requireAsvFocused("back-from-details")) return
        assertFalse(vm().replayDetailsOpen)
        assertEquals(selectedBefore, vm().selectedAddress)
        assertEquals(12L, vm().replayReport!!.changedRegisters().first { it.name.equals("x0", true) || it.name.equals("w0", true) }.value)
        assertEquals(0, compose.onAllNodesWithTag("replay_details_page").fetchSemanticsNodes().size)
        compose.onNodeWithTag("replay_reg_${x0.name}").assertIsDisplayed()
        compose.onNodeWithTag("replay_details_toggle").assertIsDisplayed()
        check(device.takeScreenshot(File(outDir, "replay-main-details-entry.png"))) { "main+entry screenshot failed" }

        compose.onNodeWithTag("replay_reg_${x0.name}").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(mainLine, clipboardText())
        compose.runOnUiThread { vm().dismissNotice() }
        compose.waitForIdle()

        // Re-open details, replay again: page must show the new report, not an accumulated old trace.
        compose.onNodeWithTag("replay_details_toggle").performClick()
        compose.waitForIdle()
        if (!requireAsvFocused("reopen-details")) return
        val before = vm().replayReport
        compose.runOnUiThread { vm().replayToSelected() }
        val started = System.currentTimeMillis()
        var sawBusy = false
        while (System.currentTimeMillis() - started < 8_000) {
            if (vm().busyMessage != null) sawBusy = true
            if (vm().errorMessage != null) break
            val current = vm().replayReport
            if (vm().busyMessage == null && current != null && (current !== before || sawBusy)) break
            compose.waitForIdle()
            Thread.sleep(50)
        }
        assertTrue(
            "second replay failed: error=${vm().errorMessage} busy=${vm().busyMessage} steps=${vm().replayReport?.steps?.map { it.mnemonic }}",
            vm().errorMessage == null && vm().busyMessage == null && vm().replayReport != null,
        )
        compose.waitForIdle()
        assertTrue("details page should stay or be updatable after a new replay", vm().replayDetailsOpen)
        val second = vm().replayReport!!
        assertEquals("repeat must not accumulate traces", firstStepCount, second.steps.size)
        val x0Again = second.changedRegisters().filter { it.name.equals("x0", true) || it.name.equals("w0", true) }
        assertEquals("main table must list x0 once, got $x0Again", 1, x0Again.size)
        assertEquals(12L, x0Again.single().value)
        compose.onNodeWithText(fiveToTwelve, substring = false).assertIsDisplayed()
    }

    @Test
    fun replayValueChangesUseHexIncludingZeroNegativeAndUnknown() {
        val unknown = "unknown"
        assertEquals("0x0", formatReplayScalar(true, 0L, unknown, "x0"))
        assertEquals("0x5", formatReplayScalar(true, 5L, unknown, "x0"))
        assertEquals("0xc", formatReplayScalar(true, 12L, unknown, "x0"))
        assertEquals("x0:0x5→0xc", formatReplayTransition("x0", true, 5L, true, 12L, unknown))
        assertEquals("z:0x0→0x1", formatReplayTransition("z", true, 0L, true, 1L, unknown))
        assertEquals("0xffffffff", formatReplayScalar(true, 0xffffffffL, unknown, "w0"))
        assertEquals("0xffffffffffffffff", formatReplayScalar(true, -1L, unknown, "x0"))
        assertEquals("0x8000000000000000", formatReplayScalar(true, Long.MIN_VALUE, unknown, "x0"))
        assertEquals(unknown, formatReplayScalar(false, 0L, unknown, "x0"))
        assertEquals(unknown, formatReplayScalar(false, 42L, unknown, "z"))
        assertEquals("0x00", formatReplayMemBytes("00", unknown))
        assertEquals("0xabcd", formatReplayMemBytes("0xABCD", unknown))
        assertEquals(unknown, formatReplayMemBytes("", unknown))
        assertEquals("x0:0xffffffffffffffff", formatReplayInitial("x0", -1L, true, unknown))
        val high = parseJsonU64(JSONObject("{\"value\":\"0xffffffffffffffff\"}"), "value")
        assertEquals(-1L, high)
        val msb = parseJsonU64(JSONObject("{\"value\":\"0x8000000000000000\"}"), "value")
        assertEquals(Long.MIN_VALUE, msb)
        // Decimal JSON numbers that fit in signed 64-bit still parse.
        val twelve = parseJsonU64(JSONObject("{\"value\":12}"), "value")
        assertEquals(12L, twelve)
    }

}
