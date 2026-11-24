# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""PixFold cross-platform live UI preview CLI and local SSE gallery server."""

from __future__ import annotations

import argparse
import hashlib
import http.server
import json
import os
import re
import socketserver
import subprocess
import sys
import threading
import time
import urllib.parse
import webbrowser
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Callable

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from live_preview_ui import GALLERY_HTML  # noqa: E402

REPO_ROOT = SCRIPTS_DIR.parent
ANDROID_DIR = REPO_ROOT / "android"
APP_DIR = ANDROID_DIR / "app"
BASELINE_DIR = APP_DIR / "src" / "test" / "snapshots"
LIVE_DIR = APP_DIR / "build" / "live-preview"
STATUS_FILE = LIVE_DIR / "status.json"
METHODS_FILE = LIVE_DIR / "methods.txt"
LOG_FILE = LIVE_DIR / "last-render.log"
JUNIT_XML = (
    APP_DIR
    / "build"
    / "test-results"
    / "testDebugUnitTest"
    / "TEST-com.cyanix.pixfold.ui.VisualRegressionSnapshotTest.xml"
)
WATCH_DIR = APP_DIR / "src" / "main"
TEST_CLASS = "com.cyanix.pixfold.ui.VisualRegressionSnapshotTest"

PREVIEW_GROUPS: dict[str, tuple[str, ...]] = {
    "home": (
        "homeScreen_light",
        "homeScreen_dark",
        "homeScreen_pureBlack",
    ),
    "settings": (
        "settingsScreen_colorSpec2021",
        "settingsScreen_colorSpec2025",
        "settingsScreen_light",
        "settingsScreen_pureBlack",
        "workspaceListDialog_dark",
    ),
    "chips": (
        "chips_singleSelect_collapsedAndExpanded",
        "chips_twoLevel_seriesPopupOpen",
    ),
    "workflows": (
        "sortPage_dark",
        "libraryPage_dark",
        "metadataPage_light",
        "packPreview_dark",
        "imagePreview_dark",
    ),
    "plan": (
        "bottomBar_workflowAndReport_light",
        "bottomBar_workflowAndReport_dark",
        "renamePlan_dark",
        "executionReport_dark",
    ),
    "dialogs": (
        "workspaceListDialog_dark",
        "undoScopeDialog_dark",
        "undoScopeDialog_pureBlack",
        "packProgressDialog_dark",
    ),
}


def _dedupe_all_methods() -> tuple[str, ...]:
    ordered: list[str] = []
    seen: set[str] = set()
    for group_name in ("home", "chips", "plan", "settings", "dialogs", "workflows"):
        for method in PREVIEW_GROUPS[group_name]:
            if method not in seen:
                seen.add(method)
                ordered.append(method)
    return tuple(ordered)


ALL_METHODS: tuple[str, ...] = _dedupe_all_methods()
PREVIEW_GROUPS["all"] = ALL_METHODS

