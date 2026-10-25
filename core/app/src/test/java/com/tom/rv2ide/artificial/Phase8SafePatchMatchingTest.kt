package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.coding.PatchMatchTier
import com.tom.rv2ide.artificial.coding.TransactionalFileCoordinator
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8SafePatchMatchingTest {

    @Test
    fun `test 1 - exact match succeeds with Tier 1`() {
        val baseContent = """
            package com.example
            
            class Greeter {
                fun greet(): String {
                    return "Hello, World!"
                }
            }
        """.trimIndent()

        val searchBlock = """
                fun greet(): String {
                    return "Hello, World!"
                }
        """.trimIndent().replaceIndent("    ")

        val replaceBlock = """
                fun greet(): String {
                    return "Hello, ROXUM!"
                }
        """.trimIndent().replaceIndent("    ")

        val result = TransactionalFileCoordinator.applySearchReplaceWithDiagnostics(baseContent, searchBlock, replaceBlock)
        assertEquals(PatchMatchTier.EXACT, result.tier)
        assertNotNull(result.updatedContent)
        assertTrue(result.updatedContent!!.contains("return \"Hello, ROXUM!\""))
        assertFalse(result.updatedContent!!.contains("return \"Hello, World!\""))
    }

    @Test
    fun `test 2 - CRLF search block matches LF file content and vice versa with Tier 2`() {
        // Part A: Base content has LF, search block has CRLF
        val baseContentLf = "line1\nline2\nline3\n"
        val searchBlockCrlf = "line2\r\n"
        val replaceBlockCrlf = "line2_modified\r\n"

        val resultA = TransactionalFileCoordinator.applySearchReplaceWithDiagnostics(baseContentLf, searchBlockCrlf, replaceBlockCrlf)
        assertEquals(PatchMatchTier.LINE_ENDING_NORMALIZED, resultA.tier)
        assertNotNull(resultA.updatedContent)
        assertEquals("line1\nline2_modified\nline3\n", resultA.updatedContent)

        // Part B: Base content has CRLF, search block has LF
        val baseContentCrlf = "line1\r\nline2\r\nline3\r\n"
        val searchBlockLf = "line2\n"
        val replaceBlockLf = "line2_modified\n"

        val resultB = TransactionalFileCoordinator.applySearchReplaceWithDiagnostics(baseContentCrlf, searchBlockLf, replaceBlockLf)
        assertEquals(PatchMatchTier.LINE_ENDING_NORMALIZED, resultB.tier)
        assertNotNull(resultB.updatedContent)
        assertEquals("line1\r\nline2_modified\r\nline3\r\n", resultB.updatedContent)
    }

    @Test
    fun `test 3 - search block with zero indentation matches file content with 4-space indentation and properly indents replacement`() {
        val baseContent = """
            class Service {
                fun calculate(x: Int): Int {
                    val y = x * 2
                    return y + 1
                }
            }
        """.trimIndent()

        val searchBlockZeroIndent = """
            fun calculate(x: Int): Int {
                val y = x * 2
                return y + 1
            }
        """.trimIndent()

        val replaceBlockZeroIndent = """
            fun calculate(x: Int): Int {
                val y = x * 3
                return y + 2
            }
        """.trimIndent()

        val result = TransactionalFileCoordinator.applySearchReplaceWithDiagnostics(baseContent, searchBlockZeroIndent, replaceBlockZeroIndent)
        assertEquals(PatchMatchTier.INDENTATION_TOLERANT, result.tier)
        assertNotNull(result.updatedContent)

        val expected = """
            class Service {
                fun calculate(x: Int): Int {
                    val y = x * 3
                    return y + 2
                }
            }
        """.trimIndent()
        assertEquals(expected, result.updatedContent)
    }

    @Test
    fun `test 4 - search block with 4-space indentation matches file content with 8-space indentation and adjusts`() {
        val baseContent = """
            class Worker {
                fun execute() {
                    val a = 1
                    val b = 2
                    val c = a + b
                }
            }
        """.trimIndent()

        val searchBlock = """
                val a = 1
                val b = 2
                val c = a + b
        """.trimIndent()

        val replaceBlock = """
                val a = 10
                val b = 20
                val c = a + b
        """.trimIndent()

        val result = TransactionalFileCoordinator.applySearchReplaceWithDiagnostics(baseContent, searchBlock, replaceBlock)
        assertEquals(PatchMatchTier.INDENTATION_TOLERANT, result.tier)
        assertNotNull(result.updatedContent)

        val expected = """
            class Worker {
                fun execute() {
                    val a = 10
                    val b = 20
                    val c = a + b
                }
            }
        """.trimIndent()
        assertEquals(expected, result.updatedContent)
    }

    @Test
    fun `test 5 - tab-indented file matches space-indented search block with consistent depth`() {
        val baseContent = "\tfun process() {\n\t\tval step = 1\n\t\tdoWork(step)\n\t}"
        val searchBlockSpaces = "    fun process() {\n        val step = 1\n        doWork(step)\n    }"
        val replaceBlockSpaces = "    fun process() {\n        val step = 2\n        doWork(step)\n    }"

        val result = TransactionalFileCoordinator.applySearchReplaceWithDiagnostics(baseContent, searchBlockSpaces, replaceBlockSpaces)
        assertEquals(PatchMatchTier.INDENTATION_TOLERANT, result.tier)
        assertNotNull(result.updatedContent)

        val expected = "\tfun process() {\n\t\tval step = 2\n\t\tdoWork(step)\n\t}"
        assertEquals(expected, result.updatedContent)
    }

    @Test
    fun `test 6 - conservative fuzzy match succeeds when similarity is at least 0_85`() {
        val baseContent = """
            fun renderHeader(title: String, subtitle: String): String {
                val formattedTitle = title.trim().uppercase()
                val formattedSubtitle = subtitle.trim()
                return "${'$'}formattedTitle - ${'$'}formattedSubtitle"
            }
        """.trimIndent()

        // Search block contains a minor typo 'formatedTitle' vs 'formattedTitle' (similarity > 0.95)
        val searchBlockWithTypo = """
            fun renderHeader(title: String, subtitle: String): String {
                val formatedTitle = title.trim().uppercase()
                val formattedSubtitle = subtitle.trim()
                return "${'$'}formatedTitle - ${'$'}formattedSubtitle"
            }
        """.trimIndent()

        val replaceBlock = """
            fun renderHeader(title: String, subtitle: String): String {
                val formattedTitle = title.trim().uppercase()
                val formattedSubtitle = subtitle.trim().lowercase()
                return "${'$'}formattedTitle: ${'$'}formattedSubtitle"
            }
        """.trimIndent()

        val result = TransactionalFileCoordinator.applySearchReplaceWithDiagnostics(baseContent, searchBlockWithTypo, replaceBlock)
        assertEquals(PatchMatchTier.CONSERVATIVE_FUZZY, result.tier)
        assertNotNull(result.updatedContent)
        assertTrue(result.updatedContent!!.contains("return \"\$formattedTitle: \$formattedSubtitle\""))
    }

    @Test
    fun `test 7 - fuzzy match rejected when similarity is below 0_85 threshold`() {
        val baseContent = """
            fun computeMetrics(data: List<Double>): MetricsResult {
                val sum = data.sum()
                val mean = sum / data.size
                val variance = data.map { (it - mean) * (it - mean) }.sum() / data.size
                return MetricsResult(mean, Math.sqrt(variance))
            }
        """.trimIndent()

        // Search block has ~70% similarity with base content (below the 0.85 threshold)
        val lowSimilaritySearch = """
            fun computeMetrics(data: List<Double>): MetricsResult {
                val total = data.sum()
                val average = total / data.size
                val stdDev = Math.sqrt(data.map { (it - average) * (it - average) }.sum() / data.size)
                return MetricsResult(average, stdDev)
            }
        """.trimIndent()

        val replaceBlock = """
            fun computeMetrics(data: List<Double>): MetricsResult = MetricsResult(0.0, 0.0)
        """.trimIndent()

        val result = TransactionalFileCoordinator.applySearchReplaceWithDiagnostics(baseContent, lowSimilaritySearch, replaceBlock)
        assertEquals(PatchMatchTier.NONE, result.tier)
        assertNull(result.updatedContent)
        assertNotNull(result.failureReason)
        assertTrue(result.failureReason!!.contains("below the required 85% threshold"))
    }

    @Test
    fun `test 8 - fuzzy match rejected when two identical or near-identical blocks exist in the file`() {
        val baseContent = """
            class DuplicateContainer {
                fun operationA() {
                    val token = "unique_security_token_payload"
                    val timestamp = 1234567890L
                    validateToken(token, timestamp)
                }

                fun intermediateHelper() {
                    println("separator")
                }

                fun operationB() {
                    val token = "unique_security_token_payload"
                    val timestamp = 1234567890L
                    validateToken(token, timestamp)
                }
            }
        """.trimIndent()

        // Slight variation in search block that would match either operationA or operationB with similarity > 0.85
        val ambiguousSearch = """
                    val tokn = "unique_security_token_payload"
                    val timestamp = 1234567890L
                    validateToken(tokn, timestamp)
        """.trimIndent()

        val replaceBlock = """
                    val token = "refreshed_token"
                    val timestamp = 9999999999L
                    validateToken(token, timestamp)
        """.trimIndent()

        val result = TransactionalFileCoordinator.applySearchReplaceWithDiagnostics(baseContent, ambiguousSearch, replaceBlock)
        assertEquals(PatchMatchTier.NONE, result.tier)
        assertNull(result.updatedContent)
        assertNotNull(result.failureReason)
        assertTrue("Must report ambiguous fuzzy match", result.failureReason!!.contains("Ambiguous fuzzy match"))
    }

    @Test
    fun `test 9 - fuzzy match rejected for short blocks under 3 lines or 20 characters`() {
        val baseContent = """
            val x = 100
            val y = 200
            val z = 300
        """.trimIndent()

        // Single line block
        val singleLineSearch = "val x = 105"
        val singleLineReplace = "val x = 999"

        val result1 = TransactionalFileCoordinator.applySearchReplaceWithDiagnostics(baseContent, singleLineSearch, singleLineReplace)
        assertEquals(PatchMatchTier.NONE, result1.tier)
        assertNull(result1.updatedContent)
        assertTrue(result1.failureReason!!.contains("too short for fuzzy matching"))

        // Two lines but short (< 20 chars total)
        val shortSearch = "a = 1\nb = 2"
        val result2 = TransactionalFileCoordinator.applySearchReplaceWithDiagnostics(baseContent, shortSearch, "c = 3")
        assertEquals(PatchMatchTier.NONE, result2.tier)
        assertNull(result2.updatedContent)
        assertTrue(result2.failureReason!!.contains("too short for fuzzy matching"))
    }

    @Test
    fun `test 10 - mismatch produces actionable feedback with line numbers and nearest candidate lines`() {
        val tempRoot = Files.createTempDirectory("roxum_phase8_diagnostics").toFile()
        try {
            val targetFile = File(tempRoot, "src/Main.kt").apply {
                parentFile?.mkdirs()
                writeText(
                    """
                    package com.example
                    
                    class Target {
                        fun originalAlphaMethod(): String {
                            val msg = "alpha message payload"
                            return msg.uppercase()
                        }
                    }
                    """.trimIndent()
                )
            }

            // Search block has partial similarity (~0.70, below 0.85 threshold)
            val patchPrompt = """
                FILE_TO_PATCH: src/Main.kt
                <<<<<<< SEARCH
                    fun executeBetaTask(flag: Boolean): String {
                        val textPayload = "alpha message payload"
                        return textPayload.lowercase()
                    }
                =======
                    fun replacementMethod(): String = "done"
                >>>>>>> REPLACE
            """.trimIndent()

            val validation = TransactionalFileCoordinator.parseProposedChangeSet(patchPrompt, tempRoot)
            assertFalse("ChangeSet must be rejected due to similarity below 0.85", validation.isValid)
            assertEquals(1, validation.errors.size)

            val errorMsg = validation.errors[0]
            assertTrue("Error message must state reason", errorMsg.contains("below the required 85% threshold"))
            assertTrue("Error message must suggest line range", errorMsg.contains("Did you mean to match lines 4-7?"))
            assertTrue("Error message must show candidate lines", errorMsg.contains("fun originalAlphaMethod(): String {"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `test 11 - multi-file patch where one file fails to match modifies zero files on disk`() {
        val tempRoot = Files.createTempDirectory("roxum_phase8_tx_rollback").toFile()
        try {
            val fileA = File(tempRoot, "src/A.kt").apply {
                parentFile?.mkdirs()
                writeText("package com.example\nfun alpha() = 1\n")
            }
            val fileB = File(tempRoot, "src/B.kt").apply {
                parentFile?.mkdirs()
                writeText("package com.example\nfun beta() = 2\n")
            }

            // File A has valid patch, but File B has completely mismatched search block
            val multiPatchPrompt = """
                FILE_TO_PATCH: src/A.kt
                <<<<<<< SEARCH
                fun alpha() = 1
                =======
                fun alpha() = 100
                >>>>>>> REPLACE

                FILE_TO_PATCH: src/B.kt
                <<<<<<< SEARCH
                fun nonExistentSymbol() = 999
                =======
                fun beta() = 200
                >>>>>>> REPLACE
            """.trimIndent()

            val validation = TransactionalFileCoordinator.parseProposedChangeSet(multiPatchPrompt, tempRoot)
            assertFalse("ChangeSet must fail validation when any patch fails to match", validation.isValid)
            assertTrue("Validation errors must mention B.kt", validation.errors.any { it.contains("B.kt") })

            // Verify disk state: neither A.kt nor B.kt must be modified
            assertEquals("package com.example\nfun alpha() = 1\n", fileA.readText())
            assertEquals("package com.example\nfun beta() = 2\n", fileB.readText())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `test 12 - patching C-Cpp files preserves preprocessor directives and comments`() {
        val cppSource = """
            #include <jni.h>
            #include <string>
            #define BUFFER_SIZE 1024

            // Critical security verification hook
            extern "C" JNIEXPORT jstring JNICALL
            Java_com_example_MainActivity_stringFromJNI(JNIEnv* env, jobject /* this */) {
                std::string hello = "Hello from Native C++";
                return env->NewStringUTF(hello.c_str());
            }

            // End of native file
        """.trimIndent()

        val searchBlock = """
            extern "C" JNIEXPORT jstring JNICALL
            Java_com_example_MainActivity_stringFromJNI(JNIEnv* env, jobject /* this */) {
                std::string hello = "Hello from Native C++";
                return env->NewStringUTF(hello.c_str());
            }
        """.trimIndent()

        val replaceBlock = """
            extern "C" JNIEXPORT jstring JNICALL
            Java_com_example_MainActivity_stringFromJNI(JNIEnv* env, jobject /* this */) {
                std::string hello = "Hello from Patched C++";
                return env->NewStringUTF(hello.c_str());
            }
        """.trimIndent()

        val result = TransactionalFileCoordinator.applySearchReplaceWithDiagnostics(cppSource, searchBlock, replaceBlock)
        assertEquals(PatchMatchTier.EXACT, result.tier)
        assertNotNull(result.updatedContent)

        val updated = result.updatedContent!!
        assertTrue(updated.contains("#include <jni.h>"))
        assertTrue(updated.contains("#include <string>"))
        assertTrue(updated.contains("#define BUFFER_SIZE 1024"))
        assertTrue(updated.contains("// Critical security verification hook"))
        assertTrue(updated.contains("Hello from Patched C++"))
        assertTrue(updated.contains("// End of native file"))
    }
}
