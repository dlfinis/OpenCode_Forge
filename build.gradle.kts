plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "ai.opencode"
version = "1.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Terminal API
        bundledPlugin("org.jetbrains.plugins.terminal")

        // Load language parsers in the test IDE to exercise optional PSI support.
        // The plugin does not declare Java or Kotlin as required runtime dependencies.
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")

    }

    // HTTP client and JSON
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.google.code.gson:gson:2.14.0")
    
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            // No upper bound; revalidate against future platform releases.
        }

        changeNotes = """
            <h2>1.2.0</h2>
            <ul>
                <li>New: Typed server health check — a non-OpenCode service on the same port is no longer mistaken for a live server, and auth failures no longer trigger automatic restarts.</li>
                <li>New: Server lifecycle ownership — Stop only terminates a server started by the plugin; shared v2 services are disconnected, never killed.</li>
                <li>New: Conservative auto-revive — the shared service identity is re-verified before re-attaching, and you are notified instead of silently starting a private replacement.</li>
                <li>New: Version gate — warns once per project when the connected server is not a v2 major version.</li>
                <li>New: Service discovery cache, with a bounded deadline when reading service.json.</li>
                <li>New: Windows CLI resolution behind .cmd shims, with shell-aware launch commands.</li>
                <li>Fix: File diffs are rebuilt from the v2 patch field; the legacy before/after contract is no longer relied upon.</li>
                <li>Fix: The web tab opens a single JCEF browser instead of two.</li>
                <li>Fix: Authentication no longer scans process environments for credentials.</li>
            </ul>
            <h2>1.1.2</h2>
            <ul>
                <li>Fix: Reuse remembered or detected live local OpenCode servers and open the local terminal on the first successful connection.</li>
            </ul>
            <h2>1.1.1</h2>
            <ul>
                <li>Fix: Make code references discoverable in the editor context menu and Tools menu.</li>
                <li>New: Add references to the enclosing class when no code is selected, with clipboard fallback if terminal delivery fails.</li>
            </ul>
            <h2>1.1.0</h2>
            <ul>
                <li>New: Custom base path support — added a "Custom base path" dropdown in the connection dialog, allowing users to specify a working directory for OpenCode terminal sessions. Automatically populated with detected project modules.</li>
                <li>Fix: Fixed the OpenCode CLI install command in README to match official download instructions.</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            create("IC", "2025.2.4")
        }
    }
}

tasks {
    // The IntelliJ Platform Gradle Plugin applies the platform JDK toolchain (21 for 2025.2+),
    // and that toolchain drives both the Java and the Kotlin target. Do not override one side
    // alone: a mismatched jvmTarget fails the build, and a lower target than the platform's
    // JDK no longer buys compatibility because sinceBuild = 252 already requires JBR 21.

    withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask> {
        systemProperty("ide.no.platform.update", "true")
    }

    // Disable buildSearchableOptions as it is flaky and often fails
    named("buildSearchableOptions") {
        enabled = false
    }
}

kotlin {
    // jvmTarget follows the IntelliJ Platform toolchain; see the note in the tasks block.
}
