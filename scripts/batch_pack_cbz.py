#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "Pillow",
# ]
# ///
"""
批量将图片文件夹打包为 CBZ 漫画脚本

功能：
1. 灵活判断目录结构，自动推导 title / series / writer
2. 为每个包含图片的文件夹生成 ComicInfo.xml 并打包为 CBZ
3. CBZ 内图片固定按名称升序（自然排序）重命名为 001.jpg、002.jpg ...（与 ComicInfo.xml 页码一致）
4. 可选删除已打包的源文件夹
5. 漫画文件夹按名称自然升序处理（数字按数值排序，如 系列A、系列A2、系列A3）

目录结构判断（灵活，无需固定层级）：
- 根目录内嵌一层文件夹（每个子文件夹是一本漫画）
    title = series = 文件夹名
- 根目录内嵌两层（外层为系列层，内层为单卷漫画层）
    title = 内层文件夹名（本卷标题），series = 外层文件夹名（[] 后部分，系列名）
- 根目录本身就是漫画文件夹（图片直接在其中，例如"在 series 文件夹"或"在单个漫画文件夹"）
    title = series = 根目录名

名称解析规则：
- 文件夹名如 "[作者] 标题 系列"：[] 内为 writer，其后为 title
- 文件夹名开头可能带 () 前缀（如 "(C108) [作者] 标题"，通常为展会/社团代码），
  与末尾 [DL]、[中文翻译] 等标注一样被忽略，不进入 ComicInfo.xml 与 CBZ 文件名
- 内层（系列）文件夹名也可能带 [] 前缀，例如 "[作者] 系列名 1"（系列第 1 卷），
  解析 series 时忽略该 [] 前缀；writer 一律取外层文件夹的 []
- 标题/系列后可能带全角（）或半角 () 括号，内含"原作"信息（如 "标题（原作：X）"），
  ComicInfo.xml 中忽略括号内容
- 标题末尾可能带 [DL]、[中文翻译] 等标注（0 个或多个，一般空格隔开、位于原作之后），
  与（）原作一样被忽略，不进入 ComicInfo.xml 与 CBZ 文件名；标题中间的 [] 保留
- 打包完成后，命名不规范（[作者] 与标题间空格缺失/多余）的"外层 series / 单个漫画"
  文件夹会被重命名为 "[作者] title"（去尾部 [DL] 等标注、保留（）原作、固定一个空格）；
  series 内层漫画文件夹不重命名（CBZ 已上移一层）

CBZ 输出位置（默认）：
- 两层结构（漫画在 series 内）：CBZ 放在 series 文件夹内（与漫画文件夹同级），不嵌套子文件夹
- 单层结构（单个漫画直接含图）：CBZ 放在漫画文件夹内部，不向上移动
- 可用 -o/--out 指定统一输出目录

示例——四种运行场景的输出结构：
  root（漫画库）
  ├── [作者甲] 系列A/              ← 场景1: series 文件夹
  │   ├── [作者甲] 系列A 1/        ← comic 文件夹（含图片）
  │   ├── [作者甲] 系列A 2/        ← comic 文件夹（含图片）
  │   └── ← cbz 生成在这里（系列A 1.cbz、系列A 2.cbz），不嵌套
  └── [作者乙] 单行本/              ← 场景2: 单个漫画（含图片）
      └── ← cbz 生成在这里
  （在 series 文件夹内运行脚本=场景3，在单个漫画文件夹内运行=场景4）

联动（与 batch_rename_images.py 配合）：
1. 先用 batch_rename_images.py 批量重命名图片（保证排序与命名一致）
2. 再运行本脚本将每个文件夹打包为 CBZ
    例如：python batch_pack_cbz.py "D:\\漫画库"
    或在目标文件夹内直接运行：python batch_pack_cbz.py

使用方法：
python batch_pack_cbz.py [根目录] [选项]
- 不传根目录时询问执行位置：默认（脚本所在目录）/ 手动输入 / 弹出窗口选择

复制到目标目录运行（依赖自动自举）：
- 脚本头部含 PEP 723 依赖声明，用 uv 直接运行即可自动安装 Pillow 并执行：
    uv run batch_pack_cbz.py
- 若用系统 python 直接运行，需先安装 Pillow（见下方依赖说明）

命令行选项：
  root                根目录（缺省询问：默认脚本目录 / 手动输入 / 弹窗选择）
  -o, --out 目录      指定 CBZ 统一输出目录（缺省：两层结构放 series 内 / 单层放漫画文件夹内）
  --lang {skip,ja,zh,interactive}
                      LanguageISO：skip 不生成 / ja 全部日语 / zh 全部中文 /
                      interactive 逐文件夹交互选择（可跳过/留空，缺省交互式询问）
  --volume {skip,auto,input}
                      Volume 模式：skip 不生成 / auto 自动检测（同系列存在
                      更高卷号时，无卷号的漫画自动推断为第 1 卷；存在小数
                      卷号如 2.5 时向上取整为 3，原整数卷顺延为 4，并按卷号
                      排序）/
                      input 逐文件夹输入（缺省交互式询问）
  --conflict {overwrite,rename,ask}
                      CBZ 文件名冲突方案：overwrite 覆盖 / rename 自动重命名
                      （数字后缀）/ ask 逐文件询问（缺省交互式询问）
  -d, --delete        打包成功后自动删除源文件夹（不询问）
  -k, --keep          打包后保留源文件夹（不询问，默认行为）
  -y, --yes           跳过所有确认（打包确认、覆盖确认）
  --dry-run           仅预览计划内容，不实际创建 CBZ
  -u, --update        更新已有 CBZ 的 ComicInfo.xml（扫描 root 下所有 .cbz，重新生成
                      并替换；支持与打包一致的 --lang / --volume，图片原样保留）

交互式流程：
- 开头询问执行位置（root）：1 默认脚本所在目录（回车）/ 2 手动输入 / 3 弹出窗口选择
- 开头询问 LanguageISO 模式：跳过（默认）/ 逐文件夹选择 ja、zh 或跳过（不生成，
  纯图片漫画可留空）
- 开头询问 Volume 模式：跳过（默认）/ 自动检测 / 逐文件夹手动输入
  （auto 模式：同系列有更高卷号时，无卷号的漫画自动推断为第 1 卷；
  存在小数卷号如 2.5 时向上取整为 3，原整数卷顺延为 4，并按卷号排序）
- 开头询问删除模式：保留（默认）/ 打包后自动删除源文件夹（保留生成的 CBZ）
- 开头询问冲突处理方案：覆盖（默认）/ 自动重命名（如 xxx (1).cbz）/ 逐文件询问
- 以上各项均可通过命令行选项直接指定
  （root / --lang / --volume / -d / -k / --conflict）

依赖：
- Pillow（读取图片宽高）
- 复制脚本到任意目录后，推荐用 uv 直接运行（自动创建临时环境并安装 Pillow，不污染目标目录）：
    uv run batch_pack_cbz.py
- 或在仓库脚本环境安装依赖：
    uv pip install --python scripts/.venv/Scripts/python.exe -r scripts/requirements.txt
"""

from __future__ import annotations

