package com.crunzex.linuxondex.vm.iso

import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import java.io.File

/**
 * Builds the cloud-init NoCloud seed a ready-made cloud image needs on its
 * first boot: the login account, an auto-logged-in serial console, and the
 * first-boot chores switched off.
 *
 * Generating it in the app means the user imports one file — the disk image
 * — instead of having to produce and copy a matching seed alongside it.
 */
object CloudInitSeedBuilder {

    /** cloud-init looks for a volume with this label. */
    private const val SEED_VOLUME_LABEL = "cidata"

    /**
     * Writes a seed ISO configuring [username]/[password] to [outputFile].
     *
     * @return the file written, so callers can store its path.
     */
    fun build(
        outputFile: File,
        username: String,
        password: String,
    ): File {
        require(username.matches(SAFE_ACCOUNT_REGEX)) { "unsafe username: $username" }
        require(password.matches(SAFE_ACCOUNT_REGEX)) { "unsafe password" }

        return try {
            Iso9660Writer.write(
                outputFile = outputFile,
                volumeLabel = SEED_VOLUME_LABEL,
                files = listOf(
                    IsoFileEntry("user-data", renderUserData(username, password).toByteArray()),
                    IsoFileEntry("meta-data", renderMetaData().toByteArray()),
                ),
            )
            AppLog.info(
                SCOPE,
                "generated seed ${outputFile.name} (${outputFile.length() shr 10} KiB) for '$username'",
            )
            outputFile
        } catch (error: Exception) {
            throw LxdError.StorageFailed("generating the cloud-init seed", error)
        }
    }

