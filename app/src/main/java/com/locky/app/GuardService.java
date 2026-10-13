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
    final Handler main=androidx.core.os.HandlerCompat.createAsync(Looper.getMainLooper());Store store;
    volatile String foreground="",sessionPkg="",purpose="",utility="첫 화면";long segmentWall,segmentElapsed,sessionEvent,phoneEvent;volatile long sessionRemaining;
    boolean interactive,waitingUnlock,wasManaged,systemPaused;volatile boolean overlayVisible;volatile String gate="unlock",gatePkg="";
    WebHost overlay;WindowManager window;final Set<String> systemPackages=new HashSet<>();
    String homePackage="";
    volatile boolean pulseScheduled;volatile long pulseCount;
    final Runnable pulse=new Runnable(){public void run(){pulseScheduled=false;if(live!=GuardService.this)return;pulseCount++;checkpoint();enforce();ensurePulse();}};
    final BroadcastReceiver screen=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String a=i.getAction();if(Intent.ACTION_SCREEN_OFF.equals(a)){checkpoint();interactive=false;sessionPkg="";sessionRemaining=0;foreground="";store.settings.edit().putBoolean("checkpoint-interactive",false).putString("last-package","").apply();waitingUnlock=true;prepareUnlock();Notices.cancelTimer(c);main.removeCallbacks(pulse);pulseScheduled=false;}
        else if(Intent.ACTION_USER_PRESENT.equals(a)){interactive=true;waitingUnlock=false;observeForeground(null);prepareUnlock();ensurePulse();}
        else if(Intent.ACTION_SCREEN_ON.equals(a)){interactive=true;main.post(checkUnlock);}
        else if(Intent.ACTION_TIME_CHANGED.equals(a)||Intent.ACTION_TIMEZONE_CHANGED.equals(a)){checkpoint();policyChanged();Scheduler.schedule(c);}
    }};
    int unlockChecks;
    final Runnable checkUnlock=new Runnable(){public void run(){if(!interactive)return;if(!getSystemService(KeyguardManager.class).isKeyguardLocked()){if(waitingUnlock){waitingUnlock=false;observeForeground(null);prepareUnlock();}ensurePulse();}else if(unlockChecks++<20)main.postDelayed(this,250);}};
    @Override protected void onServiceConnected(){
        live=this;store=Store.get(this);window=getSystemService(WindowManager.class);interactive=getSystemService(PowerManager.class).isInteractive();wasManaged=store.managed(System.currentTimeMillis());
        ResolveInfo home=getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);if(home!=null)homePackage=home.activityInfo.packageName;
        systemPackages.add("com.android.systemui");systemPackages.add("com.android.permissioncontroller");systemPackages.add("com.google.android.permissioncontroller");
        android.view.inputmethod.InputMethodManager ime=getSystemService(android.view.inputmethod.InputMethodManager.class);for(android.view.inputmethod.InputMethodInfo x:ime.getEnabledInputMethodList())systemPackages.add(x.getPackageName());
        IntentFilter f=new IntentFilter();for(String a:new String[]{Intent.ACTION_SCREEN_OFF,Intent.ACTION_SCREEN_ON,Intent.ACTION_USER_PRESENT,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED})f.addAction(a);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screen,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screen,f);
        segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();
        long recoveryFrom=store.settings.getLong("checkpoint",System.currentTimeMillis());String recoveryPackage=store.settings.getString("last-package","");
        waitingUnlock=!interactive||getSystemService(KeyguardManager.class).isKeyguardLocked();
        if(waitingUnlock)prepareUnlock();else {observeForeground(null);main.postDelayed(()->{if(foreground.isEmpty())observeForeground(null);},250);main.postDelayed(()->{if(foreground.isEmpty())observeForeground(null);},750);}Scheduler.schedule(this);recover(recoveryFrom,store.settings.getBoolean("checkpoint-interactive",true)?recoveryPackage:"");
    }
    private void recover(long from,String old){long now=System.currentTimeMillis();
        if(now-from<2000)return;
        LockyApp.app.work.execute(()->{try{JSONArray history=UsageReader.intervals(this,Math.max(from,now-180L*86400000),now,old);for(int i=0;i<history.length();i++){JSONObject u=history.getJSONObject(i);String pkg=u.getString("pkg");if(!UsageReader.recordable(this,pkg)||pkg.equals(getPackageName()))continue;JSONObject rule=store.rule(pkg);long a=u.getLong("start"),b=u.getLong("end");while(a<b){long boundary=Math.min(b,Math.min(Rules.nextDay(a,ZoneId.systemDefault()),Rules.nextBoundary(a,store.settings.getInt("start",540),store.settings.getInt("end",1260),ZoneId.systemDefault())));store.usage(a,boundary,pkg,UsageReader.label(this,pkg),rule!=null&&rule.optString("mode").equals("limited")&&store.managed(a),0);a=boundary;}}}catch(Exception ignored){}finally{main.post(this::policyChanged);}});
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(store==null)return;
        interactive=getSystemService(PowerManager.class).isInteractive();
        if(!interactive||getSystemService(KeyguardManager.class).isKeyguardLocked())return;
        if(waitingUnlock){waitingUnlock=false;prepareUnlock();}
        observeForeground(event);
    }
    boolean overview;volatile String lastWindows="";
    void observeForeground(AccessibilityEvent event) {
        if(store==null||!interactive||getSystemService(KeyguardManager.class).isKeyguardLocked())return;
        List<ForegroundPolicy.Window> candidates=new ArrayList<>();StringBuilder diagnostic=new StringBuilder();
        for(AccessibilityWindowInfo w:getWindows()) {
            String pkg="";
            // Only obtain the window root's package; never traverse content or read text.
            if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION){AccessibilityNodeInfo root=w.getRoot();if(root!=null){CharSequence name=root.getPackageName();pkg=name==null?"":name.toString();root.recycle();}}
            android.graphics.Rect bounds=new android.graphics.Rect();w.getBoundsInScreen(bounds);boolean covering=bounds.height()>getResources().getDisplayMetrics().heightPixels/2;
            candidates.add(new ForegroundPolicy.Window(pkg,w.getType(),w.isFocused(),w.isActive(),w.getLayer(),w.isInPictureInPictureMode(),covering));diagnostic.append(pkg).append(":type=").append(w.getType()).append(",focus=").append(w.isFocused()).append(",active=").append(w.isActive()).append(",layer=").append(w.getLayer()).append(",cover=").append(covering).append(";");
        }
        lastWindows=diagnostic.toString();ForegroundPolicy.Result visible=ForegroundPolicy.select(candidates,systemPackages);
        if(event!=null&&event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&event.getClassName()!=null){
            String name=event.getClassName().toString().toLowerCase(Locale.ROOT),pkg=event.getPackageName()==null?"":event.getPackageName().toString();
            if(pkg.equals(homePackage)&&(name.contains("recents")||name.contains("overview")))overview=true;
            else if(pkg.equals(homePackage)&&name.contains("launcher"))overview=false;
        }
        if(!visible.pkg.isEmpty()&&!visible.pkg.equals(homePackage)&&!systemPackages.contains(visible.pkg))overview=false;
        if(visible.paused||overview){pauseSystem();return;}
        if(visible.pkg.isEmpty())return; // transient/root unavailable: do not trust background event senders
        String pkg=visible.pkg;
        if(pkg.contains("incallui")||pkg.equals("com.android.server.telecom")){if(overlayVisible)hideOverlay();pauseSystem();return;}
        switchForeground(pkg);
    }
    void pauseSystem(){
        boolean changed=!systemPaused;if(changed){checkpoint();systemPaused=true;lastNotice=-1;}
        if(overlayVisible&&overlay!=null)overlay.root.setVisibility(View.INVISIBLE);
        if(changed&&!sessionPkg.isEmpty())Notices.paused(this,UsageReader.label(this,sessionPkg),dailyRemaining(sessionPkg),sessionRemaining);
        ensurePulse();
    }
    void switchForeground(String pkg){
        if(systemPaused){checkpoint();systemPaused=false;lastNotice=-1;}
        if(!pkg.equals(foreground)) {
            checkpoint();
            // Locky's time-detail Activity pauses a grant; real app/home transitions end it.
            if(!sessionPkg.isEmpty()&&!pkg.equals(sessionPkg)&&!pkg.equals(getPackageName())){sessionPkg="";sessionRemaining=0;}
            foreground=pkg;segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();
            store.settings.edit().putString("last-package",pkg).putLong("checkpoint",segmentWall).putBoolean("checkpoint-interactive",interactive).apply();
            if(overlayVisible&&!gate.equals("unlock")&&!gatePkg.equals(pkg))hideOverlay();
        }
        if(overlayVisible&&overlay!=null)overlay.root.setVisibility(View.VISIBLE);
        enforce();ensurePulse();
    }
    void checkpoint(){
        if(store==null)return;long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();long delta=Math.max(0,elapsed-segmentElapsed);
        // Time changes cannot manufacture usage: reconstruct the wall start from monotonic elapsed time.
        long from=Math.abs((wall-segmentWall)-delta)<2000?segmentWall:wall-delta;
        if(interactive&&!foreground.isEmpty()&&delta>0){
            String pkg=(overlayVisible||systemPaused)?getPackageName():foreground,label=systemPaused?"사용 확인":(overlayVisible||pkg.equals(getPackageName()))?utility:UsageReader.label(this,pkg);
            if(pkg.equals(homePackage)){segmentWall=wall;segmentElapsed=elapsed;store.settings.edit().putLong("checkpoint",wall).putString("last-package",foreground).putBoolean("checkpoint-interactive",interactive).apply();return;}boolean charge=!overlayVisible&&!systemPaused&&pkg.equals(sessionPkg);
            long at=from;while(at<wall){long boundary=Math.min(wall,Math.min(Rules.nextDay(at,ZoneId.systemDefault()),Rules.nextBoundary(at,store.settings.getInt("start",540),store.settings.getInt("end",1260),ZoneId.systemDefault())));boolean managed=store.managed(at);store.usage(at,boundary,pkg,label,charge&&managed,charge?sessionEvent:phoneEvent);if(charge&&managed)sessionRemaining=Math.max(0,sessionRemaining-(boundary-at));at=boundary;}
        }
        segmentWall=wall;segmentElapsed=elapsed;store.settings.edit().putLong("checkpoint",wall).putString("last-package",foreground).putBoolean("checkpoint-interactive",interactive).apply();
    }
    void flushOnMain(){if(Looper.myLooper()==Looper.getMainLooper()){checkpoint();return;}CountDownLatch latch=new CountDownLatch(1);main.post(()->{try{checkpoint();}finally{latch.countDown();}});try{latch.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    boolean shouldPulse(){return interactive&&!foreground.isEmpty()&&(!sessionPkg.isEmpty()||overlayVisible);}
    void ensurePulse(){if(shouldPulse()){if(!pulseScheduled){pulseScheduled=true;main.postDelayed(pulse,1000);}}else{main.removeCallbacks(pulse);pulseScheduled=false;}}
    long dailyRemaining(String pkg){return store.remaining(pkg,System.currentTimeMillis());}
    void enforce(){
        long now=System.currentTimeMillis();boolean managed=store.managed(now);if(managed!=wasManaged){wasManaged=managed;if(!managed){sessionPkg="";sessionRemaining=0;if(overlayVisible&&!gate.equals("unlock"))hideOverlay();Notices.cancelTimer(this);}else{sessionPkg="";sessionRemaining=0;}Scheduler.schedule(this);}
        if(!interactive||systemPaused||foreground.equals(getPackageName())||getSystemService(KeyguardManager.class).isKeyguardLocked()||foreground.isEmpty())return;
        if(overlayVisible&&gate.equals("unlock"))return;
        if(!managed){if(overlayVisible&&!gate.equals("unlock"))hideOverlay();Notices.cancelTimer(this);return;}
        JSONObject rule=store.rule(foreground);if(rule==null){if(overlayVisible&&!gate.equals("unlock"))hideOverlay();Notices.cancelTimer(this);return;}
        if(rule.optString("mode").equals("locked")||dailyRemaining(foreground)<=0){if(rule.optString("mode").equals("limited"))store.blockUntilFree(foreground,now);showGate("blocked",foreground);Notices.cancelTimer(this);return;}
        if(!foreground.equals(sessionPkg)){showGate("app",foreground);Notices.cancelTimer(this);return;}
        if(sessionRemaining<=0){showGate("renew",foreground);Notices.cancelTimer(this);return;}
        if(!overlayVisible&&(now/30000!=lastNotice)){lastNotice=now/30000;Notices.timer(this,rule.optString("label"),dailyRemaining(foreground),sessionRemaining);}
    }
    long lastNotice=-1;
    void prepareUnlock(){showGate("unlock","");utility="첫 화면";unlockChecks=0;}
    void showGate(String type,String pkg){
        if(overlayVisible&&gate.equals(type)&&gatePkg.equals(pkg)){if(overlay!=null&&interactive&&!getSystemService(KeyguardManager.class).isKeyguardLocked())overlay.root.setVisibility(View.VISIBLE);return;}checkpoint();gate=type;gatePkg=pkg;utility=type.equals("unlock")?"첫 화면":"사용 확인";
        if(overlay==null){overlay=new WebHost(this,true);WindowManager.LayoutParams p=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.OPAQUE);p.gravity=Gravity.TOP|Gravity.START;p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;p.setTitle("Locky");window.addView(overlay.root,p);}
        overlayVisible=true;overlay.root.setVisibility(interactive&&!getSystemService(KeyguardManager.class).isKeyguardLocked()?View.VISIBLE:View.INVISIBLE);overlay.screen(type);ensurePulse();
    }
    void hideOverlay(){checkpoint();overlayVisible=false;if(overlay!=null)overlay.root.setVisibility(View.GONE);segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();utility="첫 화면";}
    void choosePhone(JSONObject p) throws Exception {checkpoint();phoneEvent=store.event("unlock",p.getString("label"),"",p.optString("pkg"),0,p.optInt("aimless",0)==1);hideOverlay();launch(p);enforce();ensurePulse();}
    void allowApp(JSONObject p,int minutes) throws Exception {
        checkpoint();long remaining=dailyRemaining(gatePkg);JSONObject rule=store.rule(gatePkg);if(!store.managed(System.currentTimeMillis())){hideOverlay();return;}
        if(rule==null||rule.optString("mode").equals("locked")||remaining<=0){enforce();throw new IllegalStateException("지금은 사용할 수 없는 앱입니다.");}
        if(!gatePkg.equals(foreground))throw new IllegalStateException("앱이 바뀌었습니다. 다시 열어 주세요.");
        sessionPkg=gatePkg;purpose=p.getString("label");sessionRemaining=Rules.grant(remaining,minutes);sessionEvent=store.event("app",purpose,gatePkg,p.optString("pkg"),phoneEvent,p.optInt("aimless",0)==1);hideOverlay();lastNotice=-1;Notices.timer(this,rule.optString("label"),remaining,sessionRemaining);launch(p);ensurePulse();
    }
    void extend(int minutes) {checkpoint();if(!sessionPkg.equals(gatePkg)||dailyRemaining(gatePkg)<=0){enforce();return;}sessionRemaining=Rules.grant(dailyRemaining(gatePkg),minutes);store.event("extend",purpose,sessionPkg,"",phoneEvent);hideOverlay();lastNotice=-1;enforce();ensurePulse();}
    void stopApp(){checkpoint();sessionRemaining=0;sessionPkg="";performGlobalAction(GLOBAL_ACTION_HOME);foreground="";hideOverlay();Notices.cancelTimer(this);main.removeCallbacks(pulse);pulseScheduled=false;}
    void useUtility(String name){checkpoint();boolean changed=!name.equals(utility);utility=name;if(changed&&!name.equals("첫 화면")&&!name.equals("Locky"))store.event("utility",name,getPackageName(),"",phoneEvent);}
    void pauseForActivity(){switchForeground(getPackageName());if(!sessionPkg.isEmpty())Notices.paused(this,UsageReader.label(this,sessionPkg),dailyRemaining(sessionPkg),sessionRemaining);}
    void openMain(String screen){useUtility(screen.equals("chats")?"ChatGPT":screen.equals("notes")?"메모":"Locky");hideOverlay();startActivity(new Intent(this,MainActivity.class).putExtra("screen",screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    void launch(JSONObject p) throws Exception {String pkg=p.optString("pkg"),uri=p.optString("uri");if(pkg.isEmpty()&&uri.isEmpty())return;Intent i=Links.intent(this,pkg,uri);if(i==null)throw new IllegalStateException("연결한 앱이나 동작을 열 수 없습니다. 목적 연결을 확인해 주세요.");startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    void policyChanged(){main.post(()->{observeForeground(null);checkpoint();enforce();ensurePulse();WebHost.refreshAll();});}
    JSONObject state() throws JSONException {String pkg=overlayVisible?gatePkg:sessionPkg;return new JSONObject().put("mode",gate).put("pkg",pkg).put("label",UsageReader.label(this,pkg)).put("purpose",purpose).put("daily",dailyRemaining(pkg)).put("session",sessionRemaining).put("activePkg",sessionPkg).put("free",!store.managed(System.currentTimeMillis())).put("paused",systemPaused||foreground.equals(getPackageName())).put("visible",overlayVisible);}
    void dismissStart(){
        if(gate.equals("unlock")){checkpoint();store.event("dismiss","열지 않기","","",0);waitingUnlock=false;hideOverlay();sessionPkg="";sessionRemaining=0;performGlobalAction(GLOBAL_ACTION_HOME);foreground="";Notices.cancelTimer(this);ensurePulse();
            if(Build.VERSION.SDK_INT>=28)performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }else stopApp();
    }
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){checkpoint();live=null;main.removeCallbacksAndMessages(null);try{unregisterReceiver(screen);}catch(Exception ignored){}if(overlay!=null){window.removeView(overlay.root);overlay.close();}Notices.cancelTimer(this);super.onDestroy();}
}
