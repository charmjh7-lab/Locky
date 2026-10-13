package com.locky.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;

public final class MainActivity extends Activity {
    WebHost host;
    @Override public void onCreate(Bundle state){super.onCreate(state);Store s=Store.get(this);if(!s.settings.contains("installed"))s.settings.edit().putLong("installed",System.currentTimeMillis()).apply();host=new WebHost(this,false);setContentView(host.view);String screen=getIntent().getStringExtra("screen");host.screen(screen==null?(GuardService.live==null?"setup":"home"):screen);}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);host.screen(i.getData()!=null?"chats":i.getStringExtra("screen")==null?"home":i.getStringExtra("screen"));}
    @Override protected void onResume(){super.onResume();LockyApp.app.auth.foreground(true);if(GuardService.live!=null)GuardService.live.pauseForActivity();WebHost.refreshAll();}
    @Override protected void onPause(){LockyApp.app.auth.foreground(false);super.onPause();}
    @Override public void onBackPressed(){host.evaluate("back()");}
    @Override protected void onDestroy(){host.close();super.onDestroy();}
}
