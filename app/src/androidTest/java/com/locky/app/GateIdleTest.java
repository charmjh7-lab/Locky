package com.locky.app;

import android.content.*;
import android.os.SystemClock;
import android.view.*;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real WebView visibility and idle/re-entry check; not a Samsung compositor test. */
@RunWith(AndroidJUnit4.class)
public class GateIdleTest {
    void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    String js(WebHost host,String script) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();
        main(()->host.view.evaluateJavascript(script,value->{result.set(value);done.countDown();}));
        assertTrue("WebView script did not respond",done.await(5,TimeUnit.SECONDS));return result.get();
    }
    void awaitReady(GuardService g) throws Exception {
        long end=SystemClock.uptimeMillis()+10000;boolean[] ready={false};
        do{main(()->ready[0]=g.acceptGateInput());if(ready[0])return;Thread.sleep(25);}while(SystemClock.uptimeMillis()<end);
        fail("Gate did not become interactive: "+g.exposure);
    }
    void concealed(GuardService g,WebHost retained) throws Exception {
        main(()->{
            assertSame(retained,g.overlay);assertTrue(g.overlayWindow.isAttachedToWindow());
            assertEquals(View.VISIBLE,retained.view.getVisibility());assertEquals(0f,g.overlayLayout.alpha,0f);
            assertNotEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            assertFalse(g.acceptGateInput());
        });
        assertEquals("Hidden window must not send WebContents to the background","\"visible\"",js(retained,"document.visibilityState"));
    }
    @Test public void retainedPageStaysVisibleToChromiumDuringIdleAndReopens() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();Store store=Store.get(c);
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Configurator.getInstance().setUiAutomationFlags(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String services=device.executeShellCommand("settings get secure enabled_accessibility_services").trim();
        int start=store.settings.getInt("start",540),end=store.settings.getInt("end",1260);
        boolean enabled=store.settings.getBoolean("enabled",true);
        try{
            device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");device.pressHome();
            store.settings.edit().putBoolean("enabled",true).putInt("start",0).putInt("end",0).commit();
            ContentValues rule=new ContentValues();rule.put("pkg","com.locky.fixture");rule.put("label","유휴 진입 테스트");rule.put("mode","limited");rule.put("minutes",60);store.put("rules",rule);
            device.executeShellCommand("settings put secure enabled_accessibility_services com.locky.app/com.locky.app.GuardService");
            device.executeShellCommand("settings put secure accessibility_enabled 1");
            long deadline=SystemClock.uptimeMillis()+12000;
            while(GuardService.live==null&&SystemClock.uptimeMillis()<deadline)Thread.sleep(100);
            GuardService g=GuardService.live;assertNotNull("Accessibility service did not connect",g);
            device.executeShellCommand("am start -n com.locky.fixture/.ProbeActivity");awaitReady(g);
            WebHost retained=g.overlay;
            assertEquals("\"visible\"",js(retained,"document.visibilityState"));
            main(g::stopApp);device.pressHome();Thread.sleep(300);concealed(g,retained);
            // Exceeds Chromium's ordinary background timer budget grace period.
            // No keepalive, artificial animation or repeated WebView wakeups.
            for(int seconds=0;seconds<70;seconds++)Thread.sleep(1000);
            concealed(g,retained);
            long requested=SystemClock.uptimeMillis();
            device.executeShellCommand("am start -n com.locky.fixture/.ProbeActivity");awaitReady(g);
            long elapsed=SystemClock.uptimeMillis()-requested;
            assertSame(retained,g.overlay);
            assertEquals("\"유휴 진입 테스트\"",js(retained,"document.querySelector('.gate-app').textContent"));
            assertEquals("\"visible\"",js(retained,"document.visibilityState"));
            main(()->{assertEquals(1f,g.overlayLayout.alpha,0f);assertEquals(0,g.overlayLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);});
            android.util.Log.i("LockyIdleTest",new JSONObject().put("idleMs",70000).put("launchToInteractiveMs",elapsed).put("sameWebView",true).put("hiddenPageVisibility","visible").toString());
        }finally{
            main(()->{if(GuardService.live!=null)GuardService.live.stopApp();});
            store.getWritableDatabase().delete("rules","pkg=?",new String[]{"com.locky.fixture"});
            store.settings.edit().putBoolean("enabled",enabled).putInt("start",start).putInt("end",end).commit();
            device.executeShellCommand("settings put secure enabled_accessibility_services "+(services.equals("null")?"''":services));
            device.pressHome();
        }
    }
}
