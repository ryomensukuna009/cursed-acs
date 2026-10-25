package com.tom.rv2ide.problems

import com.tom.rv2ide.artificial.coding.BuildDiagnostic
import com.tom.rv2ide.artificial.coding.StructuredDiagnosticCollector
import com.tom.rv2ide.fragments.sidebar.BuildState
import com.tom.rv2ide.fragments.sidebar.ProblemFilter
import com.tom.rv2ide.fragments.sidebar.ProblemGrouping
import com.tom.rv2ide.fragments.sidebar.ProblemSorting
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AdvancedProblemsCenterTest {

    private val testProjectRoot = File("c:/test_workspace/MyProject")

    @Before
    fun setUp() {
        val registry = ProjectProblemRegistry.getInstance()
        registry.onProjectSwitched(testProjectRoot)
    }

    @Test
    fun testStructuredDiagnosticCollectorAapt2DuplicateAttribute() {
        val rawLog = """
            [RESOURCES] Compiling and linking resources with AAPT2...
            [RESOURCES] Linking resources with AAPT2 against android.jar...
            [RESOURCES] /storage/emulated/0/ACS_SRC/ZYGISK_SRC_RAMA_MODZ(TG@RAMA_MODZ)/app/src/main/AndroidManifest.xml:14: error: duplicate attribute.
            [RESOURCES] === [Stage Done: Resource Compilation (AAPT2)] FAILED in 42ms ===
            BUILD FAILED in 1013ms
            Stage: RESOURCES
            Error: AAPT2 resource linking failed (exit code 1)
        """.trimIndent()

        val diags = StructuredDiagnosticCollector.parseBuildOutput(
            rawOutput = rawLog,
            projectRoot = File("/storage/emulated/0/ACS_SRC/ZYGISK_SRC_RAMA_MODZ(TG@RAMA_MODZ)"),
            defaultStage = "RESOURCES"
        )

        assertTrue("Should detect at least 1 diagnostic", diags.isNotEmpty())
        val manifestDiag = diags.firstOrNull { it.filePath?.contains("AndroidManifest.xml") == true }
        assertNotNull("Should parse AndroidManifest.xml diagnostic", manifestDiag)
        assertEquals(14, manifestDiag!!.line)
        assertEquals(BuildDiagnostic.Severity.ERROR, manifestDiag.severity)
        assertEquals("duplicate attribute.", manifestDiag.message)
        assertEquals("AAPT2", manifestDiag.stage)
    }

    @Test
    fun testKotlincParenAndColonFormats() {
        val rawLog = """
            [KOTLINC] e: /src/app/MainActivity.kt: (42, 10): Unresolved reference: binding
            [KOTLINC] e: /src/app/Utils.kt:15:4: Type mismatch: inferred type is Int but String was expected
            [KOTLINC] w: /src/app/OldApi.kt: (8, 2): 'fun deprecatedMethod(): Unit' is deprecated
        """.trimIndent()

        val diags = StructuredDiagnosticCollector.parseBuildOutput(
            rawOutput = rawLog,
            projectRoot = File("/src/app"),
            defaultStage = "KOTLINC"
        )

        assertEquals(3, diags.size)
        val d1 = diags[0]
        assertEquals(42, d1.line)
        assertEquals(10, d1.column)
        assertEquals(BuildDiagnostic.Severity.ERROR, d1.severity)
        assertEquals("Unresolved reference: binding", d1.message)

        val d2 = diags[1]
        assertEquals(15, d2.line)
        assertEquals(4, d2.column)
        assertEquals(BuildDiagnostic.Severity.ERROR, d2.severity)

        val d3 = diags[2]
        assertEquals(8, d3.line)
        assertEquals(BuildDiagnostic.Severity.WARNING, d3.severity)
    }

    @Test
    fun testClangNdkBuildDiagnostics() {
        val rawLog = """
            [NATIVE] /workspace/app/src/main/cpp/Main.cpp:55:12: error: use of undeclared identifier 'nullptr_t'
            [NATIVE] /workspace/app/src/main/cpp/Main.cpp:60:5: warning: unused variable 'foo' [-Wunused-variable]
        """.trimIndent()

        val diags = StructuredDiagnosticCollector.parseBuildOutput(
            rawOutput = rawLog,
            projectRoot = File("/workspace/app"),
            defaultStage = "NATIVE"
        )

        assertEquals(2, diags.size)
        val err = diags[0]
        assertEquals(55, err.line)
        assertEquals(12, err.column)
        assertEquals(BuildDiagnostic.Severity.ERROR, err.severity)
        assertEquals("NDK_BUILD", err.stage)

        val warn = diags[1]
        assertEquals(60, warn.line)
        assertEquals(BuildDiagnostic.Severity.WARNING, warn.severity)
    }

    @Test
    fun testFallbackCapturesRealErrorMessageInsteadOfGeneric() {
        val rawLog = """
            > Task :app:compileDebugJavaWithJavac FAILED
            Execution failed for task ':app:compileDebugJavaWithJavac'.
            > Compilation failed; see the compiler error output for details.
            BUILD FAILED in 2s
        """.trimIndent()

        val diags = StructuredDiagnosticCollector.parseBuildOutput(
            rawOutput = rawLog,
            projectRoot = testProjectRoot,
            defaultStage = "GRADLE"
        )

        assertTrue(diags.isNotEmpty())
        val captured = diags.map { it.message }
        assertFalse(
            "Fallback must never return generic 'Please inspect build output log.'",
            captured.any { it.contains("Please inspect build output log", ignoreCase = true) }
        )
        assertTrue(
            "Fallback must capture task failure or compilation failed",
            captured.any { it.contains("compileDebugJavaWithJavac", ignoreCase = true) || it.contains("Compilation failed", ignoreCase = true) }
        )
    }

    @Test
    fun testProjectProblemRegistryGroupingAndSorting() {
        val registry = ProjectProblemRegistry.getInstance()
        val fileA = File(testProjectRoot, "src/A.kt")
        val fileB = File(testProjectRoot, "src/B.kt")

        val lspItemsA = listOf(
            DiagnosticItem(
                message = "Syntax error",
                code = "101",
                range = Range(Position(10, 2), Position(10, 5)),
                source = "LSP",
                severity = DiagnosticSeverity.ERROR
            ),
            DiagnosticItem(
                message = "Unused import",
                code = "102",
                range = Range(Position(2, 0), Position(2, 20)),
                source = "LSP",
                severity = DiagnosticSeverity.WARNING
            )
        )
        registry.updateLspDiagnostics(fileA, lspItemsA)

        val buildItems = listOf(
            BuildDiagnostic(
                filePath = "src/B.kt",
                line = 20,
                column = 5,
                severity = BuildDiagnostic.Severity.ERROR,
                stage = "KOTLINC",
                message = "Unresolved reference: bar",
                rawLine = "e: src/B.kt:20:5: Unresolved reference: bar"
            ),
            BuildDiagnostic(
                filePath = "src/B.kt",
                line = 25,
                column = 1,
                severity = BuildDiagnostic.Severity.ERROR,
                stage = "KOTLINC",
                message = "Cannot find symbol",
                rawLine = "e: src/B.kt:25:1: Cannot find symbol"
            )
        )
        registry.recordBuildDiagnostics(buildItems, testProjectRoot)

        // 1. Group by File
        val groupsByFile = registry.getProblemGroups(ProblemGrouping.BY_FILE, ProblemSorting.BY_SEVERITY)
        assertEquals(2, groupsByFile.size)
        // File B has 2 errors, File A has 1 error -> File B should be first
        assertEquals("B.kt", groupsByFile[0].fileName)
        assertEquals(2, groupsByFile[0].errorCount)
        assertEquals("A.kt", groupsByFile[1].fileName)
        assertEquals(1, groupsByFile[1].errorCount)

        // 2. Group by Severity
        val groupsBySev = registry.getProblemGroups(ProblemGrouping.BY_SEVERITY, ProblemSorting.BY_SEVERITY)
        val errorGroup = groupsBySev.firstOrNull { it.fileName.startsWith("Errors") }
        assertNotNull(errorGroup)
        assertEquals(3, errorGroup!!.diagnostics.size) // 1 in A + 2 in B

        val warnGroup = groupsBySev.firstOrNull { it.fileName.startsWith("Warnings") }
        assertNotNull(warnGroup)
        assertEquals(1, warnGroup!!.diagnostics.size) // 1 in A

        // 3. Group by Source
        val groupsBySource = registry.getProblemGroups(ProblemGrouping.BY_SOURCE, ProblemSorting.BY_SEVERITY)
        assertTrue(groupsBySource.any { it.fileName == "LSP" })
        assertTrue(groupsBySource.any { it.fileName == "KOTLINC" })
    }

    @Test
    fun testFilteringAndSearch() {
        val registry = ProjectProblemRegistry.getInstance()
        val fileA = File(testProjectRoot, "src/A.kt")

        registry.updateLspDiagnostics(
            fileA,
            listOf(
                DiagnosticItem(
                    message = "Unresolved reference foo",
                    code = "101",
                    range = Range(Position(1, 0), Position(1, 5)),
                    source = "LSP",
                    severity = DiagnosticSeverity.ERROR
                ),
                DiagnosticItem(
                    message = "Unused variable bar",
                    code = "102",
                    range = Range(Position(5, 0), Position(5, 5)),
                    source = "LSP",
                    severity = DiagnosticSeverity.WARNING
                ),
                DiagnosticItem(
                    message = "Hint info",
                    code = "103",
                    range = Range(Position(10, 0), Position(10, 5)),
                    source = "LSP",
                    severity = DiagnosticSeverity.INFO
                )
            )
        )

        val groups = registry.getProblemGroups(ProblemGrouping.BY_FILE, ProblemSorting.BY_SEVERITY)
        assertEquals(1, groups.size)
        val group = groups[0]

        // All filter
        assertEquals(3, group.filteredDiagnostics(ProblemFilter.ALL).size)

        // Errors only filter
        val errorsOnly = group.filteredDiagnostics(ProblemFilter.ERRORS_ONLY)
        assertEquals(1, errorsOnly.size)
        assertEquals("Unresolved reference foo", errorsOnly[0].message)

        // Warnings only filter
        val warningsOnly = group.filteredDiagnostics(ProblemFilter.WARNINGS_ONLY)
        assertEquals(1, warningsOnly.size)
        assertEquals("Unused variable bar", warningsOnly[0].message)

        // Search query filter
        val searchFoo = group.filteredDiagnostics(ProblemFilter.ALL, null, "foo")
        assertEquals(1, searchFoo.size)

        val searchNonExistent = group.filteredDiagnostics(ProblemFilter.ALL, null, "xyz12345")
        assertEquals(0, searchNonExistent.size)
    }

    @Test
    fun testBuildAiFixPrompt() {
        val registry = ProjectProblemRegistry.getInstance()
        val targetFile = File(testProjectRoot, "app/src/main/AndroidManifest.xml")
        val diag = DiagnosticItem(
            message = "duplicate attribute.",
            code = "AAPT2",
            range = Range(Position(13, 0), Position(13, 20)),
            source = "AAPT2",
            severity = DiagnosticSeverity.ERROR
        )

        val prompt = registry.buildAiFixPrompt(diag, targetFile)
        assertTrue("Prompt must include file path", prompt.contains("AndroidManifest.xml"))
        assertTrue("Prompt must include line 14", prompt.contains("14"))
        assertTrue("Prompt must include error message", prompt.contains("duplicate attribute."))
        assertTrue("Prompt must include AAPT2 source", prompt.contains("AAPT2"))
        assertTrue("Prompt must request safe modification and verification", prompt.contains("modify the file safely"))
    }

    @Test
    fun testProjectSwitchClearsPreviousDiagnosticsWithoutLeak() {
        val registry = ProjectProblemRegistry.getInstance()
        val projectA = File("c:/test_workspace/ProjectA")
        val projectB = File("c:/test_workspace/ProjectB")

        // 1. Setup ProjectA with diagnostics
        registry.onProjectSwitched(projectA)
        val fileA = File(projectA, "src/Main.kt")
        registry.updateLspDiagnostics(fileA, listOf(
            DiagnosticItem(
                message = "Error in Project A",
                code = "101",
                range = Range(Position(1, 0), Position(1, 5)),
                source = "LSP",
                severity = DiagnosticSeverity.ERROR
            )
        ))
        registry.recordBuildDiagnostics(listOf(
            BuildDiagnostic(
                filePath = "src/BuildA.kt",
                line = 10,
                column = 2,
                severity = BuildDiagnostic.Severity.ERROR,
                stage = "KOTLINC",
                message = "Build failure in A",
                rawLine = "e: src/BuildA.kt:10:2: Build failure in A"
            )
        ), projectA)

        val groupsA = registry.getAllProblemFileGroups()
        assertEquals("Project A must have 2 problem groups", 2, groupsA.size)

        // 2. Switch to ProjectB
        registry.onProjectSwitched(projectB)

        // Verify all diagnostics from ProjectA are flushed
        val groupsBInitial = registry.getAllProblemFileGroups()
        assertTrue("Project B must start with zero diagnostics", groupsBInitial.isEmpty())
        assertFalse("Build status must reset to not built", registry.hasProjectBeenBuilt())
        assertEquals("Build state must reset to IDLE", BuildState.IDLE, registry.getBuildState())

        // 3. Add diagnostics for ProjectB
        val fileB = File(projectB, "src/Other.kt")
        registry.updateLspDiagnostics(fileB, listOf(
            DiagnosticItem(
                message = "Error in Project B",
                code = "202",
                range = Range(Position(5, 0), Position(5, 10)),
                source = "LSP",
                severity = DiagnosticSeverity.ERROR
            )
        ))
        val groupsB = registry.getAllProblemFileGroups()
        assertEquals("Project B must only show its own diagnostic", 1, groupsB.size)
        assertEquals("Other.kt", groupsB[0].fileName)
        assertFalse("Must never leak Project A diagnostics into Project B",
            groupsB.any { it.fileName.contains("Main.kt") || it.fileName.contains("BuildA.kt") })
    }

    @Test
    fun testRebuildVerificationAndResolvedErrorRemoval() {
        val registry = ProjectProblemRegistry.getInstance()
        val projectRoot = testProjectRoot
        registry.onProjectSwitched(projectRoot)

        // Wave 1: Initial build failure with 2 distinct errors in FileA and FileB
        val wave1Errors = listOf(
            BuildDiagnostic(
                filePath = "src/FileA.kt",
                line = 15,
                column = 4,
                severity = BuildDiagnostic.Severity.ERROR,
                stage = "KOTLINC",
                message = "Type mismatch in FileA",
                rawLine = "e: src/FileA.kt:15:4: Type mismatch in FileA"
            ),
            BuildDiagnostic(
                filePath = "src/FileB.kt",
                line = 40,
                column = 10,
                severity = BuildDiagnostic.Severity.ERROR,
                stage = "KOTLINC",
                message = "Unresolved symbol in FileB",
                rawLine = "e: src/FileB.kt:40:10: Unresolved symbol in FileB"
            )
        )
        registry.recordBuildDiagnostics(wave1Errors, projectRoot)
        assertEquals(BuildState.BUILD_FAILED, registry.getBuildState())
        val wave1Groups = registry.getAllProblemFileGroups()
        assertEquals(2, wave1Groups.size)
        assertTrue(wave1Groups.any { it.fileName == "FileA.kt" })
        assertTrue(wave1Groups.any { it.fileName == "FileB.kt" })

        // Wave 2: User / AI repairs FileA.kt and initiates rebuild.
        // During build execution, state is BUILDING
        registry.onBuildStarted()
        assertEquals(BuildState.BUILDING, registry.getBuildState())

        // Rebuild completes: FileA.kt error is resolved, but FileB.kt still fails
        val wave2Errors = listOf(
            BuildDiagnostic(
                filePath = "src/FileB.kt",
                line = 40,
                column = 10,
                severity = BuildDiagnostic.Severity.ERROR,
                stage = "KOTLINC",
                message = "Unresolved symbol in FileB",
                rawLine = "e: src/FileB.kt:40:10: Unresolved symbol in FileB"
            )
        )
        registry.recordBuildDiagnostics(wave2Errors, projectRoot)

        val wave2Groups = registry.getAllProblemFileGroups()
        assertEquals("FileA must be removed; FileB must remain", 1, wave2Groups.size)
        assertEquals("FileB.kt", wave2Groups[0].fileName)
        assertEquals("Unresolved symbol in FileB", wave2Groups[0].diagnostics[0].message)
        assertEquals(BuildState.BUILD_FAILED, registry.getBuildState())

        // Wave 3: User fixes FileB.kt and initiates final rebuild.
        registry.onBuildStarted()
        val wave3Errors = emptyList<BuildDiagnostic>()
        registry.recordBuildDiagnostics(wave3Errors, projectRoot)

        val wave3Groups = registry.getAllProblemFileGroups()
        assertTrue("All build errors must be completely removed upon successful rebuild", wave3Groups.isEmpty())
        assertEquals("State must transition to BUILD_SUCCEEDED", BuildState.BUILD_SUCCEEDED, registry.getBuildState())
        assertEquals("Build succeeded", registry.getLastBuildSummary())
    }
}
