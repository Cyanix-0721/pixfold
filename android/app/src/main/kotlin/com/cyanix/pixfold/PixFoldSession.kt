package com.cyanix.pixfold

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import coil3.ImageLoader
import coil3.request.CachePolicy
import com.cyanix.pixfold.data.coil.CoilThumbnailSource
import com.cyanix.pixfold.data.coil.ImageLoaderCoilImageFetcher
import com.cyanix.pixfold.data.saf.AndroidSafSource
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.naming.namingSchemeSeedOf
import com.cyanix.pixfold.domain.organize.OrganizeState
import com.cyanix.pixfold.domain.organize.organizeStateOf
import com.cyanix.pixfold.domain.organize.reconcileNamingState
import com.cyanix.pixfold.domain.organize.reconcileOrganizeState
import com.cyanix.pixfold.domain.source.WorkspaceSource
import com.cyanix.pixfold.ui.library.LibraryVolumeLoader
import com.cyanix.pixfold.ui.workspace.CachingThumbnailSource
import com.cyanix.pixfold.ui.workspace.PreviewImageCacheSource
import com.cyanix.pixfold.ui.workspace.WorkspaceController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 跨配置变更存活的应用级会话容器（P1 #7）。
 *
 * 托管需要跨越屏幕旋转 / 分屏 / 动态取色切换的长生命周期对象与未保存工作流状态：
 * - [workspaceController]：SAF 目录授权状态机、扫描协程与 `ContentObserver`；
 * - [coilImageLoader] / [thumbnailSource] / [previewImageSource]：解码器与内存 LRU 缓存；
 * - [volumeLoader]：工作流 B 异步卷探测与手工元数据编辑；
 * - [organizeState] / [namingState] / [activeCollectionId] / [activeLibraryVolumeId]：工作流未保存编辑态。
 *
 * 生产路径由 [PixFoldSessionViewModel] 持有并在 `onCleared()` 中调 [close]；
 * 既有 Compose UI 测试直接调 `PixFoldApp(...)` 时走本地 `remember` + `DisposableEffect.onDispose { close() }`，
 * 保持 100% 用例隔离且不泄漏 `ImageLoader` / `ContentObserver`。
 */
class PixFoldSession(
    val workspaceSource: WorkspaceSource,
    val coilImageLoader: ImageLoader,
    val thumbnailSource: CachingThumbnailSource,
    val previewImageSource: PreviewImageCacheSource,
    val workspaceController: WorkspaceController,
    val volumeLoader: LibraryVolumeLoader,
    private val ownedScope: CoroutineScope? = null,
    private val onShutdownImageLoader: () -> Unit = { coilImageLoader.shutdown() },
) {
    var activeCollectionId: String by mutableStateOf("")
    var organizeState: OrganizeState by mutableStateOf(organizeStateOf(emptyList()))
    var namingState: NamingState by mutableStateOf(NamingState(namingSchemeSeedOf(emptyList())))
    var activeLibraryVolumeId: String? by mutableStateOf(null)

    var isClosed: Boolean = false
        private set

    private var workspaceBootstrapped: Boolean = false
    private var observedWorkspaceKeyInitialized: Boolean = false
    private var observedWorkspaceKey: String? = null
    private var organizeInitialized: Boolean = false
    private var organizeWorkspaceKey: String? = null
    private var lastReconciledCollections: List<ImageCollection> = emptyList()

    /**
     * 仅在会话首次启动时执行冷启动恢复（`prime` 或 `restore`）。
     *
     * 配置变更重建 Activity 时本会话实例存活，跳过重复启动以免把已经 `Ready` 的工作区打回 `Idle`/`Loading`。
     */
    fun ensureWorkspaceStarted(lazy: Boolean) {
        if (workspaceBootstrapped) return
        workspaceBootstrapped = true
        if (lazy) {
            workspaceController.prime()
        } else {
            workspaceController.restore()
        }
    }

    /**
     * 仅在工作区句柄**真正切换**时清空缩略图与预览缓存。
     *
     * 旋转屏幕或同工作区刷新时句柄未变，保留已解码的 LRU 缓存避免整屏重解码闪烁。
     */
    fun onWorkspaceHandleObserved(workspaceKey: String?) {
        if (!observedWorkspaceKeyInitialized) {
            observedWorkspaceKeyInitialized = true
            observedWorkspaceKey = workspaceKey
            return
        }
        if (observedWorkspaceKey != workspaceKey) {
            observedWorkspaceKey = workspaceKey
            thumbnailSource.clearCache()
            previewImageSource.clearCache()
        }
    }

    /**
     * 组合期同步工作流 A 的共享状态：
     * - 首次同步或切换工作区：重新播种；
     * - 同工作区实时刷新（集合变更）：增量对账保留人工排序与改名覆盖；
     * - 配置变更重建且数据未变：完全空操作，原样保留未保存编辑。
     */
    fun syncOrganizeCollections(
        workspaceKey: String?,
        collections: List<ImageCollection>,
    ) {
        if (!organizeInitialized || organizeWorkspaceKey != workspaceKey) {
            organizeInitialized = true
            organizeWorkspaceKey = workspaceKey
            lastReconciledCollections = collections
            activeCollectionId = collections.firstOrNull()?.id.orEmpty()
            organizeState = organizeStateOf(collections)
            namingState = NamingState(namingSchemeSeedOf(collections))
            return
        }
        if (lastReconciledCollections != collections) {
            lastReconciledCollections = collections
            organizeState = reconcileOrganizeState(organizeState, collections)
            namingState = reconcileNamingState(namingState, collections)
        }
    }

    /**
     * 返回首页时重置两条工作流的未保存编辑态（2026-10-14 用户裁决）。
     */
    fun resetWorkflowSessions(
        workspaceKey: String?,
        rootPath: String,
        collections: List<ImageCollection>,
        baseVolumes: List<ComicVolume>,
    ) {
        organizeInitialized = true
        organizeWorkspaceKey = workspaceKey
        lastReconciledCollections = collections
        organizeState = organizeStateOf(collections)
        namingState = NamingState(namingSchemeSeedOf(collections))
        activeCollectionId = collections.firstOrNull()?.id.orEmpty()
        activeLibraryVolumeId = null
        volumeLoader.resetToBase(
            workspaceKey = workspaceKey,
            rootPath = rootPath,
            baseVolumes = baseVolumes,
        )
    }

    /** 释放全部平台与协程资源（幂等）。 */
    fun close() {
        if (isClosed) return
        isClosed = true
        workspaceController.close()
        volumeLoader.cancelProbe()
        thumbnailSource.clearCache()
        previewImageSource.clearCache()
        onShutdownImageLoader()
        ownedScope?.cancel()
    }
}

