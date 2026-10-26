# 工作流 C：就地更新 CBZ 元数据 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **本计划的执行方式已由用户裁决**（User said (m07523)「在这之前怎么实现你来决定」）：**Native（内联执行）**，不派 subagent。理由 = 8 个任务是一条强串行接口链（领域类型 → 重写器 → 平台实现 → UI 消费），派发与评审成本高于直接写；且本轮还要执行关机收尾。

**Goal:** 给已有的 CBZ 就地替换 ComicInfo.xml，让用户改完元数据不必重新打包。

**Architecture:** 领域层写一个纯流式转写器（读旧 ZIP、写新 ZIP，只换一个条目）；平台层用 SAF 建临时包 + 三段式改名替换原包；UI 在库页卷行与元数据页底栏各开一个入口，确认 → 进度 → 结果三步反馈。

**Tech Stack:** Kotlin / Jetpack Compose / SAF（`DocumentsContract`）/ `java.util.zip`。

**Spec:** `docs/superpowers/specs/2026-10-26-cbz-update-design.md`（本计划每一处「为什么」都在那里；执行时先读对应小节）

## Global Constraints

- 测试命令**只能**是 `cd android && ./gate.sh` 或 `cmd.exe /c "run-gradle.bat :app:testDebugUnitTest :domain:test --rerun-tasks"`。**禁用 `--tests`**（cmd.exe 下静态假绿 0 测试）。
- 产出包必须**全 STORED** 且 `ComicInfo.xml` **恒为第 0 条**（`CbzReaderCompatTest.kt:103` / `:150`）。
- 领域层 `android/domain` **不得引用 `Context` / `R.string` / `android.*`**。平台相关一律走端口注入。
- `lint` 的 `warningsAsErrors = true`：新增资源必须 `values` + `values-zh` 同步，且**新增即被消费**（否则 `UnusedResources` 硬失败）。
- 新增 Compose 参数**必须有默认值**（否则 `LibraryPage`/`MetadataPage` 的既有测试文件全红）。
- 「取消」复用 `R.string.common_cancel`；「确定」复用 `R.string.domain_general_confirm_ok`。**不引入 `android.R.string.*`**。
- 每轮收尾：`todo_write` 立刻标完成；跑绿 → **立刻 commit** → 再做变异（AGENTS.md §0.1 第 6 条 ⑤）。
- 所有 `remember` 播种状态必须带 key（`rootPath` / `workspaceHandle`）。

## Review Focus

规格没写、但真机上一定撞的五个输入，每个都在对应任务里有测试钉住：

1. **旧包里 ComicInfo.xml 不在第一条**（第三方工具重排过）⇒ 新包必须是第 0 条，其余顺序不变。→ Task 2 / T2。
2. **旧包里有大小写不同的两个 ComicInfo.xml** ⇒ 新包只能有一个。→ Task 2 / T3。
3. **旧包的页是 DEFLATED**（别的工具压过）⇒ 新包仍要 STORED 且内容逐字节一致。→ Task 2 / T7。
4. **包里一个条目都没有**（0 字节包 / 只有目录条目）⇒ 输出仍是「只有一个 ComicInfo 的合法包」，不许 NPE、不许产出空 ZIP。→ Task 2 / T10。
5. **替换阶段三步中任意一步失败** ⇒ 用户的原包不能消失。→ Task 4 的失败断言 + Task 8 的「只能真机断」清单。

---

## File Structure

| 文件 | 责任 |
| --- | --- |
| `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/pack/CbzMetaUpdate.kt` | **新建**。计划/结果/失败原因/端口/纯函数 |
| `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/pack/CbzMetaRewriter.kt` | **新建**。流式转写核心（唯一碰 `java.util.zip` 的新地方） |
| `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/comic/ComicInfoReader.kt` | 改。追加 `withComicInfoValues` |
| `android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafPackPlatform.kt` | 改。三个 SAF 查询私有方法抽成 `internal` 顶层函数（spec D8） |
| `android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafMetaUpdater.kt` | **新建**。`CbzMetaUpdater` 的 SAF 实现 |
| `android/app/src/main/kotlin/com/cyanix/pixfold/ui/library/CbzUpdateDialogs.kt` | **新建**。确认框/进度/结果三个弹窗（单独成文件：`LibrarySteps.kt` 已 900 行） |
| `android/app/src/main/kotlin/com/cyanix/pixfold/ui/library/LibraryPage.kt` / `MetadataPage.kt` | 改。各加一个入口 |
| `android/app/src/main/kotlin/com/cyanix/pixfold/ui/library/LibrarySteps.kt` | 改。状态 + 执行 + 装配 |
| `android/app/src/main/kotlin/com/cyanix/pixfold/PixFoldApp.kt` | 改。构造 `SafMetaUpdater` 并注入 |
| `android/domain/.../text/TextKey.kt` + `res/values*/strings_library.xml` + `res/drawable/ic_edit_note.xml` | 改。文案与图标 |

---

### Task 1: 领域层数据模型与纯函数

**Files:**
- Create: `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/pack/CbzMetaUpdate.kt`
- Test: `android/domain/src/test/kotlin/com/cyanix/pixfold/domain/pack/CbzMetaUpdateTest.kt`

