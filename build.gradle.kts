import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    // Declare the toolchain explicitly so local builds and CI use the same JDK
    // even when the Gradle daemon runs on a newer one.
    jvmToolchain(21)
}

intellijPlatform {
    // The plugin has no GUI forms or Java sources that need JetBrains nullability
    // instrumentation. Disabling it also avoids the Ant failure "Packages does not exist"
    // that the Microsoft build of OpenJDK 21 triggers inside instrumentCode.
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            // 252 = IntelliJ Platform 2025.2. The plugin is compiled and tested
            // against 2025.2.6.3 so it stays compatible with 2025.2 and newer.
            sinceBuild = "252"
        }
    }

    pluginVerification {
        ides {
            // The verification DSL does not provide the intellijIdea* shortcuts, so
            // the platform type and version are passed explicitly.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.3")
            // 2025.3+ is published as the unified `idea` artifact.
            create(IntelliJPlatformType.IntellijIdea, "2025.3.6.1")
            create(IntelliJPlatformType.IntellijIdea, "2026.1.5")
            // IU is still published for 2026.2 and reuses the cached artifact.
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
            // Android Studio downloads a ~1.5 GB installer, so it is opt-in for
            // local runs and enabled by CI's scheduled verification job.
            if (providers.gradleProperty("verifyAndroidStudio").isPresent) {
                create(IntelliJPlatformType.AndroidStudio, "2026.2.1.8")
            }
        }
    }
}

ktlint {
    // Keep ktlint aligned with IntelliJ IDEA's formatting instead of forcing the
    // stricter official style on the existing code base.
    filter {
        exclude { it.file.path.contains("/generated/") }
    }
}

kover {
    reports {
        verify {
            // Current line coverage is around 88%, so 80% leaves room for small
            // refactors while still failing on meaningful regressions.
            rule("Minimum line coverage") {
                minBound(80)
            }
        }
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // 2025.3+ uses the unified artifact, but 2025.2 is only published as IC/IU.
        // Compile against the oldest supported platform to avoid using newer APIs.
        intellijIdeaCommunity("2025.2.6.3")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}
