package com.tom.rv2ide.artificial.tools

/**
 * Classifies terminal commands to determine whether they are pure inspections,
 * mutate filesystem/project state, invoke builds, or run test suites.
 *
 * Implements conservative classification:
 * 1. Pure inspection is recognized ONLY when the full invocation satisfies strict safety rules.
 * 2. Unquoted redirection (>, >>, &>) and process substitution (<(), >()) mutate files.
 * 3. Chained commands (&&, ||, ;, |) are evaluated across all stages.
 * 4. Shell wrappers (sh -c, bash -c) are unwrapped and evaluated.
 * 5. Script interpreters (python, node, perl, ruby) are treated conservatively as mutating.
 * 6. Unknown or ambiguous commands are NEVER assumed read-only.
 * 7. Build and test commands are classified accurately without confusing inspection commands (e.g. cat build.gradle).
 */
object TerminalCommandClassifier {

    private val PURE_INSPECTION_BINARIES = setOf(
        "ls", "dir", "cat", "head", "tail", "grep", "egrep", "fgrep",
        "find", "pwd", "which", "where", "whoami", "uname", "df", "du",
        "file", "stat", "readlink", "echo", "printf", "env", "printenv",
        "wc", "cut", "sort", "uniq", "diff", "cmp", "less", "more"
    )

    private val MUTATING_BINARIES = setOf(
        "rm", "rmdir", "mkdir", "touch", "cp", "mv", "sed", "awk", "dd",
        "chmod", "chown", "truncate", "patch", "tar", "unzip", "gzip", "gunzip",
        "bzip2", "zip", "kill", "pkill", "tee", "install"
    )

    private val SCRIPT_INTERPRETERS = setOf(
        "python", "python3", "python2", "node", "nodejs", "perl", "ruby", "php", "lua", "rscript"
    )

    private val PACKAGE_MANAGERS = setOf(
        "npm", "yarn", "pnpm", "pip", "pip3", "cargo", "composer", "gem", "bundle", "apt", "apt-get", "dpkg", "pacman", "brew"
    )

    private val READ_ONLY_GIT_SUBCOMMANDS = setOf(
        "status", "diff", "log", "show", "rev-parse", "describe", "cat-file",
        "ls-files", "ls-tree", "grep", "shortlog", "blame", "check-ignore",
        "check-ref-format", "version"
    )

    /**
     * Determines whether a command should be treated as potentially mutating project,
     * working-tree, or filesystem state.
     * Inspection-only commands return false; all commands with redirection, mutating binaries,
     * script interpreters, or unknown binaries return true.
     */
    fun isMutatingOrBuildCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false

        // Check for unquoted output redirection (>, >>, &>, 1>, 2>) or command substitution
        if (hasUnquotedRedirectionOrSubshell(trimmed)) {
            return true
        }

