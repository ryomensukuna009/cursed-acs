package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.coding.AcsRepoMap
import com.tom.rv2ide.artificial.coding.BuildDiagnostic
import com.tom.rv2ide.artificial.coding.ProjectDependencyInspector
import com.tom.rv2ide.artificial.coding.SymbolKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 8.4: Lightweight Project Symbol Map Tests.
 *
 * Validates deterministic C/C++, Java, and Kotlin symbol extraction,
 * safe project traversal, caching/invalidation, ranking, budget truncation,
 * and integration with real agent context.
 */
class Phase8RepoMapTest {

    private lateinit var testProjectRoot: File

    @Before
    fun setUp() {
        testProjectRoot = Files.createTempDirectory("acs_repomap_test_proj").toFile()
        AcsRepoMap.clearCache()
    }

    @After
    fun tearDown() {
        AcsRepoMap.clearCache(testProjectRoot)
        testProjectRoot.deleteRecursively()
    }

    @Test
    fun `test 1 - C and Cpp class, struct, function, and macro extraction`() {
        val cppFile = File(testProjectRoot, "NativeCode.cpp")
        cppFile.writeText(
            """
            #include "Header.h"
            #define MAX_BUFFER 1024
            #define CALC(a, b) ((a) + (b))

            // Comment class FakeClass {
            /* Multi-line
               struct FakeStruct {
            */

            class MenuManager {
            public:
                int version;
            };

            struct PlayerData : public BaseData {
                int health;
            };

            enum State {
                IDLE, RUNNING
            };

            void InitMenu(void *arg) {
                // do init
            }

            static int CalculateScore(int x, int y);
            """.trimIndent()
        )

        val symbols = AcsRepoMap.scanFileContent(cppFile, "cpp")
        val names = symbols.map { it.name }

        assertTrue("Must extract macro MAX_BUFFER", names.contains("MAX_BUFFER"))
        assertTrue("Must extract macro CALC", names.contains("CALC"))
        assertTrue("Must extract include Header.h", names.contains("Header.h"))
        assertTrue("Must extract class MenuManager", names.contains("MenuManager"))
        assertTrue("Must extract struct PlayerData", names.contains("PlayerData"))
        assertTrue("Must extract enum State", names.contains("State"))
        assertTrue("Must extract function InitMenu", names.contains("InitMenu"))
        assertTrue("Must extract function CalculateScore", names.contains("CalculateScore"))

        assertFalse("Must not extract commented FakeClass", names.contains("FakeClass"))
        assertFalse("Must not extract commented FakeStruct", names.contains("FakeStruct"))

        val macroSym = symbols.first { it.name == "MAX_BUFFER" }
        assertEquals(SymbolKind.MACRO, macroSym.kind)
        assertEquals(2, macroSym.line)

        val classSym = symbols.first { it.name == "MenuManager" }
        assertEquals(SymbolKind.CLASS, classSym.kind)

        val structSym = symbols.first { it.name == "PlayerData" }
        assertEquals(SymbolKind.STRUCT, structSym.kind)
    }

    @Test
    fun `test 2 - Java class, interface, enum, and method extraction`() {
        val javaFile = File(testProjectRoot, "Launcher.java")
        javaFile.writeText(
            """
            package com.example;

            import android.app.Activity;
            import java.util.List;

            public class Launcher extends Activity {

                interface ClickListener {
                    void onClick(int id);
                }

                enum Mode {
                    FAST, SLOW
                }

                public void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                }

                private static String formatToken(int id) {
                    return "T-" + id;
                }
            }
            """.trimIndent()
        )

        val symbols = AcsRepoMap.scanFileContent(javaFile, "java")
        val names = symbols.map { it.name }

        assertTrue("Must extract class Launcher", names.contains("Launcher"))
        assertTrue("Must extract interface ClickListener", names.contains("ClickListener"))
        assertTrue("Must extract enum Mode", names.contains("Mode"))
        assertTrue("Must extract method onCreate", names.contains("onCreate"))
        assertTrue("Must extract method formatToken", names.contains("formatToken"))
        assertTrue("Must extract import Activity", names.contains("Activity"))

        val methodSym = symbols.first { it.name == "onCreate" }
        assertEquals(SymbolKind.FUNCTION, methodSym.kind)
    }

