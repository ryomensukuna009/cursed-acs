package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.transport.ToolDefinition
import com.tom.rv2ide.artificial.transport.ToolParameterProperty

object AgentToolRegistry {

    const val TOOL_WEB_SEARCH = "web_search"
    const val TOOL_OPEN_URL = "open_url"
    const val TOOL_INSPECT_PROJECT = "inspect_project"
    const val TOOL_PROJECT_INFO = "project_info"
    const val TOOL_LIST_PROJECT_FILES = "list_project_files"
    const val TOOL_LIST_FILES = "list_files"
    const val TOOL_SEARCH_PROJECT = "search_project"
    const val TOOL_FIND_IN_PROJECT = "find_in_project"
    const val TOOL_READ_PROJECT_FILE = "read_project_file"
    const val TOOL_READ_FILE = "read_file"
    const val TOOL_READ_FILE_RANGE = "read_file_range"
    const val TOOL_INSPECT_SYMBOL_USAGES = "inspect_symbol_usages"
    const val TOOL_FIND_SYMBOL_DEFINITION = "find_symbol_definition"
    const val TOOL_LOOKUP_SYMBOL = "lookup_symbol"
    const val TOOL_FIND_SYMBOL_REFERENCES = "find_symbol_references"
    const val TOOL_FIND_REFERENCES = "find_references"
    const val TOOL_GET_FILE_OUTLINE = "get_file_outline"
    const val TOOL_FILE_OUTLINE = "file_outline"
    const val TOOL_GET_TYPE_HIERARCHY = "get_type_hierarchy"
    const val TOOL_TYPE_HIERARCHY = "type_hierarchy"
    const val TOOL_RESOLVE_IMPORT = "resolve_import"
    const val TOOL_RESOLVE_AND_INSERT_IMPORT = "resolve_and_insert_import"
    const val TOOL_GET_PROJECT_BUILD_CONFIGURATION = "get_project_build_configuration"
    const val TOOL_GET_CURRENT_BUILD_MODE = "get_current_build_mode"
    const val TOOL_GET_LAST_BUILD_RESULT = "get_last_build_result"
    const val TOOL_GET_BUILD_DIAGNOSTICS = "get_build_diagnostics"
    const val TOOL_GET_DIAGNOSTICS = "get_diagnostics"
    const val TOOL_READ_BUILD_LOG = "read_build_log"
    const val TOOL_GET_BUILD_LOG = "get_build_log"
    const val TOOL_BUILD_CURRENT_PROJECT = "build_current_project"
    const val TOOL_BUILD_PROJECT = "build_project"
    const val TOOL_APPLY_FILE_CHANGES = "apply_file_changes"
    const val TOOL_APPLY_PROJECT_PATCH = "apply_project_patch"
    const val TOOL_APPLY_PATCH = "apply_patch"
    const val TOOL_ROLLBACK_AGENT_BATCH = "rollback_agent_batch"
    const val TOOL_GET_HISTORICAL_TOOL_OUTPUT = "get_historical_tool_output"
    const val TOOL_RUN_TERMINAL_COMMAND = "run_terminal_command"
    const val TOOL_EXECUTE_COMMAND = "execute_command"
    const val TOOL_MANAGE_TERMINAL_PROCESS = "manage_terminal_process"
    const val TOOL_TERMINAL_PROCESS = "terminal_process"
    const val TOOL_INSPECT_HISTORICAL_OUTPUT = "inspect_historical_output"

    val webSearchToolDefinition = ToolDefinition(
        name = TOOL_WEB_SEARCH,
        description = "Search the live web for up-to-date documentation, libraries, error messages, APIs, or factual information. Returns titles, URLs, and text snippets.",
        parameters = listOf(
            ToolParameterProperty(
                name = "query",
                type = "string",
                description = "The search query string to look up on the web.",
                required = true
            ),
            ToolParameterProperty(
                name = "limit",
                type = "integer",
                description = "Optional maximum number of search results to return (1 to 10).",
                required = false
            )
        )
    )

