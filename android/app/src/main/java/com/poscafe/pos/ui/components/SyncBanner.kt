package com.poscafe.pos.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.poscafe.pos.sync.SyncSnapshot
import com.poscafe.pos.sync.SyncStatus
import com.poscafe.pos.ui.theme.PosColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SyncBannerViewModel @Inject constructor(status: SyncStatus) : ViewModel() {
    val snapshot: StateFlow<SyncSnapshot> =
        status.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncSnapshot())
}

private enum class BannerKind { Rejected, Offline, Unreachable, Syncing }

private fun SyncSnapshot.bannerKind(): BannerKind? = when {
    standalone -> null
    failed > 0 -> BannerKind.Rejected
    !online -> BannerKind.Offline
    running && queued > 0 -> BannerKind.Syncing
    // On Wi-Fi but the server didn't answer (wrong URL, server down, other
    // subnet). Same outcome for the cashier as offline, so say so.
    lastError != null && queued > 0 -> BannerKind.Unreachable
    else -> null
}

/**
 * Slim status strip pinned above every Home tab on server-connected tills.
 * Hidden when all is well, so it only ever costs space when the cashier
 * should know something: offline (sales are safe, will upload), uploading,
 * or the server rejected an op. Standalone tills never show it.
 */
@Composable
fun SyncBanner(onOpenSync: () -> Unit, vm: SyncBannerViewModel = hiltViewModel()) {
    val s by vm.snapshot.collectAsStateWithLifecycle()
    val kind = s.bannerKind()

    AnimatedVisibility(
        visible = kind != null,
        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
    ) {
        AnimatedContent(
            targetState = kind ?: BannerKind.Syncing,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "syncBanner",
        ) { k ->
            when (k) {
                BannerKind.Rejected -> BannerRow(
                    icon = Icons.Outlined.ErrorOutline,
                    fg = PosColors.Error,
                    bg = PosColors.ErrorSoft,
                    title = if (s.failed == 1) "1 record rejected by the server" else "${s.failed} records rejected by the server",
                    subtitle = "Kept on this device — review and retry.",
                    action = "Review",
                    onClick = onOpenSync,
                )
                BannerKind.Offline -> BannerRow(
                    icon = Icons.Outlined.CloudOff,
                    fg = Color(0xFF92400E),
                    bg = PosColors.WarningSoft,
                    title = "Offline — keep selling",
                    subtitle = if (s.queued > 0) {
                        "${s.queued} waiting · uploads automatically when back online"
                    } else {
                        "Everything is saved on this device"
                    },
                    action = null,
                    onClick = onOpenSync,
                )
                BannerKind.Unreachable -> BannerRow(
                    icon = Icons.Outlined.CloudOff,
                    fg = Color(0xFF92400E),
                    bg = PosColors.WarningSoft,
                    title = "Can't reach the server — keep selling",
                    subtitle = "${s.queued} waiting · retrying automatically",
                    action = "Details",
                    onClick = onOpenSync,
                )
                BannerKind.Syncing -> BannerRow(
                    icon = Icons.Outlined.CloudSync,
                    fg = Color(0xFF1E40AF),
                    bg = PosColors.InfoSoft,
                    title = "Syncing ${s.queued}…",
                    subtitle = null,
                    action = null,
                    onClick = onOpenSync,
                    busy = true,
                )
            }
        }
    }
}

@Composable
private fun BannerRow(
    icon: ImageVector,
    fg: Color,
    bg: Color,
    title: String,
    subtitle: String?,
    action: String?,
    onClick: () -> Unit,
    busy: Boolean = false,
) {
    Surface(
        onClick = onClick,
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.SemiBold)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.85f)) }
            }
            action?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** "just now" / "5 min ago" / "3 h ago" / "2 d ago" — for last-sync labels. */
fun relativeTime(epochMillis: Long?, now: Long = System.currentTimeMillis()): String {
    if (epochMillis == null) return "never"
    val mins = (now - epochMillis).coerceAtLeast(0) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 60 * 24 -> "${mins / 60} h ago"
        else -> "${mins / (60 * 24)} d ago"
    }
}
