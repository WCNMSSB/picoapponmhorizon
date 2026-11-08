package com.pvr.avatareditor;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Stub for PICO's avatar editor.
 *
 * The user center's avatar SDK calls getPackageInfo("com.pvr.avatareditor") while
 * loading a profile; without the package installed that call throws and the whole
 * avatar preview fails ("getCharacterData exception"). The real editor is a large
 * PICO app that needs PICO OS services, so this stub only satisfies the lookup and
 * tells the user why the editor itself is unavailable.
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF1C1B1F);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("PICO 形象编辑器");
        title.setTextColor(0xFFE6E1E5);
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("形象编辑器是 PICO OS 的独立 App，需要 PICO 私有服务，\n"
                + "目前只能显示形象中心里的头像与列表。");
        body.setTextColor(0xFFCAC4D0);
        body.setTextSize(14f);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, pad, 0, 0);
        root.addView(body);

        setContentView(root);
    }
}