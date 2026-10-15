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
        g.overlay=new WebHost(g,true);g.overlay.pendingScreen="unlock";g.gateLayout();g.overlayLayout.alpha=1f;
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
        g.dictionaryOpening=true;
        return Robolectric.buildActivity(DictionaryActivity.class,new Intent(g,DictionaryActivity.class)
            .putExtra("word","첫 단어")).create().start().resume();
    }
    @Test public void firstSearchDoesNotDetachOverlayAndRepeatedPopupUsesSameHost(){
        ComponentName target=new ComponentName("net.daum.android.dictionary","net.daum.android.dictionary.MiniPopup");
        registerDictionary(target);
        WebHost original=g.overlay;android.view.View root=original.root;
        ActivityController<DictionaryActivity> controller=prepareHost();
        DictionaryActivity a=controller.get();
        assertNull(g.dictionaryHost);assertEquals(1f,g.overlayLayout.alpha,0.001f);
        assertEquals(0,detached);assertEquals(0,attached);
        assertNotSame(original,a.host);assertSame(root,g.overlay.root);
        a.readyForDictionary();
        assertSame(a,g.dictionaryHost);assertEquals(0f,g.overlayLayout.alpha,0.001f);
        assertEquals(0,detached);assertEquals(0,attached);
        Intent first=Shadows.shadowOf((Application)g.getApplicationContext()).getNextStartedActivity();
        assertNotNull(first);assertEquals(target,first.getComponent());
        assertEquals("첫 단어",first.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));
        controller.pause().resume();
        assertSame(a,g.dictionaryHost);assertFalse(a.isFinishing());
        try{g.openDictionary("두 번째 단어",false);}catch(Exception e){throw new AssertionError(e);}
        assertSame(a,g.dictionaryHost);
        Intent second=Shadows.shadowOf((Application)g.getApplicationContext()).getNextStartedActivity();
        assertNotNull(second);assertEquals(target,second.getComponent());
        assertEquals("두 번째 단어",second.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));
        int flags=Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
        assertEquals(flags,second.getFlags()&flags);
        a.returnToStart();assertNull(g.dictionaryHost);assertSame(root,g.overlay.root);
        assertEquals(1f,g.overlayLayout.alpha,0.001f);
        assertEquals(0,detached);assertEquals(0,attached);controller.destroy();
    }
    @Test public void aTransientForeignWindowDoesNotCloseTheDictionary(){
        registerDictionary(new ComponentName("com.example.dictionary","com.example.dictionary.Lookup"));
        ActivityController<DictionaryActivity> c=prepareHost();DictionaryActivity a=c.get();a.readyForDictionary();
        g.foreground="com.android.settings";a.foregroundChanged(g.foreground);
        g.foreground=g.getPackageName();a.foregroundChanged(g.foreground);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        assertSame(a,g.dictionaryHost);a.returnToStart();c.destroy();
    }
    @Test public void aSettledForeignWindowReturnsToStart(){
        registerDictionary(new ComponentName("com.example.dictionary","com.example.dictionary.Lookup"));
        ActivityController<DictionaryActivity> c=prepareHost();DictionaryActivity a=c.get();a.readyForDictionary();
        g.foreground="com.android.settings";a.foregroundChanged(g.foreground);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        assertNull(g.dictionaryHost);c.destroy();
    }
    @Test public void dismissingStartDoesNotReparentTheOverlay(){
        registerDictionary(new ComponentName("com.example.dictionary","com.example.dictionary.Lookup"));
        ActivityController<DictionaryActivity> c=prepareHost();DictionaryActivity a=c.get();a.readyForDictionary();
        g.hideOverlay();assertNull(g.dictionaryHost);assertFalse(g.overlayVisible);
        assertEquals(0,detached);assertEquals(0,attached);c.destroy();
    }
    @Test public void cancelBeforeFirstFrameKeepsTheOriginalStartVisible(){
        ActivityController<DictionaryActivity> c=prepareHost();
        c.get().returnToStart();c.destroy();
        assertFalse(g.dictionaryOpening);assertNull(g.dictionaryHost);assertEquals(1f,g.overlayLayout.alpha,0.001f);
        assertEquals(0,detached);
    }
}
