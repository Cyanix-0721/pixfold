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
| **全量 i18n**（两条工作流页面英文化） | 领域层 99 处文案 + 测试的中文断言需重构（见 §2.3） | **✅ 阶段 2 已完成（2026-09-25）**，见 `HANGOFF.md` §9.1 与本节 §2.3 末的实测口径 |
| `minSdk` 调整 | 版本锚点变更，牵动 lint 配置与既有注释 | 不碰；本阶段用 `Build.VERSION` 分支。**⚠️ 2026-09-22 更新**：本阶段之后用户单独裁决把 `minSdk` 由 24 提到 **30**（见 `docs/notes/d1-toolchain-evidence.md` §9）；两处 `Build.VERSION` 分支（`Theme.kt:48`、`PixFoldApp.kt:192`）**保持不变**，因为 30 仍低于它们所需的 31 / 33。**⚠️ 2026-09-25 再更新**：`minSdk` 又提到 **34**（提交 `f3cfb08`，见 §9.8），34 ≥ 31 / 33 ⇒ **两处版本分支已删除**（`Theme.kt` 的 `Build.VERSION.SDK_INT >= S`、`PixFoldApp.kt` 的 `SDK_INT < TIRAMISU`），`ObsoleteSdkInt` 随之消失 |
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

> **⚠️ 本节的"不做"结论已于 2026-09-20 被用户推翻**：用户选择**引入
> `com.materialkolor:material-color-utilities`**(算法子库,非 Compose 包装),
> 该开关**已实现并交付**。原文保留以存证 —— 它记录的是"在零依赖前提下不可行"这一事实,
> 而**约束本身被用户解除了**。裁决见 HANGOFF §11 第 17 项;实现见 §6 与 §14。

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

> **✅ 阶段 2 已完成（2026-09-25）**。实施记录见 `HANGOFF.md` §9.1「✅ 全量 i18n（2026-09-25）」。
> 上表为**当时的估计**，实测口径如下（差异来自统计方式，非范围变化）：
>
> | 项 | 2026-09-20 估计 | 实测（2026-09-25） |
> | --- | --- | --- |
> | `domain/` 面向用户的文案 | 99 处 | **99 处**（一致） |
> | `app/` 源码硬编码中文 | 176 处 / 18 文件 | **215 处 / 192 条 / 19 文件**（按「用户可见文案」逐条计数） |
> | `domain/` 测试逐字断言中文 | 385 处 | **385 处**（一致） |
> | `app/` 测试逐字断言中文 | 441 处 | **490 处** |
>
> **架构结论**：领域层不再持有文案，改为产出「文案键 `TextKey` + 参数 `Msg`」，
> 由界面层在渲染时按当前语言解析（原语见 `domain/src/main/kotlin/com/pixfold/d1/domain/text/`）。
> 这条路线**同时解决了**"测试与被测代码同时改动难以发现逻辑错误"的顾虑：
> 领域层测试现在断言 `Msg` 的**结构**（键 + 参数），不碰任何自然语言。

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

> ✅ **真机核对已完成（2026-09-21）**：纯黑开关前后截图对比为「深灰紫 → **纯 `#000000`**」，
> 分隔线清晰可辨 ⇒ 卡片边界足够，**未触发**下方"优先加边框"的备选动作。
> 结论记于 `HANGOFF.md:1288`（「纯黑背景」行）。
> （本行原写「⚠️ 待真机核对」，属文档漂移，已于 2026-09-25 订正。）

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

**② 第三方组件** —— **由 AboutLibraries 自动生成**（见下）

组件列表由 `com.mikepenz:aboutlibraries` 从各依赖的 POM/元数据**自动提取**，
不需要手工维护。预期覆盖（升级后实测为准）：

| 组件 | 许可 |
| --- | --- |
| Jetpack Compose (UI / Runtime / Foundation) | Apache-2.0 |
| Material 3 (`androidx.compose.material3`) | Apache-2.0 |
| AndroidX Activity Compose / Core | Apache-2.0 |
| Kotlin Standard Library | Apache-2.0 |
| **AboutLibraries 自身** | Apache-2.0 |
| Material Symbols 图标几何数据 | Apache-2.0 |

- **页面顶部**：`本应用使用了以下开源组件，特此致谢。`
- **Material Symbols 图标**（`ic_*` 矢量资源）：许可与来源已逐条记录在
  各 `drawable/*.xml` 注释中；AboutLibraries **不会**自动收录这类"手工引入的几何数据"，
  故在许可页**以一条静态条目**列出（这是本页唯一手写的部分）。

### 8.2 为什么改用 AboutLibraries（决策变更记录）

**初稿决定**：手写静态清单、不引入三方库（当时理由是"保持零依赖"）。

**2026-09-20 用户裁决后变更**：用户明确"**并不强求无第三方依赖，热门且有在维护的库可以考虑**"，
并选择升级工具链以接入 AboutLibraries。变更依据：

| 维度 | 事实（2026-09-20 实测） |
| --- | --- |
| 热度 | GitHub **4.4k star**、591 fork、3,224 commits |
| 维护活跃度 | 最新 release **15.2.0（2026-08-28）**，依赖由 Renovate 自动跟进 |
| 构件正确坐标 | **`com.mikepenz:aboutlibraries-core`** 与 **`-compose-m3`**（均为 15.2.0） |
| ⚠️ 坑 | 父构件 `com.mikepenz:aboutlibraries` **只到 14.2.1** —— 查"最新版本"时看父构件会得出**错误结论** |
| 使用方式 | 官方推荐 `libs.versions.toml`（Version Catalog）—— **本项目早已在用** |