    @Test
    fun `test 3 - Kotlin class, interface, and function extraction`() {
        val ktFile = File(testProjectRoot, "SettingsActivity.kt")
        ktFile.writeText(
            """
            package com.example

            import com.example.models.Config

            interface OnPreferenceChangedListener {
                fun onChanged(key: String)
            }

            data class UserConfig(val id: String)

            class SettingsActivity : AppCompatActivity() {
                fun loadConfig(path: String): UserConfig {
                    return UserConfig("default")
                }

                suspend fun syncPreferences(): Boolean {
                    return true
                }
            }
            """.trimIndent()
        )

        val symbols = AcsRepoMap.scanFileContent(ktFile, "kt")
        val names = symbols.map { it.name }

        assertTrue("Must extract interface OnPreferenceChangedListener", names.contains("OnPreferenceChangedListener"))
        assertTrue("Must extract data class UserConfig", names.contains("UserConfig"))
        assertTrue("Must extract class SettingsActivity", names.contains("SettingsActivity"))
        assertTrue("Must extract function loadConfig", names.contains("loadConfig"))
        assertTrue("Must extract function syncPreferences", names.contains("syncPreferences"))

        val funSym = symbols.first { it.name == "loadConfig" }
        assertEquals(SymbolKind.FUNCTION, funSym.kind)
    }

    @Test
    fun `test 4 - correct project-relative paths and line numbers`() {
        val subDir = File(testProjectRoot, "app/src/main/cpp").apply { mkdirs() }
        val file = File(subDir, "Main.cpp")
        file.writeText(
            """
            // Header
            #include "Menu.h"

            void StartEngine() {
            }
            """.trimIndent()
        )

        val scanned = AcsRepoMap.scanProject(testProjectRoot)
        assertEquals(1, scanned.size)

        val item = scanned.first()
        assertEquals("app/src/main/cpp/Main.cpp", item.relativePath)

        val inc = item.symbols.first { it.kind == SymbolKind.INCLUDE }
        assertEquals(2, inc.line)

        val fn = item.symbols.first { it.kind == SymbolKind.FUNCTION }
        assertEquals(4, fn.line)
    }

    @Test
    fun `test 5 - exclusion of generated and build directories`() {
        // Legitimate source
        val srcDir = File(testProjectRoot, "src/main/java").apply { mkdirs() }
        File(srcDir, "App.java").writeText("public class App {}")

        // Excluded build dirs
        val buildDir = File(testProjectRoot, "build/intermediates").apply { mkdirs() }
        File(buildDir, "Generated.java").writeText("public class Generated {}")

        val cxxDir = File(testProjectRoot, ".cxx/Debug").apply { mkdirs() }
        File(cxxDir, "Cache.cpp").writeText("class Cache {};")

        val gitDir = File(testProjectRoot, ".git/hooks").apply { mkdirs() }
        File(gitDir, "Test.kt").writeText("class GitHook")

        val scanned = AcsRepoMap.scanProject(testProjectRoot)
        assertEquals(1, scanned.size)
        assertEquals("src/main/java/App.java", scanned.first().relativePath)
    }

    @Test
    fun `test 6 - active-file prioritization`() {
        val f1 = File(testProjectRoot, "A.cpp").apply { writeText("class Alpha {};") }
        val f2 = File(testProjectRoot, "B.cpp").apply { writeText("class Beta {};") }
        val f3 = File(testProjectRoot, "C.cpp").apply { writeText("class Gamma {};") }

        // Set C.cpp as active file
        val map = AcsRepoMap.rankAndFormatMap(
            projectRoot = testProjectRoot,
            activeFile = f3
        )

        val lines = map.lines().filter { it.contains(":") && !it.startsWith(" ") && !it.startsWith("=") }
        assertTrue("First listed file must be the active file C.cpp", lines.first().contains("C.cpp (active)"))
    }

    @Test
    fun `test 7 - ranking by relevant diagnostic paths and symbols`() {
        File(testProjectRoot, "Normal.cpp").apply { writeText("class Normal {};") }
        val errorFile = File(testProjectRoot, "Broken.cpp").apply {
            writeText(
                """
                void BrokenFn() {
                    UnknownType t;
                }
                """.trimIndent()
            )
        }
        val defFile = File(testProjectRoot, "Types.h").apply {
            writeText(
                """
                struct UnknownType {
                    int val;
                };
                """.trimIndent()
            )
        }

        val diag = BuildDiagnostic(
            filePath = errorFile.absolutePath,
            line = 2,
            column = 5,
            severity = BuildDiagnostic.Severity.ERROR,
            stage = "NDK_BUILD",
            message = "unknown type name 'UnknownType'",
            rawLine = "Broken.cpp:2:5: error: unknown type name 'UnknownType'"
        )

        val map = AcsRepoMap.rankAndFormatMap(
            projectRoot = testProjectRoot,
            diagnostics = listOf(diag)
        )

        val lines = map.lines().filter { it.contains(":") && !it.startsWith(" ") && !it.startsWith("=") }
        assertTrue("Diagnostic error file Broken.cpp must be marked", lines.any { it.contains("Broken.cpp (diagnostic error)") })
        // Types.h should be ranked before Normal.cpp because it defines UnknownType
        val brokenIdx = lines.indexOfFirst { it.contains("Broken.cpp") }
        val typesIdx = lines.indexOfFirst { it.contains("Types.h") }
        val normalIdx = lines.indexOfFirst { it.contains("Normal.cpp") }

        assertTrue("Broken.cpp must rank high", brokenIdx != -1)
        assertTrue("Types.h must rank before Normal.cpp", typesIdx < normalIdx)
    }

