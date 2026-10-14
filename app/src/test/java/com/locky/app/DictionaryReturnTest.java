package com.locky.app;

import android.app.Application;
import android.content.*;
import android.content.pm.*;
import android.view.WindowManager;
import java.lang.reflect.Proxy;
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
    @Test public void miniDictionaryIsLaunchedAboveTheActivityWithoutANewTask() throws Exception {
        ComponentName target=new ComponentName("net.daum.android.dictionary","net.daum.android.dictionary.MiniPopup");
        ResolveInfo info=new ResolveInfo();info.activityInfo=new ActivityInfo();info.activityInfo.packageName=target.getPackageName();info.activityInfo.name=target.getClassName();info.activityInfo.exported=true;info.activityInfo.applicationInfo=new ApplicationInfo();info.activityInfo.applicationInfo.packageName=target.getPackageName();info.nonLocalizedLabel="Mini dictionary";
        Shadows.shadowOf(g.getPackageManager()).addResolveInfoForIntent(Links.process(""),info);
        g.store.settings.edit().putString("dictionary","process:"+target.flattenToString()).commit();
        ActivityController<DictionaryActivity> controller=Robolectric.buildActivity(DictionaryActivity.class,new Intent(g,DictionaryActivity.class).putExtra("word","단어")).create();
        DictionaryActivity a=controller.get();Links.dictionary(a,"두 번째 단어");ShadowActivity.IntentForResult launched=Shadows.shadowOf(a).getNextStartedActivityForResult();
        assertEquals(target,launched.intent.getComponent());assertEquals("두 번째 단어",launched.intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));assertEquals(0,launched.intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK);assertEquals(DictionaryActivity.REQUEST,launched.requestCode);
        a.returnToStart();controller.destroy();
    }
}
