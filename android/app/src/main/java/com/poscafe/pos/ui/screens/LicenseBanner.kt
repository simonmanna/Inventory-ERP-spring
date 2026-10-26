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
