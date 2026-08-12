import tempfile
import unittest
from pathlib import Path
from unittest import mock

import desktop_image_builder as builder
import yaml


class DesktopImageBuilderTest(unittest.TestCase):

    def request(self, profile: str) -> builder.DesktopBuildRequest:
        return builder.DesktopBuildRequest(
            base_cloud_image=Path("/tmp/base.qcow2"),
            output_disk=Path("/tmp/output.qcow2"),
            work_directory=Path("/tmp/work"),
            disk_size="16G",
            username="dex",
            password="linuxondex",
            desktop_environment=profile,
        )

    def test_xfce_has_graphical_and_serial_autologin(self) -> None:
        rendered = builder._render_build_user_data(self.request("xfce"))

        self.assertIn("autologin-user=dex", rendered)
        self.assertIn("user-session=xfce", rendered)
        self.assertIn("DEX_DESKTOP_PROFILE_VALIDATED=xfce", rendered)
        self.assertIn("DEX_DESKTOP_COMMON_VALIDATED=1", rendered)
        self.assertNotIn('owner: "dex:dex"', rendered)
        self.assertIn('chown -R "dex:dex" "/home/dex"', rendered)
        self.assertIn('runuser -u "dex" -- touch', rendered)
        self.assertIn(f"Linux on DeX {builder.RELEASE_VERSION}", rendered)
        self.assertIn("systemctl enable serial-getty@ttyAMA0.service", rendered)
        self.assertIn("systemctl restart serial-getty@ttyAMA0.service", rendered)

    def test_gnome_uses_xorg_flashback_autologin(self) -> None:
        rendered = builder._render_build_user_data(self.request("gnome"))

        self.assertIn("WaylandEnable=false", rendered)
        self.assertIn("Session=gnome-flashback-metacity", rendered)
        self.assertIn("gnome-session-flashback", rendered)
        self.assertNotIn("Session=ubuntu-xorg", rendered)
        self.assertIn("DEX_DESKTOP_PROFILE_VALIDATED=gnome", rendered)

    def test_both_desktops_draw_without_a_compositor(self) -> None:
        xfce = builder._render_build_user_data(self.request("xfce"))
        gnome = builder._render_build_user_data(self.request("gnome"))

        self.assertIn('<property name="use_compositing" type="bool" value="false"/>', xfce)
        self.assertIn("[org/gnome/metacity]", gnome)
        self.assertIn("compositing-manager=false", gnome)

    def test_no_desktop_ever_blanks_its_display(self) -> None:
        for profile in ("xfce", "gnome"):
            rendered = builder._render_build_user_data(self.request(profile))

            self.assertIn("/etc/X11/xorg.conf.d/10-linux-on-dex-noblank.conf", rendered)
            self.assertIn('Option "BlankTime" "0"', rendered)
        xfce = builder._render_build_user_data(self.request("xfce"))
        self.assertIn('<property name="dpms-enabled" type="bool" value="false"/>', xfce)

    def test_fps_benchmark_ships_with_its_renderer(self) -> None:
        for profile in ("xfce", "gnome"):
            rendered = builder._render_build_user_data(self.request(profile))

            self.assertIn("- mesa-utils", rendered)
            self.assertIn("/usr/local/bin/dex-fps", rendered)
            self.assertIn("test -x /usr/local/bin/dex-fps", rendered)
            self.assertIn("test -x /usr/bin/glxgears", rendered)
            # The script must survive the surrounding f-string: its shell
            # expansions arrive with single braces, or the guest script breaks.
            self.assertIn('SECONDS_TO_RUN="${1:-20}"', rendered)
            self.assertNotIn("${{", rendered)

    def test_apt_keeps_current_lists_and_uses_bounded_fetches(self) -> None:
        rendered = builder._render_build_user_data(self.request("xfce"))

        self.assertIn('Acquire::Retries "3"', rendered)
        self.assertIn('Acquire::Languages "none"', rendered)
        self.assertNotIn("rm -rf /var/lib/apt/lists", rendered)

    def test_rendered_cloud_config_is_valid_yaml(self) -> None:
        for profile in ("xfce", "gnome"):
            rendered = builder._render_build_user_data(self.request(profile))
            parsed = yaml.safe_load(rendered)

            self.assertIsInstance(parsed, dict)
            self.assertGreater(len(parsed["write_files"]), 5)
            self.assertGreater(len(parsed["runcmd"]), 10)

    def test_finished_qcow2_is_compressed_with_portable_zlib(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            staging = directory / "staging.qcow2"
            output = directory / "output.qcow2"
            staging.touch()

            with mock.patch.object(builder, "_run_checked") as run_checked:
                builder._compact_into_output("qemu-img", staging, output)

            command = run_checked.call_args.args[0]
            self.assertIn("-c", command)
            self.assertIn("compat=1.1,compression_type=zlib", command)

    def test_build_seed_instance_id_is_unique_per_bake(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / "seed.iso"
            with mock.patch("build_ready_vm.build_seed_iso_from_documents") as build_seed:
                with mock.patch.object(builder.time, "time_ns", side_effect=[101, 202]):
                    builder._write_build_seed(output, self.request("xfce"))
                    first_metadata = build_seed.call_args.kwargs["meta_data"]
                    builder._write_build_seed(output, self.request("xfce"))
                    second_metadata = build_seed.call_args.kwargs["meta_data"]

        self.assertNotEqual(first_metadata, second_metadata)
        self.assertIn("linux-on-dex-desktop-build-xfce-101", first_metadata)


if __name__ == "__main__":
    unittest.main()
