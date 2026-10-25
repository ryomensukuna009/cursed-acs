package com.tom.rv2ide.artificial.coding

import com.tom.rv2ide.artificial.chat.ConversationCompactor
import com.tom.rv2ide.artificial.tools.AgentToolRegistry
import com.tom.rv2ide.artificial.tools.SafeUrlFetcher
import com.tom.rv2ide.artificial.tools.ToolOrchestrator
import com.tom.rv2ide.artificial.tools.WebSearchManager
import com.tom.rv2ide.artificial.transport.ChatRole
import com.tom.rv2ide.artificial.transport.ToolCall
import com.tom.rv2ide.artificial.transport.ToolResultMessage
import com.tom.rv2ide.artificial.transport.UnifiedChatMessage
import com.tom.rv2ide.artificial.transport.UnifiedTransportClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit test suite for Phase 9.0: Semantic Code Intelligence & Symbol-Aware Navigation.
 *
 * Validates:
 * 1. Exact and kind-filtered symbol definition lookup across C/C++, Java, and Kotlin.
 * 2. Structured, line-ordered file outline extraction.
 * 3. Diagnostic missing-symbol resolution from compiler error messages.
 * 4. End-to-end tool execution through ToolOrchestrator for 'find_symbol_definition' and 'get_file_outline'.
 */
class Phase9SemanticIntelligenceTest {

    private lateinit var testProjectRoot: File

    @Before
    fun setUp() {
        testProjectRoot = Files.createTempDirectory("acs_phase9_test_proj").toFile()
        AcsRepoMap.clearCache(testProjectRoot)

        // Setup fixture files
        val jniDir = File(testProjectRoot, "app/src/main/jni/Menu").apply { mkdirs() }
        val javaDir = File(testProjectRoot, "app/src/main/java/com/example").apply { mkdirs() }

        // C++ Header & Implementation
        File(jniDir, "Setup.h").writeText(
            """
            #pragma once
            #define MENU_VERSION "3.2"
            #define MAX_ITEMS 64

            struct MenuConfig {
                int theme;
                bool enabled;
            };

            void InitMenu(int width, int height);
            bool IsMenuReady();
            """.trimIndent()
        )

        File(jniDir, "Main.cpp").writeText(
            """
            #include "Setup.h"

            class ModMenuController {
            public:
                int currentMode;
            };

            void InitMenu(int width, int height) {
                // Implementation
            }

            void LaunchApp() {
                InitMenu(1920, 1080);
            }
            """.trimIndent()
        )

        // Java Source
        File(javaDir, "FloatingModMenu.java").writeText(
            """
            package com.example;

            public class FloatingModMenu {
                public void showOverlay() {
                    // show
                }

                public static boolean isServiceRunning() {
                    return true;
                }
            }
            """.trimIndent()
        )

        // Kotlin Source
        File(javaDir, "MenuState.kt").writeText(
            """
            package com.example

            data class MenuState(val isOpen: Boolean)

            fun toggleMenu(state: MenuState): MenuState {
                return state.copy(isOpen = !state.isOpen)
            }
            """.trimIndent()
        )
    }

    @After
    fun tearDown() {
        AcsRepoMap.clearCache(testProjectRoot)
        testProjectRoot.deleteRecursively()
    }

    @Test
    fun testFindSymbolDefinitions_exactCFunction_findsHeaderAndImpl() {
        val defs = AcsRepoMap.findSymbolDefinitions(
            projectRoot = testProjectRoot,
            symbolName = "InitMenu",
            exactMatch = true
        )

        assertEquals("Must find both declaration and definition of InitMenu", 2, defs.size)
        val files = defs.map { it.first }
        assertTrue(files.any { it.endsWith("Setup.h") })
        assertTrue(files.any { it.endsWith("Main.cpp") })

        val headerDef = defs.first { it.first.endsWith("Setup.h") }.second
        assertEquals(SymbolKind.FUNCTION, headerDef.kind)
        assertTrue(headerDef.signature?.contains("InitMenu(int width, int height)") == true)
    }

