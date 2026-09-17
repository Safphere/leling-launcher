package com.safphere.launcher.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用列表快照：把上次枚举结果（包名+标签）持久化，
 * 重启后冷启动无需等 PackageManager 全量枚举即可立即渲染应用页（"秒出"）。
 * 快照很小（每项约几十字节），同步读取无感知；枚举完成后由后台线程回写。
 */
object AppsSnapshot {

    data class Entry(val pkg: String, val label: String)

    private const val KEY = "apps_snapshot"

    private fun prefs(context: Context) =
        context.getSharedPreferences("apps_snapshot", Context.MODE_PRIVATE)

    fun load(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val pkg = o.optString("p")
                if (pkg.isBlank()) null else Entry(pkg, o.optString("l"))
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("p", it.pkg).put("l", it.label)) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
        if (com.safphere.launcher.BuildConfig.DEBUG) android.util.Log.w("AppsSnapshot", "saved n=${entries.size}")
    }
}
