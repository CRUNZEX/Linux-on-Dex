<div align="center">

# Linux on DeX

**Run a Linux VM with a full Linux server and desktop on your Android phone. No root.**

A real Linux VM with its own kernel - not a chroot or proot trick - so **Podman, Docker and LXC** work exactly like they do on a server, with multiple Linux distros to choose from.

[![Release](https://img.shields.io/github/v/release/CRUNZEX/linux-on-dex?include_prereleases&style=flat-square&label=release&color=blue)](https://github.com/CRUNZEX/linux-on-dex/releases)
[![Stars](https://img.shields.io/github/stars/CRUNZEX/linux-on-dex?style=flat-square&color=yellow)](https://github.com/CRUNZEX/linux-on-dex/stargazers)
![Android 13+](https://img.shields.io/badge/Android-13%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![arm64](https://img.shields.io/badge/arch-arm64-orange?style=flat-square)

<img src="docs/images/01_screenshot.jpg" alt="Linux on DeX and a terminal side by side on Samsung DeX: the VM running a MongoDB container while the Monitor page charts CPU, memory, power and temperature" width="800" />

<img src="docs/images/02_screenshot.jpg" alt="Two terminal windows on Samsung DeX: an AI coding CLI working inside the VM next to docker ps listing a running container" width="800" />

<img src="docs/images/03_screenshot.jpg" alt="VS Code (code-server) served from the VM, open in an Android browser at localhost through port forwarding" width="800" />

<img src="docs/images/04_screenshot.jpg" alt="The in-app terminal on the phone: docker ps and /etc/os-release showing Debian 13 inside the VM" width="320" />

</div>

## What you get

- **Multiple Linux distros** - Ubuntu, Debian, Kali and Alpine
- **Podman, Docker and LXC** - pre-installed and ready the moment the Alpine image boots
- **A real VM** - Linux on a custom kernel via QEMU, or hardware-accelerated on supported KVM devices
- **In-app terminal** - Termux terminal-view with JetBrains Mono Nerd Font, an extra key row, keyboard toggle, copy and paste, and live resize
- **GUI Linux support** - run a full XFCE or GNOME desktop in the built-in viewer
- **Network port forwarding** - map phone ports to VM ports, applied live while the VM runs

## Quick Setup

1. [Download the APK](https://github.com/CRUNZEX/linux-on-dex/releases/latest) and install it.
2. [Download a ready-made VM file](https://github.com/CRUNZEX/linux-on-dex/releases/latest) (`*.qcow2`) for the Linux you want, then import it in the app.
3. Adjust the specs to your liking - processors, memory, disk, display.
4. Tap **Start Linux**.

## Development

Clone with submodules so the Termux terminal libraries are available:

```sh
git clone --recurse-submodules https://github.com/CRUNZEX/linux-on-dex.git
```

For an existing checkout, run `git submodule update --init --recursive` before
building. The app compiles Termux's `terminal-emulator` and `terminal-view`
sources from `third_party/termux-app`; it does not embed or launch the Termux
application itself.

## License

[GPLv3](LICENSE)
