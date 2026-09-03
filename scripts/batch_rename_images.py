#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# ///
"""
批量重命名并移动/复制图片文件脚本

功能：
1. 递归扫描根目录下的所有子文件夹（支持多层嵌套）
2. 支持按名称/修改时间/创建时间/大小排序（升序或降序）
3. 交互式输入前缀和连接符号
4. 将子文件夹中的图片重命名为：前缀_根文件夹名_子路径_..._原文件名
5. 选择移动（删除子文件夹）或复制（保留子文件夹）文件到根目录

使用方法：
python batch_rename_images.py [根目录]
- 不传参数时默认处理脚本所在目录（即把脚本放在目标文件夹根目录下运行）
- 传入参数时处理指定目录
- 本脚本仅依赖标准库，任何装有 Python 的环境均可直接运行；
  也可用 uv 统一运行（自动选择合适的 Python 版本）：
    uv run batch_rename_images.py

支持系统：
- Windows (自动检测)
- Unix/Linux/macOS (自动检测)

特性：
- 支持多层子文件夹嵌套
- 可选前缀（默认使用）
- 自定义连接符号（默认为 "_"）
- 文件排序：名称/修改时间/创建时间/大小，升序或降序
- 智能处理文件名冲突（数字序号补全 / 括号编号）
- 移动模式：移动文件并按策略清理子文件夹（默认）
- 复制模式：复制文件保留原文件夹结构

联动：
- 可与同目录下的 batch_pack_cbz.py 配合使用
- 先运行本脚本批量重命名图片，再运行 batch_pack_cbz.py 将每个文件夹打包为 CBZ
- 注意：本脚本的"移动模式"会把图片扁平化到根目录，如需按文件夹打包 CBZ，
  请使用复制模式或在文件夹内原地重命名
"""

import platform
import re
import shutil
import sys
from pathlib import Path

# 支持的图片格式
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
    """自然排序键：将 'a2b10' 排序为 ['a', 2, 'b', 10]（数字按数值比较）"""
    return [int(part) if part.isdigit() else part.lower() for part in re.split(r"(\d+)", text)]


# 排序选项：交互选项编号 -> 排序键
SORT_OPTIONS = {
    "1": "name_asc",
    "2": "name_desc",
    "3": "mtime_asc",
    "4": "mtime_desc",
    "5": "ctime_asc",
    "6": "ctime_desc",
    "7": "size_asc",
    "8": "size_desc",
}

SORT_LABELS = {
    "name_asc": "名称升序",
    "name_desc": "名称降序",
    "mtime_asc": "修改时间升序",
    "mtime_desc": "修改时间降序",
    "ctime_asc": "创建时间升序",
    "ctime_desc": "创建时间降序",
    "size_asc": "大小升序",
    "size_desc": "大小降序",
}


def file_sort_key(sort_option: str):
    """返回排序键函数 / Return a sort key function for the given option."""
    if sort_option in ("mtime_asc", "mtime_desc"):
        return lambda f: f.stat().st_mtime
    if sort_option in ("ctime_asc", "ctime_desc"):
        # Windows 下 st_ctime 为创建时间；Unix 下为 inode 变更时间
        return lambda f: f.stat().st_ctime
    if sort_option in ("size_asc", "size_desc"):
        return lambda f: f.stat().st_size
    return lambda f: natural_key(f.name)


def apply_sort(files: list[Path], sort_option: str) -> list[Path]:
    """按指定选项排序文件列表 / Sort files by the given option."""
    reverse = sort_option.endswith("_desc")
    return sorted(files, key=file_sort_key(sort_option), reverse=reverse)


def wait_for_exit():
    """
    等待用户按回车退出
    兼容交互终端（Ctrl+C）和非交互终端（EOF）
    """
    try:
        input("\n按回车键退出...")
    except (EOFError, KeyboardInterrupt):
        print()  # 新行，美化输出


