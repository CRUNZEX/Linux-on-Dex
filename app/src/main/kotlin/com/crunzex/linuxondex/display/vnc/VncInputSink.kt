package com.crunzex.linuxondex.display.vnc

/**
 * Guest-input boundary used by [VncView].
 *
 * Keeping the Android view independent from the socket client makes every
 * mouse, touch and keyboard transition testable without opening a network
 * connection. Implementations must be safe to call from the main thread.
 */
interface VncInputSink {
    val framebufferWidth: Int
    val framebufferHeight: Int

    /** Returns false when the input could not be delivered. */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int): Boolean

    /** Returns false when the input could not be delivered. */
    fun sendKeyEvent(keysym: Int, isDown: Boolean): Boolean
}
