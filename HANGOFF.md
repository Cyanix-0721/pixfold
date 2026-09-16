# PixFold — 设计交接：图片整理与 CBZ 制作工作台

> 项目名：**PixFold**
> 状态：**设计阶段**（2026-09-03 立项；2026-09-08 D2 SAF spike 5/5 验收；2026-09-10 重审后换栈为 Android 原生；**2026-09-16 D1 在新栈启动重建**——分支拓扑与七阶段切分见 §9.1，构建链已在 temurin-17 下实证）
> 目标平台：**Android（唯一 GUI 平台）**；Windows / Linux 不再作为 GUI 目标平台
> 技术栈：**Kotlin + Jetpack Compose（Android 原生，2026-09-10 重审锁定，替换 09-08 的 Flutter + Dart；判定见 §8.2）**
> 分支模型（2026-09-16 起）：`ready`（设计文档线）→ **`dev`（开发主线）** → 阶段分支 `d1/pN`（完成即合回 `dev`）；D1 全绿后 `dev` → `main` 发 release，此后开发继续在 `dev`。详见 §9.1

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
- 不同文件访问模型（Android 走 SAF、没有真实路径）下仍能给出清晰、可理解的预览与错误信息。

产品核心价值不是“少点几次确认”，而是让复杂整理过程**看得见、改得动、做得稳、出错能回退**。

## 2. 已确认的产品约束

- **GUI 只做 Android**：Windows / Linux 不再作为 GUI 目标平台（2026-09-10 变更，原约束为“最终覆盖 Windows、Android、Linux”）。桌面端不投入新开发，保留的 Python 脚本仅作行为参考与回归基准。
- Android 是唯一 GUI 平台，不是桌面版的缩小移植；设计从手机出发，不为宽屏另设界面。
- 技术栈已锁定 **Kotlin + Jetpack Compose**（Android 原生，2026-09-10 重审锁定，§8.1），替换 2026-09-08 的 Flutter + Dart。
- 真实 CBZ 体量分布为 50MB ~ 1GB、极端可达 4GB（用户 2026-09-10 提供），设计不得假设小文件：Zip64、目标文件系统上限与阅读器兼容性须在设计中考虑。
- **操作分布（用户 2026-09-10 确认）**：**绝大多数操作在手机上完成**，仅“手机存储空间不足”这类少数情况才转到电脑。桌面端因此定位为**低投入退路**，不做 GUI；Android 端则必须能**识别空间不足并引导用户改到电脑处理**。
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

**排序键定位与字段序(2026-09-10 用户确认)**:可用排序键 = 文件名(自然) / 文件名(字典) / 目录名 / 修改时间 / 创建时间 / 文件大小,下拉即按此顺序。**自然序是主路径**——用户以 `IMG_1_xxx` 这类规范命名在源头保证顺序,工具不为 `IMG_10` 这类歧义命名兜底;**字典序保留**(与外部工具/脚本输出对齐、等宽编码、混排字符集排查时作对照),紧随自然序之后;时间与大小次常用。多级排序最多 4 级。

> 来源说明:本段原随 Flutter 原型的走查提交(2026-09-10)一并提交,换栈时被丢弃;因属**与实现栈无关的产品决策**,于 2026-09-13 从归档分支取回。

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

### Android（唯一 GUI 平台）

Android 不再是“优先适配项”，而是**唯一的 GUI 平台**（2026-09-10 变更，见 §8.2）。设计阶段必须优先验证：

- SAF 目录选择和持久化授权；
- 目录树下的扫描、读取、创建、重命名和删除能力；
- 大量图片缩略图生成；
- 后台任务、进度和应用切后台后的恢复；
- 目标目录不等于真实路径时，预览和错误信息如何表达；
- 用户选中的目录权限失效后的恢复流程；
- **大归档写入**：1GB~4GB 的 CBZ 在 SAF 下的写入、Zip64 行为、失败与中断的可恢复性。
- **存储空间预检**（2026-09-10 新增）：手机空间是既定约束 —— 执行前须按预估输出大小做**空间预检**，不足时**明确拒绝并引导到电脑处理**，不得写到一半失败留下半成品。

Android 第一版应优先保证“选目录 → 预览 → 手工确认 → 生成 CBZ/命名计划 → 执行”的闭环。

### Windows / Linux（不再是目标平台）

2026-09-10 起，桌面不再有 GUI 目标，也不再为其投入开发。保留的原则：

- 现有 `scripts/` 下的 Python 脚本继续可跑，作为行为参考、回归样例与临时手动处理手段，**不新增功能、不做 GUI 封装**；
- 领域模型仍不得依赖 Windows 专有路径与打包机制（不把创建时间当成所有平台都可靠的字段，不写死回收站、文件权限与路径分隔符），以免污染 Android 侧设计。

> **这不算缺口（2026-09-10 用户确认）**：**绝大多数操作在手机完成**，只有“手机存储空间不足”这类少数情况才转到电脑。现有 Python 脚本足以应付这个退路场景，无需 GUI，也无需为桌面单独开发。

## 8. 技术栈决策门槛

> 状态(2026-09-10):**决策已就地锁定于 §8.1 —— Kotlin + Jetpack Compose(Android 原生)**,于 2026-09-10 重审替换 09-08 的 Flutter + Dart(触发见 §8.2 证伪条件 ③)。**未另立 ADR**(与 09-08 惯例一致,决策就地记录);本节的验证门槛与三 spike 建议是决策前评估的原始记录(2026-09-07 时点),执行情况与顺序调整见 §8.2、§8.3。

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

三个 spike 的结果再决定使用 Flutter、Qt/QML、Compose Multiplatform、Tauri、Slint、原生方案或其他组合(**此为 2026-09-07 的原始记录;候选方案已于 2026-09-10 收敛为 Kotlin + Jetpack Compose,见 §8.1**)。**实际决策已按 §8.2 的顺序调整提前执行并就地记录(§8.1),未另立 ADR**;此后如确需新增 ADR,先与用户确认再落盘。

### 8.1 技术栈决策(2026-09-10 重审锁定)

> **✅ 已锁定:Kotlin + Jetpack Compose(Android 原生)**。2026-09-10 重审,依据 §8.2 证伪条件 ③(桌面端信息呈现未达 D1 预期),替换 2026-09-08 的 Flutter + Dart。**GUI 只做 Android**(§2/§7),不再有桌面端目标,因此 KMP / Compose Multiplatform 一并排除——“桌面共用同一套 UI”的前提已不存在;单端 Kotlin 可最大化 Android 原生收益,并直接复用 D2a spike 的 Kotlin 代码。
>
> **继承与作废**:D2a spike(§8.3)的结论与 Kotlin 代码**全部继承**——它本就是 Android 原生代码,由参考实现升级为正式平台层;①② 的验证结果继续有效。`C:\Personal\pixfold-d1` 的 Flutter 原型代码**作废**,需在新栈重建;但 **D1 验收清单 20 项与走查结论是与实现无关的交互契约,继续作为 D1 退出条件**(判据不因换栈调整)。
>
> **⚠️ 工程实况(2026-09-13,本节路径已失效;归档位置 2026-09-16 复检)**:`pixfold-d1`(Flutter 原型)与 `pixfold-saf-spike`(SAF spike)**两个验证工程的目录已从磁盘删除**,**`ready` 分支从未包含过它们**。删除前的内容整体归档在分支 `archive-flutter-verify`(tip `9efef5b`;`ready` 为其祖先,领先 3 个提交)。故上文及 §8.3/§9 中 `C:\Personal\pixfold-d1`、`C:\Personal\pixfold-spike` 的路径**均已不存在**,引用它们时按"归档分支中的历史快照"理解,勿当作可访问目录。归档分支**已推远程**(本地无同名分支,取用走 `origin/archive-flutter-verify`),详见 §12.7。

| 排序 | 方案 | 判断 |
| ---- | ---- | ---- |
| **已锁定** | **Kotlin + Jetpack Compose** | Android 原生,SAF/ContentResolver 直达,无跨端抽象层;D2a 的 Kotlin channel 可直接迁移为正式平台层;单人维护面最小 |
| 已排除 | Compose Multiplatform(KMP) | Android 端同为原生 Compose,但“共用桌面 UI”的价值随桌面 GUI 出范围而消失,只剩 Gradle 多平台工程复杂度 |
| 已弃用 | Flutter + Dart | 2026-09-08 曾锁定(D2 SAF spike 5/5);2026-09-10 因 §8.2 ③ 重审被替换。①② 验证结果仍有效,SAF 与拖拽经验可参考 |
| 暂排除 | Tauri(Rust + Web) | 移动端为 2.x 新路径,与 Android 优先相悖;SAF 无成熟路径;须以 Rust 重写 Python 行为参考 |
| 本轮未进入 | Qt/QML、Slint | 单人维护面/生态/学习成本不占优 |

