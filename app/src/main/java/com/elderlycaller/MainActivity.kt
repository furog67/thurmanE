package com.elderlycaller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.elderlycaller.data.Tile
import com.elderlycaller.ui.AdminPasswordDialog
import com.elderlycaller.ui.AdminScreen
import com.elderlycaller.ui.CallingScreen
import com.elderlycaller.ui.MainScreen
import com.elderlycaller.ui.PreCallScreen
import com.elderlycaller.ui.theme.ElderlyCallerTheme
import com.elderlycaller.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // CallManager is a process-scoped singleton — initialise once with the
        // application context so it survives any activity recreation Telecom
        // triggers when it brings the default dialer's UI to the front.
        CallManager.init(this)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (!CallManager.callActive.value) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
            // swallow back during an active call — user must tap HANG UP
        }

        setContent {
            ElderlyCallerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1A237E)
                ) {
                    App(viewModel)
                }
            }
        }
    }

    // Telecom fires an intent at the default dialer's main activity to bring
    // the call UI to the front (even when it was the dialer that placed the
    // call). Because CallManager is a singleton the call state is already
    // preserved — no extra work needed here beyond the default behaviour.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        KioskMode.pin(this)
    }
}

@Composable
private fun App(viewModel: MainViewModel) {
    val tiles by viewModel.tiles.collectAsState()
    val activity = LocalContext.current as? android.app.Activity

    var showPasswordDialog by remember { mutableStateOf(false) }
    var inAdmin            by remember { mutableStateOf(false) }
    var preCallTile        by remember { mutableStateOf<Tile?>(null) }

    // Keep screen on while a call is active; release when done.
    LaunchedEffect(CallManager.callActive.value) {
        if (CallManager.callActive.value) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    when {
        CallManager.callActive.value && CallManager.activeTile != null -> CallingScreen(
            tile      = CallManager.activeTile!!,
            status    = CallManager.callStatus.value,
            speakerOn = CallManager.speakerOn.value,
            onHangUp  = { CallManager.endCall() }
        )
        preCallTile != null -> PreCallScreen(
            tile   = preCallTile!!,
            onBack = { preCallTile = null },
            onCall = { tile ->
                preCallTile = null
                CallManager.startCall(tile)
            }
        )
        inAdmin -> AdminScreen(
            tiles          = tiles,
            onAddTile      = viewModel::addTile,
            onUpdateTile   = viewModel::updateTile,
            onDeleteTile   = viewModel::deleteTile,
            onChangePassword = viewModel::changePassword,
            onExit         = { inAdmin = false }
        )
        else -> {
            MainScreen(
                tiles       = tiles,
                onTileClick = { preCallTile = it },
                onAdminClick = { showPasswordDialog = true }
            )
            if (showPasswordDialog) {
                AdminPasswordDialog(
                    checkPassword = viewModel::checkPassword,
                    onSuccess = {
                        showPasswordDialog = false
                        inAdmin = true
                    },
                    onDismiss = { showPasswordDialog = false }
                )
            }
        }
    }
}
