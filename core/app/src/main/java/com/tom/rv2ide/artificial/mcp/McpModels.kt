package com.tom.rv2ide.artificial.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class McpTransportType {
    STDIO,
    SSE_HTTP
}

data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val transportType: McpTransportType = McpTransportType.STDIO,
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val endpointUrl: String = "",
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("transportType", transportType.name)
        put("command", command)
        put("args", JSONArray(args))
        val envObj = JSONObject()
        env.forEach { (k, v) -> envObj.put(k, v) }
        put("env", envObj)
        put("endpointUrl", endpointUrl)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(obj: JSONObject): McpServerConfig {
            val argsArr = obj.optJSONArray("args") ?: JSONArray()
            val argsList = (0 until argsArr.length()).mapNotNull { argsArr.optString(it).takeIf { s -> s.isNotEmpty() } }

            val envMap = mutableMapOf<String, String>()
            val envObj = obj.optJSONObject("env")
            if (envObj != null) {
                val keys = envObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    envMap[k] = envObj.optString(k, "")
                }
            }

            val tType = try {
                McpTransportType.valueOf(obj.optString("transportType", McpTransportType.STDIO.name))
            } catch (_: Exception) {
                McpTransportType.STDIO
            }

            return McpServerConfig(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", "New MCP Server"),
                transportType = tType,
                command = obj.optString("command", ""),
                args = argsList,
                env = envMap,
                endpointUrl = obj.optString("endpointUrl", ""),
                enabled = obj.optBoolean("enabled", true)
            )
        }
    }
}

data class McpDiscoveredTool(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val serverId: String,
    val serverName: String
)
