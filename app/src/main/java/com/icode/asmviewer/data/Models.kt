package com.icode.asmviewer.data

import org.json.JSONArray
import org.json.JSONObject

data class FunctionItem(
    val name: String,
    val start: Long,
    val size: Long,
    val source: String,
    val fromSymbol: Boolean,
    val fromUnwind: Boolean,
    val fromEntry: Boolean,
) {
    val displayName: String
        get() = name.ifBlank {
            if (source == "executable_view") "view_${start.toString(16)}" else "sub_${start.toString(16)}"
        }
    val isExecutableView: Boolean get() = source == "executable_view"
}

data class ImageInfo(
    val path: String,
    val fingerprint: String,
    val fileSize: Long,
    val arch: String,
    val isArm64: Boolean,
    val entry: Long,
    val isBuiltinSample: Boolean,
    val label: String,
    val addressMap: AddressMap = AddressMap(0, fileSize, "", emptyList(), isArm64),
)

data class InstructionSnap(
    val address: Long,
    val size: Int,
    val bytes: String,
    val mnemonic: String,
    val operands: String,
    val category: String,
    val branchTarget: Long?,
)

data class CfgEdgeSnap(
    val targetId: Int,
    val targetStart: Long,
    val type: String,
    val label: String,
    val unknownTarget: Boolean,
)

data class CfgBlockSnap(
    val id: Int,
    val start: Long,
    val end: Long,
    val type: String,
    val unknownTarget: Boolean,
    val inFunctionBounds: Boolean,
    val instructions: List<InstructionSnap>,
    val successors: List<CfgEdgeSnap>,
)

data class CfgSnapshot(
    val functionName: String,
    val functionStart: Long,
    val functionSize: Long,
    val source: String,
    val entry: Long,
    val truncated: Boolean,
    val truncationReason: String?,
    val blockCount: Int,
    val instructionCount: Int,
    val sectionName: String,
    val functionEnd: Long,
    val blocks: List<CfgBlockSnap>,
) {
    val instructionsInOrder: List<InstructionSnap> by lazy {
        blocks.filter { !it.unknownTarget }
            .flatMap { it.instructions }
            .sortedBy { it.address }
    }

    fun blockForAddress(address: Long): CfgBlockSnap? =
        blocks.firstOrNull { b -> b.instructions.any { it.address == address } }
            ?: blocks.firstOrNull { address >= it.start && address < it.end }
}

data class SimRegister(val name: String, val value: Long, val known: Boolean)
data class SimRegWrite(val name: String, val before: Long, val after: Long)
data class SimMemWrite(val address: Long, val after: String)

data class SimState(
    val ok: Boolean,
    val reason: String,
    val message: String,
    val pc: Long,
    val sp: Long,
    val nzcv: Long,
    val n: Boolean,
    val z: Boolean,
    val c: Boolean,
    val v: Boolean,
    val registers: List<SimRegister>,
    val lastMnemonic: String,
    val lastOperands: String,
    val lastReason: String,
    val lastMessage: String,
    val stopped: Boolean,
    val registerWrites: List<SimRegWrite>,
    val memoryWrites: List<SimMemWrite>,
    val stackAddress: Long,
    val stackBytes: String,
    val stackOk: Boolean,
)

fun parseFunctionList(json: String): Pair<ImageInfo, List<FunctionItem>> {
    val obj = JSONObject(json)
    val info = ImageInfo(
        path = obj.optString("path"),
        fingerprint = obj.optString("fingerprint"),
        fileSize = obj.optLong("fileSize"),
        arch = obj.optString("arch"),
        isArm64 = obj.optBoolean("arm64"),
        entry = obj.optLong("entry"),
        isBuiltinSample = false,
        label = obj.optString("path"),
        addressMap = parseAddressMap(obj, obj.optBoolean("arm64")),
    )
    val arr = obj.optJSONArray("functions") ?: JSONArray()
    val list = buildList {
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            add(
                FunctionItem(
                    name = f.optString("name"),
                    start = f.optLong("start"),
                    size = f.optLong("size"),
                    source = f.optString("source"),
                    fromSymbol = f.optBoolean("fromSymbol"),
                    fromUnwind = f.optBoolean("fromUnwind"),
                    fromEntry = f.optBoolean("fromEntry"),
                ),
            )
        }
    }.sortedBy { it.start }
    return info to list
}