    @Test
    fun testFindSymbolDefinitions_filteredByKind_matchesOnlyTargetKind() {
        val structDefs = AcsRepoMap.findSymbolDefinitions(
            projectRoot = testProjectRoot,
            symbolName = "MenuConfig",
            kind = SymbolKind.STRUCT,
            exactMatch = true
        )

        assertEquals(1, structDefs.size)
        assertEquals("MenuConfig", structDefs.first().second.name)
        assertEquals(SymbolKind.STRUCT, structDefs.first().second.kind)

        // Filter for FUNCTION should return empty for a struct
        val funcDefs = AcsRepoMap.findSymbolDefinitions(
            projectRoot = testProjectRoot,
            symbolName = "MenuConfig",
            kind = SymbolKind.FUNCTION,
            exactMatch = true
        )
        assertTrue("MenuConfig is not a function", funcDefs.isEmpty())
    }

    @Test
    fun testFindSymbolDefinitions_javaAndKotlinClasses() {
        val javaClassDefs = AcsRepoMap.findSymbolDefinitions(
            projectRoot = testProjectRoot,
            symbolName = "FloatingModMenu",
            kind = SymbolKind.CLASS,
            exactMatch = true
        )
        assertEquals(1, javaClassDefs.size)
        assertTrue(javaClassDefs.first().first.endsWith("FloatingModMenu.java"))

        val ktDataClassDefs = AcsRepoMap.findSymbolDefinitions(
            projectRoot = testProjectRoot,
            symbolName = "MenuState",
            kind = SymbolKind.CLASS,
            exactMatch = true
        )
        assertEquals(1, ktDataClassDefs.size)
        assertTrue(ktDataClassDefs.first().first.endsWith("MenuState.kt"))
    }

    @Test
    fun testGetFileOutline_returnsLineSortedSymbols() {
        val setupH = File(testProjectRoot, "app/src/main/jni/Menu/Setup.h")
        val outline = AcsRepoMap.getFileOutline(testProjectRoot, setupH)

        assertTrue("Outline must contain symbols", outline.isNotEmpty())
        val names = outline.map { it.name }
        assertTrue(names.contains("MENU_VERSION"))
        assertTrue(names.contains("MAX_ITEMS"))
        assertTrue(names.contains("MenuConfig"))
        assertTrue(names.contains("InitMenu"))
        assertTrue(names.contains("IsMenuReady"))

        // Assert strictly increasing or equal line numbers
        for (i in 0 until outline.size - 1) {
            assertTrue("Lines must be sorted: L${outline[i].line} <= L${outline[i+1].line}",
                outline[i].line <= outline[i+1].line)
        }
    }

    @Test
    fun testResolveDiagnosticMissingSymbols_cppUndeclaredIdentifier_resolvesHeader() {
        val diags = listOf(
            BuildDiagnostic(
                filePath = "app/src/main/jni/Other.cpp",
                line = 15,
                column = 5,
                severity = BuildDiagnostic.Severity.ERROR,
                stage = "NDK",
                message = "'InitMenu' was not declared in this scope",
                rawLine = "Other.cpp:15:5: error: 'InitMenu' was not declared in this scope"
            )
        )

        val hints = AcsRepoMap.resolveDiagnosticMissingSymbols(testProjectRoot, diags)
        assertEquals(2, hints.size)
        assertTrue("Hint must mention InitMenu", hints.any { it.contains("InitMenu") })
        assertTrue("Hints must mention Setup.h", hints.any { it.contains("Setup.h") })
        assertTrue("Hints must mention Main.cpp", hints.any { it.contains("Main.cpp") })
    }

    @Test
    fun testResolveDiagnosticMissingSymbols_javaCannotFindSymbol_resolvesJavaClass() {
        val diags = listOf(
            BuildDiagnostic(
                filePath = "app/src/main/java/com/example/MainActivity.java",
                line = 22,
                column = 10,
                severity = BuildDiagnostic.Severity.ERROR,
                stage = "JAVA",
                message = "cannot find symbol class FloatingModMenu",
                rawLine = "MainActivity.java:22: error: cannot find symbol class FloatingModMenu"
            )
        )

        val hints = AcsRepoMap.resolveDiagnosticMissingSymbols(testProjectRoot, diags)
        assertEquals(1, hints.size)
        assertTrue("Hint must mention FloatingModMenu", hints.first().contains("FloatingModMenu"))
        assertTrue("Hint must point to FloatingModMenu.java", hints.first().contains("FloatingModMenu.java"))
    }

