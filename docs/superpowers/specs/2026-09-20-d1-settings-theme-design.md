# D1 设置与主题体系 —— 设计规格（阶段 1）

> **状态**：待用户复核
> **日期**：2026-09-20
> **产物类型**：实现级设计规格（`docs/superpowers/specs/`）
> **产品权威**：`HANGOFF.md`。本文件只承载实现级设计与决策依据，不另立产品决策。
> **关联**：`docs/notes/d1-ui-friction-inventory.md`（UI 摩擦点台账）

## 1. 背景与范围

### 1.1 背景

用户 2026-09-19 提出两条方向：

1. **先做一轮基础 UI 调整**，再进入功能细化；
2. 在首页右上角加**设置入口**，承载语言、主题、配色、权限策略、关于信息等。

这是 D1 验收清单 20/20 达成后的**新需求**，属架构级变更（新增设置子系统 + 主题体系 + 本地化基础设施）。

### 1.2 本阶段（阶段 1）范围

| 交付 | 说明 |
| --- | --- |
| 首页设置入口 | 右上角图标按钮 |
| 设置页 | 外观 / 语言 / 行为 / 关于 四组 |
| 设置持久化 | 纯 Kotlin 模型 + 接口，Android 侧 SharedPreferences |
| 主题体系 | 三态主题、Monet 开关、强调色预设、纯黑背景 |
| 语言切换（部分） | 语言可切换；**英文先只覆盖设置页自身**（用户裁决 c） |
| 发布页面 | 关于页直接跳转 GitHub Releases（**不联网、零权限**） |
| 开源许可清单 | 本项目 MIT + 三方 Apache-2.0 声明 |

### 1.3 明确不在本阶段范围

| 项 | 原因 | 去向 |
| --- | --- | --- |
| **预测性返回开关** | 无运行时 API（见 §2.1） | **用户裁决：不做**，默认开启；不加只读说明 |
| **2021/2025 色彩标准切换** | 需 `material-color-utilities` 的色调板算法（见 §2.2） | HANGOFF §11 待确认 |
| **全量 i18n**（两条工作流页面英文化） | 领域层 99 处文案 + 测试 826 处中文断言需重构（见 §2.3） | **阶段 2** 独立推进 |
| `minSdk` 调整 | 版本锚点变更，牵动 lint 配置与既有注释 | 不碰；本阶段用 `Build.VERSION` 分支 |
| SAF / 权限的**实际生效** | D1 不连真实文件系统 | 本阶段只持久化偏好值 |

## 2. 已核实的硬约束（均经实测，非推测）

### 2.1 预测性返回：无运行时开关

反编译 `android-36/android.jar` 逐条确认：

```
android.window.OnBackInvokedDispatcher:
  registerOnBackInvokedCallback(int, OnBackInvokedCallback)
  unregisterOnBackInvokedCallback(OnBackInvokedCallback)
  PRIORITY_DEFAULT / PRIORITY_OVERLAY / PRIORITY_SYSTEM_NAVIGATION_OBSERVER
  ← 无任何 enabled/disabled 开关

android.app.Activity:
  getOnBackInvokedDispatcher() / onBackPressed()
```

