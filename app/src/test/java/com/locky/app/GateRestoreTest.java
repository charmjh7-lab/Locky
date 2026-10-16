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
    @Before public void setup(){Store.instance=null;g=Robolectric.buildService(GuardService.class).create().get();g.store=Store.get(g);g.interactive=true;g.segmentWall=System.currentTimeMillis();g.segmentElapsed=SystemClock.elapsedRealtime();g.window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->{if(m.getName().equals("addView"))adds++;return null;});}
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
        g.showGate("app","social");assertFalse(g.acceptGateInput());assertFalse(host.frameReady);assertEquals(android.view.View.GONE,host.loadingView.getVisibility());
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

}