    val openUrlToolDefinition = ToolDefinition(
        name = TOOL_OPEN_URL,
        description = "Fetch and read the text content of a specific public HTTP(S) web page URL to inspect documentation or articles in detail.",
        parameters = listOf(
            ToolParameterProperty(
                name = "url",
                type = "string",
                description = "The full http:// or https:// URL to open and read.",
                required = true
            )
        )
    )

    val inspectProjectToolDefinition = ToolDefinition(
        name = TOOL_INSPECT_PROJECT,
        description = "Inspect the active Android project root, active build configuration (Offline NDK Builder vs Gradle), native C/C++ sources, and manifest files.",
        parameters = emptyList()
    )

    val listProjectFilesToolDefinition = ToolDefinition(
        name = TOOL_LIST_PROJECT_FILES,
        description = "List source code, resource, and configuration files in the project or a given subdirectory.",
        parameters = listOf(
            ToolParameterProperty(
                name = "subDirectory",
                type = "string",
                description = "Optional relative sub-directory path within the project to list files from (e.g. 'app/src/main/jni').",
                required = false
            ),
            ToolParameterProperty(
                name = "limit",
                type = "integer",
                description = "Maximum number of files to return (default 80).",
                required = false
            )
        )
    )

    val searchProjectToolDefinition = ToolDefinition(
        name = TOOL_SEARCH_PROJECT,
        description = "Search across project source files for a specific string or pattern.",
        parameters = listOf(
            ToolParameterProperty(
                name = "query",
                type = "string",
                description = "The text or symbol to search for across project files.",
                required = true
            ),
            ToolParameterProperty(
                name = "fileExtension",
                type = "string",
                description = "Optional file extension to restrict search (e.g. 'kt', 'java', 'xml', 'cpp').",
                required = false
            )
        )
    )

    val readProjectFileToolDefinition = ToolDefinition(
        name = TOOL_READ_PROJECT_FILE,
        description = "Read the complete contents of a file within the project.",
        parameters = listOf(
            ToolParameterProperty(
                name = "filePath",
                type = "string",
                description = "The relative or absolute path of the project file to read.",
                required = true
            )
        )
    )

    val readFileRangeToolDefinition = ToolDefinition(
        name = TOOL_READ_FILE_RANGE,
        description = "Read a specific line range from a project file.",
        parameters = listOf(
            ToolParameterProperty(
                name = "filePath",
                type = "string",
                description = "The relative or absolute path of the file to inspect.",
                required = true
            ),
            ToolParameterProperty(
                name = "startLine",
                type = "integer",
                description = "The 1-based starting line number to read.",
                required = true
            ),
            ToolParameterProperty(
                name = "endLine",
                type = "integer",
                description = "The 1-based ending line number to read.",
                required = true
            )
        )
    )

    val inspectSymbolUsagesToolDefinition = ToolDefinition(
        name = TOOL_INSPECT_SYMBOL_USAGES,
        description = "Find all references, imports, and usages of a class, method, variable, or XML resource ID across the project.",
        parameters = listOf(
            ToolParameterProperty(
                name = "symbol",
                type = "string",
                description = "The symbol name (e.g. 'MainActivity', 'R.layout.activity_main', 'native_init').",
                required = true
            )
        )
    )

    val findSymbolDefinitionToolDefinition = ToolDefinition(
        name = TOOL_FIND_SYMBOL_DEFINITION,
        description = "Find where a class, interface, struct, function/method, enum, or macro is defined across project source files (C/C++, Java, Kotlin), returning exact file paths, line numbers, symbol kinds, and declaration signatures.",
        parameters = listOf(
            ToolParameterProperty(
                name = "symbol",
                type = "string",
                description = "The symbol or identifier name to look up (e.g. 'InitMenu', 'FloatingModMenu', 'MainActivity', 'MAX_BUFFER').",
                required = true
            ),
            ToolParameterProperty(
                name = "kind",
                type = "string",
                description = "Optional filter by symbol kind ('class', 'interface', 'struct', 'enum', 'func', 'macro').",
                required = false
            ),
            ToolParameterProperty(
                name = "exactMatch",
                type = "boolean",
                description = "Whether to require an exact case-sensitive match (true, default) or allow case-insensitive partial match (false).",
                required = false
            )
        )
    )

