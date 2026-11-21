package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class AclDiscoverabilityTicketTest {
    @Test fun firstAclCanBeAssignedAfterForegroundServiceStartsButNeverReplayed() {
        val edge = AclDiscoverabilityTicket("AA", 100L, "AA", "generic", 2)
        assertFalse(edge.take(42))
        edge.assign(42)
        assertTrue(edge.pendingFor(42))
        edge.assign(43)
        assertFalse(edge.take(43))
        assertTrue(edge.take(42))
        assertTrue(edge.takenBy(42))
        assertFalse(edge.take(42))
        edge.assign(44)
        assertFalse(edge.pendingFor(44))
    }

    @Test fun queuedRequestExpiresAndCannotCrossTargetProfileOrModeChanges() {
        val edge = AclDiscoverabilityTicket("AA", 100L, "AA", "generic", 2)
        assertTrue(edge.isFresh(10_099, "aa", "generic", 2))
        assertFalse(edge.isFresh(10_100, "AA", "generic", 2))
        assertFalse(edge.isFresh(110, "BB", "generic", 2))
        assertFalse(edge.isFresh(110, null, "generic", 2))
        assertFalse(edge.isFresh(110, "AA", "bmw_id85", 2))
        assertFalse(edge.isFresh(110, "AA", "generic", 4))
    }
}
