package com.locky.app;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.SystemClock;
import android.view.WindowManager;
import java.lang.reflect.Proxy;
import java.time.Duration;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class LinkedLaunchTest {
    GuardService g;JSONObject purpose;Intent launch;
    @Before public void setup() throws Exception {
        Store.instance=null;LockyApp.app=new LockyApp();g=Robolectric.buildService(GuardService.class).create().get();g.store=Store.get(g);GuardService.live=g;
        int minute=Rules.minute(System.currentTimeMillis(),java.time.ZoneId.systemDefault());g.store.settings.edit().putBoolean("enabled",true).putInt("start",(minute+60)%1440).putInt("end",(minute+59)%1440).commit();g.wasManaged=true;g.interactive=true;
        g.window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->null);Notices.channels(g);
        ContentValues rule=new ContentValues();rule.put("pkg","app.target");rule.put("label","Target");rule.put("mode","limited");rule.put("minutes",60);g.store.put("rules",rule);
        purpose=new JSONObject().put("id","phone-task").put("label","할 일").put("pkg","app.target");
        launch=new Intent().setComponent(new ComponentName("app.target","app.target.Main"));ActivityInfo activity=new ActivityInfo();activity.packageName="app.target";activity.name="app.target.Main";activity.exported=true;ResolveInfo resolve=new ResolveInfo();resolve.activityInfo=activity;Shadows.shadowOf(g.getPackageManager()).addResolveInfoForIntent(launch,resolve);
        g.foreground=g.getPackageName();g.segmentWall=System.currentTimeMillis();g.segmentElapsed=SystemClock.elapsedRealtime();g.choosePhone(purpose);
    }
    @After public void cleanup(){GuardService.live=null;g.main.removeCallbacksAndMessages(null);if(g.overlay!=null)g.overlay.close();g.store.close();Store.instance=null;LockyApp.app.work.shutdownNow();LockyApp.app=null;}
    @Test public void confirmationStartsOneFiveMinuteChildSessionWithoutGate() throws Exception {
        g.prepareLinkedLaunch(purpose,launch);assertNotNull(g.linkedLaunch);assertEquals("",g.sessionPkg);
        g.switchForeground("app.target");assertNull(g.linkedLaunch);assertFalse(g.overlayVisible);assertEquals("app.target",g.sessionPkg);assertEquals(300000,g.sessionRemaining);assertEquals("할 일",g.purpose);
        JSONArray events=g.store.rows("SELECT * FROM events WHERE kind='app'");assertEquals(1,events.length());assertEquals(g.phoneEvent,events.getJSONObject(0).getLong("parent"));g.enforce();assertEquals(1,g.store.rows("SELECT * FROM events WHERE kind='app'").length());
    }
    @Test public void returningSessionKeepsItsOriginalTimePurposeAndEvent() throws Exception {
        g.pausedSessions.put("app.target",new GuardService.PausedSession(123000,98,"원래 목적",SystemClock.elapsedRealtime()+60000,g.policyEnd(System.currentTimeMillis())));
        g.prepareLinkedLaunch(purpose,launch);g.switchForeground("app.target");assertEquals(123000,g.sessionRemaining);assertEquals(98,g.sessionEvent);assertEquals("원래 목적",g.purpose);assertNull(g.linkedLaunch);
    }
    @Test public void anotherAppExpiredRequestScreenOffAndUnscopedUriCannotReuseGrant() throws Exception {
        g.prepareLinkedLaunch(purpose,launch);g.switchForeground("other.app");assertNull(g.linkedLaunch);g.switchForeground("app.target");assertEquals("",g.sessionPkg);assertTrue(g.overlayVisible);g.hideOverlay();g.foreground=g.getPackageName();
        g.prepareLinkedLaunch(purpose,launch);ShadowSystemClock.advanceBy(Duration.ofSeconds(11));g.switchForeground("app.target");assertEquals("",g.sessionPkg);
        g.foreground=g.getPackageName();g.prepareLinkedLaunch(purpose,launch);g.cancelLinkedLaunch();assertNull(g.linkedLaunch);
        g.prepareLinkedLaunch(new JSONObject().put("label","URI").put("uri","https://example.com"),launch);assertNull(g.linkedLaunch);
    }
    @Test public void lessThanFiveMinutesRemainingIsTheActualGrantCeiling() throws Exception {
        g.store.getWritableDatabase().execSQL("UPDATE rules SET minutes=5");long now=System.currentTimeMillis();
        g.store.usage(now-240000,now,"app.target","Target",true,0);
        g.prepareLinkedLaunch(purpose,launch);g.switchForeground("app.target");assertTrue(g.sessionRemaining>59000&&g.sessionRemaining<=60000);assertFalse(g.overlayVisible);
    }
    @Test public void lockedAndExhaustedAppsRemainBlocked() throws Exception {
        g.store.getWritableDatabase().execSQL("UPDATE rules SET mode='locked'");g.prepareLinkedLaunch(purpose,launch);g.switchForeground("app.target");assertEquals("",g.sessionPkg);assertEquals("blocked",g.gate);g.hideOverlay();g.foreground=g.getPackageName();
        g.store.getWritableDatabase().execSQL("UPDATE rules SET mode='limited'");g.store.settings.edit().putLong("blocked-until:app.target",System.currentTimeMillis()+60000).commit();g.prepareLinkedLaunch(purpose,launch);g.switchForeground("app.target");assertEquals("",g.sessionPkg);assertEquals("blocked",g.gate);
    }
}
