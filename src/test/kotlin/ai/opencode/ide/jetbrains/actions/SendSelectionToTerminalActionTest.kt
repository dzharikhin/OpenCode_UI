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

    fun testDirectorySelectionSendsDirectoryPath() {
        val port = server?.activePort ?: 0
        val service = project.service<OpenCodeService>()
        val sm = project.service<SessionManager>()
        val dvs = project.service<DiffViewerService>()
        val client = OpenCodeApiClient("127.0.0.1", port)

        // Inject dependencies with real API client talking to fake server
        service.setTestDeps(sm, dvs, client)

        // Force connection state
        // We use reflection to set 'isConnected' to true so 'focusOrCreateTerminalAndPaste' 
        // believes we are connected and proceeds to paste.
        val connectedField = OpenCodeService::class.java.getDeclaredField("isConnected")
        connectedField.isAccessible = true
        (connectedField.get(service) as AtomicBoolean).set(true)
        
        // Also simulate port running so 'focusOrCreateTerminal' checks pass
        val portField = OpenCodeService::class.java.getDeclaredField("port")
        portField.isAccessible = true
        portField.set(service, port)

        // 1. Setup Virtual File System
        val dir = myFixture.tempDirFixture.findOrCreateDir("root/mydir")
        myFixture.tempDirFixture.createFile("root/mydir/file1.txt")
        myFixture.tempDirFixture.createFile("root/mydir/file2.txt")

        // 2. Create Action Event with Directory Selected
        val action = SendSelectionToTerminalAction()
        val dataContext = MapDataContext()
        dataContext.put(CommonDataKeys.PROJECT, project)
        dataContext.put(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(dir))
        dataContext.put(CommonDataKeys.EDITOR, null)

        val event = AnActionEvent.createFromDataContext("place", null, dataContext)

        // 3. Perform Action
        action.actionPerformed(event)

        // 4. Verify
        // Wait for server to receive request (async). Shared IDE executors can be
        // heavily delayed while the test JVM warms up, so allow a generous window.
        val prompts = waitForPrompts(1, 10_000)
        assertFalse("Server should have received a prompt", prompts.isEmpty())
        
        val lastPrompt = prompts.last()
        println("Received prompt JSON: $lastPrompt")
        
        // Expected JSON: {"text": "@root/mydir "} (approximate, based on relative path)
        // The relative path logic in SendSelectionToTerminalAction depends on project base path.
        // In test fixture, project base path is usually the temp dir root.
        
        // We verify that it contains the directory path and DOES NOT contain file paths.
        assertTrue("Should contain directory path", lastPrompt.contains("mydir"))
        assertFalse("Should NOT contain file1", lastPrompt.contains("file1.txt"))
        assertFalse("Should NOT contain file2", lastPrompt.contains("file2.txt"))
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
}
