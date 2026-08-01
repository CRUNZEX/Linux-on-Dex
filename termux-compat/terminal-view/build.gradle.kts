plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }

    sourceSets {
        named("main") {
            manifest.srcFile(
                rootProject.file("third_party/termux-app/terminal-view/src/main/AndroidManifest.xml")
            )
            java.srcDir(
                rootProject.file("third_party/termux-app/terminal-view/src/main/java")
            )
            res.srcDir(
                rootProject.file("third_party/termux-app/terminal-view/src/main/res")
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":terminal-emulator"))
    implementation("androidx.annotation:annotation:1.9.0")
}
