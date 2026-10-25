package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.transport.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reusable schema-aware argument normalization and coercion engine.
 *
 * Models often return structured JSON objects or arrays for parameters whose
 * schemas declare `type = "string"` (e.g. creating/modifying JSON configs, Gradle files,
 * or multiline templates). Standard JSON parsers parse nested structures into JSONObjects,
 * causing `optString` to return empty strings and leading to 0-byte file writes or failed validations.
 *
 * This coercer inspects the actual tool parameter schema and:
 * 1. Safely re-stringifies JSON objects/arrays when a string is expected.
 * 2. Resolves and validates complete file-path parameter aliases (filePath, path, filepath,
 *    file_path, filename, targetFile, relPath) with conflict and blank-value guards.
 * 3. Preserves semantically distinct parameters (search, replace, content, patch) without
 *    globally reinterpreting them or corrupting search-and-replace code deletions.
 * 4. Coerces numeric/boolean strings to their target types when the schema requires integer/boolean.
 * 5. Validates required fields and reports clear errors rather than silently corrupting data.
 */
object ToolArgumentCoercer {

    data class CoercionResult(
        val args: JSONObject,
        val errors: List<String> = emptyList()
    ) {
        val isSuccess: Boolean get() = errors.isEmpty()
    }

    val FILE_PATH_ALIASES = listOf(
        "filePath", "path", "filepath", "file_path", "filename", "targetFile", "relPath"
    )

    val WHOLE_FILE_CONTENT_ALIASES = listOf(
        "codeContent", "content", "code", "newContent", "changes"
    )

    val PATCH_ALIASES = listOf("patch", "diff")

    val SEARCH_BLOCK_ALIASES = listOf("search", "searchBlock")

    val REPLACE_BLOCK_ALIASES = listOf("replace", "replaceBlock")

    /**
     * Coerces arguments based on the target tool's schema.
     */
    fun coerceArgs(
        toolName: String,
        rawArgs: JSONObject,
        definition: ToolDefinition? = null
    ): CoercionResult {
        val canonicalName = AgentToolRegistry.canonicalToolName(toolName)
        val effectiveDef = definition ?: AgentToolRegistry.findToolDefinition(canonicalName)

        val coerced = JSONObject()
        val errors = mutableListOf<String>()

        // Copy all keys from rawArgs to coerced
        val keys = rawArgs.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            coerced.put(key, rawArgs.opt(key))
        }

        if (effectiveDef != null) {
            for (param in effectiveDef.parameters) {
                val paramName = param.name
                val hasParam = coerced.has(paramName)

                if (hasParam) {
                    val value = coerced.opt(paramName)
                    when (param.type.lowercase()) {
                        "string" -> {
                            if (value is JSONObject || value is JSONArray) {
                                coerced.put(paramName, value.toString())
                            } else if (value != null && value != JSONObject.NULL && value !is String) {
                                coerced.put(paramName, value.toString())
                            }
                        }
                        "integer" -> {
                            when (value) {
                                is String -> {
                                    val parsed = value.trim().toIntOrNull()
                                    if (parsed != null) {
                                        coerced.put(paramName, parsed)
                                    } else {
                                        errors.add("Parameter '$paramName' expected integer but received '$value'")
                                    }
                                }
                                is Number -> coerced.put(paramName, value.toInt())
                                is Boolean -> errors.add("Parameter '$paramName' expected integer but received boolean")
                            }
                        }
                        "boolean" -> {
                            when (value) {
                                is String -> {
                                    when (value.trim().lowercase()) {
                                        "true" -> coerced.put(paramName, true)
                                        "false" -> coerced.put(paramName, false)
                                        else -> errors.add("Parameter '$paramName' expected boolean but received '$value'")
                                    }
                                }
                                is Boolean -> { /* already boolean */ }
                                is Number -> coerced.put(paramName, value.toInt() != 0)
                            }
                        }
                    }
                }
            }
        }

        // Handle schema-aware file path alias resolution with strict conflict and blank-value guards
        val expectsFilePath = (effectiveDef?.parameters?.any { it.name == "filePath" } == true) ||
            canonicalName == AgentToolRegistry.TOOL_APPLY_FILE_CHANGES ||
            canonicalName == AgentToolRegistry.TOOL_APPLY_PROJECT_PATCH

        if (expectsFilePath) {
            val suppliedPaths = mutableMapOf<String, String>()
            var hadBlankPathAlias = false

            for (alias in FILE_PATH_ALIASES) {
                if (coerced.has(alias)) {
                    val rawVal = coerced.opt(alias)
                    if (rawVal is String) {
                        val trimmed = rawVal.trim()
                        if (trimmed.isNotEmpty()) {
                            suppliedPaths[alias] = trimmed
                        } else {
                            hadBlankPathAlias = true
                        }
                    }
                }
            }

            val distinctPaths = suppliedPaths.values.distinct()
            if (distinctPaths.size > 1) {
                val conflictSummary = suppliedPaths.entries.joinToString(", ") { "${it.key}='${it.value}'" }
                errors.add("Conflicting file path aliases provided for tool '$canonicalName': $conflictSummary")
            } else if (distinctPaths.size == 1) {
                val resolvedPath = distinctPaths.first()
                if (resolvedPath.contains('\u0000')) {
                    errors.add("Invalid file path: path contains null byte characters")
                } else {
                    coerced.put("filePath", resolvedPath)
                    for (alias in FILE_PATH_ALIASES) {
                        if (alias != "filePath") {
                            coerced.remove(alias)
                        }
                    }
                }
            } else if (hadBlankPathAlias) {
                errors.add("File path parameter is blank for tool '$canonicalName'")
            }
        }

