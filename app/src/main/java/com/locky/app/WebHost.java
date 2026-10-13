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
    final WebView view;final Context context;final Store store;final boolean isOverlay;final Handler main=new Handler(Looper.getMainLooper());String pendingScreen="home";boolean ready=false;
    WebHost(Context c,boolean overlay){context=c;store=Store.get(c);isOverlay=overlay;view=new WebView(c);hosts.add(new WeakReference<>(this));
        WebSettings s=view.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setMediaPlaybackRequiresUserGesture(true);s.setSupportMultipleWindows(false);view.setBackgroundColor(Color.rgb(244,243,237));
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(c)).build();
        view.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){return loader.shouldInterceptRequest(r.getUrl());}@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}@Override public void onPageFinished(WebView v,String url){ready=true;screen(pendingScreen);}});
        view.addJavascriptInterface(new Bridge(),"Native");view.setOnKeyListener((v,key,e)->{if(key==KeyEvent.KEYCODE_BACK&&e.getAction()==KeyEvent.ACTION_UP){evaluate("back()");return true;}return false;});
        view.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        view.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }
    void screen(String name){pendingScreen=name;if(ready)evaluate("navigate("+JSONObject.quote(name)+")");}
    void evaluate(String js){main.post(()->{if(ready&&(view.getParent()!=null||!isOverlay))view.evaluateJavascript(js,null);});}
    void close(){view.removeJavascriptInterface("Native");view.destroy();hosts.removeIf(r->r.get()==null||r.get()==this);}
    static void refreshAll(){for(var r:hosts){WebHost h=r.get();if(h!=null)h.evaluate("refresh()");}}
    void answer(String id,Object value,String error){evaluate("receive("+JSONObject.quote(id)+","+(value==null?"null":value.toString())+","+(error==null?"null":JSONObject.quote(error))+")");}
    final class Bridge {
        @JavascriptInterface public void request(String id,String action,String raw){
            if(!action.matches("[A-Za-z]+")||raw.length()>1000000){answer(id,null,"요청을 처리할 수 없습니다.");return;}
            if(Set.of("choose","grant","extend","stop","utility","open","permission","dictionary","linkTest","signin","cancelLogin","exit").contains(action))main.post(()->handle(id,action,raw));
            else LockyApp.app.work.execute(()->handle(id,action,raw));
        }
    }
    void handle(String id,String action,String raw){try {
        JSONObject a=new JSONObject(raw);Object out=new JSONObject();GuardService g=GuardService.live;
        switch(action){
            case "state":out=state();break;
            case "tick":out=new JSONObject().put("gate",g==null?new JSONObject():g.state()).put("free",!store.managed(System.currentTimeMillis())).put("busy",LockyApp.app.chat.busy.get()).put("chatError",LockyApp.app.chat.error).put("connection",LockyApp.app.auth.connection());break;
            case "apps":out=apps();break;
            case "purpose":out=store.purpose(a,a.optString("scope","*"));break;
            case "removePurpose":if(!a.getString("id").equals("aimless"))store.getWritableDatabase().delete("purposes","id=?",new String[]{a.getString("id")});break;
            case "choose":if(g!=null&&isOverlay)g.choosePhone(resolvePurpose(a));else {JSONObject p=resolvePurpose(a);store.event("unlock",p.getString("label"),"",p.optString("pkg"),0);Intent i=Links.intent(context,p.optString("pkg"),p.optString("uri"));if(i!=null)context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));else if(context instanceof MainActivity)((MainActivity)context).moveTaskToBack(true);}break;
            case "grant":if(g==null)throw new IllegalStateException("접근성 서비스를 먼저 켜 주세요.");g.allowApp(resolvePurpose(a),a.optInt("minutes",5));break;
            case "extend":if(g!=null)g.extend(a.optInt("minutes",5));break;
            case "stop":if(g!=null)g.stopApp();break;
            case "utility":if(g!=null)g.useUtility(a.getString("name"));else store.event("utility",a.getString("name"),context.getPackageName(),"",0);break;
            case "open":if(isOverlay&&g!=null)g.openMain(a.getString("screen"));else screen(a.getString("screen"));break;
            case "exit":if(context instanceof MainActivity)((MainActivity)context).moveTaskToBack(true);break;
            case "dictionary":if(g!=null)g.useUtility("사전");else store.event("utility","사전",context.getPackageName(),"",0);if(isOverlay&&g!=null)g.hideOverlay();Links.dictionary(context,a.getString("word"));break;
            case "linkTest":Intent link=Links.intent(context,a.optString("pkg"),a.optString("uri"));if(link==null)throw new IllegalArgumentException("이 앱에서 지원하는 연결인지 확인해 주세요. 딥링크는 앱마다 달라요.");context.startActivity(link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));break;
            case "saveNote":store.note(a);break;
            case "deleteNote":store.getWritableDatabase().delete("notes","id=?",new String[]{a.getString("id")});break;
            case "settings":saveSettings(a);if(g!=null)g.policyChanged();Scheduler.schedule(context);break;
            case "rule":String pkg=a.getString("pkg");if(pkg.equals(context.getPackageName()))throw new IllegalArgumentException("Locky는 관리 대상으로 지정할 수 없습니다.");String mode=a.optString("mode");if(mode.equals("none"))store.getWritableDatabase().delete("rules","pkg=?",new String[]{pkg});else {if(!mode.equals("limited")&&!mode.equals("locked"))throw new IllegalArgumentException("관리 방식을 확인해 주세요.");ContentValues v=new ContentValues();v.put("pkg",pkg);v.put("label",UsageReader.label(context,pkg));v.put("mode",mode);v.put("minutes",Rules.dailyMinutes(a.optInt("minutes",60)));store.put("rules",v);}if(g!=null)g.policyChanged();break;
            case "brief":out=Briefing.build(context,a.optInt("days",1)==7?7:1,Math.max(0,Math.min(179,a.optLong("offset",0))));break;
            case "newChat":out=new JSONObject().put("id",store.newChat());break;
            case "messages":out=store.rows("SELECT * FROM messages WHERE chat=? ORDER BY id",a.getString("id"));break;
            case "deleteChat":if(LockyApp.app.chat.busy.get()&&LockyApp.app.chat.active.equals(a.getString("id")))throw new IllegalStateException("답변을 멈춘 후 삭제해 주세요.");store.deleteChat(a.getString("id"));break;
            case "send":LockyApp.app.chat.send(a.getString("id"),a.getString("text"),store.settings.getString("model",""));break;
            case "cancelResponse":LockyApp.app.auth.cancel();break;
            case "models":out=LockyApp.app.auth.models();store.settings.edit().putString("models",out.toString()).apply();break;
            case "account":LockyApp.app.auth.select(a.getString("client"));store.settings.edit().putString("model","").putString("models","[]").apply();break;
            case "signout":out=new JSONObject().put("message",LockyApp.app.auth.signOut());store.settings.edit().putString("model","").putString("models","[]").apply();break;
            case "signin":if(isOverlay&&g!=null){g.openMain("chats");break;}LockyApp.app.auth.signIn(a.optString("client"),a.optBoolean("consent",false),new ChatGPT.SignIn(){public void browser(String url){main.post(()->{try{new androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(context,Uri.parse(url));}catch(Exception e){context.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}});}public void progress(){refreshAll();}public void success(){LockyApp.app.work.execute(()->{try{JSONArray models=LockyApp.app.auth.models();store.settings.edit().putString("models",models.toString()).apply();if(models.length()>0)store.settings.edit().putString("model",models.getJSONObject(0).getString("slug")).apply();}catch(Exception ignored){}refreshAll();});}public void error(String error){refreshAll();}});break;
            case "cancelLogin":LockyApp.app.auth.cancelLogin();break;
            case "permission":permission(a.getString("name"));break;
            default:throw new IllegalArgumentException("지원하지 않는 요청입니다.");
        }
        answer(id,out,null);
    }catch(Exception e){answer(id,null,ChatGPT.safeError(e));}}
    JSONObject resolvePurpose(JSONObject a) throws Exception {if(a.has("label"))return store.purpose(a,a.optString("scope","*"));JSONArray p=store.rows("SELECT * FROM purposes WHERE id=?",a.getString("id"));if(p.length()==0)throw new IllegalStateException("사용 목적을 다시 선택해 주세요.");return p.getJSONObject(0);}
    void saveSettings(JSONObject a) throws Exception {
        var e=store.settings.edit();if(a.has("enabled"))e.putBoolean("enabled",a.getBoolean("enabled"));
        if(a.has("start")||a.has("end")){int start=a.optInt("start",store.settings.getInt("start",540)),end=a.optInt("end",store.settings.getInt("end",1260));if(start<0||start>1439||end<0||end>1439||start==end)throw new IllegalArgumentException("서로 다른 시작·종료 시간을 선택해 주세요.");e.putInt("start",start).putInt("end",end);}
        if(a.has("reminders")){JSONArray input=a.getJSONArray("reminders"),values=new JSONArray();Set<Integer> seen=new HashSet<>();for(int i=0;i<input.length();i++){int m=input.getInt(i);if(m>=1&&m<=180&&seen.add(m))values.put(m);}e.putString("reminders",values.toString());}
        if(a.has("weeklyDay"))e.putInt("weeklyDay",Math.max(1,Math.min(7,a.getInt("weeklyDay"))));
        if(a.has("model")){String model=a.getString("model");JSONArray models=new JSONArray(store.settings.getString("models","[]"));boolean valid=false;for(int i=0;i<models.length();i++)if(models.getJSONObject(i).getString("slug").equals(model))valid=true;if(!valid)throw new IllegalArgumentException("연결된 계정의 모델을 선택해 주세요.");e.putString("model",model);}e.apply();
    }
    JSONObject state() throws Exception {
        JSONObject status=new JSONObject().put("accessibility",GuardService.live!=null).put("usage",UsageReader.allowed(context)).put("notifications",context.getSystemService(NotificationManager.class).areNotificationsEnabled()).put("exact",Build.VERSION.SDK_INT<31||context.getSystemService(AlarmManager.class).canScheduleExactAlarms());
        return new JSONObject().put("overlay",isOverlay).put("prefs",store.preferences()).put("status",status).put("free",!store.managed(System.currentTimeMillis())).put("gate",GuardService.live==null?new JSONObject():GuardService.live.state()).put("purposes",store.purposes(isOverlay&&GuardService.live!=null?GuardService.live.gatePkg:"*")).put("allPurposes",store.rows("SELECT * FROM purposes ORDER BY rowid")).put("notes",store.rows("SELECT * FROM notes ORDER BY updated DESC")).put("chats",store.rows("SELECT * FROM chats ORDER BY updated DESC")).put("rules",store.rows("SELECT * FROM rules ORDER BY label")).put("models",new JSONArray(store.settings.getString("models","[]"))).put("accounts",LockyApp.app.auth.accounts()).put("connection",LockyApp.app.auth.connection()).put("busy",LockyApp.app.chat.busy.get()).put("activeChat",LockyApp.app.chat.active).put("chatError",LockyApp.app.chat.error);
    }
    JSONArray apps() throws Exception {
        Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);List<ResolveInfo> list=context.getPackageManager().queryIntentActivities(launcher,0);Set<String> seen=new HashSet<>();JSONArray out=new JSONArray();list.sort(Comparator.comparing(x->x.loadLabel(context.getPackageManager()).toString()));
        String home="";ResolveInfo h=context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);if(h!=null)home=h.activityInfo.packageName;
        for(ResolveInfo r:list){String pkg=r.activityInfo.packageName;if(!seen.add(pkg)||pkg.equals(context.getPackageName())||pkg.equals(home)||pkg.equals("com.android.settings"))continue;out.put(new JSONObject().put("pkg",pkg).put("label",UsageReader.label(context,pkg)).put("icon",icon(r.loadIcon(context.getPackageManager()))));}return out;
    }
    String icon(Drawable d){Bitmap b=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);d.setBounds(0,0,48,48);d.draw(new Canvas(b));java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle();return "data:image/png;base64,"+android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP);}
    void permission(String name){
        if(isOverlay&&GuardService.live!=null){GuardService.live.openMain("setup");return;}
        if(name.equals("notification")&&Build.VERSION.SDK_INT>=33&&context instanceof Activity){((Activity)context).requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},4);return;}
        Intent i=switch(name){case "accessibility"->new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);case "usage"->new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);case "exact"->Build.VERSION.SDK_INT>=31?new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+context.getPackageName())):new Intent(Settings.ACTION_SETTINGS);case "battery"->new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);case "notification"->new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.getPackageName());default->throw new IllegalArgumentException("권한을 찾을 수 없습니다.");};context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