**Interfaces:**
- Consumes: `CbzWriter.COMIC_INFO_ENTRY`（已存在）
- Produces:
  - `data class CbzMetaUpdatePlan(volumeId: String, comicInfoXml: String, targetDisplayName: String, entryCount: Int)`
  - `sealed interface CbzMetaUpdateOutcome { data class Updated(entryCount: Int, xmlBytes: Int); data class Failed(reason: UpdateFailure, detail: String?) }`
  - `enum class UpdateFailure { TargetMissing, PermissionLost, NoSpace, NotAZip, ReadFailed, ReplaceFailed, Cancelled }`
  - `sealed interface UpdatePreflight { data class Ready(estimatedReadBytes: Long?, estimatedWriteBytes: Long?); data class NoSpace(requiredBytes: Long, availableBytes: Long); object Unknown }`
  - `interface CbzMetaUpdater { fun targetExists(volume: ComicVolume): Boolean; fun preflight(plan: CbzMetaUpdatePlan, volume: ComicVolume): UpdatePreflight; fun update(plan: CbzMetaUpdatePlan, volume: ComicVolume, shouldStop: () -> Boolean, onProgress: (done: Int, total: Int) -> Unit): CbzMetaUpdateOutcome }`
  - `fun isComicInfoEntry(name: String): Boolean`
  - `fun rewrittenEntryOrder(oldNames: List<String>): List<String>`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.cyanix.pixfold.domain.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CbzMetaUpdateTest {

    @Test
    fun `isComicInfoEntry ignores case and directory prefix`() {
        assertTrue(isComicInfoEntry("ComicInfo.xml"))
        assertTrue(isComicInfoEntry("comicinfo.xml"))
        assertTrue(isComicInfoEntry("COMICINFO.XML"))
        assertTrue(isComicInfoEntry("meta/ComicInfo.xml"))
        assertFalse(isComicInfoEntry("ComicInfo.xml.bak"))
        assertFalse(isComicInfoEntry("001.jpg"))
    }

    @Test
    fun `rewrittenEntryOrder puts ComicInfo first when it was in the middle`() {
        val old = listOf("001.jpg", "002.jpg", "ComicInfo.xml", "003.jpg")
        assertEquals(
            listOf("ComicInfo.xml", "001.jpg", "002.jpg", "003.jpg"),
            rewrittenEntryOrder(old),
        )
    }

    @Test
    fun `rewrittenEntryOrder drops every case variant of ComicInfo`() {
        val old = listOf("ComicInfo.xml", "001.jpg", "comicinfo.xml")
        assertEquals(listOf("ComicInfo.xml", "001.jpg"), rewrittenEntryOrder(old))
    }

    @Test
    fun `rewrittenEntryOrder works when the archive had no ComicInfo`() {
        assertEquals(
            listOf("ComicInfo.xml", "001.jpg"),
            rewrittenEntryOrder(listOf("001.jpg")),
        )
    }

    @Test
    fun `plan rejects an empty xml and an empty display name`() {
        val ok = CbzMetaUpdatePlan(
            volumeId = "v", comicInfoXml = "<x/>", targetDisplayName = "a.cbz", entryCount = 0,
        )
        assertEquals(0, ok.entryCount)
        assertTrue(runCatching { ok.copy(comicInfoXml = "") }.isFailure)
        assertTrue(runCatching { ok.copy(targetDisplayName = "") }.isFailure)
    }
}
```

- [ ] **Step 2: 跑测试确认红**

```bash
cd android && cmd.exe /c "run-gradle.bat :domain:test --rerun-tasks"
```
Expected: `domain` 编译失败 —— `Unresolved reference: isComicInfoEntry` / `CbzMetaUpdatePlan`。

- [ ] **Step 3: 实现 `CbzMetaUpdate.kt`**

按 spec §4.1 / §4.2 / §4.3 / §4.4 落地。要点：
- `CbzMetaUpdatePlan` 两个 `require`（`comicInfoXml.isNotEmpty()` / `targetDisplayName.isNotEmpty()`），**没有 `pages` 字段**。
- `isComicInfoEntry` = `name.substringAfterLast('/').equals(CbzWriter.COMIC_INFO_ENTRY, ignoreCase = true)`。
- `CbzMetaUpdater.update` 的 `shouldStop` 是**无参** lambda；KDoc 写明「只在条目边界检查」。平台实现里用它；领域转写器不接它（见 Task 2 的返工说明）。
- 每个 `UpdateFailure` 常量上方一行 KDoc 说明「用户该做什么」。

- [ ] **Step 4: 跑测试确认绿**

Expected: `domain` 测试 +5 全过，其余不动。

- [ ] **Step 5: 提交**

```bash
git add android/domain/src/main/kotlin/com/cyanix/pixfold/domain/pack/CbzMetaUpdate.kt \
        android/domain/src/test/kotlin/com/cyanix/pixfold/domain/pack/CbzMetaUpdateTest.kt
git commit -m "feat(domain): 工作流 C 的计划/结果/端口与纯函数"
```

---

### Task 2: 流式转写器 `CbzMetaRewriter`

**Files:**
- Create: `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/pack/CbzMetaRewriter.kt`
- Test: `android/domain/src/test/kotlin/com/cyanix/pixfold/domain/pack/CbzMetaRewriterTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `isComicInfoEntry`；`CbzWriter.COMIC_INFO_ENTRY`。
- Produces: `object CbzMetaRewriter { fun rewrite(input: InputStream, out: OutputStream, comicInfoXml: String, timeMillis: Long, spillDir: File?, onEntryWritten: (done: Int, total: Int) -> Unit = { _, _ -> }): Int }`

- [ ] **Step 1: 写失败测试（spec §9.1 的 T1–T12）**

