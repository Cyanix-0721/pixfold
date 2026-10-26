package com.cyanix.pixfold.data.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.pack.CbzMetaUpdateOutcome
import com.cyanix.pixfold.domain.pack.CbzMetaUpdatePlan
import com.cyanix.pixfold.domain.pack.CbzMetaUpdater
import com.cyanix.pixfold.domain.pack.CbzMetaRewriter
import com.cyanix.pixfold.domain.pack.UpdateFailure
import com.cyanix.pixfold.domain.pack.UpdatePreflight
import java.io.File

/**
 * 工作流 C 的 SAF 实现：把一卷已有 CBZ 里的 `ComicInfo.xml` 换掉。
 *
 * ## 为什么要临时包 + 三段式改名
 *
 * SAF 没有「原子替换」原语，也没有「就地改文件中间一段」的能力。唯一安全的做法是：
 *
 *  1. 在同一目录里写一个新包（`原名.updating`）；
 *  2. 把**原包**改名成 `原名.updating.bak`（此刻原包仍在磁盘上，只是换了名字）；
 *  3. 把临时包改名成**原名**（此刻用户看到的新包已经就位）；
 *  4. 删掉备份。
 *
 * 任何一步失败，原包都还有一份在磁盘上（要么原名、要么 `.bak`）。这是用户裁决 Q3 甲的
 * 落地：「宁可留一个能手动捡回来的备份，也不要出现包没了」。
 *
 * ## 为什么临时包必须与目标同目录
 *
 * `DocumentsContract.renameDocument` 只在**同一个父目录**内改名。跨目录要先 copy 再 delete，
 * 而 copy 一个 4.7 GiB 的包意味着双倍 IO 与双倍空间 —— 换来的只是「临时文件不碍眼」。
 *
 * ## 阻塞 IO
 *
 * 本类**所有方法都是阻塞 IO**（跨进程 Binder + 整包读写），调用方必须放到
 * `Dispatchers.IO`。打包链在 `b1f04b2` 踩过主线程串行 Binder 导致 ANR 的坑。
 *
 * @param spaceBytesOf   tree URI 所在卷的可用字节；拿不到返回 null（= 不知道，不阻断）
 * @param dirOfVolume    卷 → 输出目录相对路径（与 `SafCbzOutputSink` 同一个函数）
 * @param documentSizeOf 目标包的大小；拿不到返回 null
 * @param renameDocument 抽成参数**只为可测**（`renameDocument` 是静态方法，Robolectric 只能回放固定结果）
 * @param deleteDocument 同上
 * @param spillDir       大条目的落盘目录，默认 cacheDir（不放目标目录：那是用户的工作区）
 */