fun parseCfgSnapshot(json: String): CfgSnapshot {
    val obj = JSONObject(json)
    val fn = obj.getJSONObject("function")
    val bounds = obj.optJSONObject("sourceBounds")
    val blocksArr = obj.optJSONArray("blocks") ?: JSONArray()
    val blocks = buildList {
        for (i in 0 until blocksArr.length()) {
            val b = blocksArr.getJSONObject(i)
            val insArr = b.optJSONArray("instructions") ?: JSONArray()
            val instructions = buildList {
                for (j in 0 until insArr.length()) {
                    val ins = insArr.getJSONObject(j)
                    val target = if (ins.isNull("branchTarget")) null else ins.optLong("branchTarget")
                    add(
                        InstructionSnap(
                            address = ins.optLong("address"),
                            size = ins.optInt("size"),
                            bytes = ins.optString("bytes"),
                            mnemonic = ins.optString("mnemonic"),
                            operands = ins.optString("operands"),
                            category = ins.optString("category"),
                            branchTarget = target,
                        ),
                    )
                }
            }
            val succArr = b.optJSONArray("successors") ?: JSONArray()
            val succs = buildList {
                for (j in 0 until succArr.length()) {
                    val e = succArr.getJSONObject(j)
                    add(
                        CfgEdgeSnap(
                            targetId = e.optInt("targetId"),
                            targetStart = e.optLong("targetStart"),
                            type = e.optString("type"),
                            label = e.optString("label"),
                            unknownTarget = e.optBoolean("unknownTarget"),
                        ),
                    )
                }
            }
            add(
                CfgBlockSnap(
                    id = b.optInt("id"),
                    start = b.optLong("start"),
                    end = b.optLong("end"),
                    type = b.optString("type"),
                    unknownTarget = b.optBoolean("unknownTarget"),
                    inFunctionBounds = b.optBoolean("inFunctionBounds", true),
                    instructions = instructions,
                    successors = succs,
                ),
            )
        }
    }
    return CfgSnapshot(
        functionName = fn.optString("name"),
        functionStart = fn.optLong("start"),
        functionSize = fn.optLong("size"),
        source = fn.optString("source"),
        entry = obj.optLong("entry"),
        truncated = obj.optBoolean("truncated"),
        truncationReason = if (obj.isNull("truncationReason")) null else obj.optString("truncationReason"),
        blockCount = obj.optInt("blockCount"),
        instructionCount = obj.optInt("instructionCount"),
        sectionName = bounds?.optString("sectionName").orEmpty(),
        functionEnd = bounds?.optLong("functionEnd") ?: 0L,
        blocks = blocks,
    )
}

fun parseSimState(json: String): SimState {
    val obj = JSONObject(json)
    val bits = obj.optJSONObject("nzcvBits")
    val regsArr = obj.optJSONArray("registers") ?: JSONArray()
    val regs = buildList {
        for (i in 0 until regsArr.length()) {
            val r = regsArr.getJSONObject(i)
            add(SimRegister(r.optString("name"), r.optLong("value"), r.optBoolean("known", true)))
        }
    }
    val last = obj.optJSONObject("lastStep")
    val writes = last?.optJSONArray("registerWrites") ?: JSONArray()
    val rw = buildList {
        for (i in 0 until writes.length()) {
            val w = writes.getJSONObject(i)
            add(SimRegWrite(w.optString("name"), w.optLong("before"), w.optLong("after")))
        }
    }
    val mems = last?.optJSONArray("memoryWrites") ?: JSONArray()
    val mw = buildList {
        for (i in 0 until mems.length()) {
            val w = mems.getJSONObject(i)
            add(SimMemWrite(w.optLong("address"), w.optString("after")))
        }
    }
    val stack = obj.optJSONObject("stack")
    return SimState(
        ok = obj.optBoolean("ok"),
        reason = obj.optString("reason"),
        message = obj.optString("message"),
        pc = obj.optLong("pc"),
        sp = obj.optLong("sp"),
        nzcv = obj.optLong("nzcv"),
        n = bits?.optInt("n") == 1,
        z = bits?.optInt("z") == 1,
        c = bits?.optInt("c") == 1,
        v = bits?.optInt("v") == 1,
        registers = regs,
        lastMnemonic = last?.optString("mnemonic").orEmpty(),
        lastOperands = last?.optString("operands").orEmpty(),
        lastReason = last?.optString("reason").orEmpty(),
        lastMessage = last?.optString("message").orEmpty(),
        stopped = last?.optBoolean("stopped") == true,
        registerWrites = rw,
        memoryWrites = mw,
        stackAddress = stack?.optLong("address") ?: 0L,
        stackBytes = stack?.optString("bytes").orEmpty(),
        stackOk = stack?.optBoolean("ok") == true,
    )
}

fun formatHex(value: Long, width: Int = 16): String =
    "0x" + value.toULong().toString(16).padStart(width, '0')

/** Compact address for CFG/UI: 0x15a4, not 0x000015a4. */
fun formatAddr(value: Long): String = "0x" + value.toULong().toString(16)

enum class BrowseScreen { Home, InstalledApps, LibraryPicker, Functions }

data class ReplayRegister(
    val name: String,
    val value: Long,
    val known: Boolean,
    val before: Long? = null,
    val beforeKnown: Boolean = false,
)

data class ReplayMemWrite(val address: Long, val before: String, val after: String)
data class ReplayStep(
    val pc: Long,
    val mnemonic: String,
    val operands: String,
    val skipped: Boolean,
    val skipReason: String,
    val registerWrites: List<ReplayRegister>,
    val memoryWrites: List<ReplayMemWrite>,
)

data class ReplayDefaultEnv(
    val used: Boolean,
    val sp: Long,
    val stackBytes: Long,
    val unsetIntegerRegs: Long,
)

