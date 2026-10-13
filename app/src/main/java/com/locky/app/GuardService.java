package com.locky.app;

import android.accessibilityservice.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import org.json.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Event driven foreground observation. No node text, screen capture, polling of app contents or wake locks. */
public final class GuardService extends AccessibilityService {
    static volatile GuardService live;
    final Handler main=new Handler(Looper.getMainLooper());Store store;
    String foreground="",sessionPkg="",purpose="",utility="첫 화면";long segmentWall,segmentElapsed,sessionRemaining,sessionEvent,phoneEvent;
    boolean interactive,overlayVisible,waitingUnlock,wasManaged;String gate="unlock",gatePkg="";
    WebHost overlay;WindowManager window;final Set<String> systemPackages=new HashSet<>();
    final Runnable pulse=new Runnable(){public void run(){if(live!=GuardService.this)return;checkpoint();enforce();if(shouldPulse())main.postDelayed(this,1000);}};
    final BroadcastReceiver screen=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String a=i.getAction();if(Intent.ACTION_SCREEN_OFF.equals(a)){checkpoint();interactive=false;sessionPkg="";sessionRemaining=0;waitingUnlock=true;prepareUnlock();Notices.cancelTimer(c);main.removeCallbacks(pulse);}
        else if(Intent.ACTION_USER_PRESENT.equals(a)){interactive=true;waitingUnlock=false;prepareUnlock();ensurePulse();}
        else if(Intent.ACTION_SCREEN_ON.equals(a)){interactive=true;main.post(checkUnlock);}
        else if(Intent.ACTION_TIME_CHANGED.equals(a)||Intent.ACTION_TIMEZONE_CHANGED.equals(a)){checkpoint();policyChanged();Scheduler.schedule(c);}
    }};
    int unlockChecks;
    final Runnable checkUnlock=new Runnable(){public void run(){if(!interactive)return;if(!getSystemService(KeyguardManager.class).isKeyguardLocked()){if(waitingUnlock){waitingUnlock=false;prepareUnlock();}ensurePulse();}else if(unlockChecks++<20)main.postDelayed(this,250);}};
    @Override protected void onServiceConnected(){
        live=this;store=Store.get(this);window=getSystemService(WindowManager.class);interactive=getSystemService(PowerManager.class).isInteractive();wasManaged=store.managed(System.currentTimeMillis());
        systemPackages.add("com.android.systemui");systemPackages.add("com.android.permissioncontroller");systemPackages.add("com.google.android.permissioncontroller");
        android.view.inputmethod.InputMethodManager ime=getSystemService(android.view.inputmethod.InputMethodManager.class);for(android.view.inputmethod.InputMethodInfo x:ime.getEnabledInputMethodList())systemPackages.add(x.getPackageName());
        IntentFilter f=new IntentFilter();for(String a:new String[]{Intent.ACTION_SCREEN_OFF,Intent.ACTION_SCREEN_ON,Intent.ACTION_USER_PRESENT,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED})f.addAction(a);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screen,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screen,f);
        segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();
        waitingUnlock=!interactive||getSystemService(KeyguardManager.class).isKeyguardLocked();
        if(waitingUnlock)prepareUnlock();Scheduler.schedule(this);recover();
    }
    private void recover(){long from=store.settings.getLong("checkpoint",System.currentTimeMillis()),now=System.currentTimeMillis();
        if(now-from<2000)return;String old=store.settings.getString("last-package","");if(old.isEmpty())return;
        LockyApp.app.work.execute(()->{try{JSONArray history=UsageReader.intervals(this,from,now);for(int i=0;i<history.length();i++){JSONObject u=history.getJSONObject(i);String pkg=u.getString("pkg");if(store.rule(pkg)==null)continue;long a=u.getLong("start"),b=u.getLong("end");while(a<b){long boundary=Math.min(b,Rules.nextBoundary(a,store.settings.getInt("start",540),store.settings.getInt("end",1260),ZoneId.systemDefault()));store.usage(a,boundary,pkg,UsageReader.label(this,pkg),store.managed(a),0);a=boundary;}}}catch(Exception ignored){}finally{main.post(this::policyChanged);}});
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(store==null||event.getPackageName()==null)return;String pkg=event.getPackageName().toString();
        if(systemPackages.contains(pkg))return;
        if(getSystemService(KeyguardManager.class).isKeyguardLocked()){if(!waitingUnlock){checkpoint();waitingUnlock=true;prepareUnlock();}return;}
        if(!interactive){interactive=getSystemService(PowerManager.class).isInteractive();if(!interactive)return;}
        if(waitingUnlock){waitingUnlock=false;prepareUnlock();}
        if(pkg.equals(getPackageName()))return; // overlay events never become the app beneath the overlay
        if(!UsageReader.launcherApp(this,pkg))return;
        if(!pkg.equals(foreground)){
            checkpoint();if(!sessionPkg.isEmpty()&&!pkg.equals(sessionPkg)){sessionPkg="";sessionRemaining=0;}
            foreground=pkg;segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();
            store.settings.edit().putString("last-package",pkg).putLong("checkpoint",segmentWall).apply();
            if(overlayVisible&&!gate.equals("unlock")&&!gatePkg.equals(pkg))hideOverlay();
        }
        enforce();ensurePulse();
    }
    void checkpoint(){
        if(store==null)return;long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();long delta=Math.max(0,elapsed-segmentElapsed);
        // Time changes cannot manufacture usage: reconstruct the wall start from monotonic elapsed time.
        long from=wall-delta;
        if(interactive&&!foreground.isEmpty()&&delta>0){
            String pkg=overlayVisible?getPackageName():foreground,label=overlayVisible?utility:UsageReader.label(this,pkg);boolean charge=!overlayVisible&&pkg.equals(sessionPkg);
            long at=from;while(at<wall){long boundary=Math.min(wall,Math.min(Rules.nextDay(at,ZoneId.systemDefault()),Rules.nextBoundary(at,store.settings.getInt("start",540),store.settings.getInt("end",1260),ZoneId.systemDefault())));boolean managed=store.managed(at);store.usage(at,boundary,pkg,label,charge&&managed,charge?sessionEvent:phoneEvent);if(charge&&managed)sessionRemaining=Math.max(0,sessionRemaining-(boundary-at));at=boundary;}
        }
        segmentWall=wall;segmentElapsed=elapsed;store.settings.edit().putLong("checkpoint",wall).putString("last-package",foreground).apply();
    }
    void flushOnMain(){if(Looper.myLooper()==Looper.getMainLooper()){checkpoint();return;}CountDownLatch latch=new CountDownLatch(1);main.post(()->{try{checkpoint();}finally{latch.countDown();}});try{latch.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    boolean shouldPulse(){return interactive&&!foreground.isEmpty()&&(!sessionPkg.isEmpty()||overlayVisible);}
    void ensurePulse(){main.removeCallbacks(pulse);if(shouldPulse())main.postDelayed(pulse,1000);}
    long dailyRemaining(String pkg){JSONObject rule=store.rule(pkg);return rule==null?0:Math.max(0,rule.optInt("minutes",60)*60000L-store.used(pkg,System.currentTimeMillis()));}
    void enforce(){
        long now=System.currentTimeMillis();boolean managed=store.managed(now);if(managed!=wasManaged){wasManaged=managed;if(!managed){sessionPkg="";sessionRemaining=0;if(overlayVisible&&!gate.equals("unlock"))hideOverlay();Notices.cancelTimer(this);}else{sessionPkg="";sessionRemaining=0;}Scheduler.schedule(this);}
        if(!interactive||getSystemService(KeyguardManager.class).isKeyguardLocked()||foreground.isEmpty())return;
        if(overlayVisible&&gate.equals("unlock"))return;
        if(!managed){if(overlayVisible&&!gate.equals("unlock"))hideOverlay();Notices.cancelTimer(this);return;}
        JSONObject rule=store.rule(foreground);if(rule==null){Notices.cancelTimer(this);return;}
        if(rule.optString("mode").equals("locked")||dailyRemaining(foreground)<=0){showGate("blocked",foreground);Notices.cancelTimer(this);return;}
        if(!foreground.equals(sessionPkg)){showGate("app",foreground);Notices.cancelTimer(this);return;}
        if(sessionRemaining<=0){showGate("renew",foreground);Notices.cancelTimer(this);return;}
        if(!overlayVisible&&(now/30000!=lastNotice)){lastNotice=now/30000;Notices.timer(this,rule.optString("label"),dailyRemaining(foreground),sessionRemaining);}
    }
    long lastNotice=-1;
    void prepareUnlock(){showGate("unlock","");utility="첫 화면";unlockChecks=0;}
    void showGate(String type,String pkg){
        if(overlayVisible&&gate.equals(type)&&gatePkg.equals(pkg))return;checkpoint();gate=type;gatePkg=pkg;utility=type.equals("unlock")?"첫 화면":"사용 확인";
        if(overlay==null){overlay=new WebHost(this,true);WindowManager.LayoutParams p=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.OPAQUE);p.gravity=Gravity.TOP|Gravity.START;p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;p.setTitle("Locky");window.addView(overlay.view,p);}
        overlayVisible=true;overlay.view.setVisibility(View.VISIBLE);overlay.screen(type);ensurePulse();
    }
    void hideOverlay(){checkpoint();overlayVisible=false;if(overlay!=null)overlay.view.setVisibility(View.GONE);segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();utility="첫 화면";}
    void choosePhone(JSONObject p) throws Exception {checkpoint();phoneEvent=store.event("unlock",p.getString("label"),"",p.optString("pkg"),0);hideOverlay();launch(p);enforce();ensurePulse();}
    void allowApp(JSONObject p,int minutes) throws Exception {
        checkpoint();long remaining=dailyRemaining(gatePkg);JSONObject rule=store.rule(gatePkg);if(!store.managed(System.currentTimeMillis())){hideOverlay();return;}
        if(rule==null||rule.optString("mode").equals("locked")||remaining<=0){enforce();throw new IllegalStateException("지금은 사용할 수 없는 앱입니다.");}
        if(!gatePkg.equals(foreground))throw new IllegalStateException("앱이 바뀌었습니다. 다시 열어 주세요.");
        sessionPkg=gatePkg;purpose=p.getString("label");sessionRemaining=Rules.grant(remaining,minutes);sessionEvent=store.event("app",purpose,gatePkg,p.optString("pkg"),phoneEvent);hideOverlay();lastNotice=-1;Notices.timer(this,rule.optString("label"),remaining,sessionRemaining);launch(p);ensurePulse();
    }
    void extend(int minutes) {checkpoint();if(!sessionPkg.equals(gatePkg)||dailyRemaining(gatePkg)<=0){enforce();return;}sessionRemaining=Rules.grant(dailyRemaining(gatePkg),minutes);store.event("extend",purpose,sessionPkg,"",phoneEvent);hideOverlay();lastNotice=-1;enforce();ensurePulse();}
    void stopApp(){checkpoint();sessionRemaining=0;sessionPkg="";performGlobalAction(GLOBAL_ACTION_HOME);foreground="";hideOverlay();Notices.cancelTimer(this);main.removeCallbacks(pulse);}
    void useUtility(String name){checkpoint();utility=name;store.event("utility",name,getPackageName(),"",phoneEvent);}
    void pauseForActivity(){checkpoint();foreground=getPackageName();Notices.cancelTimer(this);}
    void openMain(String screen){useUtility(screen.equals("chats")?"ChatGPT":screen.equals("notes")?"메모":"Locky");hideOverlay();startActivity(new Intent(this,MainActivity.class).putExtra("screen",screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    void launch(JSONObject p) throws Exception {String pkg=p.optString("pkg"),uri=p.optString("uri");if(pkg.isEmpty()&&uri.isEmpty())return;Intent i=Links.intent(this,pkg,uri);if(i==null)throw new IllegalStateException("연결한 앱이나 동작을 열 수 없습니다. 목적 연결을 확인해 주세요.");startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    void policyChanged(){main.post(()->{checkpoint();enforce();ensurePulse();WebHost.refreshAll();});}
    JSONObject state() throws JSONException {return new JSONObject().put("mode",gate).put("pkg",gatePkg).put("label",UsageReader.label(this,gatePkg)).put("purpose",purpose).put("daily",dailyRemaining(gatePkg.isEmpty()?sessionPkg:gatePkg)).put("session",sessionRemaining).put("activePkg",sessionPkg).put("free",!store.managed(System.currentTimeMillis()));}
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){checkpoint();live=null;main.removeCallbacksAndMessages(null);try{unregisterReceiver(screen);}catch(Exception ignored){}if(overlay!=null){window.removeView(overlay.view);overlay.close();}Notices.cancelTimer(this);super.onDestroy();}
}
