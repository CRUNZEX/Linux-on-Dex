package com.crunzex.linuxondex

import java.io.File
import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedX11SecurityTest {

    @Test
    fun `managed X11 host process is private`() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull(File::isFile)?.readText() ?: error("application manifest is missing")

        val serviceDeclaration = manifest.substringAfter(
            "android:name=\".engine.proot.NativeX11Service\"",
        ).substringBefore("/>")
        assertTrue(serviceDeclaration.contains("android:exported=\"false\""))
        assertTrue(serviceDeclaration.contains("android:process=\":x11\""))
    }

    @Test
    fun `embedded X11 exposes no privileged or accessibility components`() {
        val aar = listOf(
            File("libs/termux-x11-lorie-arm64.aar"),
            File("app/libs/termux-x11-lorie-arm64.aar"),
        ).firstOrNull(File::isFile) ?: error("embedded X11 AAR is missing")

        ZipFile(aar).use { archive ->
            val manifest = archive.getInputStream(archive.getEntry("AndroidManifest.xml"))
                .bufferedReader()
                .use { it.readText() }
            assertFalse(manifest.contains("WRITE_SECURE_SETTINGS"))
            assertFalse(manifest.contains("AccessibilityService"))
            assertFalse(manifest.contains("KeyInterceptor"))
            assertFalse(manifest.contains("<receiver"))
            assertFalse(manifest.contains("<queries"))
            assertFalse(manifest.contains("android:exported=\"true\""))

            val classes = archive.getInputStream(archive.getEntry("classes.jar"))
            JarInputStream(classes).use { jar ->
                val classNames = mutableListOf<String>()
                var commandEntryPointBytes: ByteArray? = null
                while (true) {
                    val entry = jar.nextJarEntry ?: break
                    classNames += entry.name
                    if (entry.name == "com/termux/x11/CmdEntryPoint.class") {
                        commandEntryPointBytes = jar.readBytes()
                    }
                }
                assertFalse(classNames.any { it.endsWith("KeyInterceptor.class") })
                assertTrue(classNames.any { it.endsWith("MainActivity.class") })

                // The stock preferences activity must stay out of the AAR:
                // the app ships its own com.termux.x11.LoriePreferences and
                // a leftover copy would break the build at dex merge. The
                // PrefsProto classes must stay in: the viewer reads every
                // setting through them.
                assertFalse(
                    "stock LoriePreferences activity has returned to the AAR",
                    classNames.contains("com/termux/x11/LoriePreferences.class"),
                )
                assertTrue(
                    classNames.contains("com/termux/x11/LoriePreferences\$PrefsProto.class"),
                )

                val loaderConstants = commandEntryPointBytes
                    ?.toString(Charsets.ISO_8859_1)
                    ?: error("CmdEntryPoint is missing")
                assertTrue(loaderConstants.contains("loadLibrary"))
                assertTrue(loaderConstants.contains("Xlorie"))
            }
        }
    }
}
