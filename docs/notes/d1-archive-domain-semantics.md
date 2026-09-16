# D1 归档原型领域语义与测试意图提取

> **来源**：归档分支 `origin/archive-flutter-verify`，tip commit **`9efef5b`**。
> 具体文件路径（全部以 `git show 9efef5b:<路径>` 读取，未 checkout、未改动工作区）：
>
> - `pixfold-d1/lib/domain/models.dart`（415 行，排序 / 命名 / 警告 / 计划）
> - `pixfold-d1/lib/domain/comic.dart`（328 行，元数据 / ComicInfo.xml / 打包计划）
> - `pixfold-d1/lib/domain/naming.dart`（147 行，命名求值与冲突检测）
> - `pixfold-d1/lib/mock/mock_data.dart`（234 行，确定性异常样例）
> - `pixfold-d1/test/widget_test.dart`（471 行，**16 个用例**）
> - 辅助上下文：`pixfold-d1/lib/main.dart`、`lib/widgets/common.dart`、`lib/widgets/reorderable.dart`、
>   `lib/workflow_a/{flow,sort_page,naming_page,plan_page}.dart`、
>   `lib/workflow_b/{flow,library_page,metadata_page,plan_page}.dart`
>
> **性质**：Flutter + Dart 原型已整体作废（技术栈改为 Kotlin + Jetpack Compose），
> 代码不迁移；本文只提取**领域语义与测试意图**，供新栈重建。
> 文中"精确到可重写"的量化数值（补零位数、长度上限、默认值）均取自归档源码字面量。

---

## 0. 总览：三个领域文件的分工

| 文件 | 职责 | 关键类型 |
| --- | --- | --- |
| `models.dart` | 工作流 A（图片整理）的排序与命名领域 | `MockImage`、`ImageCollection`、`SortField`、`SortKey`、`SortRule`、`PageOrder`、`NamingScheme`、`NameComponent`、`NameProposal`、`RenameOp`、`ExecutionReport` |
| `comic.dart` | 工作流 B（CBZ 制作）的元数据与打包领域 | `Suggestion<T>`、`LangChoice`、`ComicVolume`、`MetaField`、`OutputConflictPolicy`、`SourcePolicy`、`VolumePackPlan` |
| `naming.dart` | 命名结构 → 建议名 → 冲突检测的纯函数 | `buildProposals`、`buildRenamePlan` |
| `mock_data.dart` | 确定性 mock 数据（含全部异常样例） | `MockWorkspaceA`、`MockLibraryB` |

**实体规模**：`models.dart` + `comic.dart` + `mock_data.dart` 共声明 **25 个具名类型**
= **16 个类**（`MockImage`、`ImageCollection`、`SortKey`、`SortRule`、`PageOrder`、`NameComponent`、
`NamingScheme`、`ProposalWarning`、`NameProposal`、`RenameOp`、`ExecutionReport`、`Suggestion<T>`、
`ComicVolume`、`VolumePackPlan`、`MockWorkspaceA`、`MockLibraryB`）
+ **9 个枚举**（`SortField`、`NameComponentKind`、`CasePolicy`、`ExtPolicy`、`ProposalWarningType`、
`LangChoice`、`MetaField`、`OutputConflictPolicy`、`SourcePolicy`）。
`naming.dart` 不含类型声明，只有纯函数 `buildProposals` / `buildRenamePlan` 与私有辅助函数。

**贯穿性契约（新栈必须保留）**：扫描/预览不改文件；建议 ≠ 事实（每个建议值带 `source` 来源标注）；
预览是主工作区；批次统一 + 逐项例外；破坏性动作默认关闭且需二次确认。

---

## 1. 领域实体清单（逐个字段）

### 1.1 `MockImage`（= 领域模型中 `SourceItem` 的 mock 快照）

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `id` | `String` | 稳定唯一标识；**同时是排序兜底键**（见 §2.3）。mock 形如 `trip-day1-1`、`aot-2-7` |
| `collectionId` | `String` | 所属集合/卷 id |
| `dir` | `String` | **相对目录**，`""` 表示根 |
| `baseName` | `String` | 不含扩展名的文件名主干 |
| `ext` | `String` | 扩展名，**不含点**（`'jpg'` / `'JPG'` 大小写原样保留） |
| `sizeBytes` | `int` | 字节数 |
| `modified` | `DateTime` | 修改时间 |
| `created` | `DateTime` | 创建时间 |
| `seed` | `int` | 程序化缩略图的**确定性种子**（mock 专用，新栈可换成真实缩略图） |

派生 getter：
- `fileName` = `baseName + '.' + ext`（`dir` 为空时 `relPath` 就等于它）
- `relPath` = `dir` 为空 ? `fileName` : `dir + '/' + fileName`
- `sizeLabel`：`>= 1 MiB` → `(bytes/1024/1024).toStringAsFixed(1) + ' MB'`；
  `>= 1 KiB` → `(bytes/1024).toStringAsFixed(0) + ' KB'`；否则 `'<n> B'`

### 1.2 `ImageCollection`（= `Collection`）

`id: String`、`name: String`、`rootDirName: String`、`images: List<MockImage>`。
`rootDirName` 独立于 `name`，是**命名组件 `rootDir` 的取值来源**。

### 1.3 排序键枚举 `SortField`

**枚举声明顺序 = UI 下拉选项顺序**（2026-09-10 用户定序：自然序为主路径，字典序保留作对照紧随其后，其余按常用度）。

| 枚举值 | `label`（UI 文案） | 比较语义 |
| --- | --- | --- |
| `naturalName` | `文件名(自然)` | `naturalCompare(fileName.toLowerCase())` |
| `fileName` | `文件名(字典)` | `fileName.toLowerCase()` 的字典序（**比较前统一转小写**） |
| `dirName` | `目录名` | `dir` 的原始字典序（**不转小写**） |
| `modified` | `修改时间` | `DateTime.compareTo` |
| `created` | `创建时间` | `DateTime.compareTo` |
| `size` | `文件大小` | `sizeBytes.compareTo` |

### 1.4 `SortKey` / `SortRule`

- `SortKey { SortField field; bool ascending; }` — 两个字段**可变**（UI 直接改草稿对象）。
- `SortRule { final List<SortKey> keys; }` + `copy()` **深拷贝**（每个 `SortKey` 新建）。
- **多级上限 = 4 级**：UI 的"添加排序级"按钮在 `keys.length >= 4` 时禁用（`sort_page.dart`）。
  删除按钮在 `keys.length <= 1` 时禁用 —— **至少保留 1 级**。
- 默认规则 `PageOrder.defaultRule()` =
  `SortRule([SortKey(dirName, true), SortKey(naturalName, true)])`
  —— **先按目录名升序，同目录内按自然序升序**。

### 1.5 `PageOrder`（两层状态，见 §3）

字段：`images`（原始全集）、`rule`、`_order`、`_manualIds: Set<String>`、`_pinnedIds: Set<String>`。
只读视图：`order`（返回 `List.unmodifiable`）。
计数/判定：`hasManual`、`manualCount`、`pinnedCount`、`isManual(id)`、`isPinned(id)`、`indexOf(img)`。
是 `ChangeNotifier`（新栈对应 StateFlow / 可变状态 + 事件）。

### 1.6 命名相关枚举与类