**代价（已接受）**：需把工具链升到 Kotlin ≥ 2.4.10，连带升级
Gradle / AGP / Compose BOM / compileSdk。**升级已完成并验证**（见 §10.6 与
`docs/notes/d1-toolchain-evidence.md` §8）。

> **合规性说明**：Apache-2.0 要求保留版权与许可声明，
> 故该清单**不只是礼貌，属合规刚需** —— 自动生成可避免"漏列某个依赖的许可"这类合规风险，
> 这也是引入它的主要收益（而非单纯省事）。

## 9. 本地化机制（阶段 1 部分）

### 9.1 语言切换实现

| SDK | 机制 |
| --- | --- |
| **API 33+**（本项目真机 = A16） | `LocaleManager.setApplicationLocales(LocaleList)`；系统负责重建与持久化 |
| API < 33 | `SharedPreferences` 记录选择 + `attachBaseContext` 包 `ContextWrapper` 并 `applyOverrideConfiguration` |

- manifest 增加 `<locale-config>`（`res/xml/locales_config.xml`）声明 `zh` / `en`，
  使系统"每应用语言"设置与我们对齐。
- ~~因阶段 1 已持久化语言选择（§3.3），**两条路径都以设置值为准**，不产生分叉。~~
  → **此口径是错的（2026-09-25 修正）**：系统侧「设置 → 应用 → 语言」是**独立的写入口**，
  不能假定「设置值为默认 `System`」就等于「用户没做过选择」。详见 §9.4。

### 9.2 资源组织

```
res/values/strings.xml        —— 英文(默认/兜底)，仅 1 条 app_name(translatable="false")
res/values/settings_strings.xml      / res/values-zh/settings_strings.xml      —— 设置页 + 许可页
res/values/domain_strings.xml        / res/values-zh/domain_strings.xml        —— 领域层文案键
res/values/strings_common.xml        / res/values-zh/strings_common.xml        —— 通用
res/values/strings_plan.xml          / res/values-zh/strings_plan.xml          —— 计划/确认/执行报告
res/values/strings_workflowa.xml     / res/values-zh/strings_workflowa.xml     —— 工作流 A
res/values/strings_workflowb.xml     / res/values-zh/strings_workflowb.xml     —— 工作流 B
res/xml/locales_config.xml    —— 声明 zh / en
```

> **✅ 阶段 2 修正（2026-09-25）**：上面原写「英文：**仅设置页**（阶段 1）」。
> 全量 i18n 完成后，**6 组资源全部有中英对照**，共 **370 个默认(英文)键 / 364 个中文键**
> —— 差 6 个是 `translatable="false"`、只在默认文件里声明的键。

> **⚠️ 兜底语言裁决与反转（2026-09-25）**：当日先裁决「中文兜底」（`values/` 装中文、
> 英文放 `values-en/`），**随后被用户自己推翻为「英文兜底」**（逐字「那还是改成英文兜底，
> 默认选项是跟随系统一般也用不上兜底就是了」）⇒ 上表是**反转后**的最终布局。
> 反转同时带来两条新约束：中文侧 `<plurals>` **只写 `other`**（lint `UnusedQuantity`
> 拒绝为 zh 写 `one`）、`translatable="false"` 的键只留在默认 `values/`。
> 完整裁决与理由见 §15.7。

**约束**：阶段 1 新增的所有设置页文案**一律走资源**，不新增硬编码中文
（既有页面的硬编码留给阶段 2）。

### 9.3 已知不完整（如实标注）

> **✅ 阶段 2 已消解（2026-09-25）**：本节描述的是阶段 1 结束时的状态，**已被全量 i18n 取代**。
> 保留原文以存证当时的实测口径。

切换 English 后，**工作流 A/B / 首页等页面仍显示中文**。设置页内以说明行如实告知：
`其他页面的多语言将在后续版本补全`。

实测覆盖缺口（2026-09-25，`grep -c stringResource`）：

| 范围 | 命中文件数 | 说明 |
| --- | --- | --- |
| `app/src/main/kotlin/.../ui/settings/SettingsScreen.kt` | 49 | 已资源化 |
| `app/src/main/kotlin/.../ui/settings/LicensesScreen.kt` | 9 | 已资源化 |
| **其余 30 个 UI 文件** | **0** | 全硬编码，含 `HomeScreen.kt` 与整个 `ui/workflowa/`、`ui/workflowb/` |

`ui/` 下非 settings 目录共 **44 行硬编码中文**（如 `HomeScreen.kt:81-82` 的工作流入口标题）。
⇒ 即使 per-app locale 完全正确，**可见效果也仅限设置页与许可页**。
本次范围经用户裁决 = **只修语言同步 bug**，不铺开 i18n。

**阶段 2 完成后的实测残留**（2026-09-25，CJK 正则逐行扫描）：

