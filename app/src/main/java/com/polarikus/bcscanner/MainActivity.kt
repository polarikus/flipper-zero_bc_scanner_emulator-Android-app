package com.polarikus.bcscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.polarikus.bcscanner.ui.main.MainScreen
import com.polarikus.bcscanner.ui.main.MainViewModel

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
            // Placeholder until ScannerScreen is implemented in T036.
            PlaceholderScreen(text = stringResource(R.string.scanner_title))
        }
    }
}

@Composable
private fun PlaceholderScreen(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.headlineSmall)
    }
}
