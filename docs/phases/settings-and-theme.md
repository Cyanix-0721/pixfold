# 设置与主题体系

> **来源**：`HANGOFF.md` 原有 §9 内容，原行号 L1313–L1372, L1373–L1603, L1604–L1632, L1686–L2034（2026-10-23 迁出）。
> **内容**：设置页 / 主题体系 / 启动图标 / i18n 与本轮实施留痕。
> **性质**：实现期逐轮留痕，只查不常读。**产品决策仍以 `HANGOFF.md` 为唯一权威**，本文件是其详版。
> **索引**：见 [`docs/INDEX.md`](../INDEX.md)。

<!-- 原 HANGOFF.md L1313–L1372 -->

## ⏭️ 设置与主题体系 —— 现状与后续(2026-09-21 记录,供换机续接)

> **分支**:`d1/settings-impl`(当时记 27 个提交自 `dev` 起算;**已于 2026-09-25 合回 `dev`** ——
> merge `7a6f678`,自 `b5a048c` 起算最终 **32** 个提交,本地分支已删除。`dev` 领先
> `origin/dev` 若干提交(确切数目见 `git rev-list --count origin/dev..dev`,**本处不硬编码
> 以免每次提交后失效**),均未 push —— push 由用户处理)。
> **工作区干净**。启动图标主题化 + minSdk 24→30 **已提交**(`2ba10f8`,见本章
> 「✅ 启动图标 MD 主题化 + minSdk 提到 30」)。规格:`docs/superpowers/specs/2026-09-20-d1-settings-theme-design.md`。
> **最新验证(2026-09-25 复跑,`minSdk=34` 终态)**:`domain:test` **229** +
> `app:testDebugUnitTest` **292** 全绿、`app:lintDebug` **`No issues found.`**、
> `assembleDebug` 成功(APK **33,046,763 B**)。(数字演进:224/278 @09-22 →
> 229/280 @09-24 P0 → 229/282 @09-25 底栏化 → 229/287 @09-25 底栏分槽 →
> 229/291 @09-25 像素守卫 → 229/292 @09-26 深色撤销守卫。)
> **本轮相关提交**:`1c95db9`(底栏视觉规格 + 撤销危险红,19 files +1592/−201)、
> `f3cfb08`(minSdk 30→34,5 files +56/−21)、`2a96acc`(文档同步)。

**已交付并真机核对通过**:

| 项 | 真机结论 |
| --- | --- |
| 设置页四组(外观/语言/行为/关于) | ✅ 渲染正常,361dp 窄屏不拥挤 |
| 主题三态 / 动态取色 / 强调色 | ✅ 可切换;动态取色默认开、强调色默认跟随系统 |
| **纯黑背景** | ✅ **真 `#000000`**(开关前后截图对比:深灰紫 → 纯黑),分隔线清晰可辨 |
| **色彩风格(9 种)** | ✅ **有可见变化** |
| 返回手势跟随动画开关 | ✅ 可见、默认开 |
| 关于页 | ✅ 版本/仓库/发布页/作者/开源许可/恢复默认 齐全 |
| **开源许可页** | ✅ AboutLibraries 真实数据渲染(三件套 + Activity 等,均 Apache-2.0)+ 许可更正块 |
| **语言切 English** | ✅ 用户真机确认设置页变英文(首页仍中文 —— 阶段 2 才补全,页面内已如实标注) |

**真机核对中修掉的真实缺陷(5 个)**:

1. **许可页一进即崩**(真缺陷):`LibrariesContainer`(本身是 LazyColumn)被嵌进
   `Column(verticalScroll)` → 无界高度 → `IllegalStateException`。
   **这违反了项目 §12.5 自己记录的布局铁律**,且**离线测试完全抓不到**(无覆盖 + 不构建即不崩)。
2. **许可页页头贴左缘**:改用 header 槽后丢了外层 padding;且"许可更正"块曾是带内边距
   Column 的兄弟节点。→ 整块并入同一带内边距 Column。
3. **版本号漏改**:显示 `1.0 (1)`,方案定的是 `0.1.0-dev`。→ 已改 `versionName`。
4. **English 选了界面不变**(真缺陷):`grep LocaleManager|setApplicationLocales` **零命中** ——
   设置页只做了"存",**从未应用** per-app locale。→ 新增 `ApplyAppLanguage`
   (`LocaleManager.setApplicationLocales`,API 33+)。
5. **系统侧给应用设为英文后,应用内仍是中文**(真缺陷,第 4 条修复的**反向过冲**):
   `ApplyAppLanguage` 在存储值为默认 `System` 时算出 `want = LocaleList.getEmptyLocaleList()`,
   而 `if (manager.applicationLocales != want)` 只在"值不同"时跳过 —— 系统侧 `[en] != []`
   ⇒ **每次启动都写一次空表**。而 AOSP 明确定义空表是「**重置为系统语言**」
   (原文:Pass a `LocaleList.getEmptyLocaleList()` to reset to the system locale),
   于是系统侧的选择被应用**主动抹掉**,界面回退中文。双向同病:应用内选英文后再从系统侧选简体,同样被覆盖。
   → **判据必须是「变化」而非「值」**:只在设置值**发生跳变**时写,启动首帧对默认 `System` 不写;
   但用户主动从英文切回「跟随系统」仍须写空表(否则退不回系统语言)。
   实现 = `PixFoldApp.kt` 的 `isFirstComposition` + `if (isStartup && language == AppLanguage.System) return@LaunchedEffect`。
   **离线性**:平台**没有**语言变化监听 API(`LocaleManager` 全部方法只有 set/get applicationLocales、
   set/get systemLocales、set/get overrideLocaleConfig),故无法"回读并让两者一致",只能靠不乱写。
   补 `AppLanguageTest`(3 条守卫:系统侧选择存活 / 应用内选择仍写入 / 存储里显式值必须赢),
   并做 3 轮变异检验证明守卫非空转(MUT1 退回缺陷版 ⇒ 仅守卫①红;MUT2 首帧一律不写 ⇒ 仅守卫③红;
   MUT3 删掉 `setApplicationLocales` ⇒ 守卫②③红)。真机 before/after 六条路径全部复现/通过。

**另一处 UX 修正**:强调色里的 `Default` 与「跟随系统」语义撞车(用户提问),
→ 改名 **「靛蓝」Indigo**(它本就是内置靛蓝种子 `0xFF3F51B5`)。

---


<!-- 原 HANGOFF.md L1373–L1603 -->

### ⏭️ 后续三步(按性价比排序,用户 2026-09-21 指定记录) —— **三步全部完成(第 3 步于 2026-09-22 以「迁移 Navigation Compose」方式完成,见 `docs/notes/d1-navigation-compose-migration.md`)**

**第 1 步(小,先做):补 `2021 ≠ 2025` 断言 —— ✅ 已完成(2026-09-21)**

- **背景**:用户真机反馈「2021/2025 变化微小」。而 `ColorSchemesTest` 当时
  **只断言了"不同色彩风格产出不同主色",没有断言 "2021 ≠ 2025"** ——
  故**无法排除"spec 参数根本没接上"**。这是真实覆盖缺口。
- **做法**:先写**临时探针**量化(不凭猜写断言),再落成断言:
  `app/src/test/kotlin/com/pixfold/d1/ui/theme/ColorSchemesTest.kt` 新增两条用例
  —— `color spec version really re-tones the scheme`(**接线验证** + 方向钉死)
  与 `color spec version is a no-op for the five styles it does not re-tone`(**覆盖面记录**)。

**实测数据**(探针:6 个种子 × 深浅 × 9 风格,逐项比较 **48 个** `ColorScheme` 角色;
**下表为 2026-09-21 修复映射缺口之前**的值 —— 修复后敏感风格涨到 **36–43/48**,
原因见本节末「问题 B」):

| 色彩风格 | 2021↔2025 变值角色数(最小 / 最大) |
| --- | --- |
| **TonalSpot / Vibrant / Expressive / Neutral** | **21 / 24**(4 个变体确实被重写) |
| Monochrome / Fidelity / Content / Rainbow / FruitSalad | **0 / 0**(逐字节相同) |

