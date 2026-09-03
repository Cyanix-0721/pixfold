# PixFold

把散乱的图片批量整理好——重命名、归序、打包成 CBZ 漫画册。

> 状态：**规划中**（完整规划见 [`HANGOFF.md`](HANGOFF.md)）

## 这是什么

将早期两个 Python 命令行工具整合为一个带 GUI 的跨平台应用，使用 Flutter 单代码库覆盖 **Windows 桌面 + Android**，并打包分发（exe / APK）。

- 📝 **图片批量重命名**：可按名称/时间/大小排序，递归扫描、多层子文件夹、自动处理重名冲突（可独立使用）
- 📦 **批量打包 CBZ**：自动推导 `title / series / writer`、生成标准 `ComicInfo.xml`、页内按自然顺序重命名、支持系列卷号推断与小数卷重编号

核心规则最初由 Python 实现并在 `scripts/` 中保留为真值来源，UI 应用以 Dart 重写并用 golden 测试保证等价。

## 目录结构

```
pixfold/
  HANGOFF.md        # 完整规划与实施路线（4 阶段）
  scripts/          # Python 规则脚本（真值来源）
    batch_rename_images.py
    batch_pack_cbz.py
  app/              # Flutter 应用工程（待创建）
```

## 路线图

- Phase 0：移植 core 规则（natural sort / 名称解析 / 卷号）并用 golden 测试对拍
- Phase 1：文件访问抽象（Windows `dart:io` + Android SAF 插件）
- Phase 2：重命名 / 打包引擎 + GUI
- Phase 3：打包分发（exe / APK + CI）

详见 [`HANGOFF.md`](HANGOFF.md)。

## License

[MIT](LICENSE) © Cyanix-0721
