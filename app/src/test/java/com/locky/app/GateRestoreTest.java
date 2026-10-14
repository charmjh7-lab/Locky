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
@Config(application=Application.class,sdk=35)
public class GateRestoreTest {
    GuardService g;int adds,removes;KeyguardManager keyguard;
    @Before public void setup(){
        Store.instance=null;g=Robolectric.buildService(GuardService.class).create().get();g.store=Store.get(g);GuardService.live=g;
        g.segmentWall=System.currentTimeMillis();g.segmentElapsed=android.os.SystemClock.elapsedRealtime();
        keyguard=g.getSystemService(KeyguardManager.class);
        g.window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->{if(m.getName().equals("addView"))adds++;if(m.getName().startsWith("removeView"))removes++;return null;});
    }
    @After public void cleanup(){GuardService.live=null;g.main.removeCallbacksAndMessages(null);if(g.overlay!=null)g.overlay.close();g.store.close();Store.instance=null;}
    @Test public void lockedPreparationIsTransparentPassiveAndUsesTheSameWindowOnUnlock(){
        g.interactive=false;g.prepareUnlock();WebHost host=g.overlay;Object web=host.view;long navigation=host.navigationGeneration;
        assertEquals(0,g.overlayLayout.alpha,0);assertFalse(host.presentationActive);assertEquals(View.VISIBLE,host.root.getVisibility());
        int passive=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        assertEquals(passive,g.overlayLayout.flags&passive);assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,host.root.getImportantForAccessibility());
        g.interactive=true;Shadows.shadowOf(keyguard).setKeyguardLocked(true);g.revealOverlay();assertEquals(0,g.overlayLayout.alpha,0);assertFalse(host.presentationActive);
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);g.prepareUnlock();
        assertSame(host,g.overlay);assertSame(web,g.overlay.view);assertEquals(1,adds);assertEquals(0,removes);assertEquals(navigation,host.navigationGeneration);
        assertEquals(1,g.overlayLayout.alpha,0);assertEquals(0,g.overlayLayout.flags&passive);assertTrue(host.presentationActive);
    }
    @Test public void alreadyVisibleAndPreviouslyDismissedStartsStillResumeWithoutNavigating(){
        g.interactive=true;g.showGate("unlock","");long navigation=g.overlay.navigationGeneration;
        g.overlay.pause();assertEquals(View.VISIBLE,g.overlay.root.getVisibility());g.showGate("unlock","");assertTrue(g.overlay.presentationActive);assertEquals(navigation,g.overlay.navigationGeneration);
        g.hideOverlay();g.interactive=false;g.prepareUnlock();assertEquals(navigation,g.overlay.navigationGeneration);assertEquals(1,adds);assertEquals(0,removes);
    }
}
