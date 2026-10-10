package com.polarikus.bcscanner.data.rpc

import com.flipperdevices.protobuf.Flipper
import com.flipperdevices.protobuf.app.Application
import com.google.protobuf.ByteString
import com.polarikus.bcscanner.domain.FlipperNotReadyException
import com.polarikus.bcscanner.domain.FlipperRpcClient
import com.polarikus.bcscanner.domain.FlipperTransport
import com.polarikus.bcscanner.domain.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [FlipperRpcClient] over the BLE [FlipperTransport].
 *
 * Decodes incoming frames into `PB_Main` messages and routes them: messages with a `command_id`
 * go to the [RpcCommandDispatcher], while `app_state_response` events update [appStarted].
 *
 * On every successful connection it automatically starts the Flipper application `bc_scanner`
 * in RPC mode (`args = "RPC"`), so the user does not have to launch it manually (FR-019).
 */
class RpcFlipperClient(
    private val transport: FlipperTransport,
    private val scope: CoroutineScope,
    private val codec: RpcFrameCodec = RpcFrameCodec(),
    timeoutMillis: Long = RpcCommandDispatcher.DEFAULT_TIMEOUT_MILLIS,
    private val appName: String = DEFAULT_APP_NAME,
    private val appPath: String = DEFAULT_APP_PATH,
    private val rpcArgs: String = DEFAULT_RPC_ARGS,
) : FlipperRpcClient {

    private val dispatcher = RpcCommandDispatcher(
        codec = codec,
        write = { frame -> transport.write(frame) },
        timeoutMillis = timeoutMillis,
    )

    private val _appStarted = MutableStateFlow(false)

    /** Whether the Flipper application signalled that it is ready in RPC mode. */
    val appStarted: StateFlow<Boolean> = _appStarted.asStateFlow()

    init {
        scope.launch {
            transport.incoming.collect { chunk ->
                codec.decode(chunk).forEach(::onMessage)
            }
        }
        scope.launch {
            transport.connectionState.collect { state -> onConnectionState(state) }
        }
    }

    override suspend fun startApp(name: String, args: String?) {
        val response = dispatcher.execute {
            setAppStartRequest(
                Application.StartRequest.newBuilder()
                    .setName(name)
                    .apply { args?.let { setArgs(it) } }
                    .build(),
            )
        }
        when (response.commandStatus) {
            Flipper.CommandStatus.OK -> Unit
            Flipper.CommandStatus.ERROR_APP_NOT_RUNNING,
            Flipper.CommandStatus.ERROR_APP_CANT_START,
            Flipper.CommandStatus.ERROR_APP_SYSTEM_LOCKED,
            Flipper.CommandStatus.ERROR_INVALID_PARAMETERS,
            -> throw FlipperNotReadyException("cannot start $name: ${response.commandStatus}")

            else -> throw FlipperNotReadyException("cannot start $name: ${response.commandStatus}")
        }
    }

    /**
     * Starts the Flipper application, trying the short name first and falling back to the full
     * `.fap` path. A user application installed on the SD card is not part of the firmware's
     * external-application list, so starting it by `appid` fails with `ERROR_INVALID_PARAMETERS`
     * on stock firmware; the loader accepts the file path instead.
     */
    private suspend fun startAppWithFallback() {
        var lastError: FlipperNotReadyException? = null
        for (candidate in listOf(appName, appPath).distinct()) {
            try {
                startApp(candidate, rpcArgs)
                return
            } catch (e: FlipperNotReadyException) {
                lastError = e
            }
        }
        throw lastError ?: FlipperNotReadyException("cannot start $appName")
    }

    override suspend fun sendData(data: ByteArray): Boolean {
        val response = dispatcher.execute {
            setAppDataExchangeRequest(
                Application.DataExchangeRequest.newBuilder()
                    .setData(ByteString.copyFrom(data))
                    .build(),
            )
        }
        return when (response.commandStatus) {
            Flipper.CommandStatus.OK -> true
            Flipper.CommandStatus.ERROR_APP_CMD_ERROR -> false
            Flipper.CommandStatus.ERROR_APP_NOT_RUNNING ->
                throw FlipperNotReadyException("app is not running in RPC mode")

            else -> false
        }
    }

    override suspend fun disconnect() {
        runCatching {
            dispatcher.execute {
                setAppExitRequest(Application.AppExitRequest.getDefaultInstance())
            }
        }
        failPending("disconnected")
    }

    private fun onMessage(message: Flipper.Main) {
        if (message.hasAppStateResponse()) {
            _appStarted.value =
                message.appStateResponse.state == Application.AppState.APP_STARTED
        } else {
            dispatcher.onMessage(message)
        }
    }

    private fun onConnectionState(state: ConnectionState) {
        if (state is ConnectionState.Connected) {
            scope.launch {
                runCatching { startAppWithFallback() }
            }
        } else {
            _appStarted.value = false
            if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
                failPending("connection lost")
            }
        }
    }

    private fun failPending(reason: String) {
        codec.reset()
        dispatcher.failAll(IllegalStateException(reason))
    }

    companion object {
        /** Application id on the FlipperZero. */
        const val DEFAULT_APP_NAME = "bc_scanner"

        /** Full path of the application installed on the SD card (loader fallback). */
        const val DEFAULT_APP_PATH = "/ext/apps/USB/bc_scanner.fap"

        /** Argument that switches the Flipper application into RPC mode. */
        const val DEFAULT_RPC_ARGS = "RPC"
    }
}
