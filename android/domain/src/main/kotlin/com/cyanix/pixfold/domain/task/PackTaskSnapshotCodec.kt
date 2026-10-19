package com.cyanix.pixfold.domain.task

/**
 * [PackTaskSnapshot] 与字符串之间的编解码（D2b「切后台恢复」）。
 *
 * ## 为什么放在领域层而不是平台层
 *
 * 快照要跨进程存活，落地方式（SharedPreferences / DataStore / 文件）是平台选择，
 * 但**「快照由哪些字段构成」是领域知识**。若编解码写在平台层，字段一改就要在两处
 * 同步（`data class` 与手写的读键逻辑），漏一处就是「重启后部分字段静默丢失」。
 * 放在这里，加字段只需改这一个文件，且能在纯 JVM 单测里来回跑。
 *
 * ## 为什么是 base64 + 行格式，不是 JSON
 *
 * `domain/` 零 Android 依赖、也**不引任何 JSON 库**（`build.gradle.kts` 只有 stdlib）。
 * 为了几个标量字段把它拖进来不值得。行格式 + base64 的好处是：任何用户内容
 * （目录名里可能有 `\n`、`\t`、`=`、引号）都能原样往返，不需要写转义规则。
 *
 * ## 为什么解析一律不抛
 *
 * 这段代码在**冷启动路径**上。历史版本写下的数据、被外部工具改坏的文件、
 * 半截写入 —— 任何一种都可能让解析失败。此时正确行为是「当作没有快照」
 * （走正常启动路径），而不是让应用在启动时崩溃。这也是 [decode] 返回可空的原因：
 * **`null` 同时表示「没有」与「读不懂」，两者对调用方的处理相同**。
 *
 * 格式（每行 `键:值`，值一律 base64url 无填充；`ot` 行可出现多次）：
 * ```
 * v:1
 * id:<taskId>
 * ph:<PackTaskPhase.name>
 * st:<Long>
 * up:<Long>
 * t:<total> d:<done> f:<failed> s:<skipped>
 * cv:<currentVolume>        （可缺席）
 * ot:<outputTarget>         （0..n 次）
 * fd:<failureDetail>        （可缺席）
 * ```
 */
object PackTaskSnapshotCodec {

    /** 当前格式版本。将来若改结构，靠它区分老数据（老数据一律当作读不懂）。 */
    const val VERSION: Int = 1

    private const val KEY_VERSION = "v"
    private const val KEY_TASK_ID = "id"
    private const val KEY_PHASE = "ph"
    private const val KEY_STARTED = "st"
    private const val KEY_UPDATED = "up"
    private const val KEY_PROGRESS = "t"
    private const val KEY_CURRENT_VOLUME = "cv"
    private const val KEY_OUTPUT_TARGET = "ot"
    private const val KEY_FAILURE_DETAIL = "fd"

    private const val SEP = ':'

    private fun enc(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun dec(value: String): String? = try {
        String(java.util.Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        // 非法 base64 ⇒ 整份数据视为读不懂（由调用方回 null）。
        null
    }

    /** 编码。输出可直接放进 `SharedPreferences` 的单个字符串。 */
    fun encode(snapshot: PackTaskSnapshot): String {
        val lines = ArrayList<String>(16)
        lines += line(KEY_VERSION, VERSION.toString())
        lines += line(KEY_TASK_ID, enc(snapshot.taskId))
        lines += line(KEY_PHASE, snapshot.phase.name)
        lines += line(KEY_STARTED, snapshot.startedAtMillis.toString())
        lines += line(KEY_UPDATED, snapshot.updatedAtMillis.toString())
        // 进度四个计数压在一行：它们同生共死，分成四行只会多三处出错机会。
        lines += line(
            KEY_PROGRESS,
            listOf(
                snapshot.progress.total,
                snapshot.progress.done,
                snapshot.progress.failed,
                snapshot.progress.skipped,
            ).joinToString(" "),
        )
        snapshot.currentVolume?.let { lines += line(KEY_CURRENT_VOLUME, enc(it)) }
        for (target in snapshot.outputTargets) {
            lines += line(KEY_OUTPUT_TARGET, enc(target))
        }
        snapshot.failureDetail?.let { lines += line(KEY_FAILURE_DETAIL, enc(it)) }
        return lines.joinToString("\n")
    }

    private fun line(key: String, value: String): String = "$key$SEP$value"

    /**
     * 解码。任何结构问题（缺键、版本不符、数字不是数字、base64 坏掉）一律返回 `null`。
     *
     * 只校验**理解这份数据所必需**的东西，不做业务校验（例如「`taskId` 不能为空」）——
     * 那些由 [PackTaskSnapshot] 自己与调用方负责，在这里重复一遍只会让老数据更难兼容。
     */
    fun decode(raw: String?): PackTaskSnapshot? {
        if (raw.isNullOrEmpty()) return null

        var version: Int? = null
        var taskId: String? = null
        var phase: PackTaskPhase? = null
        var startedAt: Long? = null
        var updatedAt: Long? = null
        var progress: TaskProgress? = null
        var currentVolume: String? = null
        var failureDetail: String? = null
        val targets = ArrayList<String>()

        for (lineText in raw.split('\n')) {
            if (lineText.isEmpty()) continue
            val sep = lineText.indexOf(SEP)
            // 没有分隔符、或键为空 ⇒ 这行不认识。整份数据视为读不懂，
            // 因为「多出来的怪行」意味着写入方与读取方版本已经不一致。
            if (sep <= 0) return null
            val key = lineText.substring(0, sep)
            val value = lineText.substring(sep + 1)
            when (key) {
                KEY_VERSION -> version = value.toIntOrNull() ?: return null
                KEY_TASK_ID -> taskId = dec(value) ?: return null
                KEY_PHASE -> phase = runCatching { PackTaskPhase.valueOf(value) }.getOrNull() ?: return null
                KEY_STARTED -> startedAt = value.toLongOrNull() ?: return null
                KEY_UPDATED -> updatedAt = value.toLongOrNull() ?: return null
                KEY_PROGRESS -> progress = parseProgress(value) ?: return null
                KEY_CURRENT_VOLUME -> currentVolume = dec(value) ?: return null
                KEY_OUTPUT_TARGET -> targets += dec(value) ?: return null
                KEY_FAILURE_DETAIL -> failureDetail = dec(value) ?: return null
                else -> return null
            }
        }

        if (version != VERSION) return null
        return PackTaskSnapshot(
            taskId = taskId ?: return null,
            phase = phase ?: return null,
            startedAtMillis = startedAt ?: return null,
            updatedAtMillis = updatedAt ?: return null,
            progress = progress ?: return null,
            currentVolume = currentVolume,
            outputTargets = targets,
            failureDetail = failureDetail,
        )
    }

    /** `t d f s` 四个十进制整数，空格分隔。 */
    private fun parseProgress(value: String): TaskProgress? {
        val parts = value.split(' ')
        if (parts.size != 4) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return TaskProgress(
            total = numbers[0],
            done = numbers[1],
            failed = numbers[2],
            skipped = numbers[3],
        )
    }
}
