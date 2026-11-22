package com.poscafe.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.poscafe.pos.data.DeviceConfig
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * One line above the till when the licence needs attention.
 *
 * Shows nothing while the licence is healthy. On a terminal the difference that matters is between
 * "renew this soon" and "this till cannot take money" — so there are exactly two looks, and the
 * second is unmissable. There is no action here on purpose: a cashier cannot fix a licence, and
 * offering them a button would only send them down a dead end mid-queue.
 */
@Composable
fun LicenseBanner(config: DeviceConfig, modifier: Modifier = Modifier) {
    val licence by config.license.collectAsStateWithLifecycle()
    if (!licence.known || !licence.warrants) return

    val blocked = !licence.writable
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (blocked) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (blocked) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (blocked) Icons.Outlined.ErrorOutline else Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.Unspecified,
            )
            Text(
                text = message(licence),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun message(licence: DeviceConfig.LicenseState): String {
    if (!licence.writable) {
        return licence.message
            ?: "This system is read-only: the licence has expired. Sales cannot be taken."
    }
    if (licence.mode == "GRACE") {
        return "The licence has expired. The till keeps working for now — tell the manager."
    }
    val days = licence.daysRemaining ?: return "The licence needs attention."
    return when {
        days <= 0L -> "The licence expires today."
        days == 1L -> "The licence expires tomorrow."
        else -> "The licence expires in $days days."
    }
}

/**
 * ADR-S083 C4 / S8 — sync health the cashier must know about: the offline window closing or closed (new sales stop
 * until the till syncs), the clock wound back, an expired device token (re-enroll), or an app too old for the
 * server (update). Re-evaluated every minute; shows nothing on a healthy or standalone till.
 */
@Composable
fun SyncHealthBanner(config: DeviceConfig, modifier: Modifier = Modifier) {
    val problem by config.syncProblem.collectAsStateWithLifecycle()
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(60_000); now = System.currentTimeMillis() }
    }
    if (!config.isEnrolled) return
    val window = config.offlinePolicy.offlineWindowHours
    val blocked = com.poscafe.pos.data.OfflineWindow.block(config.lastPullAt, window, now)
    val left = com.poscafe.pos.data.OfflineWindow.hoursLeft(config.lastPullAt, window, now)
    val text = when {
        blocked != null -> blocked
        problem == "token_expired" ->
            "This device's token expired. Sales are still sent and held for a supervisor — re-enroll the device (More → Sync)."
        problem == "upgrade_required" -> "The server needs a newer version of this app to sync. Update the app."
        left != null && left < 12 -> "Not synced for a while: sales stop in about $left h unless the till syncs."
        else -> return
    }
    val error = blocked != null || problem != null
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.Unspecified,
            )
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
