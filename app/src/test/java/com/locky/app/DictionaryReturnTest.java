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
import org.robolectric.shadows.ShadowActivity;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class DictionaryReturnTest {
    GuardService g;int attached,detached;
    @Before public void setup(){
        Store.instance=null;g=Robolectric.buildService(GuardService.class).create().get();g.store=Store.get(g);GuardService.live=g;
        g.store.settings.edit().clear().commit();g.foreground=g.getPackageName();g.interactive=true;g.overlayVisible=true;g.gate="unlock";
        g.segmentWall=System.currentTimeMillis();g.segmentElapsed=android.os.SystemClock.elapsedRealtime();
        g.window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->{if(m.getName().equals("addView"))attached++;if(m.getName().equals("removeViewImmediate"))detached++;return null;});
        g.overlay=new WebHost(g,true);g.overlay.pendingScreen="unlock";
    }
    @After public void cleanup(){GuardService.live=null;g.main.removeCallbacksAndMessages(null);g.overlay.close();g.store.close();Store.instance=null;}
    @Test public void popupBorrowsAndReturnsTheSameStartViewWithoutEndingIt(){
        WebHost original=g.overlay;android.view.View root=original.root;
        ActivityController<DictionaryActivity> controller=Robolectric.buildActivity(DictionaryActivity.class,new Intent(g,DictionaryActivity.class).putExtra("word","첫 단어")).create();
        DictionaryActivity a=controller.get();assertSame(a,g.dictionaryHost);assertSame(original,g.overlay);assertTrue(g.overlayVisible);assertNotNull(root.getParent());assertEquals(1,detached);
        a.returnToStart();assertNull(g.dictionaryHost);assertSame(root,g.overlay.root);assertEquals("unlock",g.overlay.pendingScreen);assertTrue(g.overlayVisible);assertEquals(1,attached);
        controller.destroy();assertEquals("Destroy must not restore twice",1,attached);
    }
    @Test public void daumPopupDoesNotLaunchFlashingBridgeActivity() throws Exception {
        ComponentName target=new ComponentName("net.daum.android.dictionary","net.daum.android.dictionary.MiniPopup");
        ResolveInfo info=new ResolveInfo();info.activityInfo=new ActivityInfo();
        info.activityInfo.packageName=target.getPackageName();info.activityInfo.name=target.getClassName();
        info.activityInfo.exported=true;info.activityInfo.applicationInfo=new ApplicationInfo();
        info.activityInfo.applicationInfo.packageName=target.getPackageName();
        Shadows.shadowOf(g.getPackageManager()).addResolveInfoForIntent(Links.process(""),info);
        g.store.settings.edit().putString("dictionary","process:"+target.flattenToString()).commit();
        g.gateLayout();
        g.overlay.root.layout(0,0,480,840);
        android.view.View original=g.overlay.root;
        g.openDictionary("다음 검색어",false);
        assertTrue(g.daumPopupActive);assertNull(g.dictionaryHost);
        assertEquals(0,detached);assertEquals(1f,g.overlayLayout.alpha,0.001f);
        Intent host=Shadows.shadowOf((Application)g.getApplicationContext()).getNextStartedActivity();
        assertNotNull(host);assertEquals(new ComponentName(g,DaumBackdropActivity.class),host.getComponent());
        assertNotNull(g.daumBackdrop);assertSame(original,g.overlay.root);
        ActivityController<DaumBackdropActivity> controller=Robolectric.buildActivity(DaumBackdropActivity.class,host).create();
        DaumBackdropActivity activity=controller.get();
        assertSame(activity,g.daumBackdropHost);
        // The overlay only becomes transparent after the backdrop is drawn.
        assertEquals(1f,g.overlayLayout.alpha,0.001f);
        g.showDaumPopup(activity);
        assertEquals(0f,g.overlayLayout.alpha,0.001f);
        Intent launched=Shadows.shadowOf((Application)g.getApplicationContext()).getNextStartedActivity();
        assertNotNull(launched);assertEquals(target,launched.getComponent());
        assertEquals("다음 검색어",launched.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));
        int flags=Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
        assertEquals(flags,launched.getFlags()&flags);
        g.popupForeground(g.DAUM_DICTIONARY);assertTrue(g.daumPopupSeen);
        g.foreground=g.getPackageName();g.popupForeground(g.foreground);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(850));
        assertFalse(g.daumPopupActive);assertTrue(g.overlayVisible);assertSame(original,g.overlay.root);
        assertEquals(1f,g.overlayLayout.alpha,0.001f);
        controller.destroy();
    }
    @Test public void miniDictionaryRetainsIsolatedPopupTaskFlags() throws Exception {
        ComponentName target=new ComponentName("net.daum.android.dictionary","net.daum.android.dictionary.MiniPopup");
        ResolveInfo info=new ResolveInfo();info.activityInfo=new ActivityInfo();info.activityInfo.packageName=target.getPackageName();info.activityInfo.name=target.getClassName();info.activityInfo.exported=true;info.activityInfo.applicationInfo=new ApplicationInfo();info.activityInfo.applicationInfo.packageName=target.getPackageName();info.nonLocalizedLabel="Mini dictionary";
        Shadows.shadowOf(g.getPackageManager()).addResolveInfoForIntent(Links.process(""),info);
        g.store.settings.edit().putString("dictionary","process:"+target.flattenToString()).commit();
        ActivityController<DictionaryActivity> controller=Robolectric.buildActivity(DictionaryActivity.class,new Intent(g,DictionaryActivity.class).putExtra("word","단어")).create();
        DictionaryActivity a=controller.get();Links.dictionary(a,"두 번째 단어");
        Intent launched=Shadows.shadowOf(a).getNextStartedActivity();
        assertNotNull(launched);assertEquals(target,launched.getComponent());
        assertEquals("두 번째 단어",launched.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));
        int expected=Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
        assertEquals(expected,launched.getFlags()&expected);
        a.returnToStart();controller.destroy();
    }
    void registerWorkingDictionary() {
        ComponentName target=new ComponentName("com.example.dictionary","com.example.dictionary.Lookup");
        ResolveInfo info=new ResolveInfo();info.activityInfo=new ActivityInfo();
        info.activityInfo.packageName=target.getPackageName();info.activityInfo.name=target.getClassName();
        info.activityInfo.exported=true;info.activityInfo.applicationInfo=new ApplicationInfo();
        info.activityInfo.applicationInfo.packageName=target.getPackageName();
        Shadows.shadowOf(g.getPackageManager()).addResolveInfoForIntent(Links.process(""),info);
        g.store.settings.edit().putString("dictionary","process:"+target.flattenToString()).commit();
    }
    @Test public void aTransientForeignWindowDoesNotCloseTheDictionary() {
        registerWorkingDictionary();
        ActivityController<DictionaryActivity> controller=Robolectric.buildActivity(DictionaryActivity.class,new Intent(g,DictionaryActivity.class).putExtra("word","단어")).create();
        DictionaryActivity a=controller.get();
        g.foreground="com.android.settings";a.foregroundChanged(g.foreground);
        g.foreground=g.getPackageName();a.foregroundChanged(g.foreground);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        assertSame(a,g.dictionaryHost);
        a.returnToStart();controller.destroy();
    }
    @Test public void aSettledForeignWindowReturnsToStart() {
        registerWorkingDictionary();
        ActivityController<DictionaryActivity> controller=Robolectric.buildActivity(DictionaryActivity.class,new Intent(g,DictionaryActivity.class).putExtra("word","단어")).create();
        DictionaryActivity a=controller.get();
        g.foreground="com.android.settings";a.foregroundChanged(g.foreground);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        assertNull(g.dictionaryHost);
        controller.destroy();
    }
}
