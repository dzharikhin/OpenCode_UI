package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GitIgnoreCheckerTest {

    private fun newTempDir(): File {
        val dir = File.createTempFile("opencode-gic", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    @Test
    fun testFiltersIgnoredPaths() {
        val dir = newTempDir()
        try {
            runGit(dir, "init")
            File(dir, ".gitignore").writeText("build/\n*.log\n")

            val ignored = GitIgnoreChecker.filterIgnored(
                dir.absolutePath,
                listOf("build/out.txt", "crash.log", "src/Main.kt", "README.md")
            )

            assertEquals(setOf("build/out.txt", "crash.log"), ignored)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testEmptyInput() {
        val dir = newTempDir()
        try {
            assertEquals(emptySet<String>(), GitIgnoreChecker.filterIgnored(dir.absolutePath, emptyList()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testFailOpenWithoutGitRepo() {
        val dir = newTempDir() // no git init
        try {
            val ignored = GitIgnoreChecker.filterIgnored(
                dir.absolutePath,
                listOf("anything.txt", "src/Main.kt")
            )
            assertTrue(ignored.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testFailOpenWithoutGitignore() {
        val dir = newTempDir()
        try {
            runGit(dir, "init")
            val ignored = GitIgnoreChecker.filterIgnored(
                dir.absolutePath,
                listOf("src/Main.kt")
            )
            assertTrue(ignored.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun runGit(dir: File, vararg args: String) {
        val process = ProcessBuilder("git", *args)
            .directory(dir)
            .start()
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("git ${args.joinToString(" ")} timed out")
        }
        if (process.exitValue() != 0) {
            throw AssertionError("git ${args.joinToString(" ")} failed: ${process.errorStream.readBytes().decodeToString()}")
        }
    }
}
