package com.polarikus.bcscanner.ui.main

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polarikus.bcscanner.R
import com.polarikus.bcscanner.domain.model.ConnectionState
import com.polarikus.bcscanner.domain.model.DeliveryStatus
import com.polarikus.bcscanner.domain.model.LastSentCode
import com.polarikus.bcscanner.ui.permissions.PermissionManager

/**
 * Main screen: shows the FlipperZero connection status and offers the "Scan" action only
 * while connected (FR-002, FR-003). Requests the Bluetooth runtime permissions (FR-004).
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val scanEnabled by viewModel.scanEnabled.collectAsStateWithLifecycle()
    val lastSentCode by viewModel.lastSentCode.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val requiredPermissions = remember { PermissionManager.bluetoothPermissions() }
    var permissionsGranted by remember {
        mutableStateOf(hasAllPermissions(context, requiredPermissions))
    }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionsGranted = result.values.all { it }
        permissionDenied = !permissionsGranted
        if (permissionsGranted) {
            viewModel.retryConnection()
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text = connectionStatusText(connectionState),
            style = MaterialTheme.typography.bodyLarge,
        )

        if (permissionDenied) {
            Text(
                text = stringResource(R.string.permission_bluetooth_rationale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (scanEnabled) {
            Button(onClick = onScanClick) {
                Text(text = stringResource(R.string.action_scan))
            }
        }

        if (connectionState is ConnectionState.Error) {
            Button(onClick = viewModel::retryConnection) {
                Text(text = stringResource(R.string.action_retry))
            }
        }

        LastSentSection(lastSentCode)
    }
}

@Composable
private fun LastSentSection(lastSentCode: LastSentCode?) {
    Text(
        text = stringResource(R.string.last_sent_title),
        style = MaterialTheme.typography.titleMedium,
    )
    if (lastSentCode == null) {
        Text(
            text = stringResource(R.string.last_sent_empty),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Text(
            text = String(lastSentCode.code.value, Charsets.UTF_8),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = deliveryStatusText(lastSentCode.status),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun deliveryStatusText(status: DeliveryStatus): String = when (status) {
    DeliveryStatus.Pending -> stringResource(R.string.delivery_status_pending)
    DeliveryStatus.Delivered -> stringResource(R.string.delivery_status_delivered)
    is DeliveryStatus.Error -> stringResource(R.string.delivery_status_error)
}

@Composable
private fun connectionStatusText(state: ConnectionState): String = when (state) {
    ConnectionState.Connected -> stringResource(R.string.connection_status_connected)
    ConnectionState.Connecting -> stringResource(R.string.connection_status_connecting)
    ConnectionState.Disconnected -> stringResource(R.string.connection_status_disconnected)
    is ConnectionState.Error -> stringResource(R.string.connection_status_error)
}

private fun hasAllPermissions(context: Context, permissions: List<String>): Boolean =
    permissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
