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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundsync.app.ui.theme.CyanAccent
import com.soundsync.app.ui.theme.DarkBackground
import com.soundsync.app.ui.theme.DarkCardBorder
import com.soundsync.app.ui.theme.DarkSurface
import com.soundsync.app.ui.theme.PurpleAccent
import com.soundsync.app.ui.theme.TextMuted
import com.soundsync.app.ui.theme.TextPrimary
import com.soundsync.app.ui.theme.TextSecondary
import com.soundsync.app.ui.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onCreateGroupClick: () -> Unit,
    onJoinGroupClick: () -> Unit
) {
    val deviceName by viewModel.deviceName.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        // App Logo / Title
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(CyanAccent, PurpleAccent))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SpeakerGroup,
                contentDescription = "SoundSync",
                tint = DarkBackground,
                modifier = Modifier.size(38.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "SoundSync",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Text(
            text = "Multi-Phone Synchronized Audio System",
            fontSize = 14.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Device Name Setting Field
        OutlinedTextField(
            value = deviceName,
            onValueChange = { viewModel.setDeviceName(it) },
            label = { Text("Device Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyanAccent,
                unfocusedBorderColor = DarkCardBorder,
                focusedLabelColor = CyanAccent,
                unfocusedLabelColor = TextSecondary,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Option 1: Create Speaker Group (Master)
        RoleSelectionCard(
            title = "Create Speaker Group",
            subtitle = "Act as Master / Leader. Capture whole-phone system audio from any app and stream to connected receivers.",
            icon = Icons.Default.Cast,
            accentColor = CyanAccent,
            onClick = onCreateGroupClick
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Option 2: Join Speaker Group (Slave)
        RoleSelectionCard(
            title = "Join Speaker Group",
            subtitle = "Act as Receiver / Satellite speaker. Synchronize playback with the master clock with zero drift.",
            icon = Icons.Default.Speaker,
            accentColor = PurpleAccent,
            onClick = onJoinGroupClick
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Explanatory note
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "How It Works",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• Uses Android's AudioPlaybackCapture to mirror audio from YouTube, Spotify, games, and web media.\n" +
                            "• Microsecond NTP clock estimation eliminates echo and drift between phones.\n" +
                            "• Works over local Wi-Fi or phone hotspot with background foreground streaming.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun RoleSelectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
