# PixFold — 设计交接：图片整理与 CBZ 制作工作台

> 项目名：**PixFold**
> 状态：**设计阶段**（2026-09-03 立项；2026-09-08 D2 SAF spike 5/5 验收、技术栈锁定，当前主线 D1 交互原型）
> 目标平台：**Windows、Android（优先）、Linux**
> 技术栈：**Flutter + Dart（2026-09-08 锁定，D2 SAF spike 5/5 验收，见 §8.1）**

## 1. 项目重新定位

PixFold 不是把两个 Python 脚本简单搬进一个窗口，也不是单纯追求“一键批量化”。

真实需求是：把图片整理和 CBZ 制作过程中必须由人判断的部分，变成一个**可预览、可编辑、可逐项确认、可回退的图形化工作流**。

两个 Python 脚本是现有行为参考和样例来源，但不是最终产品规范。GUI 需要在其基础上补足：

- 图片排序的可视化检查和人工调整；
- 命名结构的组成、顺序、清洗规则和逐项覆盖；
- 漫画目录、系列、卷之间的关系确认；
- CBZ 的语言、卷号、标题、作者和输出名等元数据录入；
- 执行前完整计划预览；
- 冲突处理、失败重试、撤销和结果核对；
- Windows、Android、Linux 不同文件访问模型下的一致体验。

产品核心价值不是“少点几次确认”，而是让复杂整理过程**看得见、改得动、做得稳、出错能回退**。

## 2. 已确认的产品约束

- 最终覆盖 Windows、Android、Linux。
- Android 是优先平台，不能把 Android 当成桌面版的缩小移植。
- 技术栈已锁定 **Flutter + Dart**(2026-09-08,D2 SAF spike 5/5 验收通过,§8.1);对照 CMP 仅当 §8.2 证伪条件 ③ 被 D1 证实且修复成本不可接受时才启用。
- 现有两个 Python 脚本保留，不删除，继续作为行为参考、回归样例和命令行备用工具。
- 文件破坏性操作必须经过明确确认；默认不删除源文件，不继承脚本中“强制删除”的危险默认值。
- 所有重要自动推断都必须展示依据，并允许人工覆盖。
- 每次实际写入前必须生成操作计划；计划可保存、复查和执行。

## 3. 现有能力范围

### 3.1 图片整理脚本提供的基础能力

`scripts/batch_rename_images.py` 当前包含：

- 递归扫描多层子文件夹；
- 按名称、修改时间、创建时间、文件大小排序，支持升降序；
- 自然排序；
- 根据前缀、根目录名、子路径和原文件名生成新文件名；
- 移动或复制到根目录；
- 数字后缀补零和重名冲突处理；
- 移动后的子文件夹处理策略：强制删除、只删空目录、保留。

### 3.2 CBZ 脚本提供的基础能力

`scripts/batch_pack_cbz.py` 当前包含：

- 递归识别直接包含图片的漫画文件夹；
- 根据目录层级推导 title、series、writer；
- 清理作者前缀、括号原作信息和尾部标签；
- 自动检测或人工输入卷号；
- 系列级无卷号推断和小数卷重编号；
- 逐文件夹选择 `LanguageISO`，支持 `ja`、`zh` 或跳过；
- 生成 `ComicInfo.xml`；
- 图片按自然顺序写入 CBZ，并重命名为页码；
- 输出目录、冲突处理、保留/删除源文件夹；
- 更新已有 CBZ 的 `ComicInfo.xml`；
- dry-run 预览。

这些能力需要拆成 GUI 中可观察、可修改的步骤，而不是继续堆叠命令行参数。

## 4. 核心用户工作流

### 工作流 A：图片整理与命名

```text
选择来源
  ↓
扫描目录和图片
  ↓
确认目录分组
  ↓
选择或自定义排序规则
  ↓
缩略图 / 联系表 / 大图预览
  ↓
拖拽调整顺序或逐项修正
  ↓
配置命名结构
  ↓
批量生成名称并允许逐项覆盖
  ↓
检查冲突、非法字符、重复名称
  ↓
生成执行计划
  ↓
确认后执行
  ↓
结果报告 + 撤销入口
```

排序不能只提供一个下拉框。至少要支持：

- 名称自然排序；
- 修改时间、创建时间、文件大小；
- 升序/降序；
- 多级排序，例如“先目录名，再文件名”；
- 手动拖拽调整；
- 排序结果的缩略图预览；
- 对单张图片设置固定位置；
- 记录“自动排序结果”和“人工调整结果”两层状态。

命名不能只提供“前缀 + 连接符”。需要把名称拆成可编排组件，例如：

```text
[自定义前缀]
[根目录名]
[相对目录片段]
[目录序号]
[图片序号]
[原文件名]
[自定义文本]
[扩展名策略]
```

每个组件需要支持：启用/禁用、顺序调整、分隔符、大小写/空白清洗、序号位数和冲突处理。生成结果必须以表格方式展示“原路径 → 新名称”，允许单项编辑。

### 工作流 B：CBZ 制作

```text
选择漫画库
  ↓
识别漫画 / 系列 / 卷候选
  ↓
确认目录层级和分组
  ↓
确认每卷图片顺序
  ↓
编辑 title / series / writer
  ↓
选择 language：zh / ja / 其他 / 未知 / 不写入
  ↓
确认 volume：自动建议或人工输入
  ↓
编辑 CBZ 文件名和输出位置
  ↓
预览 ComicInfo.xml 与页码列表
  ↓
选择冲突和源文件保留策略
  ↓
生成执行计划
  ↓
确认后打包
  ↓
校验 CBZ + 结果报告
```

语言不是可以放心自动推断的字段。默认应当是“未设置”，并明确显示：

- `zh`：中文；
- `ja`：日文；
- 其他 ISO 639-1 代码；
- 未知；
- 不写入标签。

如果一个批次中不同卷的语言不同，必须支持逐卷设置，不能用全局选项静默覆盖。

### 工作流 C：更新已有 CBZ

更新模式需要先读取现有 `ComicInfo.xml`，将当前值和建议值并排显示，允许用户选择：

- 保留原值；
- 使用建议值；
- 清空该字段；
- 手动改写。

图片内容默认原样保留，只更新用户确认过的元数据。

## 5. 统一领域模型

技术栈未定，但领域边界先固定。建议使用以下概念，不把领域逻辑绑定到路径、Widget 或某个 GUI 框架：

