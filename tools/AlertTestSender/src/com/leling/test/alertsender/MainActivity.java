package com.leling.test.alertsender;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** 测试应用主界面：验证广告卫士跳过开屏后应回到这里。 */
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        android.util.Log.i("SplashAd", "MainActivity: reached (splash was skipped or finished)");
        TextView t = new TextView(this);
        t.setText("✅ 广告已结束\n这里是应用主界面");
        t.setTextColor(Color.WHITE);
        t.setTextSize(22);
        t.setGravity(Gravity.CENTER);
        setContentView(t);
    }
}
