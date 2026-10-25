/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.tom.rv2ide.artificial.parser

import android.util.Log

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

class SnippetParser {
    
    private val TAG = "SnippetParser"
    
    /**
     * Removes code block markers (```) from the response
     * Handles both single and multi-line code blocks
     */
    fun removeCodeBlockMarkers(text: String): String {
        var cleaned = text.trim()
        
        // Remove opening code block with language identifier (e.g., ```kotlin, ```java, ```xml)
        cleaned = cleaned.replace(Regex("^```\\w*\\n?"), "")
        
        // Remove closing code block
        cleaned = cleaned.replace(Regex("\\n?```$"), "")
        
        // Remove any remaining standalone ``` markers
        cleaned = cleaned.replace("```", "")
        
        return cleaned.trim()
    }
    
    /**
     * Extracts code from markdown code blocks
     */
    fun extractCode(text: String): String {
        val codeBlockRegex = Regex("```[\\w]*\\n([\\s\\S]*?)```")
        val matches = codeBlockRegex.findAll(text)
        
        return if (matches.any()) {
            matches.joinToString("\n\n") { it.groupValues[1].trim() }
        } else {
            text.trim()
        }
    }
    
    /**
     * Parse response and extract multiple code snippets
     */
    fun parseMultipleSnippets(text: String): List<CodeSnippet> {
        val snippets = mutableListOf<CodeSnippet>()
        val codeBlockRegex = Regex("```([\\w]*)\\n([\\s\\S]*?)```")
        
        codeBlockRegex.findAll(text).forEach { match ->
            val language = match.groupValues[1].ifEmpty { "unknown" }
            val code = match.groupValues[2].trim()
            snippets.add(CodeSnippet(language, code))
        }
        
        return snippets
    }
    
    /**
     * Clean and prepare code for editor
     */
    fun prepareForEditor(text: String): String {
        return removeCodeBlockMarkers(text)
            .replace("\r\n", "\n") // Normalize line endings
            .trimIndent() // Remove common leading whitespace
    }
    
    /**
     * Clean file content from AI response - removes markers and metadata
     */
    fun cleanFileContent(content: String): String {
        Log.d(TAG, "=== CLEANING CONTENT ===")
        Log.d(TAG, "Original length: ${content.length}")
        Log.d(TAG, "First 200 chars: ${content.take(200)}")
        
        var cleaned = content.trim()
        
        // Remove FILE_TO_MODIFY line if present
        if (cleaned.startsWith("FILE_TO_MODIFY:")) {
            val lines = cleaned.lines()
            cleaned = lines.drop(1).joinToString("\n")
            Log.d(TAG, "Removed FILE_TO_MODIFY line")
        }
        
        // Remove CONTENT: marker if present
        if (cleaned.startsWith("CONTENT:")) {
            cleaned = cleaned.removePrefix("CONTENT:").trim()
            Log.d(TAG, "Removed CONTENT: marker")
        }
        
        // Remove code block markers
        cleaned = removeCodeBlockMarkers(cleaned)
        Log.d(TAG, "After removing code blocks: ${cleaned.length} chars")
        
        // AGGRESSIVE: Remove ALL text after code ends
        cleaned = removeAllTextAfterCode(cleaned)
        Log.d(TAG, "After removing trailing text: ${cleaned.length} chars")
        
        // Remove any explanatory text before package/plugin/root declaration
        cleaned = removeLeadingExplanations(cleaned)
        Log.d(TAG, "After removing leading text: ${cleaned.length} chars")
        
        Log.d(TAG, "Final first 200 chars: ${cleaned.take(200)}")
        Log.d(TAG, "Final last 200 chars: ${cleaned.takeLast(200)}")
        
        return cleaned.trim()
    }
    
    /**
     * AGGRESSIVE: Remove ALL text after the code actually ends
     */
    private fun removeAllTextAfterCode(content: String): String {
        val lines = content.lines().toMutableList()
        
        // Find the last line that looks like actual code
        var lastCodeLineIndex = -1
        
        for (i in lines.indices.reversed()) {
            val line = lines[i].trim()
            
            // Skip empty lines at the end
            if (line.isEmpty()) continue
            
            // Check if this is actual code
            if (isActualCode(line)) {
                lastCodeLineIndex = i
                Log.d(TAG, "Last code line at index $i: $line")
                break
            } else {
                Log.d(TAG, "Skipping non-code line at $i: $line")
            }
        }
        
        // If we found code, return only up to that line
        if (lastCodeLineIndex >= 0) {
            return lines.subList(0, lastCodeLineIndex + 1).joinToString("\n")
        }
        
        return content
    }
    