> **根因(库源码,一手)**:`ColorSpec2025 : ColorSpec2021()`,其
> `getPrimaryPalette` / `getSecondaryPalette` / `getTertiaryPalette` / `getNeutralPalette` /
> `getNeutralVariantPalette` / `getErrorPalette` **只对 NEUTRAL / TONAL_SPOT / EXPRESSIVE /
> VIBRANT 四个变体重写,其余分支一律 `else -> super`**。故 5 个风格下两套规范必然等价。

**结论**:用户感觉"变化微小"**是正常的**,不是没接上 —— 但"没接上"这一可能性此前
**确实未被任何断言排除**,补齐是必要的。**断言下界取 10**(实测最小 21,留算法微调余量;
"没接上"恒为 0 必被抓住)。

**变异验证(3 组,均捕获)**:

| 变异 | 预期 | 实测 |
| --- | --- | --- |
| ① `toAlgorithm()` 恒返回 `SPEC_2021`(即"没接上") | 接线断言失败 | ✅ **1 项失败**:`实测仅 0/48 个角色不同` |
| ② V2021/V2025 **映射对调**(即"接反了") | 计数断言通过、**方向断言**失败 | ✅ **1 项失败**:`2021 的主色实测值变了`(计数断言确实未能捕获该变异 —— 这正是钉死方向色值的理由) |
| ③ 把 `TonalSpot` 塞进"应无差异"名单 | 覆盖面断言失败 | ✅ **1 项失败**:`Expected <0>, actual <23>` |

**⚠️ 第 1 步顺带实测出的两个新问题 —— 已由用户裁决并修复(2026-09-21)**

**问题 A:「色彩标准」对 5 / 9 个风格是无操作开关。**
设置页把它做成全局开关,但上表已证明选中 Monochrome / Fidelity / Content / Rainbow /
FruitSalad 时,切换 2021↔2025 **不改变任何颜色、界面无任何反馈**。

> **✅ 用户 2026-09-21 裁决:置灰**(候选 c)。
> **实现**:`ColorSchemes.COLOR_SPEC_SENSITIVE_STYLES` 作为**单一事实源**列出 4 个
> 真正受影响的风格;设置页据此给该行传 `enabled = false`(整行置灰 + 不可点开)。
> **置灰而非隐藏**:值仍可见,用户能看出"我设过 2025,只是当前风格下不生效",
> 不会以为设置丢了。选择保留不重置 —— 切回受影响的风格即自动生效。
> **未加说明文字**:候选 (b) 的文案未采纳(用户只选了置灰);
> 代价是"为什么灰了"没有文字解释 —— 若日后觉得费解,再补一句即可。
>
> **✅ 真机核对(2026-09-21)**:选「单色」后,「色彩标准」行的语义容器实测为
> `clickable="true" enabled="false"`(`bounds=[42,1687][1222,1855]`),
> **点它没有任何对话框弹出**;切回「标准」后同一容器变 `enabled="true"`,
> 再点即弹出对话框(含 `Material 3 (2021)` / `Material 3 Expressive (2025)` 两项)。
> 值 `Material 3 Expressive (2025)` **在置灰态下仍可见**(未重置、未隐藏)。

**问题 B(更值得修):配色映射只覆盖约 24 个角色,扩展角色不随种子色变化。**
`ColorSchemes.toColorScheme()` 用 `base.copy(...)` 只覆盖了 24 个角色,
`surfaceBright` / `surfaceDim` / `surfaceContainerLowest/Low/High/Highest` /
`*Fixed*` **不在其中**,故恒为 **material3 基线常量**。

实测(种子 `#3F51B5` 靛蓝 vs `#2E7D32` 绿,**修复前**这些角色取值完全相同):

| 角色 | 浅色 | 深色 |
| --- | --- | --- |
| `surfaceContainer` | `#F3EDF7`(基线紫灰) | `#211F26`(基线紫灰) |
| `surfaceContainerHighest` | `#E6E0E9` | `#36343B` |
| `primaryFixed` | `#EADDFF`(基线淡紫) | `#EADDFF`(**深色下仍是淡紫**) |

**后果**:非靛蓝强调色下,`surface`(经种子派生,如绿色中性)**与** `surfaceContainer*`
(基线紫灰)**色相不一致** —— 即"背景对了、卡片还是 M3 基线紫"。深色下更明显,
这正是**「纯黑背景」开关之所以能救场的原因**:`withPureBlack()` 把这些角色一并压黑,
错配被掩盖;但只要用户不开纯黑(用户本人偏好开),深色下就是"深底 + 基线紫灰卡"。
`*Fixed*` 系列在深色下保持浅紫,若将来用到固定色角色会明显偏色。

> **✅ 用户 2026-09-21 裁决:修**(候选 a)。**已实现**:
> 把 **19 个扩展角色**纳入 `toColorScheme()` 的 `copy(...)`
> (7 个 surface 档 + 12 个 `*Fixed*`),取值一律来自算法输出而非 `base`。
> 连带:`withPureBlack()` 补上 `surfaceBright` / `surfaceDim` ——
> 二者补全映射后会随种子色变化,若不在纯黑时一并压掉,深色纯黑下又会冒出非黑浅色表面。
>
> **修复后实测**(同一探针,靛蓝 vs 绿,现已**不同**):
>
> | 角色 | 靛蓝 | 绿 |
> | --- | --- | --- |
> | 浅色 `surfaceContainer` | `#EFEDF4` | `#ECEFE6` |
> | 深色 `surfaceContainer` | `#1F1F25` | `#1D211B` |
> | 浅色 `primaryFixed` | `#DEE0FF` | `#BCF0B4` |
> | 深色 `primaryFixed` | `#DEE0FF` | `#BCF0B4`(仍跨深浅一致,但**已随种子**) |
>
> **连带效应**:两套规范的差异面**从 21–24/48 涨到 36–43/48**
> (因为 19 个角色不再恒定)。**数字变大不是断言松了,是缺陷少了** ——
> 这本身也反证了"差异小"曾部分源于漏映射。
>
> **回归护栏**:`surface and fixed roles come from the algorithm, not the material3 baseline`
> —— 判据是"**是否还等于 material3 基线色**"而不是"是否随种子变化"。
> 第一版用后者时被 `surfaceContainerLowest` 浅色(恒 `#FFFFFFFF`)判失败,
> 但那是算法**正确**行为(该角色取中性色板 tone 100),**"与种子无关"不等于"没接上"**。

**✅ 真机核对已完成(2026-09-21,用户授权后执行)**

条件:深色 + 强调色「绿」+ 动态取色关 + 纯黑关 + 色彩风格「标准」+ 色彩标准 2025
(即**修复前必然暴露错配**的组合)。取原始帧缓冲逐点采样:

| 取样点 | 修复前(预期) | **实测** |
| --- | --- | --- |
| 背景 | 种子派生(绿调) | `#0F0F0A`(绿调) |
| 卡片(工作流 A / B) | `#211F26`(material3 基线紫灰,B>R) | **`#28281D`(绿调,R=G>B)** |

**结论**:卡片已随种子色派生,与背景**同色相** —— 修复前"背景对了、卡片还是 M3 基线紫"
的错配在真机上**已消失**。纯黑模式未受影响(仍为真 `#000000`,已由单测与既往真机核对覆盖)。

**第 2 层(UI 断言)与第 3 层(真机实证)均已过 —— 四层自证闭环。**

**第 4 层「操作逻辑符合性走查」(2026-09-21,逐条核过)**:

| 走查点 | 结论 |
| --- | --- |
| 置灰是否波及其他设置行 | 无。`ChoiceRow` 的 `enabled` 默认 `true`,只有「色彩标准」行显式传值 |
| 置灰会不会丢掉用户已存的 2025 | 不会。只禁点击,**不写值**;切回敏感风格即自动生效,无需重设 |
| 「恢复默认」后是否卡在置灰 | 不会。重置后色彩风格回到默认(TonalSpot,属敏感集),该行自动恢复可点 |
| 纯黑开关 × 新增扩展角色 | 一致。`withPureBlack()` 在 `toColorScheme()` 之后以 `copy` 覆盖,新增的 `surfaceBright`/`surfaceDim` 已并入压黑清单,不再冒出非黑浅色面 |
| 深浅切换是否互相污染 | 无。每次由 `DynamicScheme` 重新派生;`*Fixed*` 深浅同值属 M3 规范行为,且已**随种子** |
| error 族是否被误伤 | 未动(`base.error` 等仍走 material3 默认),产品约束 5「破坏性动作必须醒目」对应的单测仍全绿 |
| 动态取色 × 扩展角色 | 一致。19 个角色与原有 24 个同取 `DynamicScheme` getter,不存在两套来源 |

> **一处诚实的取舍**:用户只选了「置灰」、未选「加说明文字」,故"**为什么**灰了"
> 没有文字解释。判定为可接受 —— 值与灰态同时可见,已能表达"设过但目前不生效"。

> **一个取证教训(值得记)**:本次第一版探针给出的是**相反结论**("2021 与 2025 逐字节相同")。
> 原因是我用 `Color.value.toInt()` 取色值 —— `Color.value` 是 `ULong`,**低 32 位是色彩空间 id
> 而不是 ARGB**,任何两个 sRGB 颜色都会被算成相等。**按颜色值断言必须用 `toArgb()`**;
> 该陷阱已写进 `ColorSchemesTest.argbRoles` 的文档注释。**先验证度量本身,再信度量结论。**

**第 2 步(中):让预测性返回在**工作流内部**也生效 —— ✅ 已完成(2026-09-21)**

- **现状(改前)**:根级 `PredictiveBackContainer`(`PixFoldApp.kt`)在**设置/许可/预览**等页有效;
  但**工作流内部(步骤切换)无效**。
- **根因(已实证)**:`WorkflowASteps` / `WorkflowBSteps` 自己注册了 `BackHandler`,
  `OnBackInvokedDispatcher` **取最后注册者**,故步骤容器的 `BackHandler` 优先级高于
  根级 `PredictiveBackHandler` —— 步骤级返回走的是普通返回,无手势跟随动画。
- **做法(已落地)**:把根级那个 private 容器提升为**公共共享组件**
  `app/src/main/kotlin/com/pixfold/d1/ui/components/PredictiveBackContainer.kt`
  (三处共用同一实现,避免动画参数各写一份而漂移),并给两个步骤容器加参数
  `predictiveBackAnimation: Boolean = true` —— 由 `PixFoldApp` 传
  `settings.predictiveBackAnimation`;容器内 `false` 时自动退回 `BackHandler`。
- **改动文件**:`ui/components/PredictiveBackContainer.kt`(新建)、`PixFoldApp.kt`
  (删 private 副本、两处透传)、`ui/workflowa/WorkflowASteps.kt`、`ui/workflowb/WorkflowBSteps.kt`。
- **UI 断言**:新增 `app/src/test/kotlin/com/pixfold/d1/PredictiveBackTest.kt`(9 条)。
  用 `activity.onBackPressedDispatcher.dispatchOnBackStarted/Progressed/Cancelled` +
  `onBackPressed()` 驱动**与系统手势同一条链路**的预测事件(activity 1.13.0 已反编译确认这些方法是 public)。
  **关键取证**:`SemanticsNode.boundsInRoot` **会反映 `graphicsLayer` 缩放** ——
  满屏 400×1000 在 progress=1 时变 `Rect.fromLTRB(20,50,380,950)`(正好 ×0.9),
  故"动画有没有接上"这次**在离线测试里可断言**(此前只能靠真机走查)。
  其中 3 条是**接线变异敏感**断言(直接观察步骤内容 bounds 是否缩放),专抓
  "把开关写死/参数没透传"这类会**静默丢掉动画**的回归。
- **变异验证(5 组全捕获)**:① 工作流 A `enabled` 写死 true → 1 项失败;
  ② 写死 false → 1 项失败;③ 工作流 B 写死 false → 1 项失败;
  ④ 丢掉 `stepIndex -= 1`(不再逐级后退)→ 4 项失败;⑤ 去掉容器 `graphicsLayer` 进度变换 → 3 项失败。
- **全量验证**:`domain:test` **224** + `app:testDebugUnitTest` **273** 全绿、`assembleDebug` 成功、
  `lintDebug` 零告警。
- **⚠️ 离线断言不到的边界**:断言的是**变换确实随进度发生**(bounds 变了),
  **不是"动画看起来舒服"**——缩放系数 0.9 的观感改由**真机核对**(见下)。
  另:被断言的是根坐标矩形,若日后改了缩放实现(如改用平移),断言需同步调整。
- **真机核对(✅ 2026-09-21,用户授权后执行)**:同一份构建,在**工作流 A / 步骤 2** 上做慢速
  边缘滑动(`adb shell input swipe 1 1390 900 1390 3000`),**滑动期间**连续抓原始帧缓冲
  (`adb exec-out screencap`),量取页面顶部 `PrimaryTabRow` **底分隔线**(横贯内容宽度的亮线)
  的位置与横向跨度。整个工作流页面(含 TabRow)都在 `PredictiveBackContainer` 内,故这条线
  就是变换的探针:

  | 帧 | 分隔线 y | x 跨度 | scale_x | scale_y | 由 x 推 p | 由 y 推 p |
  | --- | --- | --- | --- | --- | --- | --- |
  | 静止 | 346 | `[1,1263]` | 0.9984 | 1.0010 | 0.016 | −0.01 |
  | 手势中(早) | 360 | `[9,1256]` | 0.9866 | 0.9875 | 0.134 | 0.125 |
  | 手势中(中) | 418 | `[44,1221]` | 0.9312 | 0.9319 | 0.688 | 0.681 |

  两轴**独立**推出的进度一致(0.13 / 0.69,差 ≤1%),且分隔线中心始终停在 `x≈632`(=屏幕中心),
  与 `graphicsLayer { scaleX = scaleY = 1f - 0.10f * p }`(绕中心缩放)完全吻合。
- **A/B 对照(同一份构建,只切设置开关)**:把「返回手势跟随动画」**关掉**后再做同样的滑动,
  手势中两帧的分隔线**仍是 y=346、跨度 `[1,1263]`、亮度 73.7(与静止帧逐值相同)** ——
  内容纹丝不动,而松手后仍完成 2→1 的层级后退(`BackHandler` 兜底路径依旧可达)。
  开/关两种行为在同一台设备、同一份构建上被区分开,**排除了"动画是系统自带"的解释**。
- **取证说明**:合成滑动确实触发了系统边缘返回手势(旁证:工作流步骤 2→1 完成);
  进度值 p 由几何**反推**(x/y 两轴自洽),不是日志读数。

**第 3 步(大):让**目标页**显示在背后(真正的预测性返回观感)—— ✅ 已完成(2026-09-22)**

