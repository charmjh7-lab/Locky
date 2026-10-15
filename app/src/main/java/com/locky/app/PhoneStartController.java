package com.locky.app;

import android.content.*;
import android.app.KeyguardManager;
import android.os.PowerManager;

/** One preparation and at most one unlock recovery per screen-off cycle. No timers. */
final class PhoneStartController {
    private final Context context;
    private final android.content.SharedPreferences prefs;
    private boolean recoverySent;
    PhoneStartController(Context context){this.context=context;this.prefs=Store.get(context).settings;}
    long cycle(){return prefs.getLong("phone-start-cycle",0);}
    void screenOff(){
        long next=Math.max(System.currentTimeMillis(),cycle()+1);
        prefs.edit().putLong("phone-start-cycle",next).putBoolean("phone-start-awaiting-unlock",true).apply();
        recoverySent=false;request(next,"screen-off");
    }
    void prepareAfterServiceRestart(){
        if(!prefs.getBoolean("phone-start-awaiting-unlock",false)){
            prefs.edit().putLong("phone-start-cycle",Math.max(System.currentTimeMillis(),cycle()+1)).putBoolean("phone-start-awaiting-unlock",true).apply();
        }
        // A live acknowledged owner needs no second request when the service reconnects.
        if(!acknowledged()&&prefs.getLong("phone-start-prepared-cycle",0)!=cycle())request(cycle(),"service-recovery");
    }
    boolean awaitingUnlock(){return prefs.getBoolean("phone-start-awaiting-unlock",false);}
    boolean acknowledged(){
        PhoneStartActivity activity=PhoneStartActivity.current();
        return activity!=null&&activity.cycle==cycle()&&!activity.isFinishing()&&!activity.isDestroyed();
    }
    boolean unlocked(){
        if(!awaitingUnlock()||!context.getSystemService(PowerManager.class).isInteractive()
            ||context.getSystemService(KeyguardManager.class).isKeyguardLocked())return false;
        prefs.edit().putBoolean("phone-start-awaiting-unlock",false).apply();
        WebHost.resumeUnlockTrace();WebHost.trace("unlock-confirmed activity-ack="+acknowledged());
        UnlockDiagnostics.get(context).confirmedActivity();
        // Android can silently reject a BAL. A lifecycle acknowledgement, not the
        // return value of startActivity(), decides whether recovery is necessary.
        if(!acknowledged()&&!recoverySent){recoverySent=true;request(cycle(),"unlock-recovery");}
        return true;
    }
    private void request(long cycle,String reason){
        prefs.edit().putLong("phone-start-prepared-cycle",cycle).apply();
        WebHost.trace("phone-activity request reason="+reason);
        PhoneStartActivity existing=PhoneStartActivity.current();
        // Already in front: update only cycle/day metadata. Do not reorder a task,
        // navigate the document or recreate WebView for a second screen-off.
        if(existing!=null&&existing.resumed&&!existing.isFinishing()){
            existing.acceptCycle(cycle);WebHost.trace("phone-activity reused in-place");return;
        }
        try{
            // Direct launch from the system-bound AccessibilityService (BAL exemption),
            // with an already granted SAW exemption also available. No FGS/PI bypass.
            context.startActivity(PhoneStartActivity.intent(context,cycle));
        }catch(RuntimeException error){UnlockDiagnostics.get(context).failure("activity-launch",error);}
    }
}
