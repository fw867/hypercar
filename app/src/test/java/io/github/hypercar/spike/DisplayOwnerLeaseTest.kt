package io.github.hypercar.spike

import org.junit.Assert.*
import org.junit.Test

class DisplayOwnerLeaseTest {
    @Test fun deathReleasesOnceAndLateDeathFromOldClientCannotTouchNewDisplays() {
        val notifications = mutableMapOf<String, () -> Unit>()
        val lock = Any()
        var releases = 0
        val lease = DisplayOwnerLease<String>(lock) { releases++ }
        fun bind(owner: String) = lease.bind(owner) { onDeath ->
            notifications[owner] = onDeath
            val unlink: () -> Unit = {}
            unlink
        }
        assertTrue(bind("old"))
        assertTrue(bind("old"))
        assertEquals(0, releases)
        assertTrue(bind("new"))
        assertFalse(lease.owns("old"))
        assertTrue(lease.owns("new"))
        assertEquals(1, releases)
        notifications.getValue("old")()
        assertEquals(1, releases)
        notifications.getValue("new")()
        notifications.getValue("new")()
        assertEquals(2, releases)
        assertFalse(lease.owns("new"))
    }

    @Test fun alreadyDeadClientCannotReplaceLiveOwner() {
        var released = false
        val lease = DisplayOwnerLease<String>(Any()) { released = true }
        assertTrue(lease.bind("live") { { } })
        assertFalse(lease.bind("dead") { null })
        assertFalse(released)
    }
}
