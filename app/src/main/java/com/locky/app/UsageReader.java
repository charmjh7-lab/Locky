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
    static JSONArray intervals(Context c,long start,long end) throws JSONException {
        JSONArray out=new JSONArray();if(!allowed(c))return out;
        UsageEvents events=((UsageStatsManager)c.getSystemService(Context.USAGE_STATS_SERVICE)).queryEvents(start,end);UsageEvents.Event e=new UsageEvents.Event();String pkg="";long since=start;
        while(events.hasNextEvent()){events.getNextEvent(e);int t=e.getEventType();long at=Math.max(start,Math.min(end,e.getTimeStamp()));
            if(t==UsageEvents.Event.ACTIVITY_RESUMED) {
                if(!pkg.isEmpty()&&!pkg.equals(e.getPackageName()))add(out,pkg,since,at);if(!pkg.equals(e.getPackageName())){pkg=e.getPackageName();since=at;}
            } else if((t==UsageEvents.Event.ACTIVITY_PAUSED&&pkg.equals(e.getPackageName()))||t==UsageEvents.Event.SCREEN_NON_INTERACTIVE||t==UsageEvents.Event.KEYGUARD_SHOWN){add(out,pkg,since,at);pkg="";}
        }
        add(out,pkg,since,end);return out;
    }
    private static void add(JSONArray out,String pkg,long start,long end) throws JSONException {if(!pkg.isEmpty()&&end>start)out.put(new JSONObject().put("pkg",pkg).put("start",start).put("end",end));}
    static boolean launcherApp(Context c,String pkg){return c.getPackageManager().getLaunchIntentForPackage(pkg)!=null;}
}
