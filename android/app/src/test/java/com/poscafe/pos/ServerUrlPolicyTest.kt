package com.poscafe.pos

import com.poscafe.pos.data.ServerUrlPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlPolicyTest {

    @Test
    fun `local network hosts may use plain HTTP`() {
        listOf(
            "localhost", "10.0.0.5", "127.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.10",
            "169.254.1.1", "100.68.98.67", "till.local", "pos.lan", "api.internal", "router.home.arpa",
        ).forEach { assertTrue(it, ServerUrlPolicy.isLocalHost(it)) }
    }

    @Test
    fun `public hosts are not local`() {
        listOf(
            "169.58.5.88", "8.8.8.8", "172.32.0.1", "100.128.0.1", "pos.example.com", "local.example.com",
            "192.168.1", "300.1.1.1",
        ).forEach { assertFalse(it, ServerUrlPolicy.isLocalHost(it)) }
    }

    @Test
    fun `https is always accepted`() {
        assertNull(ServerUrlPolicy.problem("https://pos.example.com/api/v1/"))
    }
}
