package com.icode.asmviewer.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

data class NativeLibRef(
    val entryName: String,
    val soName: String,
    val abi: String,
    val size: Long,
    val containerLabel: String,
    val containerFile: File,
    val isTempContainer: Boolean,
)

object ApkImporter {
    const val MAX_FILE_BYTES = 96L * 1024L * 1024L
    const val MAX_APK_CONTAINER_BYTES = 2L * 1024L * 1024L * 1024L
    private val LIB_SO = Regex("^lib/([^/]+)/([^/]+\\.so)$")
    private val ARM64_ABIS = setOf("arm64-v8a", "arm64")

    fun copyUriToFile(context: Context, uri: Uri, dest: File, maxBytes: Long = MAX_FILE_BYTES): File {
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw EngineException("无法读取所选文件")
            copyCapped(input, dest, maxBytes)
        }
        return dest
    }

    fun copyApkContainer(context: Context, uri: Uri, dest: File): File {
        return copyUriToFile(context, uri, dest, MAX_APK_CONTAINER_BYTES)
    }

    fun listArm64Libraries(containers: List<Pair<File, String>>): List<NativeLibRef> {
        val foundAbis = linkedSetOf<String>()
        val arm64 = mutableListOf<NativeLibRef>()
        var sawDex = false
        try {
            for ((file, label) in containers) {
                if (!file.isFile) {
                    throw EngineException("无法读取安装包：$label")
                }
                ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name.replace('\\', '/')
                        if (entry.isDirectory) continue
                        if (name.contains("..") || name.startsWith("/")) {
                            throw EngineException("APK 中存在不安全的路径：$name")
                        }
                        val lower = name.lowercase()
                        if (lower.endsWith(".dex") || lower.endsWith(".jar")) {
                            sawDex = true
                        }
                        val match = LIB_SO.matchEntire(name) ?: continue
                        val abi = match.groupValues[1]
                        val soName = match.groupValues[2]
                        foundAbis += abi
                        if (abi in ARM64_ABIS) {
                            val size = if (entry.size >= 0) entry.size else 0L
                            arm64 += NativeLibRef(
                                entryName = name,
                                soName = soName,
                                abi = abi,
                                size = size,
                                containerLabel = label,
                                containerFile = file,
                                isTempContainer = false,
                            )
                        }
                    }
                }
            }
        } catch (e: EngineException) {
            throw e
        } catch (e: ZipException) {
            throw EngineException("APK 含有不安全或无效的路径：${e.message ?: e.toString()}")
        } catch (e: Exception) {
            throw EngineException(e.message ?: e.toString())
        }
        if (arm64.isEmpty()) {
            throw EngineException(missingArm64Message(foundAbis, sawDex))
        }
        return arm64.sortedWith(compareBy({ it.soName }, { it.containerLabel }))
    }

    fun listArm64LibrariesFromUri(context: Context, apkUri: Uri, destFile: File, label: String): List<NativeLibRef> {
        copyApkContainer(context, apkUri, destFile)
        return listArm64Libraries(listOf(destFile to label)).map { it.copy(isTempContainer = true) }
    }

    fun extractLibrary(ref: NativeLibRef, destDir: File): ExtractedSo {
        destDir.mkdirs()
        if (ref.size > MAX_FILE_BYTES && ref.size > 0) {
            throw EngineException("${ref.soName} 超过 96 MiB 限制")
        }
        try {
            ZipFile(ref.containerFile).use { zip ->
                val entry = zip.getEntry(ref.entryName)
                    ?: throw EngineException("安装包中找不到 ${ref.entryName}")
                val name = entry.name.replace('\\', '/')
                if (name.contains("..") || name.startsWith("/")) {
                    throw EngineException("APK 中存在不安全的路径：$name")
                }
                val unique = buildString {
                    append(ref.soName.substringBeforeLast('.'))
                    if (ref.containerLabel.isNotBlank()) {
                        append('_')
                        append(ref.containerLabel.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    }
                    append(".so")
                }
                val dest = File(destDir, unique)
                zip.getInputStream(entry).use { input -> copyCapped(input, dest, MAX_FILE_BYTES) }
                return ExtractedSo(ref.soName, dest, dest.length())
            }
        } catch (e: EngineException) {
            throw e
        } catch (e: ZipException) {
            throw EngineException("APK 含有不安全或无效的路径：${e.message ?: e.toString()}")
        } catch (e: Exception) {
            throw EngineException(e.message ?: e.toString())
        }
    }

    /** Compatibility helper used by older tests: extract every ARM64 .so. Prefer list + extractLibrary. */
    fun extractArm64Libraries(context: Context, apkUri: Uri, destDir: File): List<ExtractedSo> {
        destDir.mkdirs()
        val tmp = File(destDir, "_container.apk")
        val refs = listArm64LibrariesFromUri(context, apkUri, tmp, tmp.name)
        return refs.map { extractLibrary(it, destDir) }
    }

    fun installBuiltinSample(context: Context): File {
        val dest = File(context.filesDir, "samples/libicode_android_sample.so")
        dest.parentFile?.mkdirs()
        context.assets.open("samples/libicode_android_sample.so").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest
    }

    private fun missingArm64Message(foundAbis: Set<String>, sawDex: Boolean): String {
        val abiText = if (foundAbis.isEmpty()) "未发现 lib/<abi>/*.so"
        else "仅发现：" + foundAbis.joinToString(", ")
        val dex = if (sawDex) " 已忽略 DEX/Java 代码，本应用只支持 ARM64 原生库。" else " 本应用只支持 ARM64 原生库，不会把 DEX 当作可分析文件。"
        return "该包的 lib 下没有 arm64-v8a / arm64 的 .so。$abiText。$dex"
    }

    private fun copyCapped(input: InputStream, dest: File, maxBytes: Long) {
        dest.parentFile?.mkdirs()
        dest.outputStream().use { output ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > maxBytes) {
                    dest.delete()
                    throw EngineException("文件超过 ${maxBytes / (1024 * 1024)} MiB 限制")
                }
                output.write(buf, 0, n)
            }
        }
    }

    data class ExtractedSo(val name: String, val file: File, val size: Long)
}
