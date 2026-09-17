package com.safphere.launcher.perm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** 应用所需权限的清单与状态（供权限自检页/向导使用） */
object PermCatalog {

    enum class Kind { RUNTIME, ACCESSIBILITY, AUTO_START }

    data class Item(
        val label: String,
        val usage: String,
        val permission: String?,   // RUNTIME 类的权限字符串；其他类为 null
        val kind: Kind
    )

    val ITEMS: List<Item> = listOf(
        Item("电话拨打", "点联系人照片直接呼出", Manifest.permission.CALL_PHONE, Kind.RUNTIME),
        Item("发送短信", "紧急求助短信、流量查询短信", Manifest.permission.SEND_SMS, Kind.RUNTIME),
        Item("接收短信", "接收流量查询回复、自动管控", Manifest.permission.RECEIVE_SMS, Kind.RUNTIME),
        Item("读取短信", "读取流量回复内容", Manifest.permission.READ_SMS, Kind.RUNTIME),
        Item("通讯录", "从手机通讯录导入联系人", Manifest.permission.READ_CONTACTS, Kind.RUNTIME),
        Item("电话状态", "检测电话卡是否在位", Manifest.permission.READ_PHONE_STATE, Kind.RUNTIME),
        Item("麦克风", "语音问答", Manifest.permission.RECORD_AUDIO, Kind.RUNTIME),
        Item("定位", "SOS 求助短信附带位置", Manifest.permission.ACCESS_FINE_LOCATION, Kind.RUNTIME),
        Item("通知", "重要提醒通知", Manifest.permission.POST_NOTIFICATIONS, Kind.RUNTIME),
        Item("无障碍服务", "一键重启弹电源菜单", null, Kind.ACCESSIBILITY),
        Item("自启动（小米）", "保证每日检测和短信接收不被杀", null, Kind.AUTO_START)
    )

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    /** 无障碍服务是否已开启 */
    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(context.packageName)
    }
}
