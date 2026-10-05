package ai.opencode.ide.jetbrains.terminal;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.terminal.frontend.editor.TerminalViewVirtualFile;
import com.intellij.terminal.frontend.view.TerminalView;
import org.jetbrains.annotations.NotNull;

/**
 * Java shim required because {@code TerminalViewVirtualFile} is a Kotlin {@code internal}
 * (public-in-bytecode) class: only a Java caller can construct it.
 *
 * <p>Keep this the single place that references the class; Kotlin code must only use the
 * returned {@link VirtualFile}.</p>
 */
public final class TerminalViewFiles {

    private TerminalViewFiles() {
    }

    @NotNull
    public static VirtualFile create(@NotNull TerminalView view, boolean closeOnProcessTermination) {
        return new TerminalViewVirtualFile(view, closeOnProcessTermination);
    }
}
