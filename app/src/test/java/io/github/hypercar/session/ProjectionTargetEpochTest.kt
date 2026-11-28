package io.github.hypercar.session

import io.github.hypercar.settings.ProjectionTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTargetEpochTest {
    @Test fun oldCarPlayRequestsStayRetiredEvenAfterSwitchingBack() {
        val epoch = ProjectionTargetEpoch()
        val old = epoch.capture()
        assertTrue(epoch.allowsCarPlay(old, ProjectionTarget.CarPlay))
        epoch.advance()
        assertFalse(epoch.allowsCarPlay(epoch.capture(), ProjectionTarget.Yadea))
        epoch.advance()
        assertFalse(epoch.allowsCarPlay(old, ProjectionTarget.CarPlay))
        assertTrue(epoch.allowsCarPlay(epoch.capture(), ProjectionTarget.CarPlay))
    }

    @Test fun serviceStartsKeepTheProjectionAndAutomaticRequestThatDispatchedThem() {
        val epoch = ProjectionTargetEpoch()
        val start = checkNotNull(epoch.captureServiceStart(ProjectionTarget.CarPlay, automaticRequest = 3))
        assertTrue(epoch.allowsServiceStart(start, ProjectionTarget.CarPlay, automaticRequest = 3))
        assertFalse(epoch.allowsServiceStart(start, ProjectionTarget.CarPlay, automaticRequest = 4))
        assertTrue(epoch.allowsServiceStart(start, ProjectionTarget.CarPlay))

        epoch.advance()
        assertNull(epoch.captureServiceStart(ProjectionTarget.Yadea, automaticRequest = 3))
        epoch.advance()
        assertFalse(epoch.allowsServiceStart(start, ProjectionTarget.CarPlay, automaticRequest = 3))
        assertFalse(epoch.allowsServiceStart(start, ProjectionTarget.CarPlay))
    }

    @Test fun serviceStartsFromAnOldProcessOrWithoutAnOriginCannotWritePreferences() {
        val old = ProjectionTargetEpoch()
        val current = ProjectionTargetEpoch()
        val start = checkNotNull(old.captureServiceStart(ProjectionTarget.CarPlay, automaticRequest = 0))
        assertFalse(current.allowsServiceStart(start, ProjectionTarget.CarPlay, automaticRequest = 0))
        assertFalse(current.allowsServiceStart(null, ProjectionTarget.CarPlay, automaticRequest = 0))
    }

    @Test fun serviceWakeRetiresAfterCarOrProfileChangeEvenIfSelectionReturns() {
        val epoch = ProjectionTargetEpoch()
        val old = checkNotNull(epoch.captureServiceStart(ProjectionTarget.CarPlay, 3))
        epoch.advanceCarSelection()
        epoch.advanceCarSelection()
        assertFalse(epoch.allowsServiceStart(old, ProjectionTarget.CarPlay, 3))
        val current = checkNotNull(epoch.captureServiceStart(ProjectionTarget.CarPlay, 3))
        assertTrue(epoch.allowsServiceStart(current, ProjectionTarget.CarPlay, 3))
    }

    @Test fun serviceAclWakeMustStillBeTheSameAclEdgeAfterDisconnectAndRejoin() {
        val epoch = ProjectionTargetEpoch()
        val source = epoch.observeAcl("A", 100)
        val start = checkNotNull(epoch.captureServiceStart(ProjectionTarget.CarPlay, 3, source = source))
        assertTrue(epoch.allowsServiceStart(start, ProjectionTarget.CarPlay, 3, currentPeer = "a"))
        assertFalse(epoch.allowsServiceStart(start, ProjectionTarget.CarPlay, 3, currentPeer = "B"))
        epoch.retireAcl("A")
        assertFalse(epoch.allowsServiceStart(start, ProjectionTarget.CarPlay, 3, currentPeer = "A"))
        val rejoined = epoch.observeAcl("A", 100)
        assertFalse(epoch.allowsServiceStart(start, ProjectionTarget.CarPlay, 3, currentPeer = "A"))
        assertTrue(epoch.allowsServiceStart(epoch.captureServiceStart(ProjectionTarget.CarPlay, 3, source = rejoined),
            ProjectionTarget.CarPlay, 3, currentPeer = "A"))
    }

    @Test fun companionMustStillMatchTheCurrentSelectedCar() {
        val epoch = ProjectionTargetEpoch()
        val start = checkNotNull(epoch.captureServiceStart(ProjectionTarget.CarPlay, 3,
            source = epoch.observeCompanion(7, "A")))
        assertFalse(epoch.allowsServiceStart(start, ProjectionTarget.CarPlay, 3, currentPeer = null))
        assertFalse(epoch.allowsServiceStart(start, ProjectionTarget.CarPlay, 3, currentPeer = "B"))
        assertTrue(epoch.allowsServiceStart(start, ProjectionTarget.CarPlay, 3, currentPeer = "a"))
    }

    @Test fun disappearedCompanionCannotBorrowTheNextPresenceOnTheSameAssociation() {
        val epoch = ProjectionTargetEpoch()
        val seen = epoch.observeCompanion(7, "A")
        val first = checkNotNull(epoch.captureServiceStart(ProjectionTarget.CarPlay, 3, source = seen))
        assertTrue(epoch.allowsServiceStart(first, ProjectionTarget.CarPlay, 3, currentPeer = "A"))
        epoch.retireCompanion(7, "a")
        assertFalse(epoch.allowsServiceStart(first, ProjectionTarget.CarPlay, 3, currentPeer = "A"))

        val returned = epoch.observeCompanion(7, "A")
        val current = checkNotNull(epoch.captureServiceStart(ProjectionTarget.CarPlay, 3, source = returned))
        epoch.retireCompanion(seen) // A reconciliation begun during the old presence cannot remove the new one.
        assertFalse(epoch.allowsServiceStart(first, ProjectionTarget.CarPlay, 3, currentPeer = "A"))
        assertTrue(epoch.allowsServiceStart(current, ProjectionTarget.CarPlay, 3, currentPeer = "A"))
    }

    @Test fun replacementRetiresTheOldAssociationAndItsDelayedRemovalKeepsTheReplacement() {
        val epoch = ProjectionTargetEpoch()
        val old = epoch.observeCompanion(7, "A")
        val stale = checkNotNull(epoch.captureServiceStart(ProjectionTarget.CarPlay, 3, source = old))
        epoch.replaceCompanionAssociation(8, "A")
        assertFalse(epoch.allowsServiceStart(stale, ProjectionTarget.CarPlay, 3, currentPeer = "A"))
        val replacement = epoch.observeCompanion(8, "A")
        val current = checkNotNull(epoch.captureServiceStart(ProjectionTarget.CarPlay, 3, source = replacement))

        epoch.retireCompanion(7, "A")
        epoch.retireCompanion(7, null)
        epoch.retireCompanion(old)
        assertFalse(epoch.allowsServiceStart(stale, ProjectionTarget.CarPlay, 3, currentPeer = "A"))
        assertTrue(epoch.allowsServiceStart(current, ProjectionTarget.CarPlay, 3, currentPeer = "A"))
    }

    @Test fun anOldAclCannotAcquireTheNextCarPlayProjectionStamp() {
        val epoch = ProjectionTargetEpoch()
        val oldAcl = epoch.observeAcl("A", 100)
        epoch.advance()
        epoch.advance()
        val late = checkNotNull(epoch.captureServiceStart(ProjectionTarget.CarPlay, 3, source = oldAcl))
        assertFalse(epoch.allowsServiceStart(late, ProjectionTarget.CarPlay, 3, currentPeer = "A"))
    }
}
