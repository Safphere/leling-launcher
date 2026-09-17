package com.safphere.launcher.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 设备管理员接收器。被设置为 Device Owner 后（子女用 adb 或二维码配置），
 * DevicePolicyManager.reboot() 即可静默重启手机。
 */
class ElderDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, ElderDeviceAdminReceiver::class.java)
    }

    override fun onEnabled(context: Context, intent: Intent) {}
    override fun onDisabled(context: Context, intent: Intent) {}
}
