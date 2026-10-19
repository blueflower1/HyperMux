package com.hypermux.tv.data.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 轻量 JSON 缓存（SharedPreferences）：home / 分类列表第 1 页的离线兜底。
 * key 含 userId + serverHost，换账号/换服务器不串数据；超软上限不存。
 */
@Singleton
class CacheStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val sp = context.getSharedPreferences("hypermux_cache", Context.MODE_PRIVATE)

    fun put(key: String, json: String, maxBytes: Int = MAX_DEFAULT) {
        if (json.length <= maxBytes) {
            sp.edit().putString(key, json).apply()
        }
    }

    fun read(key: String): String? = sp.getString(key, null)

    fun remove(key: String) {
        sp.edit().remove(key).apply()
    }

    companion object {
        const val MAX_DEFAULT = 64 * 1024
        const val LIST_PAGE_MAX = 32 * 1024
    }
}
