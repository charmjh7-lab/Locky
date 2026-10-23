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
    volatile boolean interactive,waitingUnlock,wasManaged,systemPaused;volatile boolean overlayVisible;volatile String gate="unlock",gatePkg="";
    static final long SESSION_RETURN_GRACE=5*60_000L;
    static final class PausedSession {
        final long remaining,event,until,policyEnd;final String purpose;
        PausedSession(long remaining,long event,String purpose,long until,long policyEnd){this.remaining=remaining;this.event=event;this.purpose=purpose;this.until=until;this.policyEnd=policyEnd;}
    }
    final Map<String,PausedSession> pausedSessions=new HashMap<>();
    long sessionPolicyEnd;
    FloatingTimer floatingTimer;
    DictionaryActivity dictionaryHost;boolean dictionaryOpening;
    java.util.function.Consumer<String> dictionaryResult;String dictionaryWord="";
    android.widget.FrameLayout overlayWindow;boolean unlockPrepared,unlockTransition,lockPending;
    enum Exposure { HIDDEN, WAITING, BEHIND_KEYGUARD, INTERACTIVE, SUSPENDED }
    Exposure exposure=Exposure.HIDDEN;
    enum Input { TOUCH, FOCUS, EDITOR }
    Input inputMode=Input.TOUCH;
    Boolean observedKeyguard;
    boolean applicationWindowFailed;
    Context applicationWindowContext;WindowManager applicationWindow,gateWindow;
    final Set<Integer> keyguardWindows=new HashSet<>();
    final AppOpsManager.OnOpChangedListener overlayPermission=(op,pkg)->main.post(()->{
        if(store==null)return;WebHost.trace("overlay permission observed allowed="+android.provider.Settings.canDrawOverlays(this));applicationWindowFailed=false;reconcileWindowPermission();WebHost.refreshAll();
    });
    WebHost overlay;WindowManager window;WindowManager.LayoutParams overlayLayout;final Set<String> systemPackages=new HashSet<>();
    String homePackage="";
    volatile boolean pulseScheduled;volatile long pulseCount;
    final Runnable pulse=new Runnable(){public void run(){pulseScheduled=false;if(live!=GuardService.this)return;pulseCount++;checkpoint();enforce();ensurePulse();}};
    final BroadcastReceiver screen=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String a=i.getAction();
        if(Intent.ACTION_SCREEN_ON.equals(a)&&UnlockDiagnostics.live!=null)UnlockDiagnostics.live.resumeCapture();
        if(!Intent.ACTION_SCREEN_OFF.equals(a))WebHost.trace("broadcast observed="+(Intent.ACTION_SCREEN_ON.equals(a)?"screen-on":Intent.ACTION_USER_PRESENT.equals(a)?"user-present":"clock"));
        if(Intent.ACTION_SCREEN_OFF.equals(a))screenOff();
        else if(Intent.ACTION_USER_PRESENT.equals(a)){
            interactive=getSystemService(PowerManager.class).isInteractive();
            main.removeCallbacks(checkUnlock);checkUnlock.run();
        }else if(Intent.ACTION_SCREEN_ON.equals(a)){
            interactive=true;
            if(waitingUnlock)rememberKeyguardWindows();
            if(waitingUnlock){unlockChecks=0;main.removeCallbacks(checkUnlock);checkUnlock.run();}
        }else if(Intent.ACTION_TIME_CHANGED.equals(a)||Intent.ACTION_TIMEZONE_CHANGED.equals(a)){checkpoint();policyChanged();Scheduler.schedule(c);}
    }};
    void screenOff(){
        if(!interactive&&waitingUnlock&&unlockPrepared){WebHost.trace("duplicate screen-off ignored");return;}
        UnlockDiagnostics.get(this);WebHost.beginUnlockTrace();keyguardLocked();inputMode=Input.TOUCH;
        checkpoint();interactive=false;waitingUnlock=true;unlockPrepared=false;endUnlockTransition("screen-off");keyguardWindows.clear();
        if(lockPending){store.event("dismiss","열지 않기","","",0);lockPending=false;}
        main.removeCallbacks(lockCompletion);
        if(dictionaryHost!=null)dictionaryHost.returnToStart();
        else if(dictionaryOpening){dictionaryOpening=false;finishDictionaryRequest("사전 검색을 취소했습니다.");}
        overview=false;overviewCandidate="";clearSessions();foreground="";
        store.settings.edit().putBoolean("checkpoint-interactive",false).putString("last-package","").apply();
        setSystemPaused(false,"screen-off");rememberKeyguardWindows();main.removeCallbacks(checkUnlock);prepareUnlock();
        Notices.cancelTimer(this);main.removeCallbacks(pulse);pulseScheduled=false;
    }
    int unlockChecks;
    final Runnable settledForeground=()->observeForeground(null);
    final Runnable checkUnlock=new Runnable(){public void run(){
        if(!interactive||!waitingUnlock)return;
        if(completeUnlock()){observeForeground(null);ensurePulse();}
        else if(unlockChecks++<20)main.postDelayed(this,250);
    }};
    @Override protected void onServiceConnected(){
        live=this;store=Store.get(this);window=getSystemService(WindowManager.class);floatingTimer=new FloatingTimer(this,window);interactive=getSystemService(PowerManager.class).isInteractive();wasManaged=store.managed(System.currentTimeMillis());store.observe("service-connected",interactive);
        getSystemService(AppOpsManager.class).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,getPackageName(),overlayPermission);
        ResolveInfo home=getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);if(home!=null)homePackage=home.activityInfo.packageName;
        systemPackages.add("com.android.systemui");systemPackages.add("com.android.permissioncontroller");systemPackages.add("com.google.android.permissioncontroller");
        android.view.inputmethod.InputMethodManager ime=getSystemService(android.view.inputmethod.InputMethodManager.class);for(android.view.inputmethod.InputMethodInfo x:ime.getEnabledInputMethodList())systemPackages.add(x.getPackageName());
        IntentFilter f=new IntentFilter();for(String a:new String[]{Intent.ACTION_SCREEN_OFF,Intent.ACTION_SCREEN_ON,Intent.ACTION_USER_PRESENT,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED})f.addAction(a);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screen,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screen,f);
        segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();
        long recoveryFrom=store.settings.getLong("checkpoint",System.currentTimeMillis());String recoveryPackage=store.settings.getString("last-package","");
        waitingUnlock=!interactive||keyguardLocked();
        if(waitingUnlock)prepareUnlock();else {observeForeground(null);main.postDelayed(()->{if(foreground.isEmpty())observeForeground(null);},250);main.postDelayed(()->{if(foreground.isEmpty())observeForeground(null);},750);}Scheduler.schedule(this);recover(recoveryFrom,store.settings.getBoolean("checkpoint-interactive",true)?recoveryPackage:"");
    }
    private void recover(long from,String old){long now=System.currentTimeMillis();
        if(now-from<2000)return;
        LockyApp.app.work.execute(()->{try{JSONArray history=UsageReader.intervals(this,Math.max(from,now-180L*86400000),now,old);for(int i=0;i<history.length();i++){JSONObject u=history.getJSONObject(i);String pkg=u.getString("pkg");if(!UsageReader.recordable(this,pkg)||pkg.equals(getPackageName()))continue;JSONObject rule=store.rule(pkg);long a=u.getLong("start"),b=u.getLong("end");while(a<b){long boundary=Math.min(b,Math.min(Rules.nextDay(a,ZoneId.systemDefault()),Rules.nextBoundary(a,store.settings.getInt("start",540),store.settings.getInt("end",1260),ZoneId.systemDefault())));store.usage(a,boundary,pkg,UsageReader.label(this,pkg),rule!=null&&rule.optString("mode").equals("limited")&&store.managed(a),0,"usage-access");a=boundary;}}}catch(Exception ignored){}finally{main.post(this::policyChanged);}});
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(store==null)return;
        interactive=getSystemService(PowerManager.class).isInteractive();
        if(!interactive||keyguardLocked()){if(waitingUnlock){rememberKeyguardWindows();revealOverlay();}return;}
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
    final class GateSurface extends android.widget.FrameLayout {
        GateSurface(Context c){super(c);}
        @Override public boolean dispatchTouchEvent(MotionEvent e){
            if(!acceptGateInput())return true; // Never forward pre-authentication input to WebView.
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN)endUnlockTransition("user-touch");
            return super.dispatchTouchEvent(e);
        }
        @Override public boolean dispatchKeyEvent(KeyEvent e){return !acceptGateInput()||super.dispatchKeyEvent(e);}
    }
    boolean acceptGateInput(){return overlayVisible&&exposure==Exposure.INTERACTIVE&&interactive&&!waitingUnlock&&!systemPaused&&!keyguardLocked();}
    void requestNavigationInput(){
        // Explicit in-document chat navigation still needs Android Back/key handling.
        // This is user-driven, never part of keyguard exposure or frame callbacks.
        if(!acceptGateInput()||inputMode!=Input.TOUCH)return;
        inputMode=Input.FOCUS;WebHost.trace("input requested=navigation");revealOverlay();overlay.view.requestFocus();
    }
    void requestEditorInput(){
        if(!acceptGateInput()||dictionaryHost!=null||dictionaryOpening)return;
        endUnlockTransition("editor-request");
        if(inputMode!=Input.EDITOR){inputMode=Input.EDITOR;WebHost.trace("input requested=editor");revealOverlay();}
        if(overlay!=null){overlay.view.requestFocus();if(overlayWindow.hasWindowFocus())showEditorKeyboard();}
    }
    void showEditorKeyboard(){
        if(inputMode!=Input.EDITOR||!acceptGateInput()||overlay==null)return;
        overlay.view.post(()->{if(inputMode==Input.EDITOR&&acceptGateInput()&&overlayWindow.hasWindowFocus()){
            getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(overlay.view,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }});
    }
    void rememberKeyguardWindows(){
        if(interactive&&!keyguardLocked())return;
        for(AccessibilityWindowInfo w:getWindows())if(w.getType()==AccessibilityWindowInfo.TYPE_SYSTEM){keyguardWindows.add(w.getId());WebHost.trace("keyguard window observed id="+w.getId()+" focused="+w.isFocused()+" active="+w.isActive());}
    }
    void beginUnlockTransition(){if(!unlockTransition){unlockTransition=true;WebHost.trace("unlockTransition=start");}}
    void endUnlockTransition(String reason){if(unlockTransition){unlockTransition=false;WebHost.trace("unlockTransition=end reason="+reason);UnlockDiagnostics.get(this).transitionEnded(reason);}}
    void setSystemPaused(boolean value,String reason){if(systemPaused!=value){systemPaused=value;WebHost.trace("systemPaused="+value+" reason="+reason);}}
    boolean overview;String overviewCandidate="";long overviewCandidateSince;volatile String lastWindows="";
    void observeForeground(AccessibilityEvent event) {
        if(store==null||!interactive||keyguardLocked())return;
        List<ForegroundPolicy.Window> candidates=new ArrayList<>();StringBuilder diagnostic=new StringBuilder();
        View surface=overlayWindow==null?null:overlayWindow;
        boolean ownFocus=surface!=null&&surface.hasWindowFocus(),foreignFocus=false,explicitSystemWindow=false;
        WebHost.trace("accessibility observed event="+(event==null?0:event.getEventType())+" changes="+(event!=null&&Build.VERSION.SDK_INT>=28?event.getWindowChanges():0)+" window="+(event==null?-1:event.getWindowId()));
        for(AccessibilityWindowInfo w:getWindows()) {
            String pkg="";
            // SAW is reported as a system window. Exclude our own window, not real system UI.
            if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION||w.getType()==AccessibilityWindowInfo.TYPE_SYSTEM){AccessibilityNodeInfo root=w.getRoot();if(root!=null){CharSequence name=root.getPackageName();pkg=name==null?"":name.toString();root.recycle();}}
            if(w.getType()==AccessibilityWindowInfo.TYPE_SYSTEM&&(pkg.equals(getPackageName())
                ||(pkg.isEmpty()&&ownFocus&&w.isFocused()&&overlayLayout!=null&&overlayLayout.type==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY&&"Locky".contentEquals(w.getTitle()==null?"":w.getTitle()))))continue;
            boolean system=w.getType()==AccessibilityWindowInfo.TYPE_SYSTEM;
            String role=pkg.isEmpty()?"unknown":pkg.equals("com.android.systemui")?"system-ui":systemPackages.contains(pkg)?"system-app":"other";
            if(system){
                WebHost.trace("system window observed id="+w.getId()+" role="+role+" focused="+w.isFocused()+" active="+w.isActive()+" knownKeyguard="+keyguardWindows.contains(w.getId()));
                explicitSystemWindow|=(w.isFocused()||w.isActive())&&!pkg.isEmpty()&&!pkg.equals("com.android.systemui");
            }
            if(event!=null&&w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&w.isFocused()&&!pkg.equals(getPackageName())){
                foreignFocus|=(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&pkg.contentEquals(event.getPackageName()==null?"":event.getPackageName()))
                    ||(Build.VERSION.SDK_INT>=28&&event.getEventType()==AccessibilityEvent.TYPE_WINDOWS_CHANGED&&event.getWindowId()==w.getId()&&(event.getWindowChanges()&AccessibilityEvent.WINDOWS_CHANGE_FOCUSED)!=0);
            }
            if(pkg.equals("com.samsung.android.sidegesturepad"))continue;
            android.graphics.Rect bounds=new android.graphics.Rect();w.getBoundsInScreen(bounds);boolean covering=bounds.height()>getResources().getDisplayMetrics().heightPixels/2;
            candidates.add(new ForegroundPolicy.Window(pkg,w.getType(),w.isFocused(),w.isActive(),w.getLayer(),w.isInPictureInPictureMode(),covering));diagnostic.append(pkg).append(":type=").append(w.getType()).append(",focus=").append(w.isFocused()).append(",active=").append(w.isActive()).append(",layer=").append(w.getLayer()).append(",cover=").append(covering).append(";");
        }
        lastWindows=diagnostic.toString();ForegroundPolicy.Result visible=ForegroundPolicy.select(candidates,systemPackages);
        if(event!=null&&event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&event.getClassName()!=null){
            String name=event.getClassName().toString().toLowerCase(Locale.ROOT),pkg=event.getPackageName()==null?"":event.getPackageName().toString();
            boolean systemLauncher=pkg.equals(homePackage)||pkg.equals("com.android.systemui")||pkg.contains("launcher");
            if(systemLauncher&&(name.contains("recents")||name.contains("overview")||name.contains("recentapps"))){endUnlockTransition("overview");overview=true;overviewCandidate="";}
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
        // An unfocused background root is not a boundary. With touch-only SAW we
        // keep app focus untouched; a focused application in a non-system snapshot
        // (or actual accepted user input) completes the transition without taking IME focus.
        if(unlockTransition&&!visible.paused&&overlay!=null&&overlay.presentationActive&&overlay.hasUsableFrame()
            &&(ownFocus||candidates.stream().anyMatch(w->w.type==AccessibilityWindowInfo.TYPE_APPLICATION&&w.focused&&!systemPackages.contains(w.pkg))))
            endUnlockTransition(ownFocus?"stable-overlay-focus":"stable-application-focus");
        if(visible.paused){
            boolean explicitSystem=candidates.stream().anyMatch(w->w.type==AccessibilityWindowInfo.TYPE_APPLICATION&&w.focused
                &&(w.pkg.equals("android")||systemPackages.contains(w.pkg))&&!w.pkg.equals("com.android.systemui"));
            boolean explicitAction=event!=null&&event.getClassName()!=null&&ForegroundPolicy.explicitSystemAction(event.getClassName().toString());
            boolean interrupt=ForegroundPolicy.interruptUnlock(unlockTransition,explicitSystem||explicitSystemWindow||explicitAction);
            WebHost.trace("system decision="+(interrupt?"interrupt":"keyguard-teardown")+" unlocking="+unlockTransition+" explicit="+(explicitSystem||explicitSystemWindow||explicitAction));
            if(!interrupt)return;
            endUnlockTransition("confirmed-system-interruption");pauseSystem("confirmed-system-interruption");return;
        }
        // Revocation can make our old SAW window incapable of acquiring focus. End
        // the transition only after a real app owns a non-system snapshot, then
        // recover the already-suspended surface once (never during keyguard teardown).
        if(!visible.paused&&overlayLayout!=null
            &&overlayLayout.type==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY&&!applicationStartAllowed()
            &&(ownFocus||candidates.stream().anyMatch(w->w.type==AccessibilityWindowInfo.TYPE_APPLICATION&&w.focused))){
            endUnlockTransition("permission-revoked-stable-app");
            setSystemPaused(true,"permission-recovery");reconcileWindowPermission();
        }
        if(visible.pkg.isEmpty())return; // transient/root unavailable: do not trust background event senders
        String pkg=visible.pkg;
        if(pkg.contains("incallui")||pkg.equals("com.android.server.telecom")){endUnlockTransition("incoming-call");if(overlayVisible)hideOverlay();pauseSystem();return;}
        if(foreignFocus&&!ownFocus&&overlayVisible&&overlay!=null&&Set.of("chat","chats").contains(overlay.pendingScreen))hideOverlay();
        switchForeground(pkg);
    }
    void pauseSystem(){pauseSystem("system");}
    void pauseSystem(String reason){
        // Keyguard teardown can report a system window before the app window exists.
        if(lockPending)return;
        if(unlockTransition&&overlayVisible&&"unlock".equals(gate)&&!overview)return;
        hideFloatingTimer();
        boolean changed=!systemPaused;if(changed){checkpoint();setSystemPaused(true,reason);lastNotice=-1;}
        if(overlayVisible&&overlay!=null&&dictionaryHost==null&&!dictionaryOpening){reconcileWindowPermission();}
        if(changed&&!sessionPkg.isEmpty())Notices.paused(this,UsageReader.label(this,sessionPkg),dailyRemaining(sessionPkg),sessionRemaining);
        ensurePulse();
    }
    void switchForeground(String pkg){
        if(dictionaryHost!=null)dictionaryHost.foregroundChanged(pkg);
        if(systemPaused){checkpoint();setSystemPaused(false,"foreground");lastNotice=-1;}
        if(!pkg.equals(foreground)) {
            checkpoint();
            // Preserve each app's original grant; time away never buys a new grant.
            if(!sessionPkg.isEmpty()&&!pkg.equals(sessionPkg)&&!pkg.equals(getPackageName()))parkSession();
            foreground=pkg;segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();
            store.settings.edit().putString("last-package",pkg).putLong("checkpoint",segmentWall).putBoolean("checkpoint-interactive",interactive).apply();
            if(overlayVisible&&!gate.equals("unlock")&&!gatePkg.equals(pkg))hideOverlay();
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
            boolean covering=overlayVisible&&dictionaryHost==null;String pkg=(covering||systemPaused)?getPackageName():foreground,label=systemPaused?"사용 확인":(covering||pkg.equals(getPackageName()))?utility:UsageReader.label(this,pkg);
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
        long now=System.currentTimeMillis();boolean managed=store.managed(now);if(managed!=wasManaged){wasManaged=managed;if(!managed){clearSessions();if(overlayVisible&&!gate.equals("unlock"))hideOverlay();Notices.cancelTimer(this);}else{clearSessions();}Scheduler.schedule(this);}
        if(!sessionPkg.isEmpty()&&sessionPolicyEnd>0&&now>=sessionPolicyEnd)clearSessions();
        if(dictionaryHost!=null)return;
        if(!interactive||systemPaused||foreground.equals(getPackageName())||keyguardLocked()||foreground.isEmpty())return;
        if(overlayVisible&&gate.equals("unlock"))return;
        if(!LockyApp.app.authBrowser.isEmpty()&&foreground.equals(LockyApp.app.authBrowser)){if(overlayVisible)hideOverlay();Notices.cancelTimer(this);return;}
        if(!managed){if(overlayVisible&&!gate.equals("unlock"))hideOverlay();Notices.cancelTimer(this);return;}
        JSONObject rule=store.rule(foreground);if(rule==null){if(overlayVisible&&!gate.equals("unlock"))hideOverlay();Notices.cancelTimer(this);return;}
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
    void prepareUnlock(){
        if(unlockPrepared){WebHost.trace("duplicate prepare ignored");return;}
        if(WebHost.tracing())WebHost.trace("prepare-begin overlayPermission="+android.provider.Settings.canDrawOverlays(this)+" requestedApplication="+store.settings.getBoolean("start-under-keyguard",true));
        unlockPrepared=true;
        boolean reused=overlay!=null&&gate.equals("unlock")&&overlay.pendingScreen.equals("unlock");
        showGate("unlock","");
        if(reused)overlay.prepareStart();
        utility="첫 화면";unlockChecks=0;
        if(WebHost.tracing())WebHost.trace("prepare-dispatched reused="+reused);
    }
    boolean completeUnlock(){
        if(!waitingUnlock){WebHost.trace("duplicate unlock ignored");return false;}
        if(!interactive||keyguardLocked())return false;
        WebHost.resumeUnlockTrace();WebHost.trace("unlock-confirmed");
        if(!unlockPrepared)prepareUnlock();
        if(overlay!=null)overlay.ensurePurposeDay();
        waitingUnlock=false;setSystemPaused(false,"unlock-confirmed");beginUnlockTransition();UnlockDiagnostics.get(this).confirmed(overlayLayout==null?0:overlayLayout.type);main.removeCallbacks(checkUnlock);
        if(WebHost.tracing())WebHost.trace("complete-unlock ready="+overlay.frameReady+" pending="+overlay.preparingStart);
        // The caller reads the foreground once, retaining the actual event when available.
        revealOverlay();return true;
    }
    void showGate(String type,String pkg){
        hideFloatingTimer();
        boolean sameScreen=overlay!=null&&(overlayVisible||"unlock".equals(type))&&gate.equals(type)&&gatePkg.equals(pkg)&&overlay.pendingScreen.equals(type);
        boolean same=overlayVisible&&sameScreen;
        if(!same){checkpoint();gate=type;gatePkg=pkg;utility=type.equals("unlock")?"첫 화면":"사용 확인";}
        if(overlay==null){
            Context hostContext=this;
            if(desiredWindowType()==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY){
                try{managerFor(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);hostContext=applicationWindowContext;}
                catch(IllegalStateException|SecurityException e){applicationWindowFailed=true;UnlockDiagnostics.get(this).failure("context",e);WebHost.trace("fallback accessibility reason=window-context");}
            }
            overlay=new WebHost(hostContext,true);overlay.frameAvailableCallback=this::revealOverlay;
            overlayWindow=new GateSurface(hostContext);
            overlayWindow.getViewTreeObserver().addOnWindowFocusChangeListener(focused->{
                WebHost.trace("window focus observed="+focused);
                if(focused&&unlockTransition)observeForeground(null);
                if(focused)showEditorKeyboard();
            });
            overlayWindow.addView(overlay.root,new android.widget.FrameLayout.LayoutParams(-1,-1));
        }
        ensureGateWindow();
        overlayVisible=true;
        // Reuse the DOM on repeated unlock/show events, but always restore the surface.
        if(!sameScreen)overlay.screen(type);
        revealOverlay();ensurePulse();
    }
    boolean applicationStartAllowed(){return !applicationWindowFailed&&store.settings.getBoolean("start-under-keyguard",true)&&android.provider.Settings.canDrawOverlays(this);}
    int desiredWindowType(){return "unlock".equals(gate)&&applicationStartAllowed()?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;}
    WindowManager managerFor(int type){
        if(type!=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)return window;
        if(applicationWindow==null){
            // Never inherit AccessibilityService's default accessibility token. Reuse one
            // standard non-activity window context; no arbitrary Binder / parent token.
            Context app=getApplicationContext();
            android.view.Display display=app.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            if(display==null)throw new IllegalStateException("Primary display unavailable");
            applicationWindowContext=app.createDisplayContext(display);
            if(Build.VERSION.SDK_INT>=30)applicationWindowContext=applicationWindowContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
            applicationWindow=applicationWindowContext.getSystemService(WindowManager.class);
        }
        return applicationWindow;
    }
    boolean canReplaceGateWindow(){
        // SCREEN_ON / waitingUnlock is not a hidden boundary. Neither is alpha=0
        // during frame validation. A real system interruption may safely cover us.
        return !interactive||!overlayVisible||(systemPaused&&!waitingUnlock&&!unlockTransition);
    }
    void ensureGateWindow(){
        int type=desiredWindowType();if(overlayLayout!=null&&overlayLayout.type==type)return;
        if(overlayLayout!=null&&!canReplaceGateWindow()){WebHost.trace("window migration deferred type="+overlayLayout.type+" requested="+type);return;}
        removeGateWindow("role-or-permission");
        try{addGateWindow(type);}
        catch(SecurityException|WindowManager.BadTokenException|IllegalStateException e){
            if(type!=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)throw e;
            applicationWindowFailed=true;UnlockDiagnostics.get(this).failure("add",e);WebHost.trace("fallback accessibility reason="+e.getClass().getSimpleName());
            addGateWindow(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        }
    }
    void addGateWindow(int type){
        WindowManager manager=managerFor(type);WindowManager.LayoutParams layout=gateLayout(type);
        manager.addView(overlayWindow,layout);gateWindow=manager;overlayLayout=layout;exposure=Exposure.WAITING;
        WebHost.trace("window add accepted type="+type+" alpha=0 accelerated="+((layout.flags&WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)!=0)+" flags="+layout.flags);
    }
    void removeGateWindow(String reason){
        if(overlayLayout==null)return;
        applyExposure(Exposure.HIDDEN);
        WebHost.trace("window remove requested type="+overlayLayout.type+" reason="+reason);
        try{(gateWindow==null?window:gateWindow).removeViewImmediate(overlayWindow);WebHost.trace("window removal accepted");}catch(IllegalArgumentException ignored){WebHost.trace("window removal observed=already-detached");}
        overlayLayout=null;gateWindow=null;
    }
    void reconcileWindowPermission(){
        if(overlay==null||dictionaryHost!=null||dictionaryOpening)return;
        ensureGateWindow();revealOverlay();
    }
    WindowManager.LayoutParams gateLayout(int type){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,type,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.OPAQUE);
        // The primary-display SAW stays touchable/nonfocusable across keyguard teardown.
        // Android keeps it below keyguard; GateSurface also rejects all locked/unready input.
        if(type==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)p.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        p.windowAnimations=0; // No Locky enter/show animation layered over keyguard.
        p.alpha=0;p.gravity=Gravity.TOP|Gravity.START;p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;p.setTitle("Locky");return p;
    }
    boolean keyguardPlacementSafe(){
        // AOSP DisplayPolicy only hides these windows by keyguard on the primary display.
        // Accessibility overlays are above keyguard; FLAG_SECURE is not a placement rule.
        Display display=overlayWindow==null?null:overlayWindow.getDisplay();
        if(display==null&&applicationWindowContext!=null)display=applicationWindowContext.getSystemService(WindowManager.class).getDefaultDisplay();
        return "unlock".equals(gate)&&overlayLayout!=null&&overlayLayout.type==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            &&applicationStartAllowed()&&display!=null&&display.getDisplayId()==Display.DEFAULT_DISPLAY
            &&(overlayLayout.flags&(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD))==0;
    }
    void revealOverlay(){
        if(dictionaryHost!=null||dictionaryOpening)return;
        WebHost.trace("reveal requested state="+exposure+" type="+(overlayLayout==null?0:overlayLayout.type)+" frameReady="+(overlay!=null&&overlay.frameReady)+" retainedFrame="+(overlay!=null&&overlay.retainedFrame));
        if(overlay==null)return;
        boolean usable=overlay.hasUsableFrame()||overlay.hasPresentedDocument()||(!overlay.everRendered&&overlay.loadFailed);
        if(!overlayVisible){applyExposure(Exposure.HIDDEN);return;}
        boolean locked=!interactive||waitingUnlock||keyguardLocked();
        Exposure next;
        if(overlayLayout!=null&&overlayLayout.type==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY&&!applicationStartAllowed())next=Exposure.SUSPENDED;
        else if(systemPaused)next=Exposure.SUSPENDED;
        else if(!usable)next=Exposure.WAITING;
        else if(locked)next=keyguardPlacementSafe()?Exposure.BEHIND_KEYGUARD:Exposure.WAITING;
        else next=Exposure.INTERACTIVE;
        applyExposure(next);
    }
    // Dictionary handoff explicitly suspends this surface while the same WebView is
    // presented in its Activity. All actual window writes still go through one path.
    void setOverlayExposure(boolean exposed){setOverlayExposure(exposed,true);}
    void setOverlayExposure(boolean exposed,boolean prepareHidden){
        applyExposure(exposed?Exposure.INTERACTIVE:Exposure.SUSPENDED,prepareHidden);
    }
    void applyExposure(Exposure next){applyExposure(next,true);}
    void applyExposure(Exposure next,boolean prepareHidden){
        if(overlay==null)return;
        View surface=overlayWindow==null?overlay.root:overlayWindow;
        boolean input=next==Exposure.INTERACTIVE,hidden=next==Exposure.HIDDEN;
        int visibility=hidden?View.GONE:View.VISIBLE;
        if(surface.getVisibility()!=visibility){WebHost.trace("visibility requested="+surface.getVisibility()+"->"+visibility);surface.setVisibility(visibility);}
        int accessibility=input?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
        if(surface.getImportantForAccessibility()!=accessibility)surface.setImportantForAccessibility(accessibility);
        if(dictionaryHost==null){
            if(overlay.root.getVisibility()!=visibility){WebHost.trace("root visibility requested="+overlay.root.getVisibility()+"->"+visibility);overlay.root.setVisibility(visibility);}
            if(input)overlay.resumeOverlay();
        }
        int passive=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if(overlayLayout!=null){
            float alpha=input||next==Exposure.BEHIND_KEYGUARD?1f:0f;
            int flags;
            if(keyguardPlacementSafe()&&(next==Exposure.BEHIND_KEYGUARD||input||(next==Exposure.WAITING&&(!interactive||waitingUnlock||keyguardLocked())))){
                flags=overlayLayout.flags&~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                flags=input&&inputMode!=Input.TOUCH?flags&~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE:flags|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            }else flags=input?overlayLayout.flags&~passive:overlayLayout.flags|passive;
            if(overlayLayout.alpha!=alpha||overlayLayout.flags!=flags){
                WebHost.trace("exposure "+exposure+"->"+next+" type="+overlayLayout.type+" alpha="+overlayLayout.alpha+"->"+alpha+" flags="+overlayLayout.flags+"->"+flags);
                if(overlayLayout.alpha!=alpha)WebHost.trace("alpha requested "+overlayLayout.alpha+"->"+alpha+" state="+next);
                WebHost.trace("window update requested type="+overlayLayout.type+" alpha="+alpha+" flagsBefore="+overlayLayout.flags+" flagsAfter="+flags);
                overlayLayout.alpha=alpha;overlayLayout.flags=flags;
                try{(gateWindow==null?window:gateWindow).updateViewLayout(surface,overlayLayout);WebHost.trace("window update accepted");}
                catch(SecurityException|IllegalArgumentException e){
                    if(overlayLayout.type!=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)throw e;
                    applicationWindowFailed=true;UnlockDiagnostics.get(this).failure("update",e);exposure=Exposure.SUSPENDED;WebHost.trace("fallback pending reason="+e.getClass().getSimpleName());
                    // Do not recursively remove/add a window from a render callback.
                    main.post(this::reconcileWindowPermission);return;
                }
            }
        }
        if(exposure!=next)WebHost.trace("state requested "+exposure+"->"+next+" input="+(input?inputMode:"blocked"));
        exposure=next;
        if(dictionaryHost==null&&!input&&!hidden&&prepareHidden)overlay.prepareOverlay();
    }
    JSONObject startWindowStatus() throws JSONException {
        boolean requested=store.settings.getBoolean("start-under-keyguard",true),permission=android.provider.Settings.canDrawOverlays(this);
        String actual=overlayLayout==null?"none":overlayLayout.type==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY?"application":"accessibility";
        boolean application=actual.equals("application");
        String status=!requested?(application?"disabled-pending":"disabled"):!permission?(application?"revoked-pending":"permission-required"):applicationWindowFailed?(application?"failed-pending":"failed"):!"unlock".equals(gate)?"app-gate":actual.equals("application")?"applied":"pending";
        return UnlockDiagnostics.get(this).windowStatus().put("status",status).put("actual",actual).put("permission",permission).put("requested",requested?"application":"accessibility").put("exposure",exposure.name());
    }
    void openDictionary(String word,java.util.function.Consumer<String> result){
        if(dictionaryOpening){result.accept("사전을 여는 중입니다. 잠시 후 다시 시도해 주세요.");return;}
        if(dictionaryHost!=null){dictionaryHost.search(word,result);return;}
        if(!overlayVisible||overlay==null||!overlay.ready||!overlay.frameReady){result.accept("시작화면을 준비한 후 다시 검색해 주세요.");return;}
        dictionaryOpening=true;dictionaryWord=word;dictionaryResult=result;
        try{
            startActivity(new Intent(this,DictionaryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    |Intent.FLAG_ACTIVITY_NO_ANIMATION|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        }catch(RuntimeException e){finishDictionaryRequest("사전 화면을 열지 못했습니다.");dictionaryOpening=false;revealOverlay();}
    }
    void finishDictionaryRequest(String error){
        java.util.function.Consumer<String> result=dictionaryResult;dictionaryResult=null;dictionaryWord="";
        if(result!=null)result.accept(error);
    }
    boolean dictionaryReady(DictionaryActivity activity){
        if(dictionaryHost!=activity||!dictionaryOpening||!overlayVisible||!interactive)return false;
        checkpoint();dictionaryOpening=false;
        // Only expose the Activity once the *same* WebView has drawn there.
        setOverlayExposure(false,false);overlayWindow.removeAllViews();
        return true;
    }
    void returnDictionary(DictionaryActivity activity){
        if(dictionaryHost!=activity)return;
        checkpoint();finishDictionaryRequest("사전 검색을 취소했습니다.");
        overlay.firstFrameCallback=null;
        activity.coverStart(); // Keep old pixels until the original window has drawn again.
        dictionaryHost=null;dictionaryOpening=false;
        overlayWindow.removeAllViews();overlayWindow.addView(overlay.root,new android.widget.FrameLayout.LayoutParams(-1,-1));
        utility="첫 화면";
        if(overlayVisible&&interactive&&!keyguardLocked()){
            overlay.firstFrameCallback=()->{revealOverlay();activity.finish();};
            overlay.prepareOverlay();
        }else {activity.finish();revealOverlay();}
    }
    void hideOverlay(){endUnlockTransition("hide");overlayVisible=false;if(dictionaryHost!=null)dictionaryHost.returnToStart();finishDictionaryRequest("사전 검색을 취소했습니다.");dictionaryOpening=false;checkpoint();overlayVisible=false;if(overlay!=null){applyExposure(Exposure.HIDDEN);overlay.pause();reconcileWindowPermission();}segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();utility="첫 화면";}
    void choosePhone(JSONObject p) throws Exception {checkpoint();phoneEvent=store.event("unlock",p.getString("label"),"",p.optString("pkg"),0,p.optInt("aimless",0)==1);hideOverlay();launch(p);enforce();ensurePulse();}
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
    void policyChanged(){main.post(()->{reconcileWindowPermission();observeForeground(null);checkpoint();enforce();ensurePulse();WebHost.refreshAll();});}
    JSONObject state() throws JSONException {String pkg=overlayVisible?gatePkg:sessionPkg;return new JSONObject().put("mode",gate).put("pkg",pkg).put("label",UsageReader.label(this,pkg)).put("purpose",purpose).put("daily",dailyRemaining(pkg)).put("session",sessionRemaining).put("activePkg",sessionPkg).put("free",!store.managed(System.currentTimeMillis())).put("paused",systemPaused||foreground.equals(getPackageName())).put("visible",overlayVisible);}
    final Runnable lockCompletion=()->{
        if(!lockPending)return;
        if(!getSystemService(PowerManager.class).isInteractive()||keyguardLocked())return;
        lockPending=false;revealOverlay();
        android.widget.Toast.makeText(this,"화면을 잠그지 못했습니다. 다시 시도해 주세요.",android.widget.Toast.LENGTH_LONG).show();
    };
    void dismissStart(){
        if(!gate.equals("unlock")){stopApp();return;}
        if(lockPending)return;
        lockPending=true;
        // Keep the original opaque start on screen until Android actually locks it.
        if(Build.VERSION.SDK_INT<28||!performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)){
            lockPending=false;revealOverlay();throw new IllegalStateException("화면을 잠글 수 없습니다. 접근성 권한을 확인해 주세요.");
        }
        hideFloatingTimer();Notices.cancelTimer(this);
        main.postDelayed(lockCompletion,1500); // Failure recovery only; never delays exposure or locking.
    }
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){getSystemService(AppOpsManager.class).stopWatchingMode(overlayPermission);hideFloatingTimer();checkpoint();if(store!=null)store.observe("service-destroyed",interactive);live=null;interactive=false;overlayVisible=false;if(dictionaryHost!=null)dictionaryHost.returnToStart();main.removeCallbacksAndMessages(null);try{unregisterReceiver(screen);}catch(Exception ignored){}if(overlay!=null){removeGateWindow("service-destroy");overlay.close();}Notices.cancelTimer(this);super.onDestroy();}
}

