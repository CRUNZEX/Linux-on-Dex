package com.termux.x11

import android.content.Context

/** Package-scoped bridge to Termux:X11's non-public server constructor. */
object EmbeddedX11ServerEntry {

    /**
     * Starts the in-process X server and returns the running entry point.
     *
     * [CmdEntryPoint] was written for a shell-spawned `app_process`, where no
     * Android [Context] exists and it fabricates one through hidden APIs. In
     * an application process a real context is available, so it is handed
     * over before construction: the client-connection broadcast then travels
     * as an ordinary same-app broadcast instead of relying on a fabricated
     * system context that OEM builds are free to reject.
     */
    fun start(context: Context, arguments: Array<String>): Any {
        CmdEntryPoint.ctx = context.applicationContext
        return CmdEntryPoint(arguments)
    }
}
