# Upgrade Plan: OpenCode IntelliJ Plugin -> 2026.2 Platform API

Status: IMPLEMENTED (2026-10-05) - see commit history; corrections applied during implementation are marked below.
Target platform: IntelliJ IDEA 2026.2.1 (IU-262.9437.185)

## Corrections found during implementation

| # | Plan said | Reality |
| --- | --- | --- |
| A | "JDK 21 toolchain" starting state is fine | The platform is compiled to Java 25 bytecode (`product-info.json` -> `minRequiredJavaVersion: 25`). Toolchain + jvmTarget had to move to **25**. |
| B | plugin.xml needs only provider removals | The new terminal API lives in the terminal plugin's public content module; runtime access additionally requires `<dependencies><module name="intellij.terminal.frontend"/></dependencies>`. |
| C | Use `TerminalViewVirtualFile` directly from Kotlin | The class is Kotlin `internal`. Added Java shim `src/main/java/.../terminal/TerminalViewFiles.java` as the only construction site. |
| D | API packages unqualified | Actual packages: `com.intellij.terminal.frontend.{toolwindow,editor,view}`, `com.intellij.openapi.vfs.newvfs(.events)` for `BulkFileListener`/`VFile*Event`. |
| E | - | `./gradlew test` side effects of the toolchain upgrade: pinned Kotlin `-module-name OpenCode_UI`, disabled javac2 `instrumentCode`, restricted the test task to the three real test classes, made `sendNotification` failure-proof for headless tests. |


## Decisions

| Topic | Decision |
| --- | --- |
| `sinceBuild` | `262` (2026.2+ only, no compat shims) |
| Terminal integration | Full migration to the reworked terminal API |
| Cleanup scope | Compile blockers + real deprecations only |

## Verified starting state

`build.gradle.kts` (uncommitted) already targets `intellijIdea("2026.2.1")`, Kotlin 2.4.20,
IntelliJ Platform Gradle Plugin 2.19.0, JDK 21 toolchain (raised to 25 during implementation -
see Correction A), `kotlin.stdlib.default.dependency=false`.
The sources do **not** compile against 2026.2 today.

| # | Location | Problem |
| --- | --- | --- |
| 1 | `web/*.kt` (~50 errors) | JCEF was extracted from the platform into the bundled plugin `com.intellij.modules.jcef` (`plugins/jcef-plugin`). No `bundledPlugin(...)` dependency, so `JBCefBrowser`, `JBCefApp`, `org.cef.*` are unresolved. The classes/APIs themselves are unchanged. |
| 2 | `OpenCodeService.kt:630` | `TerminalView` + `createLocalShellWidget` are `@Deprecated(forRemoval=true)` and `@ApiStatus.ScheduledForRemoval`. |
| 3 | `DiffViewerService.kt:70` | `DiffRequestChain.setIndex(int)` is `forRemoval` **and is now a no-op** -> "open diff at index N" is silently broken. |
| 4 | `plugin.xml` | `OpenCodeWebFileEditorProvider` is registered twice (main `plugin.xml` + `opencode-web-support.xml`); the main copy defeats the optional-JCEF `<depends>` guard. |
| 5 | `AGENTS.md` / `README.md` | Still say JDK 17, 2024.2+ (242), SDK 2025.2.4, "2025.2+". |

Non-blocking deprecations: `VirtualFileManager.addVirtualFileListener` (`SessionManager.kt:317`),
`java.net.URL(String)` (`PortFinder.kt:77`).

---

## Phase 1 - Build config & descriptors (unblocks compilation)

### `build.gradle.kts`
1. Add `bundledPlugin("com.intellij.modules.jcef")`.
   The JCEF plugin lives at `plugins/jcef-plugin` and its `<id>` is literally
   `com.intellij.modules.jcef`. All classes used by this plugin exist unchanged in
   `intellij.platform.ui.jcef.jar` (`com.intellij.ui.jcef.*`) and
   `intellij.libraries.jcef.jar` (`org.cef.*`). Clears ~50 errors in
   `web/OpenCodeWebFileEditor.kt`, `web/OpenCodeWebFileEditorProvider.kt`, `web/WebModeSupport.kt`.
   - Fallback if IPGP cannot resolve that id:
     `bundledLibrary("plugins/jcef-plugin/lib/modules/intellij.platform.ui.jcef.jar")` +
     `bundledLibrary("plugins/jcef-plugin/lib/modules/intellij.libraries.jcef.jar")`.
2. `sinceBuild = "242"` -> `"262"`; keep no `untilBuild`.
3. Refresh `changeNotes` for the new minimum IDE + JCEF requirement.

### `gradle.properties`
4. Add `org.gradle.jvmargs=-Xmx3g` (2026.2 + Kotlin 2.4 exceed the 512m default).

