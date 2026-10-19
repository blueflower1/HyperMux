# ============================================================================
# HyperMux Android R8 规则（release 混淆/裁剪）
# Media3/ExoPlayer、OkHttp、Retrofit、Hilt、Coil、Compose 的规则由 AAR
# consumer-rules 自动带入；此处仅补充本体依赖的反射与泛型需求。
# ============================================================================

# ---- 反射 / 泛型签名（Gson、Retrofit 依赖）----
-keepattributes Signature, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault, InnerClasses, EnclosingMethod, Exceptions

# 网络模型与配置：Gson 反射读写字段，必须整体保留
-keep class com.hypermux.tv.data.model.** { *; }
-keep class com.hypermux.tv.config.** { *; }

# DataStore 里用 Gson 序列化的本地队列：字段名必须稳定
# （被 R8 改名后，升级前后写下的 JSON key 会不一致，旧队列就再也读不出来了）
-keep class com.hypermux.tv.data.repo.PendingProgress { *; }
-keep class com.hypermux.tv.data.cache.CacheStats { *; }
# 下载/缓存元信息（SharedPreferences + Gson 序列化）：字段名必须稳定，否则升级后读不出片名/集名
-keep class com.hypermux.tv.data.cache.MovieMeta { *; }
-keep class com.hypermux.tv.data.cache.EpisodeMeta { *; }

# ---- Gson ----
-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowshrinking class * implements com.google.gson.TypeAdapterFactory
-keep,allowobfuscation,allowshrinking class * implements com.google.gson.JsonSerializer
-keep,allowobfuscation,allowshrinking class * implements com.google.gson.JsonDeserializer

# ---- Retrofit / OkHttp ----
-keep,allowobfuscation interface com.hypermux.tv.data.api.ApiService
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# ---- R8 full mode：泛型签名（关键）----
# Retrofit 用 Proxy 反射方法的泛型返回类型；若泛型被裁剪，Gson 会把 data 反序列化成
# LinkedTreeMap，随后强转 PageVo 抛 ClassCastException（表现为"网络出错"）。
# 显式声明，确保不依赖 JAR 内嵌规则是否被采纳。
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation interface * extends com.hypermux.tv.data.api.ApiService

# ---- Hilt / Dagger ----
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.hilt.**

# ---- Kotlin / 协程 ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---- Coil 3（网络抓取器走 ServiceLoader 注册，防 R8 裁掉导致海报全不显示）----
-keep class coil3.network.** { *; }
-keep class * implements coil3.network.NetworkFetcher$Factory { *; }
-keepnames class coil3.network.okhttp.internal.OkHttpNetworkFetcherServiceLoaderTarget
-keep class coil3.util.Logger { *; }
-dontwarn coil3.**

# ---- 应用入口 ----
-keep class com.hypermux.tv.TvApp { *; }
-keep class * extends androidx.activity.ComponentActivity { *; }

# ---- 其他可选依赖的空引用（未被使用但被字节码引用）----
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