    val findSymbolReferencesToolDefinition = ToolDefinition(
        name = TOOL_FIND_SYMBOL_REFERENCES,
        description = "Find all references, usages, and call sites of a symbol across project files (C/C++, Java, Kotlin), distinguishing definition sites from call sites with enclosing scopes and line numbers.",
        parameters = listOf(
            ToolParameterProperty(
                name = "symbol",
                type = "string",
                description = "The symbol or identifier name to find usages and call-sites for (e.g. 'InitMenu', 'currentMode', 'FloatingModMenu').",
                required = true
            ),
            ToolParameterProperty(
                name = "fileHint",
                type = "string",
                description = "Optional file path hint to narrow down search or prioritize relevant files (e.g. 'Main.cpp').",
                required = false
            ),
            ToolParameterProperty(
                name = "limit",
                type = "integer",
                description = "Maximum number of references to return (default 50).",
                required = false
            )
        )
    )

    val getFileOutlineToolDefinition = ToolDefinition(
        name = TOOL_GET_FILE_OUTLINE,
        description = "Get a structured outline of all symbols (classes, methods, functions, structs, enums, macros, imports) declared in a project file, with exact line numbers and signatures.",
        parameters = listOf(
            ToolParameterProperty(
                name = "filePath",
                type = "string",
                description = "The relative or absolute path of the project file to inspect.",
                required = true
            )
        )
    )

    val getTypeHierarchyToolDefinition = ToolDefinition(
        name = TOOL_GET_TYPE_HIERARCHY,
        description = "Get the inheritance and type hierarchy for a given class, struct, or interface across the project, including its supertypes (base classes/interfaces), known subtypes/implementers, and overridden methods.",
        parameters = listOf(
            ToolParameterProperty(
                name = "typeName",
                type = "string",
                description = "The simple or qualified name of the class, struct, or interface to inspect (e.g. 'ModMenuController', 'Activity', 'BaseAdapter').",
                required = true
            )
        )
    )

    val resolveImportToolDefinition = ToolDefinition(
        name = TOOL_RESOLVE_IMPORT,
        description = "Resolve and optionally insert the correct import statement or C/C++ #include for an unresolved symbol by searching definitions in the project RepoMap.",
        parameters = listOf(
            ToolParameterProperty(
                name = "symbol",
                type = "string",
                description = "The unresolved symbol or type name (e.g. 'Environment', 'Setup', 'MenuState').",
                required = true
            ),
            ToolParameterProperty(
                name = "filePath",
                type = "string",
                description = "The target file where the symbol is referenced (e.g. 'Main.cpp', 'MainActivity.kt').",
                required = true
            ),
            ToolParameterProperty(
                name = "insert",
                type = "boolean",
                description = "Whether to safely insert the import/include statement into the file on disk (default true).",
                required = false
            )
        )
    )

    val getProjectBuildConfigurationToolDefinition = ToolDefinition(
        name = TOOL_GET_PROJECT_BUILD_CONFIGURATION,
        description = "Get detailed active build configuration, including whether Offline NDK Builder or Gradle is selected, NDK directory, and build options.",
        parameters = emptyList()
    )

    val getCurrentBuildModeToolDefinition = ToolDefinition(
        name = TOOL_GET_CURRENT_BUILD_MODE,
        description = "Returns whether the IDE is currently set to 'OFFLINE_NDK' (Standalone Offline Builder) or 'GRADLE' (Standard Gradle).",
        parameters = emptyList()
    )