测试内的 ZIP 构造 helper（**测试自己写，不复用生产力**）：

```kotlin
private fun zipped(
    vararg entries: Pair<String, ByteArray>,
    deflated: Set<String> = emptySet(),
): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zip ->
        for ((name, data) in entries) {
            zip.setLevel(if (name in deflated) 9 else 0)
            val e = ZipEntry(name).apply { time = TIME }
            if (name !in deflated) {
                val crc = CRC32().apply { update(data) }.value
                e.size = data.size.toLong(); e.compressedSize = data.size.toLong(); e.crc = crc
            }
            zip.putNextEntry(e); zip.write(data); zip.closeEntry()
        }
    }
    return bos.toByteArray()
}
```

断言一律用 `ZipFile`（中央目录才看得见 method/size/crc），**不使用 `ZipInputStream`**。十二条按 spec §9.1 表格逐条实现，其中五条关键断言：

```kotlin
@Test
fun `old ComicInfo in the middle lands first and the rest keep order`() {  // T2
    val out = ByteArrayOutputStream()
    CbzMetaRewriter.rewrite(
        zipped("001.jpg" to bytes("A"), "ComicInfo.xml" to bytes("<old/>"), "002.jpg" to bytes("B")).inputStream(),
        out, "<new/>", TIME, null,
    )
    ZipFile(fileOf(out)).use { zip ->
        assertEquals(listOf("ComicInfo.xml", "001.jpg", "002.jpg"), zip.entries().toList().map { it.name })
        assertEquals("<new/>", zip.getInputStream(zip.getEntry("ComicInfo.xml")!!).readBytes().toString(Charsets.UTF_8))
    }
}

@Test
fun `two ComicInfo variants collapse into one`() {  // T3
    val out = ByteArrayOutputStream()
    CbzMetaRewriter.rewrite(
        zipped("ComicInfo.xml" to bytes("<a/>"), "001.jpg" to bytes("A"), "comicinfo.xml" to bytes("<b/>")).inputStream(),
        out, "<new/>", TIME, null,
    )
    ZipFile(fileOf(out)).use { zip ->
        assertEquals(1, zip.entries().toList().count { it.name.equals("ComicInfo.xml", true) })
    }
}

@Test
fun `deflated input entries become stored with identical bytes`() {  // T7
    val out = ByteArrayOutputStream()
    CbzMetaRewriter.rewrite(
        zipped("001.jpg" to bytes("PAYLOAD"), deflated = setOf("001.jpg")).inputStream(),
        out, "<new/>", TIME, tempDir,
    )
    ZipFile(fileOf(out)).use { zip ->
        val e = zip.getEntry("001.jpg")!!
        assertEquals(ZipEntry.STORED, e.method)
        assertEquals("PAYLOAD", zip.getInputStream(e).readBytes().toString(Charsets.UTF_8))
    }
}

@Test
fun `deflated input without a spill dir is rejected`() {  // T8
    assertThrows(IllegalStateException::class.java) {
        CbzMetaRewriter.rewrite(
            zipped("001.jpg" to bytes("P"), deflated = setOf("001.jpg")).inputStream(),
            ByteArrayOutputStream(), "<new/>", TIME, null,
        )
    }
}

@Test
fun `an archive with no entries still yields exactly one ComicInfo`() {  // T10
    val empty = ByteArrayOutputStream().also { ZipOutputStream(it).use { z -> z.finish() } }.toByteArray()
    val out = ByteArrayOutputStream()
    assertEquals(1, CbzMetaRewriter.rewrite(empty.inputStream(), out, "<new/>", TIME, null))
    ZipFile(fileOf(out)).use { assertEquals(listOf("ComicInfo.xml"), it.entries().toList().map { e -> e.name }) }
}

@Test
fun `progress advances once per written entry`() {  // 原 T9 替换为回调序列断言
    val seen = mutableListOf<Int>()
    CbzMetaRewriter.rewrite(
        zipped("001.jpg" to bytes("A"), "002.jpg" to bytes("B")).inputStream(),
        ByteArrayOutputStream(), "<new/>", TIME, null,
        onEntryWritten = { done, _ -> seen += done },
    )
    assertEquals(listOf(1, 2, 3), seen)   // ComicInfo 先写 = 第 1 条
}
```

- [ ] **Step 2: 跑测试确认红**

Expected: `Unresolved reference: CbzMetaRewriter`。

- [ ] **Step 3: 实现 `rewrite`**

按 spec §6.2 三步：

1. `ZipOutputStream(BufferedOutputStream(out)).apply { setLevel(0) }`。**先写新 XML**：`putNextEntry` 前把 `size` / `compressedSize` / `crc` / `time` 设齐（否则 `ZipException: STORED entry missing size, compressed size, or crc-32`）。
2. `ZipInputStream(BufferedInputStream(input))` 循环 `nextEntry`：
   - `isComicInfoEntry(name)` ⇒ 丢弃，`continue`。
   - 否则复制：**统一读进 spill 或内存**（见下），算齐 size/crc 再 `putNextEntry`。
   - `spillDir == null` 且必须 spill（内容超过内存阈值）⇒ 抛 `IllegalStateException`。
3. `zip.finish()`（**不是 `close()`**），返回条目总数。

**为什么「不必区分 STORED / DEFLATED 的输出路径」**：`setLevel(0)` 后所有条目都 STORED，都必须自带 size/crc。而 `ZipInputStream` 对 STORED 报的 size/crc 可能来自本地头（第三方工具可能写错或留 0），**信它会让 central directory 与真实字节不符**（`CbzReaderCompatTest.kt:166` 的 CRC 断言会红）。统一口径：**流出多少字节就算多少**。