        // Split by unquoted command chaining operators (&&, ||, ;, |)
        val stages = splitUnquotedChainsAndPipes(trimmed)
        for (stage in stages) {
            val sub = stage.trim()
            if (sub.isEmpty()) continue
            if (isSubcommandMutatingOrAmbiguous(sub)) {
                return true
            }
        }
        return false
    }

    /**
     * Identifies if a subcommand (single command invocation without unquoted chaining/pipes)
     * mutates state or is ambiguous/unknown.
     */
    private fun isSubcommandMutatingOrAmbiguous(command: String): Boolean {
        val tokens = tokenizeCommand(command)
        if (tokens.isEmpty()) return false

        val firstToken = tokens[0].substringAfterLast("/").substringAfterLast("\\").lowercase()

        // Shell wrappers: sh -c "...", bash -c "..."
        if (firstToken in setOf("sh", "bash", "zsh", "dash", "ksh", "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh")) {
            val cIdx = tokens.indexOfFirst { it == "-c" || it.equals("/c", ignoreCase = true) || it.equals("-command", ignoreCase = true) }
            if (cIdx != -1 && cIdx + 1 < tokens.size) {
                val innerCmd = tokens[cIdx + 1]
                return isMutatingOrBuildCommand(innerCmd)
            }
            // Running a script without -c (e.g. sh script.sh): conservatively mutating
            if (tokens.size > 1 && !tokens[1].startsWith("-")) {
                return true
            }
            return true // Ambiguous shell invocation
        }

        // Script interpreters (python, node, etc.)
        if (firstToken in SCRIPT_INTERPRETERS) {
            // Safe inspection: python --version, node -v
            if (tokens.size == 2 && tokens[1] in setOf("--version", "-v", "--help", "-h")) {
                return false
            }
            return true // Script execution can mutate state
        }

        // Package managers (npm, pip, cargo, etc.)
        if (firstToken in PACKAGE_MANAGERS) {
            val subCmd = tokens.getOrNull(1)?.lowercase() ?: ""
            val readOnlyPkg = setOf("list", "ls", "show", "view", "info", "outdated", "audit", "--version", "-v", "--help", "-h")
            if (subCmd in readOnlyPkg) {
                return false
            }
            return true
        }

        // Git commands
        if (firstToken == "git") {
            val subCmd = tokens.getOrNull(1)?.lowercase() ?: ""
            if (subCmd in READ_ONLY_GIT_SUBCOMMANDS) {
                return false
            }
            if (subCmd == "branch") {
                val hasMutatingFlag = tokens.any { it in setOf("-d", "-D", "-m", "-M", "--delete", "--move") }
                val nonOptionArgs = tokens.drop(2).filter { !it.startsWith("-") }
                return hasMutatingFlag || nonOptionArgs.isNotEmpty()
            }
            if (subCmd == "tag") {
                val hasDelete = tokens.any { it in setOf("-d", "--delete") }
                val nonOptionArgs = tokens.drop(2).filter { !it.startsWith("-") }
                return hasDelete || nonOptionArgs.isNotEmpty()
            }
            if (subCmd == "remote") {
                val nonOptionArgs = tokens.drop(2).filter { !it.startsWith("-") }
                val remoteSub = nonOptionArgs.firstOrNull()?.lowercase()
                return remoteSub in setOf("add", "remove", "rm", "set-url", "rename")
            }
            if (subCmd == "config") {
                val isGet = tokens.any { it in setOf("--get", "--get-all", "--list", "-l") }
                return !isGet
            }
            // All other git commands mutate working tree or repo state (checkout, switch, commit, reset, pull, merge, etc.)
            return true
        }

        // xargs: check the target utility
        if (firstToken == "xargs") {
            val targetTokens = tokens.drop(1).filter { !it.startsWith("-") }
            if (targetTokens.isEmpty()) return true
            val targetExe = targetTokens[0].substringAfterLast("/").substringAfterLast("\\").lowercase()
            return targetExe !in PURE_INSPECTION_BINARIES || isSubcommandMutatingOrAmbiguous(targetTokens.joinToString(" "))
        }

        // Explicit mutating binaries
        if (firstToken in MUTATING_BINARIES) {
            return true
        }

        // Build tools
        if (isBuildCommand(command)) {
            return true
        }

        // Pure inspection binaries: check for mutating options
        if (firstToken in PURE_INSPECTION_BINARIES) {
            if (firstToken == "find") {
                // find with -delete, -exec, -execdir
                if (tokens.any { it in setOf("-delete", "-exec", "-execdir", "-ok") }) {
                    return true
                }
            } else if (firstToken == "sort") {
                // sort -o writes to file
                if (tokens.any { it == "-o" || it.startsWith("--output") }) {
                    return true
                }
            }
            return false
        }

        // Unknown or custom binary: rule 3 (never assume read-only)
        return true
    }

    /**
     * Determines whether a command invokes a build process (e.g. gradle, make, cargo build).
     */
    fun isBuildCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false
        val firstToken = trimmed.substringBefore(" ").substringAfterLast("/")?.substringAfterLast("\\")?.lowercase() ?: ""
        if (firstToken in PURE_INSPECTION_BINARIES) {
            return false
        }
        val lower = command.lowercase()
        if (firstToken in setOf("gradle", "gradlew")) return true
        if (lower.startsWith("./gradlew") || lower.startsWith("gradlew") || lower.startsWith("gradle") ||
            trimmed.contains("gradlew ") || trimmed.contains("gradle ")) {
            return true
        }
        if (firstToken in setOf("make", "ninja", "mvn", "ant")) return true
        if (firstToken == "cmake" && lower.contains("--build")) return true
        if (firstToken == "cargo" && (lower.contains("cargo build") || lower.contains("cargo check"))) return true
        if (firstToken in setOf("npm", "yarn", "pnpm") && (lower.contains("run build") || lower.contains("build"))) return true
        return false
    }

    /**
     * Determines whether a command invokes a test runner (e.g. ./gradlew test, pytest, npm test).
     */
    fun isTestCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false
        val firstToken = trimmed.substringBefore(" ").substringAfterLast("/")?.substringAfterLast("\\")?.lowercase() ?: ""
        if (firstToken in PURE_INSPECTION_BINARIES) {
            return false
        }
        val lower = command.lowercase()
        if (firstToken in setOf("pytest", "ctest", "jest", "vitest", "mocha")) return true
        if (firstToken in setOf("npm", "yarn", "pnpm") && lower.contains("test")) return true
        if (firstToken == "cargo" && lower.contains("cargo test")) return true
        return isBuildCommand(command) && (lower.contains("test") || lower.contains("check"))
    }

    /**
     * Checks if unquoted output redirection (>, >>, &>, >|, 1>, 2>) or command substitution occurs.
     */
    internal fun hasUnquotedRedirectionOrSubshell(command: String): Boolean {
        var inSingleQuote = false
        var inDoubleQuote = false
        var isEscaped = false

        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (isEscaped) {
                isEscaped = false
                i++
                continue
            }
            if (c == '\\') {
                isEscaped = true
                i++
                continue
            }
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                i++
                continue
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                i++
                continue
            }

            if (!inSingleQuote && !inDoubleQuote) {
                // Command substitution `...` or $(...)
                if (c == '`') return true
                if (c == '$' && i + 1 < command.length && command[i + 1] == '(') return true

                // Process substitution <(...) or >(...)
                if ((c == '<' || c == '>') && i + 1 < command.length && command[i + 1] == '(') return true

                // Redirection operators: >, >>, &>, >|
                if (c == '>') {
                    // Check if it's 2>&1 or >&2 (pure descriptor duplication)
                    val isFdDup = (i + 2 < command.length && command.substring(i, i + 3) == ">&1") ||
                                  (i + 2 < command.length && command.substring(i, i + 3) == ">&2")
                    if (!isFdDup) {
                        return true
                    }
                }
            }
            i++
        }
        return false
    }

    /**
     * Splits command string by unquoted chained operators (&&, ||, ;, |).
     */
    internal fun splitUnquotedChainsAndPipes(command: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var isEscaped = false

        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (isEscaped) {
                isEscaped = false
                current.append(c)
                i++
                continue
            }
            if (c == '\\') {
                isEscaped = true
                current.append(c)
                i++
                continue
            }
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                current.append(c)
                i++
                continue
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                current.append(c)
                i++
                continue
            }

            if (!inSingleQuote && !inDoubleQuote) {
                if (c == ';') {
                    result.add(current.toString())
                    current.clear()
                    i++
                    continue
                }
                if (c == '&' && i + 1 < command.length && command[i + 1] == '&') {
                    result.add(current.toString())
                    current.clear()
                    i += 2
                    continue
                }
                if (c == '|' && i + 1 < command.length && command[i + 1] == '|') {
                    result.add(current.toString())
                    current.clear()
                    i += 2
                    continue
                }
                if (c == '|') {
                    result.add(current.toString())
                    current.clear()
                    i++
                    continue
                }
            }
            current.append(c)
            i++
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    /**
     * Tokenizes a command line into arguments, respecting single and double quotes.
     */
    internal fun tokenizeCommand(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var isEscaped = false

        for (c in command) {
            if (isEscaped) {
                isEscaped = false
                current.append(c)
                continue
            }
            if (c == '\\') {
                isEscaped = true
                continue
            }
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                continue
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                continue
            }
            if (c.isWhitespace() && !inSingleQuote && !inDoubleQuote) {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.clear()
                }
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return tokens
    }
}
