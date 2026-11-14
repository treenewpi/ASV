package com.icode.asmviewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icode.asmviewer.data.AddressKind
import com.icode.asmviewer.data.AddressResolve
import com.icode.asmviewer.data.ViewMode
import com.icode.asmviewer.data.ViewerViewModel
import com.icode.asmviewer.data.formatWideHex
import com.icode.asmviewer.data.parseHexOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddressJumpTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

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

    private fun openSampleAndFunction(name: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("home_sample").performClick()
        waitUntil(label = "functions listed") { vm().functions.isNotEmpty() }
        val fn = vm().functions.first { it.name == name }
        compose.runOnUiThread { vm().selectFunction(fn) }
        waitUntil(label = "cfg for $name") { vm().snapshot != null && vm().selectedFunction?.name == name }
        compose.waitForIdle()
    }

    @Test
    fun threeKindsAgreeAndFileOffsetIsNotVa() {
        openSampleAndFunction("cond_abs")
        val map = vm().image!!.addressMap
        assertTrue("address map must include load segments", map.segments.isNotEmpty())
        val insns = vm().snapshot!!.instructionsInOrder
        assertTrue(insns.size >= 2)
        val va = insns[1].address
        val rva = map.vaToRva(va)
        val file = map.vaToFileOffset(va)
        assertNotNull("instruction must have a file mapping", file)

        val fromRva = map.resolve(AddressKind.Rva, rva) as AddressResolve.Ok
        val fromVa = map.resolve(AddressKind.Va, va) as AddressResolve.Ok
        val fromFile = map.resolve(AddressKind.FileOffset, file!!) as AddressResolve.Ok
        assertEquals(va, fromRva.va)
        assertEquals(va, fromVa.va)
        assertEquals(va, fromFile.va)

        if (file != va) {
            val mistaken = map.resolve(AddressKind.Va, file, requireCode = false, requireAligned = false)
            if (mistaken is AddressResolve.Ok) {
                assertNotEquals(
                    "treating a file offset as a virtual address must not be the same as a file-offset jump",
                    fromFile.va,
                    mistaken.va,
                )
            }
        }
        assertEquals(va, parseHexOffset(formatWideHex(va)))
        assertEquals(va, parseHexOffset(va.toString(16)))
        assertNull(parseHexOffset("xyz"))
        assertNull(parseHexOffset(""))
    }

    @Test
    fun sameFunctionJumpAndModeSwitchKeepAddressWithoutHistoryNoise() {
        openSampleAndFunction("cond_abs")
        val insns = vm().snapshot!!.instructionsInOrder
        val target = insns.last().address
        val historyBefore = vm().canJumpBack
        compose.runOnUiThread { vm().jumpToTypedAddress("0x" + target.toString(16), AddressKind.Va) }
        waitUntil(label = "selected target") { vm().selectedAddress == target }
        assertTrue(vm().canJumpBack != historyBefore || vm().canJumpBack)
        compose.runOnUiThread { vm().updateViewMode(ViewMode.Flat) }
        compose.waitForIdle()
        assertEquals(target, vm().selectedAddress)
        val backAfterFlat = vm().canJumpBack
        compose.runOnUiThread { vm().updateViewMode(ViewMode.Cfg) }
        compose.waitForIdle()
        assertEquals(target, vm().selectedAddress)
        assertEquals("mode switch must not add jump history", backAfterFlat, vm().canJumpBack)
        compose.onNodeWithTag("cfg_canvas").assertIsDisplayed()
        compose.onNodeWithTag("nav_listing").performClick()
        compose.waitForIdle()
        assertEquals(ViewMode.Flat, vm().viewMode)
        assertEquals(target, vm().selectedAddress)
        compose.onNodeWithTag("nav_cfg").performClick()
        compose.waitForIdle()
        assertEquals(ViewMode.Cfg, vm().viewMode)
        assertEquals(target, vm().selectedAddress)
    }

    @Test
    fun crossFunctionJumpSelectsTargetFunction() {
        openSampleAndFunction("cond_abs")
        val other = vm().functions.first { it.name == "linear_add" }
        val resolved = vm().image!!.addressMap.resolve(AddressKind.Va, other.start)
        assertTrue("linear_add start must resolve as code VA: $resolved", resolved is AddressResolve.Ok)
        compose.runOnUiThread { vm().jumpToTypedAddress("0x" + other.start.toString(16), AddressKind.Va) }
        waitUntil(label = "crossed into linear_add (fn=${vm().selectedFunction?.name} addr=${vm().selectedAddress} err=${vm().errorMessage})") {
            vm().selectedFunction?.name == "linear_add" && vm().selectedAddress == other.start
        }
        assertEquals("linear_add", vm().selectedFunction?.name)
        assertEquals(other.start, vm().selectedAddress)
        assertTrue("cross-function jump should record history", vm().canJumpBack)
        var wentBack = false
        compose.runOnUiThread { wentBack = vm().jumpBack() }
        assertTrue("jumpBack should have a previous address (fn=${vm().selectedFunction?.name} addr=${vm().selectedAddress})", wentBack)
        waitUntil(label = "back to cond_abs (fn=${vm().selectedFunction?.name} addr=${vm().selectedAddress})") {
            vm().selectedFunction?.name == "cond_abs"
        }
        assertEquals("cond_abs", vm().selectedFunction?.name)
    }

    @Test
    fun invalidAndUnalignedInputsStayPut() {
        openSampleAndFunction("cond_abs")
        val beforeFn = vm().selectedFunction?.name
        val beforeAddr = vm().selectedAddress
        compose.runOnUiThread { vm().jumpToTypedAddress("not-a-hex") }
        compose.waitForIdle()
        assertEquals(beforeFn, vm().selectedFunction?.name)
        assertEquals(beforeAddr, vm().selectedAddress)
        assertNotNull(vm().errorMessage)
        compose.runOnUiThread { vm().dismissError() }

        compose.runOnUiThread { vm().jumpToTypedAddress("0x1", AddressKind.Va) }
        compose.waitForIdle()
        assertEquals(beforeAddr, vm().selectedAddress)
        assertNotNull(vm().errorMessage)
        compose.runOnUiThread { vm().dismissError() }

        val huge = "0x7fffffffffffffff"
        compose.runOnUiThread { vm().jumpToTypedAddress(huge, AddressKind.FileOffset) }
        compose.waitForIdle()
        assertEquals(beforeAddr, vm().selectedAddress)
        assertNotNull(vm().errorMessage)
    }
}
