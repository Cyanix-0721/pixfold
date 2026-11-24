# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""PixFold cross-platform real-device scrcpy mirror, delta install, and scene-jump CLI."""

from __future__ import annotations

import argparse
from collections.abc import Callable
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPTS_DIR.parent
ANDROID_DIR = REPO_ROOT / "android"
APP_DIR = ANDROID_DIR / "app"
WATCH_DIRS: tuple[Path, ...] = (
    APP_DIR / "src" / "main",
    APP_DIR / "src" / "debug",
)
REL_DEBUG_APK = Path("app/build/outputs/apk/debug/app-debug.apk")
APP_PACKAGE = "com.cyanix.pixfold"
MAIN_ACTIVITY = f"{APP_PACKAGE}/.MainActivity"

SCENES: tuple[tuple[str, str], ...] = (
    ("catalog", "场景目录（含快捷外观切换）"),
    ("home", "首页（工作流大卡与工作区条）"),
    ("settings", "设置页（M3E 分段与色板）"),
    ("workspace_dialog", "工作区列表弹窗（高斯模糊）"),
    ("chips", "单层芯片瀑布流（3行收口/展开）"),
    ("chips_two_level", "二级系列芯片与顶点浮层"),
    ("sort", "工作流A · 排序与预览"),
    ("library", "工作流B · 书架页"),
    ("metadata", "工作流B · 元数据编辑页"),
    ("pack_preview", "工作流B · 打包预览页"),
    ("pack_progress", "双波浪打包进度弹窗"),
    ("rename_plan", "工作流A · 改名计划页"),
    ("execution_report", "工作流A · 执行报告页"),
    ("undo_dialog", "撤销范围弹窗（SoftBurst + 模糊）"),
    ("image_preview", "全屏大图与活力浮动工具栏"),
)
SCENE_IDS: frozenset[str] = frozenset(item[0] for item in SCENES)

VALID_THEMES: frozenset[str] = frozenset({"dark", "light", "pure_black", "system"})
VALID_COLOR_SPECS: frozenset[str] = frozenset({"2025", "2021"})
VALID_ACCENTS: frozenset[str] = frozenset({"default", "system", "blue", "green", "purple", "orange", "pink"})
MIN_FONT_SCALE = 0.85
MAX_FONT_SCALE = 2.0

DEFAULT_WIRELESS_SERIAL = "192.168.43.1:4444"
SERIAL_ARG_HELP = (
    "目标设备序列号（省略时优先使用唯一在线设备，"
    f"否则默认 {DEFAULT_WIRELESS_SERIAL}；host:port 未在线时自动 adb connect）"
)
SERIAL_PATTERN = re.compile(r"^[A-Za-z0-9._:-]+$")


def validate_scene(raw: str) -> str:
    cleaned = raw.strip().lower()
    if cleaned not in SCENE_IDS:
        supported = ", ".join(item[0] for item in SCENES)
        raise ValueError(f"Unknown scene {raw!r}; supported: {supported}")
    return cleaned


def validate_options(
    *,
    theme: str = "dark",
    color_spec: str = "2025",
    accent: str = "default",
    font_scale: float = 1.0,
) -> dict[str, object]:
    norm_theme = theme.strip().lower()
    if norm_theme not in VALID_THEMES:
        raise ValueError(f"Invalid theme {theme!r}; expected one of {sorted(VALID_THEMES)}")

    norm_spec = color_spec.strip()
    if norm_spec not in VALID_COLOR_SPECS:
        raise ValueError(f"Invalid color_spec {color_spec!r}; expected one of {sorted(VALID_COLOR_SPECS)}")

    norm_accent = accent.strip().lower()
    if norm_accent not in VALID_ACCENTS:
        raise ValueError(f"Invalid accent {accent!r}; expected one of {sorted(VALID_ACCENTS)}")

    if not (MIN_FONT_SCALE <= font_scale <= MAX_FONT_SCALE):
        raise ValueError(
            f"font_scale={font_scale} out of range [{MIN_FONT_SCALE}, {MAX_FONT_SCALE}]"
        )

    return {
        "theme": norm_theme,
        "color_spec": norm_spec,
        "accent": norm_accent,
        "font_scale": round(float(font_scale), 2),
    }


