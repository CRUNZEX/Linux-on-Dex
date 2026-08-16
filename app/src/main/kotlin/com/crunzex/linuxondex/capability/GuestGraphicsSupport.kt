package com.crunzex.linuxondex.capability

import java.io.File
import java.io.RandomAccessFile

/**
 * Whether a Linux guest on this device could reach the GPU, and if not, why.
 *
 * Worth answering precisely because direct device-node access and the native
 * virgl bridge are different capabilities.
 *
 * Direct rendering needs two things stock firmware normally withholds:
 *
 *  1. **A device node the app may open.** Rendering needs `/dev/dri/renderD*`
 *     (the standard interface), or the vendor's own node — `/dev/kgsl-3d0` on
 *     Adreno, `/dev/mali0` on Mali. SELinux denies all of them to untrusted
 *     apps; only the platform's own graphics processes hold that label.
 *  2. **A driver the guest can load.** Android's GPU driver is a Bionic
 *     library. Guest programs are glibc binaries, so they cannot load it even
 *     where a node is readable — which is why a container gains native CPU
 *     speed but no GPU, unlike the CPU, which needs no driver at all.
 *
 * The app therefore also ships a Bionic virgl server. It uses Android's public
 * EGL/Vulkan APIs and accepts Mesa virpipe commands from the glibc PRoot guest.
 * The engine validates that complete route on every desktop start.
 */
object GuestGraphicsSupport {

    /** What the guest can use to draw, as measured on this device. */
    enum class Renderer(val displayName: String) {
        /** A GPU node opened successfully — a matching guest driver can use it. */
        HARDWARE_DIRECT("Direct GPU node"),

        /** Mesa commands cross to a Bionic renderer using Android EGL/Vulkan. */
        HARDWARE_VIRGL("VirGL + Android GPU"),

        /** No usable node: the guest's drawing is done by the CPU. */
        SOFTWARE_LLVMPIPE("Software (llvmpipe)"),
    }

    /**
     * Render nodes worth trying, most standard first. Vendor nodes are
     * included because a device that opened one would be genuinely capable,
     * and a truthful report is more useful than an assumption.
     */
    private val GPU_DEVICE_NODES = listOf(
        "/dev/dri/renderD128",
        "/dev/dri/card0",
        "/dev/kgsl-3d0",
        "/dev/mali0",
    )

    /** The measured renderer, plus the node that decided it. */
    data class Verdict(val renderer: Renderer, val openedNodePath: String?) {
        val isHardware: Boolean get() = renderer != Renderer.SOFTWARE_LLVMPIPE
    }

    fun measure(nativeVirglRendererAvailable: Boolean = false): Verdict {
        val openableNode = GPU_DEVICE_NODES.firstOrNull(::canOpenForRendering)
        return when {
            openableNode != null ->
                Verdict(Renderer.HARDWARE_DIRECT, openedNodePath = openableNode)
            nativeVirglRendererAvailable ->
                Verdict(Renderer.HARDWARE_VIRGL, openedNodePath = null)
            else ->
                Verdict(Renderer.SOFTWARE_LLVMPIPE, openedNodePath = null)
        }
    }

    /**
     * True when this process can actually open [nodePath] for rendering.
     * Existence is not enough — SELinux denial surfaces only on open.
     */
    private fun canOpenForRendering(nodePath: String): Boolean {
        val node = File(nodePath)
        if (!node.exists()) return false
        // Render nodes are ioctl endpoints, not readable streams. Opening
        // O_RDWR is the capability Mesa/virgl actually needs and avoids a
        // false negative on drivers that reject a read-only open.
        return runCatching { RandomAccessFile(node, "rw").use { true } }.getOrDefault(false)
    }

    /**
     * One sentence explaining a verdict, for the diagnostics screen.
     * Pure so the wording can be pinned by a test.
     */
    fun explain(verdict: Verdict): String = when (verdict.renderer) {
        Renderer.HARDWARE_DIRECT ->
            "A GPU node (${verdict.openedNodePath}) is readable, so a guest " +
                "could use hardware once a matching driver ships in the image."
        Renderer.HARDWARE_VIRGL ->
            "The whole PRoot desktop can render on this device's GPU: Mesa " +
                "forwards through virpipe to Android EGL/Vulkan. The route is " +
                "verified at each desktop start and falls back to llvmpipe by " +
                "itself if this device's driver rejects it."
        Renderer.SOFTWARE_LLVMPIPE ->
            "No native virgl bridge is packaged and Android denies every GPU " +
                "node, so the guest uses llvmpipe."
    }
}
