package com.leling.test.alertsender;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

/** 模拟开屏广告：全屏"广告"页 + 右上角「跳过 N」按钮，用于验证乐龄桌面的广告卫士自动跳过。 */
public class SplashAdActivity extends Activity {
    private int left = 3;
    private Button skip;
    private final Handler h = new Handler();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (left <= 0) { skip.setText("跳过"); return; }
            skip.setText("跳过 " + left);
            left--;
            h.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        android.util.Log.i("SplashAd", "onCreate: splash showing");
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#10131A"));

        TextView ad = new TextView(this);
        ad.setText("模拟开屏广告\n（验证广告卫士）");
        ad.setTextColor(Color.WHITE);
        ad.setTextSize(26);
        ad.setGravity(Gravity.CENTER);
        root.addView(ad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        skip = new Button(this);
        skip.setText("跳过 3");
        skip.setAllCaps(false);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        sp.setMargins(0, 60, 40, 0);
        root.addView(skip, sp);

        skip.setOnClickListener(v -> {
            android.util.Log.i("SplashAd", "skip clicked -> finish");
            finish();
        });

        setContentView(root);
        h.postDelayed(tick, 600);
        h.postDelayed(new Runnable() { @Override public void run() { finish(); } }, 10000);
    }
}
