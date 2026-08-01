package com.crunzex.linuxondex.vm

import com.crunzex.linuxondex.engine.qemu.QemuAccelerator
import com.crunzex.linuxondex.engine.qemu.QemuCommandBuilder
import com.crunzex.linuxondex.engine.qemu.QemuLaunchPlan
import java.io.File
import java.util.Date
import java.util.TimeZone
import java.text.SimpleDateFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Disk speed and port publishing are the two settings that change how the
 * VM behaves outside its own window, so both are pinned to the exact QEMU
 * arguments they produce.
 */
class DiskAndNetworkTuningTest {

    private fun planWith(
        storage: StorageConfig = StorageConfig(),
        network: NetworkConfig = NetworkConfig(),
    ) = QemuLaunchPlan(
        config = VmConfig(
            id = "primary",
            name = "Linux VM",
            storage = storage,
            network = network,
        ),
        accelerator = QemuAccelerator.TCG,
        firmwareCode = File("/vm/code.fd"),
        efiVarsFile = File("/vm/vars.fd"),
        rootDiskFile = File("/vm/root.qcow2"),
        installerIso = null,
        seedIso = null,
        directKernel = null,
        qemuDataDir = File("/vm/qemu"),
        sharedFolderDir = null,
        qmpSocketFile = File("/vm/qmp.sock"),
        serialSocketFile = File("/vm/serial.sock"),
        serialLogFile = File("/vm/serial.log"),
        pidFile = File("/vm/vm.pid"),
    )

    private fun argumentsFor(storage: StorageConfig): String =
        QemuCommandBuilder.build(planWith(storage = storage)).joinToString(" ")

    // ---- Disk performance ----------------------------------------------------

    @Test
    fun `every profile keeps the speed-ups that cost nothing`() {
        for (performance in DiskPerformance.entries) {
            val joined = argumentsFor(StorageConfig(performance = performance))

            // Threaded async I/O keeps a slow flash write from stalling the
            // emulated CPUs, and zero-detection turns installer zero-writes
            // into discards. Neither trades away durability.
            assertTrue("$performance lost aio=threads", joined.contains("aio=threads"))
            assertTrue("$performance lost discards", joined.contains("discard=unmap"))
            assertTrue(
                "$performance lost zero detection",
                joined.contains("detect-zeroes=unmap"),
            )
        }
    }

    @Test
    fun `maximum speed stops honouring flushes and uses its own io thread`() {
        val joined = argumentsFor(StorageConfig(performance = DiskPerformance.MAXIMUM))

        assertTrue(joined.contains("cache=unsafe"))
        assertTrue(joined.contains("-object iothread,id=diskio0"))
        // Order of the device properties is QEMU's business, not ours.
        val blockDevice = joined.split(" ").first { it.startsWith("virtio-blk-pci") }
        assertTrue(blockDevice.contains("drive=rootdisk"))
        assertTrue(blockDevice.contains("iothread=diskio0"))
        assertFalse(DiskPerformance.MAXIMUM.flushesToDisk)
    }

    @Test
    fun `the fastest profile is what a new VM gets`() {
        // Emulated storage is the reason a software VM feels slow, so the
        // default trades crash durability for speed; the other profiles are
        // one tap away for anyone who needs it back.
        assertEquals(DiskPerformance.MAXIMUM, StorageConfig().performance)
        assertFalse(StorageConfig().performance.flushesToDisk)
    }

    @Test
    fun `the disk gets one queue per guest cpu, within reason`() {
        assertEquals(4, QemuCommandBuilder.diskQueueCount(guestCoreCount = 4))
        assertEquals(1, QemuCommandBuilder.diskQueueCount(guestCoreCount = 1))
        // A silly core count must not create a silly number of queues.
        assertEquals(8, QemuCommandBuilder.diskQueueCount(guestCoreCount = 64))
        assertEquals(1, QemuCommandBuilder.diskQueueCount(guestCoreCount = 0))
    }

    @Test
    fun `multiqueue reaches the virtio-blk device`() {
        val joined = QemuCommandBuilder.build(
            planWith(storage = StorageConfig(performance = DiskPerformance.MAXIMUM))
        ).joinToString(" ")

        assertTrue(joined.contains("num-queues="))
    }

    @Test
    fun `the balanced profile is write-back on its own io thread`() {
        val joined = argumentsFor(StorageConfig(performance = DiskPerformance.FAST))

        assertTrue(joined.contains("cache=writeback"))
        assertTrue(joined.contains("iothread=diskio0"))
        assertTrue(DiskPerformance.FAST.flushesToDisk)
    }

    @Test
    fun `the safest profile writes through and stays on the main loop`() {
        val joined = argumentsFor(StorageConfig(performance = DiskPerformance.SAFEST))

        assertTrue(joined.contains("cache=writethrough"))
        assertFalse("safest must not add an io thread", joined.contains("iothread"))
        assertTrue(DiskPerformance.SAFEST.flushesToDisk)
    }

    @Test
    fun `on scsi the io thread belongs to the controller`() {
        val joined = argumentsFor(
            StorageConfig(
                diskInterface = DiskInterface.VIRTIO_SCSI,
                performance = DiskPerformance.FAST,
            )
        )

        // scsi-hd takes no iothread property; the controller owns it.
        assertTrue(joined.contains("virtio-scsi-pci,id=scsi-root,iothread=diskio0"))
        assertFalse(joined.contains("scsi-hd,bus=scsi-root.0,drive=rootdisk,bootindex=0,iothread"))
    }

    // ---- Port forwarding -----------------------------------------------------

