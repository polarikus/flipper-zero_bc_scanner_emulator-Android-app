package com.polarikus.bcscanner

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.polarikus.bcscanner.data.ble.AndroidFlipperDeviceScanner
import com.polarikus.bcscanner.data.ble.AndroidGattConnectionFactory
import com.polarikus.bcscanner.data.ble.BleFlipperTransport
import com.polarikus.bcscanner.data.prefs.DataStoreDeviceStore
import com.polarikus.bcscanner.data.prefs.DeviceStore
import com.polarikus.bcscanner.domain.ConnectionStateMachine
import com.polarikus.bcscanner.domain.FlipperDeviceScanner
import com.polarikus.bcscanner.domain.FlipperRpcClient
import com.polarikus.bcscanner.domain.FlipperTransport
import com.polarikus.bcscanner.domain.usecase.ConnectToFlipper
import com.polarikus.bcscanner.domain.usecase.DisconnectFromFlipper
import com.polarikus.bcscanner.scanner.BarcodeScanner
import com.polarikus.bcscanner.ui.main.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
 * `BleFlipperTransport` after T017 (US1), `RpcFlipperClient` after T032 (US2),
 * `MlKitBarcodeScanner` after T033 (US2).
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Long-lived scope for transport-level event collection. */
    private val appScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Persists the last used FlipperZero address. */
    val deviceStore: DeviceStore by lazy { DataStoreDeviceStore(appContext) }

    /** Connection state machine shared across screens. */
    val connectionStateMachine: ConnectionStateMachine = ConnectionStateMachine()

    /** BLE transport; registered by US1 (T017). */
    val flipperTransport: FlipperTransport by lazy {
        BleFlipperTransport(
            connectionFactory = AndroidGattConnectionFactory(appContext),
            scope = appScope,
            deviceScanner = flipperDeviceScanner,
        )
    }

    /** Discovers a nearby FlipperZero (FR-001). */
    val flipperDeviceScanner: FlipperDeviceScanner by lazy {
        AndroidFlipperDeviceScanner(appContext)
    }

    /** Connects to a FlipperZero and remembers it (FR-011). */
    val connectToFlipper: ConnectToFlipper by lazy {
        ConnectToFlipper(flipperTransport, deviceStore, flipperDeviceScanner)
    }

    /** Disconnects from the FlipperZero. */
    val disconnectFromFlipper: DisconnectFromFlipper by lazy {
        DisconnectFromFlipper(flipperTransport)
    }

    /** Factory for the main screen view model. */
    val mainViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            MainViewModel(
                transport = flipperTransport,
                connectToFlipper = connectToFlipper,
                disconnectFromFlipper = disconnectFromFlipper,
            )
        }
    }

    /** RPC client; registered by US2 (T032). */
    var rpcClient: FlipperRpcClient? = null

    /** Barcode scanner; registered by US2 (T033). */
    var barcodeScanner: BarcodeScanner? = null
}