def validate_serial(raw: str) -> str:
    cleaned = raw.strip()
    if (
        not cleaned
        or ".." in cleaned
        or "/" in cleaned
        or "\\" in cleaned
        or not SERIAL_PATTERN.fullmatch(cleaned)
    ):
        raise ValueError(f"Invalid device serial: {raw!r}")
    return cleaned


def is_tcpip_serial(serial: str) -> bool:
    cleaned = serial.strip()
    host, sep, port = cleaned.rpartition(":")
    if sep != ":" or not host or ":" in host or not (port.isascii() and port.isdigit()):
        return False
    port_num = int(port)
    return 1 <= port_num <= 65535


def parse_adb_devices(output: str) -> list[dict[str, str]]:
    devices: list[dict[str, str]] = []
    for raw_line in output.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("*") or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        serial, state = parts[0], parts[1]
        if state != "device":
            continue
        detail = " ".join(parts[2:])
        devices.append({"serial": serial, "state": state, "detail": detail})
    return devices


def resolve_serial(
    explicit_serial: str | None,
    env_serial: str | None,
    online_devices: list[dict[str, str]],
    *,
    default_serial: str | None = DEFAULT_WIRELESS_SERIAL,
) -> str:
    if explicit_serial and explicit_serial.strip():
        return validate_serial(explicit_serial)
    if env_serial and env_serial.strip():
        return validate_serial(env_serial)
    if len(online_devices) == 1:
        return validate_serial(online_devices[0]["serial"])
    if len(online_devices) > 1:
        ids = ", ".join(d["serial"] for d in online_devices)
        raise RuntimeError(f"检测到多台在线设备 ({ids})；请通过 --serial 或 ADB_SERIAL 明确指定目标设备。")
    if default_serial and default_serial.strip():
        return validate_serial(default_serial)
    raise RuntimeError(
        "未检测到在线 Android 设备；请先在获得当轮授权后连接 USB 或执行 adb connect，"
        "或显式传入 --serial。"
    )


def build_am_start_cmd(
    adb_bin: str,
    serial: str,
    scene: str,
    *,
    theme: str = "dark",
    color_spec: str = "2025",
    accent: str = "default",
    font_scale: float = 1.0,
    exit_showcase: bool = False,
) -> list[str]:
    clean_serial = validate_serial(serial)
    base = [
        adb_bin,
        "-s",
        clean_serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        MAIN_ACTIVITY,
        "--activity-single-top",
        "--activity-clear-top",
    ]
    if exit_showcase:
        return [*base, "--ez", "pixfold_exit_showcase", "true"]

    clean_scene = validate_scene(scene)
    opts = validate_options(
        theme=theme,
        color_spec=color_spec,
        accent=accent,
        font_scale=font_scale,
    )
    return [
        *base,
        "--es",
        "pixfold_scene",
        clean_scene,
        "--es",
        "pixfold_theme",
        str(opts["theme"]),
        "--es",
        "pixfold_color_spec",
        str(opts["color_spec"]),
        "--es",
        "pixfold_accent",
        str(opts["accent"]),
        "--ef",
        "pixfold_font_scale",
        f"{float(opts['font_scale']):.2f}",
    ]


def build_install_cmd(
    apk_path: Path,
    serial: str,
    *,
    android_bin: str | None,
    adb_bin: str,
    use_delta: bool = True,
) -> list[str]:
    clean_serial = validate_serial(serial)
    if use_delta and android_bin:
        return [
            android_bin,
            "install",
            f"--apks={apk_path}",
            f"--device={clean_serial}",
            "--use-delta-install",
        ]
    return [adb_bin, "-s", clean_serial, "install", "-r", str(apk_path)]


