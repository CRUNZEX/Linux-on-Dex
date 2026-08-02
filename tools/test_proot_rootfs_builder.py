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

    def test_firefox_uses_verified_mozilla_deb_and_proot_wrapper(self) -> None:
        rendered = builder._render_build_user_data("linuxondex")

        yaml.safe_load(rendered)
        self.assertIn("https://packages.mozilla.org/apt", rendered)
        self.assertIn("35BAA0B33E9EB396F59CA838C0BA5CE6DC6315A3", rendered)
        self.assertIn("apt-get install -y firefox", rendered)
        self.assertIn("MOZ_DISABLE_CONTENT_SANDBOX=1", rendered)

    def test_runtime_names_groups_before_session_and_caps_vnc_at_240(self) -> None:
        supervisor = builder.DESKTOP_SUPERVISOR_SCRIPT

        self.assertLess(supervisor.index("dex-name-groups"), supervisor.index("Xtigervnc"))
        self.assertIn("MAX_FRAME_RATE=240", supervisor)
        self.assertIn('-FrameRate "$MAX_FRAME_RATE"', supervisor)
        self.assertIn("flock 9", builder.GROUP_NAMER_SCRIPT)

    def test_release_archive_requires_shell_code_and_firefox(self) -> None:
        entries = set(builder.REQUIRED_ARCHIVE_ENTRIES)

        self.assertIn("usr/bin/gnome-shell", entries)
        self.assertIn("usr/local/bin/firefox", entries)
        self.assertIn("usr/lib/firefox/firefox", entries)
        self.assertNotIn("usr/bin/gnome-flashback", entries)
        self.assertNotIn("usr/bin/gnome-panel", entries)
        self.assertNotIn("usr/bin/openbox", entries)


if __name__ == "__main__":
    unittest.main()
