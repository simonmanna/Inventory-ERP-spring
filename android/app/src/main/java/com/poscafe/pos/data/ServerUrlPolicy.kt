package com.poscafe.pos.data

import com.poscafe.pos.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which server URLs the device may talk to. HTTPS is always fine. Plain HTTP
 * is allowed only to hosts on the local network (private / loopback /
 * Tailscale IPs, `localhost`, `*.local`, `*.lan`, `*.internal`, `*.home.arpa`),
 * because the device token, PIN hashes and sales travel in every request.
 *
 * Builds made with `-Ppos.allowPublicCleartext=true` (debug builds always)
 * also accept public HTTP — a stopgap for servers not yet behind TLS.
 *
 * The network security config has to permit cleartext globally for the LAN
 * case (it cannot express IP ranges), so this check is what enforces it.
 */
object ServerUrlPolicy {

    /** @return null when [url] is acceptable, otherwise a message for the user. */
    fun problem(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return "Enter a full URL, e.g. https://pos.example.com/api/v1"
        return problem(parsed)
    }

    fun problem(url: HttpUrl): String? {
        if (url.isHttps || BuildConfig.ALLOW_PUBLIC_CLEARTEXT || isLocalHost(url.host)) return null
        return "Plain HTTP is only allowed on the local network. Use https:// for ${url.host}."
    }

    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        val octets = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false } ?: return false
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 10 ||
            a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127) // CGNAT range used by Tailscale
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".internal", ".home.arpa")
}
