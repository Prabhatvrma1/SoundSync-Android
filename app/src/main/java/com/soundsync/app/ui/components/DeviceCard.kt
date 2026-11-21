package com.soundsync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundsync.app.data.model.SlaveDevice
import com.soundsync.app.ui.theme.CyanAccent
import com.soundsync.app.ui.theme.DarkCardBorder
import com.soundsync.app.ui.theme.DarkSurface
import com.soundsync.app.ui.theme.RedAlert
import com.soundsync.app.ui.theme.TextMuted
import com.soundsync.app.ui.theme.TextPrimary
import com.soundsync.app.ui.theme.TextSecondary

@Composable
fun DeviceCard(
    device: SlaveDevice,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val badgeState = when {
        !device.isConnected -> BadgeState.DISCONNECTED
        device.rttMs > 60f || device.packetLossPct > 5f -> BadgeState.WARNING
        device.rttMs > 25f -> BadgeState.GOOD
        else -> BadgeState.EXCELLENT
    }

    val badgeText = if (device.isConnected) {
        "${device.rttMs.toInt()}ms • ${device.bufferHealthMs}ms buf"
    } else {
        "Offline"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = device.ipAddress,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            StatusBadge(text = badgeText, state = badgeState)

            IconButton(onClick = onDisconnect, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Disconnect Receiver",
                    tint = TextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Individual Volume Control Slider & Mute Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onMuteToggle(!device.isMuted) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (device.isMuted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                    contentDescription = "Mute",
                    tint = if (device.isMuted) RedAlert else CyanAccent
                )
            }

            Slider(
                value = if (device.isMuted) 0f else device.volume,
                onValueChange = onVolumeChange,
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = CyanAccent,
                    activeTrackColor = CyanAccent,
                    inactiveTrackColor = DarkCardBorder
                )
            )

            Text(
                text = "${(if (device.isMuted) 0 else (device.volume * 100).toInt())}%",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