    val getLastBuildResultToolDefinition = ToolDefinition(
        name = TOOL_GET_LAST_BUILD_RESULT,
        description = "Get the outcome, exit code, duration, APK output path, and summary of the most recent build executed.",
        parameters = emptyList()
    )

    val getBuildDiagnosticsToolDefinition = ToolDefinition(
        name = TOOL_GET_BUILD_DIAGNOSTICS,
        description = "Get all active project diagnostics and compiler errors (full access to the Errors / Problems tab, Gradle build output, and LSP diagnostics across all project files).",
        parameters = listOf(
            ToolParameterProperty(
                name = "runId",
                type = "string",
                description = "Optional run ID from build_current_project. If omitted, uses the last build run.",
                required = false
            )
        )
    )

    val readBuildLogToolDefinition = ToolDefinition(
        name = TOOL_READ_BUILD_LOG,
        description = "Read the raw compiler/build output log from a specific build run or the last build run.",
        parameters = listOf(
            ToolParameterProperty(
                name = "runId",
                type = "string",
                description = "Optional run ID. If omitted, uses the last build run.",
                required = false
            ),
            ToolParameterProperty(
                name = "maxLines",
                type = "integer",
                description = "Maximum lines to read from the log (default 500).",
                required = false
            )
        )
    )

    val buildCurrentProjectToolDefinition = ToolDefinition(
        name = TOOL_BUILD_CURRENT_PROJECT,
        description = "Executes the real build of the active project using the shared build orchestrator (never simulated). Returns build success/failure, runId, exit code, output APK path, and diagnostics.",
        parameters = listOf(
            ToolParameterProperty(
                name = "buildMode",
                type = "string",
                description = "Optional build mode override ('OFFLINE_NDK' or 'GRADLE'). If omitted, uses active IDE mode.",
                required = false
            )
        )
    )

    val applyFileChangesToolDefinition = ToolDefinition(
        name = TOOL_APPLY_FILE_CHANGES,
        description = "Atomically applies coordinated changes to any number of project files (1, 3, 5, 10, 20+ files) with structural validation and rollback.",
        parameters = listOf(
            ToolParameterProperty(
                name = "codeContent",
                type = "string",
                description = "The code content containing FILE_TO_MODIFY: / FILE_TO_CREATE: / FILE_TO_PATCH: blocks.",
                required = true
            )
        )
    )

    val applyProjectPatchToolDefinition = ToolDefinition(
        name = TOOL_APPLY_PROJECT_PATCH,
        description = "Atomically applies safe patches or modifications to active project files using FILE_TO_MODIFY, FILE_TO_CREATE, or FILE_TO_PATCH format.",
        parameters = listOf(
            ToolParameterProperty(
                name = "codeContent",
                type = "string",
                description = "The code content or patch containing target files and replacement code.",
                required = true
            )
        )
    )

    val rollbackAgentBatchToolDefinition = ToolDefinition(
        name = TOOL_ROLLBACK_AGENT_BATCH,
        description = "Rolls back the last applied atomic change-set if validation or build fails.",
        parameters = emptyList()
    )

    val getHistoricalToolOutputToolDefinition = ToolDefinition(
        name = TOOL_GET_HISTORICAL_TOOL_OUTPUT,
        description = "Retrieve or search specific sections from a previous tool execution output that was compacted or pruned from the context window, without re-reading the entire file or re-executing commands.",
        parameters = listOf(
            ToolParameterProperty(
                name = "callId",
                type = "string",
                description = "The tool call ID from an earlier turn (e.g. 'call_123').",
                required = true
            ),
            ToolParameterProperty(
                name = "query",
                type = "string",
                description = "Optional search query string to locate specific matching lines in the historical output.",
                required = false
            ),
            ToolParameterProperty(
                name = "startLine",
                type = "integer",
                description = "Optional 1-based start line number to inspect a specific line range.",
                required = false
            ),
            ToolParameterProperty(
                name = "endLine",
                type = "integer",
                description = "Optional 1-based end line number to inspect a specific line range.",
                required = false
            )
        )
    )

