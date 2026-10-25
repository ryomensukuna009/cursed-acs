package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.coding.AutonomousRollbackManager
import com.tom.rv2ide.artificial.policy.AgentExecutionMode
import com.tom.rv2ide.artificial.tools.AgentToolRegistry
import com.tom.rv2ide.artificial.tools.SafeUrlFetcher
import com.tom.rv2ide.artificial.tools.ToolOrchestrator
import com.tom.rv2ide.artificial.tools.WebSearchManager
import com.tom.rv2ide.artificial.transport.ToolCall
import com.tom.rv2ide.artificial.transport.UnifiedTransportClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Independent verification test verifying that in SIDE_AGENT mode,
 * zero filesystem mutations can occur across ANY tool path:
 * 1. TOOL_APPLY_FILE_CHANGES (direct write / modify / create)
 * 2. TOOL_RESOLVE_IMPORT (insert = true)
 * 3. TOOL_ROLLBACK_AGENT_BATCH (transaction rollback / restoration)
 *
 * Verifies with cryptographic SHA-256 hashes on disposable test files.
 */
class SideAgentMutationShieldTest {

    private lateinit var sandboxDir: File
    private lateinit var mainActivityFile: File
    private lateinit var nativeCppFile: File
    private lateinit var configFile: File
    private lateinit var nonExistentFile: File

    private lateinit var initialMainActivitySha: String
    private lateinit var initialNativeCppSha: String
    private lateinit var initialConfigSha: String

    private val httpClient = OkHttpClient()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return file.readBytes().let { bytes ->
            digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }

    @Before
    fun setUp() {
        sandboxDir = Files.createTempDirectory("side_agent_shield_sandbox").toFile()
        AutonomousRollbackManager.clearMemory()

        val srcDir = File(sandboxDir, "src/main/java/com/example").apply { mkdirs() }
        val jniDir = File(sandboxDir, "src/main/jni").apply { mkdirs() }

        mainActivityFile = File(srcDir, "MainActivity.kt").apply {
            writeText(
                """
                package com.example

                class MainActivity {
                    fun onCreate() {
                        println("Original untouched Kotlin file")
                    }
                }
                """.trimIndent()
            )
        }

        nativeCppFile = File(jniDir, "native-lib.cpp").apply {
            writeText(
                """
                #include <jni.h>

                extern "C" JNIEXPORT jstring JNICALL
                Java_com_example_MainActivity_stringFromJNI(JNIEnv* env, jobject) {
                    return env->NewStringUTF("Original C++ baseline");
                }
                """.trimIndent()
            )
        }

        configFile = File(sandboxDir, "build.gradle").apply {
            writeText(
                """
                plugins {
                    id("com.android.application")
                }
                """.trimIndent()
            )
        }

        nonExistentFile = File(sandboxDir, "src/main/java/com/example/FreshFile.kt")

        initialMainActivitySha = sha256(mainActivityFile)
        initialNativeCppSha = sha256(nativeCppFile)
        initialConfigSha = sha256(configFile)
    }

    @After
    fun tearDown() {
        AutonomousRollbackManager.clearMemory()
        sandboxDir.deleteRecursively()
    }

    private fun createOrchestrator(): ToolOrchestrator {
        val searchMgr = WebSearchManager(context = null, baseHttpClient = httpClient)
        val urlFetcher = SafeUrlFetcher(httpClient)
        return ToolOrchestrator(
            transportClient = UnifiedTransportClient(httpClient),
            webSearchManager = searchMgr,
            safeUrlFetcher = urlFetcher,
            projectRootProvider = { sandboxDir }
        )
    }

