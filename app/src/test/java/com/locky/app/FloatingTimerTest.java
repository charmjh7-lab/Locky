package com.locky.app;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.ContentValues;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Proxy;
import java.time.ZonedDateTime;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk={35,36})
public class FloatingTimerTest {
    GuardService service;
    FloatingTimer timer;
    WindowManager.LayoutParams params;
    int added,removed;
    @Before public void setup(){
        Store.instance=null;
        service=Robolectric.buildService(GuardService.class).create().get();
        service.store=Store.get(service);
        service.store.settings.edit().clear().commit();
        int hour=ZonedDateTime.now().getHour()*60+ZonedDateTime.now().getMinute();
        service.store.settings.edit().putInt("start",hour).putInt("end",(hour+60)%1440).commit();
        ContentValues rule=new ContentValues();rule.put("pkg","user.video");rule.put("label","Video");rule.put("mode","limited");rule.put("minutes",60);
        service.store.getWritableDatabase().insertWithOnConflict("rules",null,rule,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
        WindowManager window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(proxy,method,args)->{
            if(method.getName().equals("addView")){params=(WindowManager.LayoutParams)args[1];added++;}
            if(method.getName().equals("removeViewImmediate"))removed++;
            return null;
        });
        timer=new FloatingTimer(service,window);service.floatingTimer=timer;
        service.foreground=service.sessionPkg="user.video";service.interactive=true;service.sessionRemaining=300000;
    }
    @After public void cleanup(){timer.hide();service.main.removeCallbacksAndMessages(null);service.store.close();Store.instance=null;}
    @Test public void countdownUsesExistingSessionAndDoesNotCaptureTouchesOrFocus(){
        service.syncFloatingTimer();assertNotNull(timer.root);assertEquals("05:00",timer.remaining.getText().toString());
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,params.type);
        assertTrue((params.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0);
        assertTrue((params.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,timer.root.getImportantForAccessibility());
        service.sessionRemaining=299001;service.syncFloatingTimer();assertEquals("05:00",timer.remaining.getText().toString());
        service.sessionRemaining=299000;service.syncFloatingTimer();assertEquals("04:59",timer.remaining.getText().toString());assertEquals(1,added);
    }
    @Test public void pauseAndResumeHideWithoutChangingTheGrant(){
        service.syncFloatingTimer();service.systemPaused=true;service.syncFloatingTimer();assertNull(timer.root);assertEquals(300000,service.sessionRemaining);
        service.systemPaused=false;service.syncFloatingTimer();assertNotNull(timer.root);assertEquals("05:00",timer.remaining.getText().toString());assertEquals(2,added);assertEquals(1,removed);
        service.foreground=service.getPackageName();service.syncFloatingTimer();assertNull(timer.root);
    }
    @Test public void unrelatedAppsGateScreenOffLockAndExpiryRemoveDisplay(){
        service.syncFloatingTimer();service.foreground="other.app";service.syncFloatingTimer();assertNull(timer.root);
        service.foreground=service.sessionPkg;service.syncFloatingTimer();service.overlayVisible=true;service.syncFloatingTimer();assertNull(timer.root);
        service.overlayVisible=false;service.syncFloatingTimer();service.interactive=false;service.syncFloatingTimer();assertNull(timer.root);
        service.interactive=true;service.syncFloatingTimer();Shadows.shadowOf(service.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);service.syncFloatingTimer();assertNull(timer.root);
        Shadows.shadowOf(service.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);service.syncFloatingTimer();service.sessionRemaining=0;service.syncFloatingTimer();assertNull(timer.root);
    }
    @Test public void settingPersistsAndFreeTimeOrRemovedRuleHidesTimer() throws Exception {
        assertTrue(service.store.preferences().getBoolean("floatingTimer"));service.syncFloatingTimer();
        service.store.settings.edit().putBoolean("floatingTimer",false).commit();service.syncFloatingTimer();assertNull(timer.root);assertFalse(service.store.preferences().getBoolean("floatingTimer"));
        service.store.settings.edit().putBoolean("floatingTimer",true).putBoolean("enabled",false).commit();service.syncFloatingTimer();assertNull(timer.root);
        service.store.settings.edit().putBoolean("enabled",true).commit();service.syncFloatingTimer();assertNotNull(timer.root);
        service.store.getWritableDatabase().delete("rules","pkg=?",new String[]{service.foreground});service.syncFloatingTimer();assertNull(timer.root);
    }
    @Test public void visibleTimerDoesNotTurnForegroundUsageIntoLockyUsage() throws Exception {
        service.syncFloatingTimer();service.segmentWall=System.currentTimeMillis()-2000;service.segmentElapsed=android.os.SystemClock.elapsedRealtime()-2000;service.checkpoint();
        assertTrue(service.sessionRemaining<=298000);assertEquals("user.video",service.foreground);assertFalse(service.overlayVisible);
        assertTrue(service.store.rows("SELECT * FROM usage WHERE pkg='user.video' AND managed=1").length()>0);
    }
}
