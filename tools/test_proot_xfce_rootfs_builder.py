import subprocess
import tempfile
import unittest
from pathlib import Path

import proot_xfce_rootfs_builder as builder


class ProotXfceRootfsBuilderTest(unittest.TestCase):

    def test_runtime_uses_native_x11_and_lightweight_session(self) -> None:
        self.assertIn("native X11 ready", builder.DESKTOP_SUPERVISOR)
        self.assertNotIn("Xtigervnc", builder.DESKTOP_SUPERVISOR)
        self.assertIn("/usr/local/bin/dex-xfce-session", builder.DESKTOP_SUPERVISOR)
        self.assertNotIn("startxfce4", builder.DESKTOP_SUPERVISOR)
        self.assertIn('while [ -S /tmp/.X11-unix/X1 ]', builder.DESKTOP_SUPERVISOR)
        self.assertIn("trap handle_shutdown HUP INT TERM", builder.DESKTOP_SUPERVISOR)
        self.assertNotIn("exec dbus-run-session", builder.DESKTOP_SUPERVISOR)
        syntax_check = subprocess.run(
            ["sh", "-n"],
            input=builder.DESKTOP_SUPERVISOR,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, syntax_check.returncode, syntax_check.stderr)

    def test_session_omits_desktop_manager_and_compositor(self) -> None:
        self.assertIn("xfsettingsd --replace --no-daemon", builder.DESKTOP_SESSION)
        self.assertIn("xfwm4 --replace", builder.DESKTOP_SESSION)
        self.assertIn("xfce4-panel", builder.DESKTOP_SESSION)
        self.assertNotIn("\nxfdesktop", builder.DESKTOP_SESSION)
        self.assertIn('value="false"', builder.XFWM_CONFIGURATION)

    def test_build_context_contains_reviewable_runtime_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            context = Path(temporary_directory)
            builder.write_build_context(context)

            self.assertEqual(
                {
                    "Dockerfile",
                    "dex-desktop",
                    "dex-xfce-session",
                    "dex-name-groups",
                    "code-wrapper",
                    "firefox-wrapper",
                    "firefox-policies.json",
                    "dex-gpu",
                    "apt-config",
                    "sshd_config",
                    "xfwm4.xml",
                    "xfce4-panel.xml",
                    "xsettings.xml",
                },
                {path.name for path in context.iterdir()},
            )
            dockerfile = (context / "Dockerfile").read_text(encoding="utf-8")
            self.assertIn(builder.RELEASE_VERSION, dockerfile)
            self.assertIn("update.code.visualstudio.com/latest/linux-deb-arm64/stable", dockerfile)
            self.assertIn("https://packages.mozilla.org/apt", dockerfile)
            self.assertIn("35BAA0B33E9EB396F59CA838C0BA5CE6DC6315A3", dockerfile)
            self.assertIn("/usr/local/bin/code", dockerfile)
            self.assertIn("/usr/local/bin/firefox", dockerfile)

    def test_android_group_ids_are_named_before_the_desktop_starts(self) -> None:
        naming_offset = builder.DESKTOP_SUPERVISOR.index("dex-name-groups")
        x11_offset = builder.DESKTOP_SUPERVISOR.index("native X11 ready")

        self.assertLess(naming_offset, x11_offset)
        self.assertIn("flock 9", builder.GROUP_NAMER_SCRIPT)
        self.assertIn("id -G", builder.GROUP_NAMER_SCRIPT)

    def test_apt_is_tuned_for_proot_networking(self) -> None:
        self.assertIn('APT::Sandbox::User "root"', builder.APT_CONFIG)
        self.assertIn('Acquire::Queue-Mode "access"', builder.APT_CONFIG)
        self.assertIn('Acquire::Languages "none"', builder.APT_CONFIG)

    def test_heavy_apps_are_bounded_and_native_gpu_is_opt_in(self) -> None:
        self.assertIn("--renderer-process-limit=2", builder.VSCODE_WRAPPER_SCRIPT)
        self.assertIn("MOZ_WEBRENDER=0", builder.FIREFOX_WRAPPER_SCRIPT)
        self.assertIn('"dom.ipc.processCount": 1', builder.FIREFOX_POLICIES)
        self.assertIn("GALLIUM_DRIVER=llvmpipe", builder.DESKTOP_SUPERVISOR)
        self.assertNotIn("GALLIUM_DRIVER=virpipe", builder.DESKTOP_SUPERVISOR)
        self.assertIn("GALLIUM_DRIVER=virpipe", builder.GPU_WRAPPER_SCRIPT)


if __name__ == "__main__":
    unittest.main()