SNAPSHOT_ENTRIES: tuple[dict[str, object], ...] = (
    {"id": "home_light", "file": "home_light.png", "title": "首页 · 浅色", "group": "home", "groups": ["home"], "method": "homeScreen_light"},
    {"id": "home_dark", "file": "home_dark.png", "title": "首页 · 深色", "group": "home", "groups": ["home"], "method": "homeScreen_dark"},
    {"id": "home_pure_black", "file": "home_pure_black.png", "title": "首页 · 纯黑", "group": "home", "groups": ["home"], "method": "homeScreen_pureBlack"},
    {"id": "home_pure_black_card", "file": "home_pure_black_card.png", "title": "首页卡片 · 纯黑描边放大", "group": "home", "groups": ["home"], "method": "homeScreen_pureBlack"},
    {"id": "chips_single_select_collapsed", "file": "chips_single_select_collapsed.png", "title": "单层芯片 · 3行收口", "group": "chips", "groups": ["chips"], "method": "chips_singleSelect_collapsedAndExpanded"},
    {"id": "chips_single_select_expanded", "file": "chips_single_select_expanded.png", "title": "单层芯片 · 原地展开", "group": "chips", "groups": ["chips"], "method": "chips_singleSelect_collapsedAndExpanded"},
    {"id": "chips_two_level_popup_screen", "file": "chips_two_level_popup_screen.png", "title": "二级芯片 · 整屏浮层", "group": "chips", "groups": ["chips"], "method": "chips_twoLevel_seriesPopupOpen"},
    {"id": "chips_two_level_popup_window", "file": "chips_two_level_popup_window.png", "title": "二级芯片 · 浮层窗口", "group": "chips", "groups": ["chips"], "method": "chips_twoLevel_seriesPopupOpen"},
    {"id": "bottom_bar_plan_light", "file": "bottom_bar_plan_light.png", "title": "底栏与报告 · 浅色", "group": "plan", "groups": ["plan"], "method": "bottomBar_workflowAndReport_light"},
    {"id": "bottom_bar_plan_dark", "file": "bottom_bar_plan_dark.png", "title": "底栏与报告 · 深色", "group": "plan", "groups": ["plan"], "method": "bottomBar_workflowAndReport_dark"},
    {"id": "settings_color_spec_2021", "file": "settings_color_spec_2021.png", "title": "设置 · M3 2021 色板", "group": "settings", "groups": ["settings"], "method": "settingsScreen_colorSpec2021"},
    {"id": "settings_color_spec_2025", "file": "settings_color_spec_2025.png", "title": "设置 · M3E 2025 色板", "group": "settings", "groups": ["settings"], "method": "settingsScreen_colorSpec2025"},
    {"id": "settings_light", "file": "settings_light.png", "title": "设置 · 浅色", "group": "settings", "groups": ["settings"], "method": "settingsScreen_light"},
    {"id": "settings_pure_black", "file": "settings_pure_black.png", "title": "设置 · 纯黑", "group": "settings", "groups": ["settings"], "method": "settingsScreen_pureBlack"},
    {"id": "workspace_list_dialog_dark", "file": "workspace_list_dialog_dark.png", "title": "工作区列表弹窗 · 深色", "group": "settings", "groups": ["settings", "dialogs"], "method": "workspaceListDialog_dark"},
    {"id": "dialog_undo_scope_dark", "file": "dialog_undo_scope_dark.png", "title": "撤销范围弹窗 · 深色", "group": "dialogs", "groups": ["dialogs"], "method": "undoScopeDialog_dark"},
    {"id": "dialog_undo_scope_pure_black", "file": "dialog_undo_scope_pure_black.png", "title": "撤销范围弹窗 · 纯黑", "group": "dialogs", "groups": ["dialogs"], "method": "undoScopeDialog_pureBlack"},
    {"id": "pack_progress_dialog_dark", "file": "pack_progress_dialog_dark.png", "title": "双波浪打包进度弹窗 · 深色", "group": "dialogs", "groups": ["dialogs"], "method": "packProgressDialog_dark"},
    {"id": "sort_page_dark", "file": "sort_page_dark.png", "title": "工作流A · 排序页", "group": "workflows", "groups": ["workflows"], "method": "sortPage_dark"},
    {"id": "library_page_dark", "file": "library_page_dark.png", "title": "工作流B · 书架页", "group": "workflows", "groups": ["workflows"], "method": "libraryPage_dark"},
    {"id": "metadata_page_light", "file": "metadata_page_light.png", "title": "工作流B · 元数据编辑页", "group": "workflows", "groups": ["workflows"], "method": "metadataPage_light"},
    {"id": "pack_preview_dark", "file": "pack_preview_dark.png", "title": "工作流B · 打包预览页", "group": "workflows", "groups": ["workflows"], "method": "packPreview_dark"},
    {"id": "rename_plan_dark", "file": "rename_plan_dark.png", "title": "工作流A · 改名计划页", "group": "plan", "groups": ["plan"], "method": "renamePlan_dark"},
    {"id": "execution_report_dark", "file": "execution_report_dark.png", "title": "工作流A · 执行报告页", "group": "plan", "groups": ["plan"], "method": "executionReport_dark"},
    {"id": "image_preview_dark", "file": "image_preview_dark.png", "title": "全屏看图 · 浮动工具栏", "group": "workflows", "groups": ["workflows"], "method": "imagePreview_dark"},
)

