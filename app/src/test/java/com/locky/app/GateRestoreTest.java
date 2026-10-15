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
        static int snapshots;static boolean accepted=true;static java.util.List<Integer> actions=new java.util.ArrayList<>();
        @org.robolectric.annotation.Implementation protected boolean performGlobalAction(int action){actions.add(action);return accepted;}
        @org.robolectric.annotation.Implementation protected java.util.List<android.view.accessibility.AccessibilityWindowInfo> getWindows(){snapshots++;return super.getWindows();}
    }
    GuardService g;int adds,removes,updates;KeyguardManager keyguard;
    @Before public void setup(){
        LockActions.accepted=true;LockActions.snapshots=0;LockActions.actions.clear();Store.instance=null;g=Robolectric.buildService(GuardService.class).create().get();g.store=Store.get(g);GuardService.live=g;
        g.segmentWall=System.currentTimeMillis();g.segmentElapsed=android.os.SystemClock.elapsedRealtime();
        keyguard=g.getSystemService(KeyguardManager.class);
        g.systemPackages.add("com.android.systemui");g.systemPackages.add("com.android.permissioncontroller");
        g.window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->{if(m.getName().equals("addView"))adds++;if(m.getName().startsWith("removeView"))removes++;if(m.getName().equals("updateViewLayout"))updates++;return null;});
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
        focusSurface();g.observeForeground(null);assertFalse(g.unlockTransition);g.pauseSystem();assertEquals(0f,g.overlayLayout.alpha,0);
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
    static final class FocusSurface extends android.widget.FrameLayout {
        FocusSurface(android.content.Context c){super(c);}
        @Override public boolean hasWindowFocus(){return true;}
    }
    void focusSurface(){
        g.overlayWindow.removeView(g.overlay.root);g.overlayWindow=new FocusSurface(g);
        g.overlayWindow.addView(g.overlay.root);
    }
    android.view.accessibility.AccessibilityWindowInfo appWindow(String pkg,boolean focused){
        var w=android.view.accessibility.AccessibilityWindowInfo.obtain();var shadow=Shadows.shadowOf(w);
        shadow.setType(android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION);shadow.setId(11);shadow.setLayer(1);shadow.setFocused(focused);
        var root=android.view.accessibility.AccessibilityNodeInfo.obtain();root.setPackageName(pkg);shadow.setRoot(root);return w;
    }
    android.view.accessibility.AccessibilityWindowInfo systemWindow(){
        var w=android.view.accessibility.AccessibilityWindowInfo.obtain();var shadow=Shadows.shadowOf(w);
        shadow.setType(android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM);shadow.setId(51);shadow.setLayer(3);shadow.setFocused(true);shadow.setActive(true);return w;
    }
    void windows(android.view.accessibility.AccessibilityWindowInfo... values){Shadows.shadowOf((android.accessibilityservice.AccessibilityService)g).setWindows(java.util.Arrays.asList(values));}
    void unlockedStart(){
        windows();g.interactive=false;g.waitingUnlock=true;g.unlockPrepared=false;g.prepareUnlock();
        g.overlay.ready=true;g.overlay.frameReady=true;g.overlay.renderedScreen="unlock";
        if(g.overlay.preparingStart)g.overlay.finishStartPreparation(g.overlay.view,g.overlay.navigationGeneration,g.overlay.startPreparation,"1");
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);
        g.screen.onReceive(g,new android.content.Intent(android.content.Intent.ACTION_USER_PRESENT));
        assertTrue(g.unlockTransition);assertEquals(1f,g.overlayLayout.alpha,0);
    }
    @Test public void backgroundRootAndOutgoingShadeDoNotHideThenRevealThePreparedStart(){
        unlockedStart();int before=updates;
        windows(appWindow("example.background",false));g.observeForeground(null);assertTrue(g.unlockTransition);
        var event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.android.systemui");event.setClassName("NotificationShadeWindow");
        windows(appWindow("example.background",false),systemWindow());g.observeForeground(event);
        assertTrue(g.unlockTransition);assertFalse(g.systemPaused);assertEquals(1f,g.overlayLayout.alpha,0);assertEquals(before,updates);
        focusSurface();windows(appWindow("example.background",false));g.observeForeground(null);assertFalse(g.unlockTransition);
        windows(systemWindow());g.observeForeground(event);assertTrue(g.systemPaused);assertEquals(0f,g.overlayLayout.alpha,0);assertEquals(before+1,updates);
        g.observeForeground(event);assertEquals(before+1,updates);event.recycle();
    }
    @Test public void newlyOpenedSystemWindowWinsEvenBeforeTheFirstStableOverlayFocus(){
        unlockedStart();windows(systemWindow());
        var event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED);
        org.robolectric.util.ReflectionHelpers.setField(event,"mSourceWindowId",51);
        org.robolectric.util.ReflectionHelpers.setField(event,"mWindowChangeTypes",android.view.accessibility.AccessibilityEvent.WINDOWS_CHANGE_ADDED);
        g.observeForeground(event);assertFalse(g.unlockTransition);assertTrue(g.systemPaused);assertEquals(0f,g.overlayLayout.alpha,0);event.recycle();
    }
    @Test public void permissionOverviewAndIncomingCallStillYieldDuringUnlock(){
        unlockedStart();windows(appWindow("com.android.permissioncontroller",true));g.observeForeground(null);
        assertFalse(g.unlockTransition);assertTrue(g.systemPaused);assertEquals(0f,g.overlayLayout.alpha,0);
        unlockedStart();windows(systemWindow());
        var event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.android.systemui");event.setClassName("RecentsActivity");g.observeForeground(event);
        assertFalse(g.unlockTransition);assertTrue(g.overview);assertEquals(0f,g.overlayLayout.alpha,0);event.recycle();
        g.overview=false;unlockedStart();windows(appWindow("com.samsung.android.incallui",true));g.observeForeground(null);
        assertFalse(g.overlayVisible);assertFalse(g.unlockTransition);
    }
    @Test public void lockedAndOutOfOrderEventsCannotExposePrivateContentOrReprepareTheCycle(){
        g.interactive=true;g.screenOff();WebHost original=g.overlay;long nav=original.navigationGeneration;
        g.overlay.ready=true;g.overlay.frameReady=true;g.overlay.renderedScreen="unlock";
        int hidden=updates;g.screenOff();g.prepareUnlock();assertEquals(nav,original.navigationGeneration);assertEquals(hidden,updates);
        Shadows.shadowOf(keyguard).setKeyguardLocked(true);
        g.screen.onReceive(g,new android.content.Intent(android.content.Intent.ACTION_SCREEN_ON));
        g.screen.onReceive(g,new android.content.Intent(android.content.Intent.ACTION_USER_PRESENT));
        assertTrue(g.waitingUnlock);assertEquals(0f,g.overlayLayout.alpha,0);assertFalse(original.presentationActive);
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);g.checkUnlock.run();
        g.screen.onReceive(g,new android.content.Intent(android.content.Intent.ACTION_USER_PRESENT));
        g.screen.onReceive(g,new android.content.Intent(android.content.Intent.ACTION_SCREEN_ON));g.revealOverlay();
        assertSame(original,g.overlay);assertEquals(nav,original.navigationGeneration);assertEquals(hidden+1,updates);
    }
    @Test public void accessibilityUnlockUsesOneWindowSnapshotAndDoesNotExposeAnUnpreparedFrame(){
        g.interactive=false;g.waitingUnlock=true;g.prepareUnlock();g.overlay.ready=true;
        var event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED);
        g.onAccessibilityEvent(event);assertEquals(1,LockActions.snapshots);assertFalse(g.waitingUnlock);assertEquals(0f,g.overlayLayout.alpha,0);
        int hidden=updates;g.overlay.frameReady=true;g.revealOverlay();assertEquals(hidden+1,updates);
        g.onAccessibilityEvent(event);g.revealOverlay();assertEquals(hidden+1,updates);event.recycle();
    }

    @Test public void fastUnlockCannotExposeTheOldRevisionWhilePresetReorderIsPending(){
        unlockedStart();WebHost host=g.overlay;host.everRendered=true;host.renderedRevision=1;
        g.screenOff();assertTrue(host.preparingStart);assertTrue(host.frameReady);int hidden=updates;
        g.interactive=true;g.completeUnlock();g.revealOverlay();assertEquals(0f,g.overlayLayout.alpha,0);assertEquals(hidden,updates);
        host.finishStartPreparation(host.view,host.navigationGeneration,host.startPreparation,"1");
        assertEquals(1f,g.overlayLayout.alpha,0);assertEquals(hidden+1,updates);
        g.completeUnlock();g.revealOverlay();assertEquals(hidden+1,updates);
    }
}
