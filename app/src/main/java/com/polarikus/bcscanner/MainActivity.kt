package com.polarikus.bcscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.polarikus.bcscanner.ui.main.MainScreen
import com.polarikus.bcscanner.ui.main.MainViewModel
import com.polarikus.bcscanner.ui.scanner.ScannerScreen
import com.polarikus.bcscanner.ui.scanner.ScannerViewModel

/** Navigation routes for the app. */
object Routes {
    const val MAIN = "main"
    const val SCANNER = "scanner"
}

/**
 * Single-activity host. The navigation shell wires the two screens; their content is
 * implemented by US1 (MainScreen, T020) and US2 (ScannerScreen, T036).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BcScannerNavHost()
                }
            }
        }
    }
}

/** Compose navigation shell with the main and scanner destinations. */
@Composable
fun BcScannerNavHost(navController: NavHostController = rememberNavController()) {
    val container = (LocalContext.current.applicationContext as BcScannerApplication).container
    NavHost(navController = navController, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            val viewModel: MainViewModel = viewModel(factory = container.mainViewModelFactory)
            MainScreen(
                viewModel = viewModel,
                onScanClick = { navController.navigate(Routes.SCANNER) },
            )
        }
        composable(Routes.SCANNER) {
            val viewModel: ScannerViewModel =
                viewModel(factory = container.scannerViewModelFactory)
            ScannerScreen(
                viewModel = viewModel,
                scanner = container.barcodeScanner,
                onReturnToMain = { navController.popBackStack(Routes.MAIN, inclusive = false) },
            )
        }
    }
}
