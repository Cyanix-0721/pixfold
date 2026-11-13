# PixFold 迁移实施计划:设置抗漂移 · Coil · Navigation 3

> 依据设计规格 `docs/superpowers/specs/2026-11-13-datastore-coil-nav3-migration-design.md`。
> 用户要求三件事都落到代码:设置持久化更好维护、缩略图改用 Coil、导航迁 Navigation 3。
> DataStore 阶段已由用户裁决取消(保留 SharedPreferences)。

## 0. 阶段划分与共同约束

三阶段互相独立:各从 `dev` 开一个分支、各跑四道闸门、各做真机核对、各自提交(只提交不推送)、完成合回 `dev` 并回写文档。建议顺序 1→2→3(风险递增)。

每阶段都适用(不重复列在正文):
- **分支**:从 `dev` 开 `d4/<name>`(AGENTS §0.1 第4条,判据 `git branch --show-current` ≠ dev)。
- **TDD**:先红后绿(`test-driven-development`)。
- **四道闸门**:`cd android && ./gate.sh`(lintDebug+testDebugUnitTest+domain:test+assembleDebug);判绿看 `android/build/gate-last.rc` + 日志 `BUILD SUCCESSFUL` 且相关 task 非 `UP-TO-DATE`/`FROM-CACHE`(必要时 `--rerun-tasks`)。
- **变异验证**:证明断言有杀伤力(`verification-before-completion`)。
- **真机三层自证**:领域单测 → UI 断言 → 真机(每轮单独授权)。
- **提交后推送前**:`betterleaks git . --redact --revision="origin/dev..dev" --include=merges --include=commit-messages`。
- 除本计划列出的依赖外不新增;`domain/` 保持零 Android 依赖(`AdapterBoundaryTest` 强制)。

---

## 阶段 1 —— 设置持久化抗漂移(不改生产结构)

### 目标
让「加设置字段忘接线」必然被测试抓到,消除 load/save 漂移;生产代码与公开接口不变。

### 现状(取证)
- `android/app/src/main/kotlin/com/cyanix/pixfold/data/SharedPrefsSettingsStore.kt`:逐字段 load/save,key 在 `private companion`。
- `android/app/src/test/kotlin/com/cyanix/pixfold/data/SharedPrefsSettingsStoreTest.kt:78` 已有 `save then load round trips every field`,用 `custom`(每字段非默认)断言整对象。**但它依赖人手把新字段加进 `custom`;加字段若忘改 `custom`,新字段两边都是默认值,round-trip 会假通过。**
- `PixFoldSettings`(`android/domain/.../domain/settings/PixFoldSettings.kt`)是 9 个构造参数的 immutable data class;有派生属性 `monetEnabled`(get(),无 backing field)。

### 为什么放弃「字段表驱动重构」
Kotlin 强类型 + immutable data class 下,字段表每字段要 read/write/get/set 四个 lambda + key,样板比现状(KEY+load+save 三处)更多,还牺牲类型安全。真正的病是「测试不自动覆盖新字段」,不是「生产写法重复」。故用反射测试锁住,不重构生产代码。

### 任务
- **T1.1** 在 `SharedPrefsSettingsStoreTest.kt` 加反射驱动 round-trip:用 Java 反射 `PixFoldSettings::class.java.declaredFields` 取 9 个 backing field(自然排除无 field 的 `monetEnabled`);按类型造非默认值(enum→第一个 ≠ 默认值;Boolean→取反;Int?→非 null 正数);反射构造 `PixFoldSettings` 实例 → `save` → `load`,断言整对象相等,并断言每个 key 在 prefs 里有值。
- **T1.2** (增强)反射断言字段数 = `PixFoldSettings` primary constructor 参数数,防止派生属性混入/漏测。
- **T1.3** 四道闸门绿;**变异**:临时从 load 或 save 删某字段接线,反射测试必须红;还原(`cp` 备份还原,不用 `git checkout --`)。

### 验收
- 反射测试在「load 漏字段」「save 漏字段」两种变异下都红。
- 生产代码零改动(仅测试文件)。
- 四道闸门绿 + 真机(改设置→杀进程→重启仍在)。

### 风险
Java 反射依赖 field 顺序/名称:data class 保证声明顺序;debug 构建无混淆,成立。

---

## 阶段 2 —— 缩略图改用 Coil

### 目标
用 Coil 3 替换手写 `CachingThumbnailSource` + `AndroidThumbnailSource` 的加载/缓存/并发,保持 `ThumbnailSource` 接口不变,5 处 UI 调用点与既有测试不动。

