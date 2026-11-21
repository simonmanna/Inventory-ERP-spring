package com.poscafe.pos.data

/**
 * ADR-S083 C4 — the user offline-session window. A till that has not completed a pull for longer than the window
 * stops taking new sales until it syncs; the queue still pushes. A clock that reads earlier than the last sync is
 * not trusted either (someone wound it back), so it blocks the same way. Mirrors `web/src/lib/offline/session.ts`.
 */
object OfflineWindow {

    /** Why new writes are refused right now, or null when they are allowed. */
    fun block(lastPullAt: Long?, windowHours: Int, now: Long): String? = when {
        lastPullAt == null -> null // never synced: first-sync gating is the setup screen's, not this
        now < lastPullAt -> "This till's clock reads earlier than its last sync. Fix the date and time, then sync."
        windowHours > 0 && now - lastPullAt > windowHours * 3_600_000L ->
            "This till has not synced for more than $windowHours hours. Connect it to the server and sync before selling."
        else -> null
    }

    /** Hours left before the window closes (for a warning), or null when it has already closed or never synced. */
    fun hoursLeft(lastPullAt: Long?, windowHours: Int, now: Long): Long? {
        if (lastPullAt == null || windowHours <= 0 || now < lastPullAt) return null
        val left = windowHours * 3_600_000L - (now - lastPullAt)
        return if (left <= 0) null else left / 3_600_000L
    }
}
