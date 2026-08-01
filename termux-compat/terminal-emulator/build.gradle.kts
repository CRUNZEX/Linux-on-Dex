import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.library)
}

val generatedTermuxSources = layout.buildDirectory.dir("generated/termux-emulator/java")

val prepareTermuxEmulatorSources by tasks.registering(Sync::class) {
    from(rootProject.file("third_party/termux-app/terminal-emulator/src/main/java")) {
        // Upstream's session owns a local PTY. Linux on DeX instead feeds a
        // QEMU serial socket, so the compatible external-transport session
        // below takes its place while the emulator itself stays upstream.
        exclude("com/termux/terminal/TerminalSession.java")
        exclude("com/termux/terminal/JNI.java")
    }
    from("src/main/java")
    into(generatedTermuxSources)
}

android {
    namespace = "com.termux.emulator"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }

    sourceSets {
        named("main") {
            manifest.srcFile(
                rootProject.file("third_party/termux-app/terminal-emulator/src/main/AndroidManifest.xml")
            )
            java.setSrcDirs(listOf(generatedTermuxSources))
        }
        named("test") {
            java.srcDir(
                rootProject.file("third_party/termux-app/terminal-emulator/src/test/java")
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareTermuxEmulatorSources)
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
