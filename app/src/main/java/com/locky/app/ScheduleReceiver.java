package com.locky.app;

import android.content.*;

public final class ScheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {Scheduler.fire(context);}
}
