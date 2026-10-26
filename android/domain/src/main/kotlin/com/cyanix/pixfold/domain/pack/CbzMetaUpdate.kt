package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.model.ComicVolume

/**
 * 工作流 C（就地更新已有 CBZ 的元数据）的**领域契约**。
 *
 * ## 为什么单独成文件
 *
 * 转写动作本身（[CbzMetaRewriter]）只关心字节；「这一次更新针对哪一卷、写完之后算成功还是
 * 失败、失败时用户该做什么」是另一层判断。两者混在一起会让后者无法脱离 I/O 测试 ——
 * 而失败原因是 UI 唯一能据以给建议的东西（「包被删了」与「空间不够」给用户的下一步完全不同）。
 *
 * ## 与打包链的关系
 *
 * 打包链（[CbzWritePlan] / [PackRunner] / [CbzOutputSink]）产出**新包**；这里改**已有包**。
 * 两者刻意不复用同一组类型：
 *
 *  - 那边有 `pages`（要重新编号、要逐页读字节），这边**一个页字节都不读**；
 *  - 那边成功后有 `ExecutionReport` 与撤销记录，这边**没有撤销**（用户裁决 Q3 甲）。
 *
 * 共用的是最底层的 [CbzWriter.COMIC_INFO_ENTRY] 与 `java.util.zip` 本身。
 */

/**
 * 一次更新要写进目标包的完整信息。
 *
 * ## 为什么没有 pages 字段
 *
 * 工作流 C 的裁决 D3 是「页条目名原样保留、不重编号」。如果这里带上 `pages`，
 * 实现者迟早会顺手调 `renumberPages`（打包链里到处都是），而那正好是规格禁止的行为 ——
 * 用户改了元数据却发现自己 001.jpg 变成 002.jpg。**类型上不给这个口子。**
 *
 * @param entryCount 旧包里的条目数。0 表示「还不知道」（确认框里那一行据此隐藏）；
 *                   转写完成后由平台层 `copy(entryCount = 实际值)` 回填 —— 那时才知道准确数字。
 */
data class CbzMetaUpdatePlan(
    val volumeId: String,
    val comicInfoXml: String,
    val targetDisplayName: String,
    val entryCount: Int,
) {
    init {
        require(comicInfoXml.isNotEmpty()) { "ComicInfo.xml 内容不能为空" }
        require(targetDisplayName.isNotEmpty()) { "目标包名不能为空" }
    }
}

/** 一次更新的最终结果。 */
sealed interface CbzMetaUpdateOutcome {

    /**
     * 成功。
     *
     * @param entryCount 新包里的条目总数（含新写入的 ComicInfo.xml）
     * @param xmlBytes   写进新包的 XML 字节数
     */
    data class Updated(val entryCount: Int, val xmlBytes: Int) : CbzMetaUpdateOutcome

    /** 失败。[detail] 是给用户的技术补充（文件名、异常摘要），可以为 null。 */
    data class Failed(val reason: UpdateFailure, val detail: String? = null) : CbzMetaUpdateOutcome
}

/**
 * 失败原因。
 *
 * ## 为什么不合成一个「更新失败」
 *
 * 六种原因下用户该做的事**完全不同**：
 *
 *  - [TargetMissing] —— 包被删了/改名了，去刷新工作区；
 *  - [PermissionLost] —— 授权掉了，重新选文件夹；
 *  - [NoSpace] —— 腾空间；
 *  - [NotAZip] —— 这个文件根本不是 CBZ，换一个；
 *  - [ReadFailed] —— 包坏了或正被别的应用占用，重试；
 *  - [ReplaceFailed] —— 环境问题（只读卷？），最坏情况需要手动删备份；
 *  - [Cancelled] —— 用户自己按的，不需要建议。
 */
enum class UpdateFailure {
    /** 目标包在磁盘上找不到了。建议：回列表刷新。 */
    TargetMissing,

    /** SAF 授权失效（持久授权被撤销 / 树不存在）。建议：重新选工作区。 */
    PermissionLost,

    /** 剩余空间不足（预检或写入时发现）。建议：腾空间后重试。 */
    NoSpace,

    /** 目标文件不是合法 ZIP。建议：换一个文件，或先重新打包。 */
    NotAZip,

    /** 读或写失败（流打不开、IO 异常、包损坏）。建议：重试。 */
    ReadFailed,

    /** 三段式替换失败，原包可能还在备份里。建议：按提示手动恢复。 */
    ReplaceFailed,

    /** 用户主动取消。不给建议。 */
    Cancelled,
}

/**
 * 一次更新开始前的空间判定。
 *
 * 与打包链的 `PackUiPreflight` 保持同一种三态语义：**不知道 ≠ 够用，也不等于拒绝**。
 */
sealed interface UpdatePreflight {

    /**
     * 可以做。
     *
     * @param estimatedReadBytes  从旧包读出的字节数；null = 不知道（第三方 provider 不给大小）
     * @param estimatedWriteBytes 要写到临时包的字节数（≈ 输入大小 + 余量）
     */
    data class Ready(
        val estimatedReadBytes: Long?,
        val estimatedWriteBytes: Long?,
    ) : UpdatePreflight

    /** 空间确实不够。 */
    data class NoSpace(val requiredBytes: Long, val availableBytes: Long) : UpdatePreflight

    /** 无法判定（拿不到包大小或拿不到剩余空间）⇒ **不阻断**，与打包页一致。 */
    data object Unknown : UpdatePreflight
}

/**
 * 平台层端口：把「更新一卷已有 CBZ 的元数据」这件事抽象出来。
 *
 * **所有方法都是阻塞 IO**，调用方负责放到 `Dispatchers.IO`。
 */
interface CbzMetaUpdater {

    /** 目标包是否存在。确认框据此决定要不要给「包不见了」的提示。 */
    fun targetExists(volume: ComicVolume): Boolean

    /** 空间预检。[plan] 里此时还没有真实条目数。 */
    fun preflight(plan: CbzMetaUpdatePlan, volume: ComicVolume): UpdatePreflight

    /**
     * 执行更新。
     *
     * @param shouldStop **只在条目边界**询问（原包一字节未动，删临时包即回到起点；
     *                   停在条目中间会留下中央目录不完整的包）
     * @param onProgress 已写完的条目数 / 总条目数（总数在读到末尾前可能是估计值）
     */
    fun update(
        plan: CbzMetaUpdatePlan,
        volume: ComicVolume,
        shouldStop: () -> Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): CbzMetaUpdateOutcome
}

/**
 * 判断一个 ZIP 条目名是不是 ComicInfo.xml。
 *
 * **大小写不敏感 + 忽略目录前缀**：第三方工具可能写成 `comicinfo.xml`，也可能塞进
 * 子目录。两种都必须被识别成「旧的元数据条目」并丢弃 —— 否则产出包里会出现两个
 * ComicInfo，而 ZIP 对重名的行为未定义（阅读器取哪个不确定）。
 */
fun isComicInfoEntry(name: String): Boolean =
    name.substringAfterLast('/').equals(CbzWriter.COMIC_INFO_ENTRY, ignoreCase = true)

/**
 * 转写后的条目顺序：ComicInfo 恒第一，其余保持原顺序。
 *
 * 阅读器常在不解压整包的情况下只读第一条取元数据（打包链同理，见 [CbzWriter] 文件头），
 * 所以这条顺序是**兼容性契约**而非风格偏好。
 */
fun rewrittenEntryOrder(oldNames: List<String>): List<String> =
    listOf(CbzWriter.COMIC_INFO_ENTRY) + oldNames.filterNot { isComicInfoEntry(it) }