def build_scrcpy_cmd(
    scrcpy_bin: str,
    serial: str,
    *,
    width: int = 420,
    stay_awake: bool = False,
    turn_screen_off: bool = False,
    title: str = "PixFold Live",
) -> list[str]:
    clean_serial = validate_serial(serial)
    if width < 200 or width > 4096:
        raise ValueError(f"Invalid scrcpy window width: {width}")
    cmd = [
        scrcpy_bin,
        "-s",
        clean_serial,
        "--always-on-top",
        "--window-title",
        title,
        "--window-width",
        str(width),
    ]
    if stay_awake:
        cmd.append("--stay-awake")
    if turn_screen_off:
        cmd.append("--turn-screen-off")
    return cmd


def scan_device_watch_fingerprint(
    watch_dirs: tuple[Path, ...] = WATCH_DIRS,
) -> tuple[tuple[str, int, int], ...]:
    items: list[tuple[str, int, int]] = []
    for root in watch_dirs:
        if not root.exists():
            continue
        for path in sorted(root.rglob("*")):
            if path.is_file() and path.suffix.lower() in {".kt", ".xml"}:
                stat = path.stat()
                items.append((f"{root.name}/{path.relative_to(root)}", stat.st_mtime_ns, stat.st_size))
    return tuple(items)


def _resolve_tool(names: tuple[str, ...], extra_paths: tuple[Path, ...] = ()) -> str | None:
    for name in names:
        hit = shutil.which(name)
        if hit:
            return hit
    for candidate in extra_paths:
        if candidate.is_file():
            return str(candidate)
    return None


def _sdk_roots() -> list[Path]:
    roots: list[Path] = []
    for env_key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        val = os.environ.get(env_key)
        if val:
            roots.append(Path(val))
    local_app = os.environ.get("LOCALAPPDATA")
    if local_app:
        roots.append(Path(local_app) / "Android" / "Sdk")
    user_profile = os.environ.get("USERPROFILE")
    if user_profile:
        roots.append(Path(user_profile) / "AppData" / "Local" / "Android" / "Sdk")
    mnt_default = Path("/mnt/c/Users/Administrator/AppData/Local/Android/Sdk")
    if mnt_default.exists():
        roots.append(mnt_default)
    return roots


def _find_adb() -> str:
    extras = tuple(root / "platform-tools" / "adb.exe" for root in _sdk_roots())
    tool = _resolve_tool(("adb.exe", "adb"), extras)
    if not tool:
        raise RuntimeError("未找到 adb / adb.exe，请检查 Android SDK platform-tools 配置")
    return tool


def _find_android_cli() -> str | None:
    extras = tuple(root / "cmdline-tools" / "latest" / "bin" / "android.exe" for root in _sdk_roots())
    return _resolve_tool(("android.exe", "android"), extras)


def _find_scrcpy() -> str:
    extras: list[Path] = []
    user_profile = os.environ.get("USERPROFILE")
    if user_profile:
        extras.append(Path(user_profile) / "scoop" / "shims" / "scrcpy.exe")
    extras.append(Path("/mnt/c/Users/Administrator/scoop/shims/scrcpy.exe"))
    tool = _resolve_tool(("scrcpy.exe", "scrcpy"), tuple(extras))
    if not tool:
        raise RuntimeError("未找到 scrcpy / scrcpy.exe")
    return tool


def _run_adb_capture(cmd: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdin=subprocess.DEVNULL,
        check=False,
    )