### 现状(取证)
- 注入点 `android/app/src/main/kotlin/com/cyanix/pixfold/PixFoldApp.kt:173-175`:`CachingThumbnailSource(AndroidThumbnailSource(context))`。
- `ui/workspace/ThumbnailSource.kt`:`suspend fun thumbnail(uri,widthPx,heightPx): ImageBitmap?`。
- 自研 `CachingThumbnailSource`(159行,Semaphore 并发闸门 + 在途合并 + 字节 LRU)+ `ThumbnailCache`(93行)+ 测试 255/180 行;真机千级 862 张验证过。
- 预算 `domain/thumb/ThumbBudget.kt`(`thumbBudgetFor`):requestPx / maxConcurrent / cacheEntries / cacheBytes。

### 依赖(已核 Coil 3.6.3,2026-11-13)
- `android/gradle/libs.versions.toml`:`coil = "3.6.3"`;`coil = { group="io.coil-kt.coil3", name="coil" }`(Android artifact;**不引** `coil-network-okhttp`,本项目无网络)。app `build.gradle` 加 `implementation(libs.coil)`。
- 冲突检查:若与 Compose BOM 2026.09.00 / Kotlin 2.4.20 冲突,以 `./gate.sh` 依赖解析结果为准,必要时调整。

### 实现草案(新建 `data/saf/CoilThumbnailSource.kt`)
```kotlin
class CoilThumbnailSource(context: Context, private val budget: ThumbBudget = DEFAULT_BUDGET) : ThumbnailSource {
  private val app = context.applicationContext
  private val imageLoader = ImageLoader.Builder(app)
    .memoryCache { MemoryCache.Builder().maxSizePercent(app, 0.25).build() }
    .diskCache(null) // 全 SAF,不落磁盘
    .build()
  override suspend fun thumbnail(uri: String, widthPx: Int, heightPx: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
      val px = minOf(maxOf(widthPx,1), maxOf(heightPx,1), budget.requestPx)
      val req = ImageRequest.Builder(app).data(uri).size(px).allowHardware(false).build()
      when (val r = imageLoader.execute(req)) {
        is SuccessResult -> r.image.toBitmap().asImageBitmap()
        else -> null
      }
    }
  companion object { val DEFAULT_BUDGET = thumbBudgetFor(512, 256L*1024*1024, 8) }
}
```
> ⚠️ 待实施时按 3.6.3 源码/文档确认三处:① `coil3.Image` → `Bitmap`(`r.image` 是 `coil3.Image`,可能经 `DrawableImage.drawable` + `androidx.core.graphics.drawable.toBitmap()`);② `ImageRequest.Builder.size(px)` 单参是否存在(否则 `.size(px,px)`);③ `diskCache(null)` 签名。

### 任务
- **T2.1** 依赖入 toml + build.gradle,先跑 `./gate.sh` 证明解析不炸(零逻辑改动)。
- **T2.2** 写 `CoilThumbnailSource` + 单测(Coil 官方 Testing 页有 FakeImageLoader):「成功→非null」「失败/取不到→null」「请求尺寸不超 budget.requestPx」。
- **T2.3** 注入点改 `CoilThumbnailSource(context)`;**旧 `CachingThumbnailSource`/`ThumbnailCache` 及其测试先保留**,真机通过后单独 commit 删除。
- **T2.4** 真机门禁(授权):打开含 ~200–800 张的工作区,记滚动流畅度/内存/OOM/列数切换与旋转;与改前自研版对比读数。
- **T2.5** 若真机不如预期(卡/内存),注入点改回自研,Coil 阶段标「不采用」。

### 验收
- 四道闸门绿 + 真机滚动不卡、无 OOM、缩略图正确(含列数切换/旋转)。
- 依赖解析无冲突。

### 风险 / 判断
Coil 是三项里**风险最高、收益最模糊**:自研层已千级验证且测试充分,Coil 主要优势(网络/磁盘)本项目用不上。建议**仅当 T2.4 真机不劣于自研**才正式采用,否则保留自研。删旧代码必须是真机通过后的独立一步。

---

## 阶段 3 —— 导航迁 Navigation 3(受限 spike)

### 目标(受限)
先只迁**根导航 6 个目的地**(Home/Settings/Licenses/Organize/Library/Preview)到 Nav3,验证 type-safe `NavKey`、返回栈、预测性返回转场能复刻现有观感。两个工作流内嵌套步骤图本轮**不动**,先 spike 评估是否可共存。成功再规划全量。