**内存 vs spill 的判据**：条目 `size` 已知且 `< SPILL_THRESHOLD_BYTES`（`8L * 1024 * 1024`）⇒ 读进 `ByteArray`；否则落 spill。size 未知（DEFLATED）⇒ 先落 spill（因为不知道会有多大）。`spillDir == null` 时退化为内存，超过 8 MiB 抛 `IllegalStateException`（KDoc 写明：调用方给 `null` 就是承诺「不会有超大条目」）。

`spillDir` 的临时文件：`File.createTempFile("cbzmeta-", ".bin", spillDir)`，用 `use` 保证删。

KDoc 必写三条：不关 `input` / `out`；旧条目 mtime 不保留（spec §6.4）；产出全 STORED 且 ComicInfo 恒第 0 条。

- [ ] **Step 4: 跑测试确认绿**

Expected: `domain` 测试 +12 全过。

- [ ] **Step 5: 提交**

```bash
git add android/domain/src/main/kotlin/com/cyanix/pixfold/domain/pack/CbzMetaRewriter.kt \
        android/domain/src/test/kotlin/com/cyanix/pixfold/domain/pack/CbzMetaRewriterTest.kt
git commit -m "feat(domain): CbzMetaRewriter 流式转写（全 STORED / ComicInfo 恒第 0 条 / DEFLATED 落 spill）"
```

---


### Task 3: 回填当前值 `withComicInfoValues`

**Files:**
- Modify: `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/comic/ComicInfoReader.kt`（追加函数，不动既有）
- Test: `android/domain/src/test/kotlin/com/cyanix/pixfold/domain/comic/WithComicInfoValuesTest.kt`

**Interfaces:**
- Consumes: `parseComicInfo(xml): ComicInfoData?`（已存在，`:60`）、`CbzMetaUpdateOutcome.Updated`
- Produces: `fun withComicInfoValues(volume: ComicVolume, xml: String): ComicVolume`

- [ ] **Step 1: 写失败测试**

```kotlin
private fun volume() = ComicVolume(
    id = "v", dirPath = "a", pages = emptyList(),
    titleSug = Suggestion("建议标题", "目录"), seriesSug = Suggestion("建议系列", "目录"),
    writerSug = Suggestion("建议作者", "目录"), volumeSug = Suggestion(1, "目录"),
    langSug = LangChoice.Unset, outputDirSug = "", outputExists = true,
    title = "旧标题", series = "旧系列", writer = "旧作者", volume = 1,
    language = LangChoice.Unset, cbzFileName = "旧.cbz", outputDir = "",
)

@Test
fun `only current values change, suggestions stay`() {
    val xml = ComicInfo.build(volume().copy(title = "新标题", series = "新系列", writer = "新作者", volume = 2))
    val out = withComicInfoValues(volume(), xml)
    assertEquals("新标题", out.title)
    assertEquals("新系列", out.series)
    assertEquals("新作者", out.writer)
    assertEquals(2, out.volume)
    assertEquals("建议标题", out.titleSug.value)
    assertEquals("建议作者", out.writerSug.value)
}

@Test
fun `missing tags leave the current value empty rather than falling back to a suggestion`() {
    val xml = ComicInfo.build(volume().copy(title = "只有标题", series = "", writer = "", volume = null))
    val out = withComicInfoValues(volume(), xml)
    assertEquals("只有标题", out.title)
    assertEquals("", out.series)
    assertEquals(null, out.volume)
}

@Test
fun `cbz file name and output dir are untouched`() {
    val out = withComicInfoValues(volume(), ComicInfo.build(volume().copy(title = "新标题")))
    assertEquals("旧.cbz", out.cbzFileName)
    assertEquals("", out.outputDir)
}

@Test
fun `unparseable xml leaves the volume as-is`() {
    val original = volume()
    assertEquals(original, withComicInfoValues(original, "这不是 xml"))
}
```

- [ ] **Step 2: 跑测试确认红**

Expected: `Unresolved reference: withComicInfoValues`。

- [ ] **Step 3: 实现 `withComicInfoValues`**

```kotlin
/**
 * 用一个**刚写进磁盘的** XML 回填当前值。
 *
 * 与 [applyExistingComicInfo] 同一套规则（只改当前值、建议值一个字不动、
 * 缺失的标签把当前值留空而不是落回建议值），区别只在数据来源：
 * 那边是探针读文件，这里是手里已有 XML 全文。
 *
 * 为什么不去重读磁盘：刚写的就是它；读一遍多一次整包打开 + ZipInputStream 扫描，
 * 换来的是「可能读到一半被别的应用改了」的不确定性。
 *
 * 解析失败 ⇒ 原样返回（不改任何值，最坏是界面显示旧值，好过显示乱码）。
 */
fun withComicInfoValues(volume: ComicVolume, xml: String): ComicVolume {
    val data = parseComicInfo(xml) ?: return volume
    return volume.copy(
        title = data.title.orEmpty(),
        series = data.series.orEmpty(),
        writer = data.writer.orEmpty(),
        volume = data.number,
        language = data.language,
    )
}
```

**注意** `series` 这一行的特例：`applyExistingComicInfo` 在 `data.series == null && volume.seriesDir.isEmpty()` 时保留 `volume.seriesSug` 的值而不是留空。这里**不改**这个行为 —— 直接 `orEmpty()` 会让「单本没有 Series 标签」的卷显示空系列，与重新打包时看到的当前值不一致。⇒ 照抄 `applyExistingComicInfo` 的三分支：

