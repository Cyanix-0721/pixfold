# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Unit tests for scripts/live_device.py (zero real-device access)."""

from __future__ import annotations

import contextlib
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import live_device  # noqa: E402


class LiveDeviceCoreTest(unittest.TestCase):
    def test_validate_scene_and_options_whitelist(self) -> None:
        self.assertEqual(len(live_device.SCENES), 15)
        for scene in (
            "catalog",
            "home",
            "settings",
            "workspace_dialog",
            "chips",
            "chips_two_level",
            "sort",
            "library",
            "metadata",
            "pack_preview",
            "pack_progress",
            "rename_plan",
            "execution_report",
            "undo_dialog",
            "image_preview",
        ):
            self.assertEqual(live_device.validate_scene(scene), scene)

        self.assertEqual(live_device.validate_scene("  PACK_PROGRESS "), "pack_progress")
        for bad in ("", "   ", "unknown", "home;rm -rf /", "home && echo"):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    live_device.validate_scene(bad)

        opts = live_device.validate_options(
            theme="PURE_BLACK",
            color_spec="2021",
            accent="system",
            font_scale=1.3,
        )
        self.assertEqual(
            opts,
            {
                "theme": "pure_black",
                "color_spec": "2021",
                "accent": "system",
                "color_style": "tonal_spot",
                "font_scale": 1.3,
            },
        )
        style_opts = live_device.validate_options(color_style=" Fruit_Salad ")
        self.assertEqual(style_opts["color_style"], "fruit_salad")
        for style_id in (
            "tonal_spot",
            "vibrant",
            "expressive",
            "neutral",
            "monochrome",
            "fidelity",
            "content",
            "rainbow",
            "fruit_salad",
        ):
            with self.subTest(style_id=style_id):
                self.assertEqual(
                    live_device.validate_options(color_style=style_id)["color_style"],
                    style_id,
                )
        for kwargs in (
            {"theme": "neon"},
            {"color_spec": "2019"},
            {"accent": "gold"},
            {"color_style": "pastel"},
            {"font_scale": 0.5},
            {"font_scale": 2.5},
        ):
            with self.subTest(kwargs=kwargs):
                with self.assertRaises(ValueError):
                    live_device.validate_options(**kwargs)

    def test_validate_serial_and_parse_and_resolve_serial(self) -> None:
        self.assertEqual(live_device.validate_serial("192.168.43.1:4444"), "192.168.43.1:4444")
        self.assertEqual(live_device.validate_serial("emulator-5554"), "emulator-5554")
        for bad in ("", "  ", "192.168.43.1;whoami", "dev/null", "abc def", "../x"):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    live_device.validate_serial(bad)

        adb_out = "\n".join(
            [
                "* daemon not running; starting now at tcp:5037",
                "List of devices attached",
                "192.168.43.1:4444\tdevice product:PJX110 model:PJX110",
                "emulator-5554\toffline",
                "ABCDEF123456\tunauthorized",
                "",
            ]
        )
        online = live_device.parse_adb_devices(adb_out)
        self.assertEqual(len(online), 1)
        self.assertEqual(online[0]["serial"], "192.168.43.1:4444")

        self.assertEqual(live_device.DEFAULT_WIRELESS_SERIAL, "192.168.43.1:4444")
        self.assertEqual(
            live_device.resolve_serial(None, None, online),
            "192.168.43.1:4444",
        )
        self.assertEqual(
            live_device.resolve_serial(None, None, [{"serial": "USB123"}]),
            "USB123",
        )
        self.assertEqual(
            live_device.resolve_serial("USB123", "192.168.43.1:4444", online),
            "USB123",
        )
        self.assertEqual(
            live_device.resolve_serial(None, "ENV456", online),
            "ENV456",
        )
        self.assertEqual(
            live_device.resolve_serial(None, None, []),
            "192.168.43.1:4444",
        )
        with self.assertRaises(RuntimeError):
            live_device.resolve_serial(None, None, [], default_serial=None)
        with self.assertRaises(RuntimeError):
            live_device.resolve_serial(
                None,
                None,
                [{"serial": "A"}, {"serial": "B"}],
            )

    def test_is_tcpip_serial_and_ensure_device_serial_auto_connect(self) -> None:
        for tcpip in (
            "192.168.43.1:4444",
            "10.0.0.2:5555",
            "phone.local:1",
            "127.0.0.1:65535",
        ):
            with self.subTest(tcpip=tcpip):
                self.assertTrue(live_device.is_tcpip_serial(tcpip))

        for non_tcpip in (
            "emulator-5554",
            "ABCDEF123456",
            "192.168.43.1",
            "192.168.43.1:0",
            "192.168.43.1:65536",
            "192.168.43.1:abc",
            ":4444",
            "",
        ):
            with self.subTest(non_tcpip=non_tcpip):
                self.assertFalse(live_device.is_tcpip_serial(non_tcpip))

        def make_runner(responses: list[tuple[int, str, str]]) -> tuple[list[list[str]], object]:
            calls: list[list[str]] = []
            queue = list(responses)

            def runner(cmd: list[str]) -> subprocess.CompletedProcess[str]:
                calls.append(list(cmd))
                rc, out, err = queue.pop(0)
                return subprocess.CompletedProcess(args=cmd, returncode=rc, stdout=out, stderr=err)

            return calls, runner

        # Case A: empty explicit/env serial and no online devices -> defaults to 192.168.43.1:4444 and auto-connects.
        calls_a, runner_a = make_runner(
            [
                (0, "List of devices attached\n", ""),
                (0, "connected to 192.168.43.1:4444\n", ""),
                (0, "List of devices attached\n192.168.43.1:4444\tdevice model:PJX110\n", ""),
            ]
        )
        self.assertEqual(
            live_device.ensure_device_serial("adb.exe", None, env_serial=None, runner=runner_a),
            "192.168.43.1:4444",
        )
        self.assertEqual(
            calls_a,
            [
                ["adb.exe", "devices", "-l"],
                ["adb.exe", "connect", "192.168.43.1:4444"],
                ["adb.exe", "devices", "-l"],
            ],
        )

        # Case B: explicit TCP/IP serial already online -> single adb devices call only.
        calls_b, runner_b = make_runner(
            [(0, "List of devices attached\n10.0.0.9:5555\tdevice\n", "")]
        )
        self.assertEqual(
            live_device.ensure_device_serial("adb.exe", "10.0.0.9:5555", env_serial=None, runner=runner_b),
            "10.0.0.9:5555",
        )
        self.assertEqual(calls_b, [["adb.exe", "devices", "-l"]])

        # Case C: explicit TCP/IP serial offline initially -> auto adb connect then re-check.
        calls_c, runner_c = make_runner(
            [
                (0, "List of devices attached\n", ""),
                (0, "connected to 10.0.0.9:5555\n", ""),
                (0, "List of devices attached\n10.0.0.9:5555\tdevice\n", ""),
            ]
        )
        self.assertEqual(
            live_device.ensure_device_serial("adb.exe", "10.0.0.9:5555", env_serial=None, runner=runner_c),
            "10.0.0.9:5555",
        )
        self.assertEqual(
            calls_c,
            [
                ["adb.exe", "devices", "-l"],
                ["adb.exe", "connect", "10.0.0.9:5555"],
                ["adb.exe", "devices", "-l"],
            ],
        )

        # Case D: adb connect exits 0 with "cannot connect" and device remains offline -> raises RuntimeError.
        calls_d, runner_d = make_runner(
            [
                (0, "List of devices attached\n", ""),
                (0, "cannot connect to 192.168.43.1:4444: A connection attempt failed\n", ""),
                (0, "List of devices attached\n", ""),
            ]
        )
        with self.assertRaises(RuntimeError) as ctx:
            live_device.ensure_device_serial("adb.exe", None, env_serial=None, runner=runner_d)
        msg = str(ctx.exception)
        self.assertIn("192.168.43.1:4444", msg)
        self.assertIn("cannot connect", msg)
        self.assertEqual(len(calls_d), 3)

        # Case E: explicit non-TCP/IP USB serial -> never invokes adb connect.
        calls_e, runner_e = make_runner([(0, "List of devices attached\n", "")])
        self.assertEqual(
            live_device.ensure_device_serial("adb.exe", "USB123", env_serial=None, runner=runner_e),
            "USB123",
        )
        self.assertEqual(calls_e, [["adb.exe", "devices", "-l"]])

        # Case F: single online USB device with no explicit/env serial -> picks USB123 without adb connect.
        calls_f, runner_f = make_runner(
            [(0, "List of devices attached\nUSB123\tdevice usb:1\n", "")]
        )
        self.assertEqual(
            live_device.ensure_device_serial("adb.exe", None, env_serial=None, runner=runner_f),
            "USB123",
        )
        self.assertEqual(calls_f, [["adb.exe", "devices", "-l"]])

    def test_build_am_start_install_and_scrcpy_commands(self) -> None:
        jump_cmd = live_device.build_am_start_cmd(
            "adb.exe",
            "192.168.43.1:4444",
            "pack_progress",
            theme="pure_black",
            color_spec="2021",
            accent="system",
            color_style="expressive",
            font_scale=1.3,
        )
        self.assertEqual(
            jump_cmd,
            [
                "adb.exe",
                "-s",
                "192.168.43.1:4444",
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                "com.cyanix.pixfold/.MainActivity",
                "--activity-single-top",
                "--activity-clear-top",
                "--es",
                "pixfold_scene",
                "pack_progress",
                "--es",
                "pixfold_theme",
                "pure_black",
                "--es",
                "pixfold_color_spec",
                "2021",
                "--es",
                "pixfold_accent",
                "system",
                "--es",
                "pixfold_color_style",
                "expressive",
                "--ef",
                "pixfold_font_scale",
                "1.30",
            ],
        )

        default_cmd = live_device.build_am_start_cmd(
            "adb.exe",
            "192.168.43.1:4444",
            "home",
        )
        self.assertIn("pixfold_color_style", default_cmd)
        idx = default_cmd.index("pixfold_color_style")
        self.assertEqual(default_cmd[idx + 1], "tonal_spot")

        exit_cmd = live_device.build_am_start_cmd(
            "adb.exe",
            "192.168.43.1:4444",
            "catalog",
            exit_showcase=True,
        )
        self.assertEqual(
            exit_cmd,
            [
                "adb.exe",
                "-s",
                "192.168.43.1:4444",
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                "com.cyanix.pixfold/.MainActivity",
                "--activity-single-top",
                "--activity-clear-top",
                "--ez",
                "pixfold_exit_showcase",
                "true",
            ],
        )

        apk = Path("android/app/build/outputs/apk/debug/app-debug.apk")
        delta_cmd = live_device.build_install_cmd(
            apk,
            "192.168.43.1:4444",
            android_bin="android.exe",
            adb_bin="adb.exe",
            use_delta=True,
        )
        self.assertEqual(
            delta_cmd,
            [
                "android.exe",
                "install",
                f"--apks={apk}",
                "--device=192.168.43.1:4444",
                "--use-delta-install",
            ],
        )

        fallback_cmd = live_device.build_install_cmd(
            apk,
            "192.168.43.1:4444",
            android_bin=None,
            adb_bin="adb.exe",
            use_delta=True,
        )
        self.assertEqual(
            fallback_cmd,
            ["adb.exe", "-s", "192.168.43.1:4444", "install", "-r", str(apk)],
        )

        scrcpy_cmd = live_device.build_scrcpy_cmd(
            "scrcpy.exe",
            "192.168.43.1:4444",
            width=420,
            stay_awake=True,
            turn_screen_off=True,
        )
        self.assertEqual(
            scrcpy_cmd,
            [
                "scrcpy.exe",
                "-s",
                "192.168.43.1:4444",
                "--always-on-top",
                "--window-title",
                "PixFold Live",
                "--window-width",
                "420",
                "--stay-awake",
                "--turn-screen-off",
            ],
        )

    def test_scan_device_watch_fingerprint_and_scenes_cli_and_no_sdcard(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            main_dir = root / "main"
            debug_dir = root / "debug"
            main_dir.mkdir()
            debug_dir.mkdir()
            (main_dir / "Home.kt").write_text("fun a() {}", encoding="utf-8")
            (debug_dir / "Showcase.kt").write_text("fun b() {}", encoding="utf-8")
            ignored = debug_dir / "notes.txt"
            ignored.write_text("ignored", encoding="utf-8")

            fp1 = live_device.scan_device_watch_fingerprint((main_dir, debug_dir))
            self.assertEqual(len(fp1), 2)
            ignored.write_text("changed ignored", encoding="utf-8")
            self.assertEqual(fp1, live_device.scan_device_watch_fingerprint((main_dir, debug_dir)))
            (debug_dir / "Showcase.kt").write_text("fun b() { val v = 1 }", encoding="utf-8")
            self.assertNotEqual(fp1, live_device.scan_device_watch_fingerprint((main_dir, debug_dir)))

        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = live_device.main(["scenes"])
        self.assertEqual(rc, 0)
        out = buf.getvalue()
        self.assertIn("pack_progress", out)
        self.assertIn("undo_dialog", out)
        self.assertIn("--style", out)
        self.assertIn("tonal_spot", out)

        script_src = (SCRIPTS_DIR / "live_device.py").read_text(encoding="utf-8")
        self.assertNotIn("/sdcard", script_src)
        self.assertNotIn("/storage/emulated", script_src)

    def test_cli_parser_supports_style_option(self) -> None:
        recorded_cmds: list[list[str]] = []

        def fake_run(cmd: list[str], **_: object) -> subprocess.CompletedProcess[str]:
            recorded_cmds.append(list(cmd))
            return subprocess.CompletedProcess(args=cmd, returncode=0, stdout="", stderr="")

        with (
            mock.patch.object(live_device, "_find_adb", return_value="adb.exe"),
            mock.patch.object(live_device, "_detect_serial", return_value="192.168.43.1:4444"),
            mock.patch.object(live_device, "_find_android_cli", return_value="android.exe"),
            mock.patch.object(live_device, "_run_assemble_debug"),
            mock.patch.object(live_device.subprocess, "run", side_effect=fake_run),
        ):
            self.assertEqual(live_device.main(["jump", "home"]), 0)
            self.assertEqual(live_device.main(["jump", "home", "--style", "vibrant"]), 0)
            self.assertEqual(live_device.main(["push", "settings", "--style", "monochrome"]), 0)

        jump_default, jump_vibrant, _, push_monochrome = recorded_cmds
        self.assertEqual(
            jump_default[jump_default.index("pixfold_color_style") + 1],
            "tonal_spot",
        )
        self.assertEqual(
            jump_vibrant[jump_vibrant.index("pixfold_color_style") + 1],
            "vibrant",
        )
        self.assertEqual(
            push_monochrome[push_monochrome.index("pixfold_color_style") + 1],
            "monochrome",
        )


if __name__ == "__main__":
    unittest.main()
