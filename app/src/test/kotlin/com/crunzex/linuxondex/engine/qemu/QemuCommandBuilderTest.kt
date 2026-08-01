package com.crunzex.linuxondex.engine.qemu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.crunzex.linuxondex.vm.BootOrder
import com.crunzex.linuxondex.vm.CpuConfig
import com.crunzex.linuxondex.vm.CpuModel
import com.crunzex.linuxondex.vm.DiskPerformance
import com.crunzex.linuxondex.vm.DiskInterface
import com.crunzex.linuxondex.vm.DisplayAdapter
import com.crunzex.linuxondex.vm.DisplayConfig
import com.crunzex.linuxondex.vm.NetworkConfig
import com.crunzex.linuxondex.vm.NetworkMode
import com.crunzex.linuxondex.vm.PortForwardRule
import com.crunzex.linuxondex.vm.PortProtocol
import com.crunzex.linuxondex.vm.ScreenResolution
import com.crunzex.linuxondex.vm.SharedFolderConfig
import com.crunzex.linuxondex.vm.StorageConfig
import com.crunzex.linuxondex.vm.VmConfig
import com.crunzex.linuxondex.vm.iso.ExtractedKernel
import java.io.File

class QemuCommandBuilderTest {

    private fun plan(
        accelerator: QemuAccelerator = QemuAccelerator.TCG,
        config: VmConfig = defaultConfig(),
        sharedFolderDir: File? = File("/data/shared"),
        directKernel: ExtractedKernel? = null,
        seedIso: File? = null,
    ) = QemuLaunchPlan(
        config = config,
        accelerator = accelerator,
        firmwareCode = File("/data/vm/qemu/edk2-aarch64-code.fd"),
        efiVarsFile = File("/data/vm/disks/primary/efi-vars.fd"),
        rootDiskFile = File("/data/vm/disks/primary/root.qcow2"),
        installerIso = config.installerIsoPath?.let(::File),
        seedIso = seedIso,
        directKernel = directKernel,
        qemuDataDir = File("/data/vm/qemu"),
        sharedFolderDir = sharedFolderDir,
        qmpSocketFile = File("/data/vm/sockets/primary-qmp.sock"),
        serialSocketFile = File("/data/vm/sockets/primary-serial.sock"),
        serialLogFile = File("/data/vm/logs/primary-serial.log"),
        pidFile = File("/data/vm/logs/primary.pid"),
    )

    private fun defaultConfig(
        cpu: CpuConfig = CpuConfig(coreCount = 4, model = CpuModel.MAX),
        memoryMb: Int = 2048,
        storage: StorageConfig = StorageConfig(),
        display: DisplayConfig = DisplayConfig(),
        network: NetworkConfig = NetworkConfig(),
        sharedFolder: SharedFolderConfig = SharedFolderConfig(),
        isoPath: String? = null,
        bootOrder: BootOrder = BootOrder.INSTALLER_FIRST,
    ) = VmConfig(
        id = "primary",
        name = "Linux VM",
        cpu = cpu,
        memoryMb = memoryMb,
        storage = storage,
        display = display,
        network = network,
        sharedFolder = sharedFolder,
        installerIsoPath = isoPath,
        bootOrder = bootOrder,
    )

    private fun valueAfter(arguments: List<String>, flag: String): String {
        val index = arguments.indexOf(flag)
        assertTrue("flag $flag missing in $arguments", index >= 0 && index + 1 < arguments.size)
        return arguments[index + 1]
    }

    private fun valuesAfter(arguments: List<String>, flag: String): List<String> =
        arguments.windowed(2).filter { it[0] == flag }.map { it[1] }

    // ---- CPU and memory ----------------------------------------------------

    @Test
    fun `tcg plan uses multithreaded software acceleration`() {
        val arguments = QemuCommandBuilder.build(plan(accelerator = QemuAccelerator.TCG))

        assertTrue(valueAfter(arguments, "-accel").startsWith("tcg,thread=multi"))
    }

    @Test
    fun `kvm plan uses hardware acceleration without tcg tuning`() {
        val arguments = QemuCommandBuilder.build(plan(accelerator = QemuAccelerator.KVM))

        assertEquals("kvm", valueAfter(arguments, "-accel"))
    }

    @Test
    fun `tcg translation cache scales with guest memory`() {
        val arguments = QemuCommandBuilder.build(
            plan(config = defaultConfig(memoryMb = 4096))
        )

        // A quarter of the 4 GB guest: a desktop's code working set needs
        // this much cache to stop constant re-translation.
        assertEquals("tcg,thread=multi,tb-size=1024", valueAfter(arguments, "-accel"))
    }

