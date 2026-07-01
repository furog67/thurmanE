package com.elderlycaller.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.elderlycaller.data.Tile
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.io.File

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AdminScreen(
    tiles: List<Tile>,
    onAddTile: (imagePath: String, label: String, phoneNumber: String) -> Unit,
    onUpdateTile: (Tile) -> Unit,
    onDeleteTile: (Tile) -> Unit,
    onChangePassword: (String) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var editingTile by remember { mutableStateOf<Tile?>(null) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<Tile?>(null) }

    if (showAddDialog) {
        TileEditDialog(
            tile = null,
            onSave = { imagePath, label, phone ->
                onAddTile(imagePath, label, phone)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    editingTile?.let { tile ->
        TileEditDialog(
            tile = tile,
            onSave = { imagePath, label, phone ->
                onUpdateTile(tile.copy(imagePath = imagePath, label = label, phoneNumber = phone))
                editingTile = null
            },
            onDismiss = { editingTile = null }
        )
    }

    showDeleteConfirm?.let { tile ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete \"${tile.label}\"?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteTile(tile)
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
            }
        )
    }

    if (showChangePassword) {
        ChangePasswordDialog(
            onSave = { newPass ->
                onChangePassword(newPass)
                showChangePassword = false
            },
            onDismiss = { showChangePassword = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
    ) {
        // Top bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1565C0))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            IconButton(
                onClick = onExit,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = "Admin - Manage Contacts",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Add tile lives at the top so the phone's on-screen nav buttons
                // at the bottom can't cover it.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showAddDialog = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Contact", tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text("Add", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { showChangePassword = true }) {
                    Icon(Icons.Default.Lock, contentDescription = "Change Password", tint = Color.White)
                }
            }
        }

        Text(
            text = "Build ${com.elderlycaller.BuildConfig.VERSION_NAME} (${com.elderlycaller.BuildConfig.VERSION_CODE}) — ${com.elderlycaller.BuildConfig.BUILD_TIME}",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFE0E0E0))
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // TEMP diagnostic log — persists across app close / process death so we can
        // read what happened during a call attempt. Remove once the call flow works.
        DebugLogCard()

        // Default dialer management
        val telecomManager = context.getSystemService(TelecomManager::class.java)
        var dialerRefreshTrigger by remember { mutableIntStateOf(0) }
        val isDefaultDialer = remember(dialerRefreshTrigger) {
            telecomManager?.defaultDialerPackage == context.packageName
        }

        val roleRequestLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            dialerRefreshTrigger++
        }

        if (!isDefaultDialer) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "One-time setup: set Easy Caller as the default phone app so only this screen appears during calls.",
                        fontSize = 14.sp,
                        color = Color(0xFF5D4037),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            // Screen pinning blocks launching other apps' screens —
                            // unpin first so the system dialer picker can open.
                            (context as? android.app.Activity)?.let { com.elderlycaller.KioskMode.unpin(it) }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                // ACTION_CHANGE_DEFAULT_DIALER was removed in Android 10+;
                                // RoleManager is the only working mechanism now.
                                val roleManager = context.getSystemService(RoleManager::class.java)
                                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                                    roleRequestLauncher.launch(
                                        roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                                    )
                                } else {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                                }
                            } else {
                                val dialerIntent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                                    .putExtra(
                                        TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                                        context.packageName
                                    )
                                if (dialerIntent.resolveActivity(context.packageManager) != null) {
                                    roleRequestLauncher.launch(dialerIntent)
                                } else {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Set Up", fontSize = 14.sp) }
                }
            }
        } else {
            // Already the default dialer — show a restore button for testing
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Easy Caller is the default phone app.",
                        fontSize = 14.sp,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            // Opens the OS "Default apps" screen so the tester can pick the
                            // phone's original dialer back; refreshes this card on return.
                            (context as? android.app.Activity)?.let { com.elderlycaller.KioskMode.unpin(it) }
                            roleRequestLauncher.launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Restore", fontSize = 14.sp) }
                }
            }
        }

        // Kiosk lockdown — let the admin temporarily reach Home/Recents/Settings
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "The app is locked to the screen so the user can't reach Home or Settings.",
                        fontSize = 14.sp,
                        color = Color(0xFF0D47A1),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            (context as? android.app.Activity)?.let { com.elderlycaller.KioskMode.unpin(it) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1)),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Unlock Navigation", fontSize = 14.sp) }
                }

                // TEMP debug: disable screen pinning entirely so we can test whether
                // kiosk lock task is what blocks Easy Caller's in-call UI.
                var kioskDisabled by remember {
                    mutableStateOf(com.elderlycaller.KioskMode.isDisabled(context))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Disable screen pinning (debug)",
                        fontSize = 14.sp,
                        color = Color(0xFF0D47A1),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = kioskDisabled,
                        onCheckedChange = {
                            kioskDisabled = it
                            com.elderlycaller.KioskMode.setDisabled(context, it)
                            if (it) {
                                (context as? android.app.Activity)?.let { a ->
                                    com.elderlycaller.KioskMode.unpin(a)
                                }
                            }
                        }
                    )
                }
            }
        }

        // Tile list
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tiles, key = { it.id }) { tile ->
                AdminTileRow(
                    tile = tile,
                    onEdit = { editingTile = tile },
                    onDelete = { showDeleteConfirm = tile }
                )
            }
        }
    }
}

