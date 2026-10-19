package com.hypermux.tv.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.hypermux.tv.BuildConfig
import com.hypermux.tv.config.AppConfig
import com.hypermux.tv.data.api.ApiResult
import com.hypermux.tv.data.api.ApiService
import com.hypermux.tv.data.api.safeApiCall
import com.hypermux.tv.data.api.toApiErr
import com.hypermux.tv.data.model.LoginReq
import com.hypermux.tv.data.repo.AuthRepo
import com.hypermux.tv.data.repo.ServerMode
import com.hypermux.tv.data.repo.SettingsRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 二维码面板状态（LoginScreen 据此渲染二维码与文案） */
enum class QrState { IDLE, LOADING, ACTIVE, SCANNED, UNSUPPORTED, UNREACHABLE }

/**
 * 登录页状态与动作（契约 §3.1 / §4.3）。
 * 服务器连接：内置了公开服务器（[AppConfig.HAS_PRESET_SERVER]）时默认 [ServerMode.PUBLIC]
 * （内置预置地址，UI 不展示）；未内置时默认 [ServerMode.CUSTOM]，用户必须自行填写地址；
 * 「自定义服务器」经配置弹窗选择，地址仅在用户自选后由用户自己看到。
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val api: ApiService,
    private val gson: Gson,
    private val authRepo: AuthRepo,
    private val settingsRepo: SettingsRepo
) : ViewModel() {

    // ---- 服务器连接（主页面状态，仅在弹窗保存 / 登录成功时变更） ----
    var serverMode by mutableStateOf(if (AppConfig.HAS_PRESET_SERVER) ServerMode.PUBLIC else ServerMode.CUSTOM)
        private set

    /** 自定义地址（仅 [ServerMode.CUSTOM] 有意义；构建期可用 -PhypermuxApiBase 预填） */
    var customUrl by mutableStateOf(BuildConfig.API_BASE)
        private set

    // ---- 服务器配置弹窗（草稿态：保存才落主页面与持久化） ----
    var dialogOpen by mutableStateOf(false)
        private set
    var dialogMode by mutableStateOf(if (AppConfig.HAS_PRESET_SERVER) ServerMode.PUBLIC else ServerMode.CUSTOM)
        private set
    var dialogCustomUrl by mutableStateOf("")
        private set
    var dialogTesting by mutableStateOf(false)
        private set
    var dialogStatus by mutableStateOf<String?>(null)
        private set

    /** 当前生效服务器的最近探测结果（地址 → 是否可达）；null = 未探测（登录页圆点变灰） */
    var lastProbe: Pair<String, Boolean>? by mutableStateOf(null)
        private set

    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var errorMsg by mutableStateOf<String?>(null)
        private set

    // ---- 扫码登录（QR 引擎：create → 轮询 status → CONFIRMED 投递会话；过期自动重建） ----

    var qrState by mutableStateOf(QrState.IDLE)
        private set

    /** 二维码内容（仅不透明 qrId，不含任何秘密）；null = 当前无码可画 */
    var qrPayload by mutableStateOf<String?>(null)
        private set

    /** 服务器配置变更 → 引擎重启（内层轮询发现代际变化即退出重来） */
    @Volatile
    private var qrGeneration = 0

    init {
        viewModelScope.launch {
            // 模式：持久化值（SettingsRepo 内含旧版升级推导：存过地址 → CUSTOM）
            serverMode = settingsRepo.serverMode.first()
            // 自定义地址预填：最后一次保存的自定义地址（公开预设不属于自定义，排除）
            if (serverMode == ServerMode.CUSTOM) {
                val saved = settingsRepo.serverUrl.first()
                saved?.takeIf { it.isNotBlank() && it != AppConfig.PRESET_SERVER_URL }
                    ?.let { customUrl = it }
            }
            // 首请求前把当前档位落盘（与 login() 同一动作）：全新安装时 DataStore 里没有
            // SERVER_URL，ServerUrlInterceptor 会回退占位 localhost 被拒 —— 密码登录因为
            // login() 先 applyServer 所以正常，QR 引擎也必须先 apply 才能拿到可用地址。
            settingsRepo.applyServer(serverMode, if (serverMode == ServerMode.CUSTOM) customUrl else null)
            // 引擎在配置就绪后才启动（同一协程内串行，杜绝首请求抢跑）
            qrEngine()
        }
    }

    /**
     * 扫码登录引擎（与密码登录并存，互不干扰）：
     * create 一次性登录码 → 每 2s 轮询状态 → CONFIRMED 拿到 session 后走与密码登录
     * 完全相同的 saveSession（导航层自动跳首页）；EXPIRED 自动重建；
     * 服务器不支持（404/405）显示占位并慢速重试，服务器升级后自动恢复。
     */
    private suspend fun qrEngine() {
        while (true) {
            val gen = qrGeneration
            val url = effectiveUrl()
            if (url.isBlank()) {
                qrState = QrState.IDLE
                qrPayload = null
                delay(1000)
                continue
            }
            qrState = QrState.LOADING
            qrPayload = null
            val createdResult = safeApiCall(gson) { api.qrCreate() }
            if (gen != qrGeneration) continue
            val created = createdResult as? ApiResult.Ok
            if (created == null) {
                val code = (createdResult as ApiResult.Err).code
                // 404/405：路径不存在；401/403：旧版 jar 未豁免 qr 路径被拦截器挡下 —— 都是「服务器版本不支持扫码」
                qrState = if (code == 401 || code == 403 || code == 404 || code == 405) QrState.UNSUPPORTED else QrState.UNREACHABLE
                delay(if (qrState == QrState.UNSUPPORTED) 30_000L else 5_000L)
                continue
            }
            val qrId = created.data.qrId
            qrPayload = "hypermux://tv-login?id=$qrId"
            qrState = QrState.ACTIVE
            while (true) {
                delay(2000)
                if (gen != qrGeneration) break
                val stResult = safeApiCall(gson) { api.qrStatus(qrId) }
                if (gen != qrGeneration) break
                val st = (stResult as? ApiResult.Ok)?.data
                if (st == null) {
                    qrState = QrState.UNREACHABLE
                    break
                }
                when (st.state) {
                    "PENDING" -> Unit  // 未被扫：继续轮询，勿重建（重建会打爆 create 限频）
                    "SCANNED" -> qrState = QrState.SCANNED
                    "CONFIRMED" -> {
                        val session = st.session
                        if (session != null) {
                            qrPayload = null
                            authRepo.saveSession(session)   // 导航层自动跳首页
                            return
                        }
                        // session 已被取走（理论不会走到）：重建登录码
                        qrState = QrState.LOADING
                        break
                    }
                    // EXPIRED（含未知状态兜底）：重建登录码
                    else -> {
                        qrState = QrState.LOADING
                        break
                    }
                }
            }
        }
    }

    /** 打开服务器配置弹窗：[intent] 指定预选档位（主行进入=当前模式；"使用自定义"小字进入=CUSTOM） */
    fun openServerDialog(intent: ServerMode) {
        dialogMode = intent
        dialogCustomUrl = customUrl
        dialogStatus = null
        dialogOpen = true
    }

    fun closeDialog() {
        dialogOpen = false
    }

    fun onDialogModeChange(mode: ServerMode) {
        dialogMode = mode
        dialogStatus = null
    }

    fun onDialogCustomUrlChange(v: String) {
        dialogCustomUrl = v
        dialogStatus = null
    }

    /**
     * 弹窗内「测试连接」：探测**草稿**地址（经 [SettingsRepo.probingUrl] 临时生效，
     * 不写持久化，取消即无副作用）。401/403 亦判可达（探测走受保护的 /api/app/home）。
     */
    fun testDialogServer() {
        viewModelScope.launch {
            val target = dialogTarget() ?: return@launch
            dialogTesting = true
            dialogStatus = null
            settingsRepo.probingUrl = target
            try {
                val code = api.probe().code()
                val ok = code in 200..299 || code == 401 || code == 403
                dialogStatus = dialogStatusText(code, dialogMode)
                if (target == effectiveUrl()) lastProbe = target to ok
            } catch (e: Exception) {
                dialogStatus = if (dialogMode == ServerMode.PUBLIC) {
                    "无法连接公开服务器：" + e.toApiErr().msg
                } else {
                    "连接失败：" + e.toApiErr().msg
                }
                if (target == effectiveUrl()) lastProbe = target to false
            } finally {
                settingsRepo.probingUrl = null
            }
            dialogTesting = false
        }
    }

    /** 保存弹窗：草稿升为主页面状态并持久化（自定义空地址由弹窗"保存"按钮置灰拦截） */
    fun saveDialogServer() {
        if (dialogMode == ServerMode.CUSTOM) {
            val u = dialogCustomUrl.trim()
            if (u.isEmpty()) return
            customUrl = u
            serverMode = ServerMode.CUSTOM
        } else {
            serverMode = ServerMode.PUBLIC
        }
        lastProbe = null   // 服务器变了，旧探测结论作废
        qrGeneration++     // QR 引擎换服务器重来
        viewModelScope.launch {
            settingsRepo.applyServer(serverMode, if (serverMode == ServerMode.CUSTOM) customUrl else null)
        }
        dialogOpen = false
    }

    fun onUsernameChange(v: String) {
        username = v
        errorMsg = null
    }

    fun onPasswordChange(v: String) {
        password = v
        errorMsg = null
    }

    fun login() {
        viewModelScope.launch {
            errorMsg = null
            val name = username.trim()
            // 本地前置校验：对齐后端 LoginReq —— 用户名 3~32 位字母/数字/下划线；
            // 密码**仅校验长度 6~20**（字符集 [a-zA-Z0-9_.] 只用于"建用户/重置密码"，登录不适用，
            // 例如初始管理员密码 Admin@1234 含 @）
            if (!USERNAME_RE.matches(name)) {
                errorMsg = "用户名需为 3~32 位字母、数字或下划线"
                return@launch
            }
            if (password.length !in 6..20) {
                errorMsg = "密码长度需为 6~20 位"
                return@launch
            }
            val url = if (serverMode == ServerMode.PUBLIC) {
                AppConfig.PRESET_SERVER_URL.ifBlank { null }   // 无内置公开服务器时的兜底（正常不会走到）
            } else {
                customUrl.trim().ifBlank { null }
            }
            if (url == null) {
                errorMsg = "请先填写服务器地址"
                return@launch
            }
            settingsRepo.applyServer(serverMode, if (serverMode == ServerMode.CUSTOM) url else null)
            isLoading = true
            val r = safeApiCall(gson) { api.login(LoginReq(name, password)) }
            isLoading = false
            when (r) {
                is ApiResult.Ok -> authRepo.saveSession(r.data)   // 导航层自动跳首页
                is ApiResult.Err -> errorMsg = when (r.code) {
                    // 401 统一文案，避免暴露"账号是否存在"
                    401 -> "用户名或密码错误"
                    // 403 用服务端原文："账号已停用" / "该账号无此端访问权限"（例如用管理端账号登录手机端）
                    403 -> r.msg.ifBlank { "该账号无权访问手机端" }
                    429 -> "尝试太频繁，请稍后再试"
                    else -> r.msg
                }
            }
        }
    }

    /** 主页面生效地址（公开模式 = 内置预置，UI 不展示） */
    fun effectiveUrl(): String =
        if (serverMode == ServerMode.PUBLIC) AppConfig.PRESET_SERVER_URL else customUrl.trim()

    /** 登录页服务器行的圆点状态：null=未测 / true=可达 / false=不可达（探测对象 ≠ 当前服务器时视为未测） */
    fun probeState(): Boolean? {
        val last = lastProbe ?: return null
        return if (last.first == effectiveUrl()) last.second else null
    }

    private fun dialogTarget(): String? =
        if (dialogMode == ServerMode.PUBLIC) AppConfig.PRESET_SERVER_URL.ifEmpty { null } else dialogCustomUrl.trim().ifEmpty { null }

    /** 探测结果文案（仅弹窗内展示；不出现公开服务器地址） */
    private fun dialogStatusText(code: Int, mode: ServerMode): String {
        val who = if (mode == ServerMode.PUBLIC) "公开服务器" else "服务器"
        // 注意：中文是合法标识符字符，模板必须写 ${who}，否则 $who连接… 会被整体解析成变量名
        return when {
            code in 200..299 -> "${who}连接正常 ✓"
            code == 401 || code == 403 -> "${who}连接正常 ✓（接口需要登录）"
            code == 404 -> "${who}可达，但接口不存在（请检查地址与端口）"
            else -> "${who}连接失败（HTTP $code）"
        }
    }

    companion object {
        private val USERNAME_RE = Regex("^[a-zA-Z0-9_]{3,32}$")
    }
}
