plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "ai.opencode"
version = "1.1.2"

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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            // No upper bound; revalidate against future platform releases.
        }

        changeNotes = """
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
            ide("IC", "2025.2.4")
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
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
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
