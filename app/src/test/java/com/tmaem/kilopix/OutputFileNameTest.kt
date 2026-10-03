package com.tmaem.kilopix

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task 20: deterministic JVM regression tests for [OutputFileName]'s pure naming policy
 * (sanitization, the Task 18 80-character base cap, ".jpg" generation and collision-safe
 * suffixes). No Android runtime is required; these methods never touch provider APIs.
 */
class OutputFileNameTest {

    @Test
    fun sanitizeBase_removesUnsafeCharacters() {
        assertEquals("abcdefghij", OutputFileName.sanitizeBase("a/b\\c:d*e?f\"g<h>i|j"))
    }

    @Test
    fun sanitizeBase_removesNulAndControlCharacters() {
        assertEquals("ab", OutputFileName.sanitizeBase("a\u0000b"))
        assertEquals("compressed", OutputFileName.sanitizeBase("\u0001\u001F"))
    }

    @Test
    fun sanitizeBase_emptyOrAllUnsafe_fallsBackToDefault() {
        assertEquals("compressed", OutputFileName.sanitizeBase(""))
        assertEquals("compressed", OutputFileName.sanitizeBase("\\/:*?\"<>|"))
    }

    @Test
    fun sanitizeBase_preservesSafeCharacters() {
        assertEquals("my photo (1)", OutputFileName.sanitizeBase("my photo (1)"))
        assertEquals("café_日本-1", OutputFileName.sanitizeBase("café_日本-1"))
    }

    @Test
    fun sanitizeBase_capsBaseAt80Characters() {
        val sanitized = OutputFileName.sanitizeBase("a".repeat(200))
        assertEquals(80, sanitized.length)
        assertEquals("a".repeat(80), sanitized)
    }

    @Test
    fun sanitizeBase_exact80CharacterInput_isUnchanged() {
        val input = "b".repeat(80)
        assertEquals(input, OutputFileName.sanitizeBase(input))
    }

    @Test
    fun sanitizeBase_81Characters_truncatesTo80() {
        assertEquals("c".repeat(80), OutputFileName.sanitizeBase("c".repeat(81)))
    }

    @Test
    fun jpegName_appendsJpegExtension() {
        assertEquals("image.jpg", OutputFileName.jpegName("image"))
        assertEquals("compressed.jpg", OutputFileName.jpegName("compressed"))
    }

    @Test
    fun collisionSafeName_returnsNameWhenFree() {
        assertEquals("image.jpg", OutputFileName.collisionSafeName("image.jpg") { false })
    }

    @Test
    fun collisionSafeName_appendsNumericSuffix() {
        assertEquals(
            "image (1).jpg",
            OutputFileName.collisionSafeName("image.jpg") { it == "image.jpg" },
        )
        val occupied = setOf("image.jpg", "image (1).jpg")
        assertEquals(
            "image (2).jpg",
            OutputFileName.collisionSafeName("image.jpg") { it in occupied },
        )
    }

    @Test
    fun collisionSafeName_withoutExtension_appendsSuffixToWholeName() {
        assertEquals(
            "image (1)",
            OutputFileName.collisionSafeName("image") { it == "image" },
        )
    }

    @Test
    fun collisionSafeName_preservesExtensionAfterTruncation() {
        val base = OutputFileName.sanitizeBase("x".repeat(200))
        assertEquals(80, base.length)
        val jpeg = OutputFileName.jpegName(base)
        val result = OutputFileName.collisionSafeName(jpeg) { it == jpeg }
        assertEquals("${"x".repeat(80)} (1).jpg", result)
    }
}
