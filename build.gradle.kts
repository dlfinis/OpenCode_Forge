import java.io.File

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "ai.opencode"
version = "1.2.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
// Override with -PplatformVersion=<version> to build and test against another IDE.
// The default is the compatibility floor declared by sinceBuild = 252.
val platformVersion = providers.gradleProperty("platformVersion").orElse("2025.2.4")

// Optional extra verifier target, e.g. -PverifyTarget=2026.3. Each target downloads a full IDE
// distribution, so this is limited to one and never enabled by default.
val verifyTarget = providers.gradleProperty("verifyTarget")

dependencies {
    intellijPlatform {
        intellijIdea(platformVersion.get())
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
            <h2>1.2.3</h2>
            <ul>
                <li>Fix: Opening the OpenCode terminal no longer leaves an empty tab in the Terminal tool window or steals focus to it.</li>
                <li>Improved: A file that cannot be read while capturing a diff is no longer logged as a warning; the recovery path is expected.</li>
            </ul>
            <h2>1.2.2</h2>
            <ul>
                <li>Changed: The plugin is now licensed under GPL-3.0. The MIT notice of the original OpenCode UI project is preserved in NOTICE.</li>
                <li>Improved: The terminal no longer depends on an IntelliJ API that is scheduled for removal.</li>
            </ul>
            <h2>1.2.1</h2>
            <ul>
                <li>Improved: The plugin archive no longer bundles kotlin-stdlib and uses the IDE's copy, shrinking the download from 3.2 MB to 1.9 MB.</li>
                <li>Improved: Build toolchain refreshed (Gradle 9.9.0, IntelliJ Platform Gradle Plugin 2.20.0, Kotlin 2.2.20, OkHttp 5.5.1, Gson 2.14.0).</li>
                <li>Improved: Removed the unused okhttp-sse dependency.</li>
                <li>Fix: Accept no longer reports "Failed to stage" for files listed in .gitignore.</li>
                <li>Fix: HTTPS servers are supported (https://host:port), including the terminal and the web tab.</li>
                <li>Fix: Connecting to a remote server opens an interactive terminal instead of a headless session.</li>
                <li>Fix: Restarting a TLS connection keeps using https instead of falling back to http.</li>
                <li>New: Tools > Open Previous OpenCode Session resumes one of this project's sessions.</li>
            </ul>
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
        // Static compatibility check of the built plugin against an IDE's API surface: it inspects
        // the bytecode for classes and methods that no longer exist, without running the plugin.
        //
        // A local `verifyPlugin` checks only the platform the build already resolved, so it costs
        // no download. Verifying against a newer release is what actually catches a removed API,
        // and that costs a full IDE distribution, so it is opt-in through -PverifyTarget.
        //
        // IntelliJ IDEA Community (IC) is no longer published from 2025.3 onwards: JetBrains ships
        // a single unified distribution, so every target is IU.
        ides {
            create("IU", platformVersion.get())
            verifyTarget.orNull?.let { create("IU", it) }
        }
    }
}

// Real-process tests spawn the opencode CLI and a real server. CI runners do not have the CLI,
// and BasePlatformTestCase runs on JUnit 3, where an assumption thrown from setUp() is reported
// as a failure instead of a skip. Exclude the class when the binary is absent.
val openCodeCliOnPath = System.getenv("PATH").orEmpty()
    .split(System.getProperty("path.separator"))
    .any { File(it, "opencode").canExecute() }

tasks {
    // GPL-3.0 requires the licence to travel with the binary, and the NOTICE carries the MIT
    // notice of the original project this work derives from. Neither belongs in the repository
    // twice, so they are pulled into the archive at build time.
    processResources {
        from("LICENSE") { into("META-INF") }
        from("NOTICE") { into("META-INF") }
    }

    // The IntelliJ Platform Gradle Plugin applies the platform JDK toolchain (21 for 2025.2+),
    // and that toolchain drives both the Java and the Kotlin target. Do not override one side
    // alone: a mismatched jvmTarget fails the build, and a lower target than the platform's
    // JDK no longer buys compatibility because sinceBuild = 252 already requires JBR 21.

    withType<Test> {
        if (!openCodeCliOnPath) {
            exclude("**/RealProcessIntegrationTest*")
        }
    }

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