import argparse
import contextlib
import io
import math
import os
import platform
import re
import shutil
import sys
import unicodedata
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

# Windows 下强制 UTF-8 输出，避免 ✓ / ▸ 等符号在 GBK 编码下崩溃
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

try:
    from PIL import Image
except ImportError:  # pragma: no cover - 便于给出友好提示
    Image = None

# 支持的图片格式（与 batch_rename_images.py 保持一致）
IMAGE_EXTENSIONS = {
    ".jpg",
    ".jpeg",
    ".png",
    ".gif",
    ".bmp",
    ".webp",
    ".tiff",
    ".tif",
    ".ico",
    ".svg",
    ".heic",
    ".heif",
    ".avif",
}


def natural_key(text: str):
    """
    自然排序键：数字按数值比较，字母段按小写

    返回 (类型, 值) 元组列表，避免 str/int 直接比较崩溃：
    字母段为 (0, 小写文本)，数字段为 (1, 数值)，同一位置先比类型再比值
    """
    key = []
    for part in re.split(r"(\d+)", text):
        if not part:
            continue
        if part.isdigit():
            key.append((1, int(part)))
        else:
            key.append((0, part.lower()))
    return key


def _disp_width(text: str) -> int:
    """终端显示宽度：东亚全角/宽字符按 2 列计算"""
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in text)


def _pad(text: str, width: int) -> str:
    """按终端显示宽度右填充空格，用于列对齐输出"""
    return text + " " * max(0, width - _disp_width(text))


def ask_option(prompt: str, valid: set[str], default: str) -> str:
    """
    选项式交互选择：空回车返回默认值

    输入无效时：第一次提示重选，第二次直接返回默认值（不再纠缠）
    字母输入忽略大小写（如 y/Y 都算 y）
    """
    for attempt in range(2):
        raw = input(prompt).strip()
        if raw == "":
            return default
        if raw in valid:
            return raw
        if raw.lower() in valid:
            return raw.lower()
        if attempt == 0:
            print("  ⚠ 无效选项，请重新输入（直接回车使用默认）")
    print(f"  ⚠ 再次无效，使用默认「{default}」")
    return default


def ask_lang_mode(args) -> tuple[str, str | None]:
    """开头解析 LanguageISO 模式，返回 (mode, lang_fixed)；mode: skip / fixed / interactive"""
    if args.lang == "ja":
        print("LanguageISO: 固定 ja")
        return "fixed", "ja"
    if args.lang == "zh":
        print("LanguageISO: 固定 zh")
        return "fixed", "zh"
    if args.lang == "interactive":
        print("LanguageISO: 逐文件夹交互式选择")
        return "interactive", None
    if args.lang == "skip":
        print("LanguageISO: （不生成）")
        return "skip", None
    print("LanguageISO 选项（ComicInfo.xml 中的语言标签）：")
    print("  1. 跳过（默认，不生成 <LanguageISO> 标签）")
    print("  2. 交互式选择 — 逐文件夹选择 ja 或 zh")
    li = ask_option("请选择 (1-2，直接回车默认跳过): ", {"1", "2"}, "1")
    if li == "2":
        print("LanguageISO: 逐文件夹交互式选择")
        return "interactive", None
    print("LanguageISO: （不生成）")
    return "skip", None


def ask_volume_mode(args) -> str:
    """开头解析 Volume 模式，返回 skip / auto / input"""
    if args.volume:
        return args.volume
    print("Volume（卷号）选项：")
    print("  1. 跳过（默认，不生成 <Volume> 标签）")
    print("  2. 自动检测 — 从标题/系列名末尾自动提取数字卷号；同系列存在")
    print("     更高卷号时，无卷号的漫画自动推断为第 1 卷；存在小数卷号")
    print("     （如 2.5）时向上取整为 3，原整数卷顺延为 4")
    print("  3. 交互式输入 — 逐文件夹手动输入卷号")
    vi = ask_option("请选择 (1-3，直接回车默认跳过): ", {"1", "2", "3"}, "1")
    return {"1": "skip", "2": "auto", "3": "input"}[vi]


def choose_language(
    display: str,
    mode: str,
    lang_fixed: str | None,
    current_lang: str | None = None,
) -> str | None:
    """
    逐项选择 LanguageISO（打包/更新模式共用）
    - fixed: 直接返回 lang_fixed
    - interactive: 交互询问；已有 current_lang 时「跳过=保留现状」并提供「置空」选项
    - skip: 返回 None（不生成）
    """
    if mode == "fixed":
        return lang_fixed
    if mode != "interactive":
        return None
    if current_lang:
        print(f"  >> {display}  LanguageISO（当前 {current_lang}）:")
        print(f"    1. 跳过（保留当前 {current_lang}）   2. ja   3. zh   4. 置空（去掉）")
        li = ask_option("    请选择 (1-4，直接回车默认保留): ", {"1", "2", "3", "4"}, "1")
        if li == "2":
            return "ja"
        if li == "3":
            return "zh"
        if li == "4":
            return None  # 置空：不生成 LanguageISO
        return current_lang  # 跳过=保留现状
    print(f"  >> {display}  LanguageISO（回车=跳过/不生成）:")
    print("    1. 跳过（不生成 LanguageISO）   2. ja   3. zh")
    li = ask_option("    请选择 (1-3，直接回车默认跳过): ", {"1", "2", "3"}, "1")
    return "ja" if li == "2" else "zh" if li == "3" else None


def resolve_volume(
    title: str,
    display: str,
    mode: str,
    inferred: int | None,
    precomputed: int | None = None,
) -> tuple[str, int | float | None]:
    """
    逐项解析卷号，返回 (清理后的标题, 卷号)（打包/更新模式共用）
    - skip: 不生成（标题不变）
    - auto: 显式检测（detect_volume）+ 系列推断（inferred 为推断卷号）；
      传入 precomputed（系列重编号/推断后的最终卷号）时直接使用
      （小数卷已向上取整、整数已顺延）
    - input: 交互输入（非数字视为跳过）
    """
    if mode == "skip":
        return title, None
    if mode == "auto":
        if precomputed is not None:
            clean, _ = detect_volume(title)  # 仅清理标题，卷号以预计算结果为准
            return clean, precomputed
        clean, vol = detect_volume(title)
        if vol is not None:
            return clean, vol
        if inferred == 1:
            return title, 1
        return title, None
    vi = input(f"  >> {display}  请输入卷号（直接回车跳过）: ").strip()
    return title, int(vi) if vi.isdigit() else None


# 全角/半角括号（内容为"原作"，ComicInfo.xml 中忽略）
_PAREN_RE = re.compile(r"[（(][^（）()]*[）)]")

# 作者 [writer] 之前的 () 前缀（如 "(C108) [作者] 标题" 中的 (C108)，
# 通常为展会/社团代码，与末尾 [DL] 等标注一样忽略）
_LEADING_TAG_PAREN_RE = re.compile(r"^\s*[（(][^（）()]*[）)]\s*")

# 末尾标注：标题尾部的 [DL]、[中文翻译] 等（0 个或多个，可选空格隔开），忽略
_TRAILING_TAG_RE = re.compile(r"(?:\s*\[[^\[\]]*\])+$")