#### 历史归档:2026-09-08 决策依据

> 以下为 09-08 当时的锁定说明与候选排序,**已被上面的重审取代**;保留以记录当时的对照论证。文中的“已锁定”指当时结论,不是现状。
>
> 原标题:`8.1 技术栈决策(2026-09-08 锁定)`

> **✅ 已锁定:Flutter + Dart**。D2 SAF spike(§8.3)5/5 验收通过,证伪条件 ①② 均已排除(Android 16 真机,一加 Ace 3 Pro);桌面交互风险(D1)按 §8.2 判定为"已知可做",不再构成换栈理由。CMP 对照、Tauri 本轮不启用;若 D1 桌面表格/拖拽触发证伪条件 ③ 且修复成本不可接受,再重审 CMP。
> 历史:2026-09-07 候选排序见下(归档),环境已装 Flutter 3.47.2 属事实倾向,spike 已按同等标准检验通过,非"工具就绪"主导。

| 排序       | 方案                               | 判断                                                                                                                                                                 |
| ---------- | ---------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **已锁定** | **Flutter + Dart**                 | D2 spike 5/5 过:SAF 闭环(选目录→持久授权→遍历→读→建/改名/删)全通,851 张 <1s,跨进程授权恢复无弹窗。唯一真风险(SAF)已用自写 Kotlin channel 验证消除                    |
| 对照(备用) | **Compose Multiplatform + Kotlin** | Android 端即原生 Jetpack Compose,SAF/ContentResolver 直达,是唯一硬胜出项;代价:KMP/Gradle 工程复杂度高、桌面打包生态较新。仅当 D1 触发证伪条件 ③ 且不可修复时启用对照 |
| 暂排除     | **Tauri(Rust + Web)**              | 桌面成熟,但移动端为 2.x 新路径,与"Android 优先"相悖;SAF 无成熟路径;换栈须以 Rust 重写全部 Python 行为参考,回归基准作废                                               |
| 本轮未进入 | Qt/QML、Slint、纯原生              | 单人维护面/生态/学习成本不占优;不排除证伪后重审                                                                                                                      |

### 8.2 spike 判定与执行顺序(2026-09-07)

**证伪条件判定(2026-09-10 更新)**:D2 spike 已排除 ①②;③ 于 D1 走查中**触发**(见下),据此重审技术栈并锁定 **Kotlin + Jetpack Compose(Android 原生)**(§8.1)。下列判定取代 2026-09-08 的“维持 Flutter”。

证伪条件清单(历史记录):

1. 无法稳定完成"选目录 → 持久授权 → 递归遍历 → 读字节 → 创建/重命名"闭环,且社区包 + 自写 channel(LocalSend 同款路线)的修复成本不可接受;← **2026-09-08 已排除**
2. 千级缩略图在目标机型出现不可接受的解码性能或内存问题;← **2026-09-08 已排除(851 张 <1s)**
3. 桌面交互在现有实现基础上仍无法满足 D1 验收。← **2026-09-10 触发,据此换栈**。当日两次走查:
   - **拖拽部分(上午)判定为可修复,不构成换栈理由**:三方包(`reorderable_grid_view 2.2.8`)桌面端 drop 不落地、拖拽视觉需大量兜底;改用自研(`Draggable`/`LongPressDraggable` + `DragTarget`,见 §12.5)后网格/列表拖拽真实落地、点击与滚动无冲突。
   - **桌面信息呈现(下午)触发 ③**:走查反馈“元素过大、信息密度低,一屏看不了多少”,未达 D1 预期;用户明确表达“强烈转安卓原生开发意愿”。据 AGENTS.md“本文件优先级低于用户当回合指令”,据此重审并换栈。
   - ⚠️ **诚实标注**:就“可行性”而言 Flutter 桌面端未见硬失败(密度可通过 `visualDensity`/字号/网格列数改善),本次判定含**用户对原生控件语汇的主观偏好**成分,非纯技术排除。记录在此以保持可追溯,避免日后被误读为 Flutter 在桌面上“做不到”。

**顺序调整**:D2 的 SAF spike 可先于 D1 完整交互原型执行——平台风险(可推翻选型)高于交互风险(拖拽/表格在候选方案上均为已知可做),先验证可避免 D1 原型资产因换栈浪费。D1 原型在 spike 通过后启动,此时栈已锁,原型不重做。

### 8.3 D2 SAF spike 执行方案(2026-09-07 记录,环境验收后启动)

**状态**(2026-09-08):✅ **D2 SAF spike 验收全部通过,证伪条件 ①② 排除**。工程建于 `C:\Personal\pixfold-spike`(当时为仓库外临时目录;2026-09-13 删除前已整树归档至本地分支 `archive-flutter-verify`,见 §12.7),一加 Ace 3 Pro(Android 16/API 36)无线 adb 真机验收。**验收清单 5/5 全过**:

- ① `openTree` 12s(已授权)/ 32s(首次)+ `takePersistableUriPermission`
- ② `listImages` 851 张 / 962ms、24 张 / 28ms(已授权目录二次调用)
- ③ `readBytes` 3.4MB JPEG / 42ms,头 `ffd8ffe1...` 真 JPEG 校验通过
- ④ `createAndWrite→renameDoc→deleteDoc` 总 206ms(其中 createDocument 须 **tree URI → document URI** 转换,见 §12.5 踩坑)
- ⑤ **跨进程持久授权**:SharedPreferences 存 tree URI,杀进程重开自动恢复 → 直接列出、全程无弹窗(系统层持久授权生效,未走 SAF 重选;若授权失效会抛 SecurityException 而非静默) | 千级缩略图 851 张 <1s 无压力

**收束**(2026-09-08):验收清单 5/5 全过,无"待补验"项;证伪条件 ①② 排除,D2 至此完成。**2026-09-10 换栈后,本节 Kotlin 代码由"参考实现"升级为正式平台层**(§8.1),Flutter 脚手架与 Dart 侧作废。

> **⚠️ 产物实况(2026-09-13 更新)**:spike 工程目录 `C:\Personal\pixfold-spike` **已从磁盘删除**(连同 D1 原型,见 §8.1 工程实况)。**本节 Kotlin 方法表仍是权威参考**,但**可读的完整实现已不在磁盘上**——它归档在本地分支 `archive-flutter-verify` 的 `pixfold-saf-spike/android/app/src/main/kotlin/com/example/pixfold_saf_spike/MainActivity.kt`(含 `openTree`/`listImages`/`readBytes`/`renameDoc`/`createAndWrite`/`deleteDoc` 六个方法与 tree URI→document URI 转换)。新栈重建平台层时从此处取参考,勿再按下面的旧路径找文件。

**目标**:最小工程验证 Android SAF 全闭环,用于判定 §8.2 证伪条件 ①②;不做任何业务 UI。

**工程形态**:

- 目录:`C:\Personal\pixfold-spike`(仓库外兄弟目录,不污染设计仓库;非正式工程);**⚠️ 该目录已于 2026-09-13 删除,见上方"产物实况"**;
- `~~flutter.bat create --platforms=android,windows~~`:原 Flutter 脚手架,**换栈后作废**;本节真正要保留的产物是**下面的 Kotlin 方法实现**,而非工程形态。

**Kotlin(MainActivity,MethodChannel `pixfold/saf`)**:

| 方法             | Android 实现                                                                                                                                                                                                                              |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `openTree`       | `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`                                                                                                                                                                              |
| `listImages`     | `DocumentFile.fromTreeUri` 递归,按扩展名筛图,返回相对路径 / uri / size                                                                                                                                                                    |
| `readBytes`      | `contentResolver.openInputStream`                                                                                                                                                                                                         |
| `renameDoc`      | `DocumentsContract.renameDocument`                                                                                                                                                                                                        |
| `createAndWrite` | **先把 tree URI 经 `getTreeDocumentId` + `buildChildDocumentsUriUsingTree` 转为根目录的 document URI**,再 `DocumentsContract.createDocument` + `openOutputStream`(一加/部分国产 ROM 严格校验,直接传 tree URI 会抛 `Invalid URI`,见 §12.5) |

**~~Dart 侧~~**(已作废):原 `saf_channel.dart` 封装与测试按钮序列(选目录 → 列前 N 张 → 读第 1 张 → 复制改名 → 删除副本)**随换栈移除**。换栈后 Kotlin 直接调用上表方法,不再经 MethodChannel。

