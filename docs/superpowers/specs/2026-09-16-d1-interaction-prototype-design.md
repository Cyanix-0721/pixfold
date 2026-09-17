# PixFold D1 交互原型 · 设计规格

> **面向 Agent 执行者**:本规格是 D1 实施计划的论证依据。执行前请同时阅读本规格与对应阶段计划
> (`docs/superpowers/plans/`),并遵守文末《全局约束》。
> **产品决策权威仍是 `HANGOFF.md`**;本规格只承载 D1 阶段的实现级设计,与 HANGOFF 冲突时以 HANGOFF 为准。

**目标**:用 Kotlin + Jetpack Compose 在 Android 上重建 D1 交互原型——不连真实文件系统、用确定性 mock 数据,
让用户完整走通「图片整理与命名」(工作流 A)与「CBZ 制作」(工作流 B)两条流程,并使 HANGOFF §9《D1 验收清单》20 项可逐条验证。

**架构**:两模块 Gradle 工程。`domain/` 是**零 Android 依赖的纯 Kotlin JVM 模块**,承载全部领域逻辑
(排序、命名、元数据、ComicInfo.xml、计划),以**不可变状态 + 纯函数**表达;`app/` 是 Compose UI 层,
只做状态持有与渲染。依赖方向单向 `app → domain`,由 Gradle 边界强制。

**技术栈**:Kotlin 2.2.10(AGP 内置)、Jetpack Compose(Compose BOM 2026.02.01)、AGP 9.4.0、Gradle 9.6.0、
JDK 17(temurin-17);测试用 JUnit4 + Robolectric 4.17。**零三方依赖**(除 AndroidX/Compose/JUnit/Robolectric 等官方测试件)。

**规格**:本文件。领域语义来源 `docs/notes/d1-archive-domain-semantics.md`;构建链实证 `docs/notes/d1-toolchain-evidence.md`。

---

## 1. 范围

### 1.1 做

D1 六项交付(HANGOFF §9 D1):图片列表/缩略图预览、拖拽排序、命名结构编辑器、CBZ 元数据编辑器、
执行计划预览、冲突和警告展示。外加排序规则编辑器与危险动作二次确认(验收清单第 5/6/7/16 项要求)。

### 1.2 不做(明确排除)

- **不连真实文件系统**——无 SAF、无 ContentResolver、无权限请求;数据全部来自确定性 mock。
  (SAF 已在 D2a 验收;D2b 再进平台层。)
- **不创建正式工程**——本原型是验证工程,`pixfold-d1/` 只存在于阶段分支与 `dev`,不进 `ready`/`main`。
- **不做真实文件写入**——"执行"只产出报告,mock 掉副作用。
- **不引入持久化**——无数据库、无文件存储;状态活在内存(进程重启即重置,原型可接受)。
- **不做后台任务/进度**——属 D2b。
- **不扩展 ComicInfo.xml 字段**——只做与 `scripts/batch_pack_cbz.py` 对齐的字段子集(Title/Series/Number/Writer/LanguageISO)。

---

## 2. 全局约束

以下约束对所有阶段计划默认生效,数值与措辞不得擅改。

1. **验收判据不可调整**:HANGOFF §9《D1 验收清单》20 项是 D1 唯一出口;任一条不通过即记为待修项,
   **不得改判据以迁就现状**。清单条目变更须用户确认。
2. **修复自证三层**:凡交互或数据改动,依次过「领域单测 → UI 层断言 → 真机实证」;不得只凭"测试通过"交付。
   第 1、2 层必须能**离线**跑通(已实证,见 `d1-toolchain-evidence.md`)。
3. **零三方依赖**:实现只用 Kotlin 标准库、AndroidX/Compose、JUnit4、Robolectric。
   **不引入**任何拖拽/排序/日期/序列化三方库。拖拽自研(HANGOFF §12.5 用户决策)。
4. **不应用 `kotlin-android` 插件**:AGP 9 内置 Kotlin。Compose 编译器插件
   (`org.jetbrains.kotlin.plugin.compose`)**必须单独应用且版本锁 2.2.10**(= AGP 9.4.0 内置 KGP 版本)。
   子模块声明插件**不带版本号**(版本在根 `build.gradle.kts` 以 `apply false` 声明)。
5. **构建走 Windows 原生**:经 `cmd.exe /c gradlew.bat` 调用,不在 WSL 内跑 Gradle(HANGOFF §12.5 实测 159× I/O 差异)。
6. **compileSdk / minSdk / targetSdk = 36 / 24 / 36**;`namespace`/`applicationId` = `com.pixfold.d1`。
7. **`local.properties` 不入库**(含机器相关 `sdk.dir`);`build/`、`.gradle/`、`.kotlin/` 不入库。
8. **领域层不依赖 Android**:`domain/` 不得 import 任何 `android.*` / `androidx.*`;违反即架构缺陷。
9. **不可变状态 + 纯函数**:领域状态一律用 `data class` 表达,变更经纯函数返回新实例;
   **不用**可变 `ChangeNotifier` 式通知链(归档原型"数据对了但界面不动"的根因类别)。
10. **UI 文案用中文**,与 HANGOFF 及归档原型的措辞保持一致(下拉标签、按钮文案、警告 message)。
11. **单位与阈值取自归档语义,不得臆造**:超长阈值 **180**、非法字符集 `\ / : * ? " < > |` + 控制字符、
    多级排序上限 **4**、至少 **1** 级、页码补零 `>=100 ? 4 : 3` 位、默认 `indexStart=1`/`indexPadding=3`。
