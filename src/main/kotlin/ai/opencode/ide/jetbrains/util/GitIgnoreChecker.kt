package ai.opencode.ide.jetbrains.util

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks paths against the project's gitignore rules using a single batched
 * `git check-ignore --stdin` invocation.
 *
 * Fail-open: if git is unavailable or the command fails, paths are treated
 * as NOT ignored so the diff pipeline keeps working.
 */
object GitIgnoreChecker {

    private val logger = Logger.getInstance(GitIgnoreChecker::class.java)

    private const val TIMEOUT_SECONDS = 5L

    /**
     * Returns the subset of [relativePaths] that are ignored by git.
     * Paths must be project-relative (forward slashes). Unknown/invalid
     * paths are simply passed through to git, which ignores them.
     */
    fun filterIgnored(projectBasePath: String, relativePaths: Collection<String>): Set<String> {
        if (relativePaths.isEmpty()) return emptySet()
        if (!File(projectBasePath, ".git").exists()) return emptySet()

        return try {
            val cmd = GeneralCommandLine("git", "check-ignore", "--stdin")
                .withWorkDirectory(File(projectBasePath))
            val handler = CapturingProcessHandler(cmd)
            handler.processInput?.use { input ->
                relativePaths.forEach { input.write((it.replace('\\', '/') + "\n").toByteArray(Charsets.UTF_8)) }
            }
            val output = handler.runProcess(TIMEOUT_SECONDS.toInt())
            when (output.exitCode) {
                // 0 = at least one path ignored; stdout lists them one per line
                0 -> output.stdoutLines.toSet()
                // 1 = none of the paths are ignored
                1 -> emptySet()
                else -> {
                    logger.warn("git check-ignore failed (exit ${output.exitCode}): ${output.stderr}")
                    emptySet()
                }
            }
        } catch (e: Exception) {
            logger.warn("git check-ignore could not run: ${e.message}")
            emptySet()
        }
    }
}
