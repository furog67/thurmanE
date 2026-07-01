package com.elderlycaller

import android.Manifest
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

        // Process-lifetime call controller. Survives this activity being recreated
        // by Telecom mid-call, so the call screen restores instead of vanishing.
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
            // Swallow back while a call is active so the user must tap HANG UP.
            if (!CallManager.callActive.value) {
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
                    App(viewModel)
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
private fun App(viewModel: MainViewModel) {
    val tiles by viewModel.tiles.collectAsState()
    val activity = LocalContext.current as? android.app.Activity

    var showPasswordDialog by remember { mutableStateOf(false) }
    var inAdmin by remember { mutableStateOf(false) }
    var preCallTile by remember { mutableStateOf<Tile?>(null) }

    // Call state is the source of truth in CallManager (a singleton), NOT local
    // remember state — so if Telecom recreates this activity mid-call, the call
    // screen comes right back instead of falling through to the tile grid.
    val callActive = CallManager.callActive.value
    val activeTile = CallManager.activeTile

    LaunchedEffect(callActive) {
        if (callActive) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            callActive && activeTile != null -> CallingScreen(
                tile = activeTile,
                status = CallManager.callStatus.value,
                speakerOn = CallManager.speakerOn.value,
                onHangUp = { CallManager.endCall() }
            )
            preCallTile != null -> PreCallScreen(
                tile = preCallTile!!,
                onBack = { preCallTile = null },
                onCall = { tile ->
                    CallManager.startCall(tile)
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

        // TEMP debug banner — persists across the "flash" so the last call
        // attempt's outcome is readable even after returning to the grid.
        val debug = CallManager.debug.value
        if (debug.isNotEmpty()) {
            Text(
                text = debug,
                color = Color(0xFFFFEB3B),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(6.dp)
            )
        }
    }
}