> **✅ 2026-09-22 完成:本步已通过「把导航整体迁移到 Navigation Compose」实现**(用户裁决)。
> `NavHost` 自己同时渲染两层(目标页画在 `initialZIndex - 1f`)、自己订阅手势进度。
> 落地要点:根导航改 type-safe `@Serializable` route;两个步骤容器改**嵌套 NavHost**
> (栈深 1 时自动禁用内层返回处理器,回落免费);转场只在 NavHost 级配
> (`predictivePopEnterTransition = None` 让目标页静止在背后 + `predictivePopExitTransition` 按层级分档,
> 开关关闭时后者退为 `None`);旧 `PredictiveBackContainer` **整体删除**;
> 新增 `DestinationSurface` 不透明底座。**app 273 + domain 224 全绿、lint 零告警、6 组变异均被杀死。**
>
> **✅ 2026-09-22 追加:返回转场改为「分层」**(用户看完真机录像后判定「动画有点长且生硬」,
> 并给出两条分开的规格 —— 见 `docs/notes/d1-navigation-compose-migration.md` §7.8)。
> 用户逐字规格:工作流内部「**侧面拖动越多当前页越透明然后显示上级页面**」、
> 大页面切换「**当前页面虚化缩小**」;进入动画维持瞬切(`forward_anim` = 只做返回/手势)。
> 落地:新增 `NavTransitionStyle { Step, Page }`,`PixFoldNavHost` 多一个必填 `style` 参数,
> 三个调用点各声明一档(根图 `Page`、两个嵌套图 `Step`);
> **步骤级 = `fadeOut()`(不缩放,上级页原样露出)/ 根级 = `scaleOut(0.90f) + fadeOut()`**。
> ⚠️ **根级必须显式 `+ fadeOut()`**:compose animation 1.12.1 的 `scaleOut` 产物**只有** scale、
> **不含 alpha**(`EnterExitTransition.kt:447`),漏掉就退化成"只缩小不虚化",而**只看宽度与像素变化的断言抓不住**。
> ⚠️ **「虚化」只能是透明度,不是模糊** —— 该版本 `grep renderEffect|BlurEffect` 零命中,
> `ExitTransition` 无模糊原语,故一律以 alpha 近似(如实登记,勿当 bug 追)。
> 形态断言迁入新建 `TransitionStyleTest.kt`(3 条,判别式 = 几何 + 像素 + "满进度画面 ≈ 落地画面"),
> `PredictiveBackTest` 7 → 4 条(其原 3 条用 `boundsInRoot` 断言**步骤内容变窄**,与新规格直接相反)。
> 骨架判据:根级 400 → 360 px(恰 `PREDICTIVE_MIN_SCALE`)、步骤级 99 → 99 px 且像素变化 28 万、
> 开关关闭**逐字节不变**;6 组变异全被杀死。
> ✅ **2026-09-22 真机核对通过**(用户授权后实测;用户逐字「实测现在这样很不错了,后续还要改就得到走查阶段了」
> ⇒ 分层形态**定稿**,后续形态调整**推迟到走查阶段**;「虚化」为 alpha 近似、16dp 圆角丢失两项落差真机确认可接受)。
> 完整记录(`PixFoldRoutes.kt`/`PixFoldNav.kt`/`StepNavigation.kt`、F1–F13 源码级事实表、
> §7 实施结果 + §7.8 分层转场、已知可接受落差)见 **`docs/notes/d1-navigation-compose-migration.md`**。
> 以下原文保留以存证"问题是怎么被描述和归因的"。

- **现状**:用户反馈「设置返回有缩放渐变效果,**但没有显示到 home 页**」。
  即:当前页会随手势缩放/淡出,但**背后是空背景**(Surface 底色),看不到目标页。
- **根因**:`PredictiveBackContainer` 只对**当前内容**做 `graphicsLayer` 变换,
  没有渲染"目标页在背后"这一层。真正的预测性返回需要**两层结构**
  (目标页 + 当前页),手势进行中同时呈现二者。
- **做法**:需把根导航改成两层渲染(例如维护"当前 screen + 上一个 screen",
  手势中同时绘制两者,当前页缩放/平移、目标页在下方),属**实质性结构改动**。
  → **已放弃该做法**;改为迁移 Navigation Compose(见上方更新)。**该迁移已于 2026-09-22 完成。**
- **决策前置**:见下方「⚠️ 未决策:D1 范围 vs MVP」——
  该步的投入是否值得,取决于本阶段按"原型"还是"MVP"对待。

---


<!-- 原 HANGOFF.md L1604–L1632 -->

### ✅ 启动图标 MD 主题化 + minSdk 提到 30(2026-09-22)

> **分支**:`d1/settings-impl`(**已合回 `dev`**)。**已提交** `2ba10f8`(17 files,+566/−30)。
> **验证**:`app:testDebugUnitTest` **278** + `domain:test` **224** 全绿、`lintDebug` **零告警**、
> `assembleDebug` 成功。

**起因**:用户问「为什么要做 API 24/25 回退,我的安卓手机最低安卓 16」/「minTarget 写太低了?」,
随后裁决 **minSdk=30 + 保持 Java 17**(逐字:「至少 30 以下完全没必要,33/34/35 还是升级 JDK 用 36」)。
minSdk 本身的完整实测(功能面 / 依赖面 / lint 逐档代价 / 为何没选 36 / APK 体积连带现象)
是**工具链**问题,记录在 **`docs/notes/d1-toolchain-evidence.md` §9** 与 **§12.6**;此处只记产品面。

**启动图标改造**(用户逐字:「2,图标后期再画先用这个占位的」):

| 项 | 结果 |
| --- | --- |
| 形态 | 单层 `drawable/ic_launcher.xml` → **三层自适应图标** `mipmap-anydpi/ic_launcher.xml`(`<background>` + `<foreground>` + **`<monochrome>`**) |
| 主题化 | 带 `monochrome` 层后,Android 13+ 用户开启主题化图标时系统会按壁纸着色(**真机 Material You 已开**) |
| 字形 | **仍用占位方块几何**(`ic_launcher_foreground.xml` / `ic_launcher_monochrome.xml` 同一 `pathData`),正式图标后绘 |
| 背景 | `values/colors.xml` = `#3F51B5`(Indigo,与内置强调色种子同源)/ `values-night/colors.xml` = `#1A237E`(Indigo 900) |
| 回退档 | API 24/25 的 48dp 普通 vector **整体删除**(minSdk=30 已不需要);`mipmap-anydpi-v26/` 并入 `mipmap-anydpi/` |
| 护栏 | 新建 `app/src/test/kotlin/com/pixfold/d1/LauncherIconTest.kt`(**5 用例**,`GraphicsMode.NATIVE` 实栅格化),含「单色层与前景字形同步」的**源码结构断言** |

> **为什么图标需要专门断言**:图标是**唯一一类坏掉也全绿的资源** —— 它不参与任何逻辑,
> 编译通过、测试通过、APK 正常,只有在桌面/抽屉里才看得出错。故用「解析实际 drawable +
> 逐像素栅格化 + 源码结构比对」三条腿兜住。
> **防回退**:`monochrome` 层被删 = 2 处失败;夜间背景色被改回日间色 = 1 处失败(变异已实测)。

---


<!-- 原 HANGOFF.md L1686–L2034 -->

### ✅ 全量 i18n(2026-09-25)

> **分支**:`d1/settings-impl`(**已合回 `dev`**)。**用户裁决**:「只做全量 i18n,不动其他功能」。
> **验证**:`app` **304** + `domain` **230** 全绿、`lintDebug` **零告警**、`assembleDebug` 成功。

**核心决策:领域层不再持有文案,只产出「文案键 + 参数」**

原语:`data class Msg(val key: TextKey, val args: List<Any> = emptyList())`(`domain/text/Msg.kt`)+
扁平枚举 `TextKey`(`domain/text/TextKey.kt`,**104 个成员**)。
13 个枚举的 **53 个文案字段**全部换成键:如 `SortField(val textKey: TextKey)`、
`DangerAction(val labelKey, val bodyKey, val buttonKey)`、`ProposalWarning(val type, val message: Msg)`。
解析发生在**渲染时**,由界面层按当前语言现场完成(`ui/text/PixFoldText.kt`)。

**为什么是「键 + 参数」而不是「注入解析器」或「领域层留中文」**:
- **语言不该在建计划的那一刻被冻住** —— 若 `Msg` 存的是成品字符串,切语言后**已经显示出来的对象
  仍是旧语言**,要重新扫描/重建才更新。
- 参数保持 `Int` 原样而非预先 `toString()`,英文侧才能用 **`<plurals>`** 正确变格。
- 领域层是纯 Kotlin JVM 模块(**无 `Context`**),这虽是直接约束,
  但**不是**决定性理由 —— 决定性的是上面那条冻结问题。

**关键取舍(逐条已裁决,勿重新推导)**:

| 项 | 结论 | 理由 |
| --- | --- | --- |
| `Suggestion.source` | **保持 `String`** | mock 提供,展示的是"来源说明"原文;D4b 接入真实解析后须改产 `Msg` |
| `groupVolumesBySeries` 的分组键 | **语言无关的 `String`(未命名 = 空串)** | 该键要喂 `testTag` 与相等性比较;若用翻译后的键,**切语言会把同一系列拆成两组**;界面把 `""` 映射为 `TextKey.UnnamedSeries` 显示 |
| `ExecEntry.label` | **保持 `String`** | 它是**文件名**,不是文案(8 处构造点全传 `cbzFileName`/`finalName`) |
| `defaultCbzName()` | **保持中文** | 产物是**写到磁盘的文件名**,不随界面语言变 |
| `require{}` 的异常消息 | **保持中文** | 面向开发者的诊断信息,不进 UI |
| `WorkflowAStep`/`WorkflowBStep` 的 `label` | 改为 **`labelRes: Int`** | 存资源 id 而非字符串,同样为避免冻结 |

**测试策略 = 全部改为语言无关**(用户裁决 B):Robolectric 默认 locale 实测是 **`en_US`**
(这是最初 66 条失败的总根因),故断言必须经 `TestText`(`app/src/test/.../TestText.kt`)
从**同一份资源**解析期望值 —— 换语言不再变红,且资源与断言同源。
⚠️ `TestText` **只用于界面文案**:文件名词缀(`第01卷.cbz`)、语言码(`ja`)、
mock 数据里的中文串是**数据**不是文案,保持字面量。
领域层测试则断言 `Msg` 的**结构**(新增 `List<Msg>.keys()` / `.line(key)` 辅助),
不碰任何自然语言。

**规模(实测,替换规格 §2.3 的早期估计)**:
- `domain/` 源码面向用户文案 **99 处**(另有 48 处 mock 数据,与语言无关)
- `app/` 源码硬编码中文 **215 处 / 192 条 / 19 文件**(早期记录「176 处 / 18 文件」为低估)
- 测试中逐字断言中文 **875 处**(`domain` 385 + `app` 490;规格写的「826 处」是早期估计)

**落地结果**:资源文件 **6 组中英对照**(`domain_strings` / `strings_common` / `strings_plan` /
`strings_workflowa` / `strings_workflowb` / `settings_strings`),
共 **370 个中文键 / 364 个英文键**;**26 个 `<plurals>`** 在中英两侧类型严格一致。
`app/main` 残留中文 **1 处**(`SchemeEditor.kt:365` 的示例输入 `封:面?.jpg`,是**示例数据**)、
`domain/main` 非 mock 残留 **3 处**(`require{}` 消息 + `第N卷.cbz` 文件名模板)。

**`<plurals>` 的两条硬规则**(踩过坑,已固化为资源约定):
1. **资源类型不能跨语言不同** —— 计数键必须中英**同时**用 `<plurals>`,
   否则 lint `MissingTranslation`/`ExtraTranslation` 硬失败。中文侧 one/other **故意同值**(中文无变格)。
2. **`pluralStringResource(id, count, ...)` 的 `count` 单独传 quantity**,其余参数按位传入;
   当 `%1$d` 恰好是 quantity 时形态是 `pluralStringResource(R.plurals.k, n, n, ...rest)`(n 传两次)。
   数量不在首位的键(如 `NamingSummaryWithHead`,数量在第 2 位)由
   `PixFoldText.pluralQuantityIndex()` 单独登记下标。

**AAPT2 会 trim 资源首尾空白**(实测确证,真实产品缺陷):
`<string name="k"> </string>` 编译出来是**空串**(`len=0`),于是英文列表渲染成 `A,B`(应为 `A, B`)、
`To confirm:语言未确认` 丢掉分隔空格。**存活写法**:`\u0020`(实测 `len=1 hex=[32]`)。
全量普查后修掉 **14 处**首尾带空白的键(中英两侧),已全部复核往返正确。
⚠️ 该缺陷**只在切换英文后才可见**,中文侧用的是 `、` 不受影响 —— 是本次全量 i18n 顺带挖出的真缺陷。

**lint 的 29 条错误全部消解**(`warningsAsErrors = true` ⇒ 必须归零):
- **真阳性 `PluralsCandidate` 21 条** ⇒ 其中 **18 个键改成 `<plurals>`**(抑制会正好掩盖该规则要抓的缺陷);
- **假阳性 3 个键**(`domain_page_issue_wrong_index` 的 `should` 是动词、
  `a_scheme_comp_down_cd` 的 `down` 是副词、`plan_summary_skipped` 的 `skipped` 是过去分词)⇒ `tools:ignore`;
- **`UnusedResources` 4 条** ⇒ 删掉本次自建的死键(`common_delete`/`common_got_it`/`common_default`/`common_add`);
- **`TypographyDashes` 2 个键**(`domain_lang_other` / `meta_lang_code_label` 里的 `ISO 639-1` 是标准专名)
  ⇒ `tools:ignore`。**本次首次引入 `xmlns:tools`**(仓库此前无任何豁免先例)。

**变异验证(本次新建 `PixFoldTextTest`,8 条用例,专测解析层本身)**:
解析层此前**零直接覆盖** —— 所有既有测试都经页面间接使用它。
实测证据:把 `pluralQuantityIndex()` 的 `NamingSummaryWithHead -> 1` 改成 `-> 0` 后,
**原 295 项测试无一失败**(该分支零覆盖)。补测后同一变异**立即被捕获**:

| 变异 | 结果 |
| --- | --- |
| `NamingSummaryWithHead` 下标 1 → 0(原本**逃逸**) | ✅ `PixFoldTextTest` 1 项失败:`Expected <x.jpg · 1 component enabled>, actual <x.jpg · 1 components enabled>` |
| 非法字符分隔符键 → 通用列表分隔符 | ✅ 3 项失败(解析层 2 + `NamingPageTest` 1) |
| 资源里 `\u0020` → 裸空格(复现 AAPT2 trim) | ✅ 2 项失败;逐字 `该键必须编译出单个空格(AAPT2 会 trim 裸空白,故资源里写 \u0020). Expected < >, actual <>` |
| `a_table_summary` 的 quantity 固定为 `1` | ✅ `NamingPageTest` 5 项失败 |
| `a_naming_title` 的两个 varargs 互换 | ✅ 31 项失败(`IllegalFormatConversionException: d != java.lang.String`) |

> ⚠️ **教训**:页面级间接覆盖**不能替代**对公共解析层的直接断言。
> 一个键登记错,在页面上只表现为"少个空格/选错变体",间接断言几乎抓不到;
> 而参数错位却又会横扫所有组合该页面的测试类 —— 两种失败模式的归因难度截然不同。

**「测试语言无关」的独立验证:把整套 `app` 测试强制切到中文复跑(2026-09-25)**:
"语言无关"此前**只在 Robolectric 默认 locale(`en_US`)下验证过** —— 若某条断言误写成
英文字面量而非经 `TestText` 读资源,它在 `en_US` 下照样通过、换中文即失效,
该子目标就**未被真正验证**。故临时把 40 处 `@Config` 的 qualifiers 改为 `zh-rCN` 复跑
(**⚠️ 顺序坑:Android 资源限定符有顺序要求,locale 必须排在 `w400dp` 之类之前**;
第一次追加到末尾导致 227 项 `QualifierParser` 异常 —— 那是实验脚手架的 bug,不是测试的问题)。

结果(**两轮**):
- **第一轮**(全部 40 处改 `zh-rCN`):**304 项里 303 项通过,只有 1 项失败**,且失败的是
  `PixFoldTextTest` 按"下标登记"那条 —— 它原本用"`one` 变体 ≠ `other` 变体"作判据,
  而**中文侧 26 个 `<plurals>` 的 one/other 全部同值**(中文没有复数变化),中文下该判据
  **恒真**、断言退化成恒真式。⇒ 这条用例其实**借英文的复数变化才成立**,并非语言无关;
  已给它**显式钉死英文**(`@Config(qualifiers = "en")`)并写明原因,而不是假装它与语言无关。