data class ReplayReport(
    val ok: Boolean,
    val kind: String,
    val title: String,
    val semantics: String,
    val uniquePath: Boolean,
    val pathAmbiguous: Boolean,
    val containsLoopHeader: Boolean,
    val unknownIndirect: Boolean,
    val skippedCalls: Boolean,
    val functionStart: Long,
    val cursorAddress: Long,
    val stopAddress: Long,
    val targetBlockStart: Long,
    val pathBlocks: List<Long>,
    val steps: List<ReplayStep>,
    val observedRegisters: List<ReplayRegister>,
    val notes: List<String>,
    val defaultEnv: ReplayDefaultEnv? = null,
) {
}

fun ReplayReport.changedRegisters(): List<ReplayRegister> {
    val firstBefore = linkedMapOf<String, Pair<Long?, Boolean>>()
    val last = linkedMapOf<String, ReplayRegister>()
    for (step in steps) {
        for (w in step.registerWrites) {
            if (w.name !in firstBefore) {
                firstBefore[w.name] = w.before to w.beforeKnown
            }
            last[w.name] = w
        }
    }
    return last.values.mapNotNull { reg ->
        val (before, beforeKnown) = firstBefore[reg.name] ?: (null to false)
        if (!reg.known) {
            return@mapNotNull reg.copy(before = before, beforeKnown = beforeKnown)
        }
        if (beforeKnown && before != null && before == reg.value) {
            return@mapNotNull null
        }
        reg.copy(before = before, beforeKnown = beforeKnown)
    }
}

fun ReplayReport.changedMemory(): List<ReplayMemWrite> {
    val last = linkedMapOf<Long, ReplayMemWrite>()
    for (step in steps) {
        for (m in step.memoryWrites) {
            if (m.before == m.after) continue
            last[m.address] = m
        }
    }
    return last.values.toList()
}

fun parseReplayReport(json: String): ReplayReport {
    val obj = org.json.JSONObject(json)
    val pathArr = obj.optJSONArray("pathBlocks")
    val path = buildList {
        if (pathArr != null) for (i in 0 until pathArr.length()) add(pathArr.optLong(i))
    }
    val notesArr = obj.optJSONArray("notes")
    val notes = buildList {
        if (notesArr != null) for (i in 0 until notesArr.length()) add(notesArr.optString(i))
    }
    fun regs(arr: org.json.JSONArray?): List<ReplayRegister> = buildList {
        if (arr == null) return@buildList
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            val hasAfter = w.has("after") || w.has("value")
            val after = if (w.has("value")) w.optLong("value") else w.optLong("after")
            val hasBefore = w.has("before")
            val before = if (hasBefore) w.optLong("before") else null
            add(
                ReplayRegister(
                    name = w.optString("name"),
                    value = after,
                    known = w.optBoolean("known", hasAfter),
                    before = before,
                    beforeKnown = hasBefore && w.optBoolean("beforeKnown", true),
                ),
            )
        }
    }
    fun mems(arr: org.json.JSONArray?): List<ReplayMemWrite> = buildList {
        if (arr == null) return@buildList
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            add(ReplayMemWrite(w.optLong("address"), w.optString("before"), w.optString("after")))
        }
    }
    val stepsArr = obj.optJSONArray("steps")
    val steps = buildList {
        if (stepsArr == null) return@buildList
        for (i in 0 until stepsArr.length()) {
            val s = stepsArr.getJSONObject(i)
            add(
                ReplayStep(
                    pc = s.optLong("pc"),
                    mnemonic = s.optString("mnemonic"),
                    operands = s.optString("operands"),
                    skipped = s.optBoolean("skipped"),
                    skipReason = s.optString("skipReason"),
                    registerWrites = regs(s.optJSONArray("registerWrites")),
                    memoryWrites = mems(s.optJSONArray("memoryWrites")),
                ),
            )
        }
    }
    val envObj = obj.optJSONObject("defaultEnv")
    val defaultEnv = if (envObj != null) {
        ReplayDefaultEnv(
            used = envObj.optBoolean("used"),
            sp = envObj.optLong("sp"),
            stackBytes = envObj.optLong("stackBytes"),
            unsetIntegerRegs = envObj.optLong("unsetIntegerRegs"),
        )
    } else {
        null
    }
    return ReplayReport(
        ok = obj.optBoolean("ok"),
        kind = obj.optString("kind"),
        title = obj.optString("title"),
        semantics = obj.optString("semantics"),
        uniquePath = obj.optBoolean("uniquePath"),
        pathAmbiguous = obj.optBoolean("pathAmbiguous"),
        containsLoopHeader = obj.optBoolean("containsLoopHeader"),
        unknownIndirect = obj.optBoolean("unknownIndirect"),
        skippedCalls = obj.optBoolean("skippedCalls"),
        functionStart = obj.optLong("functionStart"),
        cursorAddress = obj.optLong("cursorAddress"),
        stopAddress = obj.optLong("stopAddress"),
        targetBlockStart = obj.optLong("targetBlockStart"),
        pathBlocks = path,
        steps = steps,
        observedRegisters = regs(obj.optJSONArray("observedRegisters")),
        notes = notes,
        defaultEnv = defaultEnv,
    )
}
