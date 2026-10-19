package com.hypermux.mobile.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 影片级元信息（「我的下载」页分组标题 / 海报用）。
 * 本地持久化：下载页在**离线**时也必须能显示正确的片名与封面（不能依赖再请求详情接口）。
 */
data class MovieMeta(
    val movieId: Long = 0L,
    val title: String = "",
    val coverUrl: String? = null
)

/**
 * 单集元信息：下载页一行/海报所需的最小信息。
 * [mediaId] = `"$movieId-$videoId"`，与 [MediaCacheManager.mediaIdOf] 一致（DownloadRequest 的 id）。
 */
data class EpisodeMeta(
    val mediaId: String = "",
    val movieId: Long = 0L,
    val videoId: Long = 0L,
    val seasonNo: Int = 1,
    val episodeNo: Int = 1,
    val title: String? = null
) {
    /** 展示名：`第N集` + 可选集标题 */
    val displayName: String
        get() = buildString {
            append("第").append(episodeNo).append("集")
            title?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
        }
}

/**
 * 下载元信息存储（SharedPreferences + Gson）。
 *
 * 为什么单独存一份、而不是只用 DownloadManager 的 DownloadRequest：
 * `DownloadRequest` 里只有 id/uri，没有片名/集名/海报（Media3 1.7 的 DownloadRequest 无 MediaItem 载体），
 * 下载页离线展示与"按影片归组"都靠这里。索引：`e_<mediaId>`（单集）+ `m_<movieId>`（影片）。
 */
@Singleton
class DownloadMetaStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("hypermux_download_meta", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ---- 影片 ----

    fun putMovie(meta: MovieMeta) {
        if (meta.movieId <= 0L) return
        prefs.edit().putString(movieKey(meta.movieId), gson.toJson(meta)).apply()
    }

    fun movie(movieId: Long): MovieMeta? = read(movieKey(movieId), MovieMeta::class.java)

    fun removeMovie(movieId: Long) {
        if (movieId <= 0L) return
        prefs.edit().remove(movieKey(movieId)).apply()
    }

    // ---- 单集 ----

    fun putEpisode(meta: EpisodeMeta) {
        if (meta.mediaId.isBlank()) return
        prefs.edit().putString(episodeKey(meta.mediaId), gson.toJson(meta)).apply()
    }

    fun episode(mediaId: String): EpisodeMeta? = read(episodeKey(mediaId), EpisodeMeta::class.java)

    fun removeEpisode(mediaId: String) {
        prefs.edit().remove(episodeKey(mediaId)).apply()
    }

    /** 所有单集元信息（下载页遍历用） */
    fun allEpisodes(): List<EpisodeMeta> =
        prefs.all.keys
            .filter { it.startsWith(PREFIX_EPISODE) }
            .mapNotNull { read(it, EpisodeMeta::class.java) }

    /**
     * 第七轮**一次性**迁移用：删除已无对应下载项的孤儿元数据。
     * - 旧 `v_<videoId>` 反查索引（"边播边缓存"归组用，新架构已不需要）整段清除；
     * - 不在 [existingMediaIds]（下载索引现有的 `"$movieId-$videoId"`）里的 `e_<mediaId>`；
     * - 清理后已无单集残留的 `m_<movieId>`。
     */
    fun pruneOrphans(existingMediaIds: Set<String>) {
        // 先算出将要保留的单集（prefs.all 是已提交快照，editor 内的变更不可见，必须在编辑前算好）
        val survivingMovieIds = allEpisodes()
            .filter { it.mediaId in existingMediaIds }
            .map { it.movieId }
            .toHashSet()
        prefs.edit().apply {
            prefs.all.keys.forEach { key ->
                when {
                    key.startsWith(PREFIX_VIDEO) -> remove(key)
                    key.startsWith(PREFIX_EPISODE) &&
                        key.removePrefix(PREFIX_EPISODE) !in existingMediaIds -> remove(key)
                    key.startsWith(PREFIX_MOVIE) &&
                        key.removePrefix(PREFIX_MOVIE).toLongOrNull() !in survivingMovieIds -> remove(key)
                }
            }
        }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // ---- 内部 ----

    private fun <T> read(key: String, cls: Class<T>): T? =
        prefs.getString(key, null)?.let { json ->
            runCatching { gson.fromJson(json, cls) }.getOrNull()
        }

    private fun movieKey(movieId: Long) = "$PREFIX_MOVIE$movieId"
    private fun episodeKey(mediaId: String) = "$PREFIX_EPISODE$mediaId"

    private companion object {
        const val PREFIX_MOVIE = "m_"
        const val PREFIX_EPISODE = "e_"
        /** 旧版本"边播边缓存"的反查索引前缀（新架构不再写入，仅迁移时清理） */
        const val PREFIX_VIDEO = "v_"
    }
}
