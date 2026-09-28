package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.NavigationCapability

/**
 * Fail-closed structural gate before the native engine compiles a module. This is deliberately
 * separate from Wasmtime's complete feature-aware bytecode validator: both checks are required
 * for production activation. In particular, this reader does not purport to validate opcodes.
 */
internal class StrictWasmModuleProfileVerifier : WasmCoreModuleProfileVerifier {
    override fun verify(moduleBytes: ByteArray, navigationCapabilities: Set<NavigationCapability>) {
        require(moduleBytes.size in 8..(8 * 1024 * 1024))
        val reader = WasmReader(moduleBytes)
        require(reader.bytes(8).contentEquals(MAGIC)) { "not a wasm32 core module" }
        val types = ArrayList<Pair<List<Int>, List<Int>>>()
        val functions = ArrayList<Int>()
        val exports = LinkedHashMap<String, Pair<Int, Int>>()
        var importedFunctions = 0
        var memoryCount = 0
        var tableCount = 0
        var definedFunctionCount = 0
        var codeCount = -1
        var lastSection = 0
        while (reader.remaining > 0) {
            val section = reader.u8()
            val size = reader.u32()
            require(size <= reader.remaining) { "section exceeds module" }
            val part = reader.sub(size)
            if (section == 0) {
                val name = part.name()
                require(name != "dylink" && name != "dylink.0" && name != "linking" &&
                    !name.startsWith("reloc.") && !name.startsWith("component")) { "dynamic linking/component metadata" }
                // Other custom sections are inert metadata; they remain bounded by the module cap.
                continue
            }
            require(section in 1..11 && section > lastSection) { "unsupported or repeated wasm section" }
            lastSection = section
            when (section) {
                1 -> repeat(part.count(10_001)) {
                    require(part.u8() == 0x60) { "non-function type" }
                    val params = List(part.count(64)) { part.valueType() }
                    val results = List(part.count(1)) { part.valueType() }
                    types += params to results
                }
                2 -> repeat(part.count(1)) {
                    require(part.name() == "arex_v1" && part.name() == "diagnostic") { "forbidden import" }
                    require(part.u8() == 0) { "only diagnostic function may be imported" }
                    val typeIndex = part.u32()
                    require(types.getOrNull(typeIndex) == (listOf(I32, I32) to listOf(I32))) { "diagnostic signature" }
                    importedFunctions++
                }
                3 -> {
                    definedFunctionCount = part.count(10_000)
                    repeat(definedFunctionCount) { index ->
                        functions += part.u32().also { require(it in types.indices) { "invalid function type" } }
                    }
                }
                4 -> repeat(part.count(1)) {
                    tableCount++
                    require(part.u8() == 0x70) { "only funcref table allowed" }
                    part.limits(4096)
                }
                5 -> repeat(part.count(1)) {
                    memoryCount++
                    part.limits(512)
                }
                6 -> repeat(part.count(4096)) {
                    part.valueType()
                    require(part.u8() in 0..1) { "invalid global mutability" }
                    // Constant expressions are checked by the engine; skip bytes until end opcode.
                    part.constantExpression()
                }
                7 -> repeat(part.count(7)) {
                    val name = part.name()
                    require(exports.put(name, part.u8() to part.u32()) == null) { "duplicate export" }
                }
                8 -> require(false) { "start function is forbidden" }
                9 -> { require(part.count(0) == 0) { "element segments are not supported in v1" } }
                10 -> {
                    codeCount = part.count(10_000)
                    repeat(codeCount) {
                        val bodySize = part.u32()
                        require(bodySize <= part.remaining) { "truncated function body" }
                        part.skip(bodySize)
                    }
                }
                11 -> {
                    // Data segments must be interpreted by the native validator; this preflight
                    // bounds the entire section and never accepts unknown section kinds.
                    require(part.count(4096) >= 0)
                    part.skip(part.remaining)
                }
            }
            require(part.remaining == 0) { "trailing bytes in section $section" }
        }
        require(importedFunctions == 1) { "exactly one diagnostic import is required" }
        require(memoryCount == 1) { "exactly one defined memory is required" }
        require(tableCount <= 1 && definedFunctionCount == codeCount) { "table/code count mismatch" }
        val expected = linkedMapOf(
            "memory" to (2 to null),
            "arex_alloc" to (0 to (listOf(I32) to listOf(I32))),
            "arex_free" to (0 to (listOf(I32, I32) to emptyList<Int>())),
            "plan_requests" to (0 to (listOf(I32, I32) to listOf(I64))),
            "parse_responses" to (0 to (listOf(I32, I32) to listOf(I64))),
        )
        if (navigationCapabilities.isNotEmpty()) {
            expected["plan_navigation"] = 0 to (listOf(I32, I32) to listOf(I64))
            expected["parse_navigation"] = 0 to (listOf(I32, I32) to listOf(I64))
        }
        require(exports.keys == expected.keys) { "exports differ from signed capability profile" }
        for ((name, spec) in expected) {
            val (kind, index) = exports.getValue(name)
            require(kind == spec.first) { "incorrect export kind: $name" }
            if (kind == 2) require(index == 0) { "incorrect memory export" }
            else {
                require(index >= importedFunctions) { "imported function re-export" }
                val type = functions.getOrNull(index - importedFunctions)?.let(types::get)
                require(type == spec.second) { "incorrect export signature: $name" }
            }
        }
    }

