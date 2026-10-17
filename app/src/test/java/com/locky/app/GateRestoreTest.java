package com.locky.app;

import android.app.Application;
import android.content.Intent;
import android.view.View;
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
    @After public void cleanup(){g.main.removeCallbacksAndMessages(null);if(g.overlay!=null)g.overlay.close();g.store.close();Store.instance=null;GuardService.live=null;if(LockyApp.app!=null){LockyApp.app.work.shutdownNow();LockyApp.app=null;}}
    @Test public void preconnectedWindowIsInvisibleAndPassiveUntilDataIsBound(){
        g.prepareAppGate();assertEquals(1,adds);assertFalse(g.overlayVisible);assertEquals(View.GONE,g.overlayWindow.getVisibility());
        assertNotEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);assertNotEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,g.overlayWindow.getImportantForAccessibility());
        Object retained=g.overlay;g.showGate("app","video");
        assertSame(retained,g.overlay);assertEquals("앱 시작",g.overlay.heading.getText());assertTrue(g.acceptGateInput());
        assertEquals(GuardService.Exposure.INTERACTIVE,g.exposure);assertEquals(View.VISIBLE,g.overlayWindow.getVisibility());assertEquals(1,adds);
        assertEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,g.overlayLayout.type);
    }
    @Test public void switchesReplaceDataAndInvalidateOldRequestsWithoutReplacingTheHierarchy(){
        g.showGate("app","video");Object retained=g.overlay;Object first=g.overlay.presetButtons.get(0);long token=g.gateTransition;
        g.showGate("app","video");assertEquals(token,g.gateTransition);
        g.foreground="social";g.showGate("app","social");assertSame(retained,g.overlay);assertSame(first,g.overlay.presetButtons.get(0));assertEquals(1,adds);assertTrue(token<g.gateTransition);
        final boolean[] ran={false};g.gateAction(token,"video",()->ran[0]=true);assertFalse(ran[0]);
        g.showGate("blocked","social");assertEquals("사용 제한",g.overlay.heading.getText());assertEquals(View.GONE,g.overlay.purposes.getVisibility());
    }
    @Test public void shadeOverviewAndScreenOffReleaseAllInputWithoutResurrectingOldGate(){
        g.showGate("app","video");long token=g.gateTransition;g.pauseSystem();
        assertEquals(GuardService.Exposure.SUSPENDED,g.exposure);assertEquals(View.GONE,g.overlayWindow.getVisibility());assertFalse(g.acceptGateInput());
        assertNotEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        g.systemPaused=false;g.revealOverlay();assertTrue(g.acceptGateInput());
        g.screen.onReceive(g,new Intent(Intent.ACTION_SCREEN_OFF));assertFalse(g.overlayVisible);assertEquals(View.GONE,g.overlayWindow.getVisibility());assertTrue(g.gateTransition>token);
        g.interactive=true;g.revealOverlay();assertEquals(View.GONE,g.overlayWindow.getVisibility());assertEquals(1,adds);
    }
    @Test public void unconfirmedOrChangedForegroundRejectsAction(){
        g.showGate("app","video");final boolean[] ran={false};
        g.foreground="social";g.gateAction(g.gateTransition,"video",()->ran[0]=true);assertFalse(ran[0]);
        g.foreground="video";g.gateAction(g.gateTransition,"video",()->ran[0]=true);assertFalse(ran[0]);assertTrue(g.systemPaused);
    }
    @Test public void phoneScreenOffCannotBeSentThroughTheAppWindow(){
        g.screenOff();assertNull(g.overlay);assertEquals(0,adds);assertFalse(g.overlayVisible);
        try{g.showGate("unlock","");fail();}catch(IllegalArgumentException expected){}
    }
    void actualWindow(String pkg){
        android.view.accessibility.AccessibilityNodeInfo node=android.view.accessibility.AccessibilityNodeInfo.obtain();node.setPackageName(pkg);
        android.view.accessibility.AccessibilityWindowInfo window=android.view.accessibility.AccessibilityWindowInfo.obtain();var shadow=Shadows.shadowOf(window);shadow.setRoot(node);shadow.setType(1);shadow.setFocused(true);shadow.setActive(true);shadow.setLayer(1);shadow.setBoundsInScreen(new android.graphics.Rect(0,0,390,780));Shadows.shadowOf(g).setWindows(java.util.List.of(window));
    }
    @Test public void nativeButtonsReachOriginalGrantExtensionCancelAndSessionRestore() throws Exception {
        LockyApp.app=new LockyApp();GuardService.live=g;Notices.channels(g);
        int minute=Rules.minute(System.currentTimeMillis(),java.time.ZoneId.systemDefault());g.store.settings.edit().putBoolean("enabled",true).putInt("start",(minute+60)%1440).putInt("end",(minute+59)%1440).commit();g.wasManaged=true;
        android.content.ContentValues rule=new android.content.ContentValues();rule.put("pkg","video");rule.put("label","Video");rule.put("mode","limited");rule.put("minutes",60);g.store.put("rules",rule);
        g.phoneEvent=g.store.event("unlock","전화 목적","","",0);actualWindow("video");g.observeForeground(null);assertEquals("app",g.gate);
        g.overlay.minuteButtons.get(2).performClick();g.overlay.presetButtons.get(0).performClick();g.overlay.presetButtons.get(0).performClick();
        assertEquals("video",g.sessionPkg);assertEquals(420000,g.sessionRemaining);assertFalse(g.overlayVisible);assertEquals(1,g.store.rows("SELECT * FROM events WHERE kind='app'").length());
        assertEquals(g.phoneEvent,g.store.rows("SELECT parent FROM events WHERE id=?",String.valueOf(g.sessionEvent)).getJSONObject(0).getLong("parent"));
        long event=g.sessionEvent;g.switchForeground("messages");actualWindow("video");g.observeForeground(null);assertEquals(event,g.sessionEvent);assertFalse(g.overlayVisible);assertTrue(g.sessionRemaining>419000);
        g.sessionRemaining=0;g.enforce();assertEquals("renew",g.gate);g.overlay.extend.performClick();g.overlay.extend.performClick();assertEquals(300000,g.sessionRemaining);assertEquals(1,g.store.rows("SELECT * FROM events WHERE kind='extend'").length());
        g.sessionRemaining=0;g.enforce();g.overlay.stop.performClick();assertEquals("",g.sessionPkg);assertFalse(g.overlayVisible);assertTrue(Shadows.shadowOf(g).getGlobalActionsPerformed().contains(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
    }
    @Test public void directNativePurposeIsSavedWithItsAppScopeBeforeGrant() throws Exception {
        LockyApp.app=new LockyApp();GuardService.live=g;Notices.channels(g);int minute=Rules.minute(System.currentTimeMillis(),java.time.ZoneId.systemDefault());g.store.settings.edit().putBoolean("enabled",true).putInt("start",(minute+60)%1440).putInt("end",(minute+59)%1440).commit();g.wasManaged=true;
        android.content.ContentValues rule=new android.content.ContentValues();rule.put("pkg","video");rule.put("label","Video");rule.put("mode","limited");rule.put("minutes",60);g.store.put("rules",rule);actualWindow("video");g.observeForeground(null);
        g.overlay.direct.performClick();g.overlay.input.setText("네이티브에서 정한 목적");g.overlay.save.performClick();assertEquals("네이티브에서 정한 목적",g.purpose);assertEquals(1,g.store.rows("SELECT * FROM purposes WHERE scope='video' AND label='네이티브에서 정한 목적'").length());assertFalse(g.overlayVisible);
    }
}
