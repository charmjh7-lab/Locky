package com.locky.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk={35,36})
public class NoticesTest {
    Context context;
    NotificationManager manager;
    @Before public void setup(){
        context=RuntimeEnvironment.getApplication();manager=context.getSystemService(NotificationManager.class);
        Shadows.shadowOf((Application)context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        Notices.channels(context);
    }
    @Test public void runningTimerMatchesTheDevicePromotionContract(){
        long before=System.currentTimeMillis();
        Notification n=Notices.timerNotification(context,"Video",600000,300000,false);
        assertEquals("timer",n.getChannelId());
        assertTrue((n.flags&Notification.FLAG_ONGOING_EVENT)!=0);
        assertEquals(Notification.VISIBILITY_PRIVATE,n.visibility);
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        assertTrue(n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN));
        assertTrue(n.when>=before+300000&&n.when<=System.currentTimeMillis()+300000);
        assertTrue(n.extras.getString(Notification.EXTRA_TEXT).contains("오늘 남은 10:00"));
        if(Build.VERSION.SDK_INT>=36){
            assertTrue(n.extras.getBoolean("android.requestPromotedOngoing"));
            assertTrue("Timer must satisfy this framework's own promotion predicate",n.hasPromotableCharacteristics());
            // The system alone grants this flag. Building an eligible notification cannot fake it.
            assertEquals(0,n.flags&Notification.FLAG_PROMOTED_ONGOING);
        }else{
            assertFalse(n.extras.getBoolean("android.requestPromotedOngoing"));
            assertFalse(n.extras.getBoolean(Notification.EXTRA_COLORIZED));
        }
    }
    @Test @Config(sdk=36) public void originalAndroid16RejectsThePreviousRequestOnlyTimer(){
        Notification previous=new Notification.Builder(context,"timer").setSmallIcon(R.drawable.icon_mono)
            .setContentTitle("Video · 이번 사용").setContentText("이번 사용 05:00 · 오늘 남은 10:00")
            .setWhen(System.currentTimeMillis()+300000).setShowWhen(true).setUsesChronometer(true)
            .setChronometerCountDown(true).setContentIntent(Notices.open(context,"timer"))
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
        previous.extras.putBoolean("android.requestPromotedOngoing",true);
        assertFalse("The old request key alone does not satisfy original Android 16",previous.hasPromotableCharacteristics());
        assertTrue(Notices.timerNotification(context,"Video",600000,300000,false).hasPromotableCharacteristics());
    }
    @Test public void pauseReplacesCountdownAndResumeRestartsIt(){
        Notices.timer(context,"Video",600000,300000);
        Notices.paused(context,"Video",595000,295000);
        assertEquals(1,manager.getActiveNotifications().length);
        Notification paused=manager.getActiveNotifications()[0].getNotification();
        assertFalse(paused.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        assertEquals(0,paused.when);
        assertTrue(paused.extras.getString(Notification.EXTRA_TITLE).contains("일시 정지"));
        if(Build.VERSION.SDK_INT>=36)assertTrue(paused.hasPromotableCharacteristics());
        Notices.timer(context,"Video",595000,295000);
        Notification resumed=manager.getActiveNotifications()[0].getNotification();
        assertTrue(resumed.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        assertTrue(resumed.when>=System.currentTimeMillis()+294900);
    }
    @Test public void exhaustedAndEndedSessionsRemoveTheTimer(){
        Notices.timer(context,"Video",600000,300000);
        Notices.timer(context,"Video",600000,0);
        assertEquals(0,manager.getActiveNotifications().length);
        Notices.timer(context,"Video",600000,300000);
        Notices.paused(context,"Video",0,10000);
        assertEquals(0,manager.getActiveNotifications().length);
        Notices.timer(context,"Video",600000,300000);
        Notices.cancelTimer(context);
        assertEquals(0,manager.getActiveNotifications().length);
    }
    @Test public void diagnosisDoesNotConfuseEligibilityWithSystemPromotion() throws Exception {
        JSONObject idle=Notices.timerStatus(context);
        assertFalse(idle.getBoolean("posted"));
        Notices.timer(context,"Video",600000,300000);
        JSONObject running=Notices.timerStatus(context);
        assertTrue(running.getBoolean("posted"));
        if(Build.VERSION.SDK_INT>=36){
            assertTrue(running.getBoolean("eligible"));
            assertFalse(running.getBoolean("promoted"));
            assertEquals(manager.canPostPromotedNotifications(),running.getBoolean("permitted"));
        }else assertFalse(running.getBoolean("supported"));
    }
    @Test public void deniedNotificationPermissionDoesNotPost(){
        Shadows.shadowOf((Application)context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        Notices.timer(context,"Video",600000,300000);
        Notices.paused(context,"Video",600000,300000);
        assertEquals(0,manager.getActiveNotifications().length);
    }
    @Test public void existingChannelChoicesArePreserved() throws Exception {
        manager.deleteNotificationChannel("timer");
        manager.createNotificationChannel(new NotificationChannel("timer","Test",NotificationManager.IMPORTANCE_MIN));
        Notices.channels(context);
        assertEquals(NotificationManager.IMPORTANCE_MIN,Notices.timerStatus(context).getInt("channelImportance"));
    }
    @Test public void missingPromotionSettingsFallsBackToAppSettings(){
        Intent intent=Notices.timerSettings(context,true);
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,intent.getAction());
        assertEquals(context.getPackageName(),intent.getStringExtra(Settings.EXTRA_APP_PACKAGE));
    }
    @Test @Config(sdk=36) public void availablePromotionSettingsOpenForThisApp(){
        Intent action=new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS);
        android.content.pm.ResolveInfo resolved=new android.content.pm.ResolveInfo();
        resolved.activityInfo=new android.content.pm.ActivityInfo();
        resolved.activityInfo.packageName="com.android.settings";resolved.activityInfo.name="PromotionSettings";
        Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(action,resolved);
        Intent opened=Notices.timerSettings(context,true);
        assertEquals(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS,opened.getAction());
        assertEquals(context.getPackageName(),opened.getStringExtra(Settings.EXTRA_APP_PACKAGE));
    }
}