- `NameComponentKind`（声明顺序即 UI"添加组件"菜单顺序）：

  | 值 | `label` | 取值来源 |
  | --- | --- | --- |
  | `prefix` | `自定义前缀` | `comp.text` |
  | `rootDir` | `根目录名` | `scheme.rootDirName` |
  | `relDir` | `相对目录片段` | `img.dir` |
  | `dirIndex` | `目录序号` | 集合内去重目录排序后 1-based 编号，`padLeft(indexPadding,'0')` |
  | `imageIndex` | `图片序号` | `indexStart + position`（position = 在**当前页序**中的下标，0-based），`padLeft(indexPadding,'0')` |
  | `origName` | `原文件名` | `img.baseName` |
  | `customText` | `自定义文本` | `comp.text` |

- `CasePolicy`：`keep('保持原样')` / `lower('转小写')` / `upper('转大写')` —— **每个组件各自持有**。
- `ExtPolicy`：`keep('保留原样')` / `lower('统一小写')` / `upper('统一大写')` / `strip('去除扩展名')`。
- `NameComponent` 字段与默认值：
  `kind`（必填）、`enabled = true`、`separatorBefore = '_'`、`casePolicy = CasePolicy.keep`、
  `trimSpaces = true`、`text = ''`。
- `NamingScheme` 字段与默认值：
  `rootDirName`（必填）、`components`（默认 3 个，见下）、`indexStart = 1`、`indexPadding = 3`、
  `extPolicy = ExtPolicy.lower`、`sanitize = true`、`replacement = '_'`。
  默认组件序列：
  `[ rootDir(sep='_'), imageIndex(sep='_'), origName(enabled: false, sep='_') ]`
  —— 即默认输出 `<根目录名>_<三位序号>.<小写扩展名>`。
- `NameProposal`：`image`、`proposedName`（自动建议）、`overrideName: String?`、`warnings: List<ProposalWarning>`；
  `finalName = overrideName ?? proposedName`；`isOverridden = overrideName != null`；
  `hasConflict = warnings 含 duplicate 或 illegalChar`。
- `ProposalWarningType`：`duplicate` / `illegalChar` / `caseCollision` / `tooLong` / `overridden`。
- `ProposalWarning(type, message)` —— 带**人类可读 message**（UI 直接显示）。
- `RenameOp { proposal; status: 'ok'|'skipped'; reason: String?; }`，`skipped = status == 'skipped'`。
- `ExecutionReport { time; okCount; skipCount; failCount; messages: List<String>; }`。

### 1.7 元数据相关（`comic.dart`）

- `Suggestion<T> { T? value; String source; }`，`hasValue` = 非 null 且（非字符串 或 非空串）。
  **每个建议值必须带来源字符串**，UI 以青色 `TagChip('建议：…')` + `来源：…` 成对显示。
- `LangChoice`（声明顺序即下拉顺序）：
  `unset('未设置（需确认）')` / `zh('zh · 中文')` / `ja('ja · 日文')` /
  `other('其他 ISO 639-1')` / `unknown('未知')` / `skip('不写入标签')`。
- `MetaField`：`title` / `series` / `writer` / `volume` / `language` / `cbzName` / `outputDir`
  —— **可人工覆盖的字段全集**。
- `ComicVolume`（`ChangeNotifier`）字段：
  - 不可变：`id`、`dirPath`、`pages: List<MockImage>`、
    `titleSug`、`seriesSug`、`writerSug`、`writerCleanedSug`、`volumeSug: Suggestion<int?>`、
    `langSug: Suggestion<String>`、`outputDirSug: String`、`outputExists: bool`（mock：输出目录已存在同名 CBZ）。
  - 可变当前值：`title`、`series`、`writer`（构造时取建议值 `?? ''`）、`volume: int?`（取建议值）、
    `language: LangChoice = unset`、`otherLangCode: String = ''`、`cbzFileName`（`_defaultCbzName()`）、
    `outputDir`（= `outputDirSug`）。
  - `overridden: Set<MetaField>`（逐项例外集合）、`pageOrder: PageOrder`（构造时用 `pages` 新建）、
    `included: bool = true`。
  - `seriesKey => series`（分组键随用户改 `series` 而变，UI 按当前值重新分组）。
- `OutputConflictPolicy`：`rename('自动重命名（加序号）')` / `skip('跳过该卷')` / `overwrite('覆盖已有文件')`。
  **默认 `rename`**（`WorkflowBController.conflictPolicy` 初值）。
- `SourcePolicy`：`keep('保留源目录（默认）')` / `delete('打包后删除源目录（危险）')`。**默认 `keep`**。
- `VolumePackPlan { volume; renumbered: List<MapEntry<MockImage,String>>; conflict: bool; conflictPolicy; sourcePolicy; }`
  —— `renumbered` 是**计划生成时快照**，不是惰性求值。

### 1.8 mock 数据（`mock_data.dart`）——异常样例是语义的一部分

`MockWorkspaceA { rootPath: r'D:\照片整理_2026'; collections }`，三个集合：

| 集合 id | name / rootDirName | 图片构成 | 覆盖的异常 |
| --- | --- | --- | --- |
| `trip` | 旅行照片 | `day1/IMG_20260701_001..018`（18 张）、`day2/IMG_20260702_001..012`（12 张） | 正常样例、多目录分组 |
| `scan` | 扫描件 | `ch01/page_01..06`、`ch02/page_01..06`（**跨目录同名 baseName**）、`ch02/封:面?.png`（非法字符 `:` `?`）、`ch02/Page1.JPG`（大小写冲突对） | 重名、非法字符、大小写冲突 |
| `misc` | 杂图 | 根目录 `img1..img12` | 自然序 vs 字典序（字典序 1,10,11,12,2…；自然序正确） |

`_numLabel` 规则：`IMG_` 前缀 → `_%03d`；`page` → `_%02d`；其他 → 裸数字。
`MockLibraryB { rootPath: r'E:\漫画库'; volumes }`，5 卷：

| id | dirPath | 页数 | 卷号建议（来源） | 语言建议（来源） | outputExists |
| --- | --- | --- | --- | --- | --- |
| `aot-1` | `…\进击的巨人\第01卷` | 16 | `1`（目录名「第01卷」解析） | `ja`（卷内文件名含日文片段，**低置信**） | false |
| `aot-2` | `…\进击的巨人\第02卷` | 18 | `2`（同上） | `ja`（同上） | false |
| `aot-0` | `…\进击的巨人\外传` | 12 | `null`（目录名「外传」未解析出卷号） | `ja`（系列内其他卷为 ja，**推断**） | false |
| `op-101` | `…\海贼王\第101卷` | 20 | `101` | `null`（**无语言线索**） | false |
| `op-102` | `…\海贼王\第102卷` | 20 | `102` | `zh`（卷内文件名以中文命名，**中置信**） | **true** |

所有卷共用：`titleSug.source = '目录层级 L2/L3'`、`seriesSug.source = '目录层级 L2'`、
`writerSug = '[作者]谏山创(原作:某人)(电子版)'`（source `'目录层级 L2 段命名'`）、
`writerCleanedSug`（source `'清理规则：去作者前缀 / 括号原作 / 尾部标签'`）、
`outputDirSug = r'E:\漫画库\_output'`。
页名形如 `<卷目录名>_p001.jpg`（`padLeft(3,'0')`）。

