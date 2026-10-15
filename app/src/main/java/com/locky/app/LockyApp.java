package com.locky.app;

import android.app.*;
import android.os.*;
import java.util.concurrent.*;

public final class LockyApp extends Application {
    static LockyApp app;
    final ExecutorService work=Executors.newFixedThreadPool(3);
    ChatGPT auth;ChatEngine chat;
    volatile String authBrowser="";
    @Override public void onCreate() {super.onCreate();app=this;UnlockDiagnostics.get(this);auth=new ChatGPT(this);chat=new ChatEngine(this,auth);Notices.channels(this);Scheduler.schedule(this);Store s=Store.get(this);s.getWritableDatabase().execSQL("UPDATE messages SET status='interrupted' WHERE status='streaming'");work.execute(s::cleanOldUsage);work.execute(()->Scheduler.fire(this));}
}
