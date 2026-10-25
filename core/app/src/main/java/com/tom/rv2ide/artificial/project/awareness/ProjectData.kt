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

package com.tom.rv2ide.artificial.project.awareness

import android.content.Context
import java.io.File

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class ProjectData(ctx: Context) {
    
    // This helps the ai to see full paths of files without bloating context with build artifacts
    fun showProjectTree(proj: File): ProjectTreeResult {
        val sb = StringBuilder()
        val ignoredDirs = setOf("build", ".gradle", ".git", "intermediates", ".cxx", ".idea", "bin", "obj", "build-cache")
    
        fun walk(dir: File, depth: Int) {
            if (depth > 8) return
            sb.appendLine(dir.canonicalPath)
            val children = dir.listFiles() ?: return
            for (file in children.sortedBy { it.name }) {
                if (file.isDirectory) {
                    if (file.name !in ignoredDirs && !file.name.startsWith(".")) {
                        walk(file, depth + 1)
                    }
                } else {
                    sb.appendLine(file.canonicalPath)
                }
            }
        }
    
        walk(proj, 0)
    
        return ProjectTreeResult(sb.toString(), proj)
    }
}

class ProjectTreeResult(
    val tree: String,
    private val root: File
) {

    fun getFileByName(filename: String): String? {

        fun search(dir: File): File? {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    val found = search(file)
                    if (found != null) return found
                } else if (file.name == filename) {
                    return file
                }
            }
            return null
        }

        return search(root)?.canonicalPath
    }

    fun readFileContent(filename: String): String? {
        val path = getFileByName(filename) ?: return null
        return File(path).readText()
    }
}
