package com.locky.app;

import android.app.*;
import android.content.Context;
import android.os.PowerManager;
import java.time.*;
import java.util.TimeZone;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class FocusAlertsTest {
    Context c;Store s;NotificationManager notifications;TimeZone old;
    long at(String time){return LocalDateTime.parse("2026-11-09T"+time).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli();}
    @Before public void setup(){
        old=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        c=RuntimeEnvironment.getApplication();Store.instance=null;s=Store.get(c);
        s.settings.edit().clear().putBoolean("enabled",true).putInt("start",540).putInt("end",1260).putString("reminders","[30,20,10,5,1]").commit();
        notifications=c.getSystemService(NotificationManager.class);
        Shadows.shadowOf((Application)c).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    }
    @After public void cleanup(){s.close();Store.instance=null;TimeZone.setDefault(old);}
    void screen(boolean on){Shadows.shadowOf(c.getSystemService(PowerManager.class)).setIsInteractive(on);}
    @Test public void screenOffSkipsPreviewsAndWakeDoesNotReplayThemButStartPostsOnce() throws Exception {
        screen(false);
        for(String time:new String[]{"08:30","08:40","08:50","08:55","08:59"})Scheduler.focusAlerts(c,at(time));
        assertEquals(0,notifications.getActiveNotifications().length);
        screen(true);Scheduler.focusAlerts(c,at("08:59:10"));assertEquals(0,notifications.getActiveNotifications().length);
        screen(false);Scheduler.focusAlerts(c,at("09:00"));
        assertEquals(1,notifications.getActiveNotifications().length);
        assertEquals("집중시간이 시작됐어요",notifications.getActiveNotifications()[0].getNotification().extras.getString(Notification.EXTRA_TITLE));
        notifications.cancelAll();Scheduler.focusAlerts(c,at("09:00:20"));assertEquals(0,notifications.getActiveNotifications().length);
    }
    @Test public void screenOnUsesNewHighChannelAndStartReplacesOldAndNewPreviews() throws Exception {
        notifications.createNotificationChannel(new NotificationChannel("reminder","Old",NotificationManager.IMPORTANCE_DEFAULT));
        screen(true);Scheduler.focusAlerts(c,at("08:55"));
        Notification n=notifications.getActiveNotifications()[0].getNotification();
        assertEquals(Notices.FOCUS_CHANNEL,n.getChannelId());
        assertEquals(NotificationManager.IMPORTANCE_HIGH,notifications.getNotificationChannel(n.getChannelId()).getImportance());
        notifications.notify(31,new Notification.Builder(c,"reminder").setSmallIcon(R.drawable.icon_mono).setContentTitle("Old preview").build());
        Scheduler.focusAlerts(c,at("08:59"));Scheduler.focusAlerts(c,at("09:00"));
        assertEquals(1,notifications.getActiveNotifications().length);
        assertEquals("집중시간이 시작됐어요",notifications.getActiveNotifications()[0].getNotification().extras.getString(Notification.EXTRA_TITLE));
    }
    @Test public void delayedOvernightStartIsDeliveredOnceAndFreshMidWindowLaunchIsQuiet() throws Exception {
        s.settings.edit().putInt("start",1320).putInt("end",420).commit();screen(false);
        Scheduler.focusAlerts(c,at("21:59"));Scheduler.focusAlerts(c,at("22:05"));
        assertEquals(1,notifications.getActiveNotifications().length);
        notifications.cancelAll();
        Scheduler.focusAlerts(c,at("22:06")+6*3600000L);assertEquals(0,notifications.getActiveNotifications().length);
        s.settings.edit().remove("focus-alert-started").remove("focus-alert-scheduled-start").commit();
        Scheduler.focusAlerts(c,at("22:30"));assertEquals(0,notifications.getActiveNotifications().length);
    }
    @Test public void disabledScheduleAndDeniedPermissionStayQuiet() throws Exception {
        screen(true);s.settings.edit().putBoolean("enabled",false).commit();
        Scheduler.focusAlerts(c,at("08:59"));Scheduler.focusAlerts(c,at("09:00"));assertEquals(0,notifications.getActiveNotifications().length);
        s.settings.edit().putBoolean("enabled",true).commit();
        Shadows.shadowOf((Application)c).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        Scheduler.focusAlerts(c,at("08:59"));Scheduler.focusAlerts(c,at("09:00"));assertEquals(0,notifications.getActiveNotifications().length);
    }
}
