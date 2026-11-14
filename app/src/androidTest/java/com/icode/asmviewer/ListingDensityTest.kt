package com.icode.asmviewer

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.icode.asmviewer.data.InstructionSnap
import com.icode.asmviewer.data.ViewMode
import com.icode.asmviewer.data.ViewerViewModel
import com.icode.asmviewer.data.formatHex
import com.icode.asmviewer.ui.formatInstructionLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ListingDensityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val inst = InstrumentationRegistry.getInstrumentation()
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

    private fun clipboardText(): String {
        var text = ""
        compose.runOnUiThread {
            val cm = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text = cm.primaryClip?.getItemAt(0)?.coerceToText(compose.activity)?.toString().orEmpty()
        }
        compose.waitForIdle()
        return text
    }

    private fun openCondAbsListing() {
        compose.waitForIdle()
        compose.onNodeWithTag("home_sample").performClick()
        waitUntil(label = "functions listed") { vm().functions.isNotEmpty() }
        val fn = vm().functions.first { it.name == "cond_abs" }
        compose.runOnUiThread { vm().selectFunction(fn) }
        waitUntil(label = "snapshot for cond_abs") { vm().snapshot != null && vm().selectedFunction?.name == "cond_abs" }
        compose.runOnUiThread { vm().updateViewMode(ViewMode.Flat) }
        compose.waitForIdle()
        compose.onNodeWithTag("nav_listing").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("listing_list").assertIsDisplayed()
    }

    @Test
    fun formatInstructionLineIsStable() {
        val insn = InstructionSnap(
            address = 0x159c,
            size = 4,
            bytes = "00040011",
            mnemonic = "ADD",
            operands = "w0, w0, #1",
            category = "alu",
            branchTarget = null,
        )
        assertEquals("0x0000159c  00040011  ADD  w0, w0, #1", formatInstructionLine(insn))
        val emptyOps = insn.copy(operands = "", bytes = "")
        assertEquals("0x0000159c  --------  ADD", formatInstructionLine(emptyOps))
    }

    @Test
    fun listingHasNoCopyButtonAndLongPressCopiesRow() {
        openCondAbsListing()
        compose.onAllNodesWithText("Copy", substring = false, ignoreCase = false).assertCountEquals(0)
        compose.onAllNodesWithText("复制", substring = false, ignoreCase = false).assertCountEquals(0)

        val insns = vm().snapshot!!.instructionsInOrder
        assertTrue(insns.size >= 2)
        val first = insns[0]
        val second = insns[1]
        val firstTag = "listing_row_${formatHex(first.address, 8)}"
        val secondTag = "listing_row_${formatHex(second.address, 8)}"
        compose.onNodeWithTag(firstTag).assertIsDisplayed()

        compose.onNodeWithTag(firstTag).performTouchInput { longClick() }
        compose.waitForIdle()
        Thread.sleep(200)
        val expected = formatInstructionLine(first)
        assertEquals(expected, clipboardText())
        waitUntil(label = "copied notice") { vm().notice == compose.activity.getString(R.string.action_copied) || clipboardText() == expected }

        compose.onNodeWithTag(secondTag).performClick()
        compose.waitForIdle()
        assertEquals(second.address, vm().selectedAddress)
        assertEquals("short press must not overwrite the clipboard", expected, clipboardText())

        val branch = insns.firstOrNull { it.branchTarget != null }
        assertTrue("cond_abs should contain a branch", branch != null)
        val target = branch!!.branchTarget!!
        compose.onNodeWithTag("listing_branch_${formatHex(branch.address, 8)}").performClick()
        waitUntil(label = "jumped to branch target") { vm().selectedAddress == target }
        assertEquals(target, vm().selectedAddress)
        // branch click is not a row long-press, clipboard stays
        assertEquals(expected, clipboardText())

        val outDir = inst.targetContext.getExternalFilesDir("shots") ?: inst.targetContext.filesDir
        outDir.mkdirs()
        device.waitForIdle()
        Thread.sleep(300)
        val ok = device.takeScreenshot(File(outDir, "listing-density-current.png"))
        assertTrue("screenshot failed", ok)
    }
}