/**
 * 承载 [PixFoldSession] 跨越配置变更的轻量 `AndroidViewModel`（P1 #7）。
 *
 * 复用 `androidx.activity:activity` 已传递引入的 `androidx.lifecycle.AndroidViewModel` /
 * `ViewModelProvider`，不增加任何新 Gradle 依赖。
 */
class PixFoldSessionViewModel(
    application: Application,
) : AndroidViewModel(application) {

    internal var sessionFactory: (Context, CoroutineDispatcher) -> PixFoldSession =
        { ctx, dispatcher -> createDefaultPixFoldSession(context = ctx, probeDispatcher = dispatcher) }

    private var _session: PixFoldSession? = null

    val session: PixFoldSession
        get() = _session ?: sessionFactory(getApplication(), Dispatchers.IO).also {
            _session = it
        }

    override fun onCleared() {
        _session?.close()
        _session = null
    }
}

internal fun createPixFoldImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .build()

internal fun createDefaultPixFoldSession(
    context: Context,
    probeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    workspaceSourceOverride: WorkspaceSource? = null,
    scope: CoroutineScope? = null,
    onShutdownImageLoader: (() -> Unit)? = null,
): PixFoldSession {
    val appContext = context.applicationContext ?: context
    val ownedScope = if (scope == null) CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) else null
    val activeScope = scope ?: requireNotNull(ownedScope)
    val source = workspaceSourceOverride ?: AndroidSafSource(appContext)
    val loader = createPixFoldImageLoader(appContext)
    val coilThumb = CoilThumbnailSource(ImageLoaderCoilImageFetcher(appContext, loader))
    val thumbnailSource = CachingThumbnailSource(coilThumb)
    val previewImageSource = PreviewImageCacheSource(coilThumb)
    val controller = WorkspaceController(
        source = source,
        scope = activeScope,
        nameResolver = { treeUri ->
            (source as? AndroidSafSource)?.displayNameOf(treeUri).orEmpty()
        },
    )
    val volumeLoader = LibraryVolumeLoader(
        scope = activeScope,
        ioDispatcher = probeDispatcher,
    )
    return PixFoldSession(
        workspaceSource = source,
        coilImageLoader = loader,
        thumbnailSource = thumbnailSource,
        previewImageSource = previewImageSource,
        workspaceController = controller,
        volumeLoader = volumeLoader,
        ownedScope = ownedScope,
        onShutdownImageLoader = onShutdownImageLoader ?: { loader.shutdown() },
    )
}
