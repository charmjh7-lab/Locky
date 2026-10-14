package com.locky.app;

import android.app.Application;
import android.content.ContentValues;
import android.content.Intent;
import android.os.SystemClock;
import android.view.WindowManager;
import java.lang.reflect.Proxy;
import java.time.Duration;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class SessionReturnTest {
    GuardService g;
    long original;
    @Before public void setup() throws Exception {
        Store.instance=null;LockyApp.app=new LockyApp();
        g=Robolectric.buildService(GuardService.class).create().get();GuardService.live=g;
        g.store=Store.get(g);g.store.settings.edit().clear().putBoolean("enabled",true).putInt("start",0).putInt("end",0).commit();g.wasManaged=true;g.interactive=true;g.homePackage="launcher";
        for(String pkg:new String[]{"app.video","app.social"}){ContentValues v=new ContentValues();v.put("pkg",pkg);v.put("label",pkg);v.put("mode","limited");v.put("minutes",60);g.store.put("rules",v);}
        g.window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->null);
        Notices.channels(g);g.foreground=g.sessionPkg="app.video";g.purpose="영상 한 편";g.sessionRemaining=180000;
        g.sessionEvent=original=g.store.event("app",g.purpose,g.sessionPkg,"",0);g.sessionPolicyEnd=g.policyEnd(System.currentTimeMillis());resetClock();
    }
    void resetClock(){g.segmentWall=System.currentTimeMillis();g.segmentElapsed=SystemClock.elapsedRealtime();}
    @After public void cleanup(){GuardService.live=null;g.main.removeCallbacksAndMessages(null);if(g.overlay!=null)g.overlay.close();g.store.close();Store.instance=null;LockyApp.app.work.shutdownNow();LockyApp.app=null;}
    @Test public void messageRoundTripWithinFiveMinutesKeepsPurposeGrantAndEvent() throws Exception {
        g.switchForeground("app.messages");assertEquals("",g.sessionPkg);long remaining=g.pausedSessions.get("app.video").remaining;
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3));g.checkpoint();assertEquals(0,g.store.used("app.video",System.currentTimeMillis()));
        g.switchForeground("app.video");assertEquals("app.video",g.sessionPkg);assertEquals(remaining,g.sessionRemaining);assertEquals(original,g.sessionEvent);assertEquals("영상 한 편",g.purpose);assertFalse(g.overlayVisible);
        assertEquals(1,g.store.rows("SELECT id FROM events WHERE kind='app'").length());
    }
    @Test public void fiveMinutesIsTheActualDeadlineAndTransientEventsDoNotExtendIt(){
        g.switchForeground("app.messages");long deadline=g.pausedSessions.get("app.video").until;
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4));g.switchForeground("launcher");assertEquals(deadline,g.pausedSessions.get("app.video").until);
        ShadowSystemClock.advanceBy(Duration.ofMillis(60001));g.switchForeground("app.video");assertEquals("",g.sessionPkg);assertTrue(g.overlayVisible);assertEquals("app",g.gate);
    }
    @Test public void separateManagedAppsResumeTheirOwnOriginalPurposesAndTimes() throws Exception {
        g.switchForeground("app.social");assertEquals("app",g.gate);g.allowApp(new JSONObject().put("label","소식 확인"),5);long social=g.sessionEvent;
        g.sessionRemaining=120000;resetClock();g.switchForeground("app.video");assertEquals(original,g.sessionEvent);assertEquals("영상 한 편",g.purpose);assertTrue(g.sessionRemaining<=180000&&g.sessionRemaining>179000);
        g.switchForeground("app.social");assertEquals(social,g.sessionEvent);assertEquals("소식 확인",g.purpose);assertTrue(g.sessionRemaining<=120000&&g.sessionRemaining>119000);assertFalse(g.overlayVisible);
    }
    @Test public void explicitlyStoppingOneAppDoesNotDiscardAnotherAppsPausedGrant() throws Exception {
        g.switchForeground("app.social");g.allowApp(new JSONObject().put("label","소식 확인"),5);long social=g.sessionEvent;
        g.switchForeground("app.video");g.stopApp();assertFalse(g.pausedSessions.containsKey("app.video"));assertTrue(g.pausedSessions.containsKey("app.social"));
        g.switchForeground("app.social");assertEquals(social,g.sessionEvent);assertFalse(g.overlayVisible);
    }
    @Test public void expiredGrantReturnsToExtensionWithoutReceivingNewMinutes(){
        g.sessionRemaining=0;g.switchForeground("app.messages");g.switchForeground("app.video");assertEquals(0,g.sessionRemaining);assertEquals(original,g.sessionEvent);assertEquals("renew",g.gate);
    }
    @Test public void screenOffAndFreePeriodClearBothActiveAndParkedGrants(){
        g.switchForeground("app.messages");g.screen.onReceive(g,new Intent(Intent.ACTION_SCREEN_OFF));assertTrue(g.pausedSessions.isEmpty());assertEquals("",g.sessionPkg);
        g.interactive=true;g.foreground=g.sessionPkg="app.video";g.sessionPolicyEnd=g.policyEnd(System.currentTimeMillis());g.sessionRemaining=120000;g.parkSession();
        g.store.settings.edit().putBoolean("enabled",false).commit();g.enforce();assertTrue(g.pausedSessions.isEmpty());assertEquals("",g.sessionPkg);
    }
    @Test public void elapsedPolicyBoundaryInvalidatesCacheEvenWhenFocusIsActiveAgain(){
        g.sessionPolicyEnd=System.currentTimeMillis()-1;g.switchForeground("app.messages");g.switchForeground("app.video");assertEquals("",g.sessionPkg);assertEquals("app",g.gate);
    }
}