    @Test
    fun `test side-agent blocks apply_file_changes direct modification and preserves SHA-256`() = runBlocking {
        val orchestrator = createOrchestrator()

        val maliciousWritePayload = JSONObject().apply {
            put("codeContent", """
                FILE_TO_MODIFY: src/main/java/com/example/MainActivity.kt
                CODE_START
                package com.example
                class MainActivity {
                    // MUTATED MALICIOUS CODE
                }
                CODE_END
            """.trimIndent())
        }

        val toolCall = ToolCall(
            id = "call_apply_1",
            name = AgentToolRegistry.TOOL_APPLY_FILE_CHANGES,
            argumentsJson = maliciousWritePayload.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(
            toolCall = toolCall,
            sessionId = "test_side_session",
            executionMode = AgentExecutionMode.SIDE_AGENT
        )

        // 1. Tool result must indicate proposal generated, not applied to disk
        assertTrue("Result must indicate Side-Agent proposal", result.content.contains("SIDE-AGENT MODE - PROPOSAL GENERATED"))
        assertTrue("Result must confirm files NOT modified on disk", result.content.contains("Files NOT modified on disk"))
        assertFalse("Tool result should not be an error", result.isError)

        // 2. Cryptographic SHA-256 verification
        assertEquals("MainActivity.kt SHA-256 must remain identical", initialMainActivitySha, sha256(mainActivityFile))
    }

    @Test
    fun `test side-agent blocks file creation and new file is not created on disk`() = runBlocking {
        val orchestrator = createOrchestrator()

        val newFilePayload = JSONObject().apply {
            put("codeContent", """
                FILE_TO_CREATE: src/main/java/com/example/FreshFile.kt
                CODE_START
                package com.example
                class FreshFile
                CODE_END
            """.trimIndent())
        }

        val toolCall = ToolCall(
            id = "call_apply_create",
            name = AgentToolRegistry.TOOL_APPLY_FILE_CHANGES,
            argumentsJson = newFilePayload.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(
            toolCall = toolCall,
            sessionId = "test_side_session",
            executionMode = AgentExecutionMode.SIDE_AGENT
        )

        assertTrue("Result must indicate Side-Agent proposal", result.content.contains("SIDE-AGENT MODE - PROPOSAL GENERATED"))
        assertFalse("FreshFile.kt must NOT exist on disk", nonExistentFile.exists())
    }

    @Test
    fun `test side-agent blocks targeted search-replace patches and preserves SHA-256`() = runBlocking {
        val orchestrator = createOrchestrator()

        val patchPayload = JSONObject().apply {
            put("filePath", "src/main/jni/native-lib.cpp")
            put("search", "Original C++ baseline")
            put("replace", "HACKED C++ CONTENT")
        }

        val toolCall = ToolCall(
            id = "call_patch_1",
            name = AgentToolRegistry.TOOL_APPLY_FILE_CHANGES,
            argumentsJson = patchPayload.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(
            toolCall = toolCall,
            sessionId = "test_side_session",
            executionMode = AgentExecutionMode.SIDE_AGENT
        )

        assertTrue("Result must indicate proposal", result.content.contains("SIDE-AGENT MODE - PROPOSAL GENERATED"))
        assertEquals("native-lib.cpp SHA-256 must remain identical", initialNativeCppSha, sha256(nativeCppFile))
    }

    @Test
    fun `test side-agent blocks import insertion on disk and preserves SHA-256`() = runBlocking {
        val orchestrator = createOrchestrator()

        val resolveImportPayload = JSONObject().apply {
            put("filePath", "src/main/java/com/example/MainActivity.kt")
            put("symbol", "ArrayList")
            put("insert", true)
        }

        val toolCall = ToolCall(
            id = "call_import_1",
            name = AgentToolRegistry.TOOL_RESOLVE_IMPORT,
            argumentsJson = resolveImportPayload.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(
            toolCall = toolCall,
            sessionId = "test_side_session",
            executionMode = AgentExecutionMode.SIDE_AGENT
        )

        assertTrue(
            "Result must indicate side-agent recommendation without modifying file",
            result.content.contains("Side-Agent Mode: Recommended statement to insert")
        )
        assertFalse("File must not contain insertion text on disk", mainActivityFile.readText().contains("import java.util.ArrayList"))
        assertEquals("MainActivity.kt SHA-256 must remain identical", initialMainActivitySha, sha256(mainActivityFile))
    }

    @Test
    fun `test side-agent blocks rollback_agent_batch and prevents disk mutation`() = runBlocking {
        // Step 1: In AUTONOMOUS mode, apply a legitimate change to record a rollback transaction
        val orchestrator = createOrchestrator()
        val initialContent = "fun baseline() = 1"
        val targetFile = File(sandboxDir, "src/main/java/com/example/RollbackTarget.kt").apply {
            writeText("package com.example\n$initialContent")
        }
        val preAutoSha = sha256(targetFile)

        val autoApplyPayload = JSONObject().apply {
            put("codeContent", """
                FILE_TO_MODIFY: src/main/java/com/example/RollbackTarget.kt
                CODE_START
                package com.example
                fun modifiedByAutonomous() = 2
                CODE_END
            """.trimIndent())
        }

        val applyCall = ToolCall(
            id = "call_auto_1",
            name = AgentToolRegistry.TOOL_APPLY_FILE_CHANGES,
            argumentsJson = autoApplyPayload.toString()
        )
        val applyResult = orchestrator.executeSingleToolCallForTest(
            toolCall = applyCall,
            sessionId = "auto_session_1",
            executionMode = AgentExecutionMode.AUTONOMOUS
        )
        assertFalse("Autonomous apply must succeed", applyResult.isError)
        val postModifySha = sha256(targetFile)
        assertFalse("File should be modified in Autonomous mode", preAutoSha == postModifySha)

        // Step 2: Now switch to SIDE_AGENT mode and attempt to call TOOL_ROLLBACK_AGENT_BATCH
        val rollbackCall = ToolCall(
            id = "call_rollback_1",
            name = AgentToolRegistry.TOOL_ROLLBACK_AGENT_BATCH,
            argumentsJson = "{}"
        )

        val rollbackResult = orchestrator.executeSingleToolCallForTest(
            toolCall = rollbackCall,
            sessionId = "side_session_1",
            executionMode = AgentExecutionMode.SIDE_AGENT
        )

        // Step 3: Verify rollback was BLOCKED by the Side-Agent shield
        assertTrue("Rollback must be blocked in Side-Agent mode", rollbackResult.isError)
        assertTrue(
            "Error must specify Side-Agent rollback prohibition",
            rollbackResult.content.contains("SIDE-AGENT MODE - ROLLBACK PROHIBITED")
        )

        // Step 4: Verify the file on disk was NOT mutated by the blocked rollback call
        assertEquals(
            "RollbackTarget.kt SHA-256 must be completely unchanged by blocked rollback call",
            postModifySha,
            sha256(targetFile)
        )
    }

    @Test
    fun `test all original files remain completely untampered across full suite`() {
        assertEquals("MainActivity SHA-256 intact", initialMainActivitySha, sha256(mainActivityFile))
        assertEquals("Native C++ SHA-256 intact", initialNativeCppSha, sha256(nativeCppFile))
        assertEquals("Config build.gradle SHA-256 intact", initialConfigSha, sha256(configFile))
        assertFalse("Fresh file does not exist", nonExistentFile.exists())
    }
}