**来源标注的措辞分级（可复用的语义）**：`低置信` / `中置信` / `（推断）` / `无语言线索` /
`（系列内其他卷为 ja）` —— 置信度是**来源字符串的一部分**，不是单独字段。

---

## 2. 排序逻辑

### 2.1 多级比较（`sortImages`）

```
list = images.toList()
list.sort((a, b) {
  for (key in rule.keys) {
    c = _compareField(a, b, key.field)
    if (c != 0) return key.ascending ? c : -c     // 每级独立升降序
  }
  return a.id.compareTo(b.id)                      // 稳定兜底
})
```

- **每级独立升/降序**（`SortKey.ascending`），不是全局一个方向。
- 第一级不同即返回，**后续级不参与**。
- 全部级相等时按 `id` 字典序兜底 —— 因此结果**确定**（不依赖排序算法是否稳定）。
- Dart `List.sort` 本身**不保证稳定**，所以 `id` 兜底是**必需**的，不是可选优化。
  → 新栈 Kotlin 的 `sortedWith` 是稳定排序，但**仍应保留 id 兜底**以保持与旧行为逐位一致。
- 排序是**纯函数**：`sortImages(images, rule)` 不改入参，返回新列表。

### 2.2 自然排序（`naturalCompare`）——精确算法

按字符逐位扫描，遇到"两边都是数字"时进入数字段比较：

```
i = j = 0
while i < a.length && j < b.length:
    da = isDigit(a[i]); db = isDigit(b[j])
    if da && db:
        ia = i;  while ia < len && isDigit(a[ia]): ia++
        ib = j;  while ib < len && isDigit(b[ib]): ib++
        na = int.parse(a[i..ia]); nb = int.parse(b[j..ib])
        if na != nb: return na.compareTo(nb)          # 数值比较 → "2" < "10"
        if (ia-i) != (ib-j): return (ia-i).compareTo(ib-j)  # 数值相等 → 位数少的在前
        i = ia; j = ib
    else:
        c = a[i].codeUnitAt.compareTo(b[j].codeUnitAt)      # 单字符码点比较
        if c != 0: return c
        i++; j++
return (a.length - i).compareTo(b.length - j)              # 短者在前
```

关键语义点：
- **数字段是"贪心最长数字串"**，`int.parse` 无长度上限（超长数字串会溢出/抛错，原型未防护）。
- **前导零的处理**：数值相等时比**原始位数**（位数少者在前）→ `naturalCompare("01", "1")` 返回**正数**，
  即 `"1" < "01"`。这与"补零后自然序"的直觉**相反**（补零反而排到后面），是新栈最容易搞错的点之一。
  实际影响有限：mock 数据里数字段的**数值**都互不相同（`IMG_…_001`、`page_01`、`img1..img12`），
  数值相等、仅前导零不同的情形没有出现，所以该分支在归档测试中未被覆盖 —— 新栈若沿用此算法需自补用例。
- **数字段 vs 非数字段的分支**：仅当**两边当前位置都是数字**时才走数字比较；否则（一边数字一边非数字，
  或两边都非数字）走**单字符码点比较**。所以 `a1` vs `ab` 在第 2 位是 `'1'`(0x31) vs `'b'`(0x62) → `a1` 在前。
- 非数字部分是**逐字符**推进（不是"逐段"对齐比较），但逐字符比较的累积效果与字典序在非数字区段上等价。
- 数字判定是**纯 ASCII** `0x30..0x39`，不认全角数字、不认 Unicode `Nd`。
- 调用方**先 `toLowerCase()`** 再比较（`naturalName` 分支），所以自然序是**大小写不敏感**的。
- 不处理 `-`、`_`、空格等分隔符的特殊权重 —— 它们就是普通字符，参与码点比较。

### 2.3 字典序 vs 自然序的差别实现

| | `fileName`（字典序） | `naturalName`（自然序） |
| --- | --- | --- |
| 预处理 | `fileName.toLowerCase()` | `fileName.toLowerCase()` |
| 比较 | `String.compareTo`（UTF-16 码元序） | `naturalCompare` |
| `img2` vs `img10` | `img10` 在前（'1'<'2'） | `img2` 在前（2 < 10） |

`dirName` 字段**不转小写**（与 `fileName` 不一致），保留原始码点序 —— 这是有意的：目录名来自文件系统，大小写敏感场景保留原样。

---

## 3. 人工调整与自动排序的两层状态

### 3.1 状态模型

| 层 | 载体 | 生命周期 |
| --- | --- | --- |
| 自动层 | `SortRule rule` + `List<MockImage> _order`（初始由 `sortImages` 算出） | 应用规则时重算 |
| 人工层 | `Set<String> _manualIds`（拖拽过的 id） | **`applyRule` 时清空** |
| 固定层 | `Set<String> _pinnedIds`（图钉 id） | **`applyRule` 时保留**（跨自动/人工层） |

- `_manualIds` 只用于**标记/提示**（UI 蓝色徽标 + `hasManual` + `manualCount`），**不参与重排计算**。
  实际顺序就是 `_order` 本身（人工调整直接改了它）。
- `_pinnedIds` 才是真正影响重排结果的集合。
- `resetToAuto()` = `applyRule(rule)`（用当前规则重算）—— 即"放弃人工调整"，
  但**固定项位置保留**（因为走的是同一条 `applyRule` 路径）。UI 按钮文案
  `重置人工调整（N 处）`，`N = manualCount`，无人工调整时禁用。

### 3.2 `applyRule` 的固定项保持算法（精确步骤）

```
1. rule = newRule.copy()                      // 深拷贝，防 UI 草稿被后续修改污染
2. pinned: Map<int, MockImage> ← 遍历 _order，凡 id ∈ _pinnedIds 的，记下其当前下标 i
3. sorted = sortImages(images, rule)          // 对全集按新规则排序
4. rest = sorted 中剔除所有 _pinnedIds 的项（保持 sorted 的相对次序）
5. result = List<MockImage?>(images.length)   // 全 null
6. 按 pinned 的下标升序遍历（entries.sort by key）：
     idx = min(记录下标, result.length-1)
     while idx >= 0 && result[idx] != null: idx--      // 从记录位向左找第一个空位
     if idx < 0: continue                              // 找不到空位则丢弃该固定项（极端兜底）
     result[idx] = 该项
7. ri = 0；从左到右扫描 result，每个 null 位填入 rest[ri++]
8. _order = result 中非 null 项（按序）
9. _manualIds.clear()                          // 人工标记清空
10. notifyListeners()
```

要点（新栈必须复刻）：
- 固定项**优先落位**，且尽量落在"记录时的下标"；被别的固定项占了才**向左**顺延。
- **落位顺序 = 记录下标升序**（不是 `_pinnedIds` 的集合序）→ 结果确定。
- 其余项**按新规则的顺序**填满所有空位 —— 固定项"挖洞"，自动排序结果"填洞"。
- `_pinnedIds` 本身**不清空**（解除固定只能靠再点一次 `togglePin`）。
- `_manualIds` **清空** —— 这就是"应用自动规则后人工标记应清空、固定标记保留"的测试意图来源。

### 3.3 `moveItemTo(id, targetIndex)` 精确语义

