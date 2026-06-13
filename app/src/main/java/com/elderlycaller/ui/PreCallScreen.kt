package com.elderlycaller.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.elderlycaller.data.Tile
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PreCallScreen(tile: Tile, onBack: () -> Unit) {
    val context = LocalContext.current
    val callPermission = rememberPermissionState(Manifest.permission.CALL_PHONE)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A237E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            // Large circular contact photo — tap this to call
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .clip(CircleShape)
                    .border(6.dp, Color(0xFF80CBC4), CircleShape)
                    .clickable {
                        if (callPermission.status.isGranted) {
                            makeCall(context, tile.phoneNumber)
                            onBack()
                        } else {
                            callPermission.launchPermissionRequest()
                        }
                    }
            ) {
                AsyncImage(
                    model = tile.imagePath,
                    contentDescription = "Call ${tile.label}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = tile.label,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "TAP PHOTO TO CALL",
                fontSize = 18.sp,
                color = Color(0xFF80CBC4),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }

        // Back arrow — top-left so user can return without calling
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

private fun makeCall(context: Context, phoneNumber: String) {
    val intent = Intent(Intent.ACTION_CALL).apply {
        data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)

    // Enable speakerphone once the call connects (if no wired headset)
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    if (!audio.isWiredHeadsetOn) {
        Handler(Looper.getMainLooper()).postDelayed({
            audio.mode = AudioManager.MODE_IN_CALL
            audio.isSpeakerphoneOn = true
        }, 3000)
    }
}
