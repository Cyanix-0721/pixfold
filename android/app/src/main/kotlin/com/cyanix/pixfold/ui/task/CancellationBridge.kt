package com.cyanix.pixfold.ui.task

import com.cyanix.pixfold.domain.task.CancellationToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把当前协程的取消事件实时桥接到 [CancellationToken]（P1 #7）。
 *
 * ## 为什么不能只用 `job.invokeOnCompletion { ... }`
 *
 * `Job.invokeOnCompletion` 的公开重载（`onCancelling = false`）直到协程**彻底完成**才触发；
 * 而当 `block` 正在 `Dispatchers.IO` 上跑阻塞的文件循环（`CbzMetaRewriter` / `executeRenamePlanReal` /
 * `performUndoReal`）时，协程会停在 `Cancelling` 态等待阻塞循环返回 —— 若循环又在等 `token.isCancelled`，
 * 就会互相等待导致后台线程成为孤儿、继续修改磁盘。
 *
 * 这里在同级 `coroutineScope` 内以 `Dispatchers.Unconfined + CoroutineStart.UNDISPATCHED` 启动一个
 * 轻量监听协程挂起在 [awaitCancellation] 上：一旦父协程被取消，监听协程会立即同步恢复并调用
 * `token.cancel()`，让阻塞循环在下一个安全边界停下；正常完成时先置位 [finished] 再取消监听，
 * 不会误伤令牌。
 */
suspend fun <T> withCancellationToken(
    token: CancellationToken,
    block: suspend () -> T,
): T {
    val parentJob = currentCoroutineContext()[Job]
    if (parentJob != null && !parentJob.isActive) {
        token.cancel()
    }
    return coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                if (!finished.get()) {
                    token.cancel()
                }
                throw e
            }
        }
        try {
            block().also {
                finished.set(true)
                watcher.cancel()
            }
        } catch (e: Throwable) {
            if (e is CancellationException || (parentJob != null && !parentJob.isActive)) {
                token.cancel()
            }
            finished.set(true)
            watcher.cancel()
            throw e
        }
    }
}
