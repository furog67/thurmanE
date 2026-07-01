package com.elderlycaller

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
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
    private lateinit var callManager: CallManager

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        callManager = CallManager(
            getSystemService(TELECOM_SERVICE) as TelecomManager,
            getSystemService(AUDIO_SERVICE) as AudioManager,
            lifecycleScope
        )

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        onBackPressedDispatcher.addCallback(this) {
            // Swallow back while a call is active so the user must tap HANG UP.
            if (!callManager.callActive.value) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }

        setContent {
            ElderlyCallerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1A237E)
                ) {
                    App(viewModel, callManager)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        KioskMode.pin(this)
    }
}

@Composable
private fun App(viewModel: MainViewModel, callManager: CallManager) {
    val tiles by viewModel.tiles.collectAsState()
    val activity = LocalContext.current as? android.app.Activity

    var showPasswordDialog by remember { mutableStateOf(false) }
    var inAdmin by remember { mutableStateOf(false) }
    var preCallTile by remember { mutableStateOf<Tile?>(null) }
    var callingTile by remember { mutableStateOf<Tile?>(null) }

    // Keep screen on during a call; release the flag when done.
    LaunchedEffect(callManager.callActive.value) {
        if (callManager.callActive.value) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            callingTile = null
        }
    }

    when {
        callingTile != null && callManager.callActive.value -> CallingScreen(
            tile = callingTile!!,
            status = callManager.callStatus.value,
            speakerOn = callManager.speakerOn.value,
            onHangUp = { callManager.endCall() }
        )
        preCallTile != null -> PreCallScreen(
            tile = preCallTile!!,
            onBack = { preCallTile = null },
            onCall = { tile ->
                callingTile = tile
                callManager.startCall(tile.phoneNumber)
                preCallTile = null
            }
        )
        inAdmin -> AdminScreen(
            tiles = tiles,
            onAddTile = viewModel::addTile,
            onUpdateTile = viewModel::updateTile,
            onDeleteTile = viewModel::deleteTile,
            onChangePassword = viewModel::changePassword,
            onExit = { inAdmin = false }
        )
        else -> {
            MainScreen(
                tiles = tiles,
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