| 范围 | 残留 | 说明 |
| --- | --- | --- |
| `app/main` | **1 处 / 1 文件** | `SchemeEditor.kt:365` 的 `SANITIZE_EXAMPLE_INPUT = "封:面?.jpg"` —— **示例数据**，非文案 |
| `domain/main`（非 mock） | **5 处 / 3 文件** | `Sort.kt:30-31`、`Execution.kt:57/60` 的 `require{}` 诊断消息；`MetaEdit.kt` 的 `第N卷.cbz` **文件名模板** |
| `domain/main`（mock） | **48 处 / 2 文件** | `MockLibraryBData.kt` 40 处 + `MockData.kt` 8 处，**按裁决保留中文**（演示数据不随界面语言变） |

⇒ 三类残留**都是有意保留的**（文件名 / 开发者诊断 / 演示数据），
不是遗漏。判据：它们都**不进用户可见的界面文案**。

### 9.4 语言同步的真实语义（2026-09-25 补充，真机核对中修出的第 5 个真实缺陷）

**缺陷现象**（用户报告）：在系统设置里把本应用语言设为英文，应用内仍是中文。

**根因**：`ApplyAppLanguage` 把「存储值为默认 `System`」当成了「无需处理」，于是算出
`want = LocaleList.getEmptyLocaleList()`；而它只用 `if (manager.applicationLocales != want)`
去重，只在"值相同"时跳过 —— 系统侧已是 `[en]`，`[en] != []` ⇒ **每次启动都写一次空表**。
AOSP 对空表的定义是**一次主动写入**（原文：Pass a `LocaleList.getEmptyLocaleList()`
to reset to the system locale），于是系统侧的选择被应用抹掉，界面回退中文。

**双向性**：反向同样成立 —— 应用内选英文后，再从系统侧选简体中文，也会被应用覆盖。

**为什么不能"回读并让两者一致"**：平台**没有**语言变化监听 API（`LocaleManager` 的完整方法集
只有 `set/getApplicationLocales`、`set/getSystemLocales`、`set/getOverrideLocaleConfig`），
应用无从得知系统侧何时被改。故唯一正确姿势是**不乱写**。

**判据必须是「变化」而非「值」**：

| 场景 | 期望行为 |
| --- | --- |
| 存储值 = `System`，启动 | **不写**（默认态不代表用户意图） |
| 存储值 = `Chinese`/`English`，启动 | **必须写**（用户显式选择必须赢过系统侧） |
| 用户在应用内改选任意值（含改回 `System`） | **必须写**，改回 `System` 时写空表以真正退回系统语言 |

故**不能**把 `System` 一律跳过 —— 用户主动切回「跟随系统」时仍需写空表才能生效。
实现见 `PixFoldApp.kt` 的 `isFirstComposition` 首帧判定。

**覆盖**：`AppLanguageTest` 3 条守卫（①系统侧选择存活 ②应用内选择仍写入 ③存储里显式值必须赢）；
3 轮变异检验证明守卫非空转；真机 before/after 六条路径全部复现/通过。

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
- **完整界面文案与语言无关**（全量 i18n，2026-09-25）：界面断言不再写死任一种语言的中文串，
  一律经 `TestText`（`app/src/test/kotlin/com/pixfold/d1/TestText.kt`）从**同一份资源**解析期望值。
  ⚠️ **Robolectric 默认 locale 实测是 `en_US`**，故实际解析出的是**默认槽位 `values/`** 的英文
  —— 这正是最初 66 条断言失败的总根因，也是「测试必须语言无关」的直接论据。
  `TestText` **只用于界面文案**：文件名词缀（`第01卷.cbz`）、语言码（`ja`）、mock 数据中文串
  是**数据**不是文案，保持字面量。
  **✅ 「语言无关」已独立验证（2026-09-25）**：把整套 `app` 测试的 qualifiers 强制为
  `zh-rCN` 复跑，**304 项里 303 项通过** —— 唯一的失败是 `PixFoldTextTest` 那条以
  「`one` 变体 ≠ `other` 变体」为判据的用例（**中文侧 26 个 `<plurals>` 的 one/other 全部同值**，
  中文下该判据恒真、断言退化成恒真式），已为它显式钉死 `qualifiers = "en"`。
  ⚠️ 顺序坑：locale 限定符必须排在 `w400dp` 之类**之前**，否则 `QualifierParser` 抛异常。
- **文案解析层本身**（`ui/text/PixFoldTextTest.kt`，2026-09-25 新建，**8 条用例**）：
  直接断言 `Msg`/`TextKey` → 最终字符串的解析行为，覆盖嵌套 `Msg`、列表参数的分隔符选择、
  复数量词下标（含数量不在首位的 `NamingSummaryWithHead`）、以及"带数字但不是计数语义的键不做变体选择"。理由见 §10.5。
  ⚠️ **其中 7 条与语言无关，第 8 条（复数量词下标）刻意钉死英文** —— 它的判据依赖单复数变化，见上。
- **per-app locale 真的写进系统**（`AppLanguageTest`，2026-09-25 补）：用 Robolectric 自带的
  `ShadowLocaleManager` 直接读 `LocaleManager.applicationLocales`，断言
  ①系统侧已选 `[en]` 时启动应用**不覆盖**它（`AppLanguageTest.kt:72`，本缺陷的回归守卫）
  ②应用内改选英语时**确实写入** `[en]`
  ③存储里是显式 `English` 时启动**必须写入**（防"乱清"改成"存了不生效"）

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

**全量 i18n 的变异验证（2026-09-25 补，新增 `PixFoldTextTest` 8 条用例，5 组全部捕获）**：

