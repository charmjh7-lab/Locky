package com.locky.app;

import android.content.*;

public final class ScheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {String action=intent.getAction();if(action!=null&&java.util.Set.of(Scheduler.ACTION,Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED).contains(action)){PendingResult result=goAsync();LockyApp.app.work.execute(()->{try{Scheduler.fire(context);}finally{result.finish();}});}}
}