TARGET_PATTERN = re.compile(r"^[A-Za-z0-9_]+$")
SNAPSHOT_NAME_PATTERN = re.compile(r"^[a-z0-9_]+\.png$")


def resolve_target(target: str) -> tuple[str, list[str]]:
    cleaned = target.strip()
    if not cleaned or not TARGET_PATTERN.fullmatch(cleaned):
        raise ValueError(f"Invalid preview target: {target!r}")
    lowered = cleaned.lower()
    if lowered in PREVIEW_GROUPS:
        return lowered, list(PREVIEW_GROUPS[lowered])

    matches = [m for m in ALL_METHODS if lowered in m.lower()]
    if not matches:
        raise ValueError(f"Unknown preview target or method: {target!r}")
    if len(matches) == 1:
        return matches[0], matches
    return cleaned, matches


def safe_snapshot_name(raw_name: str) -> str | None:
    if not raw_name or "/" in raw_name or "\\" in raw_name or ".." in raw_name:
        return None
    if not SNAPSHOT_NAME_PATTERN.fullmatch(raw_name):
        return None
    return raw_name


def is_allowed_origin(origin: str | None, port: int) -> bool:
    if origin is None or origin == "":
        return True
    return origin in {
        f"http://127.0.0.1:{port}",
        f"http://localhost:{port}",
    }


def parse_junit_result(xml_path: Path) -> dict[str, int]:
    if not xml_path.is_file():
        raise RuntimeError(f"Missing JUnit XML report: {xml_path}")
    tree = ET.parse(xml_path)
    root = tree.getroot()
    suite = root if root.tag == "testsuite" else root.find("testsuite")
    if suite is None:
        raise RuntimeError(f"Missing <testsuite> in JUnit XML: {xml_path}")
    tests = int(suite.attrib.get("tests", "0"))
    skipped = int(suite.attrib.get("skipped", "0"))
    failures = int(suite.attrib.get("failures", "0"))
    errors = int(suite.attrib.get("errors", "0"))
    executed = tests - skipped
    if executed <= 0:
        raise RuntimeError(f"JUnit XML reported 0 executed tests (tests={tests}, skipped={skipped})")
    if failures > 0 or errors > 0:
        raise RuntimeError(f"JUnit XML reported test failures (failures={failures}, errors={errors})")
    return {
        "tests": tests,
        "skipped": skipped,
        "executed": executed,
        "failures": failures,
        "errors": errors,
    }


def atomic_write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_name(f".{path.name}.{os.getpid()}.{threading.get_ident()}.tmp")
    tmp_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp_path, path)


def read_json_safe(path: Path) -> dict:
    for _ in range(3):
        if not path.is_file():
            return {"state": "idle"}
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (PermissionError, json.JSONDecodeError, OSError):
            time.sleep(0.03)
    return {"state": "idle"}


def _file_version_token(path: Path) -> str | None:
    if not path.is_file():
        return None
    stat = path.stat()
    return f"{stat.st_mtime_ns}-{stat.st_size}"


def build_state_payload(baseline_dir: Path, live_dir: Path, status_file: Path) -> dict:
    status = read_json_safe(status_file)
    snapshots = []
    for entry in SNAPSHOT_ENTRIES:
        file_name = str(entry["file"])
        base_ver = _file_version_token(baseline_dir / file_name)
        live_ver = _file_version_token(live_dir / file_name)
        snapshots.append(
            {
                "id": entry["id"],
                "file": file_name,
                "title": entry["title"],
                "group": entry["group"],
                "groups": entry["groups"],
                "method": entry["method"],
                "has_baseline": base_ver is not None,
                "has_live": live_ver is not None,
                "baseline_url": f"/img/baseline/{file_name}?v={base_ver}" if base_ver else None,
                "live_url": f"/img/live/{file_name}?v={live_ver}" if live_ver else None,
            }
        )
    return {"status": status, "snapshots": snapshots}