12. **提交粒度**:每阶段完成即提交(只 commit,**push 由用户处理**);阶段分支完成即合回 `dev` 并回写文档。
13. **UI 须符合最新 Material Design**(HANGOFF §6.5):用 material3 组件的**语义角色**而非硬编码值——
    颜色走 `MaterialTheme.colorScheme`(支持深色模式;Android 12+ 动态取色,低版本回退静态 scheme)、
    排版走 `MaterialTheme.typography` 语义层级(不手写 `fontSize`)、圆角走 `MaterialTheme.shapes`;
    触控目标 ≥ 48dp、正文 ≥ 14sp、纯图标按钮须有 `contentDescription`。
    **本阶段基准 = material3 `1.4.0`(stable)**;**M3E(Material 3 Expressive)不在本阶段范围**——
    其公开 API 仅存在于 `1.5.0-alpha28`,stable 与最新 BOM 均为 1.4.0 且 M3E 类型为 `internal`
    (实测见 `docs/notes/d1-toolchain-evidence.md`)。M3E 留到 P7 走查后评估(HANGOFF §6.5)。

---

## 3. 工程结构与分层

```text
pixfold-d1/
├── settings.gradle.kts            include(":app", ":domain")
├── build.gradle.kts               根:插件版本 apply false
├── gradle.properties
├── gradle/libs.versions.toml      版本目录(锁版本)
├── local.properties               sdk.dir(不入库)
├── .gitignore
├── domain/                        ← 纯 Kotlin JVM,零 Android
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/pixfold/d1/domain/
│       │   ├── model/             实体与枚举
│       │   ├── sort/              自然排序、多级排序、PageOrder 纯函数
│       │   ├── naming/            命名求值、冲突检测、改名计划
│       │   ├── metadata/          卷元数据、ComicInfo.xml、页码重编号
│       │   └── plan/              操作计划、危险动作、执行报告
│       └── test/kotlin/...        纯 JVM 单测(第 1 层)
└── app/                           ← Android + Compose
    ├── build.gradle.kts           implementation(project(":domain"))
    └── src/
        ├── main/kotlin/com/pixfold/d1/
        │   ├── MainActivity.kt
        │   ├── ui/                首页、工作流 A/B 各页、公共组件
        │   ├── state/             状态持有器(mutableStateOf)+ 纯函数调用
        │   └── mock/              确定性 mock 数据
        └── test/kotlin/...        Robolectric Compose UI 断言(第 2 层)
```

**分层规则**:`domain/` 不知道 UI 的存在;`app/` 不自己实现领域规则(排序、命名、警告判定、XML 生成
一律调用 `domain/`)。UI 只负责"把状态画出来"与"把用户操作翻译成纯函数调用"。

**为什么独立模块**:① 让第 1 层单测秒级反馈、不启 Android 运行时;② 用 Gradle 依赖边界**强制**
"领域逻辑不依赖 UI 与平台层"(HANGOFF §5 关键原则 3、§9 D3),而非靠自觉。

---

## 4. 领域模型(Kotlin)

> 字段名与语义对齐 `d1-archive-domain-semantics.md` §1;时间字段的实现方式有一处**有意偏离**,见 §4.1 注。

### 4.1 `SourceItem`(= 归档 `MockImage`)

```kotlin
data class SourceItem(
    val id: String,            // 稳定唯一标识;同时是排序兜底键
    val collectionId: String,  // 所属集合/卷
    val dir: String,           // 相对目录, "" = 根
    val baseName: String,      // 不含扩展名的文件名主干
    val ext: String,           // 扩展名,不含点;大小写原样保留
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
    val createdEpochMillis: Long?,
    val seed: Int,             // 程序化缩略图的确定性种子
) {
    val fileName: String get() = "$baseName.$ext"
    val relPath: String get() = if (dir.isEmpty()) fileName else "$dir/$fileName"
    val sizeLabel: String get() = /* >=1MiB → "x.y MB"; >=1KiB → "x KB"; else "n B" */
}
```

> **注(有意偏离)**:归档用 `DateTime`。新栈改用 **epoch millis(`Long`)**——避免 `java.time` 在
> minSdk 24 上需要 core library desugaring,保持零额外配置与零三方依赖;排序只需比较大小,语义等价。

### 4.2 `ImageCollection`(= 归档 `ImageCollection`)

```kotlin
data class ImageCollection(
    val id: String,
    val name: String,
    val rootDirName: String,   // 独立于 name;是命名组件 rootDir 的取值来源
    val images: List<SourceItem>,
)
```

### 4.3 排序枚举与规则

```kotlin
enum class SortField(val label: String) {
    NaturalName("文件名(自然)"),
    FileName("文件名(字典)"),
    DirName("目录名"),
    Modified("修改时间"),
    Created("创建时间"),
    Size("文件大小"),
}
data class SortKey(val field: SortField, val ascending: Boolean)
data class SortRule(val keys: List<SortKey>)   // keys.size in 1..4
```

**枚举声明顺序 = UI 下拉顺序**(HANGOFF §4 排序键定位,用户 2026-09-10 定序),不得重排。
默认规则 = `SortRule([SortKey(DirName, true), SortKey(NaturalName, true)])`。

### 4.4 命名相关

```kotlin
enum class NameComponentKind(val label: String) {
    Prefix("自定义前缀"), RootDir("根目录名"), RelDir("相对目录片段"),
    DirIndex("目录序号"), ImageIndex("图片序号"), OrigName("原文件名"), CustomText("自定义文本"),
}
enum class CasePolicy(val label: String) { Keep("保持原样"), Lower("转小写"), Upper("转大写") }
enum class ExtPolicy(val label: String) {
    Keep("保留原样"), Lower("统一小写"), Upper("统一大写"), Strip("去除扩展名")
}
data class NameComponent(
    val kind: NameComponentKind,
    val enabled: Boolean = true,
    val separatorBefore: String = "_",
    val casePolicy: CasePolicy = CasePolicy.Keep,
    val trimSpaces: Boolean = true,
    val text: String = "",
)
data class NamingScheme(
    val rootDirName: String,
    val components: List<NameComponent> = DEFAULT_COMPONENTS,
    val indexStart: Int = 1,
    val indexPadding: Int = 3,
    val extPolicy: ExtPolicy = ExtPolicy.Lower,
    val sanitize: Boolean = true,
    val replacement: String = "_",
) {
    companion object {
        // 默认输出 `<根目录名>_<三位序号>.<小写扩展名>`
        val DEFAULT_COMPONENTS = listOf(
            NameComponent(NameComponentKind.RootDir),
            NameComponent(NameComponentKind.ImageIndex),
            NameComponent(NameComponentKind.OrigName, enabled = false),
        )
    }
}
enum class ProposalWarningType { Duplicate, IllegalChar, CaseCollision, TooLong, Overridden }
data class ProposalWarning(val type: ProposalWarningType, val message: String)
data class NameProposal(
    val image: SourceItem,
    val proposedName: String,
    val overrideName: String? = null,
    val warnings: List<ProposalWarning> = emptyList(),
) {
    val finalName: String get() = overrideName ?: proposedName
    val isOverridden: Boolean get() = overrideName != null
    val hasConflict: Boolean get() = warnings.any {
        it.type == ProposalWarningType.Duplicate || it.type == ProposalWarningType.IllegalChar
    }
}
```

