package com.pixfold.d1.domain.settings

/**
 * 设置持久化接口。
 *
 * **为什么抽象成接口**:领域模型是纯 Kotlin,不该依赖 Android 的
 * `SharedPreferences`。抽象后:
 *  - Android 侧提供真实实现(SharedPreferences);
 *  - 测试用**内存实现**(无需 Robolectric,快且稳)。
 *
 * 读写刻意设计为**同步**:设置项少、值小,同步读写最简单且无并发问题。
 */
interface SettingsStore {
    /** 读取设置。实现须保证:**任何异常/非法值都回退到默认值**,不抛异常。 */
    fun load(): PixFoldSettings

    /** 保存设置。 */
    fun save(settings: PixFoldSettings)
}

/** 内存实现:用于测试,以及在没有持久化环境时的降级。 */
class InMemorySettingsStore(
    initial: PixFoldSettings = PixFoldSettings(),
) : SettingsStore {
    private var current: PixFoldSettings = initial

    override fun load(): PixFoldSettings = current

    override fun save(settings: PixFoldSettings) {
        current = settings
    }
}
