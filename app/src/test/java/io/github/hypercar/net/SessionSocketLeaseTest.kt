package io.github.hypercar.net

import java.io.Closeable
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SessionSocketLeaseTest {
    private class FakeSocket : Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun bindPrecedesConnectAndKeepsCapturedNetwork() {
        var selected = "car"
        val lease = SessionSocketLease(selected)
        val socket = FakeSocket()
        val steps = mutableListOf<String>()
        selected = "cellular"
        val fd = lease.open({ socket }, { network, _ -> steps += "bind:$network" },
            { steps += "connect" }, { steps += "handoff"; 42 })
        assertEquals("cellular", selected)
        assertEquals(listOf("bind:car", "connect", "handoff"), steps)
        assertEquals(42, fd)
        assertTrue(socket.closed)
    }

    @Test fun bindingFailureClosesSocketWithoutConnectingOrFallingBack() {
        val lease = SessionSocketLease("lost-car")
        val socket = FakeSocket()
        var connected = false
        assertThrows(IOException::class.java) {
            lease.open({ socket }, { _, _ -> throw IOException("network gone") },
                { connected = true }, { fail("must not hand off") })
        }
        assertFalse(connected)
        assertTrue(socket.closed)
    }

    @Test fun connectionFailureClosesSocket() {
        val socket = FakeSocket()
        assertThrows(IOException::class.java) {
            SessionSocketLease("car").open({ socket }, { _, _ -> },
                { throw IOException("connect failed") }, { fail("must not hand off") })
        }
        assertTrue(socket.closed)
    }

    @Test fun cancellationDuringConnectRejectsHandoffAndLeavesNextSessionUsable() {
        val old = SessionSocketLease("old")
        val next = SessionSocketLease("new")
        val socket = FakeSocket()
        assertThrows(IOException::class.java) {
            old.open({ socket }, { _, _ -> }, { old.invalidate() }, { fail("stale fd") })
        }
        assertTrue(socket.closed)
        assertEquals("new", next.open({ FakeSocket() }, { _, _ -> }, {}, { next.network }))
    }

    @Test fun cancelledLeaseDoesNotCreateAnySocket() {
        val lease = SessionSocketLease("car")
        lease.invalidate()
        assertThrows(IOException::class.java) {
            lease.open({ fail("must not create"); FakeSocket() }, { _, _ -> }, {}, {})
        }
    }

    @Test fun failedHandoffClosesSocket() {
        val socket = FakeSocket()
        assertThrows(IOException::class.java) {
            SessionSocketLease("car").open({ socket }, { _, _ -> }, {}, { throw IOException("export failed") })
        }
        assertTrue(socket.closed)
    }
}