    @Test
    fun `a forward stays on this phone unless it is published`() {
        val private = PortForwardRule(hostPort = 8080, guestPort = 80)

        assertEquals("tcp:127.0.0.1:8080-:80", private.slirpSpecification)
        assertTrue(private.displayText.contains("localhost"))
        assertFalse(private.bindAllInterfaces)
    }

    @Test
    fun `publishing a forward binds every interface`() {
        val published = PortForwardRule(
            hostPort = 8080,
            guestPort = 80,
            bindAllInterfaces = true,
        )

        assertEquals("tcp:0.0.0.0:8080-:80", published.slirpSpecification)
        assertTrue(published.displayText.contains("all interfaces"))
    }

    @Test
    fun `both kinds of forward reach the command line`() {
        val joined = QemuCommandBuilder.build(
            planWith(
                network = NetworkConfig(
                    portForwards = listOf(
                        PortForwardRule(hostPort = 8080, guestPort = 80),
                        PortForwardRule(
                            protocol = PortProtocol.UDP,
                            hostPort = 5353,
                            guestPort = 53,
                            bindAllInterfaces = true,
                        ),
                    )
                )
            )
        ).joinToString(" ")

        assertTrue(joined.contains("hostfwd=tcp:127.0.0.1:8080-:80"))
        assertTrue(joined.contains("hostfwd=udp:0.0.0.0:5353-:53"))
    }

    @Test
    fun `ssh forwarding always stays on this phone`() {
        // SSH into the VM is a convenience, not something to expose to the
        // network by accident.
        val joined = QemuCommandBuilder.build(
            planWith(network = NetworkConfig(sshPortForward = 2222))
        ).joinToString(" ")

        assertTrue(joined.contains("hostfwd=tcp:127.0.0.1:2222-:22"))
    }

    // ---- Backup naming -------------------------------------------------------

    @Test
    fun `a backup keeps the image name and appends the date and time`() {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply {
            timeZone = TimeZone.getDefault()
        }.parse("2026-07-31 22:04:31") as Date

        assertEquals(
            "linux-on-dex-alpine-3.22.0-arm64-20260731-220431.qcow2",
            VmBackupManager.backupFileName("linux-on-dex-alpine-3.22.0-arm64.qcow2", stamp),
        )
        // A disk with no extension still gets a well-formed name.
        assertEquals(
            "root-20260731-220431.qcow2",
            VmBackupManager.backupFileName("root", stamp),
        )
    }


    // ---- Metadata caching and queueing --------------------------------------

    @Test
    fun `the L2 cache is sized to cover the whole disk`() {
        // One 8-byte entry per 64 KiB cluster: a disk this size needs
        // exactly this much cache before a random read stops costing a
        // metadata read first.
        assertEquals(2L * 1024 * 1024, QemuCommandBuilder.l2CacheBytes(16))
        assertEquals(8L * 1024 * 1024, QemuCommandBuilder.l2CacheBytes(64))
    }

    @Test
    fun `the L2 cache stays within bounds a phone can afford`() {
        // Tiny disks still get a useful cache…
        assertEquals(2L * 1024 * 1024, QemuCommandBuilder.l2CacheBytes(4))
        assertEquals(2L * 1024 * 1024, QemuCommandBuilder.l2CacheBytes(1))
        // …and the largest disk allowed does not ask for 64 MB of memory.
        assertEquals(32L * 1024 * 1024, QemuCommandBuilder.l2CacheBytes(512))
    }

    @Test
    fun `a bigger disk asks for more cache, never less`() {
        val sizes = listOf(4, 16, 32, 64, 128, 512)
        val caches = sizes.map { QemuCommandBuilder.l2CacheBytes(it) }
        assertEquals(caches.sorted(), caches)
    }

    @Test
    fun `every profile caches metadata, skips file locks and queues deeply`() {
        for (performance in DiskPerformance.entries) {
            val joined = argumentsFor(
                StorageConfig(performance = performance, diskSizeGb = 64)
            )

            // None of these trade away durability, so they apply everywhere.
            assertTrue(
                "$performance lost its L2 cache",
                joined.contains("l2-cache-size=${8L * 1024 * 1024}"),
            )
            assertTrue(
                "$performance still takes an image lock",
                joined.contains("file.locking=off"),
            )
            assertTrue(
                "$performance lost its queue depth",
                joined.contains("queue-size=1024"),
            )
        }
    }


    // ---- CPU features a software VM should not claim -------------------------

    @Test
    fun `the software VM does not advertise pointer authentication`() {
        // Emulating it runs a block cipher on every function call, and the
        // instructions are no-ops on a CPU that lacks it, so refusing it is
        // free. Measured 15-24% off boot-to-login.
        assertEquals(
            "max,pauth=off",
            QemuCommandBuilder.cpuArgument(CpuModel.MAX, QemuAccelerator.TCG),
        )
    }

    @Test
    fun `hardware virtualization keeps every feature`() {
        // Real silicon does pointer authentication for free.
        assertEquals(
            "max",
            QemuCommandBuilder.cpuArgument(CpuModel.MAX, QemuAccelerator.KVM),
        )
    }

    @Test
    fun `a chosen CPU model is passed through exactly as chosen`() {
        for (model in CpuModel.entries.filter { it != CpuModel.MAX }) {
            assertEquals(
                model.qemuName,
                QemuCommandBuilder.cpuArgument(model, QemuAccelerator.TCG),
            )
        }
    }

    @Test
    fun `the built command line carries the tuned CPU`() {
        val joined = QemuCommandBuilder.build(planWith()).joinToString(" ")
        assertTrue(joined.contains("-cpu max,pauth=off"))
    }
}