```kotlin
val series = when {
    data.series != null -> data.series
    volume.seriesDir.isEmpty() -> volume.seriesSug.value.orEmpty()
    else -> ""
}
```

- [ ] **Step 4: 加一条对齐断言并跑绿**

在测试里补：

```kotlin
@Test
fun `agrees with applyExistingComicInfo on the same xml`() {
    val xml = ComicInfo.build(volume().copy(title = "T", series = "S", writer = "W", volume = 3))
    val viaXml = withComicInfoValues(volume(), xml)
    val viaProbe = applyExistingComicInfo(listOf(volume()), ExistingComicInfoProbe { parseComicInfo(xml) }).single()
    assertEquals(viaProbe.title, viaXml.title)
    assertEquals(viaProbe.series, viaXml.series)
    assertEquals(viaProbe.writer, viaXml.writer)
    assertEquals(viaProbe.volume, viaXml.volume)
}
```

Expected: `domain` 测试 +5 全过。

- [ ] **Step 5: 提交**

```bash
git add android/domain/src/main/kotlin/com/cyanix/pixfold/domain/comic/ComicInfoReader.kt \
        android/domain/src/test/kotlin/com/cyanix/pixfold/domain/comic/WithComicInfoValuesTest.kt
git commit -m "feat(domain): withComicInfoValues 用刚写入的 XML 回填当前值"
```

---

### Task 4: 平台层 `SafMetaUpdater`

**Files:**
- Modify: `android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafPackPlatform.kt`（抽 `internal` 顶层函数）
- Create: `android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafMetaUpdater.kt`
- Test: `android/app/src/test/kotlin/com/cyanix/pixfold/data/saf/SafMetaUpdaterTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `CbzMetaUpdater` / `UpdatePreflight` / `UpdateFailure`；Task 2 的 `CbzMetaRewriter.rewrite`；既有 `FilesystemSpaceSource.availableBytesOf(treeUri): Long?`（`SafPackPlatform.kt:367`）、`outputDirRelOf(outputDir, rootDisplayName): String`（`SafPackExecutor.kt:213`，`internal`）
- Produces:
  - `class SafMetaUpdater(context: Context, treeUri: Uri, space: FilesystemSpaceSource, dirOfVolume: (ComicVolume) -> String, now: () -> Long = System::currentTimeMillis) : CbzMetaUpdater`
  - `internal fun safParentDocumentUri(resolver: ContentResolver, treeUri: Uri, dirRel: String): Uri?`
  - `internal fun safFindChild(resolver: ContentResolver, treeUri: Uri, parentUri: Uri, displayName: String): Uri?`

- [ ] **Step 1: 先把三个私有方法抽成 `internal` 顶层函数（行为零变化）**

`SafPackPlatform.kt` 里 `parentDocumentUri`(`:126`) / `findChild`(`:148`) / `queryChildDocId`(`:158`) / `queryChildren`(`:171`) 四个方法体逐字搬成文件级 `internal` 函数，参数补 `resolver` / `treeUri`；`SafCbzOutputSink` 的私有方法体改成一行委托。

跑 `SafCbzOutputSink` / `SafPackExecutor` 的既有测试确认 **零红**（这次重构的目标就是零行为变化）。

- [ ] **Step 2: 写失败测试**

Robolectric 无真实 `DocumentsProvider` ⇒ 只断纯逻辑（spec §9.2）。用可注入的 `now` 与假 `FilesystemSpaceSource`：

```kotlin
@Test
fun `preflight says Unknown when size or space cannot be determined`() // 两者任一为 null
@Test
fun `preflight says NoSpace when available is below input plus the 32 MiB margin`() // 断言 required == input + 32MiB
@Test
fun `preflight says Ready with estimatedWriteBytes equal to required`()
```

⚠️ `FilesystemSpaceSource` 是 `class` 不是接口 ⇒ 测不了。**裁决：`SafMetaUpdater` 的构造参数改成 `spaceBytesOf: (Uri) -> Long?` 的函数类型**（默认值在 `PixFoldApp` 里传 `FilesystemSpaceSource(context)::availableBytesOf`），测试直接传 lambda。**不改 `FilesystemSpaceSource` 本身**（它被打包链用着，改签名会波及 6 个测试）。

- [ ] **Step 3: 实现 `SafMetaUpdater`**

按 spec §7.1–§7.8 逐节落地。必须包含：

1. `findTarget(volume)` = `safParentDocumentUri` → `safFindChild(displayName = volume.cbzFileName)`。
2. `targetExists` = `findTarget(volume) != null`。
3. `preflight` 按 spec §7.5（`SAFETY_MARGIN_BYTES = 32L * 1024 * 1024`；两者任一 null ⇒ `Unknown`）。
4. `update` 顺序：`findTarget` → 建临时包 `.updating` → `openInputStream` + `openOutputStream` 双 `use` 包住 `CbzMetaRewriter.rewrite`（`spillDir = cacheDir`）→ **先关输出流** → 三段式替换（原包 → `.bak` → 临时 → 原名 → 删 `.bak`）→ 每步失败按 spec §7.4 表格处理。
5. `shouldStop()` 在两处检查：条目循环回调里（`onEntryWritten` 里若 `shouldStop()` 就抛一个内部 `CancelledException` 由 `update` 捕获取 → 删临时包 → `Failed(Cancelled)`）、替换阶段开始前。

KDoc 必写：所有方法阻塞 IO；§7.4 的五个中间态（表格逐行照抄）；替换步骤 3 失败算成功的理由。

- [ ] **Step 4: 跑测试确认绿**

Expected: `app` 测试 +3 全过；`SafCbzOutputSink` 既有断言零红。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafPackPlatform.kt \
        android/app/src/main/kotlin/com/cyanix/pixfold/data/saf/SafMetaUpdater.kt \
        android/app/src/test/kotlin/com/cyanix/pixfold/data/saf/SafMetaUpdaterTest.kt
git commit -m "feat(saf): SafMetaUpdater（临时包 + 三段式替换 + 空间预检）"
```