```
from = _order.indexWhere(id)
if from < 0: return                          // 不存在的 id：静默 no-op
targetIndex = clamp(targetIndex, 0, _order.length - 1)   // 越界夹取，不是拒绝
if from == targetIndex: return               // 拖到自己格：不动，且不记人工标记
img = _order.removeAt(from)
_order.insert(targetIndex, img)              // 先删后插 —— targetIndex 是"移除源项后的插入位"
_manualIds.add(id)
notifyListeners()
```

**`targetIndex` 的确切语义 = 目标格子下标（0..length-1），在移除源项之后插入到该位。**
即：`[...ids].removeAt(from).insert(target, id)`。

边界行为：

| 情形 | 行为 |
| --- | --- |
| 前移（`from > target`） | 目标及之后元素**右移**一位 |
| 后移（`from < target`） | 目标及之前元素**左移**一位（因为源已先被移除） |
| 拖到末尾 | `target = length-1`；`removeAt` 后列表长 `length-1`，`insert(length-1, img)` 合法 → 落到最后 |
| 拖到首位 | `target = 0` |
| 越界负数 | 夹到 `0` |
| 越界超长 | 夹到 `length-1` |
| 拖到自己格 | `from == target` → 直接 return，**顺序不变且不产生人工标记** |
| 不存在的 id | 直接 return |

**关键陷阱**：因为"先删后插"，当 `from < target` 时，元素最终落在下标 `target`（而不是 `target+1`）。
测试 `moveItemTo：拖拽重排语义` 用 `removeAt(0)` 再 `insert(5, ids[0])` 钉死了这一点。

### 3.4 UI 侧的两层状态契约（拖拽交互）

- **拖动过程中不重排**，`onAccept`（松手落地）才调用 `moveItemTo`。
  原因见 `reorderable.dart` 注释：悬停实时重排会因指针微动反复换位，看起来"拖了没变"（2026-09-10 实测教训）。
- 拖动中只**高亮目标格**（半透明主色 + 2.5px 边框），且高亮条件含 `candidates.first != itemId`（拖到自己不高亮）。
- 拖到自己格：`DragReorderItem._drop` 里 `if (data == itemId) return;`（双保险，与 `moveItemTo` 的 `from == target` 呼应）。
- 拖拽启动方式按平台分流：**触摸平台（android/iOS）用 `LongPressDraggable`，其余用 `Draggable`**（桌面端立即拖拽，靠 slop 阈值区分点按与拖动）。
- `childWhenDragging` 用 `Opacity(0.35)`；浮层尺寸由外层 `LayoutBuilder` 传入（`Size(maxWidth-6, maxHeight-6)`），默认回退 `150×190`。
- 列表/网格通用：`Stack(fit: StackFit.passthrough)` 透传约束，否则列表项会被压成 0 高度。

### 3.5 批次规则 vs 集合独立规则（工作流 A）

`WorkflowAController` 另有一层"批次统一 + 逐项例外"：
- `batchRule: SortRule`（批次默认）+ `customRuleCollections: Set<String>`（使用独立规则的集合）。
- `effectiveRule(id)` = 集合在 `customRuleCollections` 中 ? `orders[id].rule` : `batchRule`。
- `applyRule(collectionId, rule)`：
  - 若该集合是"本组独立" → 只重排该集合；
  - 否则 → 更新 `batchRule`，并**对所有非独立集合**重排。
- `toggleCustomRule(id)`：加入时变成独立（保留当前顺序）；**移除时立刻用 `batchRule` 重排该集合**。
- 侧栏 UI 有"编辑中的规则草稿"（`_draftRules`），点"应用排序"才落地 —— **编辑不实时生效**。
- 命名结构与覆盖是**批次级**（不属于某个集合）；`overrides` 是 `Map<imageId, name>`，全局一份。

---

## 4. 命名求值

### 4.1 求值入口 `buildProposals`

```
buildProposals({ required List<MockImage> order, required NamingScheme scheme,
                 Map<String,int>? dirIndexMap, Map<String,String>? overrides })
```

- 输入 `order` 是**当前页序（含人工调整）** —— `imageIndex` 组件取的是页序下标，不是原始扫描顺序。
- **目录序号表**：`dirs = order.map(dir).toSet().toList()..sort()`（字典序），
  默认编号 `{dirs[i]: i+1}`（1-based）；可被 `dirIndexMap` 覆盖。
- 逐项生成（`for i in 0..order.length`）：
  1. 遍历 `scheme.components`，跳过 `!enabled`；
  2. 求 `_componentText(comp, img, i, dim, scheme)`；
  3. **`part.isEmpty` 的组件整体跳过**（连分隔符一起丢）—— 空目录名、空文本、空前缀都不会留下多余分隔符；
  4. 记 `parts` 与 `seps`（各自的前置分隔符）。
- **拼接规则**：`name = parts[0]`，然后 `name += seps[k] + parts[k]`（k 从 1 起）。
  即**首个组件不输出分隔符**，其余各自带自己的 `separatorBefore`。
- **扩展名策略**（在拼接之后追加）：
  - `keep` → `+ '.' + img.ext`（原样）
  - `lower` → `+ '.' + ext.toLowerCase()`
  - `upper` → `+ '.' + ext.toUpperCase()`
  - `strip` → 不追加任何扩展名
- **清洗**：`if (scheme.sanitize) name = sanitizeName(name, scheme.replacement)`
  —— 清洗作用于**完整文件名（含扩展名）**，不只是主干。
- 逐项覆盖：`p.overrideName = overrides?[image.id]`（**按 imageId 存**，见 §4.4）。
- 最后统一 `_attachWarnings(proposals, sanitize: scheme.sanitize)`。

### 4.2 组件取值 `_componentText`

| kind | 取值 | 补零 |
| --- | --- | --- |
| `prefix` / `customText` | `comp.text` | 无 |
| `rootDir` | `scheme.rootDirName` | 无 |
| `relDir` | `img.dir` | 无 |
| `dirIndex` | `dirIndexMap[img.dir] ?? 1` | `padLeft(scheme.indexPadding, '0')` |
| `imageIndex` | `scheme.indexStart + position` | `padLeft(scheme.indexPadding, '0')` |
| `origName` | `img.baseName` | 无 |

之后依次应用：
1. `casePolicy`：`keep` 不动 / `lower` / `upper`（**逐组件**，作用于该组件文本）
2. `trimSpaces == true` → `t.trim()`（去首尾空白，**不是**内部空白）

**注意顺序**：先大小写、后 trim（对英文结果无差，但对含全角空白的文本有差）。

### 4.3 序号位数与冲突

- `indexStart` 默认 `1`（UI 输入框 `min = 1`，非法输入忽略不落地）。
- `indexPadding` 默认 `3`（UI `min = 1`）。
- `imageIndex` 是 **`indexStart + position`**，position 是 **0-based 页序下标**。
  → 所以第 1 张 = `indexStart + 0`；`indexStart=1, padding=3` → `001`。
- **序号本身不做去重/冲突处理**：如果两个组件组合导致序号不唯一，交给 §5 的重名检测兜底。
- `dirIndex` 的"集合内去重目录排序"是**字典序排序后编号**，不是首次出现顺序 —— 换序不会改变目录编号。

### 4.4 逐项覆盖（override）的存储与"改结构不丢失"