解析层 `ui/text/PixFoldText.kt` 此前**零直接覆盖**（所有既有测试都经页面间接使用它）。
实测证据：把 `pluralQuantityIndex()` 的 `NamingSummaryWithHead -> 1` 改成 `-> 0` 后，
**原 295 项测试无一失败** ⇒ 该分支的登记错误**原本完全没有守护**。补测后：

| 变异 | 结果 |
| --- | --- |
| `NamingSummaryWithHead` 下标 1 → 0（**原本逃逸**） | ✅ `PixFoldTextTest` 1 项失败：`Expected <x.jpg · 1 component enabled>, actual <x.jpg · 1 components enabled>` |
| 非法字符分隔符键 → 通用列表分隔符 | ✅ 3 项失败（解析层 2 + `NamingPageTest` 1） |
| 资源里 `\u0020` → 裸空格（复现 AAPT2 trim 缺陷） | ✅ 2 项失败：`该键必须编译出单个空格(AAPT2 会 trim 裸空白,故资源里写 \u0020). Expected < >, actual <>` |
| `a_table_summary` 的 quantity 固定为 `1` | ✅ `NamingPageTest` 5 项失败 |
| `a_naming_title` 的两个 varargs 互换 | ✅ 31 项失败（`IllegalFormatConversionException: d != java.lang.String`） |

> **判据要点**：页面级**间接覆盖不能替代**对公共解析层的直接断言。
> 一个键登记错，在页面上只表现为"少个空格 / 选错变体"，间接断言几乎抓不到；
> 而参数错位却会横扫所有组合该页面的测试类 —— 两种失败模式的归因难度截然不同。
> 反过来说，第三条变异（`\u0020` → 裸空格）证明**资源文件本身也需要断言**：
> 编译期被静默 trim 的空白，只有把"编译结果"本身作为断言对象才能发现。

**语言同步修复的变异验证（2026-09-25 补,3 组全部捕获,已还原）**：

| 变异 | 结果 |
| --- | --- |
| 去掉 `if (isStartup && language == AppLanguage.System) return@LaunchedEffect`（= 退回缺陷版） | ✅ 仅「系统侧选择存活」失败 |
| 把跳过条件放宽成 `if (isStartup) return@LaunchedEffect`（首帧一律不写） | ✅ 仅「存储里显式值必须赢」失败 |
| 删掉 `manager.setApplicationLocales(want)` | ✅ 2 项失败（应用内写入 + 显式值必须赢） |

> **判据要点**：三条守卫必须**各自可被单独打掉** —— 只测「系统侧不再被清」会漏掉
> 「干脆不写」这种假修复（MUT2/MUT3 正是为此而设）。

**「色彩标准」开关的变异验证（2026-09-21 补,3 组全部捕获）**：

| 变异 | 结果 |
| --- | --- |
| `toAlgorithm()` 恒返回 `SPEC_2021`（"没接上"） | ✅ 接线断言失败：`实测仅 0/48 个角色不同` |
| V2021 / V2025 **映射对调**（"接反了"） | ✅ 计数断言**未**捕获、**方向色值断言**捕获（`2021 的主色实测值变了`） |
| 把 `TonalSpot` 塞进"应无差异"名单 | ✅ 覆盖面断言失败：`Expected <0>, actual <23>` |

**两项配套修复的变异验证（2026-09-21 补,4 组全部捕获,已还原）**：

| 变异 | 结果 |
| --- | --- |
| 删掉 19 个扩展角色映射（回到"只映射 24 个"） | ✅ 扩展角色回归用例失败 |
| 「色彩标准」行 `enabled = false`（恒置灰） | ✅ 2 项失败（受影响风格仍应可点） |
| 「色彩标准」行 `enabled = true`（从不置灰） | ✅ 1 项失败（`greyed out` 用例） |
| 把 `ColorStyle.Monochrome` 错列进 `COLOR_SPEC_SENSITIVE_STYLES` | ✅ 1 项失败（`really re-tones` 用例） |

> **判据要点**：扩展角色回归用例的判据是「**是否还等于 material3 基线色**」
> （`lightColorScheme()` / `darkColorScheme()` 的对应值），**不是**「是否随种子变化」。
> 第一版用后者时被 `surfaceContainerLowest` 浅色判失败 —— 它恒为 `#FFFFFFFF`，
> 但那是算法**正确**行为。**「与种子无关」≠「没接上」。**

### 10.6 工具链升级验证（2026-09-20，已完成）

工具链升级到最新 stable（Gradle 9.7.1 / AGP 9.4.1 / Kotlin 2.4.20 / Compose BOM 2026.09.00 /
compileSdk 37），**在临时探针分支验证通过后**才正式改：

| 验证项 | 结果 |
| --- | --- |
| 主 + 测试代码编译 | ✅ |
| **既有 461 项测试**（domain 219 + app UI 241） | ✅ **0 失败** |
| `assembleDebug` | ✅ |
| `lintDebug` | ✅ No issues found |

**为什么升级本身就要立刻跑全量测试**：这是"升级未破坏既有行为"的**唯一证据**。
若跳过它、等新功能写完再测，一旦失败将**无法区分**是工具链升级引起还是新代码写坏 —— 归因会变难。
实测耗时仅约 2 分钟，成本远低于归因困难。

