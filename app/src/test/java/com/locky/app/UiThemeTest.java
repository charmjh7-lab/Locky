package com.locky.app;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.webkit.WebView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Resource and in-place theme checks, independent of Chromium/OEM presentation. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="night")
public class UiThemeTest {
    WebHost host;
    @Before public void setup(){Store.instance=null;GuardService.live=null;}
    @After public void cleanup(){if(host!=null){host.close();host.store.close();}Store.instance=null;}
    @Test public void initialNightSurfaceAndSystemBarsAgree(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        host=new WebHost(activity,true);
        assertTrue(UiTheme.dark(activity));
        assertFalse(activity.getResources().getBoolean(R.bool.light_system_bars));
        int background=UiTheme.background(activity);
        assertEquals(background,((ColorDrawable)host.root.getBackground()).getColor());
        assertEquals(background,((ColorDrawable)host.loadingView.getBackground()).getColor());
        assertEquals(background,activity.getWindow().getNavigationBarColor());
    }
    @Test public void themeAndFontChangeRetainDocumentAndNavigation(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        host=new WebHost(activity,true);WebView original=host.view;
        host.pendingScreen="unlock";host.renderedScreen="unlock";long generation=host.navigationGeneration;
        Configuration next=new Configuration(activity.getResources().getConfiguration());
        next.uiMode=(next.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|Configuration.UI_MODE_NIGHT_NO;next.fontScale=1.4f;
        activity.getResources().updateConfiguration(next,activity.getResources().getDisplayMetrics());
        host.applyTheme();
        assertFalse(UiTheme.dark(activity));assertSame(original,host.view);
        assertEquals(generation,host.navigationGeneration);assertEquals("unlock",host.renderedScreen);
        assertEquals(140,host.view.getSettings().getTextZoom());
        assertEquals(UiTheme.background(activity),((ColorDrawable)host.root.getBackground()).getColor());
    }
}
