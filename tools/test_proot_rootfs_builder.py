import subprocess
import unittest

import proot_rootfs_builder as builder
import yaml


class ProotRootfsBuilderTest(unittest.TestCase):

    def test_gnome_uses_real_shell_without_legacy_desktop(self) -> None:
        rendered = builder._render_build_user_data("linuxondex")

        self.assertIn("gnome-shell", builder.GUEST_PACKAGES)
        self.assertIn("exec gnome-shell --x11", rendered)
        self.assertNotIn("gnome-session-flashback", builder.GUEST_PACKAGES)
        self.assertNotIn("gnome-flashback", builder.GUEST_PACKAGES)
        self.assertNotIn("gnome-panel", builder.GUEST_PACKAGES)
        self.assertNotIn("openbox", builder.GUEST_PACKAGES)
        self.assertNotIn("apt-get purge -y gvfs", rendered)

    def test_native_x11_survives_and_restarts_a_crashed_gnome_session(self) -> None:
        supervisor = builder.DESKTOP_SUPERVISOR_SCRIPT

        self.assertIn('while [ -S /tmp/.X11-unix/X1 ]', supervisor)
        self.assertIn('GNOME exited with code $session_exit_code', supervisor)
        self.assertIn("trap handle_shutdown_signal HUP INT TERM", supervisor)
        self.assertNotIn("Xtigervnc", supervisor)
        self.assertNotIn("tigervnc-standalone-server", builder.GUEST_PACKAGES)
        self.assertNotIn("exec dbus-run-session", supervisor)
        self.assertIn("--renderer-process-limit=2", builder.VSCODE_WRAPPER_SCRIPT)
        self.assertIn("GALLIUM_DRIVER=llvmpipe", supervisor)
        self.assertNotIn("GALLIUM_DRIVER=virpipe", supervisor)
        syntax_check = subprocess.run(
            ["sh", "-n"],
            input=supervisor,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, syntax_check.returncode, syntax_check.stderr)

    def test_native_gpu_is_opt_in_and_cannot_own_the_gnome_session(self) -> None:
        rendered = builder._render_build_user_data("linuxondex")

        self.assertIn("/usr/local/bin/dex-gpu", rendered)
        self.assertIn("GALLIUM_DRIVER=virpipe", builder.GPU_WRAPPER_SCRIPT)
        self.assertIn("GALLIUM_DRIVER=llvmpipe", builder.DESKTOP_PROFILE_SCRIPT)
        self.assertIn("native Android virgl bridge is unavailable", builder.GPU_WRAPPER_SCRIPT)
        self.assertNotIn("dex-gpu gnome-shell", rendered)

    def test_firefox_is_bounded_for_android_process_and_graphics_limits(self) -> None:
        rendered = builder._render_build_user_data("linuxondex")

        self.assertIn("MOZ_WEBRENDER=0", builder.FIREFOX_WRAPPER_SCRIPT)
        self.assertIn('"dom.ipc.processCount": 1', builder.FIREFOX_POLICIES)
        self.assertIn("/usr/lib/firefox/distribution/policies.json", rendered)
        yaml.safe_load(rendered)

    def test_unused_dbus_helpers_cannot_consume_phantom_process_slots(self) -> None:
        rendered = builder._render_build_user_data("linuxondex")

        for service_name in (
            "org.freedesktop.Accounts",
            "org.freedesktop.ColorManager",
            "org.freedesktop.GeoClue2",
            "org.freedesktop.ModemManager1",
            "org.gnome.Shell.CalendarServer",
        ):
            self.assertIn(service_name, rendered)
        self.assertIn('rm -f "/usr/share/dbus-1/services/$service.service"', rendered)

    def test_firefox_uses_verified_mozilla_deb_and_proot_wrapper(self) -> None:
        rendered = builder._render_build_user_data("linuxondex")

        yaml.safe_load(rendered)
        self.assertIn("https://packages.mozilla.org/apt", rendered)
        self.assertIn("35BAA0B33E9EB396F59CA838C0BA5CE6DC6315A3", rendered)
        self.assertIn("apt-get install -y firefox", rendered)
        self.assertIn("MOZ_DISABLE_CONTENT_SANDBOX=1", rendered)

    def test_runtime_names_groups_before_native_x11_session(self) -> None:
        supervisor = builder.DESKTOP_SUPERVISOR_SCRIPT

        self.assertLess(supervisor.index("dex-name-groups"), supervisor.index("native X11 ready"))
        self.assertIn("native-x11", builder._render_build_user_data("linuxondex"))
        self.assertIn("flock 9", builder.GROUP_NAMER_SCRIPT)

    def test_release_archive_requires_shell_code_and_firefox(self) -> None:
        entries = set(builder.REQUIRED_ARCHIVE_ENTRIES)

        self.assertIn("usr/bin/gnome-shell", entries)
        self.assertIn("usr/local/bin/firefox", entries)
        self.assertIn("usr/local/bin/dex-gpu", entries)
        self.assertIn("usr/lib/firefox/firefox", entries)
        self.assertIn("usr/local/share/linux-on-dex/display-backend", entries)
        self.assertNotIn("usr/bin/Xtigervnc", entries)
        self.assertNotIn("usr/bin/gnome-flashback", entries)
        self.assertNotIn("usr/bin/gnome-panel", entries)
        self.assertNotIn("usr/bin/openbox", entries)


if __name__ == "__main__":
    unittest.main()
