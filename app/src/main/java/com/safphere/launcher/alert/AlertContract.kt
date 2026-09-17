package com.safphere.launcher.alert

/** 对外集成契约：动作、权限、Extras 键（详见 docs/开发指南.md） */
object AlertContract {
    /** 推送警报广播 action（需 setPackage("com.safphere.launcher")） */
    const val ACTION_PUSH = "com.safphere.launcher.action.PUSH_ALERT"

    /** 推送权限（signature 级，仅同签名应用可推送；集成方需在清单中声明 uses-permission） */
    const val PERMISSION_PUSH = "com.safphere.launcher.permission.PUSH_ALERT"

    /** 状态查询 Provider URI（content://com.safphere.launcher.status/status） */
    const val STATUS_URI = "content://com.safphere.launcher.status/status"

    // Extras
    const val EXTRA_TYPE = "type"
    const val EXTRA_TITLE = "title"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_ICON = "icon"
    const val EXTRA_SPEAK = "speak"
    const val EXTRA_ACTION = "action"
    const val EXTRA_LEVEL = "level"
    const val EXTRA_SOURCE = "source"
}
