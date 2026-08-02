package com.crunzex.linuxondex.display.vnc

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns at most one blocking display connection and closes it with the UI.
 *
 * Cancelling a coroutine does not interrupt a Java socket blocked in read().
 * Explicit ownership is therefore required: closing this object closes the
 * socket first, which unblocks the read loop and lets its coroutine finish.
 */
class ActiveConnectionOwner<T : AutoCloseable> : AutoCloseable {

    private val activeConnection = AtomicReference<T?>(null)
    private val closed = AtomicBoolean(false)

    /** Returns false and closes [connection] if this owner cannot accept it. */
    fun attach(connection: T): Boolean {
        if (closed.get()) {
            closeQuietly(connection)
            return false
        }
        if (!activeConnection.compareAndSet(null, connection)) {
            closeQuietly(connection)
            return false
        }

        // close() may have won the race immediately after the first check.
        // In that case it either closes this connection itself, or this path
        // removes and closes it before returning.
        if (closed.get()) {
            activeConnection.compareAndSet(connection, null)
            closeQuietly(connection)
            return false
        }
        return true
    }

    /** Releases ownership without closing a connection already shutting down. */
    fun detach(connection: T) {
        activeConnection.compareAndSet(connection, null)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeConnection.getAndSet(null)?.let(::closeQuietly)
    }

    private fun closeQuietly(connection: T) {
        runCatching { connection.close() }
    }
}