    @Test
    fun `tcg translation cache stays within its bounds`() {
        assertEquals(
            QemuAccelerator.MIN_TRANSLATION_CACHE_MB,
            QemuAccelerator.translationCacheMb(guestMemoryMb = 512),
        )
        assertEquals(
            QemuAccelerator.MAX_TRANSLATION_CACHE_MB,
            QemuAccelerator.translationCacheMb(guestMemoryMb = 16384),
        )
        assertEquals(256, QemuAccelerator.translationCacheMb(guestMemoryMb = 1024))
    }

    @Test
    fun `host cpu model degrades to max when there is no hardware accelerator`() {
        val config = defaultConfig(cpu = CpuConfig(coreCount = 2, model = CpuModel.HOST))

        val arguments = QemuCommandBuilder.build(
            plan(accelerator = QemuAccelerator.TCG, config = config)
        )

        // The model, not the whole value: the software VM also turns off
        // features that are ruinous to emulate, which is asserted separately.
        assertEquals("max", valueAfter(arguments, "-cpu")?.substringBefore(','))
    }

    @Test
    fun `host cpu model is kept when kvm is in use`() {
        val config = defaultConfig(cpu = CpuConfig(coreCount = 2, model = CpuModel.HOST))

        val arguments = QemuCommandBuilder.build(
            plan(accelerator = QemuAccelerator.KVM, config = config)
        )

        assertEquals("host", valueAfter(arguments, "-cpu"))
    }

    @Test
    fun `explicit cpu model is passed through`() {
        val config = defaultConfig(cpu = CpuConfig(coreCount = 6, model = CpuModel.NEOVERSE_N1))

        val arguments = QemuCommandBuilder.build(plan(config = config))

        assertEquals("neoverse-n1", valueAfter(arguments, "-cpu"))
        assertEquals("6", valueAfter(arguments, "-smp"))
    }

    @Test
    fun `guest memory comes from the config`() {
        val arguments = QemuCommandBuilder.build(plan(config = defaultConfig(memoryMb = 6144)))

        assertEquals("6144", valueAfter(arguments, "-m"))
    }

    // ---- Storage -----------------------------------------------------------

    @Test
    fun `the disk performance profile is applied to the root drive`() {
        val config = defaultConfig(
            storage = StorageConfig(performance = DiskPerformance.SAFEST)
        )

        val arguments = QemuCommandBuilder.build(plan(config = config))
        val rootDrive = valuesAfter(arguments, "-drive").first { it.contains("id=rootdisk") }

        assertTrue(rootDrive.contains("cache=writethrough"))
    }

    @Test
    fun `virtio scsi disk interface attaches the root disk through a scsi controller`() {
        val config = defaultConfig(
            storage = StorageConfig(diskInterface = DiskInterface.VIRTIO_SCSI)
        )

        val arguments = QemuCommandBuilder.build(plan(config = config))
        val joined = arguments.joinToString(" ")

        assertTrue(joined.contains("virtio-scsi-pci,id=scsi-root"))
        assertTrue(joined.contains("scsi-hd,bus=scsi-root.0,drive=rootdisk"))
        assertFalse(joined.contains("virtio-blk-pci,drive=rootdisk"))
    }

    // ---- Boot order --------------------------------------------------------

    @Test
    fun `installer first makes the cd the primary boot device`() {
        val config = defaultConfig(
            isoPath = "/data/isos/ubuntu.iso",
            bootOrder = BootOrder.INSTALLER_FIRST,
        )

        val arguments = QemuCommandBuilder.build(plan(config = config))
        val joined = arguments.joinToString(" ")

        assertTrue(joined.contains("scsi-cd,bus=scsi-cd.0,drive=installcd,bootindex=0"))
        assertTrue(joined.contains("virtio-blk-pci,drive=rootdisk,bootindex=1"))
    }

    @Test
    fun `disk first keeps the iso attached but boots the installed system`() {
        val config = defaultConfig(
            isoPath = "/data/isos/ubuntu.iso",
            bootOrder = BootOrder.DISK_FIRST,
        )

        val arguments = QemuCommandBuilder.build(plan(config = config))
        val joined = arguments.joinToString(" ")

        assertTrue("iso stays attached", joined.contains("file=/data/isos/ubuntu.iso"))
        assertTrue(joined.contains("virtio-blk-pci,drive=rootdisk,bootindex=0"))
        assertTrue(joined.contains("drive=installcd,bootindex=1"))
    }

