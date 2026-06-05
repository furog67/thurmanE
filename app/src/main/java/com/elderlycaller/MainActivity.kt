package com.elderlycaller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.elderlycaller.ui.AdminPasswordDialog
import com.elderlycaller.ui.AdminScreen
import com.elderlycaller.ui.MainScreen
import com.elderlycaller.ui.theme.ElderlyCallerTheme
import com.elderlycaller.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
}

@Composable
private fun App(viewModel: MainViewModel) {
    val tiles by viewModel.tiles.collectAsState()

    var showPasswordDialog by remember { mutableStateOf(false) }
    var inAdmin by remember { mutableStateOf(false) }

    if (inAdmin) {
        AdminScreen(
            tiles = tiles,
            onAddTile = viewModel::addTile,
            onUpdateTile = viewModel::updateTile,
            onDeleteTile = viewModel::deleteTile,
            onChangePassword = viewModel::changePassword,
            onExit = { inAdmin = false }
        )
    } else {
        MainScreen(
            tiles = tiles,
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
