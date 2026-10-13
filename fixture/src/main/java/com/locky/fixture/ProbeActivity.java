package com.locky.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
/** Standalone foreground app for integration checks; never included in the user APK. */
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);TextView content=new TextView(this);
        content.setText("Locky 사용 시간 검증용 앱");content.setTextSize(20);
        content.setTextColor(0xff23372e);content.setBackgroundColor(0xfff4f3ed);content.setPadding(24,48,24,24);
        setContentView(content);
    }
}