def scan_watch_fingerprint(watch_root: Path) -> tuple[tuple[str, int, int], ...]:
    if not watch_root.exists():
        return ()
    items: list[tuple[str, int, int]] = []
    for path in sorted(watch_root.rglob("*")):
        if path.is_file() and path.suffix.lower() in {".kt", ".xml"}:
            stat = path.stat()
            items.append((str(path.relative_to(watch_root)), stat.st_mtime_ns, stat.st_size))
    return tuple(items)


def _live_fingerprint(live_dir: Path, status_file: Path) -> str:
    h = hashlib.sha256()
    status_tok = _file_version_token(status_file) or "none"
    h.update(status_tok.encode("utf-8"))
    if live_dir.is_dir():
        for path in sorted(live_dir.glob("*.png")):
            tok = _file_version_token(path) or ""
            h.update(f"|{path.name}:{tok}".encode("utf-8"))
    return h.hexdigest()


def _tail_lines(path: Path, count: int = 60) -> str:
    if not path.is_file():
        return ""
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    return "\n".join(lines[-count:])


def run_render(
    target: str = "all",
    *,
    android_dir: Path = ANDROID_DIR,
    live_dir: Path = LIVE_DIR,
    status_file: Path = STATUS_FILE,
    methods_file: Path = METHODS_FILE,
    log_file: Path = LOG_FILE,
    junit_xml: Path = JUNIT_XML,
    render_lock: threading.Lock | None = None,
) -> int:
    resolved_name, methods = resolve_target(target)
    lock = render_lock or threading.Lock()
    with lock:
        live_dir.mkdir(parents=True, exist_ok=True)
        if resolved_name == "all":
            methods_file.write_text("", encoding="utf-8")
        else:
            methods_file.write_text("\n".join(methods) + "\n", encoding="utf-8")

        start_epoch = time.time()
        started_mono = time.monotonic()
        atomic_write_json(
            status_file,
            {
                "state": "rendering",
                "target": resolved_name,
                "methods": methods,
                "started_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
            },
        )

        cmd = [
            "cmd.exe",
            "/c",
            "run-gradle.bat",
            ":app:recordRoborazziDebug",
            "--tests",
            TEST_CLASS,
            "-Dpixfold.preview.outDir=build/live-preview",
        ]
        env = os.environ.copy()
        env["MSYS_NO_PATHCONV"] = "1"
        try:
            with log_file.open("w", encoding="utf-8", errors="replace") as log_fp:
                proc = subprocess.run(
                    cmd,
                    cwd=str(android_dir),
                    env=env,
                    stdin=subprocess.DEVNULL,
                    stdout=log_fp,
                    stderr=subprocess.STDOUT,
                    check=False,
                )
            elapsed = round(time.monotonic() - started_mono, 2)
            if proc.returncode != 0:
                raise RuntimeError(f"Gradle exited with code {proc.returncode}")
            if not junit_xml.is_file() or junit_xml.stat().st_mtime < start_epoch - 1.0:
                raise RuntimeError(f"JUnit XML was not updated during render: {junit_xml}")
            junit = parse_junit_result(junit_xml)
            atomic_write_json(
                status_file,
                {
                    "state": "ok",
                    "target": resolved_name,
                    "methods": methods,
                    "elapsed_sec": elapsed,
                    "executed": junit["executed"],
                    "skipped": junit["skipped"],
                    "tests": junit["tests"],
                    "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
                    "error": "",
                    "error_tail": "",
                },
            )
            return 0
        except Exception as exc:
            elapsed = round(time.monotonic() - started_mono, 2)
            tail = _tail_lines(log_file, 60)
            atomic_write_json(
                status_file,
                {
                    "state": "error",
                    "target": resolved_name,
                    "methods": methods,
                    "elapsed_sec": elapsed,
                    "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
                    "error": str(exc),
                    "error_tail": tail,
                },
            )
            print(f"[live-preview] 渲染失败 ({resolved_name}): {exc}", file=sys.stderr)
            if tail:
                print(tail, file=sys.stderr)
            return 1