详细依据（含 `compileSdk 37` 的传导链、lint 收紧、教训）见
`docs/notes/d1-toolchain-evidence.md` §8。

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

---

## 14. 实施现状与后续(2026-09-21 补,供换机续接)

**分支**:`d1/settings-impl`(未合回 `dev`)。**验证**(2026-09-21 复跑):
domain **224** + app **273** 全绿、`lintDebug` 零告警。
**真机**:设置页四组、纯黑(`#000000` 实测)、色彩风格差异、关于页、许可页、语言切英文 —— 均已核对通过。

**真机核对中修掉的真实缺陷**:许可页嵌套滚动崩溃(§12.5 铁律被违反,离线测试抓不到)、
许可页页头贴边、版本号漏改、**English 选了不生效**(只存未应用 locale)。

**后续(详见 HANGOFF §9.1 同名列)**:

1. ✅ **补 `2021 ≠ 2025` 断言(已完成 2026-09-21)** —— 见 §10.5 与 HANGOFF §9.1「第 1 步」。
   实测(**修复映射缺口之前**):两套规范在 **TonalSpot / Vibrant / Expressive / Neutral** 下
   改变 **21–24 / 48** 个角色;在 **Monochrome / Fidelity / Content / Rainbow / FruitSalad** 下
   **恒为 0**(根因:`ColorSpec2025 : ColorSpec2021()`,六个 palette 方法只重写四个变体);
   **修复后**敏感风格涨到 **36–43 / 48**。
   **断言已落** `ColorSchemesTest` 两条用例,3 组变异全部捕获。
   顺带实测出**两个新问题** —— **均已经用户裁决并修复(2026-09-21)**:
   ① 该开关对 5/9 个风格是无操作 → **设置页对该 5 个风格置灰该行**
   (`COLOR_SPEC_SENSITIVE_STYLES` 单一事实源,`ChoiceRow` 新增 `enabled` 参数);
   ② 配色只映射 24 个角色,`surfaceContainer*` / `*Fixed*` 等不随种子变化 →
   深色下卡片是 material3 基线紫灰,**色相与种子派生背景错配** → **补全 19 个扩展角色映射**,
   并给 `withPureBlack()` 补压 `surfaceBright` / `surfaceDim`
   (详见 `docs/notes/d1-ui-friction-inventory.md` 第 10、11 节)。
   **✅ 真机核对已完成（2026-09-21，用户授权后）**：深色 + 绿强调色 + 动态取色关 +
   纯黑关下,卡片实测 `#28281D`（绿调,R=G>B）、背景 `#0F0F0A` —— 与修复前恒为基线
   `#211F26`（紫调）相比,色相错配在真机上已消失;置灰行实测 `enabled="false"`
   且点击无对话框,切回敏感风格即恢复可点。核对后设备已恢复原设置。
2. ✅ **工作流内部预测性返回(中,2026-09-21 已完成)** —— 根级 private 容器提升为公共
   `app/src/main/kotlin/com/pixfold/d1/ui/components/PredictiveBackContainer.kt`,
   两个步骤容器各加 `predictiveBackAnimation: Boolean = true` 并由 `PixFoldApp` 透传
   `settings.predictiveBackAnimation`(false 时容器内退回 `BackHandler`)。
   新增 `app/src/test/kotlin/com/pixfold/d1/PredictiveBackTest.kt` 9 条,
   用 `dispatchOnBackStarted/Progressed/Cancelled` 驱动真实预测事件;
   `SemanticsNode.boundsInRoot` 实测能反映 `graphicsLayer` 缩放,故动画是否接上**离线可断言**。
   5 组变异全捕获,验证 **224+273** 全绿。**真机核对通过(2026-09-21)**:工作流 A 步骤 2 慢速
   边缘滑动中,顶层 `PrimaryTabRow` 底分隔线由 y=346/跨度 `[1,1263]` 变 y=418/跨度 `[44,1221]`,
   两轴独立推出的手势进度一致(p≈0.13 与 p≈0.69),中心停在 x≈632 —— 与绕中心
   `scaleX = scaleY = 1f - 0.10f * p` 吻合;**关掉开关**后同样滑动内容逐值不变,松手仍完成 2→1。
