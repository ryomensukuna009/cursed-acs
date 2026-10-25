package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.coding.IterativeRepairCoordinator
import com.tom.rv2ide.artificial.coding.ProjectDependencyInspector
import com.tom.rv2ide.artificial.coding.StructuredDiagnosticCollector
import com.tom.rv2ide.artificial.coding.TransactionalFileCoordinator
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase7CodingAgentAndRepairTest {

    @Test
    fun `diagnostic collector parses javac, kotlinc, aapt2, and ndk-build errors and separates cascading R errors`() {
        val buildLog = """
            === [Stage 1/5: AAPT2 Compile & Link] ===
            /proj/app/src/main/res/layout/activity_main.xml:18: error: resource string/missing_title not found.
            === [Stage 2/5: JAVAC Compilation] ===
            /proj/app/src/main/java/com/example/MainActivity.java:24:12: error: package R does not exist
            e: file:///proj/app/src/main/java/com/example/Helper.kt:15:8 Unresolved reference: FancyWidget
            === [Stage 3/5: NDK Build] ===
            /proj/app/src/main/jni/native-lib.cpp:42:5: error: use of undeclared identifier ' missing_var'
        """.trimIndent()

        val projectRoot = File("/proj")
        val diagnostics = StructuredDiagnosticCollector.parseBuildOutput(buildLog, projectRoot)
        assertEquals(4, diagnostics.size)

        val grouped = StructuredDiagnosticCollector.groupCascadingDiagnostics(diagnostics)
        // The R error should be classified as a secondary cascading error because an XML resource error exists
        assertTrue(
            "Primary root causes should include XML resource error, Helper.kt, and native-lib.cpp",
            grouped.rootCauseDiagnostics.any { it.stage == "AAPT2" }
        )
        assertTrue(
            "R class error should be grouped into secondary cascading errors",
            grouped.secondaryDiagnostics.any { it.message.contains("package R does not exist") }
        )
    }

    @Test
    fun `pre-apply validator rejects path traversal, lazy truncation placeholders, unbalanced braces, and malformed XML`() {
        val tempRoot = Files.createTempDirectory("roxum_phase7_val").toFile()
        try {
            val existingKt = File(tempRoot, "app/src/main/java/com/example/Existing.kt").apply {
                parentFile?.mkdirs()
                writeText(
                    """
                    package com.example
                    
                    class Existing {
                        fun alpha() = 1
                        fun beta() = 2
                        fun gamma() = 3
                        fun delta() = 4
                        fun epsilon() = 5
                        fun zeta() = 6
                    }
                    """.trimIndent()
                )
            }

            val badAiResponse = """
                FILE_TO_MODIFY: ../../outside/Evil.kt
                package evil
                class Evil {}
                
                FILE_TO_MODIFY: app/src/main/java/com/example/Existing.kt
                package com.example
                class Existing {
                    // ... existing code ...
                    fun broken() {
                }
                
                FILE_TO_CREATE: app/src/main/res/layout/broken.xml
                <?xml version="1.0" encoding="utf-8"?>
                <LinearLayout>
                    <TextView>
                </LinearLayout>
            """.trimIndent()

            val validation = TransactionalFileCoordinator.parseProposedChangeSet(badAiResponse, tempRoot)
            assertFalse("Validation must fail on unsafe path, truncation placeholder, unbalanced braces, and malformed XML", validation.isValid)
            assertTrue(validation.errors.any { it.contains("unsafe or out-of-project path") })
            assertTrue(validation.errors.any { it.contains("truncation placeholder") })
            assertTrue(validation.errors.any { it.contains("unbalanced curly braces") })
            assertTrue(validation.errors.any { it.contains("Malformed XML") })

            // Ensure existing file on disk was untouched
            assertTrue(existingKt.readText().contains("fun zeta() = 6"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `transactional coordinator commits multi-file edits atomically and rolls back all files on mid-batch failure`() {
        val tempRoot = Files.createTempDirectory("roxum_phase7_tx").toFile()
        try {
            val fileA = File(tempRoot, "app/src/main/java/com/example/A.kt").apply {
                parentFile?.mkdirs()
                writeText("package com.example\nclass A { fun old() = 1 }")
            }
            val fileB = File(tempRoot, "app/src/main/java/com/example/B.kt")

            val goodResponse = """
                FILE_TO_MODIFY: app/src/main/java/com/example/A.kt
                package com.example
                class A {
                    fun updated(): Int = B().compute()
                }
                
                FILE_TO_CREATE: app/src/main/java/com/example/B.kt
                package com.example
                class B {
                    fun compute(): Int = 42
                }
            """.trimIndent()

            val validation = TransactionalFileCoordinator.parseProposedChangeSet(goodResponse, tempRoot)
            assertTrue("Valid multi-file change-set should pass validation: ${validation.errors}", validation.isValid)
            assertEquals(2, validation.edits.size)

            // Simulate a mid-batch failure after the first file (A.kt) has already been written
            val failedTx = TransactionalFileCoordinator.applyChangeSetTransactionally(validation.edits) { edit ->
                if (edit.relativePath.endsWith("B.kt")) {
                    throw RuntimeException("Simulated disk write failure on second file")
                }
            }

            assertFalse(failedTx.success)
            assertTrue("Transaction must report rollback", failedTx.rolledBack)
            assertEquals(
                "A.kt must be rolled back to its exact pre-transaction content",
                "package com.example\nclass A { fun old() = 1 }",
                fileA.readText()
            )
            assertFalse("Newly created B.kt must be removed on rollback", fileB.exists())

            // Now run the transaction normally without fault injection
            val committedTx = TransactionalFileCoordinator.applyChangeSetTransactionally(validation.edits)
            assertTrue(committedTx.success)
            assertFalse(committedTx.rolledBack)
            assertTrue(fileA.readText().contains("B().compute()"))
            assertTrue(fileB.exists() && fileB.readText().contains("fun compute(): Int = 42"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `post-edit verification detects missing R layout references and project dependency inspector finds call sites`() {
        val tempRoot = Files.createTempDirectory("roxum_phase7_verify").toFile()
        try {
            val layoutFile = File(tempRoot, "app/src/main/res/layout/activity_main.xml").apply {
                parentFile?.mkdirs()
                writeText("""<?xml version="1.0" encoding="utf-8"?><LinearLayout/>""")
            }
            val mainFile = File(tempRoot, "app/src/main/java/com/example/MainActivity.kt").apply {
                parentFile?.mkdirs()
                writeText(
                    """
                    package com.example
                    class MainActivity {
                        fun init() {
                            val layout = R.layout.activity_main
                            val helper = UserHelper()
                        }
                    }
                    """.trimIndent()
                )
            }
            val helperFile = File(tempRoot, "app/src/main/java/com/example/UserHelper.kt").apply {
                writeText("package com.example\nclass UserHelper { fun help() = true }")
            }

            // 1. Verify dependency inspector discovers MainActivity.kt as a dependent of UserHelper.kt
            val dependents = ProjectDependencyInspector.findDependentFiles(
                projectRoot = tempRoot,
                targetFiles = listOf(helperFile)
            )
            assertTrue("MainActivity.kt should be found as a dependent of UserHelper.kt", dependents.any { it.name == "MainActivity.kt" })

            // 2. Verify IterativeRepairCoordinator catches unresolved R.layout.non_existent_dialog
            val badEditResponse = """
                FILE_TO_MODIFY: app/src/main/java/com/example/MainActivity.kt
                package com.example
                class MainActivity {
                    fun init() {
                        val layout = R.layout.non_existent_dialog
                    }
                }
            """.trimIndent()

            val parsed = TransactionalFileCoordinator.parseProposedChangeSet(badEditResponse, tempRoot)
            assertTrue(parsed.isValid)
            val tx = TransactionalFileCoordinator.applyChangeSetTransactionally(parsed.edits)
            assertTrue(tx.success)

            val verification = IterativeRepairCoordinator.verifyAppliedEdits(tempRoot, tx.appliedEdits)
            assertFalse("Post-edit verification must catch missing R.layout.non_existent_dialog", verification.passed)
            assertTrue(verification.issues.any { it.contains("R.layout.non_existent_dialog") })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `multi-file coordinator applies and verifies 5 or more related files without artificial limits`() {
        val tempRoot = Files.createTempDirectory("roxum_phase7_multifile_5").toFile()
        try {
            val fiveFileResponse = """
                FILE_TO_CREATE: app/src/main/java/com/example/Model1.kt
                package com.example
                data class Model1(val id: Int)

                FILE_TO_CREATE: app/src/main/java/com/example/Model2.kt
                package com.example
                data class Model2(val name: String)

                FILE_TO_CREATE: app/src/main/java/com/example/Model3.kt
                package com.example
                data class Model3(val count: Long)

                FILE_TO_CREATE: app/src/main/java/com/example/Service4.kt
                package com.example
                class Service4 {
                    fun process(m: Model1): Int = m.id * 2
                }

                FILE_TO_CREATE: app/src/main/java/com/example/Manager5.kt
                package com.example
                class Manager5 {
                    val s = Service4()
                    fun run(): Int = s.process(Model1(10))
                }
            """.trimIndent()

            val validation = TransactionalFileCoordinator.parseProposedChangeSet(fiveFileResponse, tempRoot)
            assertTrue("5-file change set must pass pre-apply validation: ${validation.errors}", validation.isValid)
            assertEquals("Must have parsed exactly 5 files with no artificial cap", 5, validation.edits.size)

            val txResult = TransactionalFileCoordinator.applyChangeSetTransactionally(validation.edits)
            assertTrue("Must successfully commit all 5 files", txResult.success)
            assertEquals(5, txResult.appliedEdits.size)

            for (edit in txResult.appliedEdits) {
                assertTrue("File on disk must exist: ${edit.relativePath}", edit.targetFile.exists())
            }

            // Verify rollback capability
            val rolledBack = TransactionalFileCoordinator.rollbackLastTransaction()
            assertTrue("Rollback of 5 files must succeed", rolledBack)
            for (edit in txResult.appliedEdits) {
                assertFalse("File must be deleted after rollback: ${edit.relativePath}", edit.targetFile.exists())
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `error fingerprint computation is deterministic and detects stagnation across identical compiler failure sets`() {
        val errLogA = """
            /proj/app/src/main/java/com/example/A.kt:10: error: cannot find symbol variable x
            /proj/app/src/main/java/com/example/B.kt:25: error: unresolved reference: Foo
        """.trimIndent()

        val errLogB = """
            /proj/app/src/main/java/com/example/B.kt:25: error: unresolved reference: Foo
            /proj/app/src/main/java/com/example/A.kt:10: error: cannot find symbol variable x
        """.trimIndent()

        val diagsA = StructuredDiagnosticCollector.parseBuildOutput(errLogA, File("/proj"))
        val diagsB = StructuredDiagnosticCollector.parseBuildOutput(errLogB, File("/proj"))

        val fingerprintA = StructuredDiagnosticCollector.computeErrorFingerprint(diagsA)
        val fingerprintB = StructuredDiagnosticCollector.computeErrorFingerprint(diagsB)

        assertEquals("Fingerprints must match regardless of log line order", fingerprintA, fingerprintB)
        assertFalse(fingerprintA == "NO_ERRORS")

        // Changing an error must alter the fingerprint
        val errLogC = """
            /proj/app/src/main/java/com/example/A.kt:10: error: cannot find symbol variable x
            /proj/app/src/main/java/com/example/B.kt:30: error: type mismatch
        """.trimIndent()
        val diagsC = StructuredDiagnosticCollector.parseBuildOutput(errLogC, File("/proj"))
        val fingerprintC = StructuredDiagnosticCollector.computeErrorFingerprint(diagsC)
        assertFalse("Fingerprint must change when error changes", fingerprintA == fingerprintC)
    }

    @Test
    fun `project dependency inspector executes search, list files, and range reads`() {
        val tempRoot = Files.createTempDirectory("roxum_phase7_inspector").toFile()
        try {
            val testFile = File(tempRoot, "app/src/main/java/com/example/InspectMe.kt").apply {
                parentFile?.mkdirs()
                writeText(
                    """
                    package com.example
                    
                    class InspectMe {
                        val token = "SEARCH_TARGET_KEY"
                        fun compute(): Int = 100
                    }
                    """.trimIndent()
                )
            }

            val list = ProjectDependencyInspector.listProjectFiles(tempRoot)
            assertTrue("listProjectFiles must list InspectMe.kt", list.any { it.contains("InspectMe.kt") })

            val search = ProjectDependencyInspector.searchProject(tempRoot, "SEARCH_TARGET_KEY")
            assertEquals(1, search.size)
            assertEquals("InspectMe.kt", File(search[0].relativePath).name)

            val range = ProjectDependencyInspector.readFileRange(testFile, 1, 3)
            assertTrue(range.contains("package com.example"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `autonomous execution coordinator tracks states and notifies listeners`() {
        val statesObserved = mutableListOf<com.tom.rv2ide.artificial.coding.AutonomousExecutionState>()
        val listener = object : com.tom.rv2ide.artificial.coding.AutonomousStateListener {
            override fun onStateChanged(state: com.tom.rv2ide.artificial.coding.AutonomousExecutionState, description: String) {
                statesObserved.add(state)
            }
        }

        com.tom.rv2ide.artificial.coding.AutonomousExecutionCoordinator.addListener(listener)
        try {
            com.tom.rv2ide.artificial.coding.AutonomousExecutionCoordinator.transitionTo(
                com.tom.rv2ide.artificial.coding.AutonomousExecutionState.INSPECTING_PROJECT,
                "Inspecting test"
            )
            com.tom.rv2ide.artificial.coding.AutonomousExecutionCoordinator.transitionTo(
                com.tom.rv2ide.artificial.coding.AutonomousExecutionState.ANALYZING_TASK,
                "Analyzing test"
            )
            com.tom.rv2ide.artificial.coding.AutonomousExecutionCoordinator.transitionTo(
                com.tom.rv2ide.artificial.coding.AutonomousExecutionState.RUNNING_BUILD,
                "Building test"
            )
            com.tom.rv2ide.artificial.coding.AutonomousExecutionCoordinator.transitionTo(
                com.tom.rv2ide.artificial.coding.AutonomousExecutionState.BUILD_SUCCEEDED,
                "Success"
            )

            assertEquals(
                listOf(
                    com.tom.rv2ide.artificial.coding.AutonomousExecutionState.INSPECTING_PROJECT,
                    com.tom.rv2ide.artificial.coding.AutonomousExecutionState.ANALYZING_TASK,
                    com.tom.rv2ide.artificial.coding.AutonomousExecutionState.RUNNING_BUILD,
                    com.tom.rv2ide.artificial.coding.AutonomousExecutionState.BUILD_SUCCEEDED
                ),
                statesObserved
            )
        } finally {
            com.tom.rv2ide.artificial.coding.AutonomousExecutionCoordinator.removeListener(listener)
        }
    }

    @Test
    fun `bracket file syntax and fuzzy file path matching in TransactionalFileCoordinator`() {
        val tempRoot = Files.createTempDirectory("roxum_bracket_test").toFile()
        try {
            val nestedFile = File(tempRoot, "app/src/main/jni/Setup.h").apply {
                parentFile?.mkdirs()
                writeText("#pragma once\nint oldVar = 1;\n")
            }

            // Test AI response using [FILE: Setup.h]...[/FILE] block with bare filename
            val aiResponse = """
                Here is the fix:
                [FILE: Setup.h]
                #pragma once
                int newVar = 2;
                [/FILE]
            """.trimIndent()

            val validation = TransactionalFileCoordinator.parseProposedChangeSet(aiResponse, tempRoot)
            assertTrue("Bracket file block should parse successfully: ${validation.errors}", validation.isValid)
            assertEquals(1, validation.edits.size)
            assertEquals("app/src/main/jni/Setup.h", validation.edits[0].relativePath)
            assertTrue(validation.edits[0].finalContent.contains("int newVar = 2;"))

            val commit = TransactionalFileCoordinator.applyChangeSetTransactionally(validation.edits)
            assertTrue("Commit should succeed", commit.success)
            assertEquals("#pragma once\nint newVar = 2;", nestedFile.readText().trim())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `AgentToolRegistry resolves canonical names and aliases`() {
        assertEquals("read_project_file", com.tom.rv2ide.artificial.tools.AgentToolRegistry.canonicalToolName("read_file"))
        assertEquals("list_project_files", com.tom.rv2ide.artificial.tools.AgentToolRegistry.canonicalToolName("list_files"))
        assertEquals("build_current_project", com.tom.rv2ide.artificial.tools.AgentToolRegistry.canonicalToolName("build_project"))
        assertEquals("get_build_diagnostics", com.tom.rv2ide.artificial.tools.AgentToolRegistry.canonicalToolName("get_diagnostics"))
        assertEquals("apply_file_changes", com.tom.rv2ide.artificial.tools.AgentToolRegistry.canonicalToolName("apply_project_patch"))
        assertEquals("apply_file_changes", com.tom.rv2ide.artificial.tools.AgentToolRegistry.canonicalToolName("apply_patch"))

        assertTrue(com.tom.rv2ide.artificial.tools.AgentToolRegistry.isRegistered("read_file"))
        assertTrue(com.tom.rv2ide.artificial.tools.AgentToolRegistry.isRegistered("read_project_file"))
        assertTrue(com.tom.rv2ide.artificial.tools.AgentToolRegistry.isRegistered("build_project"))
        assertTrue(com.tom.rv2ide.artificial.tools.AgentToolRegistry.isRegistered("apply_file_changes"))
    }

    @Test
    fun `StructuredDiagnosticCollector strips stage tags and normalizes relative paths`() {
        val rawLog = """
            [INIT] Starting build...
            [NDK_BUILD] ./app/src/main/jni/Setup.h:84:5: error: use of undeclared identifier 'api_level'
            [JAVAC] /proj/app/src/main/java/com/example/Main.java:12: error: cannot find symbol
        """.trimIndent()

        val diags = StructuredDiagnosticCollector.parseBuildOutput(rawLog)
        assertEquals(2, diags.size)

        val ndkDiag = diags.first { it.stage == "NDK_BUILD" }
        assertEquals(84, ndkDiag.line)
        assertEquals(5, ndkDiag.column)
        assertEquals("app/src/main/jni/Setup.h", ndkDiag.filePath)
        assertTrue(ndkDiag.message.contains("undeclared identifier 'api_level'"))

        val javacDiag = diags.first { it.stage == "JAVAC" }
        assertEquals(12, javacDiag.line)
        assertEquals("/proj/app/src/main/java/com/example/Main.java", javacDiag.filePath)
    }
}
