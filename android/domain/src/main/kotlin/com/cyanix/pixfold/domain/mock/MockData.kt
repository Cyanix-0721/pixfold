package com.cyanix.pixfold.domain.mock

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SourceItem

data class MockWorkspaceA(val rootPath: String, val collections: List<ImageCollection>)

/**
 * 确定性 mock 数据(语义对齐归档原型 mock_data.dart)。
 * 异常样例是语义的一部分:重名 / 非法字符 / 大小写冲突 / 自然序 vs 字典序。
 */
object MockData {

    private fun img(
        id: String, collectionId: String, dir: String, base: String, ext: String = "jpg",
        size: Long = 1024, modified: Long = 0, created: Long? = 0, seed: Int = 0,
    ) = SourceItem(id, collectionId, dir, base, ext, size, modified, created, seed)

    // ---- 集合 1: 旅行照片(正常样例 + 多目录分组) ----
    private fun trip(): ImageCollection {
        val images = buildList {
            for (i in 1..18) add(
                img(
                    "trip-day1-$i", "trip", "day1", "IMG_20260701_%03d".format(i),
                    size = 2_000_000L + i, seed = i,
                )
            )
            for (i in 1..12) add(
                img(
                    "trip-day2-$i", "trip", "day2", "IMG_20260702_%03d".format(i),
                    size = 1_800_000L + i, seed = 100 + i,
                )
            )
        }
        return ImageCollection("trip", "旅行照片", "旅行照片", images)
    }

    // ---- 集合 2: 扫描件(重名 / 非法字符 / 大小写冲突) ----
    private fun scan(): ImageCollection {
        val images = buildList {
            for (i in 1..6) {
                add(img("scan-ch01-$i", "scan", "ch01", "page_%02d".format(i), ext = "png", seed = 200 + i))
            }
            for (i in 1..6) {
                add(img("scan-ch02-$i", "scan", "ch02", "page_%02d".format(i), ext = "png", seed = 300 + i))
            }
            // 非法字符样例(Windows 非法字符集,Android 同样保守处理)
            add(img("scan-illegal", "scan", "ch02", "封:面?", ext = "png", seed = 400))
            // 大小写冲突对:与 ch02/page_01.png 仅大小写不同(同目录内会互相覆盖)
            add(img("scan-case", "scan", "ch02", "Page_01", ext = "PNG", seed = 401))
        }
        return ImageCollection("scan", "扫描件", "扫描件", images)
    }

    // ---- 集合 3: 杂图(自然序 vs 字典序) ----
    private fun misc(): ImageCollection {
        val images = (1..12).map { i -> img("misc-$i", "misc", "", "img$i", seed = 500 + i) }
        return ImageCollection("misc", "杂图", "杂图", images)
    }

    /**
     * 集合 4:章节(中文数字自然排序的**常驻**样例)。
     *
     * 为什么单独放一组而不是改现有集合:中文数字卷序是用户可见行为,
     * 必须在真机上能**一眼看出来**;而 mock 里原先全是阿拉伯数字,
     * `第01卷` 这类写法归一化前后顺序完全相同,装了新包也看不出差别,
     * 真机核对会退化成空验证。
     *
     * 声明顺序**故意打乱**,并且中文与阿拉伯写法混排:
     * 归一化生效时顺序为 1..9 / 10 / 11..13 / 20 / 21;
     * 归一化失效时退化成按码点比,顺序会被搅乱(`第十一话` 跑到 `第十话` 前)。
     */
    private fun chapters(): ImageCollection {
        val names = listOf(
            "第十二话", "第三话", "第21话", "第一话", "第十话", "第七话", "第二十话",
            "第五话", "第十三话", "第二话", "第九话", "第10话", "第六话", "第四话",
            "第八话", "第十一话",
        )
        val images = names.mapIndexed { i, name ->
            img("cn-$i", "cn", "", name, seed = 600 + i)
        }
        return ImageCollection("cn", "章节", "章节", images)
    }

    val workspaceA: MockWorkspaceA = MockWorkspaceA(
        rootPath = "D:\\照片整理_2026",
        collections = listOf(trip(), scan(), misc(), chapters()),
    )
}