    @Test
    fun `direct kernel boot passes the kernel initrd and command line`() {
        val kernel = ExtractedKernel(
            kernelFile = File("/data/vm/disks/primary/boot/vmlinuz"),
            initrdFile = File("/data/vm/disks/primary/boot/initrd"),
            kernelCommandLine = "boot=casper systemd.mask=pd-mapper.service",
        )

        val arguments = QemuCommandBuilder.build(
            plan(config = defaultConfig(isoPath = "/data/isos/ubuntu.iso"), directKernel = kernel)
        )

        assertEquals("/data/vm/disks/primary/boot/vmlinuz", valueAfter(arguments, "-kernel"))
        assertEquals("/data/vm/disks/primary/boot/initrd", valueAfter(arguments, "-initrd"))
        assertEquals("boot=casper systemd.mask=pd-mapper.service", valueAfter(arguments, "-append"))
    }

    @Test
    fun `direct kernel boot keeps the iso attached for the initrd to find`() {
        val kernel = ExtractedKernel(
            kernelFile = File("/boot/vmlinuz"),
            initrdFile = File("/boot/initrd"),
            kernelCommandLine = "boot=casper",
        )

        val arguments = QemuCommandBuilder.build(
            plan(config = defaultConfig(isoPath = "/data/isos/ubuntu.iso"), directKernel = kernel)
        )

        assertTrue(arguments.joinToString(" ").contains("file=/data/isos/ubuntu.iso"))
    }

    @Test
    fun `no kernel arguments without direct kernel boot`() {
        val arguments = QemuCommandBuilder.build(
            plan(config = defaultConfig(isoPath = "/data/isos/ubuntu.iso"))
        )

        assertFalse(arguments.contains("-kernel"))
        assertFalse(arguments.contains("-append"))
    }

    @Test
    fun `plan without iso has no cdrom devices`() {
        val arguments = QemuCommandBuilder.build(plan(config = defaultConfig(isoPath = null)))
        val joined = arguments.joinToString(" ")

        assertFalse(joined.contains("installcd"))
        assertFalse(joined.contains("scsi-cd"))
    }

    // ---- Ready-made images -------------------------------------------------

    @Test
    fun `cloud init seed is attached read only when supplied`() {
        val arguments = QemuCommandBuilder.build(
            plan(seedIso = File("/data/vm-images/seed.iso"))
        )
        val seedDrive = valuesAfter(arguments, "-drive").first { it.contains("cloudinitseed") }

        assertTrue(seedDrive.contains("readonly=on"))
        assertTrue(seedDrive.contains("file=/data/vm-images/seed.iso"))
        assertTrue(
            valuesAfter(arguments, "-device").any { it.contains("drive=cloudinitseed") }
        )
    }

    @Test
    fun `no seed drive is attached once first boot is done`() {
        // The engine passes null after first boot; re-attaching would make
        // cloud-init run its one-time setup all over again.
        val arguments = QemuCommandBuilder.build(plan(seedIso = null))

        assertFalse(arguments.joinToString(" ").contains("cloudinitseed"))
    }

    // ---- Display -----------------------------------------------------------

    @Test
    fun `virtio gpu carries the requested resolution`() {
        val config = defaultConfig(
            display = DisplayConfig(
                adapter = DisplayAdapter.VIRTIO_GPU,
                resolution = ScreenResolution.FHD_1920_1080,
                vncDisplayNumber = 3,
            )
        )

        val arguments = QemuCommandBuilder.build(plan(config = config))
        val gpu = valuesAfter(arguments, "-device").first { it.startsWith("virtio-gpu-pci") }

        assertTrue(gpu.contains("xres=1920"))
        assertTrue(gpu.contains("yres=1080"))
        assertTrue(gpu.contains("edid=on"))
        assertEquals("127.0.0.1:3", valueAfter(arguments, "-vnc"))
    }

    @Test
    fun `adapters without resolution control get no xres arguments`() {
        val config = defaultConfig(display = DisplayConfig(adapter = DisplayAdapter.RAMFB))

        val arguments = QemuCommandBuilder.build(plan(config = config))

        assertTrue(arguments.contains("ramfb"))
        assertFalse(arguments.joinToString(" ").contains("xres="))
    }

    @Test
    fun `vnc always listens on loopback only`() {
        val arguments = QemuCommandBuilder.build(plan())

        assertTrue(valueAfter(arguments, "-vnc").startsWith("127.0.0.1:"))
    }

    // ---- Network -----------------------------------------------------------