```text
Workspace       用户选择的一次工作范围
SourceItem      来源文件或目录的快照
Collection      图片集合 / 漫画候选集合
PageOrder       自动排序结果 + 人工调整结果
NamingScheme    命名组件和格式化规则
NameProposal    单个文件的建议名称、来源和警告
ComicGroup      系列与卷的分组关系
ComicMetadata   title / series / writer / volume / language 等字段
PackagePlan     CBZ 输出、页码、XML 和文件策略
OperationPlan   待执行的重命名、复制、移动、打包操作
ExecutionReport 执行成功、失败、跳过和警告
UndoRecord      可撤销操作所需的逆向信息
```

关键原则：

1. **扫描、决策、执行分离**：扫描不改文件；用户决策形成计划；执行只执行已确认计划。
2. **自动建议和最终值分离**：自动解析出的值必须保留来源和置信/警告信息。
3. **路径与文档标识分离**：Windows/Linux 可使用路径，Android 需要兼容 SAF URI 或文档 ID，领域层不能假设所有资源都有普通路径。
4. **计划优先于直接操作**：任何重命名、移动、复制、删除、打包都先生成可检查的计划。
5. **结果可追溯**：记录原始标识、新标识、操作时间、策略和错误信息。

## 6. 交互设计原则

### 6.1 默认采用“建议 + 人工确认”

系统可以自动推导，但不能把推导当成事实。每个自动值都要能回答：

- 来源是什么？文件名、目录层级、图片顺序还是用户模板？
- 如果不正确，用户在哪里修改？
- 修改后是否只影响当前项，还是影响整个系列？

### 6.2 预览不是日志，而是主要工作区

预览区需要能够：

- 浏览缩略图；
- 查看大图；
- 拖拽排序；
- 编辑名称和元数据；
- 标记异常；
- 筛选未确认项、冲突项和推断项；
- 展开查看原始路径与目标路径。

### 6.3 按批次统一，也允许逐项例外

用户应该可以先对整个系列设置默认值，再对某一卷或某一张图片覆盖。逐项覆盖不能破坏批量配置，也不能在刷新扫描后无提示丢失。

### 6.4 所有破坏性动作可解释

执行前显示：

- 将修改哪些文件；
- 将创建哪些 CBZ；
- 将覆盖哪些已有文件；
- 将删除哪些源目录或文件；
- 哪些文件因权限、格式或冲突无法处理。

删除操作不应默认出现，并且需要独立确认，不与“开始执行”按钮绑定成隐式行为。

## 7. 平台策略

### Android（优先）

Android 不是后置适配项，设计阶段必须优先验证：

- SAF 目录选择和持久化授权；
- 目录树下的扫描、读取、创建、重命名和删除能力；
- 大量图片缩略图生成；
- 后台任务、进度和应用切后台后的恢复；
- 目标目录不等于真实路径时，预览和错误信息如何表达；
- 用户选中的目录权限失效后的恢复流程。

Android 第一版应优先保证“选目录 → 预览 → 手工确认 → 生成 CBZ/命名计划 → 执行”的闭环，不追求一次覆盖所有桌面能力。

### Windows

Windows 适合做完整工作台和开发验证：

- 普通文件路径访问；
- 大屏多栏布局；
- 批量拖拽和表格编辑；
- 快捷键、右键菜单和详细日志；
- 大批量文件的性能基准。

### Linux

Linux 作为第三目标平台，设计上尽量不依赖 Windows 专有路径和打包机制。需要提前避免：

- 把创建时间当成所有平台都可靠的字段；
- 把回收站、文件权限和路径分隔符写死；
- 依赖只在 Windows 有效的原生文件选择器行为。

## 8. 技术栈决策门槛

> 状态(2026-09-08):**决策已就地锁定于 §8.1**(未另立 ADR);本节的验证门槛与三 spike 建议是决策前评估的原始记录(2026-09-07 时点),执行情况与顺序调整见 §8.2、§8.3。

当前不做“先选框架再适配需求”。技术方案必须通过以下验证后再定：

1. 能否在 Android 上稳定访问 SAF 文档树；
2. 能否支持高效缩略图和大图预览；
3. 能否实现拖拽排序和表格/表单编辑；
4. 能否运行后台任务并可靠报告进度；
5. 能否实现计划、撤销、错误恢复和离线运行；
6. 能否覆盖 Windows、Android、Linux 的发布链路；
7. 核心领域逻辑能否独立测试；
8. 个人学习成本和长期维护成本是否可接受。

建议做三个小型技术验证，而不是直接开完整工程：

- **Android 文件访问 spike**：选目录、持久化权限、遍历图片、读取字节、创建文件、重命名。
- **交互原型 spike**：缩略图网格、拖拽排序、名称表格、CBZ 元数据编辑和执行计划预览。
- **核心处理 spike**：从 Python 样例中抽取排序、命名、ComicInfo.xml、CBZ 生成的最小输入输出测试。

三个 spike 的结果再决定使用 Flutter、Qt/QML、Compose Multiplatform、Tauri、Slint、原生方案或其他组合。**实际决策已按 §8.2 的顺序调整提前执行并就地记录(§8.1),未另立 ADR**;此后如确需新增 ADR(例如 D1 触发证伪条件 ③ 后重审 CMP),先与用户确认再落盘。

### 8.1 技术栈决策(2026-09-08 锁定)

> **✅ 已锁定:Flutter + Dart**。D2 SAF spike(§8.3)5/5 验收通过,证伪条件 ①② 均已排除(Android 16 真机,一加 Ace 3 Pro);桌面交互风险(D1)按 §8.2 判定为"已知可做",不再构成换栈理由。CMP 对照、Tauri 本轮不启用;若 D1 桌面表格/拖拽触发证伪条件 ③ 且修复成本不可接受,再重审 CMP。
> 历史:2026-09-07 候选排序见下(归档),环境已装 Flutter 3.47.2 属事实倾向,spike 已按同等标准检验通过,非"工具就绪"主导。

