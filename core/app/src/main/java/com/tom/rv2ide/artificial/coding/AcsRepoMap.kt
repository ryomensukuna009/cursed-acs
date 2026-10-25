package com.tom.rv2ide.artificial.coding

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Symbol kinds recognized in C/C++, Java, and Kotlin source files.
 */
enum class SymbolKind(val label: String) {
    CLASS("class"),
    INTERFACE("interface"),
    STRUCT("struct"),
    ENUM("enum"),
    FUNCTION("func"),
    VARIABLE("var"),
    FIELD("field"),
    PROPERTY("prop"),
    CONSTANT("const"),
    NAMESPACE("namespace"),
    MACRO("macro"),
    INCLUDE("include"),
    IMPORT("import")
}

/**
 * Confidence level of symbol resolution.
 */
enum class SymbolConfidence {
    EXACT_DECLARATION,
    STRUCTURAL_PARSE,
    HEURISTIC
}

/**
 * Represents an indexed code symbol with scope hierarchy and confidence.
 */
data class RepoSymbol(
    val name: String,
    val kind: SymbolKind,
    val line: Int,
    val endLine: Int = line,
    val signature: String? = null,
    val enclosingScope: String? = null,
    val qualifiedName: String? = null,
    val confidence: SymbolConfidence = SymbolConfidence.STRUCTURAL_PARSE,
    val docComment: String? = null
)

enum class ReferenceCategory(val label: String) {
    DEFINITION("Definition"),
    ACTIVE_REFERENCE("Active Reference / Call"),
    COMMENT("Commented / Inactive"),
    STRING_LITERAL("String Literal"),
    UNKNOWN_TEXTUAL("Uncertain Textual Match")
}

/**
 * Represents a reference, call-site, or usage of a symbol.
 */
data class SymbolReference(
    val symbolName: String,
    val relativePath: String,
    val line: Int,
    val column: Int = 1,
    val lineContent: String,
    val isDefinition: Boolean,
    val enclosingScope: String? = null,
    val symbolKind: SymbolKind? = null,
    val category: ReferenceCategory = if (isDefinition) ReferenceCategory.DEFINITION else ReferenceCategory.ACTIVE_REFERENCE
)

/**
 * Represents type inheritance and interface implementation relationships.
 */
data class TypeInheritance(
    val typeName: String,
    val qualifiedName: String?,
    val relativePath: String,
    val line: Int,
    val kind: SymbolKind,
    val superTypes: List<String> = emptyList(),
    val isAbstract: Boolean = false
)

/**
 * Represents method overrides and virtual implementations.
 */
data class MethodOverride(
    val methodName: String,
    val enclosingType: String,
    val relativePath: String,
    val line: Int,
    val isOverride: Boolean = true,
    val signature: String? = null
)

/**
 * Result of querying type hierarchy for a given class or interface.
 */
data class TypeHierarchyResult(
    val typeName: String,
    val relativePath: String,
    val kind: SymbolKind,
    val superTypes: List<String>,
    val subTypes: List<String>,
    val overrides: List<MethodOverride>
)

/**
 * Cached symbols for a single project file.
 */
data class FileSymbols(
    val relativePath: String,
    val lastModified: Long,
    val fileSize: Long,
    val symbols: List<RepoSymbol>,
    val typeInheritance: List<TypeInheritance> = emptyList(),
    val overrides: List<MethodOverride> = emptyList()
)

/**
 * Lightweight, token-bounded repository map indexer and ranker for ROXUM-ACS.
 *
 * Adapted from Aider's repomap architecture for mobile IDE performance:
 * - Deterministic, non-blocking scanner for C/C++, Java, and Kotlin.
 * - Project-isolated cache with modification-time & file-size freshness checks.
 * - Multi-criteria ranking: prioritizes active editor file, open tabs, diagnostic error files,
 *   direct dependencies, and query keywords.
 * - Strictly bounded token output (~1,000 tokens / 4,000 characters).
 */
object AcsRepoMap {

    private val log = LoggerFactory.getLogger(AcsRepoMap::class.java)

    const val DEFAULT_MAX_TOKENS = 1000
    const val CHARS_PER_ESTIMATED_TOKEN = 4

    private val SUPPORTED_EXTENSIONS = setOf(
        "c", "cc", "cpp", "h", "hh", "hpp",
        "java",
        "kt"
    )

    private val IGNORED_DIR_NAMES = setOf(
        ".git", ".gradle", ".idea", "build", "captures", ".cxx", ".externalNativeBuild",
        "node_modules", "obj", "bin", "target", "out"
    )

    // Language-specific regexes
    private val MACRO_REGEX = Regex("""^\s*#\s*define\s+([A-Za-z_][A-Za-z0-9_]*)(?:\(([^)]*)\))?""")
    private val INCLUDE_REGEX = Regex("""^\s*#\s*include\s+["<]([^">]+)[">]""")
    private val IMPORT_REGEX = Regex("""^\s*import\s+(?:static\s+)?([A-Za-z0-9_.*]+)""")

    // C/C++ declarations
    private val CPP_NAMESPACE_REGEX = Regex("""\bnamespace\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*\{|\s*$)""")
    private val CPP_CLASS_STRUCT_REGEX = Regex("""\b(class|struct|enum(?:\s+class)?)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*:\s*([^;{]+))?(?:\s*[{;]|\s*$)""")
    private val CPP_FUNCTION_REGEX = Regex("""^\s*(?:(?:inline|static|virtual|explicit|constexpr|extern\s+"C"|extern)\s+)*([A-Za-z_][A-Za-z0-9_:*&<>\s]+?\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:const|override|final|noexcept)?\s*[{;]""")
    private val CPP_FIELD_REGEX = Regex("""^\s*(?:(?:extern\s+"C"|extern|const|static|constexpr|mutable|unsigned|signed|volatile)\s+)*([A-Za-z_][A-Za-z0-9_:*&<>\s]+?\s+)([^;]+);\s*$""")

    // Java declarations
    private val JAVA_CLASS_INTERFACE_REGEX = Regex("""\b(class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)(?:<[^>]+>)?(?:\s+extends\s+([^{;]+?))?(?:\s+implements\s+([^{;]+?))?(?:\s*[{;]|\s*$)""")
    private val JAVA_METHOD_REGEX = Regex("""^\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default)\s+)*([A-Za-z_][A-Za-z0-9_<>\[\],\s]*\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:throws\s+[^{;]+)?\s*[{;]""")
    private val JAVA_FIELD_REGEX = Regex("""^\s*(?:(?:public|protected|private|static|final|transient|volatile)\s+)+([A-Za-z_][A-Za-z0-9_<>\[\],\s]*?\s+)([^;]+);\s*$""")