### 4.5 元数据相关

```kotlin
data class Suggestion<T>(val value: T?, val source: String) {
    val hasValue: Boolean get() = value != null && (value !is String || value.isNotEmpty())
}
enum class LangChoice(val label: String) {
    Unset("未设置（需确认）"), Zh("zh · 中文"), Ja("ja · 日文"),
    Other("其他 ISO 639-1"), Unknown("未知"), Skip("不写入标签"),
}
enum class MetaField { Title, Series, Writer, Volume, Language, CbzName, OutputDir }
enum class OutputConflictPolicy(val label: String) {
    Rename("自动重命名（加序号）"), Skip("跳过该卷"), Overwrite("覆盖已有文件"),
}
enum class SourcePolicy(val label: String) {
    Keep("保留源目录（默认）"), Delete("打包后删除源目录（危险）"),
}
```

`ComicVolume` 的**不可变建议字段**与**可变当前值**分离:

```kotlin
data class ComicVolume(
    val id: String, val dirPath: String, val pages: List<SourceItem>,
    // 不可变建议(带来源)
    val titleSug: Suggestion<String>, val seriesSug: Suggestion<String>,
    val writerSug: Suggestion<String>, val writerCleanedSug: Suggestion<String>,
    val volumeSug: Suggestion<Int?>, val langSug: Suggestion<String>,
    val outputDirSug: String, val outputExists: Boolean,
    // 当前值(用户可改)
    val title: String, val series: String, val writer: String,
    val volume: Int?, val language: LangChoice = LangChoice.Unset,
    val otherLangCode: String = "", val cbzFileName: String, val outputDir: String,
    val overridden: Set<MetaField> = emptySet(),
    val pageOrder: PageOrderState, val included: Boolean = true,
)
```

**`language` 默认 `LangChoice.Unset` 是硬语义**:即使 `langSug` 有值(如 `ja`)也不自动采纳,**必须人工确认**。

### 4.6 计划与报告

```kotlin
data class RenameOp(val proposal: NameProposal, val status: OpStatus, val reason: String?)
enum class OpStatus { Ok, Skipped }
data class ExecutionReport(
    val timeLabel: String, val okCount: Int, val skipCount: Int, val failCount: Int,
    val messages: List<String>,
)
data class VolumePackPlan(
    val volume: ComicVolume,
    val renumbered: List<Pair<SourceItem, String>>,   // 计划生成时快照
    val conflict: Boolean,
    val conflictPolicy: OutputConflictPolicy,
    val sourcePolicy: SourcePolicy,
)
```

### 4.7 警告与"待确认项"

```kotlin
const val ILLEGAL_NAME_CHARS = "\\/:*?\"<>|"   // 9 个
const val TOO_LONG_THRESHOLD = 180             // UTF-16 码元数

// ComicVolume 派生
val ComicVolume.pendingIssues: List<String>
    get() = buildList {
        if (language == LangChoice.Unset) add("语言未确认")
        if (volume == null) add("卷号缺失")
    }
```

---

## 5. 排序语义

### 5.1 自然排序 `naturalCompare(a: String, b: String): Int`

按字符逐位扫描;仅当**两侧当前位置都是数字**时进入数字段比较,否则单字符码点比较。数字段为**贪心最长数字串**。

**数值比较必须防溢出**(归档用 `int.parse`,超长数字串会抛错)。等价且安全的实现:

1. 取两侧数字段 `ra`、`rb`(原始串,含前导零);
2. 去前导零得 `na`、`nb`(全零则退化为 `"0"`);
3. 先比 `na.length` vs `nb.length`(长度大者数值大);相等再按字典序比 `na` vs `nb`;
4. 若数值相等 → **比原始位数** `ra.length` vs `rb.length`(位数少者在前)。

> 步骤 3–4 与归档"parse 后比较、相等则比位数"**语义等价**,但对超长数字串不溢出。
> 归档未覆盖"数值相等仅前导零不同"的分支(`"1"` vs `"01"` → `"1"` 在前),**新栈必须自补该用例**。

**大小写**:调用方先 `toLowerCase()`(自然序大小写不敏感)。数字判定是**纯 ASCII** `0x30..0x39`。

### 5.2 多级排序 `sortItems(images, rule): List<SourceItem>`

```kotlin
images.sortedWith { a, b ->
    for (key in rule.keys) {
        val c = compareField(a, b, key.field)
        if (c != 0) return@sortedWith if (key.ascending) c else -c   // 每级独立升降序
    }
    a.id.compareTo(b.id)                                             // 稳定兜底(必需)
}
```

- **每级独立升降序**;第一级不同即返回。
- 全级相等时按 `id` 字典序兜底 → 结果**确定**。Kotlin `sortedWith` 本身稳定,但**仍保留 id 兜底**
  以与归档行为逐位一致。
- 纯函数:不改入参。

各字段比较语义(严格对齐归档):