**验收清单**(全部通过 = 证伪条件 ① 排除;当时判 Flutter 主候选,结论在换栈后仍作为 **SAF 能力基线**保留):

1. 授权后重启进程仍有效(持久授权);
2. 500+ 图目录递归遍历耗时可接受;
3. 读取大图字节数与平台侧一致;
4. 重命名 / 创建 / 删除后回读一致;
5. 顺带测证伪条件②:千级缩略图滚动(`ContentResolver.loadThumbnail` 或 `BitmapFactory inSampleSize`)。

**触发(已不适用)**:原为"任一验收失败且修复成本不可接受 → 启动 CMP 对照 spike";2026-09-10 换栈为 Android 原生后 CMP 已排除(§8.1)。

## 9. 设计阶段路线

> 进度(2026-09-08):D0 完成;D2 的 Android SAF spike 已按 §8.2 提前执行并验收(§8.3),证伪条件 ①② 排除;当前主线为 D1 交互原型,后续 D2/D3 的完整平台与领域核心工作尚未启动。(**技术栈已于 2026-09-10 重审换栈,见 §8.1/§8.2**。)
> 进度(2026-09-13):换栈收口——两个验证工程(`pixfold-d1` / `pixfold-saf-spike`)已删除并归档至本地分支,见 §12.7;**D1 原型需在新栈从零重建**,当前无任何原型代码。
> 进度(2026-09-16):**D1 在新栈启动重建**——分支拓扑与七阶段切分已固化(§9.1),规格与分阶段计划落 `docs/superpowers/`;构建链在 `temurin-17` 下完整实证(`docs/notes/d1-toolchain-evidence.md`)。**真机走查仍缺设备**(`adb devices` 为空),故依赖真机的验收项挂起,不得以离线通过冒充 D1 完成。
> **阶段门槛(2026-09-10 补,可判定化)**:每阶段的"退出条件"必须达成才进入下一阶段,避免"看起来做完了"就往前推。状态与退出条件一览:

| 阶段                    | 状态                    | 退出条件(可判定)                                                        |
| ----------------------- | ----------------------- | ----------------------------------------------------------------------- |
| D0 需求与样例固化       | ✅ 完成                 | 脚本功能矩阵 + 样例目录 + 危险默认值标注(§3)                            |
| D1 交互原型             | 🔄 重建中(2026-09-16 起) | 下方《D1 验收清单》20 项**全部勾满**(判据不变)            |
| D2a SAF 闭环 spike      | ✅ 完成(提前,2026-09-08) | §8.3 五项验收 5/5                                                       |
| D2b 完整平台能力        | ⬜ 待办                 | 缩略图策略 / 后台任务与进度 / 授权失效恢复,三项真机可复现且有失败路径提示 |
| D3 领域核心与适配层     | ⬜ 待办                 | 排序/命名/元数据/打包四类核心逻辑单测通过,且不依赖 UI 与平台层(§5)       |
| D4 MVP 闭环             | ⬜ 待办                 | Android 完成一次真实文件的端到端闭环(预览→排序→元数据→产出) |
| D5 增强功能             | ⬜ 待办                 | 按需排期,不阻塞 D4                                                      |

> **决策门(2026-09-10 更新)**:① **正式工程创建**的触发 = D1 退出条件达成(D1 之前只维护设计文档 + 验证工程,§4);② 换栈触发 = §8.2 证伪条件 ③,**已于 2026-09-10 触发并完成换栈**(→ Kotlin + Jetpack Compose,§8.1);后续 D1 走查若再发现问题,按同一框架记录,**不再预设对照方案**。
> D1 执行注(2026-09-16 更新):D1 原型工程 = **本仓库内的 `pixfold-d1/`**,在**阶段分支**上开发(拓扑见 §9.1),不连真实文件系统、用确定性 mock 数据。原 `C:\Personal\pixfold-d1` 的 Flutter 代码**已作废**,需在 **Kotlin + Compose** 新栈重建;原 Dart 侧 16 项测试随之作废,但**其覆盖的语义必须在新栈重建**(语义已提取为 `docs/notes/d1-archive-domain-semantics.md`)。范围 = 本节 D1 六项交付;**验收平台 = Android 单端**(§2/§7);实现策略 = **内置优先、零三方依赖、拖拽自研**(2026-09-10 用户决策,设计结论见 §12.5)。领域逻辑(自然排序/多级排序/命名求值/冲突检测/ComicInfo.xml/页码重编号)为**不依赖 UI 的纯 Kotlin 核心**,跑 JVM 单测(独立 `domain/` 模块)。
> **⚠️ 工程实况(2026-09-13,路径已失效)**:`C:\Personal\pixfold-d1` **目录已从磁盘删除**(与 `pixfold-saf-spike` 一同),`ready` 分支从未包含它。删除前的完整 Flutter 原型(含 `lib/domain/comic.dart` 等领域层与 16 项测试)**归档在分支 `archive-flutter-verify`**(tip `9efef5b`,已推远程)。新栈重建时:**领域层的语义与测试意图已提取成笔记可读参考,代码本身作废、不迁移**;§12.5 的三条拖拽设计结论已单独保留在正文,不依赖归档。
> 走查修复注(2026-09-09,Flutter 原型时期,**已作废**):原 Windows 端首轮反馈(大图预览自适应与缩放、桌面即时拖拽、中文发虚、网格拖拽黑边)已在该原型上闭环。**具体实现随换栈作废**;属于设计意图的部分(预览自适应窗口 + 可缩放平移翻页、拖拽视觉要有包络与占位、UI 字号下限)已入 D1 验收清单 / 交互原则,新栈重建时重新实现并验证。
> 走查修复注(2026-09-10,Flutter 原型时期):拖拽改自研、**松手落地**语义、`PageOrder.moveItemTo` 统一重排入口 —— 这些**设计结论**继续有效并已归入 §12.5;原型上的具体修复与 3 项手势回归测试随换栈作废,须在新栈重做。
> 走查状态(2026-09-10 换栈后):**Flutter 原型的走查作废**,待新栈重建原型后重跑 D1 验收清单 20 项(判据不变,见本节《D1 验收清单》前注)。
> 工程约定(2026-09-16 细化):D1/D2 验证工程**不进 `ready` / `main`**;原型代码**在本仓库的阶段分支开发,完成即合入 `dev`**(用户 2026-09-16 决定,取代"放在仓库外目录"的做法——2026-09-13 的整树丢失教训见 §12.7)。**`dev` 产出完整测试版(D1 全绿)后才合并到 `main` 发 release**,此后开发继续在 `dev`;阶段分支完成时**先在 `dev` 回写文档**。分支拓扑与阶段切分见 §9.1。D1 按 §9 验收点 + §10 功能验收走查通过前不宣称完成。
> 归档先例:2026-09-13 删除前已整树归档到独立分支 `archive-flutter-verify`(**不在主线上**)——详见 §12.7。
> 走查反馈补回(2026-09-13):Flutter 原型时期的**走查反馈 ④/⑤ 教训**原随换栈一并移除,其中**与实现无关、新栈同样适用**的部分已自归档分支取回并归入 §12.5(反馈④:可选回调"传了但没被使用"是静默缺口,对应清单第 6 项;反馈⑤:排序字段下拉顺序,其结论已固化在 §4 排序键定位)。逐条 Flutter 修复记录本身仍不保留。

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

> **换栈影响(2026-09-10)**：技术栈改为 Kotlin + Jetpack Compose(§8.1)，原型工程 `C:\Personal\pixfold-d1` 的 Flutter 代码作废、需在新栈重建(**该目录已于 2026-09-13 删除并归档,见 §12.7**)。**下方验收清单 20 项与已积累的走查结论不作废**——清单条目是与实现无关的交互契约，继续作为 D1 退出条件；**判据不因换栈调整**(AGENTS.md 既有约定)。
>
> **列变更(2026-09-10 用户确认)**：原「Windows | Android」双列已收敛为 **Android 单列**——GUI 只做 Android(§2/§7)，Windows 列没有走查对象。**20 个条目本身一字未改**，仅移除平台列与“双端”表述。

#### D1 验收清单(2026-09-10 补,走查时逐条打勾;§9 判据的可执行化)

> 用法:在 **Android 真机**逐条勾选;任一条不通过就记为待修项,修复后**必须自证**(见下方"修复自证三层")再复勾。全部勾满 = D1 退出条件达成。

