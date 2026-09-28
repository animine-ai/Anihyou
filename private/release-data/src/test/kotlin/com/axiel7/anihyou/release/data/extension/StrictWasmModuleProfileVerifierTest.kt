package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.NavigationCapability
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class StrictWasmModuleProfileVerifierTest {
    private val verifier = StrictWasmModuleProfileVerifier()

    @Test fun `minimal core module and declared navigation exports pass structural gate`() {
        verifier.verify(module(), emptySet())
        verifier.verify(module(navigation = true), setOf(NavigationCapability.OVERVIEW_NAVIGATION))
    }

    @Test fun `navigation export without signed grant fails`() {
        assertThrows(IllegalArgumentException::class.java) { verifier.verify(module(navigation = true), emptySet()) }
    }

    @Test fun `undeclared import, shared memory and start function fail`() {
        assertThrows(IllegalArgumentException::class.java) { verifier.verify(module(importName = "network"), emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { verifier.verify(module(memoryFlags = 3), emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { verifier.verify(module(start = true), emptySet()) }
    }

    @Test fun `wrong export signature and wasm64 are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { verifier.verify(module(wrongSignature = true), emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { verifier.verify(module(memoryFlags = 4), emptySet()) }
    }

    @Test fun `missing diagnostic import and data-count section are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verify(module(includeDiagnosticImport = false), emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verify(module(dataCount = true), emptySet())
        }
    }

    private fun module(
        navigation: Boolean = false,
        importName: String = "diagnostic",
        memoryFlags: Int = 1,
        start: Boolean = false,
        wrongSignature: Boolean = false,
        includeDiagnosticImport: Boolean = true,
        dataCount: Boolean = false,
    ): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0))
        // Four function types: diagnostic, allocator, free, operation.
        section(1, bytes(4, 0x60, 2, 0x7f, 0x7f, 1, 0x7f,
            0x60, 1, 0x7f, 1, 0x7f,
            0x60, 2, 0x7f, 0x7f, 0,
            0x60, 2, 0x7f, 0x7f, 1, 0x7e))
        if (includeDiagnosticImport) {
            section(2, ByteArrayOutputStream().apply {
                write(1); name("arex_v1"); name(importName); write(0); write(0)
            }.toByteArray())
        }
        val types = (if (navigation) listOf(1, 2, 3, 3, 3, 3) else listOf(1, 2, 3, 3))
            .toMutableList().also { if (wrongSignature) it[2] = 2 }
        section(3, bytes(types.size, *types.toIntArray()))
        section(5, bytes(1, memoryFlags, 1, 2))
        val names = listOf("memory", "arex_alloc", "arex_free", "plan_requests", "parse_responses") +
            if (navigation) listOf("plan_navigation", "parse_navigation") else emptyList()
        section(7, ByteArrayOutputStream().apply {
            write(names.size)
            names.forEachIndexed { index, name ->
                name(name); write(if (index == 0) 2 else 0)
                write(if (index == 0) 0 else index)
            }
        }.toByteArray())
        if (start) section(8, bytes(1))
        if (dataCount) section(12, bytes(0))
        section(10, ByteArrayOutputStream().apply {
            write(types.size)
            types.forEachIndexed { index, _ ->
                val body = when {
                    index == 0 || (wrongSignature && index == 2) -> bytes(0, 0x41, 1, 0x0b)
                    index == 1 -> bytes(0, 0x0b)
                    else -> bytes(0, 0x42, 0, 0x0b)
                }
                write(body.size); write(body)
            }
        }.toByteArray())
    }.toByteArray()

    private fun ByteArrayOutputStream.section(id: Int, payload: ByteArray) {
        write(id); write(payload.size); write(payload)
    }
    private fun ByteArrayOutputStream.name(name: String) {
        val bytes = name.toByteArray(); write(bytes.size); write(bytes)
    }
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
}
