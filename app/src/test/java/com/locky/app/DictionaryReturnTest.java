package com.locky.app;

import android.app.Application;
import android.content.*;
import android.content.pm.*;
import android.view.WindowManager;
import java.lang.reflect.Proxy;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class DictionaryReturnTest {
    GuardService g;int attached,detached;
    @Before public void setup(){
        Store.instance=null;g=Robolectric.buildService(GuardService.class).create().get();
        g.store=Store.get(g);GuardService.live=g;
        g.store.settings.edit().clear().commit();g.foreground=g.getPackageName();g.interactive=true;g.overlayVisible=true;g.gate="unlock";
        g.segmentWall=System.currentTimeMillis();g.segmentElapsed=android.os.SystemClock.elapsedRealtime();
        g.window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->{
            if(m.getName().equals("addView"))attached++;
            if(m.getName().equals("removeViewImmediate"))detached++;
            return null;
        });
        g.overlay=new WebHost(g,true);g.overlay.pendingScreen="unlock";g.overlay.ready=true;g.overlay.frameReady=true;
        g.overlayWindow=new android.widget.FrameLayout(g);g.overlayWindow.addView(g.overlay.root);g.gateLayout();g.overlayLayout.alpha=1f;
    }
    @After public void cleanup(){
        GuardService.live=null;g.main.removeCallbacksAndMessages(null);g.overlay.close();g.store.close();Store.instance=null;
    }
    void registerDictionary(ComponentName target){
        ResolveInfo info=new ResolveInfo();info.activityInfo=new ActivityInfo();
        info.activityInfo.packageName=target.getPackageName();info.activityInfo.name=target.getClassName();
        info.activityInfo.exported=true;info.activityInfo.applicationInfo=new ApplicationInfo();
        info.activityInfo.applicationInfo.packageName=target.getPackageName();info.nonLocalizedLabel="Dictionary";
        Shadows.shadowOf(g.getPackageManager()).addResolveInfoForIntent(Links.process(""),info);
        g.store.settings.edit().putString("dictionary","process:"+target.flattenToString()).commit();
    }
    ActivityController<DictionaryActivity> prepareHost(){
        g.dictionaryOpening=true;g.dictionaryWord="첫 단어";
        return Robolectric.buildActivity(DictionaryActivity.class,new Intent(g,DictionaryActivity.class)).create().start().resume().visible();
    }
    void restored(){g.overlay.frameReady=true;g.revealOverlay();if(g.overlay.firstFrameCallback!=null){Runnable r=g.overlay.firstFrameCallback;g.overlay.firstFrameCallback=null;r.run();}}
    @Test public void firstSearchAndRepeatedPopupReuseTheOnlyWebViewAndAnswerAfterDispatch(){
        ComponentName target=new ComponentName("net.daum.android.dictionary","net.daum.android.dictionary.MiniPopup");registerDictionary(target);
        WebHost original=g.overlay;Object web=original.view;long navigation=original.navigationGeneration;
        java.util.List<String> results=new java.util.ArrayList<>();g.dictionaryResult=results::add;
        ActivityController<DictionaryActivity> controller=prepareHost();DictionaryActivity a=controller.get();
        assertSame(original,a.host);assertSame(web,a.host.view);assertSame(a,g.dictionaryHost);
        assertEquals(1f,g.overlayLayout.alpha,0.001f);assertTrue(results.isEmpty());
        assertEquals(0,detached);assertEquals(0,attached);
        a.readyForDictionary();
        assertEquals(1,results.size());assertNull(results.get(0));assertEquals(0f,g.overlayLayout.alpha,0.001f);
        assertEquals(navigation,original.navigationGeneration);
        Intent first=Shadows.shadowOf((Application)g.getApplicationContext()).getNextStartedActivity();
        assertEquals(target,first.getComponent());assertEquals("첫 단어",first.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));
        controller.pause().resume();g.openDictionary("두 번째 단어",results::add);
        assertSame(a,g.dictionaryHost);assertSame(web,a.host.view);assertEquals(2,results.size());assertNull(results.get(1));
        Intent second=Shadows.shadowOf((Application)g.getApplicationContext()).getNextStartedActivity();
        assertEquals("두 번째 단어",second.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));
        int flags=Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
        assertEquals(flags,second.getFlags()&flags);
        a.returnToStart();restored();
        assertNull(g.dictionaryHost);assertSame(g.overlayWindow,original.root.getParent());assertSame(web,original.view);
        assertEquals(1f,g.overlayLayout.alpha,0.001f);assertEquals(0,attached);assertEquals(0,detached);
        controller.destroy();assertFalse(original.closed);
    }
    @Test public void aTransientForeignWindowDoesNotCloseTheDictionary(){
        registerDictionary(new ComponentName("com.example.dictionary","com.example.dictionary.Lookup"));
        ActivityController<DictionaryActivity> c=prepareHost();DictionaryActivity a=c.get();a.readyForDictionary();
        g.foreground="com.android.settings";a.foregroundChanged(g.foreground);
        g.foreground=g.getPackageName();a.foregroundChanged(g.foreground);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        assertSame(a,g.dictionaryHost);a.returnToStart();restored();c.destroy();
    }
    @Test public void aSettledForeignWindowReturnsTheSameStart(){
        registerDictionary(new ComponentName("com.example.dictionary","com.example.dictionary.Lookup"));
        ActivityController<DictionaryActivity> c=prepareHost();DictionaryActivity a=c.get();a.readyForDictionary();
        g.foreground="com.android.settings";a.foregroundChanged(g.foreground);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        assertNull(g.dictionaryHost);restored();c.destroy();
    }
    @Test public void dismissingStartRestoresOwnershipWithoutReattachingTheOverlayWindow(){
        registerDictionary(new ComponentName("com.example.dictionary","com.example.dictionary.Lookup"));
        ActivityController<DictionaryActivity> c=prepareHost();c.get().readyForDictionary();g.hideOverlay();
        assertNull(g.dictionaryHost);assertFalse(g.overlayVisible);assertSame(g.overlayWindow,g.overlay.root.getParent());
        assertEquals(0,attached);assertEquals(0,detached);c.destroy();
    }
    @Test public void cancelledOrFailedLaunchReportsFailureWithoutClosingTheDocument(){
        java.util.List<String> results=new java.util.ArrayList<>();g.dictionaryResult=results::add;
        ActivityController<DictionaryActivity> c=prepareHost();c.get().readyForDictionary();
        assertEquals(1,results.size());assertNotNull(results.get(0));assertNull(g.dictionaryHost);assertFalse(g.dictionaryOpening);
        restored();c.destroy();assertFalse(g.overlay.closed);
        g.dictionaryResult=results::add;c=prepareHost();c.get().returnToStart();restored();c.destroy();
        assertEquals(2,results.size());assertNotNull(results.get(1));assertEquals(1f,g.overlayLayout.alpha,0.001f);
    }
    @Test public void pendingOpeningRejectsDuplicateWithoutReportingFalseSuccess(){
        g.dictionaryOpening=true;java.util.List<String> results=new java.util.ArrayList<>();g.openDictionary("중복",results::add);
        assertEquals(1,results.size());assertNotNull(results.get(0));
    }
}