| field | 比较 |
| --- | --- |
| `NaturalName` | `naturalCompare(a.fileName.toLowerCase(), b.fileName.toLowerCase())` |
| `FileName` | `a.fileName.toLowerCase()` 字典序 |
| `DirName` | `a.dir` 原始字典序(**不转小写**) |
| `Modified` | `a.modifiedEpochMillis.compareTo(b.modifiedEpochMillis)` |
| `Created` | `createdEpochMillis` 比较,**null 视为最小** |
| `Size` | `sizeBytes.compareTo` |

### 5.3 两层/三层状态:`PageOrderState` + 纯函数

```kotlin
data class PageOrderState(
    val images: List<SourceItem>,      // 全集
    val rule: SortRule,
    val order: List<SourceItem>,       // 当前页序
    val manualIds: Set<String>,        // 人工调整标记:applyRule 时清空
    val pinnedIds: Set<String>,        // 固定位置:applyRule 时保留
) {
    val hasManual: Boolean get() = manualIds.isNotEmpty()
    val manualCount: Int get() = manualIds.size
    val pinnedCount: Int get() = pinnedIds.size
    fun isManual(id: String) = id in manualIds
    fun isPinned(id: String) = id in pinnedIds
}
```

三个纯函数(**唯一变更入口**,UI 不得各自改顺序):

```kotlin
fun pageOrderOf(images: List<SourceItem>, rule: SortRule = defaultRule()): PageOrderState
fun moveItemTo(state: PageOrderState, id: String, targetIndex: Int): PageOrderState
fun togglePin(state: PageOrderState, id: String): PageOrderState
fun applyRule(state: PageOrderState, rule: SortRule): PageOrderState
```

**`moveItemTo` 精确语义**(归档 §3.3,含全部边界):

1. `from = order.indexOfFirst { it.id == id }`;`from < 0` → **原样返回**(静默 no-op);
2. `target = targetIndex.coerceIn(0, order.size - 1)`(**越界夹取**,不是拒绝);
3. `from == target` → **原样返回**(拖到自己格:顺序不变**且不记人工标记**);
4. 先 `removeAt(from)` 再 `insert(target, item)`——**`target` 是"移除源项之后"的插入位**;
5. `manualIds + id`。

> **关键陷阱**:因"先删后插",当 `from < target` 时元素最终落在下标 `target`(而非 `target+1`)。
> 必须用 `list.toMutableList().also { it.removeAt(from); it.add(target, item) }` 表达并配单测钉死。

**`applyRule` 固定项落位算法**(归档 §3.2,精确复刻):

1. 深拷贝新规则(防 UI 草稿被后续修改污染);
2. 记录每个固定项**当前**下标 → `Map<Int, SourceItem>`;
3. 对全集按新规则排序 → `sorted`;
4. 从 `sorted` 剔除所有固定项 → `rest`(保持相对次序);
5. 建 `result: Array<SourceItem?>`(全长,全 null);
6. **按记录下标升序**遍历固定项:`idx = min(记录下标, len-1)`;`while (idx >= 0 && result[idx] != null) idx--`
   (从记录位**向左**找第一个空位);`idx < 0` 则丢弃该项(极端兜底);
7. 从左到右把 `rest` 依次填入 `null` 位;
8. `order = result.filterNotNull()`;
9. **`manualIds` 清空**;**`pinnedIds` 保留**。

> `resetToAuto(state) = applyRule(state, state.rule)`——"放弃人工调整"但**固定项位置保留**
> (同一条路径)。UI 按钮文案 `重置人工调整（N 处）`,`N = manualCount`,无人工调整时禁用。

### 5.4 批次规则 vs 集合独立规则

```kotlin
data class WorkflowAState(
    val collections: List<ImageCollection>,
    val orders: Map<String, PageOrderState>,
    val batchRule: SortRule,
    val customRuleCollections: Set<String>,   // 使用独立规则的集合
) {
    fun effectiveRule(id: String): SortRule =
        if (id in customRuleCollections) orders.getValue(id).rule else batchRule
}
```

- `applyRuleToCollection(state, id, rule)`:该集合"本组独立" → 只重排该集合;
  否则 → 更新 `batchRule` 并**对所有非独立集合**重排。
- `toggleCustomRule(state, id)`:加入时变为独立(**保留当前顺序**);移除时**立刻用 `batchRule` 重排该集合**。
- ~~规则编辑用**草稿**,点"应用排序"才落地——**编辑不实时生效**。~~
  **2026-09-17 用户决定覆盖**:改为**变动即自动应用**——去掉"应用排序"按钮与草稿态
  (换字段 / 翻转方向 / 增删级均立即重排)。同时升降序交互改为:
  **再次点击已选中的字段**即翻转升/降序,方向用**实心三角**显示在选中字段名之后
  (原独立"升序/降序"切换按钮已移除)。详见 HANGOFF §9.1 走查项 W3。
- 命名结构与逐项覆盖是**批次级**(不属于某个集合),`overrides: Map<String, String>` 全局一份。

---

## 6. 命名语义

### 6.1 求值入口

```kotlin
fun buildProposals(
    order: List<SourceItem>,               // 当前页序(含人工调整)
    scheme: NamingScheme,
    dirIndexMap: Map<String, Int>? = null,
    overrides: Map<String, String>? = null,
): List<NameProposal>
```

逐项生成:

1. **目录序号表**:`dirs = order.map { it.dir }.distinct().sorted()`(字典序),默认 `{dirs[i]: i+1}`(1-based),
   可被 `dirIndexMap` 覆盖。→ **换序不改变目录编号**。
2. 遍历 `scheme.components`,跳过 `!enabled`;
3. 求组件文本(见 §6.2);
4. **`part.isEmpty()` 的组件整体跳过(连分隔符一起丢)**——空目录名/空文本/空前缀不留多余分隔符;
5. 拼接:`name = parts[0]`,然后 `name += seps[k] + parts[k]`(k 从 1 起)
   —— **首个组件不输出分隔符**;
6. 扩展名策略:`Keep` → `+ "." + ext`;`Lower` → `+ "." + ext.lowercase()`;`Upper` → 大写;`Strip` → 不追加;
7. 清洗:`if (scheme.sanitize) name = sanitizeName(name, scheme.replacement)`
   —— 清洗作用于**完整文件名(含扩展名)**;
