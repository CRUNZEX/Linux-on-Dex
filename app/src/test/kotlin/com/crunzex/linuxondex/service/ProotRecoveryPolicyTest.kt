package com.crunzex.linuxondex.service

import com.crunzex.linuxondex.engine.EngineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProotRecoveryPolicyTest {

    @Test
    fun `proot receives only the configured bounded retries`() {
        val policy = ProotRecoveryPolicy(listOf(10L, 20L))

        assertEquals(10L, policy.nextDelayMillis(EngineKind.PROOT))
        assertEquals(20L, policy.nextDelayMillis(EngineKind.PROOT))
        assertNull(policy.nextDelayMillis(EngineKind.PROOT))
    }

    @Test
    fun `full system vm is never restarted automatically`() {
        val policy = ProotRecoveryPolicy(listOf(10L))

        assertNull(policy.nextDelayMillis(EngineKind.QEMU_TCG))
        assertNull(policy.nextDelayMillis(EngineKind.QEMU_KVM))
        assertEquals(10L, policy.nextDelayMillis(EngineKind.PROOT))
    }
}
