package ai.opencode.ide.jetbrains.actions

import ai.opencode.ide.jetbrains.OpenCodeService
import ai.opencode.ide.jetbrains.SendSelectionToTerminalAction
import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.integration.FakeOpenCodeServer
import ai.opencode.ide.jetbrains.session.SessionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.atomic.AtomicBoolean

class SendSelectionToTerminalActionTest : BasePlatformTestCase() {

    private var server: FakeOpenCodeServer? = null

    override fun setUp() {
        super.setUp()
        // Setup Fake Server on random port
        server = FakeOpenCodeServer(0).apply { start() }
    }

    override fun tearDown() {
        try {
            server?.stop()
        } finally {
            super.tearDown()
        }
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = OpenCodeService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getField(target: Any, name: String): Any? {
        val field = OpenCodeService::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    private fun setConnected(service: OpenCodeService, connected: Boolean) {
        (getField(service, "isConnected") as AtomicBoolean).set(connected)
    }

    private fun injectClient(service: OpenCodeService, client: OpenCodeApiClient) {
        service.setTestDeps(project.service(), project.service(), client)
    }

    private fun waitForPrompts(count: Int, timeoutMs: Long = 20_000): List<String> {
        val start = System.currentTimeMillis()
        while ((server?.receivedPrompts?.size ?: 0) < count && System.currentTimeMillis() - start < timeoutMs) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
        return server?.receivedPrompts ?: emptyList()
    }

    fun testFailedPasteIsNotRetried() {
        val s = server ?: return
        s.failNextPosts(1)
        val service = project.service<OpenCodeService>()
        injectClient(service, OpenCodeApiClient("127.0.0.1", s.activePort))
        setConnected(service, true)

        service.focusOrCreateTerminalAndPaste("@src/once.kt ")

        Thread.sleep(1500)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("A failed paste must not be retried", s.receivedPrompts.isEmpty())
    }

    private fun invokeSchedulePaste(service: OpenCodeService, text: String, attempts: Int, delayMs: Long) {
        val m = OpenCodeService::class.java.getDeclaredMethod(
            "schedulePasteAttempt", String::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType
        )
        m.isAccessible = true
        m.invoke(service, text, attempts, delayMs)
    }

    /**
     * Opens a file in the editor, or returns null in headless environments
     * without fonts (no libfontconfig/fontconfig configuration), where the
     * editor cannot be instantiated. The caller should skip the test in that case.
     */
    private fun openEditorForOrSkip(file: VirtualFile): Editor? {
        return try {
            myFixture.openFileInEditor(file)
            myFixture.editor
        } catch (e: RuntimeException) {
            if (e.message?.contains("Fontconfig", ignoreCase = true) == true ||
                e.cause?.message?.contains("Fontconfig", ignoreCase = true) == true
            ) {
                println("Skipping editor-based test: headless environment without fonts (${e.message})")
                null
            } else {
                throw e
            }
        }
    }

    fun testProjectViewFilesAreTypedAsMentions() {
        val s = server ?: return
        val service = project.service<OpenCodeService>()
        prepareConnectedService(s)

        val typedBatches = mutableListOf<List<String>>()
        service.typedMentionChannel = { paths ->
            typedBatches.add(paths)
            true
        }
        try {
            val file1 = myFixture.tempDirFixture.createFile("root/one.kt", "a\n")
            val file2 = myFixture.tempDirFixture.createFile("root/two.kt", "b\n")

            performAction(editor = null, files = arrayOf(file1, file2))

            assertEquals("Files must be typed as one mention batch", 1, typedBatches.size)
            val paths = typedBatches.single()
            assertTrue("Must contain the relative path of file1: $paths", paths.any { it.endsWith("root/one.kt") })
            assertTrue("Must contain the relative path of file2: $paths", paths.any { it.endsWith("root/two.kt") })
            // The typed path must not also push the plain-text payload.
            assertTrue(
                "HTTP append-prompt must not be used when typed mentions delivered",
                s.receivedPrompts.isEmpty(),
            )
        } finally {
            service.typedMentionChannel = null
        }
    }

    fun testBuildMentionChunksTypesQueryThenEnter() {
        val service = project.service<OpenCodeService>()
        val chunks = service.buildMentionChunks(listOf("a.kt", "src/b.kt"))
        assertEquals(
            listOf(" @a.kt", "\r", " @src/b.kt", "\r"),
            chunks,
        )
    }

    fun testEditorSelectionTypesLinePinnedMention() {
        val s = server ?: return
        val service = project.service<OpenCodeService>()
        prepareConnectedService(s)

        val typedBatches = mutableListOf<List<String>>()
        service.typedMentionChannel = { paths ->
            typedBatches.add(paths)
            true
        }
        try {
            val file = myFixture.tempDirFixture.createFile("root/sel.kt", "line1\nline2\nline3\n")
            val editor = openEditorForOrSkip(file) ?: return

            // Select all of line 2 (0-based 1) - single line pin
            val startOffset = editor.document.getLineStartOffset(1)
            val endOffset = editor.document.getLineEndOffset(1)
            editor.selectionModel.setSelection(startOffset, endOffset)

            performAction(editor = editor, files = arrayOf(file))

            assertEquals("Selection must be typed as a line-pinned mention", 1, typedBatches.size)
            assertTrue(
                "Expected line-pinned path of sel.kt: ${typedBatches.single()}",
                typedBatches.single().single().endsWith("root/sel.kt#2"),
            )
            assertTrue("HTTP append-prompt must not be used for typed mentions", s.receivedPrompts.isEmpty())
        } finally {
            service.typedMentionChannel = null
        }
    }

    fun testEditorMultiLineSelectionTypesLineRangeMention() {
        val s = server ?: return
        val service = project.service<OpenCodeService>()
        prepareConnectedService(s)

        val typedBatches = mutableListOf<List<String>>()
        service.typedMentionChannel = { paths ->
            typedBatches.add(paths)
            true
        }
        try {
            val file = myFixture.tempDirFixture.createFile("root/range.kt", "line1\nline2\nline3\n")
            val editor = openEditorForOrSkip(file) ?: return

            // Select lines 2-3 (0-based 1..2)
            val startOffset = editor.document.getLineStartOffset(1)
            val endOffset = editor.document.getLineEndOffset(2)
            editor.selectionModel.setSelection(startOffset, endOffset)

            performAction(editor = editor, files = arrayOf(file))

            assertEquals("Multi-line selection must be typed as a line-range mention", 1, typedBatches.size)
            assertTrue(
                "Expected line-range path of range.kt: ${typedBatches.single()}",
                typedBatches.single().single().endsWith("root/range.kt#2-3"),
            )
            assertTrue("HTTP append-prompt must not be used for typed mentions", s.receivedPrompts.isEmpty())
        } finally {
            service.typedMentionChannel = null
        }
    }

    fun testEditorSelectionEndingAtLineStartExcludesTrailingLine() {
        val s = server ?: return
        val service = project.service<OpenCodeService>()
        prepareConnectedService(s)

        val typedBatches = mutableListOf<List<String>>()
        service.typedMentionChannel = { paths ->
            typedBatches.add(paths)
            true
        }
        try {
            val file = myFixture.tempDirFixture.createFile("root/trim.kt", "line1\nline2\nline3\nline4\nline5\n")
            val editor = openEditorForOrSkip(file) ?: return

            // Drag from start of line 2 (0-based 1) to start of line 5 (0-based 4):
            // the selection visually ends on line 4, so the chip must pin #2-4.
            val startOffset = editor.document.getLineStartOffset(1)
            val endOffset = editor.document.getLineStartOffset(4)
            editor.selectionModel.setSelection(startOffset, endOffset)

            performAction(editor = editor, files = arrayOf(file))

            assertEquals("Selection must be typed as a mention", 1, typedBatches.size)
            assertTrue(
                "Trailing line at column 0 must be excluded: ${typedBatches.single()}",
                typedBatches.single().single().endsWith("root/trim.kt#2-4"),
            )
        } finally {
            service.typedMentionChannel = null
        }
    }

    fun testEditorNoSelectionTypesWholeFileMention() {
        val s = server ?: return
        val service = project.service<OpenCodeService>()
        prepareConnectedService(s)

        val typedBatches = mutableListOf<List<String>>()
        service.typedMentionChannel = { paths ->
            typedBatches.add(paths)
            true
        }
        try {
            val file = myFixture.tempDirFixture.createFile("root/whole.kt", "line1\nline2\n")
            val editor = openEditorForOrSkip(file) ?: return
            editor.selectionModel.removeSelection()

            performAction(editor = editor, files = arrayOf(file))

            assertEquals("Whole-file mention must be typed, not pushed", 1, typedBatches.size)
            assertTrue(
                "Expected relative path of whole.kt: ${typedBatches.single()}",
                typedBatches.single().single().endsWith("root/whole.kt"),
            )
            assertTrue("No line pin - HTTP must not be used", s.receivedPrompts.isEmpty())
        } finally {
            service.typedMentionChannel = null
        }
    }

    fun testSpacePathFallsBackToQuotedText() {
        val s = server ?: return
        val service = project.service<OpenCodeService>()
        prepareConnectedService(s)

        val typedBatches = mutableListOf<List<String>>()
        service.typedMentionChannel = { paths ->
            typedBatches.add(paths)
            true
        }
        try {
            // The @-autocomplete query must not contain whitespace, so space paths
            // cannot be typed; they are appended as quoted text instead.
            val file = myFixture.tempDirFixture.createFile("root/space file.txt", "x\n")

            performAction(editor = null, files = arrayOf(file))

            assertTrue("Space paths must not be typed into the @-menu", typedBatches.isEmpty())
            val prompts = waitForPrompts(1)
            val last = prompts.last()
            // Raw JSON: the quote after @ appears escaped as \" in the body.
            assertTrue(
                "Expected quoted @path in prompt but got: $last",
                last.contains("@\\\"") && last.contains("space file.txt"),
            )
        } finally {
            service.typedMentionChannel = null
        }
    }

    fun testDirectorySelectionFallsBackToText() {
        val s = server ?: return
        val service = project.service<OpenCodeService>()
        prepareConnectedService(s)

        val typedBatches = mutableListOf<List<String>>()
        service.typedMentionChannel = { paths ->
            typedBatches.add(paths)
            true
        }
        try {
            val dir = myFixture.tempDirFixture.findOrCreateDir("root/mydir")
            myFixture.tempDirFixture.createFile("root/mydir/file1.txt")

            performAction(editor = null, files = arrayOf(dir))

            // The TUI cannot represent directories via mentions (chips are file
            // parts), so the action must fall back to the plain-text payload.
            assertTrue("Directories must not be typed as mentions", typedBatches.isEmpty())
            val prompts = waitForPrompts(1)
            assertTrue("Directory selection must use the text fallback", prompts.isNotEmpty())
            assertTrue(prompts.last().contains("mydir"))
            assertFalse("Directory must not swallow contained files", prompts.last().contains("file1.txt"))
        } finally {
            service.typedMentionChannel = null
        }
    }

    fun testPasteRetriesWhileApiClientNullThenDelivers() {
        val s = server ?: return
        val service = project.service<OpenCodeService>()
        val client = OpenCodeApiClient("127.0.0.1", s.activePort)
        injectClient(service, client)

        setField(service, "apiClient", null)
        invokeSchedulePaste(service, "@src/late.kt ", 60, 100L)

        Thread.sleep(400)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("Nothing should be sent while apiClient is null", s.receivedPrompts.isEmpty())

        setField(service, "apiClient", client)
        val prompts = waitForPrompts(1)
        assertEquals(1, prompts.size)
        assertTrue(prompts[0].contains("src/late.kt"))
    }

    fun testPasteGivesUpWhenApiClientNeverAppears() {
        val s = server ?: return
        val service = project.service<OpenCodeService>()
        injectClient(service, OpenCodeApiClient("127.0.0.1", s.activePort))
        setField(service, "apiClient", null)

        invokeSchedulePaste(service, "@src/never.kt ", 3, 100L)

        Thread.sleep(800)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("Nothing should be sent when apiClient never appears", s.receivedPrompts.isEmpty())
    }

    fun testSpawnedServerAuthPropagation() {
        val service = project.service<OpenCodeService>()
        val apply = OpenCodeService::class.java.getDeclaredMethod("applySpawnedServerAuth", String::class.java)
        apply.isAccessible = true

        apply.invoke(service, "secret123")
        assertEquals("opencode", getField(service, "username"))
        assertEquals("secret123", getField(service, "password"))

        apply.invoke(service, " ")
        assertNull(getField(service, "username"))
        assertNull(getField(service, "password"))

        apply.invoke(service, null)
        assertNull(getField(service, "username"))
        assertNull(getField(service, "password"))
    }

    fun testSecuredServerRequiresCredentialsForAppendPrompt() {
        val secured = FakeOpenCodeServer(0, password = "secret123").apply { start() }
        try {
            val noAuth = OpenCodeApiClient("127.0.0.1", secured.activePort)
            assertFalse("Unauthenticated append-prompt must fail", noAuth.tuiAppendPrompt("@a/b "))
            assertTrue(secured.receivedPrompts.isEmpty())

            val withAuth = OpenCodeApiClient("127.0.0.1", secured.activePort, "opencode", "secret123")
            assertTrue("Authenticated append-prompt must succeed", withAuth.tuiAppendPrompt("@a/b "))
            assertEquals(1, secured.receivedPrompts.size)

            assertFalse("Health check must fail without credentials", noAuth.checkHealth(project.basePath ?: "."))
            assertTrue("Health check must succeed with credentials", withAuth.checkHealth(project.basePath ?: "."))
        } finally {
            secured.stop()
        }
    }

    fun testAppendPromptCarriesWorkspaceDirectory() {
        val s = server ?: return
        val client = OpenCodeApiClient("127.0.0.1", s.activePort)

        assertTrue(client.tuiAppendPrompt("@a/b ", "/tmp/proj dir"))
        assertTrue(waitForPrompts(1).isNotEmpty())
        val uri = s.receivedPromptUris.last()
        assertTrue(
            "URI must carry the encoded directory param: $uri",
            uri.contains("directory=%2Ftmp%2Fproj+dir"),
        )

        assertTrue(client.tuiAppendPrompt("@c/d "))
        assertTrue(waitForPrompts(2).size >= 2)
        val bareUri = s.receivedPromptUris.last()
        assertEquals("Directory-less call must omit the param: $bareUri", "/tui/append-prompt", bareUri)
    }

    // ==================== Helpers ====================

    private fun prepareConnectedService(s: FakeOpenCodeServer) {
        val service = project.service<OpenCodeService>()
        val sm = project.service<SessionManager>()
        val dvs = project.service<DiffViewerService>()
        val client = OpenCodeApiClient("127.0.0.1", s.activePort)
        service.setTestDeps(sm, dvs, client)
        setConnected(service, true)
        setField(service, "port", s.activePort)
    }

    private fun performAction(editor: Editor?, files: Array<VirtualFile>) {
        val action = SendSelectionToTerminalAction()
        val dataContext = MapDataContext()
        dataContext.put(CommonDataKeys.PROJECT, project)
        dataContext.put(CommonDataKeys.EDITOR, editor)
        dataContext.put(CommonDataKeys.VIRTUAL_FILE, files.firstOrNull())
        dataContext.put(CommonDataKeys.VIRTUAL_FILE_ARRAY, files)
        val event = AnActionEvent.createFromDataContext("test", null, dataContext)
        action.actionPerformed(event)
    }
}
