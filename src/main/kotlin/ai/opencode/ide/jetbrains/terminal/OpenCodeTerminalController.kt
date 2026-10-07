package ai.opencode.ide.jetbrains.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.cancel

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single seam between this plugin and the reworked (2026.2) Terminal API.
 *
 * Runs the OpenCode CLI in a real editor tab: a detached terminal session
 * (created via [TerminalToolWindowTabsManager]) is wrapped in a
 * [TerminalViewVirtualFile] and opened with the platform's own
 * `terminal-view-editor` provider - no Swing reparenting hacks.
 *
 * All methods that touch the terminal/editor APIs must be called on the EDT.
 */
class OpenCodeTerminalController(
    private val project: Project,
    private val onClosed: () -> Unit
) : Disposable {

    private val logger = Logger.getInstance(OpenCodeTerminalController::class.java)

    private var tab: TerminalToolWindowTab? = null
    private var file: VirtualFile? = null
    private var editorConnection: MessageBusConnection? = null
    private var commandTask: ScheduledFuture<*>? = null
    private var typingTask: ScheduledFuture<*>? = null
    private val closed = AtomicBoolean(true)

    companion object {
        private const val COMMAND_POLL_INTERVAL_MS = 500L
        private const val COMMAND_POLL_TIMEOUT_MS = 30_000L

        /**
         * Delay between typed chunks (also the initial poll interval). Gives the TUI
         * time to react between a typed @-mention query and the Enter chunk that
         * selects the highlighted autocomplete option (the file search is asynchronous).
         */
        internal const val TYPE_CHUNK_DELAY_MS = 250L
    }

    /** True while a live session exists and its editor tab is open. */
    fun isAlive(): Boolean {
        val f = file ?: return false
        if (closed.get()) return false
        return FileEditorManager.getInstance(project).isFileOpen(f)
    }

    /**
     * Creates a detached terminal tab and opens it in the editor area.
     * @return the virtual file backing the editor tab, or null on failure.
     */
    fun open(tabName: String, workingDirectory: String?, envVariables: Map<String, String>, command: String?): VirtualFile? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        close()

        val builder = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
            .tabName(tabName)
            .requestFocus(true)
            .deferSessionStartUntilUiShown(false)
            .shouldAddToToolWindow(false)
            .closeOnProcessTermination(false)
        if (!workingDirectory.isNullOrBlank()) builder.workingDirectory(workingDirectory)
        if (envVariables.isNotEmpty()) builder.envVariables(envVariables)

        val createdTab = try {
            builder.createTab()
        } catch (e: Exception) {
            logger.warn("[Terminal] Failed to create detached terminal tab", e)
            return null
        }

        tab = createdTab
        val viewFile = TerminalViewFiles.create(createdTab)
        file = viewFile
        closed.set(false)

        watchForUserClose()
        FileEditorManager.getInstance(project).openFile(viewFile, true)
        logger.info("[Terminal] Detached terminal tab opened: $tabName")

        if (command != null) scheduleCommand(command)
        return viewFile
    }

    /** Editor instance currently showing the terminal, if any. */
    fun editor(): FileEditor? = file?.let { FileEditorManager.getInstance(project).getEditors(it).firstOrNull() }

    /**
     * Types the given text chunks into the terminal sequentially, waiting for the
     * session to reach [TerminalViewSessionState.Running] first and delaying
     * [chunkDelayMs] between chunks so the TUI can react (e.g. render the
     * @-mention autocomplete menu before the Enter chunk selects it).
     *
     * Chunks are sent as plain keystrokes - never with bracketed paste mode,
     * which the TUI intercepts as file attachments and would break the
     * @-mention autocomplete.
     *
     * @return true when the typing sequence was scheduled.
     */
    fun typeChunks(chunks: List<String>, chunkDelayMs: Long = TYPE_CHUNK_DELAY_MS): Boolean {
        if (chunks.isEmpty()) return false
        typingTask?.cancel(false)
        typingTask = AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { typeStep(chunks.iterator(), chunkDelayMs, System.currentTimeMillis()) },
            chunkDelayMs,
            TimeUnit.MILLISECONDS
        )
        return true
    }

    /** Sends one chunk per tick, re-polling until the session is Running. */
    private fun typeStep(iterator: Iterator<String>, delayMs: Long, startedAt: Long) {
        if (closed.get() || project.isDisposed) return
        val v = tab?.view ?: return
        when (v.sessionState.value) {
            is TerminalViewSessionState.Running -> {
                if (!iterator.hasNext()) return
                val chunk = iterator.next()
                ApplicationManager.getApplication().invokeLater {
                    if (closed.get() || project.isDisposed) return@invokeLater
                    val view = tab?.view ?: return@invokeLater
                    try {
                        view.createSendTextBuilder().send(chunk)
                    } catch (e: Exception) {
                        logger.warn("[Terminal] Typing chunk failed: ${e.message}")
                        return@invokeLater
                    }
                    typingTask = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                        { typeStep(iterator, delayMs, startedAt) },
                        delayMs,
                        TimeUnit.MILLISECONDS
                    )
                }
            }
            is TerminalViewSessionState.Terminated ->
                logger.warn("[Terminal] Session terminated before typed input could be sent")
            else -> {
                if (System.currentTimeMillis() - startedAt >= COMMAND_POLL_TIMEOUT_MS) {
                    logger.warn("[Terminal] Session did not reach Running state; typed input not sent")
                    return
                }
                typingTask = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    { typeStep(iterator, delayMs, startedAt) },
                    delayMs,
                    TimeUnit.MILLISECONDS
                )
            }
        }
    }

    private fun cancelTyping() {
        typingTask?.cancel(false)
        typingTask = null
    }

    /** Kills the shell process and closes the editor tab. Safe to call repeatedly, any thread. */
    fun terminate() {
        cancelCommand()
        cancelTyping()
        val v = tab?.view
        val f = file
        try {
            v?.coroutineScope?.cancel()
        } catch (e: Exception) {
            logger.debug("[Terminal] Cancelling view scope failed: ${e.message}")
        }
        if (f == null) return
        val app = ApplicationManager.getApplication()
        val closeEditor = Runnable {
            if (project.isDisposed || app.isDisposed) return@Runnable
            val fem = FileEditorManager.getInstance(project)
            if (fem.isFileOpen(f)) fem.closeFile(f)
        }
        if (app.isDispatchThread) closeEditor.run() else app.invokeLater(closeEditor)
    }

    /** Full teardown: terminate the session and drop all state. Idempotent. */
    fun close() {
        if (closed.getAndSet(true)) {
            cleanupState()
            return
        }
        terminate()
        disconnectEditorListener()
        cleanupState()
    }

    override fun dispose() {
        close()
    }

    // ==================== Internals ====================

    private fun cleanupState() {
        cancelCommand()
        cancelTyping()
        disconnectEditorListener()
        tab = null
        file = null
    }

    private fun watchForUserClose() {
        disconnectEditorListener()
        val busConnection = project.messageBus.connect()
        editorConnection = busConnection
        busConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (file === this@OpenCodeTerminalController.file && !closed.getAndSet(true)) {
                    logger.info("[Terminal] Editor tab closed by user")
                    cancelCommand()
                    cancelTyping()
                    tab = null
                    this@OpenCodeTerminalController.file = null
                    disconnectEditorListener()
                    onClosed()
                }
            }
        })
    }

    private fun disconnectEditorListener() {
        try {
            editorConnection?.disconnect()
        } catch (_: Exception) {
        }
        editorConnection = null
    }

    /**
     * The reworked session starts asynchronously; wait for [TerminalViewSessionState.Running]
     * before typing the launch command, mirroring the legacy retry pattern.
     */
    private fun scheduleCommand(cmd: String) {
        cancelCommand()
        commandTask = AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { trySendCommand(cmd, System.currentTimeMillis()) },
            COMMAND_POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun cancelCommand() {
        commandTask?.cancel(false)
        commandTask = null
    }

    private fun trySendCommand(cmd: String, startedAt: Long) {
        if (closed.get() || project.isDisposed) return
        val v = tab?.view ?: return
        val state = v.sessionState.value
        when {
            state is TerminalViewSessionState.Running -> {
                commandTask = null
                ApplicationManager.getApplication().invokeLater {
                    if (!closed.get()) v.createSendTextBuilder().shouldExecute().send(cmd)
                }
            }
            state is TerminalViewSessionState.Terminated -> {
                commandTask = null
                logger.warn("[Terminal] Session terminated before the command could be sent")
            }
            System.currentTimeMillis() - startedAt >= COMMAND_POLL_TIMEOUT_MS -> {
                commandTask = null
                logger.warn("[Terminal] Session did not reach Running state; command not sent")
            }
            else -> {
                commandTask = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    { trySendCommand(cmd, startedAt) },
                    COMMAND_POLL_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
                )
            }
        }
    }
}
