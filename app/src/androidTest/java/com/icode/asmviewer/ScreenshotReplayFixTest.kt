package com.icode.asmviewer

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.icode.asmviewer.data.ViewerViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class ScreenshotReplayFixTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)

    private fun vm() = ViewModelProvider(compose.activity)[ViewerViewModel::class.java]

    private fun waitUntil(timeoutMs: Long = 20_000, label: String, pred: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (pred()) return
            runCatching { compose.waitForIdle() }
            Thread.sleep(50)
        }
        throw AssertionError("timeout $label")
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        device.waitForIdle()
        Thread.sleep(400)
        val dir = inst.targetContext.getExternalFilesDir("shots") ?: inst.targetContext.filesDir
        dir.mkdirs()
        check(device.takeScreenshot(File(dir, name))) { "shot failed $name" }
    }

    private fun copyPublic(name: String): File {
        val dest = File(inst.targetContext.cacheDir, name)
        val pfd = inst.uiAutomation.executeShellCommand("cat /sdcard/Download/icode-viewer-test/$name")
        FileInputStream(pfd.fileDescriptor).use { input -> dest.outputStream().use { input.copyTo(it) } }
        pfd.close()
        return dest
    }

    @Test
    fun captureReplayFixes() {
        val prefix = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("prefix") ?: "replay"
        compose.waitForIdle()
        compose.onNodeWithTag("home_sample").performClick()
        waitUntil(label = "functions") { vm().functions.isNotEmpty() }
        val cond = vm().functions.first { it.name == "cond_abs" }
        compose.runOnUiThread { vm().selectFunction(cond) }
        waitUntil(label = "cfg") { vm().snapshot != null }
        compose.runOnUiThread { vm().replayToSelected() }
        waitUntil(label = "entry replay") { vm().replayReport != null }
        Thread.sleep(500)
        shot("$prefix-cond-abs-entry-cmp.png")

        compose.runOnUiThread { vm().navigateToAddress(0x15b0L, recordHistory = true) }
        compose.waitForIdle()
        compose.runOnUiThread { vm().replayToSelected() }
        waitUntil(label = "neg ret replay") { vm().replayReport != null && vm().selectedAddress == 0x15b0L }
        Thread.sleep(500)
        shot("$prefix-cond-abs-neg-ret.png")

        val so = copyPublic("libconst_twelve.so")
        compose.runOnUiThread { vm().openElfFromFile(so, "const_twelve.so") }
        waitUntil(label = "const fns") { vm().functions.any { it.name == "const_twelve" } }
        val fn = vm().functions.first { it.name == "const_twelve" }
        compose.runOnUiThread { vm().selectFunction(fn, focusAddress = fn.start + 8) }
        waitUntil(label = "const cfg") { vm().snapshot != null && vm().selectedFunction?.name == "const_twelve" }
        compose.runOnUiThread { vm().navigateToAddress(fn.start + 8, recordHistory = false) }
        compose.waitForIdle()
        compose.runOnUiThread { vm().replayToSelected() }
        waitUntil(label = "const replay") { vm().replayReport != null && vm().selectedAddress == fn.start + 8 }
        Thread.sleep(600)
        shot("$prefix-const-twelve-x0-12.png")
    }
}
