package com.locky.app;

import android.content.*;
import org.json.*;
import java.time.*;
import java.util.*;

final class Briefing {
    interface RangeReader {JSONObject read(long start,long end) throws Exception;}
    /** One build only: late recovery is visible on the next request, even for completed dates. */
    static final class Request {
        private record Range(long start,long end,boolean complete){}
        private final Map<Range,JSONObject> ranges=new HashMap<>();
        private final RangeReader reader,summaryReader;private final long today;
        private final Map<Range,JSONObject> summaries=new HashMap<>();
        Request(Context c,long now,ZoneId zone){this((start,end)->Briefing.range(c,start,end),Rules.dayStart(now,zone));}
        Request(RangeReader reader,long today){this(reader,reader,today);}
        private Request(RangeReader reader,RangeReader summaryReader,long today){this.reader=reader;this.summaryReader=summaryReader;this.today=today;}
        Request(Context c,long now,ZoneId zone,long from,long to) throws Exception {this(new Source(c,from,to),Rules.dayStart(now,zone));}
        private Request(Source source,long today){this((a,b)->source.read(a,b,true),(a,b)->source.read(a,b,false),today);}
        JSONObject comparison(long start,long end) throws Exception {
            Range key=new Range(start,end,end<=today);JSONObject result=ranges.get(key);if(result!=null)return result;
            result=summaries.get(key);if(result==null){result=summaryReader.read(start,end);summaries.put(key,result);}return result;
        }
        JSONObject range(long start,long end) throws Exception {
            Range key=new Range(start,end,end<=today);JSONObject result=ranges.get(key);
            if(result==null){result=reader.read(start,end);ranges.put(key,result);}return result;
        }
    }
    static JSONObject build(Context c,int days,long dayOffset) throws Exception {
        ZoneId z=ZoneId.systemDefault();LocalDate date=LocalDate.now(z).minusDays(dayOffset);
        long start=date.minusDays(days-1).atStartOfDay(z).toInstant().toEpochMilli();
        long end=dayOffset==0?System.currentTimeMillis():date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli();
        long historyStart=dayOffset==0?start:date.minusDays(days==1?7:2L*days-1).atStartOfDay(z).toInstant().toEpochMilli();
        Request request=new Request(c,System.currentTimeMillis(),z,historyStart,end);
        JSONObject result=request.range(start,end).put("days",days).put("date",date.toString()).put("complete",dayOffset>0).put("kind",days==7?"week":"day");
        if(days==1){JSONArray windows=FocusPeriods.windows(Store.get(c),start,end),results=new JSONArray();for(int i=0;i<windows.length();i++){
            JSONObject w=windows.getJSONObject(i);if(w.getLong("start")<end&&w.getLong("end")>start)results.put(w);
        }result.put("focusResults",results);}
        BriefingAnalysis.addComparison(c,result,days,request);return result;
    }
    static JSONObject range(Context c,long start,long end) throws Exception {return new Source(c,start,end).read(start,end,true);}
    /** One immutable input snapshot per report: no repeated OS scans for comparison days. */
    static final class Source {
        final Context c;final Store s;final JSONArray usage,events;final List<JSONObject> cleaned=new ArrayList<>();
        final Map<String,Boolean> recordable=new HashMap<>();
        Source(Context c,long start,long end) throws Exception {
            this.c=c;s=Store.get(c);s.syncFocusZone();
            GuardService guard=GuardService.live;if(guard!=null&&end>Rules.dayStart(System.currentTimeMillis(),ZoneId.systemDefault()))guard.flushOnMain();
            ScreenHistory.Evidence evidence=ScreenHistory.read(c,start,end,ScreenHistory.UNKNOWN,true);
            usage=ScreenHistory.filter(c,s.rows("SELECT * FROM usage WHERE end>? AND start<? ORDER BY start,id",""+start,""+end),evidence);
            events=s.rows("SELECT * FROM events WHERE time>=? AND time<? ORDER BY time,id",""+start,""+end);
            List<long[]> reserved=new ArrayList<>();for(int i=0;i<usage.length();i++){JSONObject u=usage.getJSONObject(i);reserved.add(new long[]{u.getLong("start"),u.getLong("end")});}
            long claimed=start,previousEnd=-1;String previousPkg="";long previousParent=0;
            for(int i=0;i<usage.length();i++){
                JSONObject raw=usage.getJSONObject(i);long a=Math.max(claimed,Math.max(start,raw.getLong("start"))),b=Math.min(end,raw.getLong("end"));if(b<=a)continue;claimed=b;
                String pkg=raw.getString("pkg");if(pkg.equals(c.getPackageName())||raw.optString("surface").equals("dictionary-tool")||!recordable(pkg))continue;
                boolean technicalResume=false;
                if(pkg.equals(previousPkg)&&raw.optLong("parent")==previousParent&&a>previousEnd&&a-previousEnd<=1500){long covered=previousEnd;
                    // Only rows in this tiny intervening gap can bridge a technical transition.
                    int j=i-1;while(j>=0&&usage.getJSONObject(j).getLong("end")>previousEnd)j--;
                    for(j=Math.max(0,j);j<i;j++){JSONObject bridge=usage.getJSONObject(j);if(bridge.getLong("end")<=covered)continue;if(bridge.getLong("start")>covered)break;if(bridge.getString("pkg").equals(c.getPackageName())||bridge.optString("surface").equals("dictionary-tool")||!recordable(bridge.getString("pkg")))covered=Math.max(covered,bridge.getLong("end"));if(covered>=a){technicalResume=true;break;}}
                }
                cleaned.add(ScreenHistory.copy(raw).put("start",a).put("end",b).put("technicalResume",technicalResume));previousEnd=b;previousPkg=pkg;previousParent=raw.optLong("parent");
            }
            JSONArray os=UsageReader.intervals(c,Math.max(start,s.settings.getLong("installed",end)),end,"",evidence);long osClaimed=start;int firstReserved=0;
            for(int i=0;i<os.length();i++){
                JSONObject u=os.getJSONObject(i);String pkg=u.getString("pkg");long a=Math.max(osClaimed,Math.max(start,u.getLong("start"))),b=Math.min(end,u.getLong("end"));osClaimed=Math.max(osClaimed,b);
                if(b<=a||pkg.equals(c.getPackageName())||!recordable(pkg))continue;
                while(firstReserved<reserved.size()&&reserved.get(firstReserved)[1]<=a)firstReserved++;
                long cursor=a;for(int j=firstReserved;j<reserved.size();j++){long[] span=reserved.get(j);if(span[1]<=cursor)continue;if(span[0]>=b)break;if(span[0]>cursor)cleaned.add(recovered(c,pkg,cursor,Math.min(b,span[0])));cursor=Math.max(cursor,span[1]);if(cursor>=b)break;}
                if(cursor<b)cleaned.add(recovered(c,pkg,cursor,b));
            }
            cleaned.sort(Comparator.comparingLong(t->t.optLong("start")));
        }
        boolean recordable(String pkg){return recordable.computeIfAbsent(pkg,key->UsageReader.recordable(c,key));}
        JSONObject read(long start,long end,boolean detailed) throws Exception {
            JSONArray spans=new JSONArray(),choices=new JSONArray();long internal=0,tools=0,recovered=0,claimed=start;
            for(int i=0;i<usage.length();i++){JSONObject u=usage.getJSONObject(i);long a=Math.max(claimed,Math.max(start,u.getLong("start"))),b=Math.min(end,u.getLong("end"));if(b<=a)continue;claimed=b;if(u.getString("pkg").equals(c.getPackageName()))internal+=b-a;else if(u.optString("surface").equals("dictionary-tool"))tools+=b-a;}
            for(JSONObject t:cleaned){if(t.getLong("start")>=end)break;long a=Math.max(start,t.getLong("start")),b=Math.min(end,t.getLong("end"));if(b<=a)continue;spans.put(ScreenHistory.copy(t).put("start",a).put("end",b));if(t.optString("source").equals("usage-access")&&!t.has("id"))recovered+=b-a;}
            JSONObject focus=FocusPeriods.data(s,start,end);
            if(!detailed)return BriefingAnalysis.comparisonSummary(start,end,spans,focus);
            for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);if(e.getLong("time")>=end)break;if(e.getLong("time")>=start)choices.put(e);}
            JSONObject result=BriefingAnalysis.analyze(s,start,end,spans,choices,focus);
            return result.put("timeZone",ZoneId.systemDefault().getId()).put("toolMillis",tools).put("internalMillis",internal).put("recovered",recovered).put("usageAccess",UsageReader.allowed(c));
        }
    }
    private static JSONObject recovered(Context c,String pkg,long start,long end) throws JSONException {return new JSONObject().put("start",start).put("end",end).put("pkg",pkg).put("label",UsageReader.label(c,pkg)).put("parent",0).put("source","usage-access").put("rule_mode","unknown");}
    /** All original observations remain in timeline; avoid sending them three more times to WebView. */
    static JSONObject forDisplay(JSONObject result) throws JSONException {
        if(!result.has("timeline")||!result.has("purposeContexts"))return result;
        JSONObject out=ScreenHistory.copy(result);out.remove("intervals");JSONArray flows=new JSONArray(),original=result.optJSONArray("flows");
        if(original!=null)for(int i=0;i<original.length();i++){JSONObject flow=ScreenHistory.copy(original.getJSONObject(i));for(String key:new String[]{"steps","blocks","gaps","newChoices"})flow.remove(key);flows.put(flow);}
        return out.put("flows",flows);
    }
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
