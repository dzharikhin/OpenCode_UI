package ai.opencode.ide.jetbrains.terminal;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.terminal.frontend.editor.TerminalViewVirtualFile;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab;
import com.intellij.terminal.frontend.view.TerminalView;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.util.Arrays;

/**
 * Java shim required because {@code TerminalViewVirtualFile} is a Kotlin {@code internal}
 * (public-in-bytecode) class: only a Java caller can construct it.
 *
 * <p>The constructor signature drifted between platform builds (2026.2.x takes
 * {@code (TerminalView, boolean)}, newer builds take {@code (TerminalToolWindowTab)}),
 * so the construction is resolved reflectively at runtime.</p>
 *
 * <p>Keep this the single place that references the class; Kotlin code must only use the
 * returned {@link VirtualFile}.</p>
 */
public final class TerminalViewFiles {

    private TerminalViewFiles() {
    }

    @NotNull
    public static VirtualFile create(@NotNull TerminalToolWindowTab tab) {
        Class<?> clazz = TerminalViewVirtualFile.class;

        // 2026.2.x shape: TerminalViewVirtualFile(view, closeOnProcessTermination)
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor(TerminalView.class, boolean.class);
            return (VirtualFile) ctor.newInstance(tab.getView(), tab.getCloseOnProcessTermination());
        } catch (NoSuchMethodException ignored) {
            // Different platform build - try the next shape.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to construct TerminalViewVirtualFile(view, boolean)", e);
        }

        // Newer shape: TerminalViewVirtualFile(tab)
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor(TerminalToolWindowTab.class);
            return (VirtualFile) ctor.newInstance(tab);
        } catch (NoSuchMethodException ignored) {
            // Fall through to the diagnostic failure below.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to construct TerminalViewVirtualFile(tab)", e);
        }

        throw new IllegalStateException(
            "No known TerminalViewVirtualFile constructor matches this IDE build. Declared: "
                + Arrays.toString(clazz.getDeclaredConstructors()));
    }
}
