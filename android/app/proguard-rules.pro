# PixFold Release R8 / ProGuard 规则（P2 #9）
#
# 上游依赖（kotlinx-serialization-core / androidx.lifecycle / androidx.navigation3 /
# aboutlibraries-core / coil）已随 AAR 自带 consumer rules；此处只补充本应用显式依赖
# 反射或跨配置序列化恢复的最小 keep 集合，避免过度放宽削弱裁剪效果。

# 1. Navigation 3 @Serializable NavKey 路由（rememberNavBackStack 持久化与多态序列化）
-keep class com.cyanix.pixfold.nav.** implements androidx.navigation3.runtime.NavKey {
    *;
}
-keepclassmembers class com.cyanix.pixfold.nav.** {
    *** Companion;
}
-keep class com.cyanix.pixfold.nav.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.cyanix.pixfold.nav.**$$serializer {
    *;
}

# 2. SharedPreferences 工作区 JSON 持久化 DTO（SafTreeStore.kt）
-keep,includedescriptorclasses class com.cyanix.pixfold.data.saf.StoredEntry {
    *;
}
-keep class com.cyanix.pixfold.data.saf.StoredEntry$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.cyanix.pixfold.data.saf.StoredEntry$$serializer {
    *;
}

# 3. 经 ViewModelProvider 反射实例化的应用会话 ViewModel
-keepclassmembers class com.cyanix.pixfold.PixFoldSessionViewModel {
    public <init>(android.app.Application);
}
