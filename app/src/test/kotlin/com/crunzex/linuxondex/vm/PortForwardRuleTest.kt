package com.crunzex.linuxondex.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortForwardRuleTest {

    @Test
    fun `builds the slirp specification qemu expects`() {
        val rule = PortForwardRule(PortProtocol.TCP, hostPort = 8080, guestPort = 80)
        assertEquals("tcp:127.0.0.1:8080-:80", rule.slirpSpecification)
    }

    @Test
    fun `udp rules carry the udp protocol`() {
        val rule = PortForwardRule(PortProtocol.UDP, hostPort = 5353, guestPort = 53)
        assertEquals("udp:127.0.0.1:5353-:53", rule.slirpSpecification)
    }

    @Test
    fun `host ports below the app-usable range are rejected`() {
        // 80 is privileged; an unrooted app cannot bind it.
        assertThrows(IllegalArgumentException::class.java) {
            PortForwardRule(hostPort = 80, guestPort = 80)
        }
    }

    @Test
    fun `guest ports may be privileged since the guest binds them`() {
        val rule = PortForwardRule(hostPort = 8022, guestPort = 22)
        assertEquals(22, rule.guestPort)
    }

    @Test
    fun `network config rejects duplicate host ports across ssh and forwards`() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkConfig(
                sshPortForward = 2222,
                portForwards = listOf(PortForwardRule(hostPort = 2222, guestPort = 8000)),
            )
        }
    }

    @Test
    fun `same host port on different protocols is allowed`() {
        val config = NetworkConfig(
            portForwards = listOf(
                PortForwardRule(PortProtocol.TCP, hostPort = 9000, guestPort = 9000),
                PortForwardRule(PortProtocol.UDP, hostPort = 9000, guestPort = 9000),
            )
        )
        assertEquals(2, config.portForwards.size)
    }
}