    // Kotlin declarations
    private val KOTLIN_CLASS_INTERFACE_REGEX = Regex("""\b(class|interface|object|enum\s+class|data\s+class|sealed\s+class)\s+([A-Za-z_][A-Za-z0-9_]*)(?:<[^>]+>)?(?:\s*\([^)]*\))?(?:\s*:\s*([^{;]+))?(?:\s*[{;]|\s*$)""")
    private val KOTLIN_FUN_REGEX = Regex("""\bfun\s+(?:<[^>]+>\s+)?(?:[A-Za-z0-9_<>.]+\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""")
    private val KOTLIN_VAL_VAR_REGEX = Regex("""^\s*(?:(?:private|protected|public|internal|override|final|abstract)\s+)*(val|var|const\s+val)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*:\s*([^=;{]+))?(?:\s*=\s*[^;]+)?""")

    private val CONTROL_FLOW_KEYWORDS = setOf(
        "if", "else", "while", "for", "switch", "catch", "return", "throw", "case", "new", "delete", "sizeof", "goto"
    )

    private data class OpenScope(
        val name: String,
        val kind: SymbolKind,
        val startLine: Int,
        val openingBraceDepth: Int,
        val symbolIndex: Int
    )

    /**
     * Splits a string by [delimiter] only at top-level (outside parentheses, braces, brackets, and quotes).
     */
    fun splitTopLevelDelimiters(text: String, delimiter: Char = ','): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var angleDepth = 0
        var inQuotes = false
        var quoteChar = ' '
        var escape = false

        for (i in text.indices) {
            val ch = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\') {
                escape = true
                continue
            }
            if (ch == '"' || ch == '\'') {
                if (!inQuotes) {
                    inQuotes = true
                    quoteChar = ch
                } else if (ch == quoteChar) {
                    inQuotes = false
                }
                continue
            }
            if (inQuotes) continue

