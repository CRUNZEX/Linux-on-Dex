package com.crunzex.linuxondex.monitor

import com.crunzex.linuxondex.vm.PortForwardRule
import java.net.Inet4Address
import java.net.NetworkInterface

/** One address the phone currently holds, with the interface it belongs to. */
data class HostAddress(
    val interfaceName: String,
    val address: String,
    val isIpv4: Boolean,
)

/**
 * The VM's networking as seen from both sides. Under QEMU user-mode (slirp)
 * networking the guest always sits behind the same fixed virtual NAT, so
 * these addresses are constants — but they are exactly what a user needs to
 * reach services between the two.
 */
data class VmNetworkInfo(
    val guestIpAddress: String,
    val hostReachableFromGuest: String,
    val guestDnsServer: String,
    val forwardedPorts: List<PortForwardRule>,
)

/**
 * Reads the phone's own IP addresses and reports the VM's fixed virtual
 * network, for the monitor's Network section. Enumeration failures degrade
 * to an empty list rather than throwing — a monitor must never crash the UI.
 */
object NetworkAddresses {

    // slirp's built-in DHCP hands out the same layout on every boot.
    private const val GUEST_IP = "10.0.2.15"
    private const val HOST_FROM_GUEST = "10.0.2.2"
    private const val GUEST_DNS = "10.0.2.3"

    /** Non-loopback host addresses, IPv4 first, each with its interface. */
    fun hostAddresses(): List<HostAddress> = try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { networkInterface ->
                networkInterface.inetAddresses.asSequence()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .mapNotNull { address ->
                        val text = address.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                        HostAddress(
                            interfaceName = networkInterface.name,
                            address = text,
                            isIpv4 = address is Inet4Address,
                        )
                    }
            }
            ?.sortedByDescending { it.isIpv4 }
            ?.toList()
            .orEmpty()
    } catch (unreadable: Exception) {
        emptyList()
    }

    fun vmNetwork(forwardedPorts: List<PortForwardRule>): VmNetworkInfo = VmNetworkInfo(
        guestIpAddress = GUEST_IP,
        hostReachableFromGuest = HOST_FROM_GUEST,
        guestDnsServer = GUEST_DNS,
        forwardedPorts = forwardedPorts,
    )
}
