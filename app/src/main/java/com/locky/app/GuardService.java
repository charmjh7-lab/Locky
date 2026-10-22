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
    volatile String foreground="",sessionPkg="",purpose="",utility="첫 화면";long segmentWall,segmentElapsed,segmentUptime,sessionEvent,phoneEvent;volatile long sessionRemaining;
    volatile boolean interactive,wasManaged,systemPaused;volatile boolean overlayVisible;volatile String gate="",gatePkg="";
    static final long SESSION_RETURN_GRACE=5*60_000L;
    static final class PausedSession {
        final long remaining,event,until,policyEnd;final String purpose;
        PausedSession(long remaining,long event,String purpose,long until,long policyEnd){this.remaining=remaining;this.event=event;this.purpose=purpose;this.until=until;this.policyEnd=policyEnd;}
    }
    final Map<String,PausedSession> pausedSessions=new HashMap<>();
    long sessionPolicyEnd;
    static final class LinkedLaunch {
        final String token=UUID.randomUUID().toString(),pkg;final JSONObject purpose;final long event,expires;final int minutes=5;
        LinkedLaunch(String pkg,JSONObject purpose,long event) throws JSONException {this.pkg=pkg;this.purpose=new JSONObject(purpose.toString());this.event=event;expires=SystemClock.elapsedRealtime()+10000;}
    }
    LinkedLaunch linkedLaunch;
    void prepareLinkedLaunch(JSONObject purpose,Intent launch) throws JSONException {
        linkedLaunch=null;String pkg=purpose.optString("pkg");
        // An unscoped URI/chooser must never turn into a grant for an arbitrary app.
        ComponentName resolved=launch.resolveActivity(getPackageManager());
        if(pkg.isEmpty()||resolved==null||!pkg.equals(resolved.getPackageName()))return;
        linkedLaunch=new LinkedLaunch(pkg,purpose,phoneEvent);
        LinkedLaunch expected=linkedLaunch;main.postDelayed(()->{if(linkedLaunch==expected)linkedLaunch=null;},10000);
    }
    void cancelLinkedLaunch(){linkedLaunch=null;}
    void consumeLinkedLaunch() throws Exception {
        LinkedLaunch pending=linkedLaunch;if(pending==null)return;
        if(SystemClock.elapsedRealtime()>pending.expires||!interactive||keyguardLocked()){linkedLaunch=null;return;}
        if(foreground.equals(getPackageName())||foreground.isEmpty())return;
        linkedLaunch=null;
        if(!foreground.equals(pending.pkg))return;
        // switchForeground has already restored any unexpired per-app grant.
        if(foreground.equals(sessionPkg))return;
        if(!store.managed(System.currentTimeMillis()))return;
        JSONObject rule=policyRule(foreground);
        if(rule==null||!"limited".equals(rule.optString("mode"))||dailyRemaining(foreground)<=0)return;
        beginAppSession(foreground,pending.purpose,pending.minutes,pending.event);
    }
    FloatingTimer floatingTimer;
    PhoneStartController phoneStart;
    boolean lockPending;
    android.widget.FrameLayout overlayWindow;
    enum Exposure { HIDDEN, WAITING, INTERACTIVE, SUSPENDED }
    Exposure exposure=Exposure.HIDDEN;
    Boolean observedKeyguard;
    boolean overlayAttached,overlayAttachPending,overlayAttachFailed,overlayLayoutDirty;int overlayAttachRetries;Runnable overlayAttachRetry;long foregroundDetectedUptime,gateDetectedUptimeSeen;
    WebHost overlay;long gateTransition;WindowManager window;WindowManager.LayoutParams overlayLayout;final Set<String> systemPackages=new HashSet<>();
    String homePackage="",homeClass="",launcherClass="";
    final Set<String> homeClasses=new HashSet<>();
    volatile boolean pulseScheduled;volatile long pulseCount;
    final Runnable pulse=new Runnable(){public void run(){pulseScheduled=false;if(live!=GuardService.this)return;pulseCount++;checkpoint();enforce();ensurePulse();}};
    final BroadcastReceiver screen=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String a=i.getAction();
        if(!Intent.ACTION_SCREEN_OFF.equals(a))WebHost.trace("broadcast observed="+(Intent.ACTION_SCREEN_ON.equals(a)?"screen-on":Intent.ACTION_USER_PRESENT.equals(a)?"user-present":"clock"));
        if(Intent.ACTION_SCREEN_OFF.equals(a))screenOff();
        else if(Intent.ACTION_USER_PRESENT.equals(a)||Intent.ACTION_SCREEN_ON.equals(a)){
            interactive=getSystemService(PowerManager.class).isInteractive();
            beginScreenObservation();completeUnlock();
        }else if(Intent.ACTION_TIME_CHANGED.equals(a)||Intent.ACTION_TIMEZONE_CHANGED.equals(a)){checkpoint();policyChanged();Scheduler.schedule(c);}
    }};
    void screenOff(){
        if(!interactive&&phoneStart!=null&&phoneStart.awaitingUnlock()){WebHost.trace("duplicate screen-off ignored");return;}
        PhoneStartActivity phone=PhoneStartActivity.current();if(phone!=null)phone.clearExternalReturn();
        store.screenState(ScreenHistory.OFF);checkpoint();interactive=false;cancelLinkedLaunch();hideFloatingTimer();
        if(lockPending){store.event("dismiss","열지 않기","","",0);lockPending=false;}
        main.removeCallbacks(lockCompletion);
        overview=false;overviewCandidate="";launcherClass="";clearSessions();foreground="";
        store.settings.edit().putBoolean("checkpoint-interactive",false).putString("last-package","").apply();
        setSystemPaused(false,"screen-off");hideOverlay();
        if(phoneStart==null)phoneStart=new PhoneStartController(this);
        phoneStart.screenOff();
        Notices.cancelTimer(this);main.removeCallbacks(pulse);pulseScheduled=false;
    }
    final Runnable settledForeground=()->observeForeground(null);
    void beginScreenObservation(){
        boolean active=interactive&&!keyguardLocked();store.screenState(active?ScreenHistory.ON:ScreenHistory.OFF);
        // Starting a newly observed active window never backfills the preceding gap.
        segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();segmentUptime=SystemClock.uptimeMillis();
    }
    boolean completeUnlock(){
        if(phoneStart==null||!phoneStart.unlocked())return false;
        setSystemPaused(false,"unlock-confirmed");ensurePulse();return true;
    }
    @Override protected void onServiceConnected(){
        live=this;store=Store.get(this);window=getSystemService(WindowManager.class);floatingTimer=new FloatingTimer(this,window);interactive=getSystemService(PowerManager.class).isInteractive();wasManaged=store.managed(System.currentTimeMillis());store.observe("service-connected",interactive);
        phoneStart=new PhoneStartController(this);
        Intent homeIntent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo home=getPackageManager().resolveActivity(homeIntent,PackageManager.MATCH_DEFAULT_ONLY);if(home!=null){homePackage=home.activityInfo.packageName;homeClass=home.activityInfo.targetActivity==null?home.activityInfo.name:home.activityInfo.targetActivity;rememberHome(home.activityInfo);}
        for(ResolveInfo candidate:getPackageManager().queryIntentActivities(homeIntent,PackageManager.MATCH_DEFAULT_ONLY))if(candidate.activityInfo.packageName.equals(homePackage))rememberHome(candidate.activityInfo);
        systemPackages.add("com.android.systemui");systemPackages.add("com.android.permissioncontroller");systemPackages.add("com.google.android.permissioncontroller");
        android.view.inputmethod.InputMethodManager ime=getSystemService(android.view.inputmethod.InputMethodManager.class);for(android.view.inputmethod.InputMethodInfo x:ime.getEnabledInputMethodList())systemPackages.add(x.getPackageName());
        IntentFilter f=new IntentFilter();for(String a:new String[]{Intent.ACTION_SCREEN_OFF,Intent.ACTION_SCREEN_ON,Intent.ACTION_USER_PRESENT,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED})f.addAction(a);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screen,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screen,f);
        segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();segmentUptime=SystemClock.uptimeMillis();
        beginScreenObservation();prepareAppGate();
        long recoveryFrom=store.settings.getLong("checkpoint",System.currentTimeMillis());String recoveryPackage=store.settings.getString("last-package","");
        if(!interactive||keyguardLocked())phoneStart.prepareAfterServiceRestart();else {completeUnlock();observeForeground(null);main.postDelayed(()->{if(foreground.isEmpty())observeForeground(null);},250);main.postDelayed(()->{if(foreground.isEmpty())observeForeground(null);},750);}Scheduler.schedule(this);recover(recoveryFrom,store.settings.getBoolean("checkpoint-interactive",true)?recoveryPackage:"");
    }
    private void recover(long from,String old){long now=System.currentTimeMillis();
        if(now-from<2000)return;
        LockyApp.app.backgroundWork.execute(()->{
            try {
                JSONArray history=UsageReader.intervals(this,Math.max(from,now-180L*86400000),now,old);
                for(int i=0;i<history.length();i++){
                    JSONObject u=history.getJSONObject(i);String pkg=u.getString("pkg");
                    if(!UsageReader.recordable(this,pkg)||pkg.equals(getPackageName()))continue;
                    // Recovery proves the app interval, never its historical management rule.
                    store.usage(u.getLong("start"),u.getLong("end"),pkg,UsageReader.label(this,pkg),false,0,"usage-access");
                }
            }catch(Exception error){android.util.Log.w("Locky","Usage recovery failed ("+error.getClass().getSimpleName()+")");}
            finally{policyChanged();}
        });
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(store==null)return;
        boolean currentInteractive=getSystemService(PowerManager.class).isInteractive();
        if(currentInteractive!=interactive){if(!currentInteractive)screenOff();else {interactive=true;beginScreenObservation();}}
        if(!interactive||keyguardLocked()){if(overlayVisible)hideOverlay();return;}
        completeUnlock();
        observeForeground(event);
        // Window events can precede availability of the new application's root.
        // Recheck after the transition rather than retaining an obsolete session.
        main.removeCallbacks(settledForeground);
        if(!homeObserved&&(foreground.isEmpty()||systemPaused||overview||foregroundUncertain))main.postDelayed(settledForeground,500);
    }
    boolean keyguardLocked(){
        boolean locked=getSystemService(KeyguardManager.class).isKeyguardLocked();
        if(observedKeyguard==null||observedKeyguard!=locked){observedKeyguard=locked;if(locked){PhoneStartActivity phone=PhoneStartActivity.current();if(phone!=null)phone.clearExternalReturn();}if(store!=null)store.screenState(locked?ScreenHistory.OFF:interactive?ScreenHistory.ON:ScreenHistory.OFF);WebHost.trace("keyguard observed locked="+locked);}
        return locked;
    }
    boolean acceptGateInput(){return overlay!=null&&overlay.currentGateReady()&&exposure==Exposure.INTERACTIVE&&currentGateEntry(overlay,overlay.gateTransition);}
    boolean acceptGateInput(long token,String pkg){
        if(token!=gateTransition||!pkg.equals(gatePkg)||!acceptGateInput())return false;
        // Revalidate actual windows at the action boundary, before saving or granting.
        observeForeground(null);
        return token==gateTransition&&pkg.equals(gatePkg)&&acceptGateInput();
    }
    void requestEditorInput(){
        if(!acceptGateInput()||overlay==null)return;
        overlay.view.requestFocus();
        getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(overlay.view,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }
    void setSystemPaused(boolean value,String reason){if(systemPaused!=value){systemPaused=value;WebHost.trace("systemPaused="+value+" reason="+reason);}}
    boolean overview,homeObserved,foregroundUncertain;String overviewCandidate="";long overviewCandidateSince;volatile String lastWindows="";
    void rememberHome(ActivityInfo info){homeClasses.add(homeClassName(info.name));if(info.targetActivity!=null)homeClasses.add(homeClassName(info.targetActivity));}
    String homeClassName(String name){return name.startsWith(".")?homePackage+name:name;}
    boolean isHomeClass(String name){return !name.isEmpty()&&(homeClassName(name).equals(homeClassName(homeClass))||homeClasses.contains(homeClassName(name)));}
    void observeForeground(AccessibilityEvent event) {
        homeObserved=false;foregroundUncertain=false;
        if(store==null||!interactive||keyguardLocked())return;
        List<ForegroundPolicy.Window> candidates=new ArrayList<>();StringBuilder diagnostic=new StringBuilder();String focusedHomeClass="";
        WebHost.trace("accessibility observed event="+(event==null?0:event.getEventType())+" changes="+(event!=null&&Build.VERSION.SDK_INT>=28?event.getWindowChanges():0)+" window="+(event==null?-1:event.getWindowId()));
        for(AccessibilityWindowInfo w:getWindows()) {
            String pkg="",windowClass="";
            if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION||w.getType()==AccessibilityWindowInfo.TYPE_SYSTEM){AccessibilityNodeInfo root=w.getRoot();if(root!=null){CharSequence name=root.getPackageName();pkg=name==null?"":name.toString();CharSequence klass=root.getClassName();windowClass=klass==null?"":klass.toString();root.recycle();}}
            if(pkg.equals("com.samsung.android.sidegesturepad"))continue;
            android.graphics.Rect bounds=new android.graphics.Rect();w.getBoundsInScreen(bounds);boolean covering=bounds.height()>getResources().getDisplayMetrics().heightPixels/2;
            if(pkg.equals(homePackage)){
                android.util.DisplayMetrics display=getResources().getDisplayMetrics();
                covering=bounds.width()>=display.widthPixels*0.9&&bounds.height()>=display.heightPixels*0.85;
                if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&w.isFocused()&&w.isActive()&&covering&&!w.isInPictureInPictureMode()&&isHomeClass(windowClass))focusedHomeClass=windowClass;
            }
            candidates.add(new ForegroundPolicy.Window(pkg,w.getType(),w.isFocused(),w.isActive(),w.getLayer(),w.isInPictureInPictureMode(),covering));diagnostic.append(pkg).append(":type=").append(w.getType()).append(",focus=").append(w.isFocused()).append(",active=").append(w.isActive()).append(",layer=").append(w.getLayer()).append(",cover=").append(covering).append(";");
        }
        lastWindows=diagnostic.toString();ForegroundPolicy.Result visible=ForegroundPolicy.select(candidates,systemPackages);
        foregroundUncertain=visible.pkg.isEmpty()||event!=null&&event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            &&event.getPackageName()!=null&&!event.getPackageName().toString().equals(visible.pkg);
        if(event!=null&&event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&event.getClassName()!=null){
            String name=event.getClassName().toString().toLowerCase(Locale.ROOT),pkg=event.getPackageName()==null?"":event.getPackageName().toString();
            if(pkg.equals(homePackage))launcherClass=event.getClassName().toString();
            boolean systemLauncher=pkg.equals(homePackage)||pkg.equals("com.android.systemui")||pkg.contains("launcher");
            if(systemLauncher&&(name.contains("recents")||name.contains("overview")||name.contains("recentapps"))){overview=true;overviewCandidate="";}
            else if(pkg.equals(homePackage)&&isHomeClass(event.getClassName().toString())&&visible.pkg.equals(homePackage)){overview=false;overviewCandidate="";}
        }
        // Current focused-window evidence supersedes an obsolete launcher event.
        if(!overview&&!visible.paused&&visible.pkg.equals(homePackage)&&!focusedHomeClass.isEmpty()){
            launcherClass=focusedHomeClass;overview=false;overviewCandidate="";
        }
        // A recent-task thumbnail can expose its old app window before the user actually enters it.
        // Require the target application window to hold focus through a short settle period.
        if(overview&&!visible.pkg.equals(homePackage)&&!visible.pkg.equals(getPackageName())
            &&ForegroundPolicy.confirmedEntry(candidates,visible,event==null||event.getPackageName()==null?"":event.getPackageName().toString(),
                event!=null&&event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)){
            overview=false;overviewCandidate="";
        }
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
        if(visible.pkg.isEmpty()){if(overlayVisible)pauseSystem("window-unavailable");return;} // Never expose stale app choices.
        String pkg=visible.pkg;
        if(pkg.contains("incallui")||pkg.equals("com.android.server.telecom")){if(overlayVisible)hideOverlay();pauseSystem();return;}
        if(restorePhoneFromForeground(visible,candidates,event==null||event.getPackageName()==null?"":event.getPackageName().toString(),
            event!=null&&event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,event==null))return;
        switchForeground(pkg);
    }
    boolean restorePhoneFromForeground(ForegroundPolicy.Result visible,List<ForegroundPolicy.Window> candidates,String eventPkg,boolean stateEvent,boolean settled){
        PhoneStartActivity phone=PhoneStartActivity.current();
        if(lockPending||!interactive||keyguardLocked()||overview||visible.paused||visible.pkg.isEmpty()
            ||phone==null||!phone.purposePending()||phone.resumed||phone.cycle!=store.settings.getLong("phone-start-cycle",phone.cycle)
            ||getPackageName().equals(visible.pkg)||phone.allowsDictionary(visible.pkg)||systemPackages.contains(visible.pkg)
            ||visible.pkg.equals("android")||visible.pkg.contains("intentresolver")||visible.pkg.contains("incallui")||visible.pkg.equals("com.android.server.telecom"))return false;
        boolean focused=candidates.stream().anyMatch(w->w.type==AccessibilityWindowInfo.TYPE_APPLICATION&&w.pkg.equals(visible.pkg)&&w.focused&&w.active&&w.covering&&!w.pip);
        if(homePackage.equals(visible.pkg)){
            if(homeClass.isEmpty()||!isHomeClass(launcherClass))return false;
            homeObserved=focused;
            return focused&&phone.returnFromForeground(false);
        }
        if(!focused)return false;
        if(!ForegroundPolicy.confirmedEntry(candidates,visible,eventPkg,stateEvent)&&!settled){
            foregroundUncertain=true; // Reuse the existing settled window observation, never an event sender alone.
            return true;
        }
        return phone.returnFromForeground(true);
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
        if(linkedLaunch!=null&&!pkg.isEmpty()&&!pkg.equals(getPackageName())&&!pkg.equals(linkedLaunch.pkg))cancelLinkedLaunch();
        if(!pkg.equals(homePackage))launcherClass="";
        if(systemPaused){checkpoint();setSystemPaused(false,"foreground");lastNotice=-1;}
        if(!pkg.equals(foreground)) {
            foregroundDetectedUptime=SystemClock.uptimeMillis();
            checkpoint();
            // Preserve each app's original grant; time away never buys a new grant.
            if(!sessionPkg.isEmpty()&&!pkg.equals(sessionPkg)&&!pkg.equals(getPackageName()))parkSession();
            foreground=pkg;segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();segmentUptime=SystemClock.uptimeMillis();
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
            if(pkg.equals(homePackage)){segmentWall=wall;segmentElapsed=elapsed;segmentUptime=SystemClock.uptimeMillis();store.settings.edit().putLong("checkpoint",wall).putString("last-package",foreground).putBoolean("checkpoint-interactive",interactive).apply();return;}boolean charge=!overlayVisible&&!systemPaused&&pkg.equals(sessionPkg)&&!UsageReader.excluded(this,pkg);
            ScreenHistory.Evidence evidence=ScreenHistory.read(this,from,wall,ScreenHistory.ON,false);
            // Deep sleep is direct evidence that a delayed checkpoint is not a
            // continuous foreground observation. Without screen transitions, keep it unknown.
            boolean suspended=segmentUptime>0&&delta-(SystemClock.uptimeMillis()-segmentUptime)>1000;
            boolean currentActive=getSystemService(PowerManager.class).isInteractive()&&!keyguardLocked();
            if((suspended&&!evidence.hasInactive)||(!currentActive&&!evidence.hasStop&&!evidence.hasInactive))evidence=ScreenHistory.read(this,from,wall,ScreenHistory.UNKNOWN,false);
            long firstInactive=Long.MAX_VALUE;for(ScreenHistory.Span span:evidence.spans)if(span.state==ScreenHistory.OFF)firstInactive=Math.min(firstInactive,span.start);
            for(long[] span:evidence.active()){if(span[0]>=firstInactive){clearSessions();cancelLinkedLaunch();charge=false;}long at=span[0],end=span[1];while(at<end){long boundary=Math.min(end,Math.min(Rules.nextDay(at,ZoneId.systemDefault()),Rules.nextBoundary(at,store.settings.getInt("start",540),store.settings.getInt("end",1260),ZoneId.systemDefault())));boolean managed=store.managed(at);store.usage(at,boundary,pkg,label,charge&&managed,charge?sessionEvent:phoneEvent);if(charge&&managed){sessionRemaining=Math.max(0,sessionRemaining-(boundary-at));invalidateGateBudget();}at=boundary;}}
            if(firstInactive<wall){clearSessions();cancelLinkedLaunch();}
        }
        segmentWall=wall;segmentElapsed=elapsed;segmentUptime=SystemClock.uptimeMillis();store.settings.edit().putLong("checkpoint",wall).putString("last-package",foreground).putBoolean("checkpoint-interactive",interactive).apply();
    }
    void flushOnMain(){if(Looper.myLooper()==Looper.getMainLooper()){checkpoint();return;}CountDownLatch latch=new CountDownLatch(1);main.post(()->{try{checkpoint();}finally{latch.countDown();}});try{latch.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    boolean shouldPulse(){return interactive&&!foreground.isEmpty()&&(!sessionPkg.isEmpty()||overlayVisible);}
    void ensurePulse(){if(shouldPulse()){if(!pulseScheduled){pulseScheduled=true;main.postDelayed(pulse,1000);}}else{main.removeCallbacks(pulse);pulseScheduled=false;}}
    // Request-local reads are never shared between bridge workers and the service thread.
    private static final class BudgetRead {
        long now=System.currentTimeMillis(),revision=-1,until;
        final Map<String,Long> remaining=new HashMap<>();final Map<String,JSONObject> rules=new HashMap<>();
    }
    private final ThreadLocal<BudgetRead> budgetReads=new ThreadLocal<>();
    private volatile long gateDailyUntil,gateDaily,gateDailyFrom,gateDailyRevision=-1;
    private volatile JSONObject gateRule;
    private volatile String gateDailyPkg="";
    private BudgetRead budgetRead(){
        BudgetRead read=budgetReads.get();if(read==null)return null;
        long now=System.currentTimeMillis(),revision=store.usageRevision();
        if(read.revision!=revision||now<read.now||now>=read.until){
            read.remaining.clear();read.rules.clear();read.now=now;read.until=policyEnd(now);read.revision=revision;
        }
        return read;
    }
    private boolean gateBudgetValid(String pkg,long now){
        return overlayVisible&&pkg.equals(gatePkg)&&pkg.equals(gateDailyPkg)&&now>=gateDailyFrom&&now<gateDailyUntil&&gateDailyRevision==store.usageRevision();
    }
    JSONObject policyRule(String pkg){
        BudgetRead read=budgetRead();long now=read==null?System.currentTimeMillis():read.now;
        if(read!=null&&read.rules.containsKey(pkg))return read.rules.get(pkg);
        JSONObject rule=gateBudgetValid(pkg,now)?gateRule:store.rule(pkg);
        if(read!=null)read.rules.put(pkg,rule);return rule;
    }
    void invalidateGateBudget(){gateDailyUntil=0;BudgetRead read=budgetReads.get();if(read!=null){read.remaining.clear();read.rules.clear();read.revision=-1;}}
    long dailyRemaining(String pkg){
        // A concurrent recovery/evidence write cannot publish a stale budget with a new revision.
        synchronized(store){
            BudgetRead read=budgetRead();long now=read==null?System.currentTimeMillis():read.now;
            if(gateBudgetValid(pkg,now))return gateDaily;
            if(read!=null&&read.remaining.containsKey(pkg))return read.remaining.get(pkg);
            JSONObject rule=policyRule(pkg);long value=store.remaining(pkg,now,rule);
            if(read!=null){read.remaining.put(pkg,value);read.revision=store.usageRevision();}
            if(Looper.myLooper()==Looper.getMainLooper()&&overlayVisible&&pkg.equals(gatePkg))cacheGateBudget(pkg,rule,value,now);
            return value;
        }
    }
    private void cacheGateBudget(String pkg,JSONObject rule,long remaining,long now){
        gateDaily=remaining;gateDailyPkg=pkg;gateRule=rule;gateDailyFrom=now;gateDailyRevision=store.usageRevision();gateDailyUntil=policyEnd(now);
    }
    void enforce(){
        boolean ownsRead=budgetReads.get()==null;if(ownsRead)budgetReads.set(new BudgetRead());
        try {
        long now=System.currentTimeMillis();boolean managed=store.managed(now);if(managed!=wasManaged){wasManaged=managed;if(!managed){clearSessions();if(overlayVisible)hideOverlay();Notices.cancelTimer(this);}else{clearSessions();}Scheduler.schedule(this);}
        if(!interactive||keyguardLocked()){if(overlayVisible)hideOverlay();return;}
        if(!sessionPkg.isEmpty()&&sessionPolicyEnd>0&&now>=sessionPolicyEnd)clearSessions();
        PhoneStartActivity phone=PhoneStartActivity.current();
        if(phone!=null&&((phone.resumed&&!phone.chosen)||phone.allowsDictionary(foreground)))return;
        if(!interactive||systemPaused||foreground.equals(getPackageName())||keyguardLocked()||foreground.isEmpty())return;
        try{consumeLinkedLaunch();}catch(Exception error){cancelLinkedLaunch();android.util.Log.e("Locky","Linked app grant failed",error);}
        if(!LockyApp.app.authBrowser.isEmpty()&&foreground.equals(LockyApp.app.authBrowser)){if(overlayVisible)hideOverlay();Notices.cancelTimer(this);return;}
        if(!managed){if(overlayVisible)hideOverlay();Notices.cancelTimer(this);return;}
        JSONObject rule=policyRule(foreground);if(rule==null){if(overlayVisible)hideOverlay();Notices.cancelTimer(this);return;}
        long remaining=dailyRemaining(foreground);
        if(rule.optString("mode").equals("locked")||remaining<=0){if(rule.optString("mode").equals("limited"))store.blockUntilFree(foreground,now);showGate("blocked",foreground,remaining);Notices.cancelTimer(this);return;}
        if(!foreground.equals(sessionPkg)){showGate("app",foreground,remaining);Notices.cancelTimer(this);return;}
        if(sessionRemaining<=0){showGate("renew",foreground,remaining);Notices.cancelTimer(this);return;}
        if(!overlayVisible&&(now/30000!=lastNotice)){lastNotice=now/30000;Notices.timer(this,rule.optString("label"),remaining,sessionRemaining);}
        } finally {try{syncFloatingTimer();}finally{if(ownsRead)budgetReads.remove();}}
    }
    void syncFloatingTimer(){
        if(floatingTimer==null||store==null)return;
        boolean ownsRead=budgetReads.get()==null;if(ownsRead)budgetReads.set(new BudgetRead());
        try {
        long now=System.currentTimeMillis();JSONObject rule=policyRule(foreground);long remaining=dailyRemaining(foreground);
        boolean visible=store.settings.getBoolean("floatingTimer",true)&&interactive&&!systemPaused&&!overlayVisible
            &&!foreground.isEmpty()&&foreground.equals(sessionPkg)&&!foreground.equals(getPackageName())
            &&store.managed(now)&&rule!=null&&"limited".equals(rule.optString("mode"))
            &&remaining>0&&sessionRemaining>0
            &&!keyguardLocked()
            &&(LockyApp.app==null||!foreground.equals(LockyApp.app.authBrowser));
        floatingTimer.update(visible,foreground,sessionEvent,rule==null?"":rule.optString("label"),purpose,sessionRemaining,remaining);
        }finally{if(ownsRead)budgetReads.remove();}
    }
    void hideFloatingTimer(){if(floatingTimer!=null)floatingTimer.hide();}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(overlay!=null)overlay.applyTheme();hideFloatingTimer();syncFloatingTimer();if(overlayVisible&&overlay!=null)revealOverlay();}
    long lastNotice=-1;
    void prepareAppGate(){
        if(overlay==null){
            overlay=new WebHost(this,WebHost.Role.APP_GATE);
            overlay.frameAvailableCallback=this::revealOverlay;
            WebHost.trace("app-gate document prewarm");
        }
        prepareAppGateWindow();
        overlay.prepareOverlay();
    }
    void gateTrace(String event){UnlockDiagnostics.get(this).gateRecord(gateTransition,event);}
    void prepareAppGateWindow(){
        if(overlayWindow!=null){
            overlayAttached=overlayWindow.isAttachedToWindow();
            if(overlayAttached)return;
            // A parent can already belong to WindowManager while attach is in flight.
            // Never register it again, including while attachment is pending.
            if(overlayWindow.getParent()!=null||overlayAttachPending){scheduleOverlayAttachRetry();return;}
        }
        if(overlayAttachFailed||overlayAttachRetry!=null)return;
        if(overlayWindow==null){
            overlayWindow=new android.widget.FrameLayout(this){
                long touchTransition=-1,keyTransition=-1;
                @Override public boolean dispatchTouchEvent(MotionEvent event){
                    if(event.getActionMasked()==MotionEvent.ACTION_DOWN)touchTransition=gateTransition;
                    if(!(acceptGateInput()||acceptGateErrorInput())||touchTransition!=gateTransition){
                        touchTransition=-1;MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();return true;
                    }
                    return super.dispatchTouchEvent(event);
                }
                @Override public boolean dispatchKeyEvent(KeyEvent event){
                    if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)keyTransition=acceptGateInput()||acceptGateErrorInput()?gateTransition:-1;
                    return !(acceptGateInput()||acceptGateErrorInput())||keyTransition!=gateTransition||super.dispatchKeyEvent(event);
                }
            };
            overlayWindow.addView(overlay.root,new android.widget.FrameLayout.LayoutParams(-1,-1));
            overlayWindow.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                @Override public void onViewAttachedToWindow(View v){
                    overlayAttached=true;overlayAttachPending=false;overlayAttachFailed=false;overlayLayoutDirty=true;
                    cancelOverlayAttachRetry();overlayAttachRetries=0;
                    gateTrace("window attached");main.post(GuardService.this::revealOverlay);
                }
                @Override public void onViewDetachedFromWindow(View v){
                    overlayAttached=false;overlayAttachPending=false;
                    gateTrace("window detached");
                    overlay.resetVisualState("window-detach");
                    applyExposure(!overlayVisible?Exposure.HIDDEN:exposure==Exposure.INTERACTIVE?Exposure.WAITING:exposure);
                    if(overlayVisible)scheduleOverlayAttachRetry();
                }
            });
            overlayLayout=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    |WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
            overlayLayout.gravity=Gravity.TOP|Gravity.START;overlayLayout.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;overlayLayout.setTitle("Locky app gate");
            // Conceal at the window, not at the WebView: INVISIBLE also hides
            // WebContents from Chromium and lets its renderer go idle.
            overlayLayout.alpha=0f;
            overlayWindow.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            overlayWindow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        try{overlayAttachPending=true;window.addView(overlayWindow,overlayLayout);
            overlayAttached=overlayWindow.isAttachedToWindow();
            if(!overlayAttached)scheduleOverlayAttachRetry();
        }
        catch(RuntimeException error){
            // A failed attach retains the same host/window for the next entry retry.
            overlayAttached=overlayWindow.isAttachedToWindow();overlayAttachPending=false;
            UnlockDiagnostics.get(this).failure("app-gate-attach",error);gateTrace("window attach failed "+error.getClass().getSimpleName());
            if(!overlayAttached)scheduleOverlayAttachRetry();
        }
    }
    void cancelOverlayAttachRetry(){if(overlayAttachRetry!=null){main.removeCallbacks(overlayAttachRetry);overlayAttachRetry=null;}}
    void scheduleOverlayAttachRetry(){
        if(overlayAttachRetry!=null||overlayAttachFailed)return;
        overlayAttachRetry=()->{
            overlayAttachRetry=null;
            if(overlayWindow==null||overlay==null)return;
            overlayAttached=overlayWindow.isAttachedToWindow();
            if(overlayAttached){overlayAttachPending=false;overlayAttachRetries=0;revealOverlay();return;}
            // Confirm the last add on the next watch tick too: addView can return
            // before Android dispatches attach, so success must not time out inline.
            if(overlayAttachRetries>=2){
                overlayAttachFailed=true;overlayAttachPending=false;
                gateTrace("window recovery failed attached=false parent="+(overlayWindow.getParent()!=null));
                // A disconnected window is not a failed WebView frame.
                applyExposure(exposure);return;
            }
            ++overlayAttachRetries;
            gateTrace("window recovery retry="+overlayAttachRetries+" parent="+(overlayWindow.getParent()!=null));
            // A detached ViewRoot may still be clearing its parent. Do not remove/add
            // concurrently; wait for the parent to be cleared by WindowManager.
            if(overlayWindow.getParent()==null)overlayAttachPending=false;
            prepareAppGateWindow();
            if(overlayAttached){gateTrace("window recovery succeeded");revealOverlay();}
            else scheduleOverlayAttachRetry();
        };
        main.postDelayed(overlayAttachRetry,1200);
    }
    void openPurposeEditor(String pkg){
        Intent launch=MainActivity.launchIntent(this).putExtra("screen","settings").putExtra("appGateEditor",pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);switchForeground(getPackageName());
    }
    JSONObject gatePayload() throws JSONException {
        String scope=gatePkg.isEmpty()?"app":gatePkg;JSONObject rule=policyRule(gatePkg);
        return new JSONObject().put("gateTransition",gateTransition).put("gateExposure","WAITING").put("gate",state()).put("prefs",store.preferences()).put("free",!store.managed(System.currentTimeMillis()))
            .put("purposeDate",java.time.LocalDate.now().toString()).put("scope",scope).put("purposes",store.purposes(scope))
            .put("allPurposes",store.rows("SELECT * FROM purposes ORDER BY rowid")).put("rules",rule==null?new JSONArray():new JSONArray().put(rule));
    }
    void showGate(String type,String pkg){
        boolean ownsRead=budgetReads.get()==null;if(ownsRead)budgetReads.set(new BudgetRead());
        try{showGate(type,pkg,dailyRemaining(pkg));}finally{if(ownsRead)budgetReads.remove();}
    }
    void showGate(String type,String pkg,long remaining){
        if(!Set.of("app","blocked","renew").contains(type))throw new IllegalArgumentException("Not an app gate");
        long detected=foregroundDetectedUptime>gateDetectedUptimeSeen?foregroundDetectedUptime:SystemClock.uptimeMillis();
        hideFloatingTimer();
        boolean same=overlayVisible&&gate.equals(type)&&gatePkg.equals(pkg);
        if(!same){
            synchronized(store){
                BudgetRead read=budgetReads.get();long revision=read==null?store.usageRevision():read.revision;checkpoint();
                if(revision!=store.usageRevision())remaining=dailyRemaining(pkg);
                JSONObject rule=policyRule(pkg);
                ++gateTransition;gate=type;gatePkg=pkg;utility="사용 확인";
                cacheGateBudget(pkg,rule,remaining,System.currentTimeMillis());
            }
        }
        if(!same){gateDetectedUptimeSeen=foregroundDetectedUptime;UnlockDiagnostics.get(this).beginGate(gateTransition,detected);gateTrace("showGate hostReuse="+(overlay!=null)+" windowReuse="+overlayAttached);}
        if(!same){cancelOverlayAttachRetry();overlayAttachRetries=0;overlayAttachFailed=false;}
        // The service preattaches this window. Do not re-run prewarm on every app
        // entry; only repair an actually absent/detached host/window.
        if(overlay==null||overlayWindow==null||!overlayWindow.isAttachedToWindow())prepareAppGate();
        overlayVisible=true;
        // Conceal previous content before a new navigation is sent to JavaScript.
        if(!same)applyExposure(Exposure.WAITING);
        if(!same){
            gateTrace("gate payload requested");
            try{JSONObject payload=gatePayload();gateTrace("gate payload ready");overlay.gateScreen(type,payload);}
            catch(JSONException error){hideOverlay();throw new IllegalStateException("앱 사용 정보를 준비하지 못했습니다.",error);}
        }
        if(same)overlay.ensurePurposeDay();
        revealOverlay();
        // First keyguard observation may have added screen evidence during reveal.
        dailyRemaining(pkg);ensurePulse();
    }
    void revealOverlay(){
        if(overlay==null||overlayWindow==null)return;
        overlayAttached=overlayWindow.isAttachedToWindow();
        if(!overlayAttached)prepareAppGateWindow();
        Exposure next=!overlayVisible?Exposure.HIDDEN:(!interactive||lockPending||keyguardLocked()||systemPaused||!gatePkg.equals(foreground))?Exposure.SUSPENDED:
            overlayAttached&&currentGateEntry(overlay,overlay.gateTransition)&&overlay.currentGateReady()?Exposure.INTERACTIVE:Exposure.WAITING;
        if(next!=exposure)gateTrace("exposure "+exposure+" -> "+next+" attached="+overlayAttached+" dom="+overlay.currentGateDataApplied()+" frame="+overlay.frameReady);
        applyExposure(next);
    }
    // Rendering must never wait for a second policy/SQLite lookup. This checks
    // the service-owned window and transition only; policy is revalidated on
    // exposure and every actual user action.
    boolean canPrepareGateFrame(WebHost host,long transition){
        return overlay==host&&!host.closed&&overlayAttached&&overlayVisible&&transition==gateTransition&&!lockPending
            &&interactive&&!systemPaused&&!keyguardLocked()&&gatePkg.equals(foreground);
    }
    boolean currentGateEntry(WebHost host,long transition){
        return canPrepareGateFrame(host,transition)&&store.managed(System.currentTimeMillis())
            &&policyRule(gatePkg)!=null;
    }
    boolean acceptGateErrorInput(){return overlay!=null&&overlay.gateErrorVisible()&&exposure==Exposure.WAITING
        &&currentGateEntry(overlay,overlay.gateTransition);}
    void applyExposure(Exposure next){
        if(overlay==null||overlayWindow==null)return;
        boolean input=next==Exposure.INTERACTIVE;
        boolean waiting=next==Exposure.WAITING;
        boolean error=waiting&&overlay.gateErrorVisible();
        // Keep Chromium's page visibility VISIBLE even during a long absence.
        // Window alpha owns pixel exposure; flags and token checks own input.
        if(overlayWindow.getVisibility()!=View.VISIBLE)overlayWindow.setVisibility(View.VISIBLE);
        if(overlay.root.getVisibility()!=View.VISIBLE)overlay.root.setVisibility(View.VISIBLE);
        if(overlay.view.getVisibility()!=View.VISIBLE)overlay.view.setVisibility(View.VISIBLE);
        int errorVisibility=error?View.VISIBLE:View.GONE;
        if(overlay.loadingView.getVisibility()!=errorVisibility)overlay.loadingView.setVisibility(errorVisibility);
        overlay.presentationActive=input||waiting;
        if(!overlay.presentationActive)overlay.suspendVisualRequest();
        if(next==Exposure.HIDDEN||next==Exposure.SUSPENDED)cancelOverlayAttachRetry();
        overlayWindow.setImportantForAccessibility(input||error?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if(overlayLayout!=null){
            float alpha=input||error?1f:0f;
            int passive=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            int flags=input?overlayLayout.flags&~passive:overlayLayout.flags|passive;
            if(waiting)flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if(alpha!=overlayLayout.alpha||flags!=overlayLayout.flags||overlayLayoutDirty){
                overlayLayout.alpha=alpha;overlayLayout.flags=flags;overlayLayoutDirty=true;
                if(overlayAttached&&overlayWindow.isAttachedToWindow())try{window.updateViewLayout(overlayWindow,overlayLayout);overlayLayoutDirty=false;}
                catch(RuntimeException layoutError){
                    overlayAttached=overlayWindow.isAttachedToWindow();
                    UnlockDiagnostics.get(this).failure("app-gate-layout",layoutError);
                    gateTrace("window layout failed "+layoutError.getClass().getSimpleName());
                    if(!overlayAttached)scheduleOverlayAttachRetry();
                }
            }
        }
        if(input&&exposure!=Exposure.INTERACTIVE)gateTrace("input allowed navigation="+overlay.navigationGeneration);
        exposure=next;
        overlay.notifyGateExposure(next);
        // The retained engine is already running. Only the not-yet-rendered
        // entry needs a wakeup and a visual request; hiding never pauses it.
        if(waiting&&!overlay.frameReady){overlay.prepareOverlay();overlay.requestVisualState();}
    }
    JSONObject startWindowStatus() throws JSONException {
        return UnlockDiagnostics.get(this).windowStatus().put("status","activity").put("actual","activity");
    }
    void hideOverlay(){
        if(!overlayVisible)return;
        gateTrace("gate hide exposure="+exposure);
        UnlockDiagnostics.get(this).finishGate(gateTransition,exposure==Exposure.INTERACTIVE?"shown-and-hidden":"hidden-before-ready");
        checkpoint();overlayVisible=false;++gateTransition;gateDailyUntil=0;if(overlay!=null)applyExposure(Exposure.HIDDEN);
        segmentWall=System.currentTimeMillis();segmentElapsed=SystemClock.elapsedRealtime();segmentUptime=SystemClock.uptimeMillis();utility="첫 화면";
    }
    void choosePhone(JSONObject p) throws Exception {invalidateGateBudget();checkpoint();phoneEvent=store.event("unlock",p.getString("label"),"",p.optString("pkg"),0,p.optInt("aimless",0)==1);utility="첫 화면";ensurePulse();}
    void allowApp(JSONObject p,int minutes) throws Exception {
        boolean ownsRead=budgetReads.get()==null;if(ownsRead)budgetReads.set(new BudgetRead());
        try {
            invalidateGateBudget();checkpoint();long remaining=dailyRemaining(gatePkg);JSONObject rule=policyRule(gatePkg);if(!store.managed(System.currentTimeMillis())){hideOverlay();return;}
            if(rule==null||rule.optString("mode").equals("locked")||remaining<=0){enforce();throw new IllegalStateException("지금은 사용할 수 없는 앱입니다.");}
            if(!gatePkg.equals(foreground))throw new IllegalStateException("앱이 바뀌었습니다. 다시 열어 주세요.");
            beginAppSession(gatePkg,p,minutes,phoneEvent);hideOverlay();launch(p);syncFloatingTimer();ensurePulse();
        }finally{if(ownsRead)budgetReads.remove();}
    }
    private void beginAppSession(String pkg,JSONObject p,int minutes,long parent) throws Exception {
        long remaining=dailyRemaining(pkg),now=System.currentTimeMillis();JSONObject rule=policyRule(pkg);
        if(!pkg.equals(foreground)||!store.managed(now)||rule==null||!"limited".equals(rule.optString("mode"))||remaining<=0)throw new IllegalStateException("지금은 사용할 수 없는 앱입니다.");
        long event=store.event("app",p.getString("label"),pkg,p.optString("pkg"),parent,p.optInt("aimless",0)==1);
        pausedSessions.remove(pkg);sessionPolicyEnd=policyEnd(now);sessionPkg=pkg;purpose=p.getString("label");sessionRemaining=Rules.grant(remaining,minutes);sessionEvent=event;lastNotice=-1;
        Notices.timer(this,rule.optString("label"),remaining,sessionRemaining);syncFloatingTimer();ensurePulse();
    }
    void extend(int minutes) {
        boolean ownsRead=budgetReads.get()==null;if(ownsRead)budgetReads.set(new BudgetRead());
        try {
            invalidateGateBudget();checkpoint();long remaining=dailyRemaining(gatePkg);
            if(!sessionPkg.equals(gatePkg)||remaining<=0){enforce();return;}
            sessionRemaining=Rules.grant(remaining,minutes);store.event("extend",purpose,sessionPkg,"",phoneEvent);hideOverlay();lastNotice=-1;enforce();ensurePulse();
        }finally{if(ownsRead)budgetReads.remove();}
    }
    void stopApp(){hideFloatingTimer();checkpoint();if(!gate.isEmpty())store.event("cancel","열지 않기",gatePkg,"",phoneEvent);pausedSessions.remove(sessionPkg.isEmpty()?gatePkg:sessionPkg);clearActiveSession();performGlobalAction(GLOBAL_ACTION_HOME);foreground="";hideOverlay();Notices.cancelTimer(this);main.removeCallbacks(pulse);pulseScheduled=false;}
    void useUtility(String name){checkpoint();boolean changed=!name.equals(utility);utility=name;if(changed&&!name.equals("첫 화면")&&!name.equals("Locky"))store.event("utility",name,getPackageName(),"",phoneEvent);}
    void pauseForActivity(){switchForeground(getPackageName());if(!sessionPkg.isEmpty())Notices.paused(this,UsageReader.label(this,sessionPkg),dailyRemaining(sessionPkg),sessionRemaining);}
    void openMain(String screen){openMain(screen,"");}
    void openMain(String screen,String chatId){useUtility(screen.equals("chats")||screen.equals("chat")?"ChatGPT":screen.equals("notes")?"메모":"Locky");hideOverlay();Intent launch=MainActivity.launchIntent(this).putExtra("screen",screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if("chat".equals(screen)&&!chatId.isEmpty())launch.putExtra("chat",chatId);startActivity(launch);}
    void launch(JSONObject p) throws Exception {String pkg=p.optString("pkg"),uri=p.optString("uri");if(pkg.isEmpty()&&uri.isEmpty())return;Intent i=Links.intent(this,pkg,uri);if(i==null)throw new IllegalStateException("연결한 앱이나 동작을 열 수 없습니다. 목적 연결을 확인해 주세요.");startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    void policyChanged(){gateDailyUntil=0;main.post(()->{invalidateGateBudget();observeForeground(null);checkpoint();enforce();ensurePulse();if(overlayVisible&&overlay!=null)overlay.evaluate("refresh(true)");WebHost.refreshGeneralHosts();});}
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
    @Override public void onDestroy(){hideFloatingTimer();checkpoint();if(store!=null){store.observe("service-destroyed",interactive);store.screenState(ScreenHistory.UNKNOWN);}live=null;interactive=false;overlayVisible=false;main.removeCallbacksAndMessages(null);try{unregisterReceiver(screen);}catch(Exception ignored){}if(overlay!=null){try{if(overlayWindow!=null&&(overlayWindow.isAttachedToWindow()||overlayWindow.getParent()!=null)){window.removeViewImmediate(overlayWindow);overlayAttached=false;overlayAttachPending=false;}}catch(IllegalArgumentException ignored){}overlay.close();}Notices.cancelTimer(this);super.onDestroy();}
}
