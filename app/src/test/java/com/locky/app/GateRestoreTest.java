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
    @Before public void setup(){Store.instance=null;g=Robolectric.buildService(GuardService.class).create().get();g.store=Store.get(g);g.interactive=true;g.foreground="video";g.segmentWall=System.currentTimeMillis();g.segmentElapsed=SystemClock.elapsedRealtime();WindowManager actual=g.getSystemService(WindowManager.class);g.window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->{if(m.getName().equals("addView"))adds++;try{return m.invoke(actual,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}
    @After public void cleanup(){g.main.removeCallbacksAndMessages(null);if(g.overlayWindow!=null&&g.overlayWindow.getParent()!=null)g.window.removeViewImmediate(g.overlayWindow);g.main.removeCallbacksAndMessages(null);if(g.overlay!=null)g.overlay.close();g.store.close();Store.instance=null;GuardService.live=null;}
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
    @Test public void prewarmAttachesOnceAndWarmEntriesHideUntilCurrentDataArrives() throws Exception {
        g.prepareAppGate();assertNotNull(g.overlayWindow);assertEquals(1,adds);assertFalse(g.overlayVisible);
        g.prepareAppGate();assertEquals(1,adds);assertEquals(android.view.View.GONE,g.overlayWindow.getVisibility());
        assertNotEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        WebHost host=g.overlay;Object web=host.view;host.ready=true;host.everRendered=true;host.renderedScreen="gateIdle";
        g.showGate("app","video");assertEquals(android.view.View.GONE,host.loadingView.getVisibility());assertFalse(g.acceptGateInput());
        assertEquals(android.view.View.GONE,g.overlayWindow.getVisibility());
        assertNotEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        assertTrue(g.overlayWindow.dispatchTouchEvent(android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_DOWN,1,1,0)));
        long old=host.navigationGeneration;host.rendered(host.view,"app",old,2);host.frameReady=true;g.revealOverlay();assertTrue(g.acceptGateInput());
        g.foreground="social";g.showGate("app","social");assertFalse(g.acceptGateInput());assertFalse(host.currentGateDataApplied());assertEquals(android.view.View.GONE,host.loadingView.getVisibility());
        assertEquals(android.view.View.GONE,g.overlayWindow.getVisibility());assertNotEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        assertNotEquals(host.navigationGeneration,host.renderedNavigation);
        host.rendered(host.view,"app",old,3);assertFalse(host.currentGateDataApplied());assertSame(web,host.view);assertEquals(1,adds);assertEquals("social",g.gatePayload().getJSONObject("gate").getString("pkg"));
    }
    @Test public void coldEntryStaysHiddenUntilDataAndSystemSuspensionRemainsPassive(){
        g.showGate("app","video");assertFalse(g.overlay.ready);assertEquals(GuardService.Exposure.WAITING,g.exposure);
        assertEquals(android.view.View.GONE,g.overlayWindow.getVisibility());assertEquals(android.view.View.VISIBLE,g.overlay.view.getVisibility());
        assertEquals(android.view.View.GONE,g.overlay.loadingView.getVisibility());assertNotEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
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
        assertEquals(android.view.View.VISIBLE,g.overlayWindow.getVisibility());assertFalse(g.overlayWindow.getViewTreeObserver().dispatchOnPreDraw());
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

    @Test public void transientPurposeNormalizesWithoutAddingPresetAndKeepsScopeValidation() throws Exception {
        GuardService.live=g;LockyApp.app=new LockyApp();Notices.channels(g);
        try{
            int minute=Rules.minute(System.currentTimeMillis(),java.time.ZoneId.systemDefault());g.store.settings.edit().putBoolean("enabled",true).putInt("start",(minute+60)%1440).putInt("end",(minute+59)%1440).commit();g.wasManaged=true;rule(60);
            actualWindow("video");g.observeForeground(null);ready();WebHost host=g.overlay;
            int count=g.store.rows("SELECT * FROM purposes").length();
            org.json.JSONObject input=new org.json.JSONObject().put("transient",true).put("label","  이번 영상만  ").put("scope","video").put("aimless",true).put("minutes",8).put("gateNavigation",host.navigationGeneration);
            assertEquals("이번 영상만",host.resolvePurpose(input).getString("label"));assertEquals(1,host.resolvePurpose(input).getInt("aimless"));
            for(String label:new String[]{"   ","가".repeat(101)}){
                try{host.resolvePurpose(new org.json.JSONObject(input.toString()).put("label",label));fail("invalid purpose accepted");}catch(org.json.JSONException expected){}
            }
            host.handle("wrong","grant",new org.json.JSONObject(input.toString()).put("scope","phone").toString());assertEquals("",g.sessionPkg);
            host.handle("stale","grant",new org.json.JSONObject(input.toString()).put("gateNavigation",-1).toString());assertEquals("",g.sessionPkg);
            host.handle("ok","grant",input.toString());assertEquals("video",g.sessionPkg);assertEquals("이번 영상만",g.purpose);assertEquals(480000,g.sessionRemaining);
            assertEquals(count,g.store.rows("SELECT * FROM purposes").length());assertEquals("이번 영상만",g.store.rows("SELECT * FROM events WHERE kind='app'").getJSONObject(0).getString("purpose"));
            assertEquals(1,g.store.rows("SELECT * FROM events WHERE kind='app'").getJSONObject(0).getInt("aimless"));
        }finally{LockyApp.app.work.shutdownNow();LockyApp.app=null;}
    }

    @Test public void dataExposesDrawableWindowButOnlyValidatedFrameCanAcceptInput(){
        g.showGate("app","video");WebHost h=g.overlay;h.ready=true;
        assertFalse(g.overlayWindow.getViewTreeObserver().dispatchOnPreDraw());assertEquals(android.view.View.GONE,g.overlayWindow.getVisibility());
        h.rendered(h.view,"app",h.navigationGeneration,1);
        assertEquals(android.view.View.VISIBLE,g.overlayWindow.getVisibility());assertFalse(g.overlayWindow.getViewTreeObserver().dispatchOnPreDraw());assertFalse(g.acceptGateInput());
        assertEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        // Data can arrive before the first attach traversal. Check the WindowManager
        // attributes too, so cached flags cannot leave touches passing underneath.
        WindowManager.LayoutParams actual=org.robolectric.util.ReflectionHelpers.getField(g.overlayWindow.getParent(),"mWindowAttributes");
        assertEquals(0,actual.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        assertNotEquals(0,actual.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        h.frameReady=true;g.revealOverlay();assertFalse(g.overlayWindow.getViewTreeObserver().dispatchOnPreDraw());assertTrue(g.acceptGateInput());
        g.hideOverlay();assertEquals(android.view.View.GONE,g.overlayWindow.getVisibility());assertTrue(h.frameReady);assertTrue(h.renderingActive);
        g.showGate("app","video");assertFalse(g.acceptGateInput());assertEquals(1,adds);
        h.rendered(h.view,"app",h.navigationGeneration,1);assertTrue(g.acceptGateInput());
    }
    @Test public void failedAttachRetriesTheSameWindowAndHostOnce(){
        final int[] attempts={0};WindowManager actual=g.getSystemService(WindowManager.class);
        g.window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->{
            if(m.getName().equals("addView")&&attempts[0]++==0)throw new IllegalArgumentException("test");
            try{return m.invoke(actual,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        });
        g.prepareAppGate();Object host=g.overlay,window=g.overlayWindow;assertFalse(g.overlayAttached);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1200));
        g.prepareAppGate();assertTrue(g.overlayAttached);assertEquals(2,attempts[0]);assertSame(host,g.overlay);assertSame(window,g.overlayWindow);
    }
    @Test public void actualDetachRecoversSameHostWithoutDuplicateRegistration(){
        g.showGate("app","video");Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Object host=g.overlay,web=g.overlay.view,window=g.overlayWindow;
        assertTrue(g.overlayAttached);g.window.removeViewImmediate(g.overlayWindow);assertFalse(g.overlayAttached);assertNull(g.overlayWindow.getParent());
        g.revealOverlay();Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1200));assertTrue(g.overlayAttached);assertEquals(2,adds);assertSame(window,g.overlayWindow);assertSame(host,g.overlay);assertSame(web,g.overlay.view);
        g.prepareAppGate();g.overlayWindow.setVisibility(android.view.View.GONE);g.prepareAppGate();assertEquals(2,adds);assertTrue(g.overlayAttached);
    }
    @Test public void staleAttachedFlagCannotSkipRealWindowRegistration(){
        g.prepareAppGate();g.window.removeViewImmediate(g.overlayWindow);g.overlayAttached=true;
        g.prepareAppGate();Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1200));assertTrue(g.overlayWindow.isAttachedToWindow());assertTrue(g.overlayAttached);assertEquals(2,adds);
    }
    @Test public void unclearedParentNeverCausesDuplicateAddAndFailureCanRetryOnNewEntry(){
        g.prepareAppGate();g.window.removeViewImmediate(g.overlayWindow);
        android.widget.FrameLayout stale=new android.widget.FrameLayout(g);stale.addView(g.overlayWindow);
        g.showGate("app","video");Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3700));
        assertEquals(1,adds);assertFalse(g.overlayAttached);assertTrue(g.overlayAttachFailed);assertTrue(g.overlay.gateErrorVisible());
        stale.removeView(g.overlayWindow);g.foreground="social";g.showGate("app","social");Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(g.overlayAttached);assertEquals(2,adds);assertFalse(g.overlayAttachFailed);assertFalse(g.overlay.gateErrorVisible());
    }
    @Test public void appGateDiagnosticsAreBoundedAndIndependentOfScreenOff() throws Exception {
        g.prepareAppGate();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        UnlockDiagnostics d=UnlockDiagnostics.get(g);d.clear();d.setEnabled(true);
        for(int i=0;i<12;i++){g.hideOverlay();g.showGate("app","video");}
        assertEquals(8,d.status().getInt("appGates"));assertEquals(0,d.status().getInt("cycles"));
        assertTrue(d.text().contains("showGate hostReuse=true windowReuse=true"));
        assertTrue(d.text().contains("data apply requested"));assertFalse(d.text().contains("video"));
        d.gateRecord(g.gateTransition-1,"wrong-token-event");assertFalse(d.text().contains("wrong-token-event"));
        d.flush();UnlockDiagnostics restored=new UnlockDiagnostics(g.getApplicationContext());assertEquals(8,restored.status().getInt("appGates"));
        d.setEnabled(false);d.clear();
    }

    @Test public void touchStartedOnPreviousGateCannotFinishOnNewGate(){
        g.showGate("app","video");ready();final int[] clicks={0};
        // Synchronous touch target exercises dispatch/cancel independently of
        // Button's posted performClick.
        android.view.View target=new android.view.View(g){boolean started;
            @Override public boolean onTouchEvent(android.view.MotionEvent event){
                if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)started=true;
                if(event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL)started=false;
                if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP){if(started)clicks[0]++;started=false;}
                return true;
            }
        };
        g.overlayWindow.addView(target,new android.widget.FrameLayout.LayoutParams(100,100));
        g.overlayWindow.measure(android.view.View.MeasureSpec.makeMeasureSpec(390,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(780,1073741824));g.overlayWindow.layout(0,0,390,780);
        motion(android.view.MotionEvent.ACTION_DOWN);
        g.foreground="social";g.showGate("app","social");ready();motion(android.view.MotionEvent.ACTION_UP);assertEquals(0,clicks[0]);
        motion(android.view.MotionEvent.ACTION_DOWN);motion(android.view.MotionEvent.ACTION_UP);assertEquals(1,clicks[0]);
    }
    void motion(int action){android.view.MotionEvent event=android.view.MotionEvent.obtain(0,1,action,20,20,0);g.overlayWindow.dispatchTouchEvent(event);event.recycle();}

}