    /**
     * The cloud-config applied on first boot.
     *
     * Everything disabled here costs real time under software emulation and
     * buys a phone nothing: snap seeding, unattended upgrades, the daily apt
     * timers, and the Qualcomm-only units Ubuntu ships that restart forever
     * on QEMU's `virt` board.
     *
     * Internal so tests can assert on the document without unpacking an ISO.
     */
    internal fun renderUserData(username: String, password: String): String = """
        #cloud-config
        hostname: dex
        manage_etc_hosts: true

        users:
          - name: $username
            gecos: Linux on DeX
            groups: [adm, sudo, users, video, audio, plugdev]
            shell: /bin/bash
            sudo: "ALL=(ALL) NOPASSWD:ALL"
            lock_passwd: false
            plain_text_passwd: $password

        ssh_pwauth: true
        disable_root: true
        datasource_list: [NoCloud, None]

        growpart:
          mode: auto
          devices: ["/"]
        resize_rootfs: true

        write_files:
          # TERM=xterm-256color: the app ships a real terminal emulator;
          # systemd's vt220 serial default would strip it to monochrome.
          - path: /etc/systemd/system/serial-getty@ttyAMA0.service.d/autologin.conf
            permissions: "0644"
            content: |
              [Service]
              Environment=TERM=xterm-256color
              ExecStart=
              ExecStart=-/sbin/agetty --autologin $username --noclear %I ${'$'}TERM
          # The app's extra terminal windows attach to virtio consoles
          # (/dev/hvc0, /dev/hvc1); each needs its own logged-in shell.
          - path: /etc/systemd/system/serial-getty@hvc0.service.d/autologin.conf
            permissions: "0644"
            content: |
              [Service]
              Environment=TERM=xterm-256color
              ExecStart=
              ExecStart=-/sbin/agetty --autologin $username --noclear %I ${'$'}TERM
          - path: /etc/systemd/system/serial-getty@hvc1.service.d/autologin.conf
            permissions: "0644"
            content: |
              [Service]
              Environment=TERM=xterm-256color
              ExecStart=
              ExecStart=-/sbin/agetty --autologin $username --noclear %I ${'$'}TERM
          # QEMU's user-mode network resolves DNS through the host's
          # /etc/resolv.conf, which does not exist on Android — the
          # DHCP-provided 10.0.2.3 resolver is dead on a phone. Public
          # resolvers, reached as ordinary UDP traffic, work everywhere.
          - path: /etc/systemd/resolved.conf.d/50-linux-on-dex-dns.conf
            permissions: "0644"
            content: |
              [Resolve]
              DNS=1.1.1.1 8.8.8.8
              FallbackDNS=9.9.9.9
              Domains=~.
          # Serial lines cannot deliver SIGWINCH; this asks the terminal for
          # its size (cursor-position report) at login, and by hand via
          # `fix_console` after a resize or font change.
          - path: /etc/profile.d/98-linux-on-dex-console.sh
            permissions: "0644"
            content: |
              [ -n "${'$'}BASH_VERSION" ] || return 0
              fix_console() {
                  [ -t 0 ] && [ -t 1 ] || return 0
                  local saved rows cols discard
                  saved=${'$'}(stty -g 2>/dev/null) || return 0
                  stty raw -echo min 0 time 5 2>/dev/null || return 0
                  printf '\0337\033[999;999H\033[6n\0338' > /dev/tty
                  IFS='[;R' read -r -t 1 -d R discard rows cols < /dev/tty 2>/dev/null
                  stty "${'$'}saved" 2>/dev/null
                  if [ -n "${'$'}rows" ] && [ -n "${'$'}cols" ] && [ "${'$'}rows" -gt 0 ] 2>/dev/null; then
                      stty rows "${'$'}rows" cols "${'$'}cols" 2>/dev/null
                  fi
              }
              case "${'$'}(tty 2>/dev/null)" in
                  /dev/ttyAMA*|/dev/ttyS*|/dev/hvc*) fix_console ;;
              esac

        runcmd:
          # Missing units (snapd on Debian, …) just log and move on.
          - [sh, -c, "systemctl disable --now snapd.service snapd.socket snapd.seeded.service 2>/dev/null || true"]
          - [sh, -c, "systemctl disable --now unattended-upgrades.service 2>/dev/null || true"]
          - [sh, -c, "systemctl disable --now apt-daily.timer apt-daily-upgrade.timer 2>/dev/null || true"]
          - [systemctl, mask, systemd-networkd-wait-online.service]
          - [systemctl, mask, pd-mapper.service, qrtr-ns.service]
          - [systemctl, daemon-reload]
          # DNS on whichever resolver stack the image uses: systemd-resolved
          # reads the drop-in above; classic dhclient setups (Debian
          # genericcloud) get a supersede rule plus an immediate resolv.conf.
          - [sh, -c, "printf 'supersede domain-name-servers 1.1.1.1, 8.8.8.8;\n' >> /etc/dhcp/dhclient.conf 2>/dev/null || true"]
          - [sh, -c, "if systemctl is-enabled --quiet systemd-resolved 2>/dev/null || systemctl is-active --quiet systemd-resolved 2>/dev/null; then systemctl restart systemd-resolved; else printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > /etc/resolv.conf; fi"]
          - [systemctl, restart, "serial-getty@ttyAMA0.service"]
          # Extra terminal windows: enable a getty on each virtio console.
          - [sh, -c, "systemctl enable serial-getty@hvc0.service 2>/dev/null || true"]
          - [sh, -c, "systemctl enable serial-getty@hvc1.service 2>/dev/null || true"]
          # Restart, not just enable: a getty started before the
          # drop-in existed would still prompt for a password.
          - [sh, -c, "systemctl restart serial-getty@hvc0.service 2>/dev/null || true"]
          - [sh, -c, "systemctl restart serial-getty@hvc1.service 2>/dev/null || true"]

        final_message: "Linux on DeX is ready. Log in as $username/$password."
    """.trimIndent() + "\n"

    internal fun renderMetaData(): String =
        "instance-id: linux-on-dex-001\nlocal-hostname: dex\n"

    private const val SCOPE = "CloudInitSeedBuilder"

    /** Keeps generated YAML free of anything that could break its structure. */
    private val SAFE_ACCOUNT_REGEX = Regex("^[A-Za-z0-9._-]{1,32}$")
}
