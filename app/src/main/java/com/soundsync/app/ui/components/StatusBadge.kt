package com.soundsync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundsync.app.ui.theme.DarkSurfaceVariant
import com.soundsync.app.ui.theme.GreenSyncOk
import com.soundsync.app.ui.theme.OrangeWarning
import com.soundsync.app.ui.theme.RedAlert

enum class BadgeState {
    EXCELLENT,
    GOOD,
    WARNING,
    DISCONNECTED
}

@Composable
fun StatusBadge(
    text: String,
    state: BadgeState,
    modifier: Modifier = Modifier
) {
    val indicatorColor = when (state) {
        BadgeState.EXCELLENT -> GreenSyncOk
        BadgeState.GOOD -> GreenSyncOk.copy(alpha = 0.8f)
        BadgeState.WARNING -> OrangeWarning
        BadgeState.DISCONNECTED -> RedAlert
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