---

### Task 5: 文案、图标与三个弹窗

**Files:**
- Modify: `android/domain/src/main/kotlin/com/cyanix/pixfold/domain/text/TextKey.kt`
- Modify: `android/app/src/main/res/values/strings_library.xml` + `values-zh/strings_library.xml`
- Create: `android/app/src/main/res/drawable/ic_edit_note.xml`
- Create: `android/app/src/main/kotlin/com/cyanix/pixfold/ui/library/CbzUpdateDialogs.kt`
- Test: 无新测试文件（三个弹窗在下个任务的 UI 测试里被断言）

**Interfaces:**
- Consumes: Task 1 的 `CbzMetaUpdateOutcome` / `UpdateFailure`；`PackProgressDialog`（`:91`）；`PixFoldAlertDialog`
- Produces:
  - `const val TAG_UPDATE_CONFIRM_DIALOG = "update-confirm-dialog"` / `TAG_UPDATE_RESULT_DIALOG = "update-result-dialog"`
  - `@Composable fun CbzUpdateConfirmDialog(volume: ComicVolume, entryCount: Int?, sizeBytes: Long?, onConfirm: () -> Unit, onCancel: () -> Unit)`
  - `@Composable fun CbzUpdateResultDialog(outcome: CbzMetaUpdateOutcome, volumeName: String, onDone: () -> Unit)`
  - `fun updateFailureKey(reason: UpdateFailure): TextKey`

- [ ] **Step 1: 加 8 个 TextKey 与 10 条字符串**

按 spec §5.1 / §5.2 逐字。`TextKey` 追加段放在 `NestedSeriesDir` 之后，段首注释 `// ---------------- 工作流 C：就地更新已有 CBZ 的元数据（工作流 C 弹窗） ----------------`。

`values` 与 `values-zh` 各 10 条（`update_meta_action` / `_action_cd` / `_title` / `_body_size` / `_body_entries` / `_unknown_size` / `_confirm` / `_progress_title` / `_done_title` / `_done_body` / `_fail_title` —— 实为 11 条，`update_meta_cancel` 已裁掉，见 spec §5.2 表）。

- [ ] **Step 2: 加图标 `ic_edit_note.xml`**

24dp / viewport 24 / `?attr/colorControlNormal` 填充。形状：一支笔 + 三条横线（沿用 Material `edit_note` 的路径数据）。**不要在 `build.gradle.kts` 里加 `material-icons-extended` 依赖**（AGENTS.md 明令，包体积不值）。

- [ ] **Step 3: 写 `CbzUpdateDialogs.kt`**

三个 composable + 一个映射函数。要点：

- `updateFailureKey` 用 `when (reason)` 全覆盖七个 `UpdateFailure`（`Cancelled` 不给文案，调用方不展示结果框——取消是用户自己按的）。
- 确认框正文两行（spec §8.3）：体积行必显（取不到就用 `unknown_size`）+ 条目行仅在 `entryCount != null` 时显示。
- 结果框正文（spec §8.5）：成功走 `update_meta_done_body`；`Failed` 走 `updateFailureKey`；`detail` 非空时换行追加。
- 「确定」按钮 `R.string.domain_general_confirm_ok`、「取消」`R.string.common_cancel`。

- [ ] **Step 4: 编译并跑 lint**

```bash
cd android && cmd.exe /c "run-gradle.bat :app:lintDebug :app:assembleDebug --rerun-tasks"
```
Expected: `BUILD SUCCESSFUL`、lint `No issues found.`（新增资源已在本任务被引用，无 `UnusedResources`）。

- [ ] **Step 5: 提交**

```bash
git commit -m "feat(ui): 工作流 C 的文案、图标与三个弹窗"
```

---

### Task 6: 两处入口（库页卷行 + 元数据页底栏）

**Files:**
- Modify: `android/app/src/main/kotlin/com/cyanix/pixfold/ui/library/LibraryPage.kt`
- Modify: `android/app/src/main/kotlin/com/cyanix/pixfold/ui/library/MetadataPage.kt`
- Test: `android/app/src/test/kotlin/com/cyanix/pixfold/ui/library/CbzUpdateEntryTest.kt`

**Interfaces:**
- Consumes: Task 5 的 `ic_edit_note` 与 `update_meta_action` / `_action_cd`
- Produces:
  - `fun volumeUpdateTag(id: String) = "library-update-$id"`（`LibraryPage.kt` 顶部）
  - `const val TAG_METADATA_UPDATE = "metadata-update"`（`MetadataPage.kt` 顶部）
  - `LibraryPage(..., onUpdateMeta: ((ComicVolume) -> Unit)? = null)`
  - `MetadataPage(..., onUpdateMeta: ((ComicVolume) -> Unit)? = null)`
  - `VolumeRow(..., onUpdateMeta: (() -> Unit)? = null)`

