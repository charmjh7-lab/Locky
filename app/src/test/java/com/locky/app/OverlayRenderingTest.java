package com.locky.app;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.webkit.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class OverlayRenderingTest {
    WebHost host;
    FrameView view;
    static class FrameView extends WebView {
        final List<VisualStateCallback> frames=new ArrayList<>();
        final List<Long> ids=new ArrayList<>();
        final List<String> scripts=new ArrayList<>();
        FrameView(Activity c){super(c);}
        @Override public void postVisualStateCallback(long id,VisualStateCallback callback){ids.add(id);frames.add(callback);}
        @Override public void evaluateJavascript(String script,ValueCallback<String> callback){scripts.add(script);if(callback!=null)callback.onReceiveValue("null");}
        void complete(int i){frames.get(i).onComplete(ids.get(i));}
    }
    @Before public void setup(){
        Store.instance=null;GuardService.live=null;
        Activity a=Robolectric.buildActivity(Activity.class).setup().visible().get();host=new WebHost(a,true);
        host.root.removeView(host.view);host.view.destroy();view=new FrameView(a);view.setVisibility(View.INVISIBLE);host.view=view;host.root.addView(view,0,new android.widget.FrameLayout.LayoutParams(-1,-1));a.setContentView(host.root);
        host.pendingScreen="unlock";host.ready=true;
    }
    @After public void cleanup(){host.close();host.store.close();Store.instance=null;}
    @Test public void javascriptRenderDoesNotExposeTheViewUntilItsFrameIsReady(){
        host.rendered("unlock");assertEquals(1,view.frames.size());assertEquals(View.INVISIBLE,view.getVisibility());assertEquals(View.VISIBLE,host.loadingView.getVisibility());
        view.complete(0);assertEquals(View.VISIBLE,view.getVisibility());assertEquals(View.GONE,host.loadingView.getVisibility());assertEquals("unlock",host.paintedScreen);
    }
    @Test public void staleFrameCannotExposeAnotherScreenOrAHiddenOverlay(){
        host.rendered("unlock");host.screen("app");view.complete(0);assertEquals(View.INVISIBLE,view.getVisibility());assertEquals(View.VISIBLE,host.loadingView.getVisibility());
        host.rendered("app");host.root.setVisibility(View.INVISIBLE);view.complete(1);assertEquals(View.INVISIBLE,view.getVisibility());
    }
    @Test public void failedVisualPreparationCanRecreateTheRendererWithoutATouch(){
        host.recoverOverlay(view);assertNotSame(view,host.view);assertFalse(host.ready);assertEquals(View.VISIBLE,host.loadingView.getVisibility());
    }
    @Test public void redisplayRequestsAFrameWithoutNavigatingOrResettingInputs(){
        host.rendered("unlock");view.complete(0);view.scripts.clear();host.root.setVisibility(View.INVISIBLE);host.root.setVisibility(View.VISIBLE);host.resumeOverlay();
        assertEquals(2,view.frames.size());assertTrue("Re-show must preserve the same DOM and drafts",view.scripts.isEmpty());view.complete(1);assertEquals(View.VISIBLE,view.getVisibility());
    }
}