| 排序       | 方案                               | 判断                                                                                                                                                                 |
| ---------- | ---------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **已锁定** | **Flutter + Dart**                 | D2 spike 5/5 过:SAF 闭环(选目录→持久授权→遍历→读→建/改名/删)全通,851 张 <1s,跨进程授权恢复无弹窗。唯一真风险(SAF)已用自写 Kotlin channel 验证消除                    |
| 对照(备用) | **Compose Multiplatform + Kotlin** | Android 端即原生 Jetpack Compose,SAF/ContentResolver 直达,是唯一硬胜出项;代价:KMP/Gradle 工程复杂度高、桌面打包生态较新。仅当 D1 触发证伪条件 ③ 且不可修复时启用对照 |
| 暂排除     | **Tauri(Rust + Web)**              | 桌面成熟,但移动端为 2.x 新路径,与"Android 优先"相悖;SAF 无成熟路径;换栈须以 Rust 重写全部 Python 行为参考,回归基准作废                                               |
| 本轮未进入 | Qt/QML、Slint、纯原生              | 单人维护面/生态/学习成本不占优;不排除证伪后重审                                                                                                                      |

### 8.2 spike 判定与执行顺序(2026-09-07)

**证伪条件判定(2026-09-08)**:D2 spike 已排除 ①②,维持 Flutter(详见 §8.1/§8.3);仅剩 ③ 留待 D1 桌面交互验证——若 D1 中数据表格/拖拽类交互在三方包 + 自建基础上仍无法满足验收,才启用 CMP 对照 spike。

证伪条件清单(历史记录):

1. 无法稳定完成"选目录 → 持久授权 → 递归遍历 → 读字节 → 创建/重命名"闭环,且社区包 + 自写 channel(LocalSend 同款路线)的修复成本不可接受;← **2026-09-08 已排除**
2. 千级缩略图在目标机型出现不可接受的解码性能或内存问题;← **2026-09-08 已排除(851 张 <1s)**
3. 桌面数据表格类交互在三方包 + 自建基础上仍无法满足 D1 验收。← **2026-09-10 D1 走查实证:实质上已排除**——三方包(`reorderable_grid_view 2.2.8`)桌面端 drop 不落地、拖拽视觉需大量兜底;改用自研(`Draggable`/`LongPressDraggable` + `DragTarget`,见 §12.5)后网格/列表拖拽真实落地、点击与滚动无冲突。待 D1 验收清单收口时正式判定并回写本节。

**顺序调整**:D2 的 SAF spike 可先于 D1 完整交互原型执行——平台风险(可推翻选型)高于交互风险(拖拽/表格在候选方案上均为已知可做),先验证可避免 D1 原型资产因换栈浪费。D1 原型在 spike 通过后启动,此时栈已锁,原型不重做。

### 8.3 D2 SAF spike 执行方案(2026-09-07 记录,环境验收后启动)

**状态**(2026-09-08):✅ **D2 SAF spike 验收全部通过,证伪条件 ①② 排除,正式锁栈 Flutter**。工程建于 `C:\Personal\pixfold-spike`(临时,不入库),一加 Ace 3 Pro(Android 16/API 36)无线 adb 真机验收。**验收清单 5/5 全过**:

- ① `openTree` 12s(已授权)/ 32s(首次)+ `takePersistableUriPermission`
- ② `listImages` 851 张 / 962ms、24 张 / 28ms(已授权目录二次调用)
- ③ `readBytes` 3.4MB JPEG / 42ms,头 `ffd8ffe1...` 真 JPEG 校验通过
- ④ `createAndWrite→renameDoc→deleteDoc` 总 206ms(其中 createDocument 须 **tree URI → document URI** 转换,见 §12.5 踩坑)
- ⑤ **跨进程持久授权**:SharedPreferences 存 tree URI,杀进程重开自动恢复 → 直接列出、全程无弹窗(系统层持久授权生效,未走 SAF 重选;若授权失效会抛 SecurityException 而非静默) | 千级缩略图 851 张 <1s 无压力

**收束**(2026-09-08):验收清单 5/5 全过,无"待补验"项;证伪条件 ①② 排除,Flutter 已锁定(§8.1)。D2 至此完成,可启动 D1 交互原型(栈已锁,原型资产不浪费)。spike 工程 `C:\Personal\pixfold-spike` 留作 Android SAF 通道参考实现(正式工程可直接迁移 Kotlin channel 代码)。

**目标**:最小工程验证 Android SAF 全闭环,用于判定 §8.2 证伪条件 ①②;不做任何业务 UI。

**工程形态**:

- 目录:`C:\Personal\pixfold-spike`(仓库外兄弟目录,不污染设计仓库;非正式工程);
- `flutter.bat create --platforms=android,windows`,仅作通道验证,不进入 D3 工程。

**Kotlin(MainActivity,MethodChannel `pixfold/saf`)**:

| 方法             | Android 实现                                                                                                                                                                                                                              |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `openTree`       | `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`                                                                                                                                                                              |
| `listImages`     | `DocumentFile.fromTreeUri` 递归,按扩展名筛图,返回相对路径 / uri / size                                                                                                                                                                    |
| `readBytes`      | `contentResolver.openInputStream`                                                                                                                                                                                                         |
| `renameDoc`      | `DocumentsContract.renameDocument`                                                                                                                                                                                                        |
| `createAndWrite` | **先把 tree URI 经 `getTreeDocumentId` + `buildChildDocumentsUriUsingTree` 转为根目录的 document URI**,再 `DocumentsContract.createDocument` + `openOutputStream`(一加/部分国产 ROM 严格校验,直接传 tree URI 会抛 `Invalid URI`,见 §12.5) |

**Dart**:`saf_channel.dart` 封装 + 测试按钮序列:选目录 → 列前 N 张 → 读第 1 张 → 复制改名 → 删除副本(每步回显)。

**验收清单**(全部通过 = 证伪条件 ① 排除,维持 Flutter 主候选):

1. 授权后重启进程仍有效(持久授权);
2. 500+ 图目录递归遍历耗时可接受;
3. 读取大图字节数与平台侧一致;
4. 重命名 / 创建 / 删除后回读一致;
5. 顺带测证伪条件②:千级缩略图滚动(`ContentResolver.loadThumbnail` 或 `BitmapFactory inSampleSize`)。

**触发**:任一验收失败且"社区包 + 自写 channel"修复成本不可接受 → 启动 CMP 对照 spike(§8.2)。

## 9. 设计阶段路线

> 进度(2026-09-08):D0 完成;D2 的 Android SAF spike 已按 §8.2 提前执行并验收(§8.3),证伪条件 ①② 排除、Flutter 锁定(§8.1);**当前主线为 D1 交互原型**,后续 D2/D3 的完整平台与领域核心工作尚未启动。
> **阶段门槛(2026-09-10 补,可判定化)**:每阶段的"退出条件"必须达成才进入下一阶段,避免"看起来做完了"就往前推。状态与退出条件一览:

