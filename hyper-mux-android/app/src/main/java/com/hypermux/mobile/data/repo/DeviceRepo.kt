package com.hypermux.mobile.data.repo

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.hypermux.mobile.data.api.ApiResult
import com.hypermux.mobile.data.api.ApiService
import com.hypermux.mobile.data.api.safeApiCall
import com.hypermux.mobile.data.model.CastDeviceVo
import com.hypermux.mobile.data.model.CastRegisterReq
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceStore by preferencesDataStore(name = "device")

/**
 * 设备身份与设备列表（契约 §3.13，计划书 80-cast-detailed.md §8）。
 *
 * deviceId = **安装级 UUID**：首次访问时生成并落 DataStore，之后只读；卸载重装即新 id。
 * 服务端只按 (user_id, device_id) 认设备，user_id 一律取自 token。
 *
 * 注册时机：登录成功、App 启动恢复会话、打开投屏设备弹层（幂等 upsert，顺带刷服务端"在线"）。
 */
@Singleton
class DeviceRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val gson: Gson
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore = context.deviceStore

    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val LAST_CAST_TARGET = stringPreferencesKey("cast_last_target")
    }

    suspend fun deviceId(): String {
        val stored = dataStore.data.first()[Keys.DEVICE_ID]
        if (!stored.isNullOrBlank()) return stored
        val fresh = UUID.randomUUID().toString()
        dataStore.edit { it[Keys.DEVICE_ID] = fresh }
        return fresh
    }

    suspend fun deviceName(): String {
        val stored = dataStore.data.first()[Keys.DEVICE_NAME]
        if (!stored.isNullOrBlank()) return stored
        return defaultName()
    }

    suspend fun setDeviceName(name: String) {
        dataStore.edit { it[Keys.DEVICE_NAME] = name.trim().take(64) }
    }

    /** 默认设备名：手机 · 机型（服务端截断 64 并清洗控制字符） */
    private fun defaultName(): String {
        val model = Build.MODEL.orEmpty().trim()
        return if (model.isBlank()) "手机" else "手机 · $model".take(64)
    }

    /** 幂等注册本机设备。失败静默（不阻塞任何主链路）。 */
    suspend fun registerSelf(): Boolean {
        val id = deviceId()
        val name = deviceName()
        return safeApiCall(gson) {
            api.registerDevice(CastRegisterReq(deviceId = id, platform = PLATFORM, deviceName = name))
        }.isSuccess
    }

    /** 启动 / 登录成功后的顺手注册：不抛异常、不阻塞调用方。 */
    fun registerSelfAsync() {
        scope.launch { runCatching { registerSelf() } }
    }

    /**
     * 同账号可投屏目标（接收端 = 全部四端：TV / 网页端 / 电脑端 / 手机端，与服务端 CastService.request 的放行口径一致）。
     * 取全部平台再本地过滤：一次请求拿到的行数本来就很少（同账号设备），比按平台各请求一次更省。
     * 只排掉本机自己（服务端同样会 400「不能投屏到本设备」）。失败返回空列表。
     */
    suspend fun listCastTargets(): List<CastDeviceVo> {
        val r = safeApiCall(gson) { api.castDevices(null) }
        if (r !is ApiResult.Ok) return emptyList()
        val self = deviceId()
        return r.data.filter { it.deviceId != self }
    }

    /** 上次成功投屏的 TV 设备 id（设备弹层据此默认高亮） */
    suspend fun lastCastTarget(): String? = dataStore.data.first()[Keys.LAST_CAST_TARGET]

    suspend fun setLastCastTarget(deviceId: String) {
        dataStore.edit { it[Keys.LAST_CAST_TARGET] = deviceId }
    }

    private companion object {
        const val PLATFORM = "mobile"
    }
}