# 卷号检测：末尾的纯数字、小数（2.5）、#1、Vol.1、vol 1 等（可选空格）
_VOLUME_RE = re.compile(r"\s*(?:#\s*|[Vv][Oo][Ll]\.?\s*)?(\d+(?:\.\d+)?)\s*$")

# 内嵌卷号检测：数字紧跟波浪线（如 "系列A3〜副标题" 中的 3），保留在标题中
_EMBEDDED_VOLUME_RE = re.compile(r"(\d+(?:\.\d+)?)[〜~]")


def strip_original_work(name: str) -> str:
    """
    去除名称中的括号内容（原作）与末尾 [] 标注（如 [DL]、[中文翻译]）并规整空白

    注意：只移除"末尾"的 [] 标注（一般位于原作之后、空格隔开），
    标题中间本身的 [] 保留不删

    例：
    "作品A（原作：X）"            -> "作品A"
    "作品A (原作X)"               -> "作品A"
    "作品A（原作：X） 1"          -> "作品A 1"
    "作品A [DL]"                  -> "作品A"
    "作品A[DL] [中文翻译]"        -> "作品A"
    "真正的[漫画]标题 [DL]"       -> "真正的[漫画]标题"   # 中间 [] 保留
    "作品A [DL] 副标题"           -> "作品A [DL] 副标题"   # 非末尾，保留
    """
    name = _PAREN_RE.sub("", name)
    name = _TRAILING_TAG_RE.sub("", name)
    return re.sub(r"\s+", " ", name).strip()


def _to_volume(raw: str) -> int | float:
    """将卷号字符串转为 int（整数卷）或 float（小数卷，如 2.5）"""
    v = float(raw)
    return int(v) if v.is_integer() else v


def detect_volume(name: str) -> tuple[str, int | float | None]:
    """
    检测卷号，返回 (处理后的名称, 卷号)

    两种模式：
    1. 末尾卷号（移除并返回）："作品A 1"、"作品A #1"、"作品A Vol.1"、"作品A 2.5"
    2. 内嵌卷号（保留名称，仅提取数值）："作品A3〜副标题" 中的 3

    整数卷返回 int，小数卷返回 float（如 2.5）

    例：
    "作品A 1"                     -> ("作品A", 1)
    "作品A #1"                    -> ("作品A", 1)
    "作品A Vol.1"                 -> ("作品A", 1)
    "作品A vol 1"                 -> ("作品A", 1)
    "作品A 2.5"                   -> ("作品A", 2.5)  # 小数卷
    "作品A3〜副标题…"             -> ("作品A3〜副标题…", 3)  # 内嵌，保留
    "作品A 3〜副标题…"            -> ("作品A 3〜副标题…", 3)  # 内嵌，保留
    "系列B3〜副标题…"             -> ("系列B3〜副标题…", 3)  # 内嵌，保留
    "系列B2.5〜副标题…"           -> ("系列B2.5〜副标题…", 2.5)  # 内嵌小数
    "无卷号标题"                   -> ("无卷号标题", None)
    """
    # 1) 末尾纯卷号：移除
    m = _VOLUME_RE.search(name)
    if m:
        volume = _to_volume(m.group(1))
        clean = name[: m.start()].strip()
        return clean, volume
    # 2) 内嵌卷号：仅提取数值，不修改名称
    m2 = _EMBEDDED_VOLUME_RE.search(name)
    if m2:
        return name, _to_volume(m2.group(1))
    return name, None


def renumber_series_volumes(volumes: list[int | float | None]) -> list[int | None]:
    """
    系列卷号重编号（仅当系列中存在小数卷时使用）

    规则：
    - 无卷号（None）保持 None
    - 小数卷向上取整（2.5 -> 3）
    - 整数卷若被小数取整后的数字占用，则顺延 +1（可连锁）
    - 保持原数值顺序不变

    例：
    [2, 2.5, 3]        -> [2, 3, 4]
    [1, 1.5, 2, 3]     -> [1, 2, 3, 4]
    [1, 2, 2.5, 3]     -> [1, 2, 3, 4]
    [2, 2.5, 3, 3.5, 4] -> [2, 3, 4, 5, 6]
    """
    indexed = [(i, v) for i, v in enumerate(volumes) if v is not None]
    indexed.sort(key=lambda x: x[1])  # 数值升序，确保小数先于其后整数处理
    used: set[int] = set()
    assigned: dict[int, int] = {}
    for i, v in indexed:
        target = math.ceil(v)
        while target in used:
            target += 1
        assigned[i] = target
        used.add(target)
    return [assigned.get(i) for i in range(len(volumes))]


def infer_volumes(metas: list[dict]) -> dict[Path, int | None]:
    """
    推断每个漫画文件夹的卷号（含"无卷号推断为第 1 卷"与"小数卷重编号"规则）

    规则：
    1. 显式卷号：从标题检测（末尾 " 1"、"#1"、"Vol.1"、"2.5" 或内嵌 "3〜"），检测到则使用
    2. 推断卷号：同一系列（series_key）下存在大于 1 的显式卷号，
       且"无显式卷号"的漫画恰好只有 1 本时，将该本推断为第 1 卷
       （例：系列同时有 系列A2、系列A3 时，"系列A" 推断为 Vol.1）
    3. 小数卷重编号：系列中存在小数卷（如 2.5）时，小数向上取整
       （2.5 -> 3），原整数卷号被占用则顺延 +1（如 3 -> 4）
    4. 其余情况无卷号（None，不生成 <Volume>）

    metas: derive_metadata 结果，须含 "folder"、"title"、"series_key"

    Returns:
        {folder: 卷号或 None}
    """
    explicit = {m["folder"]: detect_volume(m["title"])[1] for m in metas}
    groups: dict[str, list[Path]] = {}
    for m in metas:
        groups.setdefault(m["series_key"], []).append(m["folder"])

    result: dict[Path, int | None] = {}
    for folders in groups.values():
        # 1) 无卷号推断为第 1 卷（现有规则）
        vols = {f: explicit[f] for f in folders}
        no_vol = [f for f in folders if vols[f] is None]
        has_vol = [v for v in vols.values() if v is not None]
        if len(no_vol) == 1 and has_vol and max(has_vol) > 1:
            vols[no_vol[0]] = 1
        # 2) 系列存在小数卷时整体重编号（小数向上取整、整数顺延）
        series_vals = [vols[f] for f in folders]
        if any(v is not None and v != int(v) for v in series_vals):
            renumbered = renumber_series_volumes(series_vals)
            for f, nv in zip(folders, renumbered):
                result[f] = nv
        else:
            for f in folders:
                result[f] = vols[f]
    return result


def get_image_files(folder: Path) -> list[Path]:
    """获取文件夹中直接包含的图片文件（未排序）"""
    return [f for f in folder.iterdir() if f.is_file() and f.suffix.lower() in IMAGE_EXTENSIONS]


