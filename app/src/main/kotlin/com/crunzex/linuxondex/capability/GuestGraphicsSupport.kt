package com.crunzex.linuxondex.capability

import java.io.File

/**
 * Whether a Linux guest on this device could reach the GPU, and if not, why.
 *
 * Worth answering precisely, because "it runs natively under PRoot, so surely
 * it can use the GPU" is a reasonable expectation that happens to be wrong,
 * and the reason is not obvious.
 *
 * Two things have to be true for guest 3D, and on stock firmware neither is:
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
 * The check is a real open attempt rather than an existence test: a node can
 * be listed and still be unopenable, and only opening it settles the matter.
 */
object GuestGraphicsSupport {

    /** What the guest can use to draw, as measured on this device. */
    enum class Renderer(val displayName: String) {
        /** A GPU node opened successfully — a guest could use hardware. */
        HARDWARE("Hardware (GPU node open)"),

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
        val isHardware: Boolean get() = renderer == Renderer.HARDWARE
    }

    fun measure(): Verdict {
        val openableNode = GPU_DEVICE_NODES.firstOrNull(::canOpenForRendering)
        return if (openableNode == null) {
            Verdict(Renderer.SOFTWARE_LLVMPIPE, openedNodePath = null)
        } else {
            Verdict(Renderer.HARDWARE, openedNodePath = openableNode)
        }
    }

    /**
     * True when this process can actually open [nodePath] for rendering.
     * Existence is not enough — SELinux denial surfaces only on open.
     */
    private fun canOpenForRendering(nodePath: String): Boolean {
        val node = File(nodePath)
        if (!node.exists()) return false
        return runCatching { node.inputStream().use { true } }.getOrDefault(false)
    }

    /**
     * One sentence explaining a verdict, for the diagnostics screen.
     * Pure so the wording can be pinned by a test.
     */
    fun explain(verdict: Verdict): String = when (verdict.renderer) {
        Renderer.HARDWARE ->
            "A GPU node (${verdict.openedNodePath}) is readable, so a guest " +
                "could use hardware once a matching driver ships in the image."
        Renderer.SOFTWARE_LLVMPIPE ->
            "Android denies apps every GPU node, and Linux programs cannot " +
                "load Android's own driver, so the guest desktop is drawn by " +
                "the CPU. Fewer pixels is the effective lever: lower the " +
                "display resolution."
    }
}