class SafMetaUpdater(
    context: Context,
    private val treeUri: Uri,
    private val spaceBytesOf: (Uri) -> Long?,
    private val dirOfVolume: (ComicVolume) -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val documentSizeOf: (ContentResolver, Uri) -> Long? = ::queryDocumentSize,
    private val renameDocument: (ContentResolver, Uri, String) -> Uri? =
        { resolver, uri, name -> DocumentsContract.renameDocument(resolver, uri, name) },
    private val deleteDocument: (ContentResolver, Uri) -> Boolean =
        { resolver, uri -> DocumentsContract.deleteDocument(resolver, uri) },
    /**
     * 卷 → 目标包的 document URI。抽成参数**只为可测**：真实实现要走
     * SAF 目录下钻，而 Robolectric 没有真实 provider，预检三态在那里无法断言。
     * 传 null ⇒ 用默认的 SAF 查找。
     */
    private val lookupTarget: ((ComicVolume) -> Uri?)? = null,
    private val spillDir: File? = context.applicationContext.cacheDir,
) : CbzMetaUpdater {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** 目标包是否还在。确认框据此提示「包不见了」。 */
    override fun targetExists(volume: ComicVolume): Boolean = findTarget(volume) != null

    /**
     * 空间预检。
     *
     * `required = 包大小 + 32 MiB 余量`：转写期间磁盘上同时存在原包与临时包
     * （备份是改名得来的，不占额外空间）。32 MiB 是给「写临时包时 provider 自己的元数据
     * 开销」留的余量，与打包链的安全余量同源。
     *
     * 包大小或剩余空间**任一拿不到 ⇒ [UpdatePreflight.Unknown]**，不阻断 ——
     * 拿不到就说不知道，比「假装够用」和「直接拒绝」都诚实（与 `PackUiPreflight` 一致）。
     */
    override fun preflight(plan: CbzMetaUpdatePlan, volume: ComicVolume): UpdatePreflight {
        val target = findTarget(volume) ?: return UpdatePreflight.Unknown
        val inputBytes = documentSizeOf(resolver, target) ?: return UpdatePreflight.Unknown
        val available = spaceBytesOf(treeUri) ?: return UpdatePreflight.Unknown
        val required = inputBytes + SAFETY_MARGIN_BYTES
        return if (available < required) {
            UpdatePreflight.NoSpace(requiredBytes = required, availableBytes = available)
        } else {
            UpdatePreflight.Ready(estimatedReadBytes = inputBytes, estimatedWriteBytes = required)
        }
    }

    override fun update(
        plan: CbzMetaUpdatePlan,
        volume: ComicVolume,
        shouldStop: () -> Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): CbzMetaUpdateOutcome {
        val parentUri = safParentDocumentUri(resolver, treeUri, dirOfVolume(volume))
            ?: return CbzMetaUpdateOutcome.Failed(UpdateFailure.TargetMissing, "找不到输出目录")
        val target = safFindChild(resolver, treeUri, parentUri, volume.cbzFileName)
            ?: return CbzMetaUpdateOutcome.Failed(UpdateFailure.TargetMissing, volume.cbzFileName)

        // 空间预检在这里再问一次：预检与执行之间有窗口期（用户在确认框上停留），
        // 期间别的应用可能把空间吃光。不能信任调用方「我已经预检过」。
        val pre = preflight(plan, volume)
        if (pre is UpdatePreflight.NoSpace) {
            return CbzMetaUpdateOutcome.Failed(
                UpdateFailure.NoSpace,
                "需要 ${pre.requiredBytes} 字节，可用 ${pre.availableBytes} 字节",
            )
        }

        val backupName = volume.cbzFileName + BACKUP_SUFFIX
        val tempName = volume.cbzFileName + TEMP_SUFFIX

        // 上一轮崩溃可能留下同名的临时包/备份。临时包直接删（它本来就只是半成品）；
        // 备份**不删** —— 它可能是用户唯一的原包，宁可让这次改名失败也不要毁掉它。
        deleteIfExists(parentUri, tempName)

        val tempUri = try {
            DocumentsContract.createDocument(resolver, parentUri, MIME_CBZ, tempName)
                ?: return CbzMetaUpdateOutcome.Failed(UpdateFailure.ReplaceFailed, "无法创建临时包")
        } catch (e: SecurityException) {
            return CbzMetaUpdateOutcome.Failed(UpdateFailure.PermissionLost, e.message)
        } catch (e: Exception) {
            return CbzMetaUpdateOutcome.Failed(UpdateFailure.ReplaceFailed, e.message)
        }

        val timeMillis = now()
        var entryCount = 0
        val xmlBytes = plan.comicInfoXml.toByteArray(Charsets.UTF_8).size

        try {
            val input = resolver.openInputStream(target)
                ?: return failAndClean(tempUri, UpdateFailure.ReadFailed, "无法打开原包输入流")
            input.use { source ->
                val out = resolver.openOutputStream(tempUri, "w")
                    ?: return failAndClean(tempUri, UpdateFailure.ReadFailed, "无法打开临时包输出流")
                out.use { sink ->
                    entryCount = CbzMetaRewriter.rewrite(
                        input = source,
                        out = sink,
                        comicInfoXml = plan.comicInfoXml,
                        timeMillis = timeMillis,
                        spillDir = spillDir,
                        onEntryWritten = { done, total ->
                            onProgress(done, total)
                            // 只在条目边界停：此刻临时包刚写到一半，直接丢弃它即可 ——
                            // 原包一个字节都没动过。停在条目中间才会留下中央目录不完整的包。
                            if (shouldStop()) throw CancelledSignal()
                        },
                    )
                }
            }
        } catch (e: CancelledSignal) {
            deleteQuietly(tempUri)
            return CbzMetaUpdateOutcome.Failed(UpdateFailure.Cancelled)
        } catch (e: SecurityException) {
            return failAndClean(tempUri, UpdateFailure.PermissionLost, e.message)
        } catch (e: Exception) {
            return failAndClean(tempUri, UpdateFailure.ReadFailed, e.message)
        }

        // ---- 三段式替换 ----
        // ① 原包 → 备份
        val backupUri = try {
            renameDocument(resolver, target, backupName)
        } catch (e: Exception) {
            null
        }
        if (backupUri == null) {
            deleteQuietly(tempUri)
            return CbzMetaUpdateOutcome.Failed(
                UpdateFailure.ReplaceFailed,
                "无法把原包改名为 $backupName（原包未动，临时包已清理）",
            )
        }

        // ② 临时包 → 原名
        val promoted = try {
            renameDocument(resolver, tempUri, volume.cbzFileName)
        } catch (e: Exception) {
            null
        }
        if (promoted == null) {
            // 回滚：把备份改回原名。回滚成功 ⇒ 磁盘回到起点，报 ReplaceFailed 即可。
            val rolledBack = try {
                renameDocument(resolver, backupUri, volume.cbzFileName) != null
            } catch (e: Exception) {
                false
            }
            deleteQuietly(tempUri)
            return CbzMetaUpdateOutcome.Failed(
                UpdateFailure.ReplaceFailed,
                if (rolledBack) {
                    "无法把新包改名为 ${volume.cbzFileName}（原包已恢复）"
                } else {
                    "无法把新包改名为 ${volume.cbzFileName}，且原包留在 $backupName —— 请手动把它改回原名"
                },
            )
        }

        // ③ 删备份。**这一步失败算成功**：包已经是完整的新包了，
        // 因为删不掉一个多余备份就报失败，会让用户以为要重来一遍。
        val deleted = try {
            deleteDocument(resolver, backupUri)
        } catch (e: Exception) {
            false
        }
        if (!deleted) Log.w(TAG, "删除备份失败（不影响结果）：$backupName")
        return CbzMetaUpdateOutcome.Updated(entryCount, xmlBytes)
    }

    /** 取消信号。用独立异常类型是为了不把它和真正的 IO 异常混成一条分支。 */
    private class CancelledSignal : RuntimeException()

    private fun failAndClean(tempUri: Uri, reason: UpdateFailure, detail: String?): CbzMetaUpdateOutcome {
        deleteQuietly(tempUri)
        return CbzMetaUpdateOutcome.Failed(reason, detail)
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            deleteDocument(resolver, uri)
        } catch (e: Exception) {
            Log.w(TAG, "清理临时文件失败：$uri", e)
        }
    }

    private fun deleteIfExists(parentUri: Uri, displayName: String) {
        val existing = safFindChild(resolver, treeUri, parentUri, displayName) ?: return
        deleteQuietly(existing)
    }

    private fun findTarget(volume: ComicVolume): Uri? =
        lookupTarget?.invoke(volume) ?: defaultFindTarget(volume)

    private fun defaultFindTarget(volume: ComicVolume): Uri? {
        val parentUri = safParentDocumentUri(resolver, treeUri, dirOfVolume(volume)) ?: return null
        return safFindChild(resolver, treeUri, parentUri, volume.cbzFileName)
    }

    private companion object {
        const val TAG = "SafMetaUpdater"

        /** 与打包链同源的 CBZ MIME。部分 provider 不认 comicbook 类型，退回二进制亦可读。 */
        const val MIME_CBZ = "application/vnd.comicbook+zip"

        /** 空间安全余量：32 MiB。 */
        const val SAFETY_MARGIN_BYTES = 32L * 1024 * 1024

        /** 临时包后缀。同一目录，只改 cbzFileName 之后那一段。 */
        const val TEMP_SUFFIX = ".updating"

        /** 备份后缀。原包改名成它，替换成功后再删。 */
        const val BACKUP_SUFFIX = ".updating.bak"
    }
}

/**
 * 查一个 document 的字节大小。
 *
 * COLUMN_SIZE 对目录与部分 provider 会给出 null / -1，两种都当「不知道」，
 * 让上层的预检退化成 [UpdatePreflight.Unknown] —— **不要**用别的推算方式
 * （那要么读一遍整包，要么猜）。
 */
internal fun queryDocumentSize(resolver: ContentResolver, uri: Uri): Long? = try {
    resolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it > 0 } else null
    }
} catch (e: Exception) {
    null
}
