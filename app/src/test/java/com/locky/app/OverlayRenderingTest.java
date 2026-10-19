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
        final List<String> scripts=new ArrayList<>();final List<ValueCallback<String>> preparations=new ArrayList<>();int loads,resumes,pauses,layouts,timers,invalidations;
        FrameView(Activity c){super(c);}
        @Override public void postVisualStateCallback(long id,VisualStateCallback callback){ids.add(id);frames.add(callback);}
        @Override public void evaluateJavascript(String script,ValueCallback<String> callback){scripts.add(script);if(script.startsWith("prepareStart(")){preparations.add(callback);return;}if(callback!=null)callback.onReceiveValue("null");}
        @Override public void loadUrl(String url){loads++;}
        @Override public void onResume(){resumes++;}
        @Override public void onPause(){pauses++;}
        @Override public void resumeTimers(){timers++;}
        @Override public void invalidate(){invalidations++;super.invalidate();}
        @Override public void requestLayout(){layouts++;super.requestLayout();}
        void complete(int i){frames.get(i).onComplete(ids.get(i));}
    }
    @Before public void setup(){
        Store.instance=null;GuardService.live=null;
        Activity a=Robolectric.buildActivity(Activity.class).setup().visible().get();host=new WebHost(a,WebHost.Role.APP_GATE);
        assertEquals(View.LAYER_TYPE_NONE,host.view.getLayerType());
        // Robolectric 4.16.1 RoboWebSettings does not store offscreenPreRaster.
        // Its getter always returns false; hardware rasterization needs device evidence.
        host.root.removeView(host.view);host.view.destroy();view=new FrameView(a);host.view=view;host.root.addView(view,0,new android.widget.FrameLayout.LayoutParams(-1,-1));a.setContentView(host.root);
        host.pendingScreen="app";host.ready=true;host.resumeOverlay();
    }
    @After public void cleanup(){host.close();host.store.close();Store.instance=null;}
    void rendered(){host.rendered(view,"app",host.navigationGeneration,1);}
    @Test public void javascriptAndVisualCallbacksDoNotClaimAndroidHasDrawn(){
        rendered();assertEquals(1,view.frames.size());assertEquals(View.VISIBLE,view.getVisibility());
        view.complete(0);assertTrue(host.frameReady);assertEquals(View.VISIBLE,view.getVisibility());assertEquals(View.GONE,host.loadingView.getVisibility());assertEquals("",host.paintedScreen);
        host.didDraw(view);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("app",host.paintedScreen);
    }
    @Test public void staleFrameAndSameScreenJavascriptCannotCompleteNewNavigation(){
        long previous=host.navigationGeneration;rendered();host.screen("app");view.complete(0);
        assertFalse(host.frameReady);assertEquals(View.VISIBLE,view.getVisibility());
        host.rendered(view,"app",previous,1);assertEquals(1,view.frames.size());
        host.rendered(view,"app",host.navigationGeneration,1);assertEquals(2,view.frames.size());
    }
    @Test public void hiddenCallbacksCannotSuppressTheNextResume(){
        rendered();host.pause();view.complete(0);assertFalse(host.frameReady);assertEquals("",host.paintedScreen);
        host.resumeOverlay();assertEquals(2,view.frames.size());view.complete(1);assertTrue(host.frameReady);
        WebView stale=new WebView(view.getContext());host.rendered(stale,"app",host.navigationGeneration,1);assertEquals(2,view.frames.size());stale.destroy();
    }
    @Test public void preparationDoesNotCountAsPresentationAndResumePreservesDocument(){
        rendered();host.prepareOverlay();assertFalse(host.presentationActive);view.complete(0);
        host.didDraw(view);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("",host.paintedScreen);
        view.scripts.clear();host.resumeOverlay();assertSame(view,host.view);assertTrue(view.scripts.isEmpty());assertEquals(0,view.loads);assertEquals(1,view.frames.size());
        host.didDraw(view);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("app",host.paintedScreen);
    }
    @Test public void stalledVisualCallbackRequestsDrawsButCannotApproveInputByElapsedTime(){
        rendered();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        assertSame(view,host.view);assertEquals(0,view.loads);
        assertFalse(host.frameReady);assertFalse(host.currentGateReady());assertEquals(View.VISIBLE,view.getVisibility());
        assertEquals(View.GONE,host.loadingView.getVisibility());assertTrue(view.frames.size()>2);
        view.complete(0);assertTrue(host.frameReady);
        int callbacks=view.frames.size();host.resumeOverlay();assertEquals(callbacks,view.frames.size());assertEquals(1,view.resumes);
    }
    @Test public void failedInitialLoadCanRetryBeforeReadyButSuccessfulPageCannotReload(){
        host.ready=false;host.loadFailed=true;host.retryInitialLoad();assertSame(view,host.view);assertEquals(1,view.loads);assertFalse(host.ready);
        host.ready=true;rendered();host.retryInitialLoad();assertEquals(1,view.loads);
    }
    @Test public void repeatedWarmHideAndExposeDoesNotResumeRelayoutOrRevalidateTheFrame(){
        rendered();view.complete(0);host.prepareOverlay();view.scripts.clear();
        int resumes=view.resumes,timers=view.timers,layouts=view.layouts,invalidations=view.invalidations;long visual=host.visualGeneration,navigation=host.navigationGeneration;
        for(int n=0;n<10;n++){host.prepareOverlay();host.resumeOverlay();host.resumeOverlay();}
        assertTrue(host.frameReady);assertEquals(visual,host.visualGeneration);assertEquals(navigation,host.navigationGeneration);
        assertEquals(resumes,view.resumes);assertEquals(timers,view.timers);assertEquals(layouts,view.layouts);
        assertEquals(invalidations,view.invalidations);assertEquals(1,view.frames.size());assertTrue(view.scripts.isEmpty());assertEquals(0,view.loads);
    }
    @Test public void genuinePauseStillResumesAndRequestsANewFrameWithoutReloading(){
        rendered();view.complete(0);host.pause();host.pause();
        assertEquals(1,view.pauses);assertFalse(host.frameReady);assertFalse(host.hasPresentedDocument());host.resumeOverlay();
        assertEquals(2,view.resumes);assertEquals(2,view.frames.size());assertEquals(0,view.loads);
        view.complete(1);assertTrue(host.frameReady);
    }
    @Test public void changedDomOnTheSameScreenNeedsItsOwnFrameRatherThanReusingTheOldReadyFlag(){
        rendered();view.complete(0);long visual=host.visualGeneration,navigation=host.navigationGeneration;int layouts=view.layouts;
        host.rendered(view,"app",navigation,2);assertFalse(host.frameReady);assertTrue(host.hasPresentedDocument());assertTrue(host.visualGeneration>visual);assertEquals(navigation,host.navigationGeneration);
        assertEquals(2,view.frames.size());view.complete(0);assertFalse(host.frameReady);
        view.complete(1);assertTrue(host.frameReady);assertEquals(layouts,view.layouts);assertSame(view,host.view);assertEquals(0,view.loads);
    }
    @Test public void duplicateRestoreAndOlderRevisionCannotInvalidateAValidFrame(){
        rendered();view.complete(0);long generation=host.visualGeneration;
        rendered();host.rendered(view,"app",host.navigationGeneration,0);
        assertTrue(host.frameReady);assertEquals(generation,host.visualGeneration);assertEquals(1,view.frames.size());
    }
    @Test public void retainedPixelsSurviveWarmValidationButNeverADocumentDetachOrRealPause(){
        rendered();assertFalse(host.hasUsableFrame());view.complete(0);assertTrue(host.hasUsableFrame());
        host.prepareOverlay();host.rendered(view,"app",host.navigationGeneration,2);
        assertFalse(host.frameReady);assertTrue(host.hasUsableFrame());
        host.resetVisualState("detach");assertFalse(host.hasUsableFrame());
        host.requestVisualState();view.complete(view.frames.size()-1);assertTrue(host.hasUsableFrame());
        host.pause();assertFalse(host.hasUsableFrame());
        host.resumeOverlay();view.complete(view.frames.size()-1);assertTrue(host.hasUsableFrame());
        host.screen("app");assertFalse(host.hasUsableFrame());assertEquals(View.VISIBLE,view.getVisibility());
    }
    @Test public void warmEntryCannotValidateOldSameScreenBeforeItsOwnDomAcknowledgement() throws Exception {
        rendered();view.complete(0);int frames=view.frames.size();long old=host.navigationGeneration;
        host.gateScreen("app",new org.json.JSONObject());host.prepareOverlay();host.requestVisualState();
        assertEquals(frames,view.frames.size());assertFalse(host.currentGateReady());assertEquals(0,view.loads);
        host.rendered(view,"app",old,2);assertEquals(frames,view.frames.size());
        host.rendered(view,"app",host.navigationGeneration,2);assertEquals(frames+1,view.frames.size());
        view.complete(frames);assertTrue(host.currentGateReady());
    }
    @Test public void gateIdleOnlyReadiesTheDocumentAndActualFailureHasExplicitRecovery(){
        host.pendingScreen="gateIdle";host.rendered(view,"gateIdle",host.navigationGeneration,1);
        view.complete(0);assertFalse(host.gateNeedsInitialLoad());assertFalse(host.currentGateReady());
        assertFalse(host.gateErrorVisible());host.showGateError();assertTrue(host.gateErrorVisible());assertFalse(host.currentGateReady());
        host.retryInitialLoad();assertEquals(1,view.loads);assertFalse(host.ready);assertFalse(host.gateErrorVisible());
    }
    @Test public void phoneResumeInvalidatesOnlyAfterAnActualEngineResumeWithoutNavigation(){
        Activity owner=(Activity)view.getContext();host.close();host=new WebHost(owner,WebHost.Role.PHONE_START);
        host.root.removeView(host.view);host.view.destroy();view=new FrameView(owner);host.view=view;host.root.addView(view);
        int before=view.invalidations;long navigation=host.navigationGeneration;host.resume();assertTrue(view.invalidations>before);
        before=view.invalidations;host.resume();assertEquals(before,view.invalidations);
        host.pause();host.resume();assertTrue(view.invalidations>before);assertEquals(2,view.resumes);
        assertEquals(0,view.loads);assertTrue(view.scripts.isEmpty());assertEquals(navigation,host.navigationGeneration);
    }
    @Test public void unchangedInsetsDoNotScheduleAnotherViewportUpdateButImeChangesDo(){
        Shadows.shadowOf(Looper.getMainLooper()).idle();view.scripts.clear();
        var bars=androidx.core.graphics.Insets.of(0,24,0,32);
        var normal=new androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),bars).setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(),false).build();
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(host.root,normal);Shadows.shadowOf(Looper.getMainLooper()).idle();
        long count=view.scripts.stream().filter(s->s.startsWith("insetsChanged")).count();assertEquals(1,count);
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(host.root,normal);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(count,view.scripts.stream().filter(s->s.startsWith("insetsChanged")).count());
        var ime=new androidx.core.view.WindowInsetsCompat.Builder(normal).setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(),androidx.core.graphics.Insets.of(0,0,0,300)).setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(),true).build();
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(host.root,ime);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(count+1,view.scripts.stream().filter(s->s.startsWith("insetsChanged")).count());assertEquals(300,host.root.getPaddingBottom());
    }
    @Test public void unchangedSameAppEntryReusesFrameOnlyAfterNewNavigationAcknowledgement() throws Exception {
        org.json.JSONObject payload=new org.json.JSONObject().put("gate",new org.json.JSONObject().put("pkg","video"));
        host.gateScreen("app",payload);host.rendered(view,"app",host.navigationGeneration,1);view.complete(view.frames.size()-1);
        assertTrue(host.currentGateReady());long visual=host.visualGeneration,old=host.navigationGeneration;int frames=view.frames.size();
        host.gateScreen("app",payload);assertEquals(visual,host.visualGeneration);assertTrue(host.frameReady);assertFalse(host.currentGateReady());
        host.rendered(view,"app",old,1);assertFalse(host.currentGateReady());
        host.rendered(view,"app",host.navigationGeneration,1);assertTrue(host.currentGateReady());assertEquals(frames,view.frames.size());assertEquals(1f,view.getAlpha(),0);
        host.gateScreen("app",new org.json.JSONObject().put("gate",new org.json.JSONObject().put("pkg","social")));
        assertFalse(host.frameReady);assertFalse(host.hasUsableFrame());assertEquals(0f,view.getAlpha(),0);
    }

}