- **第二轮**(保留那处钉死、其余 31 个文件仍强制 `zh-rCN`):**304 项全部通过、0 失败**。
  这一轮才真正关闭了该子目标:它**同时**证明①其余 303 条确实与语言无关(在 `en_US` 与
  `zh-rCN` 下都通过),②那条钉死确实生效(否则它会在中文下失败)。

实验已完整回滚(残余 `zh-rCN` = 0;**仅保留那一处刻意的 `qualifiers = "en"`**;
`git diff --numstat` 聚合 `ins=1874 del=739` 与实验前逐字一致)。

> ⚠️ **教训**:本仓库所有计数键的中文侧 one/other 都是同值,
> 故**任何以"单复数变化"为判据的断言都必须钉死英文**(或钉死某个真正有复数形式的语言);
> 反之,**用"中文侧差异"当判据的断言在中文下必然是恒真式**。

**英文真机冒烟走查(2026-09-25,用户授权安装后由 agent 走查)**:
把应用内 locale 切到 `en` 后逐页 dump,覆盖 A 全流程(排序→命名→执行计划→确认框→报告)
与 B 全流程(库→元数据→打包)共 10 个界面:

| 界面 | 英文实测(逐字摘自 UI dump) |
| --- | --- |
| 首页 | `Settings` / `Workflow A · Organize and name images` / `Workflow B · Build CBZ files` |
| A 步骤1 | `Step 1 Sort and preview`、`30 images`、`Sort rule: Directory name ↑ · File name (natural) ↑` |
| A 步骤2 | `Naming structure · 旅行照片 (30 images)`、`旅行照片_001.jpg · 2 components enabled`、`30 items · 0 conflicts · 0 warnings` |
| A 步骤3 | `56 volumes · 56 to run`、`No dangerous action: source files are kept, nothing is deleted` |
| 普通确认框 | `Confirm?` / `· Will rename 56 items (0 conflicts skipped)` / `Cancel` / `Run` |
| 危险确认框 | `⚠️ Confirm dangerous action` / `Step 1 of 2` / `· ⚠️ "Clean up empty folders" is checked; ...` / `Step 2 of 2` / `Deletion cannot be undone. Continue?` |
| 报告页 | `56 items: 56 succeeded · 0 skipped · 0 failed`、`Result: Succeeded` |
| B 步骤1 | `2 series / 5 volumes · 5 selected · 5 to confirm`、` · current`、`Language unconfirmed` |
| B 步骤2 | `Included · 16 pages · 0 fields overridden`、`To confirm: Language unconfirmed` |
| B 步骤3 | `5 of 5 volumes will be packaged`、`Page preview (renumbered in the current order, 16 items)` |

三条要点:
1. **英文复数变体在真机上确实生效** —— `30 images`/`5 volumes`/`56 items`/`16 pages`/`2 components enabled`
   全取 `other` 形。这是**离线单测抓不到**的部分(单测只断言解析层用对了下标);
   反之 `one` 形(`1 component enabled`)由 `PixFoldTextTest` 那条钉死英文的用例守着。
2. **中文数据串按预期保留** —— `旅行照片`/`进击的巨人`/`谏山创`/`目录层级 L2（系列层）`/`第01卷`
   在英文界面下原样出现,符合 `scope_mock=A` 与"文件名词缀/来源说明是数据而非文案"。
3. **trim 敏感资源真机复核通过** —— ` · current`、` / `、`To confirm: ` 三处渲染均带空格,
   与资源里的 `\u0020` 一致 ⇒ AAPT2 trim 那个坑在真机上确认已避开。
4. 顺带消除一条旧限制:文档此前记"主界面 30 个 UI 文件 `stringResource` 命中数为 0" ⇒
   **已不成立**,首页本身就是英文的(`HomeScreen.kt` 已改)。

> ⚠️ **操作坑(可复用)**:`cmd locale set-app-locales <pkg> --user 0 en` **静默无效** ——
> 位置参数被忽略,且因 `--locales` 未指定反而**把应用 locale 重置为空表**(看起来像"命令没生效")。
> 正确写法必须带旗标:`cmd locale set-app-locales <pkg> --user 0 --locales en`。
> 复位:`cmd locale set-app-locales <pkg> --user 0`(不带 `--locales` 即空表 = 跟随系统)。

**已知不完整(如实标注)**:`app/main` 与 `domain/main` 的残留中文见上;
mock 数据(`MockLibraryBData.kt` 40 处、`MockData.kt` 8 处)**按裁决保留中文**
(演示数据,不随界面语言变)。

**资源兜底语言:英文(用户裁决 2026-09-25,`User said: 「那还是改成英文兜底，默认选项是跟随系统一般也用不上兜底就是了」`)**

资源目录布局为 `values/` = **英文(默认/兜底)**、`values-zh/` = 中文(覆盖),
**没有 `values-en/`**。含义:系统语言为中文时走 `values-zh/`;为英文或
**日语/韩语/法语等第三语言时回退到英文**。`app/src/main/res/xml/locales_config.xml`
声明 `zh` 与 `en` 两种可选语言(供系统"应用语言"列表);`app/build.gradle.kts`
**无** `resourceConfigurations` 过滤。

> 💡 **沿革(勿再翻回去)**:本裁决**推翻了同日的上一版**裁决「中文兜底」
> (`User said: 「中文兜底吧，毕竟主要就是我自用的」`)。用户自己给出的反转理由:
> **默认选项是「跟随系统」,一般也用不上兜底** ⇒ 兜底几乎只在"系统语言既非中文
> 也非英文"时可见,那种场合英文比中文更有用;附带效果是回到 Android 惯例布局
> (英文进默认 `values/`)。

> ⚠️ **`values-zh/` 里不能出现 `one` 档**(与上一版裁决不同的新增约束):
> 中文一旦放进带语言限定符的目录,lint 的 **`UnusedQuantity`** 就会明确指出
> 「For language "zh" (Chinese) the following quantities are not relevant: one」,
> 在 `warningsAsErrors = true` 下直接**硬失败**。故中文侧 26 个 `<plurals>`
> **只写 `other`**;英文侧(默认 `values/`)则 one/other 两档都要写。
> (上一版裁决时中文在默认槽位,lint 不知道它是中文,故两边写同值也能过。)

> ⚠️ **`translatable="false"` 的键只留在默认 `values/`**:5 个
> `domain_meta_field_{title,series,writer,volume,language}`(ComicInfo.xml 标签名)
> 与 `app_name`。它们是英文默认文件的一部分,**不得**出现在 `values-zh/`,
> 否则 lint 报 `ExtraTranslation`。

> 💡 **相关因果链(易被误解)**:`applicationLocales = []` **不等于中文**,而是
> **"不覆盖,跟随系统"**。它显示中文是因为 `getprop persist.sys.locale` = `zh-CN`;
> 若系统是英文,同一个 `[]` 会走默认 `values/`(英文)。故设备复位后的准确说法是
> "**复位为跟随系统,而系统恰好是中文**"。

**P7 走查增补项(用户 2026-09-17 提出,真机走查时逐条核对)**:

> 与 §9《D1 验收清单》20 项**并列**的走查项:清单 20 项是 D1 出口判据(判据不变),
> 本清单是用户在实际操作中提出的体验项,同样**须在真机逐条核对**,不得只凭离线测试宣称通过。