    @Test
    fun `disabled networking adds no netdev`() {
        val config = defaultConfig(network = NetworkConfig(mode = NetworkMode.DISABLED))

        val arguments = QemuCommandBuilder.build(plan(config = config))

        assertFalse(arguments.contains("-netdev"))
        assertFalse(arguments.joinToString(" ").contains("virtio-net-pci"))
    }

    @Test
    fun `ssh port forward is bound to loopback and ipv6 is off`() {
        val config = defaultConfig(
            network = NetworkConfig(mode = NetworkMode.USER, sshPortForward = 2222)
        )

        val arguments = QemuCommandBuilder.build(plan(config = config))

        // ipv6=off: user-mode IPv6 has no route out of the app sandbox, so
        // AAAA lookups would stall for seconds before IPv4 succeeds.
        assertEquals(
            "user,id=net0,ipv6=off,hostfwd=tcp:127.0.0.1:2222-:22",
            valueAfter(arguments, "-netdev"),
        )
    }

    @Test
    fun `user port forwards are emitted after the ssh forward`() {
        val config = defaultConfig(
            network = NetworkConfig(
                mode = NetworkMode.USER,
                sshPortForward = 2222,
                portForwards = listOf(
                    PortForwardRule(PortProtocol.TCP, hostPort = 8080, guestPort = 80),
                    PortForwardRule(PortProtocol.UDP, hostPort = 5353, guestPort = 53),
                ),
            )
        )

        val arguments = QemuCommandBuilder.build(plan(config = config))

        assertEquals(
            "user,id=net0,ipv6=off" +
                ",hostfwd=tcp:127.0.0.1:2222-:22" +
                ",hostfwd=tcp:127.0.0.1:8080-:80" +
                ",hostfwd=udp:127.0.0.1:5353-:53",
            valueAfter(arguments, "-netdev"),
        )
    }

    @Test
    fun `plugged-in usb devices are never attached to the vm`() {
        // USB was removed from the configuration for safety: the command
        // line must carry only the built-in virtual keyboard and tablet, no
        // passthrough device and no per-drive share, so nothing on the USB
        // port can ever affect whether the VM boots.
        val joined = QemuCommandBuilder.build(plan()).joinToString(" ")

        assertFalse(joined.contains("usb-host"))
        assertFalse(joined.contains("usbdrive"))
        assertTrue(joined.contains("usb-kbd"))
        assertTrue(joined.contains("usb-tablet"))
    }

    // ---- Shared folder -----------------------------------------------------

    @Test
    fun `shared folder adds a 9p device with the configured tag`() {
        val config = defaultConfig(
            sharedFolder = SharedFolderConfig(enabled = true, mountTag = "androidshare")
        )

        val arguments = QemuCommandBuilder.build(plan(config = config))

        assertTrue(valueAfter(arguments, "-fsdev").contains("path=/data/shared"))
        assertTrue(
            valuesAfter(arguments, "-device")
                .any { it == "virtio-9p-pci,fsdev=shared0,mount_tag=androidshare" }
        )
    }

    @Test
    fun `shared folder is skipped when the directory is unavailable`() {
        val config = defaultConfig(sharedFolder = SharedFolderConfig(enabled = true))

        val arguments = QemuCommandBuilder.build(plan(config = config, sharedFolderDir = null))

        assertFalse(arguments.contains("-fsdev"))
    }

    @Test
    fun `disabled shared folder adds no 9p device`() {
        val arguments = QemuCommandBuilder.build(plan())

        assertFalse(arguments.contains("-fsdev"))
    }

    // ---- Control channels --------------------------------------------------

    @Test
    fun `uefi firmware is attached as readonly code plus writable vars`() {
        val arguments = QemuCommandBuilder.build(plan())
        val pflashDrives = valuesAfter(arguments, "-drive").filter { it.contains("if=pflash") }

        assertTrue(pflashDrives.any { it.contains("readonly=on") && it.contains("edk2-aarch64-code.fd") })
        assertTrue(pflashDrives.any { !it.contains("readonly=on") && it.contains("efi-vars.fd") })
    }

    @Test
    fun `serial console mirrors to a log file from power on`() {
        val arguments = QemuCommandBuilder.build(plan())
        val chardev = valueAfter(arguments, "-chardev")

        assertTrue(chardev.contains("logfile=/data/vm/logs/primary-serial.log"))
        assertTrue("must not block boot waiting for a console client", chardev.contains("wait=off"))
    }

    @Test
    fun `no implicit devices are allowed`() {
        val arguments = QemuCommandBuilder.build(plan())

        assertTrue(arguments.contains("-nodefaults"))
        assertEquals("none", valueAfter(arguments, "-display"))
        assertEquals("none", valueAfter(arguments, "-monitor"))
    }
}