def get_image_files(directory: Path, sort_option: str = "name_asc") -> list[Path]:
    """
    获取指定目录下的所有图片文件，并按指定选项排序

    Args:
        directory: 目录路径
        sort_option: 排序方式（默认名称升序）

    Returns:
        已排序的图片文件路径列表
    """
    image_files = []
    if not directory.is_dir():
        return image_files

    for file in directory.iterdir():
        if file.is_file() and file.suffix.lower() in IMAGE_EXTENSIONS:
            image_files.append(file)

    return apply_sort(image_files, sort_option)


def scan_subdirectories(root_dir: Path, sort_option: str = "name_asc") -> dict[Path, list[Path]]:
    """
    递归扫描根目录下的所有子文件夹及其包含的图片（支持多层嵌套）

    Args:
        root_dir: 根目录路径
        sort_option: 图片排序方式

    Returns:
        字典，键为子文件夹路径，值为该文件夹中已排序的图片文件列表
    """
    subdirs_images: dict[Path, list[Path]] = {}

    def scan_recursive(directory: Path):
        """递归扫描目录"""
        for item in sorted(directory.iterdir(), key=lambda p: natural_key(p.name)):
            if item.is_dir():
                # 获取当前目录的图片
                images = get_image_files(item, sort_option)
                if images:
                    subdirs_images[item] = images
                # 递归扫描子目录
                scan_recursive(item)

    scan_recursive(root_dir)
    return subdirs_images


def generate_new_filename(
    prefix: str,
    root_name: str,
    subdir_path: Path,
    root_dir: Path,
    original_filename: str,
    separator: str = "_",
) -> str:
    """
    生成新的文件名

    Args:
        prefix: 用户输入的前缀
        root_name: 根文件夹名称
        subdir_path: 子文件夹的完整路径
        root_dir: 根目录路径
        original_filename: 原始文件名（包含扩展名）
        separator: 连接符号，默认为 "_"

    Returns:
        新的文件名
    """
    # 分离文件名和扩展名
    name_part = Path(original_filename).stem
    ext_part = Path(original_filename).suffix

    # 获取相对于根目录的路径部分
    try:
        relative_path = subdir_path.relative_to(root_dir)
        # 将路径的各个部分用连接符连接
        path_parts = list(relative_path.parts)
    except ValueError:
        # 如果无法获取相对路径，使用文件夹名
        path_parts = [subdir_path.name]

    # 组合新文件名：前缀_根文件夹名_子路径1_子路径2_..._原文件名
    # 如果前缀为空，则不添加前缀
    if prefix:
        name_components = [prefix, root_name] + path_parts + [name_part]
    else:
        name_components = [root_name] + path_parts + [name_part]

    new_name = separator.join(name_components) + ext_part

    return new_name


def ensure_unique_filename(target_dir: Path, filename: str, separator: str = "_") -> str:
    """
    确保文件名唯一，如果存在重复则智能添加编号

    规则：
    1. 如果文件名最后一部分（最后一个分隔符后）是纯数字（如001），则自动补全序号
       - 已有 001, 002 时，新文件命名为 003
       - 已有 001, 003 时，新文件命名为 002（填补空缺）
    2. 如果最后一部分包含字母（如哈希值），则添加 (1), (2) 等序号

    Args:
        target_dir: 目标目录
        filename: 文件名
        separator: 分隔符

    Returns:
        唯一的文件名
    """
    target_path = target_dir / filename
    if not target_path.exists():
        return filename

    # 分离文件名和扩展名
    name_part = Path(filename).stem
    ext_part = Path(filename).suffix

    # 查找最后一个分隔符的位置
    last_sep_index = name_part.rfind(separator)

    if last_sep_index != -1:
        # 提取最后一个分隔符后的部分
        base_name = name_part[:last_sep_index]
        last_part = name_part[last_sep_index + 1 :]

        # 检查最后一部分是否为纯数字
        if last_part.isdigit():
            # 纯数字情况：智能补全序号
            return _handle_numeric_suffix(target_dir, base_name, last_part, ext_part, separator)

    # 非纯数字或没有分隔符：添加 (1), (2) 等
    return _handle_parenthesis_suffix(target_dir, name_part, ext_part)