### `src/main/resources/META-INF/plugin.xml`
5. Remove the duplicate `<fileEditorProvider ...web.OpenCodeWebFileEditorProvider/>`.
   It is already registered in `opencode-web-support.xml`, which is the copy guarded by
   `<depends optional="true" config-file="opencode-web-support.xml">com.intellij.modules.jcef</depends>`.
   The main-block copy would `NoClassDefFoundError` if the user disables the JCEF plugin.
6. Remove `<fileEditorProvider ...terminal.OpenCodeTerminalFileEditorProvider/>` and the entire
   `<projectListeners>` block for `OpenCodeTerminalEditorListener` (both classes are deleted in Phase 3).
7. Extend `<description>` to state that Web mode requires the bundled "Web Browser (JCEF)" plugin.

---

## Phase 2 - Diff viewer (real bug fix)

### `diff/DiffViewerService.kt:70`
`DiffRequestChain.setIndex(int)` is `@Deprecated(forRemoval=true)` and its body in 262 only calls
`ThreadingAssertions.assertEventDispatchThread()` and returns - i.e. a no-op. `SimpleDiffRequestChain`
no longer exposes a setter; selection is constructor-based.

- Replace:
  ```kotlin
  val chain = SimpleDiffRequestChain(requests)
  chain.index = (initialIndex ?: 0).coerceIn(0, requests.lastIndex)
  ```
  with:
  ```kotlin
  val chain = SimpleDiffRequestChain(requests, (initialIndex ?: 0).coerceIn(0, requests.lastIndex))
  ```

---

## Phase 3 - Terminal: full migration to the reworked API

### New seam: `terminal/OpenCodeTerminalController.kt`
The only file allowed to touch terminal API, so future churn stays contained.

- Create a tab detached from the Terminal tool window:
  ```kotlin
  TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
      .workingDirectory(wd)
      .tabName(name)
      .envVariables(mapOf("OPENCODE_SERVER_PASSWORD" to pwd))
      .requestFocus(true)
      .deferSessionStartUntilUiShown(false)
      .shouldAddToToolWindow(false)
      .closeOnProcessTermination(false)
      .createTab()
  ```
- Show it in an editor tab exactly like the platform's own `MoveTerminalSessionToEditorAction`:
  wrap `tab.view` in `TerminalViewVirtualFile(tab.view, tab.closeOnProcessTermination)` and call
  `FileEditorManager.openFile(file, true)`. The terminal plugin's `TerminalViewFileEditorProvider`
  (`id="terminal-view-editor"`, policy `HIDE_DEFAULT_EDITOR`) supplies the editor - no more Swing
  reparenting hack.
- Launch OpenCode: `view.createSendTextBuilder().shouldExecute().send(cmd)`. The reworked session
  starts asynchronously, so gate on `view.sessionState` reaching `Running`, or reuse the existing
  `schedulePasteAttempt` retry pattern with `trySend` (returns success).
- Terminate: `view.coroutineScope.cancel()` + `FileEditorManager.closeFile(file)`.
  `TerminalViewFileEditor.dispose()` already cancels the view scope.
  `TerminalToolWindowTabsManager.closeTab(tab)` is a **verified no-op** for detached tabs
  (returns early when `content.manager == null`) - do not rely on it.

### Delete (superseded)
- `terminal/OpenCodeTerminalVirtualFile.kt`
- `terminal/OpenCodeTerminalFileEditor.kt`
- `terminal/OpenCodeTerminalFileEditorProvider.kt` (including its 60 s delayed-disposal machinery -
  `TerminalViewVirtualFile` sets `FileEditorManagerKeys.FORBID_TAB_SPLIT=true`, so the tab move/split
  scenario it guarded against can no longer occur)
- `terminal/OpenCodeTerminalEditorListener.kt`
- `terminal/OpenCodeTerminalLinkFilter.kt`

### `OpenCodeService.kt`
- `createTerminalUIInternal` (~line 627): delegate to the controller; drop `TerminalView` / `ShellTerminalWidget`.
- Retype `terminalVirtualFile: TerminalViewVirtualFile?`, `terminalEditor: FileEditor?`.
- `terminateProcess()` (~line 491): use `controller.terminate()` instead of `processTtyConnector.process`.
- `disconnectAndReset()` / `dispose()`: replace `OpenCodeTerminalFileEditorProvider.disposeWidget/clearAll`
  with `controller.close()`.
- `ensureTerminalUi()`: replace `OpenCodeTerminalFileEditorProvider.hasWidget(f)` with `controller.isAlive()`.
- `buildOpenCodeCommand`: drop the Windows `cmd /c "set ..."` and POSIX `VAR='...'` quoting branches;
  pass the password through `envVariables`.
- Keep `pinTerminalTab` (`FileEditorManagerEx.setFilePinned`).