### 现状(取证)
- navigation-compose 2.10.2 type-safe route;`nav/PixFoldRoutes.kt` 13 个 `@Serializable` route(data object/class)。
- `nav/PixFoldNav.kt`:`PixFoldNavHost` 包装 NavHost,定制预测性返回转场(Step 淡出 / Page 缩小 0.90+fade,`PREDICTIVE_MIN_SCALE=0.90f`),`DestinationSurface` 不透明底板。
- `nav/StepNavigation.kt`:步骤序号由返回栈派生 + 双击 pop 防护 + Tab 线性跳转。
- 15 条返回测试;设置项 `predictiveBackAnimation` 控制手势是否跟手。

### Nav3 已知 API(已核 1.2.1)
- key 必须 `: NavKey` 且 `@Serializable`;`rememberNavBackStack(...)` 得 saveable back stack。
- `NavDisplay(backStack = backStack, onBack = …, entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()), entryProvider = entryProvider { entry<Home> { … } })`。
- 预测性返回内置;自定义转场 API 需 spike 确认(可能 `transitionSpec`/`sceneStrategy`)。

### 任务
- **T3.1** spike 分支跑通最小 `NavDisplay` + 6 目的地跳转/返回(占位 Composable,不接业务)。
- **T3.2** 复刻预测性返回观感(Step/Page 两档 + 0.90 缩放 + 底板),真机与现版逐帧对比。
- **T3.3** 验证 `predictiveBackAnimation=false` 时手势不跟手、松手仍有动画。
- **T3.4** 评估嵌套步骤图能否继续用 navigation-compose 与 Nav3 共存,或必须一并迁。
- **T3.5** 输出 spike 结论:API 差异表、转场复刻方案、工作量、15 条返回测试迁移成本。

### 退出条件
- 证明:根导航 6 目的地可迁、预测性返回观感一致(真机)、`predictiveBackAnimation` 开关有效。
- 若无法复刻 0.90 缩放/两档转场或与嵌套图冲突 → 判「不收编」,保留 navigation-compose 并记录原因。

### 风险
Nav3 1.2.1 较新,自定义预测性返回转场 API 可能与现版不同;nesting 需验证;15 条返回测试要重写。

---

## 阶段间顺序与提交
1(设置)→ 2(Coil)→ 3(Nav3 spike)。每阶段:开分支 → TDD → 闸门 → 变异 → 真机 → 回写文档 → 展示改动集 → **问用户「现在提交还是攒着」** → 提交(不推送) → 密钥扫描 → 合回 `dev`。

## 未决问题(需用户确认)
1. 阶段一:接受「反射测试锁漂移、不重构字段表」这个修正?(推荐)
2. 阶段二 Coil:接受「spike 优先、真机不劣于自研才采用、旧代码真机通过后再删」?
3. 阶段三:Nav3 受限范围 = 根导航 6 目的地、嵌套步骤图本轮不动,同意?
4. 三阶段都现在做,还是先只做阶段一?

---

## 执行台账

### 阶段 1 —— 设置持久化抗漂移（2026-11-13）

- **Task 1: complete**。`android/app/src/test/kotlin/com/cyanix/pixfold/data/SharedPrefsSettingsStoreTest.kt` 新增反射测试 `every constructor parameter survives a save load round trip`。
- **实现与 T1.1 的差异**：未用 `declaredFields` + 按类型造值，改用 **primary constructor + 手写 `PARAMETER_SAMPLE`（9 项，每字段显式非默认值）** + `declaredConstructors.filterNot{isSynthetic}.maxByOrNull{parameterCount}` 反射构造。理由：直接、可读；且「参数数 == 样本数（9）」断言在加字段忘同步时同样会红，达到同样防漂移目的。T1.1「每 key 在 prefs 有值」由「整对象 save→load 相等 + 每字段非默认」等价覆盖。
- **变异验证**：删 `save` 里 `putString(KEY_COLOR_SPEC, settings.colorSpec.name)` ⇒ 新测试红、旧 `save then load round trips every field` 仍绿（正好证明旧测试漏了 `colorSpec`）；已 `cp` 还原。
- **四道闸门** `--rerun-tasks` 全量：BUILD SUCCESSFUL；app 891 / domain 986 测试 0 失败；lint 零告警。
- **Ruling（构建链路）**：worktree 内 `android/run-gradle.bat` 第 20 行硬编码主仓库路径，本阶段改用直呼 worktree 的 `gradlew.bat`（`cmd.exe /c "cd /d <worktree>\android && call mise exec -- cmd /c gradlew.bat <tasks>"`），并复制 `local.properties`（gitignored）到 worktree android。

