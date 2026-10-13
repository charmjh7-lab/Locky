package com.locky.fixture;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
public final class DictionaryActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView text=new TextView(this);text.setText("사전 검색: "+getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));text.setTextSize(20);setContentView(text);}
}
