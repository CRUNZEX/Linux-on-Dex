package com.crunzex.linuxondex.core

/**
 * Every failure the app can surface to the user, with a human-readable
 * message and enough technical detail to diagnose from the log screen.
 *
 * Keeping failures in one sealed hierarchy forces each new failure mode to
 * decide: what does the user see, and what can they do about it?
 */
sealed class LxdError(
    /** Short, user-facing explanation (no jargon, no stack traces). */
    val userMessage: String,
    /** Full detail for logs / bug reports. */
    val technicalDetail: String,
    cause: Throwable? = null,
) : Exception("$userMessage — $technicalDetail", cause) {

    class UnsupportedDevice(detail: String) : LxdError(
        userMessage = "This device cannot run the Linux VM",
        technicalDetail = detail,
    )

    class PayloadMissing(component: String) : LxdError(
        userMessage = "Virtual machine runtime is not installed in this build",
        technicalDetail = "Missing payload component: $component. " +
            "Run tools/fetch_qemu_payload.sh before building the APK.",
    )

    class PayloadCorrupted(component: String, cause: Throwable? = null) : LxdError(
        userMessage = "Virtual machine runtime failed verification",
        technicalDetail = "Component failed to install or verify: $component",
        cause = cause,
    )

    class ProcessSpawnFailed(command: String, cause: Throwable? = null) : LxdError(
        userMessage = "Could not start the virtual machine process",
        technicalDetail = "exec failed for: $command",
        cause = cause,
    )

    class BootFailed(reason: String, cause: Throwable? = null) : LxdError(
        userMessage = "The virtual machine stopped during boot",
        technicalDetail = reason,
        cause = cause,
    )

    class ControlChannelFailed(reason: String, cause: Throwable? = null) : LxdError(
        userMessage = "Lost control connection to the virtual machine",
        technicalDetail = "QMP: $reason",
        cause = cause,
    )

    class DisplayConnectFailed(reason: String, cause: Throwable? = null) : LxdError(
        userMessage = "Could not connect to the virtual machine display",
        technicalDetail = "VNC: $reason",
        cause = cause,
    )

    class StorageFailed(operation: String, cause: Throwable? = null) : LxdError(
        userMessage = "A storage operation failed",
        technicalDetail = operation,
        cause = cause,
    )

    class IsoImportFailed(reason: String, cause: Throwable? = null) : LxdError(
        userMessage = "Could not import the ISO file",
        technicalDetail = reason,
        cause = cause,
    )

    class NoEngineAvailable(detail: String) : LxdError(
        userMessage = "No virtualization method is available on this device",
        technicalDetail = detail,
    )
}