        // Handle file mutation parameter normalization without globally reinterpreting search/replace
        val isFileMutationTool = canonicalName == AgentToolRegistry.TOOL_APPLY_FILE_CHANGES ||
            effectiveDef?.parameters?.any { it.name == "codeContent" } == true

        if (isFileMutationTool) {
            // Re-stringify structured objects in content/search/replace parameters if needed
            for (alias in (WHOLE_FILE_CONTENT_ALIASES + PATCH_ALIASES + SEARCH_BLOCK_ALIASES + REPLACE_BLOCK_ALIASES)) {
                if (coerced.has(alias)) {
                    val v = coerced.opt(alias)
                    if (v is JSONObject || v is JSONArray) {
                        coerced.put(alias, v.toString())
                    }
                }
            }

            val searchVal = SEARCH_BLOCK_ALIASES.firstNotNullOfOrNull { alias ->
                coerced.optString(alias, "").takeIf { it.isNotBlank() }
            }
            val hasReplaceKey = REPLACE_BLOCK_ALIASES.any { coerced.has(it) }
            val replaceVal = REPLACE_BLOCK_ALIASES.firstNotNullOfOrNull { alias ->
                if (coerced.has(alias)) coerced.optString(alias, "") else null
            }

            val hasWholeContent = WHOLE_FILE_CONTENT_ALIASES.any { alias ->
                coerced.has(alias) && !isJsonNullOrBlank(coerced.opt(alias))
            }

            // Conflict detection: do not allow both whole-file content and search/replace simultaneously
            if (searchVal != null && hasWholeContent) {
                errors.add("Conflicting mutation arguments: both search-and-replace and whole-file content were supplied for tool '$canonicalName'")
            }

            if (searchVal != null) {
                if (!hasReplaceKey) {
                    errors.add("Search block was provided for tool '$canonicalName' without a corresponding replacement parameter ('replace')")
                } else {
                    coerced.put("search", searchVal)
                    coerced.put("replace", replaceVal ?: "")
                }
            } else {
                // If not search-and-replace, normalize whole-file content into canonical codeContent
                if (!coerced.has("codeContent") || isJsonNullOrBlank(coerced.opt("codeContent"))) {
                    for (alias in WHOLE_FILE_CONTENT_ALIASES) {
                        if (alias != "codeContent" && coerced.has(alias)) {
                            val v = coerced.opt(alias)
                            if (v is String && v.isNotBlank()) {
                                coerced.put("codeContent", v)
                                break
                            }
                        }
                    }
                }
                if (coerced.has("codeContent")) {
                    for (alias in WHOLE_FILE_CONTENT_ALIASES) {
                        if (alias != "codeContent") {
                            coerced.remove(alias)
                        }
                    }
                }

                if (!coerced.has("codeContent") && !coerced.has("patch")) {
                    for (pAlias in PATCH_ALIASES) {
                        if (coerced.has(pAlias)) {
                            val pv = coerced.optString(pAlias, "")
                            if (pv.isNotBlank()) {
                                coerced.put("patch", pv)
                                break
                            }
                        }
                    }
                }
            }
        }

        // Validate required fields for known tools
        if (effectiveDef != null) {
            for (param in effectiveDef.parameters) {
                if (param.required) {
                    val isProvided = if (isFileMutationTool && param.name == "codeContent") {
                        hasAnyContent(coerced) || coerced.optBoolean("allowEmpty", false) || coerced.optBoolean("createEmpty", false)
                    } else if (param.name == "filePath") {
                        hasAnyPath(coerced)
                    } else {
                        coerced.has(param.name) && !isJsonNullOrBlank(coerced.opt(param.name))
                    }

                    if (!isProvided) {
                        errors.add("Required parameter '${param.name}' is missing or empty for tool '$canonicalName'")
                    }
                }
            }
        }

        return CoercionResult(args = coerced, errors = errors)
    }

    private fun hasAnyContent(obj: JSONObject): Boolean {
        // Search and replace: search must be non-blank and replace key must exist (can be empty string for deletion!)
        val hasSearch = SEARCH_BLOCK_ALIASES.any { obj.has(it) && obj.optString(it, "").isNotBlank() }
        val hasReplaceKey = REPLACE_BLOCK_ALIASES.any { obj.has(it) }
        if (hasSearch && hasReplaceKey) return true

        val hasWholeContent = WHOLE_FILE_CONTENT_ALIASES.any {
            obj.has(it) && !isJsonNullOrBlank(obj.opt(it))
        }
        if (hasWholeContent) return true

        val hasPatch = PATCH_ALIASES.any {
            obj.has(it) && !isJsonNullOrBlank(obj.opt(it))
        }
        if (hasPatch) return true

        return false
    }

    private fun hasAnyPath(obj: JSONObject): Boolean {
        return obj.optString("filePath", "").isNotBlank()
    }

    private fun isJsonNullOrBlank(value: Any?): Boolean {
        if (value == null || value == JSONObject.NULL) return true
        if (value is String) return value.isBlank()
        return false
    }
}
