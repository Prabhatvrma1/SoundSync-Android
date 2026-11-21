package com.soundsync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundsync.app.network.ControlClient
import com.soundsync.app.network.DiscoveredMaster
import com.soundsync.app.ui.components.AudioVisualizer
import com.soundsync.app.ui.components.BadgeState
import com.soundsync.app.ui.components.StatusBadge
import com.soundsync.app.ui.theme.CyanAccent
import com.soundsync.app.ui.theme.DarkBackground
import com.soundsync.app.ui.theme.DarkCardBorder
import com.soundsync.app.ui.theme.DarkSurface
import com.soundsync.app.ui.theme.DarkSurfaceVariant
import com.soundsync.app.ui.theme.GreenSyncOk
import com.soundsync.app.ui.theme.OrangeWarning
import com.soundsync.app.ui.theme.PurpleAccent
import com.soundsync.app.ui.theme.RedAlert
import com.soundsync.app.ui.theme.TextMuted
import com.soundsync.app.ui.theme.TextPrimary
import com.soundsync.app.ui.theme.TextSecondary
import com.soundsync.app.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlaveScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit
) {
    val connectionState by viewModel.slaveConnectionState.collectAsState()
    val masterName by viewModel.connectedMasterName.collectAsState()
    val metrics by viewModel.slaveMetrics.collectAsState()
    val discoveredMasters by viewModel.discoveredMasters.collectAsState()
    val syncCalibrationOffset by viewModel.syncCalibrationOffsetMs.collectAsState()

    var pinInput by remember { mutableStateOf("") }
    var selectedMasterForPin by remember { mutableStateOf<DiscoveredMaster?>(null) }
    var localVolume by remember { mutableFloatStateOf(1.0f) }
    var isLocalMuted by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? Activity
    var showDisconnectConfirmDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        if (connectionState == ControlClient.ConnectionState.CONNECTED || connectionState == ControlClient.ConnectionState.CONNECTING) {
            showDisconnectConfirmDialog = true
        } else {
            viewModel.stopEverything()
            onBackClick()
        }
    }
    var manualIpInput by remember { mutableStateOf("") }
    var manualPinInput by remember { mutableStateOf("7294") }
    var showManualConnect by remember { mutableStateOf(false) }
    val gatewayIp = remember { com.soundsync.app.network.NetworkUtils.getGatewayIp(context) }

    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        if (result.contents != null) {
            val payload = com.soundsync.app.util.QrConnectionPayload.fromString(result.contents)
            if (payload != null && payload.ip.isNotEmpty()) {
                viewModel.joinMasterDirect(payload.ip, payload.port, payload.pin, payload.name)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startDiscoveringMasters()
    }

    if (showDisconnectConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirmDialog = false },
            title = {
                Text(text = "SoundSync Receiver Active", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "SoundSync is connected and playing audio. Would you like to keep audio playing in the background or disconnect?",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectConfirmDialog = false
                        activity?.moveTaskToBack(true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Keep in Background", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showDisconnectConfirmDialog = false
                        viewModel.stopEverything()
                        onBackClick()
                    },
                    border = androidx.compose.foundation.BorderStroke(1.dp, RedAlert),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Disconnect & Exit", color = RedAlert)
                }
            },
            containerColor = DarkSurface
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (connectionState == ControlClient.ConnectionState.CONNECTED) {
                                masterName ?: "Master Speaker"
                            } else {
                                "Available Speakers"
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = when (connectionState) {
                                ControlClient.ConnectionState.CONNECTED -> "Synchronized Receiver Mode"
                                ControlClient.ConnectionState.CONNECTING -> "Connecting..."
                                else -> "Scanning local Wi-Fi..."
                            },
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (connectionState == ControlClient.ConnectionState.CONNECTED || connectionState == ControlClient.ConnectionState.CONNECTING) {
                            showDisconnectConfirmDialog = true
                        } else {
                            viewModel.stopEverything()
                            onBackClick()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    if (connectionState != ControlClient.ConnectionState.CONNECTED) {
                        IconButton(onClick = { viewModel.startDiscoveringMasters() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = CyanAccent
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
        ) {
            if (connectionState == ControlClient.ConnectionState.CONNECTED) {
                // Connected View
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.height(4.dp))

                        // Sync Health Header Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Synchronization Health",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )

                                    val badgeState = if (metrics.isSynchronized) BadgeState.EXCELLENT else BadgeState.WARNING
                                    StatusBadge(
                                        text = if (metrics.isSynchronized) "Clock Locked" else "Syncing...",
                                        state = badgeState
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Grid of 4 key metrics
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    MetricBox(title = "Network RTT", value = "${metrics.rttMs.toInt()} ms")
                                    MetricBox(title = "Clock Offset", value = String.format("%.1f ms", metrics.clockOffsetMs))
                                    MetricBox(title = "Audio Drift", value = String.format("%.1f ms", metrics.driftMs))
                                    MetricBox(title = "Packet Loss", value = String.format("%.1f%%", metrics.packetLossPct))
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Buffer health bar
                                Text(
                                    text = "Jitter Buffer: ${metrics.bufferHealthMs} ms / ${metrics.targetBufferMs} ms target",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                val progress = (metrics.bufferHealthMs.toFloat() / maxOf(1, metrics.targetBufferMs)).coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = progress,
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = CyanAccent,
                                    trackColor = DarkSurfaceVariant
                                )
                            }
                        }
                    }

                    // Live Audio Visualizer
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "Receiver Speaker Output",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                AudioVisualizer(
                                    amplitude = if (metrics.playbackActive) 0.65f else 0.05f,
                                    isStreaming = metrics.playbackActive
                                )
                            }
                        }
                    }

                    // Local Speaker Volume & Mute Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "Speaker Volume",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            isLocalMuted = !isLocalMuted
                                            viewModel.setLocalReceiverMute(isLocalMuted)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isLocalMuted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                                            contentDescription = "Mute",
                                            tint = if (isLocalMuted) RedAlert else CyanAccent
                                        )
                                    }

                                    Slider(
                                        value = if (isLocalMuted) 0f else localVolume,
                                        onValueChange = {
                                            localVolume = it
                                            viewModel.setLocalReceiverVolume(it)
                                        },
                                        valueRange = 0f..1f,
                                        modifier = Modifier.weight(1f),
                                        colors = SliderDefaults.colors(
                                            thumbColor = CyanAccent,
                                            activeTrackColor = CyanAccent,
                                            inactiveTrackColor = DarkCardBorder
                                        )
                                    )

                                    Text(
                                        text = "${(if (isLocalMuted) 0 else (localVolume * 100).toInt())}%",
                                        color = TextSecondary,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Audio Sync Fine-Tuning Calibration Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Audio Sync Fine-Tuning",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "${if (syncCalibrationOffset > 0) "+" else ""}${syncCalibrationOffset.toInt()} ms",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CyanAccent
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Micro-adjust if phone speaker DAC latency differs from Leader",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Slider(
                                    value = syncCalibrationOffset,
                                    onValueChange = { viewModel.setSyncCalibrationOffset(it) },
                                    valueRange = -100f..100f,
                                    steps = 39,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = CyanAccent,
                                        activeTrackColor = CyanAccent,
                                        inactiveTrackColor = DarkCardBorder
                                    )
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("-100ms", fontSize = 11.sp, color = TextMuted)
                                    TextButton(onClick = { viewModel.setSyncCalibrationOffset(0f) }) {
                                        Text("Reset to 0 ms", fontSize = 11.sp, color = CyanAccent)
                                    }
                                    Text("+100ms", fontSize = 11.sp, color = TextMuted)
                                }
                            }
                        }
                    }

                    // Disconnect Button
                    item {
                        Button(
                            onClick = { viewModel.stopEverything(); onBackClick() },
                            colors = ButtonDefaults.buttonColors(containerColor = RedAlert.copy(alpha = 0.2f)),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, RedAlert),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text(
                                text = "Disconnect from Speaker Group",
                                color = RedAlert,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            } else {
                // Discovery List
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Scan Master QR Code Button
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                val options = com.journeyapps.barcodescanner.ScanOptions().apply {
                                    setPrompt("Point camera at Master Phone's QR Code")
                                    setBeepEnabled(true)
                                    setOrientationLocked(false)
                                }
                                scanLauncher.launch(options)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Scan QR",
                                tint = DarkBackground
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Scan Master QR Code",
                                color = DarkBackground,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 2. Hotspot Direct Connect Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, PurpleAccent)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Hotspot Direct Connect",
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = "Connected to phone hotspot? Connect directly to gateway ($gatewayIp).",
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Button(
                                    onClick = {
                                        viewModel.joinMasterDirect(gatewayIp, 8889, "7294", "Hotspot Master")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Connect", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    // 3. Manual IP & PIN Connect Toggle
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { showManualConnect = !showManualConnect },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (showManualConnect) "Hide Manual IP Connect" else "Enter Master IP Manually",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = CyanAccent
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Wifi,
                                        contentDescription = "Manual IP",
                                        tint = CyanAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                if (showManualConnect) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    OutlinedTextField(
                                        value = manualIpInput,
                                        onValueChange = { manualIpInput = it.trim() },
                                        label = { Text("Master IP Address (e.g. 192.168.43.1)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyanAccent,
                                            unfocusedBorderColor = DarkCardBorder,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = manualPinInput,
                                        onValueChange = { if (it.length <= 4) manualPinInput = it },
                                        label = { Text("Pairing PIN (Default: 7294)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyanAccent,
                                            unfocusedBorderColor = DarkCardBorder,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            if (manualIpInput.isNotEmpty()) {
                                                viewModel.joinMasterDirect(manualIpInput, 8889, manualPinInput, "Master")
                                            }
                                        },
                                        enabled = manualIpInput.isNotEmpty(),
                                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().height(46.dp)
                                    ) {
                                        Text("Connect to IP", color = DarkBackground, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Nearby Master Speakers (${discoveredMasters.size})",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    if (discoveredMasters.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                            ) {
                                Column(
                                    modifier = Modifier.padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(
                                        color = CyanAccent,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Searching for Master Speakers...",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 15.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Ensure both phones are connected to the same Wi-Fi network or mobile hotspot.",
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        items(discoveredMasters, key = { it.ipAddress }) { master ->
                            DiscoveredMasterCard(
                                master = master,
                                onConnectClick = {
                                    selectedMasterForPin = master
                                }
                            )
                        }
                    }

                    // Optional PIN input when a master is selected
                    if (selectedMasterForPin != null) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent)
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    Text(
                                        text = "Join ${selectedMasterForPin?.deviceName}",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Enter the 4-digit pairing PIN shown on the master device:",
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    OutlinedTextField(
                                        value = pinInput,
                                        onValueChange = { if (it.length <= 4) pinInput = it },
                                        placeholder = { Text("PIN (e.g. 7294)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyanAccent,
                                            unfocusedBorderColor = DarkCardBorder,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    )

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { selectedMasterForPin = null; pinInput = "" },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Cancel", color = TextSecondary)
                                        }

                                        Button(
                                            onClick = {
                                                val m = selectedMasterForPin ?: return@Button
                                                viewModel.joinMaster(m, pinInput.trim())
                                                selectedMasterForPin = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Connect", color = DarkBackground, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricBox(title: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, color = TextSecondary, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, color = CyanAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DiscoveredMasterCard(
    master: DiscoveredMaster,
    onConnectClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = master.deviceName,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${master.ipAddress} • ${master.receiverCount} receiver(s)",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = onConnectClick,
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(text = "Connect", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}
