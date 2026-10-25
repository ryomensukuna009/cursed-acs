package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.policy.AgentExecutionMode
import com.tom.rv2ide.artificial.transport.ToolDefinition
import com.tom.rv2ide.artificial.transport.ToolParameterProperty
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentCoercerTest {

    @Test
    fun testJsonObjectReStringifiedWhenStringExpected() {
        val testDef = ToolDefinition(
            name = "write_config",
            description = "Writes configuration file",
            parameters = listOf(
                ToolParameterProperty(name = "filePath", type = "string", description = "Path", required = true),
                ToolParameterProperty(name = "content", type = "string", description = "Content", required = true)
            )
        )

        // Model passes a JSON object for content instead of a string
        val nestedJson = JSONObject().apply {
            put("version", "1.0.0")
            put("buildTools", "34.0.0")
            put("dependencies", JSONArray().put("androidx.core:core-ktx:1.12.0"))
        }

        val rawArgs = JSONObject().apply {
            put("filePath", "config.json")
            put("content", nestedJson)
        }

        val result = ToolArgumentCoercer.coerceArgs("write_config", rawArgs, testDef)
        assertTrue(result.isSuccess)
        assertTrue(result.errors.isEmpty())

        val coercedContent = result.args.optString("content", "")
        assertTrue(coercedContent.isNotBlank())
        assertTrue(coercedContent.contains("\"version\""))
        assertTrue(coercedContent.contains("1.0.0"))
        assertTrue(coercedContent.contains("androidx.core"))
    }

    @Test
    fun testJsonArrayReStringifiedWhenStringExpected() {
        val testDef = ToolDefinition(
            name = "update_list",
            description = "Updates item list",
            parameters = listOf(
                ToolParameterProperty(name = "items", type = "string", description = "Items string", required = true)
            )
        )

        val rawArgs = JSONObject().apply {
            put("items", JSONArray().put("itemA").put("itemB"))
        }

        val result = ToolArgumentCoercer.coerceArgs("update_list", rawArgs, testDef)
        assertTrue(result.isSuccess)
        assertEquals("[\"itemA\",\"itemB\"]", result.args.getString("items"))
    }

    @Test
    fun testNumberAndBooleanCoercion() {
        val testDef = ToolDefinition(
            name = "configure_options",
            description = "Configures integer and boolean options",
            parameters = listOf(
                ToolParameterProperty(name = "limit", type = "integer", description = "Limit count", required = true),
                ToolParameterProperty(name = "enabled", type = "boolean", description = "Flag", required = true)
            )
        )

        val rawArgs = JSONObject().apply {
            put("limit", "50") // String representing integer
            put("enabled", "true") // String representing boolean
        }

        val result = ToolArgumentCoercer.coerceArgs("configure_options", rawArgs, testDef)
        assertTrue(result.isSuccess)
        assertEquals(50, result.args.getInt("limit"))
        assertEquals(true, result.args.getBoolean("enabled"))
    }

    @Test
    fun testMissingRequiredParameterValidation() {
        val testDef = ToolDefinition(
            name = "read_file",
            description = "Reads a file",
            parameters = listOf(
                ToolParameterProperty(name = "filePath", type = "string", description = "Path", required = true)
            )
        )

        val emptyArgs = JSONObject()
        val result = ToolArgumentCoercer.coerceArgs("read_file", emptyArgs, testDef)
        assertFalse(result.isSuccess)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("Required parameter 'filePath' is missing"))
    }

    @Test
    fun testAllSevenFilePathAliasesResolveAcrossRegisteredTools() {
        val aliases = listOf(
            "filePath",
            "path",
            "filepath",
            "file_path",
            "filename",
            "targetFile",
            "relPath"
        )

        val toolsToTest = listOf(
            Pair(AgentToolRegistry.TOOL_APPLY_FILE_CHANGES, AgentToolRegistry.applyFileChangesToolDefinition),
            Pair(AgentToolRegistry.TOOL_READ_FILE_RANGE, AgentToolRegistry.readFileRangeToolDefinition),
            Pair(AgentToolRegistry.TOOL_READ_PROJECT_FILE, AgentToolRegistry.readProjectFileToolDefinition),
            Pair(AgentToolRegistry.TOOL_GET_FILE_OUTLINE, AgentToolRegistry.getFileOutlineToolDefinition)
        )

        for (alias in aliases) {
            for ((toolName, toolDef) in toolsToTest) {
                val rawArgs = JSONObject().apply {
                    put(alias, "app/src/main/java/MainActivity.kt")
                    if (toolName == AgentToolRegistry.TOOL_APPLY_FILE_CHANGES) {
                        put("codeContent", "// new code")
                    } else if (toolName == AgentToolRegistry.TOOL_READ_FILE_RANGE) {
                        put("startLine", 1)
                        put("endLine", 50)
                    }
                }

                val result = ToolArgumentCoercer.coerceArgs(toolName, rawArgs, toolDef)
                assertTrue("Alias '$alias' must succeed for tool '$toolName'. Errors: ${result.errors}", result.isSuccess)
                assertEquals(
                    "Alias '$alias' must resolve to canonical filePath for '$toolName'",
                    "app/src/main/java/MainActivity.kt",
                    result.args.getString("filePath")
                )
            }
        }
    }

    @Test
    fun testConflictingFilePathAliasesFailSafely() {
        val rawArgs = JSONObject().apply {
            put("filePath", "app/src/main/java/MainActivity.kt")
            put("path", "app/src/main/java/Utils.kt") // Conflicting path!
            put("content", "class Modified")
        }

        val result = ToolArgumentCoercer.coerceArgs(
            AgentToolRegistry.TOOL_APPLY_FILE_CHANGES,
            rawArgs,
            AgentToolRegistry.applyFileChangesToolDefinition
        )
        assertFalse("Conflicting path aliases must fail before mutation", result.isSuccess)
        assertTrue(result.errors.any { it.contains("Conflicting file path aliases") })
    }

    @Test
    fun testBlankAndInvalidPathFailsSafely() {
        // Blank path
        val blankArgs = JSONObject().apply {
            put("path", "   ")
            put("content", "class Foo")
        }
        val blankResult = ToolArgumentCoercer.coerceArgs(
            AgentToolRegistry.TOOL_APPLY_FILE_CHANGES,
            blankArgs,
            AgentToolRegistry.applyFileChangesToolDefinition
        )
        assertFalse("Blank path must fail validation", blankResult.isSuccess)

        // Path with null byte
        val nullByteArgs = JSONObject().apply {
            put("filePath", "malicious\u0000payload.kt")
            put("content", "class Foo")
        }
        val nullByteResult = ToolArgumentCoercer.coerceArgs(
            AgentToolRegistry.TOOL_APPLY_FILE_CHANGES,
            nullByteArgs,
            AgentToolRegistry.applyFileChangesToolDefinition
        )
        assertFalse("Path with null byte must fail validation", nullByteResult.isSuccess)
        assertTrue(nullByteResult.errors.any { it.contains("null byte") })
    }

    @Test
    fun testSearchReplaceDeletionAllowsEmptyReplaceBlock() {
        val rawDeletion = JSONObject().apply {
            put("path", "app/src/main/java/Utils.kt")
            put("search", "fun deprecatedMethod() {\n    // remove me\n}")
            put("replace", "") // Explicit deletion
        }

        val result = ToolArgumentCoercer.coerceArgs(
            AgentToolRegistry.TOOL_APPLY_FILE_CHANGES,
            rawDeletion,
            AgentToolRegistry.applyFileChangesToolDefinition
        )
        assertTrue("Deletion with empty replace string must be permitted", result.isSuccess)
        assertTrue(result.errors.isEmpty())
        assertEquals("app/src/main/java/Utils.kt", result.args.getString("filePath"))
        assertEquals("", result.args.getString("replace"))
        assertFalse("Search must not be copied to codeContent", result.args.has("codeContent"))
    }

    @Test
    fun testSearchBlockWithoutReplaceFailsSafely() {
        val rawIncomplete = JSONObject().apply {
            put("filePath", "app/src/main/java/Utils.kt")
            put("search", "fun deprecatedMethod() {}")
            // No replace or replaceBlock supplied
        }

        val result = ToolArgumentCoercer.coerceArgs(
            AgentToolRegistry.TOOL_APPLY_FILE_CHANGES,
            rawIncomplete,
            AgentToolRegistry.applyFileChangesToolDefinition
        )
        assertFalse("Search without replace must fail validation", result.isSuccess)
        assertTrue(result.errors.any { it.contains("without a corresponding replacement parameter") })
    }

    @Test
    fun testConflictingSearchReplaceAndWholeFileContentFailsSafely() {
        val rawConflict = JSONObject().apply {
            put("filePath", "app/src/main/java/Utils.kt")
            put("search", "fun old() {}")
            put("replace", "fun new() {}")
            put("content", "package com.example\nclass EntirelyNewFile") // Conflicting mutation modes!
        }

        val result = ToolArgumentCoercer.coerceArgs(
            AgentToolRegistry.TOOL_APPLY_FILE_CHANGES,
            rawConflict,
            AgentToolRegistry.applyFileChangesToolDefinition
        )
        assertFalse("Conflicting mutation arguments must fail", result.isSuccess)
        assertTrue(result.errors.any { it.contains("Conflicting mutation arguments") })
    }

    @Test
    fun testMultilineUnicodeAndEscapedStringsPreservedWithoutCorruption() {
        val testDef = ToolDefinition(
            name = "save_template",
            description = "Saves file template",
            parameters = listOf(
                ToolParameterProperty(name = "filePath", type = "string", description = "Path", required = true),
                ToolParameterProperty(name = "content", type = "string", description = "Content", required = true)
            )
        )

        val complexCode = "fun greet(name: String) = \"Hello, \$name! 🚀\\n\\t\\\"Quoted\\\"\\n\""
        val rawArgs = JSONObject().apply {
            put("filePath", "Greet.kt")
            put("content", complexCode)
        }

        val result = ToolArgumentCoercer.coerceArgs("save_template", rawArgs, testDef)
        assertTrue(result.isSuccess)
        assertEquals(complexCode, result.args.getString("content"))
    }

    @Test
    fun testAgentExecutionModeToolGating() {
        // In AUTONOMOUS mode: all engineering tools are advertised
        val autoTools = AgentToolRegistry.getToolsForExecutionMode(
            mode = AgentExecutionMode.AUTONOMOUS,
            isEditAuthorized = false,
            webSearchReady = true
        )
        assertTrue(autoTools.any { it.name == AgentToolRegistry.TOOL_APPLY_FILE_CHANGES })
        assertTrue(autoTools.any { it.name == AgentToolRegistry.TOOL_RUN_TERMINAL_COMMAND })
        assertTrue(autoTools.any { it.name == AgentToolRegistry.TOOL_READ_PROJECT_FILE })
        assertTrue(autoTools.any { it.name == AgentToolRegistry.TOOL_WEB_SEARCH })

        // In SIDE_AGENT mode WITHOUT edit authorization: mutating tools MUST BE WITHHELD!
        val sideAdvisoryTools = AgentToolRegistry.getToolsForExecutionMode(
            mode = AgentExecutionMode.SIDE_AGENT,
            isEditAuthorized = false,
            webSearchReady = true
        )
        assertFalse("apply_file_changes must not be advertised in advisory mode",
            sideAdvisoryTools.any { it.name == AgentToolRegistry.TOOL_APPLY_FILE_CHANGES })
        assertFalse("apply_project_patch must not be advertised in advisory mode",
            sideAdvisoryTools.any { it.name == AgentToolRegistry.TOOL_APPLY_PROJECT_PATCH })
        assertFalse("rollback_agent_batch must not be advertised in advisory mode",
            sideAdvisoryTools.any { it.name == AgentToolRegistry.TOOL_ROLLBACK_AGENT_BATCH })
        assertFalse("run_terminal_command must not be advertised in advisory mode",
            sideAdvisoryTools.any { it.name == AgentToolRegistry.TOOL_RUN_TERMINAL_COMMAND })

        // Read-only tools must remain available
        assertTrue("read_project_file must be available in advisory mode",
            sideAdvisoryTools.any { it.name == AgentToolRegistry.TOOL_READ_PROJECT_FILE })
        assertTrue("inspect_project must be available in advisory mode",
            sideAdvisoryTools.any { it.name == AgentToolRegistry.TOOL_INSPECT_PROJECT })
        assertTrue("get_file_outline must be available in advisory mode",
            sideAdvisoryTools.any { it.name == AgentToolRegistry.TOOL_GET_FILE_OUTLINE })
        assertTrue("build_current_project must be available for non-destructive check",
            sideAdvisoryTools.any { it.name == AgentToolRegistry.TOOL_BUILD_CURRENT_PROJECT })

        // In SIDE_AGENT mode WITH explicit edit authorization: mutating tools ARE advertised
        val sideAuthorizedTools = AgentToolRegistry.getToolsForExecutionMode(
            mode = AgentExecutionMode.SIDE_AGENT,
            isEditAuthorized = true,
            webSearchReady = true
        )
        assertTrue(sideAuthorizedTools.any { it.name == AgentToolRegistry.TOOL_APPLY_FILE_CHANGES })
        assertTrue(sideAuthorizedTools.any { it.name == AgentToolRegistry.TOOL_RUN_TERMINAL_COMMAND })
    }
}