| # | 走查点 | 状态 | 说明 |
| --- | --- | --- | --- |
| W1 | **切换集合后视图自动回到顶部** | ✅ **已修并真机核对(2026-09-19)** | 原实测缺陷(2026-09-17):切集合后网格/列表**保留上一个集合的滚动位置**,用户切过去看到"中段"而非第一张。**根因**:切集合的 `onClick` 只改 `activeId`,**没有触发回顶信号**(W4 的信号通道已存在却未接上)。**修法**:复用 W4 同一条通道(`scrollSignal++` → `requestScrollToItem(0)`),且**仅当真换了集合**才自增(重复点当前集合不把用户拽回顶部)。**真机实证**:trip 下滑后首张 = `IMG_20260701_007.jpg`(第 7 张)且回顶按钮出现 → 切到 scan 后首张 = **`page_01.png`(第 1 张)**、回顶按钮隐藏;**列表视图同样验证通过**。**回归护栏 6 项**(`CollectionSwitchScrollTest`,含网格/列表/来回切/重复点当前集合);**变异验证**:撤掉修复 → 3 项失败;无条件回顶(过度回顶)→ 1 项失败。**该缺陷曾同时误导测试**:语义树只含可见项,保留滚动位置时会读到"视口中段" |
| W2 | **下滑后显示"回到顶部"按钮** | ✅ 已通过真机核对 | 网格或列表**产生下滑**时,在**右下角**实时显示回顶按钮;回到顶部后隐藏 |
| W3 | **排序规则升降序交互 + 变动即应用**(用户 2026-09-17 指定) | ✅ 已通过真机核对 | 每级默认升序;**再次点击已选中的字段**即切换升/降序;用**实心三角**(正/倒)表示方向,**仅显示在选中字段名之后**;原行尾的"升序/降序"切换按钮**已移除**;**并去掉"应用排序"按钮 —— 换字段/翻方向/增删级均即时生效** |
| W4 | **排序规则变更后自动回顶** | ✅ 已通过真机核对(⚠️ 无本地回归护栏,见下) | 规则一变即回顶 —— 重排后"当前位置"失去意义(用户看到的是中段而非新首项)。实现:`scrollToTopSignal` 自增 -> `LazyGridState/LazyListState.requestScrollToItem(0)`(**在本次布局内结算**,不用 `LaunchedEffect`);**仅 signal 变化时触发**,无关重组不得把用户拽回顶部 |

> **W3 实现记录(2026-09-17)**:三角为官方 Material Symbols `arrow_drop_up/down` 矢量资源
> (`res/drawable/ic_arrow_drop_{up,down}.xml`,Apache-2.0),经 `FilterChip` 的 `trailingIcon` 渲染 ——
> 与图钉同策略:**图标用矢量资源,不自绘**(见 §12.5 教训)。
> 交互收敛为"点击已选中字段 = 翻方向;点击其他字段 = 换字段并回到默认升序",
> 把"选谁"与"什么方向"合并到一个控件,少一行控件、少一次点击。
> 方向经 `contentDescription` 播报(如"第1级 文件名(自然) 降序"),读屏可懂。
> **变异验证**:「再点不翻转」被 3 项捕获、「三角显示在所有字段」被 1 项捕获、
> 「换字段不回默认升序」被 1 项捕获。
>
> **变动即自动应用(用户 2026-09-17 追加指定)**:原设计取自归档语义 ——
> "规则编辑用**草稿**,点「应用排序」才落地,**编辑不实时生效**"(规格 §5.4)。
> 用户决定**覆盖该设计**:去掉"应用排序"按钮与草稿态,换字段 / 翻转方向 / 增删级均**立即重排**。
> 实现上去掉 `draft` 本地状态,编辑器以传入 `rule` 为**单一事实来源**,变动直接 `onRuleChange` 上报
> (顺带消除了"双份状态可能不同步"的隐患)。规格 §5.4 已同步标注被覆盖。
> **变异验证**:"页面忽略规则变更"被 3 项捕获(含 2 项**页面级**断言 ——
> 断言"网格首项真的变了",而非只断言编辑器上报)。

> **性能实测与优化(2026-09-17,用户提问:"一个集合最大 3000 张、单张最大 50MB,立即重排是否有性能问题?")**:
>
> **结论:没有问题。** 两条原因:
> 1. **排序只读元数据**(文件名/目录/大小/时间戳),**从不读图片内容** ——
>    故 50MB/张 与 5KB/张 的排序成本**完全相同**(`SourceItem` 只含元数据;
>    缩略图走 `ProceduralThumb` 由 `seed` 派生,**零图片 IO**)。
> 2. **排序键已改为每项只算一次**。
>
> **实测发现并修掉的真实浪费**:原实现在**每次比较**里做 `fileName.lowercase()` + `tokenizeNatural()`,
> n=3000 时比较约 **17,254 次**(实测计数)→ 即 1.7 万次字符串小写化与分词,而实际只需 **3000 次**。
> 改为 decorate-sort-undecorate(先为每项预计算排序键一次)后:
>
> | 项目(n=3000) | 优化前 | 优化后 |
> | --- | --- | --- |
> | `sortItems` 单级自然升序 | ~45ms | **~12ms**(3.6×) |
> | `applyRule`(改一次规则,端到端) | ~7.4ms | **~3.7ms**(2×) |
> | `applyRule`(含 300 个固定项) | ~7.1ms | **~4.1ms** |
> | `sortItems` 单级降序 | ~7ms | ~4.6ms |
>
> 即"改一次排序规则"端到端 **2~4ms**,远低于一帧(16ms)。**故"变动即重排"是安全的**。
>
> **等价性守护**:`SortItemsEquivalenceTest` 用 18 条规则 × 含边界样本(补零/前缀零/超长数字/
> 大小写/中文/非法字符/空目录/`created=null`/全等项)的小样本集,把**朴素实现(每次现算)**
> 与**优化实现**逐项比对,确保结果**完全一致**;另测确定性(打乱输入同结果)与不改入参。
>
> **性能护栏的自我修正(值得记录)**:初版护栏用**绝对耗时上界**(n=3000 < 320ms),
> 变异验证发现它**挡不住本次这类回退** —— 退回旧实现(~45ms)**仍在阈值内通过**。
> 修正为**同机相对比较**(朴素实现 vs 优化实现,要求至少快 2×,与机器速度无关),
> 再变异即被捕获。**教训:绝对耗时阈值只能挡灾难性退化;要挡常数倍回退,必须用相对基准。**