| 阶段                    | 状态                    | 退出条件(可判定)                                                        |
| ----------------------- | ----------------------- | ----------------------------------------------------------------------- |
| D0 需求与样例固化       | ✅ 完成                 | 脚本功能矩阵 + 样例目录 + 危险默认值标注(§3)                            |
| D1 交互原型             | 🔄 进行中(Windows 走查) | 下方《D1 验收清单》20 项在 **Windows + Android 双端**全部勾满            |
| D2a SAF 闭环 spike      | ✅ 完成(提前,2026-09-08) | §8.3 五项验收 5/5                                                       |
| D2b 完整平台能力        | ⬜ 待办                 | 缩略图策略 / 后台任务与进度 / 授权失效恢复,三项真机可复现且有失败路径提示 |
| D3 领域核心与适配层     | ⬜ 待办                 | 排序/命名/元数据/打包四类核心逻辑单测通过,且不依赖 UI 与平台层(§5)       |
| D4 MVP 闭环             | ⬜ 待办                 | Android 与 Windows **各完成一次真实文件**的端到端闭环(预览→排序→元数据→产出) |
| D5 增强功能             | ⬜ 待办                 | 按需排期,不阻塞 D4                                                      |

> **决策门(2026-09-10 明确)**:① **正式工程创建**的触发 = D1 退出条件达成(D1 之前只维护设计文档 + 验证工程,§4);② 换栈(CMP 对照)的触发 = §8.2 证伪条件 ③,**当前已被 D1 实证排除**(三方包 drop 不可落地 → 自研方案满足桌面拖拽/表格交互),待 D1 验收通过时正式回写 §8.2。
> D1 执行注(2026-09-09):D1 原型工程已建 **`C:\Personal\pixfold-d1`**(仓库外,与 spike 同级;不连真实文件系统,确定性 mock 数据)。范围 = 本节 D1 六项交付;验收平台 **Windows + Android 双端**(用户 2026-09-09 决策);拖拽/表格实现策略 = **内置优先**(曾引入 `reorderable_grid_view 2.2.8` 做对照,2026-09-10 实测其桌面端 drop 不落地,已弃用,详见 §12.5;**当前零三方依赖,拖拽为自研**)。领域逻辑(自然排序/多级排序/命名求值/冲突检测/ComicInfo.xml/页码重编号)以可测试纯 Dart 实现,含 14 项测试(3 项拖拽手势回归 + 1 项 UI 重建回归)。
> 走查修复注(2026-09-09,同日晚):Windows 端用户走查首轮反馈已闭环——① 大图预览改为自适应窗口 + InteractiveViewer 缩放/平移/方向键翻页;② 网格拖拽修复:三方包默认长按 500ms 才拖,桌面端设 `dragStartDelay: Duration.zero`(移动端保留 ~400ms 长按);③ 中文发虚两层根因:Impeller SDF 文本渲染(`windows/runner/main.cpp` 禁用以回退 Skia)+ CJK 字体回退链落老字体(主题显式 `fontFamilyFallback: ['Microsoft YaHei UI','Microsoft YaHei',...]`,用户确认有效);另全局 `textScaler: linear(1.15)` 放大字号;④ 网格拖拽黑边:`dragWidgetBuilderV2` 用 `Material(elevation:8, surfaceContainerHighest, 圆角)` 包络拖拽物,`placeholderBuilder` 用带子级 `Container` 圆角描边占位(**DecoratedBox 无子元素 = 0x0 不可见,踩坑**)。拖拽时控制台 `Failed to update ui::AXTree` 为 Windows 无障碍桥已知噪音,不影响渲染。D1/D2 验证工程**不入版本控制**(用户约定,与 spike 一致)。D1 按 §9 验收点 + §10 功能验收走查通过前不宣称完成。
> 走查修复注(2026-09-10):**拖拽改自研**(三方包 drop 不落地 → 弃用,零三方依赖):整卡拖拽(桌面立即拖、移动长按拖)、**松手落地**语义(不做悬停实时重排,避免抖动)、`PageOrder.moveItemTo` 统一重排入口;修复列表行 0 高度(`StackFit.expand` 遇无界高度)与网格/列表拖拽不落地两个 bug;新增 3 项手势回归测试(测试确实先于真机抓到问题),test 10/10。详见 §12.5。
> 走查状态(2026-09-09 深夜):**Windows 端走查进行中**——首轮反馈(预览/拖拽/字体/黑边)已修复待复验,全量验收清单未走完;**Android 端尚未启动测试**(Windows 验证通过后再接真机)。

### D0：需求与样例固化

- 整理两个脚本的功能矩阵；
- 建立真实目录样例和异常样例；
- 明确哪些字段必须人工确认；
- 明确 Android 优先闭环；
- 把当前脚本中的危险默认值标注出来。

### D1：交互原型

先不连接真实文件系统，完成：

- 图片列表/缩略图预览；
- 拖拽排序；
- 命名结构编辑器；
- CBZ 元数据编辑器；
- 执行计划预览；
- 冲突和警告展示。

验收标准：用户可以完整走完两个工作流，并且每个必须人工输入的字段都有明确入口。

#### D1 验收清单(2026-09-10 补,走查时逐条打勾;§9 判据的可执行化)

> 用法:双端各走一遍,逐条勾选;任一条不通过就记为待修项,修复后**必须自证**(见下方"修复自证三层")再复勾。全部勾满 = D1 退出条件达成。