8. 覆盖:`overrideName = overrides?.get(image.id)`;
9. 最后统一 `attachWarnings(proposals, sanitize = scheme.sanitize)`。

### 6.2 组件取值与后处理

| kind | 取值 | 补零 |
| --- | --- | --- |
| `Prefix` / `CustomText` | `comp.text` | 无 |
| `RootDir` | `scheme.rootDirName` | 无 |
| `RelDir` | `img.dir` | 无 |
| `DirIndex` | `dirIndexMap[img.dir] ?: 1` | `padStart(indexPadding, '0')` |
| `ImageIndex` | `indexStart + position`(position = **0-based 页序下标**) | `padStart(indexPadding, '0')` |
| `OrigName` | `img.baseName` | 无 |

后处理顺序:**先 `casePolicy`(逐组件),再 `trimSpaces`(`trim()` 去首尾空白,不动内部)**。
`indexStart` 默认 1(UI `min=1`)、`indexPadding` 默认 3(UI `min=1`);序号本身不做去重,交给 §7 重名检测。

### 6.3 逐项覆盖(override)——"改结构后覆盖不丢失"的机制

- 存储:`Map<String, String>`,**键 = `SourceItem.id`**,值 = 用户输入的完整文件名。
- **覆盖不存储在 `NamingScheme` 里**,而是独立于命名结构的映射 → 改组件/分隔符/补零只影响 `proposedName`;
  `finalName` 优先取 `overrideName`,故覆盖项**逐字不变**。
- 写入:`setOverride(id, name)`;`name` 为 null 或空 → **remove(清空输入框 = 撤销覆盖**,不是"改成空名")。
- UI 同步:**只回写未被覆盖的输入框**(`if (!p.isOverridden && ctrl.text != p.finalName) ctrl.text = p.finalName`);
  被覆盖的输入框保留用户文本。
- 恢复建议值入口:把输入框置为 `proposedName` 并清除该 id 的覆盖。
- 任何 scheme/override/排序变化 → **使已生成的计划失效**(`plan = null`),防"看到的计划 ≠ 当前设置"。

### 6.4 改名计划

```kotlin
fun buildRenamePlan(proposals: List<NameProposal>): List<RenameOp>
```

- `hasConflict`(含 `Duplicate` 或 `IllegalChar`)→ `OpStatus.Skipped`,**reason 取第一个** Duplicate/IllegalChar 警告的 message;
- 否则 `OpStatus.Ok`;
- **`CaseCollision` 与 `TooLong` 不导致跳过**,仅警告。
- 工作流 A 的计划遍历**所有集合**拼接成整库计划。

---

## 7. 冲突与警告检测

`attachWarnings(proposals, sanitize)` 一次遍历,检测 5 类:

| 类型 | 判定 | 阈值/条件 | 跳过? |
| --- | --- | --- | --- |
| `Duplicate` 重名 | `finalName` **完全相同**(大小写敏感)的项数 > 1 | `byName[finalName].size > 1` | **是** |
| `IllegalChar` 非法字符 | `!sanitize && containsIllegalChar(finalName)` | 见下 | **是** |
| `CaseCollision` 大小写冲突 | `finalName.lowercase()` 相同但**原始名不全同** | 组内已有完全同名则跳过(已报 Duplicate) | 否(仅警告) |
| `TooLong` 超长 | `finalName.length > 180` | UTF-16 码元数(中文按 1 计) | 否(仅警告) |
| `Overridden` 人工覆盖 | `isOverridden` | — | 否(信息性) |

- **非法字符集**:`\ / : * ? " < > |`(9 个)+ 码点 `< 0x20`(控制字符)。
  `sanitizeName` 把上述全部替换为 `replacement`(默认 `"_"`)。
- **`IllegalChar` 只在 `sanitize == false` 时产生**;开了自动清洗则不会有该警告,但清洗后的名字**仍可能撞 Duplicate**
  —— 两者互补而非重复。
- **`CaseCollision` 精确语义**:按 `lowercase()` 分组,组内 ≥2 项;若组内存在完全同名
  (`distinct().size < group.size`)→ **只报 Duplicate,不报 CaseCollision**;否则组内每项加 CaseCollision。
- 所有警告 message 必须**人类可读**,UI 直接显示。

---

## 8. 元数据与 ComicInfo.xml

### 8.1 字段与默认值

| UI 标签 | `MetaField` | 类型 | 初值 | 建议来源 | 恢复建议 |
| --- | --- | --- | --- | --- | --- |
| `Title` | `Title` | `String` | `titleSug.value ?? ""` | `titleSug` | `useSuggestion(Title)` |
| `Series` | `Series` | `String` | `seriesSug.value ?? ""` | `seriesSug` | `useSuggestion(Series)` |
| `Writer` | `Writer` | `String` | `writerSug.value ?? ""` | `writerSug`(+ `writerCleanedSug` 一键清理) | `useSuggestion(Writer)` |
| `Number` | `Volume` | `Int?` | `volumeSug.value` | `volumeSug` | `useSuggestion(Volume)` |
| `LanguageISO` | `Language` | `LangChoice` | **`Unset`** | `langSug` | `useSuggestion(Language)` → 回 `Unset` + 清空 `otherLangCode` |
| `CBZ 文件名` | `CbzName` | `String` | `defaultCbzName()` | 由 `titleSug`+`volumeSug` 派生 | `useSuggestion(CbzName)` |
| `输出目录` | `OutputDir` | `String` | `outputDirSug` | `outputDirSug` | `useSuggestion(OutputDir)` |

**建议 ≠ 事实**:每个建议值必须带**人类可读来源字符串**(含置信度措辞:如 `低置信`/`中置信`/`（推断）`/`无语言线索`),
UI 必须可见(建议标签 + 来源),且**逐字段有"恢复建议值"入口**。

`language` 为 `Unset` 时 UI 显示 `errorText: '语言默认未设置，需人工确认'`。