3. ✅ **目标页显示在背后(大,2026-09-22 已完成)** —— 原计划手写两层渲染,用户裁决改为
   **把导航整体迁移到 Navigation Compose**(`NavHost` 自己同时渲染两层、自己订阅手势进度)。
   落地:根导航改 type-safe `@Serializable` route 的 `NavHost`;两个步骤容器改**嵌套 NavHost**
   (栈深 1 时自动禁用内层返回处理器,回落免费);转场只在 NavHost 级配
   (`predictivePopEnterTransition = EnterTransition.None` 让目标页静止在背后
   + `predictivePopExitTransition` 按层级分档,开关关闭时后者退为 `None`,
   即"保留普通转场、只是不跟手");旧 `PredictiveBackContainer.kt` **整体删除**
   (与 NavHost 共用 `OnBackPressedDispatcher` 的 LIFO 队列,不能共存);
   新增 `DestinationSurface` 不透明底座。`PredictiveBackTest` 9 → 7 条
   (删 2 条直渲染旧容器的,改写 3 条 `boundsInRoot` 缩放断言),`BackNavigationTest` 6 → 8 条
   (补 2 条"反向 Tab 跳转后返回链线性"—— 该约束由变异实验发现原本无人守护)。

   **2026-09-22 追加:转场形态改为「分层」**(用户看真机录像后判定「动画有点长且生硬」)。
   用户逐字规格:工作流内部「侧面拖动越多当前页越透明然后显示上级页面」、
   大页面切换「当前页面虚化缩小」;进入动画仍瞬切(`forward_anim` = 只做返回/手势)。
   新增 `NavTransitionStyle { Step, Page }` + `PixFoldNavHost` 的第 4 个必填参数 `style`,
   三个调用点各声明一档(根图 `Page`、两个嵌套图 `Step`):
   **步骤级 = `fadeOut()`(不缩放)/ 根级 = `scaleOut(0.90f) + fadeOut()`**。
   ⚠️ 根级必须显式 `+ fadeOut()`——`scaleOut` 产物只含 scale、不含 alpha。
   ⚠️「虚化」以 alpha 近似,该 compose 版本无模糊原语。
   形态断言迁入新建 `TransitionStyleTest.kt`(3 条:`@GraphicsMode(NATIVE)` + `captureToImage` 像素对比),
   `PredictiveBackTest` **7 → 4** 条(原 3 条断言"步骤内容变窄",与新规格相反,已删)。
   骨架判据:根级 400→360 px、步骤级 99→99 px 且像素变化 28 万、开关关闭逐字节不变。

   **验证**:app **273** + domain **224** 全绿、`lintDebug` 零告警、APK 13,069,573 B;
   迁移后 6 组变异(转场两级接线 4 组 + Tab 往回 pop 2 组)均被新断言杀死。
   ✅ **2026-09-22 真机核对通过**(用户授权后实测;用户逐字「实测现在这样很不错了,后续还要改就得到走查阶段了」
   ⇒ 分层形态**定稿**,后续形态调整**推迟到走查阶段**)。完整记录见
   `docs/notes/d1-navigation-compose-migration.md`(含 F1–F13 源码级事实表、§7 实施结果与 §7.8 分层转场)。

**未决策**:D1 范围 vs MVP(设置阶段超出 D1 的六项交付,归属待用户定调)。

---

## 15. 阶段 2:全量 i18n(2026-09-25 已完成)

**用户裁决**:「只做全量 i18n,不动其他功能」。**分支**:`d1/settings-impl`(未合回 `dev`)。
**验证**:`app` **304** + `domain` **230** 全绿、`lintDebug` **零告警**、`assembleDebug` 成功。

### 15.1 架构:领域层不持有文案

```kotlin
// domain/src/main/kotlin/com/pixfold/d1/domain/text/Msg.kt
data class Msg(val key: TextKey, val args: List<Any> = emptyList())
```

`TextKey` 是扁平枚举(**104 个成员**),住在 `domain/text/TextKey.kt`。
13 个枚举的 **53 个文案字段**全部换成键:

| 枚举 | 原字段 | 现形态 |
| --- | --- | --- |
| `SortField`(6) | `label` | `val textKey: TextKey` |
| `LangChoice`(6) | `label` | `val textKey` |
| `MetaField`(7,其中 5 个本就是英文) | `label` | `val textKey` |
| `NameComponentKind`(7) / `CasePolicy`(3) / `ExtPolicy`(4) | `label` | `val textKey` |
| `ProposalWarningType`(5) | `label` | `val textKey`(消息体外提为 `ProposalWarning(val type, val message: Msg)`) |
| `OpStatus`(2) / `ExecOutcome`(3) / `ExecKind`(2) | `label`/`title` | `val textKey` / `val titleKey` |
| `OutputConflictPolicy`(3) / `SourcePolicy`(2) | `label` | `val textKey` |
| `DangerAction`(3 成员 × 3 字段) | `title`/`body`/`button` | `val labelKey` / `val bodyKey` / `val buttonKey` |

**为什么选「键 + 参数」而不是另两条路**:
- **B. 向领域层注入 `TextResolver`** —— 仍要同样的 53 字段手术,且其 ~60 个方法会在每次文案变更时
  波及编译;更关键的是**没解决冻结问题**。
- **C. 领域层继续产出中文** —— 在「测试全部语言无关」的裁决下不成立:
  ~170 条断言会退化成恒真式,且 `DangerAction` 的确认文案**永远无法翻译**。
- **A(选它)的决定性理由**:**语言不该在建计划的那一刻被冻住**。
  若 `Msg` 存成品字符串,切语言后**已经显示出来的对象仍是旧语言**,须重新扫描才更新。
  领域层无 `Context` 是直接约束,但**不是**决定性理由。

**关键取舍**:`Suggestion.source` 保持 `String`;`groupVolumesBySeries` 的分组键保持
**语言无关的 `String`**(未命名 = 空串,界面映射为 `TextKey.UnnamedSeries`)——
若用翻译后的键,**切语言会把同一系列拆成两组**,且该键要喂 `testTag`;
`ExecEntry.label` 保持 `String`(它是**文件名**,8 处构造点全传 `cbzFileName`/`finalName`);
`defaultCbzName()` 与 `require{}` 消息保持中文(磁盘文件名 / 开发者诊断)。

### 15.2 解析发生在渲染时

