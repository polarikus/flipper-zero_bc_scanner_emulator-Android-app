package com.polarikus.bcscanner

import android.app.Application
import android.content.Context
import com.polarikus.bcscanner.data.prefs.DataStoreDeviceStore
import com.polarikus.bcscanner.data.prefs.DeviceStore
import com.polarikus.bcscanner.domain.ConnectionStateMachine
import com.polarikus.bcscanner.domain.FlipperRpcClient
import com.polarikus.bcscanner.domain.FlipperTransport
import com.polarikus.bcscanner.scanner.BarcodeScanner

/**
 * Application entry point and composition root (manual DI).
 *
 * Dependencies are wired here; platform implementations are hidden behind interfaces.
 */
class BcScannerApplication : Application() {

    /** Manual dependency container. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Thin manual DI container.
 *
 * Foundational bindings are available immediately. Implementations that belong to a user
 * story are registered at the end of that story (see tasks.md):
 * `BleFlipperTransport` after T017, `RpcFlipperClient` after T032, `MlKitBarcodeScanner`
 * after T033.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Persists the last used FlipperZero address. */
    val deviceStore: DeviceStore by lazy { DataStoreDeviceStore(appContext) }

    /** Connection state machine shared across screens. */
    val connectionStateMachine: ConnectionStateMachine = ConnectionStateMachine()

    /** BLE transport; registered by US1 (T017). */
    var flipperTransport: FlipperTransport? = null

    /** RPC client; registered by US2 (T032). */
    var rpcClient: FlipperRpcClient? = null

    /** Barcode scanner; registered by US2 (T033). */
    var barcodeScanner: BarcodeScanner? = null
}
