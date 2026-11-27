package io.github.hypercar.spike

import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCallAudioTest {
    private val approveMute = { true }

    private val firstCall = CallOutputRecoveryScope(4, 6_000, 31)
    private val nextCall = CallOutputRecoveryScope(5, 8_000, 32)

    private class JournalDisk(var bytes: ByteArray = ByteArray(0)) {
        var writeCount: (Long, Int) -> Int = { _, size -> size }
        var sync: () -> Unit = {}

        var now = 0L
        fun open(bootCount: Int = 6) = CallOutputJournal(bootCount, readAt = { offset, dest ->
            val start = offset.toInt()
            val count = (bytes.size - start).coerceIn(0, dest.size)
            if (count > 0) bytes.copyInto(dest, 0, start, start + count)
            count
        }, writeAt = { offset, source ->
            val start = offset.toInt()
            val count = writeCount(offset, source.size)
            if (count > 0) {
                if (start + count > bytes.size) bytes = bytes.copyOf(start + count)
                source.copyInto(bytes, start, 0, count)
            }
            count
        }, sync = { sync() }, mapSelector = { ByteBuffer.wrap(bytes) }, elapsed = { now })
    }

    private fun CallOutputJournal.dispatch(owner: Any, scope: CallOutputRecoveryScope) =
        arm(owner, scope) && commitArm(owner)

    @Test fun failedTakeoverLeavesConfirmedMuteRecoverableByItsOriginalOwner() {
        val disk = JournalDisk()
        val journal = disk.open()
        val old = Any()
        val next = Any()
        assertTrue(journal.dispatch(old, firstCall))
        assertTrue(journal.persistFor(old, CallOutputJournal.State.RESTORE, firstCall))
        assertFalse(journal.arm(next, null))
        assertEquals(CallOutputJournal.Record(CallOutputJournal.State.RESTORE, firstCall), disk.open().current())
        assertFalse(journal.persistFor(next, CallOutputJournal.State.CLEAR))
        assertTrue(journal.persistFor(old, CallOutputJournal.State.CLEAR))
    }

    @Test fun cancellingAnUndispatchedTakeoverRestoresThePreviousRecordAndWriter() {
        val legacy = ByteBuffer.allocate(56).putLong(-7).putLong(4).putLong(6_000).putInt(48, 31).array()
        for (oldFormat in listOf(true, false)) {
            val disk = JournalDisk(if (oldFormat) legacy.copyOf() else ByteArray(0))
            val journal = disk.open()
            val old = Any()
            if (!oldFormat) assertTrue(journal.dispatch(old, firstCall))
            val next = Any()
            assertTrue(journal.arm(next, nextCall))
            assertFalse(journal.abortArm(old))
            assertTrue(journal.abortArm(next))
            assertEquals(CallOutputJournal.Record(CallOutputJournal.State.MUTE_UNCONFIRMED, firstCall),
                disk.open().current())
            assertFalse(journal.persistFor(next, CallOutputJournal.State.CLEAR))
            if (!oldFormat) assertTrue(journal.persistFor(old, CallOutputJournal.State.RESTORE, firstCall))
        }
    }

    @Test fun confirmedTakeoverRetainsTheDisplacedScopeInItsArchive() {
        val disk = JournalDisk()
        val journal = disk.open()
        assertTrue(journal.dispatch(Any(), firstCall))
        val next = Any()
        assertTrue(journal.dispatch(next, nextCall))
        assertTrue(journal.persistFor(next, CallOutputJournal.State.RESTORE, nextCall))
        val slotOffset = if (disk.bytes[0] == 0x41.toByte()) 56 else 112
        val archive = ByteBuffer.wrap(disk.bytes, slotOffset + 28, 28)
        assertEquals(-7L, archive.long)
        assertEquals(4L, archive.long)
        assertEquals(6_000L, archive.long)
        assertEquals(31, archive.int)
        assertEquals(CallOutputJournal.Record(CallOutputJournal.State.RESTORE, nextCall), disk.open().current())
    }

    @Test fun failedRollbackProtectsTheDisplacedOwnerFromAnotherTakeover() {
        val disk = JournalDisk()
        val journal = disk.open()
        val old = Any()
        val next = Any()
        assertTrue(journal.dispatch(old, firstCall))
        assertTrue(journal.persistFor(old, CallOutputJournal.State.RESTORE, firstCall))
        assertTrue(journal.arm(next, nextCall))
        disk.writeCount = { offset, size -> if (offset == 0L) 0 else size }
        assertFalse(journal.abortArm(next))
        assertEquals(CallOutputJournal.Record(CallOutputJournal.State.RESTORE, firstCall), disk.open().current())
        disk.writeCount = { _, size -> size }
        assertTrue(journal.watchRollback(activeOffer = false))
        assertFalse(journal.arm(Any(), nextCall))
        assertFalse(journal.persistFor(next, CallOutputJournal.State.CLEAR))
        disk.now += 1_000
        journal.retryRollback()
        assertFalse(journal.watchRollback(activeOffer = false))
        assertEquals(CallOutputJournal.Record(CallOutputJournal.State.RESTORE, firstCall), disk.open().current())
        assertTrue(journal.persistFor(old, CallOutputJournal.State.CLEAR))
    }

    @Test fun preparingAnInitialMuteDoesNotExposeItAfterProcessDeath() {
        val disk = JournalDisk()
        assertTrue(disk.open().arm(Any(), firstCall))
        assertEquals(CallOutputJournal.Record(CallOutputJournal.State.CLEAR, null), disk.open().current())
    }

    @Test fun blockedSelectorWriteCanBeRevokedAndItsFailedRollbackStillRecoversTheOriginalCall() {
        val legacy = ByteBuffer.allocate(56).putLong((1L shl 32) + 7)
            .putLong(4).putLong(6_000).putInt(48, 31).array()
        val disk = JournalDisk(legacy)
        val journal = disk.open()
        val owner = Any()
        val writes = mutableListOf<Boolean>()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var selected = false
        disk.writeCount = { offset, size ->
            if (offset != 0L) size else if (selected) 0 else {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                selected = true
                size
            }
        }
        val output = LocalCallMute({ 0L }, { true }, { false }, { writes.add(it) },
            persist = { journal.arm(owner, nextCall) }, commitDispatch = { journal.commitArm(owner) },
            cancelUndispatched = { journal.abortArm(owner) })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val confirm = workers.submit { output.confirm(650, approveMute) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            workers.submit { output.requestRestore(retire = true) }.get(1, TimeUnit.SECONDS)
            resume.countDown()
            confirm.get(2, TimeUnit.SECONDS)
            assertTrue(writes.isEmpty())
            assertTrue(journal.watchRollback(activeOffer = false))
            val previous = CallOutputJournal.Record(CallOutputJournal.State.RESTORE, firstCall)
            assertEquals(previous, disk.open().current())

            // A second shell can die with its own candidate prepared before A was restored.
            disk.writeCount = { _, size -> size }
            assertTrue(disk.open().arm(Any(), nextCall))
            val restarted = disk.open()
            assertEquals(previous, restarted.current())
            var muted = true
            val restore = restarted.recover({ muted }) { muted = false }
            restore.write(0) { restarted.current().scope!!.allows(7_000, 4, confirmed = true, currentPid = 31) }
            assertTrue(restore.verify())
            assertFalse(muted)
            assertEquals(CallOutputJournal.State.CLEAR, disk.open().current().state)
        } finally {
            resume.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun routeChangeDuringJournalSyncCancelsTheVolumeWrite() {
        var handset = true
        val disk = JournalDisk().apply { sync = { handset = false } }
        val journal = disk.open()
        val owner = Any()
        val writes = mutableListOf<Boolean>()
        val output = LocalCallMute({ 0L }, { handset }, { false }, { writes.add(it) },
            persist = { state -> if (state == CallOutputJournal.State.MUTE_UNCONFIRMED)
                journal.arm(owner, firstCall) else journal.persistFor(owner, state, firstCall) },
            commitDispatch = { journal.commitArm(owner) }, cancelUndispatched = { journal.abortArm(owner) })
        output.confirm(650, approveMute)
        assertTrue(writes.isEmpty())
        assertEquals(CallOutputJournal.State.CLEAR, disk.open().current().state)
    }

    @Test fun failedConfirmationRestoresImmediatelyAndSuppressesFurtherMutesOnTheOffer() {
        var muted = false
        val writes = mutableListOf<Boolean>()
        val output = LocalCallMute({ 0 }, { true }, { muted }, { muted = it; writes += it },
            persist = { it != CallOutputJournal.State.RESTORE })
        output.confirm(650, approveMute)
        assertTrue(output.needsRestore())
        assertTrue(output.restore())
        output.confirm(650, approveMute)
        assertEquals(listOf(true, false), writes)
    }

    @Test fun failedRenewalRestoresImmediatelyAndSuppressesFurtherMutesOnTheOffer() {
        var now = 0L
        var muted = false
        val writes = mutableListOf<Boolean>()
        val output = LocalCallMute({ now }, { true }, { muted }, { muted = it; writes += it },
            renewRecoveryScope = { false })
        for (time in listOf(0L, 500L, 1_000L)) {
            now = time
            output.confirm(now + 650, approveMute)
        }
        assertTrue(output.needsRestore())
        assertTrue(output.restore())
        output.confirm(now + 650, approveMute)
        assertEquals(listOf(true, false), writes)
    }

    @Test fun permanentRollbackFailureLetsTheIdleServiceSleepAndResumesForANewOffer() {
        val disk = JournalDisk()
        val journal = disk.open()
        val old = Any()
        val next = Any()
        assertTrue(journal.dispatch(old, firstCall))
        assertTrue(journal.persistFor(old, CallOutputJournal.State.RESTORE, firstCall))
        assertTrue(journal.arm(next, nextCall))
        var writes = 0
        disk.writeCount = { _, _ -> writes++; 0 }
        assertFalse(journal.abortArm(next))
        repeat(30) {
            if (journal.watchRollback(activeOffer = false)) journal.retryRollback()
        }
        assertEquals(1, writes)
        repeat(30) {
            disk.now += 1_000
            if (journal.watchRollback(activeOffer = false)) journal.retryRollback()
        }
        assertFalse(journal.watchRollback(activeOffer = false))
        assertTrue(writes in 2..8)
        assertTrue(journal.watchRollback(activeOffer = true))
        disk.writeCount = { _, size -> size }
        assertFalse(journal.arm(Any(), nextCall))
        journal.retryRollback()
        assertEquals(CallOutputJournal.Record(CallOutputJournal.State.RESTORE, firstCall), disk.open().current())
        assertTrue(journal.persistFor(old, CallOutputJournal.State.CLEAR))
    }

    @Test fun journalWritesLeaveTheWatchdogAndCompensationRunnable() {
        for (blocked in listOf("confirmation", "renewal", "finish")) {
            val now = java.util.concurrent.atomic.AtomicLong()
            val muted = AtomicBoolean()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fun block() {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val output = LocalCallMute(now::get, { true }, muted::get, muted::set,
                persist = { state ->
                    if (blocked == "confirmation" && state == CallOutputJournal.State.RESTORE ||
                        blocked == "finish" && state == CallOutputJournal.State.CLEAR) block()
                    true
                }, renewRecoveryScope = { if (blocked == "renewal") block(); true })
            val workers = Executors.newFixedThreadPool(2)
            try {
                val persisting = workers.submit {
                    output.confirm(650, approveMute)
                    if (blocked == "renewal") {
                        now.set(500)
                        output.confirm(1_150, approveMute)
                        now.set(1_000)
                        output.confirm(1_650, approveMute)
                    } else if (blocked == "finish") output.restore()
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                workers.submit {
                    output.requestRestore(retire = true)
                    output.expire()
                    output.writeRestore()
                }.get(1, TimeUnit.SECONDS)
                assertFalse(muted.get())
                release.countDown()
                persisting.get(2, TimeUnit.SECONDS)
            } finally {
                release.countDown()
                workers.shutdownNow()
            }
        }
    }

    @Test fun migratingShortLegacyFilesCannotCompletePartialEvidenceWithZeroPadding() {
        val legacy = ByteBuffer.allocate(56).putLong((1L shl 32) + 7).putLong(4).putLong(6_123)
            .putInt(48, 65_567).array()
        for (length in 1..55) {
            val disk = JournalDisk(legacy.copyOf(length))
            val journal = disk.open()
            val expected = if (length < 24) null else CallOutputRecoveryScope(4, 6_123,
                if (length < 52) null else 65_567)
            assertEquals("length=$length", expected, journal.current().scope)
            disk.sync = { error("stop before publication") }
            assertFalse(journal.arm(Any(), nextCall))
            assertEquals("after extending length=$length", expected, disk.open().current().scope)
        }
        for (firstByte in listOf(0x41, 0x42, 0x43, 0x44, 0x78, 0xff)) {
            val disk = JournalDisk(byteArrayOf(firstByte.toByte()))
            assertNull(disk.open().current().scope)
            assertTrue(disk.open().dispatch(Any(), nextCall))
            assertEquals(nextCall, disk.open().current().scope)
        }
    }

    @Test fun interruptedTakeoverReopensWithTheCompletePreviousRecord() {
        val legacy = ByteBuffer.allocate(56).putLong((1L shl 32) + 7)
            .putLong(4).putLong(6_000).putInt(48, 31).array()
        val initial = JournalDisk()
        val old = Any()
        val initialJournal = initial.open()
        assertTrue(initialJournal.dispatch(old, firstCall))
        assertTrue(initialJournal.persistFor(old, CallOutputJournal.State.RESTORE, firstCall))
        for (source in listOf(legacy, initial.bytes)) {
            // Cut the staged record after each byte, fail fsync, or fail the final selector write.
            for (cut in 0..57) {
                val disk = JournalDisk(source.copyOf())
                val journal = disk.open()
                val next = Any()
                disk.writeCount = { _, size ->
                    if (size == 1 && cut == 57) 0 else minOf(size, cut)
                }
                disk.sync = { check(cut != 56) }
                assertFalse(journal.arm(next, nextCall))
                assertEquals("cut=$cut", CallOutputJournal.Record(CallOutputJournal.State.RESTORE, firstCall),
                    disk.open().current())
                assertFalse(journal.persistFor(next, CallOutputJournal.State.CLEAR))
            }
        }
    }

    @Test fun lateConfirmedMuteMayRestoreOnHangupButNeverOnTheNextCall() {
        for (returned in listOf(5_999L, 6_000L, 6_001L)) {
            val scope = CallOutputRecoveryScope(4, 6_000, 31).atWriteCompletion(returned)
            assertTrue(scope.allows(returned + 5_999, ShellCallIdentity.NO_RECOVERY_CALL, true, 31))
            assertFalse(scope.allows(returned + 6_000, ShellCallIdentity.NO_RECOVERY_CALL, true, 31))
        }
        val completed = CallOutputRecoveryScope(4, 6_000, 31).atWriteCompletion(7_000)
        assertTrue(completed.allows(7_001, ShellCallIdentity.NO_RECOVERY_CALL, true, 31))
        assertFalse(completed.allows(7_001, 5, true, 31))
        assertFalse(completed.allows(7_001, 4, true, 32))
        assertFalse(completed.allows(13_000, ShellCallIdentity.NO_RECOVERY_CALL, true, 31))
        assertFalse(CallOutputRecoveryScope(4, 6_000).atWriteCompletion(7_000).allows(7_001, 4, true))
    }

    @Test fun oldReadbackCannotClearTheJournalAfterNewMuteTakesOwnership() {
        val journal = JournalDisk().open()
        val old = Any()
        val next = Any()
        assertTrue(journal.dispatch(old, firstCall))
        assertTrue(journal.persistFor(old, CallOutputJournal.State.RESTORE, firstCall))
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val restoration = CallOutputRestoration({
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            false
        }, {}, { journal.persistFor(old, CallOutputJournal.State.CLEAR) })
        restoration.write(1)
        val reader = Thread { restoration.verify() }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(journal.dispatch(next, nextCall))
            assertTrue(journal.persistFor(next, CallOutputJournal.State.RESTORE, nextCall))
            resume.countDown()
            reader.join(5_000)
            assertFalse(reader.isAlive)
            assertEquals(CallOutputJournal.State.RESTORE, journal.current().state)
        } finally { resume.countDown() }
    }

    @Test fun blockingAuthorizationNeverExposesAnUndispatchedMuteToRecovery() {
        val writes = mutableListOf<Boolean>()
        val states = mutableListOf<CallOutputJournal.State>()
        val output = LocalCallMute({ 0L }, { true }, { false }, { writes.add(it) },
            persist = { states.add(it); true })
        var prematureRecord = false
        output.confirm(650) {
            if (writes.isEmpty() && states.isNotEmpty()) prematureRecord = true
            true
        }
        assertFalse(prematureRecord)
        assertEquals(listOf(true), writes)
    }

    @Test fun withdrawalDuringJournalWriteCancelsDispatch() {
        var current = true
        val states = mutableListOf<CallOutputJournal.State>()
        val writes = mutableListOf<Boolean>()
        val output = LocalCallMute({ 0L }, { true }, { false }, { writes.add(it) },
            persist = { states.add(it); current = false; true }, dispatchCurrent = { current })
        output.confirm(650, approveMute)
        assertTrue(writes.isEmpty())
        assertEquals(listOf(CallOutputJournal.State.MUTE_UNCONFIRMED, CallOutputJournal.State.CLEAR), states)
    }

    @Test fun aLateMuteCompletionReopensOnlyItsOwnersRecovery() {
        val now = java.util.concurrent.atomic.AtomicLong()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val muted = AtomicBoolean()
        val output = LocalCallMute(now::get, { true }, muted::get, {
            if (it) {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
            muted.set(it)
        })
        val watch = CallOutputMuteWatchdog()
        watch.update(output)
        val thread = Thread { output.confirm(650, approveMute) }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            watch.update(null)!!.writeRestore()
            now.set(6_500)
            watch.update(null)
            assertFalse(output.needsRestore())
            resume.countDown()
            thread.join(5_000)
            assertTrue(muted.get())
            assertTrue(watch.update(null)!!.restore())
            assertFalse(muted.get())
        } finally { resume.countDown() }
    }

    private fun LocalCallMute.restore(): Boolean {
        writeRestore()
        return !needsRestore() || restoration.verify()
    }

    @Test fun localOutputRequiresConfirmationAndRestoresOnExpiryOrRouteChange() {
        var now = 0L
        var builtIn = true
        var muted = false
        val writes = mutableListOf<Boolean>()
        val output = LocalCallMute({ now }, { builtIn }, { muted }, { muted = it; writes += it })
        output.expire()
        assertFalse(muted)
        output.confirm(now + 650, approveMute)
        assertTrue(muted)
        now = 650
        output.expire()
        assertTrue(output.needsRestore())
        assertTrue(output.restore())
        assertFalse(muted)
        output.confirm(now + 650, approveMute)
        builtIn = false
        now += 100
        output.checkRoute()
        assertTrue(output.needsRestore())
        assertTrue(output.restore())
        assertFalse(muted)
        assertEquals(listOf(true, false, true, false), writes)
    }

    @Test fun localOutputPreservesExistingMuteAndRespectsManualUnmute() {
        for (initialMute in listOf(false, true)) {
            var muted = initialMute
            val writes = mutableListOf<Boolean>()
            val output = LocalCallMute({ 0 }, { true }, { muted }, { muted = it; writes += it })
            output.confirm(650, approveMute)
            if (!initialMute) muted = false // A user volume change supersedes this offer's mute.
            output.confirm(650, approveMute)
            output.restore()
            assertEquals(initialMute, muted)
            assertEquals(if (initialMute) emptyList() else listOf(true), writes)
        }
    }

    @Test fun retiringCallRestoresItsLocalOutputAndRejectsALateConfirmation() {
        val identity = ShellCallIdentity({ 0L }, { TelecomCallSnapshot.Observed("TC@4") })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
        identity.trackingReady(generation)
        val lease = checkNotNull(identity.current())
        var muted = false
        val output = LocalCallMute({ 0 }, { true }, { muted }, { muted = it })
        val endpoint = ShellCallEndpoint(identity, lease) { output.restore() }
        val confirm = {
            output.confirm(650) { endpoint.runWhileAuthorized { true } == true }
        }
        confirm()
        assertTrue(muted)
        endpoint.retire()
        assertFalse(muted)
        confirm()
        assertFalse(muted)
    }

    @Test fun withdrawnOfferCannotMuteAfterItsVolumeReadCompletes() {
        val identity = ShellCallIdentity({ 0L }, { TelecomCallSnapshot.Observed("TC@4") })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
        identity.trackingReady(generation)
        val lease = checkNotNull(identity.current())
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val writes = mutableListOf<Boolean>()
        val output = LocalCallMute({ 0L }, { true }, {
            reading.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            false
        }, { writes += it })
        val endpoint = ShellCallEndpoint(identity, lease)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val confirmation = workers.submit {
                output.confirm(650) { endpoint.runWhileAuthorized { true } == true }
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val retire = workers.submit { endpoint.retire { closed.countDown() } }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            resume.countDown()
            confirmation.get(5, TimeUnit.SECONDS)
            retire.get(5, TimeUnit.SECONDS)
            assertTrue(writes.isEmpty())
        } finally {
            resume.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun blockedPlatformWriteCannotDelayTransportRetirementAndRollsBackOnReturn() {
        val identity = ShellCallIdentity({ 0L }, { TelecomCallSnapshot.Observed("TC@4") })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
        identity.trackingReady(generation)
        val endpoint = ShellCallEndpoint(identity, checkNotNull(identity.current()))
        val applied = CountDownLatch(1)
        val returnFromBinder = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val muted = java.util.concurrent.atomic.AtomicBoolean(false)
        val journal = java.util.concurrent.atomic.AtomicBoolean(false)
        val output = LocalCallMute({ 0L }, { true }, muted::get, {
            if (it) {
                muted.set(true)
                applied.countDown()
                check(returnFromBinder.await(5, TimeUnit.SECONDS))
            }
            muted.set(it)
        }, persist = { journal.set(it != CallOutputJournal.State.CLEAR); true })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val mute = workers.submit { output.confirm(650) { endpoint.runWhileAuthorized { true } == true } }
            assertTrue(applied.await(5, TimeUnit.SECONDS))
            val retire = workers.submit { endpoint.retire { closed.countDown() } }
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            retire.get(1, TimeUnit.SECONDS)
            output.requestRestore(retire = true)
            assertFalse(output.restore())
            assertFalse(muted.get())
            assertTrue(journal.get())
            returnFromBinder.countDown()
            mute.get(5, TimeUnit.SECONDS)
            assertTrue(muted.get())
            assertTrue(output.restore())
            assertFalse(muted.get())
            assertFalse(journal.get())
        } finally {
            returnFromBinder.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun aBlockedRestoreReadbackCannotDelayCompensationForALateMute() {
        val muteEntered = CountDownLatch(1)
        val releaseMute = CountDownLatch(1)
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val muted = AtomicBoolean(false)
        val writes = AtomicInteger()
        val workers = Executors.newFixedThreadPool(2)
        val output = LocalCallMute({ 0L }, { true }, {
            if (Thread.currentThread().name == "restore-readback") {
                val earlierState = muted.get()
                readEntered.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS))
                earlierState
            } else muted.get()
        }, { mute ->
            if (mute) {
                muteEntered.countDown()
                check(releaseMute.await(5, TimeUnit.SECONDS))
            } else writes.incrementAndGet()
            muted.set(mute)
        })
        val verifier = Executors.newSingleThreadExecutor { Thread(it, "restore-readback") }
        try {
            val mute = workers.submit { output.confirm(650, approveMute) }
            assertTrue(muteEntered.await(5, TimeUnit.SECONDS))
            output.requestRestore(retire = true)
            output.writeRestore()
            val earlierRead = verifier.submit(Callable { output.restoration.verify() })
            assertTrue(readEntered.await(5, TimeUnit.SECONDS))
            releaseMute.countDown()
            mute.get(5, TimeUnit.SECONDS)
            assertTrue(muted.get())
            output.writeRestore()
            assertEquals(2, writes.get())
            assertFalse(muted.get())
            assertFalse(earlierRead.isDone)
            releaseRead.countDown()
            assertFalse(earlierRead.get(5, TimeUnit.SECONDS))
            assertTrue(output.restoration.verify())
        } finally {
            releaseMute.countDown()
            releaseRead.countDown()
            workers.shutdownNow()
            verifier.shutdownNow()
        }
    }

    @Test fun expiredMuteCanRestoreWhileTheRouteQueryIsBlocked() {
        val now = java.util.concurrent.atomic.AtomicLong(0)
        val muted = java.util.concurrent.atomic.AtomicBoolean(false)
        val checking = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val output = LocalCallMute(now::get, {
            if (now.get() >= 100) {
                checking.countDown()
                check(unblock.await(5, TimeUnit.SECONDS))
            }
            true
        }, muted::get, muted::set)
        val worker = Executors.newSingleThreadExecutor()
        try {
            output.confirm(650, approveMute)
            now.set(100)
            val route = worker.submit { output.checkRoute() }
            assertTrue(checking.await(1, TimeUnit.SECONDS))
            now.set(650)
            output.expire()
            assertTrue(output.restore())
            assertFalse(muted.get())
            assertFalse(route.isDone)
        } finally {
            unblock.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun aNewShellRecoversOnlyThisBootsRecordedMuteAndKeepsFailedRecovery() {
        val disk = JournalDisk()
        val owner = Any()
        var muted = false
        val before = disk.open()
        val output = LocalCallMute({ 0L }, { true }, { muted }, {
            assertEquals(CallOutputJournal.State.MUTE_UNCONFIRMED, before.current().state)
            muted = it
        }, commitDispatch = { before.commitArm(owner) }, persist = {
            if (it == CallOutputJournal.State.MUTE_UNCONFIRMED) before.arm(owner, firstCall)
            else before.persistFor(owner, it, firstCall)
        })
        output.confirm(650, approveMute)
        assertTrue(muted)
        val after = disk.open()
        assertEquals(CallOutputJournal.State.CLEAR, disk.open(7).current().state)
        assertEquals(CallOutputJournal.State.RESTORE, after.current().state)
        var failUnmute = true
        var writes = 0
        val recovery = after.recover({ muted }) {
            if (failUnmute) error("AudioService unavailable")
            writes++
            muted = false
        }
        recovery.write(0)
        assertFalse(recovery.verify())
        assertEquals(CallOutputJournal.State.RESTORE, after.current().state)
        failUnmute = false
        recovery.write(0)
        assertTrue(recovery.verify())
        recovery.write(0) // A write queued before readback finished has already been satisfied.
        assertEquals(1, writes)
        assertFalse(muted)
        assertEquals(CallOutputJournal.State.CLEAR, after.current().state)
    }

    @Test fun reconnectRetainsTheUnknownWriteAndCompensatesWhenItArrives() {
        for (legacy in listOf(false, true)) {
            val disk = JournalDisk(ByteBuffer.allocate(8).putLong(if (legacy) 7L else 0L).array())
            var muted = true
            if (!legacy) assertTrue(disk.open().dispatch(Any(), firstCall))
            val journal = disk.open()
            val recovery = journal.recover({ muted }) { muted = false }
            assertTrue(recovery.hasReadback())
            assertFalse(recovery.verify())
            recovery.write(0)
            assertFalse(muted)
            assertFalse(recovery.verify())
            assertEquals(CallOutputJournal.State.MUTE_UNCONFIRMED, journal.current().state)
            muted = true // The previous process dispatched this before it lost the file lock.
            assertFalse(recovery.verify())
            recovery.write(0)
            assertFalse(muted)
            assertFalse(recovery.verify())
            assertEquals(CallOutputJournal.State.MUTE_UNCONFIRMED, journal.current().state)
        }
    }

    @Test fun recoveryScopeRejectsDifferentUnknownAndExpiredCalls() {
        val scope = CallOutputRecoveryScope(4, 7_000)
        assertTrue(scope.allows(1_000, 4))
        assertFalse(scope.allows(2_000, 5))
        assertFalse(scope.allows(2_000, null))
        assertFalse(scope.allows(2_000, ShellCallIdentity.NO_RECOVERY_CALL))
        assertTrue(scope.allows(2_000, ShellCallIdentity.NO_RECOVERY_CALL, confirmed = true))
        assertFalse(scope.allows(7_000, 4))
        assertFalse(scope.allows(0, 4))
    }

    @Test fun aConfirmedMuteCanBeRestoredAfterSlowRebindOnlyOnTheSameTelecomInstance() {
        val old = CallOutputRecoveryScope(4, 7_000, telecomPid = 31)
        assertTrue(old.allows(20_000, 4, confirmed = true, currentPid = 31))
        assertFalse(old.allows(20_000, 4, confirmed = false, currentPid = 31))
        assertFalse(old.allows(20_000, 4, confirmed = true, currentPid = 32))
        assertFalse(old.allows(20_000, 5, confirmed = true, currentPid = 31))
        assertFalse(old.allows(20_000, ShellCallIdentity.NO_RECOVERY_CALL, confirmed = true, currentPid = 31))
    }

    @Test fun localRestoreChecksTheCallBeforeWritingRegardlessOfTheMuteReply() {
        for (inFlight in listOf(false, true)) {
            val sameCall = AtomicBoolean(true)
            val started = CountDownLatch(1)
            val returnMute = CountDownLatch(1)
            val writes = mutableListOf<Boolean>()
            val output = LocalCallMute({ 0 }, { true }, { false }, {
                synchronized(writes) { writes += it }
                if (it) {
                    started.countDown()
                    if (inFlight) returnMute.await(2, TimeUnit.SECONDS)
                }
            }, authorizeRestore = { sameCall.get() })
            val worker = Executors.newSingleThreadExecutor()
            try {
                val confirm = worker.submit { output.confirm(650, sameCall::get) }
                assertTrue(started.await(1, TimeUnit.SECONDS))
                if (!inFlight) confirm.get(1, TimeUnit.SECONDS)
                sameCall.set(false)
                output.requestRestore(retire = true)
                output.writeRestore()
                assertEquals(listOf(true), writes)
            } finally {
                returnMute.countDown()
                worker.shutdownNow()
            }
        }
    }

    @Test fun scopedReconnectDoesNotUndoAnotherMuteAfterCompensatingOnce() {
        val disk = JournalDisk(ByteBuffer.allocate(8).putLong(-7).array())
        var muted = false
        var writes = 0
        val journal = disk.open()
        val recovery = journal.recover({ muted }, retryWhenMuted = false) {
            writes++
            muted = false
        }
        recovery.write(0)
        assertEquals(0, writes)
        assertTrue(recovery.hasReadback())
        assertFalse(recovery.verify())
        recovery.write(0)
        assertEquals(0, writes)
        muted = true // AudioService finally applies the old process's outstanding mute.
        assertFalse(recovery.verify())
        recovery.write(0)
        assertFalse(muted)
        assertFalse(recovery.verify())
        muted = true
        assertFalse(recovery.verify())
        recovery.write(0)
        assertTrue(muted)
        assertEquals(1, writes)
        assertEquals(CallOutputJournal.State.MUTE_UNCONFIRMED, journal.current().state)
    }

    @Test fun blockedReconnectReadCannotWriteAfterTheOriginalCallScopeExpires() {
        val disk = JournalDisk()
        assertTrue(disk.open().dispatch(Any(), firstCall))
        val journal = disk.open()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val recovery = journal.recover({
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        }, retryWhenMuted = false) { writes.incrementAndGet() }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val reader = workers.submit { recovery.verify() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            workers.submit { recovery.write(0) }.get(1, TimeUnit.SECONDS)
            assertEquals(0, writes.get())
            release.countDown()
            reader.get(1, TimeUnit.SECONDS)
            recovery.write(0) { firstCall.allows(6_001, 4, currentPid = 31) }
            assertEquals(0, writes.get())
            assertEquals(CallOutputJournal.Record(CallOutputJournal.State.MUTE_UNCONFIRMED, firstCall), journal.current())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun serviceStopFencesPublicationAndReadyForAnAlreadyAuthorizedCall() {
        val identity = ShellCallIdentity({ 0L }, { TelecomCallSnapshot.Observed("TC@4") })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
        identity.trackingReady(generation)
        val lease = checkNotNull(identity.current())
        val lifetime = CallAudioServiceLifetime()
        val endpoint = ShellCallEndpoint(identity, lease, lifetime)
        val awaitingAck = ShellCallEndpoint(identity, lease, lifetime)
        assertTrue(awaitingAck.writeFirstFrame(100) { true })
        assertTrue(lifetime.startStopping())
        assertFalse(lifetime.startStopping())
        assertNull(lifetime.whileRunning { error("endpoint published after shutdown") })
        assertFalse(identity.confirmsAfterRouteCheck(lease, preflight = { true }) { deadline ->
            endpoint.writeFirstFrame(deadline) { error("READY sent after shutdown") }
        })
        assertFalse(awaitingAck.acknowledgeStart())
    }

    @Test fun changingRouteDuringTheVolumeReadDoesNotMuteTheNewRoute() {
        var handset = true
        val writes = mutableListOf<Boolean>()
        val output = LocalCallMute({ 0L }, { handset }, { handset = false; false }, { writes += it })
        output.confirm(650, approveMute)
        assertTrue(writes.isEmpty())
    }

    @Test fun muteWatchdogExpiresWithoutPcmAndRetriesTheRetiredOwnersRestore() {
        var now = 0L
        var muted = false
        var failRestore = true
        var newMutes = 0
        val supervisor = CallOutputMuteWatchdog()
        val old = LocalCallMute({ now }, { true }, { muted }, {
            if (!it && failRestore) error("AudioService unavailable")
            muted = it
        })
        val next = LocalCallMute({ now }, { true }, { muted }, { muted = it; if (it) newMutes++ })
        assertNull(supervisor.update(old))
        old.confirm(650, approveMute)
        assertTrue(muted)
        now = 650
        assertEquals(old, supervisor.update(old))
        assertFalse(old.restore())
        assertTrue(muted)
        assertEquals(old, supervisor.update(next))
        assertEquals(0, newMutes)
        failRestore = false
        assertTrue(old.restore())
        assertNull(supervisor.update(next))
        assertFalse(muted)
        next.confirm(649, approveMute)
        assertFalse(muted)
        next.confirm(1_000, approveMute)
        assertTrue(muted)
        now = 1_000
        assertEquals(next, supervisor.update(next))
        assertTrue(next.restore())
        assertFalse(muted)
        assertEquals(1, newMutes)
    }
    @Test fun disconnectOrReplacementStopsAnEndpointBeforeItsNextWrite() {
        for (disconnect in listOf(true, false)) {
            var elapsed = 0L
            var foreground = "TC@4"
            val identity = ShellCallIdentity({ elapsed }, { TelecomCallSnapshot.Observed(foreground) })
            val generation = identity.beginTracking()
            identity.trackSubscriptions(generation, setOf(1))
            identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
            identity.trackingReady(generation)
            val lease = checkNotNull(identity.current())
            val endpoint = ShellCallEndpoint(identity, lease)
            val writer = Executors.newSingleThreadExecutor()
            val checked = CountDownLatch(1)
            val readyToWrite = CountDownLatch(1)
            try {
                val pendingWrite = writer.submit(Callable {
                    check(endpoint.isLive() && identity.matchesCached(lease))
                    checked.countDown()
                    check(readyToWrite.await(5, TimeUnit.SECONDS))
                    endpoint.runAudioWhileAuthorized { 1 }
                })
                assertTrue(checked.await(5, TimeUnit.SECONDS))
                if (disconnect) {
                    identity.onDisconnected(generation, 1)
                } else {
                    elapsed = 200
                    foreground = "TC@9"
                    assertTrue(identity.current() != lease)
                }
                readyToWrite.countDown()
                assertNull(pendingWrite.get(5, TimeUnit.SECONDS))
            } finally {
                writer.shutdownNow()
            }
        }
    }

    @Test fun retiredOfferStopsWritesEvenWhenTheCallLeaseIsStillCurrent() {
        val identity = ShellCallIdentity({ 0L }, { TelecomCallSnapshot.Observed("TC@4") })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
        identity.trackingReady(generation)
        val lease = checkNotNull(identity.current())
        val endpoint = ShellCallEndpoint(identity, lease)
        assertEquals(1, endpoint.runWhileAuthorized { 1 })
        assertTrue(endpoint.retire())
        assertTrue(identity.matchesCached(lease))
        assertNull(endpoint.runWhileAuthorized { error("retired offer wrote PCM") })
    }

    @Test fun revocationCanCloseTransportWhileAnAdmittedAudioOperationIsStillInFlight() {
        lateinit var endpoint: ShellCallEndpoint
        val transportClosed = CountDownLatch(1)
        val identity = ShellCallIdentity({ 0L }, { TelecomCallSnapshot.Observed("TC@4") }, {
            endpoint.retire { transportClosed.countDown() }
        })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
        identity.trackingReady(generation)
        val lease = checkNotNull(identity.current())
        endpoint = ShellCallEndpoint(identity, lease)
        val workers = Executors.newFixedThreadPool(2)
        val writing = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        try {
            val operation = workers.submit(Callable {
                endpoint.runAudioWhileAuthorized {
                    writing.countDown()
                    check(finishWrite.await(5, TimeUnit.SECONDS))
                    1
                }
            })
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            val revoked = workers.submit(Callable { identity.onDisconnected(generation, 1) })
            assertTrue(transportClosed.await(5, TimeUnit.SECONDS))
            assertFalse(revoked.isDone)
            assertNull(endpoint.runWhileAuthorized { error("retired offer sent downlink") })
            finishWrite.countDown()
            assertEquals(1, operation.get(5, TimeUnit.SECONDS))
            assertTrue(revoked.get(2, TimeUnit.SECONDS))
            assertNull(endpoint.runAudioWhileAuthorized { error("retired offer wrote uplink") })
        } finally {
            finishWrite.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun aPendingRetryOnlyAllowsAudioForAnAlreadyStartedEndpoint() {
        var elapsed = 0L
        var result: TelecomCallSnapshot = TelecomCallSnapshot.Observed("TC@4")
        val identity = ShellCallIdentity({ elapsed }, { result })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
        identity.trackingReady(generation)
        val lease = checkNotNull(identity.current())
        val endpoint = ShellCallEndpoint(identity, lease)

        elapsed = 200
        result = TelecomCallSnapshot.Unavailable()
        assertEquals(lease, identity.current())
        assertEquals(1, endpoint.runAudioWhileAuthorized { 1 })
        assertNull(endpoint.runAudioWhileAuthorized(startBefore = 500) { error("unverified endpoint started") })

        elapsed = 280
        result = TelecomCallSnapshot.Observed("TC@9")
        assertTrue(identity.current() != lease)
        assertNull(endpoint.runAudioWhileAuthorized { error("old endpoint wrote PCM after the retry") })
    }

    @Test fun startupProofCanExpireBeforeAPlatformAudioCallAcquiresTheGate() {
        var elapsed = 0L
        val identity = ShellCallIdentity({ elapsed }, { TelecomCallSnapshot.Observed("TC@4") })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
        identity.trackingReady(generation)
        val lease = checkNotNull(identity.current())
        val endpoint = ShellCallEndpoint(identity, lease)

        elapsed = 199
        var started = false
        assertFalse(identity.confirmsStart(lease, authorize = { deadline ->
            elapsed = deadline
            endpoint.runAudioWhileAuthorized(startBefore = deadline) { started = true } != null
        }))
        assertFalse(started)
        assertTrue(identity.matchesCached(lease))
    }

    @Test fun scopedDiagnosticsDoNotWaitForTheEndpointLifecycleMonitor() {
        val audio = ShellCallAudio(ShellCallIdentity(elapsed = { 0L }))
        val binderThread = Executors.newSingleThreadExecutor()
        try {
            synchronized(audio) {
                val status = binderThread.submit(Callable { audio.diagnosticStatus(1, 1) })
                assertNull(status.get(1, TimeUnit.SECONDS))
            }
        } finally {
            binderThread.shutdownNow()
        }
    }
}
