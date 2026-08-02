package com.crunzex.linuxondex.display.vnc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveConnectionOwnerTest {

    @Test
    fun `closing owner closes its active connection`() {
        val connection = TestConnection()
        val owner = ActiveConnectionOwner<TestConnection>()

        assertTrue(owner.attach(connection))
        owner.close()

        assertTrue(connection.isClosed)
    }

    @Test
    fun `connection attached after owner closes is rejected and closed`() {
        val connection = TestConnection()
        val owner = ActiveConnectionOwner<TestConnection>()

        owner.close()

        assertFalse(owner.attach(connection))
        assertTrue(connection.isClosed)
    }

    @Test
    fun `second active connection is rejected and closed`() {
        val firstConnection = TestConnection()
        val secondConnection = TestConnection()
        val owner = ActiveConnectionOwner<TestConnection>()

        assertTrue(owner.attach(firstConnection))

        assertFalse(owner.attach(secondConnection))
        assertFalse(firstConnection.isClosed)
        assertTrue(secondConnection.isClosed)
    }

    @Test
    fun `detached connection is not closed with owner`() {
        val connection = TestConnection()
        val owner = ActiveConnectionOwner<TestConnection>()

        assertTrue(owner.attach(connection))
        owner.detach(connection)
        owner.close()

        assertFalse(connection.isClosed)
    }

    private class TestConnection : AutoCloseable {
        var isClosed = false
            private set

        override fun close() {
            isClosed = true
        }
    }
}
