package com.locky.app;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.time.*;

final class Scheduler {
    static final String ACTION="com.locky.app.BOUNDARY";
    static void schedule(Context c) {
        Store s=Store.get(c);s.syncFocusZone();long now=System.currentTimeMillis();ZoneId z=ZoneId.systemDefault();int start=s.settings.getInt("start",540),end=s.settings.getInt("end",1260);
        long next=Math.min(Rules.nextBoundary(now,start,end,z),Rules.nextDay(now,z));
        if(s.settings.getBoolean("enabled",true)&&!s.managed(now))try {
            long begin=Rules.nextBoundary(now,start,end,z);JSONArray reminders=new JSONArray(s.settings.getString("reminders","[30,20,10,5,1]"));
            for(int i=0;i<reminders.length();i++){long at=begin-reminders.getInt(i)*60000L;if(at>now)next=Math.min(next,at);}
        }catch(JSONException ignored){}
        int delivery=s.settings.getInt("dailyBriefTime",540);LocalDate date=LocalDate.now(z);long at=date.atTime(delivery/60,delivery%60).atZone(z).toInstant().toEpochMilli();
        if(at<=now)at=date.plusDays(1).atTime(delivery/60,delivery%60).atZone(z).toInstant().toEpochMilli();next=Math.min(next,at);
        Intent i=new Intent(c,ScheduleReceiver.class).setAction(ACTION);PendingIntent p=PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);AlarmManager a=c.getSystemService(AlarmManager.class);
        try{if(Build.VERSION.SDK_INT<31||a.canScheduleExactAlarms())a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,p);else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,p);}catch(SecurityException denied){a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,p);}
    }
    static synchronized void fire(Context c) {
        Store s=Store.get(c);long now=System.currentTimeMillis();s.observe("scheduler-fired",c.getSystemService(PowerManager.class).isInteractive());s.syncFocusZone();ZoneId z=ZoneId.systemDefault();LocalDate today=LocalDate.now(z);int minute=Rules.minute(now,z);
        try {
            JSONArray windows=FocusPeriods.windows(s,now-2*86400000L,now);
            for(int i=0;i<windows.length();i++){JSONObject w=windows.getJSONObject(i);if(!w.getBoolean("complete"))continue;
                String key=w.getString("key");if(delivered(s,key))continue;
                // Finalize the result even with notifications disabled. Old backlog remains in-app.
                JSONObject b=Briefing.focusResult(c,key);
                if(!s.settings.getBoolean("focusBrief",true)||now-w.getLong("end")>86400000L){record(s,key,"skipped");continue;}
                deliver(c,key,"집중시간이 끝났어요","이번 집중시간에는 외부 앱을 "+Briefing.minutes(b.getLong("total"))+"분 사용했어요. 사용 흐름을 확인해 보세요.","brief|focus|"+key);
            }
            if(minute>=s.settings.getInt("dailyBriefTime",540)){
                LocalDate day=today.minusDays(1);String key="day:"+day;
                if(!delivered(s,key)){
                    if(s.settings.getBoolean("dailyBrief",true)){JSONObject b=Briefing.build(c,1,1);deliver(c,key,"어제의 사용 기록이 정리됐어요",b.getString("summary"),"brief|day|"+day);}else record(s,key,"skipped");
                }
                String week="week:"+day;
                if(today.getDayOfWeek().getValue()==s.settings.getInt("weeklyDay",7)&&!delivered(s,week)){
                    JSONObject b=Briefing.build(c,7,1);deliver(c,week,"지난 7일의 사용 기록이 정리됐어요",b.getString("summary"),"brief|week|"+day);
                }
            }
            if(s.settings.getBoolean("enabled",true)&&!s.managed(now)){
                long boundary=Rules.nextBoundary(now,s.settings.getInt("start",540),s.settings.getInt("end",1260),z),remaining=boundary-now;
                JSONArray reminders=new JSONArray(s.settings.getString("reminders","[30,20,10,5,1]"));for(int j=0;j<reminders.length();j++){int m=reminders.getInt(j);String key=today+":"+m;
                    if(remaining<=m*60000L&&remaining>Math.max(0,m*60000L-90000)&&!key.equals(s.settings.getString("reminder-sent-"+m,""))){s.settings.edit().putString("reminder-sent-"+m,key).apply();Notices.send(c,30+m,"reminder","집중 시간이 "+m+"분 뒤 시작돼요","지금 하던 일을 마무리할 여유를 남겨 주세요.","settings");}
                }
            }
        }catch(Exception e){android.util.Log.w("LockyBriefing","Briefing scheduling failed",e);}
        finally{if(GuardService.live!=null)GuardService.live.policyChanged();schedule(c);}
    }
    private static boolean delivered(Store s,String key) throws JSONException {
        JSONArray rows=s.rows("SELECT state FROM briefing_delivery WHERE key=?",key);if(rows.length()==0)return false;
        if(rows.getJSONObject(0).getString("state").equals("posting"))record(s,key,Notices.briefingPosted(s.context,key)?"posted":"interrupted");return true;
    }
    private static void record(Store s,String key,String state){ContentValues v=new ContentValues();v.put("key",key);v.put("state",state);v.put("attempts",1);v.put("updated",System.currentTimeMillis());s.put("briefing_delivery",v);}
    private static void deliver(Context c,String key,String title,String body,String route) throws JSONException {
        Store s=Store.get(c);if(delivered(s,key))return;
        // A durable attempt prevents duplicate alerts even if the process dies around notify().
        // An ambiguous interrupted attempt is not labelled sent and is never retried in a loop.
        record(s,key,"posting");String status=Notices.briefingPosted(c,key)?"posted":Notices.postBriefing(c,key,title,body,route);record(s,key,status);
    }
}