`android:enableOnBackInvokedCallback` 是 **manifest 静态属性**；官方 [Android 16 行为变更](https://developer.android.com/about/versions/16/behavior-changes-16)
给出的退出方式仅为「在 manifest 中设为 `false`」。

**结论**：本项目 targetSdk 36，预测性返回已默认开启且已按官方要求迁移
（`BackHandler` + 分层返回，`PixFoldApp` / `WorkflowASteps` / `WorkflowBSteps`）。
**用户裁决：不做该项，默认保持开启，设置页不出现该条目。**

### 2.2 色彩标准 2021/2025：零依赖下不可行

下载 `material3-android-1.4.0.aar` 反编译确认：

| 探测项 | 结果 |
| --- | --- |
| `ColorSpec` / `Spec2021` / `Spec2025` 类文件 | **0 个（不存在）** |
| `TonalPalette` | public，但构造器为 83 个 `long` 的内部形态，**无公开"从种子色构造"入口** |
| `dynamicLight/DarkColorScheme(ctx)` | 只能读**系统壁纸**取色，不能从用户选定种子色生成 |
| pom 传递依赖 | **无** `material-color-utilities` |

2021 与 2025 色彩规范是**规范级差异**（色调板映射不同），需 `material-color-utilities` 的
HCT/色调板算法。官方实现为 Google 独立库，社区版为 `materialkolor 5.0.1`。

**结论**：该项目列入待确认；本阶段**不做**。

> **重要区分（修正我最初的判断）**：**强调色预设不需要该算法** ——
> 预设可手写为若干套静态 `ColorScheme`，零依赖即可实现。见 §6.2。

### 2.3 i18n 规模：比预期大，故拆阶段

实测统计（2026-09-20）：

| 位置 | 数量 |
| --- | --- |
| `domain/` 源码中面向用户的文案 | **99 处**（另有 48 处是 mock 数据，与语言无关） |
| `app/` 源码硬编码中文 | **176 处** / 18 文件 |
| **`domain/` 测试**中逐字断言中文 | **385 处** / 16 文件 |
| **`app/` 测试**中逐字断言中文 | **441 处** |
| `@Test` 总数 | 领域 219 + app 242 = **461** |

领域层是**纯 Kotlin JVM 模块（无 Android Context）**，文案不能直接用 `stringResource`。
若一次性完成，需同时改造「源码文案契约」与「461 个测试的断言方式」——
**测试与被测代码同时改动，会让"文案对了但逻辑错了"极难被发现**。

**故拆为阶段 2**；阶段 1 的语言切换只覆盖**设置页自身**（全新代码，直接走资源，不触碰既有契约）。

### 2.4 其他已核实事实

| 项 | 实况 |
| --- | --- |
| 现有持久化 | **完全没有**（无 DataStore / SharedPreferences） |
| 现有主题 | `PixFoldTheme(darkTheme, dynamicColor)`；动态取色已支持 API 31+ |
| `LocaleManager`（每应用语言） | API 33+ 可用：`setApplicationLocales/ getApplicationLocales` ✅ |
| `MANAGE_EXTERNAL_STORAGE` | API 36 仍存在；`Environment.isExternalStorageManager()` ✅ |
| 网络权限 | **零 `uses-permission`**（无 INTERNET） |
| 版本 | `versionCode = 1` / `versionName = "1.0"` |
| 仓库 / 作者 / 许可 | `github.com/Cyanix-0721/pixfold`；MIT © 2026 Cyanix-0721；`LICENSE` 存在 |
| GitHub Releases | `releases` 返回 **HTTP 200 + 空数组**；`releases/latest` 返回 **404**（尚无发布）—— 已据用户裁决改为直接跳转发布页（§7） |
| coroutines | 经 `compose runtime` **传递可用**（`kotlinx-coroutines-core 1.9.0`） |
| `org.json` | Android 框架内置（`JSONObject`），**零依赖**可解析 GitHub 响应 |

> **用户裁决（2026-09-20）**：不上架 Play Store（侧载自用 / 自行发 GitHub Release），
> 故 `MANAGE_EXTERNAL_STORAGE` **无需任何申请**，仅需运行时一次授权。
> **Play 合规风险从设计中删除**，设置项措辞只如实说明差异。

## 3. 架构设计

### 3.1 分层与依赖方向

延续既有单边依赖 `app → domain`：

```
domain/  (纯 Kotlin JVM, 无 Android)
  settings/PixFoldSettings.kt    —— 设置模型 + 枚举(纯数据)
  settings/SettingsStore.kt      —— 读写接口(抽象)

app/     (Compose, 依赖 domain)
  data/SharedPrefsSettingsStore.kt  —— SettingsStore 的 Android 实现
  ui/settings/SettingsScreen.kt     —— 设置页
  ui/settings/LicensesPage.kt       —— 开源许可页
  ui/theme/AccentPresets.kt         —— 强调色预设(静态 ColorScheme)
```

**理由**：设置模型是**纯数据**，放 `domain/` 可跑 JVM 单测（无需 Robolectric），
与既有"领域逻辑不依赖 UI"的架构一致；Android 相关实现全部留在 `app/`。

### 3.2 设置模型

```kotlin
// domain/settings/PixFoldSettings.kt
enum class ThemeMode { System, Light, Dark }
enum class AccentChoice { System, Default, Blue, Green, Purple, Orange, Pink }
enum class AppLanguage { System, Chinese, English }
enum class StorageAccess { Saf, AllFiles }

data class PixFoldSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val accent: AccentChoice = AccentChoice.System,
    val pureBlack: Boolean = false,
    val language: AppLanguage = AppLanguage.System,
    val storageAccess: StorageAccess = StorageAccess.Saf,
) {
    /**
     * 是否使用系统动态取色(Monet)。
     *
     * **由 [accent] 派生,不单独存储** —— `System` 与"开 Monet"是**同一件事**。
     * 初稿把二者做成两个独立字段,自检时发现默认值即可自相矛盾
     * (`accent=System` 但 `monetEnabled=false`),且可能构造出
     * "开着 Monet 又选了蓝色预设"这种无意义状态。
     * 改为派生后,**非法组合在类型层面就不存在**,UI 的开关与下拉天然同步。
     */
    val monetEnabled: Boolean get() = accent == AccentChoice.System

    /** 选择强调色。选 [AccentChoice.System] 即等于开启 Monet;选预设即等于关闭 Monet。 */
    fun withAccent(next: AccentChoice) = copy(accent = next)

    /** Monet 开关。开 -> [AccentChoice.System];关 -> 回到 [AccentChoice.Default] 预设。 */
    fun withMonet(enabled: Boolean) = copy(
        accent = if (enabled) AccentChoice.System else AccentChoice.Default,
    )
}
```

**默认值理由**：

| 项 | 默认 | 理由 |
| --- | --- | --- |
| `themeMode` | `System` | 跟随系统是 Android 惯例 |
| `accent` | `System` | **用户裁决（2026-09-20）：默认使用系统取色**，与既有行为一致（`dynamicColor = true`），首次运行外观不变 |
| `pureBlack` | `false` | 影响面大（深色下的观感），默认不启用 |
| `language` | `System` | Android 惯例 |
| `storageAccess` | `Saf` | 官方推荐、D2a 已验证 5/5、隐私友好 |

> **与现状一致（已确认）**：默认 `AccentChoice.System` 等价于既有
> `PixFoldTheme(dynamicColor = true)`，**首次运行外观不变**。
> 内置预设仅在用户主动选择时生效；`API < 31` 无法动态取色时回退到 `Default`（见 §6.1）。

### 3.3 持久化

```kotlin
// domain/settings/SettingsStore.kt
interface SettingsStore {
    fun load(): PixFoldSettings
    fun save(settings: PixFoldSettings)
}
```

Android 实现用 `SharedPreferences`（`Context.getSharedPreferences("pixfold_settings", MODE_PRIVATE)`）。

**约定**：

- **每个字段独立 key**（而非整个对象序列化）—— 便于将来增删字段时向后兼容；
- **未知/非法值回退到默认值**（例如枚举名不认识）—— 避免旧版本残留值导致崩溃；
- 读写均为**同步**（字段少、值小；`SharedPreferences.commit` 在设置页里开销可忽略）。

### 3.4 状态传播

设置在**应用根**持有（与 `WorkflowASteps` 的"状态提上层"约定一致）：

```kotlin
@Composable
fun PixFoldApp(store: SettingsStore = rememberSettingsStore()) {
    var settings by remember { mutableStateOf(store.load()) }
    val update: (PixFoldSettings) -> Unit = { settings = it; store.save(it) }

    PixFoldTheme(settings = settings, systemDark = isSystemInDarkTheme()) {
        Surface(...) {
            when (screen) {
                Screen.Settings -> SettingsScreen(
                    settings = settings, onChange = update, onBack = { screen = Screen.Home },
                )
                // ...
            }
        }
    }
}
```

`store` 作为参数注入，**便于测试替换为内存实现**。

### 3.5 导航

`Screen` 密封接口新增一项：

```kotlin
sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen      // 新增
    data object WorkflowA : Screen
    data object WorkflowB : Screen
    data class Preview(val index: Int) : Screen
}
```

返回处理：`Screen.Settings` 注册 `BackHandler { screen = Screen.Home }`（与 Preview 同构）。

## 4. 设置页设计

### 4.1 结构

单一页面级 `LazyColumn`（项目布局约定）。分组用 M3 `ListItem` + 分隔线。

```
┌ ← 设置 ─────────────────────────────┐
│ 外观                                 │
│   主题          跟随系统 / 浅色 / 深色   │  ← 分段选择
│   动态取色      从壁纸提取配色(A12+) [开关]│
│   强调色        跟随系统 / 默认 / 蓝 / 绿 / 紫 / 橙 / 粉 │  ← 下拉
│   纯黑背景      深色模式下使用纯黑 [开关] │
│ 语言                                 │
│   语言          跟随系统 / 中文 / English│  ← 下拉
│ 行为                                 │
│   文件访问方式   SAF(推荐) / 全部文件访问 │  ← 下拉 + 说明
│ 关于                                 │
│   版本           0.1.0-dev (1)        │
│   仓库地址       github.com/Cyanix-0721/pixfold │
│   发布页面       github.com/Cyanix-0721/pixfold/releases │
│   作者           Cyanix-0721          │
│   开源许可       查看                 │
├──────────────────────────────────────┤
│  恢复默认设置                          │  ← 带二次确认
└──────────────────────────────────────┘
```

### 4.2 各组措辞与行为

**外观 · 主题**（三态，分段选择）

| 值 | 显示 | 行为 |
| --- | --- | --- |
| `System` | 跟随系统 | `isSystemInDarkTheme()` |
| `Light` | 浅色 | 强制浅色 |
| `Dark` | 深色 | 强制深色 |

**外观 · 动态取色（Monet）**

- 标签：`动态取色`
- 说明（次要行）：`从壁纸提取配色（Android 12+）`
- 行为：开启即 `accent = System`，用 `dynamicLight/DarkColorScheme(context)`
- **与「强调色」的关系**：二者是**同一状态的两个视图**（模型层 `monetEnabled` 由 `accent` 派生）。
  开/关 Monet 会同步改变强调色下拉的显示值，反之亦然 —— **不可能出现不一致**。
- 不可用时（API < 31）：**整行禁用**并说明 `需要 Android 12 或更高版本`

**外观 · 强调色**

- 标签：`强调色`
- 选项：`跟随系统` / `默认` / `蓝` / `绿` / `紫` / `橙` / `粉`
- **语义**：
  - `跟随系统` = 使用动态取色（等价于 Monet 开）
  - `默认` 及彩色预设 = 使用该预设的静态配色（等价于 Monet 关）
- **互斥由模型保证**（§3.2）：`monetEnabled` 是 `accent == System` 的派生值，
  不存在"开着 Monet 又选了蓝色预设"的非法组合，UI 无需额外同步逻辑。

> **UI 取舍说明**：Monet 开关与「强调色 = 跟随系统」语义重叠，
> 保留两个控件是为了满足"既有开关又有预设"的明确要求；
> 但**单一事实来源是 `accent`**，开关只是它的快捷方式。

**外观 · 纯黑背景**

- 标签：`纯黑背景`
- 说明：`深色模式下使用纯黑背景（OLED 更省电）`
- 仅在深色生效（浅色下不改变外观，但开关仍可调 —— 避免切换主题时开关状态跳变）

**语言**

- 标签：`语言`
- 选项：`跟随系统` / `中文` / `English`
- **阶段 1 实际效果**：设置页自身文案跟随切换；既有工作流页面仍为中文
  （阶段 2 补全）。**UI 上如实标注**：`其他页面的多语言将在后续版本补全`
  —— 不假装已完整支持。

**行为 · 文件访问方式**

- 标签：`文件访问方式`
- 选项：`SAF（推荐）` / `全部文件访问`
- 说明随选择变化：

| 选择 | 说明文案 |
| --- | --- |
| SAF | `通过系统文件选择器授权目录，隐私友好、无需额外权限` |
| 全部文件访问 | `直接读写存储，首次使用时需授权；适合个人自用（不上架应用商店）` |

- **D1 阶段该设置只持久化，不改变行为**（D1 不连真实文件系统）。
  UI 上标注：`将在接入真实文件系统后生效`

**关于**

| 项 | 内容 |
| --- | --- |
| 版本 | `0.1.0-dev (1)` —— 取自 `BuildConfig.VERSION_NAME/VERSION_CODE` 并加 `-dev` 后缀（用户裁决 1a） |
| 发布页面 | 见 §7 |
| 仓库地址 | `github.com/Cyanix-0721/pixfold`，点击用 `Intent.ACTION_VIEW` 打开 |
| 作者 | `Cyanix-0721` |
| 开源许可 | 进入 §8 的许可清单页 |

**恢复默认设置**

- 位于设置页底部，`TextButton`，点击弹 `AlertDialog` 二次确认：
  - 标题：`恢复默认设置？`
  - 正文：`所有外观、语言与行为设置将回到初始值。`
  - 按钮：`取消` / `恢复`
- 确认后 `onChange(PixFoldSettings())` 并持久化

## 5. 首页设置入口

`HomeScreen` 顶部标题行右侧加图标按钮：

- 图标：`ic_settings.xml`（Material Symbols `settings`，几何数据取自
  `github.com/google/material-design-icons`，Apache-2.0；与既有
  `ic_push_pin_*` 同策略：**官方矢量资源 + `painterResource`，不自绘**）
- 触控目标 ≥ 48dp（M3 `IconButton` 默认满足）
- `contentDescription = "设置"`（纯图标按钮必须给，约束 7）
- 布局：标题 `Text` 与按钮用 `Row` + `SpaceBetween`，标题保持左对齐

## 6. 主题与颜色体系

### 6.1 主题解析

```kotlin
@Composable
fun PixFoldTheme(
    settings: PixFoldSettings,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dark = when (settings.themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // 动态取色与否**只由 monetEnabled 决定**。
    // 不变式(§4.2 强制):monetEnabled == (accent == System) —— 二者表达同一件事,
    // 故此处若再判 accent,就是把同一条件写两遍(初稿曾如此,自检时发现并删除)。
    val scheme = if (settings.monetEnabled && SDK_INT >= S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        pixFoldPreset(settings.accent, dark)   // System 但 API<31 时回退默认预设
    }.let { if (settings.pureBlack && dark) it.withPureBlack() else it }

    MaterialTheme(colorScheme = scheme, content = content)
}
```

**向后兼容**：保留 `PixFoldTheme(darkTheme, dynamicColor, content)` 重载
（既有 `ThemeTest` 与调用点不破坏），内部转调新签名。

### 6.2 强调色预设

**6 套静态 `ColorScheme`**（`AccentPresets.kt`）：内置默认、蓝、绿、紫、橙、粉，每套含浅色 + 深色。

色值取自 **M3 基线色调映射**（primary = tone 40（浅）/ tone 80（深），
onPrimary/primaryContainer 等同理），保证语义角色齐全且对比度达标。

**实现方式**：调用 `lightColorScheme(...)` / `darkColorScheme(...)` 并**显式命名**
下列关键角色，其余角色沿用 M3 默认值
（实测这两个工厂的完整参数为 **48 个**；全量指定既不必要也易出错，
故只固定"决定观感"的角色）。

**必须显式指定的角色**：

`primary` / `onPrimary` / `primaryContainer` / `onPrimaryContainer` /
`secondary` / `onSecondary` / `secondaryContainer` / `onSecondaryContainer` /
`tertiary` / `onTertiary` / `tertiaryContainer` / `onTertiaryContainer` /
`background` / `onBackground` / `surface` / `onSurface` /
`surfaceVariant` / `onSurfaceVariant` / `outline` / `outlineVariant`

**不改动**：`error` 族（保持 M3 默认红，避免"错误色随主题漂移"导致危险提示弱化）。

> **对比度要求**：每套预设须满足 `onX` 与 `X` 的对比度 ≥ 4.5:1（正文）。
> 计划以单测断言（用相对亮度计算）守住，而非靠肉眼。

> **不做**：`ColorSpec` 2021/2025 切换（§2.2）。

### 6.3 纯黑背景

**关键点**：不能只把 `background` 置黑 —— 否则 `surfaceContainer*` 仍是深灰，
深色下会出现"黑底灰卡"的割裂感。

**用户裁决（2026-09-20）**：要 **OLED 那种纯黑**（`#000000`），不是"深灰"。
因此**容器层级也一并压到纯黑**，层级改由边框表达（见下）。

```kotlin
fun ColorScheme.withPureBlack() = copy(
    background = Color.Black,
    surface = Color.Black,
    // surfaceContainer* 系列同样压到纯黑 —— 用户要的是真 OLED 黑,
    // 若保留深灰容器会出现"黑底灰卡"的割裂感(与纯黑诉求矛盾)。
    // 容器与背景的区分改由 **outline/surfaceVariant 边框与分隔线**承担。
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
    surfaceVariant = Color(0xFF1A1A1A),   // 供需要"轻微填充"处使用
    // on* 角色保持原配色,确保对比度不退化
)
```

**配套要求（否则卡片会"糊成一片"）**：

- 深色 + 纯黑下，可交互卡片需有**可见边界**（`outlineVariant` 边框或分隔线），
  因为容器与背景同色后，层级只能靠边框表达；
- 该调整只在 `pureBlack == true` 时生效，**不改变默认深色的观感**。

> **⚠️ 待真机核对**：纯黑下卡片边界是否足够清晰（见 §10 验证要点）。
> 若边界不足，优先加边框而非把容器调灰 —— 后者违背用户的纯黑诉求。

## 7. 发布页面（替代"检查更新"）

### 7.1 决策（用户裁决 2026-09-20）

**去掉"检查更新"功能**，改为**直接跳转发布页**。

**理由**：

1. 关于页**已有仓库地址**入口，再加一个"检查更新"语义重复；
2. 跳转发布页是**零成本且永远准确**的 —— 用户自己看到最新版本，
   不依赖我们的解析逻辑，也不会有"解析错了版本号"的风险；
3. **不需要 `INTERNET` 权限** → 应用**保持零权限**
   （现状即零 `uses-permission`，本阶段不引入任何网络能力）。

> 曾考虑的方案（GitHub Releases API 检查更新）已弃用：
> 需加 `INTERNET` 权限、需解析 JSON、需处理 404/空列表/网络失败五态，
> 而收益仅是"少点一次链接"。

### 7.2 关于页两个链接

| 标签 | 目标 URL | 说明行 |
| --- | --- | --- |
| `仓库地址` | `https://github.com/Cyanix-0721/pixfold` | 源码与文档 |
| `发布页面` | `https://github.com/Cyanix-0721/pixfold/releases` | 查看最新版本 |

- 点击均以 `Intent.ACTION_VIEW` 打开系统浏览器
- **需处理"无浏览器"的边界**：`ActivityNotFoundException` 时以 `Snackbar`/`Toast`
  提示 `未找到可打开链接的应用`，不崩溃
- 用 `Uri.parse` + `Intent(Intent.ACTION_VIEW, uri)`；不申请任何权限

### 7.3 版本号显示

- 取 `BuildConfig.VERSION_NAME` / `VERSION_CODE`（`build.gradle.kts` 配置）
- **用户裁决**：显示 `0.1.0-dev (1)`；正式 MVP 发版时由版本配置改名为 `0.1`
- 版本号旁以次要行标注：`开发版`（如实反映尚未发布）


## 8. 开源许可页

### 8.1 结构

设置页 →「开源许可」→ 独立页面，两块内容：

**① 本项目**

```
PixFold
MIT License · Copyright (c) 2026 Cyanix-0721
[查看完整许可证]        ← 展开 LICENSE 全文
```

**② 第三方组件**（每行：名称 / 许可 / 来源链接）

| 组件 | 许可 |
| --- | --- |
| Jetpack Compose (UI / Runtime) | Apache-2.0 |
| Material 3 (`androidx.compose.material3`) | Apache-2.0 |
| AndroidX Activity Compose | Apache-2.0 |
| Kotlin Standard Library | Apache-2.0 |
| Material Symbols 图标几何数据 | Apache-2.0 |
| Robolectric（仅测试，不随应用分发） | MIT |
| JUnit 4（仅测试，不随应用分发） | EPL-1.0 |

- **出处依据**：图标许可已在各 `drawable/*.xml` 注释中记录来源；
  依赖许可见各自 POM。
- **不引入任何许可证展示三方库**（如 `aboutlibraries`）—— 手写静态清单，保持零依赖。
- 页面顶部标注：`本应用使用了以下开源组件，特此致谢。`

> **合规性说明**：Apache-2.0 要求保留版权与许可声明，
> 故该清单**不只是礼貌，属合规刚需**。

## 9. 本地化机制（阶段 1 部分）

### 9.1 语言切换实现

| SDK | 机制 |
| --- | --- |
| **API 33+**（本项目真机 = A16） | `LocaleManager.setApplicationLocales(LocaleList)`；系统负责重建与持久化 |
| API < 33 | `SharedPreferences` 记录选择 + `attachBaseContext` 包 `ContextWrapper` 并 `applyOverrideConfiguration` |

- manifest 增加 `<locale-config>`（`res/xml/locales_config.xml`）声明 `zh` / `en`，
  使系统"每应用语言"设置与我们对齐。
- 因阶段 1 已持久化语言选择（§3.3），**两条路径都以设置值为准**，不产生分叉。

### 9.2 资源组织

```
res/values/strings.xml        —— 中文(默认)
res/values-en/strings.xml     —— 英文(阶段 1 仅设置页 + 首页)
res/xml/locales_config.xml    —— 声明 zh / en
```

**约束**：阶段 1 新增的所有设置页文案**一律走资源**，不新增硬编码中文
（既有页面的硬编码留给阶段 2）。

### 9.3 已知不完整（如实标注）

切换 English 后，**工作流 A/B 页面仍显示中文**。设置页内以说明行如实告知：
`其他页面的多语言将在后续版本补全`。

## 10. 测试与自证策略

按项目「修复自证三层」约定：

### 10.1 领域层（JVM 单测）

- `PixFoldSettingsTest`：默认值、**`monetEnabled` 由 `accent` 派生**（含 `withAccent`/`withMonet` 的往返）、非法枚举回退
- `SettingsStoreContractTest`：以内存实现验证读写往返

### 10.2 主题解析（JVM 单测，无需 Robolectric）

- 主题三态 → `dark` 解析正确（System 跟随入参、Light/Dark 强制）
- 纯黑：`withPureBlack()` 后 `background == Black` 且 `onSurface` 对比度不退化
- 预设：6 套 × 2 模式均**角色完整**（无 `Color.Unspecified` 漏项）

### 10.3 UI 层（Robolectric Compose 断言）

- 首页有设置按钮且可点、有 `contentDescription`
- 设置页四组条目齐全；每项改动能**真实反映到状态**（防"假控件"）
- 主题变更后**界面配色真的变**（断言 `MaterialTheme.colorScheme.primary` 或背景色）
- 恢复默认：二次确认 → 重置 → 界面回到默认（按钮在底部，需 `performScrollToNode`）
- 许可页：本项目与三方清单均渲染
- 语言切换后设置页文案真的变（中 ↔ 英）

### 10.4 真机（第三层）

- 设置页四大组逐项真机核对
- Monet 开/关 + 纯黑背景的实际观感（截图对比）
- 语言切换后设置页文案变化
- 两个链接（仓库 / 发布页）能真实拉起浏览器；无浏览器时不崩溃
- 纯黑观感：确认是**真 `#000000`**，且卡片边界清晰可辨（§6.3）

### 10.5 变异验证

关键断言需做变异验证（去掉实现后测试必须失败），至少覆盖：
主题三态解析、`accent` 派生 `monetEnabled`、纯黑生效、恢复默认真的重置、
语言切换真的换文案。

## 11. 决策记录（原待确认项，2026-09-20 已全部裁决）

1. ~~首次运行的默认配色~~ —— **已裁决（2026-09-20）：默认使用系统取色**
   （`accent = AccentChoice.System`），与既有 `dynamicColor = true` 一致，首次运行外观不变。
   `API < 31` 无法动态取色时回退到 `Default` 预设（§6.1）。
2. ~~强调色预设色值~~ —— **已裁决（2026-09-20）：无偏好**，
   采用 M3 基线色调映射生成。
3. ~~纯黑色阶~~ —— **已裁决（2026-09-20）：OLED 纯黑 `#000000`**，
   容器层级一并压黑；实现后真机核对卡片边界（§6.3）。
4. ~~"恢复默认设置"是否也重置语言~~ —— **已裁决（2026-09-20）：重置**（含语言与文件访问方式）。
5. ~~检查更新的触发方式~~ —— **已裁决（2026-09-20）：移除"检查更新"**，
   改为关于页直接跳转发布页；应用**不引入 `INTERNET` 权限**（见 §7.1）。

## 12. 实施顺序（供 writing-plans 使用）

1. 领域层设置模型 + `SettingsStore` 接口 + 单测
2. `SharedPrefsSettingsStore` + 契约测试
3. 强调色预设 + `PixFoldTheme` 重构 + 单测
4. 纯黑背景 + 单测
5. `Screen.Settings` + 设置页骨架 + 首页入口 + UI 断言
6. 语言切换机制 + 资源 + UI 断言
7. 仓库/发布页跳转（`ACTION_VIEW` + 无浏览器兜底）+ UI 断言
8. 开源许可页 + UI 断言
9. 恢复默认设置 + UI 断言
10. 变异验证 + 真机核对 + 文档回写

## 13. 文档回写清单

| 文件 | 变更 |
| --- | --- |
| `HANGOFF.md` | 新增设置页与主题体系描述；§11 增列「2021/2025 色彩标准」待确认；§9.1 记录本阶段 |
| `AGENTS.md` | 头部快照同步 |
| `README.md` | 状态同步 |
| `docs/notes/d1-ui-friction-inventory.md` | 标注 P0 与本轮的关系（设置页不解决 P0） |
| 本文件 | 复核通过后定稿 |
