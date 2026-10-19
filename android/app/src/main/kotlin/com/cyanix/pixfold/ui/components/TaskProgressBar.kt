package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.task.TaskProgress

/**
 * 进度条本体的测试 tag。
 *
 * 曾经一度被临时写在 `PackPreviewPage.kt` 里,导致本文件无法再声明同名的同包顶层
 * 常量(编译冲突)。现已收敛为**只在本文件声明** —— 标签属于组件,跟着组件走;
 * 落在调用方文件里会让「这个 tag 到底有几个实现挂着」无法在组件侧自证。
 * 全工程只有 [TaskProgressBar] 一个实现挂它。
 */
const val TAG_TASK_PROGRESS = "task-progress"

/** 取消按钮的测试 tag（禁用态、点击回调数都挂在这里）。 */
const val TAG_TASK_CANCEL = "task-cancel"

/**
 * 打包执行进度条(D2b「后台任务与进度」的界面落点)。
 *
 * 纯展示 + 一个中断入口,**不持有任何状态**:进度由上层(执行编排 `PackRunner` 的
 * 上报节流)推下来,本组件只负责把快照画出来。这样"进度怎么算"仍然只有一个权威
 * (`TaskProgress`),界面不参与计算,也就不可能与领域层算出两个数。
 *
 * ## 一、为什么显示「已处理」而不是「已成功」
 *
 * `TaskProgress.done` **只统计成功卷**;失败与跳过各自独立计数
 * ([TaskProgress.failed] / [TaskProgress.skipped])。这不是随手拆的:
 * 2026-10-19 领域层刻意把"跳过"从 `failed` 里拆出来,因为把跳过的卷报成失败
 * 会让用户去追一个根本不存在的错误;而失败与跳过**对进度而言都是"处理过了"**,
 * 故派生属性 [TaskProgress.processed] = 成功 + 失败 + 跳过。
 *
 * 于是进度条只能讲「已处理」:进度条的长度画的是 [TaskProgress.fraction],
 * 而 `fraction` 用的正是 `processed`。若文字标签说「已成功 N」而条形走的是
 * `processed`,同一行里就会出现**两个数**:一批 5 卷、成功 2 卷、失败 1 卷时,
 * 条形已经走了 60%,文字却写「已成功 2 / 5」—— 用户会以为条画错了。
 * 相反,若为了让文字自洽而把条形改成按 `done` 画,失败项就永远不推进进度,
 * 一批里失败 30% 时进度条会**卡在 70% 再也不动**,看起来像任务死了。
 *
 * 结论:条形与文字必须同源,同源于 `processed`;成功/跳过的明细交给执行报告页。
 *
 * ## 二、为什么不能靠百分比文字隐式表达
 *
 * `LinearProgressIndicator` 的 `progress` 参数是 **lambda**(`() -> Float`)而不是
 * 一个 `Float`。这不是签名噪音:`progress` 在每帧/每次重组时被读取,lambda 形式让
 * 读取发生在**绘制阶段**而不是组合阶段 —— 传 `progress = { 0.5f }`(或在组合期
 * 求值后传常量)会让进度条每次都读到同一个数,状态推进时它**不动**;
 * 传 `{ progress.fraction }` 才会随 [progress] 变化而重绘。
 *
 * 因此本组件不提供"百分比文字"作为退路:文字一旦被用来表达进度,就会有人
 * 顺手把条形换成常量、只让文字动 —— 屏幕上"看起来在走"、条形却是死的。
 * 条形是唯一的进度表达,文字只说「已处理 N / M 卷」这个计数事实。
 *
 * ## 三、为什么取消按钮在进度条行内,而不是底栏
 *
 * 执行期间底栏的「确认打包」**必须不可用** —— 它一旦可点,用户连点两次就会
 * 并发写同一批文件(同一目标路径被两个写入器打开,轻则互相覆盖、重则留下半成品
 * CBZ)。也就是说执行期间底栏右侧那个位置是"空着但不能放东西"的。
 *
 * 在这个前提下,把「取消」放到底栏会带来两个额外代价:①需要一个"底栏在两种模式间
 * 换内容"的状态机,而这个状态机一错就会出现"执行中底栏还是确认按钮"的最坏情况;
 * ②取消是执行期的唯一动作,把它贴在进度条旁边是**从"我看到卡住了"到"我能停下来"
 * 的最短路径**,不需要用户再去别处找。故取消按钮与进度文字同一行、右侧。
 *
 * ## 关于"完成后取消按钮还在"
 *
 * 组件刻意**不**按 [TaskProgress.isComplete] 隐藏按钮:①消失/出现会改变行高,
 * 造成布局跳动(正在看进度的用户会被晃一下);②"点取消的瞬间任务刚好跑完"是真实
 * 会发生的,此时按钮若先消失,用户的点击会落到别处;③完成之后要不要收起整条
 * 进度区,是**调用方的页面策略**(它还要显示报告入口),不是本组件的职责。
 *
 * ## 文案取舍
 *
 * 本组件**不新增资源键**(本轮只允许新建这两个文件)。资源库里没有「已处理」这个键
 * (`grep -E 'processed|progress'` 扫 `res/values/` 只有 `domain_confirm_progress`
 * 这种"第 N 步"的键,语义不符),故计数退化为**语言无关的纯数字** `processed / total`
 * —— 数字与斜杠不是自然语言文案,不需要翻译,也不会触发 lint 的硬编码文案检查。
 * 「取消」则复用既有的 `R.string.common_cancel`(中英都有,见 `values/values-zh`)。
 *
 * @param progress 进度快照。**只读**,组件不改它、也不自己推进它。
 * @param onCancel 用户请求中断。可重复点击由 [cancelling] 挡住,而不是在本组件里去重。
 * @param modifier 外部布局修饰符。
 * @param cancelling 是否已经发出取消请求(任务正在收尾)。`true` 时按钮**禁用但保留**
 *   —— 保留文案与占位,避免按钮消失导致行内元素重排。
 */
@Composable
fun TaskProgressBar(
    progress: TaskProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancelling: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 必须传 lambda:见 KDoc 第二节。写成 `{ progress.fraction }` 才会在
        // 进度推进时被重新读取;在这里先求值再传常量会让条形永远停在首次组合的值。
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_TASK_PROGRESS),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 计数用 `processed` 而非 `done`:与条形同源(见 KDoc 第一节)。
            // 纯数字 + 斜杠,语言无关;「已处理」的措辞由调用方所在的标题行承担。
            Text(
                text = "${progress.processed} / ${progress.total}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onCancel,
                // 已请求取消就禁用:再点一次没有新语义,反而可能让上层重复发信号。
                enabled = !cancelling,
                modifier = Modifier.testTag(TAG_TASK_CANCEL),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}
