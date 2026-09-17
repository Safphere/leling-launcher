package com.safphere.launcher.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.BatteryManager
import android.telephony.TelephonyManager
import com.safphere.launcher.BuildConfig
import com.safphere.launcher.data.Prefs

/**
 * 【对外集成接口】设备状态只读查询。
 *
 *   query content://com.safphere.launcher.status/status
 *   列: sim_absent(INT 0/1/-1未知) | flow_remaining_mb(FLOAT -1未知) |
 *       flow_data_off(INT 0/1) | battery_pct(INT) | updated_at(LONG)
 *
 * 读权限：com.safphere.launcher.permission.PUSH_ALERT（signature 级）。
 */
class SafphereStatusProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        if (uri.pathSegments.firstOrNull() != "status") return null
        val ctx = context ?: return null

        val cursor = MatrixCursor(
            arrayOf(
                "sim_absent", "flow_remaining_mb", "flow_data_off",
                "battery_pct", "updated_at"
            )
        )
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val simAbsent = when (tm?.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> 1
            null -> -1
            else -> 0
        }
        cursor.addRow(
            arrayOf(
                simAbsent,
                Prefs.flowRemainingMb,
                if (Prefs.flowDataOffByUs) 1 else 0,
                batteryPct(ctx),
                System.currentTimeMillis()
            )
        )
        return cursor
    }

    private fun batteryPct(ctx: Context): Int = runCatching {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 1..100 } ?: -1
    }.getOrDefault(-1)

    // 只读 Provider：写操作全部拒绝
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun getType(uri: Uri): String? =
        if (uri.pathSegments.firstOrNull() == "status")
            "vnd.android.cursor.dir/vnd.com.safphere.launcher.status" else null
}
