package com.locky.app;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.webkit.*;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Lifecycle ordering checks; these do not emulate Samsung's compositor. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class OverlayRenderingTest {
    WebHost host;FrameView view;
    static class FrameView extends WebView {
        final List<VisualStateCallback> frames=new ArrayList<>();final List<Long> ids=new ArrayList<>();
        final List<String> scripts=new ArrayList<>();int loads,resumes,pauses,layouts,timers;
        FrameView(Activity c){super(c);}
        @Override public void postVisualStateCallback(long id,VisualStateCallback callback){ids.add(id);frames.add(callback);}
        @Override public void evaluateJavascript(String script,ValueCallback<String> callback){scripts.add(script);if(callback!=null)callback.onReceiveValue("null");}
        @Override public void loadUrl(String url){loads++;}
        @Override public void onResume(){resumes++;}
        @Override public void onPause(){pauses++;}
        @Override public void resumeTimers(){timers++;}
        @Override public void requestLayout(){layouts++;super.requestLayout();}
        void complete(int i){frames.get(i).onComplete(ids.get(i));}
    }
    @Before public void setup(){
        Store.instance=null;GuardService.live=null;
        Activity a=Robolectric.buildActivity(Activity.class).setup().visible().get();host=new WebHost(a,true);
        assertEquals(View.LAYER_TYPE_SOFTWARE,host.view.getLayerType());
        host.root.removeView(host.view);host.view.destroy();view=new FrameView(a);view.setVisibility(View.INVISIBLE);host.view=view;host.root.addView(view,0,new android.widget.FrameLayout.LayoutParams(-1,-1));a.setContentView(host.root);
        host.pendingScreen="unlock";host.ready=true;host.resumeOverlay();
    }
    @After public void cleanup(){host.close();host.store.close();Store.instance=null;}
    void rendered(){host.rendered(view,"unlock",host.navigationGeneration);}
    @Test public void javascriptAndVisualCallbacksDoNotClaimAndroidHasDrawn(){
        rendered();assertEquals(1,view.frames.size());assertEquals(View.INVISIBLE,view.getVisibility());
        view.complete(0);assertTrue(host.frameReady);assertEquals(View.VISIBLE,view.getVisibility());assertEquals(View.GONE,host.loadingView.getVisibility());assertEquals("",host.paintedScreen);
        host.didDraw(view);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("unlock",host.paintedScreen);
    }
    @Test public void staleFrameAndSameScreenJavascriptCannotCompleteNewNavigation(){
        long previous=host.navigationGeneration;rendered();host.screen("unlock");view.complete(0);
        assertFalse(host.frameReady);assertEquals(View.INVISIBLE,view.getVisibility());
        host.rendered(view,"unlock",previous);assertEquals(1,view.frames.size());
        host.rendered(view,"unlock",host.navigationGeneration);assertEquals(2,view.frames.size());
    }
    @Test public void hiddenCallbacksCannotSuppressTheNextResume(){
        rendered();host.pause();view.complete(0);assertFalse(host.frameReady);assertEquals("",host.paintedScreen);
        host.resumeOverlay();assertEquals(2,view.frames.size());view.complete(1);assertTrue(host.frameReady);
        WebView stale=new WebView(view.getContext());host.rendered(stale,"unlock",host.navigationGeneration);assertEquals(2,view.frames.size());stale.destroy();
    }
    @Test public void preparationDoesNotCountAsPresentationAndResumePreservesDocument(){
        rendered();host.prepareOverlay();assertFalse(host.presentationActive);view.complete(0);
        host.didDraw(view);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("",host.paintedScreen);
        view.scripts.clear();host.resumeOverlay();assertSame(view,host.view);assertTrue(view.scripts.isEmpty());assertEquals(0,view.loads);assertEquals(1,view.frames.size());
        host.didDraw(view);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("unlock",host.paintedScreen);
    }
    @Test public void stalledVisualCallbackRestoresTheExistingDocumentWithoutReload(){
        rendered();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        assertSame(view,host.view);assertEquals(0,view.loads);
        assertTrue(host.frameReady);assertEquals(View.VISIBLE,view.getVisibility());
        assertEquals(View.GONE,host.loadingView.getVisibility());assertEquals(2,view.frames.size());
        view.complete(0);assertTrue(host.frameReady);
        host.resumeOverlay();assertEquals(2,view.frames.size());assertEquals(1,view.resumes);
    }
    @Test public void failedInitialLoadCanRetryBeforeReadyButSuccessfulPageCannotReload(){
        host.ready=false;host.loadFailed=true;host.retryInitialLoad();assertSame(view,host.view);assertEquals(1,view.loads);assertFalse(host.ready);
        host.ready=true;rendered();host.loadFailed=true;host.retryInitialLoad();assertEquals(1,view.loads);
    }
    @Test public void repeatedWarmHideAndExposeDoesNotResumeRelayoutOrRevalidateTheFrame(){
        rendered();view.complete(0);host.prepareOverlay();view.scripts.clear();
        int resumes=view.resumes,timers=view.timers,layouts=view.layouts;long visual=host.visualGeneration,navigation=host.navigationGeneration;
        for(int n=0;n<10;n++){host.prepareOverlay();host.resumeOverlay();host.resumeOverlay();}
        assertTrue(host.frameReady);assertEquals(visual,host.visualGeneration);assertEquals(navigation,host.navigationGeneration);
        assertEquals(resumes,view.resumes);assertEquals(timers,view.timers);assertEquals(layouts,view.layouts);
        assertEquals(1,view.frames.size());assertTrue(view.scripts.isEmpty());assertEquals(0,view.loads);
    }
    @Test public void genuinePauseStillResumesAndRequestsANewFrameWithoutReloading(){
        rendered();view.complete(0);host.pause();host.pause();
        assertEquals(1,view.pauses);assertFalse(host.frameReady);host.resumeOverlay();
        assertEquals(2,view.resumes);assertEquals(2,view.frames.size());assertEquals(0,view.loads);
        view.complete(1);assertTrue(host.frameReady);
    }
    @Test public void changedDomOnTheSameScreenNeedsItsOwnFrameRatherThanReusingTheOldReadyFlag(){
        rendered();view.complete(0);long visual=host.visualGeneration,navigation=host.navigationGeneration;
        rendered();assertFalse(host.frameReady);assertTrue(host.visualGeneration>visual);assertEquals(navigation,host.navigationGeneration);
        assertEquals(2,view.frames.size());view.complete(0);assertFalse(host.frameReady);
        view.complete(1);assertTrue(host.frameReady);assertSame(view,host.view);assertEquals(0,view.loads);
    }

}
