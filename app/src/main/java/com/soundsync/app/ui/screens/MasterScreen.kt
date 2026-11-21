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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.soundsync.app.audio.capture.AudioCaptureManager
import com.soundsync.app.data.model.StreamQualityPreset
import com.soundsync.app.network.NetworkUtils
import com.soundsync.app.ui.components.AudioVisualizer
import com.soundsync.app.ui.components.BadgeState
import com.soundsync.app.ui.components.DeviceCard
import com.soundsync.app.ui.components.PairingDialog
import com.soundsync.app.ui.components.StatusBadge
import com.soundsync.app.ui.theme.CyanAccent
import com.soundsync.app.ui.theme.DarkBackground
import com.soundsync.app.ui.theme.DarkCardBorder
import com.soundsync.app.ui.theme.DarkSurface
import com.soundsync.app.ui.theme.DarkSurfaceVariant
import com.soundsync.app.ui.theme.OrangeWarning
import com.soundsync.app.ui.theme.PurpleAccent
import com.soundsync.app.ui.theme.RedAlert
import com.soundsync.app.ui.theme.TextMuted
import com.soundsync.app.ui.theme.TextPrimary
import com.soundsync.app.ui.theme.TextSecondary
import com.soundsync.app.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterScreen(
    viewModel: MainViewModel,
    onRequestMediaProjection: () -> Unit,
    onBackClick: () -> Unit
) {
    val deviceName by viewModel.deviceName.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val captureState by viewModel.captureState.collectAsState()
    val captureMessage by viewModel.captureMessage.collectAsState()
    val amplitude by viewModel.amplitude.collectAsState()
    val connectedSlaves by viewModel.connectedSlaves.collectAsState()
    val selectedPreset by viewModel.selectedPreset.collectAsState()
    val masterPin by viewModel.masterPin.collectAsState()
    val isTestToneActive by viewModel.isTestToneActive.collectAsState()

    var showPairingDialogState by remember { mutableStateOf(false) }
    var presetMenuExpanded by remember { mutableStateOf(false) }
    var localIp by remember { mutableStateOf("Wi-Fi") }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val ip = NetworkUtils.getLocalIpAddress() ?: "Wi-Fi"
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                localIp = ip
            }
        }
    }

    if (showPairingDialogState) {
        PairingDialog(
            pin = masterPin,
            masterIp = localIp,
            deviceName = deviceName,
            onDismiss = { showPairingDialogState = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = deviceName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Master Host • $localIp",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showPairingDialogState = true }) {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = "Show Pairing PIN",
                            tint = CyanAccent
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))

                // Waveform Visualizer & Status
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
                                text = "Audio Output",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )

                            val badgeState = when (captureState) {
                                AudioCaptureManager.CaptureState.CAPTURING_SYSTEM,
                                AudioCaptureManager.CaptureState.GENERATING_TEST_PULSE -> BadgeState.EXCELLENT
                                AudioCaptureManager.CaptureState.INITIALIZING -> BadgeState.GOOD
                                AudioCaptureManager.CaptureState.RESTRICTED -> BadgeState.WARNING
                                AudioCaptureManager.CaptureState.ERROR -> BadgeState.DISCONNECTED
                                else -> BadgeState.GOOD
                            }
                            StatusBadge(text = captureMessage, state = badgeState)
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        AudioVisualizer(amplitude = amplitude, isStreaming = isStreaming)
                    }
                }
            }

            // QR Code Quick Access Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { showPairingDialogState = true },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Pair Receivers (QR Code)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Tap to show connection QR Code, IP ($localIp), and PIN ($masterPin)",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = "Show QR",
                            tint = CyanAccent,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // Controls Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        // Quality Preset Dropdown
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Latency Profile",
                                fontSize = 14.sp,
                                color = TextSecondary
                            )

                            Box {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkSurfaceVariant)
                                        .clickable { presetMenuExpanded = true }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "${selectedPreset.title} (${selectedPreset.bufferTargetMs}ms)",
                                        color = CyanAccent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                DropdownMenu(
                                    expanded = presetMenuExpanded,
                                    onDismissRequest = { presetMenuExpanded = false },
                                    modifier = Modifier.background(DarkSurfaceVariant)
                                ) {
                                    StreamQualityPreset.values().forEach { preset ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(preset.title, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                                    Text(preset.description, color = TextSecondary, fontSize = 11.sp)
                                                }
                                            },
                                            onClick = {
                                                viewModel.setQualityPreset(preset)
                                                presetMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Big Play / Pause Sync Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (!isStreaming) {
                                Button(
                                    onClick = onRequestMediaProjection,
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f).height(50.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Start Stream",
                                        tint = DarkBackground
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Start Sync Stream",
                                        color = DarkBackground,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.pauseMasterStreaming() },
                                    colors = ButtonDefaults.buttonColors(containerColor = OrangeWarning),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f).height(50.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Pause,
                                        contentDescription = "Pause",
                                        tint = DarkBackground
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Pause Sync",
                                        color = DarkBackground,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }

                                OutlinedButton(
                                    onClick = { viewModel.stopEverything(); onBackClick() },
                                    shape = RoundedCornerShape(14.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, RedAlert),
                                    modifier = Modifier.height(50.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Stop",
                                        tint = RedAlert
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Test Pulse Audio Toggle
                        OutlinedButton(
                            onClick = { viewModel.toggleTestTone() },
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isTestToneActive) CyanAccent else DarkCardBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Test Pulse",
                                tint = if (isTestToneActive) CyanAccent else TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isTestToneActive) "Emit 1kHz Test Pulses (ACTIVE)" else "Emit 1kHz Test Pulses (Measure Sync)",
                                color = if (isTestToneActive) CyanAccent else TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // Connected Receivers Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Connected Receivers (${connectedSlaves.size})",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    if (connectedSlaves.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Mute All",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.clickable {
                                    val anyUnmuted = connectedSlaves.any { !it.isMuted }
                                    viewModel.muteAllSlaves(anyUnmuted)
                                }
                            )
                        }
                    }
                }
            }

            // Receivers List
            if (connectedSlaves.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No receivers connected yet",
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Open SoundSync on another phone and select 'Join Speaker Group'. PIN: $masterPin",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(connectedSlaves, key = { it.id }) { slave ->
                    DeviceCard(
                        device = slave,
                        onVolumeChange = { vol -> viewModel.setSlaveVolume(slave.id, vol) },
                        onMuteToggle = { mute -> viewModel.setSlaveMute(slave.id, mute) },
                        onDisconnect = { viewModel.disconnectSlave(slave.id) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