def _handle_numeric_suffix(
    target_dir: Path, base_name: str, last_part: str, ext_part: str, separator: str
) -> str:
    """
    处理纯数字后缀的情况，智能填补序号

    Args:
        target_dir: 目标目录
        base_name: 基础文件名（不含最后的数字部分）
        last_part: 最后的数字部分
        ext_part: 文件扩展名
        separator: 分隔符

    Returns:
        唯一的文件名
    """
    # 获取数字的位数（用于补零）
    num_digits = len(last_part)

    # 查找所有相同前缀的文件
    existing_numbers = set()
    pattern = f"{base_name}{separator}"

    for file in target_dir.iterdir():
        if file.is_file() and file.stem.startswith(pattern):
            # 提取数字部分
            suffix = file.stem[len(pattern) :]
            if suffix.isdigit():
                existing_numbers.add(int(suffix))

    # 找到第一个未使用的数字（从1开始）
    counter = 1
    while counter in existing_numbers:
        counter += 1

    # 格式化数字（保持位数一致）
    formatted_number = str(counter).zfill(num_digits)
    new_filename = f"{base_name}{separator}{formatted_number}{ext_part}"

    return new_filename


def _handle_parenthesis_suffix(target_dir: Path, name_part: str, ext_part: str) -> str:
    """
    处理非数字后缀的情况，添加 (1), (2) 等序号

    Args:
        target_dir: 目标目录
        name_part: 文件名（不含扩展名）
        ext_part: 文件扩展名

    Returns:
        唯一的文件名
    """
    counter = 1
    while True:
        new_filename = f"{name_part}({counter}){ext_part}"
        target_path = target_dir / new_filename
        if not target_path.exists():
            return new_filename
        counter += 1


