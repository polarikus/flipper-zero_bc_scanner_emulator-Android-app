package com.polarikus.bcscanner.data.rpc

import com.flipperdevices.protobuf.Flipper
import com.polarikus.bcscanner.domain.FlipperTransport
import com.polarikus.bcscanner.domain.model.ConnectionState
import com.polarikus.bcscanner.domain.model.FlipperDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [RpcFlipperClient]'s automatic application start (FR-019).
 *
 * A user FAP installed on the SD card is not part of the firmware's external-application list,
 * so it cannot be started by its short `appid` on stock firmware. The client must fall back to
 * the full `.fap` path when the name-based start is rejected with `ERROR_INVALID_PARAMETERS`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RpcFlipperClientTest {

    @Test
    fun `autostart uses the app name when it is accepted`() = runTest {
        val transport = FakeTransport { Flipper.CommandStatus.OK }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            RpcFlipperClient(transport, scope, timeoutMillis = 1_000)

            transport.setState(ConnectionState.Connected)
            advanceUntilIdle()

            assertEquals(listOf(RpcFlipperClient.DEFAULT_APP_NAME), transport.startNames)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `autostart falls back to the app path when the name is unknown`() = runTest {
        val transport = FakeTransport { message ->
            when (message.appStartRequest.name) {
                RpcFlipperClient.DEFAULT_APP_NAME -> Flipper.CommandStatus.ERROR_INVALID_PARAMETERS
                RpcFlipperClient.DEFAULT_APP_PATH -> Flipper.CommandStatus.OK
                else -> Flipper.CommandStatus.ERROR_INVALID_PARAMETERS
            }
        }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            RpcFlipperClient(transport, scope, timeoutMillis = 1_000)

            transport.setState(ConnectionState.Connected)
            advanceUntilIdle()

            assertEquals(
                listOf(RpcFlipperClient.DEFAULT_APP_NAME, RpcFlipperClient.DEFAULT_APP_PATH),
                transport.startNames,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sendData restarts the app when it is not running and retries once`() = runTest {
        val transport = ExchangeTransport(
            dataStatuses = ArrayDeque(
                listOf(
                    Flipper.CommandStatus.ERROR_APP_NOT_RUNNING,
                    Flipper.CommandStatus.OK,
                ),
            ),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val client = RpcFlipperClient(transport, scope, timeoutMillis = 1_000)

            val delivered = client.sendData("value".toByteArray())

            assertEquals(true, delivered)
            assertEquals(listOf(RpcFlipperClient.DEFAULT_APP_NAME), transport.startNames)
        } finally {
            scope.cancel()
        }
    }

    /** In-memory [FlipperTransport] that answers every `app_start_request` from [respond]. */
    private class FakeTransport(
        private val respond: (Flipper.Main) -> Flipper.CommandStatus,
    ) : FlipperTransport {

        private val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        private val incomingFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
        private val codec = RpcFrameCodec()

        val startNames = mutableListOf<String>()

        override val connectionState: Flow<ConnectionState> = state
        override val incoming: Flow<ByteArray> = incomingFlow

        override suspend fun connect(device: FlipperDevice) = Unit

        override suspend fun disconnect() = Unit

        override suspend fun write(data: ByteArray) {
            val message = codec.decode(data).single()
            if (!message.hasAppStartRequest()) return
            startNames += message.appStartRequest.name
            val response = Flipper.Main.newBuilder()
                .setCommandId(message.commandId)
                .setCommandStatus(respond(message))
                .build()
            incomingFlow.tryEmit(codec.encode(response))
        }

        fun setState(value: ConnectionState) {
            state.value = value
        }
    }

    /**
     * In-memory transport that accepts `app_start_request` with `OK` and answers each
     * `app_data_exchange_request` with the next status from [dataStatuses].
     */
    private class ExchangeTransport(
        private val dataStatuses: ArrayDeque<Flipper.CommandStatus>,
    ) : FlipperTransport {

        private val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        private val incomingFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
        private val codec = RpcFrameCodec()

        val startNames = mutableListOf<String>()

        override val connectionState: Flow<ConnectionState> = state
        override val incoming: Flow<ByteArray> = incomingFlow

        override suspend fun connect(device: FlipperDevice) = Unit

        override suspend fun disconnect() = Unit

        override suspend fun write(data: ByteArray) {
            val message = codec.decode(data).single()
            val status = when {
                message.hasAppStartRequest() -> {
                    startNames += message.appStartRequest.name
                    Flipper.CommandStatus.OK
                }

                message.hasAppDataExchangeRequest() ->
                    dataStatuses.removeFirstOrNull() ?: Flipper.CommandStatus.OK

                else -> return
            }
            val response = Flipper.Main.newBuilder()
                .setCommandId(message.commandId)
                .setCommandStatus(status)
                .build()
            incomingFlow.tryEmit(codec.encode(response))
        }
    }
}