- 存储：`WorkflowAController.overrides: Map<String, String>`，键 = `MockImage.id`，值 = 用户输入的完整文件名。
- 写入：`setOverride(imageId, name)`；`name == null || name.isEmpty` → `remove`（**清空输入框 = 撤销覆盖**，不是"改成空名字"）。
- 读取：`buildProposals(overrides: overrides)` 里 `p.overrideName = overrides?[img.id]`，
  `finalName = overrideName ?? proposedName`。
- **"改结构后覆盖不丢失"的机制**：覆盖**不存储在 `NamingScheme` 里**，而是独立于命名结构的
  `imageId → name` 映射。改组件/分隔符/补零只影响 `proposedName`；
  `finalName` 优先取 `overrideName`，所以覆盖项**逐字不变**。
- UI 同步：`_proposalRow` 里 `if (!p.isOverridden && ctrl.text != p.finalName) ctrl.text = p.finalName;`
  —— **只回写未被覆盖的输入框**；被覆盖的输入框保留用户文本，不被结构变化冲掉。
- 恢复建议值：`suffixIcon` 的 `restart_alt` 按钮 → `ctrl.text = p.proposedName; c.setOverride(id, null)`。
- 任何 scheme 改动 / override 改动都调 `invalidatePlan()`（`plan = null`），强制下次重新生成计划。
- **排序变化也会影响建议名**（`imageIndex` 取页序下标），但**不影响覆盖项** —— 这是两层独立性的关键。

### 4.5 计划生成

`buildRenamePlan(proposals)`：
- `p.hasConflict`（含 `duplicate` 或 `illegalChar`）→ `RenameOp(status: 'skipped', reason: <该警告的 message>)`
  —— reason 取**第一个** duplicate/illegalChar 警告的 message（`firstWhere`）。
- 否则 `status: 'ok'`。
- **注意**：`caseCollision` 与 `tooLong` **不会**导致跳过，只是警告（见 §5）。
- 工作流 A 的 `buildPlan()` 遍历**所有集合**，拼接成整库计划。

---

## 5. 冲突与警告检测

`_attachWarnings(proposals, sanitize)` 一次遍历，检测 5 类：

| 类型 | 判定规则 | 阈值 / 条件 | 是否导致跳过 |
| --- | --- | --- | --- |
| `duplicate` 重名 | `finalName` **完全相同**（大小写敏感）的项数 `> 1` | `byName[finalName].length > 1` | **是** |
| `illegalChar` 非法字符 | `!sanitize && containsIllegalChar(finalName)` | 见下 | **是** |
| `caseCollision` 大小写冲突 | `finalName.toLowerCase()` 相同但**原始名不全同** | `names.length < group.length` 时跳过（已按重名报过） | 否（仅警告） |
| `tooLong` 超长 | `finalName.length > 180` | **180 字符**（Dart `String.length` = UTF-16 码元数，中文按 1 计） | 否（仅警告） |
| `overridden` 人工覆盖 | `p.isOverridden` | — | 否（信息性） |

### 5.1 非法字符集合

```dart
const String kIllegalNameChars = r'\/:*?"<>|';   // 共 9 个
```

- 判定 `containsIllegalChar(name)`：任一字符 ∈ 该集合，**或** 码点 `< 0x20`（控制字符）。
- 清洗 `sanitizeName(name, replacement)`：把上述 9 个字符 + 正则 `[\x00-\x1f]` 全部替换为 `replacement`（默认 `'_'`）。
- **重要**：`illegalChar` 警告只在 **`sanitize == false`** 时产生 —— 开了自动清洗就不该有非法字符，
  但**清洗后的名字仍可能撞上 `duplicate`**，所以两者是互补而非重复。
- 注释明确："Windows 文件名非法字符（Android/Linux 同样按此保守处理）" —— **新栈（Android）沿用同一保守集合**。

### 5.2 大小写冲突的精确语义

```
byLower = groupBy(finalName.toLowerCase())
for group in byLower.values:
    if group.length < 2: continue
    names = set(group.map(finalName))
    if names.length < group.length: continue    // 组内存在完全同名 → 已报 duplicate，不重复报
    for p in group: p.warnings.add(caseCollision)
```

- 触发条件是"**lower 后相同、原始名互不相同**"（如 `Page1.JPG` vs `page1.jpg`）。
- 若组内既有完全重名又有大小写变体 → 只报 `duplicate`，**不报** `caseCollision`。
- 语义依据：Windows/Android 常见文件系统同目录不区分大小写，这类名字会互相覆盖。

### 5.3 元数据侧的"待确认项"（另一类警告）

`ComicVolume.pendingIssues`（`List<String>`，按固定顺序）：
1. `language == LangChoice.unset` → `'语言未确认'`
2. `volume == null` → `'卷号缺失'`

UI 用橙色 `TagChip` 在卷列表、卷头、打包计划卡三处重复展示；`LibraryPage._seriesHeader` 还会聚合出
`'N 卷待确认'`。打包执行时，语言未确认的卷会额外产生一条
`'⚠️ <dirPath>：语言未确认（已按"不写入"处理）'` 报告消息。

### 5.4 输出冲突（工作流 B）

- `ComicVolume.outputExists`（mock 布尔）→ `VolumePackPlan.conflict`。
- 冲突策略默认 `rename`：`outputDisplay` 把最后一个 `.` 前插入 ` (1)`：
  `name.substring(0, dot) + ' (1)' + name.substring(dot)`。
- `skip` → `plan.skipped == true`，`describe()` 只返回一条
  `'跳过：输出已存在 <outputDir>/<cbzFileName>'`。
- `overwrite` → 不跳过、不加序号（危险，但原型未对它做独立二次确认，只靠策略选择本身）。

---

## 6. 元数据与 ComicInfo.xml

### 6.1 字段清单与默认值

| UI 标签 | `MetaField` | 类型 | 初值 | 建议来源字段 | 人工覆盖后的恢复 |
| --- | --- | --- | --- | --- | --- |
| `Title` | `title` | `String` | `titleSug.value ?? ''` | `titleSug` | `useSuggestion(title)` |
| `Series` | `series` | `String` | `seriesSug.value ?? ''` | `seriesSug` | `useSuggestion(series)` |
| `Writer` | `writer` | `String` | `writerSug.value ?? ''` | `writerSug`（+ `writerCleanedSug` 一键清理） | `useSuggestion(writer)` |
| `Number` | `volume` | `int?` | `volumeSug.value` | `volumeSug` | `useSuggestion(volume)` |
| `LanguageISO` | `language` | `LangChoice` | **`LangChoice.unset`（未设置）** | `langSug` | `useSuggestion(language)` → 回 `unset` + 清空 `otherLangCode` |
| `CBZ 文件名` | `cbzName` | `String` | `_defaultCbzName()` | 由 `titleSug` + `volumeSug` 派生 | `useSuggestion(cbzName)` |
| `输出目录` | `outputDir` | `String` | `outputDirSug` | `outputDirSug`（非 `Suggestion`，纯 String） | `useSuggestion(outputDir)` |

**`language` 默认 `unset`（"未设置（需确认）"）是硬语义**：即使 `langSug` 有值（如 `ja`），
`ComicVolume` 构造时 `language` 仍是 `unset`，**必须人工确认**。UI 在 `unset` 时显示
`errorText: '语言默认未设置，需人工确认'`。`LangChoice.unset` 与 `unknown`、`skip` 一样**不写入** `LanguageISO`。