| #  | 验收点                                                     | 依据        | Android |
| -- | ---------------------------------------------------------- | ----------- | ------- |
| 1  | 缩略图网格/列表双视图可切换,缩略图渲染正确                 | §9          | ☐       |
| 2  | 大图预览:自适应窗口、可缩放、可拖动平移、可翻页            | §6.2        | ☐       |
| 3  | 网格拖拽真实改变顺序(松手落地)                             | §9/§10      | ☐       |
| 4  | 列表拖拽真实改变顺序                                       | §9/§10      | ☐       |
| 5  | 人工调整有可见标记,且可一键重置回自动排序                  | §4          | ☐       |
| 6  | 固定位置(图钉)项在重新应用排序后保持原位                   | §4          | ☐       |
| 7  | 多级排序规则可增删改;批次默认与"本组独立"例外并存          | §4          | ☐       |
| 8  | 命名结构:组件启用/顺序/分隔符/大小写/补零/扩展名策略可调   | §4          | ☐       |
| 9  | 命名建议表:原路径→新名称,可逐项覆盖,且改结构后覆盖不丢失   | §6.3        | ☐       |
| 10 | 冲突与警告可见:重名、非法字符、大小写冲突、超长            | §9/§10      | ☐       |
| 11 | 逐卷元数据 title/series/writer/volume 可改,建议值带来源    | §4/§6.1     | ☐       |
| 12 | 语言默认"未设置"必须人工确认;支持逐卷不同,写入/不写入可选 | §4          | ☐       |
| 13 | 批量设置不覆盖逐项例外(被手工改过的字段保持)               | §6.3        | ☐       |
| 14 | ComicInfo.xml 预览与页码列表预览(含重编号)一致             | §9/§10      | ☐       |
| 15 | 执行计划预览:操作清单 + 冲突/跳过原因                      | §6.4        | ☐       |
| 16 | 危险动作默认关闭,且需独立二次确认(删源/清理空目录)         | §2/§6.4     | ☐       |
| 17 | 执行报告(成功/跳过/失败)+ 撤销入口                          | §9/§10      | ☐       |
| 18 | 必须人工输入的字段都有明确入口(逐项核对无遗漏)             | §9          | ☐       |
| 19 | 两条工作流均可从首页完整走通到结果页                       | §9          | ☐       |
| 20 | 工程健康:静态检查零告警;测试全绿(含 UI 层断言)             | 流程约定    | ☐       |

**修复自证三层(2026-09-10 固化,凡交互/数据改动必做)**:

1. **逻辑层**:领域逻辑改动用单元测试钉死语义(如 `moveItemTo` 的"前移/拖到末尾/拖到自己格不变");
2. **UI 层**:涉及界面的改动必须断言"界面真的变了"——只断言数据的测试会放过整类 bug(拖拽 4 轮的根因就是数据对、界面不动);
3. **真机层**:GUI 问题不靠猜,用"埋点日志 → 模拟输入(pywin32 SendInput)→ 像素/快照对比"实证,方法详见 §12.5。

> 教训依据:三轮走查共修 8 项(预览溢出/拖拽不落地/中文发虚/拖拽黑边/列表 0 高度/三方包弃用/字号/通知链断裂),其中"拖拽不改变顺序"反复 4 轮才定位——前 3 轮都停在"测试通过"的假象上。

### D1.1 分支拓扑与阶段切分(2026-09-16 用户决定)

> 背景:换栈后 D1 需从零重建(§9 D1 执行注),原型代码改在**本仓库的阶段分支**开发,
> 不再放仓库外目录(2026-09-13 的整树丢失教训见 §12.7)。本节固化拓扑,避免"每阶段一分支"被各人各理解。

**分支拓扑**:

```text
ready ──► dev                          ← ready 整合进 dev,dev 成为开发主线
            ├─► d1/p1 ─┐
            ├─► d1/p2 ─┤  每阶段从 dev 开一个分支
            ├─► …      ├─ 阶段完成 → 合回 dev + 在 dev 回写文档 → 删阶段分支
            └─► d1/p7 ─┘
                  │
                  ▼
            dev = 完整测试版(D1 验收清单 20 项全绿)
                  │
                  ▼
                main ──► 发 release ──► 此后开发继续走 dev
```

- 阶段分支**从 `dev` 开、合回 `dev`**,**不直接进 `main`**;`main` 只在 D1 全绿后接收一次合并并打 release。
- 阶段分支完成时**先在 `dev` 上回写文档**(状态、踩坑、验收进度),再删分支。
- 原型代码**永不进 `ready`**;`ready` 保持为设计文档线。
- 提交与推送:**每阶段完成即提交**;push 由用户自行处理(用户 2026-09-16 约定)。

**七个阶段切片**(按能力垂直切,每阶段 = 领域+UI+测试的完整闭环,可独立评审/回滚):

| 阶段 | 交付物 | 覆盖验收项 | 自证层 |
| --- | --- | --- | --- |
| **P1** | 脚手架(AGP 9.4/Gradle 9.6/Compose BOM)+ 领域地基(模型、自然排序、多级排序、`PageOrder`/`moveItemTo`/`applyRule`/图钉)+ 确定性 mock 数据 | —(地基) | 第 1 层 |
| **P2** | 缩略图网格/列表双视图切换、大图预览(自适应窗口/缩放/平移/翻页) | 1, 2 | 1+2 |
| **P3** | 拖拽排序(网格+列表,松手落地)、图钉入口、排序规则编辑器(多级/批次+本组独立) | 3, 4, 5, 6, 7 | 1+2 |
| **P4** | 命名结构编辑器、建议表(原路径→新名)、逐项覆盖、冲突与警告 | 8, 9, 10 | 1+2 |
| **P5** | CBZ 元数据编辑器、ComicInfo.xml 预览、页码列表预览 | 11, 12, 13, 14 | 1+2 |
| **P6** | 执行计划预览、危险动作独立二次确认、执行报告 + 撤销入口 | 15, 16, 17 | 1+2 |
| **P7** | 真机走查 20 项 + 首页两条工作流端到端 | 18, 19, 20 | **第 3 层** |

**工程结构**(领域层独立成纯 Kotlin JVM 模块,用 Gradle 边界强制"领域不依赖 UI/平台"):

```text
pixfold-d1/
├── domain/     ← 纯 Kotlin JVM,零 Android 依赖;:domain:test 秒级反馈
└── app/        ← Android + Compose
```

**领域语义权威**:`docs/notes/d1-archive-domain-semantics.md`(自归档分支 `9efef5b` 提取,含 16 项测试意图逐条还原);
**构建链实证**:`docs/notes/d1-toolchain-evidence.md`(2026-09-16 探针实测);
**规格与分阶段计划**:`docs/superpowers/specs/`、`docs/superpowers/plans/`。

### D2：平台能力验证

优先 Android SAF,再验证 Windows 和 Linux 文件访问。重点验证最容易推翻技术选型的能力,不先做完整业务。

顺序注(2026-09-07):SAF spike 可提前至 D1 完整原型之前执行,判定与顺序见 §8.2。

**拆分为 D2a / D2b(2026-09-10 按事实对齐)**——原来把"SAF 可行性 spike"与"完整平台能力"混作一个 D2,而 spike 已于 2026-09-08 提前完成,导致路线图与实际进度不符:

- **D2a · SAF 闭环 spike** ✅ **已完成(2026-09-08,提前执行)**:选目录 → 持久授权 → 递归遍历 → 读字节 → 创建/改名/删除,真机 5/5 验收(§8.3);产物原在 `C:\Personal\pixfold-spike`(**该目录已于 2026-09-13 删除,完整 Kotlin 实现现归档在本地分支 `archive-flutter-verify`**,见 §12.7)。**本阶段不再重复,验收记录归档。**
- **D2b · 完整平台能力** ⬜ **待办**(D1 收口后启动):
  - Android:千级缩略图**生成策略**(`ContentResolver.loadThumbnail` vs `BitmapFactory` + `inSampleSize` + 缓存/并发上限)、**后台任务与进度**(切后台恢复、进度上报)、**授权失效恢复流程**(SecurityException → 引导重选目录);
  - **存储空间预检与不足退路(2026-09-10 新增)**:手机空间不足是既定会发生的场景(用户确认“少数情况因此转电脑”)→ 须验证:执行前按预估输出大小做空间预检、不足时明确拒绝并给出“转到电脑处理”的引导、且不留下半成品;
  - **大归档写入(2026-09-10 新增)**:真实样本存在 1GB~4GB 的 CBZ,而 D2a 的 `createAndWrite` 仅验证了小文件,**4GB 级写入在本项目是空白**。须真机验证:Kotlin/JVM 侧 zip 实现(`java.util.zip.ZipOutputStream`、Apache Commons Compress)对 Zip64 与“未知长度流式 STORED 条目”的实际支持、写入中断后的半成品识别与清理、FAT32 目标(单文件上限 4GiB−1)、以及**目标阅读器能否打开 Zip64 包并在 2GB 以上仍可用**;
  - Windows / Linux:文件访问与批量预览流程(Linux 不依赖 Windows 专有路径与回收站语义,§7);
  - 退出条件:上述三项在目标机型可复现验证,且失败路径有明确的用户可理解提示。