    @Test
    fun testToolOrchestrator_findSymbolDefinition_execution() = runBlocking {
        val orchestrator = ToolOrchestrator(
            transportClient = UnifiedTransportClient(),
            webSearchManager = WebSearchManager(),
            safeUrlFetcher = SafeUrlFetcher(),
            projectRootProvider = { testProjectRoot }
        )

        val toolCall = ToolCall(
            id = "call-sym-1",
            name = AgentToolRegistry.TOOL_FIND_SYMBOL_DEFINITION,
            argumentsJson = JSONObject().apply {
                put("symbol", "InitMenu")
                put("kind", "func")
            }.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(toolCall)
        assertFalse(result.isError)
        assertTrue(result.content.contains("Found 2 definition(s) for symbol 'InitMenu'"))
        assertTrue(result.content.contains("Setup.h"))
        assertTrue(result.content.contains("Main.cpp"))
    }

    @Test
    fun testToolOrchestrator_getFileOutline_execution() = runBlocking {
        val orchestrator = ToolOrchestrator(
            transportClient = UnifiedTransportClient(),
            webSearchManager = WebSearchManager(),
            safeUrlFetcher = SafeUrlFetcher(),
            projectRootProvider = { testProjectRoot }
        )

        val toolCall = ToolCall(
            id = "call-outline-1",
            name = AgentToolRegistry.TOOL_GET_FILE_OUTLINE,
            argumentsJson = JSONObject().apply {
                put("filePath", "app/src/main/jni/Menu/Setup.h")
            }.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(toolCall)
        assertFalse(result.isError)
        assertTrue(result.content.contains("=== SYMBOL OUTLINE:"))
        assertTrue(result.content.contains("MENU_VERSION"))
        assertTrue(result.content.contains("InitMenu"))
    }

    @Test
    fun testScopeHierarchyAndQualifiedNames() {
        val mainFile = File(testProjectRoot, "app/src/main/jni/Menu/Main.cpp")
        val symbols = AcsRepoMap.getFileOutline(testProjectRoot, mainFile)

        val controllerSym = symbols.firstOrNull { it.name == "ModMenuController" }
        assertNotNull("ModMenuController must be parsed", controllerSym)
        assertEquals(SymbolKind.CLASS, controllerSym!!.kind)
        assertTrue("Class must span multiple lines", controllerSym.endLine >= controllerSym.line)

        val fieldSym = symbols.firstOrNull { it.name == "currentMode" }
        assertNotNull("currentMode field must be parsed", fieldSym)
        assertEquals(SymbolKind.FIELD, fieldSym!!.kind)
        assertEquals("ModMenuController", fieldSym.enclosingScope)
        assertEquals("ModMenuController.currentMode", fieldSym.qualifiedName)
    }

    @Test
    fun testFindSymbolDefinitions_byQualifiedName() {
        val defsDot = AcsRepoMap.findSymbolDefinitions(
            projectRoot = testProjectRoot,
            symbolName = "ModMenuController.currentMode",
            exactMatch = true
        )
        assertEquals(1, defsDot.size)
        assertEquals("currentMode", defsDot.first().second.name)
        assertEquals("ModMenuController", defsDot.first().second.enclosingScope)

        val defsScope = AcsRepoMap.findSymbolDefinitions(
            projectRoot = testProjectRoot,
            symbolName = "ModMenuController::currentMode",
            exactMatch = true
        )
        assertEquals(1, defsScope.size)
    }

    @Test
    fun testFindSymbolReferences_distinguishesDefinitionsAndCallSites() {
        val refs = AcsRepoMap.findSymbolReferences(
            projectRoot = testProjectRoot,
            symbolName = "InitMenu"
        )

        assertTrue("Must find multiple references to InitMenu", refs.size >= 3)
        val defs = refs.filter { it.isDefinition }
        val calls = refs.filter { !it.isDefinition }

        assertEquals("Must find 2 definitions (Setup.h and Main.cpp)", 2, defs.size)
        assertTrue("Must find at least 1 call-site in LaunchApp()", calls.isNotEmpty())

        val launchCall = calls.first { it.lineContent.contains("InitMenu(1920, 1080)") }
        assertFalse(launchCall.isDefinition)
        assertEquals("LaunchApp", launchCall.enclosingScope)
    }

    @Test
    fun testIncrementalCacheInvalidation_updateAndRemove() {
        val setupFile = File(testProjectRoot, "app/src/main/jni/Menu/Setup.h")

        // 1. Initial scan is cached
        val initialDefs = AcsRepoMap.findSymbolDefinitions(testProjectRoot, "EXTRA_ITEM")
        assertTrue(initialDefs.isEmpty())

        // 2. Modify file on disk
        setupFile.appendText("\n#define EXTRA_ITEM 128\n")

        // 3. Update incrementally
        val updatedSymbols = AcsRepoMap.updateFile(testProjectRoot, setupFile)
        assertNotNull(updatedSymbols)
        assertTrue(updatedSymbols!!.symbols.any { it.name == "EXTRA_ITEM" })

        val foundAfterUpdate = AcsRepoMap.findSymbolDefinitions(testProjectRoot, "EXTRA_ITEM")
        assertEquals(1, foundAfterUpdate.size)

        // 4. Remove incrementally
        AcsRepoMap.removeFile(testProjectRoot, setupFile)
        setupFile.delete()
        val foundAfterRemove = AcsRepoMap.findSymbolDefinitions(testProjectRoot, "EXTRA_ITEM")
        assertTrue(foundAfterRemove.isEmpty())
    }

    @Test
    fun testToolOrchestrator_findSymbolReferences_execution() = runBlocking {
        val orchestrator = ToolOrchestrator(
            transportClient = UnifiedTransportClient(),
            webSearchManager = WebSearchManager(),
            safeUrlFetcher = SafeUrlFetcher(),
            projectRootProvider = { testProjectRoot }
        )

        val toolCall = ToolCall(
            id = "call-refs-1",
            name = AgentToolRegistry.TOOL_FIND_SYMBOL_REFERENCES,
            argumentsJson = JSONObject().apply {
                put("symbol", "InitMenu")
            }.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(toolCall)
        assertFalse(result.isError)
        assertTrue(result.content.contains("--- Definitions ---"))
        assertTrue(result.content.contains("--- Usages / Call Sites ---"))
        assertTrue(result.content.contains("[DEF]"))
        assertTrue(result.content.contains("[CALL]"))
        assertTrue(result.content.contains("in scope LaunchApp"))
    }

    @Test
    fun testConversationCompactor_safeguardsSymbolNavigation() {
        val longContent = buildString {
            appendLine("Found 3 reference(s) for symbol 'InitMenu':")
            appendLine("--- Definitions ---")
            appendLine("- [DEF] Setup.h:55:1 void InitMenu(int w, int h);")
            appendLine("- [DEF] Main.cpp:69:1 void InitMenu(...) {}")
            appendLine("--- Usages ---")
            appendLine("- [CALL] Main.cpp:74:1 (in scope LaunchApp): InitMenu(1920, 1080);")
            for (i in 1..30) {
                appendLine("- [CALL] Main.cpp:${80 + i}:1 (in scope Worker$i): InitMenu(1920, 1080); // padding reference entry to exceed 1500 chars")
            }
        }

        val messages = listOf(
            UnifiedChatMessage(role = ChatRole.USER, content = "Refactor InitMenu"),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Looking up references.",
                toolCalls = listOf(ToolCall(id = "c1", name = "find_symbol_references", argumentsJson = "{}"))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = longContent,
                toolResult = ToolResultMessage("c1", "find_symbol_references", longContent)
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Now reading files.",
                toolCalls = listOf(ToolCall(id = "c2", name = "read_project_file", argumentsJson = "{}"))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "File content 1",
                toolResult = ToolResultMessage("c2", "read_project_file", "File content 1")
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Reading second file.",
                toolCalls = listOf(ToolCall(id = "c3", name = "read_project_file", argumentsJson = "{}"))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "File content 2",
                toolResult = ToolResultMessage("c3", "read_project_file", "File content 2")
            ),
            UnifiedChatMessage(role = ChatRole.USER, content = "Now proceed with editing.")
        )

        val compaction = ConversationCompactor.compactMessages(
            messages = messages,
            capabilities = null,
            systemPrompt = "System",
            tools = emptyList(),
            forceCompaction = true
        )

        assertTrue(compaction.compacted)
        val toolMsg = compaction.messages.firstOrNull { it.toolResult?.toolName == "find_symbol_references" }
        assertNotNull(toolMsg)
        assertTrue("Symbol references should be preserved", toolMsg!!.content.contains("symbol references preserved for context"))
    }

    @Test
    fun testMultiDeclarationParsing_cGlobalExternAndLocals() {
        val testHeader = File(testProjectRoot, "app/src/main/jni/Menu/MultiDecl.h")
        testHeader.writeText(
            """
            #pragma once
            extern bool iconValid, settingsValid, initValid;
            int screenWidth = 1920, screenHeight = 1080;
            float minZoom, maxZoom = 2.0f;
            """.trimIndent()
        )

        AcsRepoMap.updateFile(testProjectRoot, testHeader)

        // 1. Verify comma-separated extern variables (addresses the initValid gap)
        val initValidDefs = AcsRepoMap.findSymbolDefinitions(testProjectRoot, "initValid")
        assertEquals("Must find definition of initValid from multi-declaration", 1, initValidDefs.size)
        val initSym = initValidDefs.first().second
        assertEquals("initValid", initSym.name)
        assertEquals(SymbolKind.VARIABLE, initSym.kind)
        assertEquals(2, initSym.line)

        val iconDefs = AcsRepoMap.findSymbolDefinitions(testProjectRoot, "iconValid")
        assertEquals(1, iconDefs.size)
        assertEquals(SymbolKind.VARIABLE, iconDefs.first().second.kind)

        val settingsDefs = AcsRepoMap.findSymbolDefinitions(testProjectRoot, "settingsValid")
        assertEquals(1, settingsDefs.size)
        assertEquals(SymbolKind.VARIABLE, settingsDefs.first().second.kind)

        // 2. Verify initialized multi-declaration
        val widthDefs = AcsRepoMap.findSymbolDefinitions(testProjectRoot, "screenWidth")
        assertEquals(1, widthDefs.size)
        assertEquals(SymbolKind.VARIABLE, widthDefs.first().second.kind)

        val heightDefs = AcsRepoMap.findSymbolDefinitions(testProjectRoot, "screenHeight")
        assertEquals(1, heightDefs.size)
        assertEquals(SymbolKind.VARIABLE, heightDefs.first().second.kind)

        // 3. Verify mixed uninitialized and initialized
        val minZoomDefs = AcsRepoMap.findSymbolDefinitions(testProjectRoot, "minZoom")
        assertEquals(1, minZoomDefs.size)

        val maxZoomDefs = AcsRepoMap.findSymbolDefinitions(testProjectRoot, "maxZoom")
        assertEquals(1, maxZoomDefs.size)
    }

    @Test
    fun testAccurateReferenceClassification_distinguishesCallsFromCommentsAndStrings() {
        val testFile = File(testProjectRoot, "app/src/main/jni/Menu/DialogTest.cpp")
        testFile.writeText(
            """
            void setDialog(int id);
            void setDialog(int id) {
                // empty
            }
            void runTest() {
                setDialog(1); // active call
                //setDialog(2); // commented call
                /* setDialog(3); */
                const char* name = "setDialog";
            }
            """.trimIndent()
        )

        AcsRepoMap.updateFile(testProjectRoot, testFile)

        val refs = AcsRepoMap.findSymbolReferences(testProjectRoot, "setDialog")
        assertTrue("Must find at least 5 references to setDialog", refs.size >= 5)

        val defs = refs.filter { it.category == ReferenceCategory.DEFINITION }
        val activeCalls = refs.filter { it.category == ReferenceCategory.ACTIVE_REFERENCE }
        val comments = refs.filter { it.category == ReferenceCategory.COMMENT }
        val strings = refs.filter { it.category == ReferenceCategory.STRING_LITERAL }

        assertEquals("Must find 2 definitions (declaration + body)", 2, defs.size)
        assertEquals("Must find exactly 1 active call (setDialog(1))", 1, activeCalls.size)
        assertEquals("runTest", activeCalls.first().enclosingScope)
        assertTrue(activeCalls.first().lineContent.contains("setDialog(1)"))

        assertTrue("Must identify commented calls as COMMENT", comments.size >= 2)
        assertTrue("Commented call must have category COMMENT", comments.any { it.lineContent.contains("//setDialog(2)") })
        assertFalse("Commented call must not be marked definition", comments.first { it.lineContent.contains("//setDialog(2)") }.isDefinition)

        assertEquals("Must identify string literal occurrence", 1, strings.size)
        assertTrue(strings.first().lineContent.contains("\"setDialog\""))
    }

    @Test
    fun testTypeHierarchy_cppInheritanceAndOverrides() {
        val hierarchyFile = File(testProjectRoot, "app/src/main/jni/Menu/Hierarchy.h")
        hierarchyFile.writeText(
            """
            class BaseWidget {
            public:
                virtual void draw() = 0;
            };

            class Button : public BaseWidget {
            public:
                void draw() override {}
            };

            class IconButton : public Button {
            public:
                void draw() override {}
            };
            """.trimIndent()
        )

        AcsRepoMap.updateFile(testProjectRoot, hierarchyFile)

        val hierarchy = AcsRepoMap.getTypeHierarchy(testProjectRoot, "Button")
        assertNotNull("Type hierarchy for Button must not be null", hierarchy)
        assertEquals("Button", hierarchy!!.typeName)
        assertEquals(SymbolKind.CLASS, hierarchy.kind)
        assertTrue("Supertypes must contain BaseWidget", hierarchy.superTypes.contains("BaseWidget"))
        assertTrue("Subtypes must contain IconButton", hierarchy.subTypes.contains("IconButton"))
        assertTrue("Overrides must contain draw", hierarchy.overrides.any { it.methodName == "draw" && it.enclosingType == "Button" })

        val supertypes = AcsRepoMap.findSupertypes(testProjectRoot, "Button")
        assertEquals(listOf("BaseWidget"), supertypes)

        val subtypes = AcsRepoMap.findSubtypes(testProjectRoot, "BaseWidget")
        assertTrue("Subtypes of BaseWidget must include Button", subtypes.contains("Button"))
    }

    @Test
    fun testTypeHierarchy_javaInheritanceAndOverrides() {
        val javaFile = File(testProjectRoot, "app/src/main/java/com/example/CustomDialog.java")
        javaFile.writeText(
            """
            package com.example;

            public class CustomDialog extends BaseDialog implements DialogInterface {
                @Override
                public void dismiss() {}
            }
            """.trimIndent()
        )

        AcsRepoMap.updateFile(testProjectRoot, javaFile)

        val hierarchy = AcsRepoMap.getTypeHierarchy(testProjectRoot, "CustomDialog")
        assertNotNull("Type hierarchy for CustomDialog must not be null", hierarchy)
        assertTrue("Supertypes must contain BaseDialog", hierarchy!!.superTypes.contains("BaseDialog"))
        assertTrue("Supertypes must contain DialogInterface", hierarchy.superTypes.contains("DialogInterface"))
        assertTrue("Overrides must contain dismiss", hierarchy.overrides.any { it.methodName == "dismiss" })
    }

    @Test
    fun testResolveDiagnosticMissingSymbols_withExpandedPatternsAndCodeSnippets() {
        val declFile = File(testProjectRoot, "app/src/main/jni/Menu/Globals.h")
        declFile.writeText(
            """
            #pragma once
            extern bool iconValid, settingsValid, initValid;
            void ConfigureRenderer();
            """.trimIndent()
        )

        AcsRepoMap.updateFile(testProjectRoot, declFile)

        // Diagnostic with Clang's "use of undeclared identifier"
        val diag = BuildDiagnostic(
            filePath = "app/src/main/jni/Main.cpp",
            line = 42,
            column = 10,
            severity = BuildDiagnostic.Severity.ERROR,
            stage = "NDK",
            message = "use of undeclared identifier 'initValid'",
            rawLine = "Main.cpp:42:10: error: use of undeclared identifier 'initValid'"
        )

        val hints = AcsRepoMap.resolveDiagnosticMissingSymbols(testProjectRoot, listOf(diag))
        assertEquals("Must produce 1 hint for initValid", 1, hints.size)
        val hint = hints.first()
        assertTrue("Hint must mention symbol initValid", hint.contains("Symbol 'initValid' is defined"))
        assertTrue("Hint must mention defining file Globals.h", hint.contains("Globals.h"))
        assertTrue("Hint must include 3-line code snippet from disk", hint.contains("extern bool iconValid, settingsValid, initValid;"))
    }

    @Test
    fun testToolOrchestrator_getTypeHierarchy_execution() = runBlocking {
        val hierarchyFile = File(testProjectRoot, "app/src/main/jni/Menu/WidgetTree.h")
        hierarchyFile.writeText(
            """
            class BaseComponent {};
            class Label : public BaseComponent {};
            """.trimIndent()
        )
        AcsRepoMap.updateFile(testProjectRoot, hierarchyFile)

        val orchestrator = ToolOrchestrator(
            transportClient = UnifiedTransportClient(),
            webSearchManager = WebSearchManager(),
            safeUrlFetcher = SafeUrlFetcher(),
            projectRootProvider = { testProjectRoot }
        )

        val toolCall = ToolCall(
            id = "call-type-1",
            name = AgentToolRegistry.TOOL_GET_TYPE_HIERARCHY,
            argumentsJson = JSONObject().apply {
                put("typeName", "Label")
            }.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(toolCall)
        assertFalse(result.isError)
        assertTrue(result.content.contains("=== TYPE HIERARCHY: Label [class] ==="))
        assertTrue(result.content.contains("BaseComponent"))
    }

    @Test
    fun testConversationCompactor_safeguardsTypeHierarchy() {
        val longContent = buildString {
            appendLine("=== TYPE HIERARCHY: ModMenuController [class] ===")
            appendLine("--- Supertypes (Base Classes & Interfaces) ---")
            appendLine("- BaseController")
            appendLine("--- Subtypes & Implementers in Project ---")
            appendLine("- ExtendedModMenuController")
            for (i in 1..30) {
                appendLine("- SubtypeEntry$i : public ModMenuController; // padding hierarchy output to exceed 1500 chars")
            }
        }

        val messages = listOf(
            UnifiedChatMessage(role = ChatRole.USER, content = "Inspect ModMenuController hierarchy"),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Fetching hierarchy.",
                toolCalls = listOf(ToolCall(id = "c1", name = "get_type_hierarchy", argumentsJson = "{}"))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = longContent,
                toolResult = ToolResultMessage("c1", "get_type_hierarchy", longContent)
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Now reading files.",
                toolCalls = listOf(ToolCall(id = "c2", name = "read_project_file", argumentsJson = "{}"))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "File content 1",
                toolResult = ToolResultMessage("c2", "read_project_file", "File content 1")
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Reading second file.",
                toolCalls = listOf(ToolCall(id = "c3", name = "read_project_file", argumentsJson = "{}"))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "File content 2",
                toolResult = ToolResultMessage("c3", "read_project_file", "File content 2")
            ),
            UnifiedChatMessage(role = ChatRole.USER, content = "Now proceed with editing.")
        )

        val compaction = ConversationCompactor.compactMessages(
            messages = messages,
            capabilities = null,
            systemPrompt = "System",
            tools = emptyList(),
            forceCompaction = true
        )

        assertTrue(compaction.compacted)
        val toolMsg = compaction.messages.firstOrNull { it.toolResult?.toolName == "get_type_hierarchy" }
        assertNotNull(toolMsg)
        assertTrue("Type hierarchy should be preserved", toolMsg!!.content.contains("type hierarchy preserved for context"))
    }
}
