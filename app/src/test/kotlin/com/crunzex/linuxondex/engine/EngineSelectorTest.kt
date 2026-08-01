package com.crunzex.linuxondex.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.crunzex.linuxondex.capability.DeviceCapabilities
import com.crunzex.linuxondex.capability.KvmAccess

class EngineSelectorTest {

    private fun capabilities(
        apiLevel: Int = 34,
        isArm64: Boolean = true,
        kvm: KvmAccess = KvmAccess.DENIED,
        avf: Boolean = false,
        canForkExec: Boolean = true,
        qemuPayload: Boolean = true,
        prootPayload: Boolean = true,
        totalRamMb: Int = 12 * 1024,
        dex: Boolean = false,
    ) = DeviceCapabilities(
        apiLevel = apiLevel,
        isArm64 = isArm64,
        kvm = kvm,
        hasVirtualizationFramework = avf,
        canForkExec = canForkExec,
        qemuPayloadPresent = qemuPayload,
        prootPayloadPresent = prootPayload,
        totalRamMb = totalRamMb,
        isDexModeActive = dex,
        deviceModel = "SM-TEST",
    )

    @Test
    fun `stock Samsung device with denied kvm falls back to software VM`() {
        val best = EngineSelector.selectBest(capabilities(kvm = KvmAccess.DENIED))

        assertEquals(EngineKind.QEMU_TCG, best?.kind)
    }

    @Test
    fun `usable kvm selects the hardware VM engine`() {
        val best = EngineSelector.selectBest(capabilities(kvm = KvmAccess.USABLE))

        assertEquals(EngineKind.QEMU_KVM, best?.kind)
    }

    @Test
    fun `missing qemu payload falls back to proot`() {
        val best = EngineSelector.selectBest(capabilities(qemuPayload = false))

        assertEquals(EngineKind.PROOT, best?.kind)
    }

    @Test
    fun `low ram device skips software VM but keeps proot`() {
        val best = EngineSelector.selectBest(
            capabilities(kvm = KvmAccess.ABSENT, totalRamMb = 2 * 1024)
        )

        assertEquals(EngineKind.PROOT, best?.kind)
    }

    @Test
    fun `blocked fork exec leaves no engine available`() {
        val best = EngineSelector.selectBest(capabilities(canForkExec = false))

        assertNull(best)
    }

    @Test
    fun `non arm64 device leaves no engine available`() {
        val best = EngineSelector.selectBest(capabilities(isArm64 = false))

        assertNull(best)
    }

    @Test
    fun `ranking always lists all three engines in preference order`() {
        val ranked = EngineSelector.rank(capabilities())

        assertEquals(
            listOf(EngineKind.QEMU_KVM, EngineKind.QEMU_TCG, EngineKind.PROOT),
            ranked.map { it.kind },
        )
    }

    @Test
    fun `unavailable engines carry a human readable reason`() {
        val ranked = EngineSelector.rank(capabilities(kvm = KvmAccess.DENIED))
        val kvmVerdict = ranked.first { it.kind == EngineKind.QEMU_KVM }.availability

        assertFalse(kvmVerdict.isAvailable)
        val reason = (kvmVerdict as EngineAvailability.Unavailable).reason
        assertTrue("reason should mention firmware denial", reason.contains("denied"))
    }

    @Test
    fun `zero ram reading is treated as unknown and does not block the VM`() {
        // Some devices fail the RAM probe; unknown must not disable the engine.
        val best = EngineSelector.selectBest(capabilities(totalRamMb = 0))

        assertEquals(EngineKind.QEMU_TCG, best?.kind)
    }
}