### 6.2 `_defaultCbzName()` 规则

```
t = (titleSug.value ?? id).replaceAll(RegExp(r'[\\/:*?"<>|]'), '_')   // 9 个非法字符 → _
v = volumeSug.value
return v == null ? '<t>.cbz' : '<t> 第<v 两位补零>卷.cbz'
```

例：`进击的巨人 第01卷.cbz`。注意用的是 **`titleSug` 而非用户改后的 `title`** ——
即默认名只在构造时算一次，用户改 `title` **不会**自动重算 `cbzFileName`（需手动改或点恢复建议值）。

### 6.3 `langIso(choice, otherCode)` 映射

| choice | 返回 |
| --- | --- |
| `zh` | `'zh'` |
| `ja` | `'ja'` |
| `other` | `otherCode` 为空/null → `null`；否则返回 `otherCode`（**不校验 ISO 639-1 合法性**） |
| `unset` / `unknown` / `skip` | `null`（= 不写入标签） |

### 6.4 `buildComicInfoXml` 生成结构（逐行）

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

规则：
- 字段顺序固定：`Title` → `Series` → `Number` → `Writer` → `LanguageISO`。
- **空值/不写入时输出自闭合空标签**：`<Title />`、`<LanguageISO />`（**不是省略该标签**）。
  判定：`value == null || value.isEmpty`。
- XML 转义 `_xmlEscape` 只处理 4 个：`&` → `&amp;`、`<` → `&lt;`、`>` → `&gt;`、`"` → `&quot;`
  （**不转义 `'`**，因为属性值未使用）。
- 缩进为 **2 空格**，每行以 `\n` 结尾（`writeln`）。
- `Number` = `vol.volume?.toString()`（null → 空标签）。
- 注释说明：字段是"与 `scripts/batch_pack_cbz.py` 行为对齐的字段子集" —— **新栈如需扩展字段，须回头对齐脚本**。

### 6.5 页码重编号（`renumberPages`）

```
order = vol.pageOrder.order                  // 含人工调整的最终页序
padding = order.length >= 100 ? 4 : 3        // 阈值：>= 100 页用 4 位，否则 3 位
name_i = (i+1).toString().padLeft(padding, '0') + '.' + order[i].ext.toLowerCase()
```

- **序号从 1 起**（`i+1`），**补零位数按总页数自适应**（3 位 / 4 位），**扩展名强制小写**。
- 与命名结构（工作流 A）的 `indexPadding` **完全独立** —— 打包重编号不读 `NamingScheme`。
- 返回 `List<MapEntry<MockImage, String>>`，保留原图对象引用（供 UI 标"人工调整"蓝字）。
- 例：`aot-2`（18 页）首项 = `'001.jpg'`。

### 6.6 打包计划 `VolumePackPlan.describe()`（操作清单文案契约）

按顺序产出：
1. （若 skipped）`跳过：输出已存在 <outputDir>/<cbzFileName>` 并 **return**（后续不产出）
2. `创建 CBZ → <outputDisplay>`
3. `写入 ComicInfo.xml（Title=…，Series=…，Number=<volume ?? "未设置">，Writer=<空 则 "空">，LanguageISO=<langIso ?? "不写入">）`
4. `按页序打包 <N> 张并重命名为页码（0001 起）`
5. （若 `sourcePolicy == delete`）`打包后删除源目录：<dirPath>`

`buildPackagePlans` **只处理 `included == true` 的卷**，默认 `conflictPolicy = rename`、`sourcePolicy = keep`。

---

## 7. 危险动作默认值

| 动作 | 默认 | 二次确认机制 |
| --- | --- | --- |
| 删除源目录（工作流 B `SourcePolicy.delete`） | **`keep`（保留源目录）** | 选 `delete` 时卡片立刻显示红色警告文案；点"确认打包"先弹通用确认框（列出"源目录：删除（危险）"），通过后**再弹独立确认框**：标题 `⚠️ 危险操作独立确认`，正文 `你选择了「打包后删除源目录」。删除不可恢复，确认继续？`，按钮 `取消` / `确认删除源目录`。**两次都通过才执行** |
| 清理空目录（工作流 A `cleanEmptyDirs`） | **`false`（默认关闭）** | 勾选框副标题即写明"默认关闭；勾选后仍需在确认对话框中再次确认"；确认框正文追加 `⚠️ 勾选了「清理空目录」…`；随后同样的独立二次确认框（`你勾选了「移动后清理空目录」。删除不可恢复，确认继续？`） |
| 覆盖已有 CBZ（`OutputConflictPolicy.overwrite`） | 非默认（默认 `rename`） | **仅靠策略选择本身**，无独立二次确认（原型的薄弱点，新栈可补） |
| 重命名本身 | 可执行 | 通用确认框：标题 `确认执行？`，正文列明"仅重命名 / 源文件保留，不删除任何文件 / 冲突项将跳过并列出原因"，按钮 `再看看` / `执行` |
| 冲突项 | **默认跳过** | `buildRenamePlan` 对 `duplicate`/`illegalChar` 直接 `status='skipped'` 并带 `reason`；计划页用红色图标 + 红色文件名 + reason 文本展示 |
| 撤销 | 提供但为 mock | 执行后报告页有"撤销本次执行"，弹提示说明"正式版将基于 `UndoRecord` 记录逆向操作" |

其它默认值：`NamingScheme.sanitize = true`（默认**开启**自动清洗）、`ExtPolicy.lower`（默认统一小写扩展名）、
`OutputConflictPolicy.rename`、`SourcePolicy.keep`、`ComicVolume.included = true`（默认全选）。

**统一原则**：任何删除类动作 —— 默认关闭 + 通用确认 + **独立危险确认**（两次），且确认框文案必须
**列出影响范围**（卷数、目录路径、策略）。

---

## 8. 测试意图清单（`test/widget_test.dart`，**16 项**）

### 8.1 索引表

| # | 行号 | 名称 | 类型 |
| --- | --- | --- | --- |
| 1 | 158 | 首页渲染两条工作流入口 | **UI 层断言** |
| 2 | 167 | 真机同款：ThumbCard + LayoutBuilder + 可滚动网格 | **UI 层断言** |
| 3 | 179 | 真机同款：经 Navigator.push 的第二层路由 | **UI 层断言** |
| 4 | 194 | 真机同款：鼠标拖动（真机桌面实际输入设备） | **UI 层断言** |
| 5 | 210 | 桌面端：网格拖拽改变顺序（松手落地） | **UI 层断言** |
| 6 | 242 | 移动端：长按后网格拖拽改变顺序 | **UI 层断言** |
| 7 | 264 | 列表项在无界高度下正常布局（回归：StackFit 会压成 0 高度） | **UI 层断言**（布局） |
| 8 | 299 | UI 回归：网格拖拽后首格显示内容必须更新 | **UI 层断言**（渲染真的变了） |
| 9 | 338 | `togglePin`：固定项在重新应用排序后保持原位 | **纯逻辑单测** |
| 10 | 366 | UI 回归：网格可点图钉固定位置（此前只有角标无入口） | **UI 层断言** |
| 11 | 390 | 自然排序：img2 < img10 | **纯逻辑单测** |
| 12 | 395 | 命名结构：根目录名 + 图片序号 + 补零 + 小写扩展名 | **纯逻辑单测** |
| 13 | 410 | `moveItemTo`：拖拽重排语义（remove + insert + 人工标记） | **纯逻辑单测** |
| 14 | 434 | 重名冲突被检出 | **纯逻辑单测** |
| 15 | 450 | ComicInfo.xml：语言未写入与 zh 写入 | **纯逻辑单测** |
| 16 | 459 | 页码重编号与打包计划 | **纯逻辑单测** |