def find_comic_folders(root: Path) -> list[tuple[Path, int]]:
    """
    递归查找所有"直接包含图片"的文件夹

    Returns:
        [(文件夹路径, 相对根目录的深度)]，深度 0 表示根目录本身
    """
    comics: list[tuple[Path, int]] = []

    if get_image_files(root):
        comics.append((root, 0))

    for dirpath, _dirnames, _filenames in os.walk(root):
        folder = Path(dirpath)
        if folder == root:
            continue
        if get_image_files(folder):
            depth = len(folder.relative_to(root).parts)
            comics.append((folder, depth))

    # 按相对路径自然升序（同系列聚在一起，系列内按名称/卷号排序）
    comics.sort(key=lambda item: natural_key(str(item[0].relative_to(root))))
    return comics


def parse_name(name: str) -> tuple[str, str]:
    """
    从文件夹名解析 (writer, clean_name)

    - 开头的 () 前缀（如 "(C108) [作者] 标题" 中的 (C108)，通常为展会/社团代码）忽略
    - 开头的 [writer] 提取为 writer
    - 标题/系列中的括号（）/( ) 内容（原作）被忽略

    例："(C108) [作者A] 作品A 副标题（原作：X）"
        -> ("作者A", "作品A 副标题")
    无 [] 时返回 ("", 清理后的名称)
    """
    name = name.strip()
    # 去除作者 [] 之前的 () 前缀（如 (C108)），再解析 [writer]
    name = _LEADING_TAG_PAREN_RE.sub("", name)
    writer = ""
    if name.startswith("[") and "]" in name:
        end = name.find("]")
        writer = name[1:end].strip()
        name = name[end + 1 :].strip()
    return writer, strip_original_work(name)


def clean_cbz_name(raw: str) -> str:
    """
    生成 CBZ 文件名用的干净名称：保留开头 [writer] 前缀原样，
    清理开头 () 前缀（如 (C108)）、尾部 [] 标注与（）原作内容
    （与 strip_original_work 规则一致）

    例：
    "(C108) [作者] 标题（原作：X）[DL]"          -> "[作者] 标题"
    "[作者] 真正的[漫画]标题 [中文翻译]"  -> "[作者] 真正的[漫画]标题"
    "[作者] 标题"                        -> "[作者] 标题"
    """
    rest = raw.strip()
    # 去除作者 [] 之前的 () 前缀（如 (C108)）
    rest = _LEADING_TAG_PAREN_RE.sub("", rest)
    writer_part = ""
    if rest.startswith("[") and "]" in rest:
        end = rest.find("]")
        writer_part = rest[: end + 1].strip()
        rest = rest[end + 1 :].strip()
    clean = strip_original_work(rest)
    return f"{writer_part} {clean}".strip() if writer_part else clean


def clean_folder_name(raw: str) -> str:
    """
    生成重命名后的文件夹名：保留 [作者] 前缀与（）原作内容，
    去掉开头 () 前缀（如 (C108)）与尾部 [DL] 等标注，[作者] 与标题之间固定一个空格

    例：
    "(C108) [作者]标题（原作：X）[DL]"     -> "[作者] 标题（原作：X）"
    "[作者]  真正的[漫画]标题 [中文翻译]"  -> "[作者] 真正的[漫画]标题"
    "[作者] 标题"                        -> "[作者] 标题"
    "系列A"                            -> "系列A"   # 无 [作者]，不变
    """
    rest = raw.strip()
    # 去除作者 [] 之前的 () 前缀（如 (C108)）
    rest = _LEADING_TAG_PAREN_RE.sub("", rest)
    writer_part = ""
    if rest.startswith("[") and "]" in rest:
        end = rest.find("]")
        writer_part = rest[: end + 1].strip()
        rest = rest[end + 1 :].strip()
    # 去尾部 [] 标注（保留（）原作）
    rest = _TRAILING_TAG_RE.sub("", rest)
    rest = re.sub(r"\s+", " ", rest).strip()
    return f"{writer_part} {rest}".strip() if writer_part else rest


def derive_metadata(folder: Path, root: Path, depth: int) -> dict[str, str]:
    """
    根据文件夹层级推导 title / series / writer / cbz 名称

    规则：
    - depth <= 1：title = series = 文件夹名（[] 后部分），writer 来自 []
    - depth >= 2：外层（父文件夹）= Series（系列名）+ writer，内层（当前文件夹）= Title（本卷标题）
    - 内层文件夹名也可能带 [] 前缀（单个漫画 title），与括号内容一并忽略
    - cbz 名称 = 文件夹名清理后（保留 [作者] 前缀，去掉尾部 [DL]/[中文翻译] 标注与（）原作）
    """
    if depth <= 1:
        writer, title = parse_name(folder.name)
        series = title
    else:
        outer = folder.parent
        writer, series = parse_name(outer.name)  # 外层 → Series（系列名）
        # 内层名称同样去除 [] 前缀与括号内容（writer 仍取外层）
        _, title = parse_name(folder.name)  # 内层 → Title（本卷标题）

    return {
        "writer": writer,
        "title": title,
        "series": series,
        "cbz_name": clean_cbz_name(folder.name),
    }


def read_image_size(src: Path | bytes) -> tuple[int | None, int | None]:
    """读取图片宽高（src 为文件路径或字节流，失败时返回 (None, None)）"""
    if Image is None:
        return None, None
    try:
        if isinstance(src, bytes):
            with Image.open(io.BytesIO(src)) as im:
                return im.width, im.height
        with Image.open(src) as im:
            return im.width, im.height
    except Exception:
        return None, None


def build_comic_info_xml(
    title: str,
    series: str,
    writer: str,
    image_infos: list[tuple[int, int | None, int | None]],
    volume: int | None = None,
    language_iso: str | None = None,
) -> str:
    """
    生成 ComicInfo.xml 内容

    规范来源：ComicInfo XML 标准（GitHub: anansi-project/comicinfo，
    schema/v2.0/ComicInfo.xsd，社区维护的正式标准）
    - 顶层元素用 <xs:sequence>，顺序有定义：
      Title→Series→…→Volume→…→Writer→…→PageCount→LanguageISO→…→Pages
      （PageCount 必须在 LanguageISO 之前，Pages 位于最后）
    - Page 的 Type 属性默认 "Story"（正文页）；
      Image/ImageSize/ImageWidth/ImageHeight 均为合法属性，
      DoublePage/Key/Bookmark 可选省略

    image_infos: [(ImageSize字节数, ImageWidth, ImageHeight), ...]，顺序即页码顺序
    volume: 卷号（可选，None 时不生成 <Volume>）
    language_iso: 语言代码如 "ja"/"zh"（可选，None 时不生成 <LanguageISO>）
    """
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
        'xmlns:xsd="http://www.w3.org/2001/XMLSchema">',
        f"    <Title>{escape(title)}</Title>",
        f"    <Series>{escape(series)}</Series>",
    ]
    if volume is not None:
        lines.append(f"    <Volume>{volume}</Volume>")
    if writer:
        lines.append(f"    <Writer>{escape(writer)}</Writer>")
    # 元素顺序遵循 ComicInfo 标准（anansi-project/comicinfo v2.0, xs:sequence）：
    # 规范顺序 Title→Series→…→Volume→…→Writer→…→PageCount→LanguageISO→…→Pages
    lines.append(f"    <PageCount>{len(image_infos)}</PageCount>")
    if language_iso:
        lines.append(f"    <LanguageISO>{escape(language_iso)}</LanguageISO>")
    lines.append("    <Pages>")
    for index, (size, width, height) in enumerate(image_infos):
        attrs = f' Image="{index}" Type="Story" ImageSize="{size}"'
        if width is not None:
            attrs += f' ImageWidth="{width}"'
        if height is not None:
            attrs += f' ImageHeight="{height}"'
        lines.append(f"        <Page{attrs}/>")
    lines.append("    </Pages>")
    lines.append("</ComicInfo>")
    return "\n".join(lines)