### 8.2 批量设置不覆盖逐项例外

- `overridden: Set<MetaField>` 记录被人工改过的字段。
- 批量设置**跳过** `overridden` 中的字段,并**返回实际生效数量**(UI 提示 `已设置到 N 卷`)。
- `useSuggestion(field)` **清除该字段的覆盖标记**。

### 8.3 `defaultCbzName()`

```kotlin
val t = (titleSug.value ?: id).replace(Regex("[\\\\/:*?\"<>|]"), "_")
val v = volumeSug.value
return if (v == null) "$t.cbz" else "$t 第${v.toString().padStart(2, '0')}卷.cbz"
```

> 用的是 **`titleSug` 而非用户改后的 `title`**——默认名只在构造时算一次,改 `title` **不会**自动重算
> (需手动改或点恢复建议值)。这是归档既有语义,保留。

### 8.4 `langIso`

| choice | 返回 |
| --- | --- |
| `Zh` | `"zh"` |
| `Ja` | `"ja"` |
| `Other` | `otherLangCode` 空 → `null`;否则原样返回(**不校验 ISO 639-1 合法性**) |
| `Unset` / `Unknown` / `Skip` | `null`(= 不写入标签) |

### 8.5 `buildComicInfoXml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <Title>…</Title>
  <Series>…</Series>
  <Number>…</Number>
  <Writer>…</Writer>
  <LanguageISO>…</LanguageISO>
</ComicInfo>
```

- 字段顺序固定:`Title` → `Series` → `Number` → `Writer` → `LanguageISO`。
- **空值/不写入时输出自闭合空标签**(`<Title />`、`<LanguageISO />`),**不是省略标签**。
  判定:`value == null || value.isEmpty()`。
- XML 转义只处理 4 个:`&`→`&amp;`、`<`→`&lt;`、`>`→`&gt;`、`"`→`&quot;`(**不转义 `'`**)。
- 缩进 **2 空格**,每行以 `\n` 结尾。
- `Number` = `volume?.toString()`,`null` → 空标签。

### 8.6 页码重编号 `renumberPages`

```kotlin
val order = volume.pageOrder.order          // 含人工调整的最终页序
val padding = if (order.size >= 100) 4 else 3
order.mapIndexed { i, item -> item to "${(i + 1).toString().padStart(padding, '0')}.${item.ext.lowercase()}" }
```

- 序号**从 1 起**,补零位数**按总页数自适应**(3/4 位),扩展名**强制小写**;
- 与工作流 A 的 `indexPadding` **完全独立**(不读 `NamingScheme`);
- 保留原 `SourceItem` 引用(供 UI 标"人工调整")。

---

## 9. 计划、危险动作与报告

### 9.1 打包计划 `describe()` 文案契约(按序)

1. (若 skipped)`跳过：输出已存在 <outputDir>/<cbzFileName>` 并 **return**;
2. `创建 CBZ → <outputDisplay>`;
3. `写入 ComicInfo.xml（Title=…，Series=…，Number=<volume ?: "未设置">，Writer=<空则"空">，LanguageISO=<langIso ?: "不写入">）`;
4. `按页序打包 <N> 张并重命名为页码（0001 起）`;
5. (若 `sourcePolicy == Delete`)`打包后删除源目录：<dirPath>`。

- 输出冲突默认 `Rename`:`outputDisplay` 在最后一个 `.` 前插入 ` (1)`
  → `name.substring(0, dot) + " (1)" + name.substring(dot)`。
- `Skip` → 计划 skipped;`Overwrite` → 不跳过、不加序号。
- `buildPackagePlans` **只处理 `included == true`** 的卷;默认 `conflictPolicy = Rename`、`sourcePolicy = Keep`。

### 9.2 危险动作(默认关闭 + 通用确认 + 独立二次确认)

| 动作 | 默认 | 二次确认 |
| --- | --- | --- |
| 删除源目录(`SourcePolicy.Delete`) | **`Keep`** | 选 `Delete` 时卡片立刻红色警告;点"确认打包"先弹**通用确认**(列出"源目录：删除（危险）"),通过后**再弹独立确认**:标题 `⚠️ 危险操作独立确认`,正文 `你选择了「打包后删除源目录」。删除不可恢复，确认继续？`,按钮 `取消` / `确认删除源目录` |
| 清理空目录(`cleanEmptyDirs`) | **`false`** | 勾选框副标题写明"默认关闭；勾选后仍需在确认对话框中再次确认";确认框正文追加 `⚠️ 勾选了「清理空目录」…`;随后同样的独立二次确认 |
| 覆盖已有 CBZ(`Overwrite`) | 非默认(默认 `Rename`) | **归档原型无独立二次确认——新栈必须补上**(见 §13 偏离说明) |
| 重命名本身 | 可执行 | 通用确认:标题 `确认执行？`,正文列明"仅重命名 / 源文件保留，不删除任何文件 / 冲突项将跳过并列出原因",按钮 `再看看` / `执行` |
| 冲突项 | **默认跳过** | 计划页红色图标 + 红色文件名 + reason 文本 |
| 撤销 | 提供但为 mock | 报告页"撤销本次执行",弹提示说明"正式版将基于 `UndoRecord` 记录逆向操作" |

**统一原则**:任何删除类动作 = 默认关闭 + 通用确认 + **独立危险确认**(两次),且确认框必须**列出影响范围**。

---

## 10. UI 结构与阶段映射

### 10.1 页面结构

```text
首页
├── 工作流 A · 图片整理与命名
│   ├── 步骤1 排序与预览   缩略图网格/列表双视图、大图预览、拖拽、图钉、排序规则编辑器
│   ├── 步骤2 命名结构     组件编辑器 + 建议表(原路径→新名) + 冲突/警告 + 筛选
│   └── 步骤3 执行计划     操作清单 + 跳过原因 + 危险动作 + 通用确认
└── 工作流 B · CBZ 制作
    ├── 步骤1 漫画库       卷列表(分组/待确认标记/included 开关)
    ├── 步骤2 元数据       逐卷字段编辑 + 建议来源 + 批量设置 + 语言确认
    └── 步骤3 打包计划     ComicInfo.xml 预览 + 页码列表预览 + 冲突策略 + 危险动作
结果页(两条工作流共用):执行报告(成功/跳过/失败)+ 撤销入口
```