### Accepted regression
The reworked terminal exposes **no** plugin API for custom `HyperlinkFilter`s (every `extensionPoint`
in `terminal.xml` / `plugin.xml` was checked; the hyperlink pipeline is internal
`FrontendTerminalHyperlinksProcessingKt`). Clickable `@path#Lx-y` navigation is lost.

Optional follow-up (NOT part of this change): reimplement on `TerminalViewImpl.getOutputEditor()` with an
`EditorMouseListener` + `RangeHighlighter`. That is an `impl` class, i.e. unstable API.

### API-stability risk
`TerminalToolWindowTabsManager` and `TerminalToolWindowTabBuilder` are `@ApiStatus.Experimental` /
`@NonExtendable` (some builder members are `@Internal`). Accepted; contained by the controller seam.

Fallback if the experimental API churns:
```kotlin
LocalTerminalDirectRunner.createTerminalRunner(project)
    .startShellTerminalWidget(disposable, ShellStartupOptions.Builder().workingDirectory(wd).build(), false)
```
Non-deprecated, classic engine, and keeps jediterm hyperlink filters
(via `ShellTerminalWidget.asShellJediTermWidget(widget)`).

---

## Phase 4 - Real deprecations

- `session/SessionManager.kt:317` - `VirtualFileManager.addVirtualFileListener(listener, disposable)` is
  deprecated (both overloads). Replace the `VirtualFileListener` (lines 110-132) with a `BulkFileListener`
  subscribed to `VirtualFileManager.VFS_CHANGES` on `project.messageBus.connect(this)`:
  - `before(events)`: `captureContentBeforeChange` for `VFileContentChangeEvent` / `VFileDeleteEvent`.
  - `after(events)`: `onVfsChange` + `aiCreatedFiles` bookkeeping for
    `VFileCreateEvent` / `VFileDeleteEvent` / `VFileContentChangeEvent` / `VFileMoveEvent`.
- `util/PortFinder.kt:77` - `java.net.URL(String)` deprecated -> `URI("http://$hostname:$port/global/health").toURL()`.
- Drop now-unused imports in touched files (`SessionManager`: `Service`, `VirtualFileCopyEvent`;
  `PortFinder`: `ServerSocket`; `OpenCodeService`: `OSProcessHandler`, `LocalDateTime`, `DateTimeFormatter`).
- Out of scope: the ~50 style/unused-code warnings (`exe` at `OpenCodeService.kt:691`,
  `showHeadlessStatusDialog`, boolean-literal args, log-message duplication, etc.).

---

## Phase 5 - Docs

- `AGENTS.md`: JDK 17 -> 25; "IntelliJ IDEA 2024.2+ (Since Build 242)" -> 2026.2+ (262);
  "IntelliJ Platform SDK 2025.2.4" -> 2026.2.1; update the `terminal/` file list in the architecture tree;
  note that JCEF is now a bundled plugin dependency.
- `README.md:41`: "(2025.2+)" -> "(2026.2+)"; note that Web mode needs the bundled JCEF plugin enabled;
  remove the retired "Smart Links" feature (accepted terminal regression).
- Add `releaseNotes/200.md` matching the `changeNotes` block (version bumped 1.1.0 -> 2.0.0).

---

## Verification

1. `./gradlew clean build` - must compile with zero `forRemoval` usages.
2. `./gradlew test` - 3 test classes; `SendSelectionToTerminalActionTest` is a `BasePlatformTestCase`.
   None reference the deleted terminal classes.
3. `./gradlew verifyPluginConfiguration`; optionally add
   `intellijPlatform { pluginVerification { ides { recommended() } } }` and run `./gradlew verifyPlugin`
   to catch remaining for-removal / experimental API usage.
4. `./gradlew runIde` manual smoke test:
   - Cmd/Ctrl+Esc -> OpenCode tab opens in the editor area, TUI runs, auth works.
   - Ctrl+Alt+K sends `@path#Lx-y` text.
   - Diff viewer opens **at the requested index**.
   - Accept (`git add`) / Reject (restore) work.
   - Web mode renders via JCEF.
   - Closing the tab kills the OpenCode process.
   - Disconnect / reconnect works.

## Suggested commit split

As implemented (2026-10-05):

1. `build: target 2026.2 (jcef bundled plugin, JDK 25, sinceBuild 262)` (+ version 2.0.0, changeNotes, gradle.properties, plugin.xml incl. module dependency fix from Correction B)
2. `fix(diff): restore initial index via SimpleDiffRequestChain ctor`
3. `refactor(terminal): migrate to reworked terminal API` (+ Java shim from Correction C, test/build adjustments from Correction E)
4. `chore: replace deprecated VFS listener and URL ctor`
5. `docs: 2026.2 / JDK 25`

`./gradlew verifyPlugin` (pluginVerification) was intentionally skipped in this pass.