            when (ch) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0) angleDepth--
                delimiter -> {
                    if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0 && angleDepth == 0) {
                        parts.add(text.substring(start, i).trim())
                        start = i + 1
                    }
                }
            }
        }
        if (start < text.length) {
            val last = text.substring(start).trim()
            if (last.isNotEmpty()) {
                parts.add(last)
            }
        }
        return parts
    }

    private fun countBraceDelta(line: String): Int {
        var delta = 0
        var inQuotes = false
        var quoteChar = ' '
        var escape = false
        for (ch in line) {
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\') {
                escape = true
                continue
            }
            if (ch == '"' || ch == '\'') {
                if (!inQuotes) {
                    inQuotes = true
                    quoteChar = ch
                } else if (ch == quoteChar) {
                    inQuotes = false
                }
                continue
            }
            if (!inQuotes) {
                if (ch == '{') delta++
                else if (ch == '}') delta--
            }
        }
        return delta
    }

    // Project-isolated cache: canonical project root path -> ProjectCache
    private val projectCaches = ConcurrentHashMap<String, ProjectCache>()

    private class ProjectCache(val rootCanonicalPath: String) {
        val fileMap = ConcurrentHashMap<String, FileSymbols>()
    }

    private fun getProjectCache(projectRoot: File): ProjectCache {
        val key = projectRoot.canonicalPath
        return projectCaches.computeIfAbsent(key) { ProjectCache(it) }
    }

    /**
     * Clears cached symbol data for a project root, or all projects if null.
     */
    fun clearCache(projectRoot: File? = null) {
        if (projectRoot != null) {
            projectCaches.remove(projectRoot.canonicalPath)
        } else {
            projectCaches.clear()
        }
    }

    /**
     * Invalidates a single modified or deleted file from the project's symbol cache.
     */
    fun invalidateFile(projectRoot: File, file: File) {
        val cache = projectCaches[projectRoot.canonicalPath] ?: return
        val relPath = file.relativeToOrNull(projectRoot)?.path?.replace('\\', '/') ?: file.name
        cache.fileMap.remove(relPath)
    }

    /**
     * Updates cache for a modified file incrementally without a full-project rescan.
     */
    fun updateFile(projectRoot: File, file: File): FileSymbols? {
        invalidateFile(projectRoot, file)
        return getOrScanFile(projectRoot, file)
    }

    /**
     * Removes a deleted file from cache incrementally.
     */
    fun removeFile(projectRoot: File, file: File) {
        invalidateFile(projectRoot, file)
    }

    /**
     * Invalidates a single file across all active project caches containing it.
     */
    fun invalidateFile(file: File) {
        val canonical = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
        for (cache in projectCaches.values) {
            if (canonical.startsWith(cache.rootCanonicalPath)) {
                val relPath = file.relativeToOrNull(File(cache.rootCanonicalPath))?.path?.replace('\\', '/') ?: file.name
                cache.fileMap.remove(relPath)
            }
        }
    }

    /**
     * Scans or retrieves cached symbols for a single file within the active project.
     */
    fun getOrScanFile(projectRoot: File, file: File): FileSymbols? {
        if (!file.exists() || !file.isFile || file.length() > 500_000) return null
        val ext = file.extension.lowercase()
        if (ext !in SUPPORTED_EXTENSIONS) return null

        val relPath = file.relativeToOrNull(projectRoot)?.path?.replace('\\', '/') ?: file.name
        val cache = getProjectCache(projectRoot)

        val cached = cache.fileMap[relPath]
        if (cached != null && cached.lastModified == file.lastModified() && cached.fileSize == file.length()) {
            return cached
        }

        val scanResult = scanFileContentExtended(file, ext)
        val fileSymbols = FileSymbols(
            relativePath = relPath,
            lastModified = file.lastModified(),
            fileSize = file.length(),
            symbols = scanResult.symbols,
            typeInheritance = scanResult.typeInheritance.map { it.copy(relativePath = relPath) },
            overrides = scanResult.overrides.map { it.copy(relativePath = relPath) }
        )
        cache.fileMap[relPath] = fileSymbols
        return fileSymbols
    }

    /**
     * Scans all supported source files in [projectRoot] safely respecting directory boundaries.
     */
    fun scanProject(projectRoot: File, forceRefresh: Boolean = false): List<FileSymbols> {
        if (!projectRoot.exists() || !projectRoot.isDirectory) return emptyList()

        if (forceRefresh) {
            clearCache(projectRoot)
        }

        val cache = getProjectCache(projectRoot)
        val filesOnDisk = mutableSetOf<String>()
        val result = mutableListOf<FileSymbols>()

        try {
            projectRoot.walkTopDown()
                .onEnter { dir ->
                    if (dir.name in IGNORED_DIR_NAMES) return@onEnter false
                    // Security boundary check: do not follow symlinks outside projectRoot
                    try {
                        dir.canonicalPath.startsWith(projectRoot.canonicalPath)
                    } catch (_: Exception) {
                        false
                    }
                }
                .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
                .forEach { file ->
                    val relPath = file.relativeToOrNull(projectRoot)?.path?.replace('\\', '/') ?: file.name
                    filesOnDisk.add(relPath)
                    getOrScanFile(projectRoot, file)?.let { result.add(it) }
                }

            // Cleanup removed files from cache
            cache.fileMap.keys.retainAll(filesOnDisk)
        } catch (e: Exception) {
            log.warn("Error during repository map scan: {}", e.message)
        }

        return result
    }

    /**
     * Scans source code and extracts class, interface, struct, enum, function, macro, and import symbols.
     */
    fun scanFileContent(file: File, extension: String = file.extension.lowercase()): List<RepoSymbol> {
        val lines = try {
            file.readLines()
        } catch (_: Exception) {
            return emptyList()
        }
        return scanLines(lines, extension)
    }

    /**
     * Scans source code and extracts symbols, type inheritance relationships, and method overrides.
     */
    fun scanFileContentExtended(file: File, extension: String = file.extension.lowercase()): ParsedFileScanResult {
        val lines = try {
            file.readLines()
        } catch (_: Exception) {
            return ParsedFileScanResult(emptyList())
        }
        return scanLinesExtended(lines, extension)
    }

    /**
     * Scans lines of code directly and returns extracted RepoSymbol list for backwards compatibility.
     */
    fun scanLines(lines: List<String>, extension: String): List<RepoSymbol> {
        return scanLinesExtended(lines, extension).symbols
    }

    data class ParsedFileScanResult(
        val symbols: List<RepoSymbol>,
        val typeInheritance: List<TypeInheritance> = emptyList(),
        val overrides: List<MethodOverride> = emptyList()
    )

    /**
     * Scans lines of code directly with comment stripping, brace depth tracking,
     * scope hierarchy tracking, multi-declaration parsing, type inheritance, and override extraction.
     */
    fun scanLinesExtended(lines: List<String>, extension: String): ParsedFileScanResult {
        val symbols = mutableListOf<RepoSymbol>()
        val typeInheritanceList = mutableListOf<TypeInheritance>()
        val overridesList = mutableListOf<MethodOverride>()
        val scopeStack = mutableListOf<OpenScope>()
        var braceDepth = 0
        var inBlockComment = false
        var previousLineWasJavaOverride = false
        val isCpp = extension in setOf("c", "cc", "cpp", "h", "hh", "hpp")
        val isJava = extension == "java"
        val isKotlin = extension == "kt"
        var pendingDocComment: StringBuilder? = null

        fun getEnclosingScopeName(): String? {
            return if (scopeStack.isEmpty()) null else scopeStack.joinToString(".") { it.name }
        }

        fun makeQualifiedName(name: String): String {
            val enc = getEnclosingScopeName()
            return if (enc != null) "$enc.$name" else name
        }

        fun addSymbol(
            name: String,
            kind: SymbolKind,
            lineNum: Int,
            sig: String?,
            opensBlock: Boolean = false,
            confidence: SymbolConfidence = SymbolConfidence.STRUCTURAL_PARSE
        ) {
            val enc = getEnclosingScopeName()
            val qName = makeQualifiedName(name)
            val doc = pendingDocComment?.toString()?.trim()?.takeIf { it.isNotBlank() }
            pendingDocComment = null

            val symIndex = symbols.size
            symbols.add(
                RepoSymbol(
                    name = name,
                    kind = kind,
                    line = lineNum,
                    endLine = lineNum,
                    signature = sig,
                    enclosingScope = enc,
                    qualifiedName = qName,
                    confidence = confidence,
                    docComment = doc
                )
            )

            if (opensBlock) {
                scopeStack.add(
                    OpenScope(
                        name = name,
                        kind = kind,
                        startLine = lineNum,
                        openingBraceDepth = braceDepth + 1,
                        symbolIndex = symIndex
                    )
                )
            }
        }

        lines.forEachIndexed { index, rawLine ->
            val lineNum = index + 1
            var line = rawLine.trim()

            // Handle multi-line block comments /* ... */
            if (inBlockComment) {
                if (line.contains("*/")) {
                    inBlockComment = false
                    val docPart = line.substringBefore("*/")
                    pendingDocComment?.appendLine(docPart.trim())
                    line = line.substringAfter("*/").trim()
                } else {
                    pendingDocComment?.appendLine(line)
                    return@forEachIndexed
                }
            }

            if (line.contains("/*")) {
                if (line.startsWith("/**") || line.startsWith("/*")) {
                    if (pendingDocComment == null) pendingDocComment = StringBuilder()
                    pendingDocComment?.appendLine(line)
                }
                if (!line.contains("*/")) {
                    inBlockComment = true
                    line = line.substringBefore("/*").trim()
                } else {
                    line = (line.substringBefore("/*") + " " + line.substringAfter("*/")).trim()
                }
            }

            // Strip single-line comments
            val commentIdx = line.indexOf("//")
            if (commentIdx >= 0) {
                val commentContent = line.substring(commentIdx)
                if (commentContent.startsWith("///")) {
                    if (pendingDocComment == null) pendingDocComment = StringBuilder()
                    pendingDocComment?.appendLine(commentContent.removePrefix("///").trim())
                }
                line = line.substring(0, commentIdx).trim()
            }

            if (line.isEmpty()) {
                return@forEachIndexed
            }

            var symbolDeclaredOnThisLine = false

            // 1. C/C++ Preprocessor Macros & Includes
            if (isCpp && line.startsWith("#")) {
                val macroMatch = MACRO_REGEX.find(line)
                if (macroMatch != null) {
                    val name = macroMatch.groupValues[1]
                    val params = macroMatch.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
                    val sig = if (params != null) "#define $name($params)" else "#define $name"
                    addSymbol(name, SymbolKind.MACRO, lineNum, sig, opensBlock = false)
                    symbolDeclaredOnThisLine = true
                } else {
                    val includeMatch = INCLUDE_REGEX.find(line)
                    if (includeMatch != null) {
                        val name = includeMatch.groupValues[1]
                        addSymbol(name, SymbolKind.INCLUDE, lineNum, "#include \"$name\"", opensBlock = false)
                        symbolDeclaredOnThisLine = true
                    }
                }
            }

            // 2. Imports (Java & Kotlin)
            if (!symbolDeclaredOnThisLine && (isJava || isKotlin) && line.startsWith("import ")) {
                val importMatch = IMPORT_REGEX.find(line)
                if (importMatch != null) {
                    val imported = importMatch.groupValues[1]
                    val shortName = imported.substringAfterLast('.')
                    addSymbol(shortName, SymbolKind.IMPORT, lineNum, "import $imported", opensBlock = false)
                    symbolDeclaredOnThisLine = true
                }
            }

            val insideFunction = scopeStack.any { it.kind == SymbolKind.FUNCTION }
            val insideClassOrStruct = scopeStack.any {
                it.kind == SymbolKind.CLASS || it.kind == SymbolKind.STRUCT ||
                    it.kind == SymbolKind.INTERFACE || it.kind == SymbolKind.ENUM
            }

            // 3. Kotlin definitions
            if (!symbolDeclaredOnThisLine && isKotlin) {
                val classMatch = KOTLIN_CLASS_INTERFACE_REGEX.find(line)
                if (classMatch != null) {
                    val declKind = classMatch.groupValues[1]
                    val name = classMatch.groupValues[2]
                    val superTypesRaw = classMatch.groupValues.getOrNull(3)?.trim()
                    val superTypes = if (!superTypesRaw.isNullOrBlank()) {
                        splitTopLevelDelimiters(superTypesRaw, ',').mapNotNull { superClause ->
                            val clean = superClause.trim().substringBefore('(').substringBefore('<').trim()
                            clean.takeIf { it.isNotEmpty() && it != name }
                        }
                    } else emptyList()
                    val kind = when {
                        declKind.contains("interface") -> SymbolKind.INTERFACE
                        declKind.contains("enum") -> SymbolKind.ENUM
                        else -> SymbolKind.CLASS
                    }
                    val qName = makeQualifiedName(name)
                    val opensBlock = line.contains("{") || !line.endsWith(";")
                    addSymbol(name, kind, lineNum, "$declKind $name", opensBlock = opensBlock)
                    typeInheritanceList.add(
                        TypeInheritance(
                            typeName = name,
                            qualifiedName = qName,
                            relativePath = "",
                            line = lineNum,
                            kind = kind,
                            superTypes = superTypes
                        )
                    )
                    symbolDeclaredOnThisLine = true
                } else if (!insideFunction) {
                    val funMatch = KOTLIN_FUN_REGEX.find(line)
                    if (funMatch != null) {
                        val name = funMatch.groupValues[1]
                        val params = funMatch.groupValues.getOrNull(2).orEmpty()
                        if (name !in CONTROL_FLOW_KEYWORDS) {
                            val enc = getEnclosingScopeName() ?: ""
                            val opensBlock = line.contains("{") || !line.endsWith(";")
                            val sig = "fun $name($params)"
                            addSymbol(name, SymbolKind.FUNCTION, lineNum, sig, opensBlock = opensBlock)
                            if (line.contains("override") && insideClassOrStruct) {
                                overridesList.add(
                                    MethodOverride(
                                        methodName = name,
                                        enclosingType = enc,
                                        relativePath = "",
                                        line = lineNum,
                                        signature = sig
                                    )
                                )
                            }
                            symbolDeclaredOnThisLine = true
                        }
                    } else {
                        val valVarMatch = KOTLIN_VAL_VAR_REGEX.find(line)
                        if (valVarMatch != null) {
                            val keyword = valVarMatch.groupValues[1]
                            val name = valVarMatch.groupValues[2]
                            if (name !in CONTROL_FLOW_KEYWORDS) {
                                val isConst = keyword.contains("const")
                                val inClass = insideClassOrStruct
                                val kind = when {
                                    isConst -> SymbolKind.CONSTANT
                                    inClass -> SymbolKind.PROPERTY
                                    else -> SymbolKind.VARIABLE
                                }
                                addSymbol(name, kind, lineNum, line, opensBlock = false)
                                symbolDeclaredOnThisLine = true
                            }
                        }
                    }
                }
            }

            // 4. Java definitions
            if (!symbolDeclaredOnThisLine && isJava) {
                if (line.startsWith("@Override") || line == "@Override") {
                    previousLineWasJavaOverride = true
                }
                val classMatch = JAVA_CLASS_INTERFACE_REGEX.find(line)
                if (classMatch != null) {
                    val declKind = classMatch.groupValues[1]
                    val name = classMatch.groupValues[2]
                    val extendsClause = classMatch.groupValues.getOrNull(3)?.trim()
                    val implementsClause = classMatch.groupValues.getOrNull(4)?.trim()
                    val superTypes = mutableListOf<String>()
                    if (!extendsClause.isNullOrBlank()) {
                        splitTopLevelDelimiters(extendsClause, ',').forEach { ext ->
                            val clean = ext.substringBefore('<').trim()
                            if (clean.isNotEmpty() && clean != name) superTypes.add(clean)
                        }
                    }
                    if (!implementsClause.isNullOrBlank()) {
                        splitTopLevelDelimiters(implementsClause, ',').forEach { imp ->
                            val clean = imp.substringBefore('<').trim()
                            if (clean.isNotEmpty() && clean != name) superTypes.add(clean)
                        }
                    }
                    val kind = when (declKind) {
                        "interface" -> SymbolKind.INTERFACE
                        "enum" -> SymbolKind.ENUM
                        else -> SymbolKind.CLASS
                    }
                    val qName = makeQualifiedName(name)
                    val opensBlock = line.contains("{") || !line.endsWith(";")
                    addSymbol(name, kind, lineNum, "$declKind $name", opensBlock = opensBlock)
                    typeInheritanceList.add(
                        TypeInheritance(
                            typeName = name,
                            qualifiedName = qName,
                            relativePath = "",
                            line = lineNum,
                            kind = kind,
                            superTypes = superTypes
                        )
                    )
                    symbolDeclaredOnThisLine = true
                } else if (!insideFunction) {
                    val methodMatch = JAVA_METHOD_REGEX.find(line)
                    if (methodMatch != null) {
                        val returnType = methodMatch.groupValues[1].trim()
                        val name = methodMatch.groupValues[2].trim()
                        val params = methodMatch.groupValues[3].trim()
                        if (name !in CONTROL_FLOW_KEYWORDS && name.isNotBlank()) {
                            val enc = getEnclosingScopeName() ?: ""
                            val sig = if (returnType.isNotEmpty()) "$returnType $name($params)" else "$name($params)"
                            val opensBlock = line.contains("{") && !line.endsWith(";")
                            addSymbol(name, SymbolKind.FUNCTION, lineNum, sig, opensBlock = opensBlock)
                            if ((line.contains("@Override") || previousLineWasJavaOverride) && insideClassOrStruct) {
                                overridesList.add(
                                    MethodOverride(
                                        methodName = name,
                                        enclosingType = enc,
                                        relativePath = "",
                                        line = lineNum,
                                        signature = sig
                                    )
                                )
                            }
                            previousLineWasJavaOverride = false
                            symbolDeclaredOnThisLine = true
                        }
                    } else if (insideClassOrStruct) {
                        val fieldMatch = JAVA_FIELD_REGEX.find(line)
                        if (fieldMatch != null) {
                            val typePrefix = fieldMatch.groupValues[1].trim()
                            val declaratorsRaw = fieldMatch.groupValues[2].trim()
                            val isConst = line.contains("static") && line.contains("final")
                            val kind = if (isConst) SymbolKind.CONSTANT else SymbolKind.FIELD
                            val declarators = splitTopLevelDelimiters(declaratorsRaw, ',')
                            for (decl in declarators) {
                                val declTrimmed = decl.trim()
                                val beforeEq = declTrimmed.substringBefore('=')
                                if (beforeEq.contains('(')) continue
                                val beforeBracket = beforeEq.substringBefore('[').trim()
                                val nameMatch = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*$""").find(beforeBracket)
                                val name = nameMatch?.groupValues?.get(1)?.trim()
                                if (name != null && name !in CONTROL_FLOW_KEYWORDS && name.isNotBlank()) {
                                    addSymbol(name, kind, lineNum, "$typePrefix $declTrimmed", opensBlock = false)
                                    symbolDeclaredOnThisLine = true
                                }
                            }
                        }
                    }
                }
            }

            // 5. C/C++ definitions
            if (!symbolDeclaredOnThisLine && isCpp) {
                val nsMatch = CPP_NAMESPACE_REGEX.find(line)
                if (nsMatch != null) {
                    val name = nsMatch.groupValues[1]
                    val opensBlock = line.contains("{")
                    addSymbol(name, SymbolKind.NAMESPACE, lineNum, "namespace $name", opensBlock = opensBlock)
                    symbolDeclaredOnThisLine = true
                } else {
                    val classMatch = CPP_CLASS_STRUCT_REGEX.find(line)
                    if (classMatch != null) {
                        val declKind = classMatch.groupValues[1]
                        val name = classMatch.groupValues[2]
                        val baseClause = classMatch.groupValues.getOrNull(3)?.trim()
                        val superTypes = if (!baseClause.isNullOrBlank()) {
                            splitTopLevelDelimiters(baseClause, ',').mapNotNull { base ->
                                val clean = base.replace(Regex("""\b(public|protected|private|virtual)\b"""), "")
                                    .substringBefore('<')
                                    .trim()
                                clean.takeIf { it.isNotEmpty() && it != name }
                            }
                        } else emptyList()
                        val kind = when {
                            declKind.startsWith("struct") -> SymbolKind.STRUCT
                            declKind.startsWith("enum") -> SymbolKind.ENUM
                            else -> SymbolKind.CLASS
                        }
                        val qName = makeQualifiedName(name)
                        val opensBlock = line.contains("{") || !line.endsWith(";")
                        addSymbol(name, kind, lineNum, "$declKind $name", opensBlock = opensBlock)
                        typeInheritanceList.add(
                            TypeInheritance(
                                typeName = name,
                                qualifiedName = qName,
                                relativePath = "",
                                line = lineNum,
                                kind = kind,
                                superTypes = superTypes
                            )
                        )
                        symbolDeclaredOnThisLine = true
                    } else if (!insideFunction) {
                        val funcMatch = CPP_FUNCTION_REGEX.find(line)
                        if (funcMatch != null) {
                            val returnType = funcMatch.groupValues[1].trim()
                            val name = funcMatch.groupValues[2].trim()
                            val params = funcMatch.groupValues[3].trim()
                            if (name !in CONTROL_FLOW_KEYWORDS && name.isNotBlank()) {
                                if (returnType.isNotEmpty() || insideClassOrStruct) {
                                    val enc = getEnclosingScopeName() ?: ""
                                    val sig = if (returnType.isNotEmpty()) "$returnType $name($params)" else "$name($params)"
                                    val opensBlock = line.contains("{") && !line.endsWith(";")
                                    addSymbol(name, SymbolKind.FUNCTION, lineNum, sig, opensBlock = opensBlock)
                                    if (line.contains(Regex("""\boverride\b""")) && insideClassOrStruct) {
                                        overridesList.add(
                                            MethodOverride(
                                                methodName = name,
                                                enclosingType = enc,
                                                relativePath = "",
                                                line = lineNum,
                                                signature = sig
                                            )
                                        )
                                    }
                                    symbolDeclaredOnThisLine = true
                                }
                            }
                        } else {
                            val fieldMatch = CPP_FIELD_REGEX.find(line)
                            if (fieldMatch != null) {
                                val typePrefix = fieldMatch.groupValues[1].trim()
                                val declaratorsRaw = fieldMatch.groupValues[2].trim()
                                val firstWord = typePrefix.substringBefore(' ').trim()
                                if (firstWord !in CONTROL_FLOW_KEYWORDS && firstWord !in setOf("typedef", "return", "throw", "goto", "new", "delete")) {
                                    val isConst = line.contains("const") || line.contains("constexpr")
                                    val kind = when {
                                        isConst -> SymbolKind.CONSTANT
                                        insideClassOrStruct -> SymbolKind.FIELD
                                        else -> SymbolKind.VARIABLE
                                    }
                                    val declarators = splitTopLevelDelimiters(declaratorsRaw, ',')
                                    for (decl in declarators) {
                                        val declTrimmed = decl.trim()
                                        if (declTrimmed.isEmpty()) continue
                                        val beforeEq = declTrimmed.substringBefore('=')
                                        if (beforeEq.contains('(')) continue
                                        val beforeBracket = beforeEq.substringBefore('[').trim()
                                        val nameMatch = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*$""").find(beforeBracket)
                                        val name = nameMatch?.groupValues?.get(1)?.trim()
                                        if (name != null && name !in CONTROL_FLOW_KEYWORDS && name.isNotBlank() &&
                                            name !in setOf("public", "private", "protected", "virtual", "class", "struct", "enum")) {
                                            addSymbol(name, kind, lineNum, "$typePrefix $declTrimmed", opensBlock = false)
                                            symbolDeclaredOnThisLine = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Update brace depth and pop finished scopes
            val delta = countBraceDelta(line)
            braceDepth += delta

            while (scopeStack.isNotEmpty() && braceDepth < scopeStack.last().openingBraceDepth) {
                val closed = scopeStack.removeAt(scopeStack.lastIndex)
                if (closed.symbolIndex < symbols.size) {
                    symbols[closed.symbolIndex] = symbols[closed.symbolIndex].copy(endLine = lineNum)
                }
            }
        }

        // Close any scopes that reached end of file
        while (scopeStack.isNotEmpty()) {
            val closed = scopeStack.removeAt(scopeStack.lastIndex)
            if (closed.symbolIndex < symbols.size) {
                symbols[closed.symbolIndex] = symbols[closed.symbolIndex].copy(endLine = lines.size)
            }
        }

        return ParsedFileScanResult(symbols, typeInheritanceList, overridesList)
    }

    /**
     * Ranks project files and symbols, generating a token-bounded, formatted repository map.
     */
    fun rankAndFormatMap(
        projectRoot: File,
        activeFile: File? = null,
        openFiles: List<File> = emptyList(),
        diagnostics: List<BuildDiagnostic> = emptyList(),
        userQuery: String? = null,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): String {
        val scannedFiles = scanProject(projectRoot)
        if (scannedFiles.isEmpty()) return ""

        val activeRelPath = activeFile?.relativeToOrNull(projectRoot)?.path?.replace('\\', '/')
        val openRelPaths = openFiles.mapNotNull { it.relativeToOrNull(projectRoot)?.path?.replace('\\', '/') }.toSet()

        // Normalize diagnostic file paths
        val diagRelPaths = diagnostics.mapNotNull { diag ->
            diag.filePath?.let { p ->
                val f = File(p)
                if (f.isAbsolute) f.relativeToOrNull(projectRoot)?.path?.replace('\\', '/') ?: f.name
                else p.replace('\\', '/')
            }
        }.toSet()

        // Extract identifier tokens mentioned in diagnostic messages
        val diagTokens = diagnostics.flatMap { diag ->
            Regex("""[A-Za-z_][A-Za-z0-9_]{3,}""").findAll(diag.message).map { it.value.lowercase() }
        }.toSet()

        // Extract query tokens
        val queryTokens = if (!userQuery.isNullOrBlank()) {
            Regex("""[A-Za-z_][A-Za-z0-9_]{3,}""").findAll(userQuery).map { it.value.lowercase() }.toSet()
        } else {
            emptySet()
        }

        // Rank files by relevance score
        val rankedFiles = scannedFiles.map { fileSym ->
            var score = 10 // base score
            val rel = fileSym.relativePath
            val fileNameLower = File(rel).name.lowercase()

            if (rel == activeRelPath) score += 1000
            if (rel in openRelPaths) score += 400
            if (rel in diagRelPaths) score += 600

            // Check if file contains symbols referenced in diagnostic errors
            if (fileSym.symbols.any { sym -> sym.name.lowercase() in diagTokens }) {
                score += 300
            }

            // Check if file name or symbols match user query
            if (queryTokens.any { tok -> fileNameLower.contains(tok) }) {
                score += 150
            }
            if (fileSym.symbols.any { sym -> sym.name.lowercase() in queryTokens }) {
                score += 100
            }

            // Boost key native headers / launcher entry points
            if (rel.endsWith(".h") || rel.endsWith(".hpp")) score += 20
            if (fileNameLower.contains("main") || fileNameLower.contains("menu") || fileNameLower.contains("setup")) score += 30

            fileSym to score
        }.sortedByDescending { it.second }

        // Bounded formatting
        val maxChars = maxTokens * CHARS_PER_ESTIMATED_TOKEN
        val sb = StringBuilder()
        sb.appendLine("=== REPOSITORY SYMBOL MAP (TOP DEFINITIONS) ===")

        var truncated = false
        for ((fileSym, _) in rankedFiles) {
            val rel = fileSym.relativePath
            val tag = when {
                rel == activeRelPath -> " (active)"
                rel in diagRelPaths -> " (diagnostic error)"
                rel in openRelPaths -> " (open tab)"
                else -> ""
            }

            val fileHeader = "$rel$tag:\n"
            if (sb.length + fileHeader.length > maxChars - 120) {
                truncated = true
                break
            }
            sb.append(fileHeader)

            // Prioritize definitions (classes, structs, functions, members) over includes/imports
            val sortedSymbols = fileSym.symbols.sortedBy { sym ->
                when (sym.kind) {
                    SymbolKind.NAMESPACE, SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.STRUCT, SymbolKind.ENUM -> 1
                    SymbolKind.FUNCTION -> 2
                    SymbolKind.FIELD, SymbolKind.PROPERTY, SymbolKind.CONSTANT, SymbolKind.VARIABLE -> 3
                    SymbolKind.MACRO -> 4
                    SymbolKind.INCLUDE -> 5
                    SymbolKind.IMPORT -> 6
                }
            }

            val visibleSymbols = sortedSymbols.take(12)
            for (sym in visibleSymbols) {
                val lineDesc = "  L${sym.line}: [${sym.kind.label}] ${sym.signature ?: sym.name}\n"
                if (sb.length + lineDesc.length > maxChars - 80) {
                    truncated = true
                    break
                }
                sb.append(lineDesc)
            }

            if (sortedSymbols.size > visibleSymbols.size) {
                val moreCount = sortedSymbols.size - visibleSymbols.size
                sb.appendLine("  ... (+$moreCount more symbols in $rel)")
            }

            sb.appendLine()
            if (truncated) break
        }

        if (truncated) {
            sb.appendLine("// ... [additional project symbols truncated to fit $maxTokens-token budget]")
        }

        return sb.toString().trim()
    }

    /**
     * Resolves exact or partial symbol definition locations across all project files,
     * supporting simple names (e.g. 'InitMenu', 'currentMode') and qualified names
     * (e.g. 'ModMenuController.currentMode', 'ModMenuController::InitMenu').
     */
    fun findSymbolDefinitions(
        projectRoot: File,
        symbolName: String,
        kind: SymbolKind? = null,
        exactMatch: Boolean = true
    ): List<Pair<String, RepoSymbol>> {
        val trimmed = symbolName.trim()
        if (trimmed.isEmpty()) return emptyList()

        val scannedFiles = scanProject(projectRoot)
        val results = mutableListOf<Pair<String, RepoSymbol>>()
        val normalizedQuery = trimmed.replace("::", ".")

        for (fileSym in scannedFiles) {
            for (sym in fileSym.symbols) {
                // Filter out includes and imports when searching for definitions
                if (sym.kind == SymbolKind.INCLUDE || sym.kind == SymbolKind.IMPORT) {
                    continue
                }

                if (kind != null && sym.kind != kind) {
                    continue
                }

                val symQName = sym.qualifiedName?.replace("::", ".")

                val matches = if (exactMatch) {
                    sym.name.equals(trimmed, ignoreCase = false) ||
                        (symQName != null && symQName.equals(normalizedQuery, ignoreCase = false))
                } else {
                    sym.name.contains(trimmed, ignoreCase = true) ||
                        (symQName != null && symQName.contains(normalizedQuery, ignoreCase = true))
                }

                if (matches) {
                    results.add(fileSym.relativePath to sym)
                }
            }
        }

        return results
    }

    private enum class CharCategory { CODE, COMMENT, STRING_LITERAL }

    /**
     * Line-level lexical classifier tracking block comments, line comments, and string/char literals.
     */
    private fun classifyLineCharacters(line: String, startInBlockComment: Boolean): Pair<Boolean, Array<CharCategory>> {
        var inBlock = startInBlockComment
        val categories = Array(line.length) { CharCategory.CODE }
        var i = 0
        var inString = false
        var inChar = false

        while (i < line.length) {
            if (inBlock) {
                categories[i] = CharCategory.COMMENT
                if (i + 1 < line.length && line[i] == '*' && line[i + 1] == '/') {
                    categories[i + 1] = CharCategory.COMMENT
                    inBlock = false
                    i += 2
                    continue
                }
                i++
                continue
            }

            if (inString) {
                categories[i] = CharCategory.STRING_LITERAL
                if (line[i] == '\\' && i + 1 < line.length) {
                    categories[i + 1] = CharCategory.STRING_LITERAL
                    i += 2
                    continue
                }
                if (line[i] == '"') {
                    inString = false
                }
                i++
                continue
            }

            if (inChar) {
                categories[i] = CharCategory.STRING_LITERAL
                if (line[i] == '\\' && i + 1 < line.length) {
                    categories[i + 1] = CharCategory.STRING_LITERAL
                    i += 2
                    continue
                }
                if (line[i] == '\'') {
                    inChar = false
                }
                i++
                continue
            }

            // Normal code: check for line comment //
            if (i + 1 < line.length && line[i] == '/' && line[i + 1] == '/') {
                for (j in i until line.length) {
                    categories[j] = CharCategory.COMMENT
                }
                break
            }

            // Normal code: check for block comment start /*
            if (i + 1 < line.length && line[i] == '/' && line[i + 1] == '*') {
                categories[i] = CharCategory.COMMENT
                categories[i + 1] = CharCategory.COMMENT
                inBlock = true
                i += 2
                continue
            }

            // Normal code: string start
            if (line[i] == '"') {
                categories[i] = CharCategory.STRING_LITERAL
                inString = true
                i++
                continue
            }

            // Normal code: char start
            if (line[i] == '\'') {
                categories[i] = CharCategory.STRING_LITERAL
                inChar = true
                i++
                continue
            }

            categories[i] = CharCategory.CODE
            i++
        }

        return inBlock to categories
    }

    /**
     * Finds cross-file references, usages, and call sites of a symbol across project files,
     * distinguishing definition sites from active call sites, commented-out occurrences, and string literals.
     */
    fun findSymbolReferences(
        projectRoot: File,
        symbolName: String,
        fileHint: String? = null,
        maxReferences: Int = 50
    ): List<SymbolReference> {
        val trimmed = symbolName.trim()
        if (trimmed.isEmpty()) return emptyList()

        val simpleName = trimmed.substringAfterLast('.').substringAfterLast("::")
        if (simpleName.isEmpty()) return emptyList()

        val wordBoundaryRegex = Regex("""\b${Regex.escape(simpleName)}\b""")
        val scannedFiles = scanProject(projectRoot)
        val references = mutableListOf<SymbolReference>()

        val sortedFiles = if (!fileHint.isNullOrBlank()) {
            val hintClean = fileHint.trim().lowercase().replace('\\', '/')
            scannedFiles.sortedByDescending {
                it.relativePath.lowercase().contains(hintClean)
            }
        } else {
            scannedFiles
        }

        for (fileSym in sortedFiles) {
            val file = File(projectRoot, fileSym.relativePath)
            if (!file.exists() || !file.isFile) continue

            val lines = try {
                file.readLines()
            } catch (_: Exception) {
                continue
            }

            var inBlockComment = false

            lines.forEachIndexed { idx, rawLine ->
                val lineNum = idx + 1
                val (nextInBlock, categories) = classifyLineCharacters(rawLine, inBlockComment)
                inBlockComment = nextInBlock

                val matches = wordBoundaryRegex.findAll(rawLine).toList()
                if (matches.isNotEmpty()) {
                    // Check if this line is a definition of simpleName
                    val defSymbol = fileSym.symbols.firstOrNull {
                        it.line == lineNum && it.name.equals(simpleName, ignoreCase = false)
                    }

                    // Find innermost enclosing scope for this line
                    val enclosing = fileSym.symbols
                        .filter {
                            it.line <= lineNum && lineNum <= it.endLine &&
                                it.kind in setOf(
                                    SymbolKind.CLASS,
                                    SymbolKind.STRUCT,
                                    SymbolKind.INTERFACE,
                                    SymbolKind.ENUM,
                                    SymbolKind.FUNCTION,
                                    SymbolKind.NAMESPACE
                                )
                        }
                        .minByOrNull { it.endLine - it.line }
                        ?.name

                    for (match in matches) {
                        val colIndex = match.range.first
                        val charCategory = if (colIndex < categories.size) categories[colIndex] else CharCategory.CODE

                        val isDef = defSymbol != null
                        val refCategory = when {
                            isDef -> ReferenceCategory.DEFINITION
                            charCategory == CharCategory.COMMENT -> ReferenceCategory.COMMENT
                            charCategory == CharCategory.STRING_LITERAL -> ReferenceCategory.STRING_LITERAL
                            else -> ReferenceCategory.ACTIVE_REFERENCE
                        }

                        references.add(
                            SymbolReference(
                                symbolName = simpleName,
                                relativePath = fileSym.relativePath,
                                line = lineNum,
                                column = colIndex + 1,
                                lineContent = rawLine.trim(),
                                isDefinition = isDef,
                                enclosingScope = enclosing,
                                symbolKind = defSymbol?.kind,
                                category = refCategory
                            )
                        )

                        if (references.size >= maxReferences * 2) {
                            return@forEachIndexed
                        }
                    }
                }
            }

            if (references.size >= maxReferences * 2) {
                break
            }
        }

        return references.sortedWith(
            compareByDescending<SymbolReference> { it.category == ReferenceCategory.DEFINITION }
                .thenByDescending { it.category == ReferenceCategory.ACTIVE_REFERENCE }
                .thenByDescending { ref ->
                    if (!fileHint.isNullOrBlank()) ref.relativePath.contains(fileHint, ignoreCase = true) else false
                }
        ).take(maxReferences)
    }

    /**
     * Returns all symbols declared in a specific project file, ordered by line number.
     */
    fun getFileOutline(projectRoot: File, targetFile: File): List<RepoSymbol> {
        val fileSym = getOrScanFile(projectRoot, targetFile) ?: return emptyList()
        return fileSym.symbols.sortedBy { it.line }
    }

    /**
     * Retrieves the inheritance hierarchy for a specific type (class, interface, struct),
     * returning its direct supertypes, known subtypes across the project, and any method overrides.
     */
    fun getTypeHierarchy(projectRoot: File, typeName: String): TypeHierarchyResult? {
        val trimmed = typeName.trim()
        if (trimmed.isEmpty()) return null
        val simpleName = trimmed.substringAfterLast('.').substringAfterLast("::")
        val scannedFiles = scanProject(projectRoot)

        var matchedInheritance: TypeInheritance? = null
        for (f in scannedFiles) {
            val found = f.typeInheritance.firstOrNull {
                it.typeName == simpleName || it.qualifiedName == trimmed || it.typeName == trimmed
            }
            if (found != null) {
                matchedInheritance = found
                break
            }
        }

        val targetName = matchedInheritance?.typeName ?: simpleName
        val superTypes = matchedInheritance?.superTypes ?: emptyList()
        val relPath = matchedInheritance?.relativePath ?: ""
        val kind = matchedInheritance?.kind ?: SymbolKind.CLASS

        // Subtypes: any type in the project declaring targetName in its superTypes
        val subTypes = mutableListOf<String>()
        for (f in scannedFiles) {
            for (ti in f.typeInheritance) {
                if (ti.superTypes.any { it == targetName || it.endsWith(".$targetName") || it.endsWith("::$targetName") }) {
                    subTypes.add(ti.typeName)
                    if (ti.qualifiedName != null && ti.qualifiedName != ti.typeName) {
                        subTypes.add(ti.qualifiedName)
                    }
                }
            }
        }

        // Overrides: any method overrides declared in targetName or its subtypes
        val relevantTypes = (setOf(targetName) + subTypes.map { it.substringAfterLast('.').substringAfterLast("::") }).toSet()
        val overrides = mutableListOf<MethodOverride>()
        for (f in scannedFiles) {
            for (ov in f.overrides) {
                if (ov.enclosingType in relevantTypes) {
                    overrides.add(ov)
                }
            }
        }

        if (matchedInheritance == null && subTypes.isEmpty()) {
            return null
        }

        return TypeHierarchyResult(
            typeName = targetName,
            relativePath = relPath,
            kind = kind,
            superTypes = superTypes,
            subTypes = subTypes.distinct(),
            overrides = overrides.distinctBy { "${it.relativePath}:${it.line}:${it.methodName}" }
        )
    }

    /**
     * Finds direct supertypes for a given type name.
     */
    fun findSupertypes(projectRoot: File, typeName: String): List<String> {
        return getTypeHierarchy(projectRoot, typeName)?.superTypes ?: emptyList()
    }

    /**
     * Finds all known subtypes/implementations for a given type name.
     */
    fun findSubtypes(projectRoot: File, typeName: String): List<String> {
        val trimmed = typeName.trim().substringAfterLast('.').substringAfterLast("::")
        val scannedFiles = scanProject(projectRoot)
        val subtypes = mutableListOf<String>()
        for (f in scannedFiles) {
            for (ti in f.typeInheritance) {
                if (ti.superTypes.any { it == trimmed || it.endsWith(".$trimmed") || it.endsWith("::$trimmed") }) {
                    subtypes.add(ti.typeName)
                    if (ti.qualifiedName != null && ti.qualifiedName != ti.typeName) {
                        subtypes.add(ti.qualifiedName)
                    }
                }
            }
        }
        return subtypes.distinct()
    }

    /**
     * Finds method overrides across the project, optionally filtered by enclosing type or method name.
     */
    fun findOverrides(projectRoot: File, typeName: String? = null, methodName: String? = null): List<MethodOverride> {
        val scannedFiles = scanProject(projectRoot)
        val cleanType = typeName?.trim()?.takeIf { it.isNotEmpty() }?.substringAfterLast('.')?.substringAfterLast("::")
        val cleanMethod = methodName?.trim()?.takeIf { it.isNotEmpty() }
        val overrides = mutableListOf<MethodOverride>()
        for (f in scannedFiles) {
            for (ov in f.overrides) {
                val matchesType = cleanType == null || ov.enclosingType.equals(cleanType, ignoreCase = false)
                val matchesMethod = cleanMethod == null || ov.methodName.equals(cleanMethod, ignoreCase = false)
                if (matchesType && matchesMethod) {
                    overrides.add(ov)
                }
            }
        }
        return overrides.distinctBy { "${it.relativePath}:${it.line}:${it.methodName}" }
    }

    /**
     * Inspects compiler diagnostics for missing or undeclared symbols (e.g. "'X' was not declared",
     * "cannot find symbol class X", "undefined reference to 'X'"), discovers their definitions
     * across other project files, and produces targeted contextual repair hints with code snippets.
     */
    fun resolveDiagnosticMissingSymbols(
        projectRoot: File,
        diagnostics: List<BuildDiagnostic>,
        maxHints: Int = 4
    ): List<String> {
        val missingIdentifierPatterns = listOf(
            Regex("""'([A-Za-z_][A-Za-z0-9_]*)' was not declared in this scope"""),
            Regex("""use of undeclared identifier '([A-Za-z_][A-Za-z0-9_]*)'"""),
            Regex("""no member named '([A-Za-z_][A-Za-z0-9_]*)' in '"""),
            Regex("""has no member named '([A-Za-z_][A-Za-z0-9_]*)'"""),
            Regex("""cannot find symbol\s+(?:class|method|variable)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""undefined reference to `?([A-Za-z_][A-Za-z0-9_]*)'?"""),
            Regex("""unknown type name '([A-Za-z_][A-Za-z0-9_]*)'"""),
            Regex("""unresolved reference:?\s*([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""cannot call member function\s+.*'([A-Za-z_][A-Za-z0-9_]*)'"""),
            Regex("""'([A-Za-z_][A-Za-z0-9_]*)' is not a member of '""")
        )

        val candidateNames = mutableSetOf<String>()
        for (diag in diagnostics) {
            val msg = diag.message
            for (pattern in missingIdentifierPatterns) {
                pattern.findAll(msg).forEach { match ->
                    val identifier = match.groupValues[1]
                    if (identifier.length >= 2) {
                        candidateNames.add(identifier)
                    }
                }
            }
        }

        if (candidateNames.isEmpty()) return emptyList()

        val hints = mutableListOf<String>()
        val seenSignatures = mutableSetOf<String>()

        for (name in candidateNames) {
            if (hints.size >= maxHints) break
            val definitions = findSymbolDefinitions(projectRoot, name, exactMatch = true)
            for ((relPath, sym) in definitions) {
                val key = "$relPath:${sym.line}:${sym.name}"
                if (key !in seenSignatures) {
                    seenSignatures.add(key)
                    val sig = sym.signature ?: sym.name

                    // Fetch 3-line snippet surrounding definition for immediate agent context
                    val snippet = try {
                        val srcFile = File(projectRoot, relPath)
                        if (srcFile.exists()) {
                            val allLines = srcFile.readLines()
                            val start = (sym.line - 1).coerceAtLeast(1)
                            val end = (sym.line + 1).coerceAtMost(allLines.size)
                            (start..end).joinToString("\n") { lNum ->
                                val marker = if (lNum == sym.line) ">" else " "
                                "$marker L$lNum: ${allLines[lNum - 1]}"
                            }
                        } else null
                    } catch (_: Exception) {
                        null
                    }

                    val snippetStr = if (!snippet.isNullOrBlank()) "\n```\n$snippet\n```" else ""
                    hints.add("• Symbol '$name' is defined in `$relPath:${sym.line}`: [${sym.kind.label}] `$sig`$snippetStr")
                    if (hints.size >= maxHints) break
                }
            }
        }

        return hints
    }
}
