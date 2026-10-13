package com.locky.app;

import android.content.Context;
import org.json.*;
import java.time.*;
import java.util.*;

final class Briefing {
    static JSONObject build(Context c,int days,long dayOffset) throws Exception {
        Store s=Store.get(c);ZoneId zone=ZoneId.systemDefault();long now=System.currentTimeMillis();LocalDate date=LocalDate.now().minusDays(dayOffset);
        long end=dayOffset==0?now:date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();long start=date.minusDays(days-1).atStartOfDay(zone).toInstant().toEpochMilli();
        GuardService guard=GuardService.live;if(guard!=null)guard.flushOnMain();
        JSONArray usage=s.rows("SELECT * FROM usage WHERE end>? AND start<? ORDER BY start",""+start,""+end),events=s.rows("SELECT * FROM events WHERE time>=? AND time<? ORDER BY time",""+start,""+end);
        Map<String,Long> totals=new HashMap<>(),drift=new HashMap<>();Map<String,String> labels=new HashMap<>();Map<Long,JSONObject> contexts=new HashMap<>();int unlocks=0,aimless=0;
        for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);contexts.put(e.getLong("id"),e);if(e.getString("kind").equals("unlock")){unlocks++;if(e.getString("purpose").equals("그냥 열었어요"))aimless++;}}
        long total=0,deviation=0,linked=0;JSONArray timeline=new JSONArray();
        for(int i=0;i<usage.length();i++){
            JSONObject u=usage.getJSONObject(i);long duration=Math.max(0,Math.min(end,u.getLong("end"))-Math.max(start,u.getLong("start")));String pkg=u.getString("pkg"),label=u.getString("label");
            if(duration==0||label.equals("첫 화면")||label.equals("사용 확인"))continue;
            String key=pkg.equals(c.getPackageName())?label:pkg;totals.merge(key,duration,Long::sum);labels.put(key,label);total+=duration;
            JSONObject context=contexts.get(u.getLong("parent"));if(context==null&&u.getLong("parent")>0){JSONArray prior=s.rows("SELECT * FROM events WHERE id=?",""+u.getLong("parent"));if(prior.length()>0){context=prior.getJSONObject(0);contexts.put(context.getLong("id"),context);}}if(context!=null&&context.getLong("parent")>0){JSONObject root=contexts.get(context.getLong("parent"));if(root==null){JSONArray prior=s.rows("SELECT * FROM events WHERE id=?",""+context.getLong("parent"));if(prior.length()>0){root=prior.getJSONObject(0);contexts.put(root.getLong("id"),root);}}if(root!=null&&!root.optString("target").isEmpty())context=root;}
            String purpose=context==null?"기록 없음":context.optString("purpose"),target=context==null?"":context.optString("target");
            if(!target.isEmpty()){linked+=duration;if(!pkg.equals(target)&&!pkg.equals(c.getPackageName())){deviation+=duration;drift.merge(purpose,duration,Long::sum);}}
            timeline.put(new JSONObject().put("start",u.getLong("start")).put("end",u.getLong("end")).put("pkg",pkg).put("label",label).put("purpose",purpose));
        }
        // Usage-access fills only gaps outside accessibility observation. Avoid overlay time double counting.
        JSONArray os=UsageReader.intervals(c,Math.max(start,s.settings.getLong("installed",now)),end);long recovered=0;
        for(int i=0;i<os.length();i++){
            JSONObject u=os.getJSONObject(i);String pkg=u.getString("pkg");if(pkg.equals(c.getPackageName())||!UsageReader.launcherApp(c,pkg))continue;
            long from=u.getLong("start"),to=u.getLong("end"),cursor=from,gap=0;
            for(int j=0;j<usage.length();j++){JSONObject v=usage.getJSONObject(j);long a=v.getLong("start"),b=v.getLong("end");if(b<=cursor)continue;if(a>=to)break;if(a>cursor)gap+=Math.min(to,a)-cursor;cursor=Math.max(cursor,b);if(cursor>=to)break;}
            gap+=Math.max(0,to-cursor);if(gap>0){totals.merge(pkg,gap,Long::sum);labels.put(pkg,UsageReader.label(c,pkg));recovered+=gap;total+=gap;}
        }
        JSONArray apps=new JSONArray();totals.entrySet().stream().sorted((a,b)->Long.compare(b.getValue(),a.getValue())).forEach(e->{try{apps.put(new JSONObject().put("pkg",e.getKey()).put("label",labels.get(e.getKey())).put("ms",e.getValue()));}catch(JSONException ignored){}});
        String top=apps.length()==0?"":apps.getJSONObject(0).getString("label"),summary;
        if(total==0)summary="아직 사용 기록이 없어요. 오늘의 작은 선택들이 모이면 하루의 흐름을 보여드릴게요.";
        else summary=(days==1?"오늘":"지난 7일")+" 화면을 켠 이유를 "+unlocks+"번 남겼고, 앱을 "+minutes(total)+"분 사용했어요. "+(top.isEmpty()?"":"가장 오래 머문 곳은 "+top+"예요. ")+(aimless==0?"목적을 정하지 않은 시작은 없었어요.":"그냥 열었던 순간은 "+aimless+"번이었어요.");
        String insight=linked==0?"사용 목적에 앱을 연결하면, 원래 가려던 곳과 다른 앱에 머문 시간을 비교할 수 있어요.":"연결한 목적이 있던 사용 중 "+minutes(deviation)+"분을 다른 앱에서 보냈어요. 목적에서 벗어났다는 단정 대신, 다음 시작을 돌아보는 단서로 볼 수 있어요.";
        JSONArray driftRows=new JSONArray();for(var d:drift.entrySet())driftRows.put(new JSONObject().put("purpose",d.getKey()).put("ms",d.getValue()));
        return new JSONObject().put("days",days).put("date",date.toString()).put("start",start).put("end",end).put("total",total).put("unlocks",unlocks).put("aimless",aimless).put("deviation",deviation).put("linked",linked).put("summary",summary).put("insight",insight).put("apps",apps).put("events",events).put("timeline",timeline).put("drift",driftRows).put("recovered",recovered).put("usageAccess",UsageReader.allowed(c));
    }
    static long minutes(long ms){return Math.round(ms/60000.0);}
}
