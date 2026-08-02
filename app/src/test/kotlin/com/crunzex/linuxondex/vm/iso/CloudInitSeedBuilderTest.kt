package com.crunzex.linuxondex.vm.iso

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seed document is what makes an imported image "ready": these pin the
 * guarantees the app gives a fresh guest — working DNS on a phone, a
 * colour-capable terminal, and a console that learns its size.
 */
class CloudInitSeedBuilderTest {

    private val userData = CloudInitSeedBuilder.renderUserData("dex", "dex")

    @Test
    fun `configures public dns resolvers because android has no resolv conf`() {
        assertTrue(userData.contains("/etc/systemd/resolved.conf.d/50-linux-on-dex-dns.conf"))
        assertTrue(userData.contains("DNS=1.1.1.1 8.8.8.8"))
        assertTrue(userData.contains("Domains=~."))
        // Both resolver stacks: restart resolved where it exists, and fall
        // back to a direct resolv.conf (Debian genericcloud) where it does
        // not — plus a dhclient supersede so renewals keep the servers.
        assertTrue(userData.contains("systemctl restart systemd-resolved"))
        assertTrue(userData.contains("supersede domain-name-servers 1.1.1.1, 8.8.8.8"))
        assertTrue(userData.contains("nameserver 1.1.1.1"))
    }

    @Test
    fun `serial console gets a colour-capable terminal type`() {
        assertTrue(userData.contains("Environment=TERM=xterm-256color"))
    }

    @Test
    fun `console size helper is installed and asks via cursor position report`() {
        assertTrue(userData.contains("/etc/profile.d/98-linux-on-dex-console.sh"))
        assertTrue(userData.contains("fix_console"))
        // The DSR query our terminal answers: ESC[6n.
        assertTrue(userData.contains("\\033[6n"))
    }

    @Test
    fun `kotlin dollar escapes render as real shell dollars`() {
        // A literal ${'$'} surviving into the document would break the shell
        // helper and the getty line both.
        assertFalse(userData.contains("{'$'}"))
        assertTrue(userData.contains("\$TERM"))
        assertTrue(userData.contains("\$BASH_VERSION"))
    }

    @Test
    fun `autologin account and instance identity stay stable`() {
        assertTrue(userData.contains("--autologin dex"))
        // Must match the images baked by tools/, or cloud-init re-runs the
        // whole first-boot configuration on the phone.
        assertTrue(
            CloudInitSeedBuilder.renderMetaData().contains("instance-id: linux-on-dex-001")
        )
    }
}
