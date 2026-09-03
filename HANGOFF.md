# PixFold — Handoff：图片批量整理跨平台 GUI 应用（Windows + Android）

> 项目名：**PixFold**（英文代号为主，2026-09-03 定名）
> 状态：规划完成，尚未开始实现（2026-08-31）

## 定位

将 `scripts/` 下两个 Python CLI 工具整合为一个 Windows + Android 跨平台、带 GUI 的应用，并打包分发（exe / APK）。
名字侧重**图片批量整理**（批量重命名可独立使用、场景以图片为主），CBZ 打包只是其中一项输出能力，故定名 PixFold。

## 已确认决策

- Android 端需要**完整功能**：手机上选图片文件夹 → 批量重命名 / 打包 CBZ（需深度集成 SAF）
- 需要打包成 exe / apk 分发给别人
- 技术选型：**Flutter（单代码库）**

## 技术选型：Flutter

- Windows 桌面 + Android 一套 UI 代码，Android 为第一公民，APK 打包签名成熟
- 已验证依赖：`archive`（Zip 打包/更新，支持 ZIP_STORED）、`file_picker`（目录/文件选择、saveFile）
- **自研点**：Android SAF 批量文件操作需自写 Kotlin MethodChannel 插件封装 `DocumentFile`
  （社区 `flutter_saf` 过弱：仅列目录/读字节/缩略图，无 rename/move/copy/delete）
- 备选（均不推荐）：Compose Multiplatform（Kotlin，Android SAF 顺手但 Windows 打包体积大）；
  Kivy/KivyMD（Python 复用但 Windows 上无法原生构建 Android、SAF 需 pyjnius 胶水、APK 大启动慢）；
  Tauri v2（Rust，Android SAF 生态最不成熟）

## 命名约定

- Dart/Flutter 工程名：`pixfold`（内部标识，保持简洁）
- Android applicationId / namespace：`com.cyanix.pixfold`
- Windows 可执行/显示名：PixFold；发布者 Cyanix（Inno Setup AppPublisher / MSIX Publisher）

## 架构

```
pixfold/                       # 本仓库
  HANGOFF.md                   # 本规划文档
  README.md                    # 仓库门面
  LICENSE                      # MIT
  scripts/                     # Python 规则脚本（规则真值来源，保留不删）
    batch_rename_images.py     # 批量重命名图片（纯标准库）
    batch_pack_cbz.py          # 批量打包 CBZ + ComicInfo.xml（依赖 Pillow）
  app/                         # Flutter 工程（Phase 0 起创建；Dart 包名 pixfold）
    lib/core/                  # 纯 Dart 规则库（无 Flutter 依赖，可单测）
      natural_sort.dart        # natural_key（(类型,值) 元组方案）
      name_parser.dart         # parse_name/strip_original_work/clean_cbz_name/clean_folder_name
                               # （[作者]/（原作）/[DL]/开头()前缀 解析规则）
      volume.dart              # detect_volume/infer_volumes/renumber_series_volumes（卷号+小数重编号）
      rename_engine.dart       # generate_new_filename/ensure_unique_filename/排序/移动复制/删除策略
      pack_engine.dart         # find_comic_folders/derive_metadata/build_comic_info_xml/create_cbz/update_cbz
      file_access.dart         # FileAccess 抽象：list/read/write/rename/move/copy/delete（平台无关）
    lib/platform/
      desktop_fs.dart          # Windows: dart:io 实现
      android_saf.dart         # Android: MethodChannel → Kotlin SAF 插件
    lib/ui/
      screens/rename_screen.dart、pack_screen.dart（双端共享）
    android/                   # SafPlugin.kt（DocumentFile/DocumentsContract 封装）
    test/                      # 规则 golden 测试（从 Python 实测用例移植）
    windows/                   # flutter build windows → exe
```

核心架构关键：两个 engine 只依赖 `FileAccess` 抽象，桌面走 `dart:io`、Android 走 SAF 插件，规则层完全平台无关。

## 实施步骤（4 阶段）

### Phase 0 规则验证（关键路径，先做）

1. `flutter create` 工程 + windows/android 平台
2. 移植 core 层 `natural_sort` / `name_parser` / `volume`（纯 Dart）
3. 把 Python 实测用例写成 golden 单测对拍，确认等价（如 `[2,2.5,3]→[2,3,4]`、`系列A3→Vol.3`、`[作者A]单行本→[作者A] 单行本`）

### Phase 1 文件访问抽象

4. 定义 `FileAccess` 接口（依赖 2）
5. Windows `dart:io` 实现（依赖 4）
6. Android SAF Kotlin 插件 + Dart 封装（依赖 4，与 5 并行）

### Phase 2 引擎 + UI

7. `rename_engine` + `pack_engine`（依赖 4/5/6）
8. UI 两页面：选目录 → 扫描预览 → 参数设置 → 执行 → 结果日志，适配双端差异（依赖 7）

### Phase 3 打包分发

9. Windows 打 exe（`flutter build windows` + Inno Setup/MSIX）（依赖 8）
10. Android 打 APK/AAB 签名 + GitHub Actions CI 双端产物（依赖 8，与 9 并行）
11. 端到端验证：Windows 真机流程 + Android 真机 SAF 全流程（依赖 9/10）

## 验证

1. `dart test`：core 规则 golden 用例全绿
2. 对拍：同一输入目录，GUI（Windows）结果 == CLI 脚本结果（重命名清单、CBZ 内页名、ComicInfo.xml）
3. Android 真机：SAF 选目录 → 批量重命名/打包 → CBZ 能被漫画阅读器打开；权限持久化；移动/复制/删除正确
4. 分发：exe 干净机器可装可跑；APK 签名可安装；CI 双端产物产出

## 风险与待定项

- **最大风险**：规则迁移精度 → 用 golden 测试 + 真实样例对拍兜底
- Android SAF 跨目录"移动"无原子操作（read+create+delete），批量大文件注意进度与异常处理
- heic/avif 宽高：Dart `image` 支持有限 → ComicInfo.xml 可省略 ImageWidth/Height（标准允许），v1 先省略
- `-u` 更新模式建议进 v1 GUI（规则已迁移，代价小），如需缩范围可后置
- `scripts/` 下 Python 脚本**保留**为规则真值来源，不删除

## 参考

- 规则细节与实测记录：`scripts/` 下两个 Python 脚本的 docstring
- ComicInfo.xml 规范：anansi-project/comicinfo v2.0（元素顺序 Title→Series→…→Volume→…→Writer→…→PageCount→LanguageISO→…→Pages）