### 10.2 七阶段切片

| 阶段 | 交付物 | 覆盖验收项 |
| --- | --- | --- |
| **P1** | 工程脚手架 + `domain/` 地基(§4 模型、§5 排序与 `PageOrderState`、确定性 mock)+ 首页骨架 | —(地基) |
| **P2** | 缩略图网格/列表双视图、大图预览(自适应/缩放/平移/翻页) | 1, 2 |
| **P3** | 拖拽排序(网格+列表,松手落地)、图钉入口、排序规则编辑器 | 3, 4, 5, 6, 7 |
| **P4** | 命名结构编辑器、建议表、逐项覆盖、冲突与警告(§6/§7) | 8, 9, 10 |
| **P5** | 元数据编辑器、ComicInfo.xml 预览、页码预览(§8) | 11, 12, 13, 14 |
| **P6** | 执行计划预览、危险动作二次确认、报告 + 撤销入口(§9) | 15, 16, 17 |
| **P7** | 真机走查 20 项 + 两条工作流端到端 | 18, 19, 20 |

**切片原则**:各阶段的领域逻辑**随该阶段一起进**(而非全堆在 P1),使每阶段是"领域+UI+测试"的完整闭环,
可独立评审、独立回滚。P1 只放被后续阶段共用的地基。

### 10.3 UI 约定

- **Material Design**:全部遵循 §2 约束 13(HANGOFF §6.5)。颜色/排版/形状一律取 material3 语义角色,
  不硬编码色值与字号;支持深色模式;Android 12+ 动态取色。
- **触摸目标 ≥ 48dp**;正文 ≥ 14sp(归档的"UI 字号下限"设计意图)。
- Android 系统字体天然支持中文,**无需自定义 CJK 字体**(归档的"中文发虚"是桌面 Flutter 问题,不适用)。
- **组件**:按钮/卡片/输入/开关/对话框/底部操作一律取自 `androidx.compose.material3`;
  纯图标按钮必须给 `contentDescription`(可访问性 + 可测性)。
- **信息密度**(换栈直接动因,§8.2 证伪条件 ③):在 M3 规范内用间距与网格列数控制密度,
  **不得复现**"元素过大、一屏看不了多少"。
- **拖动中不重排,松手才落地**(§12.1 契约);拖动中仅高亮目标格;拖到自己格不高亮。
- 网格卡片必须提供**可点图钉入口**(不能只有状态角标——归档走查反馈 ④ 的静默缺口,对应验收第 6 项)。

---

## 11. 状态管理约定

**领域层**:不可变 `data class` + 纯函数(§5.3、§6.1)。**不含任何 Compose 类型**。

**UI 层**:状态持有器持有 `mutableStateOf<WorkflowXState>`,用户操作 → 调用 `domain` 纯函数 → 赋值新实例:

```kotlin
class WorkflowAStateHolder(initial: WorkflowAState) {
    var state by mutableStateOf(initial)
        private set
    fun moveItem(collectionId: String, id: String, target: Int) {
        state = state.withOrder(collectionId) { moveItemTo(it, id, target) }
    }
}
```

**为什么这样**:归档原型的根因是 `ChangeNotifier` 子通知未转发 → 数据对、界面不动。
"不可变状态 + `mutableStateOf`"让 Compose 的重组由赋值天然触发,**从设计上消除该缺陷类别**;
且领域层保持纯函数,第 1 层单测无需任何框架。

**禁止**:在 `app/` 里用普通 `var` 承载需要驱动 UI 的状态(§12.2 的负向对照正是钉死这一点)。

---

## 12. 测试策略(修复自证三层)

### 12.1 第 1 层:领域单测(`domain/src/test`,纯 JVM,秒级)

必须覆盖归档 16 项测试意图中**属逻辑的 7 项**并补强:

| # | 用例 | 钉死语义 |
| --- | --- | --- |
| L1 | 自然排序 `img2 < img10` | 数字段按数值比较 |
| L2 | **自然排序前导零分支** `"1" < "01"` | 归档未覆盖,新栈必补 |
| L3 | **记号切分行为** | Kotlin `Regex.split` 丢弃捕获组的陷阱(§13) |
| L4 | 多级排序:每级独立升降序 + `id` 兜底确定性 | §5.2 |
| L5 | `moveItemTo` 先删后插语义 + 越界夹取 + 自身格 no-op + 人工标记 | §5.3 |
| L6 | `togglePin` → `applyRule` 固定项保位、`manualIds` 清空、`pinnedIds` 保留;解除后回归自动序 | §5.3 |
| L7 | 命名默认结构拼接(首个组件无分隔符 + `_` + 3 位补零 + 小写扩展名) | §6.1 |
| L8 | 覆盖以 id 为键、改结构不丢失;清空 = 撤销覆盖 | §6.3 |
| L9 | 重名跨目录被检出;`CaseCollision` 与 `Duplicate` 互斥 | §7 |
| L10 | 超长阈值 180、非法字符集、`sanitize` 开关与警告互补 | §7 |
| L11 | ComicInfo.xml:`Skip` → 自闭合空标签;`Zh` → `<LanguageISO>zh</LanguageISO>` | §8.5 |
| L12 | 页码重编号 3/4 位自适应、从 001 起、扩展名小写 | §8.6 |
| L13 | 打包计划:默认 `Rename` 且 `outputDisplay` 含 ` (1)` | §9.1 |
| L14 | 批量设置不覆盖 `overridden` 字段并返回生效数量 | §8.2 |
| L15 | `pendingIssues` 顺序与内容 | §4.7 |

### 12.2 第 2 层:UI 层断言(`app/src/test`,Robolectric,离线)

必须覆盖归档 16 项中**属 UI 的 9 项**并补强:

| # | 用例 | 钉死语义 |
| --- | --- | --- |
| U1 | 首页同时渲染两条工作流入口 | 并列结构不可合并 |
| U2 | 网格拖拽(完整手势序列:按下→越过 slop→移到目标→抬起)→ 顺序真的变 | 交互真的生效 |
| U3 | **拖动中顺序不变,松手才落地** | 防抖动契约 |
| U4 | 拖拽后**首格显示内容真的更新** | "数据变了但界面不动"必须被捕获 |
| U5 | 网格卡片**可点图钉** → 空心变实心 | 归档走查反馈 ④ 的静默缺口 |
| U6 | 列表项在无界高度下正常布局(不被压成 0) | 约束透传 |
| U7 | 命名建议表:改结构后**覆盖项输入框不被冲掉** | §6.3 |
| U8 | 计划页:冲突项显示跳过 + reason | §9.1 |
| U9 | 危险动作:默认关闭;触发需**两次**确认 | §9.2 |
| **U0** | **负向对照:用普通 `var` 承载状态 → 断言必须失败** | 证明第 2 层不是空转(§13) |

> U0 是**必须保留**的用例:它故意制造"数据对了但界面不动",断言"点击后界面应更新"并**期望失败**。
> 归档原型在此连修 4 轮,故本层必须自证其有效性(探针已实证该手法可行)。

### 12.3 第 3 层:真机实证(P7)

- 在 Android 真机逐条勾选 HANGOFF §9《D1 验收清单》20 项;
- 手段:埋点日志 → 模拟输入(`adb shell input`)→ 像素/快照对比;
- **当前 `adb devices` 为空**,故该层**挂起**;不得以离线通过冒充 D1 完成。

---

## 13. 与归档原型的偏离(有意为之,逐条说明)

| # | 偏离 | 理由 |
| --- | --- | --- |
| 1 | 可变 `ChangeNotifier` → **不可变状态 + 纯函数** | 从设计上消除"数据对了但界面不动"的缺陷类别(归档根因) |
| 2 | `DateTime` → **epoch millis** | 避免 minSdk 24 的 desugaring,保持零三方依赖;语义等价 |
| 3 | 自然排序 `int.parse` → **去前导零后按长度/字典序比较** | 归档在超长数字串上会抛错;新实现语义等价且防溢出 |
| 4 | `Overwrite` 策略**补独立二次确认** | 归档自承薄弱点;§2 约束 5"破坏性动作可解释"要求 |
| 5 | 页面结构由"流"改为**首页 → 工作流 → 步骤** | 对齐验收第 19 项"两条工作流均可从首页完整走通到结果页" |

**明确不迁移**:Dart 代码、Flutter 手势与布局机制、`AnimatedBuilder` 通知链、CJK 字体回退链。

---

## 14. 风险与未决

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| **无真机**(`adb devices` 为空) | P7 无法完成,D1 不能宣称通过 | 先做可离线验证的 P1–P6;P7 等用户接设备;如实标注"未验证" |
| **拖拽仍是最高风险交互**(上轮连修 4 轮) | 验收 3/4 项 | P3 必须用完整手势序列 + UI 断言,且保留 U0 负向对照 |
| **AGP 9 新 DSL** | 构建失败 | 已实测锁定配置(§2 约束 4);不照抄旧教程 |
| **Kotlin `Regex.split` 陷阱** | 自然排序静默失效 | P1 必修 + L3 单测钉死 |
| Robolectric 首跑较慢(~65s/次) | 反馈循环变慢 | 领域层用第 1 层快速迭代;UI 层按阶段批量跑 |

**未决**(需用户后续确认,不阻塞 P1):HANGOFF §11 第 9/10/13/14 条(拖拽落地方式是否要实时让位、
排序与命名联动强度、中文数字自然排序、命名序号取哪个)。

---

## 15. 验收清单映射(20 项 → 阶段)

| # | 验收点 | 阶段 | 离线可验? |
| --- | --- | --- | --- |
| 1 | 缩略图网格/列表双视图可切换 | P2 | ✅ |
| 2 | 大图预览:自适应/缩放/平移/翻页 | P2 | 部分(手势需真机) |
| 3 | 网格拖拽真实改变顺序 | P3 | ✅ |
| 4 | 列表拖拽真实改变顺序 | P3 | ✅ |
| 5 | 人工调整可见标记 + 一键重置 | P3 | ✅ |
| 6 | 固定位置项重新应用排序后保持原位 | P3 | ✅ |
| 7 | 多级排序可增删改;批次默认与"本组独立"并存 | P3 | ✅ |
| 8 | 命名结构各策略可调 | P4 | ✅ |
| 9 | 命名建议表可逐项覆盖;改结构覆盖不丢失 | P4 | ✅ |
| 10 | 冲突与警告可见(重名/非法字符/大小写/超长) | P4 | ✅ |
| 11 | 逐卷元数据可改,建议值带来源 | P5 | ✅ |
| 12 | 语言默认"未设置"须人工确认;逐卷不同;写入/不写入可选 | P5 | ✅ |
| 13 | 批量设置不覆盖逐项例外 | P5 | ✅ |
| 14 | ComicInfo.xml 与页码列表预览一致 | P5 | ✅ |
| 15 | 执行计划预览:操作清单 + 冲突/跳过原因 | P6 | ✅ |
| 16 | 危险动作默认关闭 + 独立二次确认 | P6 | ✅ |
| 17 | 执行报告 + 撤销入口 | P6 | ✅ |
| 18 | 必须人工输入的字段都有明确入口 | P7 | 需真机逐项核对 |
| 19 | 两条工作流均可从首页走通到结果页 | P7 | 部分 |
| 20 | 工程健康:静态检查零告警;测试全绿(含 UI 层断言) | P7 | ✅ |

> **D1 退出条件**:上表 20 项在 **Android 真机**全部勾满。**当前无真机,故 D1 未完成**;
> P1–P6 的离线验证完成**不等于** D1 通过。