### D3：领域核心与适配层

将排序、命名、元数据、ComicInfo.xml、CBZ 生成、计划和报告拆成可测试核心；平台层只负责资源访问、缩略图、权限和任务生命周期。

### D4：MVP 闭环

优先交付（**GUI 仅 Android**，2026-09-10 变更）：

- Android：单目录/单系列，图片预览与人工排序，逐卷元数据，CBZ 生成；
- 桌面端不再有交付项；现有 Python 脚本维持可运行，不新增功能。

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
- Android 真机完成 **1GB 级**归档的写入验证（Zip64、中断恢复、目标文件系统上限）；更大归档的验收口径为“空间预检 + 明确拒绝 + 引导到电脑”，**不要求 4GB 级可靠写入**（§7 / §9 D2b）；
- 桌面端无验收项（GUI 已移出范围，2026-09-10）；保留的 Python 脚本仅需维持可运行。

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
11. ~~电脑端归档的处理路径~~ —— **已定(2026-09-10)**:绝大多数操作在手机完成,只有手机存储空间不足时才转到电脑;电脑端不做 GUI,用现有 Python 脚本手工处理即可。详见 §7。
12. **空间不足的提示阈值与引导方式**:Android 端不追求 4GB 级可靠写入,但须做到“提前预检 → 明确拒绝 → 引导到电脑”。具体阈值(按预估输出的百分比?绝对值?)与提示形态待 D2b 真机验证后定。(2026-09-10 更新)
13. **自然排序是否需要支持中文数字**:当前自然排序只做“数字段 vs 字符段”切分,**不识别中文数字**(`第一话` / `第十话` 会按汉字逐字比,`第十话` 可能排在 `第二话` 前)。实际遇到中文数字命名时需加归一化。(2026-09-10 记录;**2026-09-13 自归档分支补回**,原随 Flutter 提交被丢弃,与实现栈无关)
14. **命名建议的“序号”取哪个**:现取“第 1 步整理后的当前顺序”(拖拽会改变序号);是否需要提供“沿用原文件名解析出的原始序号”(如 `IMG_1_xxx` 里的 `1`)作为可选项?直接决定“拖拽后序号是否变”。(2026-09-10 记录;**2026-09-13 自归档分支补回**,原随 Flutter 提交被丢弃,与实现栈无关)

## 12. 开发环境实况与 Android 验证准备(2026-09-07 由 s_handoff.md 并入)

> 来源:原 `s_handoff.md`,2026-09-07 用户决定并入本文件后删除原文件。此后环境与验证准备以本节为准。

> ✅ **2026-09-10 已按换栈重写本节**：技术栈由 Flutter + Dart 改为 **Kotlin + Jetpack Compose(Android 原生)**(§8.1)，原 Flutter 相关条目(Flutter SDK 声明、`flutter doctor` 流程、Impeller/CJK 字体与 mise shim 踩坑、Flutter/Dart 版本锚点)**已全部移除**。(当时标注的"Kotlin/Gradle 工程侧尚未在本机验证"**已于 2026-09-16 复检更新**,见下条横幅。)

> ✅ **2026-09-12 起 SDK 根改由 Android Studio 管理**:弃用 `scoop android-clt`(已卸载,含 persist 回收)。SDK 根 = `C:\Users\Administrator\AppData\Local\Android\Sdk`(Studio 默认位置);`ANDROID_HOME` 与 `platform-tools` / `cmdline-tools` 的 PATH **由手工设置**(Studio 不会设它们)。

> ✅ **2026-09-16 环境复检(逐项实测,修正下列文档漂移)**:① 上文标注 ⬜ 的 `platforms;android-36` **实为已装**,cmdline-tools **已是 23.0.0**(非 19.0);② Gradle/AGP/Kotlin **并非"未跑通"**——2026-09-12 曾有一次 `BUILD SUCCESSFUL`(Studio 模板工程,详见 §12.2 末行);③ **adb/fastboot 曾被 `C:\Windows\System32` 的第三方残留遮蔽**,已清除,详见 §12.5;④ 归档分支**已推远程**(§12.7)。**结论:环境比原记录更完整,唯一需动手项(adb 遮蔽)已修复。**

### 12.1 一句话现状

Windows 开发机的**基础设施**已就位(git / VS Code / scoop / winget / mise),**Android SDK 侧已就绪**:SDK 根 = `C:\Users\Administrator\AppData\Local\Android\Sdk`(Android Studio 的默认位置,由 Studio 的 SDK Manager 管理),`ANDROID_HOME`、`JAVA_HOME`、用户 PATH 均已落盘,`adb` / `fastboot` / `sdkmanager` 裸命令可用,真机走无线调试。**Kotlin / Gradle 工程侧已具备构建能力**(2026-09-12 有一次成功构建实证;JDK 门槛见 §12.6)。PixFold 仍处设计阶段,不直接创建正式工程。

> 2026-09-16 复检补注:该机主机名为 **`SLAYER`**(走透明代理,无需配 Gradle 代理;`~/.gradle/gradle.properties` 确实不存在——与 §12.5 代理条目的机器区分一致)。

### 12.2 环境实况清单(2026-09-16 复检更新)

| 组件 | 状态 | 版本 / 位置 |
| --- | --- | --- |
| Git | ✅ | 2.55.0.windows.5(scoop) |
| VS Code | ✅ | 1.138.0(scoop apps/vscode) |
| Android Studio | ✅ | 2026.1.4.7(scoop;自带 JBR **25.0.3**,不依赖外部 Java 启动) |
| scoop | ✅ | main / extras / versions / sysinternals / nerd-fonts 桶 |
| winget | ✅ | v1.29.290 |
| mise | ✅ | 2026.9.9(Windows) |
| JDK | ✅ | temurin-17.0.20+101(mise 声明 `temurin-17`);`JAVA_HOME` 已 setx |
| Android SDK 根 | ✅ | `C:\Users\Administrator\AppData\Local\Android\Sdk`(Studio 默认位置) |
| ANDROID_HOME | ✅ | 指向上述 SDK 根;2026-09-12 手工设为**用户级**(Studio 不会设它,见机制注) |
| ANDROID_SDK_ROOT | ✅ 留空 | 官方要求:与 `ANDROID_HOME` 二者只设其一,或取值一致 |
| ANDROID_SDK_HOME | ⚠️ 非 SDK 路径变量 | Studio 自动设为同一目录;它只影响老工具创建 `.android` 用户数据的位置,**不能当 SDK 路径用** |
| 用户 PATH | ✅ | `<sdk>\platform-tools`(adb / fastboot)、`<sdk>\cmdline-tools\latest\bin`(sdkmanager) |
| SDK 组件 | ✅ | platform-tools **37.0.1**;build-tools **35.0.1 + 36.0.0**;platforms **android-36 + android-37.0**;sources **android-36 + android-37.0**;emulator **37.1.11**;system-images **android-36**(google_apis_playstore / x86_64);licenses(android-sdk-license) |
| cmdline-tools | ✅ | SDK 根内 `cmdline-tools\latest` = **23.0.0**(2026-09-16 复检;原记录 19.0 已过期) |
| platforms;android-36 | ✅ 已装 | 2026-09-16 复检确认已存在(Platform 16,rev 2),与真机 Android 16 对齐;原 ⬜ 标记作废 |
| adb / fastboot | ✅ | 均来自 SDK `platform-tools` **37.0.1**,PATH 中各仅一份;未装独立 scoop adb(防双 adb,见 §12.5) |
| Gradle / Kotlin 工程侧 | ✅ 有构建实证 | 2026-09-12 一次 `BUILD SUCCESSFUL in 4m 45s`(Studio 模板 `MyApplication`,Gradle **9.6.0** / AGP **9.4.0** / Kotlin **2.2.10**,跑在 Studio JBR 25 上);**2026-09-16 在 `temurin-17` 下完整复验通过**(探针工程:assembleDebug + 纯 JVM 单测 + Robolectric UI 测试 + lint 全绿);工程事后已删,无残留代码。**AGP 9 起 Kotlin 为内置**(不再需要单独装 kotlinc) |
| WSL | ✅ 仅作 agent 宿主 | 跑 Codex,经互操作驱动 Windows 侧构建;WSL 不装 Android SDK、不承担构建(依据见 §12.5 的 I/O 实测) |
| 模拟器加速 | ⚠️ 未就绪 | 无任何 AVD(`~/.android/avd` 不存在);`HypervisorPlatform`(WHPX)= Disabled 而 Hyper-V 已启用;`aehd` 驱动已装但服务 STOPPED(AEHD 与 Hyper-V 互斥)。**D1 走真机,暂不需处理** |
| 真机连接 | ⬜ 当前无设备 | `adb devices` 为空;配对记录仍在(`~/.android/adb_known_hosts.pb`),重开无线调试即可 |

