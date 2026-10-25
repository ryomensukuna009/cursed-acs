package com.tom.rv2ide.artificial.transport

import com.tom.rv2ide.artificial.providers.ProviderTemplates
import com.tom.rv2ide.artificial.tools.AgentToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiNativeAdapterMultiToolTest {

    @Test
    fun testConsecutiveToolMessagesGroupedIntoSingleUserTurnWithIdMatching() {
        val adapter = GeminiNativeAdapter()
        val request = UnifiedChatRequest(
            profile = ProviderTemplates.geminiNativePreset(),
            modelId = "gemini-2.5-pro",
            messages = listOf(
                UnifiedChatMessage(role = ChatRole.USER, content = "Inspect and build project"),
                UnifiedChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "Inspecting project and checking build status",
                    toolCalls = listOf(
                        ToolCall(id = "gemini_call_abc1", name = "inspect_project", argumentsJson = "{}"),
                        ToolCall(id = "gemini_call_abc2", name = "get_current_build_mode", argumentsJson = "{}")
                    )
                ),
                // Out-of-order tool responses: call 2 arrives before call 1
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    toolResult = ToolResultMessage(
                        toolCallId = "gemini_call_abc2",
                        toolName = "get_current_build_mode",
                        content = "Mode: OFFLINE_NDK",
                        isError = false
                    )
                ),
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    toolResult = ToolResultMessage(
                        toolCallId = "gemini_call_abc1",
                        toolName = "inspect_project",
                        content = "Project root: /sdcard/MyApp, Type: Android NDK",
                        isError = false
                    )
                )
            ),
            tools = listOf(
                AgentToolRegistry.inspectProjectToolDefinition,
                AgentToolRegistry.getCurrentBuildModeToolDefinition
            ),
            stream = false
        )

        val built = adapter.buildHttpRequest(request, "AIzaTestKey")
        val body = JSONObject(built.bodyJson)
        val contents = body.getJSONArray("contents")

        // Turn 0: User ("Inspect and build project")
        // Turn 1: Model (assistant text + 2 functionCall parts with IDs)
        // Turn 2: User (SINGLE grouped turn containing ordered functionResponse parts with IDs!)
        assertEquals(3, contents.length())

        val turn1 = contents.getJSONObject(1)
        assertEquals("model", turn1.getString("role"))
        val turn1Parts = turn1.getJSONArray("parts")
        assertEquals(3, turn1Parts.length()) // text delta + 2 function calls
        val fc1 = turn1Parts.getJSONObject(1).getJSONObject("functionCall")
        assertEquals("inspect_project", fc1.getString("name"))
        assertEquals("gemini_call_abc1", fc1.getString("id"))
        val fc2 = turn1Parts.getJSONObject(2).getJSONObject("functionCall")
        assertEquals("get_current_build_mode", fc2.getString("name"))
        assertEquals("gemini_call_abc2", fc2.getString("id"))

        val turn2 = contents.getJSONObject(2)
        assertEquals("user", turn2.getString("role"))
        val turn2Parts = turn2.getJSONArray("parts")
        // Both responses MUST be in this single user turn, in the EXACT order of the model's function calls!
        assertEquals(2, turn2Parts.length())

        val fnResp1 = turn2Parts.getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("inspect_project", fnResp1.getString("name"))
        assertEquals("gemini_call_abc1", fnResp1.getString("id"))
        assertEquals("Project root: /sdcard/MyApp, Type: Android NDK", fnResp1.getJSONObject("response").getString("result"))

        val fnResp2 = turn2Parts.getJSONObject(1).getJSONObject("functionResponse")
        assertEquals("get_current_build_mode", fnResp2.getString("name"))
        assertEquals("gemini_call_abc2", fnResp2.getString("id"))
        assertEquals("Mode: OFFLINE_NDK", fnResp2.getJSONObject("response").getString("result"))
    }

    @Test
    fun testParallelCallsToSameFunctionWithDifferentArguments() {
        val adapter = GeminiNativeAdapter()
        val request = UnifiedChatRequest(
            profile = ProviderTemplates.geminiNativePreset(),
            modelId = "gemini-2.5-pro",
            messages = listOf(
                UnifiedChatMessage(role = ChatRole.USER, content = "Read both files"),
                UnifiedChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "Reading file A and file B in parallel",
                    toolCalls = listOf(
                        ToolCall(id = "call_file_a", name = "read_project_file", argumentsJson = """{"filePath":"A.kt"}"""),
                        ToolCall(id = "call_file_b", name = "read_project_file", argumentsJson = """{"filePath":"B.kt"}""")
                    )
                ),
                // Asynchronous arrival: B completes before A
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    toolResult = ToolResultMessage(
                        toolCallId = "call_file_b",
                        toolName = "read_project_file",
                        content = "class B"
                    )
                ),
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    toolResult = ToolResultMessage(
                        toolCallId = "call_file_a",
                        toolName = "read_project_file",
                        content = "class A"
                    )
                )
            ),
            stream = false
        )

        val built = adapter.buildHttpRequest(request, "AIzaTestKey")
        val body = JSONObject(built.bodyJson)
        val contents = body.getJSONArray("contents")
        assertEquals(3, contents.length())

        val userResponseParts = contents.getJSONObject(2).getJSONArray("parts")
        assertEquals(2, userResponseParts.length())

        // Index 0 must match call_file_a
        val resp0 = userResponseParts.getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("read_project_file", resp0.getString("name"))
        assertEquals("call_file_a", resp0.getString("id"))
        assertEquals("class A", resp0.getJSONObject("response").getString("result"))

        // Index 1 must match call_file_b
        val resp1 = userResponseParts.getJSONObject(1).getJSONObject("functionResponse")
        assertEquals("read_project_file", resp1.getString("name"))
        assertEquals("call_file_b", resp1.getString("id"))
        assertEquals("class B", resp1.getJSONObject("response").getString("result"))
    }

    @Test
    fun testMissingToolCallResponseIsSynthesizedGracefully() {
        val adapter = GeminiNativeAdapter()
        val request = UnifiedChatRequest(
            profile = ProviderTemplates.geminiNativePreset(),
            modelId = "gemini-2.5-pro",
            messages = listOf(
                UnifiedChatMessage(role = ChatRole.USER, content = "Run two tools"),
                UnifiedChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "Calling tool1 and tool2",
                    toolCalls = listOf(
                        ToolCall(id = "c1", name = "read_file", argumentsJson = """{"filePath":"A.kt"}"""),
                        ToolCall(id = "c2", name = "read_file", argumentsJson = """{"filePath":"B.kt"}""")
                    )
                ),
                // Only result for c1 is present (c2 was cancelled or missing)
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    toolResult = ToolResultMessage(
                        toolCallId = "c1",
                        toolName = "read_file",
                        content = "class A"
                    )
                )
            ),
            stream = false
        )

        val built = adapter.buildHttpRequest(request, "AIzaTestKey")
        val body = JSONObject(built.bodyJson)
        val contents = body.getJSONArray("contents")

        val turn2 = contents.getJSONObject(2)
        val turn2Parts = turn2.getJSONArray("parts")
        // Must contain 2 responses so Gemini turn pairing rule is satisfied
        assertEquals(2, turn2Parts.length())

        val resp1 = turn2Parts.getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("read_file", resp1.getString("name"))
        assertEquals("c1", resp1.getString("id"))
        assertEquals("class A", resp1.getJSONObject("response").getString("result"))

        val resp2 = turn2Parts.getJSONObject(1).getJSONObject("functionResponse")
        assertEquals("read_file", resp2.getString("name"))
        assertEquals("c2", resp2.getString("id"))
        assertTrue("Synthesized error response must indicate error", resp2.getJSONObject("response").getBoolean("error"))
    }

    @Test
    fun testOrphanedToolMessageIsOmittedWithoutGainingUserRoleSemantics() {
        val adapter = GeminiNativeAdapter()
        val request = UnifiedChatRequest(
            profile = ProviderTemplates.geminiNativePreset(),
            modelId = "gemini-2.5-pro",
            messages = listOf(
                UnifiedChatMessage(role = ChatRole.USER, content = "User prompt"),
                // Orphaned tool message without a preceding model functionCall turn
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    toolResult = ToolResultMessage(
                        toolCallId = "orphan_1",
                        toolName = "inspect_project",
                        content = "Secret Project Data"
                    )
                )
            ),
            stream = false
        )

        val built = adapter.buildHttpRequest(request, "AIzaTestKey")
        val body = JSONObject(built.bodyJson)
        val contents = body.getJSONArray("contents")

        // Turn 0: User ("User prompt")
        // The orphaned tool message MUST be omitted to prevent gaining user-message semantics!
        assertEquals(1, contents.length())
        val turn0 = contents.getJSONObject(0)
        assertEquals("user", turn0.getString("role"))
        assertEquals("User prompt", turn0.getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun testUnpopulatedFunctionCallIdPreservedAsBlank() {
        val adapter = GeminiNativeAdapter()
        val rawResponse = """
            {
                "candidates": [
                    {
                        "content": {
                            "parts": [
                                {
                                    "functionCall": {
                                        "name": "inspect_project",
                                        "args": {}
                                    }
                                }
                            ]
                        }
                    }
                ]
            }
        """.trimIndent()

        val parsed = adapter.parseNonStreamingResponse(rawResponse, "gemini-1.5-flash")
        assertEquals(1, parsed.toolCalls.size)
        val tc = parsed.toolCalls[0]
        assertEquals("inspect_project", tc.name)
        // ID must remain blank since model did not emit an id
        assertEquals("", tc.id)

        // Building next request must NOT serialize synthetic id
        val nextRequest = UnifiedChatRequest(
            profile = ProviderTemplates.geminiNativePreset(),
            modelId = "gemini-1.5-flash",
            messages = listOf(
                UnifiedChatMessage(role = ChatRole.USER, content = "Go"),
                UnifiedChatMessage(role = ChatRole.ASSISTANT, content = "", toolCalls = listOf(tc)),
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    toolResult = ToolResultMessage(toolCallId = "", toolName = "inspect_project", content = "Done")
                )
            ),
            stream = false
        )

        val built = adapter.buildHttpRequest(nextRequest, "key")
        val body = JSONObject(built.bodyJson)
        val contents = body.getJSONArray("contents")
        val modelTurn = contents.getJSONObject(1)
        val fc = modelTurn.getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall")
        assertFalse("Model functionCall without ID must not have 'id' field serialized", fc.has("id"))

        val userTurn = contents.getJSONObject(2)
        val fr = userTurn.getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertFalse("FunctionResponse without ID must not have 'id' field serialized", fr.has("id"))
    }

    @Test
    fun testRecursiveGeminiSchemaPreservesSemantics() {
        val rawSchema = JSONObject().apply {
            put("\$schema", "http://json-schema.org/draft-07/schema#")
            put("type", "object")
            put("title", "ProjectConfiguration")
            put("additionalProperties", false)
            put("description", "A complex schema with nested objects and arrays")
            put("properties", JSONObject().apply {
                put("version", JSONObject().apply {
                    put("type", "integer")
                    put("default", 1)
                    put("minimum", 1.0)
                    put("maximum", 10.0)
                    put("description", "Config version")
                })
                put("tags", JSONObject().apply {
                    put("type", "array")
                    put("description", "List of tags")
                    put("items", JSONObject().apply {
                        put("type", "string")
                    })
                })
                put("options", JSONObject().apply {
                    put("type", "object")
                    put("additionalProperties", false)
                    put("properties", JSONObject().apply {
                        put("debugMode", JSONObject().apply {
                            put("type", "boolean")
                            put("default", false)
                            put("description", "Enable debug mode")
                        })
                    })
                    put("required", JSONArray()) // Empty required array inside nested object
                })
            })
            put("required", JSONArray().put("version"))
        }

        val sanitized = ToolDefinition.sanitizeSchemaForGemini(rawSchema)

        assertEquals("OBJECT", sanitized.getString("type"))
        assertFalse(sanitized.has("\$schema"))
        assertTrue("title must be preserved", sanitized.has("title"))
        assertEquals("ProjectConfiguration", sanitized.getString("title"))
        assertTrue("additionalProperties must be preserved", sanitized.has("additionalProperties"))
        assertEquals(false, sanitized.getBoolean("additionalProperties"))
        assertEquals("A complex schema with nested objects and arrays", sanitized.getString("description"))

        val props = sanitized.getJSONObject("properties")
        val versionProp = props.getJSONObject("version")
        assertEquals("INTEGER", versionProp.getString("type"))
        assertTrue("default value must be preserved", versionProp.has("default"))
        assertEquals(1, versionProp.getInt("default"))
        assertEquals(1.0, versionProp.getDouble("minimum"), 0.001)
        assertEquals(10.0, versionProp.getDouble("maximum"), 0.001)

        val tagsProp = props.getJSONObject("tags")
        assertEquals("ARRAY", tagsProp.getString("type"))
        assertTrue(tagsProp.has("items"))
        assertEquals("STRING", tagsProp.getJSONObject("items").getString("type"))

        val optionsProp = props.getJSONObject("options")
        assertEquals("OBJECT", optionsProp.getString("type"))
        assertTrue("nested additionalProperties preserved", optionsProp.has("additionalProperties"))
        assertFalse(optionsProp.getBoolean("additionalProperties"))
        val nestedOptsProps = optionsProp.getJSONObject("properties")
        assertEquals("BOOLEAN", nestedOptsProps.getJSONObject("debugMode").getString("type"))
        assertEquals(false, nestedOptsProps.getJSONObject("debugMode").getBoolean("default"))
        // Empty required in nested object MUST be omitted!
        assertFalse(optionsProp.has("required"))

        // Root required must be preserved
        assertTrue(sanitized.has("required"))
        assertEquals("version", sanitized.getJSONArray("required").getString(0))
    }

    @Test
    fun testArrayWithoutItemsNotForcedToString() {
        val rawArraySchema = JSONObject().apply {
            put("type", "array")
            put("description", "Unconstrained array")
        }

        val sanitized = ToolDefinition.sanitizeSchemaForGemini(rawArraySchema)
        assertEquals("ARRAY", sanitized.getString("type"))
        assertEquals("Unconstrained array", sanitized.getString("description"))
        // Items must NOT be silently forced to STRING when not specified
        assertFalse("Unconstrained array items must not be forced to STRING", sanitized.has("items"))
    }

    @Test
    fun testGeminiSchemaSerializationUsesUppercaseAndNoEmptyRequired() {
        val toolWithRequired = ToolDefinition(
            name = "test_tool_req",
            description = "Tool with required and optional params",
            parameters = listOf(
                ToolParameterProperty(name = "path", type = "string", description = "Target path", required = true),
                ToolParameterProperty(name = "lines", type = "integer", description = "Max lines", required = false)
            )
        )

        val toolWithoutRequired = ToolDefinition(
            name = "test_tool_no_req",
            description = "Tool with zero required params",
            parameters = listOf(
                ToolParameterProperty(name = "filter", type = "string", description = "Optional filter", required = false)
            )
        )

        val geminiSchema1 = toolWithRequired.toGeminiSchemaObject()
        assertEquals("OBJECT", geminiSchema1.getString("type"))
        assertTrue(geminiSchema1.has("required"))
        assertEquals(1, geminiSchema1.getJSONArray("required").length())
        assertEquals("path", geminiSchema1.getJSONArray("required").getString(0))
        val props1 = geminiSchema1.getJSONObject("properties")
        assertEquals("STRING", props1.getJSONObject("path").getString("type"))
        assertEquals("INTEGER", props1.getJSONObject("lines").getString("type"))
        assertFalse(geminiSchema1.has("\$schema"))

        val geminiSchema2 = toolWithoutRequired.toGeminiSchemaObject()
        assertEquals("OBJECT", geminiSchema2.getString("type"))
        // Strict Gemini API rule: empty required array causes HTTP 400; must be omitted
        assertFalse(geminiSchema2.has("required"))
        assertEquals("STRING", geminiSchema2.getJSONObject("properties").getJSONObject("filter").getString("type"))
    }

    @Test
    fun testOpenAiCompatibleAdapterPreservesSeparateToolMessages() {
        val adapter = OpenAICompatibleAdapter()
        val request = UnifiedChatRequest(
            profile = ProviderTemplates.openAiPreset(),
            modelId = "gpt-4o",
            messages = listOf(
                UnifiedChatMessage(role = ChatRole.USER, content = "Inspect project"),
                UnifiedChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        ToolCall(id = "call_abc", name = "inspect_project", argumentsJson = "{}"),
                        ToolCall(id = "call_xyz", name = "list_files", argumentsJson = "{}")
                    )
                ),
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    toolResult = ToolResultMessage(
                        toolCallId = "call_abc",
                        toolName = "inspect_project",
                        content = "NDK Project"
                    )
                ),
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    toolResult = ToolResultMessage(
                        toolCallId = "call_xyz",
                        toolName = "list_files",
                        content = "app, build.gradle"
                    )
                )
            ),
            stream = false
        )

        val built = adapter.buildHttpRequest(request, "sk-test")
        val body = JSONObject(built.bodyJson)
        val msgs = body.getJSONArray("messages")

        // OpenAI format expects separate tool messages with role = "tool" and tool_call_id
        assertEquals(4, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
        assertEquals("assistant", msgs.getJSONObject(1).getString("role"))
        assertEquals("tool", msgs.getJSONObject(2).getString("role"))
        assertEquals("call_abc", msgs.getJSONObject(2).getString("tool_call_id"))
        assertEquals("tool", msgs.getJSONObject(3).getString("role"))
        assertEquals("call_xyz", msgs.getJSONObject(3).getString("tool_call_id"))
    }
}
