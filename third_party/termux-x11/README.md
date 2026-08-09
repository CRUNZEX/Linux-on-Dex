# Embedded Termux:X11 renderer

`app/libs/termux-x11-lorie-arm64.aar` is built from Termux:X11 commit `d8013ac5d174bb16120f915c10a7255948c59c17` and is licensed under GPL-3.0. The corresponding upstream source is available at <https://github.com/termux/termux-x11/tree/d8013ac5d174bb16120f915c10a7255948c59c17>.

Linux on DeX applies the patch set already shipped in that revision under `lorie/src/main/cpp/patches`, limits the AAR to `arm64-v8a`, sets the host application ID to `com.crunzex.linuxondex`, removes standalone launcher/receiver/accessibility components, makes runtime broadcasts app-private, and uses Android's next `xlocale.h` include for NDK 29 compatibility. The library was assembled with JDK 17, Android SDK 34, NDK 29, and CMake 3.22.1.

The host launches `CmdEntryPoint` inside a private bound `:x11` service process. This preserves Termux:X11's process isolation and Unix-socket protocol without creating an Android phantom `app_process` child. The embedded entry point calls `System.loadLibrary("Xlorie")` first so Android resolves the installed native library; it retains the upstream resource-path fallback for standalone `app_process` use. The loader change is recorded under `patches/`.

SHA-256: `346d98e909478980a13e9fd2554efc76d26bc4473f6142e4f4abaf2ca830429a`

See [LICENSE](LICENSE) for the complete GPL-3.0 text.