> 机制注(2026-09-12 实测核实):**Android Studio 只把 SDK 路径记在自己的配置里——既不设 `ANDROID_HOME`,也不改 PATH**(IDE 设置项与各工程的 `local.properties` 都在 Studio 侧)。所以命令行侧的 `adb` / `fastboot` / `sdkmanager` 完全依赖上表那两条手工设置。**SDK 组件由 Studio 的 SDK Manager(或 SDK 根内的 `sdkmanager`)安装**;环境变量改动需**新开终端**才生效(旧进程是旧快照)。

> 历史(2026-09-12 之前,已弃用):曾用 `scoop android-clt` 的包目录当 SDK 根——它的 manifest 会 `env_set ANDROID_HOME` 到包目录、并把 `cmdline-tools/latest/bin`、`platform-tools` 写进 PATH,装完即全局可用。但它与"Studio 管理 SDK"并存会产生**两份 SDK 根**,且 Studio 会把 junction 解析成带版本号的实体路径(`...\android-clt\15859902`),`current` 的"跟随最新版"特性对 Studio 无效 → 2026-09-12 卸载并回收。

> 项目级配置(2026-09-12 更新):仓库根 `.mise.toml` **保持纯配置无注释**,说明统一在本注维护。当前内容仅 `java = "temurin-17"`(Android/Gradle 构建所需)。**声明 ≠ 已安装**:新机器上需 `mise install` 才按声明下载。**边界约定**(用户级,勿破坏):Python 由 uv 管理、Node 由 fnm 管理,mise 均不接管;全局工具声明不属本仓库管辖范围。工具版本请求变更请同步维护本节(§12.2)。

> **JDK 归属(2026-09-16 复检)**:Gradle **不读** `.mise.toml`——mise 只负责"把 JDK 拉下来",构建期用的是 `JAVA_HOME`(现指向 mise 的 temurin-17)或 Studio 内置 JBR。本机存在**两个可用 JDK**:mise temurin-17.0.20+101 与 Studio JBR 25.0.3(**是完整 JDK**,含 `javac` / `jlink` / `jmod` / `jar`,仅缺 `jpackage`)。Gradle 9.6.0 在两者下均实测可启动;AGP 9.x 官方兼容表要求 **JDK 最低 17**(默认 17),故两者都满足。**取舍**:Studio 的 JBR 路径随 IDE 升级/重装漂移(`current` 是 junction,实体目录带版本号),不宜当系统 JDK;`.mise.toml` 的声明提供与 IDE 解耦的稳定构建 JDK,**建议保留**。若确要去掉 mise,须同步把 `JAVA_HOME` 改指 JBR,否则命令行 `gradlew` 会因 `JAVA_HOME` 悬空而失败。

### 12.3 Android 平台验证准备(设计阶段优先)

> **进度(2026-09-16 复检)**:SDK 侧已由 Android Studio 装齐基础组件(见 §12.2),**原记录的待补项(`platforms;android-36`、cmdline-tools 升级)均已完成**。下面 ①–⑤ 是**新机器**上的等价步骤。⑥ 的 Kotlin/Gradle 侧**已于 2026-09-16 在 `temurin-17` 下完整实证**(见 ⑥ 与 `docs/notes/d1-toolchain-evidence.md`)。

```powershell
# ① JDK 17(sdkmanager 是 Java 程序,必须先有它)
mise install

# ② Android Studio(SDK 由它自己的 SDK Manager 安装,默认位置 %LOCALAPPDATA%\Android\Sdk)
scoop install android-studio
#   首次启动完成 Setup Wizard,勾选 Android SDK Platform-Tools 等组件

# ③ 命令行环境变量(Studio 不会设,必须手工设;用户级,新终端才生效)
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdk, "User")
$entries = @((Join-Path $sdk "platform-tools"), (Join-Path $sdk "cmdline-tools\latest\bin"))
$p = @([Environment]::GetEnvironmentVariable("Path", "User") -split ';' | Where-Object { $_ })
foreach ($e in $entries) { if ($p -notcontains $e) { $p += $e } }
[Environment]::SetEnvironmentVariable("Path", ($p -join ';'), "User")

# ④ 平台组件(新终端;目标 compileSdk 36)
sdkmanager "platforms;android-36" "build-tools;36.0.0"

# ⑤ 体检(验收标准)
sdkmanager --list        # 组件齐全
adb devices              # 能看到手机
# 注意:裸 `adb` 必须解析到 <sdk>\platform-tools\adb.exe —— 先 `Get-Command adb` 确认,
#       若指向 C:\Windows\System32\adb.exe 说明有第三方残留遮蔽,见 §12.5
```

**环境准备完成** → 插上 Android 16 真机(开发者选项 + 无线调试),验证设备与 SAF 所需基础能力。

> **⑥ Kotlin / Gradle 侧(2026-09-16 已实证,遗留项关闭)**:2026-09-12 曾有一次 `BUILD SUCCESSFUL`(Studio 模板工程,Gradle 9.6.0 / AGP 9.4.0 / Kotlin 2.2.10,跑在 Studio JBR 25 上)。**2026-09-16 用一次性探针工程在 `temurin-17` 下复验完成**:`:app:assembleDebug`、`:domain:test`(纯 Kotlin JVM)、Robolectric Compose UI 测试、`:app:lintDebug` **全部通过**,故原"temurin-17 下的完整构建待 D1 确认"**已关闭**。同批实测确认两条换栈坑:① **AGP 9 内置 Kotlin**,不可再应用 `kotlin-android`,但 Compose 编译器插件(`org.jetbrains.kotlin.plugin.compose`)仍需单独应用且**版本必须 = AGP 内置 KGP(2.2.10)**;② Kotlin `Regex.split` **丢弃捕获组**(与 Python `re.split` 不同),移植脚本的自然排序会**静默返回原序**。完整证据与复现命令见 `docs/notes/d1-toolchain-evidence.md`。**AGP 9 起 Kotlin 内置**(见 §12.5 相应条目),无需单独安装 Kotlin 工具链。

正式工程框架暂不创建。

### 12.4 每日必用命令速查

```powershell
adb devices              # 列出可用设备(含无线调试)
adb logcat               # 看运行日志
sdkmanager --list        # 看 SDK 组件可用版本(来自 <sdk>\cmdline-tools\latest\bin)
mise ls                  # 看 mise 管的工具版本
./gradlew assembleDebug  # 构建(⬜ 待工程创建后适用)
```

### 12.5 踩坑速查