    val runTerminalCommandToolDefinition = ToolDefinition(
        name = TOOL_RUN_TERMINAL_COMMAND,
        description = "Execute a shell or terminal command in the project environment. Supports both synchronous commands and long-running background processes (dev servers, file watchers) via isBackground=true or action parameter ('start', 'poll', 'read_output', 'wait', 'terminate', 'list'). Commands that modify, delete, or clean files require explicit user approval.",
        parameters = listOf(
            ToolParameterProperty(
                name = "command",
                type = "string",
                description = "The terminal/shell command string to execute (e.g. 'git status', './gradlew tasks', etc.). Required when starting a process or running foreground commands.",
                required = false
            ),
            ToolParameterProperty(
                name = "workingDir",
                type = "string",
                description = "Optional sub-directory relative to project root.",
                required = false
            ),
            ToolParameterProperty(
                name = "isBackground",
                type = "boolean",
                description = "Whether to start the command as a managed long-running background process (e.g. dev server, file watcher).",
                required = false
            ),
            ToolParameterProperty(
                name = "action",
                type = "string",
                description = "Lifecycle action for managed processes: 'execute' (default foreground), 'start' (background), 'poll', 'read_output', 'wait', 'terminate', 'list'.",
                required = false
            ),
            ToolParameterProperty(
                name = "processId",
                type = "string",
                description = "Process ID returned from a background process start (required for poll, read_output, wait, terminate).",
                required = false
            ),
            ToolParameterProperty(
                name = "cursor",
                type = "integer",
                description = "Incremental line cursor for 'read_output' action (default 0).",
                required = false
            ),
            ToolParameterProperty(
                name = "maxLines",
                type = "integer",
                description = "Maximum lines to read during 'read_output' (default 100).",
                required = false
            ),
            ToolParameterProperty(
                name = "timeoutMs",
                type = "integer",
                description = "Timeout in milliseconds for foreground execution or 'wait' action.",
                required = false
            )
        )
    )

    val manageTerminalProcessToolDefinition = ToolDefinition(
        name = TOOL_MANAGE_TERMINAL_PROCESS,
        description = "Control and inspect long-running terminal processes (dev servers, file watchers, continuous test runners). Actions: 'start', 'poll', 'read_output', 'wait', 'terminate', 'list'.",
        parameters = runTerminalCommandToolDefinition.parameters
    )

    val WEB_TOOLS: List<ToolDefinition> = listOf(
        webSearchToolDefinition,
        openUrlToolDefinition
    )

    val ENGINEERING_TOOLS: List<ToolDefinition> = listOf(
        webSearchToolDefinition,
        openUrlToolDefinition,
        inspectProjectToolDefinition,
        listProjectFilesToolDefinition,
        searchProjectToolDefinition,
        readProjectFileToolDefinition,
        readFileRangeToolDefinition,
        inspectSymbolUsagesToolDefinition,
        findSymbolDefinitionToolDefinition,
        findSymbolReferencesToolDefinition,
        getFileOutlineToolDefinition,
        getTypeHierarchyToolDefinition,
        resolveImportToolDefinition,
        getProjectBuildConfigurationToolDefinition,
        getCurrentBuildModeToolDefinition,
        getLastBuildResultToolDefinition,
        getBuildDiagnosticsToolDefinition,
        readBuildLogToolDefinition,
        buildCurrentProjectToolDefinition,
        applyFileChangesToolDefinition,
        applyProjectPatchToolDefinition,
        rollbackAgentBatchToolDefinition,
        getHistoricalToolOutputToolDefinition,
        runTerminalCommandToolDefinition,
        manageTerminalProcessToolDefinition
    )

    val MUTATING_TOOL_NAMES: Set<String> = setOf(
        TOOL_APPLY_FILE_CHANGES,
        TOOL_APPLY_PROJECT_PATCH,
        TOOL_APPLY_PATCH,
        TOOL_ROLLBACK_AGENT_BATCH,
        TOOL_RUN_TERMINAL_COMMAND,
        TOOL_EXECUTE_COMMAND,
        TOOL_MANAGE_TERMINAL_PROCESS,
        TOOL_TERMINAL_PROCESS
    )