class _ThreadingHttpServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def create_server(
    host: str = "127.0.0.1",
    port: int = 4173,
    *,
    baseline_dir: Path = BASELINE_DIR,
    live_dir: Path = LIVE_DIR,
    status_file: Path = STATUS_FILE,
    render_lock: threading.Lock | None = None,
    render_fn: Callable[[str], int] | None = None,
    stop_event: threading.Event | None = None,
) -> _ThreadingHttpServer:
    shared_lock = render_lock or threading.Lock()
    shared_stop = stop_event or threading.Event()
    effective_render = render_fn or (
        lambda tgt: run_render(
            tgt,
            live_dir=live_dir,
            status_file=status_file,
            render_lock=shared_lock,
        )
    )

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format: str, *args: object) -> None:
            return

        def _send_bytes(self, status: int, content_type: str, body: bytes) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _send_json(self, status: int, data: dict) -> None:
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self._send_bytes(status, "application/json; charset=utf-8", body)

        def do_GET(self) -> None:
            parsed = urllib.parse.urlparse(self.path)
            path = parsed.path

            if path == "/":
                self._send_bytes(200, "text/html; charset=utf-8", GALLERY_HTML.encode("utf-8"))
                return

            if path == "/api/state":
                payload = build_state_payload(baseline_dir, live_dir, status_file)
                self._send_json(200, payload)
                return

            if path == "/events":
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "keep-alive")
                self.end_headers()
                last_fp = _live_fingerprint(live_dir, status_file)
                last_ping = time.monotonic()
                try:
                    self.wfile.write(b"event: update\ndata: {}\n\n")
                    self.wfile.flush()
                    while not shared_stop.is_set():
                        time.sleep(0.3)
                        fp = _live_fingerprint(live_dir, status_file)
                        if fp != last_fp:
                            last_fp = fp
                            last_ping = time.monotonic()
                            msg = f"event: update\ndata: {json.dumps({'fp': fp})}\n\n".encode("utf-8")
                            self.wfile.write(msg)
                            self.wfile.flush()
                        elif time.monotonic() - last_ping >= 15.0:
                            last_ping = time.monotonic()
                            self.wfile.write(b": keepalive\n\n")
                            self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, OSError):
                    return
                return

            for prefix, root_dir in (("/img/baseline/", baseline_dir), ("/img/live/", live_dir)):
                if path.startswith(prefix):
                    raw_name = urllib.parse.unquote(path[len(prefix) :])
                    safe_name = safe_snapshot_name(raw_name)
                    if not safe_name:
                        self._send_json(400, {"error": "invalid snapshot name"})
                        return
                    img_path = root_dir / safe_name
                    if not img_path.is_file():
                        self._send_json(404, {"error": "snapshot not found"})
                        return
                    self._send_bytes(200, "image/png", img_path.read_bytes())
                    return

            self._send_json(404, {"error": "not found"})

        def do_POST(self) -> None:
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path != "/api/render":
                self._send_json(404, {"error": "not found"})
                return

            actual_port = self.server.server_address[1]
            origin = self.headers.get("Origin")
            if not is_allowed_origin(origin, actual_port):
                self._send_json(403, {"error": "forbidden origin"})
                return

            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                self._send_json(400, {"error": "invalid content-length"})
                return
            if length > 4096:
                self._send_json(400, {"error": "body too large"})
                return

            raw_body = self.rfile.read(length) if length > 0 else b"{}"
            try:
                body = json.loads(raw_body.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                self._send_json(400, {"error": "invalid json"})
                return
            target = str(body.get("target", "all")) if isinstance(body, dict) else "all"
            try:
                resolved, _ = resolve_target(target)
            except ValueError as exc:
                self._send_json(400, {"error": str(exc)})
                return

            if shared_lock.locked():
                self._send_json(409, {"error": "render already in progress"})
                return

            threading.Thread(target=effective_render, args=(resolved,), daemon=True).start()
            self._send_json(202, {"accepted": True, "target": resolved})

    return _ThreadingHttpServer((host, port), Handler)


def _cmd_serve(port: int, open_browser: bool) -> int:
    stop_event = threading.Event()
    server = create_server("127.0.0.1", port, stop_event=stop_event)
    actual_port = server.server_address[1]
    url = f"http://127.0.0.1:{actual_port}"
    print(f"[live-preview] 画廊服务已启动: {url}")
    if open_browser:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop_event.set()
        server.shutdown()
        server.server_close()
    return 0


def _cmd_watch(target: str, port: int, debounce_sec: float, open_browser: bool, no_initial: bool) -> int:
    resolved_name, _ = resolve_target(target)
    stop_event = threading.Event()
    render_lock = threading.Lock()
    server = create_server(
        "127.0.0.1",
        port,
        render_lock=render_lock,
        stop_event=stop_event,
    )
    actual_port = server.server_address[1]
    url = f"http://127.0.0.1:{actual_port}"
    srv_thread = threading.Thread(target=server.serve_forever, daemon=True)
    srv_thread.start()
    print(f"[live-preview] 监听模式已启动 ({resolved_name}): {url}")
    if open_browser:
        webbrowser.open(url)

    try:
        if not no_initial:
            run_render(resolved_name, render_lock=render_lock)
        last_fp = scan_watch_fingerprint(WATCH_DIR)
        pending_since: float | None = None
        while not stop_event.is_set():
            time.sleep(0.4)
            cur_fp = scan_watch_fingerprint(WATCH_DIR)
            if cur_fp != last_fp:
                last_fp = cur_fp
                pending_since = time.monotonic()
            elif pending_since is not None and (time.monotonic() - pending_since) >= debounce_sec:
                pending_since = None
                run_render(resolved_name, render_lock=render_lock)
                last_fp = scan_watch_fingerprint(WATCH_DIR)
    except KeyboardInterrupt:
        pass
    finally:
        stop_event.set()
        server.shutdown()
        server.server_close()
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="PixFold 跨平台实时 UI 预览工具")
    sub = parser.add_subparsers(dest="command", required=True)

    p_serve = sub.add_parser("serve", help="启动本地 SSE 预览画廊服务")
    p_serve.add_argument("--port", type=int, default=4173)
    p_serve.add_argument("--open", action="store_true", help="自动在默认浏览器打开")

    p_render = sub.add_parser("render", help="增量渲染指定页面组或方法")
    p_render.add_argument("target", nargs="?", default="all", help="分组 (all/home/settings/chips/workflows/plan/dialogs) 或方法名子串")

    p_watch = sub.add_parser("watch", help="启动画廊并监听 Kotlin/XML 修改自动重渲")
    p_watch.add_argument("target", nargs="?", default="all")
    p_watch.add_argument("--port", type=int, default=4173)
    p_watch.add_argument("--debounce", type=float, default=1.2)
    p_watch.add_argument("--open", action="store_true")
    p_watch.add_argument("--no-initial", action="store_true")

    args = parser.parse_args(argv)
    if args.command == "serve":
        return _cmd_serve(args.port, args.open)
    if args.command == "render":
        rc = run_render(args.target)
        if rc == 0:
            st = read_json_safe(STATUS_FILE)
            print(
                f"[live-preview] 渲染完成: target={st.get('target')} "
                f"executed={st.get('executed')} elapsed={st.get('elapsed_sec')}s"
            )
        return rc
    if args.command == "watch":
        return _cmd_watch(args.target, args.port, args.debounce, args.open, args.no_initial)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
