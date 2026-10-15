package com.locky.app;

import android.app.Application;
import android.app.KeyguardManager;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Proxy;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,shadows=GateRestoreTest.LockActions.class)
public class GateRestoreTest {
    @org.robolectric.annotation.Implements(android.accessibilityservice.AccessibilityService.class)
    public static class LockActions extends org.robolectric.shadows.ShadowAccessibilityService {
        static boolean accepted=true;static java.util.List<Integer> actions=new java.util.ArrayList<>();
        @org.robolectric.annotation.Implementation protected boolean performGlobalAction(int action){actions.add(action);return accepted;}
    }
    GuardService g;int adds,removes;KeyguardManager keyguard;
    @Before public void setup(){
        LockActions.accepted=true;LockActions.actions.clear();Store.instance=null;g=Robolectric.buildService(GuardService.class).create().get();g.store=Store.get(g);GuardService.live=g;
        g.segmentWall=System.currentTimeMillis();g.segmentElapsed=android.os.SystemClock.elapsedRealtime();
        keyguard=g.getSystemService(KeyguardManager.class);
        g.window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->{if(m.getName().equals("addView"))adds++;if(m.getName().startsWith("removeView"))removes++;return null;});
    }
    @After public void cleanup(){GuardService.live=null;g.main.removeCallbacksAndMessages(null);if(g.overlay!=null)g.overlay.close();g.store.close();Store.instance=null;}
    @Test public void lockedPreparationIsTransparentPassiveAndUsesTheSameWindowOnUnlock(){
        g.interactive=false;g.prepareUnlock();WebHost host=g.overlay;Object web=host.view;long navigation=host.navigationGeneration;
        assertEquals(0,g.overlayLayout.alpha,0);assertFalse(host.presentationActive);assertEquals(View.VISIBLE,host.root.getVisibility());
        int passive=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        assertEquals(passive,g.overlayLayout.flags&passive);assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,g.overlayWindow.getImportantForAccessibility());
        g.interactive=true;Shadows.shadowOf(keyguard).setKeyguardLocked(true);g.revealOverlay();assertEquals(0,g.overlayLayout.alpha,0);assertFalse(host.presentationActive);
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);host.frameReady=true;g.revealOverlay();
        assertSame(host,g.overlay);assertSame(web,g.overlay.view);assertEquals(1,adds);assertEquals(0,removes);assertEquals(navigation,host.navigationGeneration);
        assertEquals(1,g.overlayLayout.alpha,0);assertEquals(0,g.overlayLayout.flags&passive);assertTrue(host.presentationActive);
    }
    @Test public void alreadyVisibleAndPreviouslyDismissedStartsStillResumeWithoutNavigating(){
        g.interactive=true;g.showGate("unlock","");long navigation=g.overlay.navigationGeneration;
        g.overlay.pause();assertEquals(View.VISIBLE,g.overlay.root.getVisibility());g.overlay.frameReady=true;g.showGate("unlock","");assertTrue(g.overlay.presentationActive);assertEquals(navigation,g.overlay.navigationGeneration);
        g.hideOverlay();g.interactive=false;g.prepareUnlock();assertEquals(navigation,g.overlay.navigationGeneration);assertEquals(1,adds);assertEquals(0,removes);
    }
    @Test public void readyUnlockDoesNotRenavigateOrDiscardPreparedFrameOnDuplicateSignals(){
        g.interactive=false;g.waitingUnlock=true;g.prepareUnlock();WebHost host=g.overlay;host.ready=true;host.frameReady=true;
        long nav=host.navigationGeneration,visual=host.visualGeneration;
        g.screen.onReceive(g,new android.content.Intent(android.content.Intent.ACTION_USER_PRESENT));
        g.screen.onReceive(g,new android.content.Intent(android.content.Intent.ACTION_USER_PRESENT));g.checkUnlock.run();
        assertSame(host,g.overlay);assertEquals(nav,host.navigationGeneration);assertEquals(visual,host.visualGeneration);
        assertTrue(host.frameReady);assertEquals(1f,g.overlayLayout.alpha,0);g.pauseSystem();assertEquals(1f,g.overlayLayout.alpha,0);
        g.switchForeground(g.getPackageName());g.pauseSystem();assertEquals(0f,g.overlayLayout.alpha,0);
    }
    @Test public void startDismissRequestsOnlyLockAndCleansUpWhenScreenActuallyTurnsOff() throws Exception {
        g.interactive=true;g.showGate("unlock","");g.overlay.frameReady=true;g.revealOverlay();Object web=g.overlay.view;
        g.dismissStart();g.dismissStart();
        assertEquals(java.util.List.of(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN),LockActions.actions);
        assertTrue(g.lockPending);assertTrue(g.overlayVisible);assertEquals(1f,g.overlayLayout.alpha,0);
        g.pauseSystem();assertEquals(1f,g.overlayLayout.alpha,0);
        g.screen.onReceive(g,new android.content.Intent(android.content.Intent.ACTION_SCREEN_OFF));
        assertFalse(g.lockPending);assertTrue(g.waitingUnlock);assertEquals(0f,g.overlayLayout.alpha,0);assertSame(web,g.overlay.view);
        assertEquals(1,g.store.rows("SELECT id FROM events WHERE kind='dismiss'").length());
    }
    @Test public void failedLockKeepsTheVisibleStartAndAllowsRetry(){
        g.interactive=true;g.showGate("unlock","");g.overlay.frameReady=true;g.revealOverlay();LockActions.accepted=false;
        try{g.dismissStart();fail("Expected lock failure");}catch(IllegalStateException expected){}
        assertFalse(g.lockPending);assertTrue(g.overlayVisible);assertEquals(1f,g.overlayLayout.alpha,0);
        LockActions.accepted=true;g.dismissStart();assertTrue(g.lockPending);g.lockCompletion.run();assertFalse(g.lockPending);
    }
    @Test public void appDismissStillExitsToHomeWithoutLockingPhone(){
        g.interactive=true;g.showGate("app","example.app");g.dismissStart();
        assertEquals(java.util.List.of(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME),LockActions.actions);
        assertFalse(g.overlayVisible);assertFalse(g.lockPending);
    }
}
