package com.hypermux.tv.data.repo

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.hypermux.tv.data.api.ApiResult
import com.hypermux.tv.data.api.ApiService
import com.hypermux.tv.data.api.safeApiCall
import com.hypermux.tv.data.model.CastDeviceVo
import com.hypermux.tv.data.model.CastRegisterReq
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceStore by preferencesDataStore(name = "device")

/**
 * TV 设备身份（契约 §3.13，计划书 80-cast-detailed.md §9）。
 *
 * deviceId = **安装级 UUID**：首次访问时生成并落 DataStore，之后只读。
 * 手机侧投屏时选的就是这个 id；服务端只按 (user_id, device_id) 认设备。
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

    /** 默认设备名：TV · 机型（手机的投屏设备列表里显示的就是它） */
    private fun defaultName(): String {
        val model = Build.MODEL.orEmpty().trim()
        return if (model.isBlank()) "电视" else "TV · $model".take(64)
    }

    suspend fun registerSelf(): Boolean {
        val id = deviceId()
        val name = deviceName()
        return safeApiCall(gson) {
            api.registerDevice(CastRegisterReq(deviceId = id, platform = PLATFORM, deviceName = name))
        }.isSuccess
    }

    fun registerSelfAsync() {
        scope.launch { runCatching { registerSelf() } }
    }

    suspend fun listDevices(): List<CastDeviceVo> {
        val r = safeApiCall(gson) { api.castDevices(null) }
        return if (r is ApiResult.Ok) r.data else emptyList()
    }

    private companion object {
        const val PLATFORM = "tv"
    }
}
