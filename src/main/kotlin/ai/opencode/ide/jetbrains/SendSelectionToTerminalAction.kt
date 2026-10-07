package ai.opencode.ide.jetbrains

import ai.opencode.ide.jetbrains.util.PathUtil

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Sends file references to the OpenCode terminal as native @file mention chips.
 *
 * - Editor (with selection): line-pinned chip for the selected range (@path#2-4)
 * - Editor (no selection): whole-file chip for the current file
 * - Project View selection: whole-file chip for each selected file
 * - Paths with spaces / directories: plain-text @path via /tui/append-prompt
 *
 * Whole-file chips are produced by typing an @-mention query into the terminal;
 * Enter selects the autocomplete option and the TUI inserts a real chip.
 * If the OpenCode terminal is not open yet, the plugin will create/focus it
 * and then paste the reference (Claude-style UX).
 */
class SendSelectionToTerminalAction : AnAction(), DumbAware {

    private val logger = Logger.getInstance(SendSelectionToTerminalAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val openCodeService = project.service<OpenCodeService>()

        val editor = e.getData(CommonDataKeys.EDITOR)
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        logger.debug("[PasteDiag] action fired: place=${e.place}, editor=${editor != null}, files=${virtualFiles?.size ?: 0}")

        // Preferred path: typed @-mentions (real whole-file chips in the TUI).
        // The returned payload (plain-text leftovers that cannot be typed, e.g.
        // paths with spaces) is appended via /tui/append-prompt afterwards.
        val leftoverPayload = sendMention(openCodeService, project, editor, virtualFiles, e)
        if (leftoverPayload != null) {
            logger.debug("[PasteDiag] quoted leftovers -> focusOrCreateTerminalAndPaste: '$leftoverPayload'")
            openCodeService.focusOrCreateTerminalAndPaste(leftoverPayload)
        }
    }

    /**
     * Delivers typed @-mentions when possible and returns the plain-text payload
     * for anything that cannot be typed (null when nothing is left to paste).
     *
     * - Editor with selection: typed line-pinned mention (@path#2-4).
     * - Editor without selection: typed whole-file mention.
     * - Project View files: typed whole-file mention chips.
     * - Paths with spaces: cannot trigger the @-autocomplete (queries must not
     *   contain whitespace), returned as quoted text for the paste fallback.
     * - Directories: not expressible as mentions (chips are always file parts),
     *   returned as plain text for the paste fallback.
     */
    private fun sendMention(
        service: OpenCodeService,
        project: Project,
        editor: Editor?,
        virtualFiles: Array<VirtualFile>?,
        e: AnActionEvent
    ): String? {
        if (editor != null) {
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return fallbackText(project, e)
            if (!file.isInLocalFileSystem) return fallbackText(project, e)

            val relativePath = getRelativePath(project, file)
            if (relativePath.contains(" ")) return "@${formatPathReference(relativePath)} "

            val range = lineRangeSuffix(editor)
            service.focusTerminalUIForMentions()
            if (service.sendTypedMentions(listOf("$relativePath$range"))) return null
            return "@$relativePath$range "
        }

        if (virtualFiles == null || virtualFiles.isEmpty()) return null
        val normalized = normalizeProjectSelection(virtualFiles, e.getData(CommonDataKeys.VIRTUAL_FILE))
        if (normalized.isEmpty()) return null

        val typedPaths = mutableListOf<String>()
        val quotedTexts = mutableListOf<String>()
        for (file in normalized) {
            val relativePath = getRelativePath(project, file)
            if (relativePath.contains(" ") || file.isDirectory) {
                quotedTexts.add("@${formatPathReference(relativePath)}")
            } else {
                typedPaths.add(relativePath)
            }
        }

        if (typedPaths.isNotEmpty()) {
            service.focusTerminalUIForMentions()
            service.sendTypedMentions(typedPaths)
        }
        return if (quotedTexts.isEmpty()) null else quotedTexts.joinToString(" ") + " "
    }

    /**
     * Plain-text payload for contexts that cannot be resolved to files
     * (e.g. non-local editor files), mirroring the legacy fallback.
     */
    private fun fallbackText(project: Project, e: AnActionEvent): String? {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        val relativePath = getRelativePath(project, file) ?: return null
        return "@${formatPathReference(relativePath)} "
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val editor = e.getData(CommonDataKeys.EDITOR)
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        val hasEditorContext = editor != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
        val hasFileSelection = virtualFiles != null && virtualFiles.isNotEmpty()

        // Always available even if terminal isn't created yet
        e.presentation.isEnabledAndVisible = hasEditorContext || hasFileSelection
    }

    private fun getRelativePath(project: Project, file: VirtualFile): String {
        return PathUtil.relativizeToProject(project, file.path)
    }

    /**
     * Builds a TUI line-range suffix ("#N" or "#N-M") for the current editor
     * selection. The OpenCode autocomplete treats a trailing "#N-M" on the typed
     * query as a line pin: the range is excluded from file matching and attached
     * to the inserted chip as ?start=N&end=M. Returns "" when nothing is selected.
     */
    private fun lineRangeSuffix(editor: Editor): String {
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return ""

        val document = editor.document
        val startLine = document.getLineNumber(selection.selectionStart) + 1
        val endLine = document.getLineNumber(selection.selectionEnd) + 1

        // A selection ending at column 0 of a line visually ends on the previous
        // line, so exclude that trailing empty line from the range.
        val trimmedEnd = if (endLine > startLine &&
            document.getLineStartOffset(endLine - 1) == selection.selectionEnd
        ) {
            endLine - 1
        } else {
            endLine
        }

        return if (trimmedEnd > startLine) "#$startLine-$trimmedEnd" else "#$startLine"
    }

    private fun formatPathReference(path: String): String {
        return if (path.contains(" ")) {
            "\"$path\""
        } else {
            path
        }
    }

    private fun normalizeProjectSelection(
        selectedFiles: Array<VirtualFile>,
        focusedFile: VirtualFile?
    ): List<VirtualFile> {
        val all = LinkedHashMap<String, VirtualFile>()
        selectedFiles.forEach { all[it.path] = it }

        // Some IDE versions may provide descendants in VIRTUAL_FILE_ARRAY when a directory is selected.
        // If focused item is a directory but missing from array, add it to preserve directory semantics.
        if (focusedFile != null && focusedFile.isDirectory) {
            all.putIfAbsent(focusedFile.path, focusedFile)
        }

        val sorted = all.values.sortedBy { it.path.length }
        val kept = mutableListOf<VirtualFile>()
        for (candidate in sorted) {
            val candidatePath = PathUtil.toSystemIndependentPath(candidate.path)
            val hasParentSelected = kept.any { parent ->
                val parentPath = PathUtil.toSystemIndependentPath(parent.path)
                candidatePath == parentPath || candidatePath.startsWith("$parentPath/")
            }
            if (!hasParentSelected) {
                kept.add(candidate)
            }
        }
        return kept
    }
}