统计：**UI 层断言 9 项**（#1–#8、#10，均为 `testWidgets`）／**纯逻辑单测 7 项**
（#9、#11–#16，均为 `test`），合计 **16 项**，与归档 tip 的测试数一致。

### 8.2 逐条钉死的语义

**#1 `首页渲染两条工作流入口`** — 首页必须同时出现文案 `'工作流 A · 图片整理与命名'` 与 `'工作流 B · CBZ 制作'`（各 `findsOneWidget`）。钉死：两条工作流入口是产品级并列结构，不可合并/隐藏。

**#2 `真机同款：ThumbCard + LayoutBuilder + 可滚动网格`** — 在 `TargetPlatform.windows` 下，用**真机同款结构**（真实 `ThumbCard`（含 `InkWell`/`Card`）+ `LayoutBuilder` + 内容超视口的可滚动网格）拖 `ids[0]` 到 `ids[2]` 格，断言 `order.order[2].id == ids[0]`。钉死：**复杂真实结构下拖拽仍必须生效**；简化 harness 会漏掉真机问题（注释明说 2026-09-10 的教训）。

**#3 `真机同款：经 Navigator.push 的第二层路由`** — 同样的网格放在 `Navigator.push` 出来的第二层路由里，拖拽仍生效。钉死：**路由层级不影响拖拽命中**（覆盖层/手势竞技场问题）。

**#4 `真机同款：鼠标拖动（真机桌面实际输入设备）`** — 用 `PointerDeviceKind.mouse` 而非默认 touch，拖拽仍生效。钉死：**鼠标（立即拖拽）与触摸（长按拖拽）两条手势路径都要能用**。

**#5 `桌面端：网格拖拽改变顺序（松手落地）`** — 三段断言：
(a) 指针移到目标格但**未松手**时 `order.order[0].id` 不变 → **拖动过程中不实时重排**（防抖动）；
(b) 松手后 `order.order[2].id == ids[0]` → 落地到 index 2；
(c) `order.isManual(ids[0]) == true` → **人工标记必须被记录**。

**#6 `移动端：长按后网格拖拽改变顺序`** — `TargetPlatform.android` 下，`pump(kLongPressTimeout + 50ms)` 后拖到第 2 格，`order.order[1].id == ids[0]`。钉死：**移动端长按启动拖拽**，且长按等待不能与滚动冲突。

**#7 `列表项在无界高度下正常布局（回归：StackFit 会压成 0 高度）`** — `ListView.builder` 里 `DragReorderItem` 包 `SizedBox(height: 60)`，断言 `tester.getSize(...).height == 60`。钉死：**拖拽包装层必须透传约束**（`StackFit.passthrough`），列表项保留内在高度。

**#8 `UI 回归：网格拖拽后首格显示内容必须更新`** — 在真实 `WorkflowAScreen` 上，取首张 `ThumbCard` 内所有 `Text` 拼成字符串，拖拽后断言 **首格文本与拖拽前不同**。钉死：**"数据变了但界面不动"是必须被测试捕获的缺陷**（根因注释：`PageOrder` 的通知没转发给 page controller，`AnimatedBuilder` 收不到通知）。

**#9 `togglePin：固定项在重新应用排序后保持原位`（纯逻辑）** — 步骤与断言：
1. `moveItemTo(lastId, 0)` → `order[0] == lastId`、`isManual(lastId) == true`；
2. `togglePin(lastId)` → `isPinned == true`、`pinnedCount == 1`；
3. `applyRule(defaultRule())` → **`order[0]` 仍是 `lastId`**（固定项保持原位）、
   **`hasManual == false`**（人工标记清空）、**`isPinned(lastId) == true`**（固定标记保留）；
4. `togglePin(lastId)` 解除 → `isPinned == false`；
5. 再 `applyRule(defaultRule())` → **`order.last == lastId`**（回到规则决定的位置）、`order[0] != lastId`。
   钉死：固定层与人工层的**独立生命周期**，以及"解除固定后立刻回归自动序"。

**#10 `UI 回归：网格可点图钉固定位置（此前只有角标无入口）`（UI）** — 初始 `Icons.push_pin_outlined` `findsWidgets`（每卡都有可点入口）、`Icons.push_pin` `findsNothing`；点第一个空心图钉后 `Icons.push_pin` `findsWidgets`。钉死：**"固定位置"必须有可见可点入口**，不能只有状态角标（这是曾经的缺口）。

**#11 `自然排序：img2 < img10`（纯逻辑）** — `naturalCompare('img2','img10') < 0`；`naturalCompare('img10','img1') > 0`。钉死：数字段按**数值**比较，而非字典序。

**#12 `命名结构：根目录名 + 图片序号 + 补零 + 小写扩展名`（纯逻辑）** — 默认 scheme + `indexStart=1`、`indexPadding=3`、`extPolicy=lower`；断言 `proposals.length == col.images.length`、`proposals.first.finalName` 以 `'旅行照片_001.'` 开头、以 `'.jpg'` 结尾。钉死：**默认组件序列的拼接结果**（首个组件不带分隔符 + `_` 分隔 + 3 位补零 + 小写扩展名）。

**#13 `moveItemTo：拖拽重排语义（remove + insert + 人工标记）`（纯逻辑）** — 断言序列：
- 初始 `hasManual == false`；
- `moveItemTo(ids[0], 5)` → 顺序等于 `[...ids]..removeAt(0)..insert(5, ids[0])`（**先删后插**语义）、`isManual(ids[0]) == true`；
- `moveItemTo(ids[0], length-1)` → `order.last == ids[0]`（拖到末尾）；
- 拖到自己格（`moveItemTo(ids[1], 自身下标)`）→ 顺序**完全不变**。
  钉死：`targetIndex` 是"移除后插入位"、越界夹取、自身格 no-op。

**#14 `重名冲突被检出`（纯逻辑）** — 取 `scan` 集合，把 scheme 组件清空只留 `origName`，使 `ch01/page_01..06` 与 `ch02/page_01..06` 撞名；断言存在至少一个 `duplicate` 警告。钉死：**跨目录同名必须被检出**（改名后扁平到同一输出目录会互相覆盖）。

**#15 `ComicInfo.xml：语言未写入与 zh 写入`（纯逻辑）** — `v.language = LangChoice.skip` → XML 含 `'<LanguageISO />'`（**自闭合空标签**，不是省略）；`v.language = LangChoice.zh` → 含 `'<LanguageISO>zh</LanguageISO>'`。钉死：**"不写入"的表示法是空标签**，以及 zh 的确切输出。

**#16 `页码重编号与打包计划`（纯逻辑）** — 取 `aot-2`（18 页）：`renumbered.length == pages.length`、`renumbered.first.value == '001.jpg'`；`buildPackagePlans(volumes).length == volumes.length`（全部默认 included）；`op-102`（`outputExists=true`）在默认 `rename` 策略下 `outputDisplay.contains('(1)')`。钉死：3 位补零从 001 起、扩展名小写、默认策略为 `rename` 且冲突重命名格式含 ` (1)`。