def ensure_device_serial(
    adb_bin: str,
    explicit_serial: str | None,
    *,
    env_serial: str | None = None,
    default_serial: str | None = DEFAULT_WIRELESS_SERIAL,
    runner: Callable[[list[str]], subprocess.CompletedProcess[str]] = _run_adb_capture,
) -> str:
    devices_proc = runner([adb_bin, "devices", "-l"])
    if devices_proc.returncode != 0:
        raise RuntimeError(f"adb devices 执行失败: {devices_proc.stderr.strip()}")
    online = parse_adb_devices(devices_proc.stdout)
    target = resolve_serial(
        explicit_serial,
        env_serial,
        online,
        default_serial=default_serial,
    )
    if any(d["serial"] == target for d in online) or not is_tcpip_serial(target):
        return target

    connect_proc = runner([adb_bin, "connect", target])
    after_proc = runner([adb_bin, "devices", "-l"])
    if after_proc.returncode != 0:
        raise RuntimeError(f"adb devices 执行失败: {after_proc.stderr.strip()}")
    online_after = parse_adb_devices(after_proc.stdout)
    if any(d["serial"] == target for d in online_after):
        return target

    detail = "\n".join(
        part.strip()
        for part in (connect_proc.stdout, connect_proc.stderr)
        if part and part.strip()
    )
    raise RuntimeError(
        f"自动执行 adb connect {target} 后设备仍未在线: {detail or f'exit={connect_proc.returncode}'}"
    )


def _detect_serial(adb_bin: str, explicit_serial: str | None) -> str:
    return ensure_device_serial(
        adb_bin,
        explicit_serial,
        env_serial=os.environ.get("ADB_SERIAL"),
    )


def _run_assemble_debug() -> None:
    env = os.environ.copy()
    env["MSYS_NO_PATHCONV"] = "1"
    proc = subprocess.run(
        ["cmd.exe", "/c", "run-gradle.bat", ":app:assembleDebug"],
        cwd=str(ANDROID_DIR),
        env=env,
        stdin=subprocess.DEVNULL,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"assembleDebug 构建失败 (exit={proc.returncode})")


def _cmd_scenes() -> int:
    print("PixFold Debug 实时直达场景（共 15 个）：")
    for scene_id, desc in SCENES:
        print(f"  {scene_id:<18} {desc}")
    print("\n可选外观参数：")
    print("  --theme      dark | light | pure_black | system")
    print("  --spec       2025 | 2021")
    print("  --accent     default | system | blue | green | purple | orange | pink")
    print("  --font-scale 0.85..2.0 (常用 1.0 / 1.3)")
    return 0


def _cmd_jump(args: argparse.Namespace) -> int:
    adb_bin = _find_adb()
    serial = _detect_serial(adb_bin, args.serial)
    cmd = build_am_start_cmd(
        adb_bin,
        serial,
        args.scene,
        theme=args.theme,
        color_spec=args.spec,
        accent=args.accent,
        font_scale=args.font_scale,
    )
    proc = subprocess.run(cmd, stdin=subprocess.DEVNULL, check=False)
    return proc.returncode


def _cmd_exit(args: argparse.Namespace) -> int:
    adb_bin = _find_adb()
    serial = _detect_serial(adb_bin, args.serial)
    cmd = build_am_start_cmd(adb_bin, serial, "catalog", exit_showcase=True)
    proc = subprocess.run(cmd, stdin=subprocess.DEVNULL, check=False)
    return proc.returncode


def _cmd_push(args: argparse.Namespace) -> int:
    adb_bin = _find_adb()
    serial = _detect_serial(adb_bin, args.serial)
    android_bin = _find_android_cli()
    _run_assemble_debug()

    install_cmd = build_install_cmd(
        REL_DEBUG_APK,
        serial,
        android_bin=android_bin,
        adb_bin=adb_bin,
        use_delta=not args.no_delta,
    )
    proc = subprocess.run(
        install_cmd,
        cwd=str(ANDROID_DIR),
        stdin=subprocess.DEVNULL,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"安装 Debug APK 失败 (exit={proc.returncode})")

    jump_cmd = build_am_start_cmd(
        adb_bin,
        serial,
        args.scene,
        theme=args.theme,
        color_spec=args.spec,
        accent=args.accent,
        font_scale=args.font_scale,
    )
    jump_proc = subprocess.run(jump_cmd, stdin=subprocess.DEVNULL, check=False)
    return jump_proc.returncode