> **W3/W4 真机走查与四项缺陷修正(2026-09-17 深夜~09-18 凌晨,用户逐条反馈)**:
>
> **① 三角图标曾"占位但不可见" —— 坐标系陷阱(我照抄官网 SVG 犯的错)**:
> Material Symbols 官网 SVG 用 `viewBox="0 -960 960 960"`(**负 y**),而 Android
> VectorDrawable **没有 viewBox 偏移**语义 —— 我配上 `viewportHeight="960"` + 负坐标后,
> 图形整体落在画布**之外**,表现为"**芯片右侧多出一块占位、却看不到三角**"(用户原话)。
> **正解**:y 加 960 再按 24/960 缩放;换算结果与官方经典 24x24 路径
> (`M7 14l5-5 5 5z` / `M7 10l5 5 5-5z`)**逐点一致**(已独立验证)。
> **为什么 12 项语义测试全绿却没挡住**:`Icon` 节点存在、`testTag` 可查、
> `contentDescription` 正确 —— **只是没有像素**。语义层无法表达"看得见"。
> **补的护栏**:`DirectionIconRenderTest` —— **栅格化后数非透明像素** +
> 断言两三角**朝向相反**;并先拿坏资源验证它**确实红 3 项**(非空转)。
>
> **② 三角偏小(用户:"有点小,可以稍微大点")**:
> 官方 24x24 里三角仅占 **10 宽 x 5 高**,其余全是留白 —— 故 16dp 图标实际只画出
> ~6.7dp 宽。修正:保持 24x24 方形视口(不被非等比拉伸),把**图形放大 1.6 倍**
> (`M7,14 L12,9 L17,14` -> `M4,15.5 L12,7.5 L20,15.5`),图标 16 -> 18dp,
> 实际三角约 **12dp 宽**。
>
> **③ "切换升降序有个滑动卡一下"(用户反馈)**:
> 自动回顶原用 `animateScrollToItem`,在**刚重排过**的列表上逐项动画滚动 ——
> LazyGrid/LazyList 长距离动画滚动需组合中间帧,是 Compose 公认卡顿源,
> 且"对已重排的内容做滚动动画"本身没有意义。改为**瞬时**归位。
> **刻意保留**点按钮时的平滑动画:你主动点击时有意图,动画让空间关系可读。
> **护栏**:`external scroll to top is instant in {grid,list}` —— 阈值来自**实测标定**
> (`scrollToItem` 25 帧 vs `animateScrollToItem` 42 帧,取 32 帧为界),
> 变异验证改回动画即被 2 项捕获。
>
> **④ "仅在顶部切换升降序"时顶部露出上一行碎片(用户反馈,最关键的一条)**:
> **用户补充的范围是本条的决定性线索**:"仅网格视图" + "仅在顶部" + "有一点下滑就不触发"。
> 这条**推翻了我最初"旧滚动偏移中间帧"的解释** —— 在顶部时偏移本就是 0,**没有旧偏移可残留**。
> 真实机制是 **LazyGrid 的 key 锚定**:重排后它按 `key` 记住"原来那个首项",
> 而该项此时已跑到列表后段,于是把**滚动目标改成了它的新下标** ——
> 在顶部表现为视口瞬间被锚定到后段(顶部露出别处的行边);已有下滑时锚定与目标接近,故不明显。
> **修正**:改用 `requestScrollToItem`(在**本次布局内**结算),并**只在 signal 变化时**触发。
> **顺带修掉一个我自己引入的缺陷**:若每次重组都 `requestScrollToItem`,
> 规则变更后用户往下滚会被无关重组**反复拽回顶部** ——
> 该风险由 `after a rule change user can still scroll freely` 守护(变异验证:改回则失败 1 项)。
> **⚠️ 必须如实记录的局限**:本项**在本地建不起回归护栏** ——
> Robolectric 下 `requestScrollToItem` 与 `LaunchedEffect+scrollToItem` 行为**一致**
> (实测都是 25 帧到顶),变异退回旧实现**不会失败**。
> 故本条**只有真机证据**(用户 2026-09-18 实测确认正常),无本地自动护栏。
>
> **⑤ 方法学教训(两次假阴性)**:
> 排查④时我的**真机截图逐像素对比连续给出假阴性** —— 升/降两张截图在规则编辑区报
> "零差异",而用户肉眼明确看到三角且能切换;改用**录屏抽帧**后抓到的"微弱带"
> 经核验是 **H.264 暗场压缩伪影**(偏差仅 2~11),并非真实 UI。
> **结论**:截图/录屏像素对比**不能作为唯一判据**;当它与用户观察冲突时,**用户观察为准**,
> 并应转向代码结构找可解释的机制,而不是继续加码像素分析。

> **测试方法教训(W1 引发的误判,2026-09-17)**:`uiautomator`/Compose 语义树**只包含当前可见项**。
> 网格保留滚动位置时,直接读节点会得到"视口中段"而非列表头部 ——
> 本次据此**误判"排序应用后顺序不对"**,实为读到了 `day1` 中段;
> 经"滚动到顶后再读"对照,确认**领域与 UI 顺序完全一致**。
> **结论**:凡断言"列表首项/顺序"的测试,必须**先归位到顶部**再读;
> 或直接断言**数据层**(领域)。该陷阱与 §12.5"假阴性陷阱"同类,已补入 W1 备注。

> **W1 实测证据(2026-09-17)**:在 `杂图` 向下滚至 `img4` 开头,切到 `扫描件` 也从**中段**开始;
> 切回 `杂图` 仍停在 `img4`。确认「滚动位置跨集合残留」,非读数假象。

**图钉外观修正与真机核对(2026-09-17)**:

> **问题(用户指出)**:图钉用了**五角星**(★/☆)。这是**语义错误** —— 五角星在通用语义里是
> **收藏/评分**,而该功能是**固定位置**(§4 词汇即"图钉");用户会带着"这是收藏"的错误预期操作。
> **成因(如实记录)**:初版用的是 emoji 图钉(📌/📍),语义正确但 **emoji 不接受 `color` 染色**,
> 无法用 M3 语义色表达"已固定/未固定";我在重构时改成了可染色的五角星 ——
> **属"拿语义换染色"的取舍失当**。
> **正解(两轮返工后定稿)**:**直接用官方 Material Symbols `push_pin` 的 VectorDrawable**
> (`res/drawable/ic_push_pin_{filled,outlined}.xml`,几何数据原样引用 Apache-2.0),
> 经 `painterResource` + M3 `Icon(tint=...)` 渲染。已抽为共享 `PinToggle`
> (原先卡片与列表行各写一份、且各用星号)。
> **方案取舍(三轮试错的结论)**:
> ❌ 五角星 ★/☆ —— 语义错(收藏 ≠ 固定);
> ❌ emoji 📌/📍 —— 语义对但**不接受 `color` 染色**,无法表达两态;
> ❌ 自绘 Canvas 图钉 —— 能用但**观感差**(圆帽+三角像感叹号,用户 2026-09-17 再次指出);
> ❌ `material-icons-extended` —— 官方**已弃用**(体积巨大),且 `PushPin` **不在 `material-icons-core`** 子集内
> (实测 `:app:dependencies` 确认 classpath 上无该依赖);
> ✅ **VectorDrawable + `painterResource`** —— Android **原生矢量格式**,零新增依赖、随 M3 语义色着色、
> 复用官方几何数据。
> **根因反省**:我一开始把"图标"当成"要自己画的东西",而**正确做法是用矢量资源** ——
> 仓库里本就有先例(`ic_launcher.xml`),我没去用。
> **两态不只靠颜色**:未固定 = outlined(空心),已固定 = filled(实心),形状确有区别。
> **防回退护栏**:`PinButtonTest` 断言"资源必须是 `VectorDrawable`"、"两态必须是不同图形"、
> 且"不得再出现 ★/☆";变异验证 = 改回星号即失败。

> **本机判形证据(2026-09-17,Robolectric + `GraphicsMode.NATIVE` 栅格化矢量后逐像素转 ASCII)**:
> 因截图附件在本环境不可直接读,改用像素图案判形(比肉眼看图更客观),避免"自以为好看"。
> - **filled**:宽钉帽 → 收窄 → 细钉针的**实心**图钉(标准 Material Symbols 造型);
> - **outlined**:同轮廓但**空心**,仅外圈与钉针可见;
> - 两态形状**确实不同**,不依赖颜色即可区分。
> (真机核对见下方"图钉真机核对"—— 采用矢量前的自绘版曾在真机验证过语义与尺寸,换矢量后外观以本机栅格化判形为准。)

**W2 实现记录(2026-09-17)**:滚动位置由 `LazyGridState` / `LazyListState` 驱动;
点击 `animateScrollToItem(0)` 平滑回顶。按钮为 M3 `FilledTonalIconButton` + 官方 Material Symbols
向上箭头矢量(`painterResource`,零三方依赖),置于**右下角**(拇指易达,且不与底部系统手势条冲突),
`contentDescription = "回到顶部"`。`derivedStateOf` 过滤滚动状态,避免每像素滚动都重组。

> **可见性判据修正(2026-09-17 用户指出——"划到最底部时没有显示,不符合常规操作逻辑")**:
> 初版判据为 `canScrollForward`(下方还有内容)**且** `firstVisibleItemIndex > 0`;
> **缺陷**:`canScrollForward` 表示"下方还有内容",**滑到底部时恰为 `false`** ——
> 于是在**最需要回顶的时候按钮反而消失**。
> **正解**:改用 `canScrollBackward`(**上方还有内容 = 不在顶部**),并**移除** `firstVisibleItemIndex > 0`
> (与 `canScrollBackward` 语义重复);这样"从顶部之外任意位置(含最底部)"都显示,
> "已在顶部"或"内容不足一屏"时自然隐藏。
> **回归测试**:新增 3 项(网格底部仍显示 / 列表底部仍显示 / 从底部回顶后隐藏);
> **变异验证**:退回旧判据 `canScrollForward` 即被 2 项捕获。
> **教训**:滚动可见性要用**"是否能往回滚"**判断,而不是"是否还能往下滚" ——
> 后者在端点处的取值与直觉相反。

