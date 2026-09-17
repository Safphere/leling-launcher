package com.leling.test.alertsender;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1) 地震警报
        send("EARTHQUAKE", "地震预警", "预估烈度5.5，请就近躲避在坚固家具旁",
                "🌊", "地震了，请就近躲避在坚固家具旁", "HIGH", "地震预警测试App");

        // 2) SIM卡异常警报（带一键重启动作）
        send("SIM", "未检测到电话卡！", "电话卡可能松动了，请尝试重启手机",
                "⚠️", "未检测到电话卡", "REBOOT", "SIM监控测试");

        // 3) 状态查询
        try (Cursor c = getContentResolver().query(
                Uri.parse("content://com.safphere.launcher.status/status"),
                null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                Log.i("AlertSender", "status: sim_absent=" + c.getInt(0)
                        + " flow_mb=" + c.getFloat(1)
                        + " flow_off=" + c.getInt(2)
                        + " battery=" + c.getInt(3));
            } else {
                Log.i("AlertSender", "status query empty");
            }
        } catch (Exception e) {
            Log.i("AlertSender", "status query failed: " + e);
        }
        finish();
    }

    private void send(String type, String title, String message,
                      String icon, String speak, String level, String source) {
        Intent i = new Intent("com.safphere.launcher.action.PUSH_ALERT");
        i.setPackage("com.safphere.launcher");
        i.putExtra("type", type);
        i.putExtra("title", title);
        i.putExtra("message", message);
        i.putExtra("icon", icon);
        i.putExtra("speak", speak);
        i.putExtra("level", level);
        i.putExtra("action", "SIM".equals(type) ? "REBOOT" : "NONE");
        i.putExtra("source", source);
        sendBroadcast(i);
    }
}