def _cmd_mirror(args: argparse.Namespace) -> int:
    adb_bin = _find_adb()
    scrcpy_bin = _find_scrcpy()
    serial = _detect_serial(adb_bin, args.serial)
    cmd = build_scrcpy_cmd(
        scrcpy_bin,
        serial,
        width=args.width,
        stay_awake=args.stay_awake,
        turn_screen_off=args.turn_screen_off,
    )
    proc = subprocess.run(cmd, stdin=subprocess.DEVNULL, check=False)
    return proc.returncode


def _cmd_watch(args: argparse.Namespace) -> int:
    validate_scene(args.scene)
    validate_options(
        theme=args.theme,
        color_spec=args.spec,
        accent=args.accent,
        font_scale=args.font_scale,
    )
    if not args.no_initial:
        _cmd_push(args)
    print(f"[live-device] 监听 src/main 与 src/debug 中（目标场景：{args.scene}）...")
    last_fp = scan_device_watch_fingerprint()
    pending_since: float | None = None
    try:
        while True:
            time.sleep(0.5)
            cur_fp = scan_device_watch_fingerprint()
            if cur_fp != last_fp:
                last_fp = cur_fp
                pending_since = time.monotonic()
            elif pending_since is not None and (time.monotonic() - pending_since) >= args.debounce:
                pending_since = None
                try:
                    _cmd_push(args)
                except Exception as exc:
                    print(f"[live-device] 推包失败: {exc}", file=sys.stderr)
                last_fp = scan_device_watch_fingerprint()
    except KeyboardInterrupt:
        return 0


def _add_scene_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("scene", nargs="?", default="catalog", help="直达场景名（运行 scenes 查看全部）")
    parser.add_argument("--theme", default="dark", help="dark | light | pure_black | system")
    parser.add_argument("--spec", default="2025", help="2025 | 2021")
    parser.add_argument("--accent", default="default", help="default | system | blue | green | purple | orange | pink")
    parser.add_argument("--font-scale", type=float, default=1.0, help="字体倍率 0.85..2.0")
    parser.add_argument("--serial", default=None, help=SERIAL_ARG_HELP)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="PixFold 真机 scrcpy 投屏与 Debug 场景直达工具")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("scenes", help="列出全部 15 个 Debug 直达场景（离线）")

    p_mirror = sub.add_parser("mirror", help="启动置顶 scrcpy 投屏小窗")
    p_mirror.add_argument("--serial", default=None, help=SERIAL_ARG_HELP)
    p_mirror.add_argument("--width", type=int, default=420)
    p_mirror.add_argument("--stay-awake", action="store_true")
    p_mirror.add_argument("--turn-screen-off", action="store_true")

    p_jump = sub.add_parser("jump", help="不重装包，一秒热切到指定 Debug 场景与外观")
    _add_scene_args(p_jump)

    p_push = sub.add_parser("push", help="增量编译 + delta 安装并直达指定场景")
    _add_scene_args(p_push)
    p_push.add_argument("--no-delta", action="store_true", help="禁用 android install --use-delta-install，回退 adb install -r")

    p_exit = sub.add_parser("exit", help="退出展示模式，回到真实 PixFoldApp")
    p_exit.add_argument("--serial", default=None, help=SERIAL_ARG_HELP)

    p_watch = sub.add_parser("watch", help="监听 src/main 与 src/debug 变更并自动 delta 推包直达")
    _add_scene_args(p_watch)
    p_watch.add_argument("--debounce", type=float, default=1.2)
    p_watch.add_argument("--no-delta", action="store_true")
    p_watch.add_argument("--no-initial", action="store_true")

    args = parser.parse_args(argv)
    if args.command == "scenes":
        return _cmd_scenes()
    if args.command == "mirror":
        return _cmd_mirror(args)
    if args.command == "jump":
        return _cmd_jump(args)
    if args.command == "push":
        return _cmd_push(args)
    if args.command == "exit":
        return _cmd_exit(args)
    if args.command == "watch":
        return _cmd_watch(args)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
