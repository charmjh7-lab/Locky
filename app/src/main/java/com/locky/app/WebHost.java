package com.locky.app;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import androidx.webkit.WebViewAssetLoader;
import org.json.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

final class WebHost {
    enum Role { PHONE_START, MAIN, APP_GATE }
    final Role role;
    private JSONObject pendingGate;
    long gateTransition=-1;
    String notifiedGateExposure="";long notifiedGateNavigation=-1,notifiedGateTransition=-1;
    final View loadingView;final boolean isOverlay;
    Runnable frameAvailableCallback;
    final PurposeIcons purposeIcons=new PurposeIcons();
    String purposeEditorPkg="";
    private final BroadcastReceiver packageChanges=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){purposeIcons.invalidate();if(role!=Role.APP_GATE)evaluate("refresh(true)");}};

    static final List<WeakReference<WebHost>> hosts=new CopyOnWriteArrayList<>();
    final android.widget.FrameLayout root;WebView view;final Context context;final Store store;final Handler main=new Handler(Looper.getMainLooper());volatile String pendingScreen="home",pendingChat="",pendingNote="";boolean ready=false,closed=false;
    // DOM completion, Chromium frame preparation and Android drawing are separate states.
    String renderedScreen="",preparedPurposeDay="";long navigationGeneration,renderedNavigation=-1,renderedRevision=-1,visualGeneration,visualPending=-1;
    // frameReady marks the current navigation as prepared, not a compositor fence.
    // presentationActive is a display request, not proof of pixels on screen.
    boolean frameReady,presentationActive,everRendered,loadFailed,visualFailed,renderingActive;int initialRetries;
    Boolean insetKeyboard;
    private Runnable initialWatch,visualWatch;int visualRetryCount;
    // A prepared pixel revision may outlive an entry token. Reuse requires a
    // fresh acknowledgement from the new entry and no intervening DOM changes.
    long preparedGateRevision=-1,reusableGateRevision=-1;String reusableGateScreen="";
    // Bounded Logcat plus opt-in private recording. Never log user content or package names.
    
    static boolean tracing(){return BuildConfig.DEBUG||android.util.Log.isLoggable("LockyGate",android.util.Log.DEBUG);}
    static void beginUnlockTrace(){} // Retired unlock recording.
    static void resumeUnlockTrace(){} // Retired unlock recording.
    static void trace(String event){if(tracing())android.util.Log.d("LockyGate",event);}
    void hostTrace(String event){
        trace("web="+role.name()+" "+event);
        if(isOverlay&&UnlockDiagnostics.live!=null)UnlockDiagnostics.live.gateRecord(gateTransition,event);
    }
    WebHost(Context c,Role owner){UnlockDiagnostics.get(c);context=c;store=Store.get(c);role=owner;boolean overlay=owner==Role.APP_GATE;isOverlay=overlay;if(overlay)pendingScreen="gateIdle";presentationActive=!overlay;root=new android.widget.FrameLayout(c);root.setBackgroundColor(overlay?Color.TRANSPARENT:UiTheme.background(c));hosts.add(new WeakReference<>(this));
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()|androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            int bottom=Math.max(bars.bottom,ime.bottom);
            boolean padding=v.getPaddingLeft()!=bars.left||v.getPaddingTop()!=bars.top||v.getPaddingRight()!=bars.right||v.getPaddingBottom()!=bottom;
            boolean keyboard=insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
            if(padding)v.setPadding(bars.left,bars.top,bars.right,bottom);
            if(padding||insetKeyboard==null||insetKeyboard!=keyboard){insetKeyboard=keyboard;evaluate("insetsChanged("+keyboard+")");}
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){
                hostTrace("view attach observed");
                androidx.core.view.ViewCompat.requestApplyInsets(v);
                main.post(()->{if(closed||!root.isAttachedToWindow())return;applyTheme();
                    if(role==Role.APP_GATE){prepareOverlay();requestVisualState();}
                    else if(root.getVisibility()==View.VISIBLE){resumeEngine();redraw();if(role!=Role.PHONE_START)requestVisualState();}});
            }
            public void onViewDetachedFromWindow(View v){hostTrace("view detach observed");renderingActive=false;resetVisualState("detach");}
        });
        if(overlay){
            android.widget.LinearLayout waiting=new android.widget.LinearLayout(c);waiting.setOrientation(android.widget.LinearLayout.VERTICAL);
            waiting.setGravity(Gravity.CENTER);waiting.setPadding(32,24,32,24);waiting.setBackgroundColor(UiTheme.background(c));
            android.widget.TextView title=new android.widget.TextView(c);title.setText("시작화면을 열지 못했습니다");title.setTextColor(UiTheme.ink(c));title.setTextSize(22);title.setGravity(Gravity.CENTER);waiting.addView(title);
            android.widget.TextView detail=new android.widget.TextView(c);detail.setText("다시 불러오거나 화면을 꺼 주세요.");detail.setTextColor(UiTheme.muted(c));detail.setTextSize(14);detail.setGravity(Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams info=new android.widget.LinearLayout.LayoutParams(-2,-2);info.topMargin=16;waiting.addView(detail,info);
            android.widget.Button retry=new android.widget.Button(c);retry.setText("다시 불러오기");
            android.widget.LinearLayout.LayoutParams retryParams=new android.widget.LinearLayout.LayoutParams(-2,-2);retryParams.topMargin=18;waiting.addView(retry,retryParams);
            retry.setOnClickListener(v->{GuardService g=GuardService.live;if(g!=null&&g.overlay==this&&g.acceptGateErrorInput()){initialRetries=0;retryInitialLoad();}});
            android.widget.Button dismiss=new android.widget.Button(c);dismiss.setText("그냥 끄기");waiting.addView(dismiss,new android.widget.LinearLayout.LayoutParams(-2,-2));
            dismiss.setOnClickListener(v->{GuardService g=GuardService.live;if(g!=null&&g.overlay==this&&g.acceptGateErrorInput()){try{g.stopApp();}catch(IllegalStateException e){android.widget.Toast.makeText(context,e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}}});
            loadingView=waiting;waiting.setVisibility(View.GONE);root.addView(waiting,new android.widget.FrameLayout.LayoutParams(-1,-1));
        }else loadingView=null;
        if(role!=Role.APP_GATE){IntentFilter packages=new IntentFilter();packages.addAction(Intent.ACTION_PACKAGE_ADDED);packages.addAction(Intent.ACTION_PACKAGE_REMOVED);packages.addAction(Intent.ACTION_PACKAGE_REPLACED);packages.addDataScheme("package");if(Build.VERSION.SDK_INT>=33)context.registerReceiver(packageChanges,packages,Context.RECEIVER_NOT_EXPORTED);else context.registerReceiver(packageChanges,packages);}
        createView();
    }
    void applyTheme(){
        root.setBackgroundColor(isOverlay?Color.TRANSPARENT:UiTheme.background(context));
        if(context instanceof Activity&&root.isAttachedToWindow())UiTheme.window((Activity)context);
        if(loadingView instanceof android.widget.LinearLayout){
            loadingView.setBackgroundColor(UiTheme.background(context));
            android.widget.LinearLayout waiting=(android.widget.LinearLayout)loadingView;
            for(int i=0;i<waiting.getChildCount();i++)if(waiting.getChildAt(i) instanceof android.widget.TextView){
                android.widget.TextView label=(android.widget.TextView)waiting.getChildAt(i);
                if(label instanceof android.widget.Button){
                    label.setTextColor(UiTheme.ink(context));
                    label.setBackgroundTintList(android.content.res.ColorStateList.valueOf(UiTheme.surface(context)));
                }else label.setTextColor(i==0?UiTheme.ink(context):UiTheme.muted(context));
            }
        }
        if(view!=null){view.getSettings().setTextZoom(Math.round(context.getResources().getConfiguration().fontScale*100));view.setBackgroundColor(UiTheme.background(context));if(Build.VERSION.SDK_INT>=29)view.setForceDarkAllowed(false);}
        if(ready)evaluate("themeChanged("+UiTheme.dark(context)+");viewportChanged()");
    }
    void resetVisualState(String reason){if(role==Role.PHONE_START)return;if(tracing())hostTrace("frameReady="+frameReady+"->false reason="+reason+" generation="+(visualGeneration+1));if(visualWatch!=null){main.removeCallbacks(visualWatch);visualWatch=null;}++visualGeneration;visualPending=-1;frameReady=false;visualRetryCount=0;preparedGateRevision=-1;reusableGateRevision=-1;reusableGateScreen="";}
    void showLoading(){if(role==Role.PHONE_START){cancelInitialWatch();return;}cancelInitialWatch();resetVisualState("document");renderedNavigation=-1;renderedRevision=-1;renderedScreen="";if(!isOverlay&&loadingView!=null)loadingView.setVisibility(View.GONE);}
    boolean gateErrorVisible(){return isOverlay&&(loadFailed||visualFailed);}
    void showGateError(){
        showGateError(false);
    }
    void showGateError(boolean frameFailure){
        hostTrace(frameFailure?"visual recovery failed; recovery screen requested":"document error; recovery screen requested");
        cancelInitialWatch();loadFailed=loadFailed||!frameFailure;visualFailed=frameFailure&&!loadFailed;resetVisualState("error");
        if(!isOverlay&&loadingView!=null)loadingView.setVisibility(View.VISIBLE);
        if(frameAvailableCallback!=null)frameAvailableCallback.run();
    }
    void rendered(WebView source,String screen,long navigation,long revision){
        if(closed||source!=view||!ready||navigation!=navigationGeneration||revision<0||revision<renderedRevision){if(isOverlay)hostTrace("stale DOM acknowledgement ignored navigation="+navigation);return;}
        if(isOverlay&&!pendingScreen.equals(screen))return;
        if(!isOverlay)pendingScreen=screen;
        if(role==Role.PHONE_START){renderedRevision=revision;renderedScreen=screen;everRendered=true;loadFailed=false;cancelInitialWatch();return;}
        boolean changed=revision!=renderedRevision||!screen.equals(renderedScreen);
        boolean reuse=isOverlay&&revision==reusableGateRevision&&screen.equals(reusableGateScreen)
            &&visualHierarchyAvailable()&&view.getWindowVisibility()==View.VISIBLE;
        // Revisions within an already prepared entry update in place (polling/timers).
        boolean liveUpdate=isOverlay&&currentGateReady()&&screen.equals(renderedScreen);
        if(changed&&!renderedScreen.isEmpty()&&!liveUpdate&&!reuse)resetVisualState("dom");
        hostTrace("data applied navigation="+navigation+" changed="+changed+" revision="+revision);
        renderedNavigation=navigation;renderedRevision=revision;renderedScreen=screen;everRendered=true;loadFailed=false;cancelInitialWatch();
        if(reuse){
            frameReady=true;preparedGateRevision=revision;reusableGateRevision=-1;
            hostTrace("prepared frame reused navigation="+navigation);
            if(frameAvailableCallback!=null)frameAvailableCallback.run();
            return;
        }
        if(!frameReady)requestVisualState();
        if(!isOverlay&&frameReady&&frameAvailableCallback!=null)frameAvailableCallback.run();
    }

    boolean currentGateReady(){return isOverlay&&!loadFailed&&!visualFailed&&frameReady&&renderedNavigation==navigationGeneration&&pendingScreen.equals(renderedScreen)&&!"gateIdle".equals(renderedScreen);}
    boolean currentGateDataApplied(){return isOverlay&&!loadFailed&&!visualFailed&&renderedNavigation==navigationGeneration
        &&pendingScreen.equals(renderedScreen)&&!"gateIdle".equals(renderedScreen);}
    boolean gateNeedsInitialLoad(){return !ready||loadFailed;}
    void gateScreen(String name,JSONObject payload){
        long retained=frameReady&&preparedGateRevision==renderedRevision?preparedGateRevision:-1;
        String retainedScreen=renderedScreen;
        cancelInitialWatch();visualFailed=false;initialRetries=0;resetVisualState("new-entry");
        reusableGateRevision=retained;reusableGateScreen=retainedScreen;
        pendingGate=payload;gateTransition=payload.optLong("gateTransition",-1);preparedPurposeDay=payload.optString("purposeDate");pendingScreen=name;pendingChat="";++navigationGeneration;
        renderedNavigation=-1;renderedRevision=-1;
        hostTrace("data apply requested navigation="+navigationGeneration+" ready="+ready+" engine="+renderingActive+" attached="+view.isAttachedToWindow());
        if(loadFailed){retryInitialLoad();return;}
        if(ready){
            // Keep the single loaded document. Only a truly paused renderer
            // needs an invalidation; already-active surfaces are left alone.
            boolean resumed=resumeEngine();
            dispatchScreen();
            if(resumed)invalidateFrame();
        }else watchInitialLoad();
    }
    // This only informs JavaScript; GuardService remains the exposure/input owner.
    void notifyGateExposure(GuardService.Exposure exposure){
        if(!isOverlay||closed||!ready||pendingGate==null)return;
        String name=exposure.name();long navigation=navigationGeneration,transition=gateTransition;
        if(name.equals(notifiedGateExposure)&&navigation==notifiedGateNavigation&&transition==notifiedGateTransition)return;
        notifiedGateExposure=name;notifiedGateNavigation=navigation;notifiedGateTransition=transition;
        JSONObject data=pendingGate.optJSONObject("gate");String pkg=data==null?"":data.optString("pkg");
        evaluate("gateExposureChanged("+JSONObject.quote(name)+","+navigation+","+transition+","+JSONObject.quote(pkg)+")");
    }
    // Data-only entry refresh for a phone locked across midnight. No window, loading,
    // readiness gate or WebView lifecycle changes; a visible page never runs this on a timer.
    void ensurePurposeDay(){
        String today=java.time.LocalDate.now().toString();if(closed||!ready||!Set.of("home","unlock","app").contains(renderedScreen)||today.equals(preparedPurposeDay))return;
        try{String scope="app".equals(renderedScreen)&&GuardService.live!=null?GuardService.live.gatePkg:"phone";
            JSONObject order=new JSONObject().put("date",today).put("scope",scope).put("purposes",store.purposes(scope));preparedPurposeDay=today;evaluate("prepareStart("+order+")");
        }catch(JSONException e){android.util.Log.w("LockyBriefing","Daily purpose data unavailable");}
    }
    void redraw(){
        if(closed||view==null)return;
        root.requestLayout();view.requestLayout();invalidateFrame();
    }
    void invalidateFrame(){if(!closed&&view!=null){view.invalidate();root.postInvalidateOnAnimation();}}
    boolean hasPresentedDocument(){return presentationActive&&renderingActive&&view!=null&&view.isAttachedToWindow()
        &&root.getVisibility()==View.VISIBLE&&view.getVisibility()==View.VISIBLE&&pendingScreen.equals(renderedScreen);}
    void resumeOverlay(){if(isOverlay)resumeRendering();}
    void resume(){if(role==Role.PHONE_START){if(!closed&&view!=null&&resumeEngine())invalidateFrame();}else if(!isOverlay)resumeRendering();}
    private boolean resumeEngine(){
        if(renderingActive)return false;
        hostTrace("engine resume requested");view.onResume();view.resumeTimers();renderingActive=true;return true;
    }
    private void resumeRendering(){
        if(closed||view==null)return;
        boolean exposing=!presentationActive;
        if(exposing)presentationActive=true;
        boolean resumed=resumeEngine();
        if(resumed)redraw(); // A genuinely paused surface still needs the white-screen safeguard.
        if(frameReady){
            // The validated document stays active. Policy unhide may reuse its buffer;
            // do not force another draw/layout/Chromium callback to mark input active.
            return;
        }
        if(!ready){watchInitialLoad();return;}
        if(pendingScreen.equals(renderedScreen))requestVisualState();
        else if(!everRendered)watchInitialLoad();
    }
    void pause(){
        if(role==Role.PHONE_START){if(view!=null&&renderingActive){view.onPause();renderingActive=false;}return;}
        presentationActive=false;
        if(isOverlay){suspendVisualRequest();return;}
        boolean invalidate=renderingActive||frameReady||visualPending!=-1;
        if(invalidate)resetVisualState("pause");
        if(view!=null&&renderingActive){hostTrace("engine pause requested");view.onPause();renderingActive=false;}
    }
    void prepareOverlay(){
        if(closed||!isOverlay||view==null)return;
        // Prewarm the engine once. Concealing content never pauses it or invalidates DOM.
        resumeEngine();
        if(!ready&&!loadFailed)watchInitialLoad();
    }
    void cancelVisualWatch(){if(visualWatch!=null){main.removeCallbacks(visualWatch);visualWatch=null;}}
    void suspendVisualRequest(){
        cancelVisualWatch();
        // Invalidate outstanding callbacks as well as their watchdog, retaining a ready frame.
        if(visualPending!=-1){++visualGeneration;visualPending=-1;}
        visualRetryCount=0;
    }
    boolean currentGateEntry(long transition){
        return !isOverlay||presentationActive&&(!(context instanceof GuardService)
            ||((GuardService)context).canPrepareGateFrame(this,transition));
    }
    boolean visualHierarchyAvailable(){
        return view!=null&&view.isAttachedToWindow()&&root.isAttachedToWindow()
            &&(isOverlay?root.getVisibility()==View.VISIBLE:root.isShown());
    }
    void requestVisualState(){
        if(role==Role.PHONE_START)return;
        if(closed||!ready||loadFailed||visualFailed||!visualHierarchyAvailable()||!currentGateEntry(gateTransition)
            ||(isOverlay&&renderedNavigation!=navigationGeneration)
            ||!pendingScreen.equals(renderedScreen)||frameReady||visualPending==visualGeneration)return;
        // Keep preparing the attached document, including while its dictionary Activity is paused.
        WebView candidate=view;String screen=pendingScreen;long generation=visualGeneration,navigation=navigationGeneration,transition=gateTransition,revision=renderedRevision;
        cancelVisualWatch();visualPending=generation;hostTrace("visual callback requested generation="+generation+" navigation="+navigation);
        // A delayed callback retries frame preparation, never readiness by timeout.
        visualWatch=()->{
            visualWatch=null;
            if(closed||candidate!=view||generation!=visualGeneration||navigation!=navigationGeneration
                ||frameReady||visualPending!=generation||revision!=renderedRevision||!screen.equals(pendingScreen))return;
            if(!visualHierarchyAvailable()||!currentGateEntry(transition)){suspendVisualRequest();return;}
            hostTrace("visual callback delayed navigation="+navigation);
            if(isOverlay){
                // Reuse a healthy attached document. Retry this navigation once, then
                // expose the existing native error actions rather than waiting forever.
                if(!ready||loadFailed||!candidate.isAttachedToWindow()||!currentGateDataApplied()
                    ||visualRetryCount++>=1){showGateError(true);return;}
                hostTrace("visual recovery retry navigation="+navigation);
                ++visualGeneration;visualPending=-1;invalidateFrame();requestVisualState();return;
            }
            visualPending=-1;redraw();
            if(visualRetryCount++>0){
                frameReady=true;candidate.setVisibility(View.VISIBLE);invalidateFrame();
                if(frameAvailableCallback!=null)frameAvailableCallback.run();
            }else requestVisualState();
        };
        main.postDelayed(visualWatch,1200);
        candidate.postVisualStateCallback(generation,new WebView.VisualStateCallback(){@Override public void onComplete(long id){
            hostTrace("visual callback observed generation="+id+" current="+visualGeneration);
            if(closed||candidate!=view||id!=generation||generation!=visualGeneration||navigation!=navigationGeneration
                ||revision!=renderedRevision||transition!=gateTransition||!screen.equals(pendingScreen)){hostTrace("stale visual callback ignored navigation="+navigation);return;}
            // Rendering can start without policy I/O. Before promoting the
            // current frame to visible, recheck management and the actual app.
            if(!visualHierarchyAvailable()||!currentGateEntry(transition)
                ||(isOverlay&&context instanceof GuardService
                    &&!((GuardService)context).currentGateEntry(WebHost.this,transition))){
                suspendVisualRequest();return;
            }
            if(frameReady)return;
            if(visualWatch!=null){main.removeCallbacks(visualWatch);visualWatch=null;}
            if(isOverlay&&visualRetryCount>0)hostTrace("visual recovery succeeded navigation="+navigation);
            visualPending=-1;visualRetryCount=0;frameReady=true;if(isOverlay)preparedGateRevision=revision;if(tracing())hostTrace("visual-ready observed generation="+generation);
            // Chromium guarantees that a subsequent draw can contain this DOM revision.
            // Neither this callback nor onDraw is a fence for the system compositor.
            // GuardService exposes the already-visible WebView through window alpha
            // synchronously, and independently enables only the current entry's input.
            if(isOverlay){if(frameAvailableCallback!=null)frameAvailableCallback.run();}
            else {candidate.setVisibility(View.VISIBLE);if(loadingView!=null)loadingView.setVisibility(View.GONE);if(frameAvailableCallback!=null)frameAvailableCallback.run();}
            invalidateFrame();
        }});
        // DOM layout belongs to Chromium. An attached view with unchanged bounds only
        // needs a draw here; attach/resume and watchdog recovery still request layout.
        invalidateFrame();
    }
    private void cancelInitialWatch(){if(initialWatch!=null){main.removeCallbacks(initialWatch);initialWatch=null;}}
    private void watchInitialLoad(){
        if(closed||everRendered||initialWatch!=null)return;
        WebView candidate=view;long navigation=navigationGeneration;
        initialWatch=()->{
            initialWatch=null;if(closed||candidate!=view||navigation!=navigationGeneration||everRendered)return;
            // Missing onPageFinished is recoverable too. JS liveness is not treated as a painted frame.
            candidate.evaluateJavascript("typeof nativeRestore==='function'",result->{
                if(closed||candidate!=view||navigation!=navigationGeneration||everRendered)return;
                if("true".equals(result)){if(!ready){ready=true;applyTheme();dispatchScreen();}else candidate.evaluateJavascript("nativeRestore()",null);}
                else if(isOverlay)showGateError();
                else {loadFailed=true;retryInitialLoad();if(frameAvailableCallback!=null)frameAvailableCallback.run();}
            });
            // Only an untouched, uninitialised document can be retried automatically, once.
            if(!isOverlay&&!ready&&initialRetries==0)main.postDelayed(()->{if(!closed&&candidate==view&&!ready&&!everRendered)retryInitialLoad();},500);
        };
        main.postDelayed(initialWatch,6000);
    }
    void retryInitialLoad(){
        if(closed||view==null)return;
        hostTrace("document retry requested failed="+loadFailed);
        if(visualFailed){
            // A frame timeout is not a broken HTML document.
            visualFailed=false;resetVisualState("visual-retry");
            hostTrace("visual recovery user retry navigation="+navigationGeneration);
            if(frameAvailableCallback!=null)frameAvailableCallback.run();
            invalidateFrame();
            if(!ready)watchInitialLoad();else if(renderedNavigation!=navigationGeneration)dispatchScreen();else requestVisualState();
            return;
        }
        if(everRendered&&!loadFailed){redraw();requestVisualState();return;}
        if(ready&&!loadFailed){view.evaluateJavascript("nativeRestore()",null);return;}
        if(initialRetries++>0)return;
        ready=false;everRendered=false;loadFailed=false;cancelInitialWatch();showLoading();
        if(isOverlay&&frameAvailableCallback!=null)frameAvailableCallback.run();view.stopLoading();
        hostTrace("document load requested");view.loadUrl("https://appassets.androidplatform.net/assets/index.html");watchInitialLoad();
    }
    void createView(){
        if(closed)return;
        renderingActive=false;insetKeyboard=null;loadFailed=false;visualFailed=false;if(isOverlay){notifiedGateExposure="";resetVisualState("create");}
        view=new WebView(context){
            @Override protected void onWindowVisibilityChanged(int visibility){
                super.onWindowVisibilityChanged(visibility);
                hostTrace("window visibility observed="+visibility+" attached="+isAttachedToWindow()+" accelerated="+isHardwareAccelerated());
                if(isOverlay&&WebHost.this.view==this&&visibility!=View.VISIBLE){preparedGateRevision=-1;reusableGateRevision=-1;}
            }
            @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){
                super.onSizeChanged(width,height,oldWidth,oldHeight);
                if(isOverlay&&WebHost.this.view==this&&(width!=oldWidth||height!=oldHeight)){preparedGateRevision=-1;reusableGateRevision=-1;}
            }
        };
        // Keep MainActivity's existing renderer workaround; PhoneStartActivity uses
        // the ordinary hardware-accelerated Activity window.
        if(role==Role.MAIN)view.setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        root.addView(view,0,new android.widget.FrameLayout.LayoutParams(-1,-1));
        // GuardService creates the window at alpha=0 before attachment. Keeping
        // this VISIBLE prevents a second, contradictory Chromium lifecycle.
        WebSettings s=view.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setMediaPlaybackRequiresUserGesture(true);s.setSupportMultipleWindows(false);
        if(isOverlay)s.setOffscreenPreRaster(true); // App gate only; phone rendering belongs to Activity.
        applyTheme();
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(context)).build();
        view.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){return loader.shouldInterceptRequest(r.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public void onReceivedError(WebView v,WebResourceRequest request,WebResourceError error){if(v==view&&request.isForMainFrame()){
                if(isOverlay)showGateError();else if(!everRendered){loadFailed=true;watchInitialLoad();if(frameAvailableCallback!=null)frameAvailableCallback.run();}
            }}
            @Override public void onPageFinished(WebView v,String url){if(closed||v!=view||loadFailed||ready)return;ready=true;applyTheme();if(insetKeyboard!=null)evaluate("insetsChanged("+insetKeyboard+")");dispatchScreen();}
            @Override public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail detail){
                if(v!=view)return true;
                hostTrace("renderer gone observed; recovery requested");
                // Recreation is reserved for an actually dead renderer, never a slow visual callback.
                cancelInitialWatch();ready=false;everRendered=false;loadFailed=false;initialRetries=0;renderedNavigation=-1;renderedRevision=-1;renderedScreen="";
                if(isOverlay)showGateError();else showLoading();
                root.removeView(v);v.removeJavascriptInterface("Native");v.destroy();if(!closed)main.post(WebHost.this::createView);return true;
            }
        });
        view.addJavascriptInterface(new Bridge(view),"Native");view.setOnKeyListener((v,key,e)->{if(key==KeyEvent.KEYCODE_BACK&&e.getAction()==KeyEvent.ACTION_UP){if(role==Role.PHONE_START)((PhoneStartActivity)context).onBackPressed();else evaluate("back()");return true;}return false;});
        if(isOverlay&&frameAvailableCallback!=null)frameAvailableCallback.run();
        hostTrace("document load requested");view.loadUrl("https://appassets.androidplatform.net/assets/index.html");watchInitialLoad();
    }
    void openNote(String id){pendingNote=id;screen("notes");}
    void openChat(String id){pendingChat=id;screen("chat");}
    void screen(String name){
        pendingScreen=name;if(!"chat".equals(name))pendingChat="";++navigationGeneration;showLoading();
        if(ready)dispatchScreen();watchInitialLoad();
    }
    void presented(WebView source,String screen,long previous,long navigation,String chat){
        if(closed||source!=view||!ready||previous!=navigationGeneration||navigation!=previous+1
            ||!Set.of("chat","chats","unlock").contains(screen))return;
        if(role==Role.APP_GATE)return;
        // JS has already built the next screen in this document. Do not navigate/load,
        // hide the WebView or show the native loading surface. rendered(revision) owns validation.
        navigationGeneration=navigation;pendingScreen=screen;pendingChat="chat".equals(screen)?chat:"";
        cancelInitialWatch();

    }
    private void dispatchScreen(){
        WebView candidate=view;long navigation=navigationGeneration;String name=pendingScreen,chat=pendingChat;JSONObject gateData=pendingGate;
        Runnable dispatch=()->{
            if(closed||!ready||candidate!=view||navigation!=navigationGeneration)return;
            String navJs="notes".equals(name)&&!pendingNote.isEmpty()?"openNoteById("+JSONObject.quote(pendingNote)+","+navigation+")":"chat".equals(name)&&!chat.isEmpty()?"openChatById("+JSONObject.quote(chat)+","+navigation+")":"navigate("+JSONObject.quote(name)+","+navigation+")";
            if(role==Role.APP_GATE)navJs=gateData==null?"prepareGate("+navigation+")":"presentGate("+JSONObject.quote(name)+","+gateData+","+navigation+")";
            if(role==Role.APP_GATE&&gateData!=null&&!everRendered)navJs="prepareGate("+navigation+");"+navJs;
            if(!purposeEditorPkg.isEmpty()&&"settings".equals(name))navJs="openPurposeManager("+JSONObject.quote(purposeEditorPkg)+","+navigation+")";
            if(name.startsWith("brief|"))navJs="openBriefRoute("+JSONObject.quote(name)+","+navigation+")";
            if(isOverlay){
                // Detect actual script initialization/entry failure independently of loading.
                candidate.evaluateJavascript("(function(){try{"+navJs+";return true;}catch(e){return false;}})()",result->{
                    if(!closed&&candidate==view&&navigation==navigationGeneration&&"false".equals(result))showGateError();
                });
                if(context instanceof GuardService)notifyGateExposure(((GuardService)context).exposure);
            }else candidate.evaluateJavascript(navJs,null);
        };
        if(isOverlay&&Looper.myLooper()==Looper.getMainLooper())dispatch.run();else main.post(dispatch);
    }
    void evaluate(String js){WebView candidate=view;main.post(()->{if(!closed&&ready&&candidate==view)candidate.evaluateJavascript(js,null);});}
    void close(){if(role!=Role.APP_GATE)try{context.unregisterReceiver(packageChanges);}catch(IllegalArgumentException ignored){}closed=true;ready=false;resetVisualState("close");main.removeCallbacksAndMessages(null);root.removeView(view);view.removeJavascriptInterface("Native");view.destroy();hosts.removeIf(r->r.get()==null||r.get()==this);}
    void refresh(){evaluate(isOverlay?"refresh(true)":"refresh()");}
    static void refreshAll(){for(var r:hosts){WebHost h=r.get();if(h!=null)h.refresh();}}
    static JSONObject dictionaryLaunched(){try{return new JSONObject().put("launched",true);}catch(JSONException e){throw new IllegalStateException(e);}}
    void answer(WebView source,String id,Object value,String error){
        String js="receive("+JSONObject.quote(id)+","+(value==null?"null":value.toString())+","+(error==null?"null":JSONObject.quote(error))+")";
        if(!isOverlay){evaluate(js);return;}
        main.post(()->{if(!closed&&ready&&source==view)source.evaluateJavascript(js,null);});
    }
    static void refreshGeneralHosts(){for(var r:hosts){WebHost h=r.get();if(h!=null&&h.role!=Role.APP_GATE)h.refresh();}}
    final class Bridge {
        final WebView source;
        Bridge(WebView source){this.source=source;}
        @JavascriptInterface public long phoneCycle(){return role==Role.PHONE_START?((PhoneStartActivity)context).cycle:0;}
        @JavascriptInterface public boolean darkTheme(){return UiTheme.dark(context);}
        @JavascriptInterface public void request(String id,String action,String raw){
            if(closed||source!=view)return;
            if(!action.matches("[A-Za-z]+")||raw.length()>1000000){answer(source,id,null,"요청을 처리할 수 없습니다.");return;}
            if("releaseNavigation".equals(action)){main.post(()->{if(role==Role.PHONE_START){try{JSONObject input=new JSONObject(raw);PhoneStartActivity phone=(PhoneStartActivity)context;if(input.optLong("cycle",-1)==phone.cycle)phone.releaseNavigation(input.optString("reason","tool"));}catch(JSONException ignored){}}});return;}
            if("editorFocus".equals(action)){main.post(()->{try{GuardService g=GuardService.live;validateGateAction(new JSONObject(raw),g);if(isOverlay&&g!=null)g.requestEditorInput();}catch(Exception ignored){}});return;}
            if("rendered".equals(action)){main.post(()->{try{JSONObject frame=new JSONObject(raw);rendered(source,frame.optString("screen"),frame.optLong("navigation",-1),frame.optLong("revision",-1));}catch(JSONException ignored){}});return;}
            if("present".equals(action)){main.post(()->{try{JSONObject frame=new JSONObject(raw);presented(source,frame.optString("screen"),frame.optLong("previous",-1),frame.optLong("navigation",-1),frame.optString("chat"));}catch(JSONException ignored){}});return;}
            if((isOverlay&&Set.of("purpose","removePurpose").contains(action))||Set.of("choose","finishPurposeEditing","grant","extend","stop","utility","open","permission","dictionary","linkTest","signin","cancelLogin","exit","dismiss").contains(action))main.post(()->handle(source,id,action,raw));
            else if(Set.of("brief","copyDay","saveDay").contains(action))LockyApp.app.backgroundWork.execute(()->handle(source,id,action,raw));
            else LockyApp.app.work.execute(()->handle(source,id,action,raw));
        }
    }
    void handle(String id,String action,String raw){handle(view,id,action,raw);}
    void handle(WebView source,String id,String action,String raw){if(closed||isOverlay&&source!=view)return;try {
        JSONObject a=new JSONObject(raw);Object out=new JSONObject();GuardService g=GuardService.live;
        if(isOverlay&&Set.of("grant","extend","stop","dismiss","choose","purpose","removePurpose","linkTest","open").contains(action))validateGateAction(a,g);
        switch(action){
            case "state":out=state();break;
            case "diagnostics":out=new JSONObject().put("text",UnlockDiagnostics.get(context).text()).put("status",UnlockDiagnostics.get(context).status());break;
            case "clearDiagnostics":UnlockDiagnostics.get(context).clear();out=UnlockDiagnostics.get(context).status();break;
            case "saveDiagnostics":{
                if(!(context instanceof MainActivity))throw new IllegalStateException("로키 앱의 설정에서 진단 기록을 내보내 주세요.");
                String token=RawExport.stage(context,UnlockDiagnostics.get(context).text());
                String filename="Locky-app-gate-"+java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+".txt";
                main.post(()->((MainActivity)context).saveDiagnosticsToDocument(filename,token));break;
            }
            case "tick":if(isOverlay){out=gateResponseIdentity(new JSONObject(),g).put("gate",g==null?new JSONObject():g.state()).put("free",!store.managed(System.currentTimeMillis()));break;}out=new JSONObject().put("gate",g==null?new JSONObject():g.state()).put("free",!store.managed(System.currentTimeMillis())).put("busy",LockyApp.app.chat.busy.get()).put("activeChat",LockyApp.app.chat.active).put("chatError",LockyApp.app.chat.error).put("connection",LockyApp.app.auth.connection());break;
            case "finishPurposeEditing":if(context instanceof MainActivity)((MainActivity)context).finishPurposeEditing();break;
            case "apps":out=apps();break;
            case "usageApps":out=usageApps();break;
            case "excludeUsage":{Set<String> excluded=new HashSet<>(store.settings.getStringSet("usage-excluded",Set.of()));String target=a.getString("pkg");if(a.getBoolean("excluded"))excluded.add(target);else excluded.remove(target);store.settings.edit().putStringSet("usage-excluded",excluded).apply();if(g!=null)g.policyChanged();break;}
            case "purpose":out=store.purpose(a,a.optString("scope","phone"));break;
            case "removePurpose":store.getWritableDatabase().delete("purposes","id=?",new String[]{a.getString("id")});break;
            case "choose":if(role==Role.PHONE_START)((PhoneStartActivity)context).choose(resolvePurpose(a));else if(role==Role.APP_GATE)throw new IllegalStateException("앱 사용시간을 선택해 주세요.");else {JSONObject p=resolvePurpose(a);store.event("unlock",p.getString("label"),"",p.optString("pkg"),0,p.optInt("aimless",0)==1);Intent i=Links.intent(context,p.optString("pkg"),p.optString("uri"));if(i!=null)context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));else if(context instanceof MainActivity)((MainActivity)context).moveTaskToBack(true);}break;
            case "grant":if(g==null)throw new IllegalStateException("접근성 서비스를 먼저 켜 주세요.");g.allowApp(resolveGatePurpose(a,g),a.optInt("minutes",5));break;
            case "extend":if(g!=null)g.extend(a.optInt("minutes",5));break;
            case "stop":if(g!=null)g.stopApp();break;
            case "utility":if(g!=null)g.useUtility(a.getString("name"));else store.event("utility",a.getString("name"),context.getPackageName(),"",0);break;
            case "open":if(role==Role.PHONE_START&&!Set.of("unlock","notes","chats","chat").contains(a.optString("screen"))){openMain(a.getString("screen"));}else if(isOverlay&&g!=null)g.openMain(a.getString("screen"),a.optString("chat"));else if(a.optString("screen").equals("chat")&&!a.optString("chat").isEmpty())openChat(a.getString("chat"));else screen(a.getString("screen"));break;
            case "dismiss":if(role==Role.PHONE_START)((PhoneStartActivity)context).dismiss();else if(isOverlay&&g!=null)g.stopApp();else if(context instanceof MainActivity)((MainActivity)context).moveTaskToBack(true);break;
            case "remember":if(!a.optString("note").isEmpty())store.settings.edit().putString("recent-note",a.optString("note")).apply();if(role==Role.PHONE_START){if(!((PhoneStartActivity)context).chosen)store.settings.edit().putString("phone-start-session",a.toString()).apply();pendingScreen=a.optString("screen","unlock");pendingChat="chat".equals(pendingScreen)?a.optString("chat"):"";}else if(!isOverlay){if(a.has("briefTarget"))store.settings.edit().putString("last-brief",a.getJSONObject("briefTarget").toString()).apply();pendingScreen=a.optString("screen","home");store.settings.edit().putString("chat-return",a.optString("chatReturn","chats")).putString("chats-return",a.optString("chatsReturn","home")).putString("last-screen",a.optString("screen","home")).putString("last-chat",a.optString("chat")).putString("last-note",a.optString("note")).apply();}break;
            case "dictionaries":out=Links.dictionaries(context);break;
            case "dictionaryChoice":store.settings.edit().putString("dictionary",a.getString("id")).apply();break;
            case "exit":if(role==Role.PHONE_START)((PhoneStartActivity)context).moveTaskToBack(true);else if(context instanceof MainActivity)((MainActivity)context).moveTaskToBack(true);break;
            case "dictionary":
                if(role==Role.PHONE_START){((PhoneStartActivity)context).dictionary(a.getString("word"));out=dictionaryLaunched();break;}
                Links.dictionary(context,a.getString("word"));
                if(g!=null)g.useUtility("사전");else store.event("utility","사전",context.getPackageName(),"",0);
                out=dictionaryLaunched();break;
            case "linkTest":Intent link=Links.intent(context,a.optString("pkg"),a.optString("uri"));if(link==null)throw new IllegalArgumentException("이 앱에서 지원하는 연결인지 확인해 주세요. 딥링크는 앱마다 달라요.");if(isOverlay&&g!=null)g.hideOverlay();context.startActivity(link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));break;
            case "saveNote":out=store.note(a);store.settings.edit().putString("recent-note",a.optString("id")).apply();NoteWidget.refreshAll(context);break;
            case "deleteNote":store.getWritableDatabase().delete("notes","id=?",new String[]{a.getString("id")});NoteWidget.refreshAll(context);break;
            case "settings":if(g!=null)g.flushOnMain();saveSettings(a);if(a.has("themeMode"))main.post(()->{for(var ref:hosts){WebHost h=ref.get();if(h!=null&&!h.closed)h.applyTheme();}});if(g!=null)g.policyChanged();LockyApp.app.backgroundWork.execute(()->Scheduler.fire(context));break;
            case "rule":String pkg=a.getString("pkg");if(pkg.equals(context.getPackageName()))throw new IllegalArgumentException("Locky는 관리 대상으로 지정할 수 없습니다.");String mode=a.optString("mode");JSONObject prior=store.rule(pkg);
                String key="rule-memory:"+pkg;JSONObject memory=new JSONObject(store.settings.getString(key,"{}"));if(mode.equals("restore"))mode=memory.optString("mode","limited");
                if(mode.equals("none")){if(prior!=null)memory=prior;store.getWritableDatabase().delete("rules","pkg=?",new String[]{pkg});store.settings.edit().remove("blocked-until:"+pkg).putString(key,memory.toString()).apply();}
                else {if(!mode.equals("limited")&&!mode.equals("locked"))throw new IllegalArgumentException("관리 방식을 확인해 주세요.");ContentValues v=new ContentValues();v.put("pkg",pkg);v.put("label",UsageReader.label(context,pkg));v.put("mode",mode);int limit=Rules.dailyMinutes(a.optInt("minutes",memory.optInt("minutes",60)));v.put("minutes",limit);store.put("rules",v);
                    var editor=store.settings.edit().putString(key,new JSONObject().put("mode",mode).put("minutes",limit).toString());
                    if(prior==null||!prior.optString("mode").equals(mode)||limit>prior.optInt("minutes",60))editor.remove("blocked-until:"+pkg);editor.apply();
                }
                JSONArray seen=new JSONArray(store.settings.getString("rule-seen","[]"));boolean found=false;for(int j=0;j<seen.length();j++)if(seen.getString(j).equals(pkg))found=true;if(!found){seen.put(pkg);store.settings.edit().putString("rule-seen",seen.toString()).apply();}if(g!=null)g.policyChanged();break;
            case "brief":{
                if(a.optString("kind").equals("focus"))out=Briefing.focusResult(context,a.getString("key"));
                else {long offset=a.has("date")?java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(a.getString("date")),java.time.LocalDate.now()):a.optLong("offset",0);out=Briefing.build(context,a.optInt("days",1)==7?7:1,Math.max(0,Math.min(179,offset)));}break;
            }
            case "purposeOrder":{String scope=a.optString("scope","phone");out=new JSONObject().put("date",java.time.LocalDate.now().toString()).put("scope",scope).put("purposes",store.purposes(scope));break;}
            case "copyDay":{
                String exportText=Briefing.rawDay(context,Math.max(0,Math.min(179,a.optInt("offset",0)))).toString(2);
                main.post(()->{try{
                    android.content.ClipboardManager clipboard=context.getSystemService(android.content.ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Locky 하루 기록",exportText));
                    answer(source,id,new JSONObject().put("ok",true),null);
                }catch(Exception e){answer(source,id,null,ChatGPT.safeError(e));}});
                return;
            }
            case "saveDay":{
                if(!(context instanceof MainActivity))throw new IllegalStateException("로키 앱에서 브리핑을 열어 주세요.");
                JSONObject data=Briefing.rawDay(context,Math.max(0,Math.min(179,a.optInt("offset",0))));
                String filename="Locky-"+data.getString("date")+"-raw.json";
                String token=RawExport.stage(context,data.toString(2));
                ((MainActivity)context).runOnUiThread(()->((MainActivity)context).saveRawToDocument(filename,token));
                break;
            }
            case "newChat":out=new JSONObject().put("id",store.newChat());break;
            case "quickSend":out=LockyApp.app.chat.quickSend(a.getString("request"),a.getString("text"),store.settings.getString("model",""),a.optBoolean("retry"));break;
            case "messages":out=store.messages(a.getString("id"));break;
            case "deleteChat":if(LockyApp.app.chat.busy.get()&&LockyApp.app.chat.active.equals(a.getString("id")))throw new IllegalStateException("답변을 멈춘 후 삭제해 주세요.");store.deleteChat(a.getString("id"));break;
            case "send":LockyApp.app.chat.send(a.getString("id"),a.getString("text"),store.settings.getString("model",""));break;
            case "cancelResponse":LockyApp.app.auth.cancel();break;
            case "models":out=LockyApp.app.auth.models();store.settings.edit().putString("models",out.toString()).apply();break;
            case "account":LockyApp.app.auth.select(a.getString("client"));store.settings.edit().putString("model","").putString("models","[]").apply();break;
            case "signout":out=new JSONObject().put("message",LockyApp.app.auth.signOut());store.settings.edit().putString("model","").putString("models","[]").apply();break;
            case "signin":if(role!=Role.MAIN){openMain("chats");break;}LockyApp.app.auth.signIn(a.optString("client"),a.optBoolean("consent",false),new ChatGPT.SignIn(){public void browser(String url){if(context instanceof MainActivity)((MainActivity)context).loginBrowser(url);}public void progress(){refreshAll();}public void success(){main.post(()->{if(context instanceof MainActivity)((MainActivity)context).releaseLoginBrowser();});LockyApp.app.work.execute(()->{try{JSONArray models=LockyApp.app.auth.models();store.settings.edit().putString("models",models.toString()).apply();if(models.length()>0)store.settings.edit().putString("model",models.getJSONObject(0).getString("slug")).apply();}catch(Exception ignored){}refreshAll();});}public void error(String error){main.post(()->{if(context instanceof MainActivity)((MainActivity)context).releaseLoginBrowser();});refreshAll();}});break;
            case "cancelLogin":LockyApp.app.auth.cancelLogin();if(context instanceof MainActivity)((MainActivity)context).releaseLoginBrowser();break;
            case "permission":permission(a.getString("name"));break;
            default:throw new IllegalArgumentException("지원하지 않는 요청입니다.");
        }
        // Real shared-data writes, rather than Activity/visibility callbacks,
        // notify the other document. Start pages apply only changed regions.
        if(Set.of("purpose","removePurpose","saveNote","deleteNote").contains(action))
            for(var ref:hosts){WebHost peer=ref.get();if(peer!=null&&peer!=this)peer.refresh();}
        answer(source,id,out,null);
    }catch(Exception e){answer(source,id,null,ChatGPT.safeError(e));}}
    JSONObject resolvePurpose(JSONObject a) throws Exception {if(a.optBoolean("transient",false))return Store.purposeData(a,a.optString("scope","phone"));if(a.has("label"))return store.purpose(a,a.optString("scope","phone"));JSONArray p=store.rows("SELECT * FROM purposes WHERE id=?",a.getString("id"));if(p.length()==0)throw new IllegalStateException("사용 목적을 다시 선택해 주세요.");return p.getJSONObject(0);}
    void saveSettings(JSONObject a) throws Exception {
        if(a.has("appGateDiagnostics"))UnlockDiagnostics.get(context).setEnabled(a.getBoolean("appGateDiagnostics"));
        var e=store.settings.edit();
        if(a.has("floatingTimer"))e.putBoolean("floatingTimer",a.getBoolean("floatingTimer"));
        if(a.has("themeMode")){String value=a.getString("themeMode");if(!Set.of("system","light","dark").contains(value))throw new IllegalArgumentException("화면 테마를 다시 선택해 주세요.");e.putString("theme-mode",value);}
        if(a.has("start")||a.has("end")){int start=a.optInt("start",store.settings.getInt("start",540)),end=a.optInt("end",store.settings.getInt("end",1260));if(start<0||start>1439||end<0||end>1439||start==end)throw new IllegalArgumentException("서로 다른 시작·종료 시간을 선택해 주세요.");}
        if(a.has("reminders")){JSONArray input=a.getJSONArray("reminders"),values=new JSONArray();Set<Integer> seen=new HashSet<>();for(int i=0;i<input.length();i++){int m=input.getInt(i);if(m>=1&&m<=180&&seen.add(m))values.put(m);}e.putString("reminders",values.toString());}
        if(a.has("weeklyDay"))e.putInt("weeklyDay",Math.max(1,Math.min(7,a.getInt("weeklyDay"))));
        if(a.has("dailyBrief"))e.putBoolean("dailyBrief",a.getBoolean("dailyBrief"));
        if(a.has("focusBrief"))e.putBoolean("focusBrief",a.getBoolean("focusBrief"));
        if(a.has("dailyBriefTime")){int minute=a.getInt("dailyBriefTime");if(minute<0||minute>1439)throw new IllegalArgumentException("알림 시각을 확인해 주세요.");e.putInt("dailyBriefTime",minute);}
        if(a.has("model")){String model=a.getString("model");JSONArray models=new JSONArray(store.settings.getString("models","[]"));boolean valid=false;for(int i=0;i<models.length();i++)if(models.getJSONObject(i).getString("slug").equals(model))valid=true;if(!valid)throw new IllegalArgumentException("연결된 계정의 모델을 선택해 주세요.");e.putString("model",model);}
        if(a.has("enabled")||a.has("start")||a.has("end"))store.saveFocus(a.optBoolean("enabled",store.settings.getBoolean("enabled",true)),a.optInt("start",store.settings.getInt("start",540)),a.optInt("end",store.settings.getInt("end",1260)),"user");
        e.apply();
    }
    private void validateGateAction(JSONObject action,GuardService guard){
        if(role==Role.APP_GATE&&(guard==null||guard.overlay!=this
            ||action.optLong("gateNavigation",-1)!=navigationGeneration
            ||action.has("gateTransition")&&action.optLong("gateTransition",-1)!=gateTransition||!guard.acceptGateInput(gateTransition,guard.gatePkg)))
            throw new IllegalStateException("앱 화면이 바뀌었습니다. 현재 목적을 다시 선택해 주세요.");
    }
    private JSONObject resolveGatePurpose(JSONObject input,GuardService guard) throws Exception {
        JSONObject p=resolvePurpose(input);String scope=p.optString("scope");
        if(!scope.equals("app")&&!scope.equals(guard.gatePkg))throw new IllegalStateException("사용 목적을 다시 선택해 주세요.");
        return p;
    }
    JSONObject phoneSession(){try{return new JSONObject(store.settings.getString("phone-start-session","{}"));}catch(JSONException ignored){return new JSONObject();}}
    void clearPhoneSession(){store.settings.edit().remove("phone-start-session").apply();}
    void openMain(String screen){
        Intent intent=MainActivity.launchIntent(context).putExtra("screen",screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    JSONObject purposeOrders() throws JSONException {
        if(isOverlay){String scope=GuardService.live==null?"app":GuardService.live.gatePkg;return new JSONObject().put(scope,store.purposes(scope));}
        Set<String> scopes=new HashSet<>(Set.of("phone","app"));JSONArray purposes=store.rows("SELECT DISTINCT scope FROM purposes"),rules=store.rows("SELECT pkg FROM rules");
        for(int i=0;i<purposes.length();i++)scopes.add(purposes.getJSONObject(i).getString("scope"));for(int i=0;i<rules.length();i++)scopes.add(rules.getJSONObject(i).getString("pkg"));
        JSONObject orders=new JSONObject();for(String scope:scopes)orders.put(scope,store.purposes(scope));return orders;
    }
    JSONObject gateResponseIdentity(JSONObject data,GuardService guard) throws JSONException {
        long transition=guard==null?-1:guard.gateTransition;
        return data.put("gateNavigation",navigationGeneration).put("gateTransition",transition)
            .put("gateActive",guard!=null&&guard.overlay==this&&guard.overlayVisible&&transition==gateTransition)
            .put("gateExposure",guard==null?"HIDDEN":guard.exposure.name());
    }
    JSONObject state() throws Exception {
        if(isOverlay){
            GuardService guard=GuardService.live;String scope=guard==null||guard.gatePkg.isEmpty()?"app":guard.gatePkg;
            JSONArray purposes=store.purposes(scope);JSONObject rule=guard==null?store.rule(scope):guard.policyRule(scope);
            return gateResponseIdentity(new JSONObject(),guard).put("role",role.name()).put("overlay",true).put("purposeEditor",purposeEditorPkg)
                .put("gate",guard==null?new JSONObject():guard.state()).put("prefs",store.preferences()).put("free",!store.managed(System.currentTimeMillis()))
                .put("scope",scope).put("purposeDate",java.time.LocalDate.now().toString()).put("purposes",purposes).put("purposeOrders",new JSONObject().put(scope,purposes))
                .put("allPurposes",store.rows("SELECT * FROM purposes ORDER BY rowid")).put("rules",rule==null?new JSONArray():new JSONArray().put(rule))
                .put("notes",new JSONArray()).put("chats",new JSONArray()).put("models",new JSONArray()).put("accounts",new JSONArray()).put("connection",new JSONObject());
        }
        JSONObject status=new JSONObject().put("accessibility",GuardService.live!=null).put("drawOverlays",Settings.canDrawOverlays(context)).put("startWindow",GuardService.live==null?UnlockDiagnostics.get(context).windowStatus().put("status","service-off"):GuardService.live.startWindowStatus()).put("appGateDiagnostics",UnlockDiagnostics.get(context).status()).put("usage",UsageReader.allowed(context)).put("notifications",context.getSystemService(NotificationManager.class).areNotificationsEnabled()).put("exact",Build.VERSION.SDK_INT<31||context.getSystemService(AlarmManager.class).canScheduleExactAlarms()).put("briefing",Notices.briefingStatus(context)).put("liveTimer",Notices.timerStatus(context));
        JSONArray phonePurposes=store.purposes("phone");
        return new JSONObject().put("purposeIcons",purposeIcons.forPurposes(context,phonePurposes,this::icon)).put("purposeEditor",purposeEditorPkg).put("savedBrief",new JSONObject(store.settings.getString("last-brief","{}"))).put("purposeDate",java.time.LocalDate.now().toString()).put("purposeOrders",purposeOrders()).put("chatReturn",role==Role.PHONE_START?phoneSession().optString("chatReturn","unlock"):store.settings.getString("chat-return","chats")).put("chatsReturn",role==Role.PHONE_START?phoneSession().optString("chatsReturn","unlock"):store.settings.getString("chats-return","home")).put("phoneCycle",role==Role.PHONE_START?((PhoneStartActivity)context).cycle:0).put("role",role.name()).put("phoneSession",role==Role.PHONE_START?phoneSession():new JSONObject()).put("overlay",false).put("prefs",store.preferences()).put("status",status).put("free",!store.managed(System.currentTimeMillis())).put("gate",GuardService.live==null?new JSONObject():GuardService.live.state()).put("purposes",phonePurposes).put("allPurposes",store.rows("SELECT * FROM purposes ORDER BY rowid")).put("notes",store.notes()).put("chats",store.rows("SELECT * FROM chats ORDER BY updated DESC")).put("rules",store.rows("SELECT * FROM rules ORDER BY label")).put("models",new JSONArray(store.settings.getString("models","[]"))).put("accounts",LockyApp.app.auth.accounts()).put("connection",LockyApp.app.auth.connection()).put("busy",LockyApp.app.chat.busy.get()).put("activeChat",LockyApp.app.chat.active).put("chatError",LockyApp.app.chat.error).put("ruleSeen",new JSONArray(store.settings.getString("rule-seen","[]"))).put("dictionary",store.settings.getString("dictionary","")).put("savedChat",role==Role.PHONE_START?phoneSession().optString("chat"):store.settings.getString("last-chat","")).put("savedNote",role==Role.PHONE_START?phoneSession().optString("note"):store.settings.getString("last-note",""));
    }
    JSONArray apps() throws Exception {
        Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);List<ResolveInfo> list=context.getPackageManager().queryIntentActivities(launcher,0);Set<String> seen=new HashSet<>();JSONArray out=new JSONArray();list.sort(Comparator.comparing(x->x.loadLabel(context.getPackageManager()).toString()));
        String home="";ResolveInfo h=context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);if(h!=null)home=h.activityInfo.packageName;
        for(ResolveInfo r:list){String pkg=r.activityInfo.packageName;if(!seen.add(pkg)||pkg.equals(context.getPackageName())||pkg.equals(home)||pkg.equals("com.android.settings"))continue;out.put(new JSONObject().put("pkg",pkg).put("label",UsageReader.label(context,pkg)).put("icon",icon(r.loadIcon(context.getPackageManager()))));}return out;
    }
    JSONArray usageApps() throws Exception {
        Map<String,JSONObject> choices=new TreeMap<>();JSONArray launchable=apps();for(int j=0;j<launchable.length();j++){JSONObject app=launchable.getJSONObject(j);choices.put(app.getString("pkg"),app);}
        JSONArray known=store.rows("SELECT DISTINCT pkg,label FROM usage");for(int j=0;j<known.length();j++){JSONObject app=known.getJSONObject(j);choices.putIfAbsent(app.getString("pkg"),app);}
        for(String pkg:store.settings.getStringSet("usage-excluded",Set.of()))choices.putIfAbsent(pkg,new JSONObject().put("pkg",pkg).put("label",UsageReader.label(context,pkg)));
        JSONArray out=new JSONArray();for(var app:choices.values()){String pkg=app.getString("pkg");if(pkg.equals(context.getPackageName()))continue;boolean system=pkg.equals("com.samsung.android.sidegesturepad");try{ApplicationInfo info=context.getPackageManager().getApplicationInfo(pkg,0);system|=(info.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;if(!app.has("icon"))app.put("icon",icon(info.loadIcon(context.getPackageManager())));}catch(PackageManager.NameNotFoundException ignored){}app.put("excluded",UsageReader.excluded(context,pkg)).put("system",system);out.put(app);}return out;
    }
    String icon(Drawable d){Bitmap b=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);Rect old=new Rect(d.getBounds());int width=Math.max(1,d.getIntrinsicWidth()),height=Math.max(1,d.getIntrinsicHeight());float scale=48f/Math.max(width,height);int w=Math.round(width*scale),h=Math.round(height*scale);d.setBounds((48-w)/2,(48-h)/2,(48+w)/2,(48+h)/2);d.draw(new Canvas(b));d.setBounds(old);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle();return "data:image/png;base64,"+android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP);}
    void permission(String name){
        if(name.equals("drawOverlays")){
            if(isOverlay&&GuardService.live!=null)GuardService.live.hideOverlay();
            context.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+context.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;
        }
        if(role==Role.PHONE_START){openMain("setup");return;}
        if(isOverlay&&GuardService.live!=null){GuardService.live.openMain("setup");return;}
        if(name.equals("briefChannel")){context.startActivity(Notices.briefingSettings(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
        if(name.equals("liveTimer")||name.equals("timerChannel")){context.startActivity(Notices.timerSettings(context,name.equals("liveTimer")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
        if(name.equals("notification")&&Build.VERSION.SDK_INT>=33&&context instanceof Activity){((Activity)context).requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},4);return;}
        Intent i=switch(name){case "accessibility"->new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);case "usage"->new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);case "exact"->Build.VERSION.SDK_INT>=31?new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+context.getPackageName())):new Intent(Settings.ACTION_SETTINGS);case "battery"->new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);case "notification"->new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.getPackageName());default->throw new IllegalArgumentException("권한을 찾을 수 없습니다.");};context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}

