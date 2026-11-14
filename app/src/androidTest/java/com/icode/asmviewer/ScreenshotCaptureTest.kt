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

@RunWith(AndroidJUnit4::class)
class ScreenshotCaptureTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)

    private val outDir: File by lazy {
        val dir = inst.targetContext.getExternalFilesDir("shots") ?: inst.targetContext.filesDir
        dir.mkdirs()
        dir
    }

    private fun vm(): ViewerViewModel =
        ViewModelProvider(compose.activity)[ViewerViewModel::class.java]

    private fun waitUntil(timeoutMs: Long = 15_000, label: String, predicate: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (predicate()) return
            runCatching { compose.waitForIdle() }
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $label")
    }

    private fun shot(name: String) {
        runCatching { compose.waitForIdle() }
        device.waitForIdle()
        Thread.sleep(400)
        val ok = device.takeScreenshot(File(outDir, name))
        check(ok) { "screenshot failed: $name in $outDir" }
    }

    private fun openSample() {
        if (vm().functions.isNotEmpty()) return
        compose.onNodeWithTag("home_sample").performClick()
        waitUntil(label = "functions listed") { vm().functions.isNotEmpty() }
        compose.waitForIdle()
    }

    @Test
    fun captureCurrentLocaleFlow() {
        val prefix = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("prefix") ?: "shot"
        val appName = inst.targetContext.getString(R.string.app_name)
        waitUntil(label = "activity up ($appName)") { true }
        compose.waitForIdle()
        Thread.sleep(400)
        compose.runOnUiThread { vm().goHome() }
        compose.waitForIdle()
        Thread.sleep(300)
        shot("$prefix-home.png")
        openSample()
        compose.runOnUiThread { vm().clearFunctionSelection() }
        compose.waitForIdle()
        Thread.sleep(300)
        shot("$prefix-functions-nav.png")
        val fn = vm().functions.first { it.name == "cond_abs" }
        compose.runOnUiThread { vm().selectFunction(fn) }
        waitUntil(label = "cfg for cond_abs") { vm().snapshot != null && vm().selectedFunction?.name == "cond_abs" }
        compose.waitForIdle()
        Thread.sleep(500)
        shot("$prefix-cfg-nav.png")
        compose.onNodeWithTag("jump_button").performClick()
        compose.waitForIdle()
        Thread.sleep(400)
        shot("$prefix-jump-dialog.png")
        compose.onNodeWithTag("jump_cancel").performClick()
        compose.waitForIdle()
        Thread.sleep(200)
        compose.onNodeWithTag("nav_replay").performClick()
        waitUntil(label = "replay report") { vm().replayReport != null }
        compose.waitForIdle()
        Thread.sleep(600)
        shot("$prefix-replay.png")
        runCatching { compose.onNodeWithTag("replay_details_toggle").performClick() }
        compose.waitForIdle()
        Thread.sleep(300)
        shot("$prefix-replay-details.png")
        runCatching { compose.onNodeWithTag("replay_details_back").performClick() }
        compose.waitForIdle()
        Thread.sleep(200)
        compose.onNodeWithTag("nav_listing").performClick()
        compose.waitForIdle()
        Thread.sleep(400)
        shot("$prefix-listing.png")
        compose.onNodeWithTag("nav_cfg").performClick()
        compose.waitForIdle()
        Thread.sleep(400)
        shot("$prefix-cfg-after-listing.png")
    }
}
