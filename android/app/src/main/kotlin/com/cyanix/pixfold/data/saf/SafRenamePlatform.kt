package com.cyanix.pixfold.data.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import com.cyanix.pixfold.domain.plan.RenameActions
import com.cyanix.pixfold.domain.plan.UndoActions

private const val TAG = "SafRename"

/**
 * 用 SAF 把一张图改名。
 *
 * ## 为什么返回**新 uri** 而不是 Boolean
 *
 * `DocumentsContract.renameDocument` 返回改名后的 document uri。在
 * `ExternalStorageProvider` 上它与原 uri **不同** —— 该 provider 的 document id
 * 由相对路径派生(`primary:Pictures/a.jpg` → `primary:Pictures/b.jpg`),
 * 改完名 id 就变了。撤销要改回去,必须拿得到改名后的那个 uri;
 * 若沿用旧 uri,撤销会打在一个已经不存在的 document 上(静默失败)。
 *
 * ## 为什么 `renameDocument` 可能返回 null
 *
 * 契约允许它返回 null(provider 不报告新 uri)。此时**不能**乐观认为成功:
 * 后续撤销需要那个新 uri,拿不到就等于这条撤销永远做不了。宁可如实报失败,
 * 让用户看到「改名失败」并自己确认磁盘上的实际状态 ——
 * 谎报成功会让报告说「成功 3 项」而磁盘上是别的样子。
 *
 * ## 为什么捕获 `Exception` 而不是只捕 `SecurityException`
 *
 * 真机上见过 `IllegalArgumentException`(URI 形态不符,见 HANGOFF §12.5 的
 * `createDocument` 踩坑)、`FileNotFoundException`(目标名被占时部分 ROM 抛)、
 * 以及国产 ROM 的私有异常。逐个列举必然漏,而这里任何一种失败的处理方式都相同:
 * 打日志 + 返回 null 让上层记一行「失败」。
 *
 * 刻意**不捕 `Error`**(如 `OutOfMemoryError`):那是进程级问题,
 * 吞掉它只会把崩溃点推迟到更难定位的地方。
 */
class SafRenameActions(
    context: Context,
    /**
     * 真正调 `DocumentsContract.renameDocument` 的那一步。
     *
     * 抽成参数**只为可测**：`renameDocument` 是静态方法，Robolectric 能给它的
     * shadow 只回放固定结果，「provider 返回 null」这条契约无法从外部触发。
     * 而这条分支有实打实的后果 —— 返回 null 时若乐观当成功，报告会说「成功 N 项」
     * 而撤销时手里那个 uri 指向已不存在的 document，撤销静默失败。
     */
    private val renameDocument: (ContentResolver, Uri, String) -> Uri? =
        { resolver, uri, name -> DocumentsContract.renameDocument(resolver, uri, name) },
) : RenameActions {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun rename(uri: String, newName: String): String? {
        val documentUri = uri.toUri()
        return try {
            val renamed: Uri? = renameDocument(resolver, documentUri, newName)
            if (renamed == null) {
                Log.w(TAG, "renameDocument 返回 null(provider 未报告新 uri): $uri → $newName")
            }
            renamed?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "改名失败: $uri → $newName", e)
            null
        }
    }
}

/**
 * 用 SAF 执行撤销(删除本次产物 / 把改名改回去)。
 *
 * ## 三个方法都不抛
 *
 * 撤销是用户点了「撤销」之后的补救动作。若中途抛异常,「已经回滚了几项」
 * 这件事实就永远传不回界面 —— 用户看到的是崩溃,磁盘上是改了一半的状态。
 * 领域层 [com.cyanix.pixfold.domain.plan.performUndoReal] 另有 `runCatching` 兜底,
 * 这里是第一道,也保证日志里有逐项痕迹。
 */
class SafUndoActions(context: Context) : UndoActions {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun delete(uri: String): Boolean {
        val documentUri = uri.toUri()
        return try {
            DocumentsContract.deleteDocument(resolver, documentUri)
        } catch (e: Exception) {
            // 删不掉(介质已移除 / 已被用户删 / 授权失效)不算异常状态 ——
            // 对用户而言「它不在了」与「我删掉了」结果相同。
            // 但**必须返回 false**:若这项是「删掉本次覆盖的位置」,
            // 报成功会让用户以为原来的文件也被清掉了。
            Log.w(TAG, "撤销删除失败: $uri", e)
            false
        }
    }

    override fun rename(uri: String, newName: String): Boolean {
        val documentUri = uri.toUri()
        return try {
            DocumentsContract.renameDocument(resolver, documentUri, newName) != null
        } catch (e: Exception) {
            Log.w(TAG, "撤销改名失败: $uri → $newName", e)
            false
        }
    }
}