def main():
    """主函数"""
    # 检测操作系统
    system_name = platform.system()
    print("=" * 60)
    print("批量图片重命名与移动工具")
    print(f"运行环境: {system_name}")
    print("=" * 60)
    print()

    # 获取根目录：优先命令行参数，缺省为脚本所在目录
    root_dir = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parent
    root_name = root_dir.name

    if not root_dir.is_dir():
        print(f"[错误] 目录不存在 / Directory not found: {root_dir}")
        wait_for_exit()
        return

    print(f"当前根目录: {root_dir}")
    print(f"根文件夹名: {root_name}")
    print()

    # 交互式选择排序方式
    print("请选择文件排序方式 / Choose file sort order (直接回车默认 1):")
    for num, opt in SORT_OPTIONS.items():
        print(f"  {num}. {SORT_LABELS[opt]}")
    sort_choice = input(
        "请选择 (1-8，直接回车默认 1) / Choose (1-8, Enter for default 1): "
    ).strip()
    if sort_choice not in SORT_OPTIONS:
        sort_choice = "1"
    sort_option = SORT_OPTIONS[sort_choice]
    print(f"排序方式: {SORT_LABELS[sort_option]}")
    print()

    # 扫描子文件夹
    print("正在扫描子文件夹...")
    subdirs_images = scan_subdirectories(root_dir, sort_option)

    if not subdirs_images:
        print("未找到包含图片的子文件夹！")
        wait_for_exit()
        return

    # 显示扫描结果
    total_images = 0
    print(f"\n找到 {len(subdirs_images)} 个包含图片的子文件夹：")
    print("-" * 60)
    for subdir, images in subdirs_images.items():
        print(f"  {subdir.name}: {len(images)} 张图片")
        total_images += len(images)
    print("-" * 60)
    print(f"总计: {total_images} 张图片\n")

    # 交互式选择是否使用前缀
    use_prefix = input("是否使用前缀？(Y/n，直接回车默认使用): ").strip().lower()
    use_prefix = use_prefix != "n"  # 默认为 True，只有输入 'n' 时为 False

    prefix = ""
    if use_prefix:
        # 交互式输入前缀
        while True:
            prefix = input("请输入文件名前缀（不能为空）: ").strip()
            if prefix:
                break
            print("前缀不能为空，请重新输入！")
    else:
        print("不使用前缀")

    print()

    # 交互式输入连接符
    separator = input("请输入连接符号（直接回车使用默认 '_'）: ").strip()
    if not separator:
        separator = "_"

    print(f"使用连接符: '{separator}'")
    print()

    # 预览重命名结果
    print("预览重命名结果（仅显示前5个）：")
    print("-" * 60)
    preview_count = 0
    for subdir, images in subdirs_images.items():
        for img in images[:2]:  # 每个文件夹最多显示2个
            if preview_count >= 5:
                break
            new_name = generate_new_filename(
                prefix, root_name, subdir, root_dir, img.name, separator
            )
            # 显示相对路径
            try:
                rel_path = subdir.relative_to(root_dir)
                print(f"  {rel_path}/{img.name}")
            except ValueError:
                print(f"  {subdir.name}/{img.name}")
            print(f"    → {new_name}")
            preview_count += 1
        if preview_count >= 5:
            break
    print("-" * 60)
    print()

    # 交互式选择移动或复制
    move_or_copy = (
        input("选择操作模式 (M/c)：\n  M - 移动文件（默认）\n  C - 复制文件\n请选择: ")
        .strip()
        .lower()
    )

    is_move = move_or_copy != "c"  # 默认为移动模式，只有输入 'c' 时为复制模式

    # 交互式选择删除策略（仅在移动模式下）
    delete_strategy = "force"  # 默认强制删除
    if is_move:
        print("模式: 移动文件")
        delete_choice = (
            input(
                "\n选择子文件夹删除策略 (F/e/k)：\n"
                "  F - 强制删除所有已处理子文件夹（默认，推荐）\n"
                "  E - 只删除空文件夹\n"
                "  K - 保留所有子文件夹\n"
                "请选择: "
            )
            .strip()
            .lower()
        )

        if delete_choice == "e":
            delete_strategy = "empty"
            print("删除策略: 只删除空文件夹")
        elif delete_choice == "k":
            delete_strategy = "keep"
            print("删除策略: 保留所有子文件夹")
        else:
            delete_strategy = "force"
            print("删除策略: 强制删除所有已处理子文件夹")
    else:
        print("模式: 复制文件保留子文件夹")
        delete_strategy = "keep"

    print()

    # 确认操作
    action_verb = "移动" if is_move else "复制"
    confirm = input(f"确认执行重命名和{action_verb}操作？(y/n): ").strip().lower()
    if confirm != "y":
        print("操作已取消。")
        wait_for_exit()
        return

    # 执行重命名和移动/复制
    action_verb = "移动" if is_move else "复制"
    print(f"\n开始处理（{action_verb}模式）...")
    success_count = 0
    error_count = 0
    processed_subdirs = []  # 记录已处理的子文件夹

    for subdir, images in subdirs_images.items():
        try:
            rel_path = subdir.relative_to(root_dir)
            print(f"\n处理文件夹: {rel_path}")
        except ValueError:
            print(f"\n处理文件夹: {subdir.name}")

        for img in images:
            try:
                # 生成新文件名
                new_name = generate_new_filename(
                    prefix, root_name, subdir, root_dir, img.name, separator
                )

                # 确保文件名唯一（传入separator参数）
                unique_name = ensure_unique_filename(root_dir, new_name, separator)

                # 目标路径
                target_path = root_dir / unique_name

                # 移动或复制文件
                if is_move:
                    shutil.move(str(img), str(target_path))
                    action_symbol = "→"
                else:
                    shutil.copy2(str(img), str(target_path))
                    action_symbol = "⇒"

                print(f"  ✓ {img.name} {action_symbol} {unique_name}")
                success_count += 1

            except Exception as e:
                print(f"  ✗ 处理 {img.name} 时出错: {e}")
                error_count += 1

        # 记录已处理的子文件夹
        processed_subdirs.append(subdir)

    # 根据删除策略处理子文件夹
    deleted_dirs = 0
    skipped_dirs = 0

    if delete_strategy == "force":
        # 强制删除所有已处理的子文件夹
        if error_count > 0:
            print(
                f"\n  ⚠ 有 {error_count} 个文件处理失败，force 模式将连同源文件夹一起删除，"
                "失败文件将无法找回！"
            )
        print("\n清理子文件夹（强制删除）...")
        sorted_subdirs = sorted(processed_subdirs, key=lambda p: len(p.parts), reverse=True)

        for subdir in sorted_subdirs:
            try:
                if subdir.exists() and subdir.is_dir():
                    try:
                        rel_path = subdir.relative_to(root_dir)
                        display_path = str(rel_path)
                    except ValueError:
                        display_path = subdir.name

                    # 删除前检查是否含非图片文件，提前提示
                    non_image_files = [
                        f
                        for f in subdir.iterdir()
                        if f.is_file() and f.suffix.lower() not in IMAGE_EXTENSIONS
                    ]
                    if non_image_files:
                        print(
                            f"  ⚠ {display_path} 含 {len(non_image_files)} 个非图片文件，"
                            "将一并删除："
                        )
                        for f in non_image_files[:5]:
                            print(f"      {f.name}")
                        if len(non_image_files) > 5:
                            print(f"      ... 等 {len(non_image_files)} 个")

                    # 使用 shutil.rmtree 删除整个文件夹（包括非空文件夹）
                    shutil.rmtree(str(subdir))
                    print(f"  已删除文件夹: {display_path}")
                    deleted_dirs += 1
            except Exception as e:
                try:
                    rel_path = subdir.relative_to(root_dir)
                    display_path = str(rel_path)
                except ValueError:
                    display_path = subdir.name
                print(f"  ✗ 删除文件夹 {display_path} 时出错: {e}")
                skipped_dirs += 1

    elif delete_strategy == "empty":
        # 只删除空文件夹
        print("\n清理空文件夹...")
        sorted_subdirs = sorted(processed_subdirs, key=lambda p: len(p.parts), reverse=True)

        for subdir in sorted_subdirs:
            try:
                if subdir.exists() and subdir.is_dir():
                    try:
                        rel_path = subdir.relative_to(root_dir)
                        display_path = str(rel_path)
                    except ValueError:
                        display_path = subdir.name

                    # 只删除空文件夹
                    if not any(subdir.iterdir()):
                        subdir.rmdir()
                        print(f"  已删除空文件夹: {display_path}")
                        deleted_dirs += 1
                    else:
                        print(f"  ⚠ 文件夹非空，跳过: {display_path}")
                        skipped_dirs += 1
            except Exception as e:
                try:
                    rel_path = subdir.relative_to(root_dir)
                    display_path = str(rel_path)
                except ValueError:
                    display_path = subdir.name
                print(f"  ✗ 删除文件夹 {display_path} 时出错: {e}")
                skipped_dirs += 1

    elif delete_strategy == "keep":
        # 保留所有子文件夹
        print("\n保留所有子文件夹")

    # 显示结果
    print("\n" + "=" * 60)
    print("处理完成！")
    print(f"成功{action_verb}: {success_count} 个文件")
    if error_count > 0:
        print(f"失败: {error_count} 个文件")

    if delete_strategy != "keep":
        if deleted_dirs > 0:
            print(f"已删除子文件夹: {deleted_dirs} 个")
        if skipped_dirs > 0:
            if delete_strategy == "force":
                print(f"删除失败: {skipped_dirs} 个")
            else:
                print(f"跳过非空文件夹: {skipped_dirs} 个")

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