| #  | 验收点                                                     | 依据        | Windows | Android |
| -- | ---------------------------------------------------------- | ----------- | ------- | ------- |
| 1  | 缩略图网格/列表双视图可切换,缩略图渲染正确                 | §9          | ☐       | ☐       |
| 2  | 大图预览:自适应窗口、可缩放、可拖动平移、可翻页            | §6.2        | ☐       | ☐       |
| 3  | 网格拖拽真实改变顺序(松手落地)                             | §9/§10      | ☐       | ☐       |
| 4  | 列表拖拽真实改变顺序                                       | §9/§10      | ☐       | ☐       |
| 5  | 人工调整有可见标记,且可一键重置回自动排序                  | §4          | ☐       | ☐       |
| 6  | 固定位置(图钉)项在重新应用排序后保持原位                   | §4          | ☐       | ☐       |
| 7  | 多级排序规则可增删改;批次默认与"本组独立"例外并存          | §4          | ☐       | ☐       |
| 8  | 命名结构:组件启用/顺序/分隔符/大小写/补零/扩展名策略可调   | §4          | ☐       | ☐       |
| 9  | 命名建议表:原路径→新名称,可逐项覆盖,且改结构后覆盖不丢失   | §6.3        | ☐       | ☐       |
| 10 | 冲突与警告可见:重名、非法字符、大小写冲突、超长            | §9/§10      | ☐       | ☐       |
| 11 | 逐卷元数据 title/series/writer/volume 可改,建议值带来源    | §4/§6.1     | ☐       | ☐       |
| 12 | 语言默认"未设置"必须人工确认;支持逐卷不同,写入/不写入可选 | §4          | ☐       | ☐       |
| 13 | 批量设置不覆盖逐项例外(被手工改过的字段保持)               | §6.3        | ☐       | ☐       |
| 14 | ComicInfo.xml 预览与页码列表预览(含重编号)一致             | §9/§10      | ☐       | ☐       |
| 15 | 执行计划预览:操作清单 + 冲突/跳过原因                      | §6.4        | ☐       | ☐       |
| 16 | 危险动作默认关闭,且需独立二次确认(删源/清理空目录)         | §2/§6.4     | ☐       | ☐       |
| 17 | 执行报告(成功/跳过/失败)+ 撤销入口                          | §9/§10      | ☐       | ☐       |
| 18 | 必须人工输入的字段都有明确入口(逐项核对无遗漏)             | §9          | ☐       | ☐       |
| 19 | 两条工作流均可从首页完整走通到结果页                       | §9          | ☐       | ☐       |
| 20 | 工程健康:analyze 零告警;测试全绿(含 UI 层断言)             | 流程约定    | ☐       | ☐       |

**修复自证三层(2026-09-10 固化,凡交互/数据改动必做)**:

1. **逻辑层**:领域逻辑改动用单元测试钉死语义(如 `moveItemTo` 的"前移/拖到末尾/拖到自己格不变");
2. **UI 层**:涉及界面的改动必须断言"界面真的变了"——只断言数据的测试会放过整类 bug(拖拽 4 轮的根因就是数据对、界面不动);
3. **真机层**:GUI 问题不靠猜,用"埋点日志 → 模拟输入(pywin32 SendInput)→ 像素/快照对比"实证,方法详见 §12.5。

> 教训依据:三轮走查共修 8 项(预览溢出/拖拽不落地/中文发虚/拖拽黑边/列表 0 高度/三方包弃用/字号/通知链断裂),其中"拖拽不改变顺序"反复 4 轮才定位——前 3 轮都停在"测试通过"的假象上。

### D2：平台能力验证

优先 Android SAF,再验证 Windows 和 Linux 文件访问。重点验证最容易推翻技术选型的能力,不先做完整业务。

顺序注(2026-09-07):SAF spike 可提前至 D1 完整原型之前执行,判定与顺序见 §8.2。

**拆分为 D2a / D2b(2026-09-10 按事实对齐)**——原来把"SAF 可行性 spike"与"完整平台能力"混作一个 D2,而 spike 已于 2026-09-08 提前完成,导致路线图与实际进度不符:

- **D2a · SAF 闭环 spike** ✅ **已完成(2026-09-08,提前执行)**:选目录 → 持久授权 → 递归遍历 → 读字节 → 创建/改名/删除,真机 5/5 验收(§8.3);产物 `C:\Personal\pixfold-spike` 留作 Kotlin channel 参考实现。**本阶段不再重复,验收记录归档。**
- **D2b · 完整平台能力** ⬜ **待办**(D1 收口后启动):
  - Android:千级缩略图**生成策略**(`ContentResolver.loadThumbnail` vs `BitmapFactory` + `inSampleSize` + 缓存/并发上限)、**后台任务与进度**(切后台恢复、进度上报)、**授权失效恢复流程**(SecurityException → 引导重选目录);
  - Windows / Linux:文件访问与批量预览流程(Linux 不依赖 Windows 专有路径与回收站语义,§7);
  - 退出条件:上述三项在目标机型可复现验证,且失败路径有明确的用户可理解提示。

### D3：领域核心与适配层

将排序、命名、元数据、ComicInfo.xml、CBZ 生成、计划和报告拆成可测试核心；平台层只负责资源访问、缩略图、权限和任务生命周期。

### D4：MVP 闭环

优先交付：

- Android：单目录/单系列，图片预览与人工排序，逐卷元数据，CBZ 生成；
- Windows：同一流程 + 完整批量命名和计划执行；
- Linux：完成核心流程和基本文件访问。

### D5：增强功能

- 多系列批处理；
- 命名模板保存与复用；
- CBZ 更新模式；
- 操作历史和撤销；
- 失败任务重试；
- 更丰富的图片格式和元数据；
- Windows/Linux 高级批量快捷操作。

## 10. 验收标准

> 注(2026-09-10):本节是**最终产品**的验收标准;§9《D1 验收清单》是它在 D1 阶段的可执行化(原型不连真实文件系统,故"数据正确性"里的 Python 回归比对、CBZ 可被阅读器打开等条目留到 D3/D4 验收,D1 只验交互与领域逻辑)。

### 功能

- 用户能看到并调整图片最终顺序；
- 用户能看到并修改每个文件的最终名称；
- 用户能逐卷设置 title、series、writer、volume、language；
- 用户能看到 ComicInfo.xml 和 CBZ 内页顺序预览；
- 所有文件操作都能在执行前预览；
- 默认不会删除源文件；
- 冲突、失败和跳过项都有明确结果。

### 数据正确性

- Python 脚本现有样例作为回归参考；
- 相同输入和相同人工决策下，核心结果可重复；
- ComicInfo.xml 字段和页码数量一致；
- CBZ 可被目标漫画阅读器打开；
- 执行中断后不会留下无法解释的半成品，或能明确标记待恢复状态。

### 平台

- Android 真机完成 SAF 选目录、授权、扫描、预览、打包闭环；
- Windows 完成大批量文件和详细预览流程；
- Linux 完成核心操作和基础发布验证。

## 11. 当前待确认问题

