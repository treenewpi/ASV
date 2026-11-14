package com.icode.asmviewer

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icode.asmviewer.data.ApkImporter
import com.icode.asmviewer.data.EngineException
import com.icode.asmviewer.data.EngineNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ImporterSecurityTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun write(name: String, bytes: ByteArray): File {
        val dir = File(ctx.cacheDir, "instrumented-fixtures")
        dir.mkdirs()
        val f = File(dir, name)
        f.writeBytes(bytes)
        return f
    }

    private fun uriFor(file: File): Uri = Uri.fromFile(file)

    @Test
    fun emptyFileOpenFailsRecoverably() {
        val empty = write("empty.bin", ByteArray(0))
        val engine = EngineNative()
        val ex = assertThrows(EngineException::class.java) { engine.open(empty.absolutePath) }
        assertTrue(ex.message?.isNotBlank() == true)
    }

    @Test
    fun corruptFileOpenFailsRecoverably() {
        val bad = write("corrupt.bin", "NOT-AN-ELF-FILE-just-garbage".toByteArray())
        val engine = EngineNative()
        val ex = assertThrows(EngineException::class.java) { engine.open(bad.absolutePath) }
        assertTrue(ex.message?.isNotBlank() == true)
    }

    @Test
    fun nonArm64IsReportedAndCfgRefused() {
        // Minimal ELF64 ET_DYN EM_X86_64 created by the host fixture, or a tiny header we write here.
        val bytes = ByteArray(120)
        // ELF magic, 64-bit, little, version
        bytes[0] = 0x7f; bytes[1] = 'E'.code.toByte(); bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
        bytes[4] = 2; bytes[5] = 1; bytes[6] = 1
        // e_type = 3 (ET_DYN), e_machine = 62 (EM_X86_64) at offset 16
        bytes[16] = 3; bytes[17] = 0
        bytes[18] = 62; bytes[19] = 0
        bytes[20] = 1
        val f = write("x86_64_dummy.so", bytes)
        val engine = EngineNative()
        // open may succeed or fail depending on parser strictness; if it opens, CFG must refuse.
        try {
            val h = engine.open(f.absolutePath)
            try {
                assertTrue("arch must not be reported as ARM64", !engine.isArm64(h) || engine.archName(h).lowercase().contains("x86"))
                val ex = assertThrows(EngineException::class.java) { engine.buildCfg(h, 0, 16, 3_000) }
                assertTrue(ex.message.orEmpty().contains("ARM64"))
            } finally {
                engine.close(h)
            }
        } catch (e: EngineException) {
            assertTrue("parser rejected non-ARM64: ${e.message}", e.message?.isNotBlank() == true)
        }
    }

    @Test
    fun oversizeCopyIsRejected() {
        val dest = File(ctx.cacheDir, "instrumented-fixtures/oversize-out.bin")
        dest.parentFile?.mkdirs()
        val src = File(ctx.cacheDir, "instrumented-fixtures/oversize.bin")
        src.parentFile?.mkdirs()
        // Just over the 96 MiB cap without filling the whole file with /dev/zero on host:
        // copyCapped reads a stream; we make a FileInputStream of a sparse-ish file.
        FileOutputStream(src).use { out ->
            val chunk = ByteArray(1024 * 1024)
            repeat(97) { out.write(chunk) }
        }
        val ex = assertThrows(EngineException::class.java) {
            ApkImporter.copyUriToFile(ctx, uriFor(src), dest, ApkImporter.MAX_FILE_BYTES)
        }
        assertTrue(ex.message.orEmpty().contains("96") || ex.message.orEmpty().contains("MiB") || ex.message.orEmpty().contains("限制"))
    }

    @Test
    fun traversalApkIsRejected() {
        val apk = File(ctx.cacheDir, "instrumented-fixtures/traversal.apk")
        apk.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/../../tmp/evil.so"))
            zip.write(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            zip.closeEntry()
        }
        val dest = File(ctx.cacheDir, "instrumented-fixtures/traversal-out")
        val ex = assertThrows(EngineException::class.java) {
            ApkImporter.extractArm64Libraries(ctx, uriFor(apk), dest)
        }
        assertTrue(
            "message=${ex.message}",
            ex.message.orEmpty().contains("不安全") || ex.message.orEmpty().contains("路径") || ex.message.orEmpty().contains("无效"),
        )
    }

    @Test
    fun multiLibApkExtractsArm64AndSkipsX86() {
        val sample = ApkImporter.installBuiltinSample(ctx)
        val apk = File(ctx.cacheDir, "instrumented-fixtures/multi.apk")
        ZipOutputStream(FileOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libicode_android_sample.so"))
            zip.write(sample.readBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libsecond_arm64.so"))
            zip.write(sample.readBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/x86_64/libx86_dummy.so"))
            zip.write(ByteArray(16))
            zip.closeEntry()
        }
        val dest = File(ctx.cacheDir, "instrumented-fixtures/multi-out")
        dest.deleteRecursively()
        val libs = ApkImporter.extractArm64Libraries(ctx, uriFor(apk), dest)
        assertEquals(2, libs.size)
        assertTrue(libs.any { it.name == "libicode_android_sample.so" })
        assertTrue(libs.any { it.name == "libsecond_arm64.so" })
        assertTrue(libs.all { it.file.exists() && it.size > 0 })
    }

    @Test
    fun listThenExtractOnlySelected() {
        val sample = ApkImporter.installBuiltinSample(ctx)
        val apk = File(ctx.cacheDir, "instrumented-fixtures/list-multi.apk")
        ZipOutputStream(FileOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libicode_android_sample.so"))
            zip.write(sample.readBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libsecond_arm64.so"))
            zip.write(sample.readBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/x86_64/libx86_dummy.so"))
            zip.write(ByteArray(16))
            zip.closeEntry()
        }
        val destApk = File(ctx.cacheDir, "instrumented-fixtures/list-multi-copy.apk")
        destApk.parentFile?.mkdirs()
        val refs = ApkImporter.listArm64LibrariesFromUri(ctx, uriFor(apk), destApk, "list-multi.apk")
        assertEquals(2, refs.size)
        val outDir = File(ctx.cacheDir, "instrumented-fixtures/list-multi-out")
        outDir.deleteRecursively()
        val extracted = ApkImporter.extractLibrary(refs.first { it.soName == "libsecond_arm64.so" }, outDir)
        assertEquals("libsecond_arm64.so", extracted.name)
        assertTrue(extracted.file.exists() && extracted.size > 0)
        val soFiles = outDir.listFiles()?.filter { it.extension == "so" } ?: emptyList()
        assertEquals("only the selected library should be extracted", 1, soFiles.size)
    }

    @Test
    fun dexOnlyApkIsRejectedWithNativeOnlyMessage() {
        val apk = File(ctx.cacheDir, "instrumented-fixtures/dex-only.apk")
        apk.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(64))
            zip.closeEntry()
        }
        val dest = File(ctx.cacheDir, "instrumented-fixtures/dex-only-copy.apk")
        val ex = assertThrows(EngineException::class.java) {
            ApkImporter.listArm64LibrariesFromUri(ctx, uriFor(apk), dest, "dex-only.apk")
        }
        assertTrue("message=${ex.message}", ex.message.orEmpty().contains("DEX") || ex.message.orEmpty().contains("原生"))
    }

    @Test
    fun ownPackagePublicApkListsArm64NativeLibs() {
        val ai = ctx.applicationInfo
        val base = java.io.File(ai.sourceDir)
        assertTrue(base.isFile)
        val splits = ai.splitSourceDirs?.map { java.io.File(it) } ?: emptyList()
        val containers = (listOf(base) + splits).filter { it.isFile }.map { it to it.name }
        val refs = ApkImporter.listArm64Libraries(containers)
        assertTrue(refs.any { it.soName.endsWith(".so") })
        assertTrue(refs.all { it.abi == "arm64-v8a" || it.abi == "arm64" })
    }
}
