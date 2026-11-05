package com.stremio.mobile.presentation.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stremio.mobile.core.theme.AccentGreen
import com.stremio.mobile.core.theme.AccentPurple
import com.stremio.mobile.core.theme.MutedText
import com.stremio.mobile.player.subsync.SubtitleSyncStatus
import java.util.Locale
import kotlin.math.abs

/** Human-readable summary of the automatic sync state. */
fun describeSyncStatus(status: SubtitleSyncStatus): Pair<String, String?> = when (status) {
    SubtitleSyncStatus.Off -> "Off" to "Manual delay only"
    is SubtitleSyncStatus.Unavailable -> "Unavailable" to status.reason
    is SubtitleSyncStatus.Listening ->
        "Listening… ${(status.progress * 100).toInt()}%" to "Matching speech with subtitle timing"
    is SubtitleSyncStatus.Synced -> {
        val sign = if (status.shiftMs >= 0) "+" else "−"
        val shift = String.format(Locale.US, "%s%.2f s", sign, abs(status.shiftMs) / 1000.0)
        val drift = if (status.hasDrift) {
            String.format(Locale.US, " · drift %+.2f%%", (status.scale - 1.0) * 100)
        } else {
            ""
        }
        "Synced $shift$drift" to (if (status.corrections > 1) "${status.corrections} corrections applied" else "Correction applied")
    }
    is SubtitleSyncStatus.Uncertain -> "Not confident" to status.reason
}

@Composable
fun SubtitleSyncPanel(
    status: SubtitleSyncStatus,
    autoSync: Boolean,
    driftCorrection: Boolean,
    onAutoSyncChange: (Boolean) -> Unit,
    onDriftCorrectionChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, detail) = describeSyncStatus(status)
    val dotColor = when (status) {
        is SubtitleSyncStatus.Synced -> AccentGreen
        is SubtitleSyncStatus.Listening -> AccentPurple
        is SubtitleSyncStatus.Uncertain -> Color(0xFFE0A030)
        else -> Color(0xFF6B6880)
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SyncSwitchRow("AUTO SYNC", autoSync, onAutoSyncChange)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x14FFFFFF))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                detail?.let { Text(it, color = MutedText, fontSize = 10.sp, lineHeight = 13.sp) }
            }
        }
        if (autoSync) {
            SyncSwitchRow("DRIFT CORRECTION", driftCorrection, onDriftCorrectionChange)
            if (status is SubtitleSyncStatus.Synced || status is SubtitleSyncStatus.Uncertain) {
                Text(
                    text = "Reset auto sync",
                    color = AccentPurple,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onReset)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SyncSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Switch) { onChange(!checked) }
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .padding(horizontal = 2.dp),
    ) {
        Text(label, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = AccentPurple),
        )
    }
}
