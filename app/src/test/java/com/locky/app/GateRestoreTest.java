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
    GuardService g;int adds,removes,updates;java.util.List<Float> alphas=new java.util.ArrayList<>();KeyguardManager keyguard;
    @Before public void setup(){
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);
        LockActions.accepted=true;LockActions.snapshots=0;LockActions.actions.clear();Store.instance=null;g=Robolectric.buildService(GuardService.class).create().get();g.store=Store.get(g);GuardService.live=g;
        g.segmentWall=System.currentTimeMillis();g.segmentElapsed=android.os.SystemClock.elapsedRealtime();
        keyguard=g.getSystemService(KeyguardManager.class);
        g.systemPackages.add("com.android.systemui");g.systemPackages.add("com.android.permissioncontroller");
        g.window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->{if(m.getName().equals("addView"))adds++;if(m.getName().startsWith("removeView"))removes++;if(m.getName().equals("updateViewLayout")){updates++;alphas.add(((WindowManager.LayoutParams)a[1]).alpha);}return null;});
        g.applicationWindow=g.window;
        g.applicationWindowContext=g.getApplicationContext().createDisplayContext(g.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(android.view.Display.DEFAULT_DISPLAY));
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
    @Test public void newSystemUiIdDuringUnlockDoesNotHideTheStartButAnExplicitPanelStillInterrupts(){
        unlockedStart();windows(systemWindow());
        var event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED);
        org.robolectric.util.ReflectionHelpers.setField(event,"mSourceWindowId",51);
        org.robolectric.util.ReflectionHelpers.setField(event,"mWindowChangeTypes",android.view.accessibility.AccessibilityEvent.WINDOWS_CHANGE_ADDED);
        g.observeForeground(event);assertTrue(g.unlockTransition);assertFalse(g.systemPaused);assertEquals(1f,g.overlayLayout.alpha,0);
        event.setClassName("com.android.systemui.qs.QSContainerImpl");event.setEventType(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
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

    @Test public void fastUnlockKeepsValidatedPixelsWhilePresetReorderIsPending(){
        unlockedStart();WebHost host=g.overlay;host.everRendered=true;host.renderedRevision=1;
        g.screenOff();assertTrue(host.preparingStart);assertTrue(host.frameReady);int hidden=updates;
        g.interactive=true;g.completeUnlock();g.revealOverlay();assertEquals(1f,g.overlayLayout.alpha,0);assertEquals(hidden+1,updates);
        host.finishStartPreparation(host.view,host.navigationGeneration,host.startPreparation,"1");
        assertEquals(1f,g.overlayLayout.alpha,0);assertEquals(hidden+1,updates);
        g.completeUnlock();g.revealOverlay();assertEquals(hidden+1,updates);
    }
    @Test public void applicationStartIsOpaqueBehindKeyguardButPassiveAndSecure(){
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);
        g.interactive=false;g.waitingUnlock=true;g.prepareUnlock();g.overlay.frameReady=true;g.revealOverlay();
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,g.overlayLayout.type);assertEquals(1f,g.overlayLayout.alpha,0);
        assertNull(g.overlayLayout.token);
        int passive=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        assertEquals(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,g.overlayLayout.flags&passive);assertFalse(g.overlay.presentationActive);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,g.overlayWindow.getImportantForAccessibility());
        assertTrue((g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
        assertEquals(0,g.overlayLayout.flags&(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD));
        int before=updates;g.interactive=true;Shadows.shadowOf(keyguard).setKeyguardLocked(false);g.completeUnlock();
        assertEquals(1f,g.overlayLayout.alpha,0);assertEquals(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,g.overlayLayout.flags&passive);assertEquals(before,updates);
        g.revealOverlay();assertEquals(before,updates);assertEquals(1,adds);assertEquals(0,removes);
    }
    @Test public void permissionAndGateRoleMigrateOnlyTheWindowAndRetainTheWebView(){
        g.interactive=false;g.prepareUnlock();WebHost host=g.overlay;Object web=host.view;
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,g.overlayLayout.type);
        g.hideOverlay();org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);g.reconcileWindowPermission();
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,g.overlayLayout.type);assertSame(host,g.overlay);assertSame(web,g.overlay.view);
        g.showGate("app","example.app");assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,g.overlayLayout.type);assertSame(web,g.overlay.view);
        g.hideOverlay();g.gate="unlock";g.reconcileWindowPermission();
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);g.reconcileWindowPermission();
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,g.overlayLayout.type);assertSame(web,g.overlay.view);
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);g.store.settings.edit().putBoolean("start-under-keyguard",false).commit();g.reconcileWindowPermission();
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,g.overlayLayout.type);
    }
    @Test public void rejectedApplicationWindowFallsBackWithoutRepeatedAttempts(){
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);final int[] attempts={0};
        g.window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->{
            if(m.getName().equals("addView")&&((WindowManager.LayoutParams)a[1]).type==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY){attempts[0]++;throw new SecurityException("permission changed");}return null;});
        g.applicationWindow=g.window;g.interactive=false;g.prepareUnlock();Object web=g.overlay.view;g.showGate("unlock","");
        assertEquals(1,attempts[0]);assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,g.overlayLayout.type);assertSame(web,g.overlay.view);
    }
    @Test public void ownApplicationOverlayIsNotMistakenForTheNotificationShade(){
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);unlockedStart();focusSurface();
        var own=systemWindow();var root=android.view.accessibility.AccessibilityNodeInfo.obtain();root.setPackageName(g.getPackageName());Shadows.shadowOf(own).setRoot(root);
        windows(own);g.observeForeground(null);assertFalse(g.systemPaused);assertEquals(1f,g.overlayLayout.alpha,0);
        windows(systemWindow());g.observeForeground(null);assertTrue(g.systemPaused);assertEquals(0f,g.overlayLayout.alpha,0);
    }
    @Test public void validatedApplicationWindowNeverDimsForReorderOrDomCallbackDuringUnlock(){
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);unlockedStart();
        WebHost host=g.overlay;host.everRendered=true;host.retainedFrame=true;host.renderedRevision=1;
        g.screenOff();assertTrue(host.preparingStart);assertEquals(1f,g.overlayLayout.alpha,0);
        int added=adds,removed=removes;alphas.clear();
        g.interactive=true;g.completeUnlock();assertEquals(1f,g.overlayLayout.alpha,0);
        host.finishStartPreparation(host.view,host.navigationGeneration,host.startPreparation,"2");
        assertFalse(host.frameReady);assertTrue(host.hasUsableFrame());g.revealOverlay();
        assertEquals(1f,g.overlayLayout.alpha,0);assertEquals(added,adds);assertEquals(removed,removes);
        assertTrue(alphas.isEmpty()); // Neither alpha nor window input flags change on normal unlock.
        host.frameReady=true;g.revealOverlay();g.revealOverlay();assertEquals(0,alphas.size());
    }
    @Test public void delayedAddedEventForTheObservedKeyguardCannotPauseUnlockButANewShadeCan(){
        windows(systemWindow());g.interactive=false;Shadows.shadowOf(keyguard).setKeyguardLocked(true);g.screenOff();
        assertTrue(g.keyguardWindows.contains(51));
        g.overlay.frameReady=true;g.interactive=true;Shadows.shadowOf(keyguard).setKeyguardLocked(false);g.completeUnlock();
        var event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED);
        org.robolectric.util.ReflectionHelpers.setField(event,"mSourceWindowId",51);
        org.robolectric.util.ReflectionHelpers.setField(event,"mWindowChangeTypes",android.view.accessibility.AccessibilityEvent.WINDOWS_CHANGE_ADDED);
        g.observeForeground(event);assertTrue(g.unlockTransition);assertFalse(g.systemPaused);assertEquals(1f,g.overlayLayout.alpha,0);
        focusSurface();windows();g.observeForeground(null);assertFalse(g.unlockTransition);
        windows(systemWindow());g.observeForeground(event);assertTrue(g.systemPaused);assertEquals(0f,g.overlayLayout.alpha,0);event.recycle();
    }
    @Test public void grantedPermissionDoesNotReconnectAnInvisiblePreparingWindowOnScreenOn(){
        g.interactive=false;g.waitingUnlock=true;g.prepareUnlock();int added=adds,removed=removes;
        g.interactive=true;org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);
        g.reconcileWindowPermission();g.showGate("unlock","");g.overlay.frameReady=true;g.completeUnlock();
        assertEquals(added,adds);assertEquals(removed,removes);assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,g.overlayLayout.type);
        g.screenOff();assertEquals(added+1,adds);assertEquals(removed+1,removes);assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,g.overlayLayout.type);
    }
    @Test public void revocationSuspendsWithoutReconnectUntilAnActualSystemScreenCoversUs(){
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);unlockedStart();int added=adds,removed=removes;
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);g.reconcileWindowPermission();
        assertEquals(added,adds);assertEquals(removed,removes);assertEquals(0f,g.overlayLayout.alpha,0);
        windows(appWindow("com.android.permissioncontroller",true));g.observeForeground(null);
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,g.overlayLayout.type);assertEquals(added+1,adds);assertEquals(removed+1,removes);
        assertTrue(g.systemPaused);assertEquals(0f,g.overlayLayout.alpha,0);
        g.overlay.frameReady=true;g.switchForeground("example.app");assertEquals(1f,g.overlayLayout.alpha,0);
    }
    @Test public void revocationWithoutASettingsWindowRecoversAfterAStableAppSnapshot(){
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);unlockedStart();int added=adds;
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);g.reconcileWindowPermission();
        assertTrue(g.unlockTransition);assertEquals(added,adds);
        windows(appWindow("example.app",true));g.observeForeground(null);
        assertFalse(g.unlockTransition);assertFalse(g.systemPaused);assertEquals(added+1,adds);
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,g.overlayLayout.type);
    }
    @Test public void missingPlacementProofNeverMakesALockedWindowOpaque(){
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);
        g.interactive=false;g.waitingUnlock=true;g.prepareUnlock();g.applicationWindowContext=null;g.overlay.frameReady=true;g.revealOverlay();
        assertEquals(0f,g.overlayLayout.alpha,0);assertFalse(g.overlay.presentationActive);
    }
    @Test public void statusSeparatesRequestPermissionCreationFailureAndActualRole() throws Exception {
        assertEquals("permission-required",g.startWindowStatus().getString("status"));
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);assertEquals("pending",g.startWindowStatus().getString("status"));
        g.interactive=false;g.prepareUnlock();assertEquals("applied",g.startWindowStatus().getString("status"));
        g.applicationWindowFailed=true;assertEquals("failed-pending",g.startWindowStatus().getString("status"));
        g.store.settings.edit().putBoolean("start-under-keyguard",false).commit();g.reconcileWindowPermission();
        assertEquals("disabled",g.startWindowStatus().getString("status"));assertEquals("accessibility",g.startWindowStatus().getString("actual"));
    }
    @Test public void primaryApplicationWindowAcceptsTheFirstButtonTouchWithoutTakingKeyboardFocus(){
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);g.interactive=false;g.waitingUnlock=true;g.prepareUnlock();
        g.overlay.frameReady=true;g.revealOverlay();assertFalse(g.acceptGateInput());int before=updates;
        g.interactive=true;g.completeUnlock();assertTrue(g.acceptGateInput());assertEquals(before,updates);
        final int[] touches={0};android.view.View target=new android.view.View(g);target.setOnTouchListener((v,e)->{touches[0]++;return true;});
        g.overlayWindow.removeAllViews();g.overlayWindow.addView(target,new android.widget.FrameLayout.LayoutParams(100,100));
        g.overlayWindow.measure(android.view.View.MeasureSpec.makeMeasureSpec(100,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(100,1073741824));g.overlayWindow.layout(0,0,100,100);
        var event=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_DOWN,20,20,0);
        assertTrue(g.overlayWindow.dispatchTouchEvent(event));assertEquals(1,touches[0]);assertFalse(g.unlockTransition);assertEquals(before,updates);
        Shadows.shadowOf(keyguard).setKeyguardLocked(true);g.overlayWindow.dispatchTouchEvent(event);assertEquals(1,touches[0]);event.recycle();
    }
    @Test public void editorFocusIsGrantedOnlyForAnUnlockedEditorAndOnlyOnce(){
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);g.interactive=false;g.waitingUnlock=true;g.prepareUnlock();g.overlay.frameReady=true;g.revealOverlay();
        int before=updates;g.requestEditorInput();assertEquals(before,updates);assertEquals(GuardService.Input.TOUCH,g.inputMode);
        g.interactive=true;g.completeUnlock();assertEquals(before,updates);g.requestEditorInput();
        assertEquals(before+1,updates);assertEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);assertEquals(1f,g.overlayLayout.alpha,0);
        g.requestEditorInput();assertEquals(before+1,updates);
        g.screenOff();assertEquals(GuardService.Input.TOUCH,g.inputMode);assertFalse(g.acceptGateInput());
    }
    @Test public void inPlaceChatPresentationRetainsBothOverlayAndAnyExistingMainHost(){
        LockyApp previous=LockyApp.app;LockyApp stub=new LockyApp();LockyApp.app=stub;
        org.robolectric.android.controller.ActivityController<MainActivity> activity=null;
        try{
            g.interactive=true;g.showGate("unlock","");WebHost host=g.overlay;host.ready=true;host.frameReady=true;host.loadingView.setVisibility(View.GONE);Object web=host.view;
            for(boolean warm:new boolean[]{false,true}){
                if(warm)activity=Robolectric.buildActivity(MainActivity.class,new android.content.Intent().putExtra("screen","home")).create();
                WebHost other=activity==null?null:activity.get().host;long nav=host.navigationGeneration;
                host.presented(host.view,"chat",nav,nav+1,"same-chat");
                assertEquals("chat",host.pendingScreen);assertEquals("same-chat",host.pendingChat);assertEquals(nav+1,host.navigationGeneration);assertSame(web,host.view);assertTrue(g.overlayVisible);assertEquals(View.GONE,host.loadingView.getVisibility());
                assertNull(Shadows.shadowOf((Application)g.getApplication()).getNextStartedActivity());
                if(warm)assertSame(other,activity.get().host);
                host.presented(host.view,"unlock",nav+1,nav+2,"");
            }
        }finally{if(activity!=null)activity.destroy();stub.work.shutdownNow();LockyApp.app=previous;}
    }

}
