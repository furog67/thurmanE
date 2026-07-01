package com.elderlycaller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.elderlycaller.data.Tile

@Composable
fun CallingScreen(
    tile: Tile,
    status: String,
    speakerOn: Boolean,
    onHangUp: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A237E))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AsyncImage(
                model = tile.imagePath,
                contentDescription = tile.label,
                modifier = Modifier
                    .size(260.dp)
                    .clip(CircleShape)
                    .border(5.dp, Color(0xFF80CBC4), CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = tile.label,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = status,
                fontSize = 22.sp,
                color = Color(0xFF80CBC4),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (speakerOn) "🔊 Speakerphone ON" else "🔇 Speakerphone OFF",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = if (speakerOn) Color(0xFFA5D6A7) else Color(0xFFEF9A9A),
                textAlign = TextAlign.Center
            )
        }

        Button(
            onClick = onHangUp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 48.dp)
                .height(100.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text(
                text = "HANG UP",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