    fun isMutatingTool(toolName: String): Boolean {
        val canonical = canonicalToolName(toolName)
        return MUTATING_TOOL_NAMES.contains(canonical)
    }

    val READ_ONLY_ENGINEERING_TOOLS: List<ToolDefinition> = ENGINEERING_TOOLS.filter { tool ->
        !isMutatingTool(tool.name)
    }

    fun findToolDefinition(toolName: String): ToolDefinition? {
        val canonical = canonicalToolName(toolName)
        return ENGINEERING_TOOLS.find { canonicalToolName(it.name) == canonical }
    }

    /**
     * Returns tool definitions advertised to the model based on the active agent execution mode.
     * In SIDE_AGENT mode without explicit edit authorization, mutating tools are withheld
     * to keep the agent in safe advisory mode.
     */
    fun getToolsForExecutionMode(
        mode: com.tom.rv2ide.artificial.policy.AgentExecutionMode,
        isEditAuthorized: Boolean,
        webSearchReady: Boolean = true
    ): List<ToolDefinition> {
        val baseTools = if (mode == com.tom.rv2ide.artificial.policy.AgentExecutionMode.SIDE_AGENT && !isEditAuthorized) {
            READ_ONLY_ENGINEERING_TOOLS
        } else {
            ENGINEERING_TOOLS
        }
        return if (webSearchReady) {
            baseTools
        } else {
            baseTools.filter { it.name != TOOL_WEB_SEARCH && it.name != TOOL_OPEN_URL }
        }
    }

    /**
     * Resolves alias tool names to canonical registered names.
     */
    fun canonicalToolName(toolName: String): String {
        return when (toolName.trim().lowercase()) {
            TOOL_READ_FILE -> TOOL_READ_PROJECT_FILE
            TOOL_LIST_FILES -> TOOL_LIST_PROJECT_FILES
            TOOL_FIND_IN_PROJECT -> TOOL_SEARCH_PROJECT
            TOOL_LOOKUP_SYMBOL -> TOOL_FIND_SYMBOL_DEFINITION
            TOOL_FIND_REFERENCES -> TOOL_FIND_SYMBOL_REFERENCES
            TOOL_FILE_OUTLINE -> TOOL_GET_FILE_OUTLINE
            TOOL_TYPE_HIERARCHY -> TOOL_GET_TYPE_HIERARCHY
            TOOL_RESOLVE_AND_INSERT_IMPORT -> TOOL_RESOLVE_IMPORT
            TOOL_BUILD_PROJECT -> TOOL_BUILD_CURRENT_PROJECT
            TOOL_GET_DIAGNOSTICS -> TOOL_GET_BUILD_DIAGNOSTICS
            TOOL_GET_BUILD_LOG -> TOOL_READ_BUILD_LOG
            TOOL_APPLY_PROJECT_PATCH, TOOL_APPLY_PATCH -> TOOL_APPLY_FILE_CHANGES
            TOOL_PROJECT_INFO -> TOOL_INSPECT_PROJECT
            TOOL_INSPECT_HISTORICAL_OUTPUT -> TOOL_GET_HISTORICAL_TOOL_OUTPUT
            TOOL_EXECUTE_COMMAND, TOOL_MANAGE_TERMINAL_PROCESS, TOOL_TERMINAL_PROCESS -> TOOL_RUN_TERMINAL_COMMAND
            else -> toolName.trim()
        }
    }

    fun isRegistered(toolName: String): Boolean {
        val canonical = canonicalToolName(toolName)
        return ENGINEERING_TOOLS.any { it.name == canonical } || canonical == TOOL_APPLY_FILE_CHANGES
    }

    fun getRegisteredToolNames(): List<String> {
        return ENGINEERING_TOOLS.map { it.name }
    }
}
