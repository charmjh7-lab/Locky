package com.locky.app;

import android.app.Application;
import android.view.WindowManager;
import android.os.SystemClock;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class GateRestoreTest {
    GuardService g;int adds;
    @Before public void setup(){Store.instance=null;g=Robolectric.buildService(GuardService.class).create().get();g.store=Store.get(g);g.interactive=true;g.foreground="video";g.segmentWall=System.currentTimeMillis();g.segmentElapsed=SystemClock.elapsedRealtime();g.window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->{if(m.getName().equals("addView"))adds++;return null;});}
    @After public void cleanup(){g.main.removeCallbacksAndMessages(null);if(g.overlay!=null)g.overlay.close();g.store.close();Store.instance=null;GuardService.live=null;}
    @Test public void onlyManagedAppGatesUseTheSingleAccessibilityWindow(){
        g.showGate("app","video");Object web=g.overlay.view;long nav=g.overlay.navigationGeneration;
        g.showGate("app","video");assertEquals(1,adds);assertSame(web,g.overlay.view);assertEquals(nav,g.overlay.navigationGeneration);
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,g.overlayLayout.type);
        g.pauseSystem();assertTrue(g.overlayVisible);g.systemPaused=false;g.revealOverlay();assertSame(web,g.overlay.view);
        g.showGate("blocked","video");assertEquals(1,adds);assertSame(web,g.overlay.view);
    }
    @Test public void phoneScreenOffCreatesNoOverlayAndCannotBeSentThroughShowGate(){
        g.screenOff();assertNull(g.overlay);assertEquals(0,adds);assertFalse(g.overlayVisible);
        try{g.showGate("unlock","");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void prewarmCreatesNoWindowAndWarmEntriesDoNotShowLoadingOrAcceptOldInput() throws Exception {
        g.prepareAppGate();assertNull(g.overlayWindow);assertEquals(0,adds);assertFalse(g.overlayVisible);
        WebHost host=g.overlay;Object web=host.view;host.ready=true;host.everRendered=true;host.renderedScreen="gateIdle";
        g.showGate("app","video");assertEquals(android.view.View.GONE,host.loadingView.getVisibility());assertFalse(g.acceptGateInput());
        assertEquals(android.view.View.VISIBLE,g.overlayWindow.getVisibility());
        assertEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        assertTrue(g.overlayWindow.dispatchTouchEvent(android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_DOWN,1,1,0)));
        long old=host.navigationGeneration;host.rendered(host.view,"app",old,2);host.frameReady=true;g.revealOverlay();assertTrue(g.acceptGateInput());
        g.foreground="social";g.showGate("app","social");assertFalse(g.acceptGateInput());assertFalse(host.frameReady);assertEquals(android.view.View.GONE,host.loadingView.getVisibility());
        assertEquals(android.view.View.VISIBLE,g.overlayWindow.getVisibility());assertEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        assertNotEquals(host.navigationGeneration,host.renderedNavigation);
        host.rendered(host.view,"app",old,3);assertFalse(host.frameReady);assertSame(web,host.view);assertEquals(1,adds);assertEquals("social",g.gatePayload().getJSONObject("gate").getString("pkg"));
    }
    @Test public void coldEntryDrawsWithoutLoadingAndSystemSuspensionRemainsPassive(){
        g.showGate("app","video");assertFalse(g.overlay.ready);assertEquals(GuardService.Exposure.WAITING,g.exposure);
        assertEquals(android.view.View.VISIBLE,g.overlayWindow.getVisibility());assertEquals(android.view.View.VISIBLE,g.overlay.view.getVisibility());
        assertEquals(android.view.View.GONE,g.overlay.loadingView.getVisibility());assertEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        g.pauseSystem();assertEquals(GuardService.Exposure.SUSPENDED,g.exposure);assertEquals(android.view.View.GONE,g.overlayWindow.getVisibility());
        assertNotEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }

    @Test public void repeatedGateAndTickReusePurposeAndUsageData() throws Exception {
        g.showGate("app","video");WebHost host=g.overlay;Object web=host.view;long navigation=host.navigationGeneration,transition=g.gateTransition;
        g.store.getWritableDatabase().execSQL("DROP TABLE purposes");g.store.getWritableDatabase().execSQL("DROP TABLE usage");g.store.getWritableDatabase().execSQL("DROP TABLE rules");
        for(int i=0;i<5;i++){g.showGate("app","video");g.state();g.policyRule("video");}
        assertSame(web,host.view);assertEquals(navigation,host.navigationGeneration);assertEquals(transition,g.gateTransition);assertEquals(1,adds);
    }
    void rule(int minutes){android.content.ContentValues row=new android.content.ContentValues();row.put("pkg","video");row.put("label","Video");row.put("mode","limited");row.put("minutes",minutes);g.store.put("rules",row);}
    @Test public void stableBudgetInvalidatesOnPolicyAndBoundary() throws Exception {
        rule(60);g.showGate("app","video");assertEquals(3600000,g.state().getLong("daily"));
        rule(90);assertEquals(3600000,g.state().getLong("daily"));g.invalidateGateBudget();assertEquals(5400000,g.state().getLong("daily"));
        rule(30);org.robolectric.util.ReflectionHelpers.setField(g,"gateDailyUntil",System.currentTimeMillis()-1);assertEquals(1800000,g.state().getLong("daily"));
    }
    void ready(){WebHost h=g.overlay;h.ready=true;h.renderedNavigation=h.navigationGeneration;h.renderedScreen=h.pendingScreen;h.frameReady=true;g.revealOverlay();}
    @Test public void staleNavigationAndWrongForegroundCannotStopTheNewGate() throws Exception {
        GuardService.live=g;g.showGate("app","video");long old=g.overlay.navigationGeneration;
        g.foreground="social";g.showGate("app","social");WebHost h=g.overlay;
        h.handle("1","stop",new org.json.JSONObject().put("gateNavigation",old).toString());assertTrue(g.overlayVisible);assertEquals("social",g.gatePkg);
        ready();g.foreground="video";h.handle("2","stop",new org.json.JSONObject().put("gateNavigation",h.navigationGeneration).toString());assertTrue(g.overlayVisible);assertEquals("social",g.gatePkg);
    }
    @Test public void errorExitRemainsUsableWithoutAcceptingGateActions(){
        g.showGate("app","video");g.overlay.showGateError();assertTrue(g.acceptGateErrorInput());assertFalse(g.acceptGateInput());
        g.foreground="social";assertFalse(g.acceptGateErrorInput());
    }
    void actualWindow(String pkg){
        android.view.accessibility.AccessibilityNodeInfo node=android.view.accessibility.AccessibilityNodeInfo.obtain();node.setPackageName(pkg);
        android.view.accessibility.AccessibilityWindowInfo window=android.view.accessibility.AccessibilityWindowInfo.obtain();var shadow=Shadows.shadowOf(window);shadow.setRoot(node);shadow.setType(1);shadow.setFocused(true);shadow.setActive(true);shadow.setLayer(1);shadow.setBoundsInScreen(new android.graphics.Rect(0,0,390,780));Shadows.shadowOf(g).setWindows(java.util.List.of(window));
    }
    @Test public void bridgeGrantExtensionCancelAndSessionRestoreUseExistingService() throws Exception {
        LockyApp.app=new LockyApp();GuardService.live=g;Notices.channels(g);
        try {
            int minute=Rules.minute(System.currentTimeMillis(),java.time.ZoneId.systemDefault());g.store.settings.edit().putBoolean("enabled",true).putInt("start",(minute+60)%1440).putInt("end",(minute+59)%1440).commit();g.wasManaged=true;rule(60);
            g.phoneEvent=g.store.event("unlock","전화 목적","","",0);actualWindow("video");g.observeForeground(null);ready();
            String id=g.store.purposes("video").getJSONObject(0).getString("id");WebHost host=g.overlay;
            String grant=new org.json.JSONObject().put("gateNavigation",host.navigationGeneration).put("id",id).put("minutes",7).toString();host.handle("1","grant",grant);host.handle("2","grant",grant);
            assertEquals("video",g.sessionPkg);assertEquals(420000,g.sessionRemaining);assertFalse(g.overlayVisible);assertEquals(1,g.store.rows("SELECT * FROM events WHERE kind='app'").length());
            assertEquals(g.phoneEvent,g.store.rows("SELECT parent FROM events WHERE id=?",String.valueOf(g.sessionEvent)).getJSONObject(0).getLong("parent"));
            long event=g.sessionEvent;g.switchForeground("messages");actualWindow("video");g.observeForeground(null);assertEquals(event,g.sessionEvent);assertFalse(g.overlayVisible);assertTrue(g.sessionRemaining>419000);
            g.sessionRemaining=0;g.enforce();ready();String extend=new org.json.JSONObject().put("gateNavigation",host.navigationGeneration).put("minutes",5).toString();host.handle("3","extend",extend);host.handle("4","extend",extend);assertEquals(300000,g.sessionRemaining);assertEquals(1,g.store.rows("SELECT * FROM events WHERE kind='extend'").length());
            g.sessionRemaining=0;g.enforce();ready();host.handle("5","stop",new org.json.JSONObject().put("gateNavigation",host.navigationGeneration).toString());assertEquals("",g.sessionPkg);assertFalse(g.overlayVisible);
        }finally{LockyApp.app.work.shutdownNow();LockyApp.app=null;}
    }

    @Test public void chargedRecordWrittenWhileGateVisibleInvalidatesRemaining() throws Exception {
        rule(60);g.showGate("app","video");assertEquals(3600000,g.dailyRemaining("video"));
        long now=System.currentTimeMillis(),start=Rules.dayStart(now,java.time.ZoneId.systemDefault());
        // Use a time inside this day even when the test clock begins at midnight.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(10));now=System.currentTimeMillis();
        g.store.screenState(ScreenHistory.ON);g.store.usage(now-5000,now,"video","Video",true,0);
        assertEquals(3595000,g.dailyRemaining("video"));
    }

}
