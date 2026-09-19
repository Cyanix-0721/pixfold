package com.pixfold.d1.domain.mock

import com.pixfold.d1.domain.comic.comicVolumeOf
import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.model.Suggestion

/** 工作流 B 的 mock 漫画库。 */
data class MockLibraryB(val rootPath: String, val volumes: List<ComicVolume>)

/**
 * 工作流 B 的确定性 mock 数据(语义对齐归档原型 `mock_data.dart` 的 `MockLibraryB`)。
 *
 * **异常样例是语义的一部分**,五卷刻意覆盖验收第 11–14 项要验的每一类情形:
 *
 * | id | 目录 | 页数 | 卷号建议 | 语言建议 | outputExists | 覆盖的语义 |
 * | --- | --- | --- | --- | --- | --- | --- |
 * | `aot-1` | 进击的巨人/第01卷 | 16 | `1`(目录名解析) | `ja`(低置信) | false | 正常样例 |
 * | `aot-2` | 进击的巨人/第02卷 | 18 | `2` | `ja`(低置信) | false | 3 位页码边界(18 页) |
 * | `aot-0` | 进击的巨人/外传 | 12 | `null`(未解析出) | `ja`(推断) | false | **卷号缺失待确认** |
 * | `op-101` | 海贼王/第101卷 | 20 | `101` | `null`(无语言线索) | false | **语言无建议** |
 * | `op-102` | 海贼王/第102卷 | 20 | `102` | `zh`(中置信) | **true** | 输出冲突 + 建议有语言但仍须确认 |
 *
 * 另:所有卷的 `writerSug` 都带 `[作者]…(原作:…)…(电子版)` 噪声,
 * 用来验证"建议 + 一键清理建议"两个值都可见(验收第 11 项:建议值带来源)。
 */
object MockDataB {

    /** 逐卷作者原始建议(带噪声)与清理后建议。 */
    private const val WRITER_RAW_AOT = "[作者]谏山创(原作:某人)(电子版)"
    private const val WRITER_CLEAN_AOT = "谏山创"
    private const val WRITER_RAW_OP = "[作者]尾田荣一郎"
    private const val WRITER_CLEAN_OP = "尾田荣一郎"
    private const val OUTPUT_DIR = "E:\\漫画库\\_output"

    /**
     * Title 建议的来源。
     *
     * Title 取**系列层**标题(内层「第0N卷」由 volume 单独承载):这样默认 CBZ 文件名
     * 「标题 + 第NN卷.cbz」不会出现"卷号重复两次"(若 Title 本身已含卷号,
     * 规格 §8.3 的拼接会产生 `进击的巨人 第02卷 第02卷.cbz`)。
     */
    private const val TITLE_SOURCE = "目录层级 L2（系列层）"

    private fun pages(volumeId: String, volumeDirName: String, count: Int): List<SourceItem> =
        (1..count).map { i ->
            SourceItem(
                id = "$volumeId-p$i",
                collectionId = volumeId,
                dir = "",
                baseName = "${volumeDirName}_p${i.toString().padStart(3, '0')}",
                ext = "jpg",
                sizeBytes = 800_000L + (i * 7919L) % 3_000_000L,
                modifiedEpochMillis = 1_755_000_000_000L + i * 60_000L,
                createdEpochMillis = 1_755_000_000_000L + i * 60_000L,
                seed = volumeId.hashCode() + i,
            )
        }

    private fun volume(
        id: String,
        dir: String,
        dirName: String,
        count: Int,
        title: String,
        series: String,
        writerRaw: String,
        writerClean: String,
        volume: Int?,
        volumeSource: String,
        lang: String?,
        langSource: String,
        outputExists: Boolean,
    ): ComicVolume = comicVolumeOf(
        id = id,
        dirPath = dir,
        pages = pages(id, dirName, count),
        titleSug = Suggestion(title, TITLE_SOURCE),
        seriesSug = Suggestion(series, "目录层级 L2"),
        writerSug = Suggestion(writerRaw, "目录层级 L2 段命名"),
        writerCleanedSug = Suggestion(writerClean, "清理规则：去作者前缀 / 括号原作 / 尾部标签"),
        volumeSug = Suggestion(volume, volumeSource),
        langSug = Suggestion(lang, langSource),
        outputDirSug = OUTPUT_DIR,
        outputExists = outputExists,
    )

    val library: MockLibraryB = MockLibraryB(
        rootPath = "E:\\漫画库",
        volumes = listOf(
            volume(
                id = "aot-1", dir = "E:\\漫画库\\进击的巨人\\第01卷", dirName = "第01卷", count = 16,
                title = "进击的巨人", series = "进击的巨人",
                writerRaw = WRITER_RAW_AOT, writerClean = WRITER_CLEAN_AOT,
                volume = 1, volumeSource = "目录名「第01卷」解析",
                lang = "ja", langSource = "卷内文件名含日文片段（低置信）",
                outputExists = false,
            ),
            volume(
                id = "aot-2", dir = "E:\\漫画库\\进击的巨人\\第02卷", dirName = "第02卷", count = 18,
                title = "进击的巨人", series = "进击的巨人",
                writerRaw = WRITER_RAW_AOT, writerClean = WRITER_CLEAN_AOT,
                volume = 2, volumeSource = "目录名「第02卷」解析",
                lang = "ja", langSource = "卷内文件名含日文片段（低置信）",
                outputExists = false,
            ),
            volume(
                id = "aot-0", dir = "E:\\漫画库\\进击的巨人\\外传", dirName = "外传", count = 12,
                // 外传无卷号 -> Title 保留「外传」字样,默认 CBZ 名才可读
                title = "进击的巨人 外传", series = "进击的巨人",
                writerRaw = WRITER_RAW_AOT, writerClean = WRITER_CLEAN_AOT,
                // 建议为 null + 来源说明"未解析出" -> UI 必须显示待确认
                volume = null, volumeSource = "目录名「外传」未解析出卷号",
                lang = "ja", langSource = "系列内其他卷为 ja（推断）",
                outputExists = false,
            ),
            volume(
                id = "op-101", dir = "E:\\漫画库\\海贼王\\第101卷", dirName = "第101卷", count = 20,
                title = "海贼王", series = "海贼王",
                writerRaw = WRITER_RAW_OP, writerClean = WRITER_CLEAN_OP,
                volume = 101, volumeSource = "目录名「第101卷」解析",
                lang = null, langSource = "无语言线索",
                outputExists = false,
            ),
            volume(
                id = "op-102", dir = "E:\\漫画库\\海贼王\\第102卷", dirName = "第102卷", count = 20,
                title = "海贼王", series = "海贼王",
                writerRaw = WRITER_RAW_OP, writerClean = WRITER_CLEAN_OP,
                volume = 102, volumeSource = "目录名「第102卷」解析",
                lang = "zh", langSource = "卷内文件名以中文命名（中置信）",
                outputExists = true,
            ),
        ),
    )
}