    private companion object {
        const val I32 = 0x7f
        const val I64 = 0x7e
        val MAGIC = byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0)
    }
}

/** Native Wasmtime validation must additionally disable all features outside the frozen profile. */
internal class CombinedWasmModuleProfileVerifier(
    private val nativeValidator: WasmCoreModuleProfileVerifier,
) : WasmCoreModuleProfileVerifier {
    private val structural = StrictWasmModuleProfileVerifier()
    override fun verify(moduleBytes: ByteArray, navigationCapabilities: Set<NavigationCapability>) {
        structural.verify(moduleBytes, navigationCapabilities)
        nativeValidator.verify(moduleBytes, navigationCapabilities)
    }
}

private class WasmReader(private val bytes: ByteArray, private var position: Int = 0, private val end: Int = bytes.size) {
    val remaining: Int get() = end - position
    fun u8(): Int { require(position < end) { "truncated wasm" }; return bytes[position++].toInt() and 255 }
    fun bytes(count: Int): ByteArray {
        require(count >= 0 && count <= remaining) { "truncated wasm bytes" }
        return bytes.copyOfRange(position, position + count).also { position += count }
    }
    fun skip(count: Int) { require(count >= 0 && count <= remaining); position += count }
    fun sub(count: Int): WasmReader {
        require(count >= 0 && count <= remaining)
        return WasmReader(bytes, position, position + count).also { position += count }
    }
    fun u32(): Int {
        var value = 0L
        for (shift in 0..28 step 7) {
            val byte = u8()
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) {
                require(value <= Int.MAX_VALUE && (shift != 28 || byte <= 7)) { "non-u32 length" }
                return value.toInt()
            }
        }
        error("leb128 overflow")
    }
    fun count(max: Int): Int = u32().also { require(it <= max) { "wasm count exceeds profile" } }
    fun name(): String {
        val raw = bytes(count(256))
        val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString()
        require(decoded.toByteArray(Charsets.UTF_8).contentEquals(raw) && decoded.all { it.code in 33..126 }) { "invalid wasm name" }
        return decoded
    }
    fun valueType(): Int = u8().also { require(it == 0x7f || it == 0x7e || it == 0x7d || it == 0x7c) { "unsupported value type" } }
    fun limits(max: Int) {
        val flags = u32()
        require(flags in 0..1) { "shared, memory64, or unsupported limits" }
        val min = u32()
        require(min <= max) { "minimum exceeds profile" }
        if (flags == 1) require(u32() in min..max) { "maximum exceeds profile" }
    }
    fun constantExpression() {
        when (u8()) {
            0x41, 0x42 -> { var n = 0; while (u8() and 0x80 != 0) { require(++n < 10) } }
            0x43 -> skip(4)
            0x44 -> skip(8)
            0x23 -> u32()
            else -> error("unsupported global initializer")
        }
        require(u8() == 0x0b) { "unterminated initializer" }
    }
}
