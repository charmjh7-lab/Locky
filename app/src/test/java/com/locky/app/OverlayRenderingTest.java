package com.locky.app;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The APP_GATE native cover is immediate; WebView DOM acknowledgement gates input, not pixel exposure. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class OverlayRenderingTest {
    WebHost host;
    ProbeView view;
    static final class ProbeView extends WebView {
        int visualRequests,loads,pauses,resumes;
        final List<String> scripts=new ArrayList<>();
        ProbeView(android.content.Context c){super(c);}
        @Override public void postVisualStateCallback(long id,VisualStateCallback callback){visualRequests++;}
        @Override public void loadUrl(String url){loads++;}
        @Override public void onPause(){pauses++;}
        @Override public void onResume(){resumes++;}
        @Override public void evaluateJavascript(String script,ValueCallback<String> callback){
            scripts.add(script);
            if(callback!=null)callback.onReceiveValue("true");
        }
    }
    @Before public void setup(){
        Store.instance=null;GuardService.live=null;
        Activity owner=Robolectric.buildActivity(Activity.class).setup().visible().get();
        host=new WebHost(owner,WebHost.Role.APP_GATE);
        host.root.removeView(host.view);host.view.destroy();
        view=new ProbeView(owner);host.view=view;
        host.root.addView(view,0,new android.widget.FrameLayout.LayoutParams(-1,-1));
        owner.setContentView(host.root);
        view.setVisibility(View.VISIBLE);
        host.ready=true;host.pendingScreen="app";host.resumeOverlay();
    }
    @After public void cleanup(){
        host.close();host.store.close();Store.instance=null;
    }
    @Test public void acknowledgedEntryDoesNotWaitForChromiumVisualCallback(){
        host.rendered(view,"app",host.navigationGeneration,1);
        assertTrue(host.currentGateReady());
        assertEquals(0,view.visualRequests);
        assertEquals(0,view.loads);
    }
    @Test public void newEntryBlocksOldActionsUntilItsOwnDomAcknowledgement(){
        host.rendered(view,"app",host.navigationGeneration,1);
        long old=host.navigationGeneration;
        host.gateScreen("app",new JSONObject());
        assertFalse(host.currentGateReady());
        host.rendered(view,"app",old,2);
        assertFalse(host.currentGateReady());
        host.rendered(view,"app",host.navigationGeneration,2);
        assertTrue(host.currentGateReady());
        assertEquals(0,view.visualRequests);
    }
    @Test public void invalidRevisionCannotValidateNewGate(){
        host.gateScreen("app",new JSONObject());
        host.rendered(view,"app",host.navigationGeneration,-1);
        assertFalse(host.currentGateReady());
        host.rendered(view,"blocked",host.navigationGeneration,5);
        assertFalse(host.currentGateReady());
    }
    @Test public void hiddenWebViewRetainsItsEngineAndNoPageReload(){
        host.rendered(view,"app",host.navigationGeneration,1);
        host.pause();
        assertEquals(0,view.pauses);
        host.prepareOverlay();
        host.resumeOverlay();
        assertTrue(host.currentGateReady());
        assertEquals(0,view.loads);
        assertEquals(0,view.visualRequests);
    }
    @Test public void rendererFailureStillInvalidatesInputAndAllowsRecovery(){
        host.rendered(view,"app",host.navigationGeneration,1);
        host.showGateError();
        assertFalse(host.currentGateReady());
        assertTrue(host.gateErrorVisible());
    }
}
