# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Unit and HTTP integration tests for scripts/live_preview.py."""

from __future__ import annotations

import http.client
import json
import sys
import tempfile
import threading
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import live_preview  # noqa: E402


class LivePreviewCoreTest(unittest.TestCase):
    def test_resolve_target_groups_and_method_substring_and_rejection(self) -> None:
        self.assertEqual(len(live_preview.PREVIEW_GROUPS["all"]), 22)
        self.assertEqual(len(live_preview.SNAPSHOT_ENTRIES), 25)

        for group in ("all", "home", "settings", "chips", "workflows", "plan", "dialogs"):
            resolved_name, methods = live_preview.resolve_target(group)
            self.assertEqual(resolved_name, group)
            self.assertEqual(methods, list(live_preview.PREVIEW_GROUPS[group]))

        resolved_upper, methods_upper = live_preview.resolve_target("HOME")
        self.assertEqual(resolved_upper, "home")
        self.assertEqual(methods_upper, list(live_preview.PREVIEW_GROUPS["home"]))

        single_name, single_methods = live_preview.resolve_target("packProgress")
        self.assertEqual(single_name, "packProgressDialog_dark")
        self.assertEqual(single_methods, ["packProgressDialog_dark"])

        multi_name, multi_methods = live_preview.resolve_target("undoScope")
        self.assertEqual(multi_name, "undoScope")
        self.assertEqual(
            multi_methods,
            ["undoScopeDialog_dark", "undoScopeDialog_pureBlack"],
        )

        for bad in ("", "   ", "home --tests foo", "a;rm", "../home", "notExistMethod"):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    live_preview.resolve_target(bad)

    def test_parse_junit_result_rejects_missing_zero_executed_and_failures(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            missing = root / "missing.xml"
            with self.assertRaises(RuntimeError):
                live_preview.parse_junit_result(missing)

            ok_xml = root / "ok.xml"
            ok_xml.write_text(
                '<testsuite name="VisualRegressionSnapshotTest" tests="22" skipped="19" failures="0" errors="0" time="4.2"/>',
                encoding="utf-8",
            )
            parsed = live_preview.parse_junit_result(ok_xml)
            self.assertEqual(
                parsed,
                {"tests": 22, "skipped": 19, "executed": 3, "failures": 0, "errors": 0},
            )

            zero_exec = root / "zero.xml"
            zero_exec.write_text(
                '<testsuite name="VisualRegressionSnapshotTest" tests="22" skipped="22" failures="0" errors="0"/>',
                encoding="utf-8",
            )
            with self.assertRaises(RuntimeError):
                live_preview.parse_junit_result(zero_exec)

            zero_tests = root / "empty.xml"
            zero_tests.write_text(
                '<testsuite name="VisualRegressionSnapshotTest" tests="0" skipped="0" failures="0" errors="0"/>',
                encoding="utf-8",
            )
            with self.assertRaises(RuntimeError):
                live_preview.parse_junit_result(zero_tests)

            failed = root / "failed.xml"
            failed.write_text(
                '<testsuite name="VisualRegressionSnapshotTest" tests="3" skipped="0" failures="1" errors="0"/>',
                encoding="utf-8",
            )
            with self.assertRaises(RuntimeError):
                live_preview.parse_junit_result(failed)

            errored = root / "errored.xml"
            errored.write_text(
                '<testsuite name="VisualRegressionSnapshotTest" tests="3" skipped="0" failures="0" errors="1"/>',
                encoding="utf-8",
            )
            with self.assertRaises(RuntimeError):
                live_preview.parse_junit_result(errored)

    def test_safe_snapshot_name_blocks_traversal_and_non_png(self) -> None:
        self.assertEqual(live_preview.safe_snapshot_name("home_dark.png"), "home_dark.png")
        self.assertEqual(
            live_preview.safe_snapshot_name("chips_two_level_popup_window.png"),
            "chips_two_level_popup_window.png",
        )
        for bad in (
            "",
            "../evil.png",
            "..\\evil.png",
            "sub/home_dark.png",
            "home_dark.jpg",
            "HOME_DARK.png",
            "home-dark.png",
            ".png",
        ):
            with self.subTest(bad=bad):
                self.assertIsNone(live_preview.safe_snapshot_name(bad))

    def test_is_allowed_origin(self) -> None:
        self.assertTrue(live_preview.is_allowed_origin(None, 4173))
        self.assertTrue(live_preview.is_allowed_origin("http://127.0.0.1:4173", 4173))
        self.assertTrue(live_preview.is_allowed_origin("http://localhost:4173", 4173))
        self.assertFalse(live_preview.is_allowed_origin("http://127.0.0.1:9999", 4173))
        self.assertFalse(live_preview.is_allowed_origin("https://evil.example", 4173))
        self.assertFalse(live_preview.is_allowed_origin("http://localhost.evil.example:4173", 4173))

    def test_build_state_payload_tracks_live_and_baseline(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            baseline = root / "snapshots"
            live = root / "live"
            status_file = live / "status.json"
            baseline.mkdir(parents=True)
            live.mkdir(parents=True)

            (baseline / "home_dark.png").write_bytes(b"PNG_BASE_DARK")
            (live / "home_dark.png").write_bytes(b"PNG_LIVE_DARK")
            (baseline / "home_light.png").write_bytes(b"PNG_BASE_LIGHT")
            live_preview.atomic_write_json(
                status_file,
                {"state": "ok", "target": "home", "elapsed_sec": 9.8, "executed": 3},
            )

            payload = live_preview.build_state_payload(baseline, live, status_file)
            self.assertEqual(payload["status"]["state"], "ok")
            self.assertEqual(len(payload["snapshots"]), 25)

            by_id = {item["id"]: item for item in payload["snapshots"]}
            dark = by_id["home_dark"]
            self.assertTrue(dark["has_baseline"])
            self.assertTrue(dark["has_live"])
            self.assertTrue(dark["baseline_url"].startswith("/img/baseline/home_dark.png?v="))
            self.assertTrue(dark["live_url"].startswith("/img/live/home_dark.png?v="))

            light = by_id["home_light"]
            self.assertTrue(light["has_baseline"])
            self.assertFalse(light["has_live"])
            self.assertIsNone(light["live_url"])

    def test_scan_watch_fingerprint_filters_kt_and_xml_only(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            kt_file = root / "HomeScreen.kt"
            xml_file = root / "strings.xml"
            txt_file = root / "notes.txt"
            kt_file.write_text("class A", encoding="utf-8")
            xml_file.write_text("<resources/>", encoding="utf-8")
            txt_file.write_text("ignored", encoding="utf-8")

            fp1 = live_preview.scan_watch_fingerprint(root)
            self.assertEqual(len(fp1), 2)

            txt_file.write_text("changed ignored file", encoding="utf-8")
            fp2 = live_preview.scan_watch_fingerprint(root)
            self.assertEqual(fp1, fp2)

            kt_file.write_text("class A { val x = 1 }", encoding="utf-8")
            fp3 = live_preview.scan_watch_fingerprint(root)
            self.assertNotEqual(fp1, fp3)

    def test_http_server_endpoints_and_security(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            baseline = root / "snapshots"
            live = root / "live"
            status_file = live / "status.json"
            baseline.mkdir(parents=True)
            live.mkdir(parents=True)

            base_bytes = b"\x89PNG\r\n\x1a\nBASE"
            live_bytes = b"\x89PNG\r\n\x1a\nLIVE"
            (baseline / "home_dark.png").write_bytes(base_bytes)
            (live / "home_dark.png").write_bytes(live_bytes)

            render_lock = threading.Lock()
            server = live_preview.create_server(
                host="127.0.0.1",
                port=0,
                baseline_dir=baseline,
                live_dir=live,
                status_file=status_file,
                render_lock=render_lock,
                render_fn=lambda target: 0,
            )
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)

                conn.request("GET", "/")
                resp = conn.getresponse()
                html = resp.read().decode("utf-8")
                self.assertEqual(resp.status, 200)
                self.assertIn('closedby="any"', html)
                self.assertIn("overscroll-behavior: contain", html)
                self.assertIn("color-scheme: light dark", html)

                conn.request("GET", "/api/state")
                resp = conn.getresponse()
                state_json = json.loads(resp.read().decode("utf-8"))
                self.assertEqual(resp.status, 200)
                self.assertEqual(len(state_json["snapshots"]), 25)

                conn.request("GET", "/img/baseline/home_dark.png")
                resp = conn.getresponse()
                self.assertEqual(resp.status, 200)
                self.assertEqual(resp.read(), base_bytes)

                conn.request("GET", "/img/live/home_dark.png")
                resp = conn.getresponse()
                self.assertEqual(resp.status, 200)
                self.assertEqual(resp.read(), live_bytes)

                conn.request("GET", "/img/live/../evil.png")
                resp = conn.getresponse()
                resp.read()
                self.assertIn(resp.status, (400, 404))

                conn.request(
                    "POST",
                    "/api/render",
                    body=json.dumps({"target": "home"}),
                    headers={
                        "Content-Type": "application/json",
                        "Origin": "https://evil.example",
                    },
                )
                resp = conn.getresponse()
                resp.read()
                self.assertEqual(resp.status, 403)

                conn.request(
                    "POST",
                    "/api/render",
                    body=json.dumps({"target": "bad target"}),
                    headers={
                        "Content-Type": "application/json",
                        "Origin": f"http://127.0.0.1:{port}",
                    },
                )
                resp = conn.getresponse()
                resp.read()
                self.assertEqual(resp.status, 400)

                render_lock.acquire()
                try:
                    conn.request(
                        "POST",
                        "/api/render",
                        body=json.dumps({"target": "home"}),
                        headers={
                            "Content-Type": "application/json",
                            "Origin": f"http://127.0.0.1:{port}",
                        },
                    )
                    resp = conn.getresponse()
                    resp.read()
                    self.assertEqual(resp.status, 409)
                finally:
                    render_lock.release()
                conn.close()
            finally:
                server.shutdown()
                server.server_close()


if __name__ == "__main__":
    unittest.main()
