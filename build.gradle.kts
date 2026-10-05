plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "ai.opencode"
version = "2.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2026.2.1") {
            useInstaller = false
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Terminal API
        bundledPlugin("org.jetbrains.plugins.terminal")

        // JCEF (Web Browser) - extracted from platform into a bundled plugin since 2026.2
        bundledPlugin("com.intellij.modules.jcef")

    }

    // HTTP client and JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            // No untilBuild - compatible with all future versions
        }

        changeNotes = """
            <h2>2.0.0</h2>
            <ul>
                <li><b>Breaking:</b> Requires IntelliJ IDEA 2026.2 or newer (build 262+). Older IDE versions are no longer supported.</li>
                <li>Adapted to the reworked 2026.2 terminal API: OpenCode now runs in a real editor tab backed by a detached terminal session.</li>
                <li><b>Web mode:</b> now requires the bundled "Web Browser (JCEF)" plugin to be enabled (JCEF was extracted from the platform in 2026.2).</li>
                <li>Fix: multi-file diff viewer opens at the requested file index again.</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask> {
        systemProperty("ide.no.platform.update", "true")
    }

    // Disable buildSearchableOptions as it is flaky and often fails
    named("buildSearchableOptions") {
        enabled = false
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}
