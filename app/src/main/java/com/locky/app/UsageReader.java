package com.locky.app;

import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import org.json.*;
import java.util.*;

final class UsageReader {
    static boolean allowed(Context c) {AppOpsManager a=(AppOpsManager)c.getSystemService(Context.APP_OPS_SERVICE);return a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),c.getPackageName())==AppOpsManager.MODE_ALLOWED;}
    static final java.util.concurrent.ConcurrentHashMap<String,String> labels=new java.util.concurrent.ConcurrentHashMap<>();
    static String label(Context c,String pkg){return labels.computeIfAbsent(pkg,key->{try{return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(key,0)).toString();}catch(Exception ignored){return key;}});}
    static JSONArray intervals(Context c,long start,long end) throws JSONException {return intervals(c,start,end,"");}
    static JSONArray intervals(Context c,long start,long end,String seed) throws JSONException {
        JSONArray out=new JSONArray();if(!allowed(c)||end<=start)return out;
        // Prime the state before the requested interval: a resumed activity may have
        // been on screen for hours before the service checkpoint.
        UsageEvents events=((UsageStatsManager)c.getSystemService(Context.USAGE_STATS_SERVICE)).queryEvents(Math.max(0,start-86400000L),end);
        UsageEvents.Event e=new UsageEvents.Event();String pkg=seed;long since=start;boolean visible=true;
        while(events.hasNextEvent()){events.getNextEvent(e);int t=e.getEventType();long at=e.getTimeStamp();String next=e.getPackageName();
            if(t==UsageEvents.Event.SCREEN_NON_INTERACTIVE||t==UsageEvents.Event.KEYGUARD_SHOWN||t==UsageEvents.Event.DEVICE_SHUTDOWN){if(at>=start)add(out,pkg,since,Math.min(end,at));pkg="";visible=false;}
            else if(t==UsageEvents.Event.SCREEN_INTERACTIVE||t==UsageEvents.Event.KEYGUARD_HIDDEN){visible=true;}
            else if(t==UsageEvents.Event.ACTIVITY_RESUMED&&visible){
                if(!pkg.equals(next)){if(at>=start)add(out,pkg,since,Math.min(end,at));pkg=next==null?"":next;since=Math.max(start,at);}
            }else if(t==UsageEvents.Event.ACTIVITY_PAUSED&&pkg.equals(next)){if(at>=start)add(out,pkg,since,Math.min(end,at));pkg="";}
        }
        if(visible)add(out,pkg,since,end);return out;
    }
    static String home(Context c){ResolveInfo h=c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);return h==null?"":h.activityInfo.packageName;}
    static boolean recordable(Context c,String pkg){return !pkg.isEmpty()&&!pkg.equals(home(c))&&!pkg.equals("com.android.systemui")&&!pkg.contains("permissioncontroller");}
    private static void add(JSONArray out,String pkg,long start,long end) throws JSONException {if(!pkg.isEmpty()&&end>start)out.put(new JSONObject().put("pkg",pkg).put("start",start).put("end",end));}
    static boolean launcherApp(Context c,String pkg){return c.getPackageManager().getLaunchIntentForPackage(pkg)!=null;}
}
