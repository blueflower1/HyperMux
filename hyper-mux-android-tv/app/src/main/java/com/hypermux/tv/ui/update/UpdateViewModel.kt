package com.hypermux.tv.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.hypermux.tv.BuildConfig
import com.hypermux.tv.config.AppConfig
import com.hypermux.tv.data.api.ApiService
import com.hypermux.tv.data.api.toApiErr
import com.hypermux.tv.data.model.ApiResponse
import com.hypermux.tv.data.model.UpdateInfo
import com.hypermux.tv.data.repo.SettingsRepo
import com.hypermux.tv.data.update.UpdateDownloadService
import com.hypermux.tv.data.update.UpdateDownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App 自更新（契约 §3.11）：
 * - 启动主动检查：登录后由 AppNavigation 调用 [autoCheckIfDue]，24h 节流、静默失败；
 *   查出新版本且用户没对该版本点过「稍后」→ 弹更新弹窗（Activity 作用域，跨 Tab 常驻）。
 * - 手动检查（「我的」页）：[checkNow] 必查服务器，结果 toast 提示，发现新版本直接弹窗。
 * 检查端点是匿名的（不依赖登录态），跟随用户配置的服务器；任何失败都不打扰用户（手动检查才提示）。
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val api: ApiService,
    private val gson: Gson,
    private val settingsRepo: SettingsRepo,
    private val downloadState: UpdateDownloadState
) : ViewModel() {

    /** 当前要展示的更新弹窗（null = 不弹；mandatory 由 [UpdateInfo.mandatory] 决定能否关闭） */
    private val _dialog = MutableStateFlow<UpdateInfo?>(null)
    val dialog: StateFlow<UpdateInfo?> = _dialog.asStateFlow()

    /** 是否正在检查（「我的」页该行显示「检查中…」） */
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** 「检查更新」行右侧文案（未查过 / 检查中 / 已是最新 / 有新版待更新） */
    private val _rowText = MutableStateFlow("未检查过")
    val rowText: StateFlow<String> = _rowText.asStateFlow()

    /** 手动检查结果（「我的」页 toast；[seq] 递增保证连续相同文案也能触发一次） */
    data class ManualResult(val ok: Boolean, val msg: String, val seq: Long)

    private val _manualResult = MutableStateFlow<ManualResult?>(null)
    val manualResult: StateFlow<ManualResult?> = _manualResult.asStateFlow()

    /**
     * 消费一次性结果（「我的」页 toast 展示完调用）。
     * 必须清空：本 VM 是 Activity 作用域，StateFlow 会保留上一次结果，
     * 每次重进「我的」页 collectAsStateWithLifecycle 都会把旧值重放一遍
     * （表现为"每次点我的都弹一次已是最新版本"）。
     */
    fun consumeManualResult() {
        _manualResult.value = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceWatchdog: Runnable? = null

    /**
     * 启动被动检查：登录后调用，24h 节流，静默。
     * 只在请求「成功」时记录检查时间戳（含"无新版"的 204）；失败不记录，下次启动再试。
     */
    fun autoCheckIfDue() {
        viewModelScope.launch {
            if (_checking.value) return@launch
            val last = settingsRepo.lastUpdateCheckAt(PLATFORM)
            if (System.currentTimeMillis() - last < AppConfig.UPDATE_CHECK_INTERVAL_MS) return@launch
            when (val o = requestLatest()) {
                is CheckOutcome.Ok -> {
                    settingsRepo.setLastUpdateCheckAt(PLATFORM, System.currentTimeMillis())
                    // 用户已对某版本点过「稍后」→ 不再主动弹（手动检查仍会弹）
                    if (o.info.versionCode > settingsRepo.dismissedUpdateCode(PLATFORM)) {
                        _dialog.value = o.info
                    }
                }

                is CheckOutcome.None ->
                    settingsRepo.setLastUpdateCheckAt(PLATFORM, System.currentTimeMillis())

                is CheckOutcome.Fail ->
                    Log.i(TAG, "被动更新检查失败（静默）：${o.msg}")
            }
        }
    }

    /** 手动检查：不限节流，必查服务器；结果 toast，发现新版本直接弹窗（哪怕之前点过「稍后」） */
    fun checkNow() {
        if (_checking.value) return
        viewModelScope.launch {
            _checking.value = true
            _rowText.value = "检查中…"
            when (val o = requestLatest()) {
                is CheckOutcome.Ok -> {
                    settingsRepo.setLastUpdateCheckAt(PLATFORM, System.currentTimeMillis())
                    _rowText.value = "v${o.info.versionName} 可更新"
                    _dialog.value = o.info
                    _manualResult.value = ManualResult(true, "发现新版本 v${o.info.versionName}", nextSeq())
                }

                is CheckOutcome.None -> {
                    settingsRepo.setLastUpdateCheckAt(PLATFORM, System.currentTimeMillis())
                    _rowText.value = "已是最新"
                    _manualResult.value =
                        ManualResult(true, "已是最新版本 v${BuildConfig.VERSION_NAME}", nextSeq())
                }

                is CheckOutcome.Fail -> {
                    _rowText.value = "未检查过"
                    _manualResult.value = ManualResult(false, o.msg, nextSeq())
                }
            }
            _checking.value = false
        }
    }

    /** 用户点「稍后」：记住该版本，本版本不再主动弹窗（服务端发更高版本时仍会提示） */
    fun dismissUpdate() {
        _dialog.value?.let { d ->
            viewModelScope.launch { settingsRepo.setDismissedUpdateCode(PLATFORM, d.versionCode) }
        }
        _dialog.value = null
    }

    /** 下载状态（进程级共享，见 [UpdateDownloadState]）：弹窗据此显示进度 / 切「立即安装」按钮 */
    val down: StateFlow<UpdateDownloadState.Down> = downloadState.down

    /**
     * 「立即更新」：本地没有已校验包 → 起前台下载服务；已下过得同版本包直接进可安装态
     * （进程重启后包还在也不重下）。占位 host 由 ServerUrlInterceptor 重写到用户配置的服务器。
     */
    fun startUpdate(info: UpdateInfo) {
        if (downloadState.isWorking()) return
        val file = File(
            File(appContext.filesDir, UpdateDownloadService.DIR_APKS),
            "hypermux-update-${info.versionCode}.apk"
        )
        if (file.exists()) {
            downloadState.ready(file, info.versionCode, info.versionName)
            return
        }
        if (downloadState.down.value is UpdateDownloadState.Down.Failed) downloadState.idle()
        val intent = Intent(appContext, UpdateDownloadService::class.java)
            .putExtra(UpdateDownloadService.EX_URL, "$PLACEHOLDER_BASE/api/app/update/download?platform=$PLATFORM")
            .putExtra(UpdateDownloadService.EX_SHA256, info.sha256)
            .putExtra(UpdateDownloadService.EX_VERSION_CODE, info.versionCode)
            .putExtra(UpdateDownloadService.EX_VERSION_NAME, info.versionName)
            .putExtra(UpdateDownloadService.EX_SIZE, info.sizeBytes)
        downloadState.loading(0, info.sizeBytes)
        ContextCompat.startForegroundService(appContext, intent)
        armServiceWatchdog()
    }

    /**
     * 启动看门狗：startForegroundService 之后 6s 内服务若没真正跑起来
     * （部分 ROM 会延迟投递 / 直接杀进程，FGS 超时只能让系统 FATAL 整个 App），
     * 在 App 内把下载置为可重试的失败，用户重新点「立即更新」即可。
     */
    private fun armServiceWatchdog() {
        serviceWatchdog?.let { mainHandler.removeCallbacks(it) }
        val rd = Runnable {
            val down = downloadState.down.value
            if (!downloadState.serviceAlive && down is UpdateDownloadState.Down.Loading) {
                downloadState.failed("下载服务启动失败（TV 可能限制了后台服务），请点「立即更新」重试")
            }
        }
        serviceWatchdog = rd
        mainHandler.postDelayed(rd, 6_000)
    }

    override fun onCleared() {
        serviceWatchdog?.let { mainHandler.removeCallbacks(it) }
        super.onCleared()
    }

    /**
     * 「立即安装」：FileProvider 把已校验的包交给系统安装器。
     * @return false = 用户还没允许「安装未知应用」——此时已先跳系统设置页，调用方负责提示。
     */
    fun installNow(): Boolean {
        val ready = downloadState.down.value as? UpdateDownloadState.Down.Ready ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            try {
                appContext.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${appContext.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w(TAG, "跳安装权限设置页失败：${e.message}")
            }
            return false
        }
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.update", ready.apk)
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    /** 请求服务器最新版本。[CheckOutcome.None] = 无更新（204，含该平台未发布） */
    private suspend fun requestLatest(): CheckOutcome {
        return try {
            val resp = api.checkUpdate(BuildConfig.VERSION_CODE, PLATFORM)
            if (!resp.isSuccessful) {
                val body = resp.errorBody()?.string()
                val msg = if (!body.isNullOrBlank()) {
                    runCatching { gson.fromJson(body, ApiResponse::class.java) }.getOrNull()?.msg
                } else null
                CheckOutcome.Fail(msg ?: "HTTP ${resp.code()}")
            } else {
                val r = resp.body()
                when {
                    // 204：无 body；或兼容"200 但 data 为空"
                    r == null || r.data == null -> CheckOutcome.None
                    r.code == 0 -> CheckOutcome.Ok(r.data)
                    else -> CheckOutcome.Fail(r.msg ?: "请求失败")
                }
            }
        } catch (e: IOException) {
            CheckOutcome.Fail(e.toApiErr().msg)
        } catch (e: Exception) {
            Log.w(TAG, "更新检查异常：${e.javaClass.simpleName} ${e.message}")
            CheckOutcome.Fail("请求失败")
        }
    }

    private sealed interface CheckOutcome {
        data class Ok(val info: UpdateInfo) : CheckOutcome
        data object None : CheckOutcome
        data class Fail(val msg: String) : CheckOutcome
    }

    companion object {
        private const val TAG = "UpdateVM"

        /**
         * 平台标识（契约 §3.11 + `AppReleaseService.PLATFORMS`）：
         * **TV 端必须是 `tv`**，服务端 `app_release` 按 platform 分行、各存一条最新。
         * 若误传 mobile，会拿到**手机端**的发布版本做比较 → 一进 App 就被提示更新到手机包
         * （装上去还是手机界面），且真正发布了 TV 包也检查不到。
         */
        const val PLATFORM = "tv"

        /** 占位 baseUrl（与 Retrofit 构建时一致）：真实服务器由 ServerUrlInterceptor 运行时重写 */
        private const val PLACEHOLDER_BASE = "http://localhost:8080"

        private var seq = 0L

        private fun nextSeq(): Long = ++seq
    }
}
