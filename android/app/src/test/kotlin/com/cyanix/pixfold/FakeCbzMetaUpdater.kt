package com.cyanix.pixfold

import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.pack.CbzMetaUpdateOutcome
import com.cyanix.pixfold.domain.pack.CbzMetaUpdatePlan
import com.cyanix.pixfold.domain.pack.CbzMetaUpdater
import com.cyanix.pixfold.domain.pack.UpdatePreflight

/**
 * 工作流 C 的假平台层。
 *
 * ## 为什么要它
 *
 * 真实实现 `saf` 是 SafMetaUpdater,它要 ContentResolver、要真实的
 * DocumentsProvider、要能真的改名和删文件。Robolectric 里三样都没有 ——
 * 硬用真实现只会测出「provider 不存在」,而不是「界面接线对不对」。
 *
 * ## 为什么 progressSteps 是可配置的
 *
 * 界面要区分「总量已知」与「总量未知」两种进度形态（后者只能转圈）。
 * 假实现默认给出 1..n 的完整序列,需要测未知态时传 emptyList() 即可 ——
 * 那样 onProgress 一次都不会被调,界面保持 total = 0。
 */
class FakeCbzMetaUpdater(
    var outcome: CbzMetaUpdateOutcome = CbzMetaUpdateOutcome.Updated(entryCount = 3, xmlBytes = 42),
    var exists: Boolean = true,
    var preflightResult: UpdatePreflight = UpdatePreflight.Unknown,
    var progressSteps: List<Int> = listOf(1, 2, 3),
) : CbzMetaUpdater {

    /** update 被调了几次。断言「点一次确认只执行一次」用它 —— 重复执行会重写两次包。 */
    var updateCalls: Int = 0
        private set

    /** 最后一次拿到的计划。断言「写进去的 XML 是从这一卷算出来的」。 */
    var lastPlan: CbzMetaUpdatePlan? = null
        private set

    /** 最后一次 shouldStop 的返回值,用来验证「取消真的传到了平台层」。 */
    val stopProbes: MutableList<Boolean> = mutableListOf()

    override fun targetExists(volume: ComicVolume): Boolean = exists

    override fun preflight(plan: CbzMetaUpdatePlan, volume: ComicVolume): UpdatePreflight =
        preflightResult

    override fun update(
        plan: CbzMetaUpdatePlan,
        volume: ComicVolume,
        shouldStop: () -> Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): CbzMetaUpdateOutcome {
        updateCalls++
        lastPlan = plan
        stopProbes += shouldStop()
        progressSteps.forEach { onProgress(it, progressSteps.size) }
        return outcome
    }
}
