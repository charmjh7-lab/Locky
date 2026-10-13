package com.locky.app;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.time.*;

final class Scheduler {
    static final String ACTION="com.locky.app.BOUNDARY";
    static void schedule(Context c) {
        Store s=Store.get(c);long now=System.currentTimeMillis();ZoneId z=ZoneId.systemDefault();int start=s.settings.getInt("start",540),end=s.settings.getInt("end",1260);
        long next=Rules.nextBoundary(now,start,end,z),midnight=Rules.nextDay(now,z);next=Math.min(next,midnight);
        if(s.settings.getBoolean("enabled",true)&&!s.managed(now))try {
            long begin=Rules.nextBoundary(now,start,end,z);JSONArray reminders=new JSONArray(s.settings.getString("reminders","[30,20,10,5,1]"));
            for(int i=0;i<reminders.length();i++){long at=begin-reminders.getInt(i)*60000L;if(at>now)next=Math.min(next,at);}
        }catch(JSONException ignored){}
        Intent i=new Intent(c,ScheduleReceiver.class).setAction(ACTION);PendingIntent p=PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);AlarmManager a=c.getSystemService(AlarmManager.class);
        try{if(Build.VERSION.SDK_INT<31||a.canScheduleExactAlarms())a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,p);else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,p);}catch(SecurityException denied){a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,p);}
    }
    static void fire(Context c) {
        Store s=Store.get(c);long now=System.currentTimeMillis();ZoneId z=ZoneId.systemDefault();int minute=Rules.minute(now,z),start=s.settings.getInt("start",540),end=s.settings.getInt("end",1260);boolean enabled=s.settings.getBoolean("enabled",true);String date=LocalDate.now().toString();
        if(enabled&&!s.managed(now)) {
            LocalDate freeDate=Instant.ofEpochMilli(now).atZone(z).toLocalDate();if(start<end&&minute<start)freeDate=freeDate.minusDays(1);String key=freeDate.toString();
            if(!key.equals(s.settings.getString("brief-sent",""))) {
                s.settings.edit().putString("brief-sent",key).apply();
                try {JSONObject b=Briefing.build(c,1,0);Notices.send(c,21,"brief","오늘의 선택을 돌아볼까요?",b.getString("summary"),"brief");}catch(Exception e){Notices.send(c,21,"brief","자유 시간이 시작됐어요","오늘의 사용 흐름과 목적을 브리핑에서 확인하세요.","brief");}
            }
            long boundary=Rules.nextBoundary(now,start,end,z),remaining=boundary-now;
            try{JSONArray reminders=new JSONArray(s.settings.getString("reminders","[30,20,10,5,1]"));for(int j=0;j<reminders.length();j++){int m=reminders.getInt(j);String keyR=date+":"+m;if(remaining<=m*60000L&&remaining>Math.max(0,m*60000L-90000)&&!keyR.equals(s.settings.getString("reminder-sent-"+m,""))){s.settings.edit().putString("reminder-sent-"+m,keyR).apply();Notices.send(c,30+m,"reminder","집중 시간이 "+m+"분 뒤 시작돼요","지금 하던 일을 마무리할 여유를 남겨 주세요.","settings");}}}catch(JSONException ignored){}
        }
        if(LocalDate.now().getDayOfWeek().getValue()==s.settings.getInt("weeklyDay",7)&&minute>=end&&!date.equals(s.settings.getString("weekly-sent",""))) {
            s.settings.edit().putString("weekly-sent",date).apply();Notices.send(c,22,"brief","이번 주의 흐름이 모였어요","7일의 사용 목적, 자주 머문 앱, 다른 길로 이어진 시간을 확인해 보세요.","week");
        }
        if(GuardService.live!=null)GuardService.live.policyChanged();schedule(c);
    }
}