- [ ] **Step 1: 写失败测试（spec §9.3 的 U1–U4）**

```kotlin
@Test
fun `no update button when the volume has no archive on disk`() {
    composeRule.setContent {
        LibraryPage(state = stateOf(vol(outputExists = false)), onStateChange = {}, onUpdateMeta = {})
    }
    composeRule.onNodeWithTag(volumeUpdateTag("v")).assertDoesNotExist()
}

@Test
fun `the update button appears and reports the right volume`() {
    var picked: String? = null
    composeRule.setContent {
        LibraryPage(state = stateOf(vol(outputExists = true)), onStateChange = {}, onUpdateMeta = { picked = it.id })
    }
    composeRule.onNodeWithTag(volumeUpdateTag("v")).performClick()
    assertEquals("v", picked)
}

@Test
fun `clicking the row itself still selects instead of updating`() {
    var selected: String? = null; var updated = false
    composeRule.setContent {
        LibraryPage(state = stateOf(vol(outputExists = true)), onStateChange = { selected = it.activeVolumeId }, onUpdateMeta = { updated = true })
    }
    composeRule.onNodeWithTag(volumeCardTag("v")).performClick()
    assertEquals("v", selected)
    assertFalse(updated)
}

@Test
fun `metadata footer shows update only when a volume with an archive is active`() {
    composeRule.setContent { MetadataPage(libraryState = emptyState(), onLibraryChange = {}, onUpdateMeta = {}) }
    composeRule.onNodeWithTag(TAG_METADATA_UPDATE).assertDoesNotExist()
}
```

- [ ] **Step 2: 跑测试确认红**

Expected: `onUpdateMeta` 参数不存在 ⇒ 编译失败。

- [ ] **Step 3: 实现两处入口**

- `LibraryPage`：`VolumeRow` 里 `Column(weight(1f))` 之后加 `IconButton`（spec §8.1）。判据 `onUpdateMeta != null && volume.outputExists`。
- `MetadataPage`：底栏 `PageActionBar` 用 `extraContent` 槽位（spec §8.2），`OutlinedButton` 占满整行。判据 `onUpdateMeta != null && libraryState.activeVolumeOrNull?.outputExists == true`。
- 两处新增参数**必须带默认值 `null`**。
- 空态分支 `MetadataEmptyState` 也要传 `onUpdateMeta = null`（那里没有活动卷）。

- [ ] **Step 4: 跑测试确认绿**

Expected: `app` 测试 +4 全过。

- [ ] **Step 5: 提交**

```bash
git commit -m "feat(ui): 库页卷行与元数据页底栏各加一个更新元数据入口"
```

---

### Task 7: 接线（`LibrarySteps` + `PixFoldApp`）

**Files:**
- Modify: `android/app/src/main/kotlin/com/cyanix/pixfold/ui/library/LibrarySteps.kt`
- Modify: `android/app/src/main/kotlin/com/cyanix/pixfold/PixFoldApp.kt`
- Test: `android/app/src/test/kotlin/com/cyanix/pixfold/ui/library/CbzUpdateFlowTest.kt`

**Interfaces:**
- Consumes: Task 1 `CbzMetaUpdater` / `CbzMetaUpdatePlan` / `CbzMetaUpdateOutcome`；Task 3 `withComicInfoValues`；Task 4 `SafMetaUpdater`；Task 5 三个弹窗；Task 6 两个入口
- Produces:
  - `fun LibrarySteps(..., metaUpdater: CbzMetaUpdater? = null)`
  - `PixFoldApp(..., metaUpdater: CbzMetaUpdater? = null)`（默认 `null` ⇒ 生产在内部构造）
  - 测试假实现 `android/app/src/test/kotlin/com/cyanix/pixfold/FakeCbzMetaUpdater.kt`

- [ ] **Step 1: 写 `FakeCbzMetaUpdater`**

```kotlin
class FakeCbzMetaUpdater(
    var outcome: CbzMetaUpdateOutcome = CbzMetaUpdateOutcome.Updated(entryCount = 3, xmlBytes = 42),
    var exists: Boolean = true,
    var preflight: UpdatePreflight = UpdatePreflight.Unknown,
    val progressSteps: List<Int> = listOf(1, 2, 3),
) : CbzMetaUpdater {
    var updateCalls = 0; private set
    var lastPlan: CbzMetaUpdatePlan? = null; private set
    override fun targetExists(volume: ComicVolume) = exists
    override fun preflight(plan: CbzMetaUpdatePlan, volume: ComicVolume) = preflight
    override fun update(plan, volume, shouldStop, onProgress): CbzMetaUpdateOutcome {
        updateCalls++; lastPlan = plan
        progressSteps.forEach { onProgress(it, progressSteps.size) }
        return outcome
    }
}
```

- [ ] **Step 2: 写失败测试（spec §9.3 的 U5–U6）**

```kotlin
@Test
fun `no entry anywhere when the updater is not injected`() {
    // 两个入口都不渲染（生产在无工作区时不会有 updater）
}

@Test
fun `confirm leads to progress then the result dialog`() {
    val fake = FakeCbzMetaUpdater()
    // 点入口 → 确认框出现 → 点确认 → 结果框出现，且 fake.updateCalls == 1
    // 断言 fake.lastPlan!!.comicInfoXml 等于 ComicInfo.build(该卷)
}

@Test
fun `a successful update rewrites the current values in the library state`() {
    // 完成后 onLibraryChange 收到的卷 title 等于新 XML 里的值
}

@Test
fun `cancelling closes the progress dialog and reports nothing`() {
    // failed(Cancelled) ⇒ 不弹结果框
}
```

