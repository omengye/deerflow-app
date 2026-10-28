package com.deerflow.app.data

import org.junit.Assert.*
import org.junit.Test

class DefaultNetworkTrackerTest {
    private val internet = NetworkHints(usable = true, validated = true, captivePortal = false)
    private val privateNetwork = NetworkHints(usable = true, validated = false, captivePortal = false)

    @Test fun waitsForInitialCapabilitiesInsteadOfGuessingPublicReachability() {
        val tracker = DefaultNetworkTracker<String>()
        assertFalse(tracker.available("wifi").available)
        assertEquals(0L, tracker.status.revision)
        val connected = tracker.capabilities("wifi", internet)
        assertTrue(connected.available)
        assertEquals(true, connected.validated)
        assertEquals(1L, connected.revision)
    }

    @Test fun handoverCommitsOnceAndOldNetworkCallbacksCannotTakeNewNetworkOffline() {
        val tracker = DefaultNetworkTracker<String>()
        tracker.available("wifi")
        val wifi = tracker.capabilities("wifi", internet)
        assertEquals(wifi, tracker.available("mobile"))
        assertEquals(wifi, tracker.lost("wifi"))
        assertEquals(wifi, tracker.capabilities("wifi", privateNetwork))
        val mobile = tracker.capabilities("mobile", internet)
        assertTrue(mobile.available)
        assertEquals(wifi.revision + 1, mobile.revision)
        assertEquals(mobile, tracker.lost("wifi"))
        assertEquals(mobile, tracker.blocked("wifi", true))
    }

    @Test fun lossDuringHandoverStopsConnectionAndLateCapabilitiesCannotReviveIt() {
        val tracker = DefaultNetworkTracker<String>()
        tracker.available("wifi")
        tracker.capabilities("wifi", internet)
        tracker.available("mobile")
        val offline = tracker.lost("mobile")
        assertFalse(offline.available)
        assertEquals(2L, offline.revision)
        assertEquals(offline, tracker.capabilities("mobile", internet))
        assertEquals(offline, tracker.capabilities("wifi", internet))
    }

    @Test fun repeatedCapabilitiesDoNotProduceReconnects() {
        val tracker = DefaultNetworkTracker<Int>()
        tracker.available(1)
        val first = tracker.capabilities(1, internet)
        repeat(30) {
            assertEquals(first, tracker.available(1))
            assertEquals(first, tracker.capabilities(1, internet.copy()))
        }
    }

    @Test fun privateLanOrVpnRemainsUsableWithoutAndroidValidation() {
        val tracker = DefaultNetworkTracker<Int>()
        tracker.available(1)
        val private = tracker.capabilities(1, privateNetwork)
        assertTrue(private.available)
        assertEquals(false, private.validated)
        val validated = tracker.capabilities(1, internet)
        assertEquals(private.revision, validated.revision)
        assertEquals(private.retryRevision + 1, validated.retryRevision)
        val unvalidated = tracker.capabilities(1, privateNetwork)
        assertTrue(unvalidated.available)
        assertEquals(validated.revision, unvalidated.revision)
        assertEquals(validated.retryRevision, unvalidated.retryRevision)
    }

    @Test fun captivePortalClearWakesRetryEvenBeforeAndroidValidationCompletes() {
        val tracker = DefaultNetworkTracker<Int>()
        tracker.available(1)
        val captive = tracker.capabilities(1, privateNetwork.copy(captivePortal = true))
        assertTrue(captive.available) // The configured server can still be reachable on this LAN.
        val cleared = tracker.capabilities(1, privateNetwork)
        assertEquals(captive.revision, cleared.revision)
        assertEquals(captive.retryRevision + 1, cleared.retryRevision)
        assertEquals(cleared, tracker.capabilities(1, privateNetwork))
    }

    @Test fun blockedDefaultNetworkWaitsUntilAndroidAllowsNetworkUseAgain() {
        val tracker = DefaultNetworkTracker<Int>()
        tracker.available(1)
        tracker.blocked(1, true)
        val blocked = tracker.capabilities(1, internet)
        assertFalse(blocked.available)
        val unblocked = tracker.blocked(1, false)
        assertTrue(unblocked.available)
        assertEquals(blocked.revision + 1, unblocked.revision)
        assertEquals(unblocked, tracker.blocked(1, false))
    }

    @Test fun sameNetworkLossAndReturnChangesGenerationWithoutDuplicatingLoss() {
        val tracker = DefaultNetworkTracker<Int>()
        tracker.available(1)
        val first = tracker.capabilities(1, internet)
        val lost = tracker.lost(1)
        assertFalse(lost.available)
        assertEquals(first.revision + 1, lost.revision)
        assertEquals(lost, tracker.lost(1))
        tracker.available(1)
        val returned = tracker.capabilities(1, internet)
        assertTrue(returned.available)
        assertEquals(lost.revision + 1, returned.revision)
    }
}
