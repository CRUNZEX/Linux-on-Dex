import tempfile
import unittest
from pathlib import Path

import proot_xfce_rootfs_builder as builder


class ProotXfceRootfsBuilderTest(unittest.TestCase):

    def test_runtime_uses_shared_vnc_and_lightweight_session(self) -> None:
        self.assertIn("-AlwaysShared", builder.DESKTOP_SUPERVISOR)
        self.assertIn("max_frame_rate=240", builder.DESKTOP_SUPERVISOR)
        self.assertIn('-FrameRate "$max_frame_rate"', builder.DESKTOP_SUPERVISOR)
        self.assertNotIn("-NeverShared", builder.DESKTOP_SUPERVISOR)
        self.assertIn("/usr/local/bin/dex-xfce-session", builder.DESKTOP_SUPERVISOR)
        self.assertNotIn("startxfce4", builder.DESKTOP_SUPERVISOR)

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
            self.assertIn("/usr/local/bin/code", dockerfile)

    def test_android_group_ids_are_named_before_the_desktop_starts(self) -> None:
        naming_offset = builder.DESKTOP_SUPERVISOR.index("dex-name-groups")
        vnc_offset = builder.DESKTOP_SUPERVISOR.index("Xtigervnc")

        self.assertLess(naming_offset, vnc_offset)
        self.assertIn("flock 9", builder.GROUP_NAMER_SCRIPT)
        self.assertIn("id -G", builder.GROUP_NAMER_SCRIPT)

    def test_apt_is_tuned_for_proot_networking(self) -> None:
        self.assertIn('APT::Sandbox::User "root"', builder.APT_CONFIG)
        self.assertIn('Acquire::Queue-Mode "access"', builder.APT_CONFIG)
        self.assertIn('Acquire::Languages "none"', builder.APT_CONFIG)


if __name__ == "__main__":
    unittest.main()
