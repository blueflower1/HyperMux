import java.util.Properties

// Top-level build file: plugins are declared with versions from libs.versions.toml
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---- release 签名（keystore.properties 缺失时退回 debug 签名） ----
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseStorePath = keystoreProps.getProperty("storeFile")
val hasReleaseKeystore = !releaseStorePath.isNullOrBlank() &&
    rootProject.file(releaseStorePath).exists()

// 明文流量：自托管测试默认允许 http；正式发布用 -PhypermuxCleartext=false 关闭
val allowCleartext = (rootProject.findProperty("hypermuxCleartext") as String?)?.toBoolean() ?: true

android {
    namespace = "com.hypermux.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hypermux.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = 13
        versionName = "0.1.12"

        // 留空 = 首次启动引导填写；可用 -PhypermuxApiBase=http://host:port 在构建期预置
        buildConfigField("String", "API_BASE", "\"${(rootProject.findProperty("hypermuxApiBase") ?: "")}\"")

        // 公开服务器内置地址：-PhypermuxPublicServerUrl=http://host:port 构建期注入（也可写进 local.properties）；
        // 留空 = 不提供「公开服务器」档位，用户必须自行输入服务器地址
        buildConfigField("String", "PRESET_SERVER_URL", "\"${(rootProject.findProperty("hypermuxPublicServerUrl") ?: "")}\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            manifestPlaceholders["usesCleartextTraffic"] = allowCleartext.toString()
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
            // 自托管测试环境常为 http 明文
            manifestPlaceholders["usesCleartextTraffic"] = allowCleartext.toString()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // 收敛不必要的元数据/许可证文件，减小包体
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.kotlin_module",
                "/kotlin/**",
                "DebugProbesKt.bin"
            )
        }
    }
}

// ---- dist 打包：把构建产物复制到 dist/ 并按 docs 约定命名 ----
// 命名与 `docs/build-and-acceptance.md` §2 一致：dist/hypermux-mobile-<version>-<variant>.apk
val hyperVersionName: String = android.defaultConfig.versionName ?: "0.0.0"

tasks.register<Copy>("assembleDist") {
    group = "build"
    description = "构建 debug + release，并把 APK 复制到 dist/（hypermux-mobile-<version>-<variant>.apk）"
    dependsOn("assembleDebug", "assembleRelease")

    into(rootProject.layout.projectDirectory.dir("dist"))

    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
        rename("app-debug.apk", "hypermux-mobile-$hyperVersionName-debug.apk")
    }
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename("app-release.apk", "hypermux-mobile-$hyperVersionName-release.apk")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.bom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    // 播放缓存 / 离线下载：cache-first 数据源 + 下载索引数据库（版本对齐 1.7.1，不升级）
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)

    // 第 4 层解码兜底：FFmpeg 软解扩展（含全部 ABI 的原生库，体积换兼容性）
    implementation(libs.nextlib.media3ext)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.coil.compose)
    // Coil 3 网络抓取器（独立 artifact；缺它则所有网图都加载失败）
    implementation(libs.coil.network.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