    @Test
    fun `test 8 - token-budget truncation without malformed output`() {
        // Create 20 files with many symbols
        for (i in 1..20) {
            val f = File(testProjectRoot, "File$i.kt")
            f.writeText(
                """
                class Class$i
                fun functionA$i() {}
                fun functionB$i() {}
                fun functionC$i() {}
                fun functionD$i() {}
                """.trimIndent()
            )
        }

        // Limit to 100 tokens (~400 characters)
        val map = AcsRepoMap.rankAndFormatMap(
            projectRoot = testProjectRoot,
            maxTokens = 100
        )

        assertTrue("Must include header", map.startsWith("=== REPOSITORY SYMBOL MAP (TOP DEFINITIONS) ==="))
        assertTrue("Must contain truncation notice", map.contains("truncated to fit 100-token budget"))
        assertTrue("Output length must be bounded near budget", map.length <= 600)
    }

    @Test
    fun `test 9 - cache invalidation when a source file changes`() {
        val file = File(testProjectRoot, "Mod.cpp")
        file.writeText("class FirstVersion {};")

        val scan1 = AcsRepoMap.scanProject(testProjectRoot)
        assertEquals("FirstVersion", scan1.first().symbols.first().name)

        // Modify file
        file.writeText("class SecondVersion {};")
        file.setLastModified(System.currentTimeMillis() + 5000)

        val scan2 = AcsRepoMap.scanProject(testProjectRoot)
        assertEquals("SecondVersion", scan2.first().symbols.first().name)
    }

    @Test
    fun `test 10 - cache cleanup or invalidation when a source file is removed`() {
        val file = File(testProjectRoot, "Temporary.kt")
        file.writeText("class Temporary")

        val scan1 = AcsRepoMap.scanProject(testProjectRoot)
        assertEquals(1, scan1.size)

        // Delete file
        file.delete()
        val scan2 = AcsRepoMap.scanProject(testProjectRoot)
        assertEquals(0, scan2.size)
    }

    @Test
    fun `test 11 - isolation of caches between different project roots`() {
        val root2 = Files.createTempDirectory("acs_repomap_test_proj2").toFile()
        try {
            File(testProjectRoot, "P1.kt").writeText("class ProjectOne")
            File(root2, "P2.kt").writeText("class ProjectTwo")

            val scan1 = AcsRepoMap.scanProject(testProjectRoot)
            val scan2 = AcsRepoMap.scanProject(root2)

            assertEquals(1, scan1.size)
            assertEquals("ProjectOne", scan1.first().symbols.first().name)

            assertEquals(1, scan2.size)
            assertEquals("ProjectTwo", scan2.first().symbols.first().name)
        } finally {
            AcsRepoMap.clearCache(root2)
            root2.deleteRecursively()
        }
    }

    @Test
    fun `test 12 - handling unreadable files and failed indexing`() {
        val validFile = File(testProjectRoot, "Good.cpp")
        validFile.writeText("class Good {};")

        val nonExistent = File(testProjectRoot, "Ghost.cpp")
        // Request scanning non-existent file directly
        val res = AcsRepoMap.getOrScanFile(testProjectRoot, nonExistent)
        assertEquals(null, res)

        // Scan project succeeds regardless
        val scan = AcsRepoMap.scanProject(testProjectRoot)
        assertEquals(1, scan.size)
        assertEquals("Good", scan.first().symbols.first().name)
    }

    @Test
    fun `test 13 - correct fallback when no symbols can be extracted`() {
        val emptyFile = File(testProjectRoot, "Empty.txt")
        emptyFile.writeText("Just plain text with no code symbols.")

        val map = AcsRepoMap.rankAndFormatMap(testProjectRoot)
        assertEquals("Must return empty string when no supported code files exist", "", map)
    }

    @Test
    fun `test 14 - integration proving the map reaches real agent request context`() {
        val mainCpp = File(testProjectRoot, "Main.cpp").apply {
            writeText(
                """
                #include "Setup.h"
                class MainApp {
                public:
                    void Run();
                };
                """.trimIndent()
            )
        }

        val inspection = ProjectDependencyInspector.inspectProjectForPrompt(
            projectRoot = testProjectRoot,
            userPrompt = "fix Main.cpp"
        )

        assertTrue(
            "contextPromptBlock must contain REPOSITORY SYMBOL MAP",
            inspection.contextPromptBlock.contains("=== REPOSITORY SYMBOL MAP (TOP DEFINITIONS) ===")
        )
        assertTrue(
            "contextPromptBlock must map MainApp symbol",
            inspection.contextPromptBlock.contains("MainApp")
        )
        assertTrue(
            "repoMap property in inspection result must be populated",
            inspection.repoMap.isNotBlank() && inspection.repoMap.contains("MainApp")
        )
    }
}
