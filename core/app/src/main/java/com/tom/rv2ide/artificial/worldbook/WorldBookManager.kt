package com.tom.rv2ide.artificial.worldbook

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.regex.Pattern

class WorldBookManager private constructor(context: Context) {

    private val appContext = context.applicationContext ?: context
    private val worldBookDir = File(appContext.filesDir, "world_books")
    private val worldBookFile = File(worldBookDir, "world_books.json")

    private val worldBooks = mutableListOf<WorldBook>()

    init {
        loadFromDisk()
    }

    @Synchronized
    fun getAllWorldBooks(): List<WorldBook> = worldBooks.toList()

    @Synchronized
    fun saveWorldBook(book: WorldBook) {
        val idx = worldBooks.indexOfFirst { it.id == book.id }
        if (idx >= 0) {
            worldBooks[idx] = book
        } else {
            worldBooks.add(book)
        }
        saveToDisk()
    }

    @Synchronized
    fun deleteWorldBook(id: String): Boolean {
        val removed = worldBooks.removeAll { it.id == id }
        if (removed) saveToDisk()
        return removed
    }

    /**
     * Evaluates all enabled World Books against [userPrompt] and [recentHistory].
     * Returns matching entries sorted by priority (highest priority first).
     */
    @Synchronized
    fun evaluateActiveEntries(
        userPrompt: String,
        recentHistory: List<String> = emptyList(),
        targetPosition: WorldBookPosition? = null
    ): List<WorldBookEntry> {
        val scanText = buildString {
            append(userPrompt).append('\n')
            recentHistory.takeLast(3).forEach { append(it).append('\n') }
        }

        val triggered = mutableListOf<WorldBookEntry>()

        for (book in worldBooks) {
            if (!book.enabled) continue
            for (entry in book.entries) {
                if (!entry.enabled || entry.content.isBlank()) continue
                if (targetPosition != null && entry.position != targetPosition) continue

                if (entry.constantActive) {
                    triggered.add(entry)
                    continue
                }

                if (matchesKeywords(scanText, entry)) {
                    triggered.add(entry)
                }
            }
        }

        return triggered.sortedByDescending { it.priority }
    }

    private fun matchesKeywords(text: String, entry: WorldBookEntry): Boolean {
        if (entry.keywords.isEmpty()) return false

        for (kw in entry.keywords) {
            val cleanKw = kw.trim()
            if (cleanKw.isEmpty()) continue

            if (entry.useRegex) {
                try {
                    val flags = if (entry.caseSensitive) 0 else Pattern.CASE_INSENSITIVE
                    val pattern = Pattern.compile(cleanKw, flags)
                    if (pattern.matcher(text).find()) return true
                } catch (_: Exception) {
                    // Fall back to plain substring
                    if (text.contains(cleanKw, ignoreCase = !entry.caseSensitive)) return true
                }
            } else {
                if (text.contains(cleanKw, ignoreCase = !entry.caseSensitive)) return true
            }
        }

        return false
    }

    /**
     * Constructs the contextual World Book lore block to inject into the LLM system prompt.
     */
    fun buildWorldBookInjection(
        userPrompt: String,
        recentHistory: List<String> = emptyList(),
        position: WorldBookPosition = WorldBookPosition.AFTER_SYSTEM_PROMPT
    ): String {
        val activeEntries = evaluateActiveEntries(userPrompt, recentHistory, position)
        if (activeEntries.isEmpty()) return ""

        return buildString {
            append("<world_book_context position=\"${position.id}\">\n")
            append("The following specialized domain guidelines and technical rules apply to this session:\n\n")
            for (entry in activeEntries) {
                append("### [Rule: ${entry.name}]\n")
                append(entry.content.trim()).append("\n\n")
            }
            append("</world_book_context>\n\n")
        }
    }

    private fun loadFromDisk() {
        try {
            if (!worldBookDir.exists()) worldBookDir.mkdirs()

            if (worldBookFile.exists()) {
                val jsonStr = worldBookFile.readText()
                val arr = JSONArray(jsonStr)
                worldBooks.clear()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        worldBooks.add(WorldBook.fromJson(obj))
                    }
                }
            }

            // Seed default domain presets if empty
            if (worldBooks.isEmpty()) {
                seedDefaultWorldBooks()
                saveToDisk()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load world books from disk", e)
        }
    }

    private fun seedDefaultWorldBooks() {
        val ndkBook = WorldBook(
            id = "preset_ndk_modding",
            name = "Android NDK & Native Modding Guide",
            description = "Specialized rules for JNI, NDK, Dobby hooks, libil2cpp, and memory patching",
            enabled = true,
            entries = listOf(
                WorldBookEntry(
                    id = "entry_dobby_hooks",
                    name = "Dobby & Inline Hooking Conventions",
                    enabled = true,
                    priority = 110,
                    content = "When writing C/C++ native hooks with DobbyHook or Substrate:\n" +
                        "1. Always declare original function pointers with correct calling conventions.\n" +
                        "2. Ensure mprotect / memory protection is accounted for when patching memory bytes.\n" +
                        "3. Use safe null checks on base module addresses (e.g. dlopen/dlsym or /proc/self/maps).",
                    keywords = listOf("hook", "dobby", "substrate", "libil2cpp", "offset", "hex patch", "mprotect")
                ),
                WorldBookEntry(
                    id = "entry_jni_lifecycle",
                    name = "JNI Memory & Local Reference Safety",
                    enabled = true,
                    priority = 100,
                    content = "In JNI code:\n" +
                        "1. DeleteLocalRef for loops allocating Java strings or objects.\n" +
                        "2. Use GetStringUTFChars / ReleaseStringUTFChars properly.\n" +
                        "3. Attach current thread using JavaVM->AttachCurrentThread before calling JNI from native pthreads.",
                    keywords = listOf("jni", "JNIEnv", "JavaVM", "extern \"C\"", "FindClass", "GetMethodID")
                )
            )
        )

        val composeBook = WorldBook(
            id = "preset_compose_ui",
            name = "Jetpack Compose Modern UI Rules",
            description = "Architectural rules for Compose State, Flow, and Material 3 layouts",
            enabled = true,
            entries = listOf(
                WorldBookEntry(
                    id = "entry_compose_state",
                    name = "Compose Recomposition & State Hoisting",
                    enabled = true,
                    priority = 100,
                    content = "For Jetpack Compose:\n" +
                        "1. Never execute heavy I/O or side-effects inside @Composable without LaunchedEffect.\n" +
                        "2. Hoist state upwards to callers: pass values down, propagate events up via lambdas.\n" +
                        "3. Use remember { derivedStateOf { ... } } for computed states that depend on rapidly changing inputs.",
                    keywords = listOf("@Composable", "Modifier", "LaunchedEffect", "rememberCoroutineScope", "StateFlow")
                )
            )
        )

        worldBooks.add(ndkBook)
        worldBooks.add(composeBook)
    }

    private fun saveToDisk() {
        try {
            if (!worldBookDir.exists()) worldBookDir.mkdirs()

            val arr = JSONArray()
            worldBooks.forEach { arr.put(it.toJson()) }
            worldBookFile.writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save world books to disk", e)
        }
    }

    companion object {
        private const val TAG = "WorldBookManager"

        @Volatile
        private var instance: WorldBookManager? = null

        fun getInstance(context: Context): WorldBookManager {
            return instance ?: synchronized(this) {
                instance ?: WorldBookManager(context).also { instance = it }
            }
        }
    }
}
