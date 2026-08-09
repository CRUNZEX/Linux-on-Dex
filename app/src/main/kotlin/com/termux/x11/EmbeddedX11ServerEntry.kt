package com.termux.x11

/** Package-scoped bridge to Termux:X11's non-public server constructor. */
object EmbeddedX11ServerEntry {
    fun start(arguments: Array<String>): Any = CmdEntryPoint(arguments)
}
