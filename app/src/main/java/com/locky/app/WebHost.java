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
    static final List<WeakReference<WebHost>> hosts=new CopyOnWriteArrayList<>();
    final android.widget.FrameLayout root;final View loadingView;WebView view;final Context context;final Store store;final boolean isOverlay;final Handler main=new Handler(Looper.getMainLooper());volatile String pendingScreen="home",paintedScreen="",pendingChat="";boolean ready=false,closed=false;
    // DOM completion, Chromium frame preparation and Android drawing are separate states.
    String renderedScreen="";long navigationGeneration,visualGeneration,visualPending=-1,visualCompleted=-1;
    boolean frameReady,presentationActive,everRendered,loadFailed;int initialRetries;
    private Runnable initialWatch,visualWatch;int visualRetryCount;
    WebHost(Context c,boolean overlay){context=c;store=Store.get(c);isOverlay=overlay;presentationActive=!overlay;root=new android.widget.FrameLayout(c);root.setBackgroundColor(Color.rgb(244,243,237));hosts.add(new WeakReference<>(this));
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()|androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));
            evaluate("insetsChanged("+insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())+")");return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){androidx.core.view.ViewCompat.requestApplyInsets(v);main.post(()->{redraw();requestVisualState();});}public void onViewDetachedFromWindow(View v){resetVisualState();}});
        if(overlay){
            android.widget.LinearLayout waiting=new android.widget.LinearLayout(c);waiting.setOrientation(android.widget.LinearLayout.VERTICAL);
            waiting.setGravity(Gravity.CENTER);waiting.setPadding(32,24,32,24);waiting.setBackgroundColor(Color.rgb(244,243,237));
            android.widget.TextView title=new android.widget.TextView(c);title.setText("휴대폰 시작");title.setTextColor(Color.rgb(35,55,46));title.setTextSize(22);title.setGravity(Gravity.CENTER);waiting.addView(title);
            android.widget.TextView detail=new android.widget.TextView(c);detail.setText("시작화면을 준비하고 있습니다");detail.setTextColor(Color.rgb(109,120,111));detail.setTextSize(14);detail.setGravity(Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams info=new android.widget.LinearLayout.LayoutParams(-2,-2);info.topMargin=16;waiting.addView(detail,info);
            android.widget.Button retry=new android.widget.Button(c);retry.setText("다시 불러오기");
            android.widget.LinearLayout.LayoutParams retryParams=new android.widget.LinearLayout.LayoutParams(-2,-2);retryParams.topMargin=18;waiting.addView(retry,retryParams);
            retry.setOnClickListener(v->{initialRetries=0;retryInitialLoad();});
            android.widget.Button dismiss=new android.widget.Button(c);dismiss.setText("그냥 끄기");waiting.addView(dismiss,new android.widget.LinearLayout.LayoutParams(-2,-2));
            dismiss.setOnClickListener(v->{GuardService g=GuardService.live;if(g!=null){if("unlock".equals(g.gate))g.dismissStart();else g.stopApp();}});
            loadingView=waiting;root.addView(waiting,new android.widget.FrameLayout.LayoutParams(-1,-1));
        }else loadingView=null;
        createView();
    }
    void resetVisualState(){if(visualWatch!=null){main.removeCallbacks(visualWatch);visualWatch=null;}++visualGeneration;visualPending=-1;visualCompleted=-1;frameReady=false;paintedScreen="";visualRetryCount=0;}
    void showLoading(){resetVisualState();renderedScreen="";if(loadingView!=null)loadingView.setVisibility(View.VISIBLE);if(isOverlay&&view!=null)view.setVisibility(View.INVISIBLE);}
    void rendered(WebView source,String screen,long navigation){
        if(closed||source!=view||!ready||navigation!=navigationGeneration)return;
        if(isOverlay&&!pendingScreen.equals(screen))return;
        if(!isOverlay)pendingScreen=screen;
        if(!screen.equals(renderedScreen))resetVisualState();
        renderedScreen=screen;everRendered=true;loadFailed=false;cancelInitialWatch();requestVisualState();
    }
    void redraw(){
        if(closed||view==null)return;
        root.requestLayout();view.requestLayout();view.invalidate();root.postInvalidateOnAnimation();
    }
    void resumeOverlay(){if(isOverlay)resumeRendering();}
    void resume(){if(!isOverlay)resumeRendering();}
    private void resumeRendering(){
        if(closed||view==null)return;
        // A visible root may still hold a suspended Chromium/Android surface.
        if(!presentationActive){presentationActive=true;resetVisualState();}
        view.onResume();view.resumeTimers();redraw();
        if(!ready){watchInitialLoad();return;}
        if(pendingScreen.equals(renderedScreen))requestVisualState();
        else if(!everRendered)watchInitialLoad();
    }
    void pause(){presentationActive=false;resetVisualState();if(view!=null)view.onPause();}
    void prepareOverlay(){
        if(closed||!isOverlay||view==null)return;
        if(presentationActive)pause();
        // Prepare the attached, transparent window without exposing it on the keyguard.
        view.onResume();view.resumeTimers();redraw();
        if(ready&&pendingScreen.equals(renderedScreen))requestVisualState();else watchInitialLoad();
    }
    void requestVisualState(){
        if(closed||!ready||view==null||!view.isAttachedToWindow()||root.getVisibility()!=View.VISIBLE
            ||!pendingScreen.equals(renderedScreen)||frameReady||visualPending==visualGeneration)return;
        // The dictionary host retains the original WebView and must continue
        // painting beneath popup dictionaries while its Activity is paused.
        WebView candidate=view;String screen=pendingScreen;long generation=visualGeneration,navigation=navigationGeneration;
        visualPending=generation;
        // A callback can stall after reattaching the same WebView. Retry once,
        // then display the already-rendered DOM instead of reloading and losing drafts.
        visualWatch=()->{
            visualWatch=null;
            if(closed||candidate!=view||generation!=visualGeneration||navigation!=navigationGeneration
                ||frameReady||visualPending!=generation||root.getVisibility()!=View.VISIBLE
                ||!screen.equals(pendingScreen))return;
            visualPending=-1;redraw();
            if(visualRetryCount++==0)requestVisualState();
            else{
                frameReady=true;candidate.setVisibility(View.VISIBLE);
                if(loadingView!=null)loadingView.setVisibility(View.GONE);
                redraw();
            }
        };
        main.postDelayed(visualWatch,1200);
        candidate.postVisualStateCallback(generation,new WebView.VisualStateCallback(){@Override public void onComplete(long id){
            if(closed||candidate!=view||generation!=visualGeneration||navigation!=navigationGeneration
                ||!screen.equals(pendingScreen))return;
            if(root.getVisibility()!=View.VISIBLE){visualPending=-1;return;}
            if(frameReady)return;
            if(visualWatch!=null){main.removeCallbacks(visualWatch);visualWatch=null;}
            visualPending=-1;frameReady=true;
            // This only makes a prepared frame drawable; onDraw records Android presentation.
            candidate.setVisibility(View.VISIBLE);if(loadingView!=null)loadingView.setVisibility(View.GONE);redraw();
        }});
        redraw();
    }
    void didDraw(WebView source){
        if(closed||source!=view||!presentationActive||!frameReady||!root.isShown()||visualCompleted==visualGeneration)return;
        long generation=visualGeneration;String screen=renderedScreen;
        main.post(()->{if(!closed&&source==view&&presentationActive&&frameReady&&generation==visualGeneration&&root.isShown()){
            visualCompleted=generation;paintedScreen=screen;
        }});
    }
    private void cancelInitialWatch(){if(initialWatch!=null){main.removeCallbacks(initialWatch);initialWatch=null;}}
    private void watchInitialLoad(){
        if(closed||everRendered||initialWatch!=null)return;
        WebView candidate=view;
        initialWatch=()->{
            initialWatch=null;if(closed||candidate!=view||everRendered)return;
            // Missing onPageFinished is recoverable too. JS liveness is not treated as a painted frame.
            candidate.evaluateJavascript("typeof nativeRestore==='function'",result->{
                if(closed||candidate!=view||everRendered)return;
                if("true".equals(result)){if(!ready){ready=true;dispatchScreen();}else candidate.evaluateJavascript("nativeRestore()",null);}
                else {loadFailed=true;retryInitialLoad();}
            });
            // Only an untouched, uninitialised document can be retried automatically, once.
            if(!ready&&initialRetries==0)main.postDelayed(()->{if(!closed&&candidate==view&&!ready&&!everRendered)retryInitialLoad();},500);
        };
        main.postDelayed(initialWatch,6000);
    }
    void retryInitialLoad(){
        if(closed||view==null)return;
        if(everRendered){redraw();requestVisualState();return;}
        if(ready&&!loadFailed){view.evaluateJavascript("nativeRestore()",null);return;}
        if(initialRetries++>0)return;
        ready=false;loadFailed=false;cancelInitialWatch();showLoading();view.stopLoading();
        view.loadUrl("https://appassets.androidplatform.net/assets/index.html");watchInitialLoad();
    }
    void createView(){
        if(closed)return;
        view=new WebView(context){@Override protected void onDraw(Canvas canvas){super.onDraw(canvas);didDraw(this);}};
        // Scope the compositor workaround to WebView; keep native controls/windows unchanged.
        view.setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        root.addView(view,0,new android.widget.FrameLayout.LayoutParams(-1,-1));
        if(isOverlay){view.setVisibility(View.INVISIBLE);if(loadingView!=null)loadingView.setVisibility(View.VISIBLE);}
        WebSettings s=view.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setMediaPlaybackRequiresUserGesture(true);s.setSupportMultipleWindows(false);view.setBackgroundColor(Color.rgb(244,243,237));
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(context)).build();
        view.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){return loader.shouldInterceptRequest(r.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public void onReceivedError(WebView v,WebResourceRequest request,WebResourceError error){if(v==view&&request.isForMainFrame()&&!everRendered){loadFailed=true;watchInitialLoad();}}
            @Override public void onPageFinished(WebView v,String url){if(closed||v!=view||loadFailed||ready)return;ready=true;dispatchScreen();}
            @Override public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail detail){
                if(v!=view)return true;
                // Recreation is reserved for an actually dead renderer, never a slow visual callback.
                cancelInitialWatch();ready=false;everRendered=false;loadFailed=false;initialRetries=0;showLoading();
                root.removeView(v);v.removeJavascriptInterface("Native");v.destroy();if(!closed)main.post(WebHost.this::createView);return true;
            }
        });
        view.addJavascriptInterface(new Bridge(view),"Native");view.setOnKeyListener((v,key,e)->{if(key==KeyEvent.KEYCODE_BACK&&e.getAction()==KeyEvent.ACTION_UP){evaluate("back()");return true;}return false;});
        view.loadUrl("https://appassets.androidplatform.net/assets/index.html");watchInitialLoad();
    }
    void openChat(String id){pendingChat=id;screen("chat");}
    void screen(String name){
        pendingScreen=name;if(!"chat".equals(name))pendingChat="";++navigationGeneration;showLoading();
        if(ready)dispatchScreen();watchInitialLoad();
    }
    private void dispatchScreen(){
        WebView candidate=view;long navigation=navigationGeneration;String name=pendingScreen,chat=pendingChat;
        main.post(()->{if(!closed&&ready&&candidate==view&&navigation==navigationGeneration)
            candidate.evaluateJavascript("chat".equals(name)&&!chat.isEmpty()?"openChatById("+JSONObject.quote(chat)+","+navigation+")":"navigate("+JSONObject.quote(name)+","+navigation+")",null);});
    }
    void evaluate(String js){WebView candidate=view;main.post(()->{if(!closed&&ready&&candidate==view&&(candidate.getParent()!=null||!isOverlay))candidate.evaluateJavascript(js,null);});}
    void close(){closed=true;ready=false;resetVisualState();main.removeCallbacksAndMessages(null);root.removeView(view);view.removeJavascriptInterface("Native");view.destroy();hosts.removeIf(r->r.get()==null||r.get()==this);}
    static void refreshAll(){for(var r:hosts){WebHost h=r.get();if(h!=null)h.evaluate("refresh()");}}
    void answer(String id,Object value,String error){evaluate("receive("+JSONObject.quote(id)+","+(value==null?"null":value.toString())+","+(error==null?"null":JSONObject.quote(error))+")");}
    final class Bridge {
        final WebView source;
        Bridge(WebView source){this.source=source;}
        @JavascriptInterface public void request(String id,String action,String raw){
            if(closed||source!=view)return;
            if(!action.matches("[A-Za-z]+")||raw.length()>1000000){answer(id,null,"요청을 처리할 수 없습니다.");return;}
            if("rendered".equals(action)){main.post(()->{try{JSONObject frame=new JSONObject(raw);rendered(source,frame.optString("screen"),frame.optLong("navigation",-1));}catch(JSONException ignored){}});return;}
            if(Set.of("choose","grant","extend","stop","utility","open","permission","dictionary","dictionaryPicker","linkTest","signin","cancelLogin","exit","dismiss").contains(action))main.post(()->handle(id,action,raw));
            else LockyApp.app.work.execute(()->handle(id,action,raw));
        }
    }
    void handle(String id,String action,String raw){try {
        JSONObject a=new JSONObject(raw);Object out=new JSONObject();GuardService g=GuardService.live;
        switch(action){
            case "state":out=state();break;
            case "tick":out=new JSONObject().put("gate",g==null?new JSONObject():g.state()).put("free",!store.managed(System.currentTimeMillis())).put("busy",LockyApp.app.chat.busy.get()).put("chatError",LockyApp.app.chat.error).put("connection",LockyApp.app.auth.connection());break;
            case "apps":out=apps();break;
            case "usageApps":out=usageApps();break;
            case "excludeUsage":{Set<String> excluded=new HashSet<>(store.settings.getStringSet("usage-excluded",Set.of()));String target=a.getString("pkg");if(a.getBoolean("excluded"))excluded.add(target);else excluded.remove(target);store.settings.edit().putStringSet("usage-excluded",excluded).apply();if(g!=null)g.policyChanged();break;}
            case "purpose":out=store.purpose(a,a.optString("scope","phone"));break;
            case "removePurpose":store.getWritableDatabase().delete("purposes","id=?",new String[]{a.getString("id")});break;
            case "choose":if(g!=null&&isOverlay)g.choosePhone(resolvePurpose(a));else {JSONObject p=resolvePurpose(a);store.event("unlock",p.getString("label"),"",p.optString("pkg"),0,p.optInt("aimless",0)==1);Intent i=Links.intent(context,p.optString("pkg"),p.optString("uri"));if(i!=null)context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));else if(context instanceof MainActivity)((MainActivity)context).moveTaskToBack(true);}break;
            case "grant":if(g==null)throw new IllegalStateException("접근성 서비스를 먼저 켜 주세요.");g.allowApp(resolvePurpose(a),a.optInt("minutes",5));break;
            case "extend":if(g!=null)g.extend(a.optInt("minutes",5));break;
            case "stop":if(g!=null)g.stopApp();break;
            case "utility":if(g!=null)g.useUtility(a.getString("name"));else store.event("utility",a.getString("name"),context.getPackageName(),"",0);break;
            case "open":if(isOverlay&&g!=null)g.openMain(a.getString("screen"),a.optString("chat"));else if(a.optString("screen").equals("chat")&&!a.optString("chat").isEmpty())openChat(a.getString("chat"));else screen(a.getString("screen"));break;
            case "dismiss":if(isOverlay&&g!=null)g.dismissStart();else if(context instanceof MainActivity)((MainActivity)context).moveTaskToBack(true);break;
            case "remember":if(!isOverlay){pendingScreen=a.optString("screen","home");store.settings.edit().putString("last-screen",a.optString("screen","home")).putString("last-chat",a.optString("chat")).putString("last-note",a.optString("note")).apply();}break;
            case "dictionaries":out=Links.dictionaries(context);break;
            case "dictionaryPicker":if(isOverlay&&g!=null)g.openDictionary(a.optString("word"),true);else Links.dictionaryPicker(context,a.optString("word"));break;
            case "dictionaryChoice":store.settings.edit().putString("dictionary",a.getString("id")).apply();break;
            case "exit":if(context instanceof MainActivity)((MainActivity)context).moveTaskToBack(true);break;
            case "dictionary":if(g!=null)g.useUtility("사전");else store.event("utility","사전",context.getPackageName(),"",0);if(isOverlay&&g!=null)g.openDictionary(a.getString("word"),false);else Links.dictionary(context,a.getString("word"));break;
            case "linkTest":Intent link=Links.intent(context,a.optString("pkg"),a.optString("uri"));if(link==null)throw new IllegalArgumentException("이 앱에서 지원하는 연결인지 확인해 주세요. 딥링크는 앱마다 달라요.");if(isOverlay&&g!=null)g.hideOverlay();context.startActivity(link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));break;
            case "saveNote":store.note(a);break;
            case "deleteNote":store.getWritableDatabase().delete("notes","id=?",new String[]{a.getString("id")});break;
            case "settings":saveSettings(a);if(g!=null)g.policyChanged();Scheduler.schedule(context);break;
            case "rule":String pkg=a.getString("pkg");if(pkg.equals(context.getPackageName()))throw new IllegalArgumentException("Locky는 관리 대상으로 지정할 수 없습니다.");String mode=a.optString("mode");JSONObject prior=store.rule(pkg);
                String key="rule-memory:"+pkg;JSONObject memory=new JSONObject(store.settings.getString(key,"{}"));if(mode.equals("restore"))mode=memory.optString("mode","limited");
                if(mode.equals("none")){if(prior!=null)memory=prior;store.getWritableDatabase().delete("rules","pkg=?",new String[]{pkg});store.settings.edit().remove("blocked-until:"+pkg).putString(key,memory.toString()).apply();}
                else {if(!mode.equals("limited")&&!mode.equals("locked"))throw new IllegalArgumentException("관리 방식을 확인해 주세요.");ContentValues v=new ContentValues();v.put("pkg",pkg);v.put("label",UsageReader.label(context,pkg));v.put("mode",mode);int limit=Rules.dailyMinutes(a.optInt("minutes",memory.optInt("minutes",60)));v.put("minutes",limit);store.put("rules",v);
                    var editor=store.settings.edit().putString(key,new JSONObject().put("mode",mode).put("minutes",limit).toString());
                    if(prior==null||!prior.optString("mode").equals(mode)||limit>prior.optInt("minutes",60))editor.remove("blocked-until:"+pkg);editor.apply();
                }
                JSONArray seen=new JSONArray(store.settings.getString("rule-seen","[]"));boolean found=false;for(int j=0;j<seen.length();j++)if(seen.getString(j).equals(pkg))found=true;if(!found){seen.put(pkg);store.settings.edit().putString("rule-seen",seen.toString()).apply();}if(g!=null)g.policyChanged();break;
            case "brief":out=Briefing.build(context,a.optInt("days",1)==7?7:1,Math.max(0,Math.min(179,a.optLong("offset",0))));break;
            case "copyDay":{
                String exportText=Briefing.rawDay(context,Math.max(0,Math.min(179,a.optInt("offset",0)))).toString(2);
                main.post(()->{try{
                    android.content.ClipboardManager clipboard=context.getSystemService(android.content.ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Locky 하루 기록",exportText));
                    answer(id,new JSONObject().put("ok",true),null);
                }catch(Exception e){answer(id,null,ChatGPT.safeError(e));}});
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
            case "messages":out=store.rows("SELECT * FROM messages WHERE chat=? ORDER BY id",a.getString("id"));break;
            case "deleteChat":if(LockyApp.app.chat.busy.get()&&LockyApp.app.chat.active.equals(a.getString("id")))throw new IllegalStateException("답변을 멈춘 후 삭제해 주세요.");store.deleteChat(a.getString("id"));break;
            case "send":LockyApp.app.chat.send(a.getString("id"),a.getString("text"),store.settings.getString("model",""));break;
            case "cancelResponse":LockyApp.app.auth.cancel();break;
            case "models":out=LockyApp.app.auth.models();store.settings.edit().putString("models",out.toString()).apply();break;
            case "account":LockyApp.app.auth.select(a.getString("client"));store.settings.edit().putString("model","").putString("models","[]").apply();break;
            case "signout":out=new JSONObject().put("message",LockyApp.app.auth.signOut());store.settings.edit().putString("model","").putString("models","[]").apply();break;
            case "signin":if(isOverlay&&g!=null){g.openMain("chats");break;}LockyApp.app.auth.signIn(a.optString("client"),a.optBoolean("consent",false),new ChatGPT.SignIn(){public void browser(String url){if(context instanceof MainActivity)((MainActivity)context).loginBrowser(url);}public void progress(){refreshAll();}public void success(){main.post(()->{if(context instanceof MainActivity)((MainActivity)context).releaseLoginBrowser();});LockyApp.app.work.execute(()->{try{JSONArray models=LockyApp.app.auth.models();store.settings.edit().putString("models",models.toString()).apply();if(models.length()>0)store.settings.edit().putString("model",models.getJSONObject(0).getString("slug")).apply();}catch(Exception ignored){}refreshAll();});}public void error(String error){main.post(()->{if(context instanceof MainActivity)((MainActivity)context).releaseLoginBrowser();});refreshAll();}});break;
            case "cancelLogin":LockyApp.app.auth.cancelLogin();if(context instanceof MainActivity)((MainActivity)context).releaseLoginBrowser();break;
            case "permission":permission(a.getString("name"));break;
            default:throw new IllegalArgumentException("지원하지 않는 요청입니다.");
        }
        answer(id,out,null);
    }catch(Exception e){answer(id,null,ChatGPT.safeError(e));}}
    JSONObject resolvePurpose(JSONObject a) throws Exception {if(a.has("label"))return store.purpose(a,a.optString("scope","phone"));JSONArray p=store.rows("SELECT * FROM purposes WHERE id=?",a.getString("id"));if(p.length()==0)throw new IllegalStateException("사용 목적을 다시 선택해 주세요.");return p.getJSONObject(0);}
    void saveSettings(JSONObject a) throws Exception {
        var e=store.settings.edit();if(a.has("enabled"))e.putBoolean("enabled",a.getBoolean("enabled"));
        if(a.has("floatingTimer"))e.putBoolean("floatingTimer",a.getBoolean("floatingTimer"));
        if(a.has("start")||a.has("end")){int start=a.optInt("start",store.settings.getInt("start",540)),end=a.optInt("end",store.settings.getInt("end",1260));if(start<0||start>1439||end<0||end>1439||start==end)throw new IllegalArgumentException("서로 다른 시작·종료 시간을 선택해 주세요.");e.putInt("start",start).putInt("end",end);}
        if(a.has("reminders")){JSONArray input=a.getJSONArray("reminders"),values=new JSONArray();Set<Integer> seen=new HashSet<>();for(int i=0;i<input.length();i++){int m=input.getInt(i);if(m>=1&&m<=180&&seen.add(m))values.put(m);}e.putString("reminders",values.toString());}
        if(a.has("weeklyDay"))e.putInt("weeklyDay",Math.max(1,Math.min(7,a.getInt("weeklyDay"))));
        if(a.has("model")){String model=a.getString("model");JSONArray models=new JSONArray(store.settings.getString("models","[]"));boolean valid=false;for(int i=0;i<models.length();i++)if(models.getJSONObject(i).getString("slug").equals(model))valid=true;if(!valid)throw new IllegalArgumentException("연결된 계정의 모델을 선택해 주세요.");e.putString("model",model);}e.apply();
    }
    JSONObject state() throws Exception {
        JSONObject status=new JSONObject().put("accessibility",GuardService.live!=null).put("usage",UsageReader.allowed(context)).put("notifications",context.getSystemService(NotificationManager.class).areNotificationsEnabled()).put("exact",Build.VERSION.SDK_INT<31||context.getSystemService(AlarmManager.class).canScheduleExactAlarms()).put("liveTimer",Notices.timerStatus(context));
        return new JSONObject().put("overlay",isOverlay).put("prefs",store.preferences()).put("status",status).put("free",!store.managed(System.currentTimeMillis())).put("gate",GuardService.live==null?new JSONObject():GuardService.live.state()).put("purposes",store.purposes(isOverlay&&GuardService.live!=null&&!GuardService.live.gatePkg.isEmpty()?GuardService.live.gatePkg:"phone")).put("allPurposes",store.rows("SELECT * FROM purposes ORDER BY rowid")).put("notes",store.rows("SELECT * FROM notes ORDER BY updated DESC")).put("chats",store.rows("SELECT * FROM chats ORDER BY updated DESC")).put("rules",store.rows("SELECT * FROM rules ORDER BY label")).put("models",new JSONArray(store.settings.getString("models","[]"))).put("accounts",LockyApp.app.auth.accounts()).put("connection",LockyApp.app.auth.connection()).put("busy",LockyApp.app.chat.busy.get()).put("activeChat",LockyApp.app.chat.active).put("chatError",LockyApp.app.chat.error).put("ruleSeen",new JSONArray(store.settings.getString("rule-seen","[]"))).put("dictionary",store.settings.getString("dictionary","")).put("savedChat",store.settings.getString("last-chat","")).put("savedNote",store.settings.getString("last-note",""));
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
    String icon(Drawable d){Bitmap b=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);d.setBounds(0,0,48,48);d.draw(new Canvas(b));java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle();return "data:image/png;base64,"+android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP);}
    void permission(String name){
        if(isOverlay&&GuardService.live!=null){GuardService.live.openMain("setup");return;}
        if(name.equals("liveTimer")||name.equals("timerChannel")){context.startActivity(Notices.timerSettings(context,name.equals("liveTimer")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
        if(name.equals("notification")&&Build.VERSION.SDK_INT>=33&&context instanceof Activity){((Activity)context).requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},4);return;}
        Intent i=switch(name){case "accessibility"->new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);case "usage"->new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);case "exact"->Build.VERSION.SDK_INT>=31?new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+context.getPackageName())):new Intent(Settings.ACTION_SETTINGS);case "battery"->new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);case "notification"->new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.getPackageName());default->throw new IllegalArgumentException("권한을 찾을 수 없습니다.");};context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}

