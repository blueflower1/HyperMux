package com.hypermux.tv.data.repo

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pendingProgressStore by preferencesDataStore(name = "pending_progress")

/** 一条待补报的断点（字段与 [com.hypermux.tv.data.model.ProgressReq] 对齐 + 入队时间戳） */
data class PendingProgress(
    val videoId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val ts: Long
)

/**
 * 离线断点补报队列（DataStore + Gson）。
 *
 * **为什么按 videoId 去重、只留最新**：断点上报是 10s 一次的"状态快照"而不是事件流，
 * 同集的旧快照没有任何价值（后一条一定比前一条新）。若不去重，一次离线观影会堆出成百上千条，
 * 补报时既浪费带宽又会把服务端写放大；只留最新还能保证恢复后一次补到"最后看到哪"。
 *
 * 与断点续播的关系：**服务端 detail.progress 仍是续播的唯一来源**；
 * 本地队列只负责"网络恢复后把离线期间的进度补上去"，不参与续播位置计算，因此不会破坏现有续播逻辑。
 */
@Singleton
class PendingProgressStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val QUEUE = stringPreferencesKey("queue_json")
    }

    private val dataStore: DataStore<Preferences> = context.pendingProgressStore

    /** 写入 / 单飞锁：并发 flush 时只有第一个真正跑，其余等它结束后再判断（防重复补报） */
    private val writeMutex = Mutex()
    private val flushMutex = Mutex()

    val pending: StateFlow<List<PendingProgress>> = dataStore.data
        .map { prefs -> decode(prefs[Keys.QUEUE]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 入队（同集只留最新） */
    suspend fun enqueue(item: PendingProgress) = writeMutex.withLock {
        val next = (current().filter { it.videoId != item.videoId } + item)
            .sortedBy { it.ts }
        write(next)
    }

    /** 补报成功 → 摘掉该集 */
    suspend fun remove(videoId: Long) = writeMutex.withLock {
        write(current().filter { it.videoId != videoId })
    }

    suspend fun clear() = writeMutex.withLock { write(emptyList()) }

    /**
     * 顺序补报：成功即删、失败保留（**失败的那条之后的也一并保留**，避免乱序覆盖服务端进度）。
     * 单飞：并发调用会排队，不会重复提交同一条。
     *
     * @param report 真正的上报动作；返回 true = 成功
     * @return 还剩几条未补报
     */
    suspend fun flush(report: suspend (PendingProgress) -> Boolean): Int = flushMutex.withLock {
        val queue = current()
        if (queue.isEmpty()) return@withLock 0
        var remaining = queue
        for (item in queue) {
            val ok = runCatching { report(item) }.getOrDefault(false)
            if (!ok) break
            remaining = remaining.filter { it.videoId != item.videoId }
            writeMutex.withLock { write(remaining) }
        }
        if (remaining.size != queue.size) {
            Log.i(TAG, "离线断点补报：${queue.size - remaining.size} 条成功，剩余 ${remaining.size} 条")
        }
        remaining.size
    }

    /** 供 UI 直接显示"待补报 N 条"（当前未使用，保留给后续诊断入口） */
    val pendingCount: Int get() = pending.value.size

    fun preload() {
        scope.launch { pending.first() }
    }

    /**
     * 读当前队列：**直接读 DataStore**而不是读 [pending] 的 StateFlow 值。
     * 为什么：StateFlow 的更新是异步的，入队后立刻读可能还是旧快照，
     * 会让"读-改-写"丢掉刚入队的那条（例如上报失败与播完上报几乎同时发生）。
     */
    private suspend fun current(): List<PendingProgress> =
        decode(dataStore.data.first()[Keys.QUEUE])

    private suspend fun write(list: List<PendingProgress>) {
        runCatching { dataStore.edit { it[Keys.QUEUE] = gson.toJson(list) } }
    }

    private fun decode(json: String?): List<PendingProgress> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<PendingProgress>>() {}.type
            gson.fromJson<List<PendingProgress>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "PendingProgress"
        private val gson = Gson()
    }
}