1. 图片排序是否需要“每组一套排序规则”，还是一个批次统一规则？
2. 手工拖拽后的顺序是否要自动写入文件名序号？
3. 命名模板是否需要保存为可复用配置？
4. CBZ 语言除 `zh`、`ja` 外，是否需要完整 ISO 639-1 列表？
5. 是否需要支持封面页、双页、彩页、广告页等 Page 类型？
6. 删除是否只允许移动到回收站/系统废纸篓，而不是直接删除？
7. 是否需要把一次完整工作流保存为项目文件，以便稍后继续？
8. Android 首版是否先限制为单一授权目录，暂不支持跨目录批处理？
9. **拖拽落地方式**：D1 现实现为"松手落地"（拖动中仅高亮目标格，避免悬停实时重排的 hit test 抖动导致顺序来回换位）；是否需要"拖动中实时让位"的观感？（2026-09-10 实现取舍，等走查反馈）
10. **排序与命名的联动边界**：图片序号取自第 1 步当前顺序（含人工调整），人工拖拽后是否要同步刷新命名建议的提示强度？（2026-09-10 记录）

## 12. 开发环境实况与 Android 验证准备(2026-09-07 由 s_handoff.md 并入)

> 来源:原 `s_handoff.md`,2026-09-07 用户决定并入本文件后删除原文件。此后环境与验证准备以本节为准。

### 12.1 一句话现状

Windows 开发机工具链已基本就位(git / VS Code / scoop / winget / mise / **Flutter 3.47.2** / **VS Build Tools 18.9**),Android SDK 侧亦已就绪(2026-09-07:JDK、android-clt 完整 SDK、ANDROID_HOME、JAVA_HOME、独立 adb 卸载全部完成)。PixFold 仍处设计阶段,下一步先做 spike 与交互原型,不直接创建正式工程。

### 12.2 环境实况清单(2026-09-07 更新)

| 组件                     | 状态          | 版本 / 位置                                                                                                                                                                                                                         |
| ------------------------ | ------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Git                      | ✅            | 2.55.0(scoop)                                                                                                                                                                                                                       |
| VS Code                  | ✅            | 1.136(scoop apps/vscode)                                                                                                                                                                                                            |
| scoop                    | ✅            | main / extras / versions / sysinternals / nerd-fonts 桶                                                                                                                                                                             |
| winget                   | ✅            | v1.29.290                                                                                                                                                                                                                           |
| mise                     | ✅            | 2026.9.1(全局配置 `C:\Users\Administrator\.config\mise\config.toml`)                                                                                                                                                                |
| **Flutter SDK**          | ✅            | **3.47.2 / Dart 3.13.2**,mise 全局管理,**版本请求 `latest`**(registry http 后端,2026-09-07 切换;旧自定义源备份于 `config.toml.bak-20260907`,回滚可恢复)<br>路径:`C:\Users\Administrator\AppData\Local\mise\installs\flutter\3.47.2` |
| **VS Build Tools**       | ✅            | **18.9.12112.369**(VS 2026,v145 工具集)<br>路径:`C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools`                                                                                                                      |
| JDK                      | ✅            | temurin-17.0.20+101,请求 `temurin-17`(config.toml);JAVA_HOME 已 setx(2026-09-07)                                                                                                                                                    |
| android-clt(Android SDK) | ✅            | 15859902;`current` 即**完整 SDK**:platforms;android-36、build-tools;36.0.0、platform-tools、cmdline-tools/latest、licenses 均就绪                                                                                                   |
| ANDROID_HOME             | ✅            | manifest `env_set` 自动写入安装目录;2026-09-07 手动 setx 冗余确认(值一致)                                                                                                                                                           |
| adb                      | ✅ 已卸独立版 | 2026-09-07 卸载 scoop adb(37.0.1 / 旧 37.0.0),统一用 SDK platform-tools                                                                                                                                                             |
| WSL                      | ✅(远期用)    | Debian 13 (trixie),podman 5.4.2 ——留给 Linux 目标预览/容器构建                                                                                                                                                                      |

> 机制注(2026-09-07,读 manifest 核实):`android-clt`(15859902)的 manifest 自带 `env_set:{ANDROID_HOME: <安装目录>}`、`env_add_path:[cmdline-tools/latest/bin, platform-tools]`,并把 SDK 组件目录(add-ons/build-tools/cmake/extras/licenses/ndk/patcher/platforms/skiaparser/sources/system-images)列入 `persist`(current 下为指向 `scoop\persist\android-clt` 的链接)。含义:装完即全局可用 adb / sdkmanager / avdmanager,组件目录在 scoop 更新时保留。**adb 裸命令可用来自 PATH 的 platform-tools,与 ANDROID_HOME 无直接关系**;环境变量写入后需**新终端**才生效(旧进程看不到)。注意:**包本身只含 cmdline-tools**,platform-tools 目录是 pre_install 建的"空壳 PATH 目标",adb/platforms/build-tools 均为 2026-09-03 由 `sdkmanager` 装入;platform-tools 不在 persist,`scoop update android-clt` 后若 adb 消失,用 `sdkmanager "platform-tools"` 补装(platforms/build-tools 等 persist 组件不受影响)。

> 项目级配置(2026-09-07 更新,2026-09-08 技术栈锁定后仍适用):仓库根 `.mise.toml` **保持纯配置无注释**,说明统一在本注维护。内容:`flutter = "3.47.2"`(**项目固定**当前已验证版本,全局仍 latest;技术栈已锁定为 Flutter(§8.1),工程环境需要稳定可复现;升级 = 改本文件版本号 → `mise install`)与 `java = "temurin-17"`(Android/Gradle 构建所需)。**声明 ≠ 已安装**:新机器上需 `mise install` 才按声明下载。**全局 config.toml 已由 chezmoi 纳管**(源 `~/.local/share/chezmoi/dot_config/mise/config.toml`),改全局声明请编辑 chezmoi 源后 `chezmoi apply`,**勿用 `mise use -g`**(绕过 chezmoi 造成源与实际漂移)。**边界约定**(用户级,勿破坏):Python 由 uv 管理、Node 由 fnm 管理,mise 均不接管。工具版本请求变更请同步维护本节(§12.2)。跨平台调用约定见 §12.5。

### 12.3 Android 平台验证准备(设计阶段优先)

> **进度(2026-09-07)**:下方 ①–⑥ 已全部执行完毕(含 ANDROID_HOME / JAVA_HOME 落盘、卸载独立 adb)。**⑦ 验收已于 2026-09-07 通过**(用户 pwsh 实测 `flutter doctor`:Flutter 3.47.2 ✓ / Android toolchain ✓ / VS ✓ / 设备在线;Windows 调用约定见 §12.5:pwsh 已配适配函数可直接敲 `flutter`,git-bash/CI 用全名 `flutter.bat`)。