def create_cbz(images: list[Path], cbz_path: Path, xml_content: str) -> None:
    """将图片打包为 CBZ（ZIP_STORED 无压缩，漫画阅读器兼容性最佳）"""
    digits = max(3, len(str(len(images))))
    with zipfile.ZipFile(str(cbz_path), "w", zipfile.ZIP_STORED) as zf:
        zf.writestr("ComicInfo.xml", xml_content)
        for i, img in enumerate(images):
            arcname = f"{str(i + 1).zfill(digits)}{img.suffix.lower()}"
            zf.write(str(img), arcname)


def find_available_path(path: Path) -> Path:
    """
    生成不冲突的输出路径：同名时追加 " (1)"、" (2)"... 数字后缀

    例：
    "作品A.cbz" 已存在        -> "作品A (1).cbz"
    "作品A (1).cbz" 也已存在   -> "作品A (2).cbz"
    "作品A.cbz" 不存在        -> "作品A.cbz"
    """
    if not path.exists():
        return path
    stem = path.stem
    suffix = path.suffix
    parent = path.parent
    i = 1
    while True:
        candidate = parent / f"{stem} ({i}){suffix}"
        if not candidate.exists():
            return candidate
        i += 1


def ask_folder_dialog(initial_dir: Path) -> Path | None:
    """
    弹出系统文件夹选择窗口，返回所选目录

    - 基于 tkinter（Python 标准库，跨平台），Windows 下为原生文件夹对话框
    - 无 tkinter / 无图形环境 / 用户取消时返回 None
    """
    try:
        import tkinter as tk
        from tkinter import filedialog
    except ImportError:
        print("[提示] 当前环境未安装 tkinter，无法弹出选择窗口，请改用手动输入。")
        return None
    root = None
    try:
        root = tk.Tk()
        root.withdraw()
        with contextlib.suppress(Exception):
            root.attributes("-topmost", True)  # 窗口置顶，避免被遮挡
        result = filedialog.askdirectory(initialdir=str(initial_dir), title="选择要打包的根目录")
    except Exception as e:
        print(f"[提示] 打开选择窗口失败（{e}），请改用手动输入。")
        return None
    finally:
        if root is not None:
            with contextlib.suppress(Exception):
                root.destroy()
    return Path(result) if result else None


def _read_cbz_language(cbz: Path) -> str | None:
    """读取 CBZ 内已有 ComicInfo.xml 的 LanguageISO（无该标签/文件时返回 None）"""
    try:
        with zipfile.ZipFile(str(cbz)) as zf:
            if "ComicInfo.xml" not in zf.namelist():
                return None
            xml = zf.read("ComicInfo.xml").decode("utf-8")
            m = re.search(r"<LanguageISO>([^<]+)</LanguageISO>", xml)
            return m.group(1).strip() if m else None
    except Exception:
        return None


def update_main(
    root_dir: Path, language_iso_mode: str, lang_fixed: str | None, volume_mode: str
) -> None:
    """
    更新模式：扫描 root 下所有 .cbz，逐个重新生成 ComicInfo.xml

    支持与打包模式一致的 --lang / --volume 模式（skip / fixed / interactive、skip / auto / input）：
    - volume auto 时做系列级卷号推断（同系列存在更高卷号时，无卷号推断为第 1 卷）
    - LanguageISO 交互时：已有语言「跳过=保留现状」，并提供「置空」选项去掉语言
    - 图片条目原样复制（不重新压缩），仅替换 ComicInfo.xml，用新 CBZ 替换原文件
    """
    cbz_files = sorted(
        (p for p in root_dir.rglob("*.cbz") if p.is_file()),
        key=lambda p: natural_key(str(p.relative_to(root_dir))),
    )
    if not cbz_files:
        print("未找到 CBZ 文件！")
        return

    # 第一遍：解析元数据 + （auto 时）系列级卷号推断
    metas: list[dict] = []
    for cbz in cbz_files:
        writer, raw_title = parse_name(cbz.stem)
        clean, explicit_vol = detect_volume(raw_title)
        series = clean if explicit_vol is not None else raw_title
        metas.append(
            {
                "cbz": cbz,
                "writer": writer,
                "raw_title": raw_title,
                "series": series,
                "explicit_vol": explicit_vol,
                "series_key": f"{writer}|{series}",
            }
        )
    final_vol_map: dict[Path, int | None] = {}
    if volume_mode == "auto":
        groups: dict[str, list[dict]] = {}
        for m in metas:
            groups.setdefault(m["series_key"], []).append(m)
        for sibs in groups.values():
            vols = {m["cbz"]: m["explicit_vol"] for m in sibs}
            no_vol = [m["cbz"] for m in sibs if m["explicit_vol"] is None]
            has_vol = [v for v in vols.values() if v is not None]
            if len(no_vol) == 1 and has_vol and max(has_vol) > 1:
                vols[no_vol[0]] = 1  # 推断为第 1 卷
            # 系列存在小数卷时整体重编号（小数向上取整、整数顺延）
            series_vals = [vols[m["cbz"]] for m in sibs]
            if any(v is not None and v != int(v) for v in series_vals):
                renumbered = renumber_series_volumes(series_vals)
                for m, nv in zip(sibs, renumbered):
                    final_vol_map[m["cbz"]] = nv
            else:
                for m in sibs:
                    final_vol_map[m["cbz"]] = vols[m["cbz"]]

    print("=" * 60)
    print(f"CBZ 更新工具：找到 {len(cbz_files)} 个 CBZ")
    print("=" * 60)

    updated = 0
    for idx, m in enumerate(metas, 1):
        cbz = m["cbz"]
        try:
            cur_lang = _read_cbz_language(cbz) if language_iso_mode == "interactive" else None
            # Volume 解析（auto 静默；input 交互，prompt 含文件名可识别）
            # auto 模式：final_vol_map 已含系列推断/小数重编号后的最终卷号
            title, volume = resolve_volume(
                m["raw_title"],
                cbz.name,
                volume_mode,
                None,
                precomputed=final_vol_map.get(cbz) if volume_mode == "auto" else None,
            )
            vol_str = f"Vol.{volume}" if volume is not None else "无卷号"
            lang_str = cur_lang if cur_lang else "无"

            # 先打印分隔与当前项标题，再询问语言，避免与上一项看混
            print("-" * 60)
            print(f"  [{idx}/{len(metas)}] {cbz.name}")
            print(f"       系列: {m['series']} | {vol_str} | 当前语言: {lang_str}")
            lang_iso = choose_language(
                cbz.name, language_iso_mode, lang_fixed, current_lang=cur_lang
            )

            # 读取 CBZ 内图片元数据（大小 + 宽高）
            with zipfile.ZipFile(str(cbz)) as zf:
                names = sorted(
                    (n for n in zf.namelist() if Path(n).suffix.lower() in IMAGE_EXTENSIONS),
                    key=natural_key,
                )
                image_infos: list[tuple[int, int | None, int | None]] = []
                images: dict[str, tuple[bytes, int]] = {}
                for n in names:
                    data = zf.read(n)
                    width, height = read_image_size(data)
                    image_infos.append((len(data), width, height))
                    images[n] = (data, zf.getinfo(n).compress_type)

                xml_content = build_comic_info_xml(
                    title,
                    m["series"],
                    m["writer"],
                    image_infos,
                    volume=volume,
                    language_iso=lang_iso,
                )

                # 重写 CBZ：图片原样复制 + 新 ComicInfo.xml，再替换原文件
                tmp = cbz.with_name(cbz.name + ".tmp")
                with zipfile.ZipFile(str(tmp), "w") as zf_out:
                    zf_out.writestr("ComicInfo.xml", xml_content)
                    for n, (data, ct) in images.items():
                        zf_out.writestr(n, data, compress_type=ct)
            os.replace(tmp, cbz)

            new_lang = lang_iso if lang_iso else "无语言"
            print(f"  ✓ 已更新（{len(image_infos)}页 {vol_str} 语言:{new_lang}）")
            updated += 1
        except Exception as e:
            print(f"  ✗ 更新 {cbz.name} 失败: {e}")
    print("=" * 60)
    print(f"已更新 {updated} 个 CBZ")


