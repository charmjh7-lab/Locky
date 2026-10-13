package com.locky.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;

public final class MainActivity extends Activity {
    WebHost host;
    @Override public void onCreate(Bundle state){super.onCreate(state);androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);Store s=Store.get(this);if(!s.settings.contains("installed"))s.settings.edit().putLong("installed",System.currentTimeMillis()).apply();host=new WebHost(this,false);setContentView(host.root);String screen=getIntent().getStringExtra("screen");host.screen(screen==null?(s.settings.getString("last-screen",GuardService.live==null?"setup":"home")):screen);}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);if(i.getData()!=null)host.screen("chats");else if(i.hasExtra("screen"))host.screen(i.getStringExtra("screen"));}
    @Override protected void onResume(){super.onResume();LockyApp.app.auth.foreground(true);Scheduler.schedule(this);if(GuardService.live!=null)GuardService.live.pauseForActivity();WebHost.refreshAll();}
    @Override protected void onPause(){LockyApp.app.auth.foreground(false);super.onPause();}
    @Override public void onBackPressed(){host.evaluate("back()");}
    @Override protected void onDestroy(){host.close();super.onDestroy();}
}