### 8.3 测试基建（可复用的意图，非 Dart 细节）

- `_gridHarness`：最小网格（3 列、正方形格）。
- `_realGridHarness(order, {count, pushRoute, useThumbCard, useLayoutBuilder})`：**真机同款结构**，可开关三个变量
  （是否 `ThumbCard`、是否套 `LayoutBuilder`、是否经 `Navigator.push`）—— 用来定位"简化 harness 测不出真机问题"。
- `_drag(tester, fromId, toId, {longPress, kind})`：完整手势序列
  `startGesture → pump(60ms 或 kLongPressTimeout+50ms) → moveBy(30,30)（越过 slop）→ moveTo(目标中心) → up → pumpAndSettle`。
  **注意：先 `moveBy` 小位移越过 slop 再 `moveTo`**，否则立即拖拽识别器不启动。
- 平台覆盖：`debugDefaultTargetPlatformOverride = TargetPlatform.windows/android`，每个用例末尾置回 `null`。
- 视口控制：`tester.view.physicalSize = Size(1400, 900)` + `devicePixelRatio = 1.0` + `addTearDown(tester.view.reset)`。
- 断言普遍带 `reason:` 字符串 —— 失败信息即语义说明。

---

## 9. 值得在新栈复用的语义要点

### 9.1 与实现无关的交互契约（**必须在新栈重建**）

1. **两层排序状态正交**：自动规则层（可重算）× 人工顺序层（拖拽结果）× 固定位置层（图钉）。
   人工标记在重算时清空，固定标记保留；固定项按"记录下标优先、冲突向左找空位"落位。
2. **拖动中不重排，松手才落地** —— 明确的防抖动契约，不是实现细节。
3. **`moveItemTo` 的"先删后插 + 目标格下标"语义**，含越界夹取、自身格 no-op、记录人工标记。
4. **多级排序：每级独立升降序、最多 4 级、至少 1 级、同级比较按 id 兜底保证确定性**。
5. **自然序主路径 + 字典序保留作对照**，且 `SortField` 的**声明/展示顺序**本身就是产品决策
   （自然序、字典序、目录名、修改时间、创建时间、文件大小）。
6. **建议 ≠ 事实**：每个建议值必须携带**人类可读的来源字符串**（含置信度措辞），且 UI 必须可见；
   逐字段"恢复建议值"入口必须存在。
7. **`language` 默认"未设置"，必须人工确认**；"不写入标签"是独立选项且输出为**自闭合空标签**。
8. **批次统一 + 逐项例外**：批量设置跳过被人工覆盖的字段，并**返回实际生效数量**（UI 提示 `已设置到 N 卷`）；
   覆盖集合是 `Set<MetaField>`，`useSuggestion(f)` 清除该字段的覆盖标记。
9. **逐项覆盖以稳定 id 为键、独立于结构存储** → 改命名结构/改排序都不会丢覆盖；清空输入 = 撤销覆盖。
10. **预览是主工作区**：命名预览表（原路径 → 新名 + 警告标签 + 筛选 全部/仅冲突/仅覆盖）、
    ComicInfo.xml 原文预览、页码列表预览、计划操作清单（`describe()` 的 5 类文案）。
11. **冲突/警告的 5 类分型与不同严重级**：重名 + 非法字符 → **跳过**；大小写冲突 + 超长 → **仅警告**；
    人工覆盖 → 信息性。超长阈值 **180**、非法字符集合 `\ / : * ? " < > |` + 控制字符。
12. **危险动作默认关闭 + 通用确认 + 独立危险二次确认**，确认框必须列出影响范围。
13. **计划必须先预览再执行**，冲突项默认跳过并给出 reason；执行后出报告（成功/跳过/失败/时间 + 明细），
    并提供撤销入口（正式版基于 `UndoRecord`）。
14. **`pendingIssues` 式的"待确认清单"聚合**：语言未确认 / 卷号缺失，在卷列表、卷头、计划卡多处一致展示。
15. **计划随输入失效**：任何 scheme / override / 排序变化都使已生成计划失效（`invalidatePlan`），
    防止"看到的计划"与"当前设置"不一致。
16. **测试意图本身**：三层自证 —— 纯逻辑单测（排序/重排/命名/冲突/XML/重编号）、
    UI 层断言（"界面真的变了"，#8 与 #10 是两个典型回归钉子）、真机同款结构（#2–#4 证明简化 harness 不够）。
    新栈应保留这三个层次与对应断言，而不是只留逻辑单测。

### 9.2 Dart/Flutter 特有的实现细节（只需一句话带过）

- `ChangeNotifier` + `AnimatedBuilder` + `addListener(notifyListeners)` 的通知转发链
  （#8 的根因就是这个链断了）→ 新栈用 `StateFlow` / `mutableStateOf` + Compose 重组天然解决。
- `Draggable` / `LongPressDraggable` / `DragTarget` + `StackFit.passthrough` + `ReorderableListView`
  的 Flutter 手势与布局机制 → 新栈用 Compose 的 `detectDragGesturesAfterLongPress` /
  `LazyVerticalGrid` + `animateItem` 重写。
- `MaterialApp` 主题、`ThemeData(colorSchemeSeed: indigo, useMaterial3: true)`、
  `MediaQuery(textScaler: 1.15)` 全局放大 15%、CJK 字体回退链
  （`Microsoft YaHei UI` / `Microsoft YaHei` / `Noto Sans CJK SC`）→ 新栈对应 Material 3 主题与字号基线。
- `debugDefaultTargetPlatformOverride`、`WidgetTester`、`tester.view.physicalSize` 等测试 API → 新栈用
  `ComposeTestRule` / `createAndroidComposeRule` 重写等价断言。
- `String.length` 是 UTF-16 码元数（180 阈值对中文按 1 计、emoji 按 2 计）→ 新栈 Kotlin `String.length`
  同样是 UTF-16 码元数，**语义可直接沿用**（但若要按码点计需显式说明并更新阈值）。
- `Suggestion<T>` 用泛型 + `hasValue` 的运行时类型判断 → 新栈用密封类或 nullable + 显式 `isNotEmpty`。
- `int.parse` 在超长数字串上会抛异常（自然排序未防护）、`List.sort` 不稳定 → 新栈需显式处理。

---

## 附：本次提取的读取方式（可复现）

```bash
git -C /mnt/d/Programing/Personal/pixfold ls-tree -r --name-only 9efef5b -- pixfold-d1
git -C /mnt/d/Programing/Personal/pixfold show 9efef5b:pixfold-d1/lib/domain/models.dart
git -C /mnt/d/Programing/Personal/pixfold show 9efef5b:pixfold-d1/lib/domain/comic.dart
git -C /mnt/d/Programing/Personal/pixfold show 9efef5b:pixfold-d1/lib/domain/naming.dart
git -C /mnt/d/Programing/Personal/pixfold show 9efef5b:pixfold-d1/lib/mock/mock_data.dart
git -C /mnt/d/Programing/Personal/pixfold show 9efef5b:pixfold-d1/test/widget_test.dart
```

未执行 checkout / switch / restore，未修改归档分支与工作区。