    /**
     * Check if a line is actual code (not explanation)
     */
    private fun isActualCode(line: String): Boolean {
        // Empty lines are not code
        if (line.isEmpty()) return false
        
        // Lines that start with explanation markers are NOT code
        val explanationStarters = listOf(
            "**Reasoning:**", "**Explanation:**", "**Note:**",
            "Reasoning:", "Explanation:", "Note:",
            "Next,", "Then,", "Now,", "After",
            "This will", "This adds", "This creates",
            "I will", "I'll", "Let me", "Here's",
            "The above", "The code", "Make sure",
            "To use", "You can", "Simply"
        )
        
        if (explanationStarters.any { line.startsWith(it, ignoreCase = true) }) {
            return false
        }
        
        // Code ending patterns (these ARE code)
        val codeEndings = listOf(
            ">",           // XML closing tag
            "/>",          // Self-closing XML tag
            "</manifest>", "</application>", "</activity>", "</layout>",
            "</LinearLayout>", "</RelativeLayout>", "</ConstraintLayout>",
            "</resources>", "</string>", "</color>",
            "}",           // Kotlin/Java closing brace
            ");",          // Statement with semicolon
            ")",           // Closing parenthesis
            ";",           // Semicolon
            "\"",          // String literal
        )
        
        // If line ends with code pattern, it IS code
        if (codeEndings.any { line.endsWith(it) }) {
            return true
        }
        
        // Lines with code-like content
        val codePatterns = listOf(
            "android:", "xmlns:", "app:", "tools:",  // XML attributes
            "package ", "import ", "class ", "fun ", "val ", "var ",  // Kotlin
            "public ", "private ", "protected ",  // Java/Kotlin modifiers
            "<?xml", "<!DOCTYPE",  // XML declarations
            "implementation(", "dependencies {",  // Gradle
            "    android:", "    app:",  // Indented XML
            "#include", "#pragma", "#define", "#ifdef", "#ifndef", "#endif", "#if",  // C/C++
            "extern \"C\"", "using namespace", "typedef ", "struct ", "enum "
        )
        
        return codePatterns.any { line.contains(it) }
    }
    
    /**
     * Remove leading explanatory text before the actual code starts
     */
    private fun removeLeadingExplanations(content: String): String {
        val lines = content.lines()
        
        // Find where the actual code starts
        var codeStartIndex = -1
        
        for (i in lines.indices) {
            val line = lines[i].trim()
            
            // Skip empty lines
            if (line.isEmpty()) continue
            
            // Check for common file starting patterns
            if (line.startsWith("package ") ||
                line.startsWith("import ") ||
                line.startsWith("plugins {") ||
                line.startsWith("<?xml") ||
                line.startsWith("<manifest") ||
                line.startsWith("<resources") ||
                line.startsWith("<layout") ||
                line.startsWith("<LinearLayout") ||
                line.startsWith("<RelativeLayout") ||
                line.startsWith("<ConstraintLayout") ||
                line.startsWith("<androidx.") ||
                line.startsWith("<com.google.") ||
                line.startsWith("<") && line.contains("xmlns") ||
                line.startsWith("//") ||
                line.startsWith("/*") ||
                line.startsWith("dependencies {") ||
                line.startsWith("android {") ||
                line.startsWith("#include") ||
                line.startsWith("#pragma") ||
                line.startsWith("#define") ||
                line.startsWith("#ifndef") ||
                line.startsWith("#ifdef") ||
                line.startsWith("#if") ||
                line.startsWith("extern ") ||
                line.startsWith("typedef ") ||
                line.startsWith("struct ") ||
                line.startsWith("enum ") ||
                line.startsWith("namespace ") ||
                line.startsWith("using namespace") ||
                line.startsWith("template") ||
                line.matches(Regex("^(public|private|protected|internal|open|abstract|class|interface|object|fun|val|var).*"))) {
                codeStartIndex = i
                Log.d(TAG, "Code starts at line $i: $line")
                break
            }
        }
        
        // If we found where code starts, trim everything before it
        if (codeStartIndex > 0) {
            return lines.subList(codeStartIndex, lines.size).joinToString("\n")
        }
        
        return content
    }
}

data class CodeSnippet(
    val language: String,
    val code: String
)