```powershell
# ① JDK 17(sdkmanager 是 Java 程序,必须先有它)
# 注:曾用 `mise use -g java@temurin-17`;2026-09-07 起全局声明由 chezmoi 纳管,
#     等效做法 = 编辑 chezmoi 源 dot_config/mise/config.toml → apply → mise install
mise install

# ③ ANDROID_HOME:无需手动设置(android-clt manifest env_set 自动写入,见 §12.2 机制注;历史指引为 setx)

# ② Android 命令行工具
scoop install android-clt

# ④ SDK 组件(Flutter 3.47.2 默认 compileSdk=36)
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"

# ⑤ 同意许可证(之后 Gradle 能自动补装缺失组件)
flutter.bat doctor --android-licenses    # 一路 y

# ⑦ 体检(验收标准)
flutter.bat doctor -v
# 期望:Flutter ✓ / Android toolchain ✓ / Visual Studio ✓
```

**环境准备完成** → 插上 Android 16 真机(开发者选项 + USB 调试),先只验证设备和 SAF 所需基础能力:

```powershell
flutter.bat devices        # 能看到手机
flutter.bat doctor -v      # 确认 Android toolchain 可用
```

正式工程框架暂不创建。先完成 spike 与交互原型,再根据 Android SAF、缩略图、拖拽排序和后台任务验证结果决定技术栈(见 §8)。

### 12.4 每日必用命令速查

```powershell
flutter.bat doctor -v        # 环境体检(第一排查手段)
flutter.bat devices          # 列出可用设备
flutter.bat run              # 热重载开发(r 热重载 / R 全重启 / q 退出)
flutter.bat create --platforms=windows,android app  # 技术栈确定后再创建正式工程
sdkmanager --list        # 看 SDK 组件可用版本
mise ls                  # 看 mise 管的工具版本
```

### 12.5 踩坑速查

- **Visual Studio ≠ VS Code**:编译 Windows 桌面要的是 Build Tools 的 C++ 工作负载,VS Code 只是编辑器。
- **flutter doctor 认 SDK 目录结构**(`$ANDROID_HOME\platform-tools\adb` 等),不认 PATH 上的散装 adb → platform-tools 必装。
- **双 adb 会打架**:scoop adb 与 SDK platform-tools adb 版本漂移 → 报 `adb server version mismatch`,已定方案是卸 scoop 版(2026-09-07 已卸)。
- **licenses 不点** → Android toolchain 永远 ❌;`flutter.bat doctor --android-licenses` 一路 y。
- **Windows 下 mise 无法为 flutter 生成可用 shim**(2026-09-07 实证):registry http/vfox 后端的 binary 元数据都指向无扩展名 `bin\flutter`(官方 SDK 里的 shell 脚本,非 Windows 可执行)→ mise 生成的 `flutter.exe` shim 报 `No executable found…`;pwsh 命令解析同样会误选该"文档文件"。**已解决(2026-09-07)**:pwsh profile 加 `function global:flutter`(chezmoi 源 `dot_config/powershell/profile.ps1`,每次 apply 自动同步至 `Documents\PowerShell\profile.ps1`)——Windows 检测到 `flutter.bat` 自动转调,Linux/macOS 直接原生,**交互终端统一敲 `flutter` 即可,跨平台命令一致**。`dart` 命令同样适配(随 Flutter 捆绑)。仍须用 `.bat` 全名的场景:git-bash、CI/脚本、`mise x flutter -- flutter.bat`。IDE 用 VS Code 的 `dart.flutterSdkPaths` 填 `mise where flutter` 直连 SDK。删坏 shim 无效(reshim 会重建),但 pwsh 函数优先级高于 PATH 中的 shim,不受影响。
- **本机(R7P21)访问 Google 系仓库需显式代理**(2026-09-08):Windows 主力机 R7P21 需 Clash 代理(127.0.0.1:7897);另一台机 Slayer 为透明代理无需配置。Gradle/Java **不读** `HTTP_PROXY` 环境变量,须写 `~/.gradle/gradle.properties` 的 `systemProp.http(s).proxyHost/Port`(已配,仅 R7P21 用户级,不随工程文件);否则 gradle wrapper 下载发行版与依赖解析会超时。工程内不写死任何代理/镜像配置。
- **flutter doctor 的 `flutter/dart on your path resolves to ...http-tarballs...` 为 cosmetic 警告**(2026-09-07):SDK 真实安装在 mise `http-tarballs` 缓存,`installs\flutter\3.47.2` 是 symlink,mise activate 注入 PATH 的是解析后的真实路径,与 doctor 判定的 checkout 不一致。功能无影响,可忽略。
- **mise 两种 Windows shim 模式对 flutter 均无解**(2026-09-07 实验):`windows_shim_mode=exe`(默认)生成的 `flutter.exe` shim 报 `No executable found`;切 `file` 模式生成的 `flutter.cmd` + 无扩展 bash shim 同样失败(bash shim 内部仍解析 `bin/flutter`),且无扩展 bash shim 会干扰 pwsh 命令解析(误选为 document)。结论:**mise 在 Windows 上对"入口为无扩展脚本 + .bat"类 SDK 的支持是结构性缺口**,勿再尝试;Windows 交互一律靠 pwsh 函数(见上条),已恢复 `exe` 模式。
- **compileSdk 不必 ≥ 手机版本**:手机 Android 16 = API 36,装 `platforms;android-36` 恰好对齐;以后想用新 API 再追加装更高 platform(可多版本并存)。
- **mise 装 Flutter 若在 Windows 报错** → 回退 `scoop bucket add extras && scoop install flutter`(本次未遇到,mise 3.47.2 一次成功)。
- **VS Code 报找不到 Flutter** → 设置 `dart.flutterSdkPaths` 填 `mise where flutter` 的输出。
- **Android SAF:tree URI 不能直接当 parent 传给 `DocumentsContract.createDocument`**(2026-09-08 D2 spike 实证):一加/部分国产 ROM 严格校验 parent 必须是 document URI,直接传 tree URI 会抛 `IllegalArgumentException: Invalid URI`(AOSP 行为宽松,国产 ROM 收紧)。**必须先转换**:
  ```kotlin
  val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
  val parentDocUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
  DocumentsContract.createDocument(resolver, parentDocUri, mime, name)
  ```
  读/列/改名/删直接用 document URI 无需转换;只有"在树里建子项"才需要这步。`DocumentFile.fromTreeUri(ctx, treeUri).createFile(...)` 内部用同样的 tree URI 路径,在严格 ROM 上也会同样失败,务必手动转换。
