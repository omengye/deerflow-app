package com.deerflow.app.data.background

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class BackgroundConnectionPolicyTest {
    @Test fun coldProcessDoesNotConnectUntilUserOpensApp() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val policy = BackgroundConnectionPolicy(owner, 40)
            policy.setVisible(false)
            assertFalse(policy.state.value.keepConnected)
            policy.setVisible(true)
            assertTrue(policy.state.value.keepConnected)
        } finally { owner.cancel() }
    }

    @Test fun briefBackgroundRoundTripDoesNotEmitADisconnect() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val policy = BackgroundConnectionPolicy(owner, 100)
            val transitions = mutableListOf<Boolean>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { policy.keepConnected.collect { transitions += it } }
            policy.setVisible(true); yield()
            policy.setVisible(false); yield()
            assertTrue(policy.state.value.graceActive)
            policy.setVisible(true); yield()
            delay(130)
            assertEquals(listOf(false, true), transitions)
            assertFalse(policy.state.value.graceActive)
            collector.cancelAndJoin()
        } finally { owner.cancel() }
    }

    @Test fun idleBackgroundExpiresAndForegroundReopensIt() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val policy = BackgroundConnectionPolicy(owner, 30)
            policy.setVisible(true)
            policy.setVisible(false)
            assertTrue(policy.state.value.keepConnected)
            delay(70)
            assertFalse(policy.state.value.keepConnected)
            policy.setVisible(true)
            assertTrue(policy.state.value.keepConnected)
        } finally { owner.cancel() }
    }

    @Test fun activeServiceOutlivesGraceAndCompletionReleasesConnection() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val policy = BackgroundConnectionPolicy(owner, 30)
            policy.setVisible(true)
            policy.serviceStarted()
            policy.setVisible(false)
            delay(70)
            assertFalse(policy.state.value.graceActive)
            assertTrue(policy.state.value.keepConnected)
            policy.serviceStopped()
            assertFalse(policy.state.value.keepConnected)
        } finally { owner.cancel() }
    }

    @Test fun serviceCompletionDuringGraceDoesNotDropSocketEarly() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val policy = BackgroundConnectionPolicy(owner, 100)
            policy.setVisible(true)
            policy.serviceStarted()
            policy.setVisible(false)
            policy.serviceStopped()
            assertTrue(policy.state.value.keepConnected)
            delay(140)
            assertFalse(policy.state.value.keepConnected)
        } finally { owner.cancel() }
    }

    @Test fun pauseAndTimeoutFenceLateServiceStartsUntilForegroundReturns() = runBlocking {
        for (reason in listOf("用户暂停", "系统时限")) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val policy = BackgroundConnectionPolicy(owner, 100)
                policy.setVisible(true)
                policy.serviceStarted()
                policy.setVisible(false)
                policy.pause(reason)
                assertFalse(policy.state.value.keepConnected)
                policy.serviceStopped()
                policy.serviceStarted()
                assertFalse(policy.state.value.keepConnected)
                policy.setVisible(true)
                assertNull(policy.state.value.pauseReason)
                assertTrue(policy.state.value.keepConnected)
            } finally { owner.cancel() }
        }
    }

    @Test fun failedOrPausedBackgroundServiceDoesNotBreakForegroundChat() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val policy = BackgroundConnectionPolicy(owner, 100)
            policy.setVisible(true)
            policy.pause("服务不可用")
            policy.serviceStopped()
            assertTrue(policy.state.value.keepConnected)
            policy.setVisible(false)
            assertFalse(policy.state.value.keepConnected)
        } finally { owner.cancel() }
    }

    @Test fun newerBackgroundGraceCannotBeExpiredByAnOlderTimer() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val policy = BackgroundConnectionPolicy(owner, 150)
            policy.setVisible(true)
            policy.setVisible(false)
            delay(100)
            policy.setVisible(true)
            policy.setVisible(false)
            delay(80)
            assertTrue(policy.state.value.keepConnected)
            delay(110)
            assertFalse(policy.state.value.keepConnected)
        } finally { owner.cancel() }
    }

    @Test fun serviceStateChangesWhileVisibleDoNotRestartTransport() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val policy = BackgroundConnectionPolicy(owner)
            policy.setVisible(true)
            val transitions = mutableListOf<Boolean>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { policy.keepConnected.collect { transitions += it } }
            policy.serviceStarted(); yield()
            policy.serviceStopped(); yield()
            policy.pause("服务不可用"); yield()
            assertEquals(listOf(true), transitions)
            collector.cancelAndJoin()
        } finally { owner.cancel() }
    }

    @Test fun transmissionAndUnknownAcknowledgementKeepMonitoringUntilResolved() {
        assertTrue(BackgroundWork(transmitting = 1).hasWork)
        assertTrue(BackgroundWork(pendingOperations = 1).hasWork)
        assertTrue(BackgroundWork(runningTasks = 1, waitingTasks = 1).hasWork)
        assertFalse(BackgroundWork().hasWork)
        assertTrue(BackgroundWork(runningTasks = 1, waitingTasks = 1).progressText.contains("授权"))
        assertTrue(BackgroundWork(pendingOperations = 1).progressText.contains("不会重复提交"))
    }
}