`app/src/main/kotlin/com/pixfold/d1/ui/text/PixFoldText.kt`:
- `@Composable fun textOf(msg: Msg): String` / `textOf(key: TextKey, vararg args)` / `textOfOrNull(msg: Msg?)`
- **非 Composable** 版 `resolveText(context, msg|key, args)`(测试与 Composable 共用**同一实现**)
- 私有 `resId(key)` 是**唯一**的键→资源 id 映射(穷尽 `when`,无 `else`,新增键必然编译失败)
- 私有 `pluralQuantityIndex(key)` 登记**数量不在首位**的计数键(目前仅 `NamingSummaryWithHead` 返回 1)

⚠️ 两条编码规则(踩过坑):
① `stringResource`/`textOf` 是 `@Composable`,**不能**在 `semantics {}`、`onClick` 等非组合 lambda 内调用 —— 先提到 `val`;
② `List.map { }` 的变换体若需要 `@Composable`,**必须内联**(`joinToString` 的 transform 是普通 lambda,不行)。

### 15.3 资源与 `<plurals>` 的硬规则

6 组中英对照资源,**370 个中文键 / 364 个英文键**;**26 个 `<plurals>`** 两侧类型严格一致。

1. **资源类型不能跨语言不同** —— 计数键必须中英**同时**用 `<plurals>`,
   否则 lint `MissingTranslation`/`ExtraTranslation` 硬失败。中文侧 one/other **故意同值**(中文无变格)。
2. **`pluralStringResource(id, count, ...)` 的 `count` 单独传 quantity**;`%1$d` 恰为 quantity 时
   形态是 `pluralStringResource(R.plurals.k, n, n, ...rest)`(**n 传两次**)。

**AAPT2 会 trim 资源首尾空白**(实测确证):`<string name="k"> </string>` 编译出来是**空串**
(`len=0`),英文列表渲染成 `A,B`(应为 `A, B`)。**存活写法 `\u0020`**(实测 `len=1 hex=[32]`)。
全量普查后修掉 **14 处**(中英两侧)。⚠️ 该缺陷**只在切换英文后可见**,中文侧用 `、` 不受影响。

### 15.4 lint 零告警(`warningsAsErrors = true` ⇒ 必须归零)

29 条 error 全部消解:真阳性 `PluralsCandidate` **18 键改 `<plurals>`**(抑制会掩盖该规则要抓的缺陷);
假阳性 3 键(`should`/`down`/`skipped` 不是名词)`tools:ignore`;
`UnusedResources` 4 条删死键;`TypographyDashes` 2 键(`ISO 639-1` 是标准专名)`tools:ignore`。
**本次首次引入 `xmlns:tools`**(仓库此前无任何豁免先例)。

### 15.5 测试策略与残留

界面断言一律经 `TestText`(`app/src/test/kotlin/com/pixfold/d1/TestText.kt`)从**同一份资源**解析期望值。
⚠️ **Robolectric 默认 locale 实测是 `en_US`** ⇒ 实际解析**默认槽位 `values/`**(英文),这是最初 66 条失败的总根因,
也是「测试必须语言无关」的直接论据。领域层测试改为断言 `Msg` 的**结构**(`keys()` / `line(key)` 辅助)。

残留(全部**有意保留**,均不进用户可见界面):`app/main` **1 处**(`SchemeEditor.kt:365` 示例输入)、
`domain/main` 非 mock **5 处**(`require{}` + `第N卷.cbz`)、mock **48 处**(按裁决保留中文)。

新增 `PixFoldTextTest`(**8 条**,专测解析层)—— 解析层此前**零直接覆盖**。
实测:把 `pluralQuantityIndex()` 的 `NamingSummaryWithHead -> 1` 改成 `-> 0`,
**原 295 项测试无一失败**;补测后该变异立即被捕获。变异明细见 §10.5。

**✅ 「语言无关」的独立验证(2026-09-25)**:上述语言无关性此前**只在 Robolectric 默认
locale(`en_US`)下验证过** —— 若某条断言误写成英文字面量而非经 `TestText` 读资源,
它在 `en_US` 下照样通过、换语言即失效。故临时把 40 处 `@Config` 的 qualifiers 改为
`zh-rCN` 复跑。**第一轮**结果:**304 项里 303 项通过**,唯一失败的是「复数量词下标」那条 ——
它原本以「`one` 变体 ≠ `other` 变体」为判据,而**中文侧 26 个 `<plurals>` 的 one/other
全部同值**(中文没有复数变化),中文下该判据恒真、断言退化成恒真式 ⇒ 它其实
**借英文的复数变化才成立**,已为它显式钉死 `@Config(qualifiers = "en")` 并写明原因。
**钉死之后第二轮**(保留钉死位、其余 31 个文件仍强制 `zh-rCN`)结果:**304 项全部通过、
0 失败** —— 这同时证明了两件事:其余 303 条确实与语言无关(在 `en_US` 与 `zh-rCN` 下
都通过),而那条钉死确实生效(否则它会在中文下失败)。实验已完整回滚
(残余 `zh-rCN` = 0;**仅保留那一处刻意的 `qualifiers = "en"`**;`git diff --numstat`
聚合 `ins=1874 del=739` 与实验前逐字一致)。

