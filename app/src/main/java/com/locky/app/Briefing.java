package com.locky.app;

import android.content.*;
import org.json.*;
import java.time.*;
import java.util.*;

final class Briefing {
    static JSONObject build(Context c,int days,long dayOffset) throws Exception {
        ZoneId z=ZoneId.systemDefault();LocalDate date=LocalDate.now(z).minusDays(dayOffset);
        long start=date.minusDays(days-1).atStartOfDay(z).toInstant().toEpochMilli();
        long end=dayOffset==0?System.currentTimeMillis():date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli();
        JSONObject result=range(c,start,end).put("days",days).put("date",date.toString()).put("complete",dayOffset>0).put("kind",days==7?"week":"day");
        if(days==1){JSONArray windows=FocusPeriods.windows(Store.get(c),start,end),results=new JSONArray();for(int i=0;i<windows.length();i++){
            JSONObject w=windows.getJSONObject(i);if(w.getLong("start")<end&&w.getLong("end")>start)results.put(w);
        }result.put("focusResults",results);}
        BriefingAnalysis.addComparison(c,result,days);return result;
    }
    static JSONObject range(Context c,long start,long end) throws Exception {
        Store s=Store.get(c);s.syncFocusZone();GuardService guard=GuardService.live;if(guard!=null)guard.flushOnMain();
        JSONArray usage=s.screenUsage(start,end);
        JSONArray events=s.rows("SELECT * FROM events WHERE time>=? AND time<? ORDER BY time,id",""+start,""+end);
        JSONArray cleaned=new JSONArray();long recovered=0,internal=0,tools=0;
        // All original spans reserve their time, including internal UI. OS recovery only fills gaps.
        List<long[]> reserved=new ArrayList<>();for(int i=0;i<usage.length();i++){JSONObject u=usage.getJSONObject(i);reserved.add(new long[]{u.getLong("start"),u.getLong("end")});}
        long claimed=start,previousEnd=-1;String previousPkg="";long previousParent=0;
        for(int i=0;i<usage.length();i++){
            JSONObject raw=usage.getJSONObject(i);long a=Math.max(claimed,Math.max(start,raw.getLong("start"))),b=Math.min(end,raw.getLong("end"));if(b<=a)continue;claimed=b;
            String pkg=raw.getString("pkg");if(pkg.equals(c.getPackageName())){internal+=b-a;continue;}if(raw.optString("surface").equals("dictionary-tool")){tools+=b-a;continue;}
            if(!UsageReader.recordable(c,pkg))continue;
            boolean technicalResume=false;
            if(pkg.equals(previousPkg)&&raw.optLong("parent")==previousParent&&a>previousEnd&&a-previousEnd<=1500){long covered=previousEnd;for(int j=0;j<usage.length();j++){JSONObject bridge=usage.getJSONObject(j);if(bridge.getLong("end")<=covered)continue;if(bridge.getLong("start")>covered)break;if(bridge.getString("pkg").equals(c.getPackageName())||bridge.optString("surface").equals("dictionary-tool")||!UsageReader.recordable(c,bridge.getString("pkg")))covered=Math.max(covered,bridge.getLong("end"));if(covered>=a){technicalResume=true;break;}}}
            cleaned.put(new JSONObject(raw.toString()).put("start",a).put("end",b).put("technicalResume",technicalResume));previousEnd=b;previousPkg=pkg;previousParent=raw.optLong("parent");
        }
        JSONArray os=UsageReader.intervals(c,Math.max(start,s.settings.getLong("installed",end)),end);long osClaimed=start;
        for(int i=0;i<os.length();i++){
            JSONObject u=os.getJSONObject(i);String pkg=u.getString("pkg");long a=Math.max(osClaimed,Math.max(start,u.getLong("start"))),b=Math.min(end,u.getLong("end"));osClaimed=Math.max(osClaimed,b);
            if(b<=a||pkg.equals(c.getPackageName())||!UsageReader.recordable(c,pkg))continue;
            long cursor=a;for(long[] span:reserved){if(span[1]<=cursor)continue;if(span[0]>=b)break;if(span[0]>cursor){long stop=Math.min(b,span[0]);cleaned.put(recovered(c,pkg,cursor,stop));recovered+=stop-cursor;}cursor=Math.max(cursor,span[1]);if(cursor>=b)break;}
            if(cursor<b){cleaned.put(recovered(c,pkg,cursor,b));recovered+=b-cursor;}
        }
        JSONObject result=BriefingAnalysis.analyze(s,start,end,cleaned,events,FocusPeriods.data(s,start,end));
        return result.put("timeZone",ZoneId.systemDefault().getId()).put("toolMillis",tools).put("internalMillis",internal).put("recovered",recovered).put("usageAccess",UsageReader.allowed(c));
    }
    private static JSONObject recovered(Context c,String pkg,long start,long end) throws JSONException {return new JSONObject().put("start",start).put("end",end).put("pkg",pkg).put("label",UsageReader.label(c,pkg)).put("parent",0).put("source","usage-access").put("rule_mode","unknown");}
    static JSONObject focusResult(Context c,String key) throws Exception {
        Store s=Store.get(c);JSONArray found=s.rows("SELECT body FROM briefing_results WHERE key=?",key);if(found.length()>0)return new JSONObject(found.getJSONObject(0).getString("body"));
        JSONArray windows=FocusPeriods.windows(s,System.currentTimeMillis()-181L*86400000,System.currentTimeMillis());
        for(int i=0;i<windows.length();i++){JSONObject w=windows.getJSONObject(i);if(!key.equals(w.getString("key")))continue;
            if(!w.getBoolean("complete"))throw new IllegalArgumentException("아직 진행 중인 집중시간입니다.");
            JSONObject result=range(c,w.getLong("start"),w.getLong("end")).put("timeZone",w.getString("zone")).put("key",key).put("kind","focus").put("complete",true).put("days",1)
                .put("date",Instant.ofEpochMilli(w.getLong("end")-1).atZone(ZoneId.of(w.getString("zone"))).toLocalDate().toString()).put("finalizedAt",System.currentTimeMillis());
            // First completed snapshot wins, including when two receivers race.
            ContentValues v=new ContentValues();v.put("key",key);v.put("start",w.getLong("start"));v.put("end",w.getLong("end"));v.put("body",result.toString());
            s.getWritableDatabase().insertWithOnConflict("briefing_results",null,v,android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE);
            return new JSONObject(s.rows("SELECT body FROM briefing_results WHERE key=?",key).getJSONObject(0).getString("body"));
        }throw new IllegalArgumentException("집중시간 결과를 찾을 수 없습니다.");
    }
    static JSONObject rawDay(Context c,int dayOffset) throws Exception {
        Store s=Store.get(c);ZoneId zone=ZoneId.systemDefault();LocalDate date=LocalDate.now(zone).minusDays(Math.max(0,Math.min(179,dayOffset)));
        long start=date.atStartOfDay(zone).toInstant().toEpochMilli(),end=dayOffset==0?System.currentTimeMillis():date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        JSONObject analysis=range(c,start,end);
        return new JSONObject().put("schemaVersion",1).put("source","Locky 로컬 기록").put("date",date.toString()).put("timeZone",zone.toString()).put("periodStart",start).put("periodEnd",end).put("exportedAt",System.currentTimeMillis())
            .put("events",s.rows("SELECT * FROM events WHERE time>=? AND time<? ORDER BY time,id",""+start,""+end))
            .put("usage",s.screenUsage(start,end))
            .put("usageOriginals",s.rows("SELECT * FROM usage WHERE end>? AND start<? ORDER BY start,id",""+start,""+end))
            .put("usageEvidence",s.rows("SELECT id,source,rule_mode,surface FROM usage WHERE end>? AND start<? ORDER BY start,id",""+start,""+end))
            .put("focus",analysis.getJSONObject("focus")).put("analysis",new JSONObject().put("version",2).put("timeline",analysis.getJSONArray("timeline")).put("quality",analysis.getJSONObject("quality")));
    }
    static long minutes(long ms){return Math.round(ms/60000.0);}
}