- **adb 认 SDK 目录结构**(`$ANDROID_HOME\platform-tools\adb` 等),不认 PATH 上的散装 adb → platform-tools 必装。
- **同一类工具只留一份权威(adb 只留一份)**:历史上 scoop 独立 adb(37.0.1)与 SDK platform-tools 的 adb 版本漂移 → 报 `adb server version mismatch`(2026-09-07 已卸 scoop 版)。现行约定:**adb / fastboot 一律来自 SDK `platform-tools`**;只有在"要 adb 但不做 Android 开发"的机器上才装独立 adb 包,且**不要**与 SDK 那份并存。
- **第三方驱动/模拟器会把 adb 塞进系统目录,靠 PATH 顺序遮蔽 SDK 那份**(2026-09-16 实测发现并修复;比上一条更隐蔽):本机 `C:\Windows\System32\` 与 `C:\Windows\SysWOW64\` 下各有一份 **adb 33.0.0(2023-06,32 位)+ fastboot 36.0.0**,来源是历史安装的 **OnePlus USB Drivers**(`C:\Program Files (x86)\OnePlus USB Drivers\Android\`,内含 2016 年版 adb/fastboot;MuMu 模拟器也自带 adb 但不在 PATH 上)。**根因是 PATH 求值顺序**:Windows 先拼完 Machine PATH 再拼 User PATH,`C:\Windows\system32` 在 Machine 段第 3 位、`<sdk>\platform-tools` 在 User 段第 29 位 → **后者永远追不上**,裸 `adb` 实际跑的是 32 位的 `SysWOW64\adb.exe`,与 Studio 用的 SDK 版并存(实测曾出现两个 server 交替接管:先由 33.0.0 启动、kill 后由 37.0.1 启动)。**修复**:删除系统目录下这 4 个文件(`adb.exe` / `fastboot.exe` / `AdbWinApi.dll` / `AdbWinUsbApi.dll`,System32 与 SysWOW64 各一份,需管理员),**备份后删**,不卸载 OnePlus 驱动本身。**验收**:`Get-Command adb` 应指向 `<sdk>\platform-tools\adb.exe`;`adb version` 应为 37.0.1;反复 `adb devices` 时 server PID 不变(不再重启)。**教训:凡"版本对不上/行为诡异",先 `Get-Command adb -All` 看有几份、谁在 PATH 前面,不要只看 `adb version`。**
- **licenses 不点** → Gradle 构建会停在缺组件;用 `sdkmanager --licenses` 处理(不再经 flutter doctor)。
- **Android Studio 会把 junction 解析成实体路径 → SDK 根必须是不随包改名的地方**(2026-09-12 实证):把 Studio 的 SDK Location 指向 `...\android-clt\current`,存盘后变成 `...\android-clt\15859902`(实体版本目录),工程内 `local.properties` 的 `sdk.dir` 同样被写成实体路径。→ SDK 根必须是**稳定路径**(现行 = Studio 默认位置);"用 junction 跟随最新版"这套对 Studio 无效。
- **`ANDROID_SDK_HOME` 不是 SDK 路径变量**(2026-09-12 查官方"环境变量"文档核实):它只决定**老工具(Studio 4.3 及更早)把 `.android` 用户数据建在哪**;Studio 会自动把它设成 SDK 目录,于是 `<sdk>\.android\` 下出现 `avd/`、`cache/`、`debug.keystore`、`studio/`。**SDK 位置只认 `ANDROID_HOME`**(`ANDROID_SDK_ROOT` 已废弃;若两者都设,官方要求取值一致)。
- **杀软实时防护会拦 SDK 解包**(2026-09-12 实证;本机为火绒,Defender 已被接管):安装 `sources;*` 这类含上万个小文件的包时随机报 `java.nio.file.AccessDeniedException`(实测卡在 `ScreenCaptureCallbackHandler.java`;zip 本体 CRC 完好,只解出 533/16421)。→ 把 **SDK 目录、`~/.gradle`、工程目录**加入杀软信任区/排除列表(官方文档同样建议);临时关防护可确认因果。
- **Android 16 引入"次版本号"36.0 / 36.1**(2026-09-12 查一手资料):Android 16 QPR2 是首个带次版本的版本,SDK 版本由 36 → **36.1**;在 SDK 里两者是**独立 platform 包**、可并存(目录名形如 `android-36` 与 `android-36.1`,与现有 `android-37.0` 同类)。运行时用 `Build.getMinorSdkVersion(VERSION_CODES_FULL.BAKLAVA)` 查询;Gradle 侧 DSL 是 `compileSdkMinor`(AGP 9.1+);AGP 9.0 兼容表写明"最高支持 API 36.1",要打 37 需 AGP 9.4+。
- **构建与 SDK 都放 Windows 侧,不给 WSL**(2026-09-12 实测):WSL 经 `/mnt/d`(NTFS)解包 1500 个 4KB 小文件耗时 **2864ms**,同一操作在 ext4 上只要 **18ms**(159×),而 Gradle 构建全是这类小文件操作。→ 工程留在 D 盘、构建走 Windows 原生;WSL 只作 agent 宿主与代码编辑。
- **代理:按机器区分,勿混用**(2026-09-08 记 R7P21 / 2026-09-16 复检 SLAYER):**R7P21** 需 Clash 代理(127.0.0.1:7897),Gradle/Java **不读** `HTTP_PROXY` 环境变量,须写 `~/.gradle/gradle.properties` 的 `systemProp.http(s).proxyHost/Port`(已配,仅 R7P21 用户级,不随工程文件),否则 gradle wrapper 下载发行版与依赖解析会超时。**SLAYER(本机)**走透明代理**无需配置**——2026-09-16 实测 `dl.google.com` 与 `repo1.maven.org` 均 HTTP 200,且 `~/.gradle/gradle.properties` **不存在**;勿在 SLAYER 上照抄代理配置。工程内一律不写死任何代理/镜像配置。
- **AGP 9 起 Kotlin 内置,别再手工加 kotlin-android 插件**(2026-09-16 查一手资料 + **探针实测**核实,D1 重建必读):AGP 9.0 引入 built-in Kotlin 并**默认开启**,对 KGP **2.2.10** 有运行时依赖——**不再需要声明 KGP 版本,也不需要单独安装 Kotlin 工具链**(本机确实没有 `kotlinc`,KGP/Compose 编译器均由 Gradle 自动拉取)。**关键坑**:`org.jetbrains.kotlin.android`(`kotlin-android`)插件**与新 DSL 不兼容**,照抄旧教程会撞 `ClassCastException`;实测还会撞 `Cannot add extension with name 'kotlin'`。官方逃生门是 `gradle.properties` 里设 `android.newDsl=false`(+ `android.builtInKotlin=false`),但**AGP 10 会移除该选项**,不应作为长期方案。来源:[AGP 9.0 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes)(`JDK 最低 17` / built-in Kotlin / 运行时依赖 KGP 2.2.10 均见其兼容表与 Built-in Kotlin 节)。
  > **两条配套实测结论(2026-09-16)**:① **Compose 编译器插件仍需单独应用**——内置 Kotlin 只取代 `kotlin-android`,不取代 `org.jetbrains.kotlin.plugin.compose`;其**版本必须等于 AGP 内置的 KGP 版本**(AGP 9.4.0 → **2.2.10**),不匹配是**运行期**报错。来源:[Kotlin Compose 编译器迁移指南](https://kotlinlang.org/docs/compose-compiler-migration-guide.html)。② **子模块声明 Kotlin 插件不能带版本号**——否则报 `The request for this plugin could not be satisfied because the plugin is already on the classpath with an unknown version`;正确做法是根 `build.gradle.kts` 以 `apply false` 声明版本,子模块只写 id。
- **Kotlin `Regex.split` 丢弃捕获组,与 Python `re.split` 不同(移植陷阱)**(2026-09-16 探针实测,静默失败):`Regex("(\\d+)").split(s)` **不会**把捕获的数字段放进结果(与 Python `re.split` 保留捕获组的行为相反),于是自然排序的"数字段 vs 字符段"切分全部失效、每次比较返回 0 → **排序静默返回原序**。表象是"顺序没变",极易误判为 UI 或状态通知问题。**正确做法**:用 `Regex("\\d+").findAll()` 手动扫描切记号。`scripts/batch_pack_cbz.py` 的自然排序是 Python 实现,移植时**必须**配一条"切分行为"单测钉死。
- **compileSdk 不必 ≥ 手机版本**:手机 Android 16 = API 36,装 `platforms;android-36` 恰好对齐;以后想用新 API 再追加装更高 platform(可多版本并存)。
- **Android SAF:tree URI 不能直接当 parent 传给 `DocumentsContract.createDocument`**(2026-09-08 D2 spike 实证):一加/部分国产 ROM 严格校验 parent 必须是 document URI,直接传 tree URI 会抛 `IllegalArgumentException: Invalid URI`(AOSP 行为宽松,国产 ROM 收紧)。**必须先转换**:
  ```kotlin
  val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
  val parentDocUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
  DocumentsContract.createDocument(resolver, parentDocUri, mime, name)
  ```
  读/列/改名/删直接用 document URI 无需转换;只有"在树里建子项"才需要这步。`DocumentFile.fromTreeUri(ctx, treeUri).createFile(...)` 内部用同样的 tree URI 路径,在严格 ROM 上也会同样失败,务必手动转换。
- **拖拽排序:自研,弃用三方包(交互结论,与实现无关)**(2026-09-10 用户决策,原在 Flutter 原型上验证)。三方包(`reorderable_grid_view 2.2.8`)实测 drop 不落地、拖拽视觉需大量兜底,调优成本超过自建 → **弃用;D1 原型自此零三方依赖**。三条**设计结论**(换栈后继续有效,Kotlin/Compose 侧需重新实现):
  1. **整卡可拖不必抢点击**:只要“指针移动超过 slop 才接受拖拽手势”,静止点按仍是点击(看图/按钮照常)→ 无需额外拖拽把手。
  2. **不要做“悬停实时重排”**:拖拽期间指针微动会反复触发重排 → 顺序来回换位,用户看到的是“拖了但没变”。**正确语义 = 松手落地**(拖动中仅高亮目标格,松手才提交)。
  3. **统一重排入口**:`PageOrder.moveItemTo(id, targetIndex)` 是唯一重排入口,UI 不各自改顺序。
  > 原 Flutter 侧的具体坑(`StackFit.expand` 遇无界高度压成 0、`Draggable`/`LongPressDraggable` 分支)随换栈作废,不在此保留。
- **拖拽必须用 UI 层测试自测,不能只测数据**(2026-09-10 教训,用户反馈“能拖但不改顺序”反复两轮):GUI 交互无法靠日志/截图验证,须**模拟完整手势**(按下 → 超过 slop 移动 → 移到目标 → 抬起)并断言“拖动中顺序不变 + 松手后落在目标下标”。**换栈后须用 Compose 的测试 API 重建等价用例**——这是“修复自证三层”里 UI 层的要求(§9 D1)。
  > **2026-09-16 补充(探针实测)**:该层**可以离线跑**——`Robolectric 4.17 + createComposeRule` 在纯 JVM 单测里跑通 Compose UI 断言(首跑约 65s)。**但"能通过"不等于"能失败"**:探针专门做了负向对照(用普通 `var` 而非 `mutableStateOf` 承载状态,制造"数据变了但界面不动"),断言**如期失败** → 证明该层不是空转。新栈的 UI 断言应照此**同时保留一个负向对照用例**。
- **“数据对了但界面不动”先查状态通知链**(2026-09-10 D1 实测,最隐蔽的一个):当时根因是组合式状态对象(controller 持有子 notifier)只转发了部分通知,导致**数据重排成功、界面永不重建**(日志里 drop/enabled/index 全部正常,极易误判为拖拽组件 bug)。**教训与实现无关:凡“数据对但界面不动”,先查状态变更是否真的传播到了 UI**;换栈到 Compose 后对应的是状态提升 / `mutableStateOf` 的可见性,须在新实现里重新验证。
- **“可选回调传了但没被使用”是静默缺口**(2026-09-10 晚走查反馈 ④,原在 Flutter 原型上发现;**教训与实现无关,2026-09-13 自归档分支补回**):当时网格卡片 `ThumbCard` 收了 `onPin` 参数,却只在“已固定”时画一个静态角标,**没有任何可点入口**(列表行有自己的 `IconButton`,所以只有网格模式缺)——**类型检查与常规单测都发现不了**。修复:网格卡片右下角改为可点圆形按钮(未固定=空心图钉 / 已固定=实心,带 tooltip),并补逻辑 + UI 两项测试钉死(固定 → 换排序规则仍在原位;点空心图钉 → 变实心)。
  > **对应 D1 验收清单第 6 项**(固定位置项在重新应用排序后保持原位)。**Compose 侧同样会踩**:`Modifier.clickable`/回调参数“声明了但没接到可点区域”与 Flutter 一样不会被编译器发现。**唯一可靠的发现方式是按验收清单逐条真机核对**(或 UI 层断言“点得中”),不能只靠“组件已接收该参数”。

### 12.6 版本锚点

| 项 | 版本 |
| --- | --- |
| Git / VS Code / Android Studio | 2.55.0.windows.5 / 1.138.0 / 2026.1.4.7 |
| JDK | temurin-17.0.20+101(mise;Android/Gradle 构建所需);另有 Studio 内置 **JBR 25.0.3** 可用(完整 JDK) |
| Gradle / AGP / Kotlin / Compose BOM | 9.6.0 / 9.4.0 / 2.2.10 / 2026.02.01 —— **来自 Studio 2026.1.4 新建工程模板**;2026-09-12 有成功构建实证(跑在 JBR 25 上),**2026-09-16 已在 temurin-17 下完整复验通过**(探针工程,证据见 `docs/notes/d1-toolchain-evidence.md`) |
| compileSdk / minSdk / targetSdk | 36 / 24 / 36(目标值;Studio 模板默认给的是 37 / 36 / 37,新工程须显式改回) |
| Android platform | `platforms;android-36`(**已装**,与真机 Android 16 对齐);`android-37.0` 也在(模板默认带的) |
| Android SDK 根 | `C:\Users\Administrator\AppData\Local\Android\Sdk` |

### 12.7 验证工程归档(2026-09-13)

> 背景:换栈后 `pixfold-d1`(Flutter 原型)与 `pixfold-saf-spike`(SAF spike)已作废,2026-09-13 按用户指令从磁盘删除。两者**从未进入 `ready` 分支**——删除前先整树提交到**归档分支**,再删除工作区目录。

| 项 | 内容 |
| --- | --- |
| 分支 | **`archive-flutter-verify`** —— ✅ **已推远程**(2026-09-16 复检:`origin/archive-flutter-verify` = `9efef5b` 存在);⚠️ **本地无同名分支**,取用请走 `origin/archive-flutter-verify` |
| tip | `9efef5b`(`feat(verify): 验证工程入库 ready——D1 交互原型…与 SAF 可行性 spike…`) |
| 相对 `ready` | 领先 3 个提交(`ready` 为其祖先):`81d84b2`(走查反馈④) → `556107b`(§4 排序键 + 走查反馈⑤) → `9efef5b`(两工程入库) |
| 内容 | `pixfold-d1/`(Flutter D1 原型,零三方依赖 + 自研拖拽,**16 项测试**,含 `lib/domain/` 下 `comic.dart` / `models.dart` / `naming.dart` 领域层)、`pixfold-saf-spike/`(SAF spike) |

> 注:归档 tip 的测试为 **16 项**,与 §9 D1 执行注所述"14 项测试随换栈作废"不矛盾——换栈决策时是 14 项,其后为走查反馈 ④(网格图钉入口)又补了 2 项,归档保存的是补齐后的最终快照。

**归档中仍有价值的资产**(换栈后不迁移代码,只作参考):

- **`pixfold-saf-spike` 的 Kotlin SAF 实现** —— `android/app/src/main/kotlin/com/example/pixfold_saf_spike/MainActivity.kt`,含 `openTree` / `listImages` / `readBytes` / `renameDoc` / `createAndWrite` / `deleteDoc` 六方法及 tree URI→document URI 转换;**新栈平台层可直接参考**(§8.3 的方法表在正文保留)。
- **`pixfold-d1` 的领域层语义与测试意图** —— 代码本身作废,但测试覆盖的语义(拖拽落位、固定位置、危险项默认关闭等)须在新栈重建(§9 D1 执行注)。

**备份状态与取用**(2026-09-16 复检):归档分支**已在 `origin` 上**,原先"唯一副本只在本地、有丢失风险"的隐患**已消除**(§12.7 原"建议 push"一项已完成)。取用方式:`git show origin/archive-flutter-verify:<路径>`(或直接 `git show 9efef5b:<路径>`,该 commit 对象在本地已存在),需要整树则 `git worktree add <目录> origin/archive-flutter-verify`。

> **⚠️ 教训与约定变更(2026-09-16)**:上一次的丢失风险根因是**原型代码放在仓库外的 `C:\Personal\` 目录**、且不在任何分支上——目录一删就只剩事后补的归档分支。故**本次 D1 重建改为在仓库内开阶段分支开发**(见 §9.1):代码从第一天起就在版本控制里,且每阶段完成即提交。**归档分支从此只作历史参考,不再是"唯一副本"的存放处。**
> 归档中**已提取为独立笔记**的部分(便于新栈直接取用,不必再读 Dart 源码):领域语义与 16 项测试意图 → `docs/notes/d1-archive-domain-semantics.md`。

## 13. 参考文件

- 现有行为参考:`scripts/batch_rename_images.py`
- 现有行为参考:`scripts/batch_pack_cbz.py`
- 现有行为参考矩阵(危险默认值逐条):`docs/notes/scripts-behavior-matrix.md`
- 归档原型领域语义与测试意图:`docs/notes/d1-archive-domain-semantics.md`
- 新栈构建链实证(2026-09-16 探针):`docs/notes/d1-toolchain-evidence.md`
- D1 规格与分阶段计划:`docs/superpowers/specs/`、`docs/superpowers/plans/`
- 项目门面:[`README.md`](README.md)
