package com.hypermux.mobile.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.hypermux.mobile.data.api.ApiResult
import com.hypermux.mobile.data.api.ApiService
import com.hypermux.mobile.data.api.safeApiCall
import com.hypermux.mobile.data.model.QrIdReq
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 扫码流程结果（UI 消费一次后经 resumeScanning 清空、继续扫码） */
sealed class ScanResult {
    data class Success(val targetName: String) : ScanResult()
    data class Error(val msg: String) : ScanResult()
}

/**
 * 扫一扫登录 TV / 网页端：解码 → 识别自家载荷 → 通知服务端「已扫码」（目标端显示「待确认」）
 * → 用户弹框确认 → confirm（token 由目标端轮询取走，手机端不经手会话）。
 * 手机端只有登录后的页面能看到扫一扫入口，「必须登录才能扫码」天然成立。
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val api: ApiService,
    private val gson: Gson
) : ViewModel() {

    /** 已扫到、待用户确认的 qrId（非空 → UI 弹确认框） */
    private val _foundQrId = MutableStateFlow<String?>(null)
    val foundQrId: StateFlow<String?> = _foundQrId

    /** 登录目标："tv" / "web"（确认框文案区分 TV / 网页端） */
    private val _foundKind = MutableStateFlow<String?>(null)
    val foundKind: StateFlow<String?> = _foundKind

    private val _confirming = MutableStateFlow(false)
    val confirming: StateFlow<Boolean> = _confirming

    private val _result = MutableStateFlow<ScanResult?>(null)
    val result: StateFlow<ScanResult?> = _result

    /** 相机解出一段文本：只接受自家登录载荷（TV / Web），其它二维码提示后继续扫 */
    fun onQrDecoded(text: String) {
        if (_foundQrId.value != null || _confirming.value || _result.value != null) return
        val m = QR_RE.find(text.trim())
        if (m == null) {
            _result.value = ScanResult.Error("无法识别的二维码")
            return
        }
        _foundKind.value = m.groupValues[1]
        _foundQrId.value = m.groupValues[2]
        // 通知目标端「已扫码」（失败不阻塞确认流程：confirm 时服务端会再校验状态）
        val id = m.groupValues[2]
        viewModelScope.launch {
            safeApiCall(gson) { api.qrScan(QrIdReq(id)) }
        }
    }

    fun confirmLogin() {
        val id = _foundQrId.value ?: return
        val kind = _foundKind.value ?: KIND_TV
        if (_confirming.value) return
        viewModelScope.launch {
            _confirming.value = true
            val r = safeApiCall(gson) { api.qrConfirm(QrIdReq(id)) }
            _confirming.value = false
            _foundQrId.value = null
            _foundKind.value = null
            _result.value = when (r) {
                is ApiResult.Ok -> ScanResult.Success(targetName(kind))
                is ApiResult.Err -> ScanResult.Error(
                    when (r.code) {
                        404 -> "登录码已过期，请在${targetName(kind)}上刷新二维码"
                        401 -> "登录已失效，请重新登录后再扫码"
                        else -> r.msg.ifBlank { "确认登录失败" }
                    }
                )
            }
        }
    }

    /** 取消确认 → 回到扫码 */
    fun cancelConfirm() {
        _foundQrId.value = null
        _foundKind.value = null
    }

    /** 消费结果后继续扫码 */
    fun resumeScanning() {
        _result.value = null
    }

    companion object {
        const val KIND_TV = "tv"
        const val KIND_WEB = "web"

        /** 载荷格式：hypermux://tv-login?id=<uuid> 或 hypermux://web-login?id=<uuid>（只含不透明 id，不含任何秘密） */
        private val QR_RE = Regex("^hypermux://(tv|web)-login\\?id=([0-9a-fA-F-]{36})$")

        /** UI 用的目标显示名 */
        fun targetName(kind: String?): String = if (kind == KIND_WEB) "网页端" else "TV 端"
    }
}