- [ ] **Step 3: 实现接线**

按 spec §8.6 / §8.7 / §8.8：
- `LibrarySteps` 加状态（`updateTarget` / `updating` / `updateDone` / `updateTotal` / `cancelRequested` / `cancelToken` / `updateResult`），全部 `remember(rootPath)`。
- 三个弹窗按状态渲染（`if (updateTarget != null)` / `if (updating)` / `if (result != null && result !is Failed@Cancelled)`）。
- 执行：`scope.launch { withContext(Dispatchers.IO) { updater.update(...) } }`，**每次执行前复位 `cancelRequested`**。
- 成功后：`withComicInfoValues` 回填 + `onLibraryChange`。
- 两个入口的 `onUpdateMeta` 都写成 `{ updateTarget = it }`（确认框里才真正执行）。
- `PixFoldApp`：`metaUpdater` 参数默认 `null` ⇒ 生产路径用 `remember(workspaceHandle) { SafMetaUpdater(...) }` 构造（见 spec §8.7 的代码块，`dirOfVolume` 用 `outputDirRelOf(vol.outputDir, it.displayName)`）。

- [ ] **Step 4: 跑测试确认绿**

Expected: `app` 测试 +4 全过，既有 24 处导航测试零红。

- [ ] **Step 5: 提交**

```bash
git commit -m "feat(ui): 工作流 C 接线（确认 → 进度 → 结果 + 成功后回填当前值）"
```

---

### Task 8: 四道闸门 + 变异验证 + 文档回写 + 关机

**Files:**
- Modify: `HANGOFF.md`、`docs/phases/d4-mvp.md`、`docs/INDEX.md`、`README.md`、`AGENTS.md`（头部当前阶段一句话）
- Create: `.superpowers/sdd/2026-10-26-cbz-update/progress.md`（gitignore）

- [ ] **Step 1: 四道闸门**

```bash
cd android && ./gate.sh
```
判绿三条硬判据：`build/gate-last.rc` == 0；日志里 `app` / `domain` / `lintDebug` / `assembleDebug` 四个 task **不是** `UP-TO-DATE` / `FROM-CACHE`（否则 `--rerun-tasks` 重跑）；lint 行是 `No issues found.`（不看 `grep -c`）。
读数记录：app 测试数 / domain 测试数 / APK 字节数（用 Python 解析 JUnit XML，本机无 `bc`）。

- [ ] **Step 2: 变异验证（spec §9.4 的 M1–M8）**

逐条做：备份 → 改一处 → 跑测试 → 记录红了几条 → `cp` 备份还原（**绝不用 `git checkout --`**）。每条变异必须在同一 bash 调用内完成备份+变异+还原。存活则删断言或写明为什么刻意不设。

- [ ] **Step 3: 文档回写五处**

- `HANGOFF.md`：§9.7 追加「工作流 C：就地更新元数据」节（决策 + 已实现 + 只能真机断清单）。
- `docs/phases/d4-mvp.md`：工作流 C 的实现留痕。
- `docs/INDEX.md`：登记新 spec 与新 plan 两行。
- `README.md`：能力清单加一条。
- `AGENTS.md`：头部「当前阶段」一句话更新（**不得写台账**，历史进 docs）。

- [ ] **Step 4: 提交 + 密钥扫描**

```bash
git add -A && git commit -m "docs: 工作流 C（就地更新 CBZ 元数据）实现与回写"
betterleaks git . --redact --revision="origin/dev..HEAD" --include=merges --include=commit-messages
```
判绿：`INF scanned ~NNNNNN bytes`（百万级）+ `no leaks found` + rc=0。**右端点必须是 `HEAD` 不是 `dev`**（阶段分支上写 `dev` 会静默只扫一小段）。

- [ ] **Step 5: 关机**

```bash
cmd.exe /c "shutdown /s /t 60"
```
（真机核对留到用户下次授权；关机前确认 `git status --porcelain` 为空）

---

## 自检

**1. 规格覆盖**：spec §1–§11 每一节都有任务 — §2 硬约束 → Global Constraints；§3 裁决 → 分散到各任务；§4 → Task 1；§5 → Task 1 / Task 5；§6 → Task 2；§7 → Task 4；§8 → Task 5 / 6 / 7；§9 → 各任务的 Step 1 与 Task 8 Step 2；§10 → Task 8 Step 1；§11 → Task 8 Step 3 的文档回写。

**2. 步骤扫描**：每步都是一个可执行动作（写测试 / 跑红 / 实现 / 跑绿 / 提交）。唯一需要实现者判断的是 Task 2 Step 3 的「内存 vs spill 判据」—— 已给阈值 `8L * 1024 * 1024`。

**3. 类型一致性**：`CbzMetaUpdatePlan` / `CbzMetaUpdateOutcome` / `UpdatePreflight` / `UpdateFailure` 的字段名在 Task 1 定义、Task 4/5/7 引用，逐字一致。`rewrite` 签名在 Task 2 定义、Task 4 调用，逐字一致。`withComicInfoValues(volume, xml)` 在 Task 3 定义、Task 7 调用，逐字一致。

**4. Review Focus**：五条各自有归属任务（Task 2 的 T2/T3/T7/T10、Task 4 的失败断言）。

**5. 比例**：spec 约 32 KB / plan 约 30 KB —— 同一量级，不是逐行翻译。