def wait_for_exit():
    """等待用户按回车退出，兼容交互终端（Ctrl+C）和非交互终端（EOF）"""
    try:
        input("\n按回车键退出...")
    except (EOFError, KeyboardInterrupt):
        print()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="批量将图片文件夹打包为 CBZ 漫画（自动生成 ComicInfo.xml）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            '  python batch_pack_cbz.py "D:\\漫画库"\n'
            "  python batch_pack_cbz.py  # 询问执行位置（默认脚本目录 / 手动输入 / 弹窗选择）\n"
            "  python batch_pack_cbz.py . --out D:\\cbz --dry-run\n"
        ),
    )
    parser.add_argument(
        "root", nargs="?", default=None, help="根目录（缺省交互式输入，默认脚本所在目录）"
    )
    parser.add_argument(
        "-o",
        "--out",
        default=None,
        help="CBZ 输出目录（缺省：两层结构放 series 内 / 单层放漫画文件夹内）",
    )
    parser.add_argument(
        "--lang",
        choices=["skip", "ja", "zh", "interactive"],
        default=None,
        help=(
            "LanguageISO：skip 不生成 / ja 全部日语 / zh 全部中文 / "
            "interactive 逐文件夹交互选择（可跳过/留空，缺省交互式询问）"
        ),
    )
    parser.add_argument(
        "--volume",
        choices=["skip", "auto", "input"],
        default=None,
        help=(
            "Volume 模式：skip 不生成 / auto 自动检测（同系列有更高卷号时"
            "无卷号漫画推断为第 1 卷；存在小数卷号时向上取整（2.5 → 3）"
            "原整数卷顺延（3 → 4），并按卷号排序）/ input 逐文件夹输入"
            "（缺省交互式询问）"
        ),
    )
    parser.add_argument(
        "--conflict",
        choices=["overwrite", "rename", "ask"],
        default=None,
        help=(
            "CBZ 文件名冲突方案：overwrite 覆盖 / rename 自动重命名（数字后缀）/ "
            "ask 逐文件询问（缺省交互式询问）"
        ),
    )
    parser.add_argument(
        "-d",
        "--delete",
        action="store_true",
        help="打包成功后自动删除源文件夹（保留生成的 CBZ，不询问）",
    )
    parser.add_argument(
        "-k", "--keep", action="store_true", help="打包后保留源文件夹（不询问，默认行为）"
    )
    parser.add_argument("-y", "--yes", action="store_true", help="跳过所有确认")
    parser.add_argument("--dry-run", action="store_true", help="仅预览，不实际打包")
    parser.add_argument(
        "-u",
        "--update",
        action="store_true",
        help=(
            "更新已有 CBZ 的 ComicInfo.xml：扫描 root 下所有 .cbz，重新生成并替换"
            "（支持与打包一致的 --lang / --volume，图片原样保留）"
        ),
    )
    args = parser.parse_args()

    # 检测依赖
    if Image is None:
        print("[错误] 未找到 Pillow，无法读取图片宽高。请先安装依赖：")
        print(
            "  uv pip install --python scripts/.venv/Scripts/python.exe -r scripts/requirements.txt"
        )
        wait_for_exit()
        return

    print("=" * 60)
    print("批量 CBZ 打包工具")
    print(f"运行环境: {platform.system()}")
    if args.update:
        print("运行模式: 更新模式（-u，重写已有 CBZ 的 ComicInfo.xml）")
    else:
        print("运行模式: 打包模式（图片文件夹 → CBZ）")
    print("=" * 60)
    print()

    # 根目录：优先命令行参数；否则交互式询问（默认 / 手动输入 / 弹窗选择）
    if args.root:
        root_dir = Path(args.root).resolve()
    else:
        default_dir = Path(__file__).resolve().parent
        print("执行位置（要打包的根目录 / root directory）：")
        print(f"  1. 使用默认（脚本所在目录）: {default_dir}")
        print("  2. 手动输入路径")
        print("  3. 弹出窗口选择文件夹")
        choice = ask_option("请选择 (1-3，直接回车默认 1): ", {"1", "2", "3"}, "1")
        if choice == "2":
            entered = input("  请输入路径: ").strip()
            root_dir = Path(entered).resolve() if entered else default_dir
        elif choice == "3":
            selected = ask_folder_dialog(default_dir)
            root_dir = selected.resolve() if selected else default_dir
        else:
            root_dir = default_dir
    if not root_dir.is_dir():
        print(f"[错误] 目录不存在 / Directory not found: {root_dir}")
        wait_for_exit()
        return

    # 更新模式：重写已有 CBZ 的 ComicInfo.xml（支持与打包一致的 --lang / --volume 模式）
    if args.update:
        language_iso_mode, lang_fixed = ask_lang_mode(args)
        volume_mode = ask_volume_mode(args)
        volume_labels = {"skip": "跳过（不生成）", "auto": "自动检测", "input": "交互式输入"}
        print(f"Volume 模式: {volume_labels[volume_mode]}")
        print()
        update_main(root_dir, language_iso_mode, lang_fixed, volume_mode)
        wait_for_exit()
        return

    out_dir = Path(args.out).resolve() if args.out else None
    print(f"根目录: {root_dir}")
    if out_dir:
        print(f"输出目录: {out_dir}")
    print()

    # ---- LanguageISO 模式（--lang 直接指定，否则交互式询问） ----
    language_iso_mode, lang_fixed = ask_lang_mode(args)
    print()

    # ---- Volume 模式（--volume 直接指定，否则交互式询问） ----
    volume_mode = ask_volume_mode(args)
    volume_labels = {"skip": "跳过（不生成）", "auto": "自动检测", "input": "交互式输入"}
    print(f"Volume 模式: {volume_labels[volume_mode]}")
    print()

    # ---- 删除模式（-d/-k 直接指定，否则交互式询问） ----
    if args.delete:
        delete_mode = "delete"
        print("删除模式: 打包后自动删除源文件夹")
    elif args.keep:
        delete_mode = "keep"
        print("删除模式: 保留源文件夹")
    else:
        print("删除源文件夹选项：")
        print("  1. 保留（默认，不删除）")
        print("  2. 自动删除 — 打包成功后删除源文件夹（保留生成的 CBZ）")
        del_choice = ask_option("请选择 (1-2，直接回车默认保留): ", {"1", "2"}, "1")
        delete_mode = "delete" if del_choice == "2" else "keep"
        print(f"删除模式: {'自动删除' if delete_mode == 'delete' else '保留源文件夹'}")

    # ---- CBZ 文件名冲突方案（--conflict 直接指定，否则交互式询问） ----
    if args.conflict:
        conflict_mode = args.conflict
    elif args.yes:
        conflict_mode = "overwrite"  # -y 时缺省覆盖，保持原有静默覆盖行为
    else:
        print("CBZ 文件名冲突处理方案：")
        print("  1. 覆盖（默认，直接覆盖已存在的 CBZ）")
        print("  2. 自动重命名 — 生成带数字后缀的新文件名（如 xxx (1).cbz）")
        print("  3. 逐文件询问 — 每个冲突单独选择（覆盖/自动重命名/手动重命名/跳过）")
        conf_choice = ask_option("请选择 (1-3，直接回车默认覆盖): ", {"1", "2", "3"}, "1")
        conflict_mode = {"1": "overwrite", "2": "rename", "3": "ask"}[conf_choice]
    conflict_labels = {
        "overwrite": "覆盖",
        "rename": "自动重命名（数字后缀）",
        "ask": "逐文件询问",
    }
    print(f"冲突处理方案: {conflict_labels[conflict_mode]}")
    print()

    # 扫描包含图片的文件夹
    print("正在扫描图片文件夹...")
    comics = find_comic_folders(root_dir)

    if not comics:
        print("未找到包含图片的文件夹！")
        wait_for_exit()
        return

    total_images = sum(len(get_image_files(folder)) for folder, _ in comics)
    print(f"\n找到 {len(comics)} 个包含图片的文件夹，共 {total_images} 张图片：")
    if language_iso_mode != "skip":
        if language_iso_mode == "fixed":
            print(f"LanguageISO: 固定 {lang_fixed}")
        else:
            print("LanguageISO: 逐文件夹交互式选择")
    if volume_mode != "skip":
        print(f"Volume 模式: {volume_labels[volume_mode]}")
    if delete_mode == "delete":
        print("删除模式: 打包后自动删除源文件夹")

    # 收集元数据 + 推断卷号
    # auto 模式：同系列存在更高卷号时，无显式卷号的漫画自动推断为第 1 卷
    metas: list[dict] = []
    for folder, depth in comics:
        meta = derive_metadata(folder, root_dir, depth)
        meta["folder"] = folder
        meta["depth"] = depth
        # series 分组键：两层结构用外层系列文件夹路径，单层结构每本自成一组
        meta["series_key"] = str(folder.parent) if depth >= 2 else str(folder)
        # 系列头显示名：两层用外层文件夹名（含 [作者]），单层用本文件夹名
        meta["series_display"] = folder.parent.name if depth >= 2 else folder.name
        metas.append(meta)
    volume_map: dict[Path, int | None] = infer_volumes(metas) if volume_mode == "auto" else {}

    # 按卷号排序（仅 auto 模式）：先按系列分组（自然序），系列内按推断卷号升序，
    # 无卷号的排本系列最后；skip/input 模式保持名称顺序
    if volume_mode == "auto":
        metas.sort(
            key=lambda m: (
                natural_key(m["series_key"]),
                volume_map[m["folder"]] if volume_map[m["folder"]] is not None else 10**9,
                natural_key(m["title"]),
            )
        )
        # 同步打包循环顺序（预览遍历 metas，打包遍历 comics）
        comics = [(m["folder"], m["depth"]) for m in metas]

    # ---- 预览：按系列分组、列对齐显示（* = 推断卷号，? = 待逐文件夹选择）----
    print("=" * 72)
    print(f"打包计划：{len(metas)} 个漫画，共 {total_images} 张图片")
    print("=" * 72)
    prev_key = None
    for meta in metas:
        folder = meta["folder"]
        n = len(get_image_files(folder))

        # 卷号显示（* 表示推断）
        volume_str = "-"
        if volume_mode == "auto":
            vol = volume_map[folder]
            if vol is not None:
                explicit_vol = detect_volume(meta["title"])[1]
                volume_str = f"Vol.{vol}*" if explicit_vol is None else f"Vol.{vol}"
        elif volume_mode == "input":
            volume_str = "?"

        # 语言显示（? 表示待逐文件夹选择）
        lang_str = "-"
        if language_iso_mode == "fixed":
            lang_str = lang_fixed or "-"
        elif language_iso_mode == "interactive":
            lang_str = "?"

        # 系列分组头（两层结构以系列为单位；单层结构每本独立）
        if meta["series_key"] != prev_key:
            if prev_key is not None:
                print()
            print(f"  ▸ {meta['series_display']}")
            prev_key = meta["series_key"]

        print(
            f"    {_pad(meta['title'], 34)} {_pad(volume_str, 6)} "
            f"{_pad(lang_str, 3)} {n:>4}页  → {meta['cbz_name']}.cbz"
        )
    print("=" * 72)
    print()

    # 预览（dry-run）或确认
    if args.dry_run:
        print("[预览模式] 以上为计划打包的内容，未实际创建 CBZ。")
        wait_for_exit()
        return

    if not args.yes:
        confirm = ask_option("确认执行打包操作？(y/n): ", {"y", "n"}, "n")
        if confirm != "y":
            print("操作已取消。")
            wait_for_exit()
            return

    # 逐文件夹打包
    success_folders: list[Path] = []
    fail_folders: list[Path] = []
    success_cbzs = 0

    print("\n开始打包...")
    for folder, depth in comics:
        try:
            meta = derive_metadata(folder, root_dir, depth)

            # ---- Volume 处理（复用抽象函数，与更新模式一致）----
            # auto 模式：volume_map 已含系列推断/小数重编号后的最终卷号，直接使用
            clean, volume = resolve_volume(
                meta["title"],
                meta["cbz_name"],
                volume_mode,
                None,
                precomputed=volume_map.get(folder) if volume_mode == "auto" else None,
            )
            meta["title"] = clean
            if depth <= 1:
                meta["series"] = clean  # 一层时 title == series

            # ---- LanguageISO 逐文件夹处理（复用抽象函数）----
            lang_iso = choose_language(meta["cbz_name"], language_iso_mode, lang_fixed)

            # 排序图片：固定按名称升序（自然排序），命名已由重命名脚本保证顺序
            images = get_image_files(folder)
            images.sort(key=lambda f: natural_key(f.name))

            # 读取图片元数据（大小 + 宽高）
            image_infos: list[tuple[int, int | None, int | None]] = []
            for img in images:
                size = img.stat().st_size
                width, height = read_image_size(img)
                image_infos.append((size, width, height))

            xml_content = build_comic_info_xml(
                meta["title"],
                meta["series"],
                meta["writer"],
                image_infos,
                volume=volume,
                language_iso=lang_iso,
            )

            # 决定 CBZ 输出位置：
            # - 两层结构（漫画在 series 内）：放 series 文件夹内，与漫画文件夹同级，不嵌套
            # - 单层结构（单个漫画直接含图）：放漫画文件夹内部，避免上移到根目录
            if out_dir:
                cbz_dir = out_dir
            elif depth >= 2:
                cbz_dir = folder.parent
            else:
                cbz_dir = folder
            cbz_path = cbz_dir / f"{meta['cbz_name']}.cbz"

            # 文件名冲突处理（--conflict 决定：覆盖 / 自动重命名 / 逐文件询问）
            if cbz_path.exists():
                if conflict_mode == "overwrite":
                    pass  # 直接覆盖
                elif conflict_mode == "rename":
                    cbz_path = find_available_path(cbz_path)
                    print(f"  ↪ 文件名冲突，自动重命名为: {cbz_path.name}")
                elif conflict_mode == "ask":
                    print(f"  ⚠ {cbz_path.name} 已存在，如何处理？")
                    print("    1. 覆盖   2. 自动重命名   3. 手动输入文件名   4. 跳过")
                    ch = ask_option(
                        "    请选择 (1-4，直接回车默认跳过): ",
                        {"1", "2", "3", "4"},
                        "4",
                    )
                    if ch == "2":
                        cbz_path = find_available_path(cbz_path)
                        print(f"  ↪ 自动重命名为: {cbz_path.name}")
                    elif ch == "3":
                        renamed = False
                        while True:
                            new_name = input(
                                "    请输入新文件名（含扩展名，直接回车跳过）: "
                            ).strip()
                            if not new_name:
                                break
                            new_path = cbz_dir / new_name
                            if new_path.exists():
                                print(f"    ⚠ {new_name} 也已存在，请换一个名字")
                                continue
                            cbz_path = new_path
                            renamed = True
                            break
                        if not renamed:
                            print(f"  ⚠ 跳过: {cbz_path.name}")
                            fail_folders.append(folder)
                            continue
                    elif ch != "1":
                        print(f"  ⚠ 跳过: {cbz_path.name}")
                        fail_folders.append(folder)
                        continue

            cbz_dir.mkdir(parents=True, exist_ok=True)
            create_cbz(images, cbz_path, xml_content)
            # 简洁成功信息：相对路径 + 页数 + 卷号 + 语言
            try:
                rel_cbz = cbz_path.relative_to(root_dir)
            except ValueError:
                rel_cbz = cbz_path
            info = f"{len(images)}页"
            if volume is not None:
                info += f" Vol.{volume}"
            if lang_iso:
                info += f" {lang_iso}"
            print(f"  ✓ {rel_cbz}（{info}）")
            success_folders.append(folder)
            success_cbzs += 1

        except Exception as e:
            print(f"  ✗ 打包 {folder} 时出错: {e}")
            fail_folders.append(folder)

    print()
    print(f"成功打包: {success_cbzs} 个 CBZ")
    if fail_folders:
        print(f"失败: {len(fail_folders)} 个")

    # 打包完成后：重命名命名不规范的外层 series / 单个漫画文件夹
    # （内层漫画文件夹不重命名，CBZ 已上移一层；根目录 depth=0 不重命名）
    rename_map: dict[Path, Path] = {}
    candidates: list[Path] = []
    for folder, depth in comics:
        if depth == 0:
            continue
        target = folder if depth <= 1 else folder.parent
        if target not in candidates:
            candidates.append(target)
    for cand in candidates:
        new_name = clean_folder_name(cand.name)
        if new_name == cand.name:
            continue
        new_path = cand.with_name(new_name)
        if new_path.exists():
            print(f"  ⚠ 目标文件夹已存在，跳过重命名: {new_path}")
            continue
        try:
            cand.rename(new_path)
            rename_map[cand] = new_path
            print(f"  ↪ 重命名文件夹: {cand.name} -> {new_name}")
        except Exception as e:
            print(f"  ✗ 重命名 {cand.name} 失败: {e}")

    # 若外层 series 被重命名，更新 success_folders 路径（供删除源文件夹使用）
    if rename_map:
        updated_success: list[Path] = []
        for p in success_folders:
            for old, new in rename_map.items():
                if p == old or old in p.parents:
                    p = new / p.relative_to(old)
                    break
            updated_success.append(p)
        success_folders = updated_success

    # 删除策略：根据开头选择的删除模式执行
    delete_folders = delete_mode == "delete"

    deleted = 0
    if delete_folders and success_folders:
        print("\n删除源文件夹...")
        # 先删深层，再删浅层，避免父目录残留
        # 关键：排除刚生成的 .cbz（单个漫画/根目录场景 CBZ 就在源文件夹内），
        # 删除其余源文件；删除后若文件夹已空且不是根目录，再移除空文件夹本身
        for folder in sorted(success_folders, key=lambda p: len(p.parts), reverse=True):
            try:
                if not (folder.exists() and folder.is_dir()):
                    continue
                # 提示将一并删除的非图片、非 CBZ 文件
                others = [
                    f
                    for f in folder.iterdir()
                    if f.is_file()
                    and f.suffix.lower() not in IMAGE_EXTENSIONS
                    and f.suffix.lower() != ".cbz"
                ]
                if others:
                    print(f"  ⚠ {folder.name} 含 {len(others)} 个非图片文件，将一并删除")
                for item in list(folder.iterdir()):
                    if item.is_file() and item.suffix.lower() == ".cbz":
                        continue  # 保留生成的 CBZ
                    if item.is_dir():
                        shutil.rmtree(item)
                    else:
                        item.unlink()
                # 删除后若文件夹已空且不是根目录，移除空文件夹本身
                if folder != root_dir and not any(folder.iterdir()):
                    folder.rmdir()
                try:
                    rel_del = folder.relative_to(root_dir)
                    if str(rel_del) == ".":
                        rel_del = f"<根目录: {root_dir.name}>"
                except ValueError:
                    rel_del = folder
                print(f"  已删除: {rel_del}")
                deleted += 1
            except Exception as e:
                print(f"  ✗ 删除 {folder} 时出错: {e}")
        print(f"已删除 {deleted} 个源文件夹")

    print("\n" + "=" * 60)
    print("处理完成！")
    print("=" * 60)
    wait_for_exit()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n操作已被用户中断。")
        wait_for_exit()
    except Exception as e:
        print(f"\n发生错误: {e}")
        wait_for_exit()
