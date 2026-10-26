package com.tom.rv2ide.artificial.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpClientManager private constructor(context: Context) {

    private val appContext = context.applicationContext ?: context
    private val mcpDir = File(appContext.filesDir, "mcp")
    private val mcpConfigFile = File(mcpDir, "servers.json")

    private val servers = mutableListOf<McpServerConfig>()
    private val discoveredTools = mutableListOf<McpDiscoveredTool>()
    private val nextRequestId = AtomicInteger(1)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        loadFromDisk()
    }

    @Synchronized
    fun getServers(): List<McpServerConfig> = servers.toList()

    @Synchronized
    fun saveServer(config: McpServerConfig) {
        val idx = servers.indexOfFirst { it.id == config.id }
        if (idx >= 0) {
            servers[idx] = config
        } else {
            servers.add(config)
        }
        saveToDisk()
    }

    @Synchronized
    fun deleteServer(id: String): Boolean {
        val removed = servers.removeAll { it.id == id }
        if (removed) saveToDisk()
        return removed
    }

    @Synchronized
    fun getDiscoveredTools(): List<McpDiscoveredTool> = discoveredTools.toList()

    /**
     * Connects to all enabled servers and refreshes the tool list via JSON-RPC 2.0 `tools/list`.
     */
    suspend fun refreshAllTools(): List<McpDiscoveredTool> = withContext(Dispatchers.IO) {
        val tools = mutableListOf<McpDiscoveredTool>()
        val activeServers = synchronized(this@McpClientManager) { servers.filter { it.enabled } }

        for (server in activeServers) {
            try {
                val serverTools = listToolsForServer(server)
                tools.addAll(serverTools)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to list tools for MCP server '${server.name}'", e)
            }
        }

        synchronized(this@McpClientManager) {
            discoveredTools.clear()
            discoveredTools.addAll(tools)
        }
        tools
    }

    private suspend fun listToolsForServer(server: McpServerConfig): List<McpDiscoveredTool> {
        val reqObj = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", nextRequestId.getAndIncrement())
            put("method", "tools/list")
            put("params", JSONObject())
        }

        val respObj = sendJsonRpc(server, reqObj)
        val resultObj = respObj.optJSONObject("result") ?: return emptyList()
        val toolsArr = resultObj.optJSONArray("tools") ?: return emptyList()

        val list = mutableListOf<McpDiscoveredTool>()
        for (i in 0 until toolsArr.length()) {
            val tObj = toolsArr.optJSONObject(i) ?: continue
            val name = tObj.optString("name", "")
            if (name.isBlank()) continue
            val desc = tObj.optString("description", "")
            val schema = tObj.optJSONObject("inputSchema")?.toString() ?: "{}"

            list.add(
                McpDiscoveredTool(
                    name = name,
                    description = desc,
                    inputSchemaJson = schema,
                    serverId = server.id,
                    serverName = server.name
                )
            )
        }
        return list
    }

    /**
     * Calls a tool on an MCP server via JSON-RPC 2.0 `tools/call`.
     */
    suspend fun callTool(
        server: McpServerConfig,
        toolName: String,
        arguments: JSONObject
    ): String = withContext(Dispatchers.IO) {
        val reqObj = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", nextRequestId.getAndIncrement())
            put("method", "tools/call")
            put(
                "params",
                JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments)
                }
            )
        }

        val respObj = sendJsonRpc(server, reqObj)
        val error = respObj.optJSONObject("error")
        if (error != null) {
            val code = error.optInt("code")
            val msg = error.optString("message", "MCP Tool Error")
            return@withContext "Error ($code): $msg"
        }

        val result = respObj.optJSONObject("result") ?: return@withContext respObj.toString()
        val contentArr = result.optJSONArray("content")
        if (contentArr != null && contentArr.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until contentArr.length()) {
                val item = contentArr.optJSONObject(i) ?: continue
                val text = item.optString("text", "")
                if (text.isNotEmpty()) sb.append(text).append('\n')
            }
            return@withContext sb.toString().trim()
        }

        return@withContext result.toString()
    }

    private fun sendJsonRpc(server: McpServerConfig, payload: JSONObject): JSONObject {
        return when (server.transportType) {
            McpTransportType.SSE_HTTP -> sendViaHttp(server.endpointUrl, payload)
            McpTransportType.STDIO -> sendViaStdio(server, payload)
        }
    }

    private fun sendViaHttp(url: String, payload: JSONObject): JSONObject {
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val str = resp.body?.string().orEmpty()
            return JSONObject(str)
        }
    }

    private fun sendViaStdio(server: McpServerConfig, payload: JSONObject): JSONObject {
        val cmdList = mutableListOf<String>()
        cmdList.add(server.command)
        cmdList.addAll(server.args)

        val pb = ProcessBuilder(cmdList)
        pb.environment().putAll(server.env)
        val proc = pb.start()

        val writer = OutputStreamWriter(proc.outputStream)
        writer.write(payload.toString() + "\n")
        writer.flush()

        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val line = reader.readLine() ?: "{}"
        proc.destroy()

        return JSONObject(line)
    }

    private fun loadFromDisk() {
        try {
            if (!mcpDir.exists()) mcpDir.mkdirs()
            if (mcpConfigFile.exists()) {
                val str = mcpConfigFile.readText()
                val arr = JSONArray(str)
                servers.clear()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        servers.add(McpServerConfig.fromJson(obj))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MCP servers from disk", e)
        }
    }

    private fun saveToDisk() {
        try {
            if (!mcpDir.exists()) mcpDir.mkdirs()
            val arr = JSONArray()
            servers.forEach { arr.put(it.toJson()) }
            mcpConfigFile.writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save MCP servers to disk", e)
        }
    }

    companion object {
        private const val TAG = "McpClientManager"

        @Volatile
        private var instance: McpClientManager? = null

        fun getInstance(context: Context): McpClientManager {
            return instance ?: synchronized(this) {
                instance ?: McpClientManager(context).also { instance = it }
            }
        }
    }
}
