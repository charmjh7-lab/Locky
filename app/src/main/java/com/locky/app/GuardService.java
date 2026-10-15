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
    volatile boolean interactive,wasManaged,systemPaused;volatile boolean overlayVisible;volatile String gate="",gatePkg="";
    static final long SESSION_RETURN_GRACE=5*60_000L;
    static final class PausedSession {
        final long remaining,event,until,policyEnd;final String purpose;
        PausedSession(long remaining,long event,String purpose,long until,long policyEnd){this.remaining=remaining;this.event=event;this.purpose=purpose;this.until=until;this.policyEnd=policyEnd;}
    }
    final Map<String,PausedSession> pausedSessions=new HashMap<>();
    long sessionPolicyEnd;
    FloatingTimer floatingTimer;
    PhoneStartController phoneStart;
    boolean lockPending;
    android.widget.FrameLayout overlayWindow;
    enum Exposure { HIDDEN, WAITING, INTERACTIVE, SUSPENDED }
    Exposure exposure=Exposure.HIDDEN;
    Boolean observedKeyguard;
    WebHost overlay;WindowManager window;WindowManager.LayoutParams overlayLayout;final Set<String> systemPackages=new HashSet<>();
    String homePackage="";
    volatile boolean pulseScheduled;volatile long pulseCount;
    final Runnable pulse=new Runnable(){public void run(){pulseScheduled=false;if(live!=GuardService.this)return;pulseCount++;checkpoint();enforce();ensurePulse();}};
    final BroadcastReceiver screen=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String a=i.getAction();
        if(Intent.ACTION_SCREEN_ON.equals(a)&&UnlockDiagnostics.live!=null)UnlockDiagnostics.live.resumeCapture();
        if(!Intent.ACTION_SCREEN_OFF.equals(a))WebHost.trace("broadcast observed="+(Intent.ACTION_SCREEN_ON.equals(a)?"screen-on":Intent.ACTION_USER_PRESENT.equals(a)?"user-present":"clock"));
        if(Intent.ACTION_SCREEN_OFF.equals(a))screenOff();
        else if(Intent.ACTION_USER_PRESENT.equals(a)||Intent.ACTION_SCREEN_ON.equals(a)){
            interactive=getSystemService(PowerManager.class).isInteractive();
            completeUnlock();
        }else if(Intent.ACTION_TIME_CHANGED.equals(a)||Intent.ACTION_TIMEZONE_CHANGED.equals(a)){checkpoint();policyChanged();Scheduler.schedule(c);}
    }};
    void screenOff(){
        if(!interactive&&phoneStart!=null&&phoneStart.awaitingUnlock()){WebHost.trace("duplicate screen-off ignored");return;}
        UnlockDiagnostics.get(this);WebHost.beginUnlockTrace();
        checkpoint();interactive=false;
        if(lockPending){store.event("dismiss","열지 않기","","",0);lockPending=false;}
        main.removeCallbacks(lockCompletion);
        overview=false;overviewCandidate="";clearSessions();foreground="";
        store.settings.edit().putBoolean("checkpoint-interactive",false).putString("last-package","").apply();
        setSystemPaused(false,"screen-off");hideOverlay();
        if(phoneStart==null)phoneStart=new PhoneStartController(this);
        phoneStart.screenOff();
        Notices.cancelTimer(this);main.removeCallbacks(pulse);pulseScheduled=false;
    }
    final Runnable settledForeground=()->observeForeground(null);
    boolean completeUnlock(){
        if(phoneStart==null||!phoneStart.unlocked())return false;
        setSystemPaused(false,"unlock-confirmed");ensurePulse();return true;
    }
    @Override protected void onServiceConnected(){
        live=this;store=Store.get(this);window=getSystemService(WindowManager.class);floatingTimer=new FloatingTimer(this,window);interactive=getSystemService(PowerManager.class).isInteractive();wasManaged=store.managed(System.currentTimeMillis());store.observe("service-connected",interactive);
        phoneStart=new PhoneStartController(this);
        ResolveInfo home=getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);if(home!=null)homePackage=home.activityInfo.packageName;
        systemPackages.add("com.android.systemui");systemPackages.add("com.android.permissioncontroller");systemPackages.add("com.google.android.permissioncontroller");
        android.view.inputmethod.InputMethodManager ime=getSystemService(android.view.inputmethod.InputMethodManager.class);for(android.view.inputmethod.InputMethodInfo x:ime.getEnabledInputMethodList())systemPackages.add(x.getPackageName());
        IntentFilter f=new IntentFilter();for(String a:new String[]{Intent.ACTION_SCREEN_OFF,Intent.ACTION_SCREEN_ON,Intent.ACTION_USER_PRESENT,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED})f.addAction(a);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screen,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screen,f);
        segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();
        long recoveryFrom=store.settings.getLong("checkpoint",System.currentTimeMillis());String recoveryPackage=store.settings.getString("last-package","");
        if(!interactive||keyguardLocked())phoneStart.prepareAfterServiceRestart();else {completeUnlock();observeForeground(null);main.postDelayed(()->{if(foreground.isEmpty())observeForeground(null);},250);main.postDelayed(()->{if(foreground.isEmpty())observeForeground(null);},750);}Scheduler.schedule(this);recover(recoveryFrom,store.settings.getBoolean("checkpoint-interactive",true)?recoveryPackage:"");
    }
    private void recover(long from,String old){long now=System.currentTimeMillis();
        if(now-from<2000)return;
        LockyApp.app.work.execute(()->{try{JSONArray history=UsageReader.intervals(this,Math.max(from,now-180L*86400000),now,old);for(int i=0;i<history.length();i++){JSONObject u=history.getJSONObject(i);String pkg=u.getString("pkg");if(!UsageReader.recordable(this,pkg)||pkg.equals(getPackageName()))continue;JSONObject rule=store.rule(pkg);long a=u.getLong("start"),b=u.getLong("end");while(a<b){long boundary=Math.min(b,Math.min(Rules.nextDay(a,ZoneId.systemDefault()),Rules.nextBoundary(a,store.settings.getInt("start",540),store.settings.getInt("end",1260),ZoneId.systemDefault())));store.usage(a,boundary,pkg,UsageReader.label(this,pkg),rule!=null&&rule.optString("mode").equals("limited")&&store.managed(a),0,"usage-access");a=boundary;}}}catch(Exception ignored){}finally{main.post(this::policyChanged);}});
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(store==null)return;
        interactive=getSystemService(PowerManager.class).isInteractive();
        if(!interactive||keyguardLocked())return;
        completeUnlock();
        observeForeground(event);
        // Window events can precede availability of the new application's root.
        // Recheck after the transition rather than retaining an obsolete session.
        main.removeCallbacks(settledForeground);
        main.postDelayed(settledForeground,500);
    }
    boolean keyguardLocked(){
        boolean locked=getSystemService(KeyguardManager.class).isKeyguardLocked();
        if(observedKeyguard==null||observedKeyguard!=locked){observedKeyguard=locked;WebHost.trace("keyguard observed locked="+locked);}
        return locked;
    }
    boolean acceptGateInput(){return overlayVisible&&exposure==Exposure.INTERACTIVE&&interactive&&!systemPaused&&!keyguardLocked();}
    void requestEditorInput(){
        if(!acceptGateInput()||overlay==null)return;
        overlay.view.requestFocus();
        getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(overlay.view,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }
    void setSystemPaused(boolean value,String reason){if(systemPaused!=value){systemPaused=value;WebHost.trace("systemPaused="+value+" reason="+reason);}}
    boolean overview;String overviewCandidate="";long overviewCandidateSince;volatile String lastWindows="";
    void observeForeground(AccessibilityEvent event) {
        if(store==null||!interactive||keyguardLocked())return;
        List<ForegroundPolicy.Window> candidates=new ArrayList<>();StringBuilder diagnostic=new StringBuilder();
        WebHost.trace("accessibility observed event="+(event==null?0:event.getEventType())+" changes="+(event!=null&&Build.VERSION.SDK_INT>=28?event.getWindowChanges():0)+" window="+(event==null?-1:event.getWindowId()));
        for(AccessibilityWindowInfo w:getWindows()) {
            String pkg="";
            if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION||w.getType()==AccessibilityWindowInfo.TYPE_SYSTEM){AccessibilityNodeInfo root=w.getRoot();if(root!=null){CharSequence name=root.getPackageName();pkg=name==null?"":name.toString();root.recycle();}}
            if(pkg.equals("com.samsung.android.sidegesturepad"))continue;
            android.graphics.Rect bounds=new android.graphics.Rect();w.getBoundsInScreen(bounds);boolean covering=bounds.height()>getResources().getDisplayMetrics().heightPixels/2;
            candidates.add(new ForegroundPolicy.Window(pkg,w.getType(),w.isFocused(),w.isActive(),w.getLayer(),w.isInPictureInPictureMode(),covering));diagnostic.append(pkg).append(":type=").append(w.getType()).append(",focus=").append(w.isFocused()).append(",active=").append(w.isActive()).append(",layer=").append(w.getLayer()).append(",cover=").append(covering).append(";");
        }
        lastWindows=diagnostic.toString();ForegroundPolicy.Result visible=ForegroundPolicy.select(candidates,systemPackages);
        if(event!=null&&event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&event.getClassName()!=null){
            String name=event.getClassName().toString().toLowerCase(Locale.ROOT),pkg=event.getPackageName()==null?"":event.getPackageName().toString();
            boolean systemLauncher=pkg.equals(homePackage)||pkg.equals("com.android.systemui")||pkg.contains("launcher");
            if(systemLauncher&&(name.contains("recents")||name.contains("overview")||name.contains("recentapps"))){overview=true;overviewCandidate="";}
            else if(pkg.equals(homePackage)&&name.contains("launcher")&&visible.pkg.equals(homePackage)){overview=false;overviewCandidate="";}
        }
        // A recent-task thumbnail can expose its old app window before the user actually enters it.
        // Require the target application window to hold focus through a short settle period.
        if(overview){
            boolean focusedApp=false;
            for(ForegroundPolicy.Window w:candidates)if(w.type==AccessibilityWindowInfo.TYPE_APPLICATION&&w.pkg.equals(visible.pkg)&&w.focused)focusedApp=true;
            if(visible.paused||visible.pkg.isEmpty()||visible.pkg.equals(homePackage)||!focusedApp){overviewCandidate="";pauseSystem();return;}
            long now=SystemClock.uptimeMillis();
            if(!visible.pkg.equals(overviewCandidate)){overviewCandidate=visible.pkg;overviewCandidateSince=now;main.postDelayed(settledForeground,350);pauseSystem();return;}
            if(now-overviewCandidateSince<300){pauseSystem();return;}
            overview=false;overviewCandidate="";
        }
        if(visible.paused){pauseSystem();return;}
        if(visible.pkg.isEmpty())return; // transient/root unavailable: do not trust background event senders
        String pkg=visible.pkg;
        if(pkg.contains("incallui")||pkg.equals("com.android.server.telecom")){if(overlayVisible)hideOverlay();pauseSystem();return;}
        switchForeground(pkg);
    }
    void pauseSystem(){pauseSystem("system");}
    void pauseSystem(String reason){
        if(lockPending)return;
        hideFloatingTimer();
        boolean changed=!systemPaused;if(changed){checkpoint();setSystemPaused(true,reason);lastNotice=-1;}
        if(overlayVisible)revealOverlay();
        if(changed&&!sessionPkg.isEmpty())Notices.paused(this,UsageReader.label(this,sessionPkg),dailyRemaining(sessionPkg),sessionRemaining);
        ensurePulse();
    }
    void switchForeground(String pkg){
        if(systemPaused){checkpoint();setSystemPaused(false,"foreground");lastNotice=-1;}
        if(!pkg.equals(foreground)) {
            checkpoint();
            // Preserve each app's original grant; time away never buys a new grant.
            if(!sessionPkg.isEmpty()&&!pkg.equals(sessionPkg)&&!pkg.equals(getPackageName()))parkSession();
            foreground=pkg;segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();
            store.settings.edit().putString("last-package",pkg).putLong("checkpoint",segmentWall).putBoolean("checkpoint-interactive",interactive).apply();
            if(overlayVisible&&!gatePkg.equals(pkg))hideOverlay();
        }
        if(!pkg.equals(getPackageName())&&!pkg.equals(sessionPkg))restoreSession(pkg);
        revealOverlay();
        enforce();ensurePulse();
    }
    void parkSession(){
        if(sessionPkg.isEmpty())return;
        pausedSessions.put(sessionPkg,new PausedSession(sessionRemaining,sessionEvent,purpose,SystemClock.elapsedRealtime()+SESSION_RETURN_GRACE,sessionPolicyEnd));
        clearActiveSession();
    }
    void clearActiveSession(){sessionPkg="";sessionRemaining=0;sessionEvent=0;purpose="";sessionPolicyEnd=0;}
    void clearSessions(){pausedSessions.clear();clearActiveSession();}
    long policyEnd(long now){return Math.min(Rules.nextDay(now,ZoneId.systemDefault()),Rules.nextBoundary(now,store.settings.getInt("start",540),store.settings.getInt("end",1260),ZoneId.systemDefault()));}
    void restoreSession(String pkg){
        long now=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();
        pausedSessions.entrySet().removeIf(e->elapsed>e.getValue().until||now>=e.getValue().policyEnd);
        PausedSession saved=pausedSessions.remove(pkg);JSONObject rule=store.rule(pkg);
        if(saved==null||!store.managed(now)||rule==null||!"limited".equals(rule.optString("mode"))||dailyRemaining(pkg)<=0)return;
        sessionPkg=pkg;sessionRemaining=saved.remaining;sessionEvent=saved.event;purpose=saved.purpose;sessionPolicyEnd=saved.policyEnd;lastNotice=-1;
    }
    void checkpoint(){
        if(store==null)return;store.observe("checkpoint",interactive);long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();long delta=Math.max(0,elapsed-segmentElapsed);
        // Time changes cannot manufacture usage: reconstruct the wall start from monotonic elapsed time.
        long from=Math.abs((wall-segmentWall)-delta)<2000?segmentWall:wall-delta;
        if(interactive&&!foreground.isEmpty()&&delta>0){
            boolean covering=overlayVisible;String pkg=(covering||systemPaused)?getPackageName():foreground,label=systemPaused?"사용 확인":(covering||pkg.equals(getPackageName()))?utility:UsageReader.label(this,pkg);
            if(pkg.equals(homePackage)){segmentWall=wall;segmentElapsed=elapsed;store.settings.edit().putLong("checkpoint",wall).putString("last-package",foreground).putBoolean("checkpoint-interactive",interactive).apply();return;}boolean charge=!overlayVisible&&!systemPaused&&pkg.equals(sessionPkg)&&!UsageReader.excluded(this,pkg);
            long at=from;while(at<wall){long boundary=Math.min(wall,Math.min(Rules.nextDay(at,ZoneId.systemDefault()),Rules.nextBoundary(at,store.settings.getInt("start",540),store.settings.getInt("end",1260),ZoneId.systemDefault())));boolean managed=store.managed(at);store.usage(at,boundary,pkg,label,charge&&managed,charge?sessionEvent:phoneEvent);if(charge&&managed)sessionRemaining=Math.max(0,sessionRemaining-(boundary-at));at=boundary;}
        }
        segmentWall=wall;segmentElapsed=elapsed;store.settings.edit().putLong("checkpoint",wall).putString("last-package",foreground).putBoolean("checkpoint-interactive",interactive).apply();
    }
    void flushOnMain(){if(Looper.myLooper()==Looper.getMainLooper()){checkpoint();return;}CountDownLatch latch=new CountDownLatch(1);main.post(()->{try{checkpoint();}finally{latch.countDown();}});try{latch.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    boolean shouldPulse(){return interactive&&!foreground.isEmpty()&&(!sessionPkg.isEmpty()||overlayVisible);}
    void ensurePulse(){if(shouldPulse()){if(!pulseScheduled){pulseScheduled=true;main.postDelayed(pulse,1000);}}else{main.removeCallbacks(pulse);pulseScheduled=false;}}
    long dailyRemaining(String pkg){return store.remaining(pkg,System.currentTimeMillis());}
    void enforce(){
        try {
        long now=System.currentTimeMillis();boolean managed=store.managed(now);if(managed!=wasManaged){wasManaged=managed;if(!managed){clearSessions();if(overlayVisible)hideOverlay();Notices.cancelTimer(this);}else{clearSessions();}Scheduler.schedule(this);}
        if(!sessionPkg.isEmpty()&&sessionPolicyEnd>0&&now>=sessionPolicyEnd)clearSessions();
        PhoneStartActivity phone=PhoneStartActivity.current();
        if(phone!=null&&((phone.resumed&&!phone.chosen)||phone.allowsDictionary(foreground)))return;
        if(!interactive||systemPaused||foreground.equals(getPackageName())||keyguardLocked()||foreground.isEmpty())return;
        if(!LockyApp.app.authBrowser.isEmpty()&&foreground.equals(LockyApp.app.authBrowser)){if(overlayVisible)hideOverlay();Notices.cancelTimer(this);return;}
        if(!managed){if(overlayVisible)hideOverlay();Notices.cancelTimer(this);return;}
        JSONObject rule=store.rule(foreground);if(rule==null){if(overlayVisible)hideOverlay();Notices.cancelTimer(this);return;}
        if(rule.optString("mode").equals("locked")||dailyRemaining(foreground)<=0){if(rule.optString("mode").equals("limited"))store.blockUntilFree(foreground,now);showGate("blocked",foreground);Notices.cancelTimer(this);return;}
        if(!foreground.equals(sessionPkg)){showGate("app",foreground);Notices.cancelTimer(this);return;}
        if(sessionRemaining<=0){showGate("renew",foreground);Notices.cancelTimer(this);return;}
        if(!overlayVisible&&(now/30000!=lastNotice)){lastNotice=now/30000;Notices.timer(this,rule.optString("label"),dailyRemaining(foreground),sessionRemaining);}
        } finally { syncFloatingTimer(); }
    }
    void syncFloatingTimer(){
        if(floatingTimer==null||store==null)return;
        long now=System.currentTimeMillis();JSONObject rule=store.rule(foreground);
        boolean visible=store.settings.getBoolean("floatingTimer",true)&&interactive&&!systemPaused&&!overlayVisible
            &&!foreground.isEmpty()&&foreground.equals(sessionPkg)&&!foreground.equals(getPackageName())
            &&store.managed(now)&&rule!=null&&"limited".equals(rule.optString("mode"))
            &&dailyRemaining(foreground)>0&&sessionRemaining>0
            &&!keyguardLocked()
            &&(LockyApp.app==null||!foreground.equals(LockyApp.app.authBrowser));
        floatingTimer.update(visible,foreground,sessionEvent,rule==null?"":rule.optString("label"),purpose,sessionRemaining,dailyRemaining(foreground));
    }
    void hideFloatingTimer(){if(floatingTimer!=null)floatingTimer.hide();}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(overlay!=null)overlay.applyTheme();hideFloatingTimer();syncFloatingTimer();if(overlayVisible&&overlay!=null)revealOverlay();}
    long lastNotice=-1;
    void showGate(String type,String pkg){
        if(!Set.of("app","blocked","renew").contains(type))throw new IllegalArgumentException("Not an app gate");
        hideFloatingTimer();
        boolean same=overlayVisible&&gate.equals(type)&&gatePkg.equals(pkg);
        if(!same){checkpoint();gate=type;gatePkg=pkg;utility="사용 확인";}
        if(overlay==null){
            overlay=new WebHost(this,WebHost.Role.APP_GATE);overlay.frameAvailableCallback=this::revealOverlay;
            overlayWindow=new android.widget.FrameLayout(this){
                @Override public boolean dispatchTouchEvent(MotionEvent event){return !acceptGateInput()||super.dispatchTouchEvent(event);}
                @Override public boolean dispatchKeyEvent(KeyEvent event){return !acceptGateInput()||super.dispatchKeyEvent(event);}
            };
            overlayWindow.addView(overlay.root,new android.widget.FrameLayout.LayoutParams(-1,-1));
            overlayLayout=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
            overlayLayout.gravity=Gravity.TOP|Gravity.START;overlayLayout.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;overlayLayout.setTitle("Locky app gate");
            window.addView(overlayWindow,overlayLayout);
        }
        overlayVisible=true;
        if(!same)overlay.screen(type);
        revealOverlay();ensurePulse();
    }
    void revealOverlay(){
        if(overlay==null)return;
        Exposure next=!overlayVisible?Exposure.HIDDEN:(!interactive||keyguardLocked()||systemPaused)?Exposure.SUSPENDED:
            overlay.hasUsableFrame()||overlay.hasPresentedDocument()||overlay.loadFailed?Exposure.INTERACTIVE:Exposure.WAITING;
        applyExposure(next);
    }
    void applyExposure(Exposure next){
        if(overlay==null)return;
        boolean input=next==Exposure.INTERACTIVE;
        boolean shown=input||next==Exposure.WAITING;
        overlayWindow.setVisibility(shown?View.VISIBLE:View.GONE);
        overlayWindow.setImportantForAccessibility(input?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if(overlayLayout!=null){
            int passive=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            int flags=input?overlayLayout.flags&~passive:overlayLayout.flags|passive;
            if(flags!=overlayLayout.flags){overlayLayout.flags=flags;window.updateViewLayout(overlayWindow,overlayLayout);}
        }
        exposure=next;
        if(input)overlay.resumeOverlay();else if(shown)overlay.prepareOverlay();else overlay.pause();
    }
    JSONObject startWindowStatus() throws JSONException {
        return UnlockDiagnostics.get(this).windowStatus().put("status","activity").put("actual","activity");
    }
    void hideOverlay(){
        if(!overlayVisible)return;
        checkpoint();overlayVisible=false;if(overlay!=null)applyExposure(Exposure.HIDDEN);
        segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();utility="첫 화면";
    }
    void choosePhone(JSONObject p) throws Exception {checkpoint();phoneEvent=store.event("unlock",p.getString("label"),"",p.optString("pkg"),0,p.optInt("aimless",0)==1);utility="첫 화면";ensurePulse();}
    void allowApp(JSONObject p,int minutes) throws Exception {
        checkpoint();long remaining=dailyRemaining(gatePkg);JSONObject rule=store.rule(gatePkg);if(!store.managed(System.currentTimeMillis())){hideOverlay();return;}
        if(rule==null||rule.optString("mode").equals("locked")||remaining<=0){enforce();throw new IllegalStateException("지금은 사용할 수 없는 앱입니다.");}
        if(!gatePkg.equals(foreground))throw new IllegalStateException("앱이 바뀌었습니다. 다시 열어 주세요.");
        pausedSessions.remove(gatePkg);sessionPolicyEnd=policyEnd(System.currentTimeMillis());sessionPkg=gatePkg;purpose=p.getString("label");sessionRemaining=Rules.grant(remaining,minutes);sessionEvent=store.event("app",purpose,gatePkg,p.optString("pkg"),phoneEvent,p.optInt("aimless",0)==1);hideOverlay();lastNotice=-1;Notices.timer(this,rule.optString("label"),remaining,sessionRemaining);launch(p);syncFloatingTimer();ensurePulse();
    }
    void extend(int minutes) {checkpoint();if(!sessionPkg.equals(gatePkg)||dailyRemaining(gatePkg)<=0){enforce();return;}sessionRemaining=Rules.grant(dailyRemaining(gatePkg),minutes);store.event("extend",purpose,sessionPkg,"",phoneEvent);hideOverlay();lastNotice=-1;enforce();ensurePulse();}
    void stopApp(){hideFloatingTimer();checkpoint();if(!gate.isEmpty())store.event("cancel","열지 않기",gatePkg,"",phoneEvent);pausedSessions.remove(sessionPkg.isEmpty()?gatePkg:sessionPkg);clearActiveSession();performGlobalAction(GLOBAL_ACTION_HOME);foreground="";hideOverlay();Notices.cancelTimer(this);main.removeCallbacks(pulse);pulseScheduled=false;}
    void useUtility(String name){checkpoint();boolean changed=!name.equals(utility);utility=name;if(changed&&!name.equals("첫 화면")&&!name.equals("Locky"))store.event("utility",name,getPackageName(),"",phoneEvent);}
    void pauseForActivity(){switchForeground(getPackageName());if(!sessionPkg.isEmpty())Notices.paused(this,UsageReader.label(this,sessionPkg),dailyRemaining(sessionPkg),sessionRemaining);}
    void openMain(String screen){openMain(screen,"");}
    void openMain(String screen,String chatId){useUtility(screen.equals("chats")||screen.equals("chat")?"ChatGPT":screen.equals("notes")?"메모":"Locky");hideOverlay();Intent launch=MainActivity.launchIntent(this).putExtra("screen",screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if("chat".equals(screen)&&!chatId.isEmpty())launch.putExtra("chat",chatId);startActivity(launch);}
    void launch(JSONObject p) throws Exception {String pkg=p.optString("pkg"),uri=p.optString("uri");if(pkg.isEmpty()&&uri.isEmpty())return;Intent i=Links.intent(this,pkg,uri);if(i==null)throw new IllegalStateException("연결한 앱이나 동작을 열 수 없습니다. 목적 연결을 확인해 주세요.");startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    void policyChanged(){main.post(()->{observeForeground(null);checkpoint();enforce();ensurePulse();WebHost.refreshAll();});}
    JSONObject state() throws JSONException {String pkg=overlayVisible?gatePkg:sessionPkg;return new JSONObject().put("mode",gate).put("pkg",pkg).put("label",UsageReader.label(this,pkg)).put("purpose",purpose).put("daily",dailyRemaining(pkg)).put("session",sessionRemaining).put("activePkg",sessionPkg).put("free",!store.managed(System.currentTimeMillis())).put("paused",systemPaused||foreground.equals(getPackageName())).put("visible",overlayVisible);}
    final Runnable lockCompletion=()->{
        if(!lockPending)return;
        if(!getSystemService(PowerManager.class).isInteractive()||keyguardLocked())return;
        lockPending=false;
        android.widget.Toast.makeText(this,"화면을 잠그지 못했습니다. 다시 시도해 주세요.",android.widget.Toast.LENGTH_LONG).show();
    };
    void dismissStart(){
        if(lockPending)return;
        lockPending=true;
        // Keep the original opaque start on screen until Android actually locks it.
        if(Build.VERSION.SDK_INT<28||!performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)){
            lockPending=false;throw new IllegalStateException("화면을 잠글 수 없습니다. 접근성 권한을 확인해 주세요.");
        }
        hideFloatingTimer();Notices.cancelTimer(this);
        main.postDelayed(lockCompletion,1500); // Failure recovery only; never delays exposure or locking.
    }
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){hideFloatingTimer();checkpoint();if(store!=null)store.observe("service-destroyed",interactive);live=null;interactive=false;overlayVisible=false;main.removeCallbacksAndMessages(null);try{unregisterReceiver(screen);}catch(Exception ignored){}if(overlay!=null){try{window.removeViewImmediate(overlayWindow);}catch(IllegalArgumentException ignored){}overlay.close();}Notices.cancelTimer(this);super.onDestroy();}
}
