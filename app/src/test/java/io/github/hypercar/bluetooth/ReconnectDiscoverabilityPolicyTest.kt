package io.github.hypercar.bluetooth

import io.github.hypercar.headunit.HeadUnitProfiles
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReconnectDiscoverabilityPolicyTest {
    private val bmw = "AA:BB:CC:DD:EE:01"
    private val second = "AA:BB:CC:DD:EE:02"
    private val desktop = "AA:BB:CC:DD:EE:03"

    private fun automaticKey(
        bonded: Map<String, String?>,
        history: Set<String>,
        profile: String = "bmw_id85",
        listening: Boolean = true,
        allowed: Boolean = true,
    ) = ReconnectDiscoverability.automaticBmwPreparationKey(profile, listening, allowed, history) { bonded[it] }

    @Test fun handoffKeepsWindowDuringTeardownAndOnlyForPublishedLiveSuccessor() {
        val moving = DiscoverabilityHandoff(origin = 42)
        assertTrue(moving.keepsWindow(42, 42, live = false))
        assertTrue(moving.keepsWindow(42, 43, live = false))
        assertFalse(moving.keepsWindow(41, 43, live = true))

        val successor = DiscoverabilityHandoff(origin = 42, successor = 43)
        assertTrue(successor.keepsWindow(42, 43, live = true))
        assertFalse(successor.keepsWindow(42, 43, live = false))
        assertFalse(successor.keepsWindow(42, 44, live = true))
    }

    @Test fun activityGrantStillCommitsOnceAfterWaitingWatchIsCancelled() = runBlocking {
        val reservation = DiscoverabilityReservation()
        assertTrue(reservation.acquire())
        val recorded = mutableListOf<Int?>()
        val request = SystemDiscoverabilityRequest {
            recorded.add(it)
            reservation.start()
        }
        assertTrue(request.takeOwnership())
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { request.await() }
        waiting.cancelAndJoin()
        request.abandonIfUnowned()
        assertEquals(DiscoverabilityReservation.Phase.RESERVED, reservation.phase)
        assertFalse(reservation.acquire())
        request.finish(30)
        assertEquals(DiscoverabilityReservation.Phase.STARTED, reservation.phase)
        request.finish(null)
        request.finish(30)
        assertEquals(listOf<Int?>(30), recorded)
        assertEquals(30, request.await())
    }

    @Test fun permissionRefusalAndActivityDestructionReleaseExactlyOnce() = runBlocking {
        for (result in listOf(0, null)) {
            val reservation = DiscoverabilityReservation()
            assertTrue(reservation.acquire())
            val recorded = mutableListOf<Int?>()
            val request = SystemDiscoverabilityRequest {
                recorded.add(it)
                reservation.release()
            }
            assertTrue(request.takeOwnership())
            request.finish(result)
            assertEquals(result, request.await())
            assertTrue(reservation.acquire())
            request.finish(30)
            request.abandonIfUnowned()
            assertEquals(listOf(result), recorded)
            assertEquals(DiscoverabilityReservation.Phase.RESERVED, reservation.phase)
        }
    }

    @Test fun watchMayAbandonBeforeTheActivityAcceptsOwnership() = runBlocking {
        val recorded = mutableListOf<Int?>()
        val request = SystemDiscoverabilityRequest { recorded.add(it) }
        request.abandonIfUnowned()
        assertFalse(request.takeOwnership())
        request.finish(30)
        assertNull(request.await())
        assertEquals(listOf<Int?>(null), recorded)
    }

    @Test fun automaticUsesOnlyTheUniqueCurrentlyBondedSuccessfulBmw() {
        val bonded = mapOf(bmw to "BMW 53636", second to "BMW 12345", desktop to "ZS1-PC")
        val history = setOf("${bmw.lowercase()}|车机|10", "$desktop|BMW|20")
        val key = automaticKey(bonded, history)
        assertNotNull(key)
        assertEquals(key, automaticKey(bonded, history + "$bmw|旧名称|1"))
        assertNull(automaticKey(bonded, history + "$second|车机|30"))
        assertNull(automaticKey(bonded - bmw, history))
        assertNull(automaticKey(bonded, emptySet()))
        assertNull(automaticKey(bonded, history, profile = "bmw_id9"))
        assertNull(automaticKey(bonded, history, listening = false))
        assertNull(automaticKey(bonded, history, allowed = false))
    }

    @Test fun automaticBmwOpensOnceBeforeWifiAndIgnoresCompanionRearm() {
        val key = automaticKey(mapOf(bmw to "BMW 53636"), setOf("$bmw|BMW 53636|10"))
        val p = ReconnectDiscoverabilityPolicy()
        assertFalse(p.shouldAttempt(true, null, false, false, 0, key))
        assertFalse(p.shouldAttempt(true, null, false, false, 4_999, key))
        assertTrue(p.shouldAttempt(true, null, false, false, 5_000, key))
        assertFalse(p.armForWake())
        assertFalse(p.shouldAttempt(true, null, false, false, 40_000, key))
    }

    @Test fun coldBmwPreparationDoesNotNeedWifiAndSurvivesWifiArrival() {
        val p = ReconnectDiscoverabilityPolicy()
        val prep = ReconnectDiscoverability.preparationKey("bmw_id85", false, bmw, true, true, emptySet()) { "" }
        assertNotNull(prep)
        assertFalse(p.shouldAttempt(true, null, false, false, 0, prep))
        assertTrue(p.shouldAttempt(true, "new wifi", false, false, 5_000, prep))
        assertFalse(p.shouldAttempt(true, "new wifi", true, false, 10_000, prep))
        assertFalse(p.armForWake())
        p.noteWindowClosed()
        assertTrue(p.shouldAttempt(true, "new wifi", true, false, 11_000, prep, aclPreparationKey = bmw))
    }

    @Test fun systemConfirmationOnlyBacksUpPreparedManualStartInForeground() {
        assertEquals(DiscoverabilityRoute.SYSTEM_CONFIRMATION, discoverabilityRoute(false, true, true, true))
        assertEquals(DiscoverabilityRoute.SHELL, discoverabilityRoute(true, true, true, true))
        assertEquals(DiscoverabilityRoute.SHELL, discoverabilityRoute(true, false, false, false))
        assertEquals(DiscoverabilityRoute.SKIP, discoverabilityRoute(false, false, true, true))
        assertEquals(DiscoverabilityRoute.SKIP, discoverabilityRoute(false, true, false, true))
        assertEquals(DiscoverabilityRoute.SKIP, discoverabilityRoute(false, true, true, false))
    }

    @Test fun manualStartOpensOnFirstReadyCheckForEveryProfileAndDoesNotRearm() {
        for (profile in HeadUnitProfiles.builtin) {
            val p = ReconnectDiscoverabilityPolicy(immediatePreparation = true)
            val prep = ReconnectDiscoverability.preparationKey(profile.id, true, bmw, true, true, emptySet()) { "" }
            assertEquals(profile.id, "bt:$bmw", prep)
            assertFalse(p.shouldAttempt(true, null, false, false, 0))
            assertTrue(p.shouldAttempt(true, null, false, false, 1, prep))
            assertFalse(p.armForWake())
            assertFalse(p.shouldAttempt(true, "car", true, false, 40_000, prep))
            assertFalse(p.shouldAttempt(true, "car", true, false, 80_000))
        }
    }

    @Test fun manualClickWhileWindowOpenDoesNotOpenAnotherAndClickOutsideStillDoes() {
        val prep = ReconnectDiscoverability.preparationKey("bmw_id85", true, bmw, true, true, emptySet()) { "" }
        val during = ReconnectDiscoverabilityPolicy(immediatePreparation = true)
        assertFalse(during.shouldAttempt(true, null, false, false, 1, prep, leaseOpen = true))
        assertFalse(during.shouldAttempt(true, null, false, false, 2, prep, leaseOpen = false))
        assertFalse(during.shouldAttempt(true, "car", true, false, 40_000, prep, leaseOpen = false))

        val fresh = ReconnectDiscoverabilityPolicy(immediatePreparation = true)
        assertTrue(fresh.shouldAttempt(true, null, false, false, 1, prep, leaseOpen = false))
    }

    @Test fun manualClickSuppressionSurvivesWindowExpirationDuringRestartButAllowsNewAcl() {
        val p = ReconnectDiscoverabilityPolicy(immediatePreparation = true, suppressManualWindow = true)
        // The old window is already gone by the time this new watch even starts.
        assertFalse(p.shouldAttempt(true, null, false, false, 0))
        assertFalse(p.shouldAttempt(true, null, false, false, 10_000, "bt:$bmw"))
        assertFalse(p.shouldAttempt(true, "car", true, false, 30_000))
        assertTrue(p.shouldAttempt(true, null, false, false, 30_500, aclPreparationKey = bmw))
        p.noteWindowClosed()
        assertFalse(p.shouldAttempt(true, "car", true, false, 61_000, "bt:$bmw"))
    }

    @Test fun manualCannotOpenIfDisabledOrSessionAlreadyClaimed() {
        val p = ReconnectDiscoverabilityPolicy(immediatePreparation = true)
        val prep = ReconnectDiscoverability.preparationKey("generic", true, bmw, true, true, emptySet()) { "" }
        assertFalse(p.shouldAttempt(false, null, false, false, 0, prep))
        assertFalse(p.shouldAttempt(true, null, false, true, 1, prep))
        assertFalse(p.shouldAttempt(true, null, false, false, 2, prep))
    }

    @Test fun manualWithoutPairedPreparationKeepsNetworkGrace() {
        val p = ReconnectDiscoverabilityPolicy(immediatePreparation = true)
        assertFalse(p.shouldAttempt(true, "car", true, false, 0))
        assertFalse(p.shouldAttempt(true, "car", true, false, 4_999))
        assertTrue(p.shouldAttempt(true, "car", true, false, 5_000))
    }

    @Test fun coldPreparationRequiresPairedTargetListeningAndAuthorizedAttempt() {
        fun key(mac: String? = bmw, paired: Boolean = true, listening: Boolean = true, allowed: Boolean = true) =
            ReconnectDiscoverability.preparationKey("generic", true, mac, listening, allowed, emptySet()) {
                if (paired && it == bmw) "" else null
            }
        assertNull(key(mac = null))
        assertNull(key(mac = "not a MAC"))
        assertNull(key(paired = false))
        assertNull(key(listening = false))
        assertNull(key(allowed = false))
        assertEquals(key(), key(mac = bmw.lowercase()))
    }

    @Test fun manualAutomaticTargetRequiresUniqueBondedHistoryOutsideBmwFallback() {
        val history = setOf("${second.lowercase()}|车机|20", "$second|旧名称|1", "$bmw|宝马|10")
        fun key(target: String? = null, bonded: Set<String> = setOf(second, desktop)) =
            ReconnectDiscoverability.preparationKey("generic", true, target, true, true, history) {
                if (it in bonded) "" else null
            }
        assertEquals("bt:$second", key())
        assertNull(key(bonded = setOf(second, bmw)))
        assertNull(key(target = bmw)) // A missing explicit target cannot be silently replaced.
        assertNull(key(bonded = setOf(desktop)))
    }

    @Test fun automaticEarlyPreparationRemainsLimitedToId85() {
        for (profile in HeadUnitProfiles.builtin) {
            val history = setOf("$bmw|BMW|10")
            val explicit = ReconnectDiscoverability.preparationKey(profile.id, false, bmw, true, true, history) { "BMW" }
            val automatic = ReconnectDiscoverability.preparationKey(profile.id, false, null, true, true, history) { "BMW" }
            if (profile == HeadUnitProfiles.BMW_ID85) {
                assertEquals("bt:$bmw", explicit)
                assertEquals(automaticKey(mapOf(bmw to "BMW"), history), automatic)
            } else {
                assertNull(explicit)
                assertNull(automatic)
            }
        }
    }

    @Test fun disabledOrClaimedColdPreparationCannotOpenWindow() {
        val p = ReconnectDiscoverabilityPolicy()
        assertFalse(p.shouldAttempt(false, null, false, false, 0, "bt:paired"))
        assertFalse(p.shouldAttempt(true, null, false, false, 10_000, "bt:paired"))
        assertFalse(p.shouldAttempt(true, null, false, true, 14_000, "bt:paired"))
        assertFalse(p.shouldAttempt(true, null, false, false, 15_000, "bt:paired"))
        assertFalse(p.armForWake())
    }

    @Test fun losingColdReadinessRestartsGraceAndDoesNotAuthorizeNetworkFallback() {
        val p = ReconnectDiscoverabilityPolicy()
        p.shouldAttempt(true, null, false, false, 0, "bt:paired")
        assertFalse(p.shouldAttempt(true, null, false, false, 4_000, null))
        assertFalse(p.shouldAttempt(true, null, false, false, 10_000, "bt:paired"))
        assertFalse(p.shouldAttempt(true, null, false, false, 14_999, "bt:paired"))
        assertTrue(p.shouldAttempt(true, null, false, false, 15_000, "bt:paired"))
    }
    @Test fun waitsForNetworkAndServicesThenOnlyAttemptsOnceAcrossWaitRounds() {
        val p = ReconnectDiscoverabilityPolicy()
        assertFalse(p.shouldAttempt(true, null, true, false, 0))
        assertFalse(p.shouldAttempt(true, "car", false, false, 10_000))
        assertFalse(p.shouldAttempt(true, "car", true, false, 12_000))
        assertFalse(p.shouldAttempt(true, "car", true, false, 16_999))
        assertTrue(p.shouldAttempt(true, "car", true, false, 17_000))
        assertFalse(p.shouldAttempt(true, "car", true, false, 317_000))
        assertFalse(p.shouldAttempt(true, "new network", true, false, 400_000))
    }

    @Test fun networkChangesAndDisablingResetTheGracePeriod() {
        val p = ReconnectDiscoverabilityPolicy()
        p.shouldAttempt(true, "a", true, false, 0)
        assertFalse(p.shouldAttempt(true, "b", true, false, 4_000))
        assertFalse(p.shouldAttempt(false, "b", true, false, 9_000))
        assertFalse(p.shouldAttempt(true, "b", true, false, 10_000))
        assertTrue(p.shouldAttempt(true, "b", true, false, 15_000))
    }

    @Test fun aSessionStartingDuringGraceCancelsTheOpportunityPermanently() {
        val p = ReconnectDiscoverabilityPolicy()
        p.shouldAttempt(true, "a", true, false, 0)
        assertFalse(p.shouldAttempt(true, "a", true, true, 4_000))
        assertFalse(p.shouldAttempt(true, "a", true, false, 60_000))
        assertFalse(p.armForWake())
    }

    @Test fun companionWakeCanArmOneExtraWindowAfterTheInitialAttempt() {
        val p = ReconnectDiscoverabilityPolicy()
        assertFalse(p.armForWake())
        assertFalse(p.shouldAttempt(true, "car", true, false, 0))
        assertTrue(p.shouldAttempt(true, "car", true, false, 5_000))
        assertFalse(p.armForWake())
        assertFalse(p.shouldAttempt(true, null, false, false, 5_500, aclPreparationKey = bmw))
        p.noteWindowClosed()
        assertTrue(p.armForWake())
        assertFalse(p.armForWake())
        assertTrue(p.shouldAttempt(true, "car", true, false, 6_000))
        assertFalse(p.shouldAttempt(true, null, false, false, 6_500, aclPreparationKey = bmw))
        p.noteWindowClosed()
        assertFalse(p.shouldAttempt(true, "car", true, false, 7_000))
        assertTrue(p.shouldAttempt(true, null, false, false, 7_500, aclPreparationKey = bmw))
    }

    @Test fun knownCarAclRequiresAutomaticModeBondAndNoActiveIap2OrWindow() {
        assertFalse(knownBondedCar(false, bmw, bmw, true, null))
        assertFalse(knownBondedCar(true, "", bmw, true, null))
        assertFalse(knownBondedCar(true, second, bmw, true, bmw))
        assertTrue(knownBondedCar(true, bmw, bmw, false, null))
        assertTrue(knownBondedCar(true, bmw, null, true, null))
        assertTrue(knownBondedCar(true, bmw, null, false, bmw))
        assertFalse(knownBondedCar(true, second, null, false, null))
        assertFalse(shouldDiscoverForCarAcl(autoConnect = false, knownBonded = true, iap2Started = false, discoverableAlreadyOn = false))
        assertFalse(shouldDiscoverForCarAcl(autoConnect = true, knownBonded = false, iap2Started = false, discoverableAlreadyOn = false))
        assertFalse(shouldDiscoverForCarAcl(autoConnect = true, knownBonded = true, iap2Started = true, discoverableAlreadyOn = false))
        assertFalse(shouldDiscoverForCarAcl(autoConnect = true, knownBonded = true, iap2Started = false, discoverableAlreadyOn = true))
        assertTrue(shouldDiscoverForCarAcl(autoConnect = true, knownBonded = true, iap2Started = false, discoverableAlreadyOn = false))
    }

    @Test fun singleAclEdgeWorksWithoutWifiAndCannotReopenAfterCompletionOrFailure() {
        val p = ReconnectDiscoverabilityPolicy()
        assertFalse(p.shouldAttempt(true, null, false, false, 0))
        assertTrue(p.shouldAttempt(true, null, false, false, 100, aclPreparationKey = bmw))
        assertTrue(p.discoverableWindowOpen())
        assertFalse(p.shouldAttempt(true, "car", true, false, 1_000, aclPreparationKey = bmw))
        p.noteWindowClosed()
        assertFalse(p.discoverableWindowOpen())
        assertFalse(p.shouldAttempt(true, "car", true, false, 2_000))
        assertFalse(p.shouldAttempt(true, "car", true, false, 32_000))
        assertTrue(p.shouldAttempt(true, null, false, false, 33_000, aclPreparationKey = bmw))
        p.noteWindowClosed() // A failed shell start/bind uses the same state transition.
        assertFalse(p.shouldAttempt(true, null, false, false, 33_001))
        assertFalse(p.shouldAttempt(true, "car", true, true, 34_000, aclPreparationKey = bmw))
        assertFalse(p.shouldAttempt(true, null, false, false, 35_000, aclPreparationKey = bmw))
    }

    @Test fun staleAclBeforeBinderDoesNotUseUpTheOrdinaryManualWindow() {
        val prep = "bt:$bmw"
        val manual = ReconnectDiscoverabilityPolicy(immediatePreparation = true)
        assertTrue(manual.shouldAttempt(true, null, false, false, 0, prep, aclPreparationKey = bmw))
        manual.noteWindowClosed(staleAclBeforeStart = true)
        assertTrue(manual.shouldAttempt(true, null, false, false, 500, prep))
        manual.noteWindowClosed()
        assertFalse(manual.shouldAttempt(true, null, false, false, 31_000, prep))

        val suppressed = ReconnectDiscoverabilityPolicy(immediatePreparation = true, suppressManualWindow = true)
        assertTrue(suppressed.shouldAttempt(true, null, false, false, 0, prep, aclPreparationKey = bmw))
        suppressed.noteWindowClosed(staleAclBeforeStart = true)
        assertFalse(suppressed.shouldAttempt(true, null, false, false, 500, prep))

        val started = ReconnectDiscoverabilityPolicy(immediatePreparation = true)
        assertTrue(started.shouldAttempt(true, null, false, false, 0, prep, aclPreparationKey = bmw))
        started.noteWindowClosed()
        assertFalse(started.shouldAttempt(true, null, false, false, 500, prep))
    }

    @Test fun reservationCannotSuppressClickUntilStartIsCommitted() {
        val reservation = DiscoverabilityReservation()
        assertTrue(reservation.acquire())
        assertEquals(DiscoverabilityReservation.Phase.RESERVED, reservation.phase)
        assertFalse(reservation.acquire())
        reservation.release() // Old watch cancelled while binding; the new one is free to start.
        assertEquals(DiscoverabilityReservation.Phase.IDLE, reservation.phase)
        assertTrue(reservation.acquire())
        reservation.start()
        assertEquals(DiscoverabilityReservation.Phase.STARTED, reservation.phase)
        assertFalse(reservation.acquire())
        reservation.release()
        assertEquals(DiscoverabilityReservation.Phase.IDLE, reservation.phase)
    }

    @Test fun aclObservedDuringPriorLeaseIsNotDeferredUntilThatLeaseEnds() {
        val p = ReconnectDiscoverabilityPolicy()
        assertFalse(p.shouldAttempt(true, null, false, false, 100, leaseOpen = true, aclPreparationKey = bmw))
        assertFalse(p.shouldAttempt(true, null, false, false, 31_000))
        assertTrue(p.shouldAttempt(true, null, false, false, 32_000, aclPreparationKey = bmw))
    }
}