@Composable
private fun DebugLogCard() {
    val context = LocalContext.current
    var logText by remember { mutableStateOf(com.elderlycaller.DebugLog.read()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF212121)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Diagnostic Log",
                    color = Color(0xFFFFEB3B),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { logText = com.elderlycaller.DebugLog.read() }) {
                    Text("Refresh", color = Color(0xFF90CAF9), fontSize = 13.sp)
                }
                TextButton(onClick = {
                    com.elderlycaller.DebugLog.clear()
                    logText = ""
                }) {
                    Text("Clear", color = Color(0xFFEF9A9A), fontSize = 13.sp)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (logText.isBlank()) "(empty — make a call, then reopen Admin and tap Refresh)" else logText.trim(),
                    color = Color(0xFFB2FF59),
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val text = com.elderlycaller.DebugLog.read().trim()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("Easy Caller Log", text)
                        )
                        Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Copy", fontSize = 14.sp) }
                Button(
                    onClick = {
                        // Kiosk pinning blocks launching the share sheet — unpin first.
                        (context as? android.app.Activity)?.let { com.elderlycaller.KioskMode.unpin(it) }
                        val text = com.elderlycaller.DebugLog.read().trim()
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Easy Caller Diagnostic Log")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, "Share log"))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Share", fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun AdminTileRow(
    tile: Tile,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = tile.imagePath,
                contentDescription = tile.label,
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(tile.label, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tile.phoneNumber, fontSize = 15.sp, color = Color.Gray)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFF1565C0))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFC62828))
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun TileEditDialog(
    tile: Tile?,
    onSave: (imagePath: String, label: String, phoneNumber: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var imagePath by remember { mutableStateOf(tile?.imagePath ?: "") }
    var label by remember { mutableStateOf(tile?.label ?: "") }
    var phone by remember { mutableStateOf(tile?.phoneNumber ?: "") }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var showImageSourceSheet by remember { mutableStateOf(false) }

    val permissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES)
    )

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val saved = saveImageLocally(context, it)
            if (saved != null) imagePath = saved
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraUri?.let { imagePath = it.toString() }
        }
    }

    val contactLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri ->
        uri?.let { contactUri ->
            val number = resolveContactPhone(context, contactUri)
            if (number != null) phone = number
        }
    }

    if (showImageSourceSheet) {
        AlertDialog(
            onDismissRequest = { showImageSourceSheet = false },
            title = { Text("Choose Photo Source") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            showImageSourceSheet = false
                            if (permissions.allPermissionsGranted) {
                                val uri = createCameraUri(context)
                                cameraUri = uri
                                cameraLauncher.launch(uri)
                            } else {
                                permissions.launchMultiplePermissionRequest()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Take Photo")
                    }
                    Button(
                        onClick = {
                            showImageSourceSheet = false
                            galleryLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Photo, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose from Gallery")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImageSourceSheet = false }) { Text("Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tile == null) "Add Contact Tile" else "Edit Contact Tile", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Photo picker
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFE3F2FD))
                        .border(2.dp, Color(0xFF1565C0), RoundedCornerShape(12.dp))
                        .clickable { showImageSourceSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (imagePath.isNotEmpty()) {
                        AsyncImage(
                            model = imagePath,
                            contentDescription = "Tile image",
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .background(Color(0xFF1565C0), RoundedCornerShape(8.dp))
                                .padding(4.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = Color(0xFF1565C0), modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Tap to add photo", color = Color(0xFF1565C0), fontWeight = FontWeight.Medium)
                        }
                    }
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name (shown on tile)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone Number") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                    IconButton(
                        onClick = { contactLauncher.launch(null) },
                        modifier = Modifier
                            .background(Color(0xFF1565C0), RoundedCornerShape(8.dp))
                            .size(48.dp)
                    ) {
                        Icon(Icons.Default.Contacts, contentDescription = "Pick from contacts", tint = Color.White)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (imagePath.isNotEmpty() && label.isNotEmpty() && phone.isNotEmpty()) {
                        onSave(imagePath, label, phone)
                    }
                },
                enabled = imagePath.isNotEmpty() && label.isNotEmpty() && phone.isNotEmpty()
            ) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ChangePasswordDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newPass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = newPass.isNotEmpty() && confirm.isNotEmpty() && newPass != confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Admin Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = newPass,
                    onValueChange = { newPass = it },
                    label = { Text("New Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = mismatch,
                    supportingText = if (mismatch) ({ Text("Passwords do not match") }) else null
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(newPass) },
                enabled = newPass.isNotEmpty() && newPass == confirm && newPass.length >= 4
            ) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun createCameraUri(context: Context): Uri {
    val photoFile = File(
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
        "tile_${System.currentTimeMillis()}.jpg"
    )
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
}

private fun saveImageLocally(context: Context, sourceUri: Uri): String? {
    return try {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val dest = File(dir, "tile_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest.absolutePath
    } catch (e: Exception) {
        null
    }
}

private fun resolveContactPhone(context: Context, contactUri: Uri): String? {
    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
    val contactId = contactUri.lastPathSegment ?: return null
    val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    context.contentResolver.query(
        phoneUri,
        projection,
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
        arrayOf(contactId),
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            return cursor.getString(0)
        }
    }
    return null
}
