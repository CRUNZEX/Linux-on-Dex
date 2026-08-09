package com.crunzex.linuxondex.engine.proot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.system.Os
import com.crunzex.linuxondex.core.AppLog
import com.termux.x11.EmbeddedX11ServerEntry
import java.io.File

/** Android-managed host process for the embedded Termux:X11 server. */
class NativeX11Service : Service() {

    private val serviceBinder = Binder()
    private var serverEntry: Any? = null

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != ACTION_BIND) return null
        if (serverEntry == null) startServer(intent)
        return serviceBinder
    }

    private fun startServer(intent: Intent) {
        val rootfsDir = validatedRootfs(intent.requireStringExtra(EXTRA_ROOTFS_PATH))
        val displayNumber = intent.getIntExtra(EXTRA_DISPLAY_NUMBER, INVALID_DISPLAY_NUMBER)
        require(displayNumber in VALID_DISPLAY_NUMBERS) { "invalid X11 display number" }

        val tmpDir = rootfsDir.resolve("tmp").apply { mkdirs() }
        val xkbRoot = rootfsDir.resolve(XKB_CONFIG_ROOT_RELATIVE_PATH)
        require(xkbRoot.isDirectory) { "rootfs XKB data is missing" }

        writeStatus("starting managed X11 display :$displayNumber")
        try {
            Os.setenv("TMPDIR", tmpDir.absolutePath, true)
            Os.setenv("XKB_CONFIG_ROOT", xkbRoot.absolutePath, true)
            serverEntry = EmbeddedX11ServerEntry.start(
                arrayOf(":$displayNumber", "-nolisten", "tcp")
            )
            writeStatus("managed X11 process running on :$displayNumber")
            AppLog.info(SCOPE, "managed X11 process running on :$displayNumber")
        } catch (error: Throwable) {
            writeStatus("native X11 initialization failed: ${error.javaClass.simpleName}: ${error.message}")
            AppLog.error(SCOPE, "native X11 initialization failed", error)
            throw error
        }
    }

    private fun validatedRootfs(path: String): File {
        val rootfsDir = File(path).canonicalFile
        val imagesDir = File(filesDir, PROOT_IMAGES_RELATIVE_PATH).canonicalFile
        require(rootfsDir.toPath().startsWith(imagesDir.toPath())) {
            "rootfs is outside the app's PRoot image directory"
        }
        require(rootfsDir.isDirectory) { "rootfs directory is missing" }
        return rootfsDir
    }

    private fun Intent.requireStringExtra(name: String): String =
        getStringExtra(name)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("missing $name")

    override fun onDestroy() {
        writeStatus("managed X11 process stopped")
        serverEntry = null
        super.onDestroy()
        // Xlorie has process-lifetime native state and no in-process shutdown
        // API. This service is the only Android component in :x11, so ending
        // that dedicated process is its deterministic cleanup boundary.
        Handler(Looper.getMainLooper()).post {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun writeStatus(message: String) {
        runCatching {
            File(filesDir, STATUS_FILE_RELATIVE_PATH).apply {
                parentFile?.mkdirs()
                writeText(message)
            }
        }
    }

    companion object {
        const val STATUS_FILE_NAME = "proot-x11-service.status"

        private const val SCOPE = "NativeX11Service"
        private const val ACTION_BIND = "com.crunzex.linuxondex.action.BIND_NATIVE_X11"
        private const val EXTRA_ROOTFS_PATH = "rootfs_path"
        private const val EXTRA_DISPLAY_NUMBER = "display_number"
        private const val INVALID_DISPLAY_NUMBER = -1
        private const val PROOT_IMAGES_RELATIVE_PATH = "vm/proot-images"
        private const val XKB_CONFIG_ROOT_RELATIVE_PATH = "usr/share/X11/xkb"
        private const val STATUS_FILE_RELATIVE_PATH = "vm/logs/$STATUS_FILE_NAME"
        private val VALID_DISPLAY_NUMBERS = 0..99

        fun bindingIntent(context: Context, rootfsDir: File, displayNumber: Int): Intent =
            Intent(context, NativeX11Service::class.java)
                .setAction(ACTION_BIND)
                .putExtra(EXTRA_ROOTFS_PATH, rootfsDir.absolutePath)
                .putExtra(EXTRA_DISPLAY_NUMBER, displayNumber)
    }
}
