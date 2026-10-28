package com.deerflow.app.data

import com.deerflow.app.data.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRequestTest {
    @Test fun memoryReceiptWritesWithIdenticalSettingsDoNotRestartConnection() = runTest {
        val settings = MutableSharedFlow<AppSettings>(replay = 1)
        val network = MutableStateFlow(NetworkStatus(true, 1))
        val refresh = MutableStateFlow(0L)
        val seen = mutableListOf<ConnectionRequest>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connectionRequests(settings, flowOf(true), network, refresh).collect { seen += it }
        }
        val config = AppSettings("wss://example.test/v1", "synthetic")
        settings.emit(config); runCurrent()
        repeat(20) { settings.emit(config.copy()); runCurrent() }
        assertEquals(1, seen.size)
        refresh.value++; runCurrent(); assertEquals(2, seen.size)
        network.value = NetworkStatus(true, 2); runCurrent(); assertEquals(3, seen.size)
        job.cancel()
    }

    @Test fun thirtyNetworkHandoversCancelOldRecoveryAndKeepOnlyTheLatestGeneration() = runTest {
        val network = MutableStateFlow(NetworkStatus(true, 1))
        val started = mutableListOf<Long>(); val finished = mutableListOf<Long>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connectionRequests(flowOf(AppSettings()), flowOf(true), network, flowOf(0L)).collectLatest {
                started += it.network.revision
                delay(5000)
                finished += it.network.revision
            }
        }
        runCurrent()
        repeat(30) { network.value = NetworkStatus(true, it + 2L); runCurrent(); advanceTimeBy(200) }
        advanceTimeBy(5000); runCurrent()
        assertEquals((1L..31L).toList(), started)
        assertEquals(listOf(31L), finished)
        job.cancel()
    }

    @Test fun duplicateNetworkCapabilitiesDoNotReconnectButOfflineAndReturnDo() = runTest {
        val network = MutableStateFlow(NetworkStatus(true, 1)); val seen = mutableListOf<NetworkStatus>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connectionRequests(flowOf(AppSettings()), flowOf(true), network, flowOf(0L)).collect { seen += it.network }
        }
        runCurrent()
        repeat(10) { network.value = NetworkStatus(true, 1); runCurrent() }
        network.value = NetworkStatus(false, 2); runCurrent()
        network.value = NetworkStatus(true, 3); runCurrent()
        assertEquals(listOf(NetworkStatus(true, 1), NetworkStatus(false, 2), NetworkStatus(true, 3)), seen)
        job.cancel()
    }
}
