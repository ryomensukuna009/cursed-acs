package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.chat.ChatConversation
import com.tom.rv2ide.artificial.chat.ChatFileModification
import com.tom.rv2ide.artificial.chat.ChatMarkdownRenderer
import com.tom.rv2ide.artificial.chat.ChatMessageItem
import com.tom.rv2ide.artificial.chat.ConversationSessionManager
import com.tom.rv2ide.artificial.chat.MarkdownBlock
import com.tom.rv2ide.artificial.transport.ChatRole
import com.tom.rv2ide.artificial.transport.CitationItem
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase7ChatStateAndHistoryTest {

    @Test
    fun `chat conversation serialization preserves multi-turn messages, citations, and file modifications`() {
        val turn1User = ChatMessageItem(
            id = "u1",
            conversationId = "conv-1",
            role = ChatRole.USER,
            content = "Remember the secret codeword ORBIT-749."
        )
        val turn1Assistant = ChatMessageItem(
            id = "a1",
            conversationId = "conv-1",
            role = ChatRole.ASSISTANT,
            content = "I have noted the secret codeword **ORBIT-749**.",
            providerId = "google_gemini",
            providerName = "Google Gemini",
            modelId = "gemini-2.5-flash",
            citations = listOf(
                CitationItem(
                    title = "Android Developer Guide",
                    url = "https://developer.android.com",
                    snippet = "Official Android docs",
                    sourceType = "gemini"
                )
            ),
            fileModifications = listOf(
                ChatFileModification(
                    filePath = "/proj/app/src/main/java/MainActivity.kt",
                    fileName = "MainActivity.kt",
                    isNewFile = false,
                    success = true
                )
            )
        )
        val turn2User = ChatMessageItem(
            id = "u2",
            conversationId = "conv-1",
            role = ChatRole.USER,
            content = "What codeword did I tell you earlier?"
        )
        val turn2Assistant = ChatMessageItem(
            id = "a2",
            conversationId = "conv-1",
            role = ChatRole.ASSISTANT,
            content = "You told me the codeword is `ORBIT-749`."
        )

        val conv = ChatConversation(
            id = "conv-1",
            title = "Remember codeword",
            projectPath = "/proj",
            messages = listOf(turn1User, turn1Assistant, turn2User, turn2Assistant)
        )

        val json = conv.toJson()
        val restored = ChatConversation.fromJson(json)

        assertEquals(4, restored.messages.size)
        assertEquals("Remember the secret codeword ORBIT-749.", restored.messages[0].content)
        assertEquals("I have noted the secret codeword **ORBIT-749**.", restored.messages[1].content)
        assertEquals(1, restored.messages[1].citations.size)
        assertEquals("https://developer.android.com", restored.messages[1].citations[0].url)
        assertEquals(1, restored.messages[1].fileModifications.size)
        assertEquals("MainActivity.kt", restored.messages[1].fileModifications[0].fileName)
        assertEquals("What codeword did I tell you earlier?", restored.messages[2].content)
        assertEquals("You told me the codeword is `ORBIT-749`.", restored.messages[3].content)
    }

    @Test
    fun `ConversationSessionManager retains multi-turn history, separates tool status from content, and builds model request messages`() {
        val tempDir = Files.createTempDirectory("roxum_phase7_session").toFile()
        try {
            val manager = ConversationSessionManager(tempDir)
            manager.setActiveProjectPath("/storage/emulated/0/MyProject")

            // Turn 1: User sends message + Assistant streams + completes
            val u1 = manager.appendUserMessage("My project secret codeword is ORBIT-749.")
            val a1 = manager.beginAssistantMessage(
                providerId = "google_gemini",
                providerName = "Google Gemini",
                modelId = "gemini-2.5-flash"
            )
            manager.updateAssistantStreamingState(
                messageId = a1.id,
                fullTextSoFar = "I will remember ",
                activeToolStatus = "Reading MainActivity.kt..."
            )
            // Tool status updates must not overwrite streamed text
            val midStream = manager.getActiveConversation().messages.last()
            assertEquals("I will remember", midStream.content)
            assertEquals("Reading MainActivity.kt...", midStream.activeToolStatus)

            manager.completeAssistantMessage(
                messageId = a1.id,
                finalModelText = "I will remember ORBIT-749 for future turns."
            )

            // Turn 2: User sends follow-up question
            val followUpRaw = "What was the secret codeword I told you?"
            manager.appendUserMessage(followUpRaw)
            val a2 = manager.beginAssistantMessage(
                providerId = "google_gemini",
                providerName = "Google Gemini",
                modelId = "gemini-2.5-flash"
            )

            // Build model request messages during Turn 2
            val requestMessages = manager.buildModelRequestMessages(
                conversationId = null,
                latestUserPromptWithProjectContext = "=== PROJECT ===\n$followUpRaw",
                latestRawUserText = followUpRaw
            )

            // Should contain Turn 1 USER, Turn 1 ASSISTANT, and Turn 2 USER (with project context)
            assertEquals(3, requestMessages.size)
            assertEquals(ChatRole.USER, requestMessages[0].role)
            assertTrue(requestMessages[0].content.contains("ORBIT-749"))
            assertEquals(ChatRole.ASSISTANT, requestMessages[1].role)
            assertTrue(requestMessages[1].content.contains("I will remember ORBIT-749"))
            assertEquals(ChatRole.USER, requestMessages[2].role)
            assertTrue(requestMessages[2].content.contains(followUpRaw))

            manager.completeAssistantMessage(
                messageId = a2.id,
                finalModelText = "Your secret codeword is ORBIT-749."
            )

            // Verify all 4 messages remain visible in UI conversation list
            val finalConv = manager.getActiveConversation()
            assertEquals(4, finalConv.messages.size)
            assertEquals(u1.id, finalConv.messages[0].id)

            // Verify disk reload preserves all 4 turns
            val reloadedManager = ConversationSessionManager(tempDir)
            val reloadedConv = reloadedManager.getActiveConversation("/storage/emulated/0/MyProject")
            assertEquals(4, reloadedConv.messages.size)
            assertEquals("Your secret codeword is ORBIT-749.", reloadedConv.messages[3].content)

            // Verify prepareRetryOrRegenerate on Turn 2 assistant bubble removes only a2 and keeps u1, a1, u2
            val retryPrompt = reloadedManager.prepareRetryOrRegenerate(a2.id)
            assertEquals(followUpRaw, retryPrompt)
            assertEquals(3, reloadedManager.getActiveConversation().messages.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `markdown block parser splits prose and fenced code blocks including unclosed streaming fences`() {
        val streamingInput = """
            Here is the updated Kotlin class:
            ```kotlin
            package com.example.app
            
            class Greeter {
                fun greet(): String = "Hello"
            }
            ```
            And here is an in-progress XML block:
            ```xml
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
        """.trimIndent()

        val blocks = ChatMarkdownRenderer.parseBlocks(streamingInput)
        assertEquals(4, blocks.size)

        assertTrue(blocks[0] is MarkdownBlock.TextBlock)
        assertEquals("Here is the updated Kotlin class:", (blocks[0] as MarkdownBlock.TextBlock).text)

        assertTrue(blocks[1] is MarkdownBlock.CodeBlock)
        val ktBlock = blocks[1] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", ktBlock.language)
        assertTrue(ktBlock.isComplete)
        assertTrue(ktBlock.code.contains("class Greeter"))

        assertTrue(blocks[2] is MarkdownBlock.TextBlock)
        assertTrue(blocks[3] is MarkdownBlock.CodeBlock)
        val xmlBlock = blocks[3] as MarkdownBlock.CodeBlock
        assertEquals("xml", xmlBlock.language)
        assertFalse("Unclosed streaming fence should be marked incomplete without crashing", xmlBlock.isComplete)
        assertTrue(xmlBlock.code.contains("<LinearLayout"))
    }
}
