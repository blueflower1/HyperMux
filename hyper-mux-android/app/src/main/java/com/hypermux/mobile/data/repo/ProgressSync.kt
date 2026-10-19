package com.hypermux.mobile.data.repo

import android.util.Log
import com.hypermux.mobile.data.api.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线断点补报的触发点（单一入口）：
 * ① **App 启动时**立即尝试补报一次（上次离线退出/被杀进程留下的队列）；
 * ② **[NetworkMonitor] 离线 → 在线**时补报（网络恢复即补，用户不必等下一次切集）。
 *
 * 顺序补报 + 失败保留 + 单飞都在 [PendingProgressStore.flush] 里保证，这里只负责"何时触发"，
 * 因此重复触发（例如启动时本来就在线，会先手动补一次、再由 isOnline 的初值再补一次）不会造成重复提交。
 */
@Singleton
class ProgressSync @Inject constructor(
    private val store: PendingProgressStore,
    private val playbackRepo: PlaybackRepo,
    private val networkMonitor: NetworkMonitor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 幂等：重复调用只会多挂一个 collector，因此只在 Application 里调用一次 */
    fun start() {
        scope.launch {
            // ① 启动补报：直接上报失败也保留在队列里，等下次网络恢复
            runCatching { flushOnce() }
            // ② 离线 → 在线才补：StateFlow 会先发一次当前值（在线时等于再补一次，flush 幂等，
            //    队列为空时立即返回），之后每次网络恢复都会触发一次。
            networkMonitor.isOnline.collect { online ->
                if (online) runCatching { flushOnce() }
            }
        }
    }

    /** 手动补报一次（当前无 UI 入口，留给后续"我的"页诊断/播放进入时调用） */
    suspend fun flushOnce(): Int = store.flush { item ->
        runCatching {
            playbackRepo.reportProgress(
                videoId = item.videoId,
                positionMs = item.positionMs,
                durationMs = item.durationMs,
                finished = null
            )
            true
        }.onFailure { Log.w(TAG, "补报失败，保留在队列：videoId=${item.videoId}", it) }
            .getOrDefault(false)
    }

    /** 新队列项入队时也顺手尝试一次（在线时能立刻补上） */
    fun kick() {
        scope.launch { runCatching { flushOnce() } }
    }

    private companion object {
        const val TAG = "ProgressSync"
    }
}