- **Flutter Windows 默认 Impeller(OpenGLES + SDF 文本渲染)→ 中文小字号发虚**(2026-09-09 D1 原型实测,Flutter 3.47.2):SDF 字形在 CJK 小字号上偏软,已知特性。**解法**:`windows/runner/main.cpp` 中 `project.set_impeller_switch(flutter::ImpellerSwitch::Disabled)` 回退 Skia(文本明显变锐利)。注意:① 对 exe 传命令行 `--no-enable-impeller` **无效**(Windows embedder 不解析进程命令行,只能通过 C++ wrapper 的 DartProject API 或 Android 端 flutter run 开关);② main.cpp 等 MSVC 源码注释**必须纯 ASCII**——中文注释触发 C4819(GBK 代码页)并导致构建失败。
- **Flutter Windows CJK 字体回退链落到老字体(宋体等)→ 文字持续发虚**(2026-09-09 D1 实测,Impeller 已关仍偏虚的剩余主因):不指定字体时 DirectWrite 回退选择不稳定。**解法(最终修复,用户确认效果)**:`ThemeData.fontFamilyFallback: ['Microsoft YaHei UI', 'Microsoft YaHei', 'Noto Sans CJK SC']`(明暗两套主题都加)。另有辅助缓解:全局 `textScaler: TextScaler.linear(1.15)` 放大字号、UI 字号下限 ≥12px。Android 端无此问题(系统字体链正常)。
- **拖拽排序:D1 结论 = 自研,弃用三方包**(2026-09-10 用户决策)。`reorderable_grid_view 2.2.8` 实测:桌面端能拖、有占位反馈,但 **drop 不落地(顺序不变)**;且拖拽物无 Material 包络(Card surface 铺成硬方块)、placeholder 需自行兜底——调优成本超过自建。**改为纯 Flutter 内置自研**(`Draggable`/`LongPressDraggable` + `DragTarget`),D1 原型自此**零三方依赖**。自研三条实证经验:
  1. **整卡可拖不会抢 tap**:`Draggable` 内部是 `ImmediateMultiDragGestureRecognizer`,**指针移动超过 slop 才接受手势**,静止点按仍是 tap(点击看图/按钮照常)。整卡拖拽可行,无需加拖拽把手。
  2. **`StackFit.expand` 在无界高度容器里会把子项压成 0**:`ListView` 给的是 `height: 0..∞`,expand 把无界约束透传给子项 → 行高 0、整行不显示。凡"网格 + 列表"通用组件,**用 `StackFit.passthrough`**(网格 tight / 列表内在高度都兼容)。
  3. **不要做"悬停实时重排"**:拖拽期间指针微动会反复触发 onMove → 顺序来回换位,用户看到的是"拖了但没变"。修法是**松手落地**(拖动中仅高亮目标格,`onAccept` 才重排);`PageOrder.moveItemTo(id, targetIndex)` 作为唯一重排入口。
- **拖拽功能必须用 widget test 自测**(2026-09-10 教训,用户反馈"能拖但不改顺序"两轮):GUI 交互无法靠日志/截图验证,须用 `tester.startGesture` → `moveBy`(超 slop 启动)→ `moveTo`(目标)→ `up` 模拟完整手势,并用 `debugDefaultTargetPlatformOverride` 分别覆盖桌面(`Draggable`)与移动(`LongPressDraggable`)两条分支;断言"拖动中顺序不变 + 松手后落在目标下标"。注意该 override **必须在测试体内复位**,`addTearDown` 会触发 `foundation debug variable was changed by the test`。
- **★ChangeNotifier 组合:子 notifier 的通知必须显式转发**(2026-09-10 D1 实测,最隐蔽的一个):`WorkflowAController` 只把 `PageOrder` 放进 `orders` map,但拖拽调用的是 `PageOrder.moveItemTo()` → `PageOrder.notifyListeners()`,而 `AnimatedBuilder` 只监听 controller → **数据重排成功、界面永不重建**,表现为"拖了没反应"(日志里 drop/enabled/index 全部正常,极易误判为拖拽组件问题)。**修法**:controller 构造时对每个子 notifier `addListener(notifyListeners)`,dispose 时 `removeListener` + dispose;`AnimatedBuilder` 只监听 controller。同类隐患:`ComicVolume`(元数据编辑)同理,已一并修复。**教训:凡"数据对但界面不动",先查通知链是否断裂;子 notifier 必须显式转发。**
- **GUI 交互问题的真机实证法**(2026-09-10,可复用):① 代码埋点(交互事件日志 + 构建顺序快照,用去重签名避免刷屏);② 用 **pywin32 SendInput**(`SetCursorPos` + `mouse_event` 按下/分步移动/松开)模拟真实鼠标驱动真机窗口;③ **像素采样**(`ImageGrab` 对比拖拽前后同一坐标像素)确认渲染真的变了。要点:**Flutter `localToGlobal` 给的是客户区相对坐标**,屏幕坐标需 `ClientToScreen(hwnd,(0,0))` 换算;多实例并存时必须按 `FLUTTER_RUNNER_WIN32_WINDOW` 类名 + 进程名过滤窗口,否则操作错窗口;测试窗口需 `SetWindowPos(HWND_TOPMOST)` 置前。**测试盲区警醒:只断言数据不断言 UI 的测试会放过整类 bug**——已补"模拟拖拽后断言首格渲染文本变化"的 UI 回归测试。

### 12.6 版本锚点(本机已验证)

| 项                              | 版本                                                                   |
| ------------------------------- | ---------------------------------------------------------------------- |
| Flutter stable                  | 3.47.2(2026-08-26 revision d3b14c87)                                   |
| Dart                            | 3.13.2(随 Flutter 捆绑)                                                |
| compileSdk / minSdk / targetSdk | 36 / 24 / 36(源码 `flutter_tools/.../FlutterExtension.kt` 核实)        |
| Android platform                | `platforms;android-36`(Android 16,与真机一致)                          |
| VS Build Tools                  | 18.9.12112.369(VS 2026 / v145)                                         |
| Material/Cupertino              | 以当前 Flutter SDK / 项目模板实际生成结果为准,后续创建工程时再确认依赖 |

## 13. 参考文件

- 现有行为参考:`scripts/batch_rename_images.py`
- 现有行为参考:`scripts/batch_pack_cbz.py`
- 项目门面:[`README.md`](README.md)