> ⚠️ **两条可复用的推论**:①**资源限定符有顺序要求**,locale 必须排在 `w400dp` 之类之前,
> 否则 `QualifierParser` 抛 `IllegalArgumentException`(第一轮 227 项失败即此因,
> 属实验脚手架 bug,不是测试缺陷);②本仓库所有中英计数键的**中文侧 one/other 同值**,
> 故**凡以「单复数变化」为判据的断言都必须钉死英文**(或钉死某个真有复数形式的语言),
> 反之以「中文侧差异」为判据的断言在中文下必然是恒真式。

### 15.6 英文真机冒烟走查(2026-09-25)

离线四道闸门全绿**不等于**英文界面正确 —— Robolectric 的 `en_US` 只证明解析层用对了资源,
不证明资源内容本身合理、也不证明页面里每处文案都被替换。故用户授权安装后,
把应用内 locale 切到 `en` 逐页 dump,覆盖 A 全流程(排序 → 命名 → 执行计划 → 普通确认框 →
危险确认框 → 报告)与 B 全流程(库 → 元数据 → 打包)共 **10 个界面**,全部渲染正确
(逐步摘录见 HANGOFF §9.1「✅ 全量 i18n」节的走查表)。

三条只有真机能确认的结论:

1. **英文复数变体确实生效** —— `30 images` / `5 volumes` / `56 items` / `16 pages` /
   `2 components enabled` 全取 `other` 形,`1 component enabled` 取 `one` 形。
   离线单测只断言"解析层用对了下标",**变体文本本身对错只能看真机**。
2. **中文数据串按预期保留** —— `旅行照片` / `进击的巨人` / `谏山创` /
   `目录层级 L2（系列层）` / `第01卷` 在英文界面下原样出现,符合 `scope_mock = A`
   与「文件名词缀 / 来源说明是数据而非文案」的裁决。
3. **`\u0020` 修法在真机成立** —— ` · current`、` / `、`To confirm: ` 三处均带空格。

顺带消除一条旧限制:本文档与 HANGOFF 此前记「主界面 30 个 UI 文件 `stringResource`
命中数为 0、`ui/` 非 settings 目录 44 行硬编码中文」(属阶段 1 的已知不完整)——
**全量 i18n 后已不成立**,首页本身就是英文的(`HomeScreen.kt` 已改)。

> ⚠️ **操作坑(可复用)**:`cmd locale set-app-locales <pkg> --user 0 en` **静默无效** ——
> 位置参数被忽略,且因 `--locales` 未指定反而**把应用 locale 重置为空表**,现象酷似"命令没生效"。
> 正确写法必须带旗标:`cmd locale set-app-locales <pkg> --user 0 --locales en`;
> 复位成"跟随系统"则不带 `--locales`。

### 15.7 资源兜底语言:英文(2026-09-25 用户裁决)

**现状布局**:`values/` = **英文(默认/兜底)**、`values-zh/` = 中文(覆盖),
**没有 `values-en/`**。因此:系统语言为中文时走 `values-zh/`;为英文或**日语/韩语/法语等
第三语言时回退到英文**。

**用户裁决逐字**:「那还是改成英文兜底，默认选项是跟随系统一般也用不上兜底就是了」。

**沿革**:本裁决**推翻了同日的上一版**「中文兜底」裁决(逐字「中文兜底吧，毕竟主要就是我自用的」)。
用户自己给出的反转理由:**默认选项是「跟随系统」,一般也用不上兜底** ⇒ 兜底几乎只在
"系统语言既非中文也非英文"时可见,那种场合英文比中文更有用;附带效果是回到 Android
惯例布局(英文进默认 `values/`)。

> ⚠️ **`values-zh/` 里不能出现 `one` 档**(相对上一版裁决新增的约束):中文一旦放进
> 带语言限定符的目录,lint 的 **`UnusedQuantity`** 就会指出「For language "zh" (Chinese)
> the following quantities are not relevant: one」,在 `warningsAsErrors = true` 下**硬失败**。
> 故中文侧 26 个 `<plurals>` **只写 `other`**,英文侧则 one/other 两档都要写。
> (上一版裁决时中文在默认槽位,lint 不知道它是中文,故两边写同值也能过。)

> ⚠️ **`translatable="false"` 的键只留在默认 `values/`**:5 个
> `domain_meta_field_{title,series,writer,volume,language}` 与 `app_name`;
> 它们**不得**出现在 `values-zh/`,否则 lint 报 `ExtraTranslation`。

> ⚠️ **兜底方向本身四道闸门全都看不见** —— lint 只看键集合是否对齐,单测只看解析值
> 是否等于同一份资源里的值,两者都区分不出"默认槽位装的是哪种语言"。把 `values/`
> 与翻译目录对调,这些检查**全部照旧通过**而真机表现已反。故新增
> `app/src/test/kotlin/com/pixfold/d1/ResourceFallbackTest.kt`(5 条)**直接钉住
> "哪种 locale 取到哪份文案"** —— 它是全仓唯一刻意写死中英字面量的测试,与 §15.5
> "语言无关"纪律不冲突:那一套保证换语言不失效,这一组反过来验证换语言**确实**换文案。

**附带澄清一条易误读的因果链**:`applicationLocales = []` **不等于中文**,而是
**"不覆盖,跟随系统"**。它显示中文仅因 `getprop persist.sys.locale` = `zh-CN`;
系统若为英文,同一个 `[]` 会走默认 `values/`(英文)。故设备复位后的正确说法是
「**复位为跟随系统,而系统恰好是中文**」,而非"复位为中文"。

