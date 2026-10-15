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
    WebHost overlay;WindowManager window;WindowManager.LayoutParams overlayLayout;final Set<String> systemPackages=new HashSet<>();
    String homePackage="";
    volatile boolean pulseScheduled;volatile long pulseCount;
    final Runnable pulse=new Runnable(){public void run(){pulseScheduled=false;if(live!=GuardService.this)return;pulseCount++;checkpoint();enforce();ensurePulse();}};
    final BroadcastReceiver screen=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String a=i.getAction();
        if(Intent.ACTION_SCREEN_OFF.equals(a))screenOff();
        else if(Intent.ACTION_USER_PRESENT.equals(a)){
            interactive=getSystemService(PowerManager.class).isInteractive();
            main.removeCallbacks(checkUnlock);checkUnlock.run();
        }else if(Intent.ACTION_SCREEN_ON.equals(a)){
            interactive=true;
            if(waitingUnlock){unlockChecks=0;main.removeCallbacks(checkUnlock);checkUnlock.run();}
        }else if(Intent.ACTION_TIME_CHANGED.equals(a)||Intent.ACTION_TIMEZONE_CHANGED.equals(a)){checkpoint();policyChanged();Scheduler.schedule(c);}
    }};
    void screenOff(){
        if(!interactive&&waitingUnlock&&unlockPrepared)return;
        checkpoint();interactive=false;waitingUnlock=true;unlockPrepared=false;unlockTransition=false;
        if(lockPending){store.event("dismiss","열지 않기","","",0);lockPending=false;}
        main.removeCallbacks(lockCompletion);
        if(dictionaryHost!=null)dictionaryHost.returnToStart();
        else if(dictionaryOpening){dictionaryOpening=false;finishDictionaryRequest("사전 검색을 취소했습니다.");}
        overview=false;overviewCandidate="";clearSessions();foreground="";
        store.settings.edit().putBoolean("checkpoint-interactive",false).putString("last-package","").apply();
        systemPaused=false;main.removeCallbacks(checkUnlock);prepareUnlock();
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
        live=this;store=Store.get(this);window=getSystemService(WindowManager.class);floatingTimer=new FloatingTimer(this,window);interactive=getSystemService(PowerManager.class).isInteractive();wasManaged=store.managed(System.currentTimeMillis());
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
        if(!interactive||getSystemService(KeyguardManager.class).isKeyguardLocked()){if(waitingUnlock)revealOverlay();return;}
        completeUnlock();
        observeForeground(event);
        // Window events can precede availability of the new application's root.
        // Recheck after the transition rather than retaining an obsolete session.
        main.removeCallbacks(settledForeground);
        main.postDelayed(settledForeground,500);
    }
    boolean overview;String overviewCandidate="";long overviewCandidateSince;volatile String lastWindows="";
    void observeForeground(AccessibilityEvent event) {
        if(store==null||!interactive||getSystemService(KeyguardManager.class).isKeyguardLocked())return;
        List<ForegroundPolicy.Window> candidates=new ArrayList<>();StringBuilder diagnostic=new StringBuilder();
        View surface=overlayWindow==null?null:overlayWindow;
        boolean ownFocus=surface!=null&&surface.hasWindowFocus(),newSystemWindow=false;
        for(AccessibilityWindowInfo w:getWindows()) {
            if(Build.VERSION.SDK_INT>=28&&event!=null&&event.getEventType()==AccessibilityEvent.TYPE_WINDOWS_CHANGED
                &&(event.getWindowChanges()&AccessibilityEvent.WINDOWS_CHANGE_ADDED)!=0
                &&event.getWindowId()==w.getId()&&w.getType()==AccessibilityWindowInfo.TYPE_SYSTEM
                &&(w.isFocused()||w.isActive()))newSystemWindow=true;
            String pkg="";
            // Only obtain the window root's package; never traverse content or read text.
            if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION){AccessibilityNodeInfo root=w.getRoot();if(root!=null){CharSequence name=root.getPackageName();pkg=name==null?"":name.toString();root.recycle();}}
            if(pkg.equals("com.samsung.android.sidegesturepad"))continue;
            android.graphics.Rect bounds=new android.graphics.Rect();w.getBoundsInScreen(bounds);boolean covering=bounds.height()>getResources().getDisplayMetrics().heightPixels/2;
            candidates.add(new ForegroundPolicy.Window(pkg,w.getType(),w.isFocused(),w.isActive(),w.getLayer(),w.isInPictureInPictureMode(),covering));diagnostic.append(pkg).append(":type=").append(w.getType()).append(",focus=").append(w.isFocused()).append(",active=").append(w.isActive()).append(",layer=").append(w.getLayer()).append(",cover=").append(covering).append(";");
        }
        lastWindows=diagnostic.toString();ForegroundPolicy.Result visible=ForegroundPolicy.select(candidates,systemPackages);
        if(event!=null&&event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&event.getClassName()!=null){
            String name=event.getClassName().toString().toLowerCase(Locale.ROOT),pkg=event.getPackageName()==null?"":event.getPackageName().toString();
            boolean systemLauncher=pkg.equals(homePackage)||pkg.equals("com.android.systemui")||pkg.contains("launcher");
            if(systemLauncher&&(name.contains("recents")||name.contains("overview")||name.contains("recentapps"))){unlockTransition=false;overview=true;overviewCandidate="";}
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
        // A background application's root is not proof that keyguard teardown has finished.
        // Wait for our already-prepared window to own focus in a non-system snapshot.
        if(unlockTransition&&ownFocus&&!visible.paused&&overlay!=null&&overlay.presentationActive&&overlay.frameReady)
            unlockTransition=false;
        if(visible.paused){
            boolean explicitSystem=candidates.stream().anyMatch(w->w.type==AccessibilityWindowInfo.TYPE_APPLICATION&&w.focused
                &&(w.pkg.equals("android")||systemPackages.contains(w.pkg))&&!w.pkg.equals("com.android.systemui"));
            // NotificationShadeWindow is also the keyguard window: its class name alone
            // cannot distinguish an unlock event from a user's notification shade.
            if(Build.VERSION.SDK_INT<28&&event!=null&&event.getClassName()!=null){
                String name=event.getClassName().toString().toLowerCase(Locale.ROOT);
                explicitSystem|=name.contains("notification")||name.contains("shade");
            }
            if(explicitSystem||newSystemWindow)unlockTransition=false;
            pauseSystem();return;
        }
        if(visible.pkg.isEmpty())return; // transient/root unavailable: do not trust background event senders
        String pkg=visible.pkg;
        if(pkg.contains("incallui")||pkg.equals("com.android.server.telecom")){unlockTransition=false;if(overlayVisible)hideOverlay();pauseSystem();return;}
        switchForeground(pkg);
    }
    void pauseSystem(){
        // Keyguard teardown can report a system window before the app window exists.
        if(lockPending)return;
        if(unlockTransition&&overlayVisible&&"unlock".equals(gate)&&!overview)return;
        hideFloatingTimer();
        boolean changed=!systemPaused;if(changed){checkpoint();systemPaused=true;lastNotice=-1;}
        if(overlayVisible&&overlay!=null&&dictionaryHost==null&&!dictionaryOpening)setOverlayExposure(false);
        if(changed&&!sessionPkg.isEmpty())Notices.paused(this,UsageReader.label(this,sessionPkg),dailyRemaining(sessionPkg),sessionRemaining);
        ensurePulse();
    }
    void switchForeground(String pkg){
        if(dictionaryHost!=null)dictionaryHost.foregroundChanged(pkg);
        if(systemPaused){checkpoint();systemPaused=false;lastNotice=-1;}
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
        if(store==null)return;long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();long delta=Math.max(0,elapsed-segmentElapsed);
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
        if(!interactive||systemPaused||foreground.equals(getPackageName())||getSystemService(KeyguardManager.class).isKeyguardLocked()||foreground.isEmpty())return;
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
            &&!getSystemService(KeyguardManager.class).isKeyguardLocked()
            &&(LockyApp.app==null||!foreground.equals(LockyApp.app.authBrowser));
        floatingTimer.update(visible,foreground,sessionEvent,rule==null?"":rule.optString("label"),purpose,sessionRemaining,dailyRemaining(foreground));
    }
    void hideFloatingTimer(){if(floatingTimer!=null)floatingTimer.hide();}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(overlay!=null)overlay.applyTheme();hideFloatingTimer();syncFloatingTimer();if(overlayVisible&&overlay!=null)revealOverlay();}
    long lastNotice=-1;
    void prepareUnlock(){
        if(unlockPrepared)return;
        unlockPrepared=true;
        boolean reused=overlay!=null&&gate.equals("unlock")&&overlay.pendingScreen.equals("unlock");
        showGate("unlock","");
        if(reused)overlay.evaluate("Promise.resolve(flushMemo()).then(()=>reshufflePurposesForStart())");
        utility="첫 화면";unlockChecks=0;
    }
    boolean completeUnlock(){
        if(!waitingUnlock||!interactive||getSystemService(KeyguardManager.class).isKeyguardLocked())return false;
        if(!unlockPrepared)prepareUnlock();
        waitingUnlock=false;systemPaused=false;unlockTransition=true;main.removeCallbacks(checkUnlock);
        // The caller reads the foreground once, retaining the actual event when available.
        revealOverlay();return true;
    }
    void showGate(String type,String pkg){
        hideFloatingTimer();
        boolean sameScreen=overlay!=null&&(overlayVisible||"unlock".equals(type))&&gate.equals(type)&&gatePkg.equals(pkg)&&overlay.pendingScreen.equals(type);
        boolean same=overlayVisible&&sameScreen;
        if(!same){checkpoint();gate=type;gatePkg=pkg;utility=type.equals("unlock")?"첫 화면":"사용 확인";}
        if(overlay==null){
            overlay=new WebHost(this,true);overlay.frameAvailableCallback=this::revealOverlay;
            overlayWindow=new android.widget.FrameLayout(this);
            overlayWindow.getViewTreeObserver().addOnWindowFocusChangeListener(focused->{
                if(focused&&unlockTransition)observeForeground(null);
            });
            overlayWindow.addView(overlay.root,new android.widget.FrameLayout.LayoutParams(-1,-1));
            window.addView(overlayWindow,gateLayout());
        }
        overlayVisible=true;
        // Reuse the DOM on repeated unlock/show events, but always restore the surface.
        if(!sameScreen)overlay.screen(type);
        revealOverlay();ensurePulse();
    }
    WindowManager.LayoutParams gateLayout(){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.OPAQUE);
        p.alpha=0;p.gravity=Gravity.TOP|Gravity.START;p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;p.setTitle("Locky");overlayLayout=p;return p;
    }
    void revealOverlay(){
        if(dictionaryHost!=null||dictionaryOpening)return;
        if(overlayVisible&&overlay!=null)setOverlayExposure(interactive&&!waitingUnlock&&!systemPaused
            &&!getSystemService(KeyguardManager.class).isKeyguardLocked()&&(overlay.frameReady||(!overlay.everRendered&&overlay.loadFailed)));
    }
    void setOverlayExposure(boolean exposed){setOverlayExposure(exposed,true);}
    void setOverlayExposure(boolean exposed,boolean prepareHidden){
        if(overlay==null)return;
        android.view.View surface=overlayWindow==null?overlay.root:overlayWindow;
        if(surface.getVisibility()!=View.VISIBLE)surface.setVisibility(View.VISIBLE);
        int accessibility=exposed?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
        if(surface.getImportantForAccessibility()!=accessibility)surface.setImportantForAccessibility(accessibility);
        if(dictionaryHost==null){
            if(overlay.root.getVisibility()!=View.VISIBLE)overlay.root.setVisibility(View.VISIBLE);
            // Activate the prepared presentation before committing visible/input flags together.
            if(exposed)overlay.resumeOverlay();
        }
        int passive=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if(overlayLayout!=null){
            float alpha=exposed?1f:0f;int flags=exposed?overlayLayout.flags&~passive:overlayLayout.flags|passive;
            if(overlayLayout.alpha!=alpha||overlayLayout.flags!=flags){
                overlayLayout.alpha=alpha;overlayLayout.flags=flags;window.updateViewLayout(surface,overlayLayout);
            }
        }
        // Hide private pixels and release input before doing any preparation on a locked phone.
        if(dictionaryHost==null&&!exposed&&prepareHidden)overlay.prepareOverlay();
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
        if(overlayVisible&&interactive&&!getSystemService(KeyguardManager.class).isKeyguardLocked()){
            overlay.firstFrameCallback=()->{revealOverlay();activity.finish();};
            overlay.prepareOverlay();
        }else {activity.finish();revealOverlay();}
    }
    void hideOverlay(){unlockTransition=false;overlayVisible=false;if(dictionaryHost!=null)dictionaryHost.returnToStart();finishDictionaryRequest("사전 검색을 취소했습니다.");dictionaryOpening=false;checkpoint();overlayVisible=false;if(overlay!=null){overlay.pause();overlay.root.setVisibility(View.GONE);if(overlayWindow!=null)overlayWindow.setVisibility(View.GONE);}segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();utility="첫 화면";}
    void choosePhone(JSONObject p) throws Exception {checkpoint();phoneEvent=store.event("unlock",p.getString("label"),"",p.optString("pkg"),0,p.optInt("aimless",0)==1);hideOverlay();launch(p);enforce();ensurePulse();}
    void allowApp(JSONObject p,int minutes) throws Exception {
        checkpoint();long remaining=dailyRemaining(gatePkg);JSONObject rule=store.rule(gatePkg);if(!store.managed(System.currentTimeMillis())){hideOverlay();return;}
        if(rule==null||rule.optString("mode").equals("locked")||remaining<=0){enforce();throw new IllegalStateException("지금은 사용할 수 없는 앱입니다.");}
        if(!gatePkg.equals(foreground))throw new IllegalStateException("앱이 바뀌었습니다. 다시 열어 주세요.");
        pausedSessions.remove(gatePkg);sessionPolicyEnd=policyEnd(System.currentTimeMillis());sessionPkg=gatePkg;purpose=p.getString("label");sessionRemaining=Rules.grant(remaining,minutes);sessionEvent=store.event("app",purpose,gatePkg,p.optString("pkg"),phoneEvent,p.optInt("aimless",0)==1);hideOverlay();lastNotice=-1;Notices.timer(this,rule.optString("label"),remaining,sessionRemaining);launch(p);syncFloatingTimer();ensurePulse();
    }
    void extend(int minutes) {checkpoint();if(!sessionPkg.equals(gatePkg)||dailyRemaining(gatePkg)<=0){enforce();return;}sessionRemaining=Rules.grant(dailyRemaining(gatePkg),minutes);store.event("extend",purpose,sessionPkg,"",phoneEvent);hideOverlay();lastNotice=-1;enforce();ensurePulse();}
    void stopApp(){hideFloatingTimer();checkpoint();pausedSessions.remove(sessionPkg.isEmpty()?gatePkg:sessionPkg);clearActiveSession();performGlobalAction(GLOBAL_ACTION_HOME);foreground="";hideOverlay();Notices.cancelTimer(this);main.removeCallbacks(pulse);pulseScheduled=false;}
    void useUtility(String name){checkpoint();boolean changed=!name.equals(utility);utility=name;if(changed&&!name.equals("첫 화면")&&!name.equals("Locky"))store.event("utility",name,getPackageName(),"",phoneEvent);}
    void pauseForActivity(){switchForeground(getPackageName());if(!sessionPkg.isEmpty())Notices.paused(this,UsageReader.label(this,sessionPkg),dailyRemaining(sessionPkg),sessionRemaining);}
    void openMain(String screen){openMain(screen,"");}
    void openMain(String screen,String chatId){useUtility(screen.equals("chats")||screen.equals("chat")?"ChatGPT":screen.equals("notes")?"메모":"Locky");hideOverlay();Intent launch=MainActivity.launchIntent(this).putExtra("screen",screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if("chat".equals(screen)&&!chatId.isEmpty())launch.putExtra("chat",chatId);startActivity(launch);}
    void launch(JSONObject p) throws Exception {String pkg=p.optString("pkg"),uri=p.optString("uri");if(pkg.isEmpty()&&uri.isEmpty())return;Intent i=Links.intent(this,pkg,uri);if(i==null)throw new IllegalStateException("연결한 앱이나 동작을 열 수 없습니다. 목적 연결을 확인해 주세요.");startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    void policyChanged(){main.post(()->{observeForeground(null);checkpoint();enforce();ensurePulse();WebHost.refreshAll();});}
    JSONObject state() throws JSONException {String pkg=overlayVisible?gatePkg:sessionPkg;return new JSONObject().put("mode",gate).put("pkg",pkg).put("label",UsageReader.label(this,pkg)).put("purpose",purpose).put("daily",dailyRemaining(pkg)).put("session",sessionRemaining).put("activePkg",sessionPkg).put("free",!store.managed(System.currentTimeMillis())).put("paused",systemPaused||foreground.equals(getPackageName())).put("visible",overlayVisible);}
    final Runnable lockCompletion=()->{
        if(!lockPending)return;
        if(!getSystemService(PowerManager.class).isInteractive()||getSystemService(KeyguardManager.class).isKeyguardLocked())return;
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
    @Override public void onDestroy(){hideFloatingTimer();checkpoint();live=null;interactive=false;overlayVisible=false;if(dictionaryHost!=null)dictionaryHost.returnToStart();main.removeCallbacksAndMessages(null);try{unregisterReceiver(screen);}catch(Exception ignored){}if(overlay!=null){try{window.removeViewImmediate(overlayWindow==null?overlay.root:overlayWindow);}catch(IllegalArgumentException ignored){}overlay.close();}Notices.cancelTimer(this);super.onDestroy();}
}

