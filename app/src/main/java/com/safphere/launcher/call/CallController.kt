package com.safphere.launcher.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** 通话状态与挂断：TelecomManager.endCall（需 ANSWER_PHONE_CALLS 运行时权限，API 28+） */
object CallController {

    /** 当前是否有通话（含拨出中/接通中） */
    fun isInCall(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).isInCall
        } else {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode == AudioManager.MODE_IN_CALL ||
                am.mode == AudioManager.MODE_IN_COMMUNICATION ||
                (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
                    ?.callState != TelephonyManager.CALL_STATE_IDLE
        }
    }.getOrDefault(false)

    fun canEndCall(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    /** 挂断当前通话；无权限或失败返回 false */
    fun endCall(context: Context): Boolean {
        if (!canEndCall(context)) return false
        return runCatching {
            (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
        }.getOrDefault(false)
    }
}
