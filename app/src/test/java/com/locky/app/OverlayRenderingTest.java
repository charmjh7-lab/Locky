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
        final List<String> scripts=new ArrayList<>();final List<ValueCallback<String>> preparations=new ArrayList<>();int loads,resumes,pauses,layouts,timers,invalidations,windowVisibility=View.VISIBLE;
        FrameView(android.content.Context c){super(c);}
        // The mock frame source supplies window visibility too: Robolectric's
        // isolated Activity reports GONE even after attachment and visible().
        @Override public int getWindowVisibility(){return windowVisibility;}
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
        view.setVisibility(View.VISIBLE);view.setAlpha(0f);
        // Isolated WebHost tests use the same synchronous exposure handoff as the service.
        host.frameAvailableCallback=()->{view.setAlpha(host.currentGateReady()?1f:0f);host.loadingView.setVisibility(host.gateErrorVisible()?View.VISIBLE:View.GONE);};
        host.pendingScreen="app";host.ready=true;host.resumeOverlay();
    }
    @After public void cleanup(){host.close();host.store.close();Store.instance=null;}
    void entry(String name,org.json.JSONObject payload){view.setVisibility(View.VISIBLE);view.setAlpha(0f);host.gateScreen(name,payload);}
    void rendered(){host.rendered(view,"app",host.navigationGeneration,1);}
    @Test public void acknowledgementCannotExposeBeforeThePreparedFrame(){
        rendered();assertEquals(1,view.frames.size());assertEquals(0f,view.getAlpha(),0f);
        view.complete(0);assertTrue(host.frameReady);assertEquals(View.VISIBLE,view.getVisibility());
        assertEquals(View.GONE,host.loadingView.getVisibility());
    }
    @Test public void staleFrameAndSameScreenJavascriptCannotCompleteNewNavigation(){
        long previous=host.navigationGeneration;rendered();entry("app",new org.json.JSONObject());view.complete(0);
        assertFalse(host.frameReady);assertEquals(0f,view.getAlpha(),0f);
        host.rendered(view,"app",previous,1);assertEquals(1,view.frames.size());
        host.rendered(view,"app",host.navigationGeneration,1);assertEquals(2,view.frames.size());
    }
    @Test public void ordinaryHideRetainsEngineAndValidatedFrame(){
        rendered();view.complete(0);host.pause();
        assertTrue(host.frameReady);assertFalse(host.presentationActive);assertEquals(0,view.pauses);
        host.resumeOverlay();assertEquals(1,view.frames.size());assertTrue(host.currentGateReady());
    }
    @Test public void preparationDoesNotChangePresentationOrWaitForDrawing(){
        rendered();host.prepareOverlay();assertTrue(host.presentationActive);view.complete(0);
        view.scripts.clear();host.resumeOverlay();assertSame(view,host.view);assertTrue(view.scripts.isEmpty());assertEquals(0,view.loads);assertEquals(1,view.frames.size());
    }
    @Test public void stalledVisualCallbackRetriesOnceThenShowsErrorWithoutReloading(){
        rendered();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200));
        assertEquals(2,view.frames.size());assertFalse(host.frameReady);assertFalse(host.gateErrorVisible());
        view.complete(0);assertFalse(host.frameReady); // Original request was retired by recovery.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200));
        assertSame(view,host.view);assertEquals(0,view.loads);assertFalse(host.currentGateReady());
        assertTrue(host.gateErrorVisible());assertEquals(-1,host.visualPending);
        view.complete(1);assertFalse(host.frameReady);assertTrue(host.gateErrorVisible());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));assertEquals(2,view.frames.size());
        host.retryInitialLoad();assertEquals(3,view.frames.size());assertFalse(host.gateErrorVisible());assertEquals(0,view.loads);
        view.complete(2);assertTrue(host.currentGateReady());
    }
    @Test public void nextManagedAppCanPrepareAfterVisualFailure() throws Exception {
        rendered();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));assertTrue(host.gateErrorVisible());
        entry("app",new org.json.JSONObject());host.rendered(view,"app",host.navigationGeneration,2);
        assertFalse(host.gateErrorVisible());assertFalse(host.currentGateReady());assertEquals(3,view.frames.size());
        view.complete(1);assertFalse(host.currentGateReady());view.complete(2);assertTrue(host.currentGateReady());assertEquals(0,view.loads);
    }
    @Test public void hiddenCallbackAndWatchAreRetiredBeforeTheNextPresentation(){
        rendered();host.root.setVisibility(View.GONE);view.complete(0);assertEquals(-1,host.visualPending);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));assertFalse(host.gateErrorVisible());assertEquals(1,view.frames.size());
        host.root.setVisibility(View.VISIBLE);host.resumeOverlay();assertEquals(2,view.frames.size());view.complete(1);assertTrue(host.currentGateReady());
        host.resetVisualState("dom");host.requestVisualState();host.pause();
        view.complete(2);assertFalse(host.frameReady);host.resumeOverlay();assertEquals(4,view.frames.size());view.complete(3);assertTrue(host.currentGateReady());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));assertFalse(host.gateErrorVisible());
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
    @Test public void actualDetachInvalidatesFrameWithoutReloadingDocument(){
        rendered();view.complete(0);host.resetVisualState("detach");
        assertFalse(host.frameReady);host.resumeOverlay();assertEquals(2,view.frames.size());
        view.complete(1);assertTrue(host.frameReady);assertEquals(0,view.loads);
    }
    @Test public void timerAndPurposeRevisionsKeepThePreparedEntryVisibleAndInteractive(){
        rendered();view.complete(0);long visual=host.visualGeneration,navigation=host.navigationGeneration;int layouts=view.layouts;
        for(int revision=2;revision<6;revision++){
            host.rendered(view,"app",navigation,revision);
            assertTrue(host.currentGateReady());assertTrue(host.hasPresentedDocument());assertEquals(View.VISIBLE,view.getVisibility());
        }
        assertEquals(visual,host.visualGeneration);assertEquals(1,view.frames.size());assertEquals(layouts,view.layouts);assertEquals(0,view.loads);
    }
    @Test public void duplicateRestoreAndOlderRevisionCannotInvalidateAValidFrame(){
        rendered();view.complete(0);long generation=host.visualGeneration;
        rendered();host.rendered(view,"app",host.navigationGeneration,0);
        assertTrue(host.frameReady);assertEquals(generation,host.visualGeneration);assertEquals(1,view.frames.size());
    }
    @Test public void validatedFrameSurvivesHideButNotDetachOrNewEntry(){
        rendered();assertFalse(host.frameReady);view.complete(0);assertTrue(host.frameReady);
        host.prepareOverlay();host.rendered(view,"app",host.navigationGeneration,2);
        assertTrue(host.currentGateReady());
        host.pause();assertTrue(host.frameReady);
        host.resumeOverlay();assertTrue(host.frameReady);
        host.resetVisualState("detach");assertFalse(host.frameReady);
        host.requestVisualState();view.complete(view.frames.size()-1);assertTrue(host.frameReady);
        entry("app",new org.json.JSONObject());assertFalse(host.frameReady);
        assertEquals(0f,view.getAlpha(),0f);
    }
    @Test public void warmEntryCannotValidateOldSameScreenBeforeItsOwnDomAcknowledgement() throws Exception {
        rendered();view.complete(0);int frames=view.frames.size();long old=host.navigationGeneration;
        entry("app",new org.json.JSONObject());host.prepareOverlay();host.requestVisualState();
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
    @Test public void unchangedPixelsReuseTheFrameOnlyAfterTheNewEntryAcknowledges() throws Exception {
        org.json.JSONObject payload=new org.json.JSONObject().put("gate",new org.json.JSONObject().put("pkg","video"));
        entry("app",payload);host.rendered(view,"app",host.navigationGeneration,1);view.complete(view.frames.size()-1);
        long old=host.navigationGeneration;int frames=view.frames.size();
        entry("app",payload);assertFalse(host.currentGateReady());assertEquals(0f,view.getAlpha(),0);
        host.rendered(view,"app",old,1);assertFalse(host.currentGateReady());
        host.rendered(view,"app",host.navigationGeneration,1);
        assertTrue("reuse="+host.reusableGateRevision+" prepared="+host.preparedGateRevision+" window="+view.getWindowVisibility()+" attached="+view.isAttachedToWindow(),host.currentGateReady());assertEquals(frames,view.frames.size());
        assertEquals(1f,view.getAlpha(),0);assertEquals(0,view.loads);
    }
    @Test public void liveDomChangesCannotReuseTheOlderPreparedPixels() throws Exception {
        rendered();view.complete(0);host.rendered(view,"app",host.navigationGeneration,2);
        entry("app",new org.json.JSONObject());host.rendered(view,"app",host.navigationGeneration,2);
        assertFalse(host.currentGateReady());assertEquals(2,view.frames.size());
        view.complete(1);assertTrue(host.currentGateReady());
    }
    @Test public void aNonVisibleWindowCannotReusePreviouslyPreparedPixels() throws Exception {
        rendered();view.complete(0);entry("app",new org.json.JSONObject());
        view.windowVisibility=View.GONE;host.rendered(view,"app",host.navigationGeneration,1);
        assertFalse(host.currentGateReady());assertEquals(2,view.frames.size());
    }

    @Test public void hiddenDataAcknowledgementDoesNotNeedHiddenOnDraw(){
        host.root.setVisibility(View.GONE);rendered();
        assertTrue(host.currentGateDataApplied());assertFalse(host.currentGateReady());assertEquals(0,view.frames.size());
        host.root.setVisibility(View.VISIBLE);host.resumeOverlay();assertEquals(1,view.frames.size());
        view.complete(0);assertTrue(host.currentGateReady());
    }
    @Test public void rapidEntriesRejectEveryOldCallbackEvenWhenAppReturns() throws Exception {
        entry("app",new org.json.JSONObject());rendered();long first=host.navigationGeneration;
        entry("app",new org.json.JSONObject());host.rendered(view,"app",host.navigationGeneration,2);
        entry("app",new org.json.JSONObject());host.rendered(view,"app",host.navigationGeneration,3);
        view.complete(0);view.complete(1);assertFalse(host.currentGateReady());
        host.rendered(view,"app",first,4);assertEquals(3,host.renderedRevision);
        view.complete(2);assertTrue(host.currentGateReady());
    }

    @Test public void previousRendererResponseCannotResolveARequestInTheReplacementDocument(){
        FrameView old=view;host.answer(old,"1",new org.json.JSONObject(),null);
        host.root.removeView(old);view=new FrameView(old.getContext());host.view=view;host.root.addView(view);old.destroy();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(view.scripts.stream().anyMatch(js->js.startsWith("receive(")));
    }
    @Test public void actualRendererDeathRecreatesOnlyTheDeadWebView(){
        Activity owner=(Activity)view.getContext();host.close();host=new WebHost(owner,WebHost.Role.APP_GATE);owner.setContentView(host.root);
        WebView dead=host.view;assertTrue(dead.getWebViewClient().onRenderProcessGone(dead,null));
        assertTrue(host.gateErrorVisible());assertFalse(host.currentGateReady());
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertNotSame(dead,host.view);
        host.rendered(dead,"app",host.navigationGeneration,99);assertFalse(host.currentGateReady());
    }

}